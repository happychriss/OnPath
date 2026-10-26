#!/usr/bin/env python3
"""Long-distance route lines of a region package (docs/design/map-cells.md §20, build_package.sh): the overlays layer
"routes" at z7-9.

  package_routes.py ROUTES.osm.pbf OUT.ndjson

Input: the package cut reduced to route relations + their ways / nodes (osmium tags-filter r/route=hiking,foot,bicycle).
Only national and international networks (net >= 3: nwn / iwn, ncn / icn incl. EuroVelo) – the routes that matter at
a region's scale (make_style.py route tiers: below z11 only net >= 3); no regional / local routes, no node networks.
Same attributes as the cells' route pieces (extract_overlays.py merged_routes "majoronly" copies): kind (hike | bike),
net, ref, name (name:de first, as the base map's --languages=de), majoronly 1 – so the style's routes-<kind>-major-low
layers draw them unchanged. Lines are merged per kind + net + ref + name (fewer, longer features; tippecanoe
simplifies them per zoom). Newline-delimited GeoJSON for tippecanoe -P.
"""
import json
import os
import sys

ROUTE_KIND = {"hiking": "hike", "foot": "hike", "bicycle": "bike"}
MIN_NET = 3


def net_rank(network):
    """Same as extract_overlays.net_rank: 4 international, 3 national, 2 regional, 1 local, 0 unknown."""
    n = (network or "").strip().lower()
    return {"i": 4, "n": 3, "r": 2, "l": 1}.get(n[:1], 0)


def route_of(tags):
    """(kind, net, ref, name) of a route relation's tags, or None when it is not a long-distance hiking / cycle route."""
    if tags.get("type") != "route":
        return None
    kind = ROUTE_KIND.get(tags.get("route", ""))
    rank = net_rank(tags.get("network"))
    if not kind or rank < MIN_NET or tags.get("network:type") == "node_network":
        return None
    return kind, rank, tags.get("ref", ""), tags.get("name:de") or tags.get("name", "")


def main(src, out):
    import osmium
    from shapely.geometry import LineString, mapping
    from shapely.ops import linemerge

    way_best = {}  # way id -> {kind: (net, ref, name)} – the highest network per kind wins

    class Rel(osmium.SimpleHandler):
        def relation(self, r):
            k = route_of({t.k: t.v for t in r.tags})
            if not k:
                return
            kind, rank, ref, name = k
            for m in r.members:
                if m.type == "w":
                    d = way_best.setdefault(m.ref, {})
                    if kind not in d or d[kind][0] < rank:
                        d[kind] = (rank, ref, name)

    Rel().apply_file(src)
    groups = {}

    class Ways(osmium.SimpleHandler):
        def way(self, w):
            d = way_best.get(w.id)
            if not d:
                return
            try:
                coords = [(n.lon, n.lat) for n in w.nodes]
            except osmium.InvalidLocationError:
                return  # expected: a way cut at the extract edge without its nodes
            if len(coords) < 2:
                return
            for kind, (rank, ref, name) in d.items():
                groups.setdefault((kind, rank, ref, name), []).append(LineString(coords))

    Ways().apply_file(src, locations=True, idx="flex_mem")
    n = 0
    tmp = out + ".tmp"
    with open(tmp, "w") as f:
        for (kind, rank, ref, name), lines in sorted(groups.items()):
            props = {"kind": kind, "net": rank, "majoronly": 1}
            if ref:
                props["ref"] = ref
            if name:
                props["name"] = name
            f.write(json.dumps({"type": "Feature", "geometry": mapping(linemerge(lines)), "properties": props},
                               separators=(",", ":")) + "\n")
            n += 1
    os.replace(tmp, out)
    print(f"    routes: {len(way_best)} ways, {n} long-distance route lines (net >= {MIN_NET})")
    return n


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
