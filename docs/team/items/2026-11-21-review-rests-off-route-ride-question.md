# Rests from the reviews of 2026-11-21 (off-route sentence, far start, ride question)

The findings that were fixed are on `main` (eef668763, fd0ca4c76). These were seen and left open on purpose.

## Off route (core feature: a failing test first)
- **Silent jump to a later leg.** On a loop with legs about 100 m apart the detector stays „on route“ and jumps from about
  507 m to about 1603 m along (simulated in Kotlin while fixing `OffRouteSay`). No sentence is said, and the part between,
  with any stops on it, could count as skipped. No test covers it.
- **A lagging GPS bearing within 30° of the chord still passes** (`OffRouteSay.AGREE_DEG`), for example 48° against a chord
  of 18° on a bike corner. The wrong-side risk is smaller than before, not gone.
- **Unmeasured:** how often a phone reports a bearing at walking speed. If rarely, the side sentence will be rare. The E2E
  accepts the plain or the side sentence, so it does not prove the side was spoken. Read it from a real walk.

## Far start
- `leadFailedOk` leaves `lead` set, so a plan edit after a failed search searches again. Possibly wanted (the edit may make
  the start reachable); decide before changing it.

## Arrival with a stop the route cannot reach (on main cb49cbc22, NOT in 0.18.7, no device or field run with such a stop)
- **Poor GPS along the whole stretch at the place:** the stop can stay open and no arrival comes (simulated: counted in 123 of
  240 runs at 35 m accuracy, 80 of 240 at 50 m). That is the safe side and the old behaviour.
- **With GPS noise a rider can count from 1–2 × the radius short of the place** (worst 45 m; 2–3 of 1,128 runs at 8–15 m, 12 of
  1,128 at 50 m). No run arrived from farther.
- **A rider who leaves the line past the place without counting it**, on a new line starting at its nearest point, keeps it open.
- **`NextStop.kt` does not know `passedBy`:** it filters by position along the line, so on a line like the review's out-and-back a
  passed stop could still be listed as ahead.
- **The owner has not said** that a pinned or named stop 25–150 m beside the line (no red line) stops blocking arrival once passed.
- The rule for a taken line's own place is covered by synthetic geometry only (the 8 real re-routed lines pass the original place).

## Ride question: the owner's to decide (nothing changed)
- **Bike tours:** ride detection also runs there, and „Unterwegs bist du gefahren“ / „Ohne Fahrt“ read ambiguously, most of
  all with several rides. The button words are his, so ask before rewording.
- **Walk after a ride at the end:** „Ohne Fahrt“ uses „only until“, which also drops up to 300 m of walking after the ride
  from km, time, line and GPX. The points stay stored and „Fahrten bearbeiten“ brings them back.
- Known small rest: `OverviewMaker`'s in-run `checked` memo compares the sig only. The overview is still right when opened.
