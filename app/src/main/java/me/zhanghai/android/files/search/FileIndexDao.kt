/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Every method here touches the disk, so none of them may be called from the main thread. The
 * callers, [SearchIndex] and [SearchIndexer], all run on background executors.
 */
@Dao
interface FileIndexDao {
    /** Substring match on the file name, using the FTS5 trigram index. */
    @SkipQueryVerification
    @Query(SearchIndexSchema.SEARCH)
    fun search(matchQuery: String, uriPattern: String, limit: Int): List<FileIndexEntity>

    /** Fallback for queries too short for the trigram tokenizer. */
    @SkipQueryVerification
    @Query(SearchIndexSchema.SEARCH_LIKE)
    fun searchLike(namePattern: String, uriPattern: String, limit: Int): List<FileIndexEntity>

    @SkipQueryVerification
    @Query(SearchIndexSchema.SEARCH_RECENT)
    fun searchRecent(limit: Int): List<FileIndexEntity>

    @SkipQueryVerification
    @Query(SearchIndexSchema.SEARCH_CHILD_URIS)
    fun searchChildUris(parentUri: String): List<String>

    @SkipQueryVerification
    @Query(SearchIndexSchema.DELETE_SUBTREE)
    fun deleteSubtree(uriPattern: String): Int

    @Query(SearchIndexSchema.COUNT)
    fun count(): Int

    @Query(SearchIndexSchema.CLEAR)
    fun clear()

    @RawQuery
    fun execute(query: SupportSQLiteQuery): Int

    /**
     * Runs a statement for its effect rather than for a row count.
     *
     * Room's raw-query form insists on a non-void return, and its `Int` form reports the number of
     * affected rows, which a `CREATE TABLE` does not have. A transaction of one statement reports
     * zero rows affected, which is all this needs: it exists to run the statement, not to count
     * anything.
     */
    @Transaction
    fun executeForEffect(query: SupportSQLiteQuery) {
        execute(query)
    }

    /** How many tables in the database are called [name]. */
    @SkipQueryVerification
    @Query(SearchIndexSchema.COUNT_TABLE)
    fun countTable(name: String): Int

    /** Replaces [entities] in a single transaction. */
    @Transaction
    fun upsert(entities: List<FileIndexEntity>) {
        if (entities.isEmpty()) {
            return
        }
        val arguments = ArrayList<Any?>(entities.size * SearchIndexSchema.UPSERT_ARGUMENT_COUNT)
        for (entity in entities) {
            arguments.addAll(entity.toUpsertArguments().toList())
        }
        execute(
            SimpleSQLiteQuery(
                SearchIndexSchema.buildUpsertSql(entities.size), arguments.toTypedArray()
            )
        )
    }
}