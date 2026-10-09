/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * A real SQLite, running the very statements the app runs.
 *
 * FTS5 and its trigram tokenizer are compile time options in SQLite, and the behaviour that matters
 * here, such as what a two character query does, cannot be faked. Running against the same bundled
 * SQLite the app ships is the only way for these tests to mean anything.
 *
 * Column names are passed in rather than read back from the statement, because the Android build of
 * `androidx.sqlite` that unit tests compile against does not expose the driver's column metadata.
 */
class TestDatabase private constructor(private val connection: SQLiteConnection) : AutoCloseable {
    /**
     * A row of a result set, addressed by column name.
     *
     * The values are read while the statement is still open, because the caller gets the rows after
     * the statement has been closed.
     */
    class Row private constructor(private val values: Map<String, String>) {
        operator fun get(column: String): String = values.getValue(column)

        fun getLong(column: String): Long = get(column).toLong()

        fun getBoolean(column: String): Boolean = getLong(column) != 0L

        override fun toString(): String =
            values.entries.joinToString(", ") { "${it.key}=${it.value}" }

        companion object {
            fun read(statement: SQLiteStatement, columnNames: List<String>): Row {
                val values = LinkedHashMap<String, String>(columnNames.size)
                for (index in columnNames.indices) {
                    values[columnNames[index]] = statement.getText(index)
                }
                return Row(values)
            }
        }
    }

    fun <T> query(
        sql: String,
        columnNames: List<String>,
        vararg arguments: Any?,
        block: (List<Row>) -> T
    ): T {
        val dbg = toPositionalParameters(sql)
        connection.prepare(dbg).use { statement ->
            bind(statement, arguments)
            val rows = mutableListOf<Row>()
            while (statement.step()) {
                rows.add(Row.read(statement, columnNames))
            }
            return block(rows)
        }
    }

    fun insert(entities: List<FileIndexEntity>) {
        if (entities.isEmpty()) {
            return
        }
        val arguments = mutableListOf<Any?>()
        for (entity in entities) {
            arguments.addAll(entity.toUpsertArguments().toList())
        }
        query(SearchIndexSchema.buildUpsertSql(entities.size), ENTITY_COLUMNS, *arguments.toTypedArray()) { }
    }

    fun search(matchQuery: String, uriPattern: String, limit: Int): List<Row> =
        query(
            SearchIndexSchema.SEARCH, ENTITY_COLUMNS, matchQuery, uriPattern, limit
        ) { it }

    fun searchLike(namePattern: String, uriPattern: String, limit: Int): List<Row> =
        query(
            SearchIndexSchema.SEARCH_LIKE, ENTITY_COLUMNS, namePattern, uriPattern, limit
        ) { it }

    fun searchRecent(limit: Int): List<Row> =
        query(SearchIndexSchema.SEARCH_RECENT, ENTITY_COLUMNS, limit) { it }

    fun childUris(parentUri: String): List<String> =
        query(SearchIndexSchema.SEARCH_CHILD_URIS, URI_COLUMNS, parentUri) { rows ->
            rows.map { it[SearchIndexSchema.COLUMN_URI] }
        }

    fun clear() {
        query(SearchIndexSchema.CLEAR, ENTITY_COLUMNS) { }
    }

    fun deleteSubtree(uriPattern: String): Int =
        query(SearchIndexSchema.DELETE_SUBTREE, ENTITY_COLUMNS, uriPattern) { it.size }

    fun count(): Int =
        query(SearchIndexSchema.COUNT, listOf(SearchIndexSchema.COUNT_COLUMN)) {
            it.single().getLong(SearchIndexSchema.COUNT_COLUMN).toInt()
        }

    fun hasFtsTable(): Boolean =
        query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf("present"), SearchIndexSchema.FTS_TABLE
        ) { it.isNotEmpty() }

    override fun close() {
        connection.close()
    }

    /**
     * Room binds by name but the driver only binds by position. Every statement here lists its named
     * parameters in the same order as the values are passed, so replacing the names with
     * placeholders preserves the mapping.
     */
    private fun toPositionalParameters(sql: String): String =
        sql.replace(NAMED_PARAMETER_REGEX, "?")

    private fun bind(statement: SQLiteStatement, arguments: Array<out Any?>) {
        for (index in arguments.indices) {
            val argument = arguments[index]
            val position = index + 1
            when (argument) {
                null -> statement.bindNull(position)
                is String -> statement.bindText(position, argument)
                is Long -> statement.bindLong(position, argument)
                is Int -> statement.bindInt(position, argument)
                is Boolean -> statement.bindBoolean(position, argument)
                is ByteArray -> statement.bindBlob(position, argument)
                else -> throw IllegalArgumentException("Unsupported argument $argument")
            }
        }
    }

    companion object {
        private val NAMED_PARAMETER_REGEX = Regex(":[a-zA-Z][a-zA-Z0-9_]*")

        val ENTITY_COLUMNS = SearchIndexSchema.SELECT_COLUMNS.split(", ")

        private val URI_COLUMNS = listOf(SearchIndexSchema.COLUMN_URI)

        /**
         * Creates the table and indices but not the FTS table or its triggers.
         *
         * Models a database file left behind by a version of the app whose schema did not have the
         * FTS table, which is what `ensureFtsIndex` exists to repair. A search against it has to
         * fail loudly rather than quietly return nothing, which is the bug this reproduces.
         */
        fun createWithoutFtsIndex(): TestDatabase {
            val connection = BundledSQLiteDriver().open(":memory:")
            for (statement in CREATE_TABLE_STATEMENTS) {
                connection.prepare(statement).use { it.step() }
            }
            return TestDatabase(connection)
        }

        /** What the app runs to repair a database whose FTS table is missing or out of step. */
        fun ensureFtsIndex(database: TestDatabase) {
            if (database.hasFtsTable()) {
                return
            }
            for (statement in SearchIndexSchema.CREATE_FTS_STATEMENTS) {
                database.connection.prepare(statement).use { it.step() }
            }
            database.connection.prepare(SearchIndexSchema.REBUILD_FTS_INDEX).use { it.step() }
        }

        fun create(): TestDatabase {
            val connection = BundledSQLiteDriver().open(":memory:")
            // Room creates this table from the @Entity declaration. Only the column names matter to
            // the FTS5 statements below, and those are the shared constants they both use.
            for (statement in CREATE_TABLE_STATEMENTS) {
                connection.prepare(statement).use { it.step() }
            }
            for (statement in SearchIndexSchema.CREATE_FTS_STATEMENTS) {
                connection.prepare(statement).use { it.step() }
            }
            return TestDatabase(connection)
        }
    }
}

/** The table and indices Room generates for [FileIndexEntity]. */
private val CREATE_TABLE_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS `${SearchIndexSchema.TABLE}` (" +
        "`${SearchIndexSchema.COLUMN_ID}` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_URI}` TEXT NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_PARENT_URI}` TEXT NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_NAME}` TEXT NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_SCHEME}` TEXT NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_IS_DIRECTORY}` INTEGER NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_SIZE}` INTEGER NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_LAST_MODIFIED}` INTEGER NOT NULL, " +
        "`${SearchIndexSchema.COLUMN_IS_HIDDEN}` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_file_index_uri` " +
        "ON `${SearchIndexSchema.TABLE}` (`${SearchIndexSchema.COLUMN_URI}`)",
    "CREATE INDEX IF NOT EXISTS `index_file_index_parent_uri` " +
        "ON `${SearchIndexSchema.TABLE}` (`${SearchIndexSchema.COLUMN_PARENT_URI}`)",
    "CREATE INDEX IF NOT EXISTS `index_file_index_last_modified` " +
        "ON `${SearchIndexSchema.TABLE}` (`${SearchIndexSchema.COLUMN_LAST_MODIFIED}`)"
)
