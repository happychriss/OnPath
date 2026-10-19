# Map packages as grid cells, built on demand

Status: **design, decided by the owner 2026-10-19.** Reference for both sessions: the cloud session builds the
pipeline and server parts, the laptop session builds the app parts and sets up zo.
Related: `docs/map-pipeline.md` (today's region pipeline), `tools/hetzner/README.md` (today's region server),
**`docs/design/map-data-size.md`** (how to make the data smaller – file formats may change, so this design keeps the
cell format versioned and does not fix the file list).
Requirements touched: F-OFF-01 (download an area), F-OFF-02 (route corridor secured automatically), F-OFF-03
(size before download, resume, Wi-Fi only), F-OFF-04 (monthly updates, old data works until new is verified),
F-OFF-05 (list + delete + storage used), R-01 (offline works), R-02 (resume, no partial files, checksums), R-11
(storage full), R-12 (route outside downloaded data → straight line, offer download), R-16 (keep last working
version, roll back).

## 1. The idea in one picture

```
 phone                               doorman (always on)                 builder (only while building)
 ─────                               ───────────────────                 ─────────────────────────────
 user holds on map → "Start here"
   │ POST /v1/cells/request ───────► queue (SQLite), dedupe ──start──►  osmium cut → planetiler → overlays
   │                                  │                                  → pois → terrain → contours
   │ GET /v1/cells/status (poll) ◄────┤ progress per cell ◄──report───   → manifest + sha256
   │                                  │                                  │
   │ GET manifest, then files ◄───────┴──── storage (folder / S3) ◄─────┘ upload
   ▼
 verify sha256 → join into local store → detailed map + routing appear, route recalculates
```

- The world is cut into fixed **cells**. A cell is built the first time anyone needs it, then kept for everyone.
- The app ships an **overview map**, so a new place is never blank. Details come where the user *plans*.
- Same Docker setup on zo (now) and Hetzner (later); only settings differ.

## 2. Cells

| Item | Decision |
| --- | --- |
| Grid | Web-mercator tiles at **zoom 10** (the same grid as map tiles) |
| Size | 360°/1024 = 0.352° wide; ~27 × 27 km in the Alps (at 46.5° N), smaller further north |
| Id | `z10/x/y` in URLs, `10-x-y` in file and folder names. Example: `10-543-361` |
| Example | x 543, y 361 = bbox `10.8984,46.5589,11.2500,46.8001` (Meran) |
| Ownership rule | A cell **owns** every map tile at zoom ≥ 10 inside it, and every point with `west ≤ lon < east`, `south < lat ≤ north` (half-open, so a point on a border has exactly one owner) |
| Low zooms (z0–9) | Not in cells (a z9 tile spans 4 cells). They come from the **overview** (see §7) |

Bbox of a cell (standard tile formula): `lon = x/1024·360 − 180`, `lat = atan(sinh(π·(1 − 2·y/1024)))`.
App and server use one shared function with a unit test on the Meran example.

## 3. Build steps per cell

Measured on zo (i3-8130U, 4 threads, cell 10-543-361, first run incl. downloads): **9 min, peak RAM 2.3 GB**.
Planetiler (212 s) and POIs (198 s) read the whole 130 MB Trentino extract; cutting first should bring the cell
to **3–4 min (estimate, not yet measured)**.

| # | Step | How | Measured / expected |
| --- | --- | --- | --- |
| 0 | Source extract | Regional `.osm.pbf` covering the cell + buffer (reuse `tools/hetzner/server/onpath_maps/sources.py` for the choice). Refreshed monthly, all cells of one build round use the same OSM date | cached |
| 1 | **Cut** | `osmium extract -b <cell bbox + 5 km> -s smart -S types=multipolygon,route` → `cell.osm.pbf` (a few MB). `smart` keeps ways complete and multipolygons/route relations whole, so features crossing the edge are complete | ~10 s |
| 2 | Base | Planetiler on `cell.osm.pbf`, `--bounds=<exact cell bbox> --minzoom=10 --maxzoom=14`. Tiles align with the cell exactly; the tile buffer holds neighbour geometry from the 5 km buffer | was 212 s → ~40 s? |
| 3 | Overlays | `extract_overlays.py` on `cell.osm.pbf`, then clip per §4 | 63 s → less |
| 4 | POIs | `extract_pois.py` on `cell.osm.pbf`, keep only owned points (§4) | was 198 s → ~20 s? |
| 5 | Terrain | Terrarium z10–13 of the cell (exactly its tiles) | 7 s |
| 6 | Contours | From terrain of the cell **plus a 1-tile ring** (so lines meet the neighbour), output only the cell's tiles | 43 s |
| 7 | Manifest | Sizes, sha256, OSM date, format version (§6); upload; mark ready | <5 s |

Sizes of the example cell (format 1 = today's files): terrain 19 MB, base 6.1, paths 3.7, lowways 1.9, ways 1.6,
trails 1.6, routes 1.4, contours 1.1, trailnames 0.8, pois 0.6 → **38 MB unzipped, 28 MB zip**.
**Format 2** (proposed in map-data-size.md): terrain z10–12 WebP ≈ 1 MB, one vector `overlays.pmtiles` (paths, ways,
trails, routes, trail names, contours; tippecanoe) instead of the GeoJSON files, base without duplicate path lines →
**≈ 10 MB per cell**. Steps 3 and 6 then end in tiles too: tippecanoe runs on the buffered data and only the cell's
own z ≥ 10 tiles are kept. Contours are still built from the z13 AWS tiles.

Routing data is **not** in cells: BRouter `.rd5` (5° × 5°, ~200 MB) still comes from brouter.de, downloaded by the
phone (`SegmentDownloader`). An rd5 holds 25 separate 1° × 1° parts (5–13 MB each), so the phone can fetch only the
parts it needs by HTTP range (map-data-size.md §6); a cell touches 1–2 parts.

## 4. Seams: how neighbours join without gaps or duplicates

The rule: **build from the buffered extract, cut at the exact cell border at the very end, give every object one
owner.** Both neighbours then compute the same thing at the shared border. In **format 2** almost everything is
tiles at z ≥ 10, each tile owned by exactly one cell, so joining = copying tiles and there is nothing to clip; the
GeoJSON line rules below matter only for format 1 (the bridge) and for the cell's own POI points.

| Data | Problem at the border | Rule |
| --- | --- | --- |
| Vector tiles (base, contours, terrain) | none if tiles never overlap | Cell writes only its own z ≥ 10 tiles. Tile buffers come from the 5 km cut, so roads and areas run cleanly into the next tile. Disjoint tiles → no duplicates |
| Lines in GeoJSON (paths, ways, trails, lowways, routes) | A line crossing the border would be in both cells (drawn twice) or cut off | **Split each raw line at the border first** (insert the exact intersection point), **then** simplify/smooth each piece on its own (endpoints are kept by Douglas-Peucker and fixed for Chaikin). Keep only the pieces inside the cell. Both cells compute the same border point from the same raw segment → the halves meet exactly |
| Line attributes (`len`, `tier`, merged route lines) | Computed on half a line they would differ between cells | Compute attributes on the **whole** feature from the buffered extract (route relations are complete thanks to `-S types=route`), *then* split. Very long merged lines (> buffer) can still differ slightly – accepted, see open question Q7 |
| Line labels (trail names, route refs) | Each half may get its own label close to the border | MapLibre's collision usually drops one. Accepted for format 1; with overlays as vector tiles (format 2) this behaves like any tile border |
| Dashed / dotted lines (tracks, faint trails) | Dash pattern restarts at the cut | Barely visible, accepted |
| POIs (points) | A POI near the border must not appear twice | Owner by half-open rule (§2). Area POIs: representative point computed from the whole area, then owner rule. Bus-stop merge (40 m) is done on the buffered set, then owner rule on the merged point |
| Contours | Lines must meet | 1-tile terrain ring when building (§3 step 6) |

Different OSM dates in two neighbours (one refreshed, one not) can show a small step at the border until both are
refreshed. Accepted; refreshes of a used area happen together (§9).

## 5. Server: doorman + builder

Same Docker images everywhere (`docker compose`), switched by settings. On zo: code in `~/onpath` (git clone),
data in `~/onpath-data`. Public address through the owner's Cloudflare tunnel: **`maps.free-your-pictures.org`**
for now – in the app it is a **setting** (preset in dev builds), so a later move needs no app release.

| Setting | Values | Meaning |
| --- | --- | --- |
| `BUILDER_MODE` | `local` (zo, interim) | Builder container always running, waits on the queue |
| | `hetzner` (later) | Small always-on Hetzner doorman creates a builder server from a snapshot via the Hetzner Cloud API, attaches a Volume (source OSM extracts + Planetiler caches), deletes the server afterwards |
| `STORAGE` | `local` (zo: `~/onpath-data/store`) | Files served by the doorman (through the tunnel) |
| | `s3` (Hetzner Object Storage) | Files served from the bucket (public-read, immutable paths) |
| `APP_KEYS` | list | Keys accepted from the app (against abuse of a public URL) |
| `MAX_BUILDERS` | 1 | Never more than one builder |

Hetzner builder rules (phase 3):
- Hetzner bills **started hours** → keep the builder while the queue has work, and also idle until just before the
  hour ends (in case new requests come); delete it ~5 min before the next hour starts.
- **Hard stop 2 h** per builder, **max 1 builder**. Powered-off servers still bill → always **delete**, never stop.
- API token lives in a **separate, empty Hetzner project "OnPath-builders"** – it can't touch anything else.
- The builder pulls jobs from the doorman over HTTPS with a builder token, uploads to storage, reports done. It runs
  `git pull` of `main` at start (like today's worker), so pipeline fixes are used automatically.
- No GitHub Actions involved.

### Job queue and dedupe

One SQLite table on the doorman: `jobs(cell, format, state, priority, osm_date, requested_at, requests, attempts,
error)`.

| Situation when a cell is requested | What happens |
| --- | --- |
| Ready and fresh (OSM date < 35 days) | Answer `ready` at once, no job |
| Ready but old | Answer `ready` (old version usable now) **and** queue a refresh at low priority |
| Already queued or building | Join that job (`requests + 1`), no second job |
| Failed < 3 times | Queue again |
| Failed 3 times | Answer `failed` with a plain reason; owner sees it in the log; retried after the next code/source update |

Priority (high → low): the user's own point cell → close neighbours → route corridor → refresh. Inside one priority:
oldest request first. One cell at a time (zo has ~4 GB free RAM, a cell peaks at 2.3 GB).

## 6. Storage layout and manifest

```
store/
  cells/f1/10/543/361/                 f1 = format version 1
      2026-10-17/                      OSM date = version folder, immutable once written
          base.pmtiles terrain.pmtiles paths.geojson … manifest.json
      current.json                     {"version": "2026-10-17"}  – switched only after upload + verify
  overview/f1/…                        overview pack(s), same idea
  routing/  (not used now – rd5 from brouter.de)
```

Old version folders are deleted 7 days after `current.json` moved on (so a phone mid-download can finish).
Cells nobody requested for 12 months are deleted (storage is cheap; rebuilding is cheap too).

`manifest.json` of a cell:

```json
{
  "cell": "10-543-361",
  "format": 1,
  "bbox": [10.8984, 46.5589, 11.25, 46.8001],
  "osm_date": "2026-10-17",
  "built": "2026-10-18T03:12:44Z",
  "pipeline": "git:fb0cab3",
  "min_app": "0.8.0",
  "files": [
    {"name": "terrain.pmtiles", "size": 19034112, "sha256": "…", "url": "cells/f1/10/543/361/2026-10-17/terrain.pmtiles"},
    {"name": "base.pmtiles",    "size": 6396314,  "sha256": "…", "url": "…"},
    {"name": "paths.geojson",   "size": 3879731,  "sha256": "…", "url": "…"}
  ],
  "total_size": 39845888,
  "attribution": ["© OpenStreetMap contributors", "© OpenMapTiles", "Terrain: Mapzen / AWS Open Data"]
}
```

`format` lets the file set change (map-data-size.md) without breaking old apps: the app sends the formats it
understands, the server builds/serves the newest of those. An old format is built only while app versions that need
it are still in use.

## 7. Overview map (shipped in the app)

Never a blank screen (R-01): the APK carries a small overview – base z0–8 (roads, places, water, borders) and
hillshade z5–9 for the Alps first, later Europe. At zoom 9 the overview is over-zoomed; cells start at zoom 10.
Size and exact zoom range: see map-data-size.md. Where no cell is on the phone, the map shows a light hint:
**"Overview only – set a point and the detailed map follows."**

## 8. API between app and doorman

Base `https://<server setting>/v1`. Every call carries `X-OnPath-Key: <app key>` and `X-OnPath-App: <version>`.
Wrong key → `401`. Too many requests → `429` with `Retry-After`. Later (payment) an `Authorization: Bearer <pass>`
header joins; the endpoints stay the same.

| Endpoint | Purpose |
| --- | --- |
| `POST /v1/cells/request` | Ask for cells (own point, neighbours, corridor, refresh). Returns status per cell |
| `GET /v1/cells/status?ids=10-543-361,10-544-361` | Progress per cell |
| `GET /v1/cells/10/543/361/manifest?format=1` | Current manifest (only when ready) |
| `GET /v1/files/<url from manifest>` | File download; supports HTTP `Range` (resume, R-02). Immutable → cacheable (Cloudflare); with S3 the manifest URLs point to the bucket directly |
| `GET /v1/health` | `ok` + queue length (Settings → Diagnostics) |

Request:

```json
POST /v1/cells/request
{"cells": ["10-543-361", "10-544-361"], "reason": "point", "formats": [1]}
```

`reason`: `point` (own cell, highest), `near` (neighbours), `corridor`, `refresh`.

Response (also the shape of `status`):

```json
{
  "cells": [
    {"id": "10-543-361", "state": "ready", "osm_date": "2026-10-17", "size": 28311552},
    {"id": "10-544-361", "state": "building", "step": "base", "progress": 0.4, "eta_s": 150},
    {"id": "10-545-361", "state": "queued", "position": 2, "eta_s": 520},
    {"id": "10-546-361", "state": "failed", "reason": "Source data not available for this area yet"}
  ],
  "poll_s": 15
}
```

**Polling:** every `poll_s` seconds (server decides; 15 s while something builds, 60 s when only queued) while the
app is in the foreground and the card is showing. In the background: no polling; on the next app start the app
asks again (requests are idempotent). Stop polling when all cells are `ready` or `failed`.

## 9. On the phone

### Trigger (agreed UX)
- Search only moves the map + orange ring; scrolling only looks. **Nothing downloads from looking.**
- **Setting a point** (hold on map / hold on ring → Start here / Go here) where no cell is on the phone requests
  that cell (`point`) and its **close neighbours** (`near`, see Q1).
- When the route is computed, the **corridor cells** (cells within ~10 km of the route, F-OFF-02) are requested.

### The card (persistent, in the plan sheet – no self-dismissing messages)

```
┌─────────────────────────────────────────────────────┐
│ Preparing map for this area – about 6 min           │
│ Because you planned here.                           │
│ ● Meran (your point)      ready – downloading 60 %  │
│ ◐ East of Meran           building                   │
│ ○ 2 more cells            waiting                    │
│ Route is a straight line until the map is ready.    │
└─────────────────────────────────────────────────────┘
```
Own cell first. Until routing data covers the route, the route is drawn as a straight line and marked (R-12); when
the rd5 and cell arrive the route **recalculates by itself** (no question needed – nothing the user decided changes).
The card stays until all cells are on the phone or the user closes it.

### Mobile data (proposal)
Own point cell (~28 MB today, ≈ 10 MB in format 2): automatic. Neighbours / corridor on mobile data: the card asks once with the size
("3 more cells, 85 MB – download now or on Wi-Fi?"). Settings: "Wi-Fi only" (F-OFF-03).

### Download, verify, activate
1. Download each file to `cells/10-543-361/<osm_date>.part/` with Range resume (R-02).
2. Check size + sha256 of every file against the manifest. Free space checked before (R-11).
3. Rename `.part` → final folder, then add the cell to the local store (below). Only complete, verified cells are ever
   used. The previous version stays until the new one is active and the map loaded fine; then it is deleted (R-16,
   F-OFF-04).

### Joining cells into the local store – open (see map-data-size.md)
Many cells as separate MapLibre sources would multiply the layers (too slow). Options:

| Option | How | For / against |
| --- | --- | --- |
| A. Merge tiles into one local tile store per layer (e.g. one MBTiles/SQLite file, MapLibre `mbtiles://`) | Cells are disjoint at z ≥ 10, so merging = insert tiles; removing a cell = delete its tiles | Clean, fast, one source per layer; needs MapLibre Native MBTiles support checked on Android, and **overlays as vector tiles** |
| B. Rewrite one PMTiles per layer when a cell is added | Simple reading | Rewrites large files on each add |
| C. Concatenate GeoJSON overlays per kind (format 1 only) | Works with today's files | Large in-memory GeoJSON, rebuild on each add; fine as a bridge |

**Recommendation:** A (or B if MBTiles is not usable on Android), together with format 2; C only as a bridge while
format 1 is in use. A short spike decides (map-data-size.md §7).

### Refresh and storage
- Downloaded cells are kept. The app lists them (F-OFF-05: area, size, date, delete; total storage).
- **Monthly refresh on Wi-Fi, only for cells used** (planned/navigated in) in the last 6 months: the app asks for the
  manifest; newer → download in the background (`refresh`), activate after verification. Old data keeps working.
- Server side: a refresh request of an old cell queues its rebuild, so used areas stay fresh and unused ones cost
  nothing.

## 10. Failure cases

| Case | What the user sees | What happens |
| --- | --- | --- |
| Offline when setting a point | Card: "No connection – the detailed map for this area will load when you are online." Overview + straight-line route | Request stored; sent on next connection |
| Server down / unreachable | Card: "Map server not reachable – trying again." (stays) | Retry with backoff (1, 2, 5, 10 min); diagnostics entry (INFO) |
| Build fails | Card line: "This area could not be prepared (reason). Planning still works with a straight line." | Server retries up to 3×; `Problems.warn` on the phone |
| Download breaks halfway | Card shows the % and "continues when connected" | Range resume; `.part` never used (R-02) |
| Checksum wrong | Card: "Download damaged – loading again." | Delete `.part`, retry once, then failed |
| Storage full | Card: "Not enough space (needs 85 MB). Delete old areas in Settings → Map data." | Nothing written (R-11) |
| New version broken | Nothing visible | Keep and roll back to the previous version (R-16) |
| Wrong / missing app key | Card: "Map server refused the request – update the app." | `Problems.error` |
| Builder stuck (Hetzner) | Card keeps "building", ETA grows | Hard stop at 2 h, server deleted, job back in queue |

## 11. Costs

| Item | zo (phase 1–2) | Hetzner (phase 3) |
| --- | --- | --- |
| Doorman | 0 € (zo runs anyway) | smallest shared server, ~4–5 €/month |
| Builder | 0 € | 8 GB server for the hours it runs: a few cents per hour, several cells per hour |
| Volume (OSM extracts + Planetiler caches, ~30–50 GB) | zo disk | ~2–3 €/month |
| Storage + traffic | zo disk, tunnel | Object Storage ~5 €/month incl. 1 TB storage + 1 TB egress (≈ 35 000 cell downloads) |
| Snapshot of builder image | – | < 1 €/month |
| **Total** | **~0 €** | **~12–15 €/month** at low use |

Prices: check the Hetzner console (`onpath_hetzner.py plan` shows current ones); 2026 prices rose.

## 12. Migration zo → Hetzner

1. Same compose files; on Hetzner set `BUILDER_MODE=hetzner`, `STORAGE=s3`.
2. Copy `~/onpath-data/store` to the bucket (`rclone sync`), queue DB along.
3. Point `maps.free-your-pictures.org` at the Hetzner doorman (Cloudflare tunnel or DNS) – or change the address
   setting. Manifests contain relative URLs, so nothing in the app changes.
4. Keep zo running read-only for a week as fallback, then stop it.

## 13. Payment later (do not block it)

- **Region** = a named set of cells (e.g. "South Tyrol" = 18 cells). A **pass** gives downloads + updates for that
  region in one month. Downloaded maps keep working after the month.
- Free tier: overview map + a small home area (a few cells).
- Google Play Billing on the phone → pass token → `Authorization` header; doorman checks it per request. Cells,
  manifests and the API do not change.
- ODbL: we charge for the **service** (building, hosting, updates), not for the data; attribution stays.

## 14. Who builds what

| Part | Owner |
| --- | --- |
| Cell math (id ↔ bbox) as Python + Kotlin, shared test values | cloud (Python), laptop (Kotlin) |
| `build_cell.sh` (cut, clip, owner rule, manifest), contours ring, overview pack | cloud |
| Doorman (API, queue, storage modes), builder image, Hetzner mode, compose files | cloud |
| zo setup (clone, compose up, Cloudflare tunnel route, app keys), measurements on zo | laptop (with the owner) |
| App: server setting + key, request/status client, card, trigger on Start/Go here, corridor request | laptop |
| App: download/verify/activate, local store join, overview hint, cells list in Settings | laptop (store format agreed with cloud via map-data-size.md) |

## 15. Phases

| Phase | Goal | Done when |
| --- | --- | --- |
| **1. zo + manual test** | `build_cell.sh` with osmium cut + clipping; doorman + builder in Docker on zo; API reachable via the tunnel | Two neighbouring cells built on zo, measured (target 3–4 min/cell), pushed to the phone by hand, **no seam visible** at the border (headless render + phone) |
| **2. App trigger + card** | Overview in the APK, trigger on setting a point, card, download/verify/activate, corridor cells, monthly refresh | Owner plans in a new valley with an empty phone and gets the detailed map + route without touching Settings |
| **3. Hetzner** | Doorman on Hetzner, builder from snapshot, Object Storage, migration | zo switched off; cost per month as in §11 |

## 16. Owner decisions (2026-10-19: "all as recommended")

All questions below are decided as recommended (owner, 2026-10-19). The "Recommendation" column is now the decision.

| # | Question | Recommendation |
| --- | --- | --- |
| Q1 | Request all 8 neighbours or only close ones? | Only neighbours within ~5 km of the point (usually 0–3). Saves 20–30 min of building on zo; the corridor covers the rest |
| Q2 | Mobile data: own cell automatic, rest asks with size? | Yes |
| Q3 | Buffer for the osmium cut | 5 km (complete features at the edge); larger only if Q7 shows problems |
| Q4 | Overview in the APK: Alps only or Europe, how many MB? | Alps first; decide size with map-data-size.md |
| Q5 | Refresh: monthly for cells used in the last 6 months? | Yes, Wi-Fi only |
| Q6 | Delete server cells unused for 12 months? | Yes (rebuild takes minutes) |
| Q7 | Accept small differences of very long merged lines (route bands, trail-name tiers) at cell borders? | Yes for format 1; vector-tile overlays (map-data-size.md) remove most of it |
| Q8 | App key: one key compiled into the app is easy to extract. Enough for now? | Yes, plus rate limit per IP; real protection comes with the pass token |
| Q9 | Keep the current region zips (`south-tyrol`) working next to cells? | Yes until phase 2 is done, then drop them |
| Q10 | Routing: rd5 from brouter.de – fetch only the 1° parts (5–13 MB) by HTTP range, or whole 200 MB files? | 1° parts (map-data-size.md §6); own part automatic, others ask with size like neighbours |
| Q11 | Go straight to format 2 (≈ 10 MB/cell) for the first app release with cells, skipping GeoJSON cells? | Yes – phase 1 may test with format 1, but the app ships cells only in format 2 |
