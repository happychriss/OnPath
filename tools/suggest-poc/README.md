# Tour suggestions – POC on the computer

An experiment, not app code (design: `docs/design/tour-suggestions.md`). A wish (start, duration, walking pace,
difficulty, taste) becomes loop candidates through BRouter; they are scored, a few different ones are shown, the owner
says which is best and why. Made to be changed quickly: no build, no tests, every knob in one file.

## Start

```
python3 tools/suggest-poc/serve.py            # page: http://localhost:8790  (starts the route service on 8791)
python3 tools/suggest-poc/run.py --start Hilden --hours 2 --pace 3.5 --effort relaxed --forest 0.9 --quiet 0.7 --png
```

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

## Tuning log

- **13a → 13b** (Hilden, owner's example "waldreich, entspannt, 2 h, langsam"): the first result was five tidy loops
  through the town (50–70 % street); the best "forest" was a path between railway and industry. Nobody would walk that
  – one walks 1.5 km to the Stadtwald, loops there and walks back. Changes: new shape `keule`; the second pass towards
  pleasant spots; forest counts from class 5 with steeper values; weights forest 3 → 5, street 2.5 → 3, doubled 3 → 2
  (zero at 50 % instead of 30 %: a stalk through town is fine when it leads into the forest), shape 1 → 0.5.
  Result: first place = 1.4 km through town, 4.1 km loop in the Stadtwald, 1.4 km back (59 % near forest).
- **13b → 13c** (Düsseldorf centre): a 2 km mini loop won because its via points had jumped to the same park spots and
  no longer grew with the wish. Selection now skips routes more than 20 % off the wished time.

## Known weak spots

- From a town centre the honest result is half streets – this is the case for **trailheads** (start at a car park /
  stop at the forest); not built yet. The quick pick „Ohligser Heide (Solingen Vogelpark)" – a start at the forest edge – shows the difference (81 % near forest, 90 % signed ways).
- "Street" is rough: a pavement along a main road is "Straße", not "große Straße" (only its noise class shows it);
  a lit park path in town counts as street (`lit_town_path_is_street`).
- Small dead-end stubs at via points still happen (only exact there-and-back at a leg join is cut).
- Doubled way = the very same way pieces; the other pavement of the same street is not seen as doubled.
- No way names in the routing data, so the text names kinds of way only. No POIs (viewpoints, cafés, water) yet.
- Hike only, flat country only tried; the time model is two numbers.
