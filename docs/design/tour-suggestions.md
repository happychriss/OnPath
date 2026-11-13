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
