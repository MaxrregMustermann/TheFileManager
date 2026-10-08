/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchIndexSchemaTest {
    @Test
    fun quotesAnFts5StringLiteral() {
        assertEquals("\"abc\"", SearchIndexSchema.buildMatchQuery("abc"))
        assertEquals("", SearchIndexSchema.buildMatchQuery(""))
        assertEquals("\"a\"\"b\"", SearchIndexSchema.buildMatchQuery("a\"b"))
        assertEquals("\"\"\"\"\"\"", SearchIndexSchema.buildMatchQuery("\"\""))
    }

    @Test
    fun escapesLikeWildcards() {
        assertEquals("a\\%b", SearchIndexSchema.escapeLike("a%b"))
        assertEquals("a\\_b", SearchIndexSchema.escapeLike("a_b"))
        assertEquals("a\\\\b", SearchIndexSchema.escapeLike("a\\b"))
        assertEquals("plain", SearchIndexSchema.escapeLike("plain"))
    }

    @Test
    fun buildsASubtreePattern() {
        // The trailing slash is a real separator, not part of the value, so it is not escaped.
        assertEquals(
            "file\\%3A\\%2F\\%2F\\%2Fstorage/%",
            SearchIndexSchema.subtreePattern("file%3A%2F%2F%2Fstorage")
        )
        assertEquals(
            "file\\%3A\\%2F\\%2F\\%2Fstorage/%",
            SearchIndexSchema.subtreePattern("file%3A%2F%2F%2Fstorage/")
        )
        assertEquals(
            "file\\%3A\\%2F\\%2F\\%2Fstorage|file\\%3A\\%2F\\%2F\\%2Fstorage/%",
            SearchIndexSchema.subtreePattern("file%3A%2F%2F%2Fstorage", includeSelf = true)
        )
    }

    @Test
    fun buildsAContainsPattern() {
        assertEquals("%abc%", SearchIndexSchema.containsPattern("abc"))
        assertEquals("%100\\%%", SearchIndexSchema.containsPattern("100%"))
        assertEquals("%a\\_b%", SearchIndexSchema.containsPattern("a_b"))
    }

    @Test
    fun buildsABatchedUpsert() {
        val sql = SearchIndexSchema.buildUpsertSql(2)
        assertEquals(
            "INSERT INTO file_index (uri, parent_uri, name, scheme, is_directory, size, " +
                "last_modified, is_hidden) VALUES (?, ?, ?, ?, ?, ?, ?, ?),(?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uri) DO UPDATE SET parent_uri = excluded.parent_uri, " +
                "name = excluded.name, scheme = excluded.scheme, " +
                "is_directory = excluded.is_directory, size = excluded.size, " +
                "last_modified = excluded.last_modified, is_hidden = excluded.is_hidden",
            sql
        )
    }

    @Test
    fun rejectsAnEmptyUpsert() {
        assertThrows(IllegalArgumentException::class.java) {
            SearchIndexSchema.buildUpsertSql(0)
        }
    }
}