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
`RoutingEngine` needs two additions for it: profile switches per request and weighted nogo lines.

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
