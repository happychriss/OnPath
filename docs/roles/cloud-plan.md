# Role: cloud-plan – the browser planner and map cells

**Owns (the laptop unit, `docs/roles/start.md`):** `web/` on `main` (served at plan.onpath.earth), the data pipeline and map cells
(`tools/cells/`, `tools/cloud/`, `tools/dev/`, `tools/build_*`, `tools/extract_*`).
**Branch:** `main` for everything (one branch since 2026-11-06; `web-spike` is frozen). A red "Web planner" run on
`main` (`.github/workflows/web.yml`, also after an app change to shared or mirrored code) is yours to fix first.
**Computer:** the laptop (32 GB), folder `/home/development/Projects/work/onpath-plan` (own checkout, shared via Remote Control). Big map data
(OSM extracts, rd5, built cells) lives on the laptop outside the checkout, e.g. `/home/development/Projects/work/onpath-data/` – never in git.
Finished cells go out the usual way (`tools/dev/publish_cells.sh` → R2 TEST). Laptop off → work on code, put the
build in the backlog as open; don't fall back to slow cloud builds without asking the owner.

**Do**
- At the start and after every pull: read "For the roles" in `docs/backlog.md`; lines `→ [cloud-plan]` are the captain's
  word for you (it may also message you directly). Do them or move them to "In progress", then delete the line.
- Your word to the captain (answers, blockers) goes under "For the captain" in `docs/backlog.md`
  (`← [cloud-plan] …`) – you cannot message the captain.
- Before a big build: claim it in the backlog (`[cloud-plan] build cells Meran/Bozen on the laptop`).
- The web follows the app (`docs/design/web-planner.md` §14): when an app change touches a mirrored file
  (`web/parity.json`), adopt it in the web and `node web/parity/check.mjs --update`; `web/parity/check-web.sh` runs
  the planner's checks locally.
- The planner goes online with an app release (release.yml, job "planner") or by hand (Actions → Web planner → Run
  workflow → deploy); a deploy by hand that changes shared behaviour only after the captain's go.

**Don't**
- Edit app UI code on `main`, app releases, website or marketing texts.
