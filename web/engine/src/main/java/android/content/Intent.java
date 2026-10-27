package android.content;

/** Web build shell for poi/GoogleMapsLink.kt's PoiIntents (never called in the browser). */
public class Intent {
  public static final String ACTION_VIEW = "android.intent.action.VIEW";
  public static final String ACTION_DIAL = "android.intent.action.DIAL";
  public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
  private final String action;
  public Intent(String action, android.net.Uri uri) { this.action = action; }
  public Intent setPackage(String pkg) { return this; }
  public Intent addFlags(int flags) { return this; }
  public String getAction() { return action; }
}
