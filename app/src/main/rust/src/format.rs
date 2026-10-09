//! The archive formats, and which crate handles each of them.
//!
//! The split of responsibilities is deliberate:
//!
//! * 7Z is read and written by `sevenz-rust2`, the only one of the three that can write 7Z.
//! * Everything else is written by `oxiarc`, whose streaming writers only need `Write`.
//! * XZ is read by `oxiarc` as well, because `unarc-rs` cannot read it.
//! * Everything else is read by `unarc-rs`, which covers the long tail neither of the others
//!   does: RAR, ARJ, ACE, ARC and the rest.

/// What the app asks the native side to do. The numbering is part of the contract with
/// `ArchiveFormat` on the Kotlin side.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    SevenZ,
    Zip,
    Tar,
    TarXz,
    Xz,
}

impl Format {
    pub fn id(self) -> i32 {
        match self {
            Format::SevenZ => 0,
            Format::Zip => 1,
            Format::Tar => 2,
            Format::TarXz => 3,
            Format::Xz => 4,
        }
    }

    pub fn from_id(id: i32) -> Option<Format> {
        Some(match id {
            0 => Format::SevenZ,
            1 => Format::Zip,
            2 => Format::Tar,
            3 => Format::TarXz,
            4 => Format::Xz,
            _ => return None,
        })
    }

    /// The extension the app appends when creating an archive of this format.
    pub fn extension(self) -> &'static str {
        match self {
            Format::SevenZ => "7z",
            Format::Zip => "zip",
            Format::Tar => "tar",
            Format::TarXz => "tar.xz",
            Format::Xz => "xz",
        }
    }

    /// Whether the app can encrypt an archive of this format.
    pub fn supports_password(self) -> bool {
        matches!(self, Format::Zip)
    }

    /// Guesses the format from the first bytes of a file. Magic bytes rather than the name, since
    /// the name says what the file is called and the bytes say what it is.
    /// `None` when the bytes match nothing this code writes. Reading never gets that far: an archive
    /// the app did not create is read by `unarc-rs`, which does its own, far wider, detection.
    pub fn detect(header: &[u8]) -> Option<Format> {
        const SEVEN_Z: &[u8] = &[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C];
        const XZ: &[u8] = &[0xFD, b'7', b'z', b'X', b'Z', 0x00];
        const ZIP_LOCAL: &[u8] = &[b'P', b'K', 0x03, 0x04];
        const ZIP_EMPTY: &[u8] = &[b'P', b'K', 0x05, 0x06];
        const ZIP_SPANNED: &[u8] = &[b'P', b'K', 0x07, 0x08];
        if starts_with(header, SEVEN_Z) {
            return Some(Format::SevenZ);
        }
        if starts_with(header, XZ) {
            return Some(Format::Xz);
        }
        if starts_with(header, ZIP_LOCAL)
            || starts_with(header, ZIP_EMPTY)
            || starts_with(header, ZIP_SPANNED)
        {
            return Some(Format::Zip);
        }
        if header.len() > 262 && &header[257..262] == b"ustar" {
            return Some(Format::Tar);
        }
        None
    }
}
fn starts_with(header: &[u8], prefix: &[u8]) -> bool {
    header.len() >= prefix.len() && &header[..prefix.len()] == prefix
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_by_magic_bytes_rather_than_by_name() {
        assert_eq!(
            Format::detect(&[0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C]),
            Some(Format::SevenZ)
        );
        assert_eq!(
            Format::detect(&[0xFD, b'7', b'z', b'X', b'Z', 0x00]),
            Some(Format::Xz)
        );
        assert_eq!(Format::detect(&[b'P', b'K', 0x03, 0x04]), Some(Format::Zip));
        assert_eq!(Format::detect(&[b'P', b'K', 0x05, 0x06]), Some(Format::Zip));
        assert_eq!(Format::detect(&[b'P', b'K', 0x07, 0x08]), Some(Format::Zip));
        assert_eq!(Format::detect(b"not an archive"), None);
        assert_eq!(Format::detect(&[]), None);
    }

    #[test]
    fn detects_tar_from_its_magic_offset() {
        let mut header = vec![0u8; 265];
        header[257..262].copy_from_slice(b"ustar");
        assert_eq!(Format::detect(&header), Some(Format::Tar));
    }

    #[test]
    fn format_ids_round_trip() {
        for format in [
            Format::SevenZ,
            Format::Zip,
            Format::Tar,
            Format::TarXz,
            Format::Xz,
        ] {
            assert_eq!(Format::from_id(format.id()), Some(format));
        }
        assert_eq!(Format::from_id(-1), None);
    }
}
