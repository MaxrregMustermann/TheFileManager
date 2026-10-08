/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(entities = [FileIndexEntity::class], version = 1, exportSchema = false)
abstract class SearchIndexDatabase : RoomDatabase() {
    abstract fun fileIndexDao(): FileIndexDao

    /**
     * Room cannot declare FTS5 tables, so the index and the triggers that keep it in step with
     * [SearchIndexSchema.TABLE] are created right after Room creates its own tables.
     */
    object Callback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            for (statement in SearchIndexSchema.CREATE_FTS_STATEMENTS) {
                db.execSQL(statement)
            }
        }
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