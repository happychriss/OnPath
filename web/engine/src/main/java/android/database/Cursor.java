package android.database;

/** Web stand-in (search/AddressSearch.kt names it; the web has no offline address files yet). */
public interface Cursor extends java.io.Closeable {
    int FIELD_TYPE_NULL = 0, FIELD_TYPE_INTEGER = 1, FIELD_TYPE_FLOAT = 2, FIELD_TYPE_STRING = 3, FIELD_TYPE_BLOB = 4;
    boolean moveToNext();
    int getColumnCount();
    int getType(int i);
    long getLong(int i);
    double getDouble(int i);
    String getString(int i);
    void close();
}
