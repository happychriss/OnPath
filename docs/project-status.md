# OnPath – project status and decisions

Current release: see "Where things are" below. Open work: `docs/team/backlog.md`. Core features and their tests:
`docs/core-features.md`. What each release changed: GitHub Releases (`docs/release-notes/` holds only the current
release's notes) and the git history – this file keeps only the current state and the decisions that still apply.

## Where things are
<!-- Kept by the captain every round (docs/team/roles/captain.md). -->
- **GitHub release:** v0.18.11 (2026-11-27 00:05 UTC, bc388b880, owner's word 2026-11-26 23:04): the Daumen-Karte (search,
  ☰ and activity in the plan card, no top band), menus likeliest row first, T1–T5 as rows, „Als Übernachtung“.
- **Planner (plan.onpath.earth):** deployed by the 0.18.10 release run (planner job green, 2026-11-25), with the detour line
  and the modal wait window.
- **Pixel:** the owner gets updates from GitHub this weekend (not on USB); whether 0.18.7 is installed is not known.
- **Google Play internal test (owner 2026-11-07): live.** App `com.onpath.app`, Play App Signing (Google holds the app key),
  upload key only on donald in `.secrets/` (gitignored), `tools/dev/play_bundle.sh` → `dist/onpath-<version>.aab`,
  uploaded by hand (`docs/play-build.md`); first release 0.17.3 (versionCode 1703), 0.17.4 (1704) live 2026-11-07, 3–4 testers by email list. Every new
  upload needs a higher versionCode. The owner's Pixel stays on donald's debug builds (Play over GitHub/debug = uninstall).
- **Owner decisions 2026-11-07:** "on the line" = 24 dp (the `metersPerPixel` fix has been on main since 46e93b09);
  offline corridor 10 km on foot, 20 km for bike activities (donald). Smaller open items: `docs/team/backlog.md`; open questions: backlog "Waiting for the owner"; the owner's own questions: the Fragenregister.

## Last session
- **donald, 2026-11-27 – 0.18.11 released** (Daumen-Karte); cleanup branch `chore/aufraeumen-0.18.11` waits for the owner.
- **donald, 2026-11-25 – 0.18.10 released + planner deployed** (night list: restore box, detour reason, share-name blocker fixed test-first, E2E hold test steady under new brouter.de data) – `docs/team/handoffs/handoff-donald-2026-11-24.md` top.
- **donald, 2026-11-24 late – standing-still GPS by the motion sensor, on main (42ddd8bcc), test APK on his Pixel 23:03:** owner's word after his Pixel lying indoors woke 9 times in 48 min: poor fixes no longer wake while the sensor says still, a sensor minute rests, the sensor moving wakes at once, GPS OFF after 1 h still (owner) – back on at the first move or when the sensor loses its word (`recording/RestGps`, `RestWatch.onTick`). Gate full green; NOT checked: E2E, a real tour.
- **laptop, 2026-11-24 – long plan link online, wait window modal + pairing-link fix on main (READ `docs/team/handoffs/handoff-laptop-2026-11-17.md`, top):** the owner starts planner deploys in Actions; the next one carries the modal window and the pairing fix. LordTriangel's PRs: planner notes in the laptop chat.
- **donald, 2026-11-24 – detour line on main in the owner's words** („Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar“; gate full, planner check green; the laptop has the web line). Readers-all merged with main (3c7779e2c, gate full green), waits for the captain's Wasm check + [umbau]'s review. Restore-plan box: picture approved, stays until answered, not built. LordTriangel PRs 20–22 read (notes in the backlog „For the captain“). Pixel log 2026-11-24: nothing new (known perf / snapshot warnings).
- **donald, 2026-11-22 evening – 0.18.8 and 0.18.9 released (READ `docs/team/handoffs/handoff-donald-2026-11-21.md`, top):** a field-log line per POI press; a press now answers after the next drawn frame, because a turning compass can keep the map from ever going idle (measured on the emulator, not proven on his Pixel). Planner deployed.
- **donald, 2026-11-21 evening – 0.18.7 released (READ `docs/team/handoffs/handoff-donald-2026-11-21.md`):** POI press waits for the map's idle (cause on the owner's Pixel still unproven; the field log now says whether the list was stale), ride question at save, off-route voice with side and metres only when sure, far-start warning. Full E2E 106/106 on 53031a337. The release run's planner check went red (areas-smoke), so the planner was not deployed: the laptop has it. Rests: `docs/team/items/2026-11-21-review-rests-off-route-ride-question.md`.
- **umbau, 2026-11-21 – multi-day day rows (READ `docs/team/handoffs/handoff-umbau-2026-11-19.md`, top state):** the owner's variant A in the app and the shared `DayRowText`; the planner follows from the web-ready line; a hotel at the only known end reads „ab …“ / „bis …“ (owner); the detour stitch is being built on `wip/umbau-2026-11-21-detour-stitch` (pieces 1 + 2a), nothing of it on main until `SameRouteTest` passes on JVM + Wasm.
- **umbau, 2026-11-21 morning – planner block closed (READ `docs/team/handoffs/handoff-umbau-2026-11-19.md`, top state):** route run for the planner (`begin(askLong)`, `PlanTourPort`, `MapDataPort.None`) and the pure moves (ThumbRules, CellCards, MapCredits, RoundTrip + RouteNamer, ProfileRules, SearchLines) on main; owner: only logic mirrors count, no further cuts; the laptop wires the rests.
- **umbau, 2026-11-20 afternoon – after 0.18.2 (READ `docs/team/handoffs/handoff-umbau-2026-11-19.md`, top state):** Zuletzt updates its entry (`RoutesState.keepDraft` with an update port; the planner still to follow – [laptop]); the owner's test cuts built (566 → 505 pictures, colour pins out, `docs/team/items/2026-11-20-test-streichliste.md`); planner block in `state/`: the laptop's wishes, and the cut `XState` + `XSlice : XState` for `PlaceMenus`, `PlanEdit`, `PlaceAdd` – all three listed in `web-ready.txt` (`docs/design/web-ready-status.md` §6). Next: `SaveSlice` / `RoutesSlice` the same way on the laptop's word.
- **tidy, 2026-11-20 night – repo tidy-up for 0.18.2 (READ `docs/team/items/2026-11-20-tidy-done-and-the-wip-branches.md`):** the sessions' notes are in `docs/team/`, guide page `docs/README.md`, spikes in `experiments/`, 91 done items and 12 old handoffs out, personal things cut. Open: the wip branches (list there, nothing deleted), test places in code – the owner's call.
- **IR, 2026-11-18/19 – tour suggestions live (READ `docs/team/handoffs/handoff-ir-2026-11-19.md`):** planner + app on by default, swipe card row, „Rundtour von hier“ only for the first point, suggest files in all cells, core lean (bike 3 h < 96 MB), README + website pictures.
- **umbau-hp, 2026-11-20 night – release hardening for 0.18.2 done (READ `docs/team/items/2026-11-20-umbau-hp-hardening-findings.md`):** everything since v0.18.1 reviewed in two rounds, no track-losing or crashing bug; five app fixes (plan-sync race, late „Übernehmen“, route picture retry, the route window's time line, a real C13 race in `MapCells` behind a flaky test – last 399e55b6) and the planner wait window (07b07800), each test first + fresh review. Next for umbau-hp: the routing port 5b after the release.
- **donald, 2026-11-19 – 0.18.1 released (dccc49789):** round trips for everyone, progress design (A window / B ring), Undo in the points list, „Tag 2 starten“, Zuhause hidden; four red tests fixed (TourDb 8→9, hold-measure, overview swipe, Rundgang flow 10 = a real double map move). Planner deploy pending ([laptop]). State: `docs/team/items/2026-11-19-donald-fresh-session-state.md`.
- **donald, 2026-11-18 late – READ `docs/team/handoffs/handoff-donald-2026-11-18-late.md`:** v0.18.0 released; calm line dropped; website pictures; open: 0.18.1 (owner's word), Zuhause branch, Undo in the points sheet, E06.
<!-- Newest first; keep the newest 2–3 entries – older ones go (git history has them). Who (donald / laptop / captain / cloud-web), date, what changed, what's half done, next. -->

## Where we are
- Android app, Kotlin + Compose + MapLibre, fully offline: map cells and routing data load per area from the map
  server (`docs/design/map-cells.md`, `docs/design/map-hosting.md`), offline BRouter routing, recording as a
  foreground service, Plan ⇄ Navigate (`docs/ux-two-modes.md`), voice guidance (`docs/design/voice-guidance.md`), place
  search (offline index + Photon), GPX in / out, plan sync with the browser planner (`docs/design/web-sync.md`),
  backups, in-app updates, UI in English and German (`docs/i18n.md`).
- Browser planner plan.onpath.earth (`web/`, `docs/design/web-planner.md`) compiles the app's shared Kotlin – same
  routes, same plan sheet numbers.
- Owner tests on a Pixel 8 (Android 17). Releases go out from `main` (`.claude/rules/release.md`); E2E (emulator) and
  the full unit suite run after every push.
- Map pipeline and cell builds: `docs/map-pipeline.md`, `tools/cells/`, `tools/cloud/`, dev loop `tools/dev/`.

## Decisions (still valid – the one place for product rules)
How we work together: `docs/team/owner-preferences.md`.

| Topic | Decision |
| --- | --- |
| Platform | Android first (Kotlin, Compose); iPhone later (`docs/design/multiplatform.md`, core logic free of Android) |
| Licence / repo | Apache-2.0, public repo github.com/happychriss/OnPath |
| Distribution | Sideloaded test builds (GitHub Releases + in-app updater). Google Play (owner 2026-10-30): app created in the Play Console, free download, purchases later in the app; closed test with the Google Group "OnPath – Mitmachen & Testen" (join on request, owner approves); needs the Play build (A-003, PR #2) |
| Website | onpath.earth (repo `happychriss/onpath.earth`, Cloudflare Pages): welcome, help, tester guide, privacy, imprint; `noindex` until the legal check; contact info@onpath.earth. Planner plan.onpath.earth public (no login), "App holen" only after Share / on shared links |
| App ⇄ planner | Same routes and plans: the planner compiles the app's shared files (`app/web-shared-files.txt`); the app leads, the web follows (`docs/design/web-planner.md` §14) |
| Map | MapLibre Native 13.7.1, PMTiles, own style from `tools/make_style.py` (one style, `tools/themes/classic.json`) |
| Routing | BRouter (MIT) embedded, offline, rd5 segments; profiles per activity (`.claude/rules/routing.md`) |
| Activities | Exactly six: Bike, Gravelbike, E-bike, MTB, E-MTB, Hike T1–T5 |
| Voice | Built (v0.14.0): Android text-to-speech, offline voices, only where we vibrate (`docs/design/voice-guidance.md`) |
| Komoot GPX | Imports correctly; `<trk><type>` preselects the activity, export writes it |
| Offline corridor | Map areas and routing data follow the plan (corridor 10 km hike / 20 km bike – `OfflineCorridor`, at most 30 areas) |
| Search | Offline OSM index + Photon (rough position only, ~1 km); no Google Places |
| Google | Never store or draw Google content; link out only (exception: a place the user shared from Google Maps into OnPath – shown with the pin, and kept in "Recent" with a share sign, owner 2026-11-02) |
| Place shared from Google Maps | The map flies there, shows the found-place pin with its name chip ("Name ›", preview line to the route) and opens its menu by itself, once per share; a search hit gets the same pin + chip, its menu opens with a tap on the chip (owner 2026-11-02) |
| Footways without a bicycle tag | Bike / Gravel keep avoiding them; the amber "… m pushing" note is enough (owner 2026-10-30) |
| POIs | From OSM, offline; calm defaults like Komoot |
| Pace | Settings pace (5 levels); during a tour the arrival adapts after ~15 min / 1 km; nothing carried across tours |
| Battery | Slower GPS at rest, never lose a point; no battery % in the summary |
| Map ↔ route colours | Green = good, black = possible, red / dashed = forbidden; difficulty = grey line |
| Gestures | Hold-only in Plan, no quick-tap actions, no dragging points (delete + add) |
| Re-routing | Only after asking; skip passed stops; show the original route grey |
| Navigate | Only the followed line (no numbered points); what Plan shows = what Navigate follows |
| Deleting all points in a tour | Guidance ends, recording goes on, Undo restores |
| Messages | `docs/design/ui-style.md`: FRAGE box (blue edge, two verbs) / HINWEIS box (one button; grey, amber ⚠, red ⚠) / ANZEIGE (no box); one box at a time, 12 dp under the top; no dark bar. **Nothing disappears by itself** – everything stays until answered or the situation is gone ("we are a hiking app – it can rain, stress…"); nothing may flash. Exception (owner 2026-11-26): a short info line **without a button** may go after a few seconds; anything with a button stays |
| The owner's words | A UI text the owner picked stays (owner 2026-11-01, "Hierhin"): a review or polish pass never rewords it – it proposes the change as a question |
| Core features | A field bug first gets a reproducing test, then the fix (`docs/core-features.md`) |
| Public texts | README, pitch, store: factual and calm, like telling a friend or a magazine review – what happens, no slogans, no superlatives, no exclamation marks, no competitor bashing; Komoot only as dated, sourced facts with credit where it is stronger. Everyday usability first; internals are basic requirements. Drafts to the owner before anything goes public |
| Private material | Marketing, the owner's GPX / Komoot data, orders, ideas: private repo `happychriss/onpath.private` – never in a public repo |
| Settings | Topic list, one page per topic; developer tools after 7 taps on the version |
| Vehicle detection | Nothing during the ride; a summary with four choices at End tour; raw points kept |
| Ideas dropped | Sunset warning, emergency screen, battery check at start, weather |

## Environment notes (for cloud / AI sessions)
- No Android emulator in the cloud container (no KVM): rely on unit tests, Paparazzi screenshots, JVM routing tests
  (`tools/routing/jvm-test/run.sh`) and headless-browser map renders; E2E on GitHub (`tools/ci/e2e_github.sh`).
  Setup once: `tools/cloud/session_setup.sh`.
- Reachable: download.openstreetmap.fr, brouter.de, AWS S3 terrain tiles, osmdata.openstreetmap.de, naciscdn.org,
  raw.githubusercontent.com, maven.google.com, Maven Central (sometimes HTTP 429 – retry).
  Not reachable: download.geofabrik.de, overpass-api.de.
- A test APK built in the cloud has no map key (`ONPATH_APP_KEYS` is a GitHub secret): it cannot download map areas;
  the in-app updater only sees GitHub releases.
- Maven Central mirror in a cloud session: only `beforeSettings` repositories in `~/.gradle/init.d` – an
  `allprojects { repositories }` line breaks this project's settings (`tools/cloud/session_setup.sh` does it right).
- A dispatched E2E run (`e2e_github.sh`) cancels the pending push E2E runs of the same ref; `e2e.yml` runs only the
  first class of a comma list – one class per run.

## Lessons from past sessions
- `tools/ci/gate.sh` misses androidTest and text-asserting tests in other packages: compile androidTest, run `--full`
  after text changes.
- Never install a build while a tour records (`adb shell dumpsys activity services com.onpath.app` → RecordingService).
- Content that only lives in a chat is lost at the next restart – results go into the repo as a file.
