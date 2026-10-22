# Bus / car / lift during a recording – design (owner 2026-10-22, not built yet)

Problem (owner): the hike ends, the recording is not ended, the user takes a bus or car (or loads the bike into a car)
– the ride is recorded as part of the tour. Detectable by an unusually high, sustained speed.

## Decided so far
- **Nothing happens during the ride** (owner's variant): the recording goes on exactly as always; no question, no
  notification. OnPath only marks segments as "ridden" (bus/car) or "lift" in the background.
- **Off-route warning and junction vibration stay on** during a ride (owner 2026-10-22) – no muting.
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

## Also decided (owner "Ja", 2026-10-22)
- The four choices with the preselection above; raw points are always kept.
- Limits 12 km/h (foot) / 50 km/h (bike), ≥ 1 min (adjust after field tests).

## Tests (core feature C4 – never lose a track)
JVM: real tours with an inserted bus ride, lift, GPS gap, fast descent – detection, proposed end, no false alarms.
Emulator: mock GPS at 50 km/h, then End tour → summary → "Save only until …" trims correctly, raw points kept.
