//! Writing archives.
//!
//! The ZIP and TAR writers stream: the fork's `add_stream` hands back a `Write`, so an entry is
//! compressed and pushed to the output as the app produces it. Only the 7Z writer materialises an
//! entry at a time, because `sevenz-rust2` owns its own compressor and is handed a `Read` rather
//! than a `Write`.

use std::io::{self, Read, Seek, Write};

use crate::format::Format;

/// What the app says about the entry it is about to write.
#[derive(Clone, Debug, Default)]
pub struct NewEntry {
    pub name: String,
    pub size: u64,
    pub is_directory: bool,
    pub last_modified_millis: Option<i64>,
}

pub struct SevenZ<W: Write + Seek> {
    writer: sevenz_rust2::ArchiveWriter<W>,
}

pub struct Zip<W: Write> {
    writer: oxiarc_archive::zip::ZipWriter<W>,
}

pub struct Tar<W: Write> {
    writer: oxiarc_archive::tar::TarWriter<W>,
}

pub struct TarXz<W: Write> {
    writer: oxiarc_archive::tar::TarWriter<oxiarc_archive::xz::XzStreamWriter<W>>,
}

pub struct Xz<W: Write> {
    writer: oxiarc_archive::xz::XzStreamWriter<W>,
}

/// A writable archive, whichever crate is behind it.
///
/// Generic over the sink so that the host tests can drive it into a `Vec<u8>` while the app drives
/// it into a channel.
pub enum ArchiveWriter<W: Write + Seek> {
    SevenZ(SevenZ<W>),
    Zip(Zip<W>),
    Tar(Tar<W>),
    TarXz(TarXz<W>),
    Xz(Xz<W>),
}

impl<W: Write + Seek> ArchiveWriter<W> {
    pub fn create(channel: W, format: Format) -> io::Result<ArchiveWriter<W>> {
        Ok(match format {
            // The 7Z writer seeks back over the signature header it leaves a placeholder for, so
            // every sink has to be seekable whether or not the format being written needs it.
            Format::SevenZ => ArchiveWriter::SevenZ(SevenZ {
                writer: sevenz_rust2::ArchiveWriter::new(channel)
                    .map_err(|error| io::Error::other(format!("{error:?}")))?,
            }),
            Format::Zip => ArchiveWriter::Zip(Zip {
                writer: oxiarc_archive::zip::ZipWriter::new(channel),
            }),
            Format::Tar => ArchiveWriter::Tar(Tar {
                writer: oxiarc_archive::tar::TarWriter::new(channel),
            }),
            Format::TarXz => ArchiveWriter::TarXz(TarXz {
                writer: oxiarc_archive::tar::TarWriter::new(
                    oxiarc_archive::xz::XzStreamWriter::new(channel, Default::default())
                        .map_err(|error| io::Error::other(format!("{error:?}")))?,
                ),
            }),
            Format::Xz => ArchiveWriter::Xz(Xz {
                writer: oxiarc_archive::xz::XzStreamWriter::new(channel, Default::default())
                    .map_err(|error| io::Error::other(format!("{error:?}")))?,
            }),
        })
    }

    /// Writes one entry, pulling its bytes out of [source] until it is exhausted.
    pub fn write_entry(&mut self, entry: &NewEntry, source: &mut dyn Read) -> io::Result<()> {
        match self {
            ArchiveWriter::SevenZ(writer) => write_seven_z(&mut writer.writer, entry, source),
            ArchiveWriter::Zip(writer) => write_zip(&mut writer.writer, entry, source),
            ArchiveWriter::Tar(writer) => write_tar(&mut writer.writer, entry, source),
            ArchiveWriter::TarXz(writer) => write_tar(&mut writer.writer, entry, source),
            ArchiveWriter::Xz(writer) => {
                io::copy(source, &mut writer.writer).map_err(map_error)?;
                Ok(())
            }
        }
    }

    /// Writes the central directory or stream footer. Must be called before the output is used.
    pub fn finish(self) -> io::Result<()> {
        match self {
            ArchiveWriter::SevenZ(writer) => {
                writer.writer.finish().map_err(io::Error::other)?;
                Ok(())
            }
            ArchiveWriter::Zip(mut writer) => writer.writer.finish().map_err(map_error),
            ArchiveWriter::Tar(mut writer) => writer.writer.finish().map_err(map_error),
            ArchiveWriter::TarXz(writer) => {
                // `into_inner` finishes the tar first and hands back the XZ stream, which then
                // needs its own footer before the file is complete.
                let mut xz = writer.writer.into_inner().map_err(map_error)?;
                xz.finish().map_err(map_error)
            }
            ArchiveWriter::Xz(mut writer) => writer.writer.finish().map_err(map_error),
        }
    }
}

/// The 7Z writer reads each entry through a `Read` it is given and finishes compressing inside that
/// call, so one entry's bytes are held at a time. The ZIP and TAR writers do not have to: their
/// stream writers push to the output as the bytes arrive.
fn write_seven_z<W: Write + Seek>(
    writer: &mut sevenz_rust2::ArchiveWriter<W>,
    entry: &NewEntry,
    source: &mut dyn Read,
) -> io::Result<()> {
    let mut archive_entry = if entry.is_directory {
        sevenz_rust2::ArchiveEntry::new_directory(&entry.name)
    } else {
        sevenz_rust2::ArchiveEntry::new_file(&entry.name)
    };
    if let Some(nt_time) = entry.last_modified_millis.and_then(to_nt_time) {
        archive_entry.last_modified_date = nt_time;
    }
    let mut reader = BorrowedReader(source);
    writer
        .push_archive_entry(archive_entry, (!entry.is_directory).then_some(&mut reader))
        .map_err(|error| io::Error::other(format!("{error:?}")))?;
    Ok(())
}

fn write_zip<W: Write>(
    writer: &mut oxiarc_archive::zip::ZipWriter<W>,
    entry: &NewEntry,
    source: &mut dyn Read,
) -> io::Result<()> {
    if entry.is_directory {
        return writer
            .add_directory(&with_trailing_slash(&entry.name))
            .map_err(map_error);
    }
    // The stream writer borrows the parent, so it has to be finished before anything else is
    // added to the archive.
    let mut stream = writer.add_stream(&entry.name).map_err(map_error)?;
    io::copy(source, &mut stream).map_err(map_error)?;
    stream.finish().map_err(map_error)
}

/// A TAR header records the entry size before the data, so unlike ZIP the size has to be known
/// first. The app knows it, because it is what it read off the file being archived.
fn write_tar<W: Write>(
    writer: &mut oxiarc_archive::tar::TarWriter<W>,
    entry: &NewEntry,
    source: &mut dyn Read,
) -> io::Result<()> {
    if entry.is_directory {
        return writer
            .add_directory(&with_trailing_slash(&entry.name))
            .map_err(map_error);
    }
    let mut stream = writer
        .add_stream(&entry.name, entry.size)
        .map_err(map_error)?;
    let written = io::copy(&mut source.take(entry.size), &mut stream).map_err(map_error)?;
    if written != entry.size {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            format!(
                "{} produced {written} bytes but its size is {}",
                entry.name, entry.size
            ),
        ));
    }
    stream.finish().map_err(map_error)
}

/// Lets a `&mut dyn Read` be handed to an API that wants a sized `Read`.
struct BorrowedReader<'a>(&'a mut dyn Read);

impl Read for BorrowedReader<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        self.0.read(buffer)
    }
}

fn to_nt_time(millis: i64) -> Option<sevenz_rust2::NtTime> {
    let duration = std::time::Duration::from_millis(millis.unsigned_abs());
    let time = if millis >= 0 {
        std::time::UNIX_EPOCH.checked_add(duration)?
    } else {
        std::time::UNIX_EPOCH.checked_sub(duration)?
    };
    sevenz_rust2::NtTime::try_from(time).ok()
}

fn with_trailing_slash(name: &str) -> String {
    if name.ends_with('/') {
        name.to_owned()
    } else {
        format!("{name}/")
    }
}

fn map_error<E: std::fmt::Debug>(error: E) -> io::Error {
    io::Error::other(format!("{error:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tar_directories_end_with_a_slash() {
        assert_eq!(with_trailing_slash("dir"), "dir/");
        assert_eq!(with_trailing_slash("dir/"), "dir/");
        assert_eq!(with_trailing_slash("a/b"), "a/b/");
    }
}
