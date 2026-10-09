/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import android.content.Context
import java8.nio.file.Path
import java8.nio.file.Paths
import java.net.URI
import me.zhanghai.android.files.storage.createOrLog

/**
 * An index of file names and paths, so that search can answer from SQLite instead of walking a file
 * system, which is slow to begin with and slower still over a network share.
 *
 * Every method here touches the disk, so none of them may be called from the main thread.
 */
object SearchIndex {
    const val DEFAULT_SEARCH_LIMIT = 1000

    @Volatile
    private var searchIndexDatabase: SearchIndexDatabase? = null

    private val fileIndexDao: FileIndexDao?
        get() = searchIndexDatabase?.fileIndexDao()

    private val coverage = IndexCoverage()

    /**
     * Opens the database. Until this has been called the index is unavailable and callers should
     * fall back to walking the file system.
     */
    fun initialize(context: Context) {
        if (searchIndexDatabase != null) {
            return
        }
        synchronized(this) {
            if (searchIndexDatabase == null) {
                searchIndexDatabase = SearchIndexDatabase.create(context)
                coverage.clear()
            }
        }
    }

    /**
     * Whether the index can answer a query, creating the FTS table if it is missing.
     *
     * The table is created by a Room callback that only runs when the database file is first created,
     * so a file left behind by an earlier version of the app opens without it, and every query
     * against it fails. `SearchIndex` swallows those failures, which is why the symptom was a search
     * that silently found nothing rather than an error. Repairing here means the check happens once
     * per process, on the first query that needs it.
     */
    val isInitialized: Boolean
        get() {
            val database = searchIndexDatabase ?: return false
            database.ensureFtsIndex()
            return true
        }

    /**
     * The indexed descendants of [root] whose name or path contains [query], ignoring case, newest
     * first.
     */
    fun search(root: Path, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Path> {
        if (!isInitialized) {
            return emptyList()
        }
        val fileIndexDao = fileIndexDao ?: return emptyList()
        if (query.isEmpty() || limit <= 0) {
            return emptyList()
        }
        val uriPattern = SearchIndexSchema.subtreePattern(root.toIndexUri())
        val entities = try {
            if (query.length >= SearchIndexSchema.MIN_TRIGRAM_QUERY_LENGTH) {
                fileIndexDao.search(SearchIndexSchema.buildMatchQuery(query), uriPattern, limit)
            } else {
                fileIndexDao.searchLike(
                    SearchIndexSchema.containsPattern(query), uriPattern, limit
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        return entities.mapNotNull { it.uri.toIndexPath() }
    }

    /** The most recently modified indexed files, newest first. */
    fun searchRecent(limit: Int = DEFAULT_SEARCH_LIMIT): List<Path> {
        if (!isInitialized) {
            return emptyList()
        }
        val fileIndexDao = fileIndexDao ?: return emptyList()
        if (limit <= 0) {
            return emptyList()
        }
        val entities = try {
            fileIndexDao.searchRecent(limit)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        return entities.mapNotNull { it.uri.toIndexPath() }
    }

    fun upsert(entities: List<FileIndexEntity>) {
        val fileIndexDao = fileIndexDao ?: return
        try {
            fileIndexDao.upsert(entities)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** The URIs already indexed for the direct children of [directory]. */
    fun indexedChildUris(directory: Path): List<String> {
        val fileIndexDao = fileIndexDao ?: return emptyList()
        return try {
            fileIndexDao.searchChildUris(directory.toIndexUri())
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Forgets everything at or below [uri]. */
    fun deleteSubtreeUri(uri: String) {
        val fileIndexDao = fileIndexDao ?: return
        // Anything below a path that just lost its index entries is no longer covered.
        coverage.remove(uri)
        try {
            fileIndexDao.deleteSubtree(SearchIndexSchema.subtreePattern(uri, includeSelf = true))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clear() {
        val fileIndexDao = fileIndexDao ?: return
        coverage.clear()
        try {
            fileIndexDao.clear()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun count(): Int = try {
        fileIndexDao?.count() ?: 0
    } catch (e: Exception) {
        e.printStackTrace()
        0
    }

    /**
     * Records that everything below [root] is in the index, so that a search there does not also
     * have to walk the file system.
     */
    fun markCovered(root: Path) {
        coverage.add(root.toIndexUri())
    }

    fun isCovered(root: Path): Boolean =
        isInitialized && coverage.contains(root.toIndexUri())
}

/** The URI a path is stored under, and the only stable identity a provider gives us. */
fun Path.toIndexUri(): String = toUri().toString()

fun String.toIndexPath(): Path? = URI::class.createOrLog(this)?.let { Paths.get(it) }