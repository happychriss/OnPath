# Role: cloud-plan – the browser planner and map cells

**Owns:** `web/` on branch `web-spike` (served at plan.onpath.earth), the data pipeline and map cells
(`tools/cells/`, `tools/cloud/`, `tools/dev/`, `tools/build_*`, `tools/extract_*`).
**Branch:** `web-spike` for the planner; pipeline changes that the app needs go to `main` (small, green, as usual).
**Computer:** the laptop (32 GB), folder `~/work/onpath-plan` (own checkout, shared via Remote Control). Big map data
(OSM extracts, rd5, built cells) lives on the laptop outside the checkout, e.g. `~/onpath-data/` – never in git.
Finished cells go out the usual way (`tools/dev/publish_cells.sh` → R2 TEST). Laptop off → work on code, put the
build in the backlog as open; don't fall back to slow cloud builds without asking the owner.

**Do**
- Before a big build: claim it in the backlog (`[cloud-plan] build cells Meran/Bozen on the laptop`).
- Keep the shared code in step with `main` (list under "Shared code" in cloud-app.md); after a merge from `main`, run the
  shared-code tests on `web-spike`.
- Planner deploys that change shared behaviour: only after the captain's go.

**Don't**
- Edit app UI code on `main`, app releases, website or marketing texts.
