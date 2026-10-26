package android.database;

/** Web stand-in (see Cursor). */
public interface DatabaseErrorHandler {
    void onCorruption(android.database.sqlite.SQLiteDatabase db);
}
