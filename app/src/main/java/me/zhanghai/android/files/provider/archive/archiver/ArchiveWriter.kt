/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.size
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

class ArchiveWriter @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    format: ArchiveFormat
) : AutoCloseable {
    private val archive: WritableArchive = createArchive(channel, format)

    /**
     * Writes [file] into the archive under [entryName].
     *
     * The file's contents are pulled by whichever implementation is behind the archive, so they are
     * never all in memory at once. [listener] is called with the number of bytes written so far,
     * which is what the progress bar shows.
     */
    @Throws(IOException::class)
    fun write(file: Path, entryName: Path, intervalMillis: Long, listener: ((Long) -> Unit)?) {
        val name = entryName.toString()
        val lastModifiedTime = file.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS)
        val attributes = file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val type = when {
            attributes is PosixFileAttributes -> attributes.type()
            attributes.isDirectory -> PosixFileType.DIRECTORY
            attributes.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val size = file.size(LinkOption.NOFOLLOW_LINKS)
        val symbolicLinkTarget = if (attributes.isSymbolicLink) {
            file.readSymbolicLinkByteString().toString()
        } else {
            null
        }
        val entry = NewEntry(
            name, size, lastModifiedTime, type == PosixFileType.DIRECTORY,
            type == PosixFileType.SYMBOLIC_LINK, symbolicLinkTarget
        )
        if (type == PosixFileType.REGULAR_FILE) {
            file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                val progress = ProgressInputStream(inputStream, intervalMillis, listener)
                try {
                    archive.writeEntry(entry, progress)
                } finally {
                    // Whatever is left of the last interval is reported even when the archive gave
                    // up early, so the progress bar does not stall short of the entry's size.
                    progress.finish()
                }
            }
        } else {
            archive.writeEntry(entry, null)
            listener?.invoke(size)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        archive.close()
    }

    /**
     * The Rust crates when they are available, and `libarchive` otherwise.
     *
     * The format is what decides here rather than the caller passing one, since the Rust writer
     * covers every format the app can create and `libarchive` needs a filter as well as a format.
     */
    private fun createArchive(
        channel: SeekableByteChannel, format: ArchiveFormat
    ): WritableArchive {
        if (RustArchive.ensureAvailable()) {
            return RustArchive.Writer(channel, format)
        }
        val libarchiveFormat: Int
        val filter: Int
        when (format) {
            ArchiveFormat.ZIP -> {
                libarchiveFormat = me.zhanghai.android.libarchive.Archive.FORMAT_ZIP
                filter = me.zhanghai.android.libarchive.Archive.FILTER_NONE
            }
            ArchiveFormat.TAR_XZ -> {
                libarchiveFormat = me.zhanghai.android.libarchive.Archive.FORMAT_TAR
                filter = me.zhanghai.android.libarchive.Archive.FILTER_XZ
            }
            ArchiveFormat.SEVEN_Z -> {
                libarchiveFormat = me.zhanghai.android.libarchive.Archive.FORMAT_7ZIP
                filter = me.zhanghai.android.libarchive.Archive.FILTER_NONE
            }
            ArchiveFormat.TAR, ArchiveFormat.XZ ->
                throw UnsupportedOperationException("$format is only available in the Rust writer")
        }
        return WriteArchive(channel, libarchiveFormat, filter, null)
    }
}

/**
 * Reports progress and checks for cancellation as the archive pulls the bytes through it.
 *
 * The archive reads the entry's contents rather than the app pushing them, so this is what carries
 * what [copyTo] used to: the listener hears how many bytes have gone by since it last heard, and a
 * cancelled job stops promptly rather than after the whole file. It repeats `copyTo`'s behaviour of
 * reporting a delta rather than a running total, since that is what the progress bar adds up.
 */
private class ProgressInputStream(
    private val inputStream: InputStream,
    private val intervalMillis: Long,
    private val listener: ((Long) -> Unit)?
) : InputStream() {
    private var count = 0L

    private var lastProgressMillis = System.currentTimeMillis()

    @Throws(IOException::class)
    override fun read(): Int {
        val byte = inputStream.read()
        if (byte != -1) {
            count(1)
        }
        return byte
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = inputStream.read(b, off, len)
        if (read > 0) {
            count(read)
        }
        return read
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long = inputStream.skip(n)

    @Throws(IOException::class)
    override fun close() {
        inputStream.close()
    }

    @Throws(IOException::class)
    override fun available(): Int = inputStream.available()

    /** Reports the bytes counted since the last report, which is normally the last partial interval. */
    fun finish() {
        val listener = listener ?: return
        listener(count)
        count = 0
    }

    @Throws(InterruptedIOException::class)
    private fun count(read: Int) {
        count += read
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
        val listener = listener ?: return
        val currentTimeMillis = System.currentTimeMillis()
        if (currentTimeMillis >= lastProgressMillis + intervalMillis) {
            listener(count)
            lastProgressMillis = currentTimeMillis
            count = 0
        }
    }
}