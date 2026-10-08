/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed file.
 *
 * The path is stored as a URI string rather than as an encoded tree, because a search has to match
 * a whole subtree, and `uri LIKE 'root/%'` with an index on the leading columns is the cheapest way
 * to express that.
 */
@Entity(
    tableName = SearchIndexSchema.TABLE,
    indices = [
        Index(value = [SearchIndexSchema.COLUMN_URI], unique = true),
        Index(value = [SearchIndexSchema.COLUMN_PARENT_URI]),
        Index(value = [SearchIndexSchema.COLUMN_LAST_MODIFIED]),
    ]
)
data class FileIndexEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = SearchIndexSchema.COLUMN_ID) val id: Long = 0,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_URI) val uri: String,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_PARENT_URI) val parentUri: String,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_NAME) val name: String,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_SCHEME) val scheme: String,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_IS_DIRECTORY) val isDirectory: Boolean,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_SIZE) val size: Long,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_LAST_MODIFIED) val lastModifiedTimeMillis: Long,
    @ColumnInfo(name = SearchIndexSchema.COLUMN_IS_HIDDEN) val isHidden: Boolean
) {
    /** The arguments for [SearchIndexSchema.UPSERT], which leaves [id] to SQLite. */
    fun toUpsertArguments(): Array<Any?> =
        arrayOf(uri, parentUri, name, scheme, isDirectory, size, lastModifiedTimeMillis, isHidden)
}