package android.database.sqlite;

/** Web stand-in (see android.database.Cursor): never opened in the browser. */
public class SQLiteDatabase {
    public static final int OPEN_READONLY = 1, NO_LOCALIZED_COLLATORS = 16;
    public interface CursorFactory {}
    public static SQLiteDatabase openDatabase(String path, CursorFactory f, int flags, android.database.DatabaseErrorHandler h) {
        throw new UnsupportedOperationException("no SQLite in the browser");
    }
    public android.database.Cursor rawQuery(String sql, String[] args) { throw new UnsupportedOperationException(); }
    public void close() {}
}
