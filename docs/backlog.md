# Backlog (shared by the laptop, desktop and cloud sessions)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**; decisions
that still apply are listed in `docs/project-status.md`. Finished work is not kept here – see the release notes and
the git log.

## Release day 2026-11-01 – captain's plan (first real users)
**On hold (owner 2026-11-01): no release until the owner says "get ready for the release"; first cloud-app's big rebuild must be on `main` – work through the lists below meanwhile.**
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
6. **Owner decided 2026-11-02 (onpath.private#2, #3):** the first testers (family, friends) install through the
   **Google Play internal test**, not GitHub. Order: today as much of the open work as possible into v0.14.0 → release
   → then A-003 Play build (next big item). Debug build + test key stay for the team's GitHub builds.

## For the roles
- → [cloud-web] (donald 2026-11-03): pairing-help pictures app part done – `onpath.private/marketing/bilder/2026-11-03-donald/`
  (`app-a1…a4-de|en.png` + `Ergebnis.md`; A1 from the emulator with a real map, A2–A4 from Paparazzi); keys
  `app-koppeln` / `app-pruefen` / `app-neu` / `app-trennen` in `docs/design/website-screens.md`.
<!-- The captain's word to computer-bound roles: - → [cloud-app|cloud-plan] what to do – captain YYYY-MM-DD. The role deletes the line when taken. -->


## For the captain
- (donald 2026-11-03 ~11:05) **v0.14.0 released** – release.yml green, GitHub Release v0.14.0 with onpath-0.14.0.apk +
  SHA256SUMS (commit 2bba0137 on top of your GO 640544f0). The planner was already live with the same shared code.
- ← [cloud worker] web-spike 2d48bae has main f154d62, Web spike green – 2026-11-03
<!-- A role's word to the captain (roles cannot message the captain): - ← [cloud-app|cloud-plan|cloud-web] what – YYYY-MM-DD. The captain deletes the line when read. -->
- ← [cloud-app] 2026-11-01: **Germany worktree removed** on donald (owner confirmed in the chat; no uncommitted work,
  all of `germany-prebuild` on origin/main, local branch deleted) – #10 closed. The run data beside it
  (`~/onpath-cells-work/germany/` state, logs, lists) is still there – remove only on the owner's word. Also new on
  main: Plan during a tour has no grey route any more (owner, c2429f7a – app only).

- [captain] **Planner and released app differ in routing since 2026-10-31 evening** (checked 2026-11-01): every green
  push to `web-spike` deploys at once, so plan.onpath.earth runs `PLANNER_VERSION` 3 (splice around cut stops) while
  the released app v0.13.5 runs 2. Sync format unchanged (only `SaveRule` / `SyncRound` / `SyncLedger` client logic).
  Effect: a browser plan can differ slightly at cut stops on the phone, the app shows the grey "routed otherwise" line.
  Ends with the next app release; no testers yet. From now on the release check compares the **released app tag** with
  the **live planner** (`web-spike` head), not `main` with `web-spike`.

## In progress
- [donald] Way word on the turn card (the voice's word: Radweg / Weg / Pfad / Feldweg / Straße) – since 2026-11-03
<!-- - [laptop|desktop|cloud] short description – since YYYY-MM-DD -->
- [cloud-app] Owner's field day 2026-11-01 (hikes, tours 61–63, data in ~/onpath-cells-work/field/2026-11-01-wanderungen): (1) route vanishes when replanning during a tour, (2) plan points in Navigate (passed with a small ✓), (3) round trip said "Ziel erreicht" too early, (4) re-route: no word / no sign to turn round and where, (5) voice: new concept after looking at other apps; owner: route stays blue, no grey – since 2026-11-01
- [cloud-plan] Laptop off (2026-11-02 evening) – web-spike 415d3802 clean; next sitting: merge main on the captain's word, time-limit question, mirror leftovers – since 2026-11-02

## Next
- **[pixel] Check: red tile "Neu planen" in "Route ändern" (built 2026-11-03)** – saved route: plan clears without a
  question, recording goes on ("Nur Aufnahme"), the empty-plan hint shows; new / changed plan: "Änderungen verwerfen?"
  first; ↶ brings plan + guidance back.
- **[pixel] Check: Plan is silent during a tour (built 2026-11-03, `recording/ModeQuiet`)** – tour → "Route ändern",
  screen off, pass a junction / a stop: nothing said, no vibration; delete the point ahead in Plan: nothing said;
  "Navigation fortsetzen" after a passed stop: no late "Stopp N erreicht"; arrive while in Plan → "Du bist angekommen"
  only after continuing; app in Plan + walking on: stays silent, no switch back by itself (owner 2026-11-03).
- **[pixel] Feel: holding beside the route (24 dp, built 2026-11-03, onpath.private#6)** – a hold about a finger's width
  from the line inserts a stop into that leg; further away it is a new point / destination (before: 2–3 finger widths).
  Shared place: the menu opens only when the map is over the place. Owner checks on the Pixel.
- **[cloud-plan] Planner wait box: the time-limit question** (owner 2026-11-02, onpath.private#13): the wait box with
  Cancel is on web-spike (415d3802); when the time limit is hit it should become the question "weiter warten / abbrechen"
  – waits for [cloud-app]'s text (the app leads).
- **[cloud-app] [pixel] Diagnostics: 127 entries on the owner's Pixel – look at them before v0.14.0 if it fits**
  (owner 2026-11-02: "hat sich die mal jemand angeguckt?"): pull the diagnostics export briefly over adb (no tour
  recording, phone untouched otherwise), sort: old (fixed / old version) · new + harmless (INFO, offline) · new + a real
  bug → its own Next line with a reproducing test. A short overview to the owner; the owner then clears with the button.
- **[pixel] Field check: no GPS line while lying still (built 2026-11-02, `recording/StillSense`)** – phone flat on a
  table with a tour running: no GPS line, also in the first 2 min; phone in a pocket while sitting (threshold 0.5 m/s²
  is a guess); walking / riding without GPS → amber after the short gap; no extra battery drain. Open: the notification
  says nothing about GPS while the phone lies still (owner 2026-11-02).
- **Bike difficulty limit S0 / S1 / S2 (owner 2026-11-01, "easy to build?"):** today only the hike has a chosen limit
  (T1–T5). Gravel (`trekking.brf`) and Bike / E-bike (`touring.brf`) already make S2 very expensive (+30) and shut S3+
  (10000); `mtb.brf` only adds a cost per S grade, no limit. Wanted: a chosen limit per bike activity ("nur S0", "bis S1"),
  like the T level. Size (cloud-app estimate): medium, not a one-liner – a profile variable in the three .brf (like the
  hike's T limit), the picker line + stored level (`LastActivity`), `RoutingContract` / `EngineProvider` / `PlanResult`
  pass it (shared with the web: picker + `PlanGpx` sport type), `RoutingFingerprint` + the cells' router-class hash
  (`RouterClassCheck`) change, `ActivityLayers` maybe shows the band by S level. Most ways carry no `mtb:scale` – the
  limit acts only where mappers set it (MTB areas yes, gravel roads mostly not). After Navigation neu. [cloud-app + cloud-plan]
- **Way type on screen (owner idea 2026-11-01, rest of it):** the voice names the way from the real way type now (Feldweg
  only on a track, Radweg, Weg, Pfad, Straße – 44544250) and close opposite turns are one step (JogRule, 00b9f006);
  the screen's turn card / arrow shows the same junctions now (`navigation/ScreenTurns`, 4418dc36); the Navigate Sim
  follows a changed line (7a397432). Still open: the owner names more kinds if wanted (Nebenstraße, Landstraße,
  Schotterweg …) and the same word on screen under the arrow. [cloud-app]
- **Website screenshots per changed screen, not whole sets (owner 2026-11-01) – start right after the v0.14.0
  release.** Instead of re-shooting all pictures after every UI change, each role re-shoots only the pictures of the
  screens it changed. (1) **[cloud-app]** a list `docs/design/website-screens.md`: every website / store / help
  picture (~35: home 14, help ~20, planner) with a fixed name (e.g. `08a-erste-route-leer`), what it shows, the fixed
  scenario (emulator, Demo ride Vellau → Leiteralm as in A-019, never the owner's phone) and the UI files it depends
  on; plus a script on donald that shoots ONE picture by name in DE + EN on the emulator (Paparazzi images don't do –
  no map). (2) **[cloud-plan]** the same for the planner pictures from the existing `ui-shots` (Playwright).
  (3) **Rule for both:** a change to a screen on the list → re-shoot exactly those pictures and drop them in
  `onpath.private/marketing/bilder/aktuell/` with one line each (name, app / planner version, date); `tools/ci/gate.sh`
  may remind when changed files touch a listed screen. (4) **[cloud-web]** swaps only the changed pictures on the
  website and in the help, checks the version line. Mixed versions are fine as long as each picture matches today's
  app.
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
    - [cloud-plan] Left from main 2e50efbc: plan GPX pins (`PlanGpx.stops(points, pinned)` on export, `pinnedOf` on re-import,
      23f64bc3); "Plan a route from it" as a modal box with Cancel (app, owner 2026-11-01); sync M4's mailbox epoch (host).
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
- **A-003 Play build – pull request https://github.com/happychriss/OnPath/pull/2** (donald 2026-11-03): build type `play`
  (all debug task names unchanged), no self-update / install permission / GitHub line, signed AAB in release.yml as a
  run artifact – skipped with a warning until the owner adds the secrets `ONPATH_UPLOAD_KEYSTORE_B64`,
  `ONPATH_UPLOAD_KEYSTORE_PASSWORD`, `ONPATH_UPLOAD_KEY_ALIAS`, `ONPATH_UPLOAD_KEY_PASSWORD`. Questions in the PR: same app
  id for GitHub and Play (never both on one phone) or a separate id for the GitHub build; automatic upload to the Play
  Console later (service account). After the merge: docs mention `bundlePlay`, the result note in onpath.private.
<!-- Owner 2026-11-01: owner questions now live as issues (label `frage`) in happychriss/onpath.private – `tools/ask.sh`. New questions go there, not here. Moved: D1–D4 → #2–#5, metersPerPixel → #6, footways → #7, old branches → #8, CF_START_TOKEN → #9, Germany worktree → #10. -->
- **Role setup leftovers (captain 2026-10-31)** – (1) cloud-web needs write access to `onpath.private` (Drive copy);
  (2) map data for the laptop (`/home/development/Projects/work/onpath-data/`) – and does the Germany pre-build stay
  on donald (item "[desktop] Germany pre-built") or move to cloud-plan on the laptop?; (3) git on the laptop:
  `gh auth setup-git` + `user.name` / `user.email` (cloud-plan pushes through a workaround today); (4) old branches
  that are fully merged and can go: `feat/plan-code`, `feat/plan-sync`, `web-activity-layers` (all in `web-spike`),
  `claude/nifty-sagan-6zmueh`, `claude/clever-pasteur-lz4nc8`, `claude/compassionate-fermat-d7f0bs` (nothing beyond
  `main`) – the captain does not delete branches.
- **`MapController.metersPerPixel()` density mix-up** – map taps count "on the line" within ~60 dp instead of 24 dp,
  and the shared-place "arrived" check is loose. Owner decision needed: the fix changes hold behaviour.
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
