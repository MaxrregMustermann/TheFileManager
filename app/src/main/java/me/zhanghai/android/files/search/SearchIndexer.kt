/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import android.os.AsyncTask
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.isHidden
import me.zhanghai.android.files.provider.common.readAttributes

/**
 * Walks a file tree and feeds [SearchIndex].
 *
 * Everything is best effort. A file that cannot be read is skipped rather than aborting the scan,
 * and a scan gives up after [MAX_ENTRIES_PER_RUN] entries so that it cannot monopolize the disk for
 * the whole session.
 */
object SearchIndexer {
    /**
     * Enough to cover an ordinary home directory in one session while leaving the disk alone
     * quickly.
     */
    const val MAX_ENTRIES_PER_RUN = 20_000

    /** The roots a background scan has already been started for. */
    private val requestedRoots = mutableSetOf<String>()

    /**
     * Replaces everything the index holds about the direct children of [directory].
     *
     * This is the cheap, always correct part of indexing: one directory listing, which the file
     * list already performs anyway, so the top level of the index cannot drift from what the user
     * is looking at.
     */
    fun indexDirectory(directory: Path) {
        if (!SearchIndex.isInitialized) {
            return
        }
        val parentUri = directory.toIndexUri()
        val entities = readDirectory(directory, parentUri) ?: return
        val presentUris = entities.mapTo(mutableSetOf()) { it.uri }
        for (old in SearchIndex.indexedChildUris(directory)) {
            if (old !in presentUris) {
                SearchIndex.deleteSubtreeUri(old)
            }
        }
        SearchIndex.upsert(entities)
        indexTreeInBackground(directory, AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService)
    }

    /**
     * Indexes [root] and everything below it, breadth first, stopping once [maxEntries] have been
     * written. Returns whether the whole subtree made it into the index.
     *
     * Breadth first on purpose: a user who searches expects the files just below the root to be
     * searchable first, and a partial scan that has only reached depth two is far more useful than
     * one that walked a single deep branch to the end.
     */
    fun indexTree(root: Path, maxEntries: Int = MAX_ENTRIES_PER_RUN): Boolean {
        if (!SearchIndex.isInitialized) {
            return false
        }
        var budget = maxEntries
        val queue = ArrayDeque<Path>()
        queue.addLast(root)
        while (queue.isNotEmpty() && budget > 0) {
            val directory = queue.removeFirst()
            val entities = readDirectory(directory, directory.toIndexUri()) ?: continue
            SearchIndex.upsert(entities)
            budget -= entities.size
            for (entity in entities) {
                if (entity.isDirectory) {
                    entity.uri.toIndexPath()?.let { queue.addLast(it) }
                }
            }
        }
        val isComplete = queue.isEmpty()
        if (isComplete) {
            SearchIndex.markCovered(root)
        }
        return isComplete
    }

    /**
     * Runs [indexTree] in the background, at most once per root per process, so that a search
     * below [root] can stop walking the file system from then on.
     */
    fun indexTreeInBackground(root: Path, executor: ExecutorService) {
        if (!SearchIndex.isInitialized) {
            return
        }
        synchronized(this) {
            if (!requestedRoots.add(root.toIndexUri())) {
                return
            }
        }
        executor.submit {
            try {
                indexTree(root)
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: RuntimeException) {
                e.printStackTrace()
            }
        }
    }

    /** Reads one directory, returning null when it cannot be read at all. */
    private fun readDirectory(directory: Path, parentUri: String): List<FileIndexEntity>? {
        val entities = mutableListOf<FileIndexEntity>()
        try {
            Files.newDirectoryStream(directory).use { directoryStream ->
                for (path in directoryStream) {
                    if (Thread.interrupted()) {
                        throw InterruptedIOException()
                    }
                    path.toFileIndexEntity(parentUri)?.let { entities.add(it) }
                }
            }
        } catch (e: DirectoryIteratorException) {
            e.printStackTrace()
            return null
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
        return entities
    }

}

/** Builds an index entry, or null when the file cannot be read. */
private fun Path.toFileIndexEntity(parentUri: String): FileIndexEntity? {
    val attributes = try {
        readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
    val uri = toIndexUri()
    return FileIndexEntity(
        uri = uri,
        parentUri = parentUri,
        name = fileName?.toString() ?: uri,
        scheme = uri.substringBefore("://", uri),
        isDirectory = attributes.isDirectory,
        size = attributes.size(),
        lastModifiedTimeMillis = attributes.lastModifiedTime().toMillis(),
        isHidden = try {
            isHidden
        } catch (e: IOException) {
            false
        }
    )
}
