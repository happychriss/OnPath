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

## Ride question: the owner's to decide (nothing changed)
- **Bike tours:** ride detection also runs there, and „Unterwegs bist du gefahren“ / „Ohne Fahrt“ read ambiguously, most of
  all with several rides. The button words are his, so ask before rewording.
- **Walk after a ride at the end:** „Ohne Fahrt“ uses „only until“, which also drops up to 300 m of walking after the ride
  from km, time, line and GPX. The points stay stored and „Fahrten bearbeiten“ brings them back.
- Known small rest: `OverviewMaker`'s in-run `checked` memo compares the sig only. The overview is still right when opened.
