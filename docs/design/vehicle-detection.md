# Bus / car / lift during a recording – design (owner; built: `rides/`)

Problem (owner): the hike ends, the recording is not ended, the user takes a bus or car (or loads the bike into a car)
– the ride is recorded as part of the tour. Detectable by an unusually high, sustained speed.

## Decided (owner)
- **Nothing happens during the ride** (owner's variant): the recording goes on exactly as always; no question, no
  notification. OnPath only marks segments as "ridden" (bus/car) or "lift" in the background.
- **Off-route warning and junction vibration stay on** during a ride (owner) – no muting.
- **At "End tour"** a summary with a choice replaces the short question when ridden segments were found (below).

## Detection (recording service; can also run over the whole stored track at the end)
- Vehicle: sustained average speed above the limit – hike > 12 km/h for ≥ 60 s and ≥ 500 m; bike / MTB / e-bike
  > 50 km/h for ≥ 60 s and ≥ 1 km; GPS gaps count (distance / time since the last fix: bus without GPS, tunnels).
- Lift: > 2 m/s vertical on a nearly straight line.
- Proposed end: the last point where the user still moved on foot / by bike before the ride (waiting at the stop cut).
- No single outlier counts (GPS jumps, fast MTB descent, S-pedelec 45 km/h, running).

## End-of-tour summary (proposal)
Timeline (walk / ride / lift with times, km, ascent, Ø speed) + small map (walked blue, ridden grey dashed, lift
purple), then one choice:
1. **Save only until 16:42** – ride and after it dropped (preselected when the ride is at the end).
2. **Save all, rides not counted** – grey on the map, not in km / time / pace (preselected when hiking goes on after
   the ride, and for lifts).
3. **Split into two tours** – before / after the ride.
4. **Save as recorded.**
Raw points are kept; "Edit rides" in the tour shows the same sheet again later.

## Also decided (owner)
- The four choices with the preselection above; raw points are always kept.
- Limits 12 km/h (foot) / 50 km/h (bike), ≥ 1 min (adjust after field tests).

## Tests (core feature C4 – never lose a track)
JVM: real tours with an inserted bus ride, lift, GPS gap, fast descent – detection, proposed end, no false alarms.
Emulator: mock GPS at 50 km/h, then End tour → summary → "Save only until …" trims correctly, raw points kept.

## As built
- `rides/RideDetector` runs over the stored points when End tour is tapped (and, without a choice, when the recording
  service ends a tour – rides are then only marked "as recorded"). Foot > 12 km/h / bike > 50 km/h sustained ≥ 60 s and
  ≥ 500 m / 1 km (GPS gaps count; bus stops ≤ 3 min / 250 m join); guards: lone GPS jumps removed, start → end ≥ half the
  minimum and ≥ 30 % of the path, on foot the fastest 30 s > 20 km/h (not a run), on a bike not downhill ≤ −3 % unless
  > 80 km/h. Lift: **> 1 m/s** vertical (not 2: the owner's Taser gondola, 1500 → 907 m in 356 s without a fix, is 1.67 m/s),
  ≥ 100 m, ≥ 90 % straight, ≥ 20 % steep; downhill on a bike > 2 m/s, ≥ 97 % straight, ≥ 35 % steep. Ride start = the
  last point before it outside 40 m of where it began (the waiting is cut off).
- `tours.rides` (DB v3, JSON): rides as point indices, the choice, the stats as recorded, end time. Points never change.
- Summary: `RideSummaryDialog` (`ui/RideSummary.kt`) (Cancel · Delete · Save stay). Choice stored on the tour before the stop; the service
  applies it (`RideApply.finish`). "Split" makes the tours after rides (parts ≥ 100 m or ≥ 5 min) as new tours with a
  copy of their points; the original keeps every point. My routes → ⋮ → Edit rides (no split there).
- Backup GPX: a ride not counted = own `<trkseg>` with `<extensions><onpath:ride kind=…/>`; points dropped by
  "Save only until" / "Split" are not in the track but in `<onpath:dropped>` beside the record – restore gets all.
- Not done: the live in-tour estimate (TripPace) still sees a ride while it happens; there is no learning across tours (pace per tour only, owner), so nothing carries over to later tours. Finished-tour times use only the kept parts (AppViewModel.tourTimes).
