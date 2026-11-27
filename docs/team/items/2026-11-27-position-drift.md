# Position wanders while the phone lies still (owner's Pixel 7, 2026-11-27 23:32, Navigate)

Read over USB from the Pixel (`events.log`, `onpath.db` tour 24, app installed 22:57): tour 24 recording since 22:59,
indoors in Hilden, phone on the table.

## What happened
- GPS indoors: no usable fix for 41–517 s at a time; the fixes that came had 26–49 m accuracy, each 18–49 m off the last.
- The motion sensor said still the whole time (`Standing still for 60 s (motion sensor): GPS every 10 s`), with short
  „Moving again (GPS)“ wakes that went back to still within a second.
- **The dot**: `MapScreen` feeds `MapController.updateLocation` every GPS fix of the app's tracker – no accuracy threshold,
  no still rule (only „Position auf der Route“ snaps it to the line when close). So it jumped 20–50 m with each fix. The
  still rule (`PointFilter.judge(still)`, 2026-11-27) only filtered the **track**, not the dot.
- **The track**: after each gap the fix was `continuous = false` → `judge` accepted it unconditionally as a **new track
  piece** – 7 points in 5 pieces in 30 min, the still rule skipped („a new track piece always starts“).
- RestWatch / RestGps (battery) work as meant: still by the sensor for 60 s → GPS every 10 s; they do not move the dot.
- The direction arrow: compass unsure indoors → grey, as designed; not part of the wander.

## Fix (branch `fix/position-drift` @ 26b9799cb, gate green, NOT on main – captain's go)
Test first with tour 24's real fixes (`PointFilterTest.afterAGapAFixNearTheLastPointWhileStillIsDrift`, red before), then:
1. `PointFilter.judge`: still by the sensor, a fix within twice its accuracy of the last point is drift **after a gap too**
   – no new piece.
2. `StillDot` (core, pure) + `StillDotFeed` (app): while the sensor says still (`RecState.sensorStill`, new) and the fix is
   within twice its accuracy of the last track point, the **dot stays on that point**. Farther shows at once (a real move
   is never hidden); no sensor word / no track point / paused → the fix as before. `StillDotTest`.
Like Google Maps / Komoot: the dot stands still, the accuracy circle stays (MapLibre draws it in Navigate).

## The owner's question: make „still“ shorter (1 minute or less)?
- The sensor itself already decides „still“ over a **10 s window** (`StillDetector.WINDOW_MS`, at least 6 s of readings) and
  „moving“ at once – so the dot / track rule above acts after ~10 s, not a minute.
- The **60 s** is `RestWatch.REST_AFTER_MS`: only when GPS slows to every 10 s (battery). Proposal: **30 s** with the
  sensor's word – a stop at a junction to look at the map is usually 10–30 s, and the sensor wakes GPS at the first step,
  so nothing is lost; below 30 s GPS would slow at every traffic light (a restart costs a few seconds of fixes). Not built
  – a separate small change with a `RestWatchTest` case if the owner wants it.

## Not done
- No release / main push; no tour touched on the phone (tour 24 still records).
- Morning: install the branch build on the Pixel only after the captain's go, then lie the phone still for 10 min and read
  `events.log` + tour points again.
