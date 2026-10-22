#!/usr/bin/env python3
"""Generate the OnPath MapLibre styles from design-token themes.

  python3 tools/make_style.py                  classic theme -> app/src/main/assets/style/onpath.json (format 1)
                                               and .../style/f2/onpath.json (format 2)
  python3 tools/make_style.py --theme NAME     tools/themes/NAME.json -> .../style/onpath-NAME.json (+ f2/)
  python3 tools/make_style.py --all            every theme in tools/themes/
  python3 tools/make_style.py --format 1|2     only one map package format (default: both)

Two map package formats (docs/design/map-data-size.md, docs/map-pipeline.md):
  format 1  overlays as GeoJSON files (routes, trails, ways, lowways, paths, trailnames .geojson)
  format 2  overlays as ONE vector tile file overlays.pmtiles (source "overlays", one source-layer per former GeoJSON
            file, lowways -> paths at z11-12); the base map has no path / track lines at z13-14, so every layer that
            read them reads overlays "paths" instead. Layer ids are the same in both (the app switches them by id).
The app picks format 2 when overlays.pmtiles exists (StyleLoader); metadata "onpath:format" says which one a file is.

All colours, widths / zoom stops, opacities, fonts and hillshade settings live in tools/themes/*.json
(token names: docs/map-style-notes.md). This file only holds the layer structure and order.
Schema: OpenMapTiles (Planetiler) in source "base"; overlays routes/trails GeoJSON; contours PMTiles.
Only MapLibre Native features are used (no JS-only properties).
"""
import argparse
import json
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import style_theme  # noqa: E402
from style_theme import token, zoom  # noqa: E402,F401  (make_style.token is part of the module API)
import style_trail_names  # noqa: E402

STYLE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "style")
DEFAULT_THEME = "classic"

T, Z = token, zoom
NAME = ["coalesce", ["get", "name:de"], ["get", "name_de"], ["get", "name"]]
# country labels left out when zoomed out (their names would cover their neighbours): ISO 3166 codes
MICRO_STATES = ["AD", "LI", "MC", "SM", "VA", "MT", "JE", "GG", "IM", "FO", "AX", "GI"]

L = []
FMT = 1  # map package format the style is built for (build())
# format 1 GeoJSON source -> format 2 source-layer of "overlays"
OVERLAY_LAYERS = {"routes": "routes", "trails": "trails", "ways": "ways", "paths": "paths", "lowways": "paths",
                  "trailnames": "trailnames", "barriers": "barriers"}

# Router classes (format 2 packages built with extract_overlays.py --wayclass; docs/map-style-notes.md "Router
# classes", owner decisions 2026-10-22): per activity group 0 good (green) / 1 possible (black) / 2 forbidden (red,
# dashed + sign: access bans, one-way) / 3 too hard (excluded only by sac_scale / mtb:scale / trail_visibility or
# as a via ferrata / steps: a plain grey line – owner after the renders: "it's not forbidden there"). A feature with the class key uses ONLY the class; features without it (older map cells) keep the
# tag rules. Activity groups = the app's BRouter profiles (BRouterEngine.profileFile), one style mode each, switched
# by MapController.setOverlay: foot = Hike, bike = Bike trekking + E-bike, touring = Bike touring, mtb = MTB + MTB e-bike.
CLASS_KEY = {"foot": "ch", "bike": "ct", "touring": "cf", "mtb": "cm"}
BARRIER_BIT = {"foot": 1, "bike": 2, "touring": 4, "mtb": 8}  # overlays "barriers" b = blocked-profile mask
# Hiking route band above the user's T level: grey, dashed (owner decision 2026-10-22 (3)). The app sets the real
# level at runtime (MapController.setSacLimit rewrites both filters); the style ships with the app default T3.
DEFAULT_SAC_LIMIT = 3


def has_class(mode):
    return ["has", CLASS_KEY[mode]]


def class_is(mode, value):
    return ["==", ["get", CLASS_KEY[mode]], value]


def out_path(theme, fmt=1):
    name = "onpath.json" if theme == DEFAULT_THEME else f"onpath-{theme}.json"
    return os.path.join(STYLE_DIR, name) if fmt == 1 else os.path.join(STYLE_DIR, f"f{fmt}", name)


def own_paths():
    """Format 2: the overlay's "paths" source-layer (every path, track, cycleway, steps, pedestrian way …)."""
    return {"source": "overlays", "source-layer": "paths"}


# format 2 "paths" attributes (extract_overlays.py tile_attrs): k t/p/x, sub = OSM highway unless path / track,
# tn tunnel, ac access=no, foot / bicycle, uv unpaved cycleway, vf via ferrata. OpenMapTiles' class "path" =
# highway path, footway, cycleway, bridleway, steps, pedestrian (subclass = highway).
SUB = ["coalesce", ["get", "sub"], ""]
NOT_TUNNEL_OWN = ["!", ["has", "tn"]]
OWN_PATH = ["all", ["!", ["has", "sub"]], ["!=", ["get", "k"], "t"]]  # highway=path
OWN_PATH_CLASS = ["any", OWN_PATH, ["in", SUB, ["literal", ["footway", "cycleway", "bridleway", "steps", "pedestrian"]]]]


def interp(*stops, base=1.2):
    return ["interpolate", ["exponential", base], ["zoom"], *stops]


def layer(**kw):
    lyr = {"id": kw.pop("id_"), **kw}
    L.append({k: lyr[k] for k in ("id", "type", "source", "source-layer") if k in lyr} | lyr)  # readable key order


def halo():
    return {"text-halo-color": T("label.halo.color"), "text-halo-width": T("label.halo.width"),
            "text-halo-blur": T("label.halo.blur")}


def bg():
    layer(id_="background", type="background", paint={"background-color": T("land.color")})


def landcover():
    for cls, minz in (("farmland", 0), ("grass", 0), ("wood", 0), ("wetland", 9), ("rock", 0), ("ice", 0),
                      ("sand", 9)):
        layer(id_=f"landcover-{cls}", type="fill", source="base", **{"source-layer": "landcover"},
              minzoom=minz, filter=["==", ["get", "class"], cls],
              paint={"fill-color": T(f"{cls}.color"), "fill-antialias": False})
    # Ground symbols (sprite "pattern-*", tools/make_sprite.py) only close in (GROUND_SYMBOLS_ZOOM, the last two zoom
    # steps before the app's max zoom 18 – owner 2026-10-20: below that plain landcover colour), very light.
    # None for forest and meadow, none for settlements (owner: too busy when cycling through a town).
    # OpenMapTiles classes / subclasses (Planetiler): grass = meadow, grassland, grass, scrub, heath, park … → plain colour;
    # wetland = natural=wetland (subclasses wetland, marsh, wet_meadow, swamp, bog, reedbed, …) → marsh tufts;
    # farmland: only subclasses orchard / vineyard get a symbol, plain farmland / farm none (see make_sprite.py).
    for pid, cls, sub in (("wetland", "wetland", None), ("orchard", "farmland", "orchard"),
                          ("vineyard", "farmland", "vineyard"), ("rock", "rock", None), ("sand", "sand", None)):
        flt = ["==", ["get", "class"], cls]
        if sub:
            flt = ["all", flt, ["==", ["get", "subclass"], sub]]
        layer(id_=f"landcover-{pid}-pattern", type="fill", source="base", **{"source-layer": "landcover"},
              minzoom=GROUND_SYMBOLS_ZOOM, filter=flt, paint={"fill-pattern": f"pattern-{pid}"})
    layer(id_="landcover-ice-outline", type="line", source="base", **{"source-layer": "landcover"}, minzoom=10,
          filter=["==", ["get", "class"], "ice"],
          paint={"line-color": T("ice.outline.color"), "line-width": Z("ice.outline.width"),
                 "line-dasharray": [3, 2]})
    layer(id_="landuse-residential", type="fill", source="base", **{"source-layer": "landuse"},
          filter=["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood", "commercial", "retail"]]],
          paint={"fill-color": T("residential.color"), "fill-opacity": Z("residential.opacity")})
    layer(id_="landuse-industrial", type="fill", source="base", **{"source-layer": "landuse"}, minzoom=11,
          filter=["in", ["get", "class"], ["literal", ["industrial", "garages", "railway", "quarry"]]],
          paint={"fill-color": T("industrial.color")})
    layer(id_="landuse-green", type="fill", source="base", **{"source-layer": "landuse"}, minzoom=11,
          filter=["in", ["get", "class"], ["literal", ["cemetery", "pitch", "stadium", "playground"]]],
          paint={"fill-color": T("leisure.color")})
    # The OpenMapTiles park layer also carries name points (format 1; trim_base.sh drops them in format 2) –
    # a point in a line layer makes MapLibre Native log "Invalid geometry in line layer".
    layer(id_="park-protected", type="line", source="base", **{"source-layer": "park"}, minzoom=9,
          filter=["!=", ["geometry-type"], "Point"],
          paint={"line-color": T("park.outline.color"), "line-width": Z("park.outline.width"),
                 "line-dasharray": [6, 3]})


def hillshade():
    # Two light sources: a broad one (default NW) and a weaker fill light (default W) for multi-directional modelling.
    for lid, key in (("hillshade-nw", "main"), ("hillshade-w", "fill")):
        k = f"hillshade.{key}"
        layer(id_=lid, type="hillshade", source="terrain", maxzoom=17,
              paint={"hillshade-illumination-direction": T(k + ".direction"),
                     "hillshade-illumination-anchor": "map",
                     "hillshade-exaggeration": Z(k + ".exaggeration"),
                     "hillshade-shadow-color": T(k + ".shadow"),
                     "hillshade-highlight-color": T(k + ".highlight"),
                     "hillshade-accent-color": T(k + ".accent")})


def contours():
    major = ["==", ["get", "idx"], 1]
    # owner 2026-10-20: contour lines always from z12 (z12 tiles carry the 50 m lines, z13+ the 20 m lines)
    layer(id_="contour-minor", type="line", source="contours", **{"source-layer": "contour"}, minzoom=12,
          filter=["all", ["!", major], ["any", [">=", ["zoom"], 14], ["==", ["%", ["get", "ele"], 50], 0]]],
          paint={"line-color": T("contour.minor.color"), "line-opacity": Z("contour.minor.opacity"),
                 "line-width": Z("contour.minor.width")})
    layer(id_="contour-major", type="line", source="contours", **{"source-layer": "contour"}, minzoom=12,
          filter=major,
          paint={"line-color": T("contour.major.color"), "line-opacity": Z("contour.major.opacity"),
                 "line-width": Z("contour.major.width")})
    layer(id_="contour-label", type="symbol", source="contours", **{"source-layer": "contour"}, minzoom=14,
          filter=major,
          layout={"symbol-placement": "line", "text-field": ["to-string", ["get", "ele"]],
                  "text-font": T("font.regular"), "text-size": Z("contour.label.size"),
                  "symbol-spacing": 350, "text-padding": 10},
          paint={"text-color": T("contour.label.color"), "text-halo-color": T("contour.label.halo"),
                 "text-halo-width": 1.2})


def water():
    for wid, inter, dash in (("waterway-stream", 0, False), ("waterway-stream-intermittent", 1, True)):
        paint = {"line-color": T("waterline.color"), "line-width": Z("stream.width")}
        if dash:
            paint["line-dasharray"] = T("stream.dash")
        layer(id_=wid, type="line", source="base", **{"source-layer": "waterway"}, minzoom=11,
              filter=["all", ["!=", ["get", "class"], "river"], ["!=", ["get", "brunnel"], "tunnel"],
                      ["==", ["coalesce", ["get", "intermittent"], 0], inter]],
              paint=paint)
    layer(id_="waterway-river", type="line", source="base", **{"source-layer": "waterway"}, minzoom=8,
          filter=["all", ["==", ["get", "class"], "river"], ["!=", ["get", "brunnel"], "tunnel"]],
          layout={"line-cap": "round"},
          paint={"line-color": T("waterline.color"), "line-width": Z("river.width")})
    layer(id_="water", type="fill", source="base", **{"source-layer": "water"},
          filter=["!=", ["get", "brunnel"], "tunnel"],
          paint={"fill-color": T("water.color")})
    layer(id_="water-outline", type="line", source="base", **{"source-layer": "water"}, minzoom=12,
          paint={"line-color": T("water.outline.color"), "line-width": Z("water.outline.width")})


def buildings():
    # Neutral grey blocks; a separate outline line from z15 (fill-outline-color is only 1 device px, too faint).
    layer(id_="building", type="fill", source="base", **{"source-layer": "building"}, minzoom=14,
          paint={"fill-color": T("building.fill"), "fill-opacity": Z("building.opacity")})
    layer(id_="building-outline", type="line", source="base", **{"source-layer": "building"}, minzoom=15,
          paint={"line-color": T("building.outline"), "line-width": Z("building.outline.width"),
                 "line-opacity": interp(15, 0.6, 16, 1, base=1)})


ROAD_GROUPS = [
    # id, classes, minzoom, colour token prefix
    ("service", ["service"], 13, "road"),
    ("minor", ["minor"], 11, "road"),
    # busier roads stand out by a yellow-to-orange scale by road class (OSM has no traffic counts)
    ("tertiary", ["tertiary"], 10, "tertiary"),
    ("secondary", ["secondary"], 8, "secondary"),
    ("primary", ["primary"], 7, "primary"),
    ("motorway", ["motorway", "trunk"], 6, "motorway"),
]
ON_BRIDGE = ["case", ["==", ["get", "brunnel"], "bridge"], 1, 0]  # bridges over the way below at crossings
STREETS = ("service", "minor")  # small streets: two grey edges with a light lane (owner: no white lines)
# Format 2: from z13 the small streets come from overlays "streets" (smoothed, OpenMapTiles attribute names, so the
# filters stay the same); tools/trim_base.sh drops them from the base map there. Owner 2026-10-21.
STREETS_ZOOM = 13
OWN_STREETS = {"source": "overlays", "source-layer": "streets"}


def street_layer(id_, minzoom, **kw):
    """A layer of small streets (STREETS classes): format 1 from the base map; format 2 from the base map below
    STREETS_ZOOM (id + "-low") and from overlays "streets" above (same id, so ids stay the same in both formats)."""
    if FMT != 2:
        layer(id_=id_, source="base", **{"source-layer": "transportation"}, minzoom=minzoom, **kw)
        return
    if minzoom < STREETS_ZOOM:
        layer(id_=f"{id_}-low", source="base", **{"source-layer": "transportation"}, minzoom=minzoom,
              maxzoom=STREETS_ZOOM, **kw)
    layer(id_=id_, **OWN_STREETS, minzoom=max(minzoom, STREETS_ZOOM), **kw)


def roads():
    cls = ["get", "class"]
    tunnel = ["==", ["get", "brunnel"], "tunnel"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    # casings (non-tunnel), then fills; tunnels faint
    for gid, classes, minz, col in ROAD_GROUPS:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        paint = ({"line-color": T("street.edge.color"), "line-gap-width": Z(f"street.{gid}.gap"),
                  "line-width": Z("street.edge.width")} if gid in STREETS else
                 {"line-color": T(f"{col}.casing"), "line-gap-width": Z(f"road.{gid}.width"),
                  "line-width": Z("road.casing.width")})
        kw = dict(type="line", filter=f, layout={"line-join": "round", "line-cap": "round", "line-sort-key": ON_BRIDGE},
                  paint=paint)
        if gid in STREETS:
            street_layer(f"road-{gid}-casing", max(minz, 10), **kw)
        else:
            layer(id_=f"road-{gid}-casing", source="base", **{"source-layer": "transportation"}, minzoom=max(minz, 10), **kw)
    for gid, classes, minz, col in ROAD_GROUPS:
        kw = dict(type="line", filter=["all", ["in", cls, ["literal", classes]], tunnel], layout={"line-join": "round"},
                  paint={"line-color": T("tunnel.color"), "line-width": Z(f"road.{gid}.width"),
                         "line-opacity": T("tunnel.opacity"), "line-dasharray": [2, 1]})
        if gid in STREETS:
            street_layer(f"road-{gid}-tunnel", 12, **kw)
        else:
            layer(id_=f"road-{gid}-tunnel", source="base", **{"source-layer": "transportation"}, minzoom=12, **kw)


def track_edges():
    """Edges of two-edge tracks (and the single dashed track below TRACK_DOTS_ZOOM). Drawn with the road casings,
    below every lane / road fill (lanes()), so a track meeting a street or road stops at that road's edge."""
    own = {"source": "paths", "minzoom": LOW_ZOOM_SOLID}
    k = ["get", "k"]
    track = ["all", ["==", k, "t"], ["!", ["has", "pv"]]]
    tt = ["coalesce", ["get", "tt"], 0]
    layer(id_="track-own-paved", type="line", **own, filter=["all", ["==", k, "t"], ["has", "pv"]],
          layout={"line-join": "round"},
          paint={"line-color": T("street.edge.color"), "line-width": Z("street.edge.width"), "line-gap-width": Z("street.minor.gap")})
    layer(id_="track-own-single", type="line", **own, maxzoom=TRACK_DOTS_ZOOM, filter=track, layout={"line-join": "round"},
          paint={"line-color": T("track.color"), "line-width": Z("track.single.width"), "line-dasharray": T("track.dash.single")})
    # Dotted edges: butt caps + miter joins, dots = short dashes (theme track.dash.*, square). MapLibre gives every
    # vertex of a round join fan and of a round cap the same distance along the line, so with round caps / joins
    # the whole join arc (outer side of every bend) and the half ring at each way end took one dash value – nearly
    # always "on" with dots that close together – and drew solid brown (owner 2026-10-20). Butt caps end the edges
    # flat under the lane's round cap; miter joins add no fan (sharp hairpins fall back to a small bevel).
    # Kept on purpose when every other way got round joins (2026-10-21): smoothed tracks (extract_overlays --smooth)
    # turn by at most ~15° per vertex, where miter and round look the same; a round join would bring the solid arcs
    # back at the corners that stay sharp (junction nodes).
    for sid, f in (("compact", ["==", tt, 1]), ("medium", ["any", ["==", tt, 2], ["==", tt, 0]]),
                   ("loose", ["==", tt, 3]), ("soft", [">=", tt, 4])):
        layer(id_=f"track-own-{sid}", type="line", source="paths", minzoom=TRACK_DOTS_ZOOM, filter=["all", track, f],
              layout={"line-cap": "butt", "line-join": "miter"},
              paint={"line-color": T("track.color"), "line-width": Z("track.dot.width"), "line-gap-width": Z("track.gap"),
                     "line-dasharray": T(f"track.dash.{sid}")})


def lanes():
    """All lane fills, after ALL edges / casings (track_edges(), roads()) – classic casing-then-fill order across every
    way class (owner 2026-10-19: crossings looked messy, side-street edges ran across the main street). A two-edge way
    without a fill let the edges of one way run straight across the other way's lane, and the round caps closed every
    way piece with a ring / "c"-hook at each junction. With the lanes on top, junctions merge into one road and end caps
    disappear under the neighbour's lane. Order = who wins at a junction: tracks < paved tracks < service < minor <
    tertiary … motorway, so a smaller way's lane never cuts the bigger road's lane. Bridges sort above (line-sort-key)."""
    own = {"source": "paths", "minzoom": LOW_ZOOM_SOLID}
    k = ["get", "k"]
    track = ["all", ["==", k, "t"], ["!", ["has", "pv"]]]
    cls = ["get", "class"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    rl = {"line-join": "round", "line-cap": "round"}
    # Track lanes: exactly the gap width, so the own edges stay fully visible; slightly transparent (token), so the
    # route band under the track still tints the lane a little.
    lane = {"line-color": T("track.lane.color"), "line-opacity": T("track.lane.opacity")}
    layer(id_="track-own-lane", type="line", source="paths", minzoom=TRACK_DOTS_ZOOM, filter=track,
          layout=rl, paint={**lane, "line-width": Z("track.gap")})
    # paved track = small street: street lane
    slane = {"line-color": T("street.lane.color"), "line-opacity": T("street.lane.opacity")}
    layer(id_="track-own-paved-lane", type="line", **own, filter=["all", ["==", k, "t"], ["has", "pv"]],
          layout=rl, paint={**slane, "line-width": Z("street.minor.gap")})
    for gid, classes, minz, col in ROAD_GROUPS:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        lay = {**rl, "line-sort-key": ON_BRIDGE}
        if gid in STREETS:
            # small streets: light lane exactly the gap between the two grey edges (edges stay as in 0.7.0)
            street_layer(f"road-{gid}-lane", max(minz, 10), type="line", filter=f, layout=lay,
                         paint={**slane, "line-width": Z(f"street.{gid}.gap")})
            continue
        fill = T(f"{col}.fill")
        layer(id_=f"road-{gid}", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=minz, filter=f, layout=lay,
              # below z10 there is no casing: draw major roads in their casing colour so they stay visible
              paint={"line-color": fill if minz >= 10 else ["step", ["zoom"], T(f"{col}.casing"), 10, fill],
                     "line-width": Z(f"road.{gid}.width")})


def railways():
    cls = ["get", "class"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    # Railways are an important orientation feature (owner request): visible from z8, with a light
    # casing so they read on any background, and a classic white hatch from z12.
    layer(id_="rail-casing", type="line", source="base", **{"source-layer": "transportation"}, minzoom=11,
          filter=["all", ["==", cls, "rail"], not_tunnel],
          paint={"line-color": T("rail.casing.color"), "line-width": Z("rail.casing.width")})
    layer(id_="rail", type="line", source="base", **{"source-layer": "transportation"}, minzoom=8,
          filter=["all", ["==", cls, "rail"], not_tunnel],
          paint={"line-color": T("rail.color"), "line-width": Z("rail.width")})
    layer(id_="rail-hatch", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          filter=["all", ["==", cls, "rail"], not_tunnel],
          paint={"line-color": T("rail.hatch.color"), "line-width": Z("rail.hatch.width"),
                 "line-dasharray": [3, 3]})
    layer(id_="aerialway", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          filter=["in", cls, ["literal", ["aerialway", "cable_car"]]],
          paint={"line-color": T("aerialway.color"), "line-width": Z("aerialway.width")})


def route_bands():
    # One transparent green band without edges for every activity (owner 2026-10-19, colour from the analysis in
    # docs/design/map-colour-analysis.md), drawn over street / road lanes (they show green-tinted) and under paths and trails, so the ways stay readable.
    # extract_overlays.py merges route pieces into continuous lines (the transparent band would darken where pieces
    # meet); the original pieces of major routes come again as "majoronly" copies for the pink highlight and labels.
    # The app shows the band of the current activity (MapController.setOverlay toggles routes-<kind>*).
    # No MTB band since 2026-10-21 (map vs router audit #7): mtb.brf ignores route=mtb relations and rewards cycle
    # routes, so MTB / MTB e-bike show the cycle route band ("routes-bike").
    for kind, minz in (("hike", 10), ("bike", 9)):
        vis = "visible" if kind == "hike" else "none"
        flt = ["==", ["get", "kind"], kind]
        band_w = Z("route.band.width", interp(9, 1.2, 12, 2.0, 13, 2.6, 14, 7, 15, 12, 18, 18, base=1))
        # optional dark edge on both sides of the band (theme token): the band reads on forest green and on pale land
        if T("route.band.edge.color", None):
            layer(id_=f"routes-{kind}-edge", type="line", source="routes", minzoom=minz, filter=flt,
                  layout={"line-join": "round", "line-cap": "round", "visibility": vis},
                  paint={"line-color": T("route.band.edge.color"), "line-width": Z("route.band.edge.width")})
        band = ["all", flt, ["!", ["has", "majoronly"]]]
        if kind == "hike":
            band = hike_band_filter(DEFAULT_SAC_LIMIT, over=False)
        layer(id_=f"routes-{kind}", type="line", source="routes", minzoom=minz,
              filter=band,
              layout={"line-join": "round", "line-cap": "round", "visibility": vis},
              # Zoomed out the route IS a thin line on the way (no empty band); from z13.5 it widens into the
              # soft band. Our own look (owner: similar to Komoot, not a copy).
              paint={"line-color": ["interpolate", ["linear"], ["zoom"], 13, T("route.line.color"), 14, T("route.band.color")],
                     "line-opacity": T("route.opacity"),
                     "line-width": band_w})
        if kind == "hike":
            # pieces over trails above the user's T level (the router forbids them): grey dashes instead of the band
            # (owner after the renders 2026-10-22: a plain grey band, no dashes)
            layer(id_="routes-hike-over", type="line", source="routes", minzoom=minz,
                  filter=hike_band_filter(DEFAULT_SAC_LIMIT, over=True),
                  layout={"line-join": "round", "line-cap": "round", "visibility": vis},
                  paint={"line-color": T("route.over.color", "#6f6f6f"), "line-opacity": T("route.over.opacity", 0.55),
                         "line-width": band_w})
        # Major routes (regional / national / international network, e.g. Via Claudia Augusta, E5, Etschradweg):
        # Komoot-like – the band itself alternates green and soft pink dashes (same width, slightly transparent, so
        # the path inside stays visible), plus the route name along the line.
        major = ["all", flt, [">=", ["coalesce", ["get", "net"], 0], 2]]
        # zoomed out: the major route as a thin solid pink line on top of the green one
        layer(id_=f"routes-{kind}-major-low", type="line", source="routes", minzoom=minz, maxzoom=13.5,
              filter=major, layout={"line-join": "round", "line-cap": "round", "visibility": vis},
              paint={"line-color": T("route.major.line.color"), "line-width": interp(9, 1.2, 12, 2.0, 13, 2.6, base=1)})
        layer(id_=f"routes-{kind}-major", type="line", source="routes", minzoom=13.5,
              filter=major, layout={"line-join": "round", "visibility": vis},
              paint={"line-color": T("route.major.color"), "line-width": Z("route.width"),
                     "line-opacity": T("route.major.opacity"), "line-dasharray": T("route.major.dash")})
        layer(id_=f"routes-{kind}-major-label", type="symbol", source="routes", minzoom=12,
              filter=["all", major, ["!=", ["coalesce", ["get", "name"], ""], ""]],
              layout={"symbol-placement": "line", "symbol-spacing": interp(12, 300, 16, 500),
                      "text-field": ["get", "name"], "text-font": T("font.medium"), "text-size": interp(12, 10, 16, 12, base=1),
                      "text-max-angle": 30, "text-padding": 4, "text-offset": [0, 1.0], "visibility": vis},
              paint={"text-color": T("route.major.label.color"), "text-halo-color": T("label.halo.color"),
                     "text-halo-width": 1.5})


def hike_band_filter(limit, over):
    """Hiking route band pieces at / below (over=False) or above (over=True) the T level `limit` (route "sac", set by
    extract_overlays.py; pieces without it count as T0), and pieces the hiking profile forbids at any level ("hx":
    via ferrata, trail_visibility=no, private – packages with router classes). MapController.setSacLimit builds the
    same two filters."""
    sac = ["coalesce", ["get", "sac"], 0]
    return ["all", ["==", ["get", "kind"], "hike"], ["!", ["has", "majoronly"]],
            ["any", [">", sac, limit], ["has", "hx"]] if over else ["all", ["<=", sac, limit], ["!", ["has", "hx"]]]]


def paths():
    """Ways for walking and riding (owner 2026-10-19, docs/design/map-colour-analysis.md), all without white:
    path = thin black line; mountain trail = black dots (trails()); track (forest / farm road) = brown – one dashed
    line up to z14.5, then two dotted edges whose dot spacing shows the surface (tracktype); paved track = like a
    small street (two grey edges). From z13 paths and tracks come from our own exact paths.geojson (extract_overlays),
    so a dotted trail has no solid base-map line underneath. Without that file (older map packages) the base map's
    lines are drawn instead (layers marked "onpath:fallback-for": "paths", kept by the app's OptionalSources only then)."""
    cls = ["get", "class"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    paved = ["==", ["get", "surface"], "paved"]
    base_track = ["all", ["==", cls, "track"], not_tunnel]
    foot = ["all", ["==", cls, "path"], ["!=", ["get", "subclass"], "cycleway"], not_tunnel]
    steps = ["==", ["get", "subclass"], "steps"]
    pw = Z("path.width")
    fb = {"onpath:fallback-for": "paths"}
    tl = {"source": "base", "source-layer": "transportation"}
    # zoomed out (base map): one thin solid darker line
    layer(id_="track-low", type="line", **tl, minzoom=12, maxzoom=LOW_ZOOM_SOLID, filter=base_track,
          layout={"line-join": "round"}, paint={"line-color": T("track.color"), "line-width": Z("track.low.width")})
    layer(id_="path-low", type="line", **tl, minzoom=12, maxzoom=LOW_ZOOM_SOLID, filter=["all", foot, ["!", steps]],
          layout={"line-join": "round"}, paint={"line-color": T("path.low.color"), "line-width": pw})
    # fallback without paths.geojson (format 1 only: format 2 always has the overlay)
    if FMT == 1:
        paths_base_fallback(tl, base_track, foot, steps, pw, fb)
    if FMT == 2:
        own = own_paths()
        cw = Z("path.width")  # a plain path line (owner 2026-10-21: no blue ways)
        cyc = ["all", ["==", SUB, "cycleway"], NOT_TUNNEL_OWN]
        layer(id_="cycleway", type="line", **own, minzoom=13, layout={"line-join": "round"},
              filter=["all", cyc, ["!", ["has", "uv"]]],
              paint={"line-color": T("path.color"), "line-width": cw})
        layer(id_="cycleway-unpaved", type="line", **own, minzoom=13, layout={"line-join": "round"},
              filter=["all", cyc, ["has", "uv"]],
              paint={"line-color": T("path.color"), "line-width": cw, "line-dasharray": T("cycleway.dash")})
        layer(id_="path-steps", type="line", **own, minzoom=14, layout={"line-join": "round"},
              filter=["all", ["==", SUB, "steps"], NOT_TUNNEL_OWN],
              paint={"line-color": T("path.color"), "line-width": Z("steps.width"), "line-dasharray": T("steps.dash")})
    else:
        paths_base_lines(tl, foot, steps)
    # own exact data
    own = {"source": "paths", "minzoom": LOW_ZOOM_SOLID}
    k = ["get", "k"]
    layer(id_="paths-own", type="line", **own,
          filter=["all", ["==", k, "p"], ["!", ["has", "st"]], ["<", ["get", "sac"], 2], ["<", ["coalesce", ["get", "vis"], 0], 3]],
          layout={"line-join": "round", "line-cap": "round"}, paint={"line-color": T("path.color"), "line-width": pw})


def paths_base_fallback(tl, base_track, foot, steps, pw, fb):
    layer(id_="track-base", type="line", **tl, minzoom=LOW_ZOOM_SOLID, filter=base_track, metadata=fb,
          layout={"line-join": "round"},
          paint={"line-color": T("track.color"), "line-width": Z("track.single.width"), "line-dasharray": T("track.dash.single")})
    layer(id_="path-base", type="line", **tl, minzoom=LOW_ZOOM_SOLID, filter=["all", foot, ["!", steps]], metadata=fb,
          layout={"line-join": "round"}, paint={"line-color": T("path.color"), "line-width": pw})


def paths_base_lines(tl, foot, steps):
    """Format 1: cycleways (black path line, unpaved dashed – green on top where good for the activity, green_ways())
    and steps from the base map."""
    cls = ["get", "class"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    cyc = ["all", ["==", cls, "path"], ["==", ["get", "subclass"], "cycleway"], not_tunnel]
    cw = Z("path.width")  # a plain path line (owner 2026-10-21: no blue ways)
    layer(id_="cycleway", type="line", **tl, minzoom=13, layout={"line-join": "round"},
          filter=["all", cyc, ["!=", ["get", "surface"], "unpaved"]],
          paint={"line-color": T("path.color"), "line-width": cw})
    layer(id_="cycleway-unpaved", type="line", **tl, minzoom=13, layout={"line-join": "round"},
          filter=["all", cyc, ["==", ["get", "surface"], "unpaved"]],
          paint={"line-color": T("path.color"), "line-width": cw, "line-dasharray": T("cycleway.dash")})
    layer(id_="path-steps", type="line", **tl, minzoom=14, layout={"line-join": "round"},
          filter=["all", foot, steps],
          paint={"line-color": T("path.color"), "line-width": Z("steps.width"), "line-dasharray": T("steps.dash")})


def surface_loose():
    """Bike activities only (MapController.setOverlay shows the "surface-loose-*" layers): a thin warm-brown
    edge on both sides of ways with a loose surface (gravel/stony or natural soft ground, ways.geojson surf ≥ 3)
    or bad smoothness (smooth ≥ 3). Compacted / fine gravel do not count – fine on any bike."""
    loose = ["any", [">=", ["coalesce", ["get", "surf"], 0], 3], [">=", ["coalesce", ["get", "smooth"], 0], 3]]
    for sid, kind, gap in (("track", ["==", ["get", "k"], "t"], "track.gap"),
                           ("path", ["!=", ["get", "k"], "t"], "path.loose.gap")):
        layer(id_=f"surface-loose-{sid}", type="line", source="ways", minzoom=13, filter=["all", kind, loose],
              layout={"line-join": "round", "visibility": "none"},
              paint={"line-color": T("surface.loose.color"), "line-width": Z("surface.loose.width"),
                     "line-gap-width": Z(gap)})


def trails():
    """Komoot-like path rule (owner, 2026-10-18), all black – difficulty by rhythm, not colour:
    no grade / T1 = solid (drawn by the base map's path layers), T2–T3 mountain hiking path = round dots,
    T4–T6 alpine path = dot-dash. An opaque light underlay under the dotted ones hides the base map's solid
    line of the same way, so only one clean row of dots shows. The T-labels along the trail give the exact grade."""
    sac = ["get", "sac"]
    # trail_visibility bad or worse: same rhythm, but sparser and lighter dots (faint trail on the ground)
    faint = [">=", ["coalesce", ["get", "vis"], 0], 3]
    graded = ["any", [">=", sac, 2], faint]
    # Zoomed out: one thin solid dark line for every graded trail (dots would only read as noise).
    # Zoomed out every path / track from our own data (the base map has them only from z12–13), so every route line
    # sits on a visible way – like Komoot. Tracks lighter than paths.
    layer(id_="trails-low", type="line", source="lowways", minzoom=11, maxzoom=LOW_ZOOM_SOLID,
          layout={"line-join": "round"},
          paint={"line-color": ["case", ["==", ["get", "k"], "t"], T("track.low.color"), T("trail.low.color")],
                 # one zoom curve with the case inside (MapLibre allows only one zoom expression per property;
                 # a zoom curve inside "case" made MapLibre drop the whole layer)
                 "line-width": interp(11, ["case", ["==", ["get", "k"], "t"], T("lowways.track.width.z11", 0.6), T("lowways.path.width.z11", 0.8)],
                                      13, ["case", ["==", ["get", "k"], "t"], T("lowways.track.width.z13", 1.0), T("lowways.path.width.z13", 1.3)], base=1)})
    for sid, flt in (("mountain", ["all", [">=", sac, 2], ["<=", sac, 3]]), ("alpine", [">=", sac, 4])):
        layer(id_=f"trails-{sid}", type="line", source="trails", minzoom=LOW_ZOOM_SOLID, filter=["all", flt, ["!", faint]],
              layout={"line-cap": "round", "line-join": "round"},
              paint={"line-color": T("trail.color"), "line-width": Z("trail.width"),
                     "line-dasharray": T(f"trail.{sid}.dash")})
    for sid, flt in (("mountain", ["<=", sac, 3]), ("alpine", [">=", sac, 4])):
        layer(id_=f"trails-{sid}-faint", type="line", source="trails", minzoom=LOW_ZOOM_SOLID, filter=["all", flt, faint],
              layout={"line-cap": "round", "line-join": "round"},
              paint={"line-color": T("trail.faint.color"), "line-width": Z("trail.width"),
                     "line-dasharray": T(f"trail.faint.{sid}.dash")})
    # SAC grade ("T1" … "T6") as small black text along the trail, on the other side than the
    # trail name (offset +), so both stay readable. Low priority: dropped first when space is short.
    layer(id_="trails-sac-label", type="symbol", source="trails", minzoom=14, filter=[">=", sac, 1],
          layout={"symbol-placement": "line", "symbol-spacing": interp(14, 220, 17, 320),
                  "text-field": ["concat", "T", ["to-string", sac]], "text-font": T("font.regular"),
                  "text-size": Z("trail.sac.label.size"), "text-max-angle": 30, "text-padding": 2,
                  "text-offset": [0, 0.75]},
          paint={"text-color": T("trail.sac.label.color"), "text-halo-color": T("label.halo.color"),
                 "text-halo-width": 1.2})
    # MTB / MTB e-bike (MapController.setOverlay shows this instead of the T-label): MTB scale ("S0" … "S5") and the
    # SAC grade in one label ("T3 S2") – mtb.brf uses both; two labels on the same anchors would hide each other.
    # Every other activity sees the T-label (since 2026-10-21 also the bikes: trekking / touring forbid T3+).
    t_text = ["concat", "T", ["to-string", sac]]
    s_text = ["concat", "S", ["get", "mtb"]]
    layer(id_="trails-mtb-label", type="symbol", source="trails", minzoom=14,
          filter=["any", ["has", "mtb"], [">=", sac, 1]],
          layout={"symbol-placement": "line", "symbol-spacing": interp(14, 220, 17, 320),
                  "text-field": ["case", ["all", ["has", "mtb"], [">=", sac, 1]], ["concat", t_text, " ", s_text],
                                 ["has", "mtb"], s_text, t_text], "text-font": T("font.regular"),
                  "text-size": Z("trail.sac.label.size"), "text-max-angle": 30, "text-padding": 2,
                  "text-offset": [0, 0.75], "visibility": "none"},
          paint={"text-color": T("trail.sac.label.color"), "text-halo-color": T("label.halo.color"),
                 "text-halo-width": 1.2})


def green_ways():
    """Ways made for walking or cycling, like Komoot's thin green line (owner 2026-10-20: "there I know I can walk /
    ride well"). From the base map's transportation layer (class path + subclass + foot / bicycle tags), so no new map
    package is needed. Rule (owner 2026-10-21): green = good for the current activity, everything else the normal black
    line – no blue ways. Depends on the activity – the app switches them (MapController.setOverlay):
    - "-foot" (hiking, visible by default): footways, pedestrian zones and cycleways (shared use is common here),
      each unless foot=no, plus paths marked foot=designated;
    - "-bike" (Bike trekking / E-bike, hidden by default): every cycleway, plus footways / pedestrian zones / paths
      with bicycle=yes|designated (cycle lanes beside streets: bike_map());
    - "-touring" (Bike touring): the same, but footways / pedestrian zones / paths only with bicycle=designated;
    - "-mtb" (MTB / MTB e-bike): like "-bike" with MTB's own difficulty limits;
    - no bike layer is green on bicycle=dismount|use_sidepath or on trails the activity's router profile avoids.
    Pedestrian zones are also drawn as a plain path line (all activities) – they were missing since 0.7.0 because
    paths.geojson has no highway=pedestrian and the base map's path layer is only a fallback."""
    if FMT == 2:
        # same rules on the overlay's paths (sub = OSM highway; no sub + not a track = highway=path)
        src = own_paths()
        path = NOT_TUNNEL_OWN
        sub = SUB
        is_path = OWN_PATH
    else:
        src = {"source": "base", "source-layer": "transportation"}
        cls = ["get", "class"]; sub = ["get", "subclass"]
        path = ["all", ["==", cls, "path"], ["!=", ["get", "brunnel"], "tunnel"]]
        is_path = ["==", sub, "path"]
    layer(id_="pedestrian-line", type="line", **src, minzoom=13,
          filter=["all", path, ["==", sub, "pedestrian"]], layout={"line-join": "round", "line-cap": "round"},
          paint={"line-color": T("path.color"), "line-width": Z("greenway.width")})
    # never green when the activity may not use the way (owner 2026-10-21, private garden footway at Plarserhof):
    # foot / bicycle = no|private, or access=no/private without foot / bicycle = yes|designated|permissive – the same
    # rule as access_restrictions(), so a way never carries both the green line and the no-entry sign
    restricted = ["has", "ac"] if FMT == 2 else ["==", ["get", "access"], "no"]

    def may_use(tag):
        val = ["coalesce", ["get", tag], ""]
        return ["all", ["!", ["in", val, ["literal", ["no", "private"]]]],
                ["any", ["!", restricted], ["in", val, ["literal", ["yes", "designated", "permissive"]]]]]
    foot = ["all", path, may_use("foot"), ["any",
            ["in", sub, ["literal", ["footway", "pedestrian", "cycleway"]]],
            ["all", is_path, ["==", ["coalesce", ["get", "foot"], ""], "designated"]]]]
    # Bikes: one layer per BRouter profile family (map vs router audit 2026-10-21, MapController.setOverlay):
    # "-bike" = Bike trekking / E-bike (trekking.brf), "-touring" = Bike touring (fastbike-verylowtraffic.brf: a path
    # or footway costs 100 unless bicycle=designated, then 2), "-mtb" = MTB / MTB e-bike (mtb.brf).
    # Never green on hard trails (format 2: overlay "sac", and "mtb" = mtb:scale from map packages built after
    # 2026-10-21): trekking / touring forbid T3+ and add +30 on T2 and on mtb:scale 2–3, MTB forbids T4+, adds +15 on
    # T3 and ~9000 from mtb:scale 4. Format 1 (base map) has no grades – no difficulty rule there.
    bike_ok = ["all", ["!", ["in", ["coalesce", ["get", "bicycle"], ""], ["literal", ["dismount", "use_sidepath"]]]],
               may_use("bicycle")]

    def bike_way(values, max_sac, max_mtb):
        flt = ["all", path, bike_ok, ["any",
               ["==", sub, "cycleway"],
               ["all", ["any", ["in", sub, ["literal", ["footway", "pedestrian"]]], is_path],
                ["in", ["coalesce", ["get", "bicycle"], ""], ["literal", values]]]]]
        if FMT == 2:
            flt += [["<=", ["coalesce", ["get", "sac"], 0], max_sac], ["<=", ["coalesce", ["get", "mtb"], 0], max_mtb]]
        return flt
    # Router classes (format 2): green = the activity's class is "good" on a path-like way. Hiking: only ways built for
    # walking (footways, pedestrian zones, cycleways, foot=designated paths) – hiking-mountain.brf costs a plain path
    # like a footway, and plain paths keep their black / dotted difficulty look. Bikes: any path-like way (cycleway,
    # footway, pedestrian, bridleway, path) – the profile tells which ones are good. Never tracks or streets.
    hike_kind = ["all", path, ["any", ["in", sub, ["literal", ["footway", "pedestrian", "cycleway"]]],
                               ["all", is_path, ["==", ["coalesce", ["get", "foot"], ""], "designated"]]]]
    bike_kind = ["all", path, ["any", ["in", sub, ["literal", ["footway", "pedestrian", "cycleway", "bridleway"]]],
                               is_path]]
    for mode, flt, visible in (("foot", foot, True), ("bike", bike_way(["yes", "designated"], 1, 1), False),
                               ("touring", bike_way(["designated"], 1, 1), False),
                               ("mtb", bike_way(["yes", "designated"], 2, 3), False)):
        if FMT == 2:
            flt = ["case", has_class(mode), ["all", hike_kind if mode == "foot" else bike_kind, class_is(mode, 0)], flt]
        layer(id_=f"greenway-{mode}", type="line", **src, minzoom=13,
              filter=flt, layout={"line-join": "round", "line-cap": "round", "visibility": "visible" if visible else "none"},
              paint={"line-color": T("greenway.color"), "line-width": Z("greenway.width")})


def access_restrictions():
    """Ways you may not use, like Komoot's blocked sign: a light red tint along the way (z14+) and a small red
    no-entry sign repeated along it (z15+). OpenMapTiles sets "access": "no" for OSM access=no/private, but many
    marked trails are "access=no + foot=yes" (closed to vehicles, open to walkers) – so the marking depends on
    the activity: the "-foot" layers (hiking, visible by default) skip ways with foot=yes, the "-bike" layers
    (bike profiles, hidden by default) skip ways with bicycle=yes. The app switches them with the activity
    (MapController.setOverlay). Only paths, tracks, minor streets and non-driveway service roads – private driveways
    would put a sign at every house; for bikes also steps. Routing (BRouter) already respects these tags."""
    cls = ["get", "class"]
    allowed = ["literal", ["yes", "designated", "permissive"]]
    # streets: service roads (not driveways / parking aisles – a sign at every house) and, since 2026-10-21 (map vs
    # router audit #9), minor streets (residential / unclassified / living street): BRouter forbids them for everyone
    street = ["any", ["==", cls, "minor"],
              ["all", ["==", cls, "service"],
               ["!", ["in", ["get", "service"], ["literal", ["driveway", "parking_aisle"]]]]]]
    base = ["all", ["==", ["get", "access"], "no"],
            ["any", ["in", cls, ["literal", ["path", "track"]]], street]]
    # format 2: paths and tracks come from the overlay (ac = access no/private); the service roads from the overlay
    # "streets" (OpenMapTiles attribute names) – own layers "access-no-*-<mode>-service" (switched with the others by
    # MapController.setOverlay)
    own = ["all", ["has", "ac"], ["any", ["==", ["get", "k"], "t"], OWN_PATH_CLASS]]
    service = ["all", ["==", ["get", "access"], "no"], street]
    bsrc = {"source": "base", "source-layer": "transportation"}
    parts = [("", own_paths(), own), ("-service", OWN_STREETS, service)] if FMT == 2 else [("", bsrc, base)]
    # Bikes: steps look blocked too (audit 2026-10-21: MTB forbidden, touring 1000, trekking 40 = carry the bike),
    # unless bicycle=yes|designated (a ramp) – like any other blocked way
    steps = ["==", SUB, "steps"] if FMT == 2 else ["all", ["==", cls, "path"], ["==", ["get", "subclass"], "steps"]]

    def blocked_where(mode, where):
        return ["any", where, steps] if mode != "foot" and where is not service else where
    # Router classes (format 2): forbidden = the activity's class 2 on paths / tracks / via ferratas and on minor
    # streets / service roads (not driveways). Bike profiles differ (steps: MTB forbidden, trekking / touring only
    # expensive = black), so format 2 has one layer set per profile group; format 1 keeps foot / bike.
    class_where = {"": ["any", ["==", ["get", "k"], "t"], OWN_PATH_CLASS, ["==", SUB, "via_ferrata"]],
                   "-service": street}
    modes = ([("foot", "foot", True), ("bike", "bicycle", False), ("touring", "bicycle", False),
              ("mtb", "bicycle", False)] if FMT == 2 else [("foot", "foot", True), ("bike", "bicycle", False)])

    def blocked_filter(mode, tag, suffix, where):
        old = ["all", blocked_where(mode, where), ["!", ["in", ["coalesce", ["get", tag], ""], allowed]]]
        if FMT != 2:
            return old
        return ["case", has_class(mode), ["all", class_where[suffix], class_is(mode, 2)], old]
    for mode, tag, visible in modes:
        vis = {"visibility": "visible" if visible else "none"}
        for suffix, src, where in parts:
            layer(id_=f"access-no-tint-{mode}{suffix}", type="line", **src, minzoom=14,
                  filter=blocked_filter(mode, tag, suffix, where), layout={"line-join": "round", "line-cap": "round", **vis},
                  paint={"line-color": T("access.no.tint.color"), "line-width": Z("access.no.tint.width")})
        # owner decision 2026-10-22 (4): forbidden = red and dashed – short red dashes along the way from z13
        for suffix, src, where in parts:
            layer(id_=f"access-no-dash-{mode}{suffix}", type="line", **src, minzoom=13,
                  filter=blocked_filter(mode, tag, suffix, where), layout={"line-join": "round", **vis},
                  paint={"line-color": T("forbidden.color", "#c62828"), "line-width": Z("forbidden.width", 1.4),
                         "line-dasharray": T("forbidden.dash", [2, 2])})
        for suffix, src, where in parts:
            layer(id_=f"access-no-sign-{mode}{suffix}", type="symbol", **src, minzoom=15,
                  filter=blocked_filter(mode, tag, suffix, where),
                  layout={"symbol-placement": "line", "symbol-spacing": interp(15, 90, 18, 160),
                          "icon-image": "access-no", "icon-size": interp(15, 0.75, 18, 1.0, base=1),
                          "icon-rotation-alignment": "viewport", "icon-allow-overlap": False, "icon-padding": 1, **vis})


def via_ferrata():
    """Format 2: via ferratas (highway=via_ferrata or via_ferrata_scale, overlay paths "vf") get a warning look of their
    own (owner 2026-10-20): a dark red dashed line with short cross ticks – like the cross-hatched "Klettersteig"
    lines of Alpine club / swisstopo maps, and in red so it never reads as an ordinary trail. Drawn above the trail
    dots of the same way."""
    own = own_paths()
    vf = ["has", "vf"]
    layer(id_="via-ferrata-ticks", type="line", **own, minzoom=13, filter=vf,
          layout={"line-cap": "butt", "line-join": "miter"},
          paint={"line-color": T("viaferrata.color"), "line-width": Z("viaferrata.tick.width"),
                 "line-dasharray": T("viaferrata.tick.dash")})
    layer(id_="via-ferrata", type="line", **own, minzoom=13, filter=vf,
          layout={"line-cap": "butt", "line-join": "round"},
          paint={"line-color": T("viaferrata.color"), "line-width": Z("viaferrata.width"),
                 "line-dasharray": T("viaferrata.dash")})


# line-offset of a cycle lane beside the street: half the drawn street (lane / road fill + edge) plus ~1 px
BIKE_LANE_BIG = ["literal", ["motorway", "trunk", "primary", "secondary", "motorway_link", "trunk_link",
                             "primary_link", "secondary_link"]]


def bike_map():
    """Format 2, bike activities only (hidden by default, MapController.setOverlay): cycle lanes beside streets
    (overlay paths k="x" with cwl / cwr: thin green offset lines like the green ways – lane / track solid, shared
    lane / bus lane dashed), one-way arrows on streets
    (base map "oneway") and a blue double arrow where a one-way street is open to bikes both ways (oneway:bicycle=no)."""
    own = own_paths()
    sub = SUB

    def offset(sign):  # line-offset > 0 = right of the way's direction; one zoom curve with the class case inside
        return interp(*[x for z, big, ter, small in ((14, 4.5, 3.5, 2.5), (16, 7.5, 6.5, 4.0), (18, 13, 11.5, 6.5))
                        for x in (z, ["case", ["in", sub, BIKE_LANE_BIG], sign * big, ["==", sub, "tertiary"], sign * ter,
                                      sign * small])], base=1)
    hidden = {"visibility": "none"}
    for side, key, sign in (("left", "cwl", -1), ("right", "cwr", 1)):
        val = ["get", key]
        side_offset = offset(sign)
        for suffix, flt, dash in (("", ["in", val, ["literal", ["l", "t", "o"]]], None),
                                  ("-shared", ["in", val, ["literal", ["s", "b"]]], T("bike.lane.shared.dash"))):
            paint = {"line-color": T("greenway.color"), "line-width": Z("bike.lane.width"), "line-offset": side_offset}
            if dash:
                paint["line-dasharray"] = dash
            layer(id_=f"bike-lane-{side}{suffix}", type="line", **own, minzoom=14,
                  filter=["all", ["==", ["get", "k"], "x"], ["has", key], flt],
                  layout={"line-join": "round", **hidden}, paint=paint)
    cls = ["get", "class"]
    # bigger roads from the base map, small streets from overlays "streets" (base map trimmed at z13+) – same arrows,
    # MapController.setOverlay switches both
    # Router classes: "cr" = the bike profiles' one-way (1 along the line, -1 against it; absent = both ways). Small
    # streets with classes show the arrow only where bikes may not ride both ways (oneway:bicycle=no, contraflow lanes).
    for lid, src in (("bike-oneway", {"source": "base", "source-layer": "transportation"}),
                     ("bike-oneway-streets", OWN_STREETS)):
        old = ["all", ["in", ["coalesce", ["get", "oneway"], 0], ["literal", [1, -1]]],
               ["!", ["in", cls, ["literal", ["path", "track", "rail", "transit", "aerialway", "cable_car"]]]]]
        rotate = ["case", ["==", ["get", "oneway"], -1], 180, 0]
        if lid == "bike-oneway-streets":
            old = ["case", ["has", "ct"], ["has", "cr"], old]
            rotate = ["case", ["has", "cr"], ["case", ["==", ["get", "cr"], -1], 180, 0], rotate]
        layer(id_=lid, type="symbol", **src, minzoom=15, filter=old,
              layout={"symbol-placement": "line", "symbol-spacing": interp(15, 80, 18, 140), "icon-image": "oneway-arrow",
                      "icon-rotate": rotate, "icon-rotation-alignment": "map",
                      "icon-size": interp(15, 0.75, 18, 1.0, base=1), "icon-padding": 2, **hidden})
    # one-way paths, footways, cycleways, tracks (overlay "ow" = oneway, "owb" = oneway:bicycle; audit #13 / #14):
    # BRouter makes the reverse ×100 for bikes. oneway:bicycle wins (owb=0: the both-ways symbol below).
    owb = ["coalesce", ["get", "owb"], 9]
    way = ["any", ["==", ["get", "k"], "t"], OWN_PATH_CLASS]
    # a little bigger than on streets: the grey arrow sits on a thin black line, not on a light lane
    tag_oneway = ["all", way, ["any", ["all", ["has", "ow"], ["!", ["has", "owb"]]], ["in", owb, ["literal", [1, -1]]]]]
    layer(id_="bike-oneway-paths", type="symbol", **own, minzoom=15,
          filter=["case", ["has", "ct"], ["all", way, ["has", "cr"]], tag_oneway],
          layout={"symbol-placement": "line", "symbol-spacing": interp(15, 80, 18, 140), "icon-image": "oneway-arrow",
                  "icon-rotate": ["case", ["has", "cr"], ["case", ["==", ["get", "cr"], -1], 180, 0],
                                  ["has", "owb"], ["case", ["==", owb, -1], 180, 0],
                                  ["==", ["get", "ow"], -1], 180, 0], "icon-rotation-alignment": "map",
                  "icon-size": interp(15, 1.0, 18, 1.3, base=1), "icon-padding": 2, **hidden})
    layer(id_="bike-both-ways", type="symbol", **own, minzoom=15,
          filter=["all", ["has", "ow"], ["==", ["coalesce", ["get", "owb"], 1], 0]],
          layout={"symbol-placement": "line", "symbol-spacing": interp(15, 80, 18, 140), "icon-image": "bike-both-ways",
                  "icon-rotation-alignment": "map", "icon-size": interp(15, 0.8, 18, 1.05, base=1),
                  "icon-padding": 2, **hidden})


def too_hard():
    """Format 2 with router classes: ways the activity's profile excludes only for their difficulty (class 3: T3+ for
    trekking / touring, T4+ / mtb:scale 4+ for MTB, trail_visibility=no and via ferratas for hiking) – a plain grey line over the way,
    no red, no sign (owner 2026-10-22). One layer per profile group (MapController.setOverlay)."""
    own = own_paths()
    for mode in CLASS_KEY:
        layer(id_=f"too-hard-{mode}", type="line", **own, minzoom=13,
              filter=["all", ["has", CLASS_KEY[mode]], class_is(mode, 3)],
              layout={"line-join": "round", "line-cap": "round", "visibility": "visible" if mode == "foot" else "none"},
              paint={"line-color": T("toohard.color", "#8a8a8a"), "line-opacity": T("toohard.opacity", 0.75),
                     "line-width": Z("toohard.width", 2.0)})


def barriers():
    """Format 2 with router classes: barrier nodes (gates, stiles, bollards …) the activity's profile cannot pass
    (overlays "barriers", b = blocked-profile mask, extract_overlays.py --wayclass) – the no-entry sign on the node,
    one layer per profile group (MapController.setOverlay)."""
    for mode, bit in BARRIER_BIT.items():
        layer(id_=f"barrier-blocked-{mode}", type="symbol", source="barriers", minzoom=15,
              filter=["==", ["%", ["floor", ["/", ["coalesce", ["get", "b"], 0], bit]], 2], 1],
              layout={"icon-image": "access-no", "icon-size": interp(15, 0.7, 18, 0.95, base=1),
                      "icon-allow-overlap": True, "icon-ignore-placement": False,
                      "visibility": "visible" if mode == "foot" else "none"})


def bridges():
    cls = ["get", "class"]
    bridge = ["==", ["get", "brunnel"], "bridge"]
    kw = dict(type="line", minzoom=14, layout={"line-join": "round"},
              paint={"line-color": T("bridge.casing.color"), "line-gap-width": interp(14, 3.5, 16, 8, 18, 16),
                     "line-width": Z("bridge.casing.width")})
    roads = ["minor", "tertiary", "secondary", "primary", "trunk", "motorway"][1 if FMT == 2 else 0:]
    layer(id_="bridge-casing", source="base", **{"source-layer": "transportation"},
          filter=["all", bridge, ["in", cls, ["literal", roads]]], **kw)
    if FMT == 2:  # small streets from the overlay (format 2 base map has none at z13+)
        layer(id_="bridge-casing-streets", **OWN_STREETS, filter=["all", bridge, ["==", cls, "minor"]], **kw)


def boundaries():
    layer(id_="boundary-country", type="line", source="base", **{"source-layer": "boundary"},
          filter=["all", ["<=", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
          paint={"line-color": T("boundary.country.color"), "line-width": interp(4, 1, 12, 2.5),
                 "line-dasharray": [3, 1.5]})
    layer(id_="boundary-region", type="line", source="base", **{"source-layer": "boundary"}, minzoom=7,
          filter=["all", ["in", ["get", "admin_level"], ["literal", [3, 4]]], ["!=", ["get", "maritime"], 1]],
          paint={"line-color": T("boundary.region.color"), "line-width": interp(7, 0.6, 12, 1.4),
                 "line-dasharray": [3, 2]})


def labels():
    REG, MED, ITA, MINOR = T("font.regular"), T("font.medium"), T("font.italic"), T("font.minor-label")
    h = halo()
    # water names
    layer(id_="waterway-label", type="symbol", source="base", **{"source-layer": "waterway"}, minzoom=13,
          filter=["==", ["get", "class"], "river"],
          layout={"symbol-placement": "line", "text-field": NAME, "text-font": ITA, "text-size": 12,
                  "symbol-spacing": 400, "text-letter-spacing": 0.05},
          paint={"text-color": T("label.water.color"), **h})
    layer(id_="water-label", type="symbol", source="base", **{"source-layer": "water_name"}, minzoom=10,
          layout={"text-field": NAME, "text-font": ITA, "text-size": interp(10, 11, 15, 14),
                  "text-max-width": 7},
          paint={"text-color": T("label.water.color"), **h})
    # road names
    layer(id_="road-label", type="symbol", source="base", **{"source-layer": "transportation_name"}, minzoom=14,
          filter=["!", ["in", ["get", "class"], ["literal", ["path", "track", "rail", "transit"]]]],
          layout={"symbol-placement": "line", "text-field": NAME, "text-font": MINOR,
                  "text-size": interp(14, 10, 17, 12.5), "symbol-spacing": 300, "text-padding": 4},
          paint={"text-color": T("label.road.color"), **h})
    layer(id_="road-shield", type="symbol", source="base", **{"source-layer": "transportation_name"},
          minzoom=10, maxzoom=14,
          filter=["all", ["in", ["get", "class"], ["literal", ["motorway", "trunk", "primary"]]],
                  ["has", "ref"], ["<=", ["length", ["get", "ref"]], 6]],
          layout={"symbol-placement": "line", "text-field": ["get", "ref"], "text-font": MED,
                  "text-size": 10, "symbol-spacing": 450, "text-rotation-alignment": "viewport",
                  "text-padding": 20},
          paint={"text-color": T("label.shield.color"), "text-halo-color": T("label.shield.halo"),
                 "text-halo-width": 2.2})
    # saddles / passes
    layer(id_="pass-label", type="symbol", source="base", **{"source-layer": "mountain_peak"}, minzoom=12,
          filter=["all", ["==", ["get", "class"], "saddle"], ["has", "name"]],
          layout={"text-field": ["case", ["has", "ele"], ["concat", NAME, "\n", ["to-string", ["get", "ele"]], " m"], NAME],
                  "text-font": ITA, "text-size": 11,
                  "text-max-width": 8, "text-padding": 6},
          paint={"text-color": T("label.pass.color"), **h})
    # huts: drawn from the POI overlay (poi-* layers), see pois()
    # peaks
    peak = ["all", ["in", ["get", "class"], ["literal", ["peak", "volcano"]]], ["has", "name"]]
    peak_text = ["format", "▲", {"font-scale": 0.8, "text-color": T("label.peak.icon.color")}, "\n", {},
                 NAME, {}, "\n", {},
                 ["case", ["has", "ele"], ["concat", ["to-string", ["get", "ele"]], " m"], ""],
                 {"font-scale": 0.85}]
    layer(id_="peak-label", type="symbol", source="base", **{"source-layer": "mountain_peak"}, minzoom=10,
          filter=peak,
          layout={"text-field": peak_text, "text-font": MINOR, "text-size": interp(10, 11, 15, 13),
                  "text-anchor": "top", "text-offset": [0, -0.6], "text-max-width": 8,
                  "text-padding": 8,
                  "symbol-sort-key": ["-", 0, ["coalesce", ["get", "ele"], 0]]},
          paint={"text-color": T("label.peak.color"), **h})
    # places. Zoomed out (overview, docs/map-pipeline.md §9b): countries z2-6, capitals and big cities (OpenMapTiles
    # rank <= 3: Munich, Milan, Vienna …) z4-5, every city from z6, towns from z8. rank = 1 is the biggest; the
    # sort key places big ones first where labels collide. Lowest layer = lowest placement priority.
    place = ["get", "class"]
    by_rank = ["coalesce", ["get", "rank"], 99]
    layer(id_="place-country", type="symbol", source="base", **{"source-layer": "place"}, minzoom=2, maxzoom=7,
          filter=["all", ["==", place, "country"], ["!", ["in", ["coalesce", ["get", "iso_a2"], ""], ["literal", MICRO_STATES]]]],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(2, 10, 6, 13),
                  "text-transform": "uppercase", "text-letter-spacing": 0.12, "text-max-width": 6,
                  "text-padding": 6, "symbol-sort-key": by_rank},
          paint={"text-color": T("label.country.color"), **h})
    layer(id_="place-hamlet", type="symbol", source="base", **{"source-layer": "place"}, minzoom=12,
          filter=["in", place, ["literal", ["hamlet", "isolated_dwelling", "neighbourhood", "quarter", "locality"]]],
          layout={"text-field": NAME, "text-font": MINOR, "text-size": interp(12, 10.5, 16, 13),
                  "text-max-width": 7, "text-padding": 4},
          paint={"text-color": T("label.hamlet.color"), **h})
    layer(id_="place-suburb", type="symbol", source="base", **{"source-layer": "place"}, minzoom=11, maxzoom=16,
          filter=["==", place, "suburb"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(11, 11, 15, 13),
                  "text-transform": "uppercase", "text-letter-spacing": 0.1, "text-max-width": 7},
          paint={"text-color": T("label.suburb.color"), **h})
    layer(id_="place-village", type="symbol", source="base", **{"source-layer": "place"}, minzoom=10,
          filter=["==", place, "village"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(10, 11, 14, 14, 16, 16),
                  "text-max-width": 7},
          paint={"text-color": T("label.village.color"), **h})
    layer(id_="place-town", type="symbol", source="base", **{"source-layer": "place"}, minzoom=8,
          filter=["==", place, "town"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(8, 11.5, 12, 15, 15, 18),
                  "text-max-width": 7, "symbol-sort-key": by_rank},
          paint={"text-color": T("label.town.color"), **h})
    layer(id_="place-city", type="symbol", source="base", **{"source-layer": "place"}, minzoom=6, maxzoom=15,
          filter=["==", place, "city"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(6, 12.5, 10, 16, 14, 21),
                  "text-max-width": 7, "symbol-sort-key": by_rank},
          paint={"text-color": T("label.city.color"), **h})
    layer(id_="place-city-major", type="symbol", source="base", **{"source-layer": "place"}, minzoom=4, maxzoom=6,
          filter=["all", ["==", place, "city"], ["any", ["<=", by_rank, 3], ["all", ["==", ["get", "capital"], 2], ["<=", by_rank, 6]]]],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(4, 11, 6, 12.5),
                  "text-max-width": 7, "symbol-sort-key": by_rank},
          paint={"text-color": T("label.city.color"), **h})


# --- POIs (F-MAP-09): GeoJSON overlay from tools/extract_pois.py --------------------------------
# Icons come from the sprite (tools/make_sprite.py): "poi-<kind>" (badge) and "poi-<kind>-small" (dot).
# Tiers ("more at closer zoom"); within a tier the order is the placement priority (first = most important).
# Collision is on, so dense areas thin out; place/peak labels (above) win over POIs.
POI_TIERS = [
    # tier, icon minzoom, label minzoom, kinds
    # restaurants and cafés with huts and lifts from z12 (owner): a hike is planned around where you can eat
    (1, 12, 14, ["hut", "bivouac", "restaurant", "cafe", "viewpoint", "spring", "waterfall", "lift", "castle"]),
    # bars / snack bars and bus stops from z14
    (2, 14, 15, ["water", "bar", "fast_food", "shelter", "camping", "church", "ruins", "cave", "museum", "chapel"]),
    (3, 15, 16, ["picnic", "toilets", "info", "bike_shop", "charging", "shop", "parking"]),
]
# Bus stops have their own two layers (see pois()): zoomed out one symbol per stop name (the pair of stops on both
# sides of the road merged by extract_pois.py), from BUS_DETAIL_ZOOM every stop on its own.
BUS_ZOOM = 14
BUS_DETAIL_ZOOM = 16
POI_PRIO_EXTRA = ["bus"]
POI_PRIO = ["train"] + [k for _, _, _, kinds in POI_TIERS for k in kinds] + POI_PRIO_EXTRA
POI_KINDS = set(POI_PRIO)
POI_SMALL_UNTIL = 13  # tier 1 uses the small dot variant below this zoom
LOW_ZOOM_SOLID = 13   # below this zoom dashed paths / tracks / trails are drawn solid and darker
GROUND_SYMBOLS_ZOOM = 16  # ground symbol patterns only from here (app max zoom 18: the last two zoom steps)
TRACK_DOTS_ZOOM = 14.5  # from here a track is two dotted edges (dot spacing = surface), below one dashed line


def _match(values, fallback):
    expr = ["match", ["get", "kind"]]
    for k, v in values:
        expr += [k, v]
    return expr + [fallback]


def pois():
    kind = ["get", "kind"]
    rank = _match([(k, i) for i, k in enumerate(POI_PRIO)], 99)
    # lower = placed first; named POIs before unnamed ones of the same kind
    sort_key = ["+", ["*", rank, 2], ["case", ["has", "name"], 0, 1]]
    badge = ["concat", "poi-", kind]
    name = ["coalesce", ["get", "name"], ""]
    # Bottom-most layer = least important tier; MapLibre places symbols of upper layers first.
    # Bus stops: the merged pair symbol (plus unpaired stops) until BUS_DETAIL_ZOOM, then every stop.
    is_bus = ["==", kind, "bus"]
    specs = [
        (f"poi-bus-detail", 3, BUS_DETAIL_ZOOM, None, BUS_DETAIL_ZOOM, ["all", is_bus, ["!", ["has", "merged"]]]),
        (f"poi-bus", 3, BUS_ZOOM, BUS_DETAIL_ZOOM, 15, ["all", is_bus, ["!", ["has", "detail"]]]),
    ] + [(f"poi-tier{tier}", tier, minz, None, label_z, ["in", kind, ["literal", kinds]])
         for tier, minz, label_z, kinds in reversed(POI_TIERS)]
    for id_, tier, minz, maxz, label_z, flt in specs:
        icon = badge
        if minz < POI_SMALL_UNTIL:
            icon = ["step", ["zoom"], ["concat", "poi-", kind, "-small"], POI_SMALL_UNTIL, badge]
        extra = {"maxzoom": maxz} if maxz else {}
        layer(id_=id_, type="symbol", source="pois", minzoom=minz, filter=flt, **extra,
              layout={"icon-image": icon,
                      # small dots stay tiny while zoomed out, badges from z13
                      "icon-size": interp(12, 0.45, 12.9, 0.5, 13, 0.78, 15, 0.92, 17, 1.05, base=1),
                      "icon-allow-overlap": False, "icon-padding": 1,
                      "symbol-sort-key": sort_key, "symbol-z-order": "auto",
                      "text-field": ["step", ["zoom"], "", label_z, name],
                      "text-optional": True,
                      "text-font": T("font.medium") if tier == 1 else T("font.minor-label"),
                      "text-size": interp(14, 10.5, 17, 12.5, base=1),
                      "text-variable-anchor": ["left", "right", "top", "bottom"],
                      "text-radial-offset": 1.05, "text-justify": "auto", "text-max-width": 8,
                      "text-padding": 2},
              paint={"text-color": T("poi.label.color") if tier > 1 else T("poi.label.tier1.color"),
                     "text-halo-color": T("label.halo.color"), "text-halo-width": T("label.halo.width")})


def stations():
    """Train stations: always prominent (owner request) – own top POI layer, icon from z10, name from z11.
    Id starts with "poi-" so the app's POI category filter (Settings) still applies."""
    kind = ["get", "kind"]
    layer(id_="poi-station", type="symbol", source="pois", minzoom=10,
          filter=["==", kind, "train"],
          layout={"icon-image": "poi-train",
                  "icon-size": interp(10, 0.85, 13, 0.95, 16, 1.15, base=1),
                  "icon-allow-overlap": False, "icon-padding": 1,
                  "symbol-sort-key": 0,
                  "text-field": ["step", ["zoom"], "", 11, ["coalesce", ["get", "name"], ""]],
                  "text-optional": True,
                  "text-font": T("station.label.font"),
                  "text-size": interp(11, 11, 14, 12.5, 17, 14, base=1),
                  "text-variable-anchor": ["right", "left", "top", "bottom"],
                  "text-radial-offset": 1.1, "text-justify": "auto", "text-max-width": 9, "text-padding": 2},
          paint={"text-color": T("station.label.color"),
                 "text-halo-color": T("label.halo.color"), "text-halo-width": T("label.halo.width")})


def build(theme=DEFAULT_THEME, fmt=1):
    global FMT
    FMT = fmt
    style_theme.use(theme)
    L.clear()
    # Order matters: route bands sit above the street / road lanes (owner 2026-10-21: under the streets only two thin
    # pale edges showed, routes on small streets looked missing – Komoot tints the street) and below paths / trails /
    # labels, so the path line inside the band stays sharp.
    bg(); landcover(); hillshade(); contours(); water(); buildings(); roads(); track_edges(); lanes(); route_bands(); railways(); paths()
    green_ways()
    trails(); surface_loose(); access_restrictions()
    if FMT == 2:
        via_ferrata(); too_hard(); bike_map(); barriers()
    bridges(); boundaries()
    # Trail / route name labels live in their own module (owned separately from colours).
    L.extend(style_trail_names.layers())
    # POIs above the trail names: MapLibre places the upper layer's symbols first, so a route number ("40") that repeats
    # along its line must give way to a POI – before, the inn vanished at some zooms (owner field test 2026-10-22).
    pois(); stations()
    labels()
    if FMT == 2:
        # GeoJSON overlay sources -> source-layers of the one vector source "overlays"
        for lyr in L:
            if lyr.get("source") in OVERLAY_LAYERS:
                lyr["source-layer"] = OVERLAY_LAYERS[lyr["source"]]
                lyr["source"] = "overlays"
        # the order of keys does not matter to MapLibre, but keep id/type/source/source-layer first for reading
        L[:] = [{k: lyr[k] for k in ("id", "type", "source", "source-layer") if k in lyr} | lyr for lyr in L]
    sources = {
        "base": {"type": "vector", "url": "pmtiles://file://{DATA_DIR}/base.pmtiles",
                 "attribution": "© OpenStreetMap contributors"},
        "terrain": {"type": "raster-dem", "url": "pmtiles://file://{DATA_DIR}/terrain.pmtiles",
                    "encoding": "terrarium", "tileSize": 256},
        "contours": {"type": "vector", "url": "pmtiles://file://{DATA_DIR}/contours.pmtiles"},
    }
    if FMT == 2:
        # paths z11-14, trails / ways / trailnames z13-14, routes z9-14 (tools/build_overlays.sh)
        sources["overlays"] = {"type": "vector", "url": "pmtiles://file://{DATA_DIR}/overlays.pmtiles"}
    else:
        sources.update(geojson_overlay_sources())
    sources["pois"] = {"type": "geojson", "data": "file://{DATA_DIR}/pois.geojson", "maxzoom": 14, "buffer": 32}
    style = {
        "version": 8,
        "name": T("style.name", "OnPath"),
        "metadata": {"onpath:version": 1, "onpath:theme": theme, "onpath:format": FMT},
        "center": [11.16, 46.67], "zoom": 12,
        "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
        "sprite": "asset://sprites/onpath",
        "sources": sources,
        "layers": list(L),
    }
    out = out_path(theme, FMT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(style, f, ensure_ascii=False, indent=1)
    print("wrote", os.path.abspath(out), len(L), "layers")


def geojson_overlay_sources():
    """Format 1: one GeoJSON source per overlay file."""
    return {
            "routes": {"type": "geojson", "data": "file://{DATA_DIR}/routes.geojson", "maxzoom": 14,
                       "tolerance": 0.5},
            # trails / ways lie exactly on the base map's lines: little simplification, maxzoom 16 (extract_overlays)
            "trails": {"type": "geojson", "data": "file://{DATA_DIR}/trails.geojson", "maxzoom": 16,
                       "tolerance": 0.25},
            # optional (stripped with its layers when the file is missing): tracktype / surface / smoothness
            "lowways": {"type": "geojson", "data": "file://{DATA_DIR}/lowways.geojson", "maxzoom": 12,
                        "tolerance": 1.0},
            "ways": {"type": "geojson", "data": "file://{DATA_DIR}/ways.geojson", "maxzoom": 16,
                     "tolerance": 0.25},
            # every path / track with exact geometry (k, sac, vis, tt, pv, st) – drawn from z13 instead of the base map's
            "paths": {"type": "geojson", "data": "file://{DATA_DIR}/paths.geojson", "maxzoom": 16,
                      "tolerance": 0.25},
            **style_trail_names.sources(),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--theme", default=DEFAULT_THEME, help="theme name in tools/themes/ (default classic)")
    ap.add_argument("--all", action="store_true", help="build every theme in tools/themes/")
    ap.add_argument("--format", choices=["1", "2", "all"], default="all", help="map package format (default: both)")
    a = ap.parse_args()
    themes = sorted(f[:-5] for f in os.listdir(style_theme.THEMES_DIR) if f.endswith(".json")) if a.all else [a.theme]
    fmts = [1, 2] if a.format == "all" else [int(a.format)]
    for t in themes:
        for fmt in fmts:
            build(t, fmt)


if __name__ == "__main__":
    main()
