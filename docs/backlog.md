# Backlog (shared by the laptop, desktop and cloud sessions)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see the release notes and
the git log.

## In progress
<!-- - [laptop|desktop|cloud] short description – since YYYY-MM-DD -->

## Next
- **On the next real ride (owner)** – onboarding checklist step 7 (30 min, screen off, battery saver; steps 2–6 passed on
  the Pixel 2026-10-30, step 1 = fresh install → emulator); rest view + smarter cues on the bike and on foot; pocket after
  5 s on the bike; lock-screen notification on the Pixel; the older phone checks below; compass arrow: does it stay grey
  all the time on the Pixel? (1c5b8ad); new off-route question "Abseits der Route – neu planen?" with "Ja" / "Ja, Stopp N
  auslassen" (16, 81dbff7); map symbols: rock from z14, orchards / vineyards stronger.
- **[desktop] Germany pre-built on donald → TEST R2 (owner 2026-10-30)** – all ~500 map cells + region packages (z7–9)
  for Germany, built overnight with `tools/dev/publish_cells.sh` (origin/main, job image, 2–3 at once, low priority),
  doorman adopts them. Production stays cloud-only.
- **Web planner: mirror app changes (keep this list current)** – owner 2026-10-30: every app change the browser should
  mirror gets a line here (what · app commit · what the web does); a done line is removed. web-spike last merged main at
  e8245e5 (10-29); after the next merge `web/parity.json` flags these files too. Drive hand-off: 00 Übergabe,
  "2026-10-30 donald → Web-Planer: App-Änderungen zum Nachziehen".
  - *Must (the web build or the web map breaks after merging main):*
    - Junction cues "only where one could go wrong" · 59802bc, 8706d07, 905a2c7 · `JunctionCues.kt` now needs
      `navigation/CueRule.kt` + `navigation/SpeedWindow.kt` – add both to `appFiles` (pure Kotlin); `GeoPoint.shape`,
      `JunctionShape` (Junctions.kt) and the longer `OnPathHints.java` rows come along by themselves; `CueRuleTest.kt`
      may join `appTests`.
    - Search index on disk · 156c265 · `OfflineIndex` implements `PlaceIndex` and `RouteNamer.placesNear` takes one:
      add `search/PlaceIndex.kt` to `appFiles` (moved out of the SQLite `DiskIndex.kt` on main – pure, no imports,
      kept so by `SharedCoreRuleTest.files`; `DiskIndex.kt` / `SearchDb.kt` / `SearchAreas.kt` stay out). Nothing
      else: all other `appFiles` changed since e8245e5 compile with what the list already has (`AppSettings` →
      `BikeScreen` enum in the same file; `AddPreview` → new strings, generated; sync files → `TrackCode` / `SyncRound`
      only in KDoc, see the sync line). `AddressSearch.kt` now has a `ThreadPoolExecutor` (d9cf983) – JVM compile
      fine; TeaVM never links it, it sits only in `AddressIndex` (companion `pool`), which no web code reaches (the
      web uses `AddressHit` / `AddressQuery` only). Only if the web ever calls `AddressIndex.search`: a stand-in.
    - String `msg_no_location_permission` removed · e7d4e5a · `WebMaps.kt` names it: use `plan_no_location_permission`
      or a web string.
    - f2 style: POI layers read `pois.pmtiles`, `onpath:fallback-sources` → `pois.geojson` · a85d8c7, 6ddc551 (POI
      tiles are built on the phone, the server has none) · mirror `map/OptionalSources.kt`'s swap in `cells.ts`, else
      no POIs on the web map with the next style.
    - Plan-sync track format (A-007) · b15d0a3, a4bea0e · new `SyncItem.kind = 3` (TRACK), field 4 = raw `TrackCode`
      bytes (frozen in `SyncTrackTest`, ≤ 16,000 bytes, Douglas–Peucker); `PlanCode.activityCode` / `activityOf` public;
      merge copies the losing track too; `saveTrack` / `tracks()`, `SyncFormat.cleanTitle`, optional `SyncRound` +
      `Mailbox`. Add `sync/TrackCode.kt` + `SyncRound.kt` to `appFiles` (`SyncTrackTest.kt` + `MemoryMailbox.kt` to
      `appTests`); tracks in My plans (line, rename, delete, never opened as a plan); web-sync.md §5 / §11 / §13.1;
      German menu text like the app ("Handy verbinden"). `fake-d1.js` fails on Node 22.14 (`?1`) –
      `tools/sync/mailbox-server.mjs` rewrites them.
    - Automatic sync + pairing indicator · 5d458ea, 2dc4ea2 · no "Sync now"; sync after a change, on page load, on
      `visibilitychange` (visible), when the network returns; visible + paired: a pass ~every 60 s, doubling on
      errors / offline up to 10 min, reset on success; never in a hidden tab, never two at once. Sync view: "Paired with
      <phone>", "Last synced: just now / 5 min ago" (then the time), error / offline lines, Unpair. Header: phone icon
      with ✓ (tooltip "Pixel 8 ↔ this browser · 1 min ago"), amber on the phone's problems (507 full, refused, local
      storage, last online pass > 6 h after the last good one; never plain offline); "ended from the phone" until the
      sync view is opened. Not paired: neutral "Pair phone".
    - Sync model "one compartment per phone, Speichern sends to the phone" (owner 2026-10-30, after the e2e test) ·
      Drive order A-017 (01 Aufträge) · browser: one store per phone (keyed by the phone's device id) + "Ohne Handy";
      pull automatic, push only with the header's Speichern (bold + "Noch nicht auf dem Handy gespeichert"); no upload of
      older browser routes on pairing ("Zu <Handy> kopieren"); unpair in the browser closes, never ends the mailbox.
      App side (donald, after the format is in web-sync.md §5): JOIN redirect instead of "Unpair first" (the phone
      keeps one mailbox, writes its code into the new browser's mailbox), hello role field (phone / browser), name from
      Settings.Global.DEVICE_NAME else Build.MODEL; "(copy)" shown as "(Kopie)" in German.
  - *Should:*
    - Clearer add buttons · e7d4e5a · place card main button = `AddPreview.label` ("Set as destination", "Add as stop
      2", "Add as new destination"); `subtitle` is now only the reason ("fits best there · about +0.9 km"); small As
      start / As destination left out when the main button does the same; the POI hold menu says the label too.
    - App colours A-009 · e63c11c · primary #245F46, primaryContainer #E1EAD5, background #FAFAF6, text #243C31 – web
      `style.css` still has `--green` #1F6E43 / `--green-light` #DDEFE3.
    - Privacy sentence · 880850d · `web_pair_privacy` to say the same: routes + imported tracks, encrypted on the device,
      we can't read them, recorded tours are never sent, server Cloudflare (EU).
    - Doorman: no plain IPs · 2e851ef · web host: rate-limit key in `index.js` is the plain `cf-connecting-ip` → the
      daily salted `ipHash`; `[observability.logs] invocation_logs = false` in `web/host/wrangler.toml`.
    - Plan header: time + distance never cut (own line when narrow) · e55badb · check the web sheet on a narrow phone.
      The German chip "Wandern · T2" – the web already builds it so.
    - Pairing confirmation · after pairing the browser shows the same short confirmation ('Verbunden – … Gespeichert
      wird auf deinem Handy.', app string `sync_paired_note`, with OK); the route list may say 'Gespeichert auf deinem
      Handy' (owner 2026-10-30).
    - Pull right away when the plans list opens (owner 2026-10-30) · app: a pass when My routes / its Planned tab /
      Browser sync opens (`SyncPoll.opened` / `openDue`), not within 10 s of another pass, never during a tour; the
      ~60 s poll runs only while My routes / Browser sync is shown, no longer on the Plan map · browser: poll only
      while the plans list (or the sync view) is visible, pull on open (same 10 s debounce).
    - Mark plans that came from the phone · (this commit, app side: `plansync/SyncMarks.kt`) · bold title + 'Neu vom
      Handy' / 'Am Handy geändert' (EN 'New from the phone' / 'Changed on the phone') in My plans until opened; seeing
      the list does not clear it, own changes in the browser never mark, a delete / unpair removes it; kept in local
      storage (owner 2026-10-30).
  - *Open order A-016 (replaces A-005):* `/.well-known/assetlinks.json` built on web-spike (ad990c9, v0.13.1 key);
    plan.onpath.earth answers 404 today → deploy the host, let Cloudflare Access pass `/.well-known/*` without login,
    check `adb shell pm get-app-links com.onpath.app`. Until then Android opens `#p=` / `#pair=` links in the browser.
  - *Not for the browser:* lock-screen arrival rounding (30c01c6 – the web shows no arrival; if ever: `TimeEstimate.clock`),
    smart screen / rest view, cue timing, vibration, pocket, lock-screen notification, notifications chip, provisional
    position, GPS-only recording, phone setup (incl. 3b21fc3 texts), demo location, POI tiles + search on disk on the
    phone (beyond the build points above), memory / health line, End-tour ride icons, ☰ phone-setup entry.
- **Phone setup: check brand menu words on real phones** – Xiaomi (Keine Einschränkungen / Beschränkungen, Autostart,
  Akku-Sparmodus), Huawei/Honor (Manage automatically → three switches, DE labels), OnePlus/Oppo/realme (Nicht
  optimieren, Hintergrundaktivität zulassen, Erweiterte Optimierung), Samsung app battery option (Nicht eingeschränkt vs
  Uneingeschränkt) – texts built in 3b21fc3 from support pages; testers' diagnostics carry a "Phone check:" line.
- **Plan sync phone ⇄ browser (A-007) – app part built, check on the phone once the mailbox is deployed** – ☰ → Pair
  with browser / Settings → Browser sync (camera opens the `#pair=` link, check digits, Pair; syncs every minute while open; Unpair asks).
  Saved routes and imported GPX tracks sync both ways (a delete from the browser → recycle bin; never during a tour).
  Tested without a phone: `CorePlanSyncTest`, `SyncFilesTest`, `SyncTrackTest`, and against the real `sync.js` in Node
  (`tools/sync/e2e.sh`). Web side: see "Web planner: mirror app changes".
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
- **Smart screen while cycling – built (e6ee6ed), measure + ride** – rest view for bike activities (owner decisions
  2026-10-30). Still: Pixel power rails A/B on a 10-min demo ride (Always on vs Smart), the owner rides with it.
  From `docs/design/battery-audit.md` still open: slower GPS with the screen off, frozen track + tail.
- **Map: Knotenpunkte + cliffs/fords (owner 2026-10-30)** – cycle junction networks ("Knotenpunkte", NL / BE / DE: OSM
  nodes with `rcn_ref`, `network:type=node_network` – green numbered circles + network lines, own overlay layer,
  switchable in Settings → Map) and cliffs / fords on the map. Owner decision: these map-pipeline changes (together with
  the sidewalk-fragment stub fix, merged with the map batch) go live WITH THE MONTHLY REFRESH – no CELL_SCHEMA bump just
  for them; existing cells get them when their OSM data is 30 days old (Germany ~27.11), newly requested cells right away.
- **Google Maps place → go there (owner)** – when a place arrives from Google Maps (Share → OnPath,
  `receiveSharedPlace`), ask "Route here?" (from the position / the plan's end) instead of only showing the place card.
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
- **requirements.md sync (cloud / owner)** – `docs/requirements.md` is the copy of the shared Claude doc. Needed there:
  activity table with the app's names (Bike, Gravelbike, E-bike, MTB, E-MTB, Hike) and E-bike routing like the Bike
  (touring.brf, assist ~25 km/h); Komoot `<trk><type>` preselects the activity on import, export writes it; F-PLAN-06
  difficulty = distance + ascent + trail technique (hike T3+, bike S2+), hike bands like Komoot.
- **Map: paths look interrupted at junctions (Hilden vs Komoot)** – drawn footways / sidewalks stop short at streets;
  routing is not affected. Leads: `extract_overlays.py` leaves sidewalk / crossing out of paths.geojson; crossings may
  hide under the road casing. Check with a headless render of a Hilden cell (z15–17) first.
- **Router classes** – tertiary and bigger roads have no class (`docs/map-style-notes.md`). The app compares the cells'
  `wayclass` hash (format 2, comments don't count) with its profiles and asks for a refresh (`cells/RouterClassCheck`),
  but the doorman rebuilds only on a CELL_SCHEMA bump or OSM age – a profile change still needs that bump. Later
  (captain): a doorman `WAYCLASS` value (wrangler.toml, = `wayclass.jar --hash` of the job image) so a published cell
  with another hash counts as stale and is rebuilt when asked.
- **CI follow-ups:** lint reports only (known findings in `app/lint-baseline.xml`) – work the baseline down, then let
  lint fail the run; `gradle/actions` stays on v5 (MIT): v6 moves its caching into a closed component under Gradle's
  Terms of Use – owner to decide before upgrading.
- **Small:** Ifinger T1 JVM test (`tools/routing/jvm-test/RerouterJvmTest.kt`, expects "unreachable", BRouter finds
  7.2 km – expectation or gap?); zip import checks only sizes (cell downloads check sha256).

## Waiting for the owner
- **`CF_START_TOKEN` (GitHub secret + the Cloudflare token behind it)** – only needed for the one-time Cloudflare setup
  of plan.onpath.earth (A-008); owner 2026-10-30: keep for now, delete later (GitHub: `gh secret delete CF_START_TOKEN`;
  Cloudflare dashboard → My Profile → API Tokens). The branch `marketing` is already gone.
- **Map server access before the public launch** – today one shared app key + IP rate limit + build caps. Proposal in
  `docs/design/map-access.md` (anonymous per-phone quota of new areas, supporter code, production key with rotation).
  Owner decides limit, supporter platform, timing. Production is not deployed yet (all released APKs use test); when it
  is, it carries everything already on test (e.g. A-014 no plain IPs / no invocation logs, 2e851ef) – the first
  production deploy is part of the launch together with Google Play.
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
