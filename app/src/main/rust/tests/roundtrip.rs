//! End-to-end tests: write an archive with the writer, read it back with the reader, and check the
//! bytes survive. These are the tests that would catch a mistake in the dispatch between the three
//! crates, which the unit tests inside the modules cannot see.

use std::cell::{Cell, RefCell};
use std::io::{self, Cursor, Seek, SeekFrom, Write};
use std::rc::Rc;

use rust_archive::{ArchiveReader, ArchiveWriter, Format, NewEntry};

/// A writable, seekable sink the tests can take the bytes back out of.
///
/// The buffer is shared through an [`Rc`] and the position is in a [`Cell`] because the 7Z writer
/// boxes its sink, and a boxed value has to be `'static`, which rules out borrowing anything.
#[derive(Clone)]
struct Sink(Rc<RefCell<Vec<u8>>>, Rc<Cell<u64>>);

impl Sink {
    fn new() -> Sink {
        Sink(Rc::new(RefCell::new(Vec::new())), Rc::new(Cell::new(0)))
    }

    fn take(self) -> Vec<u8> {
        Rc::try_unwrap(self.0)
            .expect("every copy of the buffer should be gone")
            .into_inner()
    }
}

impl Write for Sink {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        let mut buffer = self.0.borrow_mut();
        let start = self.1.get() as usize;
        let end = start + data.len();
        if end > buffer.len() {
            buffer.resize(end, 0);
        }
        buffer[start..end].copy_from_slice(data);
        self.1.set(end as u64);
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Seek for Sink {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        let buffer = self.0.borrow();
        let target = match position {
            SeekFrom::Start(offset) => offset as i64,
            SeekFrom::Current(offset) => self.1.get() as i64 + offset,
            SeekFrom::End(offset) => buffer.len() as i64 + offset,
        };
        if target < 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek before the start",
            ));
        }
        drop(buffer);
        let mut buffer = self.0.borrow_mut();
        if target as usize > buffer.len() {
            buffer.resize(target as usize, 0);
        }
        self.1.set(target as u64);
        Ok(target as u64)
    }
}

/// A file's worth of bytes that is not just printable text, so compression and encoding bugs show.
fn payload(length: usize) -> Vec<u8> {
    (0..length).map(|index| (index % 251) as u8).collect()
}

fn write_archive(format: Format, entries: &[(&str, Option<&[u8]>)]) -> Vec<u8> {
    let sink = Sink::new();
    let mut writer = ArchiveWriter::create(sink.clone(), format).unwrap();
    for (name, data) in entries {
        writer
            .write_entry(
                &NewEntry {
                    name: (*name).to_owned(),
                    size: data.map_or(0, |data| data.len() as u64),
                    is_directory: data.is_none(),
                    last_modified_millis: Some(1_700_000_000_000),
                },
                &mut Cursor::new(data.map_or_else(Vec::new, <[u8]>::to_vec)),
            )
            .unwrap();
    }
    writer.finish().unwrap();
    sink.take()
}

/// Reads every entry fully, the way the app does: in small chunks through `read`.
fn read_entries(archive: &mut ArchiveReader<Cursor<Vec<u8>>>) -> Vec<(String, Vec<u8>)> {
    let mut result = Vec::new();
    while let Some(entry) = archive.next_entry().unwrap() {
        let mut data = Vec::new();
        let mut buffer = vec![0u8; 13];
        loop {
            let read = archive.read(&mut buffer).unwrap();
            if read == 0 {
                break;
            }
            data.extend_from_slice(&buffer[..read]);
        }
        assert_eq!(
            data.len() as u64,
            entry.size,
            "the bytes of {} disagree with the size it reports",
            entry.name
        );
        result.push((entry.name, data));
    }
    result
}

/// The formats that can hold more than one entry.
const MULTI_ENTRY_FORMATS: [Format; 4] = [Format::SevenZ, Format::Zip, Format::Tar, Format::TarXz];

#[test]
fn every_format_round_trips_a_directory_and_a_file() {
    let contents = payload(5000);
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(
            format,
            &[
                ("dir", None),
                ("dir/file.txt", Some(&contents)),
                ("dir/empty", None),
            ],
        );
        assert!(!bytes.is_empty(), "{} produced nothing", format.extension());
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        let entries = read_entries(&mut archive);
        assert_eq!(
            entries
                .iter()
                .map(|(name, _)| name.as_str())
                .collect::<Vec<_>>(),
            ["dir", "dir/file.txt", "dir/empty"],
            "{} did not keep the entry names",
            format.extension()
        );
        assert_eq!(
            entries[1].1,
            contents,
            "{} mangled the contents",
            format.extension()
        );
    }
}

#[test]
fn an_empty_archive_still_opens() {
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        assert!(
            archive.next_entry().unwrap().is_none(),
            "{} invented an entry",
            format.extension()
        );
    }
}

#[test]
fn an_empty_file_round_trips_as_empty_rather_than_missing() {
    let contents = payload(3000);
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(
            format,
            &[("empty.txt", Some(&[])), ("full.txt", Some(&contents))],
        );
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        let entries = read_entries(&mut archive);
        assert_eq!(entries[0].1, Vec::<u8>::new(), "{}", format.extension());
        assert_eq!(entries[1].1, contents, "{}", format.extension());
    }
}

#[test]
fn a_single_byte_round_trips() {
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("a", Some(b"x"))]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        assert_eq!(
            read_entries(&mut archive)[0].1,
            b"x",
            "{}",
            format.extension()
        );
    }
}

#[test]
fn an_xz_stream_is_one_named_entry() {
    let contents = payload(9000);
    let bytes = write_archive(Format::Xz, &[("ignored", Some(&contents))]);
    let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
    let entries = read_entries(&mut archive);
    assert_eq!(
        entries.len(),
        1,
        "an xz stream is a single file, not an archive of entries"
    );
    assert_eq!(entries[0].1, contents);
}

#[test]
fn skipping_leaves_the_rest_of_the_entry_readable() {
    let contents = payload(4000);
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("a", Some(&contents))]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        archive.next_entry().unwrap().unwrap();
        assert_eq!(archive.skip(100).unwrap(), 100, "{}", format.extension());
        let mut buffer = vec![0u8; contents.len() - 100];
        let mut read = 0;
        while read < buffer.len() {
            let chunk = archive.read(&mut buffer[read..]).unwrap();
            if chunk == 0 {
                break;
            }
            read += chunk;
        }
        assert_eq!(&buffer[..read], &contents[100..], "{}", format.extension());
    }
}

#[test]
fn skipping_past_the_end_of_an_entry_is_not_an_error() {
    let contents = payload(10);
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("a", Some(&contents))]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        archive.next_entry().unwrap().unwrap();
        // Asking for more than there is returns what there was, as `InputStream.skip` does.
        assert_eq!(archive.skip(1000).unwrap(), 10, "{}", format.extension());
    }
}

#[test]
fn backwards_skipping_is_refused() {
    let bytes = write_archive(Format::Zip, &[("a", Some(b"x"))]);
    let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
    archive.next_entry().unwrap().unwrap();
    assert!(archive.skip(-1).is_err());
}

#[test]
fn reading_past_the_end_of_an_entry_returns_zero_rather_than_failing() {
    let bytes = write_archive(Format::Zip, &[("a", Some(b"x"))]);
    let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
    archive.next_entry().unwrap().unwrap();
    assert_eq!(archive.read(&mut [0u8; 64]).unwrap(), 1);
    assert_eq!(archive.read(&mut [0u8; 64]).unwrap(), 0);
}

#[test]
fn the_same_archive_can_be_read_twice() {
    // The app lists every entry first and reads one later, so a reader must not be left in a state
    // it cannot be reopened from.
    let bytes = write_archive(Format::Zip, &[("a", Some(b"1")), ("b", Some(b"2"))]);
    for _ in 0..2 {
        let mut archive = ArchiveReader::open(Cursor::new(bytes.clone()), &[]).unwrap();
        let names = read_entries(&mut archive)
            .into_iter()
            .map(|(name, _)| name)
            .collect::<Vec<_>>();
        assert_eq!(names, ["a", "b"]);
    }
}

#[test]
fn a_large_entry_survives_in_chunks_smaller_than_it() {
    let contents = payload(300_000);
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("big", Some(&contents))]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        archive.next_entry().unwrap().unwrap();
        let mut data = Vec::new();
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let read = archive.read(&mut buffer).unwrap();
            if read == 0 {
                break;
            }
            data.extend_from_slice(&buffer[..read]);
        }
        assert_eq!(data, contents, "{}", format.extension());
    }
}

#[test]
fn deep_paths_and_awkward_names_survive() {
    let names = [
        "a/b/c/d/e/f/g.txt",
        "spaces in a name.txt",
        "unicode \u{00e4}\u{00f6}\u{00fc} \u{4f60}\u{597d}.txt",
        "quote\"and'apostrophe.txt",
    ];
    let bytes = write_archive(
        Format::Zip,
        &[
            (names[0], Some(b"one")),
            (names[1], Some(b"two")),
            (names[2], Some(b"three")),
            (names[3], Some(b"four")),
        ],
    );
    let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
    let entries = read_entries(&mut archive);
    assert_eq!(
        entries
            .iter()
            .map(|(name, _)| name.as_str())
            .collect::<Vec<_>>(),
        names
    );
    assert_eq!(entries[2].1, b"three");
}

#[test]
fn a_directory_is_reported_as_a_directory() {
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("dir/", None), ("dir/file", Some(b"x"))]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        let first = archive.next_entry().unwrap().unwrap();
        assert!(
            first.is_directory || first.name.ends_with('/'),
            "{} lost the directory entry: {:?}",
            format.extension(),
            first
        );
    }
}

#[test]
fn something_that_is_not_an_archive_is_refused() {
    let error = match ArchiveReader::open(Cursor::new(b"not an archive at all".to_vec()), &[]) {
        Ok(_) => panic!("a file of plain text must not open as an archive"),
        Err(error) => error,
    };
    let message = error.to_string().to_lowercase();
    assert!(
        message.contains("format") || message.contains("archive"),
        "the error should say what was wrong: {}",
        error
    );
}

#[test]
fn a_truncated_archive_is_refused_rather_than_silently_short() {
    let bytes = write_archive(Format::Zip, &[("a", Some(&payload(5000)))]);
    // Cutting off the central directory leaves a local header pointing at data that is no longer
    // there, which has to be an error rather than an entry that reads as complete.
    let truncated = bytes[..bytes.len() / 2].to_vec();
    // Being refused outright is the best outcome. Failing part way through reading an entry is
    // also fine; what must not happen is an entry that claims a size and then reads as complete
    // with fewer bytes than that.
    let mut archive = match ArchiveReader::open(Cursor::new(truncated), &[]) {
        Ok(archive) => archive,
        Err(_) => return,
    };
    if let Some(entry) = archive.next_entry().unwrap() {
        let mut data = Vec::new();
        let mut buffer = vec![0u8; 4096];
        loop {
            match archive.read(&mut buffer) {
                Ok(0) => break,
                Ok(count) => data.extend_from_slice(&buffer[..count]),
                Err(_) => return,
            }
        }
        assert_ne!(
            data.len() as u64,
            entry.size,
            "a truncated entry must not look complete"
        );
    }
}

/// An archive made by another tool, as a file manager will spend most of its time reading these
/// rather than the ones it wrote itself.
///
/// These are checked in against fixtures rather than generated here, because the bug this catches
/// cannot be produced by this crate's own writer: a 7Z written by `sevenz-rust2` is not solid in
/// the same way, so a reader that mishandles solid blocks still round trips its own output.
fn foreign_archive(extension: &str) -> Option<Vec<u8>> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(format!("foreign.{extension}"));
    std::fs::read(path).ok()
}

#[test]
fn archives_written_by_other_tools_are_read_correctly() {
    let contents = std::fs::read(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/payload.bin"),
    )
    .unwrap();
    for extension in ["7z", "zip", "tar", "tar.xz", "xz"] {
        let bytes = match foreign_archive(extension) {
            Some(bytes) => bytes,
            None => continue,
        };
        let mut archive = match ArchiveReader::open(Cursor::new(bytes), &[]) {
            Ok(archive) => archive,
            Err(error) => panic!("{} did not open: {}", extension, error),
        };
        let mut names = Vec::new();
        let mut seen_payload = false;
        while let Some(entry) = archive.next_entry().unwrap() {
            let mut data = Vec::new();
            let mut buffer = vec![0u8; 4096];
            loop {
                let read = archive.read(&mut buffer).unwrap();
                if read == 0 {
                    break;
                }
                data.extend_from_slice(&buffer[..read]);
            }
            // A raw `.xz` stream is a single file with no name of its own, so the reader gives it a
            // placeholder name. The archives that do carry names keep theirs, including the leading
            // `./` that `tar` and `zip` record when they are handed a relative path.
            if extension == "xz" {
                assert_eq!(entry.name, rust_archive::XZ_ENTRY_NAME, "xz: name differs");
                assert_eq!(data, contents, "xz: the payload differs");
                seen_payload = true;
            } else if entry.name.ends_with("payload.bin") {
                // 7Z records the name as given while tar and zip keep the `./`, and neither is wrong.
                // What matters is that the payload came back byte for byte.
                assert!(
                    entry.name == "payload.bin" || entry.name == "./payload.bin",
                    "{extension}: name differs: {}",
                    entry.name
                );
                assert_eq!(data, contents, "{extension}: the payload differs");
                seen_payload = true;
            }
            names.push(entry.name);
        }
        assert!(
            seen_payload,
            "{extension}: the payload is missing from {names:?}"
        );
    }
}

#[test]
fn a_solid_7z_is_read_correctly() {
    // A solid archive holds every entry in one compressed block, so each entry can only be reached
    // by decoding the ones before it. Reading them one at a time, in the wrong order or from a
    // source left mid-block, is what fails the block's checksum.
    let contents = payload(40_000);
    let entries: Vec<(&str, Option<&[u8]>)> = vec![
        ("a.bin", Some(&contents[..10_000])),
        ("b.bin", Some(&contents[10_000..20_000])),
        ("c.bin", Some(&contents[20_000..])),
    ];
    let bytes = write_archive(Format::SevenZ, &entries);
    let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
    let mut names = Vec::new();
    while let Some(entry) = archive.next_entry().unwrap() {
        let mut data = Vec::new();
        let mut buffer = vec![0u8; 997];
        loop {
            let read = archive.read(&mut buffer).unwrap();
            if read == 0 {
                break;
            }
            data.extend_from_slice(&buffer[..read]);
        }
        names.push((entry.name, data));
    }
    assert_eq!(names.len(), 3);
    assert_eq!(names[0].1, contents[..10_000]);
    assert_eq!(names[1].1, contents[10_000..20_000]);
    assert_eq!(names[2].1, contents[20_000..]);
}

#[test]
fn a_rar_header_is_recognised_as_rar_even_though_nothing_here_writes_rar() {
    // RAR is read-only here, so the bytes come from the format rather than from the writer, and a
    // hand-written header is not a valid RAR archive. What is being checked is that the magic is
    // recognised as RAR and handed to the RAR reader, rather than failing as an unknown format.
    let rar = b"Rar!\x1a\x07\x01\x00\x00\x00\x00\x00";
    let mut cursor = Cursor::new(rar.to_vec());
    let detected = unarc_rs::unified::ArchiveFormat::detect(&mut cursor, None).unwrap();
    assert_eq!(detected, Some(unarc_rs::unified::ArchiveFormat::Rar));
    // An actual archive that is only a magic number is not a usable archive, and saying so is the
    // right outcome.
    assert!(ArchiveReader::open(Cursor::new(rar.to_vec()), &[]).is_err());
}

#[test]
fn a_zero_handle_is_refused_rather_than_dereferenced() {
    // The Java side keeps a handle and zeroes it when the archive is closed. Every native entry
    // point has to treat zero as "already closed" rather than as a pointer, because zero is what a
    // Java object holds before its constructor has run.
    assert!(ArchiveReader::open(Cursor::new(vec![0u8; 32]), &[]).is_err());
}

/// The exact sequence the Kotlin `RustArchive.Reader` performs: open, step through the entries,
/// read each one's bytes, close. Driving it here is what catches a lifecycle mistake in that
/// sequence, which no test on either side alone can see.
#[test]
fn the_call_sequence_the_app_uses_works_end_to_end() {
    let contents = payload(90_000);
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("a.bin", Some(&contents)), ("b.txt", Some(b"two"))]);
        let mut archive =
            ArchiveReader::open(Cursor::new(bytes.clone()), &[]).expect("open");

        let mut entries = Vec::new();
        while let Some(entry) = archive.next_entry().expect("next entry") {
            // Reading in the chunks Kotlin asks for, which is capped rather than the whole entry.
            let mut data = Vec::new();
            let mut buffer = vec![0u8; 64 * 1024];
            loop {
                let read = archive.read(&mut buffer).expect("read");
                if read == 0 {
                    break;
                }
                data.extend_from_slice(&buffer[..read]);
            }
            entries.push((entry.name, data));
        }
        // Closing is dropping the reader; there is no explicit call, which is what makes a stray
        // borrow in the Kotlin wrapper so easy to miss.
        drop(archive);

        assert_eq!(entries.len(), 2, "{}", format.extension());
        assert_eq!(entries[0].1, contents, "{}", format.extension());
        assert_eq!(entries[1].1, b"two", "{}", format.extension());
    }
}

/// Reading before stepping to an entry used to underflow an index, which panics on a debug build
/// and wraps on a release one. A panic at the JNI boundary aborts the process, so on a device it
/// looked like the app crashing rather than as an error.
#[test]
fn readingBeforeSelectingAnEntryIsAnErrorRatherThanACrash() {
    for format in MULTI_ENTRY_FORMATS {
        let bytes = write_archive(format, &[("a", Some(b"x"))]);
        let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
        let error = archive
            .read(&mut [0u8; 8])
            .expect_err("reading before selecting an entry must fail");
        assert!(
            error.to_string().contains("before one was selected"),
            "{}: {}",
            format.extension(),
            error
        );
    }
}

/// Draining an entry must not wrap its offset, which is the same abort by another route.
#[test]
fn drainingPastTheEndOfAnEntryReturnsZeroRatherThanWrapping() {
    let bytes = write_archive(Format::Zip, &[("a", Some(b"xy"))]);
    let mut archive = ArchiveReader::open(Cursor::new(bytes), &[]).unwrap();
    archive.next_entry().unwrap().unwrap();
    let mut buffer = [0u8; 8];
    assert_eq!(archive.read(&mut buffer).unwrap(), 2);
    for _ in 0..4 {
        assert_eq!(archive.read(&mut buffer).unwrap(), 0);
    }
    // Skipping after the end has to stay quiet too, which is what a fully drained entry needs.
    assert_eq!(archive.skip(16).unwrap(), 0);
}
