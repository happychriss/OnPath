#!/usr/bin/env python3
"""Post-process the overview map (tools/build_overview.sh, docs/map-pipeline.md §9b) into the files the app ships.

  overview_merge.py world WORLD_RAW.pmtiles NE.sqlite OUT.pmtiles --bbox 5,43.5,17,48.5 [--poly alps.poly]
      World z0-5 (Planetiler OpenMapTiles run on an EMPTY OSM file: only Natural Earth + water polygons) plus
      - place points from Natural Earth (layer "place": countries, populated places, OpenMapTiles attributes
        class / rank / capital / name / name:de / name_de) – the empty OSM run has no place layer at all;
      - z5 country / region borders: OpenMapTiles takes borders from OSM from z5 on, so the z4 Natural Earth
        borders are copied into the z5 tiles;
      - a HOLE at z5 where the Alps overview has its own data (the AREA: bbox, cut to the OSM extract's Osmosis
        polygon --poly – outside that polygon the Alps run has no OSM data, e.g. Munich, Zurich, Milan are not in
        Geofabrik's alps extract): the app over-zooms these z5 tiles up to
        zoom 9 outside the Alps (source "overview-world", maxzoom 5) and draws the Alps file inside, so nothing is
        drawn twice (no doubled borders / labels / lake outlines).

  overview_merge.py clip ALPS_RAW.pmtiles OUT.pmtiles --bbox 5,43.5,17,48.5 [--poly alps.poly] --minzoom 5
      The Alps base map (Planetiler with OSM) cut to exactly the area and to zoom >= minzoom, so its edge meets the
      world file's hole.

Geometry work happens in tile coordinates (extent 4096, y down) with shapely; tiles that lie fully inside / outside
the area are copied unchanged.
"""
import argparse
import gzip
import math
import sqlite3
import sys

import mapbox_vector_tile as mvt
from pmtiles.reader import MmapSource, Reader, all_tiles
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer
from shapely.affinity import affine_transform
from shapely.geometry import MultiLineString, MultiPoint, MultiPolygon, Point, box, shape

EXTENT = 4096
OPTS = {"y_coord_down": True, "extents": EXTENT}
# Natural Earth "min_zoom" (suggested label zoom) of a populated place -> first tile zoom carrying it. The z5 tiles
# carry everything up to NE min_zoom 7 because they are over-zoomed up to zoom 9 outside the Alps.
PLACE_LEAD = 2


def tile_frac(z, lon, lat):
    n = 2 ** z
    lat = max(-85.0511287798, min(85.0511287798, lat))
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n


def read_poly(path):
    """Osmosis .poly file (Geofabrik publishes one per extract) -> shapely (Multi)Polygon in lon/lat."""
    from shapely.geometry import Polygon
    from shapely.ops import unary_union
    outer, holes, ring, name = [], [], None, None
    with open(path) as f:
        lines = [ln.strip() for ln in f if ln.strip()][1:]
    for ln in lines:
        if ring is None:
            if ln == "END":
                break
            name, ring = ln, []
        elif ln == "END":
            (holes if name.startswith("!") else outer).append(Polygon(ring))
            ring = None
        else:
            ring.append(tuple(float(v) for v in ln.split()[:2]))
    return unary_union(outer).difference(unary_union(holes)) if holes else unary_union(outer)


def area_mercator(bbox, poly_path=None):
    """The area (bbox, cut to the polygon) in Web Mercator units 0..1 (y down)."""
    import shapely
    area = box(*bbox)
    if poly_path:
        area = area.intersection(read_poly(poly_path))
    return shapely.transform(area, lambda c: [tile_frac(0, lon, lat) for lon, lat in c])


def area_in_tile(z, x, y, area):
    """The area (Web Mercator units) in the tile's coordinates (extent 4096, y down)."""
    n = 2 ** z * EXTENT
    return affine_transform(area, [n, 0, 0, n, -x * EXTENT, -y * EXTENT])


TILE_WITH_BUFFER = box(-EXTENT / 8, -EXTENT / 8, EXTENT * 9 / 8, EXTENT * 9 / 8)


def unzip(data):
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def decode(data):
    return mvt.decode(unzip(data), default_options={"y_coord_down": True})


def encode(layers):
    out = []
    for name, feats in layers.items():
        for f in feats:
            g = f["geometry"]
            if g is not None and g.geom_type == "GeometryCollection" and not g.is_empty:
                # a clip touching an edge: keep the parts of the highest dimension (polygon > line > point)
                kinds = {p.geom_type.replace("Multi", "") for p in g.geoms}
                f["geometry"] = same_kind(g, next(k for k in ("Polygon", "LineString", "Point") if k in kinds))
        feats = [f for f in feats if f["geometry"] is not None and not f["geometry"].is_empty]
        if feats:
            out.append({"name": name, "features": feats})
    if not out:
        return None
    return gzip.compress(mvt.encode(out, default_options={**OPTS, "on_invalid_geometry": mvt.encoder.on_invalid_geometry_make_valid}))


def to_shapely(tile):
    """Decoded tile -> {layer: [{geometry: shapely, properties, id}]}."""
    out = {}
    for name, layer in tile.items():
        feats = []
        for f in layer["features"]:
            try:
                g = shape(f["geometry"])
            except Exception as e:  # expected: a degenerate geometry from quantization – dropped, counted
                print(f"  skip bad geometry in {name}: {e}", file=sys.stderr)
                continue
            feats.append({"geometry": g, "properties": f["properties"], **({"id": f["id"]} if f.get("id") else {})})
        out[name] = feats
    return out


def same_kind(g, kind):
    """Only the parts of g with the dimension of the original geometry type (a cut may add stray points / lines)."""
    if g.is_empty:
        return None
    base = kind.replace("Multi", "")
    parts = [p for p in getattr(g, "geoms", [g]) for p in getattr(p, "geoms", [p]) if p.geom_type == base]
    if not parts:
        return None
    if len(parts) == 1:
        return parts[0]
    return {"Point": MultiPoint, "LineString": MultiLineString, "Polygon": MultiPolygon}[base](parts)


def cut(layers, rect, keep_inside):
    """Intersect (keep_inside) or subtract every geometry with rect."""
    for feats in layers.values():
        for f in feats:
            g = f["geometry"]
            if not g.is_valid:
                g = g.buffer(0) if g.geom_type in ("Polygon", "MultiPolygon") else g
            f["geometry"] = g.intersection(rect) if keep_inside else g.difference(rect)
            f["geometry"] = same_kind(f["geometry"], g.geom_type)
    return layers


def write(path, tiles, header, metadata):
    with open(path, "wb") as f:
        w = Writer(f)
        for t in sorted(tiles, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*t), tiles[t])
        w.finalize(header, metadata)


def read_all(path):
    with open(path, "rb") as f:
        r = Reader(MmapSource(f))
        h, md = r.header(), r.metadata()
        tiles = {t: d for t, d in all_tiles(r.get_bytes)}
    return h, md, tiles


def vector_header(minzoom, maxzoom, bbox):
    minlon, minlat, maxlon, maxlat = bbox
    return {"tile_type": TileType.MVT, "tile_compression": Compression.GZIP, "min_zoom": minzoom,
            "max_zoom": maxzoom, "min_lon_e7": int(minlon * 1e7), "min_lat_e7": int(minlat * 1e7),
            "max_lon_e7": int(maxlon * 1e7), "max_lat_e7": int(maxlat * 1e7), "center_zoom": minzoom,
            "center_lon_e7": int((minlon + maxlon) / 2 * 1e7), "center_lat_e7": int((minlat + maxlat) / 2 * 1e7)}


# --- Natural Earth places -------------------------------------------------------------------------------------
def ne_places(db):
    """[(lon, lat, first tile zoom, properties)] – countries and populated places in OpenMapTiles' place schema."""
    c = sqlite3.connect(db)
    out = []
    for name, de, lon, lat, rank, minl, iso in c.execute(
            "select name, name_de, label_x, label_y, labelrank, min_label, iso_a2_eh from ne_10m_admin_0_countries"):
        if name and lon is not None:
            props = {"class": "country", "rank": int(rank), "name": name, **({"name:de": de, "name_de": de} if de else {}),
                     **({"iso_a2": iso} if iso and iso != "-99" else {})}
            out.append((lon, lat, max(1, int(math.floor(minl)) - PLACE_LEAD), props))
    for name, de, lon, lat, sr, cap, minz, fc in c.execute(
            "select name, name_de, longitude, latitude, scalerank, adm0cap, min_zoom, featurecla "
            "from ne_10m_populated_places"):
        if not name or lon is None:
            continue
        # rank like OpenMapTiles (1 = biggest); capital 2 = country capital, 4 = region capital
        props = {"class": "city", "rank": min(int(sr) + 1, 10), "name": name,
                 **({"name:de": de, "name_de": de} if de else {})}
        if cap:
            props["capital"] = 2
        elif fc and "Admin-1" in fc:
            props["capital"] = 4
        out.append((lon, lat, int(math.floor(minz)) - PLACE_LEAD, props))
    return out


def world(a):
    bbox = a.bbox
    area = area_mercator(bbox, a.poly)
    h, md, raw = read_all(a.input)
    maxz = h["max_zoom"]
    places = ne_places(a.ne)
    by_tile = {}
    for lon, lat, first, props in places:
        for z in range(max(0, first), maxz + 1):
            fx, fy = tile_frac(z, lon, lat)
            t = (z, int(fx), int(fy))
            by_tile.setdefault(t, []).append({"geometry": Point((fx - t[1]) * EXTENT, (fy - t[2]) * EXTENT),
                                              "properties": props})
    out = {}
    n_hole = 0
    for z in range(0, maxz + 1):
        for x in range(2 ** z):
            for y in range(2 ** z):
                t = (z, x, y)
                data = raw.get(t)
                layers = to_shapely(decode(data)) if data else {}
                if z == maxz and "boundary" not in layers:
                    parent = raw.get((z - 1, x // 2, y // 2))
                    if parent:
                        pb = to_shapely(decode(parent)).get("boundary", [])
                        ox, oy = (x % 2) * EXTENT / 2, (y % 2) * EXTENT / 2
                        moved = []
                        for f in pb:
                            g = affine_transform(f["geometry"], [2, 0, 0, 2, -2 * ox, -2 * oy]).intersection(TILE_WITH_BUFFER)
                            if not g.is_empty:
                                moved.append({"geometry": g, "properties": f["properties"]})
                        if moved:
                            layers["boundary"] = moved
                if t in by_tile:
                    layers.setdefault("place", []).extend(by_tile[t])
                if z == maxz:
                    rect = area_in_tile(z, x, y, area)
                    if rect.intersects(TILE_WITH_BUFFER):
                        cut(layers, rect, keep_inside=False)
                        n_hole += 1
                if not layers:
                    continue
                if data and t not in by_tile and z != maxz:
                    out[t] = data if data[:2] == b"\x1f\x8b" else gzip.compress(data)
                    continue
                enc = encode(layers)
                if enc:
                    out[t] = enc
    vl = [l for l in md.get("vector_layers", []) if l["id"] != "place"]
    vl.append({"id": "place", "fields": {"class": "String", "rank": "Number", "capital": "Number", "iso_a2": "String", "name": "String",
                                         "name:de": "String", "name_de": "String"}, "minzoom": 0, "maxzoom": maxz})
    md = {**md, "vector_layers": vl, "name": "OnPath overview world",
          "description": f"Natural Earth world z0-{maxz}, hole at z{maxz} for the Alps overview {bbox}"}
    write(a.out, out, vector_header(0, maxz, (-180, -85.0511, 180, 85.0511)), md)
    size = sum(len(v) for v in out.values())
    print(f"world: {len(out)} tiles, {size / 1e6:.2f} MB, {len(places)} NE places, z{maxz} hole tiles {n_hole}")


def clip(a):
    bbox = a.bbox
    area = area_mercator(bbox, a.poly)
    h, md, raw = read_all(a.input)
    out = {}
    n_cut = 0
    for (z, x, y), data in raw.items():
        if z < a.minzoom:
            continue
        rect = area_in_tile(z, x, y, area)
        if not rect.intersects(TILE_WITH_BUFFER):
            continue
        if rect.contains(TILE_WITH_BUFFER):
            out[(z, x, y)] = data if data[:2] == b"\x1f\x8b" else gzip.compress(data)
            continue
        n_cut += 1
        enc = encode(cut(to_shapely(decode(data)), rect, keep_inside=True))
        if enc:
            out[(z, x, y)] = enc
    md = {**md, "description": f"OnPath overview, Alps {bbox} z{a.minzoom}-{h['max_zoom']}, cut to the area"}
    write(a.out, out, vector_header(a.minzoom, h["max_zoom"], bbox), md)
    size = sum(len(v) for v in out.values())
    print(f"clip: {len(out)} tiles ({n_cut} cut at the area edge), {size / 1e6:.2f} MB")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    bb = lambda s: tuple(float(v) for v in s.split(","))  # noqa: E731
    w = sub.add_parser("world")
    w.add_argument("input"); w.add_argument("ne"); w.add_argument("out")
    c = sub.add_parser("clip")
    c.add_argument("input"); c.add_argument("out")
    for p in (w, c):
        p.add_argument("--bbox", type=bb, required=True)
        p.add_argument("--poly", help="Osmosis polygon of the OSM extract (the area = bbox and polygon)")
    c.add_argument("--minzoom", type=int, default=5)
    a = ap.parse_args()
    {"world": world, "clip": clip}[a.cmd](a)


if __name__ == "__main__":
    main()
