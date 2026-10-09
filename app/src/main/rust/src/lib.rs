//! The native half of the archive support.
//!
//! `libarchive` used to do this work. These crates replace it:
//!
//! * `sevenz-rust2` reads and writes 7Z, which no other implementation here can write.
//! * `oxiarc` writes everything else and reads XZ, which `unarc-rs` cannot read.
//! * `unarc-rs` reads everything else, including RAR and the other formats only it covers.
//!
//! The oxiarc dependency is a fork, because upstream's writers buffer a whole entry in memory, which
//! a file manager cannot afford for the files it archives.

mod format;
mod io;
mod reader;
mod writer;

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jbyteArray, jint, jlong, jobjectArray};
use jni::JNIEnv;

pub use format::Format;
pub use reader::{ArchiveReader, Entry};
pub use writer::{ArchiveWriter, NewEntry};

/// Every failure surfaces as this, because it is what every caller already handles.
const IO_EXCEPTION: &str = "java/io/IOException";

/// The name an XZ stream's single entry is given, since a raw XZ file has no name of its own.
pub const XZ_ENTRY_NAME: &str = "content";

/// How many fields a metadata array carries, in the order [RustArchive] expects.
const METADATA_FIELDS: usize = 7;

/// The index of each field in the array [read_new_entry] reads.
mod field {
    pub const NAME: usize = 0;
    pub const SIZE: usize = 1;
    pub const LAST_MODIFIED_MILLIS: usize = 2;
    pub const IS_DIRECTORY: usize = 5;
}

/// Anything that can go wrong is reported to the app as an `IOException`, because that is what
/// every caller of these functions already handles.
fn throw(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new(IO_EXCEPTION, message.as_ref());
}

fn io_result<T>(env: &mut JNIEnv, result: std::io::Result<T>) -> Option<T> {
    match result {
        Ok(value) => Some(value),
        Err(error) => {
            throw(env, error.to_string());
            None
        }
    }
}

fn null_pointer() -> jlong {
    0
}

type ReadHandle = reader::ArchiveReader<io::ChannelReader>;

type WriteHandle = writer::ArchiveWriter<io::ChannelWriter>;

fn from_handle<'a>(handle: jlong) -> Option<&'a mut ReadHandle> {
    if handle == 0 {
        return None;
    }
    // SAFETY: handles come from `Box::into_raw` in `nativeOpen` and are only used by the Java side
    // that owns them, which frees them in `nativeClose`. Every function here is called on one
    // thread at a time, from the same Java object.
    unsafe { Some(&mut *(handle as *mut ReadHandle)) }
}

fn writer_from_handle<'a>(handle: jlong) -> Option<&'a mut WriteHandle> {
    if handle == 0 {
        return None;
    }
    // SAFETY: as above.
    unsafe { Some(&mut *(handle as *mut WriteHandle)) }
}

fn build_reader(
    env: &mut JNIEnv,
    channel: &JObject,
    passwords: &JObjectArray,
) -> std::io::Result<ReadHandle> {
    let channel = io::ChannelReader::new(env, channel)
        .map_err(|error| std::io::Error::other(error.to_string()))?;
    let mut collected = Vec::new();
    let length = env.get_array_length(passwords).map_err(io::java_error)?;
    for index in 0..length {
        // A JNI array element is null rather than absent, so a null slot is simply skipped.
        let value = env
            .get_object_array_element(passwords, index)
            .map_err(io::java_error)?;
        if !value.is_null() {
            let value: JString = value.into();
            collected.push(env.get_string(&value).map_err(io::java_error)?.into());
        }
    }
    reader::ArchiveReader::open(channel, &collected)
}

// ---------------------------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    channel: JObject,
    passwords: JObjectArray,
) -> jlong {
    let built = build_reader(&mut env, &channel, &passwords);
    let archive = match io_result(&mut env, built) {
        Some(archive) => archive,
        None => return null_pointer(),
    };
    Box::into_raw(Box::new(archive)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeNextEntry<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    let archive = match from_handle(handle) {
        Some(archive) => archive,
        None => {
            throw(&mut env, "The archive has already been closed");
            return std::ptr::null_mut();
        }
    };
    let entry = match io_result(&mut env, archive.next_entry()) {
        Some(Some(entry)) => entry,
        _ => return std::ptr::null_mut(),
    };
    let metadata = [
        Some(entry.name),
        Some(entry.size.to_string()),
        entry.last_modified_millis.map(|millis| millis.to_string()),
        entry.symlink_target,
        Some(entry.is_encrypted.to_string()),
        Some(entry.is_directory.to_string()),
        Some(entry.is_symlink.to_string()),
    ];
    let array =
        match env.new_object_array(METADATA_FIELDS as jint, "java/lang/String", JObject::null()) {
            Ok(array) => array,
            Err(_) => return std::ptr::null_mut(),
        };
    for (index, value) in metadata.iter().enumerate() {
        let element: JObject = match value {
            Some(value) => match env.new_string(value.clone()) {
                Ok(value) => value.into(),
                Err(_) => return std::ptr::null_mut(),
            },
            None => JObject::null(),
        };
        if env
            .set_object_array_element(&array, index as jint, &element)
            .is_err()
        {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeReadData(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    length: jint,
) -> jbyteArray {
    let archive = match from_handle(handle) {
        Some(archive) => archive,
        None => {
            throw(&mut env, "The archive has already been closed");
            return std::ptr::null_mut();
        }
    };
    let mut temporary = vec![0u8; length.max(0) as usize];
    let read = match io_result(&mut env, archive.read(&mut temporary)) {
        Some(read) => read,
        None => return std::ptr::null_mut(),
    };
    if read == 0 {
        return std::ptr::null_mut();
    }
    match env.byte_array_from_slice(&temporary[..read]) {
        Ok(array) => array.into_raw(),
        Err(error) => {
            throw(&mut env, error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeSkipData(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    request: jlong,
) -> jlong {
    let archive = match from_handle(handle) {
        Some(archive) => archive,
        None => {
            throw(&mut env, "The archive has already been closed");
            return -1;
        }
    };
    io_result(&mut env, archive.skip(request)).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeCloseRead(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the Java side only calls this once, and only with a handle from `nativeOpen`.
    drop(unsafe { Box::from_raw(handle as *mut ReadHandle) });
}

// ---------------------------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    channel: JObject,
    format: jint,
) -> jlong {
    let format = match Format::from_id(format) {
        Some(format) => format,
        None => {
            throw(&mut env, format!("Unknown archive format {format}"));
            return null_pointer();
        }
    };
    let channel = match io::ChannelWriter::new(&mut env, &channel) {
        Ok(channel) => channel,
        Err(error) => {
            throw(&mut env, error.to_string());
            return null_pointer();
        }
    };
    match io_result(&mut env, writer::ArchiveWriter::create(channel, format)) {
        Some(archive) => Box::into_raw(Box::new(archive)) as jlong,
        None => null_pointer(),
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeWriteEntry(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    metadata: JObjectArray,
    source: JObject,
) -> jint {
    let archive = match writer_from_handle(handle) {
        Some(archive) => archive,
        None => {
            throw(&mut env, "The archive has already been closed");
            return -1;
        }
    };
    let entry = match read_new_entry(&mut env, &metadata) {
        Some(entry) => entry,
        None => return -1,
    };
    let mut entry_source = match io::EntrySource::new(&mut env, &source) {
        Ok(source) => source,
        Err(error) => {
            throw(&mut env, error.to_string());
            return -1;
        }
    };
    match io_result(&mut env, archive.write_entry(&entry, &mut entry_source)) {
        Some(()) => 0,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeFinish(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        throw(&mut env, "The archive has already been closed");
        return -1;
    }
    // SAFETY: `finish` consumes the writer, so the handle is spent, and the Java side only calls
    // this once with a handle from `nativeCreate`.
    let archive = unsafe { Box::from_raw(handle as *mut WriteHandle) };
    match io_result(&mut env, archive.finish()) {
        Some(()) => 0,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_me_zhanghai_android_files_provider_archive_archiver_RustArchive_nativeCloseWrite(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the Java side only calls this once, and only with a handle from `nativeCreate`.
    drop(unsafe { Box::from_raw(handle as *mut WriteHandle) });
}

fn read_new_entry(env: &mut JNIEnv, metadata: &JObjectArray) -> Option<writer::NewEntry> {
    let string = |env: &mut JNIEnv, index: usize| -> Option<String> {
        let value = match env.get_object_array_element(metadata, index as jint) {
            Ok(value) => value,
            Err(_) => return None,
        };
        if value.is_null() {
            return None;
        }
        let value: JString = value.into();
        env.get_string(&value).ok().map(Into::into)
    };
    let name = string(env, field::NAME);
    let name = match name {
        Some(name) => name,
        None => {
            throw(env, "An archive entry has no name");
            return None;
        }
    };
    Some(writer::NewEntry {
        name,
        size: string(env, field::SIZE)
            .and_then(|value| value.parse().ok())
            .unwrap_or(0),
        is_directory: string(env, field::IS_DIRECTORY).as_deref() == Some("true"),
        last_modified_millis: string(env, field::LAST_MODIFIED_MILLIS)
            .and_then(|value| value.parse().ok()),
    })
}
