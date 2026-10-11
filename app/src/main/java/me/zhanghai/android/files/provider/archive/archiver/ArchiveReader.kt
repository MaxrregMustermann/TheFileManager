/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import androidx.preference.PreferenceManager
import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.common.DelegateForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.DelegateNonForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.ForceableChannel
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

object ArchiveReader {
    /**
     * What one walk of an archive produces.
     *
     * The three maps are held together because they are built together and become meaningless
     * together: an index from one walk only means anything next to the entries from that same walk,
     * and every caller drops all three when the archive is refreshed.
     */
    class ArchiveListing(
        val entries: Map<Path, ReadArchive.Entry>,
        /**
         * The position each entry in [entries] was listed from, so a later read can ask for it by
         * that position instead of by name.
         *
         * Absent for the directories this walk synthesised rather than read, and for an entry whose
         * name was dropped as a path traversal attempt. Those are never read, so they never need an
         * index, and claiming one would be worse than not having the map.
         */
        val entryIndices: Map<Path, Int>,
        val tree: Map<Path, List<Path>>
    )

    @Throws(IOException::class)
    fun readEntries(
        file: Path,
        passwords: List<String>,
        rootPath: Path
    ): ArchiveListing {
        val entries = mutableMapOf<Path, ReadArchive.Entry>()
        val entryIndices = mutableMapOf<Path, Int>()
        val rawEntries = readEntries(file, passwords)
        for (indexedEntry in rawEntries) {
            val entry = indexedEntry.entry
            var path = rootPath.resolve(entry.name)
            // Normalize an absolute path to prevent path traversal attack.
            if (!path.isAbsolute) {
                // TODO: Will this actually happen?
                throw AssertionError("Path must be absolute: $path")
            }
            if (path.nameCount > 0) {
                path = path.normalize()
                if (path.nameCount == 0) {
                    // Don't allow a path to become the root path only after normalization.
                    continue
                }
            } else {
                if (!entry.isDirectory) {
                    // Ignore a root path that's not a directory
                    continue
                }
            }
            entries.getOrPut(path) { entry }
            // Indexed with the same `getOrPut`, so the index belongs to the entry that was kept.
            // An archive can hold two entries that normalize to one path, and the second one is not
            // the one anybody can see.
            entryIndices.getOrPut(path) { indexedEntry.index }
        }
        entries.getOrPut(rootPath) { createDirectoryEntry("") }
        val tree = mutableMapOf<Path, MutableList<Path>>()
        tree[rootPath] = mutableListOf()
        val paths = entries.keys.toList()
        for (path in paths) {
            var path = path
            while (true) {
                val parentPath = path.parent ?: break
                val entry = entries[path]!!
                if (entry.isDirectory) {
                    tree.getOrPut(path) { mutableListOf() }
                }
                tree.getOrPut(parentPath) { mutableListOf() }.add(path)
                if (entries.containsKey(parentPath)) {
                    break
                }
                entries[parentPath] = createDirectoryEntry(parentPath.toString())
                path = parentPath
            }
        }
        return ArchiveListing(entries, entryIndices, tree)
    }

    private fun createDirectoryEntry(name: String): ReadArchive.Entry {
        require(!name.endsWith("/")) { "name $name should not end with a slash" }
        return ReadArchive.Entry(
            name, false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
            PosixFileMode.DIRECTORY_DEFAULT, null
        )
    }

    /** An entry and the position it was listed from, which is what addresses it later. */
    private class IndexedEntry(val index: Int, val entry: ReadArchive.Entry)

    @Throws(IOException::class)
    private fun readEntries(file: Path, passwords: List<String>): List<IndexedEntry> {
        val charset = archiveFileNameCharset
        val (archive, closeable) = openArchive(file, passwords)
        return closeable.use {
            buildList {
                var index = 0
                while (true) {
                    val entry = archive.readEntry(charset) ?: break
                    add(IndexedEntry(index++, entry))
                }
            }
        }
    }

    @Throws(IOException::class)
    fun newInputStream(
        file: Path, passwords: List<String>, entry: ReadArchive.Entry
    ): InputStream? {
        val charset = archiveFileNameCharset
        val (archive, closeable) = openArchive(file, passwords)
        var successful = false
        return try {
            while (true) {
                val currentEntry = archive.readEntry(charset) ?: break
                if (currentEntry.name != entry.name) {
                    continue
                }
                successful = true
                break
            }
            if (successful) {
                CloseableInputStream(archive.newDataInputStream(), closeable)
            } else {
                null
            }
        } finally {
            if (!successful) {
                closeable.close()
            }
        }
    }

    /**
     * Opens [file], preferring the Rust implementation and falling back to `libarchive`.
     *
     * The fallback exists because the Rust library is compiled from source during the build, so it
     * can be absent from a checkout that has not run the Gradle task, and because `libarchive` covers
     * a few formats the Rust crates do not.
     *
     * Also how [ArchiveFileSystem] gets an archive it holds open across several reads: an archive
     * opened once and walked in order decodes a solid 7Z block once, where one opened per file
     * decodes it once per file.
     */
    @Throws(IOException::class)
    internal fun openArchive(
        file: Path,
        passwords: List<String>
    ): Pair<ReadableArchive, Closeable> {
        val channel = try {
            CacheSizeSeekableByteChannel(file.newByteChannel())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (channel != null) {
            var successful = false
            try {
                // The file name, for the two formats no signature can identify: an ARC starts
                // with the DOS end-of-file marker and an ICE with its own uncompressed length, so
                // both are detected as ZIP from their bytes alone. The name is a fallback and
                // never an override — a real archive keeps its format whatever it is called.
                val archive = readArchive(channel, passwords, file.fileName?.toString())
                successful = true
                return archive to ArchiveCloseable(archive, channel)
            } finally {
                if (!successful) {
                    channel.close()
                }
            }
        }
        val inputStream = file.newInputStream()
        var successful = false
        try {
            val archive: ReadableArchive = ReadArchive(inputStream, passwords)
            successful = true
            return archive to ArchiveCloseable(archive, inputStream)
        } finally {
            if (!successful) {
                inputStream.close()
            }
        }
    }

    /**
     * Reads [channel] with the Rust crates when they are available, and `libarchive` otherwise.
     *
     * A file the Rust side refuses is retried with `libarchive` rather than reported, because an
     * archive one implementation will not open is often one the other will, and the failure modes
     * do not agree: `unarc-rs` reads a long tail that `libarchive` does not, and neither reads
     * every format the other does.
     */
    @Throws(IOException::class)
    private fun readArchive(
        channel: SeekableByteChannel, passwords: List<String>, name: String?
    ): ReadableArchive {
        if (RustArchive.ensureAvailable()) {
            try {
                return RustReadArchive(channel, passwords, name)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return ReadArchive(channel, passwords)
    }

    // size() may be called repeatedly for ZIP and 7Z, so make it cached to improve performance.
    private fun CacheSizeSeekableByteChannel(channel: SeekableByteChannel): SeekableByteChannel =
        if (channel is ForceableChannel) {
            CacheSizeForceableSeekableByteChannel(channel)
        } else {
            CacheSizeNonForceableSeekableByteChannel(channel)
        }

    private class CacheSizeNonForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateNonForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private class CacheSizeForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    /**
     * How entry names are decoded.
     *
     * `internal` rather than private because [ArchiveFileSystem] walks a held-open archive with the
     * same charset the listing used, and using a different one would compare two different strings.
     */
    internal val archiveFileNameCharset: Charset
        get() =
            if (isRunningAsRoot) {
                try {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(rootContext)
                    val key = rootContext.getString(R.string.pref_key_archive_file_name_encoding)
                    val defaultValue = rootContext.getString(
                        R.string.pref_default_value_archive_file_name_encoding
                    )
                    Charset.forName(sharedPreferences.getString(key, defaultValue)!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                    StandardCharsets.UTF_8
                }
            } else {
                Charset.forName(Settings.ARCHIVE_FILE_NAME_ENCODING.valueCompat)
            }

    private class ArchiveCloseable(
        private val archive: ReadableArchive,
        private val closeable: Closeable
    ) : Closeable {
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                archive.close()
            } finally {
                closeable.close()
            }
        }
    }

    private class CloseableInputStream(
        inputStream: InputStream,
        private val closeable: Closeable
    ) : DelegateInputStream(inputStream) {
        @Throws(IOException::class)
        override fun close() {
            super.close()

            closeable.close()
        }
    }
}
