package com.onpath.app.search

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/** JDBC-backed [SearchDb] for the JVM (the phone uses Android's SQLite, [AndroidSearchDb]). */
class JdbcSearchDb(file: File) : SearchDb {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private var depth = 0

    private fun bind(st: PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, v ->
        when (v) {
            null -> st.setNull(i + 1, Types.NULL)
            is String -> st.setString(i + 1, v)
            is Int -> st.setLong(i + 1, v.toLong())
            is Long -> st.setLong(i + 1, v)
            is Double -> st.setDouble(i + 1, v)
            else -> st.setString(i + 1, v.toString())
        }
    }

    @Synchronized override fun exec(sql: String, vararg args: Any?) {
        c.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    @Synchronized override fun query(sql: String, vararg args: String, row: (SearchDb.Row) -> Unit) {
        c.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs: ResultSet ->
                val r = object : SearchDb.Row {
                    override fun isNull(i: Int): Boolean { rs.getObject(i + 1); return rs.wasNull() }
                    override fun str(i: Int): String? = rs.getString(i + 1)
                    override fun long(i: Int): Long = rs.getLong(i + 1)
                    override fun double(i: Int): Double = rs.getDouble(i + 1)
                }
                while (rs.next()) row(r)
            }
        }
    }

    @Synchronized override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) c.autoCommit = false
        var ok = false
        try {
            val v = block(); ok = true; return v
        } finally {
            if (--depth == 0) { if (ok) c.commit() else c.rollback(); c.autoCommit = true }
        }
    }

    @Synchronized override fun prepare(sql: String): SearchDb.Insert {
        val st = c.prepareStatement(sql)
        return object : SearchDb.Insert {
            override fun run(vararg values: Any?) { synchronized(this@JdbcSearchDb) { bind(st, values); st.executeUpdate() } }
            override fun close() = st.close()
        }
    }

    @Synchronized override fun close() = c.close()

    companion object {
        val opener: (File) -> SearchDb = { JdbcSearchDb(it) }
    }
}

/** All entries of an index as places, in index order (tests). */
fun PlaceIndex.places(): List<Place> = when (this) {
    is OfflineIndex -> entries.map { it.place }
    is DiskIndex -> allPlaces()
    else -> error("unknown index")
}
