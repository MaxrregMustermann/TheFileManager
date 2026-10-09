/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(entities = [FileIndexEntity::class], version = 1, exportSchema = false)
abstract class SearchIndexDatabase : RoomDatabase() {
    abstract fun fileIndexDao(): FileIndexDao

    /**
     * Room cannot declare FTS5 tables, so the index and the triggers that keep it in step with
     * [SearchIndexSchema.TABLE] are created separately from Room's own tables.
     */
    object Callback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            for (statement in SearchIndexSchema.CREATE_FTS_STATEMENTS) {
                db.execSQL(statement)
            }
        }
    }

    /**
     * Creates the FTS table and its triggers if they are not there already, and makes the index match
     * the rows that are already in [SearchIndexSchema.TABLE].
     *
     * The rebuild is not optional. A database whose rows predate the triggers has an index that does
     * not know about them, and FTS5 treats that as corruption: deleting such a row fails with
     * "database disk image is malformed", because the delete trigger cannot find the row it is meant
     * to remove. Rebuilding is what FTS5 provides for exactly this, and it is what keeps deletes
     * working.
     */
    fun ensureFtsIndex() {
        try {
            if (hasFtsTable()) {
                return
            }
            for (statement in SearchIndexSchema.CREATE_FTS_STATEMENTS) {
                fileIndexDao().executeForEffect(SimpleSQLiteQuery(statement))
            }
            fileIndexDao().executeForEffect(SimpleSQLiteQuery(SearchIndexSchema.REBUILD_FTS_INDEX))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasFtsTable(): Boolean =
        try {
            fileIndexDao().countTable(SearchIndexSchema.FTS_TABLE) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

    companion object {
        const val NAME = "search-index.db"

        fun create(context: Context): SearchIndexDatabase =
            Room.databaseBuilder(context.applicationContext, SearchIndexDatabase::class.java, NAME)
                // The SQLite that Android itself provides predates FTS5 on every release older than
                // Android 11, so the index would not exist at all without a bundled SQLite.
                .setDriver(BundledSQLiteDriver())
                .addCallback(Callback)
                .build()
    }
}