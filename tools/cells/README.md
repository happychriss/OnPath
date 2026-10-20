# tools/cells – build one map cell (phase 1)

Design: `docs/design/map-cells.md` (cells = z10 tiles, owner rule, manifest). Measurements and seam findings:
section "Phase 1 results" there. This folder is additive – `tools/build_region.sh` stays the region / zip build;
`build_cell.sh` calls the same tools (Planetiler, `extract_overlays.py`, `extract_pois.py`, `build_terrain.py`,
`build_contours.py`) with cell-specific bboxes and clips the results.

| File | What |
| --- | --- |
| `Dockerfile` | Builder image: JRE 21, osmium-tool, Python deps (same versions as zo's venv), Planetiler jar, non-root. Code is **not** baked in – the repo is mounted at `/onpath` (read-only), data at `/data` |
| `run.sh Z X Y` | `docker run` with limits (`--memory=3g --memory-swap=3g --cpus=3 --cpu-shares=512`) |
| `build_cell.sh Z X Y` | The build inside the container (steps, timings, publish) – see its header |
| `cells.py` | Cell math (bbox, buffer, owner rule), pmtiles owned-tile filter, GeoJSON clip, POI id remap, manifest; `cells.py selftest` |
| `seam_check.py WEST EAST LON [--png P]` | Seam report for two side-by-side cells + PNG renders across the border |

## Use

```bash
cd ~/onpath && git pull
docker build -t onpath-cell-builder -f tools/cells/Dockerfile tools/cells
tools/cells/run.sh 10 543 361          # Meran; ~/onpath-data is mounted as /data
```

Input: `SRC_PBF` (default `/data/work/osm/trentino_alto_adige-latest.osm.pbf`), Planetiler sources in
`/data/work/planetiler-sources` (downloaded when missing). Output:

```
~/onpath-data/cells/f1/10/543/361/<osm_date>/   base, terrain, contours .pmtiles, *.geojson, manifest.json, build.json
~/onpath-data/cells/f1/10/543/361/current.json  {"version": "<osm_date>"} – switched after the folder is complete
```

`build.json` (not in the manifest) has wall time and peak RAM per step. `KEEP_WORK=1` keeps
`/data/work/cells/10-543-361/` (cut `.osm.pbf`, raw unclipped outputs in `raw/`, logs) for `seam_check.py`.

Seam check (inside the image):
```bash
docker run --rm -v ~/onpath-data:/data -v ~/onpath:/onpath:ro onpath-cell-builder python3 \
  /onpath/tools/cells/seam_check.py /data/cells/f1/10/543/361/<date> /data/cells/f1/10/544/361/<date> 11.25 --png /data/seam
```

## Notes
- **Memory of the cut:** osmium's `smart` strategy keeps bit sets over the whole OSM id range (node ids ~13e9):
  ~4 GB even for a 14 MB cell extract – it was OOM-killed at 3 GB on zo. `build_cell.sh` therefore renumbers the
  source once (`osmium renumber -i`, ~10 s, ~300 MB, cached next to the source until it changes); the cut then needs
  ~170 MB. POI ids are mapped back to OSM ids (`cells.py remap-ids`).
- **zo and Docker networking:** every container on the default bridge adds a `veth` interface at start and removes it
  at stop. The owner's Docbox mDNS daemon (`dnssd`, `cdclient_daemon.rb`) crashes on the vanished interface and
  systemd restarts the Docbox services (seen 2026-10-20 at each `docker build` step and run). **Fix (2026-10-20): `run.sh`
  uses `--network host` by default** – no veth per run, Docbox restart counters stayed unchanged over two builds.
  Never `docker build` on zo on the default bridge (use `docker build --network host`).
- Low zooms (z < 10) are not in a cell (overview map, map-cells.md §7): a cell alone shows nothing below z10.
- `DOCKER_ARGS="--dns 1.1.1.1"` where the Docker bridge has no working DNS (the laptop).
