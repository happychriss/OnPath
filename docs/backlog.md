# Backlog (shared by the laptop, desktop and cloud sessions)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see the release notes and
the git log.

## In progress
<!-- - [laptop|desktop|cloud] short description – since YYYY-MM-DD -->

## Next
- **Phone setup – check on the Pixel (0.13.0)** – the appendix checklist of `docs/design/onboarding-phone-check.md`
  (first start welcome, deny twice, approximate, notifications off, vibration test with Alarm vibration on / off,
  battery Restricted, screen-off ride with battery saver); brand steps (Samsung, Xiaomi, Huawei, OnePlus) are not
  checked on such phones – testers' diagnostics now carry a "Phone check:" line. Small follow-up: a quiet plan-sheet
  line for recommended items instead of the guide before a tour, if the guide feels like too much.
- **Flaky under full local load:** `NoStaleDownloadsTest.pointNoLongerWantedIsNotDownloaded` failed once in a full local
  run, green alone and in CI – watch it.
- **Web planner (branch web-spike): take over the app's plan-sync format (A-007, main 880850d)** – additive, old web code stays
  safe. New `SyncItem.kind = 3` (TRACK), field tag 4 = raw `TrackCode` bytes (`sync/TrackCode.kt`, frozen in `SyncTrackTest`;
  max 16,000 bytes, thinned by Douglas–Peucker); `PlanCode.activityCode` / `activityOf` now public; merge copies the losing
  track too; `saveTrack` / `tracks()`, `SyncFormat.cleanTitle`, optional `SyncRound` + `Mailbox`. To do: add
  `sync/TrackCode.kt` (+ `SyncRound.kt`) to `appFiles` in `web/engine/build.gradle.kts`; show tracks in My plans (line,
  rename, delete, never open as a plan); web-sync.md §5 / §11 / §13.1 (owner: tracks are synced); German menu text to
  match the app ("Handy verbinden"). Note: `fake-d1.js` fails on Node 22.14 (`?1` placeholders) – `tools/sync/mailbox-server.mjs` rewrites them.
- **Plan sync phone ⇄ browser (A-007) – app part built, check on the phone once the mailbox is deployed** – ☰ → Pair
  with browser / Settings → Browser sync (camera opens the `#pair=` link, check digits, Pair; Sync now; Unpair asks).
  Saved routes and imported GPX tracks sync both ways (a delete from the browser → recycle bin; never during a tour).
  Tested without a phone: `CorePlanSyncTest`, `SyncFilesTest`, `SyncTrackTest`, and against the real `sync.js` in Node
  (`tools/sync/e2e.sh`). Web planner: track items (kind 3, field 4 = `TrackCode`) to show / keep – see `sync/TrackCode.kt`.
- **Check on the phone (0.11.6 – 0.12.0, built without a phone):**
  - junction vibration: the app vibrated at every junction on the 2026-10-28 ride (trace "vibrated=true"), the owner
    felt nothing → Pixel Settings → Sound & vibration → Vibration & haptics → *Alarm vibration*, Do Not Disturb /
    Bedtime (our usage is ALARM); since 0.12.0 no cue for slight bends on a bike;
  - roundabout: drawn sign and "Roundabout · exit N" on the turn card / lock screen (exit number only unit-tested);
  - Navigate over the lock screen (power button during a tour), Plan asks to unlock;
  - recorded heights above sea level (first 15 s without height), compare with the map;
  - "Plan a route from it" on the owner's own GPX tracks;
  - pinch zoom keeps direction-up; one "Following stopped: map gesture" (18:25) unexplained – the trace names every
    stop, export diagnostics right after it happens; the arrow at the dot in Plan north-up follows the phone;
  - the brief blank map after planning to New York (not reproduced here – needs the trace);
  - the plan sheet follows the finger 1:1 (CI emulator: 0.69–0.80 of the finger travel; the trace logs "Sheet let go
    at X px …");
  - POI order while zooming at Bad Waldsee with schema-4 cells; the "Waiting for GPS · last fix …" line in Navigate
    is not cut;
  - older features never phone-tested on purpose: re-routing P0–P4, arrival / time estimate, End-tour ride summary,
    "Route back to the start?", slower GPS at rest, map freshness.
- **Re-routing: smarter choice of where to rejoin (owner; the release after next)** – `navigation/RerouteTargets`
  offers the next stop (≤ 5 km ahead) and line points 500 / 1500 / 3000 m ahead (`docs/plan-auto-reroute.md` §5).
  When should the way back skip the next route point (much closer to the one after) – or ask? Work from real cases:
  the owner's ride of 2026-10-28 (left the plan at km 0.5–0.8 and 6.0–6.8, up to 380 m) and routes the owner uploads.
  Idea: skip when the detour is far longer and the point is not POI-pinned / named; ask only when it might matter.
- **[desktop] Smart screen while cycling (owner decisions 2026-10-30)** – Bike activities only (Bike, Gravel, E-bike, MTB,
  E-MTB; Hike keeps the pocket logic): far from the next turn a **rest view** – black (OLED), big next-turn arrow,
  distance, time left, dimmed; the map stops drawing. Full map again ~**50 s before a turn** (time at current speed,
  at least 150 m), ~10 s after it, on a tap (~30 s), off-route, arrival, GPS lost. Setting "Screen while cycling:
  Always on / Smart / Like the phone", **Smart is the default for bike activities**. **No lower frame rate while the
  map is visible** (owner: "never"). Measure first and after: Pixel 8 power rails (display / CPU / GPU / GNSS) on a
  10-min demo ride. From `docs/design/battery-audit.md` still open: slower GPS with the screen off, frozen track + tail.
- **Cycle junction networks ("Knotenpunkte", NL / BE / DE) on the map (owner)** – OSM nodes with `rcn_ref`
  (`network:type=node_network`): green numbered circles + the network lines, own overlay layer, switchable in
  Settings → Map. Needs `extract_overlays.py` + `make_style.py` + a cell schema bump (donald / map pipeline).
- **Google Maps place → go there (owner)** – when a place arrives from Google Maps (Share → OnPath,
  `receiveSharedPlace`), ask "Route here?" (from the position / the plan's end) instead of only showing the place card.
- **Plan links: publish `/.well-known/assetlinks.json` on plan.onpath.earth (Drive order A-005)** – test key SHA-256 in
  the A-005 report; until then Android opens the links in the browser unless "Open by default" allows OnPath.
- **Two MainActivity instances at start (seen only on the first start after an install)** – one process, two
  activities, each its own ViewModel; shared state is already per host (`ui/MapReload.kt`) and the event log names
  every "Screen #n created / destroyed". Next: read those lines after the next install; if it is a plain launcher
  start while #1 lives, `finish()` the new one in `onCreate`. Bigger option (owner OK first): one app-wide state holder.
- **Web planner (`docs/design/web-planner.md`, branch `web-spike`)** – phase 1 (plan links into the app) is built;
  further phases as in the design doc.
- **Route hints – open candidates (numbers in `docs/design/bike-routing.md` "Route hints")** – faint trail
  (trail_visibility bad / horrible: 2 % of hikes, 11 % of MTB routes – owner question); ferry name / landing (not in
  BRouter's data – from our overlays / POIs); times and seasons, borders (own data needed); hints in Navigate ("Ferry in
  2 km"); Bike / Gravel over smoothness=very_horrible (a profile question). Not planned: bollards, gates, barriers,
  access=destination / agricultural, node bicycle=no, toll.
- **Map cells: partial rebuild (`docs/design/map-hosting.md` §7h)** – a look-only schema bump rebuilds every layer.
  Per-layer schema in `cells.py` / the manifest; `job_build.py` rebuilds only the changed layers when the extract date
  is the same and copies the rest (R2 CopyObject, sha256 kept). Hilden: cloud 9.7 → ~4.2 min, donald 179 → ~80 s.
- **requirements.md sync (cloud / owner)** – `docs/requirements.md` is the copy of the shared Claude doc. Needed there:
  activity table with the app's names (Bike, Gravelbike, E-bike, MTB, E-MTB, Hike) and E-bike routing like the Bike
  (touring.brf, assist ~25 km/h); Komoot `<trk><type>` preselects the activity on import, export writes it; F-PLAN-06
  difficulty = distance + ascent + trail technique (hike T3+, bike S2+), hike bands like Komoot.
- **Map: paths look interrupted at junctions (Hilden vs Komoot)** – drawn footways / sidewalks stop short at streets;
  routing is not affected. Leads: `extract_overlays.py` leaves sidewalk / crossing out of paths.geojson; crossings may
  hide under the road casing. Check with a headless render of a Hilden cell (z15–17) first.
- **Router classes** – the app does not compare the cells' `wayclass` hash with its own profiles (a profile change
  needs a rebuild of all cells); tertiary and bigger roads have no class (`docs/map-style-notes.md`).
- **Safety:** cliffs and fords on the map.
- **CI:** `verifyPaparazziDebug` and lint do not run in CI; `actions/checkout@v4` / `setup-java@v4` give Node 20
  warnings.
- **Compass accuracy gating; DEM-based ascent** (today GPS altitude with 5 m hysteresis).
- **Small:** Ifinger T1 JVM test (`tools/routing/jvm-test/RerouterJvmTest.kt`, expects "unreachable", BRouter finds
  7.2 km – expectation or gap?); zip import checks only sizes (cell downloads check sha256).

## Waiting for the owner
- **Map server access before the public launch** – today one shared app key + IP rate limit + build caps. Proposal in
  `docs/design/map-access.md` (anonymous per-phone quota of new areas, supporter code, production key with rotation).
  Owner decides limit, supporter platform, timing.
- **Google Play internal test track** – create the app, secret release key + `PLAY_SERVICE_ACCOUNT_JSON` in GitHub
  secrets, upload from `release.yml` (today: fixed test key). Removes Play Protect warnings.
- **Footways without a bicycle tag (Hilden Itter path)** – Bike / Gravel profiles avoid them (push penalty). Option:
  allow paved footways with an amber "footway – may need to push" note?
- **AGP 9.4.1 upgrade** – later, when the owner says so.

## Later
- **iPhone version** – direction and order in `docs/design/multiplatform.md`; core logic free of Android
  (`SharedCoreRuleTest`).
- **Open source + self-hosted map servers (concept, not built):** app setting "Map server" (custom URL + key, also via
  QR code; cells carry their server in the manifest); `/v1` API as an open spec; all-in-one Docker Compose or the cloud
  setup; builder image on ghcr.io; F-Droid build without Play Billing. Not before the Play launch.
- **Ideas:** outdoor mode, way back on own track, hut info, tour suggestions, first-start intro / help page.
- **Map wishes (only when the owner says so):** shrub symbols; trail numbers abroad beyond numeric refs (E5 / GR20,
  `osmc:symbol`); contours below z12.
- **Unverified paths (owner: keep):** untagged OSM ways joining two paths, ≤ 300 m, grey dashed + point-menu hint,
  never routed (example way 330547188 near Algund).
- Tune the difficulty thresholds (Easy / Moderate / Difficult) with real tours.
