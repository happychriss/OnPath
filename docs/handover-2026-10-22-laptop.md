# Handover laptop → cloud (2026-10-22)

Owner switches the laptop off after the final phone round. Everything below is on `main`; nothing is only on the laptop
except map-building scratch data (not needed). Read this, then `docs/backlog.md` "Next" and `docs/core-features.md`.

## 1. State of main (2026-10-22, after e86d428)

Released: **0.8.7** (GitHub). On main since then, not released yet (laptop installed **0.9.0** on the owner's phone as a
fresh install for testing – the owner tests it now):
- **Map loading:** one modal "Loading/Preparing the map for your area · N regions · about X MB" with % (smooth, never
  backwards), time left, Cancel = stop (ring removed for searches, points kept). Triggers: set point, app start in a new
  place, search, confirmed tap on the world map, route corridor (T5 on tour = still only while the app is open – see 3.3).
  No flash messages anywhere (`ui/NoFlash.kt`).
- **World overview** in the APK (Natural Earth base z0–5 + pre-rendered hill shade z0–7, 14.3 MB, `tools/build_overview.sh`,
  `tools/build_hillshade.py`); drawn under cells at all zooms, covered inside cells. **Release APKs don't have it yet** – see 3.6.
- **Routing data comes with the map** (C10): 1° rd5 parts (~10 MB instead of ~200 MB) via HTTP range from brouter.de
  (`routing/Rd5Parts.kt`, `data/Rd5PartStore.kt`, `data/RoutingDataLoader.kt`), loaded with every region / point / route
  corridor; the user never sees "routing data". Verified on the JVM (identical routes). **Not yet verified on the phone**.
- **Points editor** (C9): full sheet = slider → summary line → points (drag ≡ reorder, ✕ delete, tap row → map) → elevation
  profile; sheet follows the finger and springs; way types/surface in Route details (tap profile / badge).
- **Haptics:** only map holds vibrate (1× empty map/line/POI, 2× on a route point, at the hold); media-usage vibrations
  (Android drops short unlabelled ones as touch feedback when the system's touch feedback is off); own setting
  "Vibrate when planning". Points list silent.
- **Tour & plan backup** (C11): GPX to `Documents/OnPath` after every finished tour/saved plan/import (no permission
  prompt on Android 10+), restore offer after reinstall (folder picker once), recycle bin 30 days, Android backup rules,
  Export all (ZIP), Settings → Backup. DB schema 2 (uuid, pinned, deleted_at).
- **Map vs router quick fixes A** (`docs/map-style-notes.md` "Map vs router quick fixes"); private ways never green.
- **Cloud (TEST env live):** any area via Geofabrik extracts (`tools/cloud/osm_sources.py`, no Trentino pin, no daily limit,
  2.5 GB size guard), up to 4 parallel builds, user-waiting requests first, live ETA. Production not deployed (owner: later).

## 2. Final phone round (laptop agent, running when this was written)

Core e2e classes on the Pixel (CorePointsList, CoreDelete, CorePlan, CoreField, FreshInstall, CoreBackup), then a complete
wipe and 0.9.0 installed fresh for the owner's test. If its report is missing, the owner tells you the result.

## 3. Open tasks for the cloud session (owner-approved, in this order)

1. **Cell manifest records its sources** (`sources: [{id, date, url}]` from osm_sources) – debugging aid; app ignores unknown fields (check `CellManifest`).
2. **Cloud cleanup** (Worker cron daily): delete superseded cell versions older than 7 days (keep current + previous),
   delete format-1 cells (`cells/f1/…`, D1 rows), remove the unused `osm/trentino_alto_adige.osm.pbf` from the GCS source
   bucket (or document the gsutil command if the job's account can't delete).
3. **T5 in the background:** on tour, every ~300 m, request cells (+ routing parts) within ~5 km of the edge of installed
   detail from `RecordingService` (screen off / app in background); never switch the map store during a tour
   (`MapCells.holdUpdates`) – install after the tour / when back in Plan. Never disturb recording.
4. **Flaky test** `CellDownloadTest.requestPollDownloadInstall` (generation timing ~line 114) – fix the race properly.
5. **Next release (owner): junction vibration + lock-screen info.** Vibrate ~30–50 m before a turn on the followed route
   (e.g. left = 2 short, right = 3 short; merge cues in switchbacks; none when off route; setting "Vibrate at junctions",
   default on; media-usage vibration like `ui/Haptics.kt`). Lock-screen notification: time to go + arrival, distance to go,
   next junction ("Left in 120 m"); updates ≤ every 10 s; no button that ends a tour. Check BRouter turn instructions
   (voicehints) for junction detection; JVM tests with real routes (`tools/routing/jvm-test`).
6. **Approach 1 – "the router decides, the map shows it"** (~3 days): audit in `docs/design/map-vs-router-audit.md`
   (30 mismatches, BRouter itself is right). Evaluate BRouter's profiles per way tag set with its expression engine
   (`tools/routing/wayclass/EvalWay.java` shows the API: BExpressionMetaData, BExpressionContextWay, evaluate/getCostfactor)
   in the pipeline; store a class per profile group (hike / trekking+e-bike / touring / MTB: good / ok / forbidden) on
   overlay paths + streets (+ barrier nodes), schema bump `-s3`, style colours only by the class (fallback to tag rules for
   old cells); route-band sections above the user's T level dashed/greyed (runtime filter). Automatic consistency test:
   real routes never cross "forbidden", forbidden ways never routed. Owner reviews renders before main.
7. **World map in release APKs – DONE (laptop, 2026-10-22):** files (ov-v3: base z0–5 2.8 MB, hill shade z0–7 11.5 MB,
   SHA256SUMS) are assets of the GitHub **pre-release `overview-2026-10-22`** (not an app release; the in-app updater only
   reads releases/latest); repo variable `ONPATH_OVERVIEW_URL` points to it. The next release build includes the overview.
   To rebuild: `tools/build_overview.sh` + a new pre-release, update the variable.
8. **Release 0.9.0** once the owner's phone test is OK (version bump + release notes; e2e gate in CI).

## 4. Owner rules learned (keep)
- No self-dismissing messages; nothing may flash. Plain words – never "cells", "routing data", file paths.
- Core features: field bug → first a reproducing test, then the fix. Real-phone e2e passes are worth it (the Pixel found
  bugs the emulator didn't: locked screen, real network location, late first fix closing a menu).
- Vibration: only map holds, at the press. Points list silent.
- Keep the laptop usable: heavy jobs niced, ≤ 4 workers; no local emulator (phone or CI instead).
- Map colours: green = good for the current activity, else black; no blue ways; never green on a way the router forbids.
