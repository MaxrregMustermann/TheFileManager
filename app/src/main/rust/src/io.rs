//! Bridges between the archive crates and the `java.nio` channels the app already has.
//!
//! An archive can live on FTP, SMB or a document provider, so it cannot be assumed to be a file on
//! local storage. Everything here reads and writes through a Java object instead.

use std::io::{self, Read, Seek, SeekFrom, Write};

use jni::objects::{GlobalRef, JByteArray, JObject};
use jni::sys::jint;
use jni::{AttachGuard, JNIEnv, JavaVM};

/// How much data moves per JNI call.
pub const CHUNK_SIZE: usize = 64 * 1024;

/// Attaches the calling thread and hands back the guard, which detaches on drop.
fn attach<'a>(vm: &'a JavaVM) -> io::Result<AttachGuard<'a>> {
    vm.attach_current_thread()
        .map_err(|error| io::Error::other(error.to_string()))
}

/// JNI failures are reported to Rust as I/O failures, which is what the callers do with them.
pub fn java_error(error: jni::errors::Error) -> io::Error {
    io::Error::other(error.to_string())
}

/// A `Read + Seek` over the Java object passed to `nativeOpen`.
pub struct ChannelReader {
    vm: JavaVM,
    channel: GlobalRef,
    position: u64,
}

impl ChannelReader {
    pub fn new(env: &mut JNIEnv, channel: &JObject) -> jni::errors::Result<ChannelReader> {
        Ok(ChannelReader {
            vm: env.get_java_vm()?,
            channel: env.new_global_ref(channel)?,
            position: 0,
        })
    }
}

/// The signature of `SeekableByteChannel.read`, which takes a buffer and not a byte array.
///
/// The app's channel is a `java8.nio` one, so going through it means going through
/// `java.nio.ByteBuffer`. Getting this wrong is not a compile error on either side: the JNI lookup
/// fails at runtime, on the first read, as a `NoSuchMethodError`.
const CHANNEL_READ: &str = "(Ljava/nio/ByteBuffer;)I";

/// The matching signature of `SeekableByteChannel.write`, which reports how much it took.
const CHANNEL_WRITE: &str = "(Ljava/nio/ByteBuffer;)I";

impl Read for ChannelReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if buffer.is_empty() {
            return Ok(0);
        }
        let mut guard = attach(&self.vm)?;
        let env = &mut *guard;
        let channel = env
            .new_local_ref(self.channel.as_obj())
            .map_err(java_error)?;
        // A direct buffer over the caller's own memory, so the bytes land where Rust expects them
        // without a second copy back. The length is what the channel is told it may fill, which is
        // not the whole slice: the caller may have handed over a buffer larger than it wants read.
        let wanted = buffer.len();
        // SAFETY: the pointer is the caller's own slice, `wanted` is its length, and the buffer is
        // dropped at the end of this call, before the slice can be moved or resized.
        let direct = unsafe { env.new_direct_byte_buffer(buffer.as_mut_ptr(), wanted) }
            .map_err(java_error)?;
        let read = env
            .call_method(&channel, "read", CHANNEL_READ, &[(&direct).into()])
            .and_then(|value| value.i())
            .map_err(java_error)?;
        if read > 0 {
            self.position += read as u64;
        }
        // A channel returns 0 at the end of the stream, which is what std::io expects too.
        Ok(read.max(0) as usize)
    }
}

impl Seek for ChannelReader {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        let target = match position {
            SeekFrom::Start(offset) => offset as i64,
            SeekFrom::Current(offset) => self.position as i64 + offset,
            SeekFrom::End(offset) => self.size()? as i64 + offset,
        };
        if target < 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek before the start of the archive",
            ));
        }
        let mut guard = attach(&self.vm)?;
        let env = &mut *guard;
        let channel = env
            .new_local_ref(self.channel.as_obj())
            .map_err(java_error)?;
        let new_position = env
            .call_method(
                &channel,
                "position",
                "(J)J",
                &[jni::objects::JValue::Long(target)],
            )
            .and_then(|value| value.j())
            .map_err(java_error)?;
        if new_position < 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "the archive provider refused to seek",
            ));
        }
        self.position = new_position as u64;
        Ok(self.position)
    }
}

impl ChannelReader {
    fn size(&mut self) -> io::Result<u64> {
        let mut guard = attach(&self.vm)?;
        let env = &mut *guard;
        let channel = env
            .new_local_ref(self.channel.as_obj())
            .map_err(java_error)?;
        let size = env
            .call_method(&channel, "size", "()J", &[])
            .and_then(|value| value.j())
            .map_err(java_error)?;
        Ok(size.max(0) as u64)
    }
}

/// A `Write + Seek` over the Java channel passed to `nativeCreate`. Only the 7Z writer needs the
/// seek, to fill in the signature header it leaves a placeholder for.
pub struct ChannelWriter {
    vm: JavaVM,
    channel: GlobalRef,
    buffer: Vec<u8>,
}

impl ChannelWriter {
    pub fn new(env: &mut JNIEnv, channel: &JObject) -> jni::errors::Result<ChannelWriter> {
        Ok(ChannelWriter {
            vm: env.get_java_vm()?,
            channel: env.new_global_ref(channel)?,
            buffer: vec![0; CHUNK_SIZE],
        })
    }
}

impl Write for ChannelWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        let mut written = 0;
        while written < data.len() {
            let end = (written + self.buffer.len()).min(data.len());
            let chunk = &data[written..end];
            let mut guard = attach(&self.vm)?;
            let env = &mut *guard;
            let channel = env
                .new_local_ref(self.channel.as_obj())
                .map_err(java_error)?;
            let buffer = wrap(env, chunk)?;
            // A channel reports what it took rather than throwing when it takes less, so a short
            // write has to be resumed rather than treated as done.
            let accepted = env
                .call_method(&channel, "write", CHANNEL_WRITE, &[(&buffer).into()])
                .and_then(|value| value.i())
                .map_err(java_error)?;
            if accepted <= 0 {
                return Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "The channel accepted none of the archive",
                ));
            }
            written += accepted as usize;
        }
        Ok(written)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// `ByteBuffer.wrap`, which is how a read-only buffer over existing bytes is made.
fn wrap<'a>(env: &mut JNIEnv<'a>, data: &[u8]) -> io::Result<JObject<'a>> {
    let array = env.byte_array_from_slice(data).map_err(java_error)?;
    let buffer = env
        .call_static_method(
            "java/nio/ByteBuffer",
            "wrap",
            "([B)Ljava/nio/ByteBuffer;",
            &[(&array).into()],
        )
        .map_err(java_error)?;
    let buffer = buffer.l().map_err(java_error)?;
    Ok(JObject::from(buffer))
}

impl Seek for ChannelWriter {
    fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
        let offset: i64 = match position {
            SeekFrom::Start(offset) => offset as i64,
            SeekFrom::Current(offset) | SeekFrom::End(offset) => offset,
        };
        if offset < 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "seek before the start of the archive",
            ));
        }
        let mut guard = attach(&self.vm)?;
        let env = &mut *guard;
        let channel = env
            .new_local_ref(self.channel.as_obj())
            .map_err(java_error)?;
        let new_position = env
            .call_method(
                &channel,
                "position",
                "(J)J",
                &[jni::objects::JValue::Long(offset)],
            )
            .and_then(|value| value.j())
            .map_err(java_error)?;
        Ok(new_position.max(0) as u64)
    }
}

/// A `Read` that pulls the entry currently being written out of the app.
pub struct EntrySource {
    vm: JavaVM,
    source: GlobalRef,
    buffer: Vec<u8>,
}

impl EntrySource {
    pub fn new(env: &mut JNIEnv, source: &JObject) -> jni::errors::Result<EntrySource> {
        Ok(EntrySource {
            vm: env.get_java_vm()?,
            source: env.new_global_ref(source)?,
            buffer: vec![0; CHUNK_SIZE],
        })
    }
}


impl Read for EntrySource {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if output.is_empty() {
            return Ok(0);
        }
        let mut guard = attach(&self.vm)?;
        let env = &mut *guard;
        let source = env
            .new_local_ref(self.source.as_obj())
            .map_err(java_error)?;
        let array: JByteArray = env
            .byte_array_from_slice(&self.buffer)
            .map_err(java_error)?;
        // `InputStream.read(byte[], int, int)`, which is the one overload whose signature is fixed
        // by the platform rather than by this crate, so it does not have to change in step with the
        // Kotlin side.
        let accepted = env
            .call_method(
                &source,
                "read",
                "([BII)I",
                &[
                    (&array).into(),
                    jni::objects::JValue::Int(0),
                    jni::objects::JValue::Int(self.buffer.len() as jint),
                ],
            )
            .and_then(|value| value.i())
            .map_err(java_error)?
            .max(0) as usize;
        let accepted = accepted.min(self.buffer.len()).min(output.len());
        output[..accepted].copy_from_slice(&self.buffer[..accepted]);
        Ok(accepted)
    }
}
