#!/usr/bin/env python3
"""Finish the world overview base map shipped in the APK (tools/build_overview.sh, docs/map-pipeline.md §9b).

  overview_merge.py WORLD_RAW.pmtiles NE.sqlite OUT.pmtiles [--maxzoom Z]

WORLD_RAW is Planetiler's OpenMapTiles profile run on an EMPTY OSM file (`--bounds=world`): only Natural Earth and
the water polygons. That leaves gaps this script fills, in the same OpenMapTiles schema the cells use (one style):
- place: the empty-OSM run has none. Natural Earth countries and populated places are added with OpenMapTiles'
  attributes (class country / city, rank = NE scalerank + 1 resp. labelrank, capital 2 = country / 4 = region,
  name, name:de, name_de, iso_a2). A place goes into the tiles from its NE min_zoom - 2 on, so the last zoom level
  carries what the over-zoomed views up to z9 need.
- deeper zooms: OpenMapTiles takes borders from OSM from z5, lakes, rivers and land use from z6, so Planetiler's
  tiles there lack them (z6+ hold only the ocean and NE ice). The last Natural Earth level of each layer
  ([LAST_NE]: z4 borders, z5 rivers, land use, lakes) is copied (over-zoomed) into the deeper tiles, so they keep
  everything the zoom above shows and only the coast gets finer.

Geometry work happens in tile coordinates (extent 4096, y down) with shapely.
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
# Natural Earth "min_zoom" (suggested label zoom) of a place -> first tile zoom carrying it
PLACE_LEAD = 2
# layer -> last zoom OpenMapTiles fills it from Natural Earth; deeper tiles get copies (water: all but the ocean)
LAST_NE = {"boundary": 4, "waterway": 5, "landuse": 5, "water": 5}
TILE_WITH_BUFFER = box(-EXTENT / 8, -EXTENT / 8, EXTENT * 9 / 8, EXTENT * 9 / 8)


def tile_frac(z, lon, lat):
    n = 2 ** z
    lat = max(-85.0511287798, min(85.0511287798, lat))
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n


def unzip(data):
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def decode(data):
    return mvt.decode(unzip(data), default_options={"y_coord_down": True})


def same_kind(g, kind):
    """Only the parts of g with the given dimension (a clip may add stray points / lines)."""
    if g is None or g.is_empty:
        return None
    base = kind.replace("Multi", "")
    parts = [p for p in getattr(g, "geoms", [g]) for p in getattr(p, "geoms", [p]) if p.geom_type == base]
    if not parts:
        return None
    if len(parts) == 1:
        return parts[0]
    return {"Point": MultiPoint, "LineString": MultiLineString, "Polygon": MultiPolygon}[base](parts)


def encode(layers):
    out = []
    for name, feats in layers.items():
        feats = [f for f in feats if f["geometry"] is not None and not f["geometry"].is_empty]
        if feats:
            out.append({"name": name, "features": feats})
    if not out:
        return None
    return gzip.compress(mvt.encode(out, default_options={
        **OPTS, "on_invalid_geometry": mvt.encoder.on_invalid_geometry_make_valid}))


def to_shapely(tile):
    """Decoded tile -> {layer: [{geometry: shapely, properties, id}]}."""
    out = {}
    for name, layer in tile.items():
        feats = []
        for f in layer["features"]:
            try:
                g = shape(f["geometry"])
            except Exception as e:  # expected: a degenerate geometry from quantization – dropped, reported
                print(f"  skip bad geometry in {name}: {e}", file=sys.stderr)
                continue
            feats.append({"geometry": g, "properties": f["properties"], **({"id": f["id"]} if f.get("id") else {})})
        out[name] = feats
    return out


def overzoom(feats, dz, x, y):
    """Features of an ancestor tile dz levels up, moved into child tile (x, y) and clipped to it (+ buffer)."""
    n = 2 ** dz
    ox, oy = (x % n) * EXTENT / n, (y % n) * EXTENT / n
    out = []
    for f in feats:
        g = f["geometry"]
        if not g.is_valid and g.geom_type in ("Polygon", "MultiPolygon"):
            g = g.buffer(0)
        moved = affine_transform(g, [n, 0, 0, n, -n * ox, -n * oy])
        if not moved.intersects(TILE_WITH_BUFFER):
            continue
        g2 = same_kind(moved.intersection(TILE_WITH_BUFFER), g.geom_type)
        if g2 is not None:
            out.append({"geometry": g2, "properties": f["properties"]})
    return out


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
        props = {"class": "city", "rank": min(int(sr) + 1, 10), "name": name,
                 **({"name:de": de, "name_de": de} if de else {})}
        if cap:
            props["capital"] = 2
        elif fc and "Admin-1" in fc:
            props["capital"] = 4
        out.append((lon, lat, int(math.floor(minz)) - PLACE_LEAD, props))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("ne", help="natural_earth_vector.sqlite (Planetiler's sources)")
    ap.add_argument("out")
    ap.add_argument("--maxzoom", type=int, help="leave out deeper zoom levels of the input")
    a = ap.parse_args()

    with open(a.input, "rb") as f:
        r = Reader(MmapSource(f))
        h, md = r.header(), r.metadata()
        raw = {t: d for t, d in all_tiles(r.get_bytes)}
    maxz = min(h["max_zoom"], a.maxzoom if a.maxzoom is not None else 99)
    places = ne_places(a.ne)
    by_tile = {}
    for lon, lat, first, props in places:
        for z in range(max(0, first), maxz + 1):
            fx, fy = tile_frac(z, lon, lat)
            t = (z, int(fx), int(fy))
            by_tile.setdefault(t, []).append({"geometry": Point((fx - t[1]) * EXTENT, (fy - t[2]) * EXTENT),
                                              "properties": props})
    keep_cache = {}

    def kept(z, x, y):
        """The LAST_NE layers of tile (z, x, y) that end at zoom z, ocean left out."""
        if (z, x, y) not in keep_cache:
            d = raw.get((z, x, y))
            layers = to_shapely(decode(d)) if d else {}
            keep_cache[(z, x, y)] = {k: [f for f in v if not (k == "water" and f["properties"].get("class") == "ocean")]
                                     for k, v in layers.items() if LAST_NE.get(k) == z}
        return keep_cache[(z, x, y)]

    out = {}
    for z in range(0, maxz + 1):
        for x in range(2 ** z):
            for y in range(2 ** z):
                t = (z, x, y)
                data = raw.get(t)
                extra = by_tile.get(t, [])
                if z <= min(LAST_NE.values()) and not extra:
                    if data:
                        out[t] = data if data[:2] == b"\x1f\x8b" else gzip.compress(data)
                    continue
                layers = to_shapely(decode(data)) if data else {}
                for src in sorted(set(LAST_NE.values())):
                    if z <= src:
                        continue
                    dz = z - src
                    for name, feats in kept(src, x >> dz, y >> dz).items():
                        moved = overzoom(feats, dz, x, y)
                        if moved:
                            layers.setdefault(name, []).extend(moved)
                if extra:
                    layers.setdefault("place", []).extend(extra)
                enc = encode(layers)
                if enc:
                    out[t] = enc
    vl = [v for v in md.get("vector_layers", []) if v["id"] != "place"]
    vl.append({"id": "place", "fields": {"class": "String", "rank": "Number", "capital": "Number", "iso_a2": "String",
                                         "name": "String", "name:de": "String", "name_de": "String"},
               "minzoom": 0, "maxzoom": maxz})
    md = {**md, "vector_layers": vl, "name": "OnPath overview",
          "description": f"World z0-{maxz} from Natural Earth + water polygons, places added (overview_merge.py)"}
    header = {"tile_type": TileType.MVT, "tile_compression": Compression.GZIP, "min_zoom": 0, "max_zoom": maxz,
              "min_lon_e7": -1800000000, "min_lat_e7": -850511287, "max_lon_e7": 1800000000,
              "max_lat_e7": 850511287, "center_zoom": 0, "center_lon_e7": 0, "center_lat_e7": 0}
    with open(a.out, "wb") as f:
        w = Writer(f)
        for t in sorted(out, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*t), out[t])
        w.finalize(header, md)
    print(f"overview base: {len(out)} tiles z0-{maxz}, {sum(len(v) for v in out.values()) / 1e6:.2f} MB before dedup, "
          f"{len(places)} NE places")


if __name__ == "__main__":
    main()
