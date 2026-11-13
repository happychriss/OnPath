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
