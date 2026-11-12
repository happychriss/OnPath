# OnPath – project status and decisions

Current release **v0.17.2** (2026-11-07). Open work: `docs/backlog.md`. Core features and their tests:
`docs/core-features.md`. What each release changed: GitHub Releases (`docs/release-notes/` holds only the current
release's notes) and the git history – this file keeps only the current state and the decisions that still apply.

## Where things are
<!-- Kept by the captain every round (docs/roles/captain.md). -->
- **GitHub release:** v0.17.2 (2026-11-07 ~07:45, 7dd730f5, donald): calmer look (walnut, paper, ink blue), plan and tour
  apart with "Zuletzt", hold to unlock, route previews, the night's code audit. Before it: gate --full, GitHub E2E
  CoreField / CorePlan / CorePlanLink / Release-Rundgang green, unit.yml green. Details:
  `docs/history/handoff-donald-2026-11-06.md` ("MORNING 2026-11-07").
- **Planner (plan.onpath.earth):** live 78f5a4a6 (laptop, 2026-11-06/07: plan bar, list km / ↑↓, 3D flyover, big profile
  range). **Not deployed with v0.17.2:** "Web planner" (`web.yml`) is red on parity since 2026-11-06 22:39 – 17 mirrors of
  the new app look; the laptop's catch-up is on branch `wip/laptop-2026-11-07-parity-colours` (backlog "Next laptop
  session – start here"), then Actions → Web planner → Run workflow → deploy.
- **Pixel:** not checked after v0.17.2 – ask the owner / check About.
- **Google Play internal test (owner 2026-11-07): live.** App `com.onpath.app`, Play App Signing (Google holds the app key),
  upload key only on donald in `.secrets/` (gitignored), `tools/dev/play_bundle.sh` → `dist/onpath-<version>.aab`,
  uploaded by hand (`docs/play-build.md`); first release 0.17.3 (versionCode 1703), 0.17.4 (1704) live 2026-11-07, 3–4 testers by email list. Every new
  upload needs a higher versionCode. The owner's Pixel stays on donald's debug builds (Play over GitHub/debug = uninstall).
- **Owner decisions 2026-11-07:** "on the line" = 24 dp (the `metersPerPixel` fix has been on main since 46e93b09);
  offline corridor 10 km on foot, 20 km for bike activities (donald). Smaller open items: `docs/backlog.md`; open questions: backlog "Waiting for the owner"; the owner's own questions: the Fragenregister.

## Last session
- **laptop, 2026-11-12 late – deployed (d5e5b842):** after any pairing the browser pushes every waiting tour at once, no Save (owner: Save first was not ok; web-sync.md §7/§14); activity list no longer slides over its chip when the pace part arrives (Web planner red since 11-08, parity-smoke); search-smoke „Vellau from the base map“ red once in a local run, green twice after – watch.
- **laptop, 2026-11-12 – pairing the browser kept but the phone lost (owner field, Pixel):** phone silent ≥ 3 days → chip yellow „seit N Tagen nicht verbunden“, the Phone window one sentence + „Neu verbinden“ (unpair + new code in one step; the closed folder's tours come along even when a reinstalled phone reports a new device id – they wait and go up with the next Save); „Jetzt abgleichen“ says done / nothing new; no remove lines for old phones. sync-smoke 14e; check-web.sh green. Deploy by the owner (Actions → Web planner).
- **laptop, 2026-11-12 – three owner items on main, planner deploy pending (owner starts it by hand / gh rule):** (1) POI as route point keeps its symbol + route-blue ring + numbered badge, app AND planner (`poi/PinnedPoiKinds.kt`, `web/app/src/poipin.ts`, poipin-smoke; not yet seen on a device); (2) planner ↶ ↷ in their own pill, only when something can be undone, like the app's UndoPill; (3) planner profile calm like the app (route-blue fill fading, steepness only on the line; map steepness line flat = route blue now). check-web.sh all green on the merged tree; search-smoke text fixed. Open: pairing fix (browser kept a pairing the Pixel lost) in work – see the claim.
- **laptop, 2026-11-08 evening:** planner synced with main at v0.17.6 – 8 app mirrors checked: T chips keep the activity list open with the level line „T2 · Bergwandern: …“ and „Fertig“ (app 2a54f2e4), Help has the app's About links (help, website, contact, privacy, Impressum, source – ca5f0f7b); storage-not-ready / brand page / first-map card / sheet rise phone only; undo pill → backlog for the owner. Deployed by hand. Calm phase: no wake-up (captain 2026-11-07).
- **laptop, 2026-11-07 – Meine Touren like the app (9f967f05, live):** route preview tiles (routeshape.ts, the line kept like the numbers), the plan on the map „In Bearbeitung“ first under Zuletzt, drafts with Planen · Speichern · Löschen, the clean saved row with ⋮ (Planen first) and ›, the large overview (Abbrechen · Planen), a new route draws itself in 0.5 s (routereveal.ts). web-planner.md §25; import- and sync-smoke follow; shots https://claude.ai/artifact/6cce4twTRBjtYshAV4fMBB. Next laptop item: „gekoppelt = eine Liste“.
- **laptop, 2026-11-07 – planner PlanReach (donald's note):** a stop the route does not reach says what does („② erst ab T2 erreichbar“, the app's PlanReach, run in the engine after the route), Show draws the reaching way amber; parity MapController / AppViewModel / PlanReach (718d1e86), deployed.
- **laptop, 2026-11-07 – planner parity + plan sheet A (both live):** "Web planner" green again – the app's colour rule,
  blue #335A85, paper B, warm shadows, corner family, blue compass, goal flag, quiet locate (d5aace4c, 8ca0d359). Plan sheet A
  (a239a275, order like the phone 480b646d): wide = facts, plan row, lines, then the points with „+ Ziel hinzufügen“, no
  steps, the profile along the map's bottom for good; phone = two steps („2 Punkte ⌃“, „⤢ Höhenprofil groß“). Shots on https://claude.ai/artifact/F1rCK7xXXmUhWcQRj3o1mR
  („Gebaut“). Open: Meine Touren like the app's (backlog), „gekoppelt = eine Liste“.
<!-- Newest first; keep the newest 2–3 entries – older ones go (git history has them). Who (donald / laptop / captain / cloud-web), date, what changed, what's half done, next. -->
- **donald, 2026-11-06 evening → 2026-11-07 morning – READ `docs/history/handoff-donald-2026-11-06.md`** (section
  "MORNING 2026-11-07" first): the six evening branches merged, code audit (launchSafe, geo math in one place,
  PlanSession / TourController / RoutesState out of AppViewModel, tests 2840 → ~2610, dead code and 37 strings out),
  v0.17.2 released. Decided without the owner and left for later: listed there.
- **laptop, 2026-11-07 – 250 tours per phone (owner):** shared `sync/SyncLimits` (250, amber from 200, JVM test); the
  planner refuses a NEW tour beyond 250 before the name is asked (Save amber + why), the Phone window shows „N von 250
  Touren“ with a bar; the host returns the mailbox use with the pull, its 1000 items stay a safety net. sync-smoke part
  17. App side → [donald] item in "Next". Not deployed yet.
- **laptop, 2026-11-06/07 – one branch + planner round (owner in the chat all day):** web-spike merged into main (PR #3),
  daily sync task paused; PR #5 deploy+quick (test phase: deploy after the affected local tests, owner). Live on
  plan.onpath.earth (78f5a4a6): plan bar (+ Neu · Meine Touren with the app's search · Speichern ▾ with the phone badge
  when paired · 📱 phone status with „abgeglichen vor …“), list km / ↑↓, round-trip toggle, all glyph ranges, big profile
  range + „⤢ Großes Profil“, 3D flyover, 3D perf 31 → 60 fps. Flaky smokes fixed (test-side camera races). Laptop map
  store NRW + Meran (`tools/dev/fetch_area.py`). Open: the backlog's „Next laptop session – start here“; app items handed
  to donald (lock line, Navigate ⚙ settings look, 250 in the app). Owner questions in the Fragenregister (laptop-2026110x-*).

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
How we work together: `docs/owner-preferences.md`.

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
| Messages | `docs/design/ui-style.md`: FRAGE box (blue edge, two verbs) / HINWEIS box (one button; grey, amber ⚠, red ⚠) / ANZEIGE (no box); one box at a time, 12 dp under the top; no dark bar. **Nothing disappears by itself** – everything stays until answered or the situation is gone ("we are a hiking app – it can rain, stress…"); nothing may flash |
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
