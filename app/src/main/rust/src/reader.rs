//! Reading archives.
//!
//! Entries are handed to the app one at a time, and the bytes of the entry that is currently
//! selected are buffered so that they can be served in chunks.
//!
//! The buffering is per entry, not per archive: `unarc-rs` and `sevenz-rust2` both hand out a
//! reader that they own, so an entry has to be materialized before any of it can be served. That
//! costs the size of the largest single entry, which for a file manager is one file, rather than
//! the size of the archive. XZ is a single stream rather than a list of entries, and streams
//! properly.

use std::io::{self, BufReader, Cursor, Read, Seek, SeekFrom};

use crate::format::Format;

/// What the app is told about one entry.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub size: u64,
    /// Milliseconds since the epoch, or `None` when the archive does not say.
    pub last_modified_millis: Option<i64>,
    pub symlink_target: Option<String>,
    pub is_encrypted: bool,
    pub is_directory: bool,
    pub is_symlink: bool,
}

/// Bytes of the entry that is currently selected, and how far through them the app has read.
#[derive(Default)]
pub struct Pending {
    data: Vec<u8>,
    offset: usize,
    /// True once the whole entry has been read out of the archive.
    filled: bool,
}

impl Pending {
    fn reset(&mut self) {
        self.data.clear();
        self.offset = 0;
        self.filled = false;
    }

    fn drain_into(&mut self, output: &mut [u8]) -> usize {
        let available = (self.data.len() - self.offset).min(output.len());
        output[..available].copy_from_slice(&self.data[self.offset..self.offset + available]);
        self.offset += available;
        if self.offset == self.data.len() && self.filled {
            self.data = Vec::new();
            self.offset = 0;
        }
        available
    }
}

/// A readable archive, whichever crate is behind it.
///
/// Generic over the source so that the host tests can drive it from an in-memory cursor while the
/// app drives it from a channel.
pub enum ArchiveReader<R: Read + Seek> {
    SevenZ(SevenZReader<R>),
    Unarc(UnarcReader<R>),
    /// Boxed because oxiarc's decoder is several kilobytes on its own, and every open archive would
    /// otherwise pay for it whether it is an XZ stream or not.
    Xz(Box<XzReader>),
}

/// The 7Z reader, which owns its own entry iterator.
pub struct SevenZReader<R: Read + Seek> {
    archive: sevenz_rust2::ArchiveReader<R>,
    entries: Vec<sevenz_rust2::ArchiveEntry>,
    index: usize,
    pending: Pending,
}

/// Everything `unarc-rs` reads that is neither 7Z nor XZ.
pub struct UnarcReader<R: Read + Seek> {
    archive: unarc_rs::unified::UnifiedArchive<BufReader<R>>,
    entries: Vec<unarc_rs::unified::ArchiveEntry>,
    index: usize,
    pending: Pending,
}

/// An XZ stream, which holds one file. It is decoded up front, because the size of a stream is only
/// knowable by decoding it, and a file manager has to show a size for every entry it lists.
pub enum XzReader {
    /// The stream holds a single file, which is all a raw `.xz` ever is.
    File(Pending),
    /// The stream holds a tar, which is what a `.tar.xz` is.
    Tar(Box<UnarcReader<Cursor<Vec<u8>>>>),
}

impl<R: Read + Seek> ArchiveReader<R> {
    /// Opens [reader] as whatever it turns out to be.
    pub fn open(mut reader: R, passwords: &[String]) -> io::Result<ArchiveReader<R>> {
        let format = detect(&mut reader)?;
        match format {
            Format::SevenZ => Ok(ArchiveReader::SevenZ(SevenZReader::open(
                reader, passwords,
            )?)),
            Format::Xz => Ok(ArchiveReader::Xz(Box::new(XzReader::open(
                reader, passwords,
            )?))),
            _ => Ok(ArchiveReader::Unarc(UnarcReader::open(reader, passwords)?)),
        }
    }

    /// Moves to the next entry and returns it, or `None` at the end of the archive.
    pub fn next_entry(&mut self) -> io::Result<Option<Entry>> {
        match self {
            ArchiveReader::SevenZ(reader) => reader.next_entry(),
            ArchiveReader::Unarc(reader) => reader.next_entry(),
            ArchiveReader::Xz(reader) => reader.next_entry(),
        }
    }

    /// Fills [output] from the entry that is currently selected.
    pub fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        match self {
            ArchiveReader::SevenZ(reader) => reader.read(output),
            ArchiveReader::Unarc(reader) => reader.read(output),
            ArchiveReader::Xz(reader) => reader.read(output),
        }
    }

    /// Skips over the bytes of the entry that is currently selected.
    pub fn skip(&mut self, request: i64) -> io::Result<i64> {
        if request < 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "cannot skip backwards",
            ));
        }
        let mut remaining = request as u64;
        let mut buffer = [0u8; crate::io::CHUNK_SIZE];
        while remaining > 0 {
            let wanted = remaining.min(buffer.len() as u64) as usize;
            let read = self.read(&mut buffer[..wanted])?;
            if read == 0 {
                break;
            }
            remaining -= read as u64;
        }
        Ok(request - remaining as i64)
    }
}

impl<R: Read + Seek> SevenZReader<R> {
    fn open(mut source: R, passwords: &[String]) -> io::Result<SevenZReader<R>> {
        use sevenz_rust2::Password;

        let password = match passwords.first() {
            Some(password) => Password::from(password.as_str()),
            None => Password::empty(),
        };
        let archive = sevenz_rust2::Archive::read(&mut source, &password)
            .map_err(|error| io::Error::other(format!("{error:?}")))?;
        let entries = archive.files.clone();
        Ok(SevenZReader {
            archive: sevenz_rust2::ArchiveReader::from_archive(archive, source, password),
            entries,
            index: 0,
            pending: Pending::default(),
        })
    }

    fn next_entry(&mut self) -> io::Result<Option<Entry>> {
        if self.index >= self.entries.len() {
            return Ok(None);
        }
        self.pending.reset();
        let entry = to_seven_z_entry(&self.entries[self.index]);
        self.index += 1;
        Ok(Some(entry))
    }

    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if self.pending.filled {
            return Ok(self.pending.drain_into(output));
        }
        let wanted = self.entries[self.index - 1].name().to_owned();
        self.pending.filled = true;
        // `read_file` rather than `for_each_entries`, because the latter is a single pass over a
        // solid archive: calling it once per entry would resume from wherever the previous call left
        // the source and fail the block's checksum. `read_file` seeks to the entry itself, at the
        // cost of decoding the block it lives in from the start.
        self.pending.data = self
            .archive
            .read_file(&wanted)
            .map_err(|error| io::Error::other(format!("{error:?}")))?;
        Ok(self.pending.drain_into(output))
    }
}

/// The formats to try when nothing in the file identifies it.
///
/// An empty tar is 1024 zero bytes, which is the same as a zeroed file of any other length, so
/// there is no magic to go on. Trying them in turn is what `libarchive` did, and TAR first because
/// all zeros is a valid empty tar, which ZIP would then accept too and report as an empty archive
/// with the wrong name.
const UNIDENTIFIABLE_FORMATS: [unarc_rs::unified::ArchiveFormat; 2] = [
    unarc_rs::unified::ArchiveFormat::Tar,
    unarc_rs::unified::ArchiveFormat::Zip,
];

/// Opens one format and reads out its entry list, or reports why it could not.
///
/// Returned as a pair rather than as a reader so that the caller can decide what to do with a source
/// it may still want to hand to something else, which is what the fallback in `open` needs.
fn open_unarc<T: Read + Seek>(
    format: unarc_rs::unified::ArchiveFormat,
    source: T,
    options: &unarc_rs::unified::ArchiveOptions,
) -> io::Result<(
    unarc_rs::unified::UnifiedArchive<BufReader<T>>,
    Vec<unarc_rs::unified::ArchiveEntry>,
)> {
    let mut archive = format
        .open_with_options(BufReader::new(source), options.clone())
        .map_err(|error| io::Error::other(format!("{error:?}")))?;
    let entries = archive
        .entries()
        .map_err(|error| io::Error::other(format!("{error:?}")))?;
    Ok((archive, entries))
}

impl<R: Read + Seek> UnarcReader<R> {
    fn open(mut source: R, passwords: &[String]) -> io::Result<UnarcReader<R>> {
        let format = unarc_rs::unified::ArchiveFormat::detect(&mut source, None)
            .map_err(io::Error::other)?;
        let mut options = unarc_rs::unified::ArchiveOptions::new();
        if let Some(password) = passwords.first() {
            options = options.with_password(password.as_str());
        }
        match format {
            Some(format) => {
                let (archive, entries) = open_unarc(format, source, &options)?;
                Ok(UnarcReader {
                    archive,
                    entries,
                    index: 0,
                    pending: Pending::default(),
                })
            }
            None => {
                // The file has nothing in it that says what it is, so each candidate gets a turn and
                // the first that opens is the answer. Probing borrows the source, so the winner is
                // then opened for real once the probe has been dropped and the source rewound.
                let start = source.stream_position()?;
                let mut failure = None;
                for format in UNIDENTIFIABLE_FORMATS {
                    source.seek(SeekFrom::Start(start))?;
                    match open_unarc(format, &mut source, &options) {
                        Ok(_) => {
                            source.seek(SeekFrom::Start(start))?;
                            let (archive, entries) = open_unarc(format, source, &options)?;
                            return Ok(UnarcReader {
                                archive,
                                entries,
                                index: 0,
                                pending: Pending::default(),
                            });
                        }
                        Err(error) => failure = Some(error),
                    }
                }
                Err(failure.unwrap_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::InvalidData,
                        "The file is not an archive of any supported format",
                    )
                }))
            }
        }
    }

    fn next_entry(&mut self) -> io::Result<Option<Entry>> {
        if self.index >= self.entries.len() {
            return Ok(None);
        }
        self.pending.reset();
        let entry = to_unarc_entry(&self.entries[self.index]);
        self.index += 1;
        Ok(Some(entry))
    }

    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if self.pending.filled {
            return Ok(self.pending.drain_into(output));
        }
        let index = self.index - 1;
        self.pending.filled = true;
        let mut sink = Vec::new();
        self.archive
            .read_to(&self.entries[index], &mut sink)
            .map_err(|error| io::Error::other(format!("{error:?}")))?;
        self.pending.data = sink;
        Ok(self.pending.drain_into(output))
    }
}

impl XzReader {
    fn open<R: Read + Seek>(mut source: R, passwords: &[String]) -> io::Result<XzReader> {
        let mut reader = oxiarc_archive::xz::XzReader::new(BufReader::new(&mut source))
            .map_err(|error| io::Error::other(format!("{error:?}")))?;
        let data = reader
            .decompress()
            .map_err(|error| io::Error::other(format!("{error:?}")))?;
        // A `.tar.xz` is a tar inside an XZ stream, so the tar is what the app should be shown. The
        // stream is decompressed in full either way, since only decompressing reveals the size.
        if Format::detect(&data) == Some(Format::Tar) {
            return Ok(XzReader::Tar(Box::new(UnarcReader::open(
                Cursor::new(data),
                passwords,
            )?)));
        }
        // An empty tar is 1024 zero bytes, so the decompressed data of an empty `.tar.xz` says
        // nothing about what it holds. It gets the same treatment an unidentifiable tar file does:
        // if a tar reader accepts it, it was a tar.
        if data.len() >= 512 && data.iter().all(|byte| *byte == 0) {
            let options = unarc_rs::unified::ArchiveOptions::new();
            // Probing borrows the data, since a reader takes ownership of whatever it is given.
            let is_tar = open_unarc(
                unarc_rs::unified::ArchiveFormat::Tar,
                Cursor::new(&data),
                &options,
            )
            .is_ok();
            if is_tar {
                return Ok(XzReader::Tar(Box::new(UnarcReader::open(
                    Cursor::new(data),
                    passwords,
                )?)));
            }
        }
        Ok(XzReader::File(Pending {
            data,
            offset: 0,
            filled: true,
        }))
    }

    fn next_entry(&mut self) -> io::Result<Option<Entry>> {
        match self {
            XzReader::Tar(reader) => reader.next_entry(),
            XzReader::File(pending) => {
                // There is only ever one entry, so it is handed out once. Leaving it available
                // would make a caller that loops until `next_entry` returns null loop forever.
                if !pending.filled {
                    return Ok(None);
                }
                let size = pending.data.len() as u64;
                pending.filled = false;
                Ok(Some(Entry {
                    name: crate::XZ_ENTRY_NAME.to_owned(),
                    size,
                    is_encrypted: false,
                    ..Entry::default()
                }))
            }
        }
    }

    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        match self {
            XzReader::Tar(reader) => reader.read(output),
            XzReader::File(pending) => {
                let read = pending.drain_into(output);
                // Nothing can follow the end of a stream that is a single file.
                if pending.offset == pending.data.len() {
                    pending.filled = false;
                }
                Ok(read)
            }
        }
    }
}

/// Reads enough of the start of the file to recognise the format, and rewinds.
fn detect<R: Read + Seek>(reader: &mut R) -> io::Result<Format> {
    let start = reader.stream_position()?;
    let mut header = [0u8; 512];
    let mut read = 0;
    while read < header.len() {
        match reader.read(&mut header[read..])? {
            0 => break,
            n => read += n,
        }
    }
    reader.seek(std::io::SeekFrom::Start(start))?;
    // ZIP is the fallback because it is the only format here whose central directory is found by
    // scanning back from the end of the file, so it is the one that can report a usable error for a
    // file that is not an archive at all. An archive too short to have any recognisable bytes, such
    // as an empty tar, is 1024 zero bytes and is genuinely indistinguishable, which is why an empty
    // tar round trip is asserted for the formats that carry their own signature.
    Ok(Format::detect(&header[..read]).unwrap_or(Format::Zip))
}

fn to_seven_z_entry(entry: &sevenz_rust2::ArchiveEntry) -> Entry {
    let is_directory = entry.is_directory();
    Entry {
        name: name(entry.name(), is_directory),
        size: entry.size(),
        last_modified_millis: Some(system_time_millis(entry.last_modified_date().into())),
        symlink_target: None,
        is_encrypted: false,
        is_directory,
        is_symlink: false,
    }
}

/// Strips the trailing slash from a directory's name.
///
/// Archive formats disagree about whether a directory's name ends in a slash, and the app resolves
/// entry names as path components, where the slash would be an empty one. Normalising here means
/// the formats agree and the app does not have to care.
fn name(name: &str, is_directory: bool) -> String {
    if is_directory {
        name.trim_end_matches('/').to_owned()
    } else {
        name.to_owned()
    }
}

fn to_unarc_entry(entry: &unarc_rs::unified::ArchiveEntry) -> Entry {
    use unarc_rs::unified::ArchiveEntryKind;

    let is_directory = entry.is_directory();
    let is_symlink = matches!(
        entry.kind(),
        ArchiveEntryKind::SymbolicLink | ArchiveEntryKind::HardLink
    );
    Entry {
        name: name(entry.name(), is_directory),
        size: entry.original_size(),
        last_modified_millis: entry.modified_time().and_then(dos_millis),
        symlink_target: entry.link_target().map(str::to_owned),
        is_encrypted: entry.is_encrypted(),
        is_directory,
        is_symlink,
    }
}

/// Milliseconds since the epoch, negative for the times before it that archives can record.
fn system_time_millis(time: std::time::SystemTime) -> i64 {
    match time.duration_since(std::time::UNIX_EPOCH) {
        Ok(duration) => duration.as_millis() as i64,
        Err(error) => -(error.duration().as_millis() as i64),
    }
}

/// A MS-DOS date and time, which has no sub-second part, no timezone, and starts at 1980.
/// An MS-DOS timestamp only has two-second resolution, and every field is zero for a timestamp
/// that was never set, which real archives do contain. That combination is not a date, so it comes
/// back as absent rather than as 1979-12-31.
fn dos_millis(time: unarc_rs::date_time::DosDateTime) -> Option<i64> {
    let month = time.month();
    let day = time.day();
    if month == 0 || day == 0 {
        return None;
    }
    let days = days_from_civil(time.year() as i64, month as u32, day as u32);
    Some(
        (days * 86_400
            + time.hour() as i64 * 3_600
            + time.minute() as i64 * 60
            + time.second() as i64)
            * 1000,
    )
}

/// Days since 1970-01-01 for a proleptic Gregorian date, after Howard Hinnant's algorithm.
fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    let year = year - i64::from(month <= 2);
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let month = i64::from(month);
    let day = i64::from(day);
    let day_of_year = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_civil_dates_to_epoch_days() {
        assert_eq!(days_from_civil(1970, 1, 1), 0);
        assert_eq!(days_from_civil(2000, 3, 1), 11_017);
        assert_eq!(days_from_civil(1980, 1, 1), 3_652);
    }

    #[test]
    fn dos_timestamps_have_no_sub_second_part() {
        // 1980-01-01T00:00:00Z, the earliest a MS-DOS timestamp can express.
        let epoch = (1 << 21) | (1 << 16);
        assert_eq!(
            dos_millis(unarc_rs::date_time::DosDateTime::new(epoch)),
            Some(315_532_800_000)
        );
    }

    #[test]
    fn unset_dos_timestamps_are_absent_rather_than_a_date() {
        assert_eq!(dos_millis(unarc_rs::date_time::DosDateTime::new(0)), None);
    }

    #[test]
    fn times_before_the_epoch_are_negative() {
        let before = std::time::UNIX_EPOCH - std::time::Duration::from_secs(1);
        assert_eq!(system_time_millis(before), -1000);
    }
}
