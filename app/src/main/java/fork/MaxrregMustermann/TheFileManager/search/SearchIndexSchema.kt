/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.search

/**
 * The SQL behind the search index.
 *
 * Room only knows how to declare FTS3 and FTS4 tables, while fast substring search needs FTS5 with
 * its trigram tokenizer. That tokenizer turns every three character window of a string into a
 * token, which turns a `contains()` style substring match into an index lookup instead of a table
 * scan.
 *
 * Every statement is a `const val` because Room reads the value of a `@Query` annotation at compile
 * time, which means the expression building them has to be constant folded.
 *
 * They live here rather than inline in the database so that the unit tests can run them against a
 * real SQLite, which is the only way to be sure FTS5 and the trigram tokenizer behave as expected.
 */
object SearchIndexSchema {
    const val TABLE = "file_index"
    const val COLUMN_ID = "id"
    const val COLUMN_URI = "uri"
    const val COLUMN_PARENT_URI = "parent_uri"
    const val COLUMN_NAME = "name"
    const val COLUMN_SCHEME = "scheme"
    const val COLUMN_IS_DIRECTORY = "is_directory"
    const val COLUMN_SIZE = "size"
    const val COLUMN_LAST_MODIFIED = "last_modified"
    const val COLUMN_IS_HIDDEN = "is_hidden"

    /** The trigram tokenizer needs three characters to build a single token. */
    const val MIN_TRIGRAM_QUERY_LENGTH = 3

    const val LIKE_ESCAPE = "\\"

    /** [LIKE_ESCAPE] as a [Char], for escaping character by character. */
    private const val LIKE_ESCAPE_CHAR = '\\'

    const val SELECT_COLUMNS = "$COLUMN_ID, $COLUMN_URI, $COLUMN_PARENT_URI, $COLUMN_NAME, " +
        "$COLUMN_SCHEME, $COLUMN_IS_DIRECTORY, $COLUMN_SIZE, $COLUMN_LAST_MODIFIED, " +
        "$COLUMN_IS_HIDDEN"

    /**
     * `remove_diacritics 0` reproduces `String.contains(query, ignoreCase = true)`, which is what
     * the walk based search did: case is folded, diacritics are not.
     *
     * The trigram tokenizer rejects any other argument, so this string must stay in sync with what
     * SQLite accepts: `case_sensitive` and `remove_diacritics` are the only two.
     */
    const val TOKENIZER = "trigram remove_diacritics 0"

    const val FTS_TABLE = "file_index_fts"

    /** An external content table, so the index does not keep a second copy of every file name. */
    const val CREATE_FTS_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts5(" +
            "$COLUMN_NAME, $COLUMN_URI, " +
            "content='$TABLE', content_rowid='$COLUMN_ID', tokenize='$TOKENIZER')"

    const val CREATE_FTS_INSERT_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TABLE}_insert AFTER INSERT ON $TABLE BEGIN " +
            "INSERT INTO $FTS_TABLE(rowid, $COLUMN_NAME, $COLUMN_URI) " +
            "VALUES (new.$COLUMN_ID, new.$COLUMN_NAME, new.$COLUMN_URI); END"

    /**
     * An external content FTS5 table is updated with the special `'delete'` command, which needs the
     * values the row had rather than the ones it has.
     */
    const val CREATE_FTS_DELETE_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TABLE}_delete AFTER DELETE ON $TABLE BEGIN " +
            "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, $COLUMN_NAME, $COLUMN_URI) " +
            "VALUES ('delete', old.$COLUMN_ID, old.$COLUMN_NAME, old.$COLUMN_URI); END"

    const val CREATE_FTS_UPDATE_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS ${FTS_TABLE}_update AFTER UPDATE ON $TABLE BEGIN " +
            "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, $COLUMN_NAME, $COLUMN_URI) " +
            "VALUES ('delete', old.$COLUMN_ID, old.$COLUMN_NAME, old.$COLUMN_URI); " +
            "INSERT INTO $FTS_TABLE(rowid, $COLUMN_NAME, $COLUMN_URI) " +
            "VALUES (new.$COLUMN_ID, new.$COLUMN_NAME, new.$COLUMN_URI); END"

    val CREATE_FTS_STATEMENTS = listOf(
        CREATE_FTS_TABLE, CREATE_FTS_INSERT_TRIGGER, CREATE_FTS_DELETE_TRIGGER,
        CREATE_FTS_UPDATE_TRIGGER
    )

    /**
     * Repopulates the FTS index from the table it is an index of.
     *
     * FTS5 provides this for exactly the case where the index and its content table disagree, which
     * is what happens when the table is created over a content table that already has rows. Without
     * it every later delete fails with "database disk image is malformed", because the delete
     * trigger's `delete` command looks for a row the index never had.
     */
    const val REBUILD_FTS_INDEX =
        "INSERT INTO $FTS_TABLE($FTS_TABLE) VALUES('rebuild')"

    /** The number of bound arguments one row of an upsert takes. The id is left to SQLite. */
    const val UPSERT_ARGUMENT_COUNT = 8

    private const val UPSERT_COLUMNS = "$COLUMN_URI, $COLUMN_PARENT_URI, $COLUMN_NAME, " +
        "$COLUMN_SCHEME, $COLUMN_IS_DIRECTORY, $COLUMN_SIZE, $COLUMN_LAST_MODIFIED, " +
        "$COLUMN_IS_HIDDEN"

    private const val UPSERT_VALUE_PLACEHOLDERS = "(?, ?, ?, ?, ?, ?, ?, ?)"

    private const val UPSERT_ASSIGNMENTS =
        "$COLUMN_PARENT_URI = excluded.$COLUMN_PARENT_URI, " +
            "$COLUMN_NAME = excluded.$COLUMN_NAME, " +
            "$COLUMN_SCHEME = excluded.$COLUMN_SCHEME, " +
            "$COLUMN_IS_DIRECTORY = excluded.$COLUMN_IS_DIRECTORY, " +
            "$COLUMN_SIZE = excluded.$COLUMN_SIZE, " +
            "$COLUMN_LAST_MODIFIED = excluded.$COLUMN_LAST_MODIFIED, " +
            "$COLUMN_IS_HIDDEN = excluded.$COLUMN_IS_HIDDEN"

    /**
     * Inserts [times] rows, replacing any row with the same URI, so that re-indexing a directory
     * does not have to delete it first.
     *
     * An upsert rather than `INSERT OR REPLACE`, because replacing deletes the row and inserts a new
     * one under a new id, which throws away the row id the FTS5 index is keyed on and makes the
     * triggers rewrite every entry twice.
     */
    fun buildUpsertSql(times: Int): String {
        require(times > 0) { "times must be positive but was $times" }
        val placeholders = List(times) { UPSERT_VALUE_PLACEHOLDERS }.joinToString(",")
        return "INSERT INTO $TABLE ($UPSERT_COLUMNS) VALUES $placeholders " +
            "ON CONFLICT($COLUMN_URI) DO UPDATE SET $UPSERT_ASSIGNMENTS"
    }

    /**
     * Substring match on the file name, restricted to the subtree matched by `uriPattern`.
     *
     * Room compiles and runs every query it can, which the FTS5 table makes impossible here because
     * it is created at runtime, so the queries are annotated with [SkipQueryVerification] and are
     * covered by unit tests against a real SQLite instead.
     */
    const val SEARCH =
        "SELECT $SELECT_COLUMNS FROM $TABLE WHERE $COLUMN_ID IN " +
            "(SELECT rowid FROM $FTS_TABLE WHERE $FTS_TABLE MATCH :matchQuery) AND " +
            "$COLUMN_URI LIKE :uriPattern ESCAPE '$LIKE_ESCAPE' " +
            "ORDER BY $COLUMN_LAST_MODIFIED DESC, $COLUMN_ID ASC LIMIT :limit"

    /**
     * Fallback for queries shorter than [MIN_TRIGRAM_QUERY_LENGTH] characters, which the trigram
     * tokenizer cannot index. Scanning the index is still far cheaper than walking a file system,
     * let alone a network share.
     */
    const val SEARCH_LIKE =
        "SELECT $SELECT_COLUMNS FROM $TABLE WHERE " +
            "$COLUMN_NAME LIKE :namePattern ESCAPE '$LIKE_ESCAPE' AND " +
            "$COLUMN_URI LIKE :uriPattern ESCAPE '$LIKE_ESCAPE' " +
            "ORDER BY $COLUMN_LAST_MODIFIED DESC, $COLUMN_ID ASC LIMIT :limit"

    /** The most recently modified indexed files, newest first. */
    const val SEARCH_RECENT =
        "SELECT $SELECT_COLUMNS FROM $TABLE WHERE $COLUMN_IS_DIRECTORY = 0 " +
            "ORDER BY $COLUMN_LAST_MODIFIED DESC, $COLUMN_ID ASC LIMIT :limit"

    /** The URIs the index already holds for the direct children of a directory. */
    const val SEARCH_CHILD_URIS =
        "SELECT $COLUMN_URI FROM $TABLE WHERE $COLUMN_PARENT_URI = :parentUri"

    const val DELETE_SUBTREE =
        "DELETE FROM $TABLE WHERE $COLUMN_URI LIKE :uriPattern ESCAPE '$LIKE_ESCAPE'"

    const val COUNT = "SELECT COUNT(*) FROM $TABLE"

    /** Counts a table by name, for checking whether the FTS table exists yet. */
    const val COUNT_TABLE =
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = :name"

    const val COUNT_COLUMN = "COUNT(*)"

    const val CLEAR = "DELETE FROM $TABLE"

    /**
     * Quotes [query] as an FTS5 string literal. FTS5 has no escape character inside string
     * literals, so a double quote is written twice.
     */
    fun buildMatchQuery(query: String): String =
        if (query.isEmpty()) "" else "\"" + query.replace("\"", "\"\"") + "\""

    /**
     * Escapes the LIKE wildcards in [value]. File names routinely contain `_`, and a URI routinely
     * contains `%20`, so escaping is not optional.
     */
    fun escapeLike(value: String): String =
        buildString(value.length) {
            for (character in value) {
                if (character == LIKE_ESCAPE_CHAR || character == '%' || character == '_') {
                    append(LIKE_ESCAPE)
                }
                append(character)
            }
        }

    /**
     * The LIKE pattern matching everything below [uri], and optionally [uri] itself.
     *
     * A root URI never ends in a slash, so appending one cannot match a sibling whose name merely
     * starts with the same characters.
     */
    fun subtreePattern(uri: String, includeSelf: Boolean = false): String {
        val prefix = escapeLike(if (uri.endsWith("/")) uri else "$uri/")
        return if (includeSelf) "${escapeLike(uri)}|$prefix%" else "$prefix%"
    }

    /** The LIKE pattern matching [value] anywhere inside a string. */
    fun containsPattern(value: String): String = "%${escapeLike(value)}%"
}