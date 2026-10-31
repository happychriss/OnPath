# Backlog (shared by the laptop, desktop and cloud sessions)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see the release notes and
the git log.

## In progress
<!-- - [laptop|desktop|cloud] short description – since YYYY-MM-DD -->

## Next
- **From the external review (A-020 §4) – build only after a small user test by the owner:**
  (1) offline readiness "Für diese Tour offline bereit" (maps + routing data for the planned route on the phone, one
  line before the start); (2) empty state with a permanent hold hint (the empty plan map says how to set a point –
  hold – and the hint stays until the first point); (3) Pause / End easy to find during a tour (today behind the slide
  "Tour menu"). Photon outage and WebGL2 stay low (owner 2026-10-30).
- **On the next real ride (owner)** – onboarding checklist step 7 (30 min, screen off, battery saver; steps 2–6 passed on
  the Pixel 2026-10-30, step 1 = fresh install → emulator); rest view + smarter cues on the bike and on foot; pocket after
  5 s on the bike; lock-screen notification on the Pixel; the older phone checks below; compass arrow: does it stay grey
  all the time on the Pixel? (1c5b8ad); new off-route question "Abseits der Route – neu planen?" with "Ja" / "Ja, Stopp N
  auslassen" (16, 81dbff7); map symbols: rock from z14, orchards / vineyards stronger.
- **Plan edits during a tour – built 2026-10-31 (cloud), check it** – `docs/design/tour-edit-position.md`. Owner: on the next
  ride delete a point ahead / the destination after passing the others (grey ✓ points, pale plan line, "Kein Ziel mehr
  vor dir."). **donald:** run `tools/ci/release_rundgang.sh` once – flows 11 and 12 are new and were only compiled in the
  cloud (no emulator); a red flow blocks the next release.
- **Voice guidance (owner 2026-10-31) – after the feature stop (first the ride with the checklist)** – owner: "every tester
  asks whether it talks". **Concept: `docs/design/voice-guidance.md`** (rules, all sentences DE/EN, 5 questions for the
  owner). Android's own text-to-speech (`TextToSpeech`, no Play Services; only voices that need no
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
  mirror gets a line here (what · app commit · what the web does); a done line is removed. Checked against
  `origin/web-spike` 2026-10-31 evening: web-spike last merged main at fb2d467 (10-31 10:56, merge 096e8ac); main has
  51 commits since (up to 565adf3, v0.13.3) – `web/parity/merge-main.sh` brings them, `web/parity.json` flags the
  shared files. Drive hand-offs: 00 Übergabe, "2026-10-31 Web-Planer Teil 1–7" (open points: Teil 4–7).
  - *Must:*
    - Pairing in the browser – bugs (incident 2026-10-31, Drive Teil 4 §1) · web only · (1) `PlanSync.dir` (open
      folder + folder list) is read only in `init()`: re-read the folders in every locked operation and in `refresh()`,
      `BroadcastChannel('onpath-sync')` announces each change of pair / folders / pending; Playwright test with two tabs
      (pair in A → B shows paired without reload; unpair in A → B shows unpaired and writes nothing back). (2) No new
      version detection: build id in the page + `/version.json` (no-cache), checked on focus and every fetch; on a
      mismatch one box "Neue Version des Planers" [Neu laden] – never reload by itself (unsaved route); engine file names
      with version / hash. (3) „Trennen“ sits right beside „Jetzt abgleichen“: check `#unpair` cannot fire without a
      deliberate click (focus / Enter), move it away (e.g. red text button at the bottom), still no question (A-017).
    - Island retry without reflection (Köln Dom) · 287590d · BRouter jars renamed to `1.7.11-beta-onpath1`
      (`islandNodePairs` package-private, `tools/routing/patch_brouter_jar.py`), `OnPathHints.islandRetries` /
      `skipOnSnap` – after the merge run `node web/parity/check.mjs --update` (new jar names) and drop the `knownDiff` of
      Köln Dom → Neumarkt in `web/test/plans.json` (a normal gate case, app 1334 m); fingerprint changes once – steps in
      `docs/handoff-web-island.md` (Drive Teil 6).
    - My routes: "Neu" / "Geändert" tags instead of bold + grey line (owner 2026-10-31, replaces A-018's bold marks) ·
      3df74cd · the web's My routes shows the phone's `mark` as the same light blue tag after the name (`#DDEAFB` /
      `#0B3D91`, 12 px bold pill, never cut), gone when opened; careful: bold means "waiting" in the browser today –
      steps in `docs/handoff-web-route-tags.md`.
  - *Should:*
    - Save asks for the name (owner 2026-10-31, app 0.13.5, replaces "save at once") · app commit "Save always asks
      for the name" · checked against `origin/web-spike` 761bbb6: the browser still saves at once and renames in place
      (`#savedLine` → `#renameField`), so it does **not** match yet. Web: every Save (header, ⋯ → Save route, Save
      first) opens the small name popover (the `.savepop` styles are still there) with the name fully selected,
      Cancel · Save; a saved route is overwritten **and renamed when the name changed** (one route); blank = keep the
      proposal / old name; "Save as copy" proposes `RouteRules.copyName` ("Name (2)", a copy of "Name (2)" is
      "Name (3)"); "Saved as …" becomes a plain line (no `#renameField`). Update `parity-smoke.mjs` (renameTo, "no
      field, no dialog" checks). App: `SaveNameDialog`, `AppViewModel.askSave / confirmSave`, `SaveDialogTest`.
    - Plan sheet without ⋯ (owner 2026-10-31, app 0.13.5) · app commit "Plan sheet: icon row instead of ⋯" · the
      browser's floating window keeps its own layout, but mirror the wording and placement: ✕ **„Plan schließen“ /
      "Close plan"** (was „Route löschen“; "Discard changes?" / „Änderungen verwerfen?“ Cancel · Discard only with
      unsaved changes; a saved route stays), **„Umkehren“ · „Rundtour“** beside it in the summary (not only above the
      points), **„Als Kopie speichern“ / "Save as copy"** in My routes. String keys renamed – fix `t()` in main.ts after
      `merge-main.sh`: `plan_clear_route` → `plan_close_plan` (+ `plan_discard_title`, `plan_discard`),
      `plan_save_as_new_route` → `plan_save_as_copy`. App: `PlanPanel.PlanActions`, `PlanActionsTest`.
    - Candidate marker instead of the orange search ring (owner 2026-10-31, A-019 add-on) · app commit "Candidate
      marker" · the web's `.searchring` (search.ts, style.css) becomes the app's look: a dashed route-blue (`#1E3FAE`)
      ring, radius 21 px, 3.5 px dashes 7 / 4.5 over a 7 px white halo, ~12 % blue tint inside, a small "+" badge
      (blue disc r 8.5, white +) at its upper right, the name in route blue below; plus a thin dashed preview line
      (2.5 px, dash 2 × width, opacity 0.9, under the route points) from the place to `WaypointInsertion.joinPoint`
      (shared file: let `placePreview` return the join point) – only with a route. No orange (= recording).
      `map/CandidateMarker.kt` draws it; picture `CandidateMarkerScreenshotTest`.
    - Short-trip detour retry (owner 2026-10-31, Hilden) · f8a55de, 17d78d1 · shared `routing/Planner.kt`
      (`retryShortDetours`: a leg < 2 km straight routed > 2× is retried via the midpoint, taken when ≥ 30 % shorter) –
      comes with the merge, nothing to code; add the Hilden case to `web/test/plans.json` (Bike 51.169243,6.925344 →
      51.169,6.93578 = 1099 m, before 2054 m); `PLANNER_VERSION` 2, fingerprint changes once –
      `docs/handoff-web-short-detour.md`.
    - Save like Word (owner 2026-10-31) · add2b08 · Save saves at once under the proposed name (plan link title, else
      "Vellau → Leiteralm", else "Route 31. Okt., 10:12"; a taken name gets " (2)"), saving a saved route overwrites,
      a copy via ⋯ "Save as new route", rename by tapping "Saved as ‚Name‘ ✎" (field in place). The browser still shows
      the small name popover (`savePopover`) – save at once, name editable in place; `parity.json` flags `ui/RouteRules.kt`
      (saveMode NEW_ROUTE / OVERWRITE). Drive Teil 7 §2.
    - Routing-data notice + way-type / surface line shared (owner: app = web) · 64e217e · new pure
      `data/RoutingDataNotice.kt` (`kindForRoute` → LOADING / NOT_YET, keys `maps_routing_*`) and
      `routing/BreakdownRules.kt` (labels, order, ARGB / hex colours, `foldSmall`, `summaryLine` „Radweg 92 % ·
      Asphalt 98 %“): add both to `appFiles` (+ `RoutingDataNoticeTest`, `BreakdownRulesTest` to `appTests`); the plan
      sheet shows the same notice line and way-type / surface line with bars – `docs/handoff-web-notice-breakdown.md`.
    - Search list never jumps for the same text (field bug 0.13.2) · e6b9c31 · shared `SearchRanking.keepShown` /
      `stillMatching` (`search/OfflineIndex.kt`, already in `appFiles`): the page keeps the list it shows per text, later
      answers only add below, online rows of the shorter text that still match stay (app: `ui/search/SearchSession.run`,
      test `SearchStableListTest`). `WebSearch.online` only passes `keepShown` to `mix` today.
    - Six short search chips: Orte · Essen · Sehenswert · Wasser · Bahn · Parken (Places · Food · Sights · Water ·
      Transit · Parking) · 2fd9546 · table `SearchKinds.Category` in `search/Place.kt` (already compiled; `Category.of`,
      strings `search_cat_*`, test `SearchCategoryTest`): same six chips (scrolling row), filter with
      `SearchQuery.category`; the Places chip keeps street / address hits.
    - Pairing display: a deleted phone hello (guard) · b585cbc · the app says "connected" only with a live browser hello.
      The web's `phoneHello` skips a deleted hello silently, `status.paired` = pair + open folder: in `refresh()` a phone
      hello seen and now deleted → "Unpaired on the phone" (grey, Pair phone); the pill's "synced … ago" labelled as this
      browser's pass. Today no real case (phone Unpair ends the mailbox → 410).
    - One message system · fb2d467 (merged), `docs/design/ui-style.md` · the page has no `MessageCard` look yet: FRAGE
      (white card, blue 4 px left edge, two verb buttons, until answered), HINWEIS (one button – OK or the one way out;
      grey / amber ⚠ / red ⚠ edge, never goes by itself), ANZEIGE (no box, a line in place); one box at a time (a
      question first), 12 px under the top bar; details of a state = sheet from below, never a dialog. Drive Teil 4 §4.
    - Route hints end crumbs + first-route fit (field A-019) · 4ab0b37, c7bf190 · shared `ui/RouteWarnings.kt`: a stretch
      < 5 m (`END_CRUMB_M`) at the very start / end is no spot – comes with the merge, the hint parity should pass as is;
      the first route of a plan and an opened plan link are fitted whole like ⛶ (check the page does so).
    - Privacy sentence · 880850d · `web_pair_privacy` still says routes only: say the same as the app – routes +
      imported tracks, encrypted on the device, we can't read them, recorded tours are never sent, server Cloudflare (EU).
    - Pairing confirmation · app `sync_paired_plan` · `web_pair_done` still says "sync both ways by themselves": after
      pairing the same sentence with OK, until OK: 'Verbunden. Was du im Browser speicherst, kommt auf dein Handy – dort
      bleibt alles gespeichert.' (EN 'Paired. What you save in the browser comes to your phone – everything is kept
      there.').
    - Host: no plain IPs · 2e851ef (doorman) · `web/host/src/index.js` still keys the rate limit by the plain
      `cf-connecting-ip` → the daily salted `ipHash` (as `sync.js` already does); `[observability.logs]
      invocation_logs = false` in `web/host/wrangler.toml`.
  - *Low prio:*
    - Date „30. Okt. 2026“ with the dot like the app (owner) · `appDate` in `plans.ts` takes `Intl` month 'short' → "Okt"
      without the dot in German; also `web_plan_dated` / `when`. Drive Teil 7 §3.
    - A-016 App Links · `/.well-known/assetlinks.json` is live (200, v0.13.1 key, checked 2026-10-31) · left: on the
      phone `adb shell pm get-app-links com.onpath.app` – then `#p=` / `#pair=` links open the app.
    - Licences list in the browser (A-020) · app: About → Licences (`data/Licences.kt`, texts in `assets/licences/`)
      · the web planner lists its own bundled parts (MapLibre GL JS, BRouter onpath1, Noto Sans glyphs, icons, npm
      libraries) with version + licence, offline in the page, plus the ODbL line.
  - *Not for the browser:* lock-screen arrival rounding (30c01c6), smart screen / rest view, cue timing, vibration,
    pocket, lock-screen notification, notifications chip, provisional position, GPS-only recording, phone setup, demo
    location, POI tiles + search on disk on the phone, memory / health line, End-tour ride icons, ☰ phone-setup entry;
    plan edits during a tour (a3e9a4a, `TourSplit` kept out of the shared code on purpose), compact tour sheet
    (089496c), locate zoom in Navigate (f07078b), far start (e81f8f4), Bug 02 lead rules (56fffcb), "Show whole route"
    (b269c82 = the web's ⛶), shared place lookup (c4f5e99 – the web has no share target), Problems merge text (04528ed
    – the web's `diag.ts` dedupes on the exact message, nothing merges), atomic files, download chip.
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
