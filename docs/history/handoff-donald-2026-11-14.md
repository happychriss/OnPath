# Handoff donald, 2026-11-14 (noon) – tour suggestions; donald is shut down, the owner is away for the weekend

One topic since 2026-11-13: **tour suggestions** ("Rundtour-Vorschläge"). Everything is on `main` or on a `wip/` branch;
nothing runs on donald any more. Read first: `docs/design/tour-suggestions.md` (current state: principles, the method,
the owner's taste rules, the parked planner plan) and `tools/suggest-poc/README.md`.

## Where it stands
- **POC, rebuilt 2026-11-14** (`tools/suggest-poc/`, on main): three levels (what a way is · what the place offers ·
  the person's character from the activity) and five axes (Natur, Ruhe, Weg, Form, Besonderes); time and ascent as
  ranges; no effort level, no pace; one method; `settings.json` (~130 numbers); `cases.py` = the owner's comments as
  checks (54 of 55 met). The state before the rebuild is the tag `suggest-poc-v1`.
- **Start it again on donald** (nothing is installed to start by itself):
  `systemd-run --user --unit=onpath-suggest-poc-page --working-directory=<checkout> --nice=19 --setenv=PATH="$PATH" $(which python3) tools/suggest-poc/serve.py`
  → `http://localhost:8790` (the unit needs PATH, otherwise `javac` for the route service is not found). Or plainly
  `python3 tools/suggest-poc/serve.py` in a terminal.
- **Data outside git on donald** (kept on disk, not in any repo): `~/onpath-suggest-poc/` (1.9 GB: the owner's 20
  comments `feedback.jsonl` – read with `tools/suggest-poc/feedback.py`; saved runs; downloaded map areas; the night's
  and the rebuild's pictures `night/`, `v2/`, `r10/`; AI region lists `region-ai/`; sketch sources `sketch/`; the
  road-test password `remote/token`), routing data `~/onpath-routing/segments/` (all Germany + northern Alps). The
  comments hold his start points – do not put that file into the public repo.

## Unfinished, on branches
| Branch | What | State |
| --- | --- | --- |
| `wip/donald-2026-11-14-suggest-next` | part 1 of the next step: river following (Rhine ride from Zons back, reliably), the Hilden bike ring through the green belts, loops outside the wished time range only as an exception | stopped after ~15 min when donald went down; compiles, **not run through `cases.py`**, do not merge as is |
| `wip/donald-2026-11-13-suggest-round7` | old-structure repair round, stopped earlier | superseded by the rebuild; keep only as a reference |
| `wip/donald-2026-11-14-planner-suggest-remote` | the planner framework: exchangeable mechanisms (`poc`, `ring`, `remote`, slot `core`), "Rundtour von hier" per sketch 2, the debug setting "Tourvorschläge (Test)" (`?debug`), all behind a switch that is off | tests green except four suites that are red on untouched main on donald too; **knows no ranges yet**, still sends effort / pace (the remote front ignores them); parked – deploy waits for the owner's word |
| `wip/donald-2026-11-13-planner-suggest` | the same framework before the rebase | behind, ignore |

## Next, in the owner's order
1. Finish part 1 (branch above): run `cases.py`, look at the Zons and Hilden bike pictures.
2. **Measure against official loops** (owner 2026-11-14: "nach offiziellen Wanderrouten gucken … ein Gebiet mit vielen
   … schauen, wo du landest … reichen Parameter, fehlt ein Schritt, oder die Sammlung programmatisch als Inspiration"):
   circular hiking relations from OpenStreetMap in one rich area (Eifel / Hunsrück / Sauerland), per loop: how much of
   it ours walks, how our five axes rate the official loop, what it has that ours lack; first settings only, then the
   missing step; official loops as candidates (the map areas would have to carry circular routes as ordered lines).
   Not started. The full brief is in this session's chat; the points above are its core.
3. Still weak after the rebuild: following a river; a first loop that meets its own way (2 of 20 wishes); bike loops
   with 500–800 m on a road; entry points (trailhead when the start is in a town) not built; MTB and the AI tips not
   run in the new version; "Wegbeschaffenheit" may be redundant with the activity.

## The owner's standing words on this topic
- Routes first; the planner deploy waits ("das merken wir uns für später") – build so it fits in later.
- Clean up, reduce history, robust and clear, minimise code by thinking, a cleanup look after every round; don't test
  to death, it is a POC.
- No tuning for one region: relative to the place, character from the activity, few principles.
- AI only as region tips per map area (middle model, 9–11 ct per area through the CLI) or as a judge in development;
  nothing of it is needed for the suggestions to work.
- The road test (service + test planner on donald, VPN) is paused: units stopped and disabled, code in
  `tools/suggest-poc/remote/`, his sheet `~/onpath-suggest-poc/remote/MORGEN.md`.

## Pages for the owner
Sketch 1 https://claude.ai/artifact/4vw94n6ag24ziSHRMSkftX · sketch 2 ("Rundtour von hier")
https://claude.ai/artifact/GE3STB3Dj1zFgYTiAK8gjV · the night's judgement https://claude.ai/artifact/XLLZc8KsLr51CABSVAKQF5 ·
the rebuild https://claude.ai/artifact/QB7sxGZWfvm72EmyF2PjA7
