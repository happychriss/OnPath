package android.content.res;

/**
 * Web build: replaces tools/routing/jvm-test/stubs' AssetManager (same open) and adds list, so the app's
 * map/Overview.kt compiles. The files live under [root] (the engine's assets folder).
 */
public class AssetManager {
  public static java.io.File root;
  public java.io.InputStream open(String name) throws java.io.IOException { return new java.io.FileInputStream(new java.io.File(root, name)); }
  public String[] list(String path) {
    String[] names = new java.io.File(root, path).list();
    return names != null ? names : new String[0];
  }
}
