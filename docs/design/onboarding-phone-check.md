# Onboarding and "Phone check": permissions, location, vibration, battery

Status: **built in 0.13.0** (owner: yes to all four decisions of §12). Code: `setup/PhoneCheck.kt`, `setup/SetupGuide.kt` (pure rules), `ui/setup/` (reader, guide screen, Settings → Phone setup), `recording/Cues.kt` (vibration incl. test). Core feature C17.

Goal: an older hiker or cyclist sets OnPath up once, understands *why* each thing is needed, and can trust that on the
trail it still works – also after months without use, on any brand of phone and in English or German. The owner can
test only on a Pixel 8, so the design follows Android's documented behaviour and keeps every check a pure, unit-tested
rule (section 10).

## 1. Principles

1. **The app works without anything.** Map, planning, search and My routes need no permission. Nothing blocks the
   app; every request can be skipped ("Later") and asked again where it is needed. (CLAUDE.md "robust first".)
2. **Ask in context, one thing at a time** (Android: "wait until the user interacts with the feature" –
   [runtime permissions](https://developer.android.com/training/permissions/requesting)). Location is asked when the
   user taps ◎ or *Start*, not at the first screen. Never two system dialogs in one go.
3. **Explain first, in plain words, then show the system dialog.** Our own screen says what OnPath does with it and
   what the user gains ("So the map shows where you are and the tour is recorded"), and which choice to pick – never
   quoting system button text (it differs per Android version and language). Only where Android gives us the label
   (`getBackgroundPermissionOptionLabel()`) do we name it.
4. **One step engine, three entry points:** (a) *Set up at home* after the first start (optional, ~1 minute),
   (b) *Before the tour* when Start is tapped (only what is missing and needed), (c) *Settings → Phone setup*
   (everything, with a traffic light). Same steps, same texts, same tests.
5. **Check again every time the app comes back** (`onResume`) – the phone, the user or Android may have taken
   something away. A step that the user fixed in system settings turns green by itself when they come back; no
   "Done" button needed.
6. **Say what happened, calmly, without blame:** "Your phone removed OnPath's location access because OnPath was not
   used for some months. That is normal – allow it again in one step."
7. **Older users:** one decision per screen, body text ≥ 16 (`OnPathType`), large buttons (≥ 56 dp), one primary
   button + "Later", a step counter ("Step 2 of 3"), a picture of what will happen, no jargon ("access to your
   location", not "permission"; "phone settings", not "system settings"), works at font scale 2.0 and with TalkBack.
8. **No Google Play services, no tricks:** only public Android / AndroidX APIs; no direct battery-exemption dialog
   (Play policy, section 9); no background location unless the owner decides so (section 9).

## 2. What OnPath needs from the phone

| # | Item | Why (user words) | Needed for | Check (API) | Fix (screen) | Can be taken away by |
| --- | --- | --- | --- | --- | --- | --- |
| L1 | Location access "while using the app" | the dot on the map, recording, guidance | Navigate, recording, ◎ | `checkSelfPermission(FINE/COARSE)` | system dialog; after 2 denials: App info → Permissions | user; **unused-app reset** |
| L2 | **Precise** location | the track and turn guidance need metres, not ~3 km² | recording, guidance | FINE granted (COARSE only = approximate) | ask FINE+COARSE again → "Change to precise" ([location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)) | user |
| L3 | Location switched on (phone) | without it the phone gives no position at all | as L1 | `LocationManager.isLocationEnabled` | `ACTION_LOCATION_SOURCE_SETTINGS` | user, quick settings tile |
| N1 | Notifications allowed | the recording notification: you see that it records and can pause; off-route question when the screen is off | recording, off-route question | `NotificationManagerCompat.areNotificationsEnabled()` + channel importance ≠ NONE for `recording`, `navigation`, `offroute-question` | API 33+: system dialog; else `ACTION_APP_NOTIFICATION_SETTINGS` / `ACTION_CHANNEL_NOTIFICATION_SETTINGS` | user; unused-app reset (API 33+) |
| V1 | Vibration felt | turn cues in the pocket (1× left, 2× right), off-route warning | junction / off-route vibration | `Vibrator.hasVibrator()`; **no API tells whether the user's settings mute it** → *Test vibration* + "Did you feel it?" | Pixel: Sound & vibration → Vibration & haptics → Alarm vibration on | user (vibration master switch, alarm vibration, intensity 0) |
| B1 | Battery: not "Restricted" | with "Restricted" Android may stop the recording in the background | recording with the screen off | `ActivityManager.isBackgroundRestricted()` (API 28) | App info → Battery → "Unrestricted"/"Optimized" (`ACTION_APPLICATION_DETAILS_SETTINGS`) | user; some brands do it by themselves |
| B2 | Brand battery killers (Samsung, Xiaomi, Huawei/Honor, OnePlus, Oppo/realme) | these phones close apps in the background more than Android itself | long recordings | `Build.MANUFACTURER` (+ B1, standby bucket) | brand page with steps (section 6) | brand power manager, OS updates (OnePlus: "reverted randomly") |
| B3 | Battery saver's location mode | some phones turn GPS off with the screen when battery saver is on | recording with battery saver | `PowerManager.isPowerSaveMode` + `getLocationPowerSaveMode()` (API 28) | Battery saver settings (`ACTION_BATTERY_SAVER_SETTINGS`) | user, automatic battery saver |
| H1 | Unused-app reset / hibernation | a seasonal app: after "a few months" unused, Android removes location (and on 12+ notification) access and pauses background work | season start | `PackageManagerCompat.getUnusedAppRestrictionsStatus` (androidx.core ≥ 1.7; we have 1.15) | `IntentCompat.createManageUnusedAppRestrictionsIntent` via `startActivityForResult` ([hibernation](https://developer.android.com/topic/performance/app-hibernation)) | Android (automatic) |
| U1 | Install updates (sideloaded builds only) | so OnPath can install its own updates | Settings → Updates | `packageManager.canRequestPackageInstalls()` | `ACTION_MANAGE_UNKNOWN_APP_SOURCES` | user; not in the Play version (A-003) |
| S1 | Storage (Android 8–9 only) | GPX backup copy in Documents | backups | as today | as today | – |

Not needed (and not asked): background location (section 9), exact alarms, full-screen intents (showing Navigate over
the lock screen uses `setShowWhenLocked`, no permission), Bluetooth, contacts, camera (QR pairing uses the system
camera app's link).

**Required vs recommended:** for *recording / Navigate*, L1 + L2 + L3 are **required** (without them the tour cannot
start – as today, but explained); N1, V1, B1–B3 are **recommended** (the tour starts, the step offers to fix it once
per tour start at most, "Later" always possible); H1 and U1 are **optional** (Settings only, plus the season-start
message).

## 3. What the app does today (gaps)

From the code inventory of 2026-10-29:
- The system dialog for location **and** notifications opens at the very first screen (`MainActivity.onCreate →
  requestLocation(false)`), combined, without our explanation – against principle 2 and Android's guidance; older
  users then decide before they know what the app is.
- Approximate-only location: the dialog comes back at every start (`hasLocation()` checks FINE), with no explanation;
  recording then fails with a long sentence about Settings → Apps.
- Notifications: never checked; when blocked, the recording notification and the off-route question silently do not
  appear (only Problems warnings).
- Battery, battery saver, standby bucket, hibernation, Do Not Disturb, vibration settings: never checked. Two static
  hints in Settings → Navigation.
- `ACCESS_BACKGROUND_LOCATION` is in the manifest but never requested – it is used only to continue a tour after a
  reboot (`TourInterruptedReceiver`). Its presence alone starts the Play declaration (section 9).
- Good already: USAGE_ALARM for navigation vibration (passes Do Not Disturb and silent mode on stock Android – it is
  an "alarm"), GPS-off and no-permission states on the ◎ button, the interrupted-tour notification, `failLoud`.

## 4. The system

### 4.1 Model (pure, `data/PhoneCheck.kt`, unit-tested)

```
data class PhoneState(            // read by a small Android adapter, PhoneStateReader
    sdk: Int, brand: String,
    location: Grant,              // NONE / APPROXIMATE / PRECISE
    locationOn: Boolean,
    canAskLocation: Boolean,      // false after 2 denials (shouldShowRequestPermissionRationale + our own count)
    notifications: Grant3,        // ON / OFF / CHANNEL_OFF
    hasVibrator: Boolean, vibrationFelt: Felt,   // UNKNOWN / YES / NO (user's answer, remembered)
    backgroundRestricted: Boolean, standbyBucket: Int?, powerSave: Boolean, locationPowerSaveMode: Int?,
    unusedAppReset: ResetStatus,  // ENABLED / DISABLED / NOT_AVAILABLE / UNKNOWN
    lastSeen: LastSeen,           // what was granted when the app was last open + when (Prefs)
)
enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }
data class Check(val id: CheckId, val ok: Boolean, val need: Need, val taken: Taken?)   // taken = USER / RESET / UNKNOWN
object PhoneCheck {
    fun all(s: PhoneState): List<Check>                     // Settings page
    fun beforeTour(s: PhoneState, a: Activity, vib: JunctionVibration): List<Check>   // only missing + relevant
    fun firstSetup(s: PhoneState): List<Check>
    fun takenAway(prev: LastSeen, now: PhoneState, daysUnused: Int): List<Check>   // "your phone removed …"
}
```
- **Relevance:** vibration only if the user's setting is not OFF; B2 only for listed brands; B3 only while battery
  saver is on; U1 only in the sideload build.
- **"Taken away by the phone"**: granted at last use, missing now, `unusedAppReset == ENABLED` and ≥ 60 days since last
  use → `Taken.RESET` (the calm text of principle 6); otherwise `Taken.USER` ("Location access is off").
- `PhoneState` goes into the diagnostics export (one line per item) – testers with other phones send us what their
  phone says (section 10).

### 4.2 The guide (UI, `ui/setup/SetupGuide.kt`)

A full-screen sheet with one step per screen:
- **Header**: "Step 1 of 3" + a large icon (location pin, bell, vibrating phone, battery).
- **Why** (2 short sentences) + **what to do** ("On the next screen choose: allow while using the app, and *precise*
  on.") – our words, never the system's button text.
- **One primary button** ("Continue" → system dialog or settings screen) and **"Later"**.
- Coming back (`onResume`) re-reads `PhoneState`: done → a green check "✓ Location allowed" for 1 s, next step; not
  done → the same step again with the *after-denial* text ("No problem. You can allow it later in Settings → Phone
  setup." / after 2 denials: "Android does not ask again. Tap Continue, then *Permissions → Location*.").
- **End**: a summary with traffic lights and "Done".

### 4.3 Entry points

1. **First start** – replaces today's immediate system dialog. A welcome card on the map (no blocking modal):
   "Welcome to OnPath. To show where you are and to guide you, OnPath needs a few things from your phone. Set it up
   now (1 minute) – or later, when you start a tour." Buttons: **Set up now** / **Later**. *Set up now* runs the guide
   with L1/L2 (+L3), N1, V1 (test), B1/B2/B3 when relevant. The restore offer and the loading modal keep their order
   rules (`RestoreOfferRule`), now waiting for the guide instead of `locationAsking`.
2. **Before the tour** – *Start navigation / Start recording* runs `beforeTour`: required items missing → the guide
   (the tour starts at its end, as `withLocation{}` does today); only recommended items missing → one quiet line in
   the plan sheet ("⚠ Vibration not tested · Battery may stop recording – Check") at most once per day, never blocks.
3. **Settings → Phone setup** (new first item of the "App" group, beside Privacy): the list from `PhoneCheck.all`
   with ✓ / ⚠ / ✗, each row: title, one line why, state, and **Fix** (opens the same step). Below: "What OnPath does
   with it" – links to Privacy for data, and per item what happens without it.
   In the Settings list (owner 2026-10-30, `SettingsSummary.phone` / `groups`): something a tour needs is missing →
   the row moves to the **top** of the list (no heading), red ✗, "1 item needed for tours", and the ☰ menu gets a red
   "⚠ Phone setup" as its first item (opens this page); only recommended items missing → its normal place, amber
   "2 recommended"; all fine → "Everything set up". The phone state is re-read on every return to the app.
4. **Locate ◎ / GPS states** stay as today, but the tap opens the guide's L-steps instead of the bare system dialog.
5. **Season start**: `takenAway(...)` non-empty at app start → one calm card at the next tour start (principle 6), not
   a modal at app open.

### 4.4 Texts (EN; DE in `strings_setup.xml`, reviewed via `tools/i18n`)

| Step | Title | Why | What to do |
| --- | --- | --- | --- |
| L1 | Your location | So the map shows where you are, and your tour is recorded and guided. OnPath keeps it on your phone. | On the next screen, allow OnPath **while using the app**. |
| L2 | Precise location | For a track and turn guidance OnPath needs your exact position – an approximate one is off by up to 2 km. | On the next screen, switch **precise location** on. |
| L3 | Location is off | Your phone's location is switched off, so no app can find your position. | Tap Continue and switch location on. |
| N1 | Notifications | While you record, a notification shows that OnPath is working and lets you pause. It also asks if you leave the route while your screen is off. | Allow notifications. |
| V1 | Vibration | In your pocket OnPath tells you about turns: one buzz = left, two = right. | Tap **Test**. Did you feel two short buzzes? **Yes** / **No** (No → the phone's vibration settings, with the path for this phone). |
| B1 | Battery | Your phone is set to restrict OnPath in the background. It may stop recording when the screen is off. | Tap Continue, then *Battery* → **Unrestricted** (or "Optimized"). |
| B2 | [Brand] phones | Phones from [brand] close apps in the background to save battery. Two settings keep OnPath recording. | numbered steps for that brand (section 6) |
| B3 | Battery saver | Battery saver is on. On some phones it turns off GPS when the screen is off. | Turn battery saver off for long tours, or keep the screen on. |
| H1 | When you don't use OnPath for a while | Android removes access from apps you have not opened for some months. OnPath then asks again – or you switch this off. | Tap Continue, then switch off **Pause app activity if unused** (wording varies by phone). |

Rules: "you" form (DE "du", as the website), no word "permission" in titles, numbers in plain words, no system
button labels except from APIs.

## 5. Location in detail

- Request **FINE + COARSE together** (Android 12+: "don't request ACCESS_FINE_LOCATION by itself"). Result COARSE
  only → L2 step with its own explanation, then ask again (the system shows "Change to precise location").
- **Do not ask at every start.** Count our asks per item in Prefs; after the system stops asking
  (`shouldShowRequestPermissionRationale == false` after a denial) send to App info with exact steps.
- Approximate-only users can still plan and see an approximate circle; recording asks L2 at Start.
- Location services off (L3): today's `ACTION_LOCATION_SOURCE_SETTINGS`, explained.
- "Improve location accuracy" (Wi-Fi/Bluetooth scanning, Google's location accuracy): not checked – OnPath's track
  is GPS only; network fixes only help the dot when GPS is weak.

## 6. Battery and brands

- **Stock Android / Pixel**: a location foreground service started from the visible app keeps running in Doze and with
  battery "Optimized"; only **"Restricted"** (`isBackgroundRestricted`) is a problem → B1. Also show the standby
  bucket in Diagnostics (`UsageStatsManager.getAppStandbyBucket`, own app, no permission) – `RESTRICTED` explains a
  stopped background download.
- **No direct exemption dialog**: Play allows `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only for chat/VoIP, safety, task
  automation and device-companion apps ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby));
  OnPath uses a foreground service instead and, if needed, the App info screen.
- **Brands** (ranking and paths from [dontkillmyapp.com](https://dontkillmyapp.com/): Huawei, Xiaomi, OnePlus,
  Samsung worst; Pixel best):

  | Brand (`Build.MANUFACTURER`) | Steps we show (short) |
  | --- | --- |
  | samsung | Settings → Battery → Background usage limits: *Put unused apps to sleep* off; OnPath not in *Sleeping / Deep sleeping apps*, add to *Never sleeping apps*. App info → Battery → **Unrestricted**. |
  | xiaomi, redmi, poco | App info → *Autostart* on; App info → Battery saver → **No restrictions**; in Recent apps pull OnPath down → lock 🔒. |
  | huawei, honor | Settings → Battery → *App launch* → OnPath → *Manage manually*, all three switches on. |
  | oneplus, oppo, realme | Battery → Battery optimization → OnPath → **Don't optimize**; App info → *Allow background activity*; lock in Recent apps. |
  | others | App info → Battery → **Unrestricted** (or "Don't optimize"). |

  We can open only App info (`ACTION_APPLICATION_DETAILS_SETTINGS`) reliably – vendor screens have no public intents,
  so steps are text with the path, plus a link "More help for [brand]" to dontkillmyapp.com (online, optional). Texts
  are ours, inspired by dontkillmyapp (its API asks for contact + credit before reuse – we only link). Menu names
  change between OS versions: say "may be called differently on your phone".
- **Robustness stays in the app too**: START_STICKY restart, the interrupted-tour notification, "Continue" in the app.

## 7. Vibration

- Keep `USAGE_ALARM` for turn and off-route cues: on stock Android alarms pass Do Not Disturb (alarms are allowed by
  default) and silent mode, and background apps must use an "attentional" usage
  ([haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis)). The user can still mute it:
  the vibration master switch, *Alarm vibration* (Pixel: Sound & vibration → Vibration & haptics), intensity 0, or an
  OEM setting. **No public API reads these** → the only honest check is a test the user feels.
- **Test vibration** button (step V1 and Settings → Navigation): plays the real cues through the same code path as the
  service (`RecordingService.vibrate` moved to a small `Cues` helper) – "left" (1×), pause, "right" (2×) – then asks
  *Did you feel it?* Yes → remembered (`vibrationFelt = YES`, cleared if the phone's vibration state changes, e.g.
  ringer mode); No → steps for this phone (Pixel path verified by the owner; others generic).
- `hasVibrator() == false` → V1 says "This phone cannot vibrate" and the setting shows it.
- The trace keeps "Junction cue … vibrated=…" (proves the app side, as the 2026-10-28 ride showed).

## 8. When OnPath is not used for months (hibernation)

- Android 11+ (and 6–10 with Play services): after "a few months" without opening OnPath, runtime permissions are
  reset; on 12+ the app also hibernates (no background work, cache cleared). Opening the app counts as use; a job
  does not.
- Effect on OnPath: location (and on 13+ notifications) must be allowed again. Maps, tracks and settings stay (not in
  the cache). Nothing breaks thanks to section 5 – the guide asks again at the next ◎ / Start, with the calm
  "your phone removed it" text.
- **Do not push users to switch it off** (it protects their privacy, and Android recommends asking only for apps that
  work mainly in the background). Offer it in Settings → Phone setup as an optional row with a clear explanation, for
  users who want "it simply works at the start of the season".
- Remember `lastSeen` (granted items + date) at every app stop; at the first start after a long pause run
  `takenAway`.

## 9. Google Play consequences (decide before the Play test)

- **Background location:** a location foreground service started from the visible app needs only "while using the
  app" ([FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
  Just having `ACCESS_BACKGROUND_LOCATION` in the manifest triggers Play's declaration form + video, and Play treats
  location use outside the app ("passive tracking of routes") strictly
  ([Play background location](https://support.google.com/googleplay/android-developer/answer/9799150)). OnPath uses it
  only to continue a tour automatically after a phone restart. **Recommendation: remove it** – after a restart the
  existing notification says "open OnPath and tap Continue" (one tap; robust-first is kept because no point is lost
  before the restart). Remove the "Allow all the time" hint in Settings → Navigation with it.
- **Battery exemption:** settings screen only (section 6), never the direct dialog.
- **Install updates (U1):** Play version without the updater and `REQUEST_INSTALL_PACKAGES` (A-003).
- The Play listing's data-safety answers follow from section 2 (location on the phone only).

## 10. Testing with only a Pixel 8

- **Pure rules**: `PhoneCheckTest` – every row of section 2 × SDK 26 / 29 / 31 / 33 / 35 × brands × "taken away" cases;
  texts per item exist in EN + DE (`StringFilesTest`).
- **Screenshots**: Paparazzi for every step and the Settings page in EN + DE, at font scale 1.0 and 2.0, small phone
  (`DeviceConfig.NEXUS_5`).
- **Emulator (CI, `e2e.yml`)**: grant / deny / "don't ask again" with UiAutomator; approximate only (`pm grant` COARSE);
  notifications off (`cmd notification`/`pm revoke POST_NOTIFICATIONS`); restricted battery
  (`cmd appops set com.onpath.app RUN_ANY_IN_BACKGROUND ignore`); standby bucket
  (`am set-standby-bucket com.onpath.app restricted`); Doze (`dumpsys deviceidle force-idle`) during a recording;
  hibernation (`cmd app_hibernation set-state com.onpath.app true`) and permission reset; API 26 and 35 images.
- **On the Pixel (owner, 15 min)**: a checklist in this doc's appendix – first start, deny twice, approximate, vibration
  test with Alarm vibration on/off, battery Restricted, a 30-minute screen-off ride.
- **Other phones**: testers send *Diagnostics → Send problem report*; it now contains `PhoneState`. Brand texts are
  marked "not tested on this brand" until a tester confirms.

## 11. Build plan

1. `PhoneState` reader + `PhoneCheck` rules + tests + diagnostics line (no UI change) – small.
2. Guide UI + Settings → Phone setup + strings EN/DE + Paparazzi – medium.
3. Entry points: first-start card instead of the start-up dialog; Start → `beforeTour`; ◎ → guide; season-start card;
   approximate-location fix – medium; core feature tests (C-list) for "tour starts after the guide".
4. Vibration test + `Cues` helper – small.
5. Brand battery pages – small (texts), tester feedback later.
6. Play cleanup (background location out, updater out in the Play build) – with A-003.

## 12. Owner decisions (all four: yes, 2026-10-29)

1. **First start**: welcome card with *Set up now / Later* instead of today's immediate system dialog? (Recommended.)
2. **Background location**: remove before the Play test (one tap on "Continue" after a phone restart)? (Recommended.)
3. **Hibernation**: only an optional row in Settings → Phone setup, no push? (Recommended.)
4. **Vibration test**: part of *Set up now* and offered once before the first tour with vibration on? (Recommended.)

## Appendix – Pixel 8 checklist (owner)

1. Fresh install → welcome card, *Later* → map works, no system dialog.
2. ◎ → step L1 → deny → "No problem…" → ◎ again → deny again → step says "Android does not ask again" → Continue
   opens App info.
3. Allow *approximate* → Start → step L2 → precise → tour starts.
4. Notifications off in App info → Start → step N1 (recommended) → *Later* → tour starts; Settings → Phone setup shows ⚠.
5. Vibration test with Alarm vibration on (felt) and off (not felt → steps shown).
6. App info → Battery → Restricted → Phone setup shows ✗ B1 → fix → ✓ after coming back.
7. 30-minute ride with the screen off, battery saver on → B3 shown before the start; track complete.
