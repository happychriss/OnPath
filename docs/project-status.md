# OnPath – project status and decisions

Current release **0.12.0** (2026-10-28). Open work: `docs/backlog.md`. Core features and their tests:
`docs/core-features.md`. What each release changed: `docs/release-notes/` and the git history – this file keeps only
the current state.

## Last session
<!-- Overwrite at the end of every session: who (laptop/desktop/cloud), date, what changed, what's half done, next. -->
- **desktop (donald), 2026-10-29/30 – memory, plan sync, screenshots (captain session with background agents).**
  On main since 0.12.0 (not released yet): Demo location (Developer tools), app colours closer to onpath.earth
  (A-009), tools/hetzner removed, **POIs as vector tiles built on the phone at join** (Plan 1.49 → 0.45 GB PSS on the
  Pixel 8, no cell / server change), **search index on disk** (search +67 → +13 MB Java heap), **plan sync with the
  browser** (A-007, core feature C16, owner's privacy sentence). Memory goal (< 800 MB in Plan) is met: 0.36–0.58 GB.
  **Half done:** branch `wip/memory-fix1` (50a470f, not verified after the last main merge): free the GeoJSON
  stand-in before a style reload, prefetch delta 1, trim → `onLowMemory`, fuller health line – notes and numbers in
  external-docs/memory/fix1-wip.md (private). Savings only 10–30 MB now; graphics (150–250 MB) is the biggest part.
  **Next:** finish / trim that branch (tests, keep prefetch 1?, split commits) → propose release 0.12.1 (owner OK);
  phone test of plan sync once plan.onpath.earth has the mailbox; web planner takes over the track format (backlog);
  owner questions: area names in screenshots 06 / A-006-3, silent lock-screen notification, DEMO tag on screenshots.

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
| Distribution | Sideloaded test builds (GitHub Releases + in-app updater); Google Play internal track later |
| Map | MapLibre Native 13.7.1, PMTiles, own style from `tools/make_style.py` (one style, `tools/themes/classic.json`) |
| Routing | BRouter (MIT) embedded, offline, rd5 segments; profiles per activity (CLAUDE.md "Activities") |
| Activities | Exactly six: Bike, Gravelbike, E-bike, MTB, E-MTB, Hike T1–T5 |
| Voice | Not planned; off-route warning and junction vibration instead |
| Komoot GPX | Imports correctly; `<trk><type>` preselects the activity, export writes it |
| Offline corridor | Map areas and routing data follow the plan (~10 km corridor, at most 30 areas) |
| Search | Offline OSM index + Photon (rough position only, ~1 km); no Google Places |
| Google | Never store or draw Google content; link out only (exception: a place shared from Google Maps) |
| POIs | From OSM, offline; calm defaults like Komoot |
| Pace | Settings pace (5 levels); during a tour the arrival adapts after ~15 min / 1 km; nothing carried across tours |
| Battery | Slower GPS at rest, never lose a point; no battery % in the summary |
| Map ↔ route colours | Green = good, black = possible, red / dashed = forbidden; difficulty = grey line |
| Navigate | Only the followed line (no numbered points); what Plan shows = what Navigate follows |
| Deleting all points in a tour | Guidance ends, recording goes on, Undo restores |
| Messages | INFO grey / ACTION blue strip for questions / WARNING amber ⚠ / ERROR red with one way out; no dark bar |
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
