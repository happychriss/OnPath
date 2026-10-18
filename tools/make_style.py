#!/usr/bin/env python3
"""Generate the OnPath MapLibre styles from design-token themes.

  python3 tools/make_style.py                  classic theme -> app/src/main/assets/style/onpath.json
  python3 tools/make_style.py --theme NAME     tools/themes/NAME.json -> .../style/onpath-NAME.json
  python3 tools/make_style.py --all            every theme in tools/themes/

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

L = []


def out_path(theme):
    return os.path.join(STYLE_DIR, "onpath.json" if theme == DEFAULT_THEME else f"onpath-{theme}.json")


def interp(*stops, base=1.2):
    return ["interpolate", ["exponential", base], ["zoom"], *stops]


def layer(**kw):
    L.append({"id": kw.pop("id_"), **kw})


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
    layer(id_="park-protected", type="line", source="base", **{"source-layer": "park"}, minzoom=9,
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
    layer(id_="contour-minor", type="line", source="contours", **{"source-layer": "contour"}, minzoom=13,
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


def roads():
    cls = ["get", "class"]
    tunnel = ["==", ["get", "brunnel"], "tunnel"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    groups = [
        # id, classes, minzoom, colour token prefix
        ("service", ["service"], 13, "road"),
        ("minor", ["minor"], 11, "road"),
        ("tertiary", ["tertiary"], 10, "road"),
        ("secondary", ["secondary"], 8, "major"),
        ("primary", ["primary", "trunk"], 7, "major"),
        ("motorway", ["motorway"], 6, "motorway"),
    ]
    # casings (non-tunnel), then fills; tunnels faint
    for gid, classes, minz, col in groups:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        layer(id_=f"road-{gid}-casing", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=max(minz, 10), filter=f, layout={"line-join": "round", "line-cap": "round"},
              paint={"line-color": T(f"{col}.casing"), "line-gap-width": Z(f"road.{gid}.width"),
                     "line-width": Z("road.casing.width")})
    for gid, classes, minz, col in groups:
        layer(id_=f"road-{gid}-tunnel", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=12, filter=["all", ["in", cls, ["literal", classes]], tunnel],
              layout={"line-join": "round"},
              paint={"line-color": T("tunnel.color"), "line-width": Z(f"road.{gid}.width"),
                     "line-opacity": T("tunnel.opacity"), "line-dasharray": [2, 1]})
    for gid, classes, minz, col in groups:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        fill = T(f"{col}.fill")
        layer(id_=f"road-{gid}", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=minz, filter=f, layout={"line-join": "round", "line-cap": "round"},
              # below z10 there is no casing: draw major roads in their casing colour so they stay visible
              paint={"line-color": fill if minz >= 10 else ["step", ["zoom"], T(f"{col}.casing"), 10, fill],
                     "line-width": Z(f"road.{gid}.width")})
    # railways
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
    # Opaque pale bands (no stacking artefacts where segments overlap), drawn under roads, paths and trails.
    for kind, minz in (("hike", 11), ("bike", 9), ("mtb", 9)):
        layer(id_=f"routes-{kind}", type="line", source="routes", minzoom=minz,
              filter=["==", ["get", "kind"], kind],
              layout={"line-join": "round", "line-cap": "round",
                      "visibility": "visible" if kind == "hike" else "none"},
              paint={"line-color": T(f"route.{kind}.color"),
                     "line-opacity": interp(minz, T("route.opacity.start"), minz + 1.5, 1, base=1),
                     "line-width": Z("route.width")})


def paths():
    cls = ["get", "class"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    paved = ["==", ["get", "surface"], "paved"]
    # tracks (Feldweg / farm and forest tracks, highway=track): Komoot-like narrow light road with
    # brown edges – dashed when unpaved, solid when paved. A wide edge line is drawn first, then a
    # narrower light fill on top, so only the two edges remain visible. At z12 just a thin dashed line.
    track = ["all", ["==", cls, "track"], not_tunnel]
    ew = Z("track.edge.width")
    # Zoomed out: one thin solid darker line per track (dashes would only read as noise).
    layer(id_="track-low", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          maxzoom=LOW_ZOOM_SOLID, filter=track, layout={"line-join": "round"},
          paint={"line-color": T("track.low.color"), "line-width": Z("track.low.width")})
    layer(id_="track-casing", type="line", source="base", **{"source-layer": "transportation"}, minzoom=LOW_ZOOM_SOLID,
          filter=["all", track, ["!", paved]],
          paint={"line-color": T("track.edge.color"), "line-width": ew, "line-dasharray": T("track.edge.dash")})
    layer(id_="track-paved", type="line", source="base", **{"source-layer": "transportation"}, minzoom=LOW_ZOOM_SOLID,
          filter=["all", track, paved], layout={"line-join": "round"},
          paint={"line-color": T("track.edge.color"), "line-width": ew})
    layer(id_="track", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=track, layout={"line-join": "round"},
          paint={"line-color": T("track.fill.color"), "line-width": Z("track.fill.width")})
    # cycleways (solid; unpaved dashed)
    cyc = ["all", ["==", cls, "path"], ["==", ["get", "subclass"], "cycleway"], not_tunnel]
    cw = Z("cycleway.width")
    layer(id_="cycleway", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", cyc, ["!=", ["get", "surface"], "unpaved"]],
          paint={"line-color": T("cycleway.color"), "line-width": cw})
    layer(id_="cycleway-unpaved", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", cyc, ["==", ["get", "surface"], "unpaved"]],
          paint={"line-color": T("cycleway.color"), "line-width": cw, "line-dasharray": T("cycleway.dash")})
    foot = ["all", ["==", cls, "path"], ["!=", ["get", "subclass"], "cycleway"], not_tunnel]
    steps = ["==", ["get", "subclass"], "steps"]
    pw = Z("path.width")
    layer(id_="path-casing", type="line", source="base", **{"source-layer": "transportation"},
          minzoom=T("path.casing.minzoom"), filter=foot,
          paint={"line-color": T("path.casing.color"), "line-width": Z("path.casing.width")})
    layer(id_="path-paved", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", foot, paved, ["!", steps]], paint={"line-color": T("path.paved.color"), "line-width": pw})
    layer(id_="path-low", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          maxzoom=LOW_ZOOM_SOLID, filter=["all", foot, ["!", steps]], layout={"line-join": "round"},
          paint={"line-color": T("path.low.color"), "line-width": pw})
    layer(id_="path", type="line", source="base", **{"source-layer": "transportation"}, minzoom=LOW_ZOOM_SOLID,
          filter=["all", foot, ["!", paved], ["!", steps]],
          paint={"line-color": T("path.color"), "line-width": pw, "line-dasharray": T("path.dash")})
    layer(id_="path-steps", type="line", source="base", **{"source-layer": "transportation"}, minzoom=14,
          filter=["all", foot, steps],
          paint={"line-color": T("path.color"), "line-width": Z("steps.width"), "line-dasharray": T("steps.dash")})


def trails():
    sac = ["get", "sac"]
    # SAC T1-T2 easy (dark brown), T3 mountain (orange-red), T4+ alpine (red, dotted)
    color = ["case", [">=", sac, 4], T("trail.alpine.color"), [">=", sac, 3], T("trail.mountain.color"),
             T("trail.easy.color")]
    layer(id_="trails-casing", type="line", source="trails", minzoom=12, filter=[">=", sac, 1],
          layout={"line-join": "round"},
          paint={"line-color": T("trail.casing.color"), "line-width": Z("trail.casing.width")})
    # Zoomed out: solid, a shade darker per difficulty, so trails stay readable on relief and forest.
    low = ["case", [">=", sac, 4], T("trail.alpine.low.color"), [">=", sac, 3], T("trail.mountain.low.color"),
           T("trail.easy.low.color")]
    layer(id_="trails-low", type="line", source="trails", minzoom=11, maxzoom=LOW_ZOOM_SOLID, filter=[">=", sac, 1],
          layout={"line-join": "round"}, paint={"line-color": low, "line-width": Z("trail.width")})
    for sid, flt in (("easy", ["<=", sac, 2]), ("mountain", ["==", sac, 3]), ("alpine", [">=", sac, 4])):
        layer(id_=f"trails-{sid}", type="line", source="trails", minzoom=LOW_ZOOM_SOLID,
              filter=["all", [">=", sac, 1], flt],
              paint={"line-color": color, "line-width": Z("trail.width"),
                     "line-dasharray": T(f"trail.{sid}.dash")})
    # SAC grade ("T1" … "T6") as small black text along the trail, on the other side than the
    # trail name (offset +), so both stay readable. Low priority: dropped first when space is short.
    layer(id_="trails-sac-label", type="symbol", source="trails", minzoom=14, filter=[">=", sac, 1],
          layout={"symbol-placement": "line", "symbol-spacing": interp(14, 220, 17, 320),
                  "text-field": ["concat", "T", ["to-string", sac]], "text-font": T("font.regular"),
                  "text-size": Z("trail.sac.label.size"), "text-max-angle": 30, "text-padding": 2,
                  "text-offset": [0, 0.75]},
          paint={"text-color": T("trail.sac.label.color"), "text-halo-color": T("label.halo.color"),
                 "text-halo-width": 1.2})


def bridges():
    cls = ["get", "class"]
    bridge = ["==", ["get", "brunnel"], "bridge"]
    layer(id_="bridge-casing", type="line", source="base", **{"source-layer": "transportation"}, minzoom=14,
          filter=["all", bridge, ["in", cls, ["literal", ["minor", "tertiary", "secondary", "primary", "trunk", "motorway"]]]],
          paint={"line-color": T("bridge.casing.color"), "line-gap-width": interp(14, 3.5, 16, 8, 18, 16),
                 "line-width": Z("bridge.casing.width")})


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
    # places
    place = ["get", "class"]
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
    layer(id_="place-town", type="symbol", source="base", **{"source-layer": "place"}, minzoom=7,
          filter=["==", place, "town"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(7, 11, 12, 15, 15, 18),
                  "text-max-width": 7},
          paint={"text-color": T("label.town.color"), **h})
    layer(id_="place-city", type="symbol", source="base", **{"source-layer": "place"}, minzoom=5, maxzoom=15,
          filter=["==", place, "city"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(5, 12, 10, 16, 14, 21),
                  "text-max-width": 7},
          paint={"text-color": T("label.city.color"), **h})


# --- POIs (F-MAP-09): GeoJSON overlay from tools/extract_pois.py --------------------------------
# Icons come from the sprite (tools/make_sprite.py): "poi-<kind>" (badge) and "poi-<kind>-small" (dot).
# Tiers ("more at closer zoom"); within a tier the order is the placement priority (first = most important).
# Collision is on, so dense areas thin out; place/peak labels (above) win over POIs.
POI_TIERS = [
    # tier, icon minzoom, label minzoom, kinds
    (1, 12, 14, ["hut", "bivouac", "viewpoint", "spring", "waterfall", "lift", "castle"]),
    (2, 14, 15, ["water", "restaurant", "cafe", "shelter", "camping", "church", "ruins", "cave", "museum", "chapel"]),
    (3, 15, 16, ["picnic", "toilets", "info", "bar", "fast_food", "bike_shop", "charging", "shop", "parking",
                 "bus"]),
]
POI_PRIO = ["train"] + [k for _, _, _, kinds in POI_TIERS for k in kinds]
POI_KINDS = set(POI_PRIO)
POI_SMALL_UNTIL = 13  # tier 1 uses the small dot variant below this zoom
LOW_ZOOM_SOLID = 13   # below this zoom dashed paths / tracks / trails are drawn solid and darker


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
    for tier, minz, label_z, kinds in reversed(POI_TIERS):
        icon = badge
        if minz < POI_SMALL_UNTIL:
            icon = ["step", ["zoom"], ["concat", "poi-", kind, "-small"], POI_SMALL_UNTIL, badge]
        layer(id_=f"poi-tier{tier}", type="symbol", source="pois", minzoom=minz,
              filter=["in", kind, ["literal", kinds]],
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


def build(theme=DEFAULT_THEME):
    style_theme.use(theme)
    L.clear()
    # Order matters: route bands sit below roads/paths/trails so they never wash out the trail lines.
    bg(); landcover(); hillshade(); contours(); water(); buildings(); route_bands(); roads(); paths()
    trails(); bridges(); boundaries(); pois(); stations()
    # Trail / route name labels live in their own module (owned separately from colours).
    L.extend(style_trail_names.layers())
    labels()
    style = {
        "version": 8,
        "name": T("style.name", "OnPath"),
        "metadata": {"onpath:version": 1, "onpath:theme": theme},
        "center": [11.16, 46.67], "zoom": 12,
        "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
        "sprite": "asset://sprites/onpath",
        "sources": {
            "base": {"type": "vector", "url": "pmtiles://file://{DATA_DIR}/base.pmtiles",
                     "attribution": "© OpenStreetMap contributors"},
            "terrain": {"type": "raster-dem", "url": "pmtiles://file://{DATA_DIR}/terrain.pmtiles",
                        "encoding": "terrarium", "tileSize": 256},
            "contours": {"type": "vector", "url": "pmtiles://file://{DATA_DIR}/contours.pmtiles"},
            "routes": {"type": "geojson", "data": "file://{DATA_DIR}/routes.geojson", "maxzoom": 14,
                       "tolerance": 0.5},
            "trails": {"type": "geojson", "data": "file://{DATA_DIR}/trails.geojson", "maxzoom": 14,
                       "tolerance": 0.5},
            **style_trail_names.sources(),
            "pois": {"type": "geojson", "data": "file://{DATA_DIR}/pois.geojson", "maxzoom": 14,
                     "buffer": 32},
        },
        "layers": list(L),
    }
    out = out_path(theme)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        json.dump(style, f, ensure_ascii=False, indent=1)
    print("wrote", os.path.abspath(out), len(L), "layers")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--theme", default=DEFAULT_THEME, help="theme name in tools/themes/ (default classic)")
    ap.add_argument("--all", action="store_true", help="build every theme in tools/themes/")
    a = ap.parse_args()
    themes = sorted(f[:-5] for f in os.listdir(style_theme.THEMES_DIR) if f.endswith(".json")) if a.all else [a.theme]
    for t in themes:
        build(t)


if __name__ == "__main__":
    main()
