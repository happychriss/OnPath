# OnPath – project status and decisions

Current release **0.13.5** (2026-10-31). Open work: `docs/backlog.md`. Core features and their tests:
`docs/core-features.md`. What each release changed: `docs/release-notes/` and the git history – this file keeps only
the current state.

## Übergabe Captain-Sitzung donald – 2026-10-31 abends
<!-- The captain session on donald ends here; the owner switches to fixed roles (`docs/working-agreement.md`). -->
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
- **cloud-app (donald), 2026-10-31/11-01 – captain's order done.** Release-Rundgang 14/14 PASS (flows 11 + 12 green),
  again 14/14 on main with voice. Voice guidance merged to `main` (69515dde): branch merged with main, one test made
  tolerant to the line's geometry length, Settings → Navigation screenshots EN + DE; full gate green (2253 tests). Debug
  build installed on the Pixel (data kept). Field bugs (4c64138d): T0 bike plan link (shared `PlanCode` – cloud-plan
  told in the mirror list), track behind „Unterbrochene Tour“ + EN "Interrupted tour", shared place menu on every share,
  shared place found by address takes the named POI (kind). **Next:** owner tests voice on the Pixel, captain's go →
  release (version bump + notes; website / Play voice texts then change, marketing list).
- **cloud-captain (fresh session), 2026-10-31 late – release check for voice done.** Read "For the captain"
  (cloud-app: Rundgang 14/14, voice on main 69515dd, field bugs 4c64138). Release check: fingerprint equal (BRouter +
  profiles unchanged, `PLANNER_VERSION` 3 on both), `sync/` unchanged since merge base 38fc16c, unit green on 69515dd,
  web-spike CI green on 97e8836; only shared change = `PlanCode` T0 read tolerance → go written for v0.14.0 in the
  backlog. Orders: cloud-app releases after the owner's Pixel voice test (and E2E on 69515dd / 4c64138 green – still
  running at hand-off); cloud-plan was merging main into web-spike (not pushed yet). Earlier captain decisions and the
  clearance rule (a fresh captain needs the owner's trusted-device card once; never attach to a computer folder) still
  hold – `docs/working-agreement.md` §8. **Next for the captain:** after the release tell cloud-web to change the
  website's voice sentence (+ Play store text, backlog "Website and Google Play follow-ups" (1)); re-run the check if
  cloud-plan's merge or a planner deploy touches shared code. Owner still owes the items under "Waiting for the owner".
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
| Google | Never store or draw Google content; link out only (exception: a place shared from Google Maps) |
| Place shared from Google Maps | The map flies there, shows the candidate marker (dashed blue ring with "+", preview line to the route) and opens its add menu by itself, once per share |
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
