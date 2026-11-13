# Tour suggestions (design note, 2026-11-13)

Owner 2026-11-13: "ein intelligenter Routenvorschlag – eine schöne, waldreiche Rundroute zu Fuß um Hilden, ganz
entspannt, ungefähr 2 Stunden, ich gehe eher langsam" – or simply sliders (length, effort, forest). Routes made to the
user's taste, not picked from other people's tours; clever like one would plan them oneself (not along the road, real
loops); a start point, and nearby entry points when there is nothing good at the door. Must run on the phone and in
the web planner. Earlier wishes: GitHub discussions 4 (hiking loops nearby) and 12 (bike loops from the doorstep),
backlog "Tour suggestions". State: **a POC on the computer** (`tools/suggest-poc/`, its README has the measured facts
and the tuning log); nothing in the app yet.

## Three parts

| Part | What | AI? | Network? |
| --- | --- | --- | --- |
| 1 Understand the wish | sentence or sliders → start, target time, pace, difficulty, effort, taste weights | only for free text | only for free text |
| 2 Build candidates | 20–70 possible loops from the start | no | no |
| 3 Score and choose | grade every loop, show the best few that differ | no | no |

The quality comes from 2 and 3. A language model does not know the ways and cannot draw a route; it can translate a
wish into slider values, and later help judge candidates.

## What is already there

- BRouter's rd5 carries per way piece `estimated_forest_class`, `estimated_noise_class`, `estimated_town_class`,
  `estimated_river_class` (1–6; measured filled around Hilden), plus highway, surface, hiking-route membership.
  `hiking-mountain.brf` has the switches `consider_forest / noise / river / town / elevation` (off in the app today).
- Offline POIs with car parks, bus stops and stations – the entry points.
- One routing core for app and web (TeaVM); time model and difficulty rating exist.

## Building loops

| Method | Idea | Verdict |
| --- | --- | --- |
| A BRouter's own round trip mode | points on a circle | geometric only; comparison at most (not tried) |
| B Fan + taste profile | 8–12 directions × a few shapes, 2–3 via points, routed with the taste switches on | the frame (POC) |
| C Targets instead of geometry | via points pulled to where it is pleasant | POC: a second pass uses the pleasant spots the first pass walked over; later also POIs, signed trails |
| D Way back with a penalty | earlier legs are weighted nogo lines for later legs | works (POC): shared way 98 % → 33 % at weight 20 |

A shape that matters: **stem and loop** ("keule") – walk to the forest, loop there, walk back. From a town centre this
is what a person does; a tidy circle through the streets is not.

## Scoring

Per route, from the way tags: time fit, ascent fit (effort), near forest, quiet, water, signed ways, natural ground,
street share (big streets count double), doubled way, stalk, crossings, shape. Each part 0..1, weighted mean; the taste
parts are multiplied by the wish's sliders. Selection: best first, skip routes too similar to a chosen one, skip
routes whose time is off. A random seed gives other loops on "new suggestions".

## Entry points (not built)

1. Loops from the given start. 2. Best score weak (e.g. half of it streets) → car parks / stops within a radius that
lie at pleasant ground → loops from the best 3–5 → "from car park X, 4 km away". 3. No downloaded map there → no
suggestion and a plain notice, never a bad route.

## Input

| Variant | Offline | Verdict |
| --- | --- | --- |
| Sliders / chips: duration, pace, difficulty (T level + effort), taste | yes | step 1 |
| Sentence, keyword rules on the device | yes | brittle |
| Sentence through an online model → slider values the user sees and can correct | no | step 3, labelled "needs internet"; only the sentence leaves the device, the place name is resolved by the app's own search; needs a small server, a privacy text (pull request) |
| Model on the phone | yes | not now (hundreds of MB or Google services) |

## Architecture (target)

```
wish (sliders) ─┐
wish (sentence, online, later) ─┴─► TourWish
                                      │        ◄── entry points (offline POIs)
                                      ▼
                           candidates (fan, stem-and-loop, way back penalised – through RoutingEngine)
                                      ▼
                           numbers + score (way tags)
                                      ▼
                           selection (best, different)
                                      ▼
              suggestion cards ──► a tap opens the loop as an ordinary plan (point list)
```

Everything between `TourWish` and the selection is one new package in the shared core (no Android types), so the web
planner gets it through TeaVM. A suggestion ends as a plain point list – edit, save, navigate, share unchanged.
**The POC's router and the app's.** The POC's route service talks to BRouter itself – the same jars, profiles and rd5
as the app – but not through the app's wrapper (`BRouterEngine` / `Planner`: via correction, island retry, leg cache,
the there-and-back trim at a join). The real feature goes into the shared core on top of `RoutingEngine`. What the
route service uses that `RoutingContract` does not offer today – the contract change to plan:

| Needed | POC | Today in `RoutingContract` |
| --- | --- | --- |
| Weighted nogo lines per request (the way back avoids the way out) | `nogo=<weight>;<line>` → `OsmNogoPolygon.nogoWeight` | no nogos at all |
| Profile switches per request (`consider_forest`, `consider_noise`, `hiking_routes_preference`, `mainroad_penalty`, `avoid_unsafe` …) | `kv=` lines → `RoutingContext.keyValues` | fixed per activity (+ T level) |
| Way tags per piece of the line (forest / noise / river / town class, highway, surface, sidewalk, `route_*` flags) | BRouter's aggregated messages with `processUnusedTags` | only what `RouteSummary` / `RawTap` (tests) read |
| One new profile term: cost along a main road (`mainroad_penalty`, hiking) | `service/make_profiles.py` patches a copy of `hiking-mountain.brf` | not in the app's profile (default 0 = unchanged routes) |

## Open risks

- Phone speed: a request is 400–600 short legs (4–9 s on the desktop with 4 threads) – fewer candidates, results one
  by one, measure on the Pixel.
- The forest class means "green nearby", not "in the forest"; street detection from tags is rough.
- Bike loops of 40–80 km cost far more routing – hike first.
- Whether brouter.de keeps filling the estimated classes in every tile (check when map areas are built).

## The POC

`python3 tools/suggest-poc/serve.py` → http://localhost:8790 (form, map, cards, "Das ist die beste" + reason →
`~/onpath-suggest-poc/feedback.jsonl`). `python3 tools/suggest-poc/run.py … --png` prints the table and writes
pictures – the way an AI session judges routes and tunes `weights.json`. Next: the owner's verdicts on real requests,
then entry points, then a sketch of the app screen.

## Round 2 (2026-11-13, after the owner's first look)

Owner: cycling too; not long along a main road – dodge to side streets; "why not the Elbsee?"; known tourist ways
(Meran: Tappeinerweg, Waalwege) must count – they are in our map data; few controls (hoch / mittel / niedrig); no
endless trial and error.

- **Destinations first (method C built).** Before the fan the engine collects what is in reach – named lakes, known
  signed routes (ranked by network: international / national 1.0, regional 0.85, local 0.6), viewpoints – and builds
  loops *through* them: round a lake or a circular signed route ("Runde"), or a stem-and-loop pulled onto the
  destination. The card names what the loop uses ("2,1 km Algunder Waalweg · 1,4 km am Menzelsee").
  **Data:** the rank of a signed route is in the routing data (way flags `route_hiking_iwn/nwn/rwn/lwn`,
  `route_bicycle_icn/ncn/rcn/lcn`) – used for the score, offline. Names and lake shapes come in the POC from one
  Overpass query per area (cached); on the phone the same is in the map area's files: `overlays.pmtiles` layer
  `routes` (`kind`, `name`, `ref`, `net`; `docs/map-pipeline.md` "Route bands"), `base.pmtiles` `water` +
  `water_name`, the POI file for viewpoints. No tile package for Hilden is on this computer, hence the stand-in.
  The Elbsee answer: 3 km from Hilden centre, so there, along the shore and back is ≥ 9–10 km – out of reach of the
  7 km wish, and the first generator had no notion of a destination. With 3 h it is now offered.
- **Main roads, two places.** Routing: `mainroad_penalty` (hiking) makes walking on a primary / secondary / tertiary
  road or on a noisy pavement beside one clearly dearer than a side street. Scoring: the **longest continuous
  stretch** along a main road (hike 300 m, bike 1000 m free; zero points at 2.5 ×). Way kinds: "an Hauptstraße" = the
  road or a pavement / cycle path beside it (by noise class); quiet street; way near forest; other way. A lit park
  path is no longer "street".
- **Cycling.** Bike (`touring.brf`), Gravel (`trekking.brf`), MTB (`mtb.brf`) with own pace words, thresholds and
  weights (`weights.json` `activities`). Long loops (≥ 50 km) use few candidates with six short legs – routing time
  grows with the square of a leg's length.
- **Few controls.** Start, activity, duration, pace (gemütlich / normal / zügig), effort (leicht / mittel /
  sportlich; T level only for hiking), three lines niedrig / mittel / hoch – **Natur, Ruhe, Bekannte Wege** – and
  "am Wasser entlang". The sliders are folded away for tuning.
- **A fixed yardstick instead of trial and error:** `bench.py` – nine fixed wishes, one sheet, pass / fail on plain
  expectations, compared with the sheet before. `bench.py --refs` scores known circular routes with our scorer next
  to our own best – where a known route scores clearly lower, a weight is wrong.

## Round 3 (2026-11-13 evening): our own map material, and variant B – search in the way network

Owner: "macht es nicht mehr Sinn, das Kartenmaterial zu benutzen, das wir schon haben?" and "bau einfach eine zweite
Variante … was echt Cooles, was es noch nicht gibt; ob Handy oder Server sehen wir dann".

### Data source = the app's map areas (checked on the real cells of Hilden and Meran)

The POC now downloads the z10 cells exactly as the app does (`cells.py`) and reads them (`pmt.py`). What is in them:

| Need | Where | State |
| --- | --- | --- |
| Known routes with name and rank | `overlays.pmtiles` `routes`: `kind` hike / bike, `name`, `ref`, `net` (4 international … 2 regional) | there for net ≥ 2 (neanderland STEIG, X30, X19, EuroVelo, Euroga …); hiking net 1 only as a merged band without name; bike local routes have names |
| Local circular walks, promenades, Waalwege | `overlays.pmtiles` `trailnames` (the map's trail labels: `name`, `tier` 1–3) | there: "Elbsee Rundweg A7", "Rundweg A2 Ohligser Heide", "Tappeiner-Promenade", "Algunder Waalweg", "Maiser Waalweg". These are label lines, not whole routes – enough to name and to attract, **not** enough to walk a circular route as it is |
| Lakes with name | `base.pmtiles` `water` (polygons) + `water_name` (points, z14) | there (Elbsee, Menzelsee, Unterbacher See …); a lake across a cell border is joined by name |
| Viewpoints | `pois.geojson` kind `viewpoint` | there |
| Forest / parks as areas | `base.pmtiles` `landcover`, `park` | there, not used yet (the routing data's forest class does the job per way) |
| **Missing for this feature** | a route's identity and order (relation id, roundtrip yes / no) | not in the cells – a known circular route cannot be offered "as it is" from today's files; would be one more small layer or file per cell (route id, name, rank, roundtrip, ordered line) |

So nothing online is needed any more; Overpass stays only as an explicit fallback. The implausible lake names of
round 2 came from the Overpass stand-in.

### Variant B – one graph, one search, thousands of loops

Variant A asks the router for every leg of every candidate (72 candidates ≈ 500 routings). Variant B asks **once**
for the way network around the start – read directly from BRouter's node cache, with the profile's verdict per link
(`POST /graph`; Hilden, 4 km radius: 21 900 nodes, 28 700 links, 0.16 s) – and searches loops inside it:

1. every link gets an unpleasantness per metre for this wish (near forest, quiet, water / lake shore, signed or
   known route by rank, natural ground; street and main road cost extra; the profile forbids what the T level forbids);
2. one cheapest-way search from the start; anchors = per direction sector and distance band the most pleasantly
   reached node, plus nodes on the destinations in reach;
3. loops start → A → start and start → A → B → start; every later part pays a penalty on ways already walked;
4. a lake shore or circular route that closes in the network is walked as a ring;
5. the same score sheet as A; each shown loop is then routed once through the real router as a check.

This is the "prize-collecting tour with a length budget" idea in its simplest honest form; local improvement
(swap a piece for a nicer detour) is not built.

**Contract for the real build:** besides the three additions of round 2, variant B needs one more thing from the
routing layer – *the way network around a point with the profile's cost per link* (BRouter has it in memory during
every routing; today no API hands it out). With it the loop search is plain Kotlin on top – shared by app and web.

### Phone or server (measured on this desktop, Python – so the search time is an upper bound, not an estimate of Kotlin)

| Step | Hilden 2 h (7 km) | Hilden 3 h (10.5 km) | Düsseldorf 2 h (8.4 km) |
| --- | --- | --- | --- |
| way network (BRouter, Java) | 0.2–0.3 s, 13 800 nodes | – | 0.3 s, 38 200 nodes |
| loop search (Python, ~120 searches) | ~2 s | ~4 s | ~3.5 s |
| whole request B | 2.0–2.7 s | 4.4–6.3 s | 4.7 s |
| whole request A | 5.2 s | – | 9 s |

The network of a 2–3 h walk is a few MB in memory; the searches are ordinary shortest-path runs. In Kotlin on a phone
this should be seconds, not tens of seconds – **an estimate, not measured**. A server is not needed for the search;
it would only be needed for an online free-text wish. Bike loops (15 km radius, several 100 000 links) are the open
case: variant B is hiking-only so far.

## Round 4 (2026-11-13) – known routes first, bike in the network search, surface

- Owner: official hiking / cycling / European routes must be included and **strongly preferred**, and visible on the
  map; surface must be asked per activity; bike needs the same quality as hike. Trigger: at Zons the Rhine cycle path
  (EV15, 0.5 km from the start) was known and ignored.
- The network search (variant B) now serves all activities. Bikes get a coarser network (no minor ways unless signed).
  A signed route's ways are cheap in the search in proportion to „Bekannte Wege“ × rank, and a construction step builds
  loops *along* a known route (both directions) – so „Rheinradweg nach Norden, zurück anders“ exists by construction.
- Surface: one form line per activity → pleasure value per surface class (asphalt / firm / natural); from the routing
  data's `surface` (71 % filled by length near Zons), `tracktype` (16 %), else the kind of way.
- Phone / server: a 3 h bike wish = a network of ~200,000 nodes (57 MB as text here), ~30 searches. Measured only in
  Python on the desktop (10 s); a phone figure is not measured.
- Contract additions for the real feature (beyond round 3's list): the network export needs tag filters (minor ways
  off, signed ways kept) and a route's identity / order in the map data (still missing: pieces are matched by name).
- Open: the planner's own map picture in the POC page (not built), main-road cost for bikes, tuning of the surface
  values, the Elbsee case lost in the bench after the water fix.

## Round 5 (2026-11-13) – "along a main road" is a weight, loops made for a lake

- **Main road = a weight 0..1 per piece of way, per activity** (`classes.main`): road class, calm (30 km/h), cycle
  track / lane, bridge, a path beside a loud road. One rule feeds the network cost, the profile addition, the
  longest-stretch score and the card. Found on the owner's Zons case: the "1.4 km main road" of the Rhine ride was a
  30 km/h village street on the signed route.
- **A known route counts on a road only where the routing data flags the road itself** – the map's route line
  alone (30 m) also catches the carriageway next to the cycle path.
- **Score parts must tell loops apart:** after `water` (round 4) the `signed` part had the same fault for bikes
  (1.0 for all). Rule for every new part: look at its spread over one run's candidates.
- **A theme needs its own construction and a reserved slot:** with "am Wasser entlang" the search builds loops for
  the lakes in reach with a *direct* way there (the pleasant-way search never arrives across a town) and shows one
  even when loops elsewhere score higher.
- **For the real feature:** rivers as named destinations (the map has `waterway` with names), ferries as a flagged
  piece of a loop (hours, price), and the there-and-back stem as a legitimate shape for a destination across town.
- Numbers and what is still weak: `tools/suggest-poc/README.md` "Round 5".

## Stage 2 (2026-11-13) – AI region knowledge per map area, with / without

Built as an experiment behind a switch (default off): `region_ai.py` (one call per map area, the AI picks by number from
a closed list of our own named objects – no name matching), `regionbonus.py` (reads the files, never calls the AI),
score part `ai` and extra destinations in variant B, card line "KI-Tipp: Name (km)", compare view on the page and in
`run.py --compare-ai`. Details, formula and the bench off / on: `tools/suggest-poc/README.md` "Stage 2".

- **Cost:** 7–12 ct per map area with the middle model through the command-line tool (its own overhead included);
  seven areas made so far. Once per area, not per user or request; nothing leaves the phone.
- **What it adds beyond the map:** a ranking of areas and sights (Urdenbacher Kämpe, Zonser Grind, Knechtsteden,
  Ohligser Heide, Neandertal); the signed routes it picks are already rewarded by rank.
- **What the map lacks (the AI's extras):** ferries (Zons–Urdenbach …), monasteries and old towns, city parks and
  promenades, cable cars – not in our cell files as destinations today.
- **Result so far:** no change in about half of the bench cases; on bikes it pulls loops to the Rhine and into named
  nature areas – one case clearly better, the others a different defensible choice. Not proven to be worth it yet;
  the owner's own comparison decides.
- **For the real feature:** the file would be made by the map server when it builds a cell and shipped with the cell;
  the AI's free sentence must not be shown as fact (it was wrong for the Elbsee).

## Owner's taste rules (2026-11-13) – round 6

The owner tried the page (bike 2 h near Zons and Köln, hikes around Hilden) and said what a good tour is for him. These
are rules of the product, not tuning; the numbers behind them are in `weights.json` (`score._taste_rules`).

| Rule (his sense) | In the POC |
| --- | --- |
| **The loop meets its own way at most once.** "Eine 8 oder ein Wollknäuel, um auf die Kilometerzahl zu kommen … mehrfach kreuzen ist keine Wanderung." | `suggest.self_crossings`: a point reached a second time (crossing, an eight's middle, a stretch walked twice, a dead-end spur) is one meeting; meetings close together along the way are one (the same bottleneck – a ferry and its landing). 0 or 1 is fine (score part `selfcross`, 1.0 / 0.85), 2 or more is **not offered** (`select.max_self_cross`) and variant B drops such a loop when it is built. The old `shape` part (compactness) is gone: a long narrow loop along both banks of a river is a good ride. Card: "kreuzt sich 1×". |
| **Along a road is always bad – signed route or not.** "Auch wenn das als Wanderweg / offizieller Radweg ausgezeichnet ist, das macht keinen Spaß … vor allem wenn ich naturnah ankreuze." | "Beside a road" is part of the main-road weight (`suggest.main_weight`): any separate way (cycle path, shared path, footway, track) counts by the routing data's noise class (4 / 5 / 6 → 0.35 / 0.7 / 1.0). Such a piece (a) costs in the network search and in the profile copies, (b) gets **no signed-route discount** and does not count as "markiert" in the score, where a followed route runs beside a road a quiet way nearby wins (`netsearch.follow.road_cost`), (c) is punished by share (`roadside`) and by the longest stretch (`mainroad`), both scaled by the Natur level (`score.natur_scaled`, `main_road_natur`). |
| **Not through houses** ("geht durch viel zu viel Wohngebiet – schlecht"). | Score part `town` = share on streets and on town ways that are not green; costs in the search (`netsearch.pen_town`), both x the Natur level. |
| **Large connected nature is a different feeling than a green strip.** | `nature.py`: forest / heath / wetland (`landcover`) and protected nature (`park`: Naturschutzgebiet …) of our own map areas drawn into a 45 m grid per area; the measure is the share of nature within 300 m and 900 m around a point – deep inside a big area ~1, along its edge ~0.5, a green strip 0.1–0.3. Score part `deep` (x Natur), a pleasure term in the search, card: "2,4 km im Naturschutzgebiet Ohligser Heide". Size and depth come from one number; polygons are not joined across tile borders. |
| **A short way out and back is allowed** (500–700 m on foot) "– das erhöht die Möglichkeiten für einen schönen Weg". | `score.free_stem_m` (hike 700 m, bike / gravel 2500 m, MTB 1500 m): the stem counts neither as doubled way nor as a meeting, and in variant B the way home may use the first metres of the way out without the "already walked" penalty. |
| **The length is a rough guide** (± 1 km on foot, ± 5 km by bike) "bevor man seltsame Wege geht". | `score.length_flat_km`: full points inside the band, gently down outside; the cut for "too far off" is the old share plus half the band. No loop is lengthened by an eight or a detour: such loops fall to the crossing rule. |
| **One comment per suggestion** instead of "beste / schlecht". | One text field + "Kommentar senden" on every card; saved with run, route id, name, numbers and wish. `python3 tools/suggest-poc/feedback.py` prints all comments with their routes. |

**What the Münchener Straße stretch was** (his comment on the bike loop of 21:50): 1.15 km of `highway=path, bicycle=designated,
foot=designated, segregated=no`, noise class 6, town class 4, on EuroVelo 15 – a shared path right beside a four-lane
road. It was classed as a plain "Weg" with main-road weight 0, because the old "beside" rule only knew `highway=cycleway`.

**How variant B gets to the wished length today** (the owner asked): it does not stretch a loop. One search from the start
tells how far every point is; turning points (anchors) are taken at fixed shares of the wished length (bands, per
direction) and on destinations; a loop is start → anchor → home another way, or start → A → B → start for two anchors whose
estimated total fits; "Entlang" picks the point on the known route where stem + route + estimated way home fill the length,
a lake round is taken as it is. Loops outside the length limit are dropped, the rest are scored. The eights and tangles
came from the way home: it only *pays extra* for ways already used, so where the pleasant ways are few it crossed or
re-used the way out – nothing counted crossings until now.

**Open after round 6** (bench, variant B, 81 of 90): the Rhine rides are no longer on top in the three bike cases at the
Rhine – see the README's round-6 section for the reason and numbers.

## Into the browser planner – the plan, parked (owner 2026-11-13: "merk dir diese Planung … erstmal weiter an den Routen, das bauen wir etwas später")

Not built, not scheduled. The routes come first (the POC); this is what was agreed for when it is built.

**Surface (owner's own proposal, sketch 2 = https://claude.ai/artifact/GE3STB3Dj1zFgYTiAK8gjV; sketch 1 with three
entry variants = https://claude.ai/artifact/4vw94n6ag24ziSHRMSkftX; sources `~/onpath-suggest-poc/sketch/`):**
1. The first tap into an empty plan opens today's menu ("Hier starten" · "Hierhin"); a third row **"Rundtour von hier"**
   (the owner's words). No extra button in the "Neuer Plan" sheet.
2. The wish: in the browser in the plan sheet, on a phone as a window over the dimmed map (like the Save-name dialog),
   both with **Abbrechen** · **OK**. No Start line – the tapped point is the start, its place name under the title.
   Lines: activity chip, Dauer (1 h · 2 h · 3 h · halber Tag + fine control), Natur / Ruhe / Bekannte Wege each
   niedrig · mittel · hoch, "am Wasser entlang", Wegbeschaffenheit per activity, "Mehr" (Anstrengung). Pace comes from
   the existing time model. Last values remembered.
3. Abbrechen → the plain plan, nothing changed. OK → the existing wait card, then three loops on the map and three
   cards (a speaking name, km · time · ↑, difficulty, one reason line, honest hints such as "mit Fähre", "etwas kürzer
   als gewünscht"; no score number), "Neue Vorschläge", "Wunsch ändern", ✕ back to the empty plan.
4. "Übernehmen" → a normal plan (round trip, a few points); edit / save / share / phone sync as today.

**Still the owner's to decide (recommendations given, no answer yet):** the entry only while the plan is empty
(recommended); the same row in the menu of a place / map symbol – on a phone three of five taps near a trailhead hit a
symbol, so without it the entry is often missing (recommended: yes, under "Hierhin"); the small line under the entry
("Runden nach Dauer und Geschmack" is the sketch's wording, not the owner's).

**Technique:** write the network search once in the shared core (pure Kotlin, no Android), not port the POC's Python;
`web/engine` compiles it with TeaVM like the router today, it runs in the planner's worker and hands suggestions out
one by one; a chosen suggestion is an ordinary point list. Way network from the routing-data parts the planner already
fetches; known routes, trail names, lakes, protected areas from the map areas the page loads anyway (read in the page,
handed to the core); AI tips as an optional small file per map area from the map server; weights in one file shared
with the POC; the POC's bench cases become the core's tests. First a trial build for Hike only: does the network read
from BRouter's node cache work after TeaVM, how fast, how much memory – bike (200 000 nodes) is the open case. The
generated profile copies (main-road cost) change the routing fingerprint and must not touch ordinary plans. Fallback
only if the browser trial fails: the POC as a server service (the start point would leave the device; the app would
need the port anyway).

## Round 7 – stopped, parked (owner 2026-11-13 late: "die laufende Runde unterbrechen … die Grünstück-Kette bauen, das hat mehr Zukunft")

Round 6 built the owner's taste rules but regressed; round 7 was to repair that inside variant B. It was stopped after
~10 minutes; its unfinished, untested changes are on `wip/donald-2026-11-13-suggest-round7` (one commit: Rhine as a
named destination, roadside by share, first steps of crossing-aware building – do not merge as is). What it was to do,
still open, to pick up again or to make unnecessary by the green-patch chain:
1. Rhine bike rides back on top without giving up the roadside rule (my recommendation, the owner did not answer: for
   bikes the roadside punishment by share of the loop; big rivers as named destinations so km on the bank count as
   first-class water; an inland loop with 57 % through houses must not win at Natur hoch). Cases: Zons 3 h, Zons
   control, the owner's case bike 2 h from 51.14162, 6.81206; Köln 2 h must show ≥ 3 suggestions.
2. Crossing-aware building instead of only filtering (7 built Rhine loops were dropped for two meetings).
3. Hilden + Wasser 3 h: a lake round shown again; why no way round the Elbsee exists in the network.
4. Düsseldorf 2 h became shorter and less green (56 % near forest for 81).
5. The free stem also in the "Entlang" and lake constructions.
6. Bench with the KI switch on for the owner's two cases.
Also noted from his later comments (2026-11-13 22:40–22:51, `feedback.py`): the "durch Häuser" measure is wrong (the
loop he liked had 60 %, the one "zu viel durch Orte" 47 %) → real settlement areas from our map; eights "bringen es
gar nicht" → prefer zero crossings, one only when nothing clean exists; ascent: Anstrengung as a clear limit of
metres up per hour, shown on the card; "warum nicht am Wasser entlang" (hike from 51.18413, 6.94460) and "warum nichts
über die Urdenbacher Kämpe" (bike from Hilden) – compass turning points hit such things only by chance.

## Variant C – the green-patch chain ("Grünstück-Kette"), round 8 (2026-11-13 night)

Owner: "die Grünstück-Kette bauen, das hat mehr Zukunft" – his reference is a bike loop from Hilden he praised:
"wirklich clever – einfach alle Grüngürtel geschickt kombiniert". Variant B sets turning points by compass direction,
so a green belt lying between two directions is met only by chance. Variant C plans like a person ("durch den
Stadtwald, rüber zur Heide, über den Grünzug zurück"). `tools/suggest-poc/greenchain.py`, `--method c`, on the page
"C · Grünstück-Kette". **Not the page's default:** on the bench it is worse than B for hikes (below).

**Method, in plain words**
1. The way network around the start is loaded once (as in B) and every piece of way gets B's unpleasantness.
2. *Patches.* A piece of way is green when it is no street, not on / beside a busy road, not inside built-up land, and
   lies in or at nature (forest, heath, protected area within 150 m – `nature.py`; or the routing data's top forest
   classes; or at a bank: open water within 90 m). Green pieces that hang together are one patch; a not-green piece of
   up to 80 m (150 m for bikes) between two green ones – a road crossing – joins them. Patches under 600 m of green way
   (1.5 km for bikes) are dropped, the 40 (30) most worthwhile kept. The start is a patch of one point, or lies in one.
3. *Connectors.* From every patch one search over the not-green rest; it stops at the first point of another patch,
   so only neighbours are linked. Per pair up to two connectors (three from the start) whose gates lie well apart.
4. *The chain.* On this small graph every cycle start → patch → … → start of up to 4 patches (6 for bikes) is
   enumerated depth-first, cut off by the length. Worth = green metres walked inside the patches × their quality −
   unpleasant metres of the connectors (× more the higher the Natur level). The best chain per set of patches is kept.
5. *Realise.* Connectors as found; inside a patch the nicest way between its two gates; when length is left, on foot a
   real loop inside the patch (two turning points, three parts of a third each – tried in up to three directions for
   chains of one or two patches). For bikes there is no stretching inside a patch: it produced dead-end spurs that
   counted as meetings (3–5 per loop). Then the same score sheet as A and B, and the BRouter check route.
6. *Names.* Per patch the part really walked decides: the protected area it runs in, else the lake at its bank, else
   "Wald bei / Grün bei / Ufer bei <nearest named place>". Nothing invented – but see the weak spots.

**What it needs from the map:** `base.pmtiles` of the map areas: `landcover` (forest, heath, wetland), `park`
(protected areas with names), `landuse` (built-up land), `water` (lake, river, pond), `place` (names) – drawn into
one 45 m grid per map area (`nature.py`, version 4, cached beside the cell); plus the routing data's way network and
tags as in B. Nothing online.

**Settlement measure (needed first).** "Durch Häuser" was the routing data's coarse town class; it rated the loop the
owner praised at 60 % and the one he called "zu viel durch Orte" at 47 %. Now: a way counts when at least half of the
land within 45 m is the map's built-up land. Checked against his comments (share of the loop inside built-up land):

| His comment | old | new |
|---|---|---|
| "schöner Weg, nimmt alles mit" (Hilden bike, S · Schleife) | 60 % | 35 % |
| "zu viel durch Orte" (Hilden bike, Ziel EV3) | 47 % | 46 % |
| "Rückweg geht nur durch die Stadt" | 45 % | 46 % |
| "viel zu viel Wohngebiet" (Zons bike, ONO · Keule) | not stored | 29 % |
| "wunderbar am Rhein lang" (Zons bike, Entlang EV15) | not stored | 12 % |

The order is right now; the gap between the praised and the disliked Hilden loop is modest (35 vs 46). The measure
feeds the score part `town` and the search cost `pen_town` of B and C alike.

**Ascent (his Meran comment "wie wollen wir mit Steigung umgehen?").** Anstrengung is a clear cap of metres up per
hour of the tour (`effort.<level>.up_m_per_h`: on foot leicht 120, mittel 300; bike / gravel 100 / 250; MTB 200 / 400;
sportlich no cap) – proposed values, not yet tried by him. Loops above the cap are not offered; if none is below it,
the flattest are shown with a note. The card shows "↑ 304 m · mittel".

**Crossing ("Achten bringen es gar nicht").** Two knobs. The score part `selfcross` now costs a loop that meets its
own way once about 4 points (value 1.0 / 0.6 / 0, weight 4). The strict rule – such a loop only while fewer than n clean
loops are chosen, `select.cross_fill_below` – is built but **off**: the counter also counts a touch near the foot of a
lollipop, and with n = 2 the strict rule hid clearly better loops (Ohligser Heide: 87 points, 86 % near forest, with
one touch, behind 81 points with 28 % street). Two or more meetings are still never offered.

**Sizes and times (desktop, Python, one request)**

| Case | Network nodes | Patches | Connectors | Chain steps | Chains | Loops kept | Shown | Seconds |
|---|---|---|---|---|---|---|---|---|
| Hilden hike 2 h | 13 788 | 17 | 141 | 2 773 | 75 | 17 | 5 | 1.3 |
| Ohligser Heide hike 2 h | 14 483 | 20 | 182 | 8 180 | 56 | 10 | 5 | 1.0 |
| Düsseldorf hike 2 h | 38 223 | 25 | 293 | 32 083 | 167 | 1 | 1 | 1.7 |
| Hilden hike 3 h | 40 024 | 40 | 769 | 200 000 (limit) | 617 | 13 | 5 | 2.8 |
| Meran hike 3 h | 12 210 | 40 | 622 | 151 779 | 958 | 13 | 2 | 1.5 |
| Hilden bike 2 h | 103 375 | 30 | 379 | 500 000 (limit) | 443 | 12 | 2 | 5.9 |
| Zons bike 3 h | 203 271 | 30 | 358 | 500 000 (limit) | 380 | 12 | 3 | 8.6 |
| Hilden bike 4 h | 209 520 | 30 | 698 | 500 000 (limit) | 1 539 | 10 | 1 | 12.9 |

Hikes take 1–3 s (B: 2–7 s), bikes 5–13 s (B: 5–14 s). Per request: network 0.1–1.9 s, patches 0.2–3.3 s, connectors
0.1–3.6 s, chain 0.01–1.8 s; the rest is realising and scoring. Where the enumeration stops at its step limit (3 h
hikes, all bikes) it is not exact. Memory not measured; nothing measured on a phone.

**Bench, B vs C, same weights (2026-11-13k), same sheet**

| Case | ok B → C | Points B → C | Through houses % | Deep nature % | Meets itself | Shown by C | Seconds |
|---|---|---|---|---|---|---|---|
| Hilden hike 2 h | 6/7 → 7/7 | 75.3 → 70.9 | 22 → 36 | 29 → 28 | 0 → 0 | 5 | 2.3 → 1.3 |
| Ohligser Heide hike 2 h | 7/8 → 7/8 | 91.1 → 87.1 | 13 → 11 | 65 → 65 | 0 → 1 | 5 | 2.0 → 1.0 |
| Düsseldorf hike 2 h | 6/6 → 4/6 | 71.3 → 65.0 | 25 → 16 | 0 → 0 | 1 → 1 | 1 | 4.9 → 1.7 |
| Hilden + Wasser 3 h | 7/8 → 5/8 | 78.2 → 73.7 | 18 → 22 | 46 → 35 | 0 → 0 | 5 | 4.3 → 2.0 |
| Hilden Bekannte Wege 3 h | 8/8 → 8/8 | 82.1 → 81.3 | 18 → 21 | 37 → 40 | 0 → 0 | 5 | 6.7 → 2.8 |
| Elbsee hike 2 h, Wasser (new) | 6/7 → 6/7 | 89.5 → 89.9 | 4 → 0 | 71 → 81 | 0 → 0 | 5 | 2.8 → 1.5 |
| Meran hike 3 h | 8/8 → 7/8 | 76.5 → 75.6 | 24 → 22 | 6 → 0 | 0 → 0 | 2 | 1.7 → 1.5 |
| Hilden bike 2 h | 6/6 → 5/6 | 83.4 → 86.0 | 37 → 28 | 22 → 30 | 0 → 0 | 2 | 6.2 → 5.9 |
| Hilden bike 2 h, Natur hoch (new) | 5/7 → 6/7 | 81.1 → 84.5 | 35 → 27 | 22 → 32 | 0 → 0 | 2 | 5.5 → 5.7 |
| Hilden bike 4 h | 6/6 → 5/6 | 85.8 → 84.8 | 28 → 22 | 12 → 23 | 0 → 0 | 1 | 13.8 → 12.9 |
| Hilden gravel 3 h | 5/6 → 6/6 | 82.5 → 85.9 | 23 → 22 | 25 → 31 | 0 → 0 | 4 | 9.3 → 10.8 |
| Zons bike 3 h | 6/7 → 6/7 | 80.4 → 83.8 | 30 → 15 | 19 → 25 | 0 → 0 | 3 | 11.8 → 8.6 |
| Zons bike 3 h control | 6/7 → 5/7 | 86.5 → 87.8 | 24 → 18 | 16 → 27 | 0 → 0 | 2 | 10.8 → 8.7 |
| Owner: bike 2 h Stürzelberg | 6/7 → 5/7 | 80.6 → 81.9 | 17 → 11 | 18 → 16 | 1 → 0 | 2 | 5.2 → 4.8 |
| Owner: bike 2 h Köln | 5/6 → 6/6 | 86.1 → 86.0 | 15 → 16 | 8 → 8 | 0 → 0 | 4 | 5.3 → 5.3 |

Total: B 93 of 104 expectations, C 88 of 104. B shows at least three loops in every case but Hilden gravel and Köln.
Sheets: `~/onpath-suggest-poc/bench/20261113-231959-2026-11-13k-B.txt` and `…/20261113-232238-2026-11-13k-C.txt`.
Nothing was tuned for C beyond what is described here; B's numbers differ from round 6 because the settlement
measure and the crossing weight changed for both.

- **Hikes: C is worse or equal.** From Hilden centre it walks out through town to the fields and the Garather Forst
  (36 % through houses) where B goes to the Ohligser Heide; in Düsseldorf one loop only; with "am Wasser entlang" it
  does not put a lake loop on top (the Stadtwald loop wins, a lake chain is shown further down). From the edge of a
  big patch (Ohligser Heide, Stadtwald north of Hilden) its loops are as good as B's.
- **Bikes: C is better on the numbers the owner cares about** – fewer houses (Hilden 37 → 28 %, Zons 30 → 15 %), more
  deep nature – and its top loops are rings through the green belts (Hilden: Ohligser Heide – Götsche – Garather
  Forst – Benrath – Eller Forst – Elbsee; Zons: Chorbusch – Rheinaue Worringen-Langel – right bank – Urdenbacher
  Kämpe – ferry). But it shows only 1–3 loops, they tend to be short (Hilden 29–31 km for 36), and it does not build
  the "wunderbar am Rhein lang" ride on purpose: the Rhine-route expectations stay red (they are red for B too).

**Weak spots of C**
- Chains share patches, so after the "at most half shared" rule few different loops remain (bikes: 1–3).
- No stretching for bikes → loops as long as the chain happens to be; on foot the loop inside a patch can still touch
  itself once, and from the Vogelpark the stick of the lollipop is 1.2 km (15 % walked twice).
- A river bank is "green at water" like a pond; the Rhine is not a destination of its own; known routes count only
  through B's edge values, not in the chain's worth.
- Names from the nearest place can mislead ("Grün bei Bruchermühle" for a loop in the Hildener Stadtwald; "Ufer bei
  Zenoberg – Tirol" for the Passer promenade). Protected-area and lake names are right where they apply.
- The enumeration is depth-first with a step limit – complete for 2 h hikes, cut off beyond (3 h hike, all bikes).
- The touch cost (search around what was already walked) is built but off: no gain measured, and it hurt the Heide.
- Not run: MTB, the KI switch with C, `bench.py --refs`, the page clicked in a real browser.

**Related work, said to exist – not looked up:** computing jogging routes from the faces of the way network (a loop
as the border of a set of blocks; Karlsruhe, about 2013) and the arc-orienteering formulation for cycle-route
planners (Leuven). The chain here is a small relative of both: patches instead of faces, a budgeted cycle on a patch
graph instead of on the whole network.

## Planner framework (prepared 2026-11-13, switched off)

Owner: "die Struktur muss so sein, dass die Routing-Mechanismen, die wir nachher entwickeln, einfach ausgetauscht werden
können, und dass KI nur ein weiterer Parameter ist … das Framework kannst du doch schon erstellen. Und sicherstellen,
dass wir die andere Planer-Funktionalität dabei nicht gefährden." Built on the branch
`wip/donald-2026-11-13-planner-suggest` – not on main, not deployed. Code: `web/app/src/suggest/`.

**The contract (`types.ts`).** `TourWish` (start, activity + T level, hours, pace = the planner's time model, effort,
Natur / Ruhe / Bekannte Wege as low · mid · high, water, surface per activity, seed, `tips` = AI region knowledge, one
more field) → a mechanism (`SuggestProvider.run`) hands out `Candidate`s: a title, reason lines, honest hints and
either a line (`track`) or plan points. A mechanism only says where a loop goes and why it is nice. `session.ts` then
does the same for every mechanism: a line becomes few plan points (the app's TrackToPlan, `engine.planFromTrack`), the
points are planned by the planner's own `engine.plan`, and the result is a `Suggestion` – so km, time, ascent,
difficulty and the line on a card are exactly what "Übernehmen" gives. The session adds "etwas kürzer / länger als
gewünscht" (more than 15 % off the wished time), drops loops that do not route or repeat, reports progress, and
`cancel()` / a newer `start()` supersede a running request (the engine's running plan is dropped).

**Mechanisms (`registry.ts` – one line each):**
| id | what | where |
| --- | --- | --- |
| `poc` | asks the POC on this computer (`tools/suggest-poc/serve.py`, methods a / b / c with `?suggestPoc=`) through the Vite dev server's `/poc/` proxy; its chosen loops come over as lines | dev build only (`import.meta.env.DEV`); the production build contains neither the mechanism nor a request to it (checked: no `poc/`, no `8790` in `dist/`) |
| `ring` | **stand-in**: three points on a circle through the start, routed by the planner – proves the in-browser path (worker, progress, cancel, superseding) and is what the tests use; no taste at all | built page on localhost; the UI says "Platzhalter" |
| `core` | the real one, not built: the network search in the shared Kotlin core, compiled into the engine | the plan, with the interfaces, is `core-slot.ts` |

**A new mechanism = 3 steps:** (1) a file with a `SuggestProvider` (`id`, `devName`, `run(wish, ctx, emit)`); (2) add
it to `providers()` in `registry.ts` (the first is the default); (3) nothing else – wish, cards, Übernehmen and
`test/suggest-smoke.mjs` stay. AI tips: the wish carries `tips`; a mechanism that has tips uses them only when it is
on (the `poc` mechanism passes it as the POC's `ai`).

**The surface (`ui.ts`, sketch 2):** the first-tap menu gets the row "Rundtour von hier" → the wish (wide: in the plan
sheet under the sheet's own activity chip; phone: a window in the Save-name dialog's look) with Abbrechen · OK → the
planner's message box as the wait card (with Abbrechen) → three lines on the map + three cards (title, difficulty
badge, km · time · ↑, reasons, hints, "Übernehmen"), "Neue Vorschläge" (seed + 1), "Wunsch ändern" (values kept), ✕.
"Übernehmen" = `commit(points)` through the page's edit queue, like points clicked by hand: undo history, share code
in the address, Save, phone sync as for any plan. The module puts on the page only its panel in the sheet, one map
source, one marker and one message, and takes all of it away again in `leave()`.

**The switch – off.** `suggestEnabled()`: only when the page runs on localhost / 127.0.0.1 AND `?suggest=1` (or
localStorage `onpath.suggest = "1"`). On plan.onpath.earth it cannot be turned on. With it off: the menu has its two
rows, no request, no storage key written (the wish is remembered in `onpath.suggest.wish` only after an OK).
Touch points in existing files: `main.ts` (two imports; `let suggestUi`; one row in the first-tap menu and one in the
place menu; one guard in `press()` – a click only picks a suggestion's line while suggestions show; one line in
`setPoints()` – a plan arriving from elsewhere closes the suggestions; the set-up block at the end),
`vite.config.ts` (dev-server proxies), new texts in `web/strings/values*/strings_suggest.xml`. The build's files are
the same as before (no extra chunk); `main-*.js` grew by 16.5 kB (5.4 kB gzip), the CSS by 3.8 kB. The engine (Wasm)
is unchanged.

**Also changed in `main.ts` (not part of the feature, found on the way):** `let saveState` is now declared before its
first use (`saveEntry('empty')` near the top). As written before, the Vite dev server stopped at "Cannot access
'saveState' before initialization" – `npm run dev` showed no planner at all on main; the built page did not show it.

**Undecided by the owner** (recommendations built in, marked in `ui.ts`): the row only while the plan is empty; the
same row in the menu of a place / map symbol under "Hierhin" (in the first try on the real map the tap hit the symbol
"Quelle" on desktop and phone – without the row there the entry would have been missing); the small line under the
row ("Runden nach Dauer und Geschmack" – my words).

**Tested (donald, 2026-11-13):** `web/app/test/suggest-smoke.mjs` (new, 40 checks, green): switch off → two rows,
nothing on the page, no storage key, no request to the POC; switch on with `ring` → third row, wish in the sheet,
Abbrechen = the empty plan exactly, OK → wait card → cards; a second request supersedes the first (3 h asked, then
1 h: only 1 h loops arrive); a map click starts no plan meanwhile; Übernehmen → a round-trip plan, in the address,
shareable, saved, undo → empty, redo → back; a failing mechanism → one calm message, plan untouched; phone width → the
wish is a window with Abbrechen · OK that fits 412 × 915 without scrolling. Existing suites with the switch off – see
the hand-over report for the list and for the suites that are red on this computer on untouched main too (pairing).
Not tested: the place-menu row in a test (needs a map store); the page in a real phone browser; `poc` in a test (it
exists only in the dev server – looked at by hand, pictures in `~/onpath-suggest-poc/planner/`).

**Seeing it:** `node web/app/scripts/dev-local.mjs <segmentsDir> <mapStoreDir> 5199 [overviewDir]` → the planner on
this computer with local routing data and map areas, `/poc/` passed to the POC →
`http://127.0.0.1:5199/?suggest=1` (`&suggestPoc=c`, `&suggestVia=ring`).

**Known gaps:** a POC line turned into plan points gives 7–15 points (TrackToPlan follows the line closely) – too
many for a tidy plan, and the planner's own profile may route a piece differently from the POC's line; the cards'
titles are the POC's working names ("SW · Schleife"), speaking names come with variant C; no small elevation profile
on the cards and no "etwa 7 km" beside the duration yet (sketch 2 has both); on a phone the activity cannot be changed
inside the wish window (it shows the chip's words; change it in the sheet before).
