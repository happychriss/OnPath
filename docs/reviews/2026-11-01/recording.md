# Pre-release review – recording / navigation / voice / DB / backup (cloud-app)

Reviewer focus: recording/, navigation/, recording/Speaker, rides/, time/TripPace, data/TourDb, backup/, the arrival flow, UI ⇄ service.
Paths relative to `app/src/main/java/com/onpath/app/`. Read-only review, nothing changed. No BLOCKER found: every path I
looked at keeps the points already written to SQLite. The HIGH ones break guidance in the pocket, or can lose
track points without the user being told.

## HIGH

### H1 – Plan mode during a tour turns off the off-route warning, also with the phone in the pocket
- Where: `ui/AppViewModel.kt:3294` (enter → ACTION_NAVIGATING on=false), `recording/RecordingService.kt:195-200`, `navigation/Navigator.kt:243` (`… && navigating && …`), `Navigator.kt:415` (far start likewise).
- Scenario: at a café the rider opens Plan to look at the route or add a point, then pockets the phone without tapping "Continue". For the rest of the ride no off-route vibration, voice, notification or question comes. The junction vibration and voice still play (`junctionCue` / `voiceStep` do not check `navigating`), so the rider thinks guidance is on. The off-route state is held and asked only when the app is next in Navigate.
- Fix: count as navigating whenever the app is not on screen (`AppVisibility.visible == false` → navigating = true), or switch back to Navigate on screen-off / onStop during a tour. Add a NavigatorTest for "Plan, then app backgrounded → off route asks".
- Certainty: confirmed (code). Owner rule "no questions in Plan" – this only changes what happens when the app is off screen.

### H2 – Arrival only works with the app on screen; after arrival the tour still raises off-route alarms
- Where: `ui/NavigateScreen.kt:681-683` (LaunchedEffect → `vm.checkArrival`), `ui/AppViewModel.kt:3665-3672` (uses the app's own `tracker`, which stops in the background), `SAY_ARRIVED` only from there; `navigation/Navigator.kt:205-250` has no "destination reached" state; `navigation/RerouteTargets.kt:83` (with nothing left, the target is the end of the line).
- Scenario: a hike or ride with the phone in the pocket reaches the destination. No "You have arrived" (voice-guidance.md promises it), no arrival question. The rider walks 60 m on to the car park or hut: LEAVING → OFF_ROUTE → long vibration, "Off the route. Please look at your phone.", a route back to the destination, and a heads-up with sound. After "Keep" it asks again after 2 / 4 / 8 min. OsmAnd and Organic Maps end guidance at the destination and keep recording.
- Fix: move the arrival check into the service (Navigator or a service ArrivalGate on GPS fixes). Once arrived, the service sets "arrived": no more off-route questions or junction cues, it says Arrived itself and publishes `RecState.nav.arrived`, which the UI shows as the dialog. This also takes one of three progress computations off the UI (FollowProgress + RouteProgress + Navigator).
- Certainty: arrival only in the UI = confirmed; the alarm after arrival = strong suspicion (no code stops it, no test covers it).

### H3 – The service restarts itself after the process dies: it may sit at "Searching GPS…" with nothing recorded, and no watchdog notices
- Where: `recording/RecordingService.kt:260-279` (null intent → `goForeground()`), manifest without ACCESS_BACKGROUND_LOCATION (owner decision), `RecordingService.kt:391/1086` (gpsLost only changes the status word), `VoiceGuide.kt:126-129` ("No GPS" said once).
- Scenario: a 4 h ride; an OEM memory kill or a crash anywhere in the process (UI, MapLibre) kills the process, and START_STICKY brings the service back from the background. On Android 14+ startForeground of a location FGS from the background throws, and `failLoud` posts a notification. Good, but the text says "could not start", not "stopped – open OnPath to continue". On Android 11–13 (or OEM variants) the FGS may start without while-in-use location. The listener is registered but gets no fixes. The notification says "Searching GPS…" for hours, and the user believes it is recording.
- Fix: (a) on the null-intent restart without background location (SDK ≥ 30), don't pretend. Post "Recording interrupted – open OnPath and tap Continue" (like TourInterruptedReceiver) and stop. (b) Watchdog in the ticker: no GPS fix for N min (e.g. 5) while not paused → one loud notification + vibration ("GPS has delivered nothing for 5 min – open OnPath"). Test on Android 12 / 13 / 14 with `adb shell am crash com.onpath.app` during a tour.
- Certainty: suspicion (platform behaviour differs by version; needs a device test).

## MEDIUM

### M1 – Notification id clash: "walking while paused" and "recording cannot be saved" share id 44
- Where: `recording/RecordingService.kt:1181-1183` (`PAUSE_ALERT_ID = 44`, `SAVE_FAIL_ID = 44`), `:875` (`setPaused` cancels 44), `:921`, `:859`.
- Scenario: storage full → the ongoing "Recording cannot be saved" warning goes up. Pause / Resume (or the paused reminder) cancels or overwrites it. `saveFailed` stays true, so it is never posted again: points keep failing with no warning on the lock screen.
- Fix: a separate id (e.g. 46) for SAVE_FAIL / failLoud. One-line change + test.
- Certainty: confirmed.

### M2 – Points that failed to write are dropped for good, though they are still in memory
- Where: `recording/RecordingService.kt:477-483`.
- Scenario: storage runs full mid-tour (photos, map cells), or a transient SQLite error (locked / IO). Each failed point stays in `track` (it is drawn) but never reaches the DB. Once space is freed, writing resumes but the gap stays. After a restart, a Continue or the end, that part of the drawn track is missing.
- Fix: keep unwritten points in a small queue (seq taken at accept time). On the next successful write, flush the queue in one transaction. Test with a store that throws N times.
- Certainty: confirmed.

### M3 – An uncaught exception in the service kills the process, and with it the recording
- Where: `recording/RecordingService.kt:110` (scope without a CoroutineExceptionHandler), `:627-644` (handle after a re-route), `:222-246` (action handlers call Navigator directly), `:320/577` (`loadFollow` / `Navigator(...)` outside try in the start path), `:966` / `:837` (catch only SecurityException around `nm.notify`).
- Scenario: a geometry edge case in `Navigator.onResult` / `take` / `question` (TurnRound, NavLine) or a non-SecurityException from `notify`. onFix is guarded ("navigation must never cost the track") but these paths are not. The process crashes and, with H3, the tour stops. A bad `nav-session.json` that throws in `Navigator(...)` crashes on every sticky restart.
- Fix: add `CoroutineExceptionHandler { _, e -> Problems.error("recording", …) }` to `scope`. Wrap each `handle(navigator.…)` in the action branches and in the re-route result like onFix does. In the start path, on a failing `loadFollow`, record without navigation and say so.
- Certainty: confirmed structurally (no crash seen).

### M4 – The activity-switch question counts as "off route"
- Where: `navigation/Navigator.kt:71` (`offRoute` = true for every Question, also `switch != null`), `recording/RecordingService.kt:688, 719-720` (onRoute = prompt == null), `:1087` (status), `:790`.
- Scenario: the user picks another activity in the check sheet while on the route. The voice says "Off the route. Please look at your phone." The lock screen status turns to "Off route". Junction vibration and voice stop until the question is answered (it stays, by design, also on the route).
- Fix: `offRoute` false for `Question.switch != null`. Allow junction cues / voice while only a switch question is open.
- Certainty: confirmed (code reading).

### M5 – Battery saver turned on mid-tour is not noticed
- Where: only checked before the tour (`ui/setup/PhoneStateReader.kt:72-73`); the ticker `RecordingService.kt:361-389` does not watch it.
- Scenario: a long day hike; auto battery saver turns on at 15–20 %. On phones whose location mode for battery saver is "GPS off when the screen is off" (some OEMs), the track stops whenever the screen is off. Only "Searching GPS…" says so. This classic tracker failure is caught only by the setup check.
- Fix: register `ACTION_POWER_SAVE_MODE_CHANGED` in the service. If `locationPowerSaveMode` is not NO_CHANGE / FOREGROUND_ONLY, post one HINWEIS notification + vibration ("Battery saver stops GPS with the screen off").
- Certainty: suspicion (OEM dependent).

### M6 – Moving time loses the fraction of every tick
- Where: `recording/RecordingService.kt:371` (`((now - lastTick) / 1000.0).toLong().coerceIn(1, 600)`).
- Scenario: `delay(1000)` on Main resumes after 1000 ms + the work of the tick (+ frames while the map is on screen). Each tick adds floor(): a 1.9 s tick counts 1 s. Over 4 h that is about 1–3 % too little time (minutes). The lock-screen chronometer is re-anchored to `elapsedS` every minute and jumps back.
- Fix: count milliseconds (`elapsedMs += now - lastTick`, cap 600 000) and derive `elapsedS`.
- Certainty: confirmed (size depends on load).

### M7 – Tour DB corruption mid-tour: the new points land in a fresh DB with no tour row
- Where: `data/TourDb.kt:65-82` (KeepCorruptFile moves the DB aside, the helper reopens a new empty DB), `:317-322` (`updateStats` ignores 0 rows), `RecordingService.kt:477`.
- Scenario: SQLITE_CORRUPT during a tour → the file is moved aside → the next `appendPoint` writes into the new DB for a `tour_id` that does not exist. `saveFailed` clears. At the end the tour is in neither the old nor the new DB (only in `.corrupt-*`), and the backup has nothing to copy.
- Fix: `updateStats` / the service check the affected rows. Tour row gone → insert it again with the same id (or a new tour plus the in-memory track) and failLoud. Rare, but it is exactly the case where the user loses the tour.
- Certainty: suspicion (rare path, code reading).

## LOW

- L1 – **False "Back on the route" after a plan edit while off route.** `RecordingService.loadFollow` (`:577`) builds a new Navigator (prompt null) but `voice` keeps `lastOffRoute = true` → `VoiceGuide.kt:117-120` queues BackOnRoute (newLine, but not rerouted). Fix: reset the off-route memory of VoiceGuide on FOLLOW_CHANGED. Suspicion.
- L2 – **"No GPS signal" is said 30 s after starting indoors**, before the route was ever reached (`VoiceGuide.kt:126-129` has no `started` guard; the ticker feeds it while gpsLost). The doc says "while guided". Guard with `started` / hadGpsFix. Confirmed.
- L3 – **Speaker:** started lazily on the first sentence (`RecordingService.kt:706-710`, though its doc says "at the tour start"). The first "Off we go" waits for TTS init, and a `pending` junction sentence may come seconds late with an old distance. There is no re-init after the TTS engine dies or updates (state stays READY, every `speak` fails → only warnings). Ringing (MODE_RINGTONE) is not "in call" (`Speaker.kt:120`). Fix: `voiceSpeaker()` at the tour start when voice is on; drop `pending` older than ~5 s; re-create the TTS after N errors. Confirmed.
- L4 – **Voice vs vibration with chains of turns:** JunctionCues gives one cue for the whole chain (`JunctionCues.kt:59-60`, prevNeeded), VoiceGuide merges only the next one (`VoiceGuide.kt:170-172`). On three close switchbacks the voice may announce the third without a vibration. Cosmetic; use the same chain rule. Suspicion.
- L5 – **No copy of the running tour outside SQLite.** Backups run only for "done" tours (`backup/BackupQueue.kt:72`). On a multi-day or 8 h tour the DB is the only copy until the end (see M7). OsmAnd writes its GPX every few minutes. Option: a GPX snapshot of the active tour every ~15 min to `files/` (not Documents). Design suggestion.
- L6 – **Main thread / allocations on long tours:** `trackSnapshot = track.map { it.toList() }` copies the whole track on every accepted point (`RecordingService.kt:949`), and the UI redraws it every second. `Geo.length(st.line)` runs in each `publish` (`:1070`). Fine for 2 h; check at 8 h / 30 k points (jank, battery). Append-only snapshot / cached length. Suspicion.
- L7 – **Leak:** `geoidExec` (`RecordingService.kt:734`) is never shut down – one idle thread per service instance. Confirmed.
- L8 – **Silent fallback (CLAUDE.md "no silent errors"):** `activityOf` (`RecordingService.kt:999-1000`) and `NavSession.kt:146-148` turn an unknown or missing activity into HIKE / TRACK without a Problems line. A bike tour with a bad name would get hike cues and timing. Add `Problems.warn`. Confirmed.
- L9 – **Lost plan edit (race):** the VM writes `nav-session.json` + follow.json and sends FOLLOW_CHANGED (`AppViewModel.kt:3809-3820`). If a re-route result is handled first, the service overwrites both (`RecordingService.kt:605-611`), then loads its own session in `loadFollow` (`:559-563`). The edit is dropped quietly. Narrow window. Fix: the version / a token in the intent. Suspicion.
- L10 – **Unfinished "Save" vs late sticky restart:** `saveUnfinished` marks the tour done on IO while a late sticky restart may already be recording it. The ticker then writes `state='active'` every 10 s (`RecordingService.kt:380`) and RideApply has already run on it. The guard checks only `recState` at tap time. Low probability. Suspicion.

## Things checked and found fine
- Every accepted point goes to SQLite at once with `insertOrThrow`. `seq` advances only on success. WAL; the start path keeps the tour "active" on every failure (Continue / Save offered). Continue resumes in a new segment from `recordingCursor`.
- Two tours at once: `finishOtherActive`; a double Start answers with startForeground (no "did not call startForeground").
- FGS type location + FOREGROUND_SERVICE_LOCATION; MapLoadService handles `onTimeout` (Android 15 dataSync limit); the update install is asked first during a tour; BOOT / PACKAGE_REPLACED receiver notifies.
- GPS off or permission revoked mid-tour: the listener stays registered (resumes when on); revoke kills the process → restart → failLoud on missing permission.
- Off-route hysteresis (15 s + 25 m, rejoin 3 fixes at half distance), GPS-lost freezing, the refresh / keep back-off (2 / 4 / 8 min) and stale-result ids look sound. Re-routing is off the main thread and cancellable.
- Vibration uses USAGE_ALARM (passes background / DND). TTS uses navigation-guidance usage + transient-duck focus, offline voices only, quiet in a call.
- Time model uses elapsedRealtime for timers and the GPS time for point speed checks. No DST issue; a manual clock change only affects the shown arrival clock.
