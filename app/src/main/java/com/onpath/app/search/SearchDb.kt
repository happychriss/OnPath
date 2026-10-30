package com.onpath.app.search

import com.onpath.app.debug.Problems
import java.io.Closeable
import java.io.File

/**
 * Minimal SQL access for the offline search index on disk ([SearchAreas], [DiskIndex]): Android's SQLite on the
 * phone, JDBC in unit tests. Thread-safe (reads may run while a build writes).
 */
interface SearchDb : Closeable {
    /** One statement without result rows; [args]: String / Long / Int / Double / null. */
    fun exec(sql: String, vararg args: Any?)

    /** Streams the result rows to [row]; [args] are bound as strings (numbers are inlined in [sql]). */
    fun query(sql: String, vararg args: String, row: (Row) -> Unit)

    /** [block] in one transaction: all or nothing. */
    fun <T> transaction(block: () -> T): T

    /** A prepared insert, run once per row (inside [transaction]). */
    fun prepare(sql: String): Insert

    interface Row {
        fun isNull(i: Int): Boolean
        fun str(i: Int): String?
        fun long(i: Int): Long
        fun double(i: Int): Double
    }

    interface Insert : Closeable {
        /** Binds [values] (String / Long / Int / Double / null) and runs the statement. */
        fun run(vararg values: Any?)
    }

    companion object {
        /** The index file's companions (journal); deleted together with it. */
        fun files(f: File): List<File> = listOf("", "-wal", "-shm", "-journal").map { File(f.path + it) }
    }
}

/** [SearchDb] on Android's SQLite (write-ahead log: a search reads while a new area is written). */
class AndroidSearchDb(file: File) : SearchDb {
    private val db: android.database.sqlite.SQLiteDatabase = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
        android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS) {
        // a damaged index is only a copy of the map files: SearchAreas deletes and builds it again on the next failure
        Problems.warn("search", "Offline search index file is corrupt; it is built again")
    }.apply {
        enableWriteAheadLogging()
    }

    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun query(sql: String, vararg args: String, row: (SearchDb.Row) -> Unit) {
        db.rawQuery(sql, args).use { c ->
            val r = object : SearchDb.Row {
                override fun isNull(i: Int) = c.isNull(i)
                override fun str(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
                override fun long(i: Int) = c.getLong(i)
                override fun double(i: Int) = c.getDouble(i)
            }
            while (c.moveToNext()) row(r)
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val v = block()
            db.setTransactionSuccessful()
            return v
        } finally {
            db.endTransaction()
        }
    }

    override fun prepare(sql: String): SearchDb.Insert {
        val st = db.compileStatement(sql)
        return object : SearchDb.Insert {
            override fun run(vararg values: Any?) {
                st.clearBindings()
                values.forEachIndexed { i, v ->
                    when (v) {
                        null -> st.bindNull(i + 1)
                        is String -> st.bindString(i + 1, v)
                        is Int -> st.bindLong(i + 1, v.toLong())
                        is Long -> st.bindLong(i + 1, v)
                        is Double -> st.bindDouble(i + 1, v)
                        else -> st.bindString(i + 1, v.toString())
                    }
                }
                st.executeInsert()
            }
            override fun close() = st.close()
        }
    }

    override fun close() = db.close()
}
