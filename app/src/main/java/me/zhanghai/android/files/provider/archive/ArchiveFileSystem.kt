/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.ClosedFileSystemException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchService
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.archiver.ArchiveReader
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.archive.archiver.ReadableArchive
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringBuilder
import me.zhanghai.android.files.provider.common.ByteStringListPathCreator
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

internal class ArchiveFileSystem(
    private val provider: ArchiveFileSystemProvider,
    val archiveFile: Path
) : FileSystem(), ByteStringListPathCreator, Parcelable {
    val rootDirectory = ArchivePath(this, SEPARATOR_BYTE_STRING)

    init {
        if (!rootDirectory.isAbsolute) {
            throw AssertionError("Root directory $rootDirectory must be absolute")
        }
        if (rootDirectory.nameCount != 0) {
            throw AssertionError("Root directory $rootDirectory must contain no names")
        }
    }

    val defaultDirectory: ArchivePath
        get() = rootDirectory

    private val lock = Any()

    private var isOpen = true

    private var passwords = listOf<String>()

    private var isRefreshNeeded = true

    private var entries: Map<Path, ReadArchive.Entry>? = null

    /**
     * Where each entry of [entries] was listed from, so a read can ask for it by position.
     *
     * Null exactly when [entries] is null. The two are built by one walk and dropped by one refresh,
     * and an index from a different walk would address a different entry.
     */
    private var entryIndices: Map<Path, Int>? = null

    private var tree: Map<Path, List<Path>>? = null

    /**
     * Archives this file system is holding open across reads.
     *
     * Before, every file read opened the archive and closed it again, so extracting a folder of 400
     * files opened the archive 400 times and every one of those decoded the whole solid 7Z block
     * that file lived in. Holding the archive open across the reads of a job turns that into one
     * open, and the win is not small: the same 400 entries take 204 ms stored non-solid and 15.5 s
     * solid when each one is read by name from a fresh archive.
     *
     * Held under [lock] like everything else here, and released by [refresh], [addPassword] and
     * [close] - see [closeOpenArchivesLocked] for why all three are needed. The file system itself is
     * held weakly by `ArchiveFileSystemProvider`, so a garbage collected one takes its archives with
     * it, which is the eviction policy a file manager wants: nobody has to decide when to let go.
     */
    private val openArchives = ArrayList<OpenArchive>()

    @Throws(IOException::class)
    fun getEntry(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            ensureEntriesLocked(path)
            getEntryLocked(path)
        }

    @Throws(IOException::class)
    private fun getEntryLocked(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            entries!![path] ?: throw NoSuchFileException(path.toString())
        }

    @Throws(IOException::class)
    fun newInputStream(file: Path): InputStream =
        synchronized(lock) {
            ensureEntriesLocked(file)
            val entry = getEntryLocked(file)
            if (entry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            val inputStream = try {
                newInputStreamLocked(file, entry)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            }
            ArchiveExceptionInputStream(inputStream, file)
        }

    /**
     * A stream over [entry], preferring an archive this file system already has open.
     *
     * The position the entry was listed from is what makes that possible. An archive only moves
     * forward, so an entry the held archive has already walked past cannot be served from it - the
     * archive is closed and reopened, which is exactly what the app did for every read before, and
     * then the request is retried against the fresh archive. When there is no position at all, which
     * is the case for the directories this class synthesises and for a name the listing dropped as a
     * path traversal attempt, the per-read path below is the only one there is.
     */
    @Throws(IOException::class)
    private fun newInputStreamLocked(file: Path, entry: ReadArchive.Entry): InputStream {
        val index = entryIndices!![file]
        if (index != null) {
            val inputStream = newInputStreamFromOpenArchiveLocked(entry, index)
            if (inputStream != null) {
                return inputStream
            }
        }
        val inputStream = try {
            ArchiveReader.newInputStream(archiveFile, passwords, entry)
        } catch (e: ArchiveException) {
            throw e.toFileSystemOrInterruptedIOException(file)
        } ?: throw NoSuchFileException(file.toString())
        return inputStream
    }

    /**
     * A stream over [entry] served off an archive this file system is holding open, or null when no
     * archive it holds can serve it and the caller has to fall back to opening one.
     *
     * Two attempts, which is all the situation can need. The first is an archive that may already
     * have walked past [index]; the second is a fresh archive, which starts at the beginning of the
     * archive, so if that one cannot serve it then the entry really is not there and the caller
     * reports it rather than reading whatever happens to be at that position.
     */
    private fun newInputStreamFromOpenArchiveLocked(
        entry: ReadArchive.Entry, index: Int
    ): InputStream? {
        val charset = ArchiveReader.archiveFileNameCharset
        for (attempt in 0 until 2) {
            val openArchive = leaseOpenArchiveLocked()
            val inputStream = try {
                openArchive.archive.openEntry(index, entry, charset)
            } catch (e: Exception) {
                // The lease has to go back either way, or a failed read would take the archive out
                // of the pool for good. The exception is the caller's to handle and is not ours to
                // translate here.
                releaseOpenArchiveLocked(openArchive)
                throw e
            }
            if (inputStream != null) {
                return LeaseInputStream(inputStream, openArchive, this)
            }
            closeOpenArchiveLocked(openArchive)
        }
        return null
    }

    /**
     * An archive to serve one read from, leased to this call until the stream it produces is closed.
     *
     * A pooled archive that is free comes first, so that reads in order keep getting the same
     * archive, which is what makes the block reuse work. Beyond [MAX_OPEN_ARCHIVES] concurrent
     * readers each get an archive of their own that is closed with their stream, which is the
     * behaviour the app had for every read: a bounded pool with a wait for a free slot would be a
     * deadlock waiting for a caller that holds two streams at once to read them in an order the
     * second one cannot have.
     */
    private fun leaseOpenArchiveLocked(): OpenArchive {
        for (openArchive in openArchives) {
            if (!openArchive.isLeased) {
                openArchive.isLeased = true
                return openArchive
            }
        }
        val (archive, closeable) = ArchiveReader.openArchive(archiveFile, passwords)
        val isPooled = openArchives.size < MAX_OPEN_ARCHIVES
        val openArchive = OpenArchive(archive, closeable, isPooled)
        openArchive.isLeased = true
        if (isPooled) {
            openArchives.add(openArchive)
        }
        return openArchive
    }

    private fun releaseOpenArchiveLocked(openArchive: OpenArchive) {
        openArchive.isLeased = false
    }

    private fun closeOpenArchiveLocked(openArchive: OpenArchive) {
        openArchives.remove(openArchive)
        openArchive.isLeased = false
        openArchive.close()
    }

    /**
     * Closes every archive being held open.
     *
     * Called from all three places where what the held archives know stops being true:
     *
     * - [close], because the file system is going away and the archive holds a file handle.
     * - [refresh], because the entry table is being rebuilt and every index a caller holds goes with
     *   it. Leaving an archive open here is how a read returns a *different* entry's bytes with no
     *   error at all.
     * - [addPassword], because the archive was opened with the passwords that existed when it was
     *   opened. Before, the next read picked a new password up because the next read opened the
     *   archive again; holding it open would leave the user typing the right password into a
     *   dialog that can no longer do anything, and the failure looks like the native side ignoring
     *   them.
     */
    private fun closeOpenArchivesLocked() {
        val held = ArrayList(openArchives)
        openArchives.clear()
        for (openArchive in held) {
            openArchive.isLeased = false
            openArchive.close()
        }
    }

    @Throws(IOException::class)
    private fun releaseOpenArchive(openArchive: OpenArchive) {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            releaseOpenArchiveLocked(openArchive)
        }
    }

    /**
     * An archive this file system is holding open, and the channel it was opened on.
     *
     * Leased to one stream at a time, because the native side keeps a single position per archive
     * handle and does not lock it: two threads reading through one handle would interleave into each
     * other's bytes, which is a wrong answer rather than an error.
     */
    private class OpenArchive(
        val archive: ReadableArchive,
        private val closeable: Closeable,
        private val isPooled: Boolean
    ) : Closeable {
        var isLeased = false

        @Throws(IOException::class)
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                archive.close()
            } finally {
                closeable.close()
            }
        }
    }

    /**
     * A stream over one entry of a held-open archive, which returns the archive to the pool when it
     * is closed.
     *
     * Closing the stream closes the entry and nothing else. The archive outlives it, which is the
     * whole difference from before, where closing the stream closed the archive with it.
     */
    private class LeaseInputStream(
        inputStream: InputStream,
        private val openArchive: OpenArchive,
        private val fileSystem: ArchiveFileSystem
    ) : DelegateInputStream(inputStream) {
        private var isClosed = false

        @Throws(IOException::class)
        override fun close() {
            if (isClosed) {
                return
            }
            isClosed = true
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                super.close()
            } finally {
                fileSystem.releaseOpenArchive(openArchive)
            }
        }
    }

    @Throws(IOException::class)
    fun getDirectoryChildren(directory: Path): List<Path> =
        synchronized(lock) {
            ensureEntriesLocked(directory)
            val entry = getEntryLocked(directory)
            if (!entry.isDirectory) {
                throw NotDirectoryException(directory.toString())
            }
            tree!![directory]!!
        }

    @Throws(IOException::class)
    fun readSymbolicLink(link: Path): String =
        synchronized(lock) {
            ensureEntriesLocked(link)
            val entry = getEntryLocked(link)
            if (!entry.isSymbolicLink) {
                throw NotLinkException(link.toString())
            }
            entry.symbolicLinkTarget.orEmpty()
        }

    fun addPassword(password: String) {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            passwords += password
            // The archives held open were opened with the passwords that existed a line ago, and the
            // native side reads them once, at open. Closing them is what makes the next read use the
            // password that was just added; without this the dialog appears to do nothing.
            closeOpenArchivesLocked()
        }
    }

    fun refresh() {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            isRefreshNeeded = true
            // The indices the entries were listed at are about to be rebuilt and may not mean the
            // same thing afterwards, so nothing may keep reading through an archive positioned for
            // the old listing.
            closeOpenArchivesLocked()
        }
    }

    @Throws(IOException::class)
    private fun ensureEntriesLocked(file: Path) {
        if (!isOpen) {
            throw ClosedFileSystemException()
        }
        if (isRefreshNeeded) {
            val listing = try {
                ArchiveReader.readEntries(archiveFile, passwords, rootDirectory)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            }
            entries = listing.entries
            entryIndices = listing.entryIndices
            tree = listing.tree
            isRefreshNeeded = false
        }
    }

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            provider.removeFileSystem(this)
            isRefreshNeeded = false
            entries = null
            entryIndices = null
            tree = null
            // Releases the file handles the held archives are holding. A stream handed out by
            // newInputStream outlives the file system if the caller keeps it, and its archive is
            // closed underneath it; the stream then reports the entry as closed rather than reading
            // the wrong bytes, which is the only safe thing left to say.
            closeOpenArchivesLocked()
            isOpen = false
        }
    }

    override fun isOpen(): Boolean = synchronized(lock) { isOpen }

    override fun isReadOnly(): Boolean = true

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)

    override fun getFileStores(): Iterable<FileStore> {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun supportedFileAttributeViews(): Set<String> =
        ArchiveFileAttributeView.SUPPORTED_NAMES

    override fun getPath(first: String, vararg more: String): ArchivePath {
        val path = ByteStringBuilder(first.toByteString())
            .apply { more.forEach { append(SEPARATOR).append(it.toByteString()) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPath(first: ByteString, vararg more: ByteString): ArchivePath {
        val path = ByteStringBuilder(first)
            .apply { more.forEach { append(SEPARATOR).append(it) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        throw UnsupportedOperationException()
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newWatchService(): WatchService {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as ArchiveFileSystem
        return archiveFile == other.archiveFile
    }

    override fun hashCode(): Int = archiveFile.hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(archiveFile as Parcelable, flags)
    }

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        private val SEPARATOR_BYTE_STRING = SEPARATOR.toByteString()
        private const val SEPARATOR_STRING = SEPARATOR.toInt().toChar().toString()

        /**
         * How many archives this file system keeps open at once.
         *
         * One is all the case this exists for needs: an extraction job reads its entries in order,
         * one at a time, and reusing one archive for all of them is what decodes a solid 7Z block
         * once. The second is there because the app really does read one path from several threads
         * at once - the image view opens three streams for a single file - and one leased archive
         * cannot serve two of them.
         */
        private const val MAX_OPEN_ARCHIVES = 2

        @JvmField
        val CREATOR = object : Parcelable.Creator<ArchiveFileSystem> {
            override fun createFromParcel(source: Parcel): ArchiveFileSystem {
                val archiveFile = source.readParcelable<Parcelable>(Path::class.java.classLoader)
                    as Path
                return ArchiveFileSystemProvider.getOrNewFileSystem(archiveFile)
            }

            override fun newArray(size: Int): Array<ArchiveFileSystem?> = arrayOfNulls(size)
        }
    }
}
