# OnPath – project status and decisions

Current release **0.14.0** (2026-11-03). Open work: `docs/backlog.md`. Core features and their tests:
`docs/core-features.md`. What each release changed: `docs/release-notes/` and the git history – this file keeps only
the current state.

## Where things are
<!-- Kept by the captain every round (docs/roles/neu/captain.md 5c). -->
- **Pixel:** main b047bf79, installed 2026-11-01 19:55 (newest handoff that names it; the night draft 63b0194d is not on it).
- **GitHub release:** v0.16.0 (2026-11-05 night, 7419f1dd, cloud worker for donald): round trips start at ① and stay round trips (A, C), re-route "Über ③ / ③ auslassen" with numbered circles (B), "PLANEN · Ohne Netz ✓/⟳/⚠", the found place's card "+ Zur Route" · "⋯ Mehr", A-022 words (Meine Touren, Geplant | Gemacht). Before: v0.15.0 (2026-11-04, e0ad10a8). Not in it: the leg cache for long bike routes (measured only, `docs/design/long-bike-routes.md`). Next: A-003 Play build.
- **Planner (plan.onpath.earth):** web-spike 74c2b069 (2026-11-05, has main c40b638b = the v0.16.0 shared code; Web spike green after one re-run of a timing flake in robust-smoke). Open for cloud-plan: A-022 web texts, the pin card, loopStart in the web point menu, the 'Weiter warten?' box.
- **State:** released 0.13.2 → 0.13.5 today. main (8cf47345 or newer) also has, **not released yet**: the planner
  splice "route only around a cut stop" (38fc16c6, ~2× faster, `PLANNER_VERSION` 3); the compact plan sheet with the
  rolled-up one-row step (d2885035, interim build on the owner's phone since 21:45); faster builds (`tools/ci/gate.sh`,
  warm Gradle daemon, configuration cache). Next release = **0.13.6**.
- **Rules from today** (CLAUDE.md / memory): app ⇄ web identical, shared Kotlin; the Release-Rundgang
  (13 flows, `tools/ci/release_rundgang.sh`) is **optional** – only on request or for core navigation / recording
  changes, a normal release = gate + GitHub smoke; gates via `tools/ci/gate.sh`, never `./gradlew --stop` (kills other
  sessions' daemons); one agent per core area; donald has 30 GB RAM: one emulator, no Gradle beside an emulator unless
  ≥ 10 GB is free; messages per `docs/design/ui-style.md`.
- **Web planner:** handoffs Teil 1–7 plus „2026-10-31 nachts App-Sitzung → Web-Planer“ are in Drive „00 Übergabe“;
  the backlog list "Web planner: mirror app changes" is current.
- **Marketing:** A-019 + Nachtrag + Nachtrag 2 delivered (artifacts and Drive result docs in „01 Aufträge“; images in
  the private `external-docs/screenshots/2026-10-31-a019*`). A-021 (website / store texts) is **open** for the
  marketing session. A-020 is done.
- **Owner decisions still open:** RAM upgrade for donald (64 GB recommended) vs a cloud build VM; Rundgang margin for
  offline (10 km kept; 5 km would save ~25 % download); `metersPerPixel` density fix (taps count "on the line"
  within ~60 dp instead of 24 dp; the fix changes hold behaviour). Smaller open items: `docs/backlog.md`.

## Last session
<!-- Overwrite at the end of every session: who (laptop/desktop/cloud), date, what changed, what's half done, next. -->
- **hp (laptop, cloud-plan), 2026-11-05 evening:** web-spike has main 7d830d25 + the planner follow-ups: 'Weiter
  warten?' at the time limit (SlowPlan, edit-smoke fakes the limit), A-022 web texts, pin card like the app, point menu
  loopStart, header „Android-App testen“ → try page. Smokes green locally (edit, sheet, lang, import, robust); CI flaked
  once on robust-smoke "two quick clicks" (passes locally). Next on hp: the planner terrain view (claimed).
- **laptop (hp), 2026-11-05 evening – planner terrain view (owner: "wir bauen A und C"), web-spike:** (A) the elevation
  profile linked to the map – the app's cursor in the sheet's profile ("375 m · 2,7 km · 14 %" + dot on the route), the
  pointer on the route moves it, ⤢ opens a big profile below the map with the route coloured by steepness; (C) toolbar
  "3D" – MapLibre terrain from the cells' heights, tilted, view only, off = flat again. Also fixed: old cells (terrain to
  z12) gave flat z13 tiles – now magnified from z12. web-planner.md §19, ui-smoke checks, `test/terrain-shots.mjs`.
  Open: walls at the edge of the loaded map area in 3D; D (flyover) and the app only on the owner's word.
- **donald, 2026-11-03 late – shut down by the owner (~23:20); new build items go to the cloud.** On main tonight:
  plan in blue (no top band), novice fixes, fewer terms → superseded by A-022 (open), load-route question, compact
  bars + equal pills, map buttons one column 56 dp, slow-planning question, next stop in the turn card, way word.
  Five unfinished works are `wip/donald-2026-11-03-*` branches – the list and order are in the backlog ("For the cloud").
  Not released: v0.15.0 waits for those five + a Rundgang. Pixel last installed ~22:10 (main of then).
- **donald, 2026-11-03 evening – after v0.14.0, all on main:** route hold 24 dp (#6), diagnostics list folded,
  Plan silent during a tour (no walk-off back to Navigate – owner), red tile "Neu planen" in "Route ändern", way word
  on the turn card ("Links auf den Radweg"), Plan in blue (version 3, Navigate unchanged, blue position sign).
  A-003 Play build = pull request #2 (waiting for the owner + secrets). Open for the owner: slow-planning wording
  (#21), map buttons form (issue), hook vs pushes to onpath.private. Not on the Pixel yet (unplugged) – install the
  whole state when it is back; [pixel] check lines in the backlog.
- **donald, 2026-11-03 – v0.14.0 released** after the captain's GO (release.yml green, APK + SHA256SUMS). Before it:
  the night list (words Route / Tour / Aufnahme, "Los geht's", plan during a tour = tile bar, paved footways note,
  stop bar ⏸ / ✎ / ■ without ✕, found place = pin + chip, empty-plan hint, shared places in Recent, no GPS line while
  still), Rundgang 14/14 PASS, pairing-help pictures for cloud-web. Open for the owner: colour mockups (draft page
  §5), slow-planning wording (#21), hook vs pushes to onpath.private. [pixel] items wait for the phone (diagnostics,
  still-sense field check).
- **cloud-plan (laptop), 2026-11-01/02 – end of the sitting.** `web-spike` 415d3802, everything pushed, goes live by itself
  on green CI (every green push to `web-spike` deploys – `web-spike.yml`). Done: list A 10–13, "More before the release"
  (sync H1 / M7, edit queue, storage.persist, write failures), main merged three times (last: 6f60dd01 – shared rules wired:
  `app/web-shared-files.txt` read by the web build, PlanPoints rows, PlanNames / RouteFit, SaveRule H3, sync M4), keys
  Space / `/` / `F` / `?`, plan point colours, German badge colour, wait box in the middle of the map with Cancel,
  closed-folder question, sheet-smoke races fixed (CI was red). Pictures in `onpath.private` (`marketing/bilder/2026-11-02-hp/`):
  W1–W6 pairing help + film picture. **Open:** main has newer commits not merged (merge once on the captain's word);
  wait box → time-limit question (needs cloud-app's text, onpath.private#13); mirror line "Left from main 2e50efbc"
  (plan GPX pins, track → plan modal, M4 mailbox epoch). **Laptop:** `onpath.private` cloned beside the checkout
  (`Projects/work/onpath.private`); WebKit for `browsers-check.mjs` needs 6 libs copied into Playwright's
  `minibrowser-wpe/sys/lib` (done); a leftover helper worktree `.claude/worktrees/agent-a1a7c490002c2fb5d` (branch
  `web-closed-folder-ask`, already merged) – delete when convenient.
- **donald, 2026-11-02 late – found place = pin + name chip, new empty-plan hint (owner's choices, drafts on
  https://claude.ai/artifact/5mhA8L5AR6vb1TNx8BB2qU).** A search result / shared place is a route-blue pin with a white
  chip "Name ›" under it (tap = the place's menu, which ends with "Nadel entfernen"); it goes when added, with a new
  search, "Neu planen", map-load cancel or "Nadel entfernen" – a tap elsewhere closes only the menu; hidden in Navigate,
  chip hidden while a track is shown; the menu never covers the pin. Empty plan: ✋ "**Wohin soll’s gehen?** Halte dein
  Ziel auf der Karte gedrückt – oder such es oben in der Suchleiste." gate --full green; Rundgang flow 10 PASS, flow 9
  flaky once after flow 10 on the same emulator (passed alone). Not yet on the Pixel. Web planner still draws the old
  ring – its daily merge follows. Word pair Route / Tour / Aufnahme decided (#14) → backlog, next.
- **donald, 2026-11-02 evening – the night draft is on `main` (63b0194d).** `wip/donald-2026-11-01-nacht` rebased,
  checked against the old claude-app chat (owner choices 21:38–22:17): all as chosen except the green "Route ändern …
  Fertig" header, which the owner turned down (22:07) – the unused code + 3 unused texts removed. 3 unit tests follow the
  new behaviour, 191 screenshots re-recorded, Rundgang flow 7 ends the recording via the slider bar, flow 2 presses
  "Aufnahme speichern"; gate --full green. Design review vs Komoot / Organic Maps / OsmAnd saved:
  `docs/design/review-2026-11-01-vs-komoot.md`. The owner never said "passt" to the whole draft – on the next ride:
  route-change mode vs planning at home, the dense status card, the small line on the green slider outdoors. Hourly
  wake-up for donald set (trig_01AXEuUPbmvBV1DfJfeokDg5, :56). Rundgang flows 2 + 7 PASS on the emulator (21:16). Fixed: `device.sh emulator -- release_rundgang.sh` waited for itself forever, and an adb server started inside held the emulator lock after the run. The owner may delete `wip/donald-2026-11-01-nacht`
  (and `wip/voice-guidance` – nothing on it beyond main).
- **cloud-app (donald), 2026-11-01 night – the owner's hikes (tours 61–63).** Data secured on donald:
  `~/onpath-cells-work/field/2026-11-01-wanderungen/` (live-1931 during tour 63, final-* after it: DB, events, problems,
  nav session, GPX, logcat, the owner's screenshot 17:43). Analysed: round trip 61 (a step back at the start → matcher on
  the loop's closing part → 6 of 7 points "passed" → early "Ziel erreicht"), tour 62 (park path 2–25 m beside Neustraße
  → switched back to the original behind its loop, point skipped, "Ziel erreicht" at 54 m). Built on main: no grey in
  Plan during a tour, plan line always drawn (c2429f7a), plan points in Navigate with ✓ (6b8751da). Concept for the
  owner: `docs/design/navigation-v2.md` (voice timing like Organic Maps / OsmAnd, points stay open – owner decision,
  parallel ways, round trips, off-route side + distance) – three questions open in §4, nothing of it built yet.
  Owner note for later: ☰ "Meine Routen" first. Pixel: main b047bf79 installed 19:55 (no tour running).
- **cloud-app (donald), 2026-11-01 evening – voice after the owner's Hilden tour, pictures, captain's list.** Owner's
  field word "keine einzige Ansage passte" (demo tour 59 through Hilden's Stadtpark): replayed on the JVM with real data
  and checked against OSM – (1) a pedestrian crossing / sideways step was two turns ("links auf den Feldweg, danach
  rechts") → shared `navigation/JogRule` (00b9f006: two opposite turns < 20 m, on foot 12 m = one step, ≤ 30° = quiet;
  JunctionCues, VoiceGuide, trace); (2) every park footway was "Feldweg" → the way word from the real way type
  (44544250: Feldweg only on a track, Radweg, Weg, Pfad, Straße); (3) the screen's turn card came from geometry
  ("Kehre rechts" for two park junctions) → `navigation/ScreenTurns` = the voice's junctions (4418dc36); (4) the Navigate
  "Sim" kept riding the old line after a plan edit – what looked like "links und rechts vertauscht" (checked: no side is
  swapped, 116 sentences on 14 Hilden routes) → `DemoLocation.switchLine` (7a397432). New: Navigate "▶ Sim" chip for
  the developer tools (06d01d1f), `tools/routing/jvm-test/HildenVoiceTest.kt` (PASS). Release-Rundgang 14/14 on main.
  Captain's order: (1) pictures DE + EN → `onpath.private/marketing/bilder/2026-11-01-donald/` (a669452), list
  `docs/design/website-screens.md` + `tools/dev/website_shot.sh`; (2)/(3) M5, M7, M5, M1 were already on main; M4 not
  built (owner question onpath.private#13). **Pixel:** 06d01d1f + voice fixes installed 17:01; the Sim-follow fix
  (7a397432) and screen turns (4418dc36) are NOT on the Pixel yet – it went off USB; install when it is back (no tour
  running). Shared code for cloud-plan: JogRule + JunctionCues (backlog "For the captain"). **Next:** owner's ride /
  Sim check of the voice; the rest of the way-type idea (word on screen, more kinds) after the owner's word.
- **cloud-app (donald), 2026-11-01 night – AppViewModel split 4 + 5, split done.** 4/5 `ui/MapDataFlow` (a8aade87:
  cell triggers, card, loading modal, background strip, Offline maps, routing data loader), 5/5 `ui/SearchFlow`
  (2e50efbc: search view, candidate marker, place names, shared places) – each with a small `Host` interface (getters /
  setters on the ViewModel, no copies), public names forwarded; AppViewModel 5083 → 4091 lines; no shared file touched.
  Kept in the ViewModel on purpose: `routingDataArrived` + `gated()` (one owner), routing data freshness, the
  very-long-plan deferral, `addPlace` / place card (plan edits). Safety net on donald: full gate per step, E2E map tests
  green before and after step 4, on the final state Release-Rundgang 14/14 + CorePlanLink + RoutingDataFreshInstall
  (with download, `E2E_ARGS="-e routingDownload true"` – new in `release_rundgang.sh`) PASS. Two E2E tests were stale
  (red before the split too) and are fixed: broken-link text, fresh-install notice on a fast line. Word to the captain
  ("split done") in the backlog. **Pixel:** after the owner ended the tour, the debug build of `main` (cbb2289b) was
  installed (16:31) and starts cleanly (onpath.private#12 closed). **Next:** the owner tests that ONE build outside, then the
  captain's release check; cloud-plan merges `main` once (captain messages).
- **cloud-app (donald), 2026-11-01 – fix day (owner: no release today, everything in). Session ends here; a fresh
  cloud-app session goes on.** On `main`, each with gate.sh (often `--full`), small commits: list A 1–9; "More before the
  release" incl. shared sync rules (M4 restart, H3 `sync/SaveRule`), Points row texts, name / fit rules; then the open
  review items 1:1 with the owner (decided one question box at a time): recording M7 (corrupt DB → tour rescued), M5
  (battery saver mid-tour said once), routing M5 (browser plans never stored straight), M3 (Plan from track: modal +
  Cancel), M1 (remaining ascent = plan's), M8 (track time = moving time), L1–L3, L7, L8, Arch 6 (one `net/HttpDownload`,
  APK sha256 from the release digest), 7 (brouter.de User-Agent), 8–14 (silent failures, style off the main thread, Prefs,
  Gson declared, dead texts, one tile namer / flat distance), 5 (`app/web-shared-files.txt` + `SharedFilesRuleTest`);
  owner wishes: GPS "No GPS" only after 2.5 min while moving, quiet standing still, slider "Stopp", ■ Stop, one off-route
  question shape, tour summary "Unterwegs 4:05 (10:12–14:17) · in Bewegung 2:52 · Pausen 1:13", stale followed line
  removed. **AppViewModel split (owner: all now): 1–3 done (Updates, Phone setup, Pairing → own classes); 4 + 5 (cells /
  routing data, search / shared places) for the next session – plan + Fable's coupling list in
  `docs/reviews/2026-11-01/appviewmodel-split.md`.** Release-Rundgang 14/14 PASS on cbe9833f (all of today incl. split
  1–3). Not done: owner's jog-rule / way-type idea (backlog, think first); the diagnostics export showed no crash – the
  "Los geht's" repeats were five short tours at night, each saying its start sentence. **Next:** split 4 + 5 (with the
  emulator E2E map tests + Rundgang), then the owner tests ONE build outside, then the captain's release check.
  Lessons (memory): gate misses androidTest and text-asserting tests in other packages – compile androidTest, `--full`
  after text changes; never installDebug during a tour; owner's questions one box at a time.
- **cloud-app (donald), 2026-11-01 – release day turned fix day (owner: no release today, everything in).** List A
  1–9 + "More before the release" on `main`, small green commits, Release-Rundgang 14/14 PASS (dfc701d9), E2E + unit
  green (140d5bec). Field bugs: voice repeat (voice memory per tour in trip-pace.json, service scope exception handler,
  minimum intervals), ■ Stop in the one-row tour step + slider "Stopp" (owner), plan lines in tour edits (passed points
  kept, pale plan beside a re-route, SplitRequests, session token). Robustness: quiet while standing still (MoveWatch),
  arrival in the service (RecState.arrived), watchdog after a sticky restart, unwritten points queued, guarded service
  actions, switch question ≠ off route, off-route on when Plan is off screen, notification ids. Routing / sync: splice
  catch-all, GPX rte + trk once, background routing thread, mailbox restart (M4), save-as-copy over a sync change (H3,
  `sync/SaveRule`), stuck outbox said, Points row texts + name / fit rules shared. UX: hold hint, "Used OnPath before?",
  dev tools only via unlock, POI tap details, "Close plan", errors without "move", one off-route question shape (owner),
  route / tour / track. **Shared code for cloud-plan** (mirror list): Planner, GpxModel, SyncRound / SyncLedger,
  SaveRule, PlanPoints, PlanNames – fingerprint unchanged. **Open:** the owner's diagnostics export of the voice-repeat
  ride (confirms the crash path); device check of the tour-edit lines in Demo mode; owner idea jog rule + way types
  (backlog, think first); a mailbox epoch on the server (cloud-plan). **Next:** the rest of the backlog; release after the
  captain's go.
- **cloud-app (donald), 2026-10-31/11-01 – captain's order done.** Release-Rundgang 14/14 PASS (flows 11 + 12 green),
  again 14/14 on main with voice. Voice guidance merged to `main` (69515dde): branch merged with main, one test made
  tolerant to the line's geometry length, Settings → Navigation screenshots EN + DE; full gate green (2253 tests). Debug
  build installed on the Pixel (data kept). Field bugs (4c64138d): T0 bike plan link (shared `PlanCode` – cloud-plan
  told in the mirror list), track behind „Unterbrochene Tour“ + EN "Interrupted tour", shared place menu on every share,
  shared place found by address takes the named POI (kind). **Next:** owner tests voice on the Pixel, captain's go →
  release (version bump + notes; website / Play voice texts then change, marketing list).
- **cloud-captain, 2026-10-31/11-01 night – release check + pre-release review.** Release check for voice: fingerprint,
  sync format, shared code equal on `main` / `web-spike` → go v0.14.0 (waits for the owner's Pixel test + green E2E;
  E2E was red on 69515dd / 3649b9cc – the voice setup step; cloud-app's fix 5b2dfeec still running at hand-off).
  **Review:** `docs/reviews/2026-11-01-pre-release.md` + six reports and two root-cause notes in
  `docs/reviews/2026-11-01/` – parity OK, no lost tracks, fix list A (cloud-app 1–9, cloud-plan 10–13), owner
  decisions B (debug build + public key, install path, closed sync folders, test servers). The owner's three field
  bugs analysed: voice repeat = recording service restarts mid-tour (crash path), plan lines vanish in tour edits
  (four causes), quick ■ Stop in the rolled-up tour row. Release-day plan at the top of `docs/backlog.md`. A-019
  pictures copied Drive → `onpath.private/marketing/bilder/` (59 PNGs); cloud-web ordered to put them into the help
  pages + draft a tester note. The captain session is cleared (trusted-device card) – messages to the roles work.
  **Next:** morning – owner confirms list A / decisions B; check E2E; after the fixes re-run the release check and
  give the go for app + planner in the same hour.
- **cloud (marketing / website), 2026-10-29/31 – onpath.earth live, planner public, Play Console prepared. Session
  ended (owner: fixed roles).** Website repo `happychriss/onpath.earth` (Cloudflare Pages, deploys `main`): PRs #3–#7
  merged – new home page (six points with real app screens, planner section, "Was OnPath besser kann", "Offen
  entwickelt"), tester guide `/ausprobieren/` (Google Group "OnPath – Mitmachen & Testen" linked; Play link still
  grey), "Die Idee" with the owner's portrait, privacy for planner + tester group + sync last-seen + Android device
  backup, contact info@onpath.earth (Cloudflare Email Routing), external-review fixes (hold not tap, vibration only
  where one could go wrong, no absolute promises, honest test status). Site stays `noindex` until the legal check
  (`docs/RELEASE.md` there). plan.onpath.earth: Cloudflare Access off (owner), checked live. Play Console: app created
  (`com.onpath.app`), app access / ads / rating / audience 18+ / data safety / health / ad ID / store listing filled
  with the owner; store assets with the owner. Drive orders: A-019 (one image set per app version, donald), A-020
  (review points for the app), A-003 (Play build) still unstarted. Open items: backlog "Website and Google Play
  follow-ups". **Next:** Play build (A-003) → internal test → closed test → Play link on the website.
- **cloud (Claude Code web), 2026-10-31 – session ended (owner: switch to fixed roles).** On `main`: plan edits
  during a tour – the position splits the plan into passed / ahead (`navigation/TourSplit`, field bug Hilden, design
  `docs/design/tour-edit-position.md`, a3e9a4a3, CI green; Rundgang flows 11 + 12 only compiled); voice concept
  `docs/design/voice-guidance.md` (owner: all questions yes); F-NAV-05 planned. **Half done – branch
  `wip/voice-guidance` (c9fcc073), NOT on main, not compiled yet:** `navigation/VoiceGuide` + `VoiceText` +
  `strings_voice.xml` EN/DE (+ meta), `recording/Speaker` (TTS, offline voice, ducking), service wiring (`voiceStep`,
  `ACTION_SAY` for arrival / nothing ahead), Settings → Navigation voice switches per bike / hike, phone setup step
  VOICE (`PhoneCheck`, `SetupGuideScreen`, test via `MainActivity.testVoice`), tests `VoiceGuideTest`, `VoiceTextTest`,
  `PhoneCheckTest.voiceStep…`, docs. The earlier compile hang was a corrupted `VoiceGuide.kt` (129k lines, rewritten in
  c9fcc073). **Next:** compile + all unit tests + Paparazzi (Settings → Navigation and setup snapshots change) on that
  branch, fix, then merge to main; the owner tests on the Pixel (setup voice step, a ride). Not released.
- **cloud-plan (desktop donald, `~/Projects/OnPath-Planner`), 2026-10-31 – browser planner on `web-spike` 97e8836,
  live on plan.onpath.earth, CI green.** App ⇄ web parity made a gate: `web/test/plans.json` (14 plans: TU Dortmund
  Bug 01, Meran T3, Zoggler loop + delete / drag, MTB Lana–Ulten, meadow snap, pinned POI, failed leg, Köln island,
  Hilden short trip, Vienna no data, the owner's 70.6 km hike) – the app's own path on the JVM (`parityJvm`:
  BRouterEngine + PlanPoints + PlanResult) against the Wasm engine's page calls (`web/app/test/plan-parity.mjs`), all
  equal. The web now plans through the shared code: `routing/PlanResult` (snapped points, gaps incl. skipped, model
  time, warnings, rating, ProfileData), `gpx/PlanGpx` (export with `<wpt>`, re-import exact), `PlanPoints.deleteAt` /
  `move` (round trip stays closed), `RoutingFingerprint` (diagnostics + sync fields 7 / 8 on Save, grey line for a phone
  plan routed otherwise, `web-sync.md` §5.2), `RoutingDataNotice`, `BreakdownRules`, `ActivityLayers`. Also built:
  pairing across tabs (BroadcastChannel), new-version box (`msgcard.ts` = MessageCard, `version.json`), Unpair apart,
  New / Changed tags, search list kept + six chips, Clear during a long plan answers at once, first-route fit. Speed
  (owner): adding a point to his 70.6 km hike was ~5 s – half was the Bug-01 pass re-routing the whole chunk; main's
  splice (38fc16c, owner "Option 1") → live 2.2 s. Lessons: donald runs out of memory with parallel builds (Gradle
  under `~/onpath-cells-work/gradle.lock`, ≤ 2 workers, one heavy run at a time); Node never exits after loading the
  TeaVM Wasm engine – run engine tests in a child process. Handoffs in Drive „2026-10-31 donald (Web-Planer) …“ (3
  docs) are superseded by this entry and the backlog. **Half done / next (backlog "Web planner: mirror app changes"):**
  Save asks for the name + plan sheet without ⋯ (app 0.13.5), candidate marker, fit after adding a place, rest of the
  message system, margin cells; merge main (d288503 and later).
- **cloud, 2026-10-29/30 – 0.13.0 phone setup (owner: yes to all 4 decisions).** Welcome page instead of the start-up
  dialog; step-by-step guide (location precise / on, notifications, vibration test, battery, brand steps, battery
  saver, unused-app reset) from the welcome, before a tour, the ◎ button and Settings → Phone setup; re-check on every
  return; "removed by the phone" after months; background location removed; "Add to route" → "Set as destination" /
  "Add as stop N" / "Add as new destination". Earlier the same day: code + docs clean-up, Drive handoffs consolidated.
  **Next:** owner's Pixel checklist (backlog), memory work (donald, wip/memory-fix1), smarter rejoin, Knotenpunkte.

## Where we are
- Android app, Kotlin + Compose + MapLibre, fully offline: map cells and routing data load per area from the map
  server (`docs/design/map-cells.md`, `docs/design/map-hosting.md`), offline BRouter routing, recording as a
  foreground service, Plan ⇄ Navigate (`docs/ux-two-modes.md`), place search (offline index + Photon), GPX in / out,
  backups, in-app updates, UI in English and German (`docs/i18n.md`).
- Owner tests on a Pixel 8 (Android 17). Releases go out from `main` (see CLAUDE.md "Releases"); E2E (emulator) and
  the full unit suite run after every push.
- Map pipeline and cell builds: `docs/map-pipeline.md`, `tools/cells/`, `tools/cloud/`, dev loop `tools/dev/`.

## Decisions (still valid; product rules also in CLAUDE.md and `docs/owner-preferences.md`)
| Topic | Decision |
| --- | --- |
| Platform | Android first (Kotlin, Compose); iPhone later (`docs/design/multiplatform.md`, core logic free of Android) |
| Licence / repo | Apache-2.0, public repo github.com/happychriss/OnPath |
| Distribution | Sideloaded test builds (GitHub Releases + in-app updater). Google Play (owner 2026-10-30): app created in the Play Console, free download, purchases later in the app; closed test with the Google Group "OnPath – Mitmachen & Testen" (join on request, owner approves); needs the Play build (A-003) |
| Website | onpath.earth (repo `happychriss/onpath.earth`, Cloudflare Pages): welcome, help, tester guide, privacy, imprint; `noindex` until the legal check; contact info@onpath.earth. Planner plan.onpath.earth public (no login), "App holen" only after Share / on shared links |
| Map | MapLibre Native 13.7.1, PMTiles, own style from `tools/make_style.py` (one style, `tools/themes/classic.json`) |
| Routing | BRouter (MIT) embedded, offline, rd5 segments; profiles per activity (CLAUDE.md "Activities") |
| Activities | Exactly six: Bike, Gravelbike, E-bike, MTB, E-MTB, Hike T1–T5 |
| Voice | Planned (owner 2026-10-31): Android text-to-speech, offline voices, only where we vibrate – backlog "Voice guidance" |
| Komoot GPX | Imports correctly; `<trk><type>` preselects the activity, export writes it |
| Offline corridor | Map areas and routing data follow the plan (~10 km corridor, at most 30 areas) |
| Search | Offline OSM index + Photon (rough position only, ~1 km); no Google Places |
| Google | Never store or draw Google content; link out only (exception: a place the user shared from Google Maps into OnPath – shown with the pin, and kept in "Recent" with a share sign, owner 2026-11-02) |
| Place shared from Google Maps | The map flies there, shows the found-place pin with its name chip ("Name ›", preview line to the route) and opens its menu by itself, once per share; a search hit gets the same pin + chip, its menu opens with a tap on the chip (owner 2026-11-02) |
| Footways without a bicycle tag | Bike / Gravel keep avoiding them; the amber "… m pushing" note is enough (owner 2026-10-30) |
| POIs | From OSM, offline; calm defaults like Komoot |
| Pace | Settings pace (5 levels); during a tour the arrival adapts after ~15 min / 1 km; nothing carried across tours |
| Battery | Slower GPS at rest, never lose a point; no battery % in the summary |
| Map ↔ route colours | Green = good, black = possible, red / dashed = forbidden; difficulty = grey line |
| Navigate | Only the followed line (no numbered points); what Plan shows = what Navigate follows |
| Deleting all points in a tour | Guidance ends, recording goes on, Undo restores |
| Messages | `docs/design/ui-style.md`: FRAGE box (blue edge, two verbs) / HINWEIS box (one button; grey, amber ⚠, red ⚠) / ANZEIGE (no box); one box at a time, 12 dp under the top; no dark bar |
| Settings | Topic list, one page per topic; developer tools after 7 taps on the version |
| Vehicle detection | Nothing during the ride; a summary with four choices at End tour; raw points kept |
| Ideas dropped | Sunset warning, emergency screen, battery check at start, weather |

## Environment notes (for cloud / AI sessions)
- No Android emulator in the cloud container (no KVM): rely on unit tests, Paparazzi screenshots, JVM routing tests
  (`tools/routing/jvm-test/run.sh`) and headless-browser map renders; CI runs the emulator tests.
- Reachable: download.openstreetmap.fr, brouter.de, AWS S3 terrain tiles, osmdata.openstreetmap.de, naciscdn.org,
  raw.githubusercontent.com, maven.google.com, Maven Central (sometimes HTTP 429 – retry).
  Not reachable: download.geofabrik.de, overpass-api.de.
- Private material (owner's GPX, Komoot data, orders) lives in Google Drive "OnPath" – never copied into this repo.
