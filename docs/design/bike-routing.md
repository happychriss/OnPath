# Bike routing: profiles and why (2026-10-25/26)

Owner, after the Hilden field test: "the bike routing part is very important, above all for normal trekking bikes – we
must not miss good bike ways". This page holds the bike-profile decisions as shipped since 0.11.0, the reason for each
and the tests that guard them. Each rule is marked `OnPath` in the profile files (`app/src/main/assets/brouter/`);
change a rule here and there together.

## Decisions

| # | Decision | Why | Profiles |
| --- | --- | --- | --- |
| 1 | "Touring bike" = a normal touring bike: `touring.brf` (trekking + unpavedPenalty 2.5 + traffic) replaces the road-bike profile fastbike | fastbike is a road-bike profile: on 60 random routes around Hilden it rode 21 % bike ways and 43 % roads / busy roads (trekking: 50 % bike ways); busy roads on the Hilden random pairs 32 % → about 3 % | touring |
| 2 | E-bike routes like Touring (only power, speed and mass differ) | owner choice 2026-10-25 | touring |
| 3 | Traffic penalty on busy traffic classes 4+ only (+0.2 / +0.4 / +0.8); quiet roads free | BRouter's upstream table also charges quiet roads (class 1–2), which pushes routes off quiet side roads | touring |
| 4 | A busy road is not "perfect" just because a cycle route runs on it | a secondary with traffic class 5–6 on a local cycle route beat the parallel signed cycleway | touring, trekking |
| 5 | Rough way on a cycle route is not "perfect" (track grade 3–5, smoothness bad or worse, ground / dirt / sand / grass / mud / earth / rock) – "v_rough" | the other-region check found Touring on 11–13 km of rough gravel in the Alps where a signed cycle route ran over it; loose share there 10.4 → 5.6 % (owner confirmed 2026-10-26) | touring |
| 6 | A footway on a signed cycle route is ridden; another paved / firm footway without a bicycle tag only +1 (decision 11); sidewalks, crossings, pedestrian zones stay "pushing" | footways are normal parts of signed cycle routes (owner 2026-10-25, Hilden Itter path); the plan shows "… m pushing" for the rest | touring, trekking (mtb still pushing) |
| 7 | Ferry costs 1 km instead of 10 km; a ferry without bike access is forbidden | a 70 m canal ferry gave a 7 km detour; `FerryTest`: 56 of 208 routes used ferries that do not take bikes | touring, trekking, mtb |
| 8 | Off-route add 0.15 (was 0.05) – cycle routes slightly preferred | 0.20 sent a Meran route over 2.7 km more SAC T2 path (`SafetyTest`) | touring, trekking |
| 9 | Never against a one-way (×100) | owner rule 2026-10-20 (`OnewayTest`) | all bike |
| 10 | Touring time model ~21.7 km/h on asphalt, the same rider as Trekking | the old 120 W gave ~24 km/h, a road-bike pace | app (`time/PaceModel`) |
| 11 | A compacted surface is firm (counts like fine_gravel): a compacted bike path costs like a paved one (1.15, was 5.15 – Touring 11.15); only a footway **without** a bicycle tag gets the cheap +1, bicycle=no / dismount keep +4 | owner field report 2026-10-26: Hilden, Trekking bike, the shared foot / cycle path along the Itter (path, bicycle=designated, surface=compacted, sign DE:240) was left for 700 m of side streets even with plan points on it; "compacted" was treated like loose ground, so the path cost more than a primary road. The +4 guard: with compacted firm, a 592 m bicycle=no promenade in Meran became a shortcut (+1 was meant for untagged footways only) | touring, trekking (mtb, hike unchanged) |

Guard rails for every profile change: Meran `SafetyTest` / `OnewayTest` (no T3+, no via ferrata, no wrong-way metres),
`RandomPairsTest` (60 random pairs 2–8 km, seed 42, Düsseldorf / Hilden / Solingen: busy-road share), `FerryTest`,
`IslandTest`, `ItterPathTest`, `TouringProfileTest` (touring.brf stays a trekking copy with only the intended differences),
`BikeProfileCostTest` (way costs per tag set, unit test), and a check in
other regions (Alps, Black Forest, Bodensee, Netherlands, Ruhr, cities) for surprises such as rough gravel.

Open (owner, `docs/backlog.md`): long timetabled ferries (22 km IJsselmeer) only with a via point (today) or automatic.

## Measured before the tuning (tools/routing/jvm-test, 60 random routes 2–8 km, Düsseldorf / Hilden / Solingen, brouter.de rd5)
| Profile (activity) | bike ways* | quiet roads | roads | busy roads | footway, no bike tag | track / path |
| --- | --- | --- | --- | --- | --- | --- |
| trekking (Trekking bike, E-bike) | 49.5 % | 22.9 % | 14.2 % | 3.3 % | 0.3 % | 7.1 % |
| fastbike-verylowtraffic (Touring bike) | 21.0 % | 29.7 % | 34.0 % | 8.7 % | 0.2 % | 3.5 % |
| mtb (MTB) | 53.7 % | 12.3 % | 1.4 % | 0.1 % | 7.5 % | 21.3 % |
\* cycleway, bicycle=designated or bicycle=yes. (Activity mapping as it was then; since 0.11.0 Touring bike and E-bike
use touring.brf.)

Trekking with footways (not sidewalks) at +1 instead of +4: only 9 of 60 routes change, footway share 0.3 → 0.7 %.
So trekking was generally fine; the footway rule matters locally (Hilden, Itter path). "Touring bike" was the weak
spot: fastbike is a road-bike profile (path / footway without surface 100, cycleway 1.3 > tertiary 1.0).

## Why Hilden lost
A footway without a bicycle tag cost ≈ 5.05 in trekking (pushing speed, upstream decision
https://github.com/abrensch/brouter/issues/225) – more than a primary road (3.05). Also on a cycle route relation
(the +4 was added on top). The false "no way" message was a separate app bug (fixed 283bcaf, Planner spurs).

## What other routers do (research, 2026-10-25)
- Our trekking / lookups.dat = upstream master (incl. unpavedPenalty, Aug 2026) plus OnPath edits; rd5 data on
  brouter.de is weekly and current.
- OsmAnd: footways allowed by default (priority 0.6, ×1.35 paved, sidewalk 0.3), designated ×1.8, cycle routes ×1.2.
- GraphHopper / OSRM: footways as pushing sections. bikerouter.de Poutnik profiles: full bike access on cycle routes,
  footway +3.5, off-route penalty 0.2.
- Law (DE): Zeichen 239 footway = no cycling unless "Radverkehr frei"; 240 / 241 = designated. OSM default DE footway
  = dismount, path = yes. A cheaper footway must therefore be shown in the UI, not hidden.

## How a profile change is checked
**Tool** `tools/routing/jvm-test/GpxCompare.kt` + `gpx_compare_report.py` (header of each; run.sh `RAW_TAP=1`,
`ASSETS_DIR=` for A/B). It plans a reference GPX line (a planned route or a recorded ride) in ~40 km pieces through the
app's own path (Planner → BRouterEngine, shipped profiles, via correction) twice: **sparse** = only start and end of a
piece (the router's own choice), **medium** = a via every ~5 km taken from the reference. The reference is traced with
dense vias (every 150 m) and a permissive profile (`trace.brf`) to learn its BRouter way tags. Overlap = share of the
reference within 20 m of our line. Deviations ≥ 300 m get a reason class, a GPX with both lines and a PNG. Each run has
its own work dir and profile copy. Reference data stays outside the repo; only aggregate numbers go into it.

Variants were tried one at a time, then combined: footway rule +1 / +0.5, off-route add 0.10 / 0.15 / 0.20, ferry
1 / 2 / 4 km and a lower ferry factor, traffic penalty (upstream table, and busy classes only at three strengths), busy
road on a cycle route not "perfect". Findings: unpavedPenalty 2.5 barely changes routes in mostly paved flat country;
off-route 0.20 broke the Meran T2 guard rail – 0.15 is the limit; a ferry factor below 5.67 also routes a 22 km
timetabled lake ferry on its own (left to a via point).

Also found on the way: 22 % of planned routes had no per-point way attributes in the app (BRouter's 0 m first run;
fixed on main 802e7f9, `BRouterWayStatsTest`).

## Follow-ups (desktop, 2026-10-26)
- *Ferries that do not take bikes.* A ferry with bicycle=no cost a bike only a little more (+4 per metre, +15 on a cycle
  route; mtb.brf counts any way on a cycle route as bike access), so routes used it. touring / trekking / mtb.brf now
  forbid a ferry with bicycle=no|private, or without a bicycle tag and vehicle=no|private, or without both and
  access=no|private (`ferry_nobike`; bicycle=dismount stays fine); hiking-mountain.brf forbids a ferry without foot
  access. Test: 228 short ferries (≤ 1.5 km) with bicycle=no or foot=no in the five test segments (OSM), routed from 250 m
  beyond each end (`tools/routing/jvm-test/FerryTest.kt`): routes over a forbidden ferry Touring / Trekking 56 → 0 of
  208, MTB 67 → 0 of 194, Hike 6 → 0 of 225; where the ferry is the only way across (Venice lagoon, small islands) bikes
  now get no route. Way classes of normal ways unchanged (no cell schema bump), WayClass selftest pins the ferry cases;
  guard rails unchanged.
- *Köln Dom → Neumarkt gave "no route" for every activity* (not a bike-profile issue): the point in the middle of the
  cathedral snapped onto a short footway inside it that connects to nothing BRouter knows (an "island"). BRouter's own
  island retry snapped the start to the same way five times; as a destination it gave up at once. `BRouterEngine` now
  runs BRouter again with that way on its island list (snapping only, up to 3 ways); the point lands on the Domplatte
  40 m away and every activity gets a route (bikes 1.4 km, ~300 m of it footway, shown as pushing where bikes have no
  access). `tools/routing/jvm-test/IslandTest.kt`, `IslandPointTest`.
- The map's router classes (`cf`, now touring.brf) follow with cell schema 5 (`docs/design/map-hosting.md` §7c).

- *Compacted shared paths (Hilden Itter, owner 2026-10-26, decision 11).* The path the owner rides
  (OSM ways 26650741 / 891696635: highway=path, bicycle=designated, foot=designated, surface=compacted) cost 5.15 per
  metre in trekking.brf (11.15 in touring.brf): no tracktype, and `isunpaved` listed only fine_gravel / cobblestone as
  firm, so "compacted" fell into the "not probably good" branch (1 + 4 × unpavedPenalty) – the same path without a
  surface tag cost 1.15. Residential streets (1.25) won, the leg 3 → 4 went 700 m through side streets (406 m of them
  streets) instead of 460 m on the path. Now compacted is firm in touring / trekking.brf. Access is unchanged: the plan
  still shows "… m pushing" wherever bikes have no access (the field plan's 160 m was a footway without a bicycle tag
  at the destination, not on the Itter path; it stays). Checked: Itter leg 3 → 4 696 → 459 m, 0 m of streets, for
  Trekking / Touring / E-bike (`tools/routing/jvm-test/ItterPathTest.kt`); 60 random pairs (Hilden / Düsseldorf /
  Solingen): 8–10 of 60 routes per activity change, busy roads unchanged (Touring 3.2 %, Trekking 7.3 %), footway
  without bicycle tag Touring 0.6 → 0.7 %, Trekking 0.8 → 0.8 %, bike ways Trekking 42.1 → 42.5 %; Meran
  `SafetyTest` identical, `OnewayTest` wrong-way metres unchanged; `FerryTest` 0 forbidden ferries; `IslandTest` OK.
  In Meran, routes to the Rennweg point now push along a 475–592 m bicycle=no promenade instead of carrying the bike
  up 148 m of steps (both shown as pushing). The map's router classes follow with the next cell rebuild (profile hash
  changes; a look-only cell schema bump like schema 5 is needed to refresh published cells).

## Map drawing: paths interrupted at streets (separate problem)
Sidewalks and crossings (`footway=sidewalk|crossing`) are not drawn (k="x" in format 2), so ~18 % of path ends in a
Hilden sample (61 of 344) stop at an invisible piece before the street. Drawing all crossings is no fix (301 crossing
ends would then dangle at hidden sidewalks). Proposal: in `extract_overlays.py` draw only the hidden pieces that link
a drawn path end to the nearest drawn way (short chain through sidewalk / crossing, ≤ ~30 m) – needs a map rebuild.
