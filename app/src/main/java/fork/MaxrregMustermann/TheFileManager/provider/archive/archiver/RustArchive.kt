/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.attribute.FileTime
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileMode
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileType
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads an archive through the Rust crates.
 *
 * Presents the same interface as [ReadArchive], which is `libarchive`, so that [ArchiveReader] can
 * choose between them without either knowing about the other.
 */
class RustReadArchive @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    passwords: List<String>,
    name: String?
) : ReadableArchive {
    private val reader = RustArchive.Reader(channel, passwords, name)

    override val position: Int
        get() = reader.position

    @Throws(IOException::class)
    override fun readEntry(charset: Charset): ReadArchive.Entry? =
        reader.readEntry()?.let { entry ->
            ReadArchive.Entry(
                entry.name, entry.isEncrypted,
                entry.lastModifiedMillis?.let { FileTime.fromMillis(it) }, null, null,
                when {
                    entry.isDirectory -> PosixFileType.DIRECTORY
                    entry.isSymlink -> PosixFileType.SYMBOLIC_LINK
                    else -> PosixFileType.REGULAR_FILE
                },
                entry.size, null, null,
                when {
                    entry.isDirectory -> PosixFileMode.DIRECTORY_DEFAULT
                    entry.isSymlink -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
                    else -> PosixFileMode.FILE_DEFAULT
                },
                entry.symlinkTarget
            )
        }

    @Throws(IOException::class)
    override fun newDataInputStream(): InputStream = reader.newDataInputStream()

    @Throws(IOException::class)
    override fun close() {
        reader.close()
    }
}

/**
 * The native half of the archive support, which used to be `libarchive`.
 *
 * Everything here is a thin bridge: the formats, the entry order and the errors all belong to the
 * Rust side, and this class only exists to hand it a channel and to turn what it returns into the
 * types the rest of the app already uses.
 *
 * The reader and the writer are separate objects rather than one class with a mode, because the
 * native side does the same and the handle behind them has two different lifetimes.
 */
internal object RustArchive {
    /**
     * How many bytes move across JNI per call. Large enough that the call overhead stops mattering,
     * small enough that a per-call allocation on the native side stays cheap.
     */
    private const val CHUNK_SIZE = 64 * 1024

    /**
     * The fields a `nativeNextEntry` result carries, in the order the native side writes them.
     *
     * Everything is a string because a null in an object array costs a local reference to test for
     * and a string costs a conversion, and none of these fields are large.
     */
    private const val METADATA_NAME = 0
    private const val METADATA_SIZE = 1
    private const val METADATA_LAST_MODIFIED_MILLIS = 2
    private const val METADATA_SYMLINK_TARGET = 3
    private const val METADATA_IS_ENCRYPTED = 4
    private const val METADATA_IS_DIRECTORY = 5
    private const val METADATA_IS_SYMLINK = 6

    /** How many fields [nativeNextEntry] returns, which is what `METADATA_FIELDS` is on the Rust side. */
    private const val METADATA_FIELDS = METADATA_IS_SYMLINK + 1

    /**
     * How many archives this process has opened.
     *
     * A measurement, not a limit, and it exists because "reads the archive once" is otherwise
     * invisible from outside: a caller sees the same bytes either way. Reading every entry of an
     * archive used to open it once per entry, and this is the number that says so.
     */
    @Volatile
    var openCount: Int = 0
        private set

    @Volatile
    private var isAvailable: Boolean? = null

    /**
     * Whether the native library is in the APK and can be loaded.
     *
     * The Rust library is built from source by the `buildRustArchive` Gradle task rather than checked
     * in, so it can be missing in an incremental build or when the toolchain is not installed. In
     * that case [ReadArchive] and [WriteArchive], which are `libarchive`, still work.
     */
    @Throws(IOException::class)
    fun ensureAvailable(): Boolean =
        isAvailable ?: try {
            System.loadLibrary("rust_archive")
            true
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
            false
        }.also { isAvailable = it }

    /**
     * A readable archive, whatever crate is behind it.
     *
     * Only one of these is open at a time in practice, but nothing enforces that: the native side
     * keeps one handle per object and does not lock, so two threads sharing an instance would
     * interleave reads. The callers all hold the archive on one thread.
     */
    class Reader @Throws(IOException::class) constructor(
        channel: SeekableByteChannel,
        passwords: List<String>,
        /**
         * The archive's file name, or null when the caller does not have one.
         *
         * Detection reads the bytes first, so a name never overrides what the file is; it is the
         * fallback for the two formats no signature can identify, ARC and ICE. Without it those
         * two are detected as ZIP and refused, and every other format is unaffected.
         */
        name: String?
    ) : Closeable {
        private var handle: Long =
            nativeOpenNamed(ArchiveChannel(channel), passwords.toTypedArray(), name).also {
                // Zero is what a failed open returns, and every other call would then report the
                // archive as closed, which hides the real problem. Checked here instead.
                check(it != 0L) { "The archive could not be opened" }
                openCount++
            }

        private var isClosed = false

        /**
         * How many entries [readEntry] has returned, which is the position the next one comes from.
         *
         * The native side keeps its own count and this mirrors it, so that an entry the listing saw
         * at position [n] can be asked for by that [n] later without searching for it by name. Only
         * the sequential walk [readEntry] performs counts, which is the order the position is
         * relative to.
         */
        var position: Int = 0
            private set

        @Throws(IOException::class)
        private fun handle(): Long {
            // An IOException rather than the IllegalStateException `check` would throw, because
            // every caller of this class already handles one and would otherwise let it escape.
            if (isClosed) {
                throw IOException("The archive is closed")
            }
            return handle
        }

        /**
         * Moves to the next entry, or returns null at the end of the archive.
         *
         * Null is the end of the archive, not a failure. A failure arrives as a thrown
         * `IOException` from the native side, because it throws one before returning, so an empty
         * return cannot mean both. Treating null as an error here made every archive fail the
         * moment its last entry was reached.
         */
        @Throws(IOException::class)
        fun readEntry(): Entry? {
            val metadata = nativeNextEntry(handle()) ?: return null
            if (metadata.size != METADATA_FIELDS) {
                throw IOException("The native side returned ${metadata.size} fields, not $METADATA_FIELDS")
            }
            fun field(index: Int): String? = metadata[index]
            val name = field(METADATA_NAME) ?: throw IOException("An entry has no name")
            val size = field(METADATA_SIZE)?.toLongOrNull() ?: 0
            val lastModifiedMillis = field(METADATA_LAST_MODIFIED_MILLIS)?.toLongOrNull()
            position++
            return Entry(
                name, size, lastModifiedMillis, field(METADATA_SYMLINK_TARGET),
                field(METADATA_IS_ENCRYPTED) == "true", field(METADATA_IS_DIRECTORY) == "true",
                field(METADATA_IS_SYMLINK) == "true"
            )
        }

        @Throws(IOException::class)
        fun newDataInputStream(): InputStream = DataInputStream()

        @Throws(IOException::class)
        override fun close() {
            if (isClosed) {
                return
            }
            isClosed = true
            val handle = handle
            this.handle = 0
            if (handle != 0L) {
                nativeCloseRead(handle)
            }
        }

        class Entry(
            val name: String,
            val size: Long,
            /** Milliseconds since the epoch, or null when the archive does not say. */
            val lastModifiedMillis: Long?,
            val symlinkTarget: String?,
            val isEncrypted: Boolean,
            val isDirectory: Boolean,
            val isSymlink: Boolean
        )

        private inner class DataInputStream : InputStream() {
            private var isClosed = false

            @Throws(IOException::class)
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xFF
            }

            @Throws(IOException::class)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (isClosed) {
                    throw IOException("The entry stream is closed")
                }
                if (len == 0) {
                    return 0
                }
                // The native side returns null rather than a zero-length array at the end of the
                // entry, since `InputStream.read` is expected to return -1 there.
                val read = nativeReadData(handle(), minOf(len, CHUNK_SIZE)) ?: return -1
                System.arraycopy(read, 0, b, off, read.size)
                return read.size
            }

            override fun skip(n: Long): Long {
                if (n <= 0) {
                    return 0
                }
                val skipped = nativeSkipData(handle(), n)
                if (skipped < 0) {
                    throw IOException("Could not skip $n bytes")
                }
                return skipped
            }

            override fun close() {
                if (isClosed) {
                    return
                }
                isClosed = true
                // The native side is told nothing here: the archive is closed when the archive is,
                // and closing the archive is the caller's job.
            }

            override fun available(): Int = 0
        }
    }

    /**
     * A writable archive, whatever crate is behind it.
     *
     * [finish] has to be called before the channel is used, or the archive has no central directory
     * and nothing can read it back. It is called by [WriteArchive.close], which is the only path
     * the app has of finishing an archive.
     */
    class Writer @Throws(IOException::class) constructor(
        channel: SeekableByteChannel,
        format: ArchiveFormat
    ) : WritableArchive {
        private var handle: Long = nativeCreate(ArchiveChannel(channel), format.id).also {
            check(it != 0L) { "The archive could not be created" }
        }

        private var isClosed = false

        @Throws(IOException::class)
        override fun writeEntry(entry: NewEntry, source: InputStream?) {
            check(!isClosed) { "The archive is closed" }
            // A null in the array means the archive does not record that field, which is why the
            // element type is nullable even though every field here is always filled in.
            val metadata: Array<String?> = arrayOf(
                entry.name,
                entry.size.toString(),
                entry.lastModifiedTime?.toMillis()?.toString(),
                "",
                "false",
                entry.isDirectory.toString(),
                entry.isSymbolicLink.toString()
            )
            // A directory or a symbolic link has no contents, and the native side is handed an empty
            // stream rather than nothing because the metadata already says what the entry is.
            val contents = source ?: EMPTY_INPUT_STREAM
            if (nativeWriteEntry(handle, metadata, contents) != 0) {
                throw IOException("Could not write the entry ${entry.name}")
            }
        }

        @Throws(IOException::class)
        override fun close() {
            if (isClosed) {
                return
            }
            isClosed = true
            val handle = handle
            this.handle = 0
            if (handle != 0L) {
                // Finishing is what writes the central directory, so a failure here is the one that
                // matters; the handle is spent either way, since the native side frees it on finish.
                if (nativeFinish(handle) != 0) {
                    throw IOException("Could not finish the archive")
                }
            }
        }

    }

    /** Stands in for the contents of an entry that has none. */
    private val EMPTY_INPUT_STREAM: InputStream = object : InputStream() {
        override fun read(): Int = -1

        override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    }

    private external fun nativeOpenNamed(
        channel: ArchiveChannel, passwords: Array<String>, name: String?
    ): Long

    private external fun nativeNextEntry(handle: Long): Array<String?>?

    private external fun nativeReadData(handle: Long, length: Int): ByteArray?

    private external fun nativeSkipData(handle: Long, request: Long): Long

    private external fun nativeCloseRead(handle: Long)

    private external fun nativeCreate(channel: ArchiveChannel, format: Int): Long

    private external fun nativeWriteEntry(
        handle: Long, metadata: Array<String?>, source: InputStream
    ): Int

    private external fun nativeFinish(handle: Long): Int

    private external fun nativeCloseWrite(handle: Long)
}
