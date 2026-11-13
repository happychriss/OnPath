# Tour suggestions – POC on the computer

An experiment, not app code (design: `docs/design/tour-suggestions.md`). A wish (start, duration, walking pace,
difficulty, taste) becomes loop candidates through BRouter; they are scored, a few different ones are shown, the owner
says which is best and why. Made to be changed quickly: no build, no tests, every knob in one file.

## Start

```
python3 tools/suggest-poc/serve.py            # page: http://localhost:8790  (starts the route service on 8791)
python3 tools/suggest-poc/run.py --start Hilden --hours 2 --pace gemütlich --natur hoch --ruhe hoch --png
python3 tools/suggest-poc/run.py --start Hilden --activity bike --hours 2 --ruhe hoch --wasser
python3 tools/suggest-poc/bench.py            # the yardstick: nine fixed wishes, one sheet, compared with the last one
python3 tools/suggest-poc/bench.py --refs     # known circular routes scored by our scorer
```

- Other ports (a second copy next to a running one): `SUGGEST_SERVICE=http://127.0.0.1:8793` (route service),
  `--port` / `SUGGEST_PORT` (page). Another weights file: `SUGGEST_WEIGHTS=<file>` or `bench.py --weights <file>`.
- `--method b` (run.py, bench.py; on the page "Verfahren") = variant B, the search in the way network (hiking only so
  far); `a` = the fan of routed loops. Same score sheet for both.
- Destinations (lakes, known routes, named trails, viewpoints) come from **the app's own map areas**: once
  `python3 tools/suggest-poc/cells.py get <lon> <lat> 1` (3 × 3 cells around the point, ~15 MB each, from our map
  server with the key in `~/.gradle/gradle.properties`; into `~/onpath-suggest-poc/cells/`). A missing area → the
  request runs without its destinations and says which. `weights.json` `attractions.source: "overpass"` = the old
  online stand-in.

- Routing data: `~/onpath-routing/segments/*.rd5` (other folder: `ONPATH_SEGMENTS=<dir>`). Hilden / Düsseldorf need
  `E5_N50.rd5`. Profiles: the app's `app/src/main/assets/brouter/` (`PROFILE_DIR=<dir>` to try a changed copy).
- Needs a JDK (javac, ~2 s compile at the first start), Python 3, for `--png` Chrome / Chromium (`CHROME=<path>`).
- Everything it writes is outside the repo: `~/onpath-suggest-poc/` (`SUGGEST_DATA`): `runs/<run>.json`,
  `runs/<run>/` (table.txt, one GeoJSON per candidate, pictures), `feedback.jsonl`, `service.log`.
- Stop: Ctrl-C for the page; the route service keeps running (`pkill -f RouteService`). After a change to
  `service/RouteService.java` stop it once – the next start compiles again.

## Parts

| File | What |
| --- | --- |
| `service/RouteService.java`, `start.sh` | One long-running JVM with the app's BRouter jars. `POST /route`: points + profile parameters + weighted nogo lines → line with BRouter's full way tags per piece. Only routes. |
| `service/make_profiles.py` | The app's profiles copied to a build folder + the POC's one addition (`mainroad_penalty`, hiking). |
| `service/RouteService.java` `POST /graph` | The way network around a point straight from BRouter's node cache: nodes, links with length, the profile's cost factor per direction, full way tags, geometry (Hilden, 4 km radius: 21 900 nodes, 28 700 links, 1 015 km of ways, 0.16 s). |
| `netsearch.py` | **Variant B**: unpleasantness per metre for every piece of way (the score sheet's ingredients), one search from the start, anchors per direction and distance + on destinations, loops start → A → (B →) start with a penalty on ways already walked, ready-made rings (lake shore, circular route), then the same score sheet. Chosen loops go once through the real router (`router_match_pct`). |
| `cells.py`, `pmt.py` | The app's map areas on this computer (download as the app does) and a small pure-Python reader for PMTiles + vector tiles. |
| `attractions.py` | Destinations in reach from the map areas: known routes with name and rank (`overlays.pmtiles` `routes`), named trails – local circular walks, promenades, Waalwege (`trailnames`), named lakes with their shore (`base.pmtiles` `water` + `water_name`), viewpoints (`pois.geojson`). One name per piece of way (the highest-ranking route). |
| `bench.py` | The fixed yardstick (see below). |
| `suggest.py` | Everything else: wish, fan of candidates, way back pushed off the way out, resize until the time fits, second pass towards pleasant spots, numbers, score, selection, text. |
| `weights.json` | **Every knob** (generator, profile switches, time model, way classes, score weights, selection, quick-pick places). Read again on every request – edit, save, press „Vorschläge". Raise `version` with each tuning so a verdict in `feedback.jsonl` says which weights it saw. |
| `serve.py`, `page.html` | The page: form, map (Leaflet + online OSM tiles), cards, „Das ist die beste" / „Die ist schlecht" + reason → `feedback.jsonl` (wish, all candidates' numbers, weights version). |
| `run.py` | The same from the terminal: table of all candidates, a text per chosen route, GeoJSON, with `--png` pictures (overview + each chosen route coloured by way kind) – the way for an AI session to look at the routes. |

## How a request runs

1. Wish → target time; target km = hours × pace (`time`: plus ascent / `ascent_m_per_h`).
2. Fan: `directions` × `shapes` (dreieck = 2 via points, viereck = 3, keule = a stem out and a smaller loop at its end).
   Each candidate is routed leg by leg; every earlier leg is a weighted nogo line for the later ones (`back_penalty`),
   so the way back avoids the way out. The loop is resized up to `fit_iterations` times until the time fits.
3. Second pass (`taste_pass`): the pleasant, non-street spots the first pass walked over (by the wish's forest / quiet
   / water sliders) become targets – every via point jumps to the nearest one. Name ends in „gezielt".
4. Numbers per route from the way tags (`classes`): near forest %, street %, big street %, paved %, signed %, loud %,
   doubled %, stalk, crossings, shape. Parts 0..1 → weighted mean × 100 (`score`); taste parts × the wish slider.
5. Selection (`select`): best first, not too similar to one already chosen, time within `max_length_off`.

## Facts measured (2026-11-13, this computer, E5_N50.rd5 of 2026-10-25)

- `estimated_forest_class`, `estimated_noise_class`, `estimated_town_class`, `estimated_river_class` **are filled** in
  our rd5 (Hilden centre → forest edge, 3.9 km: forest class on 35 % of the length, noise on 79 %, town on 66 %).
  `estimated_traffic_class` is not on footways (roads only). The forest class means "green nearby" (parks count, a
  pavement next to a wood counts) – so streets get only `forest_on_street_factor` of it.
- A weighted nogo line along the way out works: same two points back, shared way 98 % without, 70 % at weight 2–5,
  33 % at 20, 2 % at 100 (+23 % length). Default 20.
- Speed: one leg 35–60 ms; a request = 72 candidates ≈ 400–580 legs: 4–6 s around Hilden, 9 s in Düsseldorf centre
  (4 router threads, desktop). A phone will be several times slower – fewer candidates or results one by one.

## Round 3 – variant B and our own map areas (2026-11-13 evening)

Bench, same weights (13f), `bench.py --method a` vs `--method b` (bike cases run as A in both):

| Case (hike) | ok A → B | Points A → B | Near forest % | Longest main-road m | Doubled % | Seconds A → B |
| --- | --- | --- | --- | --- | --- | --- |
| Hilden 2 h | 5/6 → 5/6 | 67.9 → 68.3 | 60 → 48 | 370 → 74 | 0 → 0 | 5.2 → 2.0 |
| Ohligser Heide 2 h | 5/7 → 6/7 | 78.5 → 84.6 | 81 → 71 | 0 → 0 | 21 → 0 | 3.7 → 1.8 |
| Düsseldorf 2 h | 5/5 → 5/5 | 71.0 → 85.2 | 39 → 80 | 74 → 4 | 1 → 1 | – → 4.7 |
| Hilden + Wasser 3 h | 6/6 → 6/6 | 74.4 → 76.3 | 42 → 49 | 108 → 23 | 0 → 0 | 6.6 → 4.4 |
| Hilden Bekannte Wege 3 h | 6/7 → 6/7 | 77.7 → 79.8 | 67 → 64 | 370 → 109 | 0 → 0 | – → 6.3 |
| Meran 3 h | 7/7 → 7/7 | 70.5 → 81.5 | 57 → 62 | 16 → 29 | 0 → 2 | 4.7 → 1.6 |

Total 49 → 50 of 53. B's red checks: Hilden 2 h near forest 48 % (limit 50), Heide street 21 % (limit 15), Bekannte
Wege signed 46 % (limit 50). No weight was tuned for B – `netsearch` in `weights.json` holds its first guesses.

- The real router confirms 70–98 % of a B loop's length when it is routed through via points every 600 m
  (`router_match_pct` in the run's numbers); the rest are places where BRouter prefers another way between two points.
- Heights: BRouter marks "no elevation" with a huge negative value (bridges, gaps) – ignored in B's ascent. B's ascent
  is a plain 6 m hysteresis over node heights, A's is BRouter's own filter; they agree within a few metres on the
  checked loops (Meran 181 vs 186, 294 vs 309).
- B counts a crossing per `footway=crossing` way piece, A per crossing node – B's "Querungen" read higher in towns.
- Weak in B: the anchors are one node per sector and band, so in a city all good loops share the same park and only
  the first is shown as clearly different; names of loops repeat ("Ziel Posthornweg" three times); no local
  improvement step; ring walks only where the map's trail labels close a ring in the network (seen: none chosen in
  the bench); hiking only; Python speed.

## Tuning: only against the bench

`bench.py` runs nine fixed wishes (Hilden hike 2 h, Ohligser Heide, Düsseldorf, Hilden + Wasser 3 h → Elbsee, Hilden
Bekannte Wege, bike 2 h / 4 h, gravel 3 h, Meran) and prints one sheet: the first suggestion's numbers and pass / fail
on plain expectations (length ± 15 %, longest main-road stretch under the limit, doubled < 10 %, ≥ 3 suggestions,
≤ 15 s, plus per case: near forest, Elbsee among the first three, a known route named …). Each sheet is saved
(`~/onpath-suggest-poc/bench/`) and compared with the one before. Rule: change `weights.json`, raise `version`, run the
bench; a change that turns a green case red needs a reason in the log below.

## Tuning log

- **13a → 13b** (Hilden, owner's example "waldreich, entspannt, 2 h, langsam"): the first result was five tidy loops
  through the town (50–70 % street); the best "forest" was a path between railway and industry. Nobody would walk that
  – one walks 1.5 km to the Stadtwald, loops there and walks back. Changes: new shape `keule`; the second pass towards
  pleasant spots; forest counts from class 5 with steeper values; weights forest 3 → 5, street 2.5 → 3, doubled 3 → 2
  (zero at 50 % instead of 30 %: a stalk through town is fine when it leads into the forest), shape 1 → 0.5.
  Result: first place = 1.4 km through town, 4.1 km loop in the Stadtwald, 1.4 km back (59 % near forest).
- **13b → 13c** (Düsseldorf centre): a 2 km mini loop won because its via points had jumped to the same park spots and
  no longer grew with the wish. Selection now skips routes more than 20 % off the wished time.

- **13c → 13e** (round 2, owner's feedback): destinations (lakes, known routes) as loop targets; main roads = longest
  stretch (`mainroad`, weight 3) + `mainroad_penalty` in the hiking profile; signed ways by rank (weight 1 → 2.5,
  `hiking_routes_preference` 0.2–0.8 → 0.3–1.1); street 3 → 3.5; lit park path no longer street; water = river class
  or metres on a lake's shore (weight 1.5 → 3); activities bike / gravel / mtb with own weights; few controls.
- **13e → 13f** (bench): (1) Hilden 2 h fell to 39 % near forest – every way to the Stadtwald has a 370 m stretch
  along a main road and that was punished twice (street share with main roads counted double + the new longest-stretch
  part) → `big_street_counts` 2 → 1. Result 58 % near forest, but the stretch (370 m > 300 m) now shows as a red
  check: honest, it is the only way out of the centre to the forest. (2) doubled weight 2 → 3, zero at 50 → 35 %
  (Ohligser Heide's first place walked 21 % twice) – not enough yet, see weak spots. (3) long bike loops: no target
  candidates (their 25 km legs took 12 s).

## Known weak spots

- **Ohligser Heide:** the first place is now a loop pulled onto the X30 with 21 % walked twice (before round 2: no
  doubling). Target loops on a *linear* known route tend to go out and back along it – they need their own shape
  (follow the route for a while, come back another way) instead of the pulled stem-and-loop.
- **Hilden centre → Stadtwald:** 370 m along a main road is unavoidable on the way NE; the bench shows it red.
- Destinations depend on Overpass in the POC (slow, often busy); the phone will read them from the map area's files.
- Names: a way counts for *every* signed route that runs over it (three names for the same 2 km); route names are
  matched by distance (≈ 30–60 m), so a parallel way can pick up a name.
- Bike: the routing itself is the app's profile unchanged (its traffic classes); the POC's main-road cost exists
  only for hiking. Bike main-road stretches of 600–800 m occur (limit 1000 m). MTB was not looked at.
- The page was not clicked through in a real browser after the rebuild of the form (pictures and endpoints only).

- From a town centre the honest result is half streets – this is the case for **trailheads** (start at a car park /
  stop at the forest); not built yet. The quick pick „Ohligser Heide (Solingen Vogelpark)" – a start at the forest edge – shows the difference (81 % near forest, 90 % signed ways).
- "An Hauptstraße" for a pavement relies on the noise class (≥ 3); a pavement without a noise class beside a busy
  tertiary road still counts as a quiet street.
- Small dead-end stubs at via points still happen (only exact there-and-back at a leg join is cut).
- Doubled way = the very same way pieces; the other pavement of the same street is not seen as doubled.
- No way names in the routing data; names come from the destinations (known routes, lakes).
- The time model is two numbers per activity.

## Round 4 (2026-11-13, weights 2026-11-13g) – bike in the network search, along known routes, surface, routes on the map

Why the Rhine cycle path was missed at Zons (owner's run 20261113-193638): EV15 was in the destinations list 0.5 km
from the start, but long bike loops ran as variant A with destinations switched off (6 plain hexagons), and the score's
`water` part was 1.0 for every candidate (one pond passed = full points). Changed:

- **Variant B for Bike / Gravel / MTB** (`netsearch.py`, `activities.<act>.netsearch`): the network comes without
  forbidden ways and without minor ways (`skip` = service / footway / steps / path …, unless on a signed cycle route or
  `bicycle=designated` – `keep`; every way within 500 m of the start stays). Zons 3 h: 203,000 nodes, load 1.8 s.
- **Known routes count strongly**: a way on a signed route costs `1 - known_discount_per_signed x Bekannte Wege x rank`
  of the others in the search (hoch ≈ 28 %, mittel ≈ 60 %, niedrig ≈ 84 %), score weight `signed` 2.5 → 4.5 (bike 6).
- **Loops along a known route** (`netsearch.follow`, from Bekannte Wege ≥ 0.3): short stem onto the route, along it to
  the point where stem + route + way home fill the wished length, back another way; both directions; card name
  „Entlang <Weg>“. `net.follow` in the run file says per route what was built.
- **One name per route**: „EuroVelo 15 - Rheinradweg - Teilstrecke …“ pieces and „(West)/(Ost)“ parts are one route.
- **`water` part fixed**: share near water (full at `water_full_at`) or lake shore (full at `lake_full_m`, on long loops
  `lake_full_share` of the loop).
- **Wegbeschaffenheit** (form line, per activity; `surface` in weights.json): surface class A asphalt / F firm / N
  natural from `surface` (filled on 71 % of the way length around Zons), else `tracktype` (16 %), else the kind of way;
  `smoothness` (14 %) is not used. Score part `surface`, cost in the search, card shows the split. Replaces the old
  `natural` part (weight 0).
- **Known routes on the map**: checkbox „bekannte Wege zeigen“ draws the routes of our own map areas (`/known`,
  `attractions.known_lines`) under the suggestions, name on hover / tap. The planner's own map picture (MapLibre +
  our style) was **not** built in – the page is Leaflet; that is a rewrite of the page's map part.
- Bench: new case `zons-bike-3h` (≥ 15 km Rheinradweg in the first suggestion). Sheet B 2026-11-13g: 56 of 59;
  red: Hilden 2 h forest 45 % (limit 50), Heide street 21 %, **Hilden + Wasser lost the Elbsee in the top 3** (new
  with the water fix – not tuned). Times B: bike 2 h 5.7 s, 3 h 10.3 s, 4 h 13.2 s, gravel 3 h 9.8 s.
- Weak: the network search on 200,000 nodes is at the limit of Python (0.2 s per search); „Entlang EV15“ southwards has
  a 2.4 km main-road stretch and comes back along the same river; bike main-road cost is still the app's profile;
  the surface options' numbers are a first guess; MTB not run.

## Round 5 (2026-11-13, weights 2026-11-13h) – main roads for bikes, the Rhine ride on top, loops made for a lake

Owner: "Mach bitte Punkt 1, bevor ich probiere" (main roads for bikes; the Rhine ride first; the Elbsee back).

- **What the 1.4 km "main road" of the Rhine ride was:** Urdenbach, between the ferry and Benrath – `highway=tertiary`
  with `maxspeed=30` and `cycleway:both=shared_lane`, on the signed Rhine route. A 30 km/h village street, classed
  as main road only because every tertiary was. No busy road, no bridge.
- **`classes.main` (suggest.main_weight):** "along a main road" is now a weight 0..1 instead of yes / no. Bikes:
  secondary 1.0, tertiary 0.8, × 0.3 on a 30 km/h road, × 0.5 with a cycle track, × 0.75 with a lane, × 0.4 on a
  bridge, a separate cycle path beside a loud road 0.4. From 0.5 a piece is shown as "an Hauptstraße"; the longest
  stretch counts weighted metres from 0.3. Hike: all 1 (the old rule, results unchanged).
- **Cost:** variant B's edge value takes `pen_main × weight`; the bike / gravel profile copies got
  `mainroad_penalty` (make_profiles.py – final check route and variant A). mtb.brf is not changed.
- **A known route on a main road** counts only where the routing data says the road itself carries a signed route.
  Before, a loop "along EV15" rode 2 km on the B9 carriageway (70 km/h, no cycle way) because the road lies within
  30 m of the route's line; the route is the path beside it.
- **`signed` part for bikes:** full only at 90 % rank-weighted share (was 60 % – every bike loop had 1.0, the same
  fault the water part had). Points of bike cases are therefore lower than on the 13g sheet and not comparable.
- **Water:** weight 3 → 6 with `wasser_off` 0.25 → 0.12 (no change without the theme); full river share 0.5 → 0.8,
  full lake stretch 1 km → 2.5 km / 25 % of the loop: passing a pond no longer gives full points.
- **Loops made for a lake (`netsearch.lake`, hike only):** a fairly direct way to the lake (the most pleasant way
  from Hilden centre to the Elbsee was 7.7 km for 2.5 km as the crow flies, so no loop ever got there), then round
  the lake when the length allows and the walked ring really encloses the water, else along the shore each way,
  home another way. A way up to ~120 m from the water counts as "at the lake" (`lake_pad_cells`). With the theme
  on, one shown loop is made for a lake, and a lake round is shown when one fits (`select.lake_slot_from_water`).
  Off for bikes: a small lake round is far too short for a bike wish (it won at Zons with 44 km for 54).
- **Card:** "mit Fähre" when a loop uses a ferry (all three Zons suggestions cross with the Zons–Urdenbach ferry);
  the main-road chip turns red at the activity's own threshold.
- **Bench:** Zons now expects ≥ 25 km on the Rhine route in the first suggestion; new control `zons-bike-3h-mittel`
  (Bekannte Wege mittel, no water); Hilden + Wasser also expects a lake round among the suggestions.

Still weak after round 5:
- The Hilden "Am Elbsee entlang" loop has 1.8 km at the Menzelsee and only 0.9 km at the Elbsee, is 14 % too long
  and comes home the long way round the south-west. No way round the Elbsee itself was found in the network.
- The Menzelsee round is a real round, but its way home wanders through the industrial area (the way back is
  pushed off the way there) – a plain there-and-back stem would be better here.
- Zons: only three different suggestions (many candidates are near copies), and all use the ferry – no check of
  its hours. The first one has a spur at its north end (seen on the picture, not looked into).
- Rivers are not named destinations yet: "at the water" for the Rhine comes from the routing data's river class,
  which cannot tell the Rhine from a pond.
- Not done: the two old reds (Hilden 2 h forest 49 % for 50, Heide street 21 % for 15); MTB; variant A not re-run.

## Stage 2 – AI region knowledge per map area, behind a switch (2026-11-13)

Owner: "einmal mit und einmal ohne KI, dass man es im direkten Vergleich sehen kann." Default **off**; with it off the
bench sheet is number for number the round-5 sheet (checked).

- **Where the knowledge comes from:** `region_ai.py` – one AI call per map area (middle model, 7–12 ct through this
  tool), the AI picks **by number from our own list** of named things (no name matching), files in
  `~/onpath-suggest-poc/region-ai/10-x-y.json`. At request time no AI is called; a missing file = as without AI
  (the page says so). `python3 tools/suggest-poc/region_ai.py --around Hilden --km 20` lists the areas in reach without
  a file and the cost; `--create` makes them. Present: the 3×2 areas around Hilden / Zons / Solingen and Meran.
- **Switch:** page "mit KI-Regionswissen", `run.py --ai`, `bench.py --ai`, wish field `ai`.
- **Score part `ai`** (`score.weights.ai`, knobs under `ai.bonus`): every ~30 m of the loop counts once, with the rang
  value of the best pick it lies on / in (rang 3 = 1.0, 2 = 0.7, 1 = 0.4); that share of the loop is full at 50 %.
  A pick that is a signed route counts half (`route_pick_factor`) because the `signed` part already pays for signed
  routes by rank. A sight passed adds 0.06 × rang value, at most 0.15. Picks the activity does not fit are not loaded.
- **Generator (variant B):** up to `ai.targets` picks that the map's own destinations do not have (areas, sights, river
  banks) become anchors – loops "Ziel <Name> (KI-Tipp)". Variant A only scores, it builds nothing towards picks.
- **Card:** "KI-Tipp: <Name> (x km)" – name and km only; the AI's sentence only as a tooltip "KI-Text, ungeprüft".
- **Compare:** page button "Vergleich mit / ohne KI" (same wish, same seed, both top 3 in a table, two buttons switch
  the map); `run.py … --compare-ai [--png]` prints the table and writes `overview.png` + `overview-ohne-ki.png`.
- **Points with and without are not comparable** – with the switch on there is one more part in the mean.

Bench, variant B, weights 13h, off → on (first suggestion):

| Case | Points | First suggestion off → on | Judgement |
|---|---|---|---|
| Hilden hike 2 h | 69.8 → 68.4 | same loop | same |
| Ohligser Heide 2 h | 86.4 → 86.3 | same loop | same |
| Düsseldorf 2 h | 84.6 → 72.7 | same loop, AI part 0 (no pick touched) | same; the points fall for nothing |
| Hilden + Wasser 3 h | 77.0 → 71.7 | same loop | same |
| Hilden Bekannte Wege 3 h | 78.9 → 76.9 | NO → O loop, 3.2 km in the Ohligser Heide | about the same |
| Hilden bike 2 h | 86.8 → 78.9 | W loop (36.0 km, 2:01) → to the Rhine (38.8 km, 2:10, 16 km EV15) | a ride a local would name, but 8 % too long and less green |
| Hilden bike 4 h | 87.4 → 82.8 | Euroga loop north → along the Rhine (41 km EV15), 15.5 s | different taste, not better; too slow |
| Hilden gravel 3 h | 84.6 → 76.7 | → through the Urdenbacher Kämpe (4.4 km) | plausible |
| Zons bike 3 h | 80.7 → 80.3 | same Rhine ride | same |
| Zons bike 3 h control | 87.2 → 84.5 | → via Knechtsteden forest: 36 % green for 15, main road 105 m for 233 | better |
| Meran hike 3 h | 81.0 → 78.3 | same loop | same |

64 of 66 → 63 of 66 (the lost one is the time limit on the 4 h bike case). Honest summary: in 6 of 11 cases nothing
changes; where it changes, it pulls bike loops to the Rhine and into named nature areas – once clearly better, mostly a
different, defensible choice, never a clearly bad loop in this sheet. Weak: a loop that touches no pick loses points for
no reason (Düsseldorf); the anchors cost 1–3 s on bikes; much of the picks repeats the signed routes; only one
AI call per area, stability untested.

## Round 6 (2026-11-13, weights 2026-11-13i) – the owner's taste rules

The owner's rules (table and reasons: `docs/design/tour-suggestions.md` "Owner's taste rules"): the loop meets its own way
at most once; along a road is bad, signed or not; not through houses; large connected nature counts; a short way out and
back is free; the length is a rough guide; one comment per suggestion.

- **New / changed:** `nature.py` (deep nature from our map areas' `landcover` + `park`, a 45 m grid per area, cached as
  `nature-v3.*` beside the cell), `feedback.py` (prints the comments with their routes), score parts `selfcross`,
  `roadside`, `town`, `deep` (the old `shape` is gone), `score.free_stem_m`, `score.length_flat_km`,
  `select.max_self_cross`, `classes.main.beside_noise`, `netsearch.pen_town / pen_main_natur / roadside_no_discount /
  follow.road_cost`, the profile copies' "beside a road" for every separate way (`service/make_profiles.py` – restart the
  route service). Page: one field + "Kommentar senden" per card; chips "kreuzt sich n×", "hin und zurück n m", "an / neben
  Straße %", "durch Häuser %", "tief in der Natur %"; "x km im Naturschutzgebiet <Name>".
- **Read the owner's comments:** `python3 tools/suggest-poc/feedback.py [--last 5]`.

### Bench, variant B, 13h → 13i (the two owner cases are new in both columns)

| Case | ok before → after | Points | First suggestion after |
| --- | --- | --- | --- |
| Hilden hike 2 h | 5/6 → 7/7 | 69.8 → 69.9 | to the Ohligser Heide, 3.1 km in the reserve, stem 249 m |
| Ohligser Heide 2 h | 6/7 → 7/8 | 86.4 → 90.3 | 84 % near forest, 68 % deep nature, 2.6 km Ohligser Heide + 2.3 km Krüdersheide |
| Düsseldorf 2 h | 5/5 → 6/6 | 84.6 → 70.9 | a shorter loop (6.6 km, 1:35) – worse: 56 % near forest for 81 |
| Hilden + Wasser 3 h | 7/7 → 6/8 | 77.0 → 75.3 | no lake loop on top, no lake round shown – worse |
| Hilden Bekannte Wege 3 h | 7/7 → 8/8 | 78.9 → 78.7 | |
| Hilden bike 2 h | 5/5 → 6/6 | 86.8 → 80.4 | |
| Hilden bike 4 h | 5/5 → 5/6 (6/6 re-timed) | 87.4 → 81.9 | 94 km for 88 |
| Hilden gravel 3 h | 5/5 → 5/6 (6/6 re-timed) | 84.6 → 82.4 | |
| Zons bike 3 h | 6/6 → 6/7 | 80.7 → 78.3 | a loop to the east – the Rhine ride is no longer first |
| Zons bike 3 h control | 6/6 → 6/7 | 87.2 → 81.9 | 5.4 km Rheinradweg for 24 |
| Owner: bike 2 h, Stürzelberg, Wasser | 5/6 → 6/7 | 78.1 → 73.5 | "Entlang Euroga Radwege"; the Rhine ride he called the best is second (71.1) |
| Owner: bike 2 h, Köln | 5/5 → 5/6 | 85.9 → 76.4 | one suggestion only |
| Meran hike 3 h | 7/7 → 8/8 | 81.0 → 79.2 | |

Total 74 of 77 → 81 of 90 (13 more expectations: "kreuzt sich ≤ 1×" in every case; the length check is now the owner's
band). Points before / after are not comparable (other parts).

### What is wrong after round 6 (tune next, against this sheet)

- **The Rhine rides lost the top place** in all three Rhine bike cases. The owner's favourite ("wunderbar am Rhein lang",
  south to Worringen and back by the ferry) has 1.5 km on the path beside the Neusser Landstraße; the longest-roadside rule
  costs it the first place (`mainroad` 0.18 before softening for bikes, second place after). The rule he asked for and the
  loop he likes pull against each other here; a way round that stretch was not found by the search.
- **Too few suggestions for bikes at the Rhine:** 7 of the built loops are dropped for meeting their own way twice (bridges
  and ferries are bottlenecks); Köln shows one loop, Stürzelberg four.
- **Hilden + Wasser:** the lake loops are built but none is shown; the first loop walks 15 % twice.
- **"Beside a road" comes from the noise class only** – the routing data has no "this path runs parallel to that road". A
  forest path near a motorway counts too (x 0.7).
- **`follow` and the lake loops** still treat their stem as walked (no free stem there).
- **Timing:** the first request in a new map area builds its nature grid (a few seconds, once). Two bike cases took 31 s
  and 69 s in the last full bench run (not explained); run alone right after they took 10.1 s and 14.9 s, as in the run
  before – with those the sheet reads 83 of 90.
- **Not run:** variant A, MTB, `bench.py --refs`, the switch "mit KI", the page in a real browser (the comment button was
  not clicked).

## Round 8 (2026-11-13 night, weights 2026-11-13k) – variant C, the green-patch chain

`--method c` (run.py, bench.py), on the page "C · Grünstück-Kette (Grünflächen verbinden)"; B stays the default.
Method, sizes, bench B vs C and weak spots: `docs/design/tour-suggestions.md` "Variant C – the green-patch chain".

- `greenchain.py` – green ways → patches → connectors between neighbouring patches → chains enumerated on the patch
  graph → realised (loops inside a patch on foot). Knobs: `weights.json` "greenchain" (per activity under
  `activities.<name>.greenchain`). `net.chain_log` in a saved run says what became of every chain.
- `nature.py` (grid version 4) – besides deep nature now built-up land (`built`), open water (`water`) and place names
  (`place_near`) from the map areas. **"Durch Häuser" = inside the map's built-up land** (`nature.built_from`,
  `built_radius_m`), for B and C – it replaces the routing data's town class.
- Anstrengung is a cap of metres up per hour (`effort.<level>.up_m_per_h`); the card shows "↑ 304 m · mittel".
- Crossing: score part `selfcross` 1.0 / 0.6 / 0 at weight 4; the strict rule `select.cross_fill_below` is built, off
  (99).
- Bench: two cases from the owner's comments (`elbsee-hike-2h-wasser`, `hilden-bike-2h-natur`), expectations
  `lake_km` and `built_max`.

Result: B 93 of 104 expectations, C 88 of 104. C is quicker on foot (1–3 s) and better for bikes on houses and deep
nature, worse for hikes from a town centre and with "am Wasser entlang"; it shows fewer loops.

Tuning log, round 8 (each tried against the bench):
- touch cost 0.15 (search around nodes already passed): no gain for bikes, worse loops in the Ohligser Heide → 0.
- stretching inside a patch for bikes: spurs, 3–5 meetings per loop → off (`stretch_share` 0).
- strict "clean loops first" with n = 2: hid the best Heide loops → off; the score part does it instead.
- patch quality squared + connectors up to 45 % of the wish: Hilden hike unchanged, Düsseldorf lost its only loop →
  back to 1.0 / 35 %.
- bank value by the Wasser wish (a pond between houses is no destination without the theme): kept.

## Night 2026-11-13/14 (weights 2026-11-14b) – one pooled method `auto`, judged at the owner's test places

Full account, and **the method written out for the planner port**: `docs/design/tour-suggestions.md`
("Night 2026-11-13/14 …" and "The method, as the planner should build it").

- **`auto` is the default** (page, `run.py`, `bench.py`): the network search (B) and the green-patch chain (C) run on
  one loaded network, their loops go into one pool, one score, one selection. `--method a|b|c` stay for comparison.
- **`judge.py`** – the second yardstick: 21 wishes at places with different terrain (Hilden, Nordkirchen, Monschau,
  Rursee, Winterberg, Siebengebirge, Ahr, Wuppertal, Mettmann, Schwelm, Hagen, Wetter, Fröndenberg, Zons, Meran), one
  picture per run and the numbers to judge it; marks in `~/onpath-suggest-poc/night/judgement.md`.
  `python3 tools/suggest-poc/judge.py --method auto --tag x [--only nk,wup]`
- **Map areas** in reach are fetched by themselves (`cells.ensure`; `SUGGEST_REQUEST_CELLS=0` stops the page from
  asking the server for areas it does not list). A run without a map area says so in a box.
- New on the card: deductions ("Abzug: langes Stück an einer Straße −5"), green km, rail-trail km, km at the water,
  the working name and where the loop came from (b / c).

### Tuning log of the night (each against bench + the judged pictures)
| Change | Why | Effect |
|---|---|---|
| Missing map areas fetched; note in a box | Nordkirchen: "alles nach Süden", "durch Werne", "Grünstück" – his runs had no map area | with the areas: loops in four directions, none through Werne |
| Start seated on the connected network | Wuppertal Mirke by bike: no suggestion (start on steps to a private path) | 4 suggestions |
| Road grid: a way beside a road found by geometry (`classes.main.beside_geo`); small roads by traffic class | "auf einer Landstraße wandern" – no noise class along a country road | such pieces are drawn and counted as "an Straße" |
| Flaws = points off (`score.flaws`) | "nur 4 Punkte Unterschied" | a loop with a long road stretch / through houses / doubled is clearly behind |
| Pool + one selection (`select.floor_points`, `shared_cost`, `same_dir_cost`, `max_built_pct`, `max_doubled_pct`) | "geht nur durch den Ort" as a fifth suggestion; all one way | fewer, different suggestions; old bench cases 93 (B) / 88 (C) → 98 of 104 |
| Chain connectors searched again in the whole network (`greenchain.connector_full_search`); chains to realise must differ (`realise_max_common`) | "durch Hassels, warum nicht durch den Hasseler Forst" | Hilden bike: 21 % through houses (C before 26 %, B 35 %), 19 km in green |
| Rail trails: cost −45 %, green for the chain, lines and turning points, part `railtrail` | owner's test regions (Wuppertal, Ruhr) | Wuppertal 2 h: 4–22 km rail trail in the shown loops; Wetter: 9.7 km |
| `green` part (km in green); water from the map's shapes, big water told from ponds; water loops first with the box | "die Länge durch was Grünes ist wichtig"; "warum nicht am Wasser entlang?" | Elbsee case: Elbsee – Menzelsee first (4.1 km at the lakes); both water bench cases green |
| Bikes: houses a flaw only beyond 30 % | the loop he liked had 35 %, "zu viel durch Orte" 46 % | bike points less crushed in suburbs |
| Speed: lighter road grid, bounded searches, connector cache, router checks side by side, `long` settings | pooled runs took 20 s for 3 h by bike | 13–19 s |
| Tried and dropped: a "theme bonus" in points for water | the forest loop also touches water and still won | replaced by "water loops first" in the selection |

Known weak spots after the night: Fröndenberg / Ruhr valley (narrow river not seen as big water), Mettmann (rail trail
6 km away not used), hikes from a town centre (entry points missing), names ("Bahntrasse" several times), bike loops
10–15 km short of the wish, long bike runs over 15 s.
