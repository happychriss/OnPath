package com.onpath.app.search

import com.onpath.app.debug.Problems
import java.io.File

/** [AddressDb] on Android's SQLite, read-only. */
class AndroidAddressDb(file: File) : AddressDb {
    // Own error handler: Android's default one DELETES a database file it finds corrupt – a map data file must stay
    // (the query fails, AddressIndex skips the file and reports it).
    private val db = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
        android.database.sqlite.SQLiteDatabase.OPEN_READONLY or android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        { Problems.warn("search", "Offline address file ${file.name} is corrupt; not deleted, no addresses from it") })

    override fun rows(sql: String, vararg args: String): List<Array<Any?>> = db.rawQuery(sql, args).use { c ->
        val out = ArrayList<Array<Any?>>()
        while (c.moveToNext()) out += Array(c.columnCount) { i ->
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                android.database.Cursor.FIELD_TYPE_NULL -> null
                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                else -> c.getString(i)
            }
        }
        out
    }

    override fun close() = db.close()
}
