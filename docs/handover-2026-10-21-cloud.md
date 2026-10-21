# Handover cloud → laptop (2026-10-21, evening)

Owner: "write a full handover to the laptop … will continue from laptop." Read this, then `docs/backlog.md` "Next"
and `docs/core-features.md`. All work is on GitHub; nothing is only in the cloud container.

## 1. Where things are

| Where | What |
| --- | --- |
| `main` (released **0.8.5**) | everything below up to "E2E gate"; release.yml runs the emulator tests first (`needs: e2e`) |
| branch **`e2e`** (not merged, not released) | hold diagnostics, **route ⋯ → "Points… (delete)"**, field-condition emulator tests, real routing data in CI, ANR handling – see §3 |
| GitHub Actions | `E2E` (emulator, every app push to main + gate in Release), `Release`, `Maps deploy` |

Merge `e2e` into main when its emulator run is green (`gh api repos/happychriss/OnPath/actions/runs?branch=e2e`),
then bump to 0.8.6 + `docs/release-notes/v0.8.6.md` (point list + hold diagnostics). The owner wants 0.8.6.

## 2. Releases today (all from the cloud)

- **0.8.1** map-loading overlay (modal, Cancel), outdated areas update themselves (content schema `-s2`),
  Settings → Diagnostics → *Reset maps (like a fresh install)*, release APKs carry the test map key
  (GitHub environment `test`, secret `ONPATH_APP_KEYS`, newest entry, masked).
- **0.8.2** route bands drawn **over** streets/roads, under paths/trails/rail (routes on small streets looked missing).
- **0.8.3** owner decisions on map updates (`docs/design/map-updates-ux.md`): check the map areas along a route
  **after routing**; ready → quick download with a veil; to be built → small hint; overlay only where no detailed
  map exists; **mobile data by default** ("Maps only on Wi-Fi" setting); no map switch during a tour
  (`MapCells.holdUpdates`); routing data (rd5) older than 35 days refreshed after a route (also mobile) + silent
  re-route, notice only when the way changed. No idle / monthly refresh any more.
- **0.8.4** hold on a route point: marker found from the **plan's own list** (not the drawing copy); markers redrawn
  after Navigate → Plan; near-miss diagnostics.
- **0.8.5** app update download resumes (Range, 4 tries) – field: 4× "connection abort".

## 3. OPEN – core bug C1: holding a route point adds a new point (no menu)

Owner, on 0.8.3 and **still on 0.8.5**: hold a point → no menu, a new point is added. Second regression
(0.7.19 was the first) → owner rule: core features get tests first (`docs/core-features.md`, CLAUDE.md).

What is known:
- JVM tests (`CoreHoldPointTest`) and emulator tests pass for: plain map, turned map (137°), after a style reload,
  "Go here" from my location with a moving GPS fix and the map following, real BRouter routing (snapped points).
- The **Navigate → Plan** emulator test (`CoreFieldE2ETest.navigateThenBackToPlanStillDeletes`) was blocked by the
  one-time "Keep recording after a restart?" dialog (fixed on `e2e`); its result is the first thing to check.
- The last `e2e` run before the ANR fix failed everywhere at the very first hold with no "Hold:" log at all – most
  likely a system dialog of the loaded CI emulator (now closed by `dismissSystemDialogs()`); check the newest run.
- **Every hold in Plan now logs one line** (branch `e2e`, MapHost): `Hold: marker M, finger-down D, nearest point N
  at X dp, P points (Q drawn), follow F, bearing B°, menu open O`. It goes to logcat (tag `OnPath`) and to the
  diagnostics export.

**Next (laptop, phone on USB):** build the `e2e` branch (`./gradlew assembleDebug`, `adb install -r`), then
`adb logcat -s OnPath | grep Hold:` while the owner holds a point. The line decides:
- X large (> 26 dp) while the finger is on the drawn marker → touch coordinates and map projection disagree
  (suspect: MapView inside Compose after a mode / layout change, camera padding from Navigate);
- P ≠ Q → drawing and plan out of step;
- marker found but no menu → the menu (MapOverlays `Anchored` / `onGone`) closes itself;
- no "Hold:" line → the press never reached MapHost (overlay / dialog in front, MapLibre long-press not firing).
Write the reproducing emulator / JVM test first, then the fix (owner rule).

**Do not run the emulator tests on the owner's phone as they are:** `E2EBase.launch()` deletes the app's data folder
and prefs (map areas, plan, settings). Make a phone-safe variant first, or use a second device.

Workaround for the owner until fixed (branch `e2e`, ships with 0.8.6): route ⋯ → **Points… (delete)** – list of all
points with a Delete button, no map gesture (`PointListDialog`, tests: `PointListTest`, `deleteFromThePointList`).

## 4. Owner decisions today (keep)

- Map loading: plan on the latest map (check after routing), modal only without any detailed map, mobile data
  default, routing data is the most important ("A" – refresh on mobile too), never switch the map during a tour.
- Core features must have regression tests; **emulator tests before every release** ("not good to find out during
  walking"). A field bug in a core feature: first a test that reproduces it, then the fix.
- **Overview map: one world overview, the Alps are not treated specially** (owner: "gerade wenn ich teste").
- **Testing starts like a complete fresh install** (owner: "Erstmal fangen wir beim Test wie eine vollständige neue
  Installation an").

## 5. Next tasks for the laptop (in order)

1. **C1 hold bug** – §3.
2. **Merge `e2e`, release 0.8.6** (point list + hold log) once the emulator run is green.
3. **Fresh-install test procedure** (owner): before a test, export tours (My tours → export) if needed, then
   `adb uninstall com.onpath.app` (or `adb shell pm clear com.onpath.app` + delete
   `/sdcard/Android/data/com.onpath.app/files/onpath`), install the GitHub release APK, start: permissions, overview,
   set a point → overlay → cell build/download, routing data download. Note every rough edge. In-app shortcut:
   Settings → Diagnostics → Reset maps (cells only, keeps routing data, tours, settings). The emulator tests already
   start from a clean app each time.
4. **World overview map** (owner: no Alps special case). Today only the laptop has an Alps overview
   (`tools/build_overview.sh`, base z0–8 + hill shade z5–9, 13.7 MB) and **GitHub release APKs ship none** (assets not
   in git, release.yml does not fetch them) → outside the cells / below z10 the phone shows an empty map. Plan:
   - build one **world** overview (OpenMapTiles schema like the cells, so one style): base z0–6 or z0–7, hill shade
     z0–7 (measure sizes; target ≤ ~20 MB in the APK) – Planetiler on the planet needs a big machine → a one-off
     Cloud Run Job (tools/cloud) or the laptop; alternative: Planetiler `--bounds=world` with Natural Earth + OSM
     water/land polygons only for z0–5;
   - store the two files in R2 (versioned folder, e.g. `overview/<date>/`), **release.yml downloads them** into
     `ONPATH_OVERVIEW_ASSETS` before `assembleDebug` (no map data in git);
   - app: `map/Overview.kt` already draws `overview/*.pmtiles` below z10; check `pmtiles://asset://` on the phone
     (map-data-size.md §1b: not verified on a device yet).
5. Emulator tests still to add (docs/core-features.md): recording survives Plan mode, a real cell generation
   (map update), fresh install → first point → overlay.
6. From before: production map environment not deployed; old cell version folders not cleaned; "Update maps now"
   could go (owner); first-start "base.pmtiles path not found" (diagnostics 10:46); JVM routing test "unreachable T1
   Ifinger"; delete branches `downloads` and `e2e` on GitHub (owner – sessions cannot delete branches).

## 6. Useful facts

- Phone (diagnostics 14:54): Pixel 8, Android 17, 0.8.4 then 0.8.5; 4 cells `2026-10-19-s2` (small streets ok);
  routing data `E10_N45.rd5` from 2026-10-17 (no refresh until 35 days).
- Emulator tests: `app/src/androidTest/.../e2e/` (`E2EBase`, `CorePlanE2ETest`, `CoreFieldE2ETest`); CI artifact
  "e2e-report" holds screenshots (`e2e-out/e2e/*.png`), per-test logcat and the JUnit XML. Locally:
  `./gradlew connectedDebugAndroidTest -Ponpath.e2e=true` (x86_64 emulator; `-Ponpath.e2e=true` adds x86_64 libs).
- Mock GPS on the emulator: `cmd location providers add-test-provider gps` / `set-test-provider-location gps
  --location LAT,LON` (CoreFieldE2ETest); real routing data is pushed to `/data/local/tmp/E10_N45.rd5` by the workflow
  (cached weekly).
