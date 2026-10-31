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
- **Voice guidance (owner 2026-10-31) – after the feature stop (first the ride with the checklist)** – owner: "every tester
  asks whether it talks". Android's own text-to-speech (`TextToSpeech`, no Play Services; only voices that need no
  network), one voice for everything incl. names; all speech from one place (a `Speaker` interface) so the voice can
  later be swapped for pre-made recordings (whole sentences, e.g. Piper / "Thorsten") if testers find it poor.
  Three parts:
  1. *Phone setup:* new step "Voice" like the vibration test – offline voice for the app language there (else guide to
     download it), test sentence, "Heard it?" Yes / No (No → volume hint); row in Settings → Phone setup.
  2. *Spoken texts:* the same content as the screen, but own strings in whole sentences, units written out, distances
     rounded ("In 150 Metern scharf links.", not "Scharf links · 170 m"); ~30–40 strings EN + DE, the owner reviews them.
  3. *When to speak:* only where we vibrate today (`CueRule`) – once before the turn and "now"; priorities (off route
     before a turn), nothing repeated; questions with buttons only say "look at the phone" ("Abseits der Route. Neue
     Route möglich – bitte aufs Handy schauen."); arrival "Ziel erreicht" / stop reached; music ducked, Bluetooth
     headsets, silent during a phone call; setting per bike / hike: Off / Turns only / All. Rules pure + unit-tested;
     the sound itself only on the owner's ride. Effort ~3–4 days. Changes the decision "Voice: not planned".
- **[desktop] Germany pre-built on donald → TEST R2 (owner 2026-10-30)** – all ~500 map cells + region packages (z7–9)
  for Germany, built overnight with `tools/dev/publish_cells.sh` (origin/main, job image, 2–3 at once, low priority),
  doorman adopts them. Production stays cloud-only.
- **Web planner: mirror app changes (keep this list current)** – owner 2026-10-30: every app change the browser should
  mirror gets a line here (what · app commit · what the web does); a done line is removed. web-spike last merged main at
  e8245e5 (10-29); after the next merge `web/parity.json` flags these files too. Drive hand-off: 00 Übergabe,
  "2026-10-30 donald → Web-Planer: App-Änderungen zum Nachziehen".
  - *Must (the web build or the web map breaks after merging main):*
    - Island retry without reflection (Köln Dom) · (this branch, 2026-10-31) · BRouter jars renamed to
      `1.7.11-beta-onpath1` (brouter-core: `islandNodePairs` package-private, `tools/routing/patch_brouter_jar.py`),
      `OnPathHints.islandRetries` / `skipOnSnap` – run `node web/parity/check.mjs --update` (new jar names) and make
      Köln Dom → Neumarkt a normal gate case in `web/test/plans.json` (1334 m); fingerprint changes once – steps in
      `docs/handoff-web-island.md`.
    - My routes: "New" / "Changed" tags instead of bold + grey line (owner 2026-10-31) · (this branch, 2026-10-31) ·
      the web's My routes shows the phone's `mark` as the same light blue tag after the name ("Neu" / "Geändert"),
      same look (`#DDEAFB` / `#0B3D91`, 12 px bold pill, never cut) – steps in `docs/handoff-web-route-tags.md`.
    - Plan sync fields 7 / 8 (routing fingerprint + rd5 dates, owner 2026-10-31) · (this branch, 2026-10-31) · shared
      `sync/SyncItem.kt` (`SyncItem.routing` / `routingData`, `SyncRoutingData`, `SyncLedger.save(…, routing,
      routingData)`) – no new file; the page fills both when it saves (its engine's `RoutingFingerprint` + the days of
      the rd5 it routed with) and shows a grey line "Planned on the phone with other routing – the route may differ
      slightly" when the phone's fingerprint differs; web-sync.md gets §5.2 – text and steps in
      `docs/handoff-web-sync-fingerprint.md`.
    - One plan computation: `routing/PlanResult.kt` · (this branch, 2026-10-31) · `Engine.plan` calls
      `PlanResult.plan` (route + model time, snapped points – pins, named, far and skipped stops kept – gaps, warnings,
      rating, profile) instead of its own copy; the page adopts the snapped points; add `routing/PlanResult.kt` +
      `routing/ProfileData.kt` to `appFiles`, delete `profileJson` / own time, rating, warnings and `WebPlanRules.gaps`
      calls – steps in `docs/handoff-web-planresult.md`.
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
      App side **done on main** (A-017 phone side, format = web-sync.md §5.1 on web-spike 0e675f3): a paired phone that
      scans another browser's code shows its check digits, Pair → `JoinRound` claims that browser's mailbox if needed
      and PUTs exactly one JOIN item (kind 4, id `SyncJoin.ID` = first 16 bytes of SHA-256("OnPath join") =
      `sAZCnWUrch6J4Mt9nYTAzw`, device = the phone's device id = folder key, field 5 = the phone's own pairing code as
      `PairCode` text, sealed with the browser's keys; 409 → once more on the returned rev); "Unpair first" is gone.
      Hello: field 6 = role varint (1 phone, 2 browser; missing = unknown) – the phone writes role 1. Web to do:
      `SyncItem` / `SyncFormat` / `SyncLedger` / `SyncMerge` changes come with the shared files (no new file; the
      phone-only `JoinRound` sits in `SyncRound.kt`); after a pull `SyncJoin.secretIn(ledger)` → adopt that code,
      DELETE the own mailbox, read the phone's from 0; write the own hello with `ledger.hello(device, name, now,
      SyncItem.ROLE_BROWSER)` (a call without role keeps the stored one); name the folder after the hello with
      `role == ROLE_PHONE`. Phone name = Settings.Global.DEVICE_NAME, else Build.MODEL. Copy marker: items keep
      " (copy)" (`SyncMerge.COPY`, unchanged – the web's titles stay as they are); the app shows "(Kopie)" via
      `SyncMerge.shownTitle` / `storedTitle` (pure, shared – the web may use them with `web` strings). Frozen bytes:
      `PlanSyncTest.joinItemIsFrozen` (also from Python hashlib). Phone device id now in Android backup + device
      transfer (only `sync/device.txt`): a reinstalled phone opens its old folder. Node e2e: `SyncMailboxE2ETest.
      aSecondBrowserJoinsThroughTheRealMailbox`; `tools/sync/e2e.sh` now copies every `web/host/src/*.js` (sync.js
      imports maps.js).
    - Mark plans that came from the phone (order A-018) · 4673d12 (app side: `plansync/SyncMarks.kt`) · bold title +
      'Neu vom Handy' / 'Am Handy geändert' (EN 'New from the phone' / 'Changed on the phone') in My plans until opened;
      seeing the list does not clear it, own changes in the browser never mark, a delete / unpair removes it; kept per
      compartment in local storage (owner 2026-10-30). web-spike 14938ba has marks (A-017 point 7) but only
      `font-weight: 700` – make it as clearly bold as the app (ExtraBold vs SemiBold) and check it is live.
    - Map look per activity (owner 2026-10-31: routes and map look identical in browser and app) · `map/ActivityLayers.kt`
      (pure, shared) · the web always drew the Hike look (bike layers hidden): add the file to `appFiles`, engine call
      `activityLayers`, apply it in `main.ts` on `load` and in `setActivity()`, smoke test Bike / Hike T4 – exact steps
      in `docs/handoff-web-activity-layers.md`.
    - Round trip delete / reorder like the app (owner 2026-10-31: plans identical) · `PlanPoints.deleteAt` (app map
      menu Delete now keeps a loop closed: `[a, b, c, a]` → `[b, c, b]`) · the web's `deletePoint` opens the loop
      (`[b, c]`) and `edit "move"` uses `PlanEdits.move` (breaks a loop on a list drag): `edit "delete"` →
      `PlanPoints.deleteAt`, `"move"` → `PlanPoints.move`, `deletePoint` through the engine, meta by value – steps in
      `docs/handoff-web-parity-b.md` §3.
  - *Should:*
    - "Connected" only with a live hello of the other side (field bug 2026-10-31, owner: "why does my phone say
      connected when the browser doesn't?") · (this branch, 2026-10-31) · the app now reads the browser hellos
      (`plansync/BrowserLink`: live → connected, all deleted → "Unpaired in the browser" + pair again by JOIN, none →
      waiting) and labels its own last pass "synced 1 min ago". The web's `status.paired` = pair + open folder only and
      `phoneHello` skips a deleted hello without saying so: a phone hello written as deleted would still show "Paired
      with Pixel 8". Today the phone never deletes its hello (Unpair DELETEs the mailbox → 410 → "ended"), so this is
      a guard: in `refresh()` a phone hello seen and now deleted → show "Unpaired on the phone" (grey, Pair phone), and
      the pill's "synced … ago" from `lastSync` (this browser's pass) labelled as such, never as the phone's time.
    - Routing-data notice + way-type / surface line shared (owner: app = web) · (this branch, 2026-10-31) · new pure
      `data/RoutingDataNotice.kt` (`kindForRoute` → LOADING / NOT_YET, keys `maps_routing_*`) and
      `routing/BreakdownRules.kt` (labels, order, ARGB / hex colours, `foldSmall`, `summaryLine` „Radweg 92 % ·
      Asphalt 98 %“): add both to `appFiles` (+ `RoutingDataNoticeTest`, `BreakdownRulesTest` to `appTests`); the plan
      sheet shows the same notice line and the way-type / surface line with bars – steps in
      `docs/handoff-web-notice-breakdown.md`.
    - Search list never jumps for the same text (field bug 0.13.2, "olive hilde…") · (this branch, 2026-10-31) · shared
      `SearchRanking.keepShown` (rows on screen stay, a later answer for the same text only adds below) and
      `SearchRanking.stillMatching` (online rows of the shorter text that still match the longer one stay in its list)
      in `search/OfflineIndex.kt` (already in `appFiles`). `WebSearch.online` keeps `mix(keepShown = …)` (still
      there), but the page should keep the list it shows per text and carry online rows across keystrokes the same way
      (see `ui/search/SearchSession.run`, test `SearchStableListTest`).
    - Six short search chips (owner 2026-10-31): Orte · Essen · Sehenswert · Wasser · Bahn · Parken (Places · Food ·
      Sights · Water · Transit · Parking) · (this branch, 2026-10-31) · the table is `SearchKinds.Category` in
      `search/Place.kt` (pure, already in `appFiles`; `Category.of(kind)`, strings `search_cat_*`), test
      `SearchCategoryTest`. The web can compile it as is: give the browser planner's search the same six chips
      (scrolling row) and filter with `SearchQuery.category`; the Places chip also keeps street / address hits
      (`SearchRanking.rank` filters addresses by the chip's kinds now).
    - Map symbols: rock / scree from z14, stronger orchard / vineyard · 2026-10-31 · nothing to code – `copy-engine.mjs`
      copies `style/f2/onpath.json` + `sprites/` on the next merge of main; just check them on the web map.
    - One message system · `ui/MessageCard.kt` (2026-10-31, `docs/design/ui-style.md`) · the browser uses the same three
      kinds and look: FRAGE (white card, blue left edge, two verb buttons, until answered), HINWEIS (one button – OK or
      the one way out; grey / amber ⚠ / red ⚠ edge, never goes by itself), ANZEIGE (no box, a line in place); one box at
      a time (a question first), 12 dp under the top bar; green filled primary button, green text button beside it.
    - One plan GPX export · `gpx/PlanGpx.kt` (pure, shared) · the web's `WebApi.gpx` writes the line only: use
      `PlanGpx.export` (named route points as `<wpt>`, sport, "<name> - plan.gpx"); on import `PlanGpx.stopsOf` first
      (an exported plan comes back exactly, no fitting) – `docs/handoff-web-parity-b.md` §1–2.
    - Routing fingerprint · `routing/RoutingFingerprint.kt` (pure, shared; app: Settings → Diagnostics) · the web shows
      the same line (fingerprint + rd5 dates) – `docs/handoff-web-parity-b.md` §4; sync fields 7 / 8 + hint only a
      proposal (`docs/design/routing-fingerprint.md`, owner to decide).
    - A stop on a way is always visited, a skipped stop is never moved (Bug 01, 2026-10-31) · 060f35e, 0ef8dd3,
      d5a7db5 · matched points now come from `OnPathHints.matched` (no reflection) and the Planner's spur rule is
      relative – both come along with the merge (shared files; `PlannedRoute.spurs` is now `skipped`, the web never
      read it), so the browser re-routes to the stop by itself (check: TU Dortmund 51.48667, 7.41685 by Bike, the line
      goes down the steps to stop 3 and back). Web to do: `WebPlanRules.gaps` passes the skipped stops,
      `PlanGaps.of(wps, p.snapped, p.skipped)` – then a stop still skipped shows its grey gap and the red line "Route
      misses point 3 by 50 m" (new string `plan_gap_skipped`, generated); the markers already stay where they were set.
      Island points (Köln Dom, `IslandTest.kt`): `snappedWay` is now read in `OnPathHints` too, but BRouter's
      `RoutingEngine.islandNodePairs` is *private* – `islandRetries` / `skipOnSnap` stay reflection, so the browser
      still gets no second snap for an island point (needs a BRouter patch or a subclass hook; low prio).
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
    - Pairing confirmation · (this commit, app side) · after pairing the browser shows the same sentence with OK, until
      OK: 'Verbunden. Was du im Browser speicherst, kommt auf dein Handy – dort bleibt alles gespeichert.' (EN 'Paired.
      What you save in the browser comes to your phone – everything is kept there.', app string `sync_paired_plan`);
      the phone goes straight back to the Plan map and shows it there as a strip with OK (first pairing and a joining
      browser alike); the route list may say 'Gespeichert auf deinem Handy' (owner 2026-10-30).
    - Pull right away when the plans list opens (owner 2026-10-30) · app: a pass when My routes / its Planned tab /
      Browser sync opens (`SyncPoll.opened` / `openDue`), not within 10 s of another pass, never during a tour; the
      ~60 s poll runs only while My routes / Browser sync is shown, no longer on the Plan map · browser: poll only
      while the plans list (or the sync view) is visible, pull on open (same 10 s debounce).
    - Save at hand (owner 2026-10-30) · (this commit, `RouteRules.saveAtHand`) · app: green tonal "Speichern" beside ⋯
      in the plan sheet's first line (icon only at a large font) and "Route speichern" on top of ☰ – only while the plan
      is new / changed, hidden once saved. Web already has Speichern in its header (A-017, bold when unsaved); differs
      only in that it stays visible when saved – fine as is unless the owner wants it alike.
  - *Low prio:*
    - No WebGL2 → blank page (owner 2026-10-30) · seen in a headless browser test; old browsers / hardware acceleration
      off hit it too · instead of a blank map the browser shows: „Dein Browser kann die Karte nicht zeichnen (WebGL2
      fehlt). Bitte einen aktuellen Chrome, Firefox oder Edge nutzen und die Hardwarebeschleunigung einschalten.“
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
- **(low prio) Online place search depends on photon.komoot.io (owner 2026-10-30)** – `search/OnlineSearch.kt`
  (`PhotonProvider`, created in `AppViewModel`). Public use is allowed in reasonable volume, but no availability
  guarantee and possible throttling – an operational risk with many testers / a public launch. Today the offline index
  of loaded areas is searched first; Photon only adds places outside loaded maps (rough position ~1 km), so an outage
  means "nothing found outside loaded areas", not a crash. Small step: check we use it gently (own User-Agent / app id
  – `PhotonProvider.userAgent` exists, check it names the app; typing debounce, cache, back-off on errors), make the
  endpoint configurable, a calm line when it fails. Later (with the public launch / map server access): an own
  lightweight place index per country (towns, peaks, huts – not every address), built in the map pipeline and served by
  the map server, offline too – then Photon can go.
- **iPhone version** – direction and order in `docs/design/multiplatform.md`; core logic free of Android
  (`SharedCoreRuleTest`).
- **Open source + self-hosted map servers (concept, not built):** app setting "Map server" (custom URL + key, also via
  QR code; cells carry their server in the manifest); `/v1` API as an open spec; all-in-one Docker Compose or the cloud
  setup; builder image on ghcr.io; F-Droid build without Play Billing. Not before the Play launch.
- **Replace the tour's route by a saved route during a tour** – owner 2026-10-31: not for now (too much automation).
  Today a tap on a planned route in My routes during a tour says "End the running tour first, then this route can be
  loaded." (`RouteRules.openAsk` → `BLOCKED_DURING_TOUR`).
- **Ideas:** outdoor mode, way back on own track, hut info, tour suggestions, first-start intro / help page.
- **Map wishes (only when the owner says so):** shrub symbols; trail numbers abroad beyond numeric refs (E5 / GR20,
  `osmc:symbol`); contours below z12.
- **Unverified paths (owner: keep):** untagged OSM ways joining two paths, ≤ 300 m, grey dashed + point-menu hint,
  never routed (example way 330547188 near Algund).
- Tune the difficulty thresholds (Easy / Moderate / Difficult) with real tours.
