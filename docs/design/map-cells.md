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

> **Hosting decided 2026-10-20 (owner): Cloudflare Worker (doorman, D1 queue) + R2 (cells) + Google Cloud Run
> Jobs (builder) – see `docs/design/map-hosting.md`.** It replaces the Hetzner mode below (`BUILDER_MODE=hetzner`,
> `STORAGE=s3` on Hetzner, the Hetzner builder rules); zo keeps `BUILDER_MODE=local` as the test machine. Queue,
> dedupe and priorities below are implemented as described, in D1.

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

### Trigger – when the map is fetched (owner, updated 2026-10-20)
Rule: **the map comes where you plan or where you are** – a point you set, the route, or your own position.
Searching, scrolling and zooming ("just looking") never download (owner 2026-10-20: "alles außer T2").

| # | Situation | What is requested | Since |
|---|---|---|---|
| T1 | **Setting a point** (hold on map / hold on ring → Start here / Go here, POI → Go here) where no cell is on the phone | that cell (`point`) + close neighbours (`near`, Q1) | 2026-10-19 |
| ~~T2~~ | Search result – the orange ring is placed (search, or a place shared from Google Maps / geo: link) | **nothing** – rejected by the owner 2026-10-20; the ring only shows the place, the map comes when a point is set there (T1) | rejected |
| T3 | **App opened in Plan mode at a new place** – own GPS position has no cell on the phone (first start, holiday, travel) | own cell (`point`) + close neighbours | **2026-10-20 (owner)** |
| T4 | **Route computed** | corridor cells (within ~10 km of the route, F-OFF-02) | 2026-10-19 |
| T5 | **Navigate / recording** and the own position moves towards the edge of the cells on the phone (e.g. a tour leaves the planned corridor) | the next cell ahead (`near`) | **2026-10-20 (owner)** |
| T6 | **Refresh** – a cell used in the last 6 months is older than the monthly build | new version (`refresh`, Wi-Fi only, Q5) | 2026-10-19 |
| – | Searching (T2), scrolling / zooming the map, looking at a POI, opening a saved tour that is fully covered | nothing | – |

Notes:
- T3/T5 are "own point" requests, so the same data rule applies as for T1 (own cell automatic, more asks with size on
  mobile data, Q2). A cell already on the phone or queued is never requested again.
- T3 runs once per app start / return to the app, only with a usable fix (accuracy ≤ ~500 m; a network fix is enough
  to pick the cell). No fix → nothing, and the overview map stays.
- The card says *why*: "Because you planned here" (T1/T4), "Because you are here" (T3/T5).
- Not built yet (phase 2, laptop side: "App: trigger on Start/Go here" in §14) – T3 and T5 must be added to that work.

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

### Joining cells into the local store – decided 2026-10-21: B (PMTiles generations), see §19
Many cells as separate MapLibre sources would multiply the layers (too slow). Options considered:

| Option | How | For / against |
| --- | --- | --- |
| A. Merge tiles into one local tile store per layer (e.g. one MBTiles/SQLite file, MapLibre `mbtiles://`) | Cells are disjoint at z ≥ 10, so merging = insert tiles; removing a cell = delete its tiles | Clean, fast, one source per layer; needs MapLibre Native MBTiles support checked on Android, and **overlays as vector tiles** |
| B. Rewrite one PMTiles per layer when a cell is added | Simple reading | Rewrites large files on each add |
| C. Concatenate GeoJSON overlays per kind (format 1 only) | Works with today's files | Large in-memory GeoJSON, rebuild on each add; fine as a bridge |

**Decision (2026-10-21):** B, written as a new store generation per change – robust offline, no second SQLite
library writing next to MapLibre's, testable on the JVM; reasons and measurements in §19.1. C is not needed (cells
ship in format 2 only, Q11).

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

> Superseded for phase 3 by `docs/design/map-hosting.md` §3 (Cloudflare + Cloud Run: ≈ 0–1 €/month at test use).

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

> **Superseded:** the target is Cloudflare + Google Cloud (`docs/design/map-hosting.md`, owner setup §6). Cells
> built on zo can be copied into the R2 bucket with the same keys (`cells/f1/…`), e.g. `rclone sync` with the R2
> S3 endpoint; the doorman then needs their rows (or a refresh request) in D1.

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
| App: server setting + key, request/status client, card, triggers T1, T3–T6 (§9: point, own position on open, corridor, position near the edge while on tour, refresh), corridor request | laptop |
| App: download/verify/activate, local store join, overview hint, cells list in Settings | laptop (store format agreed with cloud via map-data-size.md) |

## 15. Phases

| Phase | Goal | Done when |
| --- | --- | --- |
| **1. zo + manual test** | `build_cell.sh` with osmium cut + clipping; doorman + builder in Docker on zo; API reachable via the tunnel | Two neighbouring cells built on zo, measured (target 3–4 min/cell), pushed to the phone by hand, **no seam visible** at the border (headless render + phone) |
| **2. App trigger + card** | Overview in the APK, trigger on setting a point, card, download/verify/activate, corridor cells, monthly refresh | Owner plans in a new valley with an empty phone and gets the detailed map + route without touching Settings |
| **3. Cloud** (was Hetzner) | Doorman = Cloudflare Worker + D1, cells in R2, builder = Cloud Run Job (`docs/design/map-hosting.md`) | test + production deployed by GitHub Actions; cost as in map-hosting.md §3 |

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

## 17. Phase 1 results on zo (2026-10-20)

Built with `tools/cells/run.sh` (Docker `onpath-cell-builder`, `--memory 3g --cpus 3 --cpu-shares 512 --network host`),
format 1, source = Trentino extract of 2026-10-19, 5 km buffer.

| | Cell 10/543/361 (Meran) | Cell 10/544/361 (Bozen side) |
| --- | --- | --- |
| Wall time | **2 min 42 s** (region-style build before: 9 min) | 2 min 36 s |
| Biggest steps | base 63 s (peak 0.9 GB), contours 24 s, overlays 23 s, pois 19 s, renumber 10 s, terrain 9 s, cut 5 s | similar |
| Size (format 1) | 28 MB | 23 MB |

- **Cut memory:** `osmium extract -s smart` needs ~4 GB with today's node ids (~13e9) → over the 3 GB limit; the
  source is renumbered once first (`osmium renumber`, ~10 s, cached) → cut 5 s / 127 MB.
- **Seams (`tools/cells/seam_check.py`, border lon 11.25):** 0 lines crossing the border, 0 features in both cells,
  0 POIs in both cells, 0 pmtiles tiles (z ≥ 10) in both cells; line ends on the border pair up (paths 70/73, trails
  27/29, ways 44/46, routes 57/59 – the few unpaired ends are lines that end at the border in OSM or are merged
  differently); visual check of the lines across the border: they join.
- **zo impact:** load stayed low, Immich/Forgejo healthy. Docker containers on the default bridge made the owner's
  Docbox mDNS daemon crash and systemd restart the Docbox services (converter 7×, scanner 2× on 2026-10-20 during the
  first tests) → `--network host` is now the default; no further restarts over two builds.
- **Next:** doorman (API + queue) as a second container on zo, then `maps.free-your-pictures.org` in the Cloudflare
  tunnel (owner-approved step), then format 2 (map-data-size.md).

## 18. Format 2 cells (2026-10-20, laptop)

`tools/cells/build_cell.sh` builds **format 2 by default** (`FORMAT=2`, `FORMAT=1` still works; output under
`cells/f<FORMAT>/…`). File set and how each file is made: `tools/cells/README.md` "Format 1 and 2". In short:
trimmed base (Planetiler `--exclude_layers … --languages=de` + `trim_base.sh`), **one `overlays.pmtiles`**
(`extract_overlays.py --tiles --smooth` on the buffered cut → tippecanoe with `--clip <buffered bbox>` → only the
cell's own z ≥ 10 tiles), WebP terrain z10–12 (`build_terrain.py --cell`), contours from the AWS z12/z13 PNGs
(shared `TERRARIUM_CACHE`), `pois.geojson` (owner rule), **`addresses/10-X-Y.sqlite`** (`extract_addresses.py
--owner`: streets / places by the half-open owner rule; municipalities from whole boundaries – the cut now also
completes `boundary` relations). Manifest `format: 2`; files in sub folders are listed with their path
(`addresses/10-543-361.sqlite`), `job_build.py` uploads them too.

**Decision: contours stay a separate `contours.pmtiles`** (not joined into overlays via `build_overlays.sh --extra`):
same file and style source as the region package, optional on its own (OptionalSources strips it), and joining
would not make the cell smaller. Routes' z9 tiles are dropped like every z < 10 tile (the overview's job, §7).

Built locally (laptop, Docker image with tippecanoe built from source, `--memory 3g --cpus 3`), Trentino extract of
2026-10-19, 5 km buffer, warm caches (Planetiler sources, part of the AWS tiles):

| | 10/543/361 f1 | 10/543/361 **f2** | 10/544/361 f1 | 10/544/361 **f2** |
| --- | --- | --- | --- | --- |
| Wall time | 165 s | **129 s** | 132 s | **137 s** |
| Peak RAM (one step) | 0.92 GB (base) | 0.74 GB (base) | 0.86 GB | 0.84 GB |
| base | 5.14 MB | 3.56 | 3.58 | 2.63 |
| overlays | 11.1 MB (6 GeoJSON) | 2.77 (`overlays.pmtiles`) | 7.95 | 2.25 |
| terrain | 10.79 | 1.02 | 10.79 | 1.01 |
| contours | 0.69 | 0.69 | 0.63 | 0.63 |
| pois | 0.63 | 0.63 | 0.26 | 0.26 |
| addresses | – | 0.63 | – | 0.30 |
| **Total** | **28.3 MB** | **9.3 MB** | **23.2 MB** | **7.1 MB** |

Format 2 steps (10/543/361): cut 3 s, base 51 s, base-trim 3 s, overlays 13 s + tippecanoe 8 s, pois 8 s,
addresses 8 s, terrain 24 s (partly AWS downloads), contours 9 s. A cell is ~3× smaller than format 1 and as fast.

**Seams (`seam_check.py`, border lon 11.25, format 2):** tiles held by both cells: 0 in base, terrain, contours and
overlays. Lines crossing the shared tile edge at z14 (west cell's tile vs east cell's tile): overlays paths 79/79
joined, trails 28/28, ways 49/49, routes 64/64, all with equal attributes; base transportation 24/24, waterway 15/15.
POIs in both cells 0; house numbers in both cells 0; 83 street names in both cells (streets crossing the border: one
row per cell, the app merges the same name within 2 km). Render of the overlay z14 tiles across the border: lines
meet. Contours (z13): 323 of 412 crossings within 3/4096 tile units, all 412 within 40 units – the same offset as at
any internal tile edge of one cell (339 of 449 within 3 units there): contour lines are traced per tile, not a seam
effect of cells. The format 1 check still passes unchanged (0 crossing lines, 0 duplicates).

Test region package in format 2 (`tools/build_region.sh --format 2`, bbox 10.70,46.30,11.60,46.85, OSM 2026-10-17):
base 21.1 MB, overlays 14.2, terrain 8.7, contours 4.1, pois 2.6, addresses 2.5 → **53 MB, zip 50 MB** (format 1, older build in
`onpath-build/out/`: ~125 MB without addresses). `build_region.sh` has one `--format` switch for every file now (terrain and overlays branches merged).

The cloud builds and serves format 2 only since 2026-10-21 (first cloud builds of both cells: identical sizes and
seam check, map-hosting.md §7b). Open: the builder image on zo must be rebuilt (tippecanoe). App download + store: §19.

## 19. App side: cell store, downloads, API for the UI (2026-10-21, laptop, step 8)

Code: `app/src/main/java/com/onpath/app/cells/`. No UI beyond a debug block (Settings → Diagnostics → "Map cells
(debug)": installed cells with version + size, Delete, cells in progress, "Request cell at map centre"). The triggers
(T1, T3–T6), the card, the overview hint and the Settings list are built on the API below.

### 19.1 Store decision: one PMTiles per layer, rewritten as a new generation (option B)

| | A. MBTiles (`mbtiles://`) | **B. PMTiles generations (chosen)** |
| --- | --- | --- |
| MapLibre 13.7.1 Android | `MBTilesFileSource` is in the native library (absolute paths only) – never used by us, untested on the phone | `pmtiles://file://` – every map of the app uses it today |
| New tiles visible | MapLibre keeps its own SQLite handle and caches tiles / TileJSON (bounds from the metadata table) → a style reload is needed anyway | new generation = new paths → a style reload shows them; no app restart (unlike replacing a file in place, which needs a restart because MapLibre keeps the PMTiles index per URL) |
| Crash safety | SQLite transaction | write `gNNNNNN.tmp`, verify every file, one atomic folder rename; the previous generation stays until the map loaded the new one |
| Concurrency | **our writes (Android's SQLite) and MapLibre's reads (its own bundled SQLite) on one file in one process** – two SQLite libraries in one process break each other's POSIX locks (sqlite.org/howtocorrupt.html §2.2) | MapLibre only reads finished, never-changing files |
| JVM tests | needs sqlite-jdbc + a writer anyway | pure Kotlin (`PmTiles.kt`), round trips + real cells tested |
| Cost | insert/delete only the cell's tiles | full rewrite per change: 2 real cells 0.4 s, 2 cells + the 53 MB region package 1 s on the laptop (phone ≈ 3–5×); cells change rarely |

Rewriting is cheap because a cell holds only its own z ≥ 10 tiles (§4, ~340 tiles per layer): joining = copying
tiles, no feature merging. The writer keeps tile bytes unchanged, stores identical tiles of one input once, writes
clustered archives with leaf directories when the root would exceed 16 KiB.

### 19.2 Layout and rules

```
<dataDir>/cells/store/g000007/   base.pmtiles overlays.pmtiles terrain.pmtiles contours.pmtiles pois.geojson store.json
<dataDir>/cells/download/10-543-361/2026-10-19.part/   downloading (Range resume, never read by the map)
<dataDir>/cells/download/10-543-361/2026-10-19/        complete, size + sha256 verified, waiting to be joined
<dataDir>/cells/requests.json   queue (survives restarts)      <dataDir>/cells/usage.json   last use per cell (T6)
<dataDir>/addresses/10-543-361.sqlite   the cell's addresses (AddressSearch already reads every file there)
```

- **Map folder** (`DataFiles.mapDir()`): the current generation when it holds ≥ 1 cell, else the data folder exactly as
  before. `StyleLoader` replaces `{DATA_DIR}` with it (style overrides still come from `<dataDir>/styles`), the
  missing-data check and the search index read it. Source ids stay `base`, `overlays`, `terrain`, `contours`, `pois` →
  the format-2 style works unchanged; `OptionalSources` still strips a missing optional file.
- **Ownership** decides where every tile / POI comes from: a tile at z ≥ 10 inside an installed cell, and a POI inside
  it (half-open rule §2), come only from that cell; everything else from the region package.
- **Region package + cells:** a **format-2** package in the data folder is joined as the bottom layer (fills areas
  without cells and z0–9); cells override it where both exist. A new import → `regionChanged()` → joined again.
  A **format-1** package is not joined (GeoJSON overlays, other terrain encoding – mixing would break the style):
  with the first cell the map shows cells only (+ overview). Without cells, both formats work as before.
- **POIs:** one merged `pois.geojson` per generation (same ownership rule, streamed) – the style's single geojson
  source, POI tap and search stay unchanged. Chosen over one file per cell (would need a source per cell).
- **Generations:** number = highest folder + 1; current = highest complete one with a readable `store.json`
  (`.tmp` / `.bad` / unreadable folders are ignored and cleaned). After the map loaded a generation
  (`confirmLoaded`), older ones are deleted; if MapLibre fails to load it, `rollback` sets it aside and the previous
  becomes current (R-16) – its newly added cells must then be loaded again.
- **Space (R-11):** before a download: free ≥ 2 × cell size + 32 MB; before writing a generation: free ≥ sum of its
  inputs + 32 MB. Otherwise nothing is written and the cell shows "Not enough space (needs N MB) …".
- The region package's files exist twice while cells are installed (data folder + store) – accepted, region packages
  are phased out (Q9).

### 19.3 Download flow (per cell, one worker coroutine)

request (`POST /v1/cells/request`, once per app run, grouped by reason) → poll `GET /v1/cells/status` every `poll_s`
**only while the app is in the foreground** (no WorkManager – not a dependency; the queue continues on the next start)
→ `ready` and allowed by the data rule → `GET …/manifest?format=2` (checks cell id, format 2, `min_app`) → each file
to `<name>.part` with `Range` resume → size + sha256 (wrong → deleted, one retry, then failed) → folder renamed from
`.part` → joined into a new generation → addresses moved → download folder deleted → `generation` changes → the app
reloads the style. A complete download found at start (crash before joining) is joined then.
HttpURLConnection like AppUpdater; headers `X-OnPath-Key`, `X-OnPath-App`.

**Mobile data rule** (§9, Q2/Q5): unmetered network (`NET_CAPABILITY_NOT_METERED`) → always; refresh → Wi-Fi only;
`wifiOnly` setting → Wi-Fi only; else a cell requested with `ownPoint` loads at once, others wait as
`WaitingForWifi(bytes)` until Wi-Fi or `approveMobile(cells)`.

**Failures** (§10) → state + Problems: no network → `Offline` ("No connection – … will load when you are online.",
INFO), retried on network change or after 60 s; server down / 5xx / 429 → `Offline("Map server not reachable – trying
again.")`, backoff 1, 2, 5, 10 min (or `Retry-After`); 401 → `Failed(retryable = false)` "Map server refused the
request – update the app." (ERROR); build failed → `Failed("This area could not be prepared (reason). Planning still
works with a straight line.")` and dropped from the queue (the next trigger asks again); damaged twice / storage full /
store error → `Failed`.

### 19.4 API for the UI agent (`MapCells`, one instance: `AppCells.get(context)`, also `vm.cells`)

| Call / state | Use |
| --- | --- |
| `cellFor(p)`, `cellsAround(p, radiusM = 5000)` (own first), `cellsFor(s, w, n, e)`, `cellsAlong(line, corridorM = 10 000)` | cells for a point, box, route corridor (`CellId`: `id` "10-543-361", `bbox`, `center`) |
| `missing(cells)` | of these, not installed and not queued – what a trigger requests |
| `hasDetailedMap(p)` | installed cell or region package covers p → "Overview only" hint when false |
| `request(cells, CellReason.POINT/NEAR/CORRIDOR/REFRESH, ownPoint = reason == POINT)` → added cells | T1/T3: `request(listOf(own), POINT)` + `request(near, NEAR)`; T4: `CORRIDOR`; T5: `NEAR` with `ownPoint = true`; T6: `REFRESH` |
| `states: StateFlow<Map<CellId, CellState>>`, `state(c)` | `None`, `Requested`, `Queued(position, etaS)`, `Building(step, progress, etaS)`, `WaitingForWifi(bytes)`, `Downloading(done, total)` (`percent`), `Installing`, `Ready(version)`, `Offline(message)`, `Failed(message, retryable)`; `CellState.label()` gives a short line |
| `waitingForMobileApproval()` → (cell, bytes) · `approveMobile(cells)` | the card's "3 more cells, 85 MB – download now or on Wi-Fi?" |
| `wifiOnly` (var) | Settings "Wi-Fi only" (F-OFF-03) – not stored yet, the UI keeps it in AppSettings |
| `installed: StateFlow<List<InstalledCell>>` (id, version, built, `bytes` per file, `totalBytes`, installedAt) · `delete(cells)` | Settings list (F-OFF-05) |
| `cancel(cells)` | user closed the card / does not want them |
| `markUsed(cells)` · `refreshDue(ageDays = 30)` | T6: mark cells when planning / navigating; request `refreshDue()` with `REFRESH` once a day on Wi-Fi |
| `generation: StateFlow<Int>` · `mapDir()` | the VM reloads the style when the map folder changed (done) |
| `setForeground(on)`, `networkChanged()` | done by MapHost / AndroidNetworkStatus |

Card text per §9 ("Because you planned here" / "Because you are here") is the UI's: the reason is the trigger's.

### 19.5 Tests (JVM)

`PmTilesTest` (spec tile ids, directory round trip, leaf directories, merge filter / override / dedupe, mixed tile
types refused, truncated file), `CellIdTest` (Meran values, half-open rule, neighbours, corridor), `CellStoreTest`
(add / neighbour / replace / delete / last delete, damaged input keeps the old generation, crash leftovers, low storage,
region join + re-join, format-1 not joined, rollback, search index on a generation), `CellDownloadTest` against a tiny
socket-based fake doorman (request / status / manifest, 401, offline, Range resume, sha256 damage, request → poll →
download → join, mobile-data rule, Wi-Fi only, offline → online, server failure, refresh, restart resume, delete).
Optional: `RealCellsTest` with `ONPATH_CELLS_DIR=…/cells-data/cells/f2` (+ `ONPATH_REGION_DIR=…/out-f2/onpath-data`)
joins the two real cells (+ region); `ONPATH_LIVE=1` downloads 10-543-361 from the test doorman (key from
`~/.gradle/gradle.properties`, never printed) – passed 2026-10-21. Gradle caches test results: add `--rerun`.

### 19.6 To check on the phone

1. Settings → Diagnostics → "Request cell at map centre" (map centred on Meran) → states → map shows the cell
   without restart; no seam at lon 11.25 after the second cell (Bozen side).
2. With the format-2 South Tyrol package installed: cell area and package area join; POI tap and search find POIs,
   peaks, trail names and addresses inside the cell.
3. Style reload while navigating (T5 later): location puck and app layers come back, camera untouched.
4. Speed of a generation write with ~9 cells (+ package) on the Pixel; mobile data vs Wi-Fi detection; resume after
   switching off Wi-Fi mid-download; app killed mid-join → joined at next start.

