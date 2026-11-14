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

WEAK_SPOTS
