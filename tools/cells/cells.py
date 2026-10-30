#!/usr/bin/env python3
"""Map cells (docs/design/map-cells.md): cell math, owner rule, clipping and manifest for one z10 cell.

A cell is the web-mercator tile z10/x/y. It owns every map tile at zoom >= 10 inside it and every point with
west <= lon < east, south < lat <= north (half-open: a point on a border has exactly one owner).
A region package (map-cells.md §20, build_package.sh) is the tile z7/x/y: it owns the map tiles z7-9 inside it.

  cells.py bbox Z X Y [--buffer-km K | --ring-z12] [--inset]
                                             print minlon,minlat,maxlon,maxlat: the cell, the cell + K km, or the
                                             cell + one z12 tile all round (terrain for contours); --inset moves
                                             every edge 1e-7 deg inwards so tools that turn a bbox into a tile
                                             range do not pick up the neighbour's tiles on an exact border
  cells.py tiles Z X Y IN.pmtiles OUT.pmtiles [--minzoom 10] [--maxzoom M]
                                             keep only the tiles the cell owns (minzoom <= zoom <= maxzoom);
                                             none (a sea cell): a valid archive without tiles
  cells.py empty-overlays OUT.pmtiles NAME ATTRIBUTION [DESCRIPTION]
                                             an overlays archive without tiles (build_overlays.sh, no features)
  cells.py clip Z X Y IN_DIR OUT_DIR         GeoJSON: lines cut at the cell border (pieces inside kept),
                                             points kept by the owner rule; other files copied unchanged
  cells.py remap-ids IDX_DIR FILE.geojson    POI ids of a renumbered extract ("n12", "w3") back to OSM ids
                                             (IDX_DIR from `osmium renumber -i`)
  cells.py manifest Z X Y DIR OSM_DATE GIT_REV URL_PREFIX [--format N] [--wayclass wayclass.json]
                                             write DIR/manifest.json (sizes + sha256 of every file in DIR and its
                                             sub folders, e.g. addresses/10-543-361.sqlite; format default 1)
                                             [--layers "overlays pois" --base-manifest published.json]: partial
                                             rebuild – DIR has only these layers, the rest is listed from the
                                             published manifest (map-hosting.md §7h)
  cells.py version-name OSM_DATE [--package] "2026-10-20-s7" (a package: its own schema, PACKAGE_SCHEMA)
  cells.py osm-date PBF...                   OSM date of a cell cut from these extracts (the oldest header date)
  cells.py layers                            the layer names of a format-2 cell (LAYER_SCHEMA)
  cells.py selftest                          checks the Meran example (10/543/361) and a package (7/66/42)
"""
import hashlib
import json
import math
import os
import sys
import time

FORMAT = 1  # default; build_cell.sh passes --format (format 2: docs/design/map-cells.md §18)
# Content schema of a cell: bump whenever the style needs data that older cells lack (2 = overlays layer "streets";
# 3 = router classes on paths / streets, barrier nodes, SAC on hiking route pieces – extract_overlays.py
# --wayclass; 4 = POI placement priority "pr" in pois.geojson – extract_pois.priority(); 5 = router
# classes from the tuned bike profiles – touring.brf for touring / e-bike, tuned trekking.brf; 6 = town flag
# "u" on paths / streets, link pieces k="l" for path ends at hidden sidewalks / crossings, "ar" on pedestrian squares –
# extract_overlays.py TownIndex / link_pieces, "quiet towns", plus the router classes of the bike footway
# cost fix; 7 = bike map: cycle routes only of the networks lcn / rcn / ncn / icn, label lines "lb" for
# named local cycle routes, "bs" on bicycle streets, "br" / "fw" / tn "p" | "t" in "paths", street areas (layer "areas"), short town
# sidewalk stubs hidden). The
# version folder is "<osm_date>-s<schema>", so a rebuild is a new version for app and caches; the doorman rebuilds
# cells of an older schema when asked (wrangler.toml CELL_SCHEMA; older cells from CELL_SCHEMA_SERVE on are served while
# rebuilt), the app re-requests installed cells below the schema its style needs (cells/CellApi.CELL_SCHEMA – stays 3
# for schemas 4 to 7: s3 cells just place same-kind POIs in tile order / show the older router classes / draw towns and
# bike routes as before). Keep builder
# and doorman in step. CELL_SCHEMA stays a plain number on this line (tools/dev/publish_area.sh reads it) and is always
# the highest value in LAYER_SCHEMA (selftest).
CELL_SCHEMA = 7
# Per-layer content schema (docs/design/map-hosting.md §7h "Partial rebuild"): for each layer of a format-2 cell, the
# cell schema in which its data last changed. A change bumps CELL_SCHEMA (version name, doorman and app see only that)
# AND the layer(s) it touches; a change that needs two layers in step (e.g. trim_base hiding lines the overlays draw
# from then on) bumps both. When the OSM extract date is the same, job_build.py then rebuilds only the layers whose
# schema is newer than the published version's and copies the others into the new version. 7 = all layers when the
# table came in: older versions (< PARTIAL_FROM_SCHEMA) are always rebuilt whole.
LAYER_SCHEMA = {"base": 7, "overlays": 7, "pois": 7, "addresses": 7, "terrain": 7, "contours": 7}
PARTIAL_FROM_SCHEMA = 7
OSM_LAYERS = ("base", "overlays", "pois", "addresses")  # these need the OSM cut; terrain + contours only AWS tiles
REQUIRED_LAYERS = ("base", "overlays", "terrain")       # a published version without one of them is never reused
# Region packages (z7 tile, map z7-9: main roads, towns, forests, rivers, long-distance routes, relief) have their own
# content schema – the doorman compares a package version with PACKAGE_SCHEMA (wrangler.toml), never with CELL_SCHEMA.
# 1 = OpenMapTiles base z7-9 without farmland, overlays layer "routes" (net >= 3), hillshade.pmtiles.
PACKAGE_Z = 7
PACKAGE_MAXZOOM = 9
PACKAGE_SCHEMA = 1
PACKAGE_MIN_APP = "0.11.3"  # apps up to 0.11.3 never ask for a package (their cell ids are z10 only)
MIN_APP = "0.8.0"  # the first app that downloads cells; it reads format 1 and 2
ATTRIBUTION = ["© OpenStreetMap contributors", "© OpenMapTiles", "Terrain: Mapzen / AWS Open Data"]


# ------------------------------------------------------------------------------------------- cell math
def tile_lon(x, z):
    return x / 2 ** z * 360.0 - 180.0


def tile_lat(y, z):
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / 2 ** z))))


def cell_bbox(z, x, y):
    """(minlon, minlat, maxlon, maxlat) of tile z/x/y – exact, not rounded."""
    return tile_lon(x, z), tile_lat(y + 1, z), tile_lon(x + 1, z), tile_lat(y, z)


def buffered_bbox(bbox, km):
    w, s, e, n = bbox
    dlat = km / 111.32
    dlon = km / (111.32 * math.cos(math.radians(max(abs(s), abs(n)))))
    return w - dlon, s - dlat, e + dlon, n + dlat


def owns_point(bbox, lon, lat):
    w, s, e, n = bbox
    return w <= lon < e and s < lat <= n


def owns_tile(z, x, y, tz, tx, ty, minzoom=10, maxzoom=None):
    """Does cell z/x/y own map tile tz/tx/ty (minzoom <= tz <= maxzoom)?"""
    if tz < max(minzoom, z) or (maxzoom is not None and tz > maxzoom):
        return False
    d = tz - z
    return tx >> d == x and ty >> d == y


def cell_id(z, x, y):
    return f"{z}-{x}-{y}"


# ------------------------------------------------------------------------------------------- pmtiles
def filter_tiles(z, x, y, src, dst, minzoom=10, maxzoom=None):
    from pmtiles.reader import MmapSource, Reader, all_tiles
    from pmtiles.tile import zxy_to_tileid
    from pmtiles.writer import Writer
    w_, s_, e_, n_ = cell_bbox(z, x, y)
    kept = dropped = 0
    with open(src, "rb") as f:
        get = MmapSource(f)
        reader = Reader(get)
        header, meta = reader.header(), reader.metadata()
        tiles = []
        maxz = minzoom
        for (tz, tx, ty), data in all_tiles(get):
            if owns_tile(z, x, y, tz, tx, ty, minzoom, maxzoom):
                tiles.append((zxy_to_tileid(tz, tx, ty), bytes(data)))
                maxz = max(maxz, tz)
                kept += 1
            else:
                dropped += 1
    tiles.sort()
    tmp = dst + ".tmp"
    h = {k: header[k] for k in ("tile_type", "tile_compression")}
    h.update(min_zoom=minzoom, max_zoom=maxz if maxzoom is not None else max(maxz, header.get("max_zoom", maxz)),
             min_lon_e7=int(w_ * 1e7), min_lat_e7=int(s_ * 1e7),
             max_lon_e7=int(e_ * 1e7), max_lat_e7=int(n_ * 1e7),
             center_zoom=max(minzoom, 12),
             center_lon_e7=int((w_ + e_) / 2 * 1e7), center_lat_e7=int((s_ + n_) / 2 * 1e7))
    with open(tmp, "wb") as f:
        if not tiles:  # data only in the buffer (a sea cell, a cut without the cell's land): a valid empty file
            write_empty_pmtiles(f, h, meta)
        else:
            w = Writer(f)
            for tid, data in tiles:
                w.write_tile(tid, data)
            w.finalize(h, meta)
    os.replace(tmp, dst)
    return kept, dropped


def write_empty_pmtiles(f, header, meta):
    """A PMTiles v3 archive without tiles (pmtiles' Writer.finalize cannot write one): header, an empty root directory,
    the metadata. header: tile_type, tile_compression, zooms, bounds, center (as for Writer.finalize). The app reads
    it like any cell file (cells/PmTiles.kt: no entries, nothing merged); the cell is complete, its map just empty."""
    import gzip
    from pmtiles.tile import Compression, serialize_directory, serialize_header
    root = serialize_directory([])
    md = gzip.compress(json.dumps(meta).encode(), mtime=0)
    h = dict(header)
    h.update(addressed_tiles_count=0, tile_entries_count=0, tile_contents_count=0, clustered=True,
             internal_compression=Compression.GZIP, root_offset=127, root_length=len(root),
             metadata_offset=127 + len(root), metadata_length=len(md),
             leaf_directory_offset=127 + len(root) + len(md), leaf_directory_length=0,
             tile_data_offset=127 + len(root) + len(md), tile_data_length=0)
    f.write(serialize_header(h))
    f.write(root)
    f.write(md)


def empty_overlays(dst, name, attribution, description=None, minzoom=9, maxzoom=14):
    """build_overlays.sh when no overlay layer has a feature (tippecanoe refuses empty input): an empty MVT archive
    (gzip tiles, like tippecanoe's) with the usual metadata and no vector layers."""
    from pmtiles.tile import Compression, TileType
    meta = {"name": name, "format": "pbf", "type": "overlay", "attribution": attribution, "vector_layers": []}
    if description:
        meta["description"] = description
    h = dict(tile_type=TileType.MVT, tile_compression=Compression.GZIP, min_zoom=minzoom, max_zoom=maxzoom,
             min_lon_e7=-1800000000, min_lat_e7=-850511287, max_lon_e7=1800000000, max_lat_e7=850511287,
             center_zoom=minzoom, center_lon_e7=0, center_lat_e7=0)
    tmp = dst + ".tmp"
    with open(tmp, "wb") as f:
        write_empty_pmtiles(f, h, meta)
    os.replace(tmp, dst)


# ------------------------------------------------------------------------------------------- GeoJSON
def _r(c):
    # 6 decimals (~0.1 m): border points are computed from the same segment in both cells -> identical
    return [round(c[0], 6), round(c[1], 6)]


def clip_geojson(z, x, y, src, dst):
    from shapely.geometry import box, mapping, shape
    bbox = cell_bbox(z, x, y)
    cell = box(*bbox)
    with open(src) as f:
        fc = json.load(f)
    out, n_in, n_cut, n_drop = [], 0, 0, 0
    for feat in fc.get("features", []):
        n_in += 1
        g = feat.get("geometry") or {}
        t = g.get("type")
        if t == "Point":
            lon, lat = g["coordinates"][:2]
            if owns_point(bbox, lon, lat):
                out.append(feat)
            else:
                n_drop += 1
            continue
        if t not in ("LineString", "MultiLineString"):
            out.append(feat)  # not expected in format 1 overlays; keep unchanged
            continue
        geom = shape(g)
        w, s, e, n = geom.bounds
        if bbox[0] <= w and e <= bbox[2] and bbox[1] <= s and n <= bbox[3]:
            out.append(feat)  # fully inside: unchanged
            continue
        part = geom.intersection(cell)
        lines = [p for p in getattr(part, "geoms", [part]) if p.geom_type == "LineString" and p.length > 0]
        if not lines:
            n_drop += 1
            continue
        n_cut += 1
        for ln in lines:
            out.append({"type": "Feature", "geometry": {"type": "LineString",
                                                        "coordinates": [_r(c) for c in ln.coords]},
                        "properties": feat.get("properties", {})})
    tmp = dst + ".tmp"
    with open(tmp, "w") as f:
        json.dump({"type": "FeatureCollection", "features": out}, f, separators=(",", ":"))
    os.replace(tmp, dst)
    return n_in, len(out), n_cut, n_drop


def clip_dir(z, x, y, src_dir, dst_dir):
    os.makedirs(dst_dir, exist_ok=True)
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith(".geojson"):
            continue
        n_in, n_out, n_cut, n_drop = clip_geojson(z, x, y, os.path.join(src_dir, name), os.path.join(dst_dir, name))
        print(f"    {name}: {n_in} in -> {n_out} out ({n_cut} cut at the border, {n_drop} outside)")


# ------------------------------------------------------------------------------------------- renumbered ids
def remap_ids(idx_dir, path):
    """osmium renumber -i writes per type the old ids in new-id order (new id k -> old id idx[k - start])."""
    import numpy as np
    with open(os.path.join(idx_dir, "start_ids")) as f:
        starts = dict(zip("nwr", map(int, f.read().strip().split(","))))
    idx = {t: np.memmap(os.path.join(idx_dir, name), dtype="<i8", mode="r")
           for t, name in (("n", "node.idx"), ("w", "way.idx"), ("r", "relation.idx"))}
    with open(path) as f:
        fc = json.load(f)
    n = 0
    for feat in fc["features"]:
        p = feat.get("properties", {})
        pid = p.get("id")
        if isinstance(pid, str) and pid[:1] in idx and pid[1:].isdigit():
            p["id"] = f"{pid[0]}{int(idx[pid[0]][int(pid[1:]) - starts[pid[0]]])}"
            n += 1
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(fc, f, separators=(",", ":"))
    os.replace(tmp, path)
    return n


# ------------------------------------------------------------------------------------------- manifest
def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def list_files(d):
    """Every file below d as a relative path ("base.pmtiles", "addresses/10-543-361.sqlite"), hidden files,
    manifest.json and build.json left out."""
    out = []
    for root, dirs, names in os.walk(d):
        dirs[:] = sorted(n for n in dirs if not n.startswith("."))
        for name in names:
            rel = os.path.relpath(os.path.join(root, name), d)
            if name.startswith(".") or rel in ("manifest.json", "build.json") or name.endswith(".tmp"):
                continue
            out.append(rel)
    return sorted(out, key=lambda r: (r.count("/"), r))


def layer_of(name):
    """Layer of a format-2 cell file ("overlays.pmtiles" -> "overlays", "addresses/10-1-2.sqlite" -> "addresses"),
    None for anything else."""
    if name.startswith("addresses/") and name.endswith(".sqlite") and name.count("/") == 1:
        return "addresses"
    stem, _, ext = name.partition(".")
    if "/" in name or stem not in LAYER_SCHEMA or stem == "addresses":
        return None
    return stem if ext == ("geojson" if stem == "pois" else "pmtiles") else None


def schema_of(version):
    """"2026-10-20-s7" -> 7; versions named by the OSM date alone are schema 1 (the app's and doorman's rule)."""
    _, sep, tail = str(version or "").rpartition("-s")
    return int(tail) if sep and tail.isdigit() else 1


def layer_schemas(manifest):
    """{layer: schema it was built for} of a published manifest: from its "layers" when it has them, else (built
    before the table) every layer as new as the version's schema allows – min(version schema, LAYER_SCHEMA)."""
    s = schema_of(manifest.get("version"))
    rec = manifest.get("layers") if isinstance(manifest.get("layers"), dict) else {}
    out = {}
    for name, cur in LAYER_SCHEMA.items():
        r = rec.get(name)
        out[name] = r["schema"] if isinstance(r, dict) and isinstance(r.get("schema"), int) else min(s, cur)
    return out


def rebuild_plan(published, osm_date, z, x, y, fmt=2):
    """Layers to build for a new version of cell z/x/y, given its published manifest (dict or None) and the OSM date of
    the sources now. None = build the whole cell: nothing usable published, another OSM date, a region package, format
    1, a version older than PARTIAL_FROM_SCHEMA, a file of no known layer, a required layer missing, an entry without
    size / sha256, or nothing (or everything) to rebuild. Else the sorted list of the stale layers."""
    if not isinstance(published, dict) or is_package(z) or fmt != 2 or published.get("format") != fmt:
        return None
    if published.get("cell") != cell_id(z, x, y) or not osm_date or published.get("osm_date") != osm_date:
        return None
    if schema_of(published.get("version")) < PARTIAL_FROM_SCHEMA:
        return None
    files = published.get("files") or []
    if any(not isinstance(f, dict) or not isinstance(f.get("size"), int) or len(str(f.get("sha256", ""))) != 64
           for f in files):
        return None
    have = {layer_of(str(f.get("name", ""))) for f in files}
    if None in have or not set(REQUIRED_LAYERS) <= have:
        return None
    built = layer_schemas(published)
    stale = sorted(name for name, cur in LAYER_SCHEMA.items() if built[name] < cur)
    return stale if 0 < len(stale) < len(LAYER_SCHEMA) else None


def osm_date(paths):
    """OSM date of a cell cut from these extracts: the oldest header date (the cell is as new as its oldest part); a
    file without one: its modification day."""
    import osmium
    dates = []
    for p in paths:
        r = osmium.io.Reader(p, osmium.osm.osm_entity_bits.NOTHING)
        h = r.header()
        d = (h.get("osmosis_replication_timestamp") or h.get("timestamp") or "")[:10]
        r.close()
        dates.append(d or time.strftime("%Y-%m-%d", time.gmtime(os.path.getmtime(p))))
    return min(dates)


def version_name(osm_date, schema=CELL_SCHEMA, rebuild=1):
    """Version folder of a cell build: "2026-10-20-s2" (schema 1 cells were named by the OSM date alone); a region
    package: version_name(date, PACKAGE_SCHEMA). rebuild > 1: a rebuild of a version already published with the same
    OSM date and schema (a pipeline fix, job_build.py rebuild_number) – "2026-10-20.2-s7": a published folder is
    immutable and cached, and app and doorman compare only names. The name still ends in "-s<schema>" (schema_of, the
    doorman's and the app's schemaOf) and sorts after "2026-10-20-s7" and before the next OSM date (cleanup.js)."""
    return f"{osm_date}{f'.{rebuild}' if rebuild > 1 else ''}-s{schema}"


def is_package(z):
    return z == PACKAGE_Z


def merge_files(built, base, layers, url_prefix):
    """Partial rebuild: the file entries of a new version = the freshly built files (`built`, all of `layers`) + the
    published version's (`base` manifest) entries of every other layer, size and sha256 kept, url moved into the new
    version folder (job_build.py copies them there – a version folder stays complete and immutable)."""
    out = []
    for f in built:
        if layer_of(f["name"]) not in layers:
            raise ValueError(f"{f['name']} is not part of the rebuilt layers {sorted(layers)}")
        out.append(f)
    for f in base.get("files") or []:
        layer = layer_of(f["name"])
        if layer is None:
            raise ValueError(f"published file {f['name']} belongs to no layer")
        if layer not in layers:
            out.append({"name": f["name"], "size": f["size"], "sha256": f["sha256"],
                        "url": f"{url_prefix.rstrip('/')}/{f['name']}"})
    return sorted(out, key=lambda e: (e["name"].count("/"), e["name"]))


def write_manifest(z, x, y, d, osm_date, git_rev, url_prefix, fmt=FORMAT, version=None, sources=None, wayclass=None,
                   layers=None, base=None):
    """sources: [{id, date, url}] of the OSM extracts the cell was cut from (debugging aid; the app ignores it).
    wayclass: hash of the BRouter profiles the router classes were made with (extract_overlays.py --wayclass).
    layers + base: partial rebuild (map-hosting.md §7h) – d holds only the files of `layers`; the other layers' entries
    come from the published manifest `base` (same OSM date required) and are copied into the version by the job."""
    files = []
    for name in list_files(d):
        p = os.path.join(d, name)
        files.append({"name": name, "size": os.path.getsize(p), "sha256": sha256(p),
                      "url": f"{url_prefix.rstrip('/')}/{name}"})
    version = version or osm_date
    partial = layers is not None
    if partial:
        layers = set(layers)
        if not layers or not layers <= set(LAYER_SCHEMA) or base is None:
            raise ValueError(f"partial rebuild needs known layers and the published manifest (got {sorted(layers)})")
        if base.get("osm_date") != osm_date or base.get("cell") != cell_id(z, x, y) or base.get("format") != fmt:
            raise ValueError(f"published manifest is of another cell / OSM date / format "
                             f"({base.get('cell')} {base.get('osm_date')} f{base.get('format')}) – rebuild it whole")
        files = merge_files(files, base, layers, url_prefix)
    pkg = is_package(z)
    m = {"cell": cell_id(z, x, y), "format": fmt, "bbox": [round(v, 6) for v in cell_bbox(z, x, y)],
         "osm_date": osm_date, "version": version, "schema": PACKAGE_SCHEMA if pkg else CELL_SCHEMA,
         "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
         "pipeline": f"git:{git_rev}", "min_app": PACKAGE_MIN_APP if pkg else MIN_APP, "files": files,
         "total_size": sum(f["size"] for f in files), "attribution": ATTRIBUTION}
    if pkg:
        m["kind"] = "package"
        m["zooms"] = [PACKAGE_Z, PACKAGE_MAXZOOM]
    if sources:
        m["sources"] = [{k: s.get(k) for k in ("id", "date", "url")} for s in sources]
    if partial and "overlays" not in layers and base.get("wayclass"):
        wayclass = base["wayclass"]  # the router classes are part of the overlays kept from the published version
    if wayclass:
        m["wayclass"] = wayclass
    if not pkg and fmt == 2:
        # per layer: the schema it was built for and the version folder it was built in (kept ones: as published)
        prev = layer_schemas(base) if partial else {}
        prev_rec = base.get("layers") if partial and isinstance(base.get("layers"), dict) else {}
        m["layers"] = {name: {"schema": cur, "version": version} if not partial or name in layers else
                       {"schema": prev[name], "version": (prev_rec.get(name) or {}).get("version") or base["version"]}
                       for name, cur in LAYER_SCHEMA.items()}
        if partial:
            m["reused_from"] = base["version"]
    with open(os.path.join(d, "manifest.json.tmp"), "w") as f:
        json.dump(m, f, indent=1, ensure_ascii=False)
    os.replace(os.path.join(d, "manifest.json.tmp"), os.path.join(d, "manifest.json"))
    return m


# ------------------------------------------------------------------------------------------- selftest
def selftest():
    b = cell_bbox(10, 543, 361)
    exp = (10.8984, 46.5589, 11.2500, 46.8001)
    assert all(abs(a - e) < 1e-4 for a, e in zip(b, exp)), b
    assert owns_point(b, 10.8984375, 46.7) and not owns_point(b, 11.25, 46.7)          # west in, east out
    assert owns_point(cell_bbox(10, 544, 361), 11.25, 46.7)                            # ...owned by the east one
    assert owns_tile(10, 543, 361, 10, 543, 361) and owns_tile(10, 543, 361, 14, 543 * 16 + 15, 361 * 16)
    assert not owns_tile(10, 543, 361, 14, 544 * 16, 361 * 16) and not owns_tile(10, 543, 361, 9, 271, 180)
    # region package 7/66/42 (Düsseldorf / Hilden; Bonn lies just south of it in 7/66/43): z7-9 under it, nothing else
    p = cell_bbox(7, 66, 42)
    assert abs(p[0] - 5.625) < 1e-9 and abs(p[2] - 8.4375) < 1e-9 and 50.73 < p[1] < 50.75 and 52.47 < p[3] < 52.49, p
    assert owns_point(p, 6.78, 51.22) and not owns_point(p, 7.10, 50.73)
    assert owns_tile(7, 66, 42, 7, 66, 42, 7, 9) and owns_tile(7, 66, 42, 9, 66 * 4 + 3, 42 * 4, 7, 9)
    assert not owns_tile(7, 66, 42, 10, 531, 342, 7, 9) and not owns_tile(7, 66, 42, 6, 33, 21, 7, 9)
    assert (531 >> 3, 342 >> 3) == (66, 42)  # the Hilden cell lies under it
    assert version_name("2026-10-26", PACKAGE_SCHEMA) == f"2026-10-26-s{PACKAGE_SCHEMA}"
    assert version_name("2026-10-26", 7, 2) == "2026-10-26.2-s7" and schema_of("2026-10-26.2-s7") == 7
    assert sorted(["2026-10-27-s7", "2026-10-26.2-s7", "2026-10-26-s7"]) == \
        ["2026-10-26-s7", "2026-10-26.2-s7", "2026-10-27-s7"]
    # per-layer schema: the cell schema is the newest layer's (version name, doorman and app compare only that)
    assert CELL_SCHEMA == max(LAYER_SCHEMA.values()) and PARTIAL_FROM_SCHEMA <= CELL_SCHEMA
    assert [layer_of(n) for n in ("base.pmtiles", "pois.geojson", "addresses/10-1-2.sqlite", "build.json")] == \
        ["base", "pois", "addresses", None]
    print("ok")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd, rest = argv[0], argv[1:]
    if cmd == "selftest":
        selftest()
        return 0
    if cmd == "version-name":   # cells.py version-name OSM_DATE [--package] [--rebuild N] → "2026-10-20-s2"
        n = int(rest[rest.index("--rebuild") + 1] or 1) if "--rebuild" in rest[1:] else 1
        print(version_name(rest[0], PACKAGE_SCHEMA if "--package" in rest[1:] else CELL_SCHEMA, n))
        return 0
    if cmd == "remap-ids":
        print(f"    {os.path.basename(rest[1])}: {remap_ids(rest[0], rest[1])} ids mapped back to OSM ids")
        return 0
    if cmd == "osm-date":
        print(osm_date(rest))
        return 0
    if cmd == "layers":
        print(" ".join(LAYER_SCHEMA))
        return 0
    if cmd == "empty-overlays":
        empty_overlays(*rest[:4])
        return 0
    z, x, y = map(int, rest[:3])
    a = rest[3:]
    if cmd == "bbox":
        b = cell_bbox(z, x, y)
        if "--buffer-km" in a:
            b = buffered_bbox(b, float(a[a.index("--buffer-km") + 1]))
        if "--ring-z12" in a:
            d = 12 - z
            w_, n_ = cell_bbox(12, (x << d) - 1, (y << d) - 1)[0], cell_bbox(12, (x << d) - 1, (y << d) - 1)[3]
            e_, s_ = cell_bbox(12, ((x + 1) << d), ((y + 1) << d))[2], cell_bbox(12, ((x + 1) << d), ((y + 1) << d))[1]
            b = (w_, s_, e_, n_)
        if "--inset" in a:
            b = (b[0] + 1e-7, b[1] + 1e-7, b[2] - 1e-7, b[3] - 1e-7)
        print(",".join(repr(v) for v in b))
    elif cmd == "tiles":
        minzoom = int(a[a.index("--minzoom") + 1]) if "--minzoom" in a else 10
        maxzoom = int(a[a.index("--maxzoom") + 1]) if "--maxzoom" in a else None
        kept, dropped = filter_tiles(z, x, y, a[0], a[1], minzoom, maxzoom)
        print(f"    {os.path.basename(a[1])}: kept {kept} tiles, dropped {dropped} (not owned / outside "
              f"z{minzoom}-{maxzoom if maxzoom is not None else ''})")
    elif cmd == "clip":
        clip_dir(z, x, y, a[0], a[1])
    elif cmd == "manifest":
        fmt = int(a[a.index("--format") + 1]) if "--format" in a else FORMAT
        ver = a[a.index("--version") + 1] if "--version" in a else None
        src = None
        if "--sources" in a:
            with open(a[a.index("--sources") + 1]) as f:
                src = json.load(f)
        wc = None
        if "--wayclass" in a:
            with open(a[a.index("--wayclass") + 1]) as f:
                wc = json.load(f).get("profiles")
        layers = base = None
        if "--layers" in a and a[a.index("--layers") + 1].strip():
            layers = a[a.index("--layers") + 1].split()
            with open(a[a.index("--base-manifest") + 1]) as f:
                base = json.load(f)
        m = write_manifest(z, x, y, a[0], a[1], a[2], a[3], fmt, ver, src, wc, layers, base)
        if layers:
            print(f"    partial: rebuilt {' '.join(sorted(layers))}, the rest kept from {base['version']}")
        print(f"    manifest: {len(m['files'])} files, {m['total_size'] / 1e6:.1f} MB")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
