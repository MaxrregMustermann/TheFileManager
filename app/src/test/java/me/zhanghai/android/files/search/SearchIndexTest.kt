/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the search index against a real SQLite, which is the only way to know that FTS5 and the
 * trigram tokenizer actually behave the way the queries assume.
 */
class SearchIndexTest {
    private lateinit var database: TestDatabase

    private val root = "file:///storage/emulated/0"

    @Before
    fun setUp() {
        database = TestDatabase.create()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entity(
        uri: String,
        name: String,
        parentUri: String = root,
        isDirectory: Boolean = false,
        lastModified: Long = 0,
        isHidden: Boolean = false
    ) = FileIndexEntity(
        uri = uri, parentUri = parentUri, name = name, scheme = "file", isDirectory = isDirectory,
        size = 0, lastModifiedTimeMillis = lastModified, isHidden = isHidden
    )

    /** The queries [SearchIndex.search] runs, including its quoting and its short query fallback. */
    private fun search(query: String, root: String = this.root, limit: Int = 100): List<String> {
        val uriPattern = SearchIndexSchema.subtreePattern(root)
        val names = if (query.length >= SearchIndexSchema.MIN_TRIGRAM_QUERY_LENGTH) {
            database.search(SearchIndexSchema.buildMatchQuery(query), uriPattern, limit)
        } else {
            database.searchLike(SearchIndexSchema.containsPattern(query), uriPattern, limit)
        }
        return names.map { it[SearchIndexSchema.COLUMN_NAME] }
    }

    @Test
    fun matchesSubstringOfFileName() {
        database.insert(
            listOf(
                entity("$root/Report.pdf", "Report.pdf"),
                entity("$root/notes.txt", "notes.txt")
            )
        )
        assertEquals(listOf("Report.pdf"), search("repo"))
        assertEquals(listOf("Report.pdf"), search("port"))
        assertEquals(emptyList<String>(), search("pdfx"))
    }

    @Test
    fun matchesCaseInsensitively() {
        database.insert(listOf(entity("$root/Report.PDF", "Report.PDF")))
        assertEquals(listOf("Report.PDF"), search("report"))
        assertEquals(listOf("Report.PDF"), search("REPORT"))
    }

    @Test
    fun matchesAnywhereInANameLongerThanThreeCharacters() {
        database.insert(listOf(entity("$root/a.txt", "a.txt")))
        assertEquals(listOf("a.txt"), search("a.txt"))
        assertEquals(listOf("a.txt"), search("a.tx"))
    }

    @Test
    fun matchesPartOfThePath() {
        // The path is indexed too, so a directory name is searchable.
        database.insert(
            listOf(
                entity("$root/Vacation/photo.jpg", "photo.jpg", parentUri = "$root/Vacation"),
                entity("$root/Work/photo.jpg", "photo.jpg", parentUri = "$root/Work")
            )
        )
        assertEquals(listOf("photo.jpg"), search("Vaca"))
    }

    @Test
    fun findsFilesAnywhereUnderTheRoot() {
        database.insert(
            listOf(
                entity("$root/a/b/c/deep.txt", "deep.txt", parentUri = "$root/a/b/c"),
                entity("file:///other/deep.txt", "deep.txt", parentUri = "file:///other")
            )
        )
        assertEquals(listOf("deep.txt"), search("deep"))
        assertEquals(
            2,
            database.search(
                SearchIndexSchema.buildMatchQuery("deep"),
                SearchIndexSchema.subtreePattern("file:///"), 100
            ).size
        )
    }

    @Test
    fun doesNotMatchASiblingWithTheSamePrefix() {
        database.insert(
            listOf(
                entity("$root/Documents/a.txt", "a.txt", parentUri = "$root/Documents"),
                entity("$root/DocumentsBackup/a.txt", "a.txt", parentUri = "$root/DocumentsBackup")
            )
        )
        assertEquals(listOf("a.txt"), search("a.txt", root = "$root/Documents"))
    }

    @Test
    fun escapesLikeWildcardsInUris() {
        // A URI routinely contains a percent escape and a file name routinely contains an
        // underscore, both of which are LIKE wildcards if they are not escaped.
        database.insert(
            listOf(
                entity("$root/My%20Files/a_b.txt", "a_b.txt", parentUri = "$root/My%20Files"),
                entity("$root/MyXFiles/aXb.txt", "aXb.txt", parentUri = "$root/MyXFiles")
            )
        )
        assertEquals(listOf("a_b.txt"), search("a_b", root = "$root/My%20Files"))
    }

    @Test
    fun upsertReplacesRatherThanDuplicates() {
        database.insert(listOf(entity("$root/a.txt", "a.txt")))
        database.insert(
            listOf(
                entity("$root/a.txt", "a.txt", lastModified = 42),
                entity("$root/b.txt", "b.txt", lastModified = 1)
            )
        )
        assertEquals(2, database.count())
        val rows = database.search(
            SearchIndexSchema.buildMatchQuery("a.txt"), SearchIndexSchema.subtreePattern(root), 100
        )
        assertEquals(1, rows.size)
        assertEquals(42, rows[0].getLong(SearchIndexSchema.COLUMN_LAST_MODIFIED))
    }

    @Test
    fun reindexingRemovesTheOldNameFromTheIndex() {
        database.insert(listOf(entity("$root/1", "old.txt")))
        database.insert(listOf(entity("$root/1", "new.txt")))
        assertEquals(emptyList<String>(), search("old."))
        assertEquals(listOf("new.txt"), search("new."))
    }

    @Test
    fun deletingASubtreeForgetsIt() {
        database.insert(
            listOf(
                entity("$root/Dir/a.txt", "a.txt", parentUri = "$root/Dir"),
                entity("$root/keep.txt", "keep.txt")
            )
        )
        database.deleteSubtree(SearchIndexSchema.subtreePattern("$root/Dir"))
        assertEquals(emptyList<String>(), search("a.txt"))
        assertEquals(listOf("keep.txt"), search("keep.txt"))
    }

    @Test
    fun recentFilesAreNewestFirstAndExcludeDirectories() {
        database.insert(
            listOf(
                entity("$root/1", "old.txt", lastModified = 100),
                entity("$root/2", "new.txt", lastModified = 300),
                entity("$root/3", "mid.txt", lastModified = 200),
                entity("$root/dir", "dir", isDirectory = true, lastModified = 400)
            )
        )
        assertEquals(
            listOf("new.txt", "mid.txt", "old.txt"),
            database.searchRecent(100).map { it[SearchIndexSchema.COLUMN_NAME] }
        )
    }

    @Test
    fun limitsResults() {
        database.insert((1..10).map { entity("$root/a$it.txt", "a$it.txt") })
        assertEquals(10, search(".txt").size)
        assertEquals(2, search(".txt", limit = 2).size)
        // Only a1.txt contains this; a10.txt does not.
        assertEquals(listOf("a1.txt"), search("a1.t"))
        assertEquals(1, database.searchRecent(1).size)
    }

    @Test
    fun shortQueriesFallBackToLike() {
        // The trigram tokenizer cannot index fewer than three characters, and matching has to keep
        // working anyway.
        database.insert(
            listOf(
                entity("$root/ab.txt", "ab.txt"),
                entity("$root/cab.txt", "cab.txt")
            )
        )
        assertEquals(listOf("ab.txt", "cab.txt"), search("ab").sorted())
        assertEquals(listOf("cab.txt"), search("ca"))
    }

    @Test
    fun indexesCharactersOutsideAscii() {
        database.insert(listOf(entity("$root/\u00fcber.txt", "\u00fcber.txt")))
        assertEquals(listOf("\u00fcber.txt"), search("\u00fcber"))
        assertEquals(listOf("\u00fcber.txt"), search("ber"))
    }

    @Test
    fun quotesAreNotSqlSyntaxErrors() {
        database.insert(listOf(entity("$root/a\"b.txt", "a\"b.txt")))
        assertEquals(listOf("a\"b.txt"), search("a\"b.t"))
        assertEquals(listOf("a\"b.txt"), search("b.t"))
    }

    @Test
    fun listsIndexedChildren() {
        database.insert(
            listOf(
                entity("$root/Dir/a.txt", "a.txt", parentUri = "$root/Dir"),
                entity("$root/other.txt", "other.txt")
            )
        )
        assertEquals(
            listOf("$root/Dir/a.txt"), database.childUris("$root/Dir")
        )
    }

    @Test
    fun storesEveryColumn() {
        database.insert(
            listOf(
                entity(
                    "$root/Dir/a.txt", "a.txt", parentUri = "$root/Dir", isDirectory = false,
                    lastModified = 1234, isHidden = true
                )
            )
        )
        val row = database.query(SearchIndexSchema.SEARCH_RECENT, TestDatabase.ENTITY_COLUMNS, 1) {
            it.single()
        }
        assertEquals("$root/Dir/a.txt", row[SearchIndexSchema.COLUMN_URI])
        assertEquals("$root/Dir", row[SearchIndexSchema.COLUMN_PARENT_URI])
        assertEquals("a.txt", row[SearchIndexSchema.COLUMN_NAME])
        assertEquals("file", row[SearchIndexSchema.COLUMN_SCHEME])
        assertFalse(row.getBoolean(SearchIndexSchema.COLUMN_IS_DIRECTORY))
        assertEquals(1234, row.getLong(SearchIndexSchema.COLUMN_LAST_MODIFIED))
        assertTrue(row.getBoolean(SearchIndexSchema.COLUMN_IS_HIDDEN))
    }

    @Test
    fun keepsTheIndexInStepWithTheTable() {
        database.insert(listOf(entity("$root/a.txt", "Report.pdf")))
        database.insert(listOf(entity("$root/a.txt", "Summary.pdf", lastModified = 5)))
        database.deleteSubtree(SearchIndexSchema.subtreePattern(root, includeSelf = true))
        // A stale FTS index would make this report a mismatch instead of passing.
        database.query("INSERT INTO ${SearchIndexSchema.FTS_TABLE}(${SearchIndexSchema.FTS_TABLE}) " +
            "VALUES('integrity-check')", emptyList()) { }
    }
}