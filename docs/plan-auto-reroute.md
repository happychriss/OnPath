# Plan: automatic re-routing while navigating

Status: **implemented P0–P4 (branch `feat/reroute`, 2026-10-18), field test pending.** Where the text below still says
"automatic", the owner decisions win. Implementation notes:

| Piece | Where | Notes |
| --- | --- | --- |
| Detection (P0) | `navigation/OffRouteDetector`, `navigation/NavLine` | §4 as written: window −300 m / +2 km (+5 km while off), setting + min(accuracy, 20 m), 15 s **and** 25 m (+margin) moved, back on after 3 fixes within half the distance, GPS loss freezes timers (a confirmed state stays). |
| Way back (P1) | `navigation/RerouteTargets`, `navigation/Rerouter`, `RoutingContract.RouteLimits/RouteCancel`, `BRouterEngine` | Option C. Base = progress when leaving, or further when abeam of a later part (shortcut). Candidates: next stop ≤ 5 km, +0.5/1.5/3 km, never beyond the next stop. Score = way back + rest of the original (both `Geo.estimateSeconds`, ties → nearer). **Extra rule:** a way back shorter than 80 % of the route part it replaces (+100 m) is a skip, not a rejoin (round-trip end next to you, way back of an out-and-back) and is not chosen. 8 s per request, 12 s total, cancel via BRouter `terminate()`. The straight-line engine is never used for a way back. |
| Flow (P2) | `navigation/Navigator` (pure), `navigation/NavSession` (`nav-session.json`), `recording/RecordingService` | Off route confirmed → one vibration + notification (sound) → "finding a way back…" → question. Question refreshed silently after 150 m (≥ 30 s). No way / no data → arrow banner, retry after 200 m or 2 min, max 3. "Keep original" → arrow banner, asked again only after 200 m **and** 2/4/8 min (with vibration). After "Take new route": back on the original (30 m into the untouched rest, or 3 fixes on the original near where you left it) → original used again, grey line gone, no vibration. Plan mode: no new questions; a pending one stays. Off-route warning off in Settings = never ask. |
| Notification | `RecordingService.updateAlert` | One ongoing notification on channel `offroute-question` (high, sound, no channel vibration – the service vibrates once), lock-screen visible, actions **Take new route / Keep original**; stays until answered or back on route. The old `offroute` channel is deleted. |
| UI (P3) | `ui/NavigateScreen` (`OffRouteUi`, `RerouteQuestionCard`), `ui/MapScreen.MapSync`, `map/MapController.NAV_LAYERS` | Question card low (thumb reach) above the grouped map controls; banners on top. Map layers `nav-original` (grey, below the followed line), `nav-proposal` (dashed preview while asking), `nav-guide` (dashed line to the route in arrow mode) – all in `ROUTE_OVERLAY_LAYERS` (eye button). |
| Continue navigation (P4) | `AppViewModel.continueNavigation` | No longer edits the plan's waypoints. Edited plan → new session; else the session (and a new route taken) stays. The service checks the next fix at once (`ACTION_CHECK_NOW`) and asks when away from the line (no vibration – the user is looking). |

Tests: `OffRouteDetectorTest`, `RerouteTargetsTest`/`RerouterTest`, `NavigatorTest` (unit); `tools/routing/jvm-test/RerouterJvmTest.kt`
(real BRouter data, 20 cases on 5 routes: all found, ≤ to-destination +10 %, median ~40 ms, max ~250 ms on the laptop JVM;
unreachable target gives up after 8 s, cancel after 0.3 s, no data in 1 ms); Paparazzi `navigateOffRoute*`,
`navigateRerouteQuestion*`. Not done: quiet mode (not needed with ask-every-time), heading for bikes, one matcher for
progress (the to-go strip still uses `FollowProgress`).

## Owner decisions (2026-10-18) – these override the recommendations below

| # | Decision |
| --- | --- |
| Q1 | **Ask every time, never re-route silently.** When off route is confirmed: vibrate, compute the way back in the background, then show a question that **stays until answered** (no timeout, no disappearing banner – hiking in rain/stress): "Off route – new route found (+350 m, rejoins in 600 m)" · **Take new route** · **Keep original**. If the user gets back onto the route by themselves, the question goes away (nothing left to answer). No "Only warn / Off" setting needed for now (ask is the behaviour). |
| Q2 | **Yes:** after taking a new route, the original stays visible as a thin grey line. |
| Q3 | **Skip always:** stops passed by a shortcut are skipped, also POI-pinned ones (no extra question). |
| Q4 | **No extra vibration** for a found route – the vibration comes with the question (Q1). Failure (no way found): question/banner with arrow to the route, same vibration. |
| Q5 | **No separate change** – "Continue navigation" uses the same ask flow when you are off route. |
| Q6 | Not now (no answer = recommendation). |

UX rule from the owner for the whole app: **no messages that disappear by themselves**; banners/questions stay until
answered or until the situation is gone.

**Short version**

- When you are really off the route (distance **and** time **and** you are moving), OnPath **re-routes automatically and
  offline** from your position back onto the route **ahead of you** – never back to a stop you already passed.
- It tries a few rejoin points ahead (the next stop and points 0.5 / 1.5 / 3 km further on the route) and takes the one
  with the shortest total time. The rest of the original route stays exactly as planned.
- One short vibration + banner "New route · +350 m" with a button **"Original route"**. No pop-up, nothing to tap.
- If routing fails (no data, timeout): banner + an **arrow to the route** and the distance, like today's warning.
- The service does it (works with the screen off and in the pocket). BRouter is fast enough: rejoin legs take
  **1–30 ms** on the laptop JVM (phone estimate ≤ 0.2 s), so battery cost is negligible.

---

## 1. Goal and non-goals

**Goal.** Komoot-like "you left the route → here is a new way" for all six activities (hike + 5 bike profiles), but
**fully offline** (Komoot needs mobile data for this, see `docs/komoot-comparison.md`) and **robust first**: no false
alarms from GPS noise, no endless re-routing loops, no U-turn orders, never send the user back to a passed stop, never
lose the original plan, never block or crash the recording.

**Non-goals (for this feature)**
- No off-road guidance (owner decision): the new route always uses ways; straight lines only as a clearly marked
  fallback (R-12/R-13) and as a direction arrow.
- No voice turn-by-turn (F-NAV-05 stays "Could"); vibration + notification + banner are enough.
- No online routing, no traffic, no "alternative routes" choice while walking.
- No automatic changes to the user's **plan** (Plan mode waypoints). Re-routing changes only the *navigation line*.
- No re-routing in Plan mode or during free recording (nothing to go back to).

## 2. Today's behaviour (code as of `5b87fa4`) and gaps

| What | Where | Today |
| --- | --- | --- |
| Off-route check | `recording/RecordingService.checkOffRoute()` | Every accepted fix: `Geo.distanceToLine()` to the **whole** followed line. `> offRouteM` (Settings 25/40/75 m, default 40) for `OFF_ROUTE_MS` = 15 s → one alert: vibration pattern + high-priority notification "You left the route". Back under the threshold → alert cleared at once (no hysteresis). |
| GPS filter | `RecordingService.onFix()` | Only GPS provider, accuracy ≤ 30 m, speed ≤ 35 m/s. `gpsLost()` (> 8 s no good fix) resets the off-route timer (R-04). |
| Plan vs. Navigate | `ACTION_NAVIGATING` | Off-route checks pause in Plan; the recording continues. |
| Followed line | `Prefs.saveFollow()` → `follow.json` | Only **name + points**. No stops, no "plan or track" for the service (`Prefs.followIsTrack` is a VM flag). |
| Banner | `ui/NavigateScreen.OffRouteBanner` | "Off route · 54 m away" while `RecState.offRouteAlert`. Turn card hidden when off route (`nextTurnHint`, `TURN_HIDE_OFF_M` = 40 m). |
| Remaining / progress | `data/FollowProgress.remaining()` (with a `hint` index) | Computed in the Compose layer (`NavigateMode`), not in the service. |
| Re-route | `AppViewModel.continueNavigation()` | **Only manual**, only from Plan ("Continue navigation"): `RouteProgress.remainingStops()` drops passed stops, then `set(listOf(pos) + stops)` **replaces the plan's waypoints** (undoable), re-plans with `Planner`, follows the result (`followNextRoute`). Not for followed tracks (`followIsTrack`). |
| Routing engine | `routing/BRouterEngine` | Blocking, one at a time (static `LOCK`), LRU cache of 8, timeout 15–30 s (`timeoutFor`), `MAX_SNAP_M` 1000 m, via-spike correction 400 m (hike 50 m). `RoutingEngine.route()` has no timeout or cancel parameter. |
| Arrival | `AppViewModel.checkArrival()` + `RouteProgress.arrived()` | Within 50 m of `followLine.last()` and ≤ 150 m of the line left. |

**Gaps:** no automatic re-route; no re-route at all for followed GPX tracks; the service knows no stops; the off-route
state has no hysteresis, no "moving" check and no accuracy margin; the detector scans the whole line (on an
out-and-back route the "way back" counts as "on route"); a re-route edits the plan; long failures (16–30 s) would block
the single routing thread.

## 3. Scenarios

| # | Scenario | What should happen | How (see §4/§5) |
| --- | --- | --- | --- |
| 1 | **Missed turn** (walked on 100 m past a junction) | Off route after ~15 s → new route from here, usually "turn around 100 m" or a parallel way that rejoins | Re-route to best rejoin point ahead; U-turn only if it is really the best |
| 2 | **Deliberate shortcut** (knows a better path) | One quiet re-route; the new route follows the shortcut if it rejoins ahead; no repeated nagging | Rejoin candidates ahead; when the user meets the original route again → back to it automatically; after 3 ignored re-routes in 10 min → "quiet mode" (no more vibration, banner only) |
| 3 | **Detour** (closed path, hut visit, 300 m off and back) | Re-route may show "back to route"; when the user returns onto the original route the original line is used again | "Rejoin detection": within 20 m of the original line ahead of the last progress for 3 fixes → original line restored, no vibration |
| 4 | **GPS jump / tunnel / forest** | No alarm, no re-route | Accuracy margin, ≥ 3 consecutive bad fixes, GPS-lost freeze (R-04), "moved ≥ 25 m" rule |
| 5 | **Standing still** (break, photo, GPS drifts 50 m) | Nothing | Off-route timer only counts while moving (speed ≥ 0.5 m/s or moved ≥ 25 m since leaving) |
| 6 | **Walking back the way you came** (on the line, wrong direction) | No off-route alarm (you are on the route); remaining distance grows; optional small chip "Going back along the route" | Progress decreases while distance to line is small; no re-route |
| 7 | **Round trip / out-and-back** | Never "you have arrived" at the start; never re-route to the end point that is next to you | Progress-windowed matching; target = points **ahead along the line**, not the straight-line-nearest destination |
| 8 | **Followed GPX track (no stops)** | Same as a plan: rejoin the track ahead; never route to the track end directly (it would skip the track) | Candidates on the line only (no stop) |
| 9 | **Off the routing data** (no rd5 tile) | Warning + arrow + distance to the nearest route point ahead; no error pop-up | `BRouterEngine.checkSegments` fails fast → fallback "arrow mode" (R-12) |
| 10 | **No route found** (only via paths above SAC limit, island) | Same fallback; one retry after the user moved 200 m or 2 min | Short re-route timeout (8 s), back-off (R-13) |
| 11 | **Very long route** (100 km bike) | Re-route stays quick | Only the first ≤ 3 km (or up to the next stop ≤ 5 km) are re-routed; the rest is spliced from the original line |
| 12 | **Low battery** (< 15 %, R-10) | Re-route still works; fewer candidates | Battery saver: 1–2 candidates, GPS rate unchanged by this feature |
| 13 | **Free recording** (nothing followed) | Nothing | Detector off |
| 14 | **App killed / phone restart while off route** | After the sticky restart the current (re-routed) line and the original are both back | Navigation session persisted (§7) |

## 4. Detection: when are we really off route?

Move the logic out of the service into a **pure, unit-tested state machine** `navigation/OffRouteDetector` (input:
fix lat/lon, accuracy, speed, bearing, elapsed time; output: state + distance + progress). The service feeds it.

### 4.1 Matching to the line (progress)
- Keep a **progress index** on the active line (like `FollowProgress.remaining(hint)`), and search only a window
  around it: from `progress − 300 m` to `progress + 2 km` (whole line only after a GPS gap or at start). This fixes
  out-and-back and round trips (the way back does not count as "on route" when you are on the way out) and makes the
  check cheap on long lines.
- Progress moves forward with the user; it may go backwards only while the user is on the line (scenario 6).

### 4.2 States and thresholds

| State | Enter when | Leave when |
| --- | --- | --- |
| `ON_ROUTE` | start, or rejoin rule | distance > **D_off** for one good fix → `LEAVING` |
| `LEAVING` | see above | distance ≤ D_off → `ON_ROUTE`; distance > D_off for **T_off = 15 s** and moved ≥ **25 m** since entering → `OFF_ROUTE` |
| `OFF_ROUTE` | confirmed | re-route starts (auto) or user is back (rejoin rule) |
| `REROUTING` | re-route request running | result → `ON_ROUTE` on the new line; failure → `OFF_NO_ROUTE` |
| `OFF_NO_ROUTE` | re-route failed | rejoin rule, or retry (moved 200 m or 2 min) |
| `NO_GPS` | no good fix for 8 s (`gpsLost`) | 3 good fixes in a row; timers start from zero (R-04) |

- **D_off = setting (25 / 40 / 75 m, default 40) + accuracy margin** `min(accuracy, 20 m)`. A fix with 25 m accuracy
  needs ~60 m distance. Fixes with accuracy > 30 m are ignored as today (R-05).
- **Rejoin rule (hysteresis):** distance ≤ **D_on = D_off / 2** (20 m by default) for **3 good fixes** (~3–5 s),
  on the original line or the current line, **ahead of** the last progress − 300 m. Original line wins when both match →
  the re-route is dropped silently (scenario 3).
- **Heading** is used only as a tie-breaker (which of two nearby line parts you are on) and for scenario 6 — not to
  declare "off route" (GPS bearing is noisy at walking speed; `HeadingFilter.MOVING_MPS` = 1 m/s).
- **Moving check:** the 15 s count only runs while the user has moved ≥ 25 m away from where `LEAVING` started, so a
  break with GPS drift never triggers (scenario 5).

## 5. Re-route strategy

### 5.1 Options

| | A. Rejoin ahead (fixed point) | B. To next stop / destination (today's "Continue") | **C. Best rejoin ahead (recommended)** | D. Prompt instead of automatic |
| --- | --- | --- | --- | --- |
| What | Route from position to the line point ~1 km ahead, then the original line | `Planner` from position through the remaining stops | Route to 2–4 candidates ahead (next stop if ≤ 5 km along, line points +0.5 / +1.5 / +3 km), pick min. **leg time + original time from that point**; splice | Same routing as A/B/C, but only after the user taps "Reroute" in the banner |
| Keeps the plan shape | yes | partly (BRouter may choose a different way between stops) | **yes** | – |
| Works for GPX tracks | yes | **no** (no stops; routing to the track end skips the track) | **yes** | – |
| Round trip / out-and-back | yes | **danger**: destination = start, near you at the beginning | **yes** (targets along the line) | – |
| Passed stops | never targeted | `RouteProgress.remainingStops` drops them (works, tested) | never targeted (candidates are ahead of progress) | – |
| Risk of silly detours | **high**: a point 300 m ahead across a river cost a 3.3 km leg (see §5.3) | low | low (several candidates) | – |
| Cost on the phone | 1 request (~10 ms JVM) | 1 request per chunk, up to the destination (≤ 200 ms JVM for 65 km) | 2–4 short requests (≤ 30 ms each JVM) | same |
| UX | automatic | automatic | automatic, with "Original route" | needs a tap with gloves / phone out of the pocket; against "pocket use" |

D is **not** recommended as the default: on a bike or with the phone in a pocket the user cannot tap. It stays as a
setting ("Only warn").

### 5.2 Recommendation: C + automatic, with these defaults

| Parameter | Default | Note |
| --- | --- | --- |
| Off-route distance | Settings 25 / **40** / 75 m + accuracy margin (≤ 20 m) | existing setting |
| Off-route time | **15 s** while moving (≥ 25 m) | as today (F-NAV-02) |
| Back on route | ≤ D_off/2 for 3 fixes | hysteresis |
| Candidates | next stop if ≤ 5 km ahead along the line; line points at +0.5, +1.5, +3 km (not beyond the next stop) | at most 4 requests |
| Score | `leg.durationS + originalTimeFrom(candidate)` (BRouter time, so climbs count) | min wins; ties → nearer candidate |
| Request timeout | **8 s** each, **12 s** total per re-route | engine needs an optional timeout (§7); today 15–30 s |
| Rate limit | at most 1 re-route per **30 s**, doubling (60 s, 2 min) while the user keeps ignoring new routes; reset when back on a line | no loops (OsmAnd: 3 s × 1.5 up to 120 s) |
| Failure back-off | retry after 200 m moved or 2 min; after 3 failures: arrow mode only until back on route | R-13 |
| Quiet mode | 3 re-routes in 10 min that the user did not follow → no vibration for re-routes until back on the original line | deliberate shortcut / own way |
| Cache | keep BRouter's LRU; candidates are new coordinates each time, so no reliance on cache | |

**Settings (simple):** one row under the existing off-route setting:
"When off route: **Reroute automatically** · Only warn · Off" (default: Reroute automatically).
The distance chips stay. Nothing else exposed.

**Passed stops:** a stop is passed when visited (≤ 40 m, `RouteProgress.PASSED_RADIUS_M`) **or** behind the progress
index — same rules as `RouteProgress.remainingStops`, but using the detector's progress instead of the nearest segment.
A stop skipped by a shortcut is therefore not sent back to. (Komoot / Garmin also have "skip next via point" – not
needed with this rule; see question Q3.)

**U-turn avoidance:** BRouter 1.7.11 has `RoutingContext.startDirection` / `forceUseStartDirection` (service params
`heading` / `direction`). It fakes a point 1000 m behind the start, so the profile's normal turn cost applies to the
first turn: a U-turn costs about 2 × `turncost` (trekking/fastbike 90 → ~180 m extra; **hiking `turncost 0` → no effect**).
In our experiment (on the route, heading backwards) it changed **nothing** (§5.3). For bikes we can still pass the GPS
heading when moving ≥ 2 m/s – cheap, a small nudge. So U-turns are handled by our candidate choice: a U-turn back to a missed junction wins only when it is really
the fastest way to the route ahead. That is the right answer for a missed turn (scenario 1).

### 5.3 Measurements (JVM, laptop: Ryzen 7 5850U, BRouter 1.7.11, `E10_N45.rd5`)

`tools/routing/jvm-test/RerouteTest.kt` (run: `KTTEST=$PWD/tools/routing/jvm-test/RerouteTest.kt
tools/routing/jvm-test/run.sh /home/development/Projects/onpath-build/out/segments`). Position = a point on the route at
30 % / 60 %, moved 100 m / 250 m to the right. "Total" = rejoin leg + rest of the original line.

| Case | at / off | Rejoin +300 m | Rejoin +1 km | To destination | Total via +1 km vs. to destination |
| --- | --- | --- | --- | --- | --- |
| Hike Algund → Schenna (6.8 km) | 30 % / 100 m | 14 ms, 339 m | 24 ms, 1041 m | 48 ms, 4823 m | 4822 vs 4823 m |
| | 60 % / 250 m | 10 ms, 469 m | 15 ms, 1083 m | 25 ms, 3007 m | 2795 vs 3007 m |
| Hike Meran 2000 → Ifinger hut | 30 % / 250 m | 2 ms, 219 m | 2 ms, 923 m | 2 ms, 1633 m | same |
| Trekking Bozen → Meran (33 km) | 30 % / 100 m | 4 ms, 373 m | 4 ms, 1010 m | 115 ms, 23.3 km | same |
| | 30 % / 250 m | 11 ms, **3266 m** | 9 ms, 2629 m | 93 ms, 24.9 km | 24.95 vs 24.91 km |
| | 60 % / 100 m | 6 ms, 1624 m | 6 ms, 2314 m | 39 ms, 14.3 km | same |
| MTB Algund → Vellau | 30 % / 250 m | 26 ms, 642 m | 28 ms, 1304 m | 28 ms, 2366 m | same |
| Trekking Bozen → Mals (88 km, long) | 30 % / 100 m | 5 ms, 1327 m | 7 ms, 2145 m | 197 ms, 64.9 km | 64.8 vs 64.9 km |

Other numbers from the same run:
- First route after start (loads the rd5 + profile): **300–760 ms**; after that warm.
- All rejoin legs: **median 6 ms, max 28 ms**; to destination: median 39 ms, max 197 ms (88 km route).
- **Unreachable** target (hike SAC T1, Meran 2000 → Ifinger summit): fails only after **16 s** (`MIN_TIMEOUT_MS`
  15 s). A good re-route needs < 1 s, so a re-route timeout of 8 s is safe and avoids blocking the routing thread.
- `startDirection` with `forceUseStartDirection` (on the route, heading forward / backward / none; trekking and
  hiking): identical route every time – the ~180 m U-turn penalty (trekking) is smaller than any alternative.
- Phone: no direct measurement yet (field test item). Expect roughly 3–5× slower than the laptop JVM → rejoin
  candidates well under 0.5 s together.

**What the numbers say:** (1) Routing is cheap – there is no need for "prompt to save battery". (2) For a plan made by
BRouter, "to destination" and "rejoin + original" give almost the same total (BRouter rejoins its own optimum), so B
works for plans – but **not** for tracks, round trips or very long routes. (3) A **single fixed** rejoin point can be
bad (3.3 km leg to reach a point 300 m ahead, across the Etsch valley), hence several candidates (C).

## 6. UX in Navigate mode

Rule (requirements §7): no pop-ups while navigating except off-route and critical battery. All of this is banners in
the existing top column of `NavigateOverlay` (turn card → status card → off-route banner) + the notification.

| Moment | Screen (Navigate) | Phone in pocket / screen off |
| --- | --- | --- |
| Leaving (< 15 s) | nothing (maybe the existing small "54 m off" text in the status card) | nothing |
| Off route confirmed, auto on | amber banner "Off route · finding a way back…" (spinner) | — (re-route takes < 1 s; no vibration yet) |
| Re-route found | green banner "**New route** · +350 m · rejoins in 600 m" + button **"Original route"** (48 dp, 15 s, then the banner shrinks to a chip). New line in route blue; the original line stays as a **thin grey** line. Turn card shows the first turn of the new line. | **one short double vibration** (different from the long off-route pattern); notification text "New route – +350 m" (silent update of the ongoing notification, no heads-up) |
| Re-route failed / no data | amber banner "Off route · 120 m · no way found" + **arrow to the nearest route point ahead** with distance | **long off-route vibration** (today's pattern) + heads-up notification (today's `alertOffRoute`) |
| Only-warn setting | today's banner + big button "**Reroute**" | today's vibration + notification with action "Reroute" |
| Back on original route | banner disappears, grey line goes, no vibration | — |
| "Original route" tapped | new line removed, navigate to the original line again (detector won't re-route again until 200 m further off route or 2 min) | — |

- Gloves: only one button, 48 dp+, top of screen, no swipe needed. Everything works without touching.
- The plan in Plan mode is **not** changed. Plan shows the original plan; the "Tour running" bar says
  "Tour running · rerouted" when a re-route is active.
- Text stays plain English (later `strings.xml` EN + DE).

## 7. Architecture

### 7.1 Owner: the recording service (not the ViewModel)
**Decision: `RecordingService` owns detection and re-routing**, through a new pure package `navigation/`:

| New piece | Pure? | Job |
| --- | --- | --- |
| `navigation/OffRouteDetector` | yes | §4 state machine, progress index, rejoin rule |
| `navigation/RerouteTargets` | yes | candidates ahead, passed-stop rules, scoring, splice "leg + rest of original" |
| `navigation/NavSession` | data | what is navigated: kind (PLAN / TRACK), name, original line (+ cumulative time/ele), stops with names/pins, current line, re-route count, quiet mode, last re-route time |
| `navigation/Rerouter` | no (engine) | runs candidates on a single background thread, timeout, cancel |

Why the service: F-NAV-04 (screen off, background) and the pocket scenario need it; the Activity/ViewModel may not
exist (R-07 restart, R-15 interruptions). The service already gets every fix, already has the wake lock and the
off-route alert, and `BRouterEngine` only needs a `Context`. Battery: re-routing is rare and short (§5.3, < 1 s CPU per
event), and detection with a window is cheaper than today's whole-line scan, so the 5 %/h target (§8) is not at risk.

### 7.2 Data flow
- "Start navigation" / "Continue navigation" (ViewModel) writes a **`nav-session.json`** (atomic tmp + rename, like
  `Prefs.saveFollow`) with the original line and the stops, and sends `ACTION_FOLLOW_CHANGED`. `follow.json` stays as
  the "current line" for backwards compatibility (old sessions load as TRACK without stops).
- On a re-route the service writes the new current line to `follow.json` + session, and publishes it in `RecState`
  (new fields: `navLine`, `navLineVersion`, `originalLine`, `reroute: RerouteUi?`). The ViewModel collects
  `RecordingService.state` and updates `followLine` when the version changes → `NavigateMode` recomputes
  `FollowProgress` and `TurnDetector.turns` (both already keyed on `remember(line)`).
- Arrival (`checkArrival`) uses `followLine.last()`, which the splice keeps → no change. Progress for the "to go" strip
  could later come from the detector too (one matcher instead of two) – phase 4.
- `continueNavigation()` later uses the same `RerouteTargets` (phase 4) so that it stops editing the plan; until then it
  keeps working as today and simply starts a new session.

### 7.3 Threading, cancel, supersede
- Service fixes run on the main looper; the re-route runs on a **single-thread dispatcher** in the service. A newer
  request cancels the older one (`Job.cancel` + BRouter `RoutingEngine.terminate()`, which exists in 1.7.11).
- `BRouterEngine`'s static `LOCK` serializes service and ViewModel requests (same process). A re-route in Navigate and a
  plan edit in Plan cannot overlap in practice; the 8 s re-route timeout bounds the wait.
- **Contract change (small):** `RoutingEngine.route(..., timeoutMs: Long? = null)` (default = today's behaviour). Update
  `docs/architecture-contract.md`. Cloud owns `routing/BRouter*` → cloud does this part.

### 7.4 Crash recovery (R-07)
`nav-session.json` + `follow.json` are written before the new line is shown. After a sticky restart the service reloads
both; the detector starts in `NO_GPS` and re-matches with a full-line search. Track points are untouched by this
feature (still written per fix).

### 7.5 Interaction with existing parts
| Part | Change |
| --- | --- |
| Off-route alert (`alertOffRoute`) | used only for "re-route failed / only warn"; re-route success = short vibration + silent notification update |
| `TurnDetector` / turn card | works on the new line; hidden in `OFF_ROUTE` / `REROUTING` |
| `FollowProgress` / to-go strip | new line → new totals (includes the extra metres) |
| Recording | unchanged; re-route events could be logged later (debug) |
| Plan mode | detector paused (as today); re-route kept; Plan shows the original plan |

## 8. Edge cases and failure handling

| Case | Handling |
| --- | --- |
| No route found / timeout (R-13) | keep current line, arrow + distance to nearest line point ahead, long vibration once; retry after 200 m / 2 min; after 3 failures stop retrying until back on route |
| Outside rd5 coverage (R-12) | engine fails fast ("No routing data") → same arrow mode; banner "No routing data here" |
| GPS lost (R-04) | `NO_GPS`: no timers, no re-route; after return 3 good fixes, then a full re-match |
| Position far from any way (meadow, > 1 km, `MAX_SNAP_M`) | engine error → arrow mode (no off-road guidance) |
| Re-route result starts > 150 m from the user (snapped far away) | treat as failure for the banner text ("nearest way 300 m"), still draw it |
| Destination reached while rerouted | normal arrival logic |
| User slides to Plan during a re-route | result is kept and shown when back in Navigate |
| Plan edited in Plan while a tour runs, then "Continue navigation" | new session replaces the old one (original line = new plan) |
| Two quick re-routes | rate limit 30 s; newer supersedes older |
| Memory | BRouter requests are short; same `-Xmx` budget as planning |

## 9. Test plan

1. **Pure unit tests (cloud, no device):** `OffRouteDetectorTest` with simulated traces generated from real route
   lines (the JVM test can dump Bozen/Meran lines as JSON fixtures): missed turn, 50 m GPS jump, tunnel gap 60 s,
   standing still with 40 m drift, walk back along the line, out-and-back, round trip start/end, rejoin after a hut
   detour. Plus a few **recorded traces** from the owner's field tests (GPX from My tours → fixture).
   `RerouteTargetsTest`: candidates never behind progress, skips passed stops, stops at next stop, splice keeps the
   destination, round trip never targets the start at the beginning, scoring picks the cheaper candidate.
2. **JVM BRouter tests:** extend `tools/routing/jvm-test/RerouteTest.kt` (this commit) into pass/fail checks: chosen
   candidate total ≤ "to destination" + 10 %; timing budget; unreachable target fails within the 8 s re-route timeout.
3. **Paparazzi:** Navigate with "finding a way back…", "New route · +350 m" + Original route button, "no way found" +
   arrow, Settings row with the three choices.
4. **Field test checklist (owner, Pixel 8):**
   - [ ] Walk past a junction on purpose by 100 m → re-route within ~20 s, sensible way, one short vibration.
   - [ ] Take a known shortcut → one re-route, then back to the original route without vibration.
   - [ ] Hut visit 200 m off the route and back → original line again, no repeated alarms.
   - [ ] Pause 5 min under trees → no alarm.
   - [ ] Phone in pocket, screen off, bike → vibration felt, new route visible when taking the phone out.
   - [ ] Tap "Original route" with gloves.
   - [ ] Follow an imported GPX track and leave it → rejoins the track ahead.
   - [ ] Round trip: start and end near the car park → no early arrival, no re-route to the start.
   - [ ] Airplane mode the whole time; battery % per hour noted.
   - [ ] Note re-route times shown in logcat (`adb logcat -d | grep -i reroute`, lines "reroute #n: found in … ms").

## 10. Phases (small, shippable)

| Phase | Content | Who | Effort |
| --- | --- | --- | --- |
| **P0** | `navigation/OffRouteDetector` (pure) with progress window, accuracy margin, moving check, hysteresis; service uses it for today's warning (no re-route yet). Unit tests. | cloud | S (½ day) |
| **P1** | `RerouteTargets` (pure) + `RoutingEngine` timeout parameter + `BRouterEngine.terminate` on cancel; JVM tests (this experiment → assertions). | cloud (routing owner) | M (1 day) |
| **P2** | `NavSession` persisted; service runs `Rerouter`; `RecState` carries the line; ViewModel follows it; vibration/notification; Settings "When off route". | cloud or laptop (claim) | M (1 day) |
| **P3** | Navigate UI: banners, grey original line, "Original route" button, arrow mode; Paparazzi. Install + walk test. | **laptop** (device feel) | M (1 day) |
| **P4** | `continueNavigation()` uses `RerouteTargets` (no plan edit); one matcher for progress; quiet mode tuning from field data; release. | either | S–M |

Each phase is a separate release-able commit set on `main` behind the default "Reroute automatically" only from P2 on
(P0/P1 change no visible behaviour except fewer false alarms).

## 11. Open questions for the owner

| # | Question | Recommendation |
| --- | --- | --- |
| Q1 | Automatic re-route by default, or only warn and offer a "Reroute" button (F-NAV-02 says "offer")? | **Automatic** (pocket / bike use), with "Original route" undo; "Only warn" as setting. Update F-NAV-02 text. |
| Q2 | When re-routed, keep showing the original route as a thin grey line? | **Yes**, so a deliberate own way stays understandable. |
| Q3 | A stop you walked past by a shortcut: skip it automatically, or route back to it? | **Skip automatically** once you are past it on the route; a POI-pinned stop (hut you added on purpose) asks via the banner "Skipped Brunnenburg – go back?" (one button). |
| Q4 | Vibration for a successful re-route: short double buzz, or nothing (only the banner)? | **Short double buzz**; the long pattern only when no way back was found. |
| Q5 | Should "Continue navigation" (Plan → Navigate) stop changing the plan's waypoints and use the same re-route? | **Yes** (phase 4) – the plan stays what you planned. |
| Q6 | Offer a quick "Reroute now" button in Navigate even when on route (e.g. after a closed path)? | **Not now**; a closed path means you walk off the route anyway and the auto re-route follows. Later: "Avoid this way" (BRouter nogo) if field tests show the need. |

## 12. How other apps do it (research)

Sources read on 2026-10-18; manuals/help pages marked (doc), forum reports (forum, uncertain).

| App | Off-route detection | Re-route | Back to route vs. destination | Notes |
| --- | --- | --- | --- | --- |
| **OsmAnd** (source) | 60 m, or 30 m + accuracy, × 2 deviation multiplier → ~120 m; same for walk/bike; user setting "Minimal distance to recalculate" overrides | automatic; also on **wrong direction** (heading > 90° off); U-turn hint only if heading > 135° for 10 s *and* no forward route | GPX: start at "start of track" or "nearest point"; "Attach to the roads" (50 m) | back-off 3 s × 1.5 up to 120 s; partial recalculation reusing the previous route |
| **Organic Maps** (source) | matching threshold **20 m walking, 30 m cycling**; counter +1/+2 per non-matching fix, rebuild after > 10 (≈ 6–11 fixes) | automatic | to destination | heading not used for rebuilding; finish tolerance 15 m |
| **Locus Map** (doc) | user "distance for recalculation" (default not documented) | automatic (Premium), retries "after 30 seconds" if the new route is not followed | **"Point priority"** = to the next via point, **"Route priority"** = back to the original line at the nearest possible place; "Off" = guidance back to the original route; "Strict route following" leads back to the last missed point | beyond "maximum allowed deviation": simple guidance (arrow) instead of turn-by-turn; sends a start `direction` to BRouter |
| **Komoot** (doc, partly 403) | not published | "Automatic rerouting / Auto Replan" toggle, only "if a better alternative is found"; **needs internet** | Garmin app: re-routes back to the planned route, "original komoot route is not permanently changed" | offline: only "off tour" + direction back (review 2023) |
| **Garmin Edge 840/1040** (doc + forum) | not published | Course/Route recalculation: **Prompted / Automatic / Off** (forum); on prompt the first option "shortest route back to the course … starts automatically after 10 seconds" (doc) | back to course | "Pause navigation" as an alternative |
| Gaia GPS, Outdooractive, Google Maps (walk/bike) | no official documentation found | – | – | – |

Sources:
- OsmAnd `RoutingHelper.java`, `RoutingHelperUtils.java`, `RouteRecalculationHelper.java`:
  https://github.com/osmandapp/OsmAnd/tree/master/OsmAnd/src/net/osmand/plus/routing ;
  settings https://docs.osmand.net/docs/user/navigation/guidance/navigation-settings ;
  GPX navigation https://docs.osmand.net/docs/user/navigation/setup/gpx-navigation
- Organic Maps `routing_session.cpp`, `routing_settings.cpp`: https://github.com/organicmaps/organicmaps/tree/master/libs/routing
- Locus Map manual: https://docs.locusmap.app/doku.php?id=manual:user_guide:functions:navigation:settings ;
  blog https://www.locusmap.app/another-playing-with-locus-navigation-settings/
- Komoot: https://support.komoot.com/hc/en-us/articles/5247137024282 , https://support.komoot.com/hc/en-us/articles/10207935661338 ;
  review https://www.velotouring.fun/review-komoot-navigation
- Garmin Edge 840 manual: https://www8.garmin.com/manuals-apac/webhelp/edge840/EN-SG/GUID-AC0FFBE2-C018-48D7-8B48-2684BF25D7D0-8699.html ,
  https://www8.garmin.com/manuals/webhelp/GUID-17DE938E-466A-4746-BDBF-7A6FC1B3A32C/EN-US/GUID-62C3249E-F56E-467B-A9DA-83D907CDF4E6.html ;
  forum https://forums.garmin.com/sports-fitness/cycling/f/edge-1040-series/297022/course-vs-route-recalculation

**BRouter 1.7.x** (source `brouter-core`, https://github.com/abrensch/brouter ; service docs
https://github.com/abrensch/brouter/blob/master/docs/developers/android_service.md ; readme https://brouter.de/brouter/readme.txt):
- Start heading: `heading=<deg>` → `startDirection` + `forceUseStartDirection` (always used); `direction=<deg>` → only used
  in a "fast partial recalc". Implemented as a fake point 1000 m behind the start + normal `turncost`
  (`OsmPath`, `StdPath`). **No dedicated U-turn parameter.** (Fields confirmed in our 1.7.11 jar with `javap`.)
- **Fast partial recalculation:** with `RoutingContext.rawTrackPath` BRouter stores the last result and, when the
  next request has the **same destination** (± ~2 m), same profile and nogos, searches only until it meets the old
  track (`fastPartialRecalc`). Not needed at our speeds; a possible optimisation for very long bike routes later.
- No "route from current position / remaining points" API: the caller sends position + remaining vias + destination.
- `correctMisplacedViaPoints` (+ `…Distance`, default 400 m), `waypointCatchingRange` (default 250 m; we use 1000 m),
  `nogos` (`lon,lat,radius[,weight]`, weight = soft penalty), `polylines` / `polygons`, `turnInstructionMode` 0–7,
  `alternativeidx` 0–3, `engineMode` (4 = round trip), `allowSamewayback` (mirrors vias for out-and-back – not a
  U-turn switch).
- Phone routing times: no published benchmarks (service default timeout 60 s). Our laptop numbers are in §5.3.

**Take-aways for OnPath:** thresholds of 20–40 m + accuracy and a persistence of several fixes / ~10–15 s are normal;
everybody re-routes automatically by default (Garmin even after a 10 s prompt); Locus' "route priority" = our option C,
"point priority" = option B; back-off between re-routes is standard; nobody relies on heading for detection except
OsmAnd's "wrong direction" rule (which we deliberately skip for hikers – scenario 6).
