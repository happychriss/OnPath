# Backlog (shared by the laptop, desktop and cloud sessions)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see the release notes and
the git log.

## Release day 2026-11-01 – captain's plan (first real users)
1. **Morning, owner (10 min):** read the review's top list (`docs/reviews/2026-11-01-pre-release.md`, BLOCKERs first)
   and say which blockers must be fixed before the release – the rest goes to "Next".
2. **cloud-app** fixes the app blockers on `main`; **cloud-plan** the web blockers on `web-spike` and merges `main` again
   (10 commits behind at 2026-11-01 00:00). Both run their gates; E2E on `main` must be green (5b2dfeec fixed the red
   69515dd run).
3. **Captain** re-runs the release check on the final `main` + `web-spike` commits and writes the go.
4. **Release together:** cloud-app releases v0.14.0 (version + notes) and cloud-plan deploys the planner from the same
   shared code – same hour, so phone and browser never differ in fingerprint / sync format.
5. **cloud-web:** website texts match the release (voice already says "available" – 065d3b7), help pages get pictures
   (A-019 set in `onpath.private/marketing/bilder/`), a short tester note: what to try, how to report (Settings →
   Diagnostics export, mail, GitHub issue).
6. **Owner decides:** how the first users install – APK from the GitHub release (sideload, Play Protect warning) or
   wait for the Play internal test (A-003 not started)? All APKs still use the TEST map server (one shared key, IP
   limits) – fine for a small group, say how many testers.

## For the roles
<!-- The captain's word to computer-bound roles: - → [cloud-app|cloud-plan] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->
- → [cloud-web] Order (captain 2026-10-31): (1) copy the Drive folder into `onpath.private` (setup step 7) as soon as the
  owner gives write access; (2) A-021 tab name / alt text „Aufs Handy · Teilen“ → „Teilen“. The website's voice
  sentence waits until voice guidance is released.

- → [cloud-web] Help pages (`/hilfe/…`, `/help/…`) have no pictures at all – only the home page has the 14 app
  images. Once the owner has put the A-019 set into Drive (backlog "Waiting for the owner"), add the matching
  screens to the help topics (first steps, planning, tour menu, GPX, Google Maps place). Also: the site already
  calls voice guidance available (065d3b7) – fine if v0.14.0 ships 2026-11-01, otherwise roll that text back –
  captain 2026-11-01.

## For the captain
<!-- A role's word to the captain (roles cannot message the captain): - ← [cloud-app|cloud-plan|cloud-web] what – YYYY-MM-DD. The captain deletes the line when read. -->
- ← [cloud-web] Orders done: Drive copy (by your helper), A-021 tab name + rest (onpath.earth 61a09b6), website voice + planner as available (065d3b7, owner: now, before v0.14.0). Store text version 3 (voice, planner, Tour menu …) is in onpath.private – into the Play Console with 0.14.0. The website follow-up (1) Voice in "Next" is done for the website; the Play text waits for 0.14.0 – 2026-11-01
- ← [cloud-plan] Order done – web-spike f31b9ac (pushed; CI decides the deploy): (1) main merged twice (up to 4555c3d incl. voice guidance + field bugs), engine / shared-code / plan-parity tests and all browser smokes green on the laptop; mirrors checked (voice, tour, unfinished track: nothing for the browser). T0: the web reads links only through `PlanCode` and writes 1–5 – nothing to change. (2) Save asks for the name, Save as copy + Share plan in My routes ⋮, plan sheet without ⋯ (✕ Close asks "Discard changes?" when unsaved; Export GPX moved into the Share dialog – owner's choice 2026-11-01). (3) Candidate marker + dashed join line, fit after a place from the card. Not mirrored: the app's rolled-up one-row step (its green Start has no browser counterpart) – owner/captain decide. Laptop: Node via nvm, Playwright Chromium installed; search-smoke / cells-smoke need a cell store the laptop does not have yet – 2026-11-01

- [captain] release v0.14.0 (voice guidance): app + planner OK – 2026-10-31. Fingerprint equal (BRouter + profiles
  unchanged, `PLANNER_VERSION` 3 both), `sync/` unchanged since the merge base 38fc16c, CI green (main unit 69515dd,
  web-spike 97e8836); only shared change on main = `PlanCode` T0 read tolerance. Release still waits for the owner's
  Pixel test.

## In progress
<!-- - [laptop|desktop|cloud] short description – since YYYY-MM-DD -->
- [captain] code + architecture review before the first-user release (owner's order, overnight; read-only, report in `docs/reviews/`) – since 2026-11-01
- [cloud-app] Release v0.14.0 (voice) – captain's go; waits for the owner's Pixel voice test OK + E2E green on 69515dd / 4c64138 – since 2026-11-01

## Next
- **Website and Google Play follow-ups (marketing; website repo `happychriss/onpath.earth`)** – (1) **Voice:** onpath.earth
  (since PR #7) and the Play store text say "Sprachansagen sind nicht vorgesehen – OnPath führt über Karte und
  Vibration" – written before the owner's voice decision of 2026-10-31; change both when voice guidance ships.
  (2) **Play Console:** pricing is still "paid" → switch to free (Monetize → App pricing → Make free) before the first
  release; data safety says approximate location + in-app search history (Photon), nothing shared – update when data
  flows change; app access lists "Pair with browser" with steps. (3) **Play link:** when the closed test exists, set
  `PLAY_OPTIN_URL` in the website's `content_de.py` and mail the link to the group (welcome message too). (4) **Images:**
  A-019 (donald) – one image set from one app version for website, store, help and video; the store's place image
  still shows the old "Zur Route hinzufügen". (5) **Owner, Cloudflare:** switch off Email Address Obfuscation (it adds
  a script the privacy text says is not active). (6) **Before the public launch:** legal check of imprint / privacy
  (website `docs/RELEASE.md`), then website `--release` and the planner's `INDEXING = "on"` together; privacy line on
  the map server ("IP for a few minutes") once A-014 is in production; English store listing; video (idea in Drive). (7) **Alt text / tab name** „Aufs Handy · Teilen“ → „Teilen“
  (A-021, captain handoff 2026-10-31).
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
  vor dir."). Rundgang flows 11 + 12 green on donald (2026-11-01).
- **Voice guidance – for the next release (owner 2026-10-31), on `main` since 69515dde (2026-11-01)** – gate + Rundgang
  green, debug build on the Pixel. **Owner:** test on the Pixel – Phone setup step "Voice" (offline voice, test
  sentence, "Heard it?"), Settings → Navigation voice switches, then a ride (sound, ducking, Bluetooth, phone call);
  review the ~30–40 spoken sentences (`strings_voice.xml`). Then the captain's go → release. Owner: "every tester
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
  `origin/web-spike` f31b9ac (2026-11-01, [cloud-plan]): web-spike has main up to 4555c3d. Done there and removed from this list: Save asks for the name, plan sheet without ⋯ (Export GPX in Share), candidate marker, fit after a place, T0 links (nothing to do), pairing across tabs + new-version box +
  Unpair apart, island retry (Köln = app, no knownDiff), New / Changed tags, short-trip retry, splice (owner's 70.6 km
  hike live 4.7 → 2.2 s), routing-data notice + way-type line, search list never jumps + six chips, deleted phone hello,
  privacy + pairing sentences, host ipHash, date with the dot, first-route fit, "New route for … – check it".
  Plan parity: `web/test/plans.json` 14 cases, all app = web (gate in the jvm job).
  - *Should:*
    - [cloud-plan] Rolled-up one-row step? (app d288503, PlanSize.COLLAPSED: activity icon, time · distance, ⚠ count,
      compact green Start) · the web has small / profile / points (`sheetstep.ts`) and no Start – a fourth, one-row step
      only if the owner wants it on a phone-width browser.
    - [cloud-plan] One message system – the rest · `docs/design/ui-style.md` · `web/app/src/msgcard.ts` is the app's
      MessageCard (FRAGE / HINWEIS kinds, one at a time, 12 px under the search bar), used today only for "New version
      of the planner". Still to move there: the map strip's questions / errors (`mapnotice.ts`, `areas.ts`), the
      pairing confirmations, any `confirm()` / own dialog that is a HINWEIS; ANZEIGE stays a line in place.
    - [cloud-plan] Quiet margin cells after planning (offline after planning) · 27029db · `cells/CellTriggers.kt`
      (`CellJob.margin`: cells along the route vs the corridor margin, `tourStarted`) – check `areas.ts` (T1–T4 mirror)
      asks the margin cells quietly like the app.
    - [cloud-app] Pure files for two small rules the web mirrors today: `RouteRules.newRouteName` / `copyName` (needs
      `TourDb.TourKind`) and `WholeRoute.firstRoute` / `fitAfterPlace` (needs `AppMode`) – as pure Kotlin beside them
      (like `RoutingDataNotice` / `BreakdownRules`) the web compiles them instead of mirroring (`web/parity.json`).
  - *Low prio:*
    - A-016 App Links · `/.well-known/assetlinks.json` is live (200, v0.13.1 key) · left: on the phone
      `adb shell pm get-app-links com.onpath.app` – then `#p=` / `#pair=` links open the app (owner).
    - [cloud-plan] Licences list in the browser (A-020) · app: About → Licences (`data/Licences.kt`,
      `assets/licences/`) · the web planner lists its own bundled parts (MapLibre GL JS, BRouter onpath1, Noto Sans
      glyphs, icons, npm libraries) with version + licence, offline in the page, plus the ODbL line.
    - [cloud-plan] Editing speed beyond the splice: a plan edit still routes the whole chunk once (owner's hike ≈ 2 s
      live); faster only by reusing legs of the previous plan – owner 2026-10-31 said no (same points, same route).
    - Console error "no content-length header … HTTP Byte Serving" and a faint seam in the hillshade / contours
      (captain handoff 2026-10-31) · web only · find which file is served without Content-Length (PMTiles range
      requests) and where the seam comes from.
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
- **Role setup leftovers (captain 2026-10-31)** – (1) cloud-web needs write access to `onpath.private` (Drive copy);
  (2) map data for the laptop (`/home/development/Projects/work/onpath-data/`) – and does the Germany pre-build stay
  on donald (item "[desktop] Germany pre-built") or move to cloud-plan on the laptop?; (3) git on the laptop:
  `gh auth setup-git` + `user.name` / `user.email` (cloud-plan pushes through a workaround today); (4) old branches
  that are fully merged and can go: `feat/plan-code`, `feat/plan-sync`, `web-activity-layers` (all in `web-spike`),
  `claude/nifty-sagan-6zmueh`, `claude/clever-pasteur-lz4nc8`, `claude/compassionate-fermat-d7f0bs` (nothing beyond
  `main`) – the captain does not delete branches.
- **`MapController.metersPerPixel()` density mix-up** – map taps count "on the line" within ~60 dp instead of 24 dp,
  and the shared-place "arrived" check is loose. Owner decision needed: the fix changes hold behaviour.
- **A-019 images to Drive** – the owner drags the folders (`external-docs/screenshots/2026-10-31-a019*`), or sets up
  `rclone` once (no remote configured on donald).
- **Worktree `~/onpath-cells-work/germany/repo` (branch `germany-prebuild`)** still exists on donald – decide whether
  to remove it.
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
- **Plan sheet "a third lower"** – reached only ~23 % (d2885035); more needs touch targets smaller than 48 dp, owner
  said no (2026-10-31).
- **(optional) Gradle shared remote build cache** (e.g. a GCS bucket) for donald + CI + cloud sessions.
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
