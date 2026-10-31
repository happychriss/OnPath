# OnPath – project status and decisions

Current release **0.13.0** (2026-10-30). Open work: `docs/backlog.md`. Core features and their tests:
`docs/core-features.md`. What each release changed: `docs/release-notes/` and the git history – this file keeps only
the current state.

## Last session
<!-- Overwrite at the end of every session: who (laptop/desktop/cloud), date, what changed, what's half done, next. -->
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
| Distribution | Sideloaded test builds (GitHub Releases + in-app updater); Google Play internal track later |
| Map | MapLibre Native 13.7.1, PMTiles, own style from `tools/make_style.py` (one style, `tools/themes/classic.json`) |
| Routing | BRouter (MIT) embedded, offline, rd5 segments; profiles per activity (CLAUDE.md "Activities") |
| Activities | Exactly six: Bike, Gravelbike, E-bike, MTB, E-MTB, Hike T1–T5 |
| Voice | Planned (owner 2026-10-31): Android text-to-speech, offline voices, only where we vibrate – backlog "Voice guidance" |
| Komoot GPX | Imports correctly; `<trk><type>` preselects the activity, export writes it |
| Offline corridor | Map areas and routing data follow the plan (~10 km corridor, at most 30 areas) |
| Search | Offline OSM index + Photon (rough position only, ~1 km); no Google Places |
| Google | Never store or draw Google content; link out only (exception: a place shared from Google Maps) |
| Place shared from Google Maps | The map flies there, shows the orange ring and opens the ring's add menu by itself, once per share |
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
