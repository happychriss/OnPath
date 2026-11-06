package android.content;

/**
 * Web build: replaces tools/routing/jvm-test/stubs' Context (same three methods) and adds startActivity, so the app's
 * poi/GoogleMapsLink.kt compiles; the web never starts an activity (the page opens the link itself).
 */
public abstract class Context {
  public abstract Context getApplicationContext();
  public abstract java.io.File getFilesDir();
  public abstract android.content.res.AssetManager getAssets();
  public void startActivity(Intent intent) { throw new UnsupportedOperationException("no activities in the browser"); }
}
