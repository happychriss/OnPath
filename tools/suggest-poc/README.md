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
