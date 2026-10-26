#!/usr/bin/env python3
"""Map cells (docs/design/map-cells.md): cell math, owner rule, clipping and manifest for one z10 cell.

A cell is the web-mercator tile z10/x/y. It owns every map tile at zoom >= 10 inside it and every point with
west <= lon < east, south < lat <= north (half-open: a point on a border has exactly one owner).

  cells.py bbox Z X Y [--buffer-km K | --ring-z12] [--inset]
                                             print minlon,minlat,maxlon,maxlat: the cell, the cell + K km, or the
                                             cell + one z12 tile all round (terrain for contours); --inset moves
                                             every edge 1e-7 deg inwards so tools that turn a bbox into a tile
                                             range do not pick up the neighbour's tiles on an exact border
  cells.py tiles Z X Y IN.pmtiles OUT.pmtiles [--minzoom 10]
                                             keep only the tiles the cell owns (zoom >= minzoom)
  cells.py clip Z X Y IN_DIR OUT_DIR         GeoJSON: lines cut at the cell border (pieces inside kept),
                                             points kept by the owner rule; other files copied unchanged
  cells.py remap-ids IDX_DIR FILE.geojson    POI ids of a renumbered extract ("n12", "w3") back to OSM ids
                                             (IDX_DIR from `osmium renumber -i`)
  cells.py manifest Z X Y DIR OSM_DATE GIT_REV URL_PREFIX [--format N] [--wayclass wayclass.json]
                                             write DIR/manifest.json (sizes + sha256 of every file in DIR and its
                                             sub folders, e.g. addresses/10-543-361.sqlite; format default 1)
  cells.py selftest                          checks the Meran example (10/543/361)
"""
import hashlib
import json
import math
import os
import sys
import time

FORMAT = 1  # default; build_cell.sh passes --format (format 2: docs/design/map-cells.md §18)
# Content schema of a cell: bump whenever the style needs data that older cells lack (2 = overlays layer "streets",
# 2026-10-21; 3 = router classes on paths / streets, barrier nodes, SAC on hiking route pieces – extract_overlays.py
# --wayclass, 2026-10-22; 4 = POI placement priority "pr" in pois.geojson – extract_pois.priority(), 2026-10-23; 5 = router
# classes from the tuned bike profiles – touring.brf for touring / e-bike, tuned trekking.brf, 2026-10-26). The
# version folder is "<osm_date>-s<schema>", so a rebuild is a new version for app and caches; the doorman rebuilds
# cells of an older schema when asked (wrangler.toml CELL_SCHEMA; older cells from CELL_SCHEMA_SERVE on are served while
# rebuilt), the app re-requests installed cells below the schema its style needs (cells/CellApi.CELL_SCHEMA – stays 3
# for schemas 4 and 5: s3 cells just place same-kind POIs in tile order / show the older router classes). Keep builder
# and doorman in step.
CELL_SCHEMA = 5
MIN_APP = "0.8.0"  # the first app that downloads cells; it reads format 1 and 2 (style f2 since 0.7.x)
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


def owns_tile(z, x, y, tz, tx, ty, minzoom=10):
    """Does cell z/x/y own map tile tz/tx/ty?"""
    if tz < max(minzoom, z):
        return False
    d = tz - z
    return tx >> d == x and ty >> d == y


def cell_id(z, x, y):
    return f"{z}-{x}-{y}"


# ------------------------------------------------------------------------------------------- pmtiles
def filter_tiles(z, x, y, src, dst, minzoom=10):
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
            if owns_tile(z, x, y, tz, tx, ty, minzoom):
                tiles.append((zxy_to_tileid(tz, tx, ty), bytes(data)))
                maxz = max(maxz, tz)
                kept += 1
            else:
                dropped += 1
    tiles.sort()
    tmp = dst + ".tmp"
    with open(tmp, "wb") as f:
        w = Writer(f)
        for tid, data in tiles:
            w.write_tile(tid, data)
        h = {k: header[k] for k in ("tile_type", "tile_compression")}
        h.update(min_zoom=minzoom, max_zoom=max(maxz, header.get("max_zoom", maxz)),
                 min_lon_e7=int(w_ * 1e7), min_lat_e7=int(s_ * 1e7),
                 max_lon_e7=int(e_ * 1e7), max_lat_e7=int(n_ * 1e7),
                 center_zoom=max(minzoom, min(12, h.get("max_zoom", 12))),
                 center_lon_e7=int((w_ + e_) / 2 * 1e7), center_lat_e7=int((s_ + n_) / 2 * 1e7))
        w.finalize(h, meta)
    os.replace(tmp, dst)
    return kept, dropped


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


def version_name(osm_date, schema=CELL_SCHEMA):
    """Version folder of a cell build: "2026-10-20-s2" (schema 1 cells were named by the OSM date alone)."""
    return f"{osm_date}-s{schema}"


def write_manifest(z, x, y, d, osm_date, git_rev, url_prefix, fmt=FORMAT, version=None, sources=None, wayclass=None):
    """sources: [{id, date, url}] of the OSM extracts the cell was cut from (debugging aid; the app ignores it).
    wayclass: hash of the BRouter profiles the router classes were made with (extract_overlays.py --wayclass)."""
    files = []
    for name in list_files(d):
        p = os.path.join(d, name)
        files.append({"name": name, "size": os.path.getsize(p), "sha256": sha256(p),
                      "url": f"{url_prefix.rstrip('/')}/{name}"})
    m = {"cell": cell_id(z, x, y), "format": fmt, "bbox": [round(v, 6) for v in cell_bbox(z, x, y)],
         "osm_date": osm_date, "version": version or osm_date, "schema": CELL_SCHEMA, "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
         "pipeline": f"git:{git_rev}", "min_app": MIN_APP, "files": files,
         "total_size": sum(f["size"] for f in files), "attribution": ATTRIBUTION}
    if sources:
        m["sources"] = [{k: s.get(k) for k in ("id", "date", "url")} for s in sources]
    if wayclass:
        m["wayclass"] = wayclass
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
    print("ok")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    cmd, rest = argv[0], argv[1:]
    if cmd == "selftest":
        selftest()
        return 0
    if cmd == "version-name":   # cells.py version-name OSM_DATE → "2026-10-20-s2"
        print(version_name(rest[0]))
        return 0
    if cmd == "remap-ids":
        print(f"    {os.path.basename(rest[1])}: {remap_ids(rest[0], rest[1])} ids mapped back to OSM ids")
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
        minzoom = int(a[3]) if a[2:3] == ["--minzoom"] else 10
        kept, dropped = filter_tiles(z, x, y, a[0], a[1], minzoom)
        print(f"    {os.path.basename(a[1])}: kept {kept} tiles, dropped {dropped} (not owned / below z{minzoom})")
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
        m = write_manifest(z, x, y, a[0], a[1], a[2], a[3], fmt, ver, src, wc)
        print(f"    manifest: {len(m['files'])} files, {m['total_size'] / 1e6:.1f} MB")
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
