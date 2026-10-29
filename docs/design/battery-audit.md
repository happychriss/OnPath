# OnPath battery audit (read-only), app 0.7.2 @ c8f2338

Status: audit 2026-10-20 of app 0.7.2 (cloud, read-only; no device measurements) – line numbers and findings are as of that code. **Owner rule: save power only when nobody looks (background, pocket, screen off); on screen the app runs at full speed** ("when I look at it, it must run fast and polished" – so no map fps caps). **Done:** pocket-aware keep-screen-on (proximity screen-off + sleep after 60 s covered, `ui/PocketScreen.kt`), GPS slower while paused (10 s, never off – owner) + wake lock off while paused, PauseWatch reminder after 150 m, no network provider in the service; the UI (app open) uses an accurate (≤ 150 m) network fix when GPS is silent 30 s (owner: position indoors); chronometer notification re-posted only on change; slower GPS at rest (`RestWatch`). Owner: no battery % in the tour summary; the battery-optimisation hint is a quiet line in Settings. **Open:** adaptive GPS rate with interval-scaled thresholds (rules under discussion with the owner), frozen + tail track source, slow ticker, off-route question back-off, compass rate, onCamera/setNavGuide/lifecycle collectors.

Scope: Navigate first (a tour is running: `RecordingService` records GPS, navigation and re-routing; the map follows the position heading-up), then Plan and the app in the background. Typical day: 6–14 h, phone mostly in the pocket with the screen off.

**How this was checked:** by reading the code, plus the MapLibre 13.7.1 bytecode (`javap`) for the location component. Nothing was measured on a device, so impact levels are estimates. No files were edited.

Paths are relative to `app/src/main/java/com/onpath/app/`.

---

## Summary

The service design is sound. Recording and navigation live in a location foreground service, and the UI's GPS stops in `onStop`. The map lifecycle is forwarded, and MapLibre releases the compass when the map stops. Re-routing is bounded.

The power goes to things that are always on at full rate, even when nothing needs them:

- GNSS at 1 Hz all day, including pauses and breaks.
- A network-location request that the service throws away.
- A wake lock held all tour, plus a 1 s ticker.
- A notification redrawn every 5 s.

When the screen is on, there are two bigger costs:

- "Keep screen on while navigating" is **on by default**.
- The MapLibre location animation has no frame cap, so the map redraws at the display rate (up to 120 Hz on a Pixel 8) all the time a fix is animating.

Requirement R-10 (battery saver mode) and the detection part of R-09 ("detect when throttled") are not implemented.

---

## A. Screen off, tour running (the priority case), ranked by expected impact

### A1. GNSS fixed at 1 Hz all day, with no adaptive rate — HIGH
- **Now:**
  - `recording/RecordingService.kt:149` creates `LocationTracker(this, 1000L)`.
  - `recording/LocationTracker.kt:98` calls `requestLocationUpdates(GPS_PROVIDER, 1000, 0f, …)`.
  - The rate never changes: not when the screen is off, not while standing still, not when paused, not in Plan mode during a tour.
  - While **paused**, GPS keeps running at 1 Hz and every fix is thrown away (`RecordingService.kt:308`, `if (tourId < 0 || paused) return`).
  - `PointFilter` (`recording/PointFilter.kt:172,183`) already drops fixes that moved less than 3–25 m within 30 s. When walking with 5 m accuracy, only about one fix in 3 becomes a track point. When standing, almost none do.
- **Why it costs:** GNSS is the largest single consumer when the screen is off. At 1 Hz the application processor also wakes every second to handle each fix (listener, off-route matching, trip pace, `publish()`).
  - On most chips, power does not fall in proportion between 1 s and 2–3 s intervals, because the chip stays in tracking mode.
  - The real savings are (a) fewer application-processor wake-ups and (b) long slow or idle periods. Breaks, hut stops and pauses can be hours of a 14 h day.
- **Fix:** add a small pure `GpsPolicy` (unit-testable, like `PointFilter`) and a `LocationTracker.setInterval(ms)` that re-requests updates only when the interval actually changes.
  - **Paused:** stop the service's GPS (`tracker.stop()`) and start it again on resume. Nothing is recorded while paused, and a visible map has the UI's own tracker. **This is a quick win.**
  - **Standing still** (no accepted point and speed < 0.5 m/s for ≥ 2 min): 5–10 s. Back to 1 s on the first fix that moves ≥ `minMoveM` or has speed > 0.8 m/s.
  - **Screen off and moving:** 2 s for Hike, keep 1 s for bike activities. Off-route confirmation needs 15 s and 25 m+ (`navigation/OffRouteDetector.kt:312-313`), so a 2 s rate still gives 7+ fixes.
  - **No fix (searching):** keep 1 s.
  - **Optional, API 31+:** `LocationRequest.Builder(…).setMaxUpdateDelayMillis(…)` (GNSS batching where the hardware supports it) lets the application processor sleep between batches. Keep it ≤ 5 s, because R-07 says "written … at least every 5 s"; going higher is an owner decision.
- **Robustness risks (must change together with the rate):**
  - `gpsLost()` uses a fixed 8 s (`RecordingService.kt:305`). At a 10 s interval it flips to "lost" on every tick, and `navigator.onGpsLost()` resets the off-route state (`:291`). Make the threshold `max(8 s, 2.5 × interval)`.
  - `GAP_MS = 30 s` (`:784`) must stay well above the slowest interval, or every slow fix starts a new segment.
  - With batching, use `loc.elapsedRealtimeNanos` instead of the arrival time in `onFix` (`:312-314`). Otherwise batched fixes look like gaps.
  - Pausing GPS while paused is risk-free for the track.

### A2. Network location requested every 5 s and then discarded — MEDIUM, quick win, zero risk
- **Now:** `LocationTracker.kt:100-101` also requests `NETWORK_PROVIDER` every 5 s.
  - In the **service** every network fix is thrown away (`RecordingService.kt:309`, `if (loc.provider != GPS_PROVIDER) return`), and the service tracker's `location` flow is not read by anyone.
  - In the **UI** tracker, network fixes are ignored after the first GPS fix (`LocationTracker.kt:56`), but the request stays active.
- **Why it costs:** on a Pixel the network provider is Google's network location provider. A 5 s request means repeated Wi-Fi scans (with "Wi-Fi scanning" on, even when Wi-Fi is off) and cell lookups. These are charged to OnPath, all day, for nothing.
- **Fix:**
  - Add a `useNetwork` flag to `LocationTracker`, and set it to false in `RecordingService.onCreate`.
  - In the UI tracker, call `removeUpdates(netListener)` after the first GPS fix. Optionally request it again if the GPS fix is more than 60 s old.
- **Risk:** none for recording. The service never used these fixes.

### A3. Partial wake lock held for the whole tour, plus a 1 s ticker — MEDIUM (becomes HIGH once A1 lowers the GPS rate)
- **Now:**
  - `RecordingService.kt:269-278` takes `PARTIAL_WAKE_LOCK` for 12 h, and it is renewed every tick (`:290`), so it is held from start to finish, **including while paused**.
  - `startTicker()` (`:280-303`) runs every second on the main thread. Each tick does the elapsed time, the `gpsLost` check, `trip.tick()` (→ `TripPace.refresh` → `RouteTimes.remainingFrom`), the stats write every 10 s, `saveTrip` every 30 s, and `publish()`.
  - `publish()` builds a new `RecState` with `tripTime()` and may post the notification (A4).
- **Why it costs:**
  - A location foreground service does not need a wake lock to get fixes; the system delivers them.
  - At 1 Hz the CPU wakes for every fix anyway, so the extra cost today is mainly "never suspends between fixes". Once the GPS rate drops (A1), the wake lock plus the 1 s ticker become the main thing keeping the CPU awake.
  - Long wake locks are also what Android's battery screen and the newer Play vitals "excessive wake lock" metric flag. Relevant for the planned Play release; check the current Play rules.
- **Fix, in steps:**
  1. **Quick:** release the wake lock while paused, together with A1's GPS stop. Take it again on resume.
  2. Tick at 1 s only while the app is visible (`state.subscriptionCount > 0`, or a flag set by the app in `onStart`/`onStop`). When nobody is watching, tick every 10–30 s. Notification and stats need no more.
  3. After measuring, try **without** the long wake lock. Hold short wake locks (with timeouts) only around a re-route job (`startReroute`, budget ≤ 12 s), `finish()` and `onDestroy()`.
- **Robustness:**
  - Elapsed time already comes from `elapsedRealtime` deltas (`:286-288`, good), **but** each tick is capped by `coerceIn(1, 600)`. Without the wake lock, a CPU asleep for more than 10 min (long GPS loss while standing) would lose time. Raise or remove the cap, or compute `elapsedS` from a base timestamp.
  - Points are written in the fix callback, so they are unaffected.
  - Field-test on Pixel **and** Samsung before step 3.

### A4. Foreground notification redrawn every 5 s — MEDIUM-LOW, quick win
- **Now:** `publish()` calls `nm.notify(NOTIF_ID, buildNotification())` at most every 5 s (`RecordingService.kt:605-611`). It is called every tick, so in practice that is every 5 s for the whole tour. The title shows `h:mm:ss` (`:732`, `data/Geo.kt:121-123`).
- **Why it costs:** each notify is a call into system_server plus a re-inflate in SystemUI: about 720 per hour, with the screen off too.
- **Fix:**
  - Use `setUsesChronometer(true)` + `setShowWhen(true)` + `setWhen(System.currentTimeMillis() - elapsedS*1000)`, so the system counts the time by itself. Turn the chronometer off and show a fixed time while paused, because the chronometer counts wall time.
  - Re-post only when something changes: status (Paused / Searching GPS / Off route), distance by ≥ 100 m, or at most once a minute.
  - While `!PowerManager.isInteractive`, re-post only on status changes.
- **Risk:** none. Keep `FOREGROUND_SERVICE_IMMEDIATE` and the actions.

### A5. Unanswered off-route question keeps starting BRouter jobs — LOW-MEDIUM (only in that situation)
- **Now:** while a `NavPrompt.Question` is open and unanswered, `Navigator.followUp` (`navigation/Navigator.kt` ~258-261) starts a fresh re-route every 150 m and 30 s (`REFRESH_MOVE_M`, `REFRESH_MIN_MS`, `:343-345`). Each job may take up to 12 s of CPU (`navigation/Rerouter.kt:128-129`).
  - Example: a hiker who deliberately takes another path with the phone in the pocket and never answers. That is one BRouter run about every 2 min, for hours.
  - The NoWay and Kept paths are already bounded (MAX_FAILURES = 3, Kept back-off up to 8 min). Cancellation and the single-thread executor are fine, so jobs cannot loop.
- **Fix:** while the screen is off, back off the Question refresh too (e.g. ×2 per refresh up to 8 min, like `KEEP_WAIT_MS`). Or refresh once when the screen turns on or the app is opened (`ACTION_SCREEN_ON` / `onStart` → `ACTION_CHECK_NOW`-style refresh). The question itself stays, per the owner rule.
- **Risk:** none for the track. The proposal may be a little older when the user finally looks.

### A6. Small per-fix and per-tick work — LOW
- `RecordingService.kt:599` copies the whole track (`track.map { it.toList() }`) on every accepted point, even when nobody is watching. That is O(n) with n up to 10–20 k points by evening.
  - **Fix:** publish the track only when there is a subscriber, or keep frozen chunks plus a tail, or publish a point count and let the UI fetch the track.
- `navigation/NavLine.kt:35-36`: `match()` allocates two `DoubleArray(n)` for the **whole** line on every call. It is called 3–5× per fix via `pick` / `dir` (`navigation/OffRouteDetector.kt:246-286`), although only the window segments are used.
  - **Fix:** size the arrays to `last-first+1`. This cuts garbage, not much CPU.
- `map/HeadingEngine.kt:164` computes `GeomagneticField` on every UI fix. It could be done once per km. Trivial.
- SQLite: one insert per accepted point (WAL), stats every 10 s, trip JSON every 30 s, and `Problems` writes coalesced to 2 s. These are all cheap; **keep them** (R-07).

### A7. Two UI coroutines keep ticking while the activity is stopped — LOW
- `ui/MapScreen.kt:64-66`: `produceState { while (true) { tracker.status(); delay(1000) } }` stays in the composition after `onStop`. It keeps running while the process lives, which is all day during a tour.
  - Each tick, `status()` calls `checkSelfPermission` and `LocationManager.isLocationEnabled`, which are binder calls.
- `collectAsState()` on `recState` / `location` (`MapScreen.kt:60-61`) also keeps collecting. Compose pauses recomposition while stopped, but the collectors still run.
- **Fix:** use `collectAsStateWithLifecycle()` (lifecycle-runtime-compose 2.8.7 is already a dependency). Wrap the status loop in `repeatOnLifecycle(STARTED)`, or use `LocalLifecycleOwner` + `Lifecycle.currentStateFlow`.
- **Risk:** none.

---

## B. Screen on in Navigate (taken out of the pocket, or never switched off), ranked

### B1. "Keep screen on while navigating" defaults to ON — VERY HIGH when the phone goes into the pocket without pressing power
- **Now:**
  - `data/AppSettings.kt:12` has `keepScreenOn = true`, and `data/Prefs.kt:53` defaults it to `true`.
  - `ui/MapScreen.kt:104` + `:313-319` set `view.keepScreenOn` whenever Navigate is following a line.
  - If the user puts the phone away without pressing power, the screen stays on at full brightness, the map renders continuously (B2), and pocket touches can change things.
  - This alone can cost several times more than everything in section A.
- **Fix (owner decision):**
  - **(a)** Default it to off and rely on the system screen timeout. Turning the screen back on returns to Navigate (`onReturnToApp` re-centres).
  - **(b)** Keep it, but only for N minutes after the last touch, turn arrow or off-route question.
  - **(c)** While it is active, also hold a `PROXIMITY_SCREEN_OFF_WAKE_LOCK` (after checking `pm.isWakeLockLevelSupported`). The screen then goes off in the pocket and comes back when the phone is taken out, like during a phone call. This works on a Pixel 8.
- **Risk:** none for recording.

### B2. MapLibre location animation and map redraw have no frame cap — HIGH while the screen is on
- **Now:**
  - `map/MapController.kt:269-298` activates the location component without `setMaxAnimationFps`. In 13.7.1 the default is `Integer.MAX_VALUE` (`LocationAnimatorCoordinator.maxAnimationFps`, checked in the bytecode).
  - Every 1 Hz fix (`MapScreen.kt:271` → `forceLocationUpdate`, `MapController.kt:306-310`) starts a puck/camera animation that lasts about the time between fixes (max 2000 ms). So the map is **redrawn every display frame, all the time**: up to 120 fps on a Pixel 8's 120 Hz panel in TRACKING_COMPASS. This is also true in Plan mode whenever fixes arrive, because the puck animates.
  - Compass changes add 500 ms animations each (`COMPASS_UPDATE_RATE_MS`).
  - No `MapView.setMaximumFps` is set (`ui/MapHost.kt:60`).
- **Why it costs:** a full GL render of the vector map on every frame, and it keeps the LTPO panel at high refresh. This is the biggest cost of a screen-on minute apart from the backlight.
- **Fix (two lines, quick win):**
  - `map.locationComponent.setMaxAnimationFps(15)` (try 10–20) after `activateLocationComponent`.
  - `mapView.setMaximumFps(30)` while Navigate is following. Keep 60 for gestures in Plan, or use 30 everywhere and check how panning feels.
  - Optional: `LocationComponentOptions.builder(…).accuracyAnimationEnabled(false)`, and `compassAnimationEnabled(false)` in Navigate.
- **Risk:** none. Movement looks slightly less fluid.

### B3. Compose recomposes on every rendered frame — MEDIUM
- **Now:** `ui/MapScreen.kt:100` `onCamera` is attached to `addOnCameraMoveListener` (`ui/MapHost.kt:117`), which fires every frame while tracking. On each call it:
  - computes `computeScale` (a new `Pair`, so the state always changes),
  - increments `cameraTick`,
  - sets `bearing`.

  `bearing` feeds `NavigateMode(…, bearing, footer)` (`:112`) and the compass button. `cameraTick` feeds `MapOverlayHost`. The result is Compose recomposing parts of the screen at the render rate.
- **Fix:**
  - Update `scale` only in `onCameraIdle`, or when zoom changes by more than 0.05.
  - Update `bearing` only when it changes by ≥ 1°.
  - Increment `cameraTick` only while an anchored overlay is open (`vm.mapOverlayOpen`).
  - Together with B2's fps cap this reduces CPU a lot.

### B4. Recorded track: the whole GeoJSON is rebuilt on every accepted point — MEDIUM, grows during the day
- **Now:**
  - `ui/MapScreen.kt:263`: `LaunchedEffect(c, rec?.track?.sumOf { it.size }) { c.setTrack(SRC_REC, rec.track) }`. The key itself is O(n) on every recomposition, i.e. every second.
  - `setTrack` (`map/MapController.kt:491-496`) converts all points to a `MultiLineString` and calls `setGeoJson`. MapLibre then re-serialises and re-tiles everything.
  - With 10–20 k points in the evening, that is tens of ms of CPU every few seconds, on the main thread plus the native worker.
- **Fix:**
  - Split into a frozen `recorded-track` source, updated every 60 s or every 200 points, and a small `recorded-tail` source (last ~100 points) updated on every point. Or simply throttle to every 10 s.
  - Put a `pointCount` in `RecState` instead of summing in the UI.
- **Risk:** none. Only drawing changes.

### B5. Compass sensor: faster than needed, unused most of the time, and also on in Plan — LOW-MEDIUM
- **Now:**
  - `map/HeadingEngine.kt:141` registers `TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_UI` (~60 ms). Rotation vector fuses the gyro, accelerometer and magnetometer.
  - Readings are thrown away while moving with GPS or on a followed route (`HeadingEngine.kt:96-98`). That covers most of the time the screen is on during a hike.
  - Because `PUCK_MODE = RenderMode.COMPASS` (`map/MapController.kt:84`), MapLibre counts the compass as used in **every** mode (`LocationLayerController.isConsumingCompass()` ⇔ renderMode == COMPASS, checked in the bytecode). So the sensor also runs in Plan whenever the map is visible. The comments at `MapController.kt:113` and `HeadingEngine.kt:113-115` ("only while … Navigate") are wrong.
  - **Done well:** it *is* released on `onStop`. `MapView.onStop` → `LocationComponent.onLocationLayerStop` → `updateCompassListenerState(false)` + `cancelAllAnimations` (checked in the bytecode). So it does not run with the screen off.
- **Fix:**
  - Use 100–200 ms (`SENSOR_DELAY_NORMAL`, or 100_000 µs) and retune `SENSOR_ALPHA` (≈0.25).
  - Inside `HeadingEngine`, unregister the sensor while `filter.usingRoute/usingGps` is true, and register it again when a fix says "slow", or when the route direction is older than `ROUTE_VALID_MS`.
  - Fix the two comments.
  - Optional: `RenderMode.NORMAL` in Plan, if the owner accepts no heading triangle in Plan.

### B6. Small items on every fix — LOW
- `ui/MapScreen.kt:259-262` calls `setNavGuide` on **every fix**, even when there is no guide. It sets an empty GeoJSON each time, which costs a source update and a render. Skip it when null → null.
- The UI tracker also requests network every 5 s (A2) and registers a second 1 Hz GPS listener while the service has one (`MainActivity.kt:202`, `ui/AppViewModel.kt:97`). The system merges the two GNSS requests, so the extra cost is CPU only.
- **Optional:** during a tour, feed the UI from the service's fixes instead of its own registration.

---

## C. Plan mode / app in the background without a tour

- **Good:**
  - The UI tracker starts in `MainActivity.onStart` (`:202`) and stops in `onStop` (`:209`).
  - MapHost forwards the lifecycle to the MapView (`ui/MapHost.kt:66-85`), and the compass is released on stop (see B5).
  - Nothing location-related runs in the background without a tour.
- **Plan in the foreground:**
  - 1 Hz GPS + network every 5 s + compass sensor + unthrottled puck animation (B2).
  - B2 and A2 apply here too. Plan could use 2–3 s GPS when not following. Low priority.
- **Background:**
  - The `produceState` loop (A7) stays alive in a stopped activity. Without the service's wake lock it only runs when the CPU is awake anyway, and cached apps get frozen. Low.
- **Network:**
  - The update check (`ui/MapScreen.kt:106` → `AppViewModel.checkForUpdates`, gated by `update/AppUpdater.kt:67` to once per 24 h) is a single HTTPS GET. It also runs in Navigate; skipping it while `recState != null` would be cleaner, but the battery effect is negligible.
  - Online search and shared links only run on user action.
- **Vibration:** only for off-route, errors and the plan-mode hold buzz. Short and one-off. No TTS exists (F-NAV-05 is "Could").

---

## D. Android specifics

- **Foreground service type:** correct. The manifest has `foregroundServiceType="location"` + `FOREGROUND_SERVICE_LOCATION`, and `startForeground(…, TYPE_LOCATION)` is called on every start command (`RecordingService.kt:196, 258-262`). Location foreground services have no Android 15 time limit (the 6 h cap applies to dataSync / mediaProcessing), so 14 h days are fine.
- **Battery optimisation:**
  - There is no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` request. There is only a quiet hint plus an "App settings" button (`ui/SettingsScreen.kt:229-236`).
  - `batteryHintShown` (`ui/AppViewModel.kt:387-389`) was never read (dead code, since removed).
  - R-09's "detect when throttled" is missing.
- **Is "Unrestricted" needed?**
  - **Pixel:** a running location foreground service keeps getting fixes under the default "Optimized" setting, and moving with the phone stops deep Doze. "Unrestricted" mainly helps two cases:
    - the START_STICKY restart after the process was killed (Android 12+ limits starting a foreground service from the background, and allow-listed apps are exempt);
    - long stationary breaks, where deep Doze can start (and the wake lock is only honoured for allow-listed apps).
  - **Samsung (sleeping / deep sleeping apps), Xiaomi and Huawei:** these really do kill foreground-service apps, so "Unrestricted" (plus Samsung "Never sleeping apps") is needed for reliability there.
  - "Unrestricted" does not make the app use more power by itself. It only changes what the system allows, but it does make the long wake lock (A3) fully effective.
- **Proposal:**
  - Show the existing hint once at the first tour start, and only when `!PowerManager.isIgnoringBatteryOptimizations(pkg)` or `ActivityManager.isBackgroundRestricted`.
  - Use `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. It needs no permission. Google Play restricts `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to certain use cases, so check the policy before using it.
  - Show both values in Settings → Diagnostics and the diagnostics export.
- **Doze test:** see section G.

---

## E. Already done well

- Recording and navigation run in a location foreground service. The UI is not needed when the screen is off (F-NAV-04).
- Elapsed time is counted from real-time deltas, so delayed ticks do not lose time (`RecordingService.kt:286-288`). This also makes removing the wake lock possible later (mind the 600 s cap).
- Notifications are throttled (5 s), the alert notification is deduplicated (15 s), `setOnlyAlertOnce`, and the recording channel is `IMPORTANCE_LOW`.
- Re-routing:
  - runs on a single-thread executor;
  - a newer job cancels the older one (`RouteCancel` + job cancel);
  - has an 8 s / 12 s budget, at most 3 failures, back-off after "Keep", and a refresh only after 150 m and 30 s;
  - stale results are ignored;
  - the engine is created once and cached.
- Off-route matching is limited to a window (−300 m / +2 km) instead of the whole line.
- `PointFilter` keeps the database and the track small.
- DB stats are written every 10 s, the trip file every 30 s, and `Problems` writes are coalesced.
- MapView lifecycle forwarding is correct. MapLibre releases the compass and cancels animations on stop. The UI's GPS stops in `onStop`.
- `HeadingFilter` has smoothing and a 3° deadband, so there are no tiny bearing changes.
- No Play Services, no network in normal use, and no polling loops in the service other than the 1 s ticker.

---

## F. Prioritised plan

**Quick wins (small, no risk to the track):**
1. B2: `locationComponent.setMaxAnimationFps(15)` + `mapView.setMaximumFps(30)` in Navigate.
2. A2: no network provider in the service; stop network in the UI after the first GPS fix.
3. A1 / A3 step 1: while paused, stop the service's GPS and release the wake lock (resume restarts both).
4. B1: owner decision on the keep-screen-on default. Off, a time limit, or a proximity wake lock.
5. A4: chronometer notification; re-post only on change or once a minute (on status change only while the screen is off).
6. B3: `onCamera` updates only on real changes. B6: skip the empty `setNavGuide`. A7: `collectAsStateWithLifecycle` and a lifecycle-bound status loop.

**Bigger changes (need tests and a field test):**
7. A1: an adaptive GPS policy (standing still → 5–10 s; screen off while hiking → 2 s), with `gpsLost` / `GAP_MS` thresholds tied to the interval. Unit-test the policy like `PointFilter`.
8. B4: recorded track as frozen part + tail sources.
9. A3 steps 2–3: slow ticker when no UI is watching. Then, after measuring, drop the long wake lock in favour of short ones around re-routing and finish (raise the 600 s tick cap first).
10. A5: back off the Question refresh while the screen is off.
11. B5: compass at 100–200 ms and unregistered while route or GPS gives the heading.
12. R-10: a battery saver mode (e.g. below 15 %: GPS 3–5 s, 15 fps map, keep-screen-on off). R-09: throttling detection (gaps in fixes while recording, `isBackgroundRestricted`).

---

## G. How to measure on the Pixel 8

1. **Baseline and A/B runs:**
   - `adb shell dumpsys batterystats --reset`, unplug (or use wireless adb), walk 1–2 h (or replay), then `adb bugreport bugreport.zip`.
   - Load the zip into **Battery Historian**: `docker run -p 9999:9999 gcr.io/android-battery-historian/stable:3.1 --port 9999`.
   - Look at: GPS on-time, the "OnPath:recording" wake lock, CPU running, Wi-Fi scans charged to OnPath, sensors, screen and brightness.
2. **Quick text checks during a tour:**
   - `adb shell dumpsys batterystats --charged com.onpath.app`: wake lock time, GPS time, sensor time, "Wifi scan".
   - `adb shell dumpsys location`: active requests per provider and interval. Today it should show gps 1000 ms ×2 when the UI is visible, and network 5000 ms.
   - `adb shell dumpsys sensorservice`: is the rotation vector active, and at what rate (Plan vs Navigate vs screen off)?
   - `adb shell dumpsys power | grep -i -A2 wake_lock`
3. **Per-component power:** the Pixel 8 has on-device power rails (ODPM). Record a **Perfetto** trace (ui.perfetto.dev → "Power" → power rails + battery counters) for 10 min with the screen on in Navigate, before and after B2. Display, GPU, CPU and GNSS rails show up separately.
4. **Frame rate:** Developer options → "Show refresh rate"; or `adb shell dumpsys SurfaceFlinger --timestats -enable`, then `-dump`, before and after B2.
5. **Doze robustness test** (after any wake lock or GPS change):
   - `adb shell dumpsys battery unplug && adb shell dumpsys deviceidle force-idle` with a tour running.
   - Check that points keep arriving (track continuity, `tours` table).
   - Then `adb shell dumpsys deviceidle unforce && adb shell dumpsys battery reset`.
   - Repeat with battery set to "Optimized" and "Unrestricted", and on a Samsung.
6. **Field metric with no tools needed:** log `BatteryManager.BATTERY_PROPERTY_CAPACITY` (and `CHARGE_COUNTER`) at tour start, every hour, and at the end into the trip file. Show "%/h" in the tour summary and the diagnostics export. This tracks the targets in `docs/requirements.md:259-260`: < 5 %/h with the screen off at 1 Hz, < 15 %/h with the screen on.
