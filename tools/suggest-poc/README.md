# Tour suggestions – proof of concept

A wish in (where, how long, how hard, what you like), a few loops out, each with its reasons. Runs on this computer
only; nothing here is in the app or the planner. The method and its principles: `docs/design/tour-suggestions.md`.

## Run it

```
python3 tools/suggest-poc/serve.py                      # the page: http://localhost:8790
python3 tools/suggest-poc/run.py Hilden --hours 1.5-2.5 --ascent 0-150 --png
python3 tools/suggest-poc/run.py Zons --activity bike --hours 3 --natur hoch --wasser --all
python3 tools/suggest-poc/cases.py                      # the owner's comments as checks
python3 tools/suggest-poc/feedback.py --last 5          # his comments from the page, with their routes
```

- Needs: Java (the route service compiles itself in 2 s), the routing data in `~/onpath-routing/segments/`
  (`ONPATH_SEGMENTS`), a map key for the test map server in `~/.gradle/gradle.properties` (map areas are fetched as
  the app does). Python: standard library only.
- Ports: page 8790 (`--port` / `SUGGEST_PORT`), route service 8791 (`SUGGEST_SERVICE=http://127.0.0.1:<port>`).
- Everything it writes goes to `~/onpath-suggest-poc/` (`SUGGEST_DATA`): map areas, runs, pictures, comments.

## Judge it

1. `cases.py` – every comment the owner made, as one wish and a few plain statements. Read the failed ones.
2. Pictures – `run.py … --png` writes `overview.png` and `route-1.png` into `~/onpath-suggest-poc/runs/<run>/`. Look at
   them: would one walk this? Is it near the best this place allows? What obvious thing does it miss?
3. Other regions – a change counts only if it does not make another kind of place worse (coast, heath, mountains,
   a big city, flat farmland). `--all` prints every loop of the pool with its five axes, so one sees why a loop lost.
4. The owner's comments from the page (`feedback.py`) are the last word.

## Change it

- A number: `settings.json` (read again on every request; each group says what its numbers mean). No number there is
  for a region – if a place needs its own value, the measure is wrong.
- What a way *is*: `facts.py`. What is good *here*: `place.py`. What a person *wants*: `character.py`.
- A new way to build loops: a few lines in `generate.pool` that choose turning points and call `add`.
- What counts and what is shown: `score.py` (axes, hard rules, selection, the words).
- After a change: `cases.py`, a look at two or three pictures in different regions, then a pass over the code for
  anything that became needless.

## Files

| File | What |
| --- | --- |
| `suggest.py` | the edge: settings, place names, route service, map areas, the saved run; `suggest(wish)` |
| `character.py` | level 3: the wish (with its two ranges) and the character it gives |
| `facts.py` | level 1: way tags + map grids → facts per way |
| `place.py` | level 2: the place's yardstick, what stands out (areas, lines), the way out |
| `generate.py` | one assembly routine, turning points from four sources → the pool of loops |
| `score.py` | five axes, hard rules, selection, title / reasons / hints |
| `net.py` | the way network and the graph routines (search, path) |
| `land.py`, `named.py`, `cells.py`, `pmt.py` | our map areas: land grid, named routes / lakes / viewpoints, download, tile reader |
| `service/` | the BRouter route service (Java) and the profile copies with the main-road cost |
| `serve.py`, `page.html`, `run.py` | the page, its server, the terminal tool with pictures |
| `cases.py`, `feedback.py` | the owner's comments as checks; his comments from the page |
| `region_ai.py` | optional: the AI's region tips per map area (one call per area, picks from our own list) |
| `remote/` | the locked front and the test planner for testing on the road (services stopped; see its README) |
| `settings.json` | every number of the method |

## Weak spots (2026-11-14)

- **Following a river is brittle.** A loop along a line needs a way home that is not the line; at a river that means
  a bridge or a ferry at the right place. Zons, bike 3 h: the Rhine ride (north to Düsseldorf and back on the other
  bank, 59 km) comes out in a trace but not reliably in a run – the one failing check in `cases.py`. The 2 h Rhine
  ride from Stürzelberg works.
- **Direction variety.** Hilden, bike 2 h: the three first loops all go west to the Rhine; the ring through the green
  belts east of the town, which the first version had on top, is not offered.
- **Meeting the own way.** At 2 of 20 judged wishes the first loop still meets its own way once (the coast at
  St. Peter-Ording, the Rhine meadow at Zons – few ways through a dyke or a meadow).
- **A wished climb in the hills.** Schierke, T3 sportlich: the loops have 300–430 m where 650 m and more were wished;
  the run says so, but a higher turning point was not found within the time.
- **Roads for bikes.** In farmland and at the coast a first loop can have 500–800 m on a road at a stretch.
- **Speed.** Walks 0.2–2 s (München centre 5 s), bikes 2–14 s on this computer; on big bike networks the facts take
  the most. Memory is not measured; nothing is measured on a phone.
- **Names.** Long protected-area names; unnamed areas are "Wald bei <Ort>"; in the mountains many titles are only a
  direction.
- **Brooks** come from the routing data's river class plus green beside the way; not checked on pictures.
- **Not tried in this version:** MTB, the AI tips input (`--tips`), the remote front end to end (it compiles and the
  adapter is changed), the page's controls by hand (pictures are made from saved runs).
- **Unmeasured guesses** in `settings.json`: the character table (axis weights at both ends, ground welcome, usual
  ascent per hour) and the search factors are first values that passed the checks, nothing more.
