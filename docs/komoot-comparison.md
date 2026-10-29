# Komoot vs OnPath – what to copy, what to skip

Research note, 2026-10-17. Based on Komoot help pages, reviews and forum threads (sources at the end), compared with
OnPath's first test build. Requirement IDs refer to `docs/requirements.md`.

## 1. How Komoot does it (short)

**Adding points to a route.** Komoot has two ways to add a point: *"Include on route"* puts the point
where it fits best in the sequence (smallest detour, whatever order you tap). The *+ / add at end* option
puts it just before the end point. Dragging the route line creates a new via point at that spot.
Dragging an existing point moves it and keeps the order. You reorder points by dragging them in the waypoint
list ("Show all waypoints"). There is one button to reverse the whole tour, plus undo/redo.
"Follow ways" can be turned off per point, which gives a dotted straight line ("off-grid").

**Round trips.** The app has a one-way / round-trip switch. With only start and end set, a "round trip" often
comes back as an out-and-back. Komoot's own advice is to plan one-way and shape the loop with a few points.
Users complain that more points give "a spaghetti mess of mini out-and-backs", that tapping a POI by
mistake makes it the start or end, and that the tool is "hardly intuitive" and fights you when you want an
exact line. So the "smart insert" default avoids spikes when you add a point in the middle, but it confuses
people who expect the points to stay in the order they tapped.

**Planner summary.** Distance, time, ascent and descent, and a difficulty of Easy (blue), Moderate (red) or
Hard (black). Difficulty combines fitness (duration and climb thresholds per sport) with technical
difficulty (SAC scale for hiking). A fitness slider changes the time estimate. "Way types & surfaces"
gives metres per type (path, cycleway, street, trail, singletrack, road …) and per surface (asphalt,
gravel, sand, cobbles …). The elevation profile is coloured green on flat sections and red on steep ones,
and you can drag on it to zoom. "Route alerts" list problems along the route: unsuitable surface ("you may
need to walk your bike"), steep gradients, steps and restricted sections.

**Map and POIs.** Highlights (community favourites) appear as red dots and are on by default in the
planner. Other POIs ("Places": bike repair, e-bike chargers, transit) only show when you turn them on from
the search field. Base map, sport maps (Premium) and heatmaps come from one layer picker. Satellite is
available. 3D and Trail View are web/Premium only. The map stays clean because only Highlights are shown
by default.

**Navigation.** On the phone: turn-by-turn voice, speed, distance done and distance left. You get an
off-route warning with distance to the line, and a compass in off-grid sections. Rerouting needs mobile data,
even in the 2026 watch app. Offline maps are a corridor about 3 km wide around a saved route. Reviewers
note bugs in the off-route alert, and that the free tier gives one free offline region.

## 2. Where OnPath stands
OnPath routes offline (Komoot needs data to reroute). Most of the "Adopt now" items below and several "Adopt later"
ones are in the app now (current behaviour: `docs/ux-two-modes.md`, `docs/core-features.md`). Decided differently
since: no dragging of points or the route line – Plan uses hold-only gestures (owner).

## 3. Recommendations

### Adopt now (small, high value)
| What | Why | Effort |
| --- | --- | --- |
| **Smart insert on tap:** a new point near the current line (e.g. < 150 m, or a long-press menu "Insert here") goes into the nearest leg; elsewhere it is appended | Fixes the out-and-back spike, which is the most common planning annoyance. Appending stays the predictable default, which avoids Komoot's "where did my point go" confusion | S |
| **Drag a point to move it** (order kept) | Basic editing, F-PLAN-04. Each drag re-routes only the two legs next to the point | M |
| **Reverse route** button | One line of code on the waypoint list; useful for loops and the way back | S |
| **Round trip toggle:** "back to start" adds the start again as the last point | F-PLAN-03. Simple and transparent, with no automatic loop generation | S |
| **Redo** next to undo | F-PLAN-08 Must; the undo stack is already there | S |
| **Way types & surfaces bar** (one stacked bar each, tap for metres) | Komoot's most-used summary detail. BRouter already gives `highway`/`surface` tags and the contract fields already exist | M |
| **Gradient-coloured elevation profile** (green < 6 %, orange 6–12 %, red > 12 %; adjust by activity) | Shows climbs at a glance. The data is already computed from the route points | S |
| **Simple difficulty badge** Easy / Moderate / Hard from time + ascent (+ max SAC for hike) | One word answers "can I do this?". Use Komoot-like thresholds | S |
| **"Hide route" toggle** (eye button) that dims or hides planned and imported lines | Lets you read trail colours and labels under the route | S |
| **Remaining distance / ascent / time** on the following screen | F-NAV-01 Must; the most useful number while out on the trail | M |

### Adopt later
| What | Why | Effort |
| --- | --- | --- |
| Drag the route line to insert a point (map gesture) | Komoot-style shaping. It needs hit-testing on the line and a ghost marker, so do it after smart insert | M |
| Reorder points in a list sheet (drag handles) | Rarely needed once insert and move work, but needed for F-PLAN-04 | M |
| Route warnings list (F-PLAN-11): steps, push sections (path/track on a bike profile above SAC/MTB scale), > 15 % gradient, ferry, private access | Safety value; BRouter tags allow it. Tap a warning to zoom to the spot | M |
| Per-leg "straight line" (off-grid) option (F-PLAN-09) | Covers crossing a meadow or a missing path; the dashed fallback already renders | S |
| Offline "reroute back to route" / "to destination" after an off-route alert (F-NAV-02) | Komoot can't do this without data, so it is a real advantage for OnPath | M |
| POI layer filter (food / water / huts / views / transport) in the layer button | Keeps the map uncluttered like Komoot. Show views and huts by default; the rest are opt-in | S |
| Fitness / speed setting that changes the time estimate | Personalised times; one slider in settings | S |
| Route corridor download (F-OFF-02), about 3 km wide like Komoot | Needed once routes go outside the prepared regions (build 2) | L |

### Deliberately skip (keeps the app simple)
| What | Why | Effort saved |
| --- | --- | --- |
| Automatic loop generator ("give me a 20 km round trip") | Komoot's own version gives out-and-backs; it is hard to do well offline | L |
| Reordering all points automatically when you add one (Komoot's default "include on route" anywhere) | Confusing to users; nearest-leg insert near the line is enough | M |
| Community Highlights, photos, tips, ratings | Needs a server and moderation; the Google Maps link-out covers it | L |
| Heatmaps, Trail View, satellite imagery | Online-only and licensing heavy; against offline-by-design | L |
| 3D map | Komoot keeps it web-only; costly on the GPU and battery, with little value for planning | M |
| Turn-by-turn voice (F-NAV-05) | Already decided: an off-route vibration is enough for v1 | M |
| Social feed, collections, device sync (Garmin/Wahoo) | Not core; GPX export covers devices | L |

## Sources
- Komoot route planner help: https://www.komoot.com/help/routeplanner
- Komoot tour characteristics (difficulty, way types, surfaces): https://www.komoot.com/tour-characteristics
- Komoot map layers (Adventure Hub): https://www.komoot.com/adventure-hub/5f7DQJSWnG9Km1qfOYagEt/quatro-formas-de-usar-as-camadas-do-mapa-komoot-para-planeares-uma-rota
- Advnture, "Seven tips for planning even better routes with komoot": https://advnture.com/features/planning-even-better-routes-with-komoot
- BikeRadar, "Komoot: your complete guide": https://www.bikeradar.com/advice/buyers-guides/guide-to-using-komoot
- Singletrack World forum, loops / out-and-back complaints: https://singletrackworld.com/forum/bike-forum/route-planning-komoot-start-and-finish-same-place/
- DC Rainmaker, Komoot offline routing (Jan 2026): https://www.dcrainmaker.com/2026/01/komoots-offline-routing.html
- Search summaries only (page not opened; support.komoot.com returned 403): Komoot warnings help
  https://www.komoot.com/help/warnings and Bikerumor 2020 on route warnings
  https://bikerumor.com/2020/03/09/komoot-mtb-hill-climb-routes-just-got-a-whole-lot-easier-plus-new-route-warnings/
