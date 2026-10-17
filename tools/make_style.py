#!/usr/bin/env python3
"""Generate the OnPath MapLibre style (app/src/main/assets/style/onpath.json).

Edit colours / layers here, then run: python3 tools/make_style.py
Schema: OpenMapTiles (Planetiler) in source "base"; overlays routes/trails GeoJSON; contours PMTiles.
"""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "style", "onpath.json")

REG, MED, ITA = ["Noto Sans Regular"], ["Noto Sans Medium"], ["Noto Sans Italic"]
NAME = ["coalesce", ["get", "name:de"], ["get", "name_de"], ["get", "name"]]

C = {
    "land": "#f5f2e9",
    "farmland": "#f0efdc",
    "grass": "#e4eecf",
    "wood": "#cde3b4",
    "wetland": "#dbe9d6",
    "rock": "#ebe7e0",
    "ice": "#f6fafc",
    "sand": "#f1ead2",
    "residential": "#ece7e1",
    "industrial": "#e9e4e4",
    "water": "#a9d3ef",
    "waterline": "#8fc2e8",
    "building": "#ddd5cc",
    "casing": "#b7b0a6",
    "road": "#ffffff",
    "major": "#fff6d9",
    "major_casing": "#c9b98e",
    "motorway": "#fbe0a8",
    "motorway_casing": "#d4a95c",
    "track": "#8a6a4a",
    "path": "#5b4636",
    "label": "#3a3632",
    "halo": "rgba(255,255,255,0.9)",
}


def interp(*stops, base=1.2):
    return ["interpolate", ["exponential", base], ["zoom"], *stops]


L = []


def layer(**kw):
    L.append({"id": kw.pop("id_"), **kw})


def bg():
    layer(id_="background", type="background", paint={"background-color": C["land"]})


def landcover():
    for cls, col, minz in (("farmland", C["farmland"], 0), ("grass", C["grass"], 0), ("wood", C["wood"], 0),
                           ("wetland", C["wetland"], 9), ("rock", C["rock"], 0), ("ice", C["ice"], 0),
                           ("sand", C["sand"], 9)):
        layer(id_=f"landcover-{cls}", type="fill", source="base", **{"source-layer": "landcover"},
              minzoom=minz, filter=["==", ["get", "class"], cls],
              paint={"fill-color": col, "fill-antialias": False})
    layer(id_="landcover-ice-outline", type="line", source="base", **{"source-layer": "landcover"}, minzoom=10,
          filter=["==", ["get", "class"], "ice"],
          paint={"line-color": "#b9d6ea", "line-width": 0.8, "line-dasharray": [3, 2]})
    layer(id_="landuse-residential", type="fill", source="base", **{"source-layer": "landuse"},
          filter=["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood", "commercial", "retail"]]],
          paint={"fill-color": C["residential"], "fill-opacity": interp(9, 0.5, 13, 1)})
    layer(id_="landuse-industrial", type="fill", source="base", **{"source-layer": "landuse"}, minzoom=11,
          filter=["in", ["get", "class"], ["literal", ["industrial", "garages", "railway", "quarry"]]],
          paint={"fill-color": C["industrial"]})
    layer(id_="landuse-green", type="fill", source="base", **{"source-layer": "landuse"}, minzoom=11,
          filter=["in", ["get", "class"], ["literal", ["cemetery", "pitch", "stadium", "playground"]]],
          paint={"fill-color": "#dfeacb"})
    layer(id_="park-protected", type="line", source="base", **{"source-layer": "park"}, minzoom=9,
          paint={"line-color": "rgba(90,150,80,0.3)", "line-width": interp(9, 0.8, 14, 1.6),
                 "line-dasharray": [6, 3]})


def hillshade():
    # Two light sources: a broad soft one from NW and a weaker one from W for multi-directional modelling.
    layer(id_="hillshade-nw", type="hillshade", source="terrain", maxzoom=17,
          paint={"hillshade-illumination-direction": 315, "hillshade-illumination-anchor": "map",
                 "hillshade-exaggeration": interp(5, 0.55, 10, 0.5, 13, 0.4, 15, 0.25, base=1),
                 "hillshade-shadow-color": "rgba(70,62,78,0.55)",
                 "hillshade-highlight-color": "rgba(255,253,240,0.45)",
                 "hillshade-accent-color": "rgba(60,55,70,0.25)"})
    layer(id_="hillshade-w", type="hillshade", source="terrain", maxzoom=17,
          paint={"hillshade-illumination-direction": 260, "hillshade-illumination-anchor": "map",
                 "hillshade-exaggeration": interp(10, 0.25, 13, 0.2, 15, 0.1, base=1),
                 "hillshade-shadow-color": "rgba(60,70,90,0.35)",
                 "hillshade-highlight-color": "rgba(255,255,255,0)",
                 "hillshade-accent-color": "rgba(0,0,0,0)"})


def contours():
    major = ["==", ["get", "idx"], 1]
    layer(id_="contour-minor", type="line", source="contours", **{"source-layer": "contour"}, minzoom=13,
          filter=["all", ["!", major], ["any", [">=", ["zoom"], 14], ["==", ["%", ["get", "ele"], 50], 0]]],
          paint={"line-color": "#b39a78", "line-opacity": interp(13, 0.25, 15, 0.4), "line-width": 0.6})
    layer(id_="contour-major", type="line", source="contours", **{"source-layer": "contour"}, minzoom=12,
          filter=major,
          paint={"line-color": "#a88a64", "line-opacity": interp(12, 0.3, 15, 0.5),
                 "line-width": interp(12, 0.7, 16, 1.2)})
    layer(id_="contour-label", type="symbol", source="contours", **{"source-layer": "contour"}, minzoom=14,
          filter=major,
          layout={"symbol-placement": "line", "text-field": ["to-string", ["get", "ele"]], "text-font": REG,
                  "text-size": 10, "symbol-spacing": 350, "text-padding": 10},
          paint={"text-color": "#9a7f5c", "text-halo-color": "rgba(245,242,233,0.85)", "text-halo-width": 1.2})


def water():
    for wid, inter, dash in (("waterway-stream", 0, None), ("waterway-stream-intermittent", 1, [3, 2])):
        paint = {"line-color": C["waterline"], "line-width": interp(11, 0.5, 14, 1.2, 17, 2.5)}
        if dash:
            paint["line-dasharray"] = dash
        layer(id_=wid, type="line", source="base", **{"source-layer": "waterway"}, minzoom=11,
              filter=["all", ["!=", ["get", "class"], "river"], ["!=", ["get", "brunnel"], "tunnel"],
                      ["==", ["coalesce", ["get", "intermittent"], 0], inter]],
              paint=paint)
    layer(id_="waterway-river", type="line", source="base", **{"source-layer": "waterway"}, minzoom=8,
          filter=["all", ["==", ["get", "class"], "river"], ["!=", ["get", "brunnel"], "tunnel"]],
          layout={"line-cap": "round"},
          paint={"line-color": C["waterline"], "line-width": interp(8, 0.8, 12, 2, 17, 6)})
    layer(id_="water", type="fill", source="base", **{"source-layer": "water"},
          filter=["!=", ["get", "brunnel"], "tunnel"],
          paint={"fill-color": C["water"]})
    layer(id_="water-outline", type="line", source="base", **{"source-layer": "water"}, minzoom=12,
          paint={"line-color": C["waterline"], "line-width": 0.6})


def buildings():
    layer(id_="building", type="fill", source="base", **{"source-layer": "building"}, minzoom=14,
          paint={"fill-color": C["building"], "fill-outline-color": "#cbc2b8",
                 "fill-opacity": interp(14, 0.6, 16, 1)})


def roads():
    cls = ["get", "class"]
    tunnel = ["==", ["get", "brunnel"], "tunnel"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    groups = [
        # id, classes, minzoom, fill, casing, width stops (fill)
        ("service", ["service"], 13, C["road"], C["casing"], interp(13, 0.6, 16, 3, 18, 8)),
        ("minor", ["minor"], 11, C["road"], C["casing"], interp(11, 0.6, 14, 2.6, 16, 6, 18, 14)),
        ("tertiary", ["tertiary"], 10, C["road"], C["casing"], interp(10, 0.8, 14, 3.5, 16, 8, 18, 16)),
        ("secondary", ["secondary"], 8, C["major"], C["major_casing"], interp(8, 0.6, 12, 2, 14, 4.5, 16, 9, 18, 18)),
        ("primary", ["primary", "trunk"], 7, C["major"], C["major_casing"], interp(7, 0.6, 12, 2.6, 14, 5.5, 16, 10, 18, 20)),
        ("motorway", ["motorway"], 6, C["motorway"], C["motorway_casing"], interp(6, 0.6, 12, 2.8, 14, 6, 16, 11, 18, 22)),
    ]
    # casings (non-tunnel), then fills; tunnels faint
    for gid, classes, minz, fill, casing, width in groups:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        layer(id_=f"road-{gid}-casing", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=max(minz, 10), filter=f, layout={"line-join": "round", "line-cap": "round"},
              paint={"line-color": casing, "line-gap-width": width,
                     "line-width": interp(10, 0.5, 14, 0.9, 18, 1.5)})
    for gid, classes, minz, fill, casing, width in groups:
        layer(id_=f"road-{gid}-tunnel", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=12, filter=["all", ["in", cls, ["literal", classes]], tunnel],
              layout={"line-join": "round"},
              paint={"line-color": "#d8d2c8", "line-width": width, "line-opacity": 0.7,
                     "line-dasharray": [2, 1]})
    for gid, classes, minz, fill, casing, width in groups:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        layer(id_=f"road-{gid}", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=minz, filter=f, layout={"line-join": "round", "line-cap": "round"},
              paint={"line-color": fill if minz >= 10 else ["step", ["zoom"], C["motorway_casing"] if gid == "motorway" else C["major_casing"], 10, fill],
                     "line-width": width})
    # railways
    layer(id_="rail", type="line", source="base", **{"source-layer": "transportation"}, minzoom=10,
          filter=["all", ["==", cls, "rail"], not_tunnel],
          paint={"line-color": "#9a948c", "line-width": interp(10, 0.8, 16, 2.2)})
    layer(id_="rail-hatch", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", ["==", cls, "rail"], not_tunnel],
          paint={"line-color": "#ffffff", "line-width": interp(13, 0.6, 16, 1.2), "line-dasharray": [3, 3]})
    layer(id_="aerialway", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          filter=["in", cls, ["literal", ["aerialway", "cable_car"]]],
          paint={"line-color": "#6b6670", "line-width": 1})


def route_bands():
    # Opaque pale bands (no stacking artefacts where segments overlap), drawn under roads and paths.
    for kind, col, minz in (("hike", "#9ed68d", 11), ("bike", "#eeb08a", 9), ("mtb", "#cfa9e3", 9)):
        layer(id_=f"routes-{kind}", type="line", source="routes", minzoom=minz,
              filter=["==", ["get", "kind"], kind],
              layout={"line-join": "round", "line-cap": "round",
                      "visibility": "visible" if kind == "hike" else "none"},
              paint={"line-color": col, "line-opacity": interp(minz, 0.6, minz + 1.5, 1, base=1),
                     "line-width": interp(9, 1.5, 11, 2.5, 13, 6, 15, 12, 18, 18)})


def paths():
    cls = ["get", "class"]
    not_tunnel = ["!=", ["get", "brunnel"], "tunnel"]
    # tracks: brown, paved tracks solid, unpaved dashed
    track = ["all", ["==", cls, "track"], not_tunnel]
    paved = ["==", ["get", "surface"], "paved"]
    layer(id_="track-casing", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=track, layout={"line-join": "round"},
          paint={"line-color": "rgba(255,255,255,0.7)", "line-width": interp(13, 2.2, 16, 4, 18, 7)})
    tw = interp(12, 0.8, 14, 1.3, 16, 2, 18, 3.5)
    layer(id_="track-paved", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          filter=["all", track, paved], paint={"line-color": C["track"], "line-width": tw})
    layer(id_="track", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          filter=["all", track, ["!", paved]],
          paint={"line-color": C["track"], "line-width": tw, "line-dasharray": [3, 1.5]})
    # cycleways (solid; unpaved dashed)
    cyc = ["all", ["==", cls, "path"], ["==", ["get", "subclass"], "cycleway"], not_tunnel]
    cw = interp(13, 1, 16, 2.2, 18, 3.5)
    layer(id_="cycleway", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", cyc, ["!=", ["get", "surface"], "unpaved"]],
          paint={"line-color": "#3c7fb0", "line-width": cw})
    layer(id_="cycleway-unpaved", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", cyc, ["==", ["get", "surface"], "unpaved"]],
          paint={"line-color": "#3c7fb0", "line-width": cw, "line-dasharray": [3, 1.5]})
    foot = ["all", ["==", cls, "path"], ["!=", ["get", "subclass"], "cycleway"], not_tunnel]
    steps = ["==", ["get", "subclass"], "steps"]
    pw = interp(12, 0.7, 14, 1.2, 16, 1.8, 18, 3)
    layer(id_="path-casing", type="line", source="base", **{"source-layer": "transportation"}, minzoom=14,
          filter=foot, paint={"line-color": "rgba(255,255,255,0.55)", "line-width": interp(14, 2.5, 18, 6)})
    layer(id_="path-paved", type="line", source="base", **{"source-layer": "transportation"}, minzoom=13,
          filter=["all", foot, paved, ["!", steps]], paint={"line-color": "#8b7d70", "line-width": pw})
    layer(id_="path", type="line", source="base", **{"source-layer": "transportation"}, minzoom=12,
          filter=["all", foot, ["!", paved], ["!", steps]],
          paint={"line-color": C["path"], "line-width": pw, "line-dasharray": [2.5, 1.2]})
    layer(id_="path-steps", type="line", source="base", **{"source-layer": "transportation"}, minzoom=14,
          filter=["all", foot, steps],
          paint={"line-color": C["path"], "line-width": interp(14, 2, 18, 5), "line-dasharray": [0.4, 0.4]})


def trails():
    sac = ["get", "sac"]
    # SAC T1-T2 easy (dark brown), T3 mountain (orange-red), T4+ alpine (red, dotted)
    color = ["case", [">=", sac, 4], "#c4233a", [">=", sac, 3], "#d0602a", "#4f3a2a"]
    layer(id_="trails-casing", type="line", source="trails", minzoom=12, filter=[">=", sac, 1],
          layout={"line-join": "round"},
          paint={"line-color": "rgba(255,255,255,0.6)", "line-width": interp(12, 2, 14, 3, 16, 4.5, 18, 7)})
    for sid, flt, dash in (("easy", ["<=", sac, 2], [2.5, 1.2]), ("mountain", ["==", sac, 3], [2, 1.2]),
                           ("alpine", [">=", sac, 4], [1, 1.2])):
        layer(id_=f"trails-{sid}", type="line", source="trails", minzoom=11,
              filter=["all", [">=", sac, 1], flt],
              paint={"line-color": color, "line-width": interp(11, 0.8, 14, 1.5, 16, 2.2, 18, 3.5),
                     "line-dasharray": dash})


def bridges():
    cls = ["get", "class"]
    bridge = ["==", ["get", "brunnel"], "bridge"]
    layer(id_="bridge-casing", type="line", source="base", **{"source-layer": "transportation"}, minzoom=14,
          filter=["all", bridge, ["in", cls, ["literal", ["minor", "tertiary", "secondary", "primary", "trunk", "motorway"]]]],
          paint={"line-color": "#9f978c", "line-gap-width": interp(14, 3.5, 16, 8, 18, 16),
                 "line-width": 1.2})


def boundaries():
    layer(id_="boundary-country", type="line", source="base", **{"source-layer": "boundary"},
          filter=["all", ["<=", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
          paint={"line-color": "#9e8aa6", "line-width": interp(4, 1, 12, 2.5), "line-dasharray": [3, 1.5]})
    layer(id_="boundary-region", type="line", source="base", **{"source-layer": "boundary"}, minzoom=7,
          filter=["all", ["in", ["get", "admin_level"], ["literal", [3, 4]]], ["!=", ["get", "maritime"], 1]],
          paint={"line-color": "#b8a9bd", "line-width": interp(7, 0.6, 12, 1.4), "line-dasharray": [3, 2]})


def labels():
    halo = {"text-halo-color": C["halo"], "text-halo-width": 1.4, "text-halo-blur": 0.3}
    # water names
    layer(id_="waterway-label", type="symbol", source="base", **{"source-layer": "waterway"}, minzoom=13,
          filter=["==", ["get", "class"], "river"],
          layout={"symbol-placement": "line", "text-field": NAME, "text-font": ITA, "text-size": 12,
                  "symbol-spacing": 400, "text-letter-spacing": 0.05},
          paint={"text-color": "#3d7bb0", **halo})
    layer(id_="water-label", type="symbol", source="base", **{"source-layer": "water_name"}, minzoom=10,
          layout={"text-field": NAME, "text-font": ITA, "text-size": interp(10, 11, 15, 14),
                  "text-max-width": 7},
          paint={"text-color": "#3d7bb0", **halo})
    # road names
    layer(id_="road-label", type="symbol", source="base", **{"source-layer": "transportation_name"}, minzoom=14,
          filter=["!", ["in", ["get", "class"], ["literal", ["path", "track", "rail", "transit"]]]],
          layout={"symbol-placement": "line", "text-field": NAME, "text-font": REG,
                  "text-size": interp(14, 10, 17, 12.5), "symbol-spacing": 300, "text-padding": 4},
          paint={"text-color": "#5c5650", **halo})
    layer(id_="road-shield", type="symbol", source="base", **{"source-layer": "transportation_name"},
          minzoom=10, maxzoom=14,
          filter=["all", ["in", ["get", "class"], ["literal", ["motorway", "trunk", "primary"]]],
                  ["has", "ref"], ["<=", ["length", ["get", "ref"]], 6]],
          layout={"symbol-placement": "line", "text-field": ["get", "ref"], "text-font": MED,
                  "text-size": 10, "symbol-spacing": 450, "text-rotation-alignment": "viewport",
                  "text-padding": 20},
          paint={"text-color": "#7a5d1c", "text-halo-color": "#fff6d9", "text-halo-width": 2.2})
    # saddles / passes
    layer(id_="pass-label", type="symbol", source="base", **{"source-layer": "mountain_peak"}, minzoom=12,
          filter=["all", ["==", ["get", "class"], "saddle"], ["has", "name"]],
          layout={"text-field": ["case", ["has", "ele"], ["concat", NAME, "\n", ["to-string", ["get", "ele"]], " m"], NAME],
                  "text-font": ITA, "text-size": 11,
                  "text-max-width": 8, "text-padding": 6},
          paint={"text-color": "#6b5a48", **halo})
    # huts: now drawn from the POI overlay (poi-* layers), see pois()
    # peaks
    peak = ["all", ["in", ["get", "class"], ["literal", ["peak", "volcano"]]], ["has", "name"]]
    peak_text = ["format", "▲", {"font-scale": 0.8, "text-color": "#6f5b45"}, "\n", {},
                 NAME, {}, "\n", {},
                 ["case", ["has", "ele"], ["concat", ["to-string", ["get", "ele"]], " m"], ""],
                 {"font-scale": 0.85}]
    layer(id_="peak-label", type="symbol", source="base", **{"source-layer": "mountain_peak"}, minzoom=10,
          filter=peak,
          layout={"text-field": peak_text, "text-font": REG, "text-size": interp(10, 11, 15, 13),
                  "text-anchor": "top", "text-offset": [0, -0.6], "text-max-width": 8,
                  "text-padding": 8,
                  "symbol-sort-key": ["-", 0, ["coalesce", ["get", "ele"], 0]]},
          paint={"text-color": "#4e3f30", **halo})
    # places
    place = ["get", "class"]
    layer(id_="place-hamlet", type="symbol", source="base", **{"source-layer": "place"}, minzoom=12,
          filter=["in", place, ["literal", ["hamlet", "isolated_dwelling", "neighbourhood", "quarter", "locality"]]],
          layout={"text-field": NAME, "text-font": REG, "text-size": interp(12, 10.5, 16, 13),
                  "text-max-width": 7, "text-padding": 4},
          paint={"text-color": "#55504a", **halo})
    layer(id_="place-suburb", type="symbol", source="base", **{"source-layer": "place"}, minzoom=11, maxzoom=16,
          filter=["==", place, "suburb"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(11, 11, 15, 13),
                  "text-transform": "uppercase", "text-letter-spacing": 0.1, "text-max-width": 7},
          paint={"text-color": "#7a736b", **halo})
    layer(id_="place-village", type="symbol", source="base", **{"source-layer": "place"}, minzoom=10,
          filter=["==", place, "village"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(10, 11, 14, 14, 16, 16),
                  "text-max-width": 7},
          paint={"text-color": C["label"], **halo})
    layer(id_="place-town", type="symbol", source="base", **{"source-layer": "place"}, minzoom=7,
          filter=["==", place, "town"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(7, 11, 12, 15, 15, 18),
                  "text-max-width": 7},
          paint={"text-color": "#2c2926", **halo})
    layer(id_="place-city", type="symbol", source="base", **{"source-layer": "place"}, minzoom=5, maxzoom=15,
          filter=["==", place, "city"],
          layout={"text-field": NAME, "text-font": MED, "text-size": interp(5, 12, 10, 16, 14, 21),
                  "text-max-width": 7},
          paint={"text-color": "#24211e", **halo})


# --- POIs (F-MAP-09): GeoJSON overlay from tools/extract_pois.py --------------------------------
# kind -> (group, glyph, minzoom). Glyphs must exist in the bundled "Noto Sans Regular" ranges
# (0-255, 9472-9727 geometric shapes, 9728-9983 misc symbols).
POI_GROUPS = {  # group -> circle colour
    "food": "#d9822b",       # food & drink
    "hut": "#b8392e",        # huts, shelters, camping
    "water": "#2f86c9",      # drinking water, springs, waterfalls
    "sight": "#7d5ba6",      # viewpoints, castles, churches, museums
    "transport": "#4a6382",  # bus, train, lifts, parking
    "facility": "#5e7f73",   # toilets, picnic, info, bike
}
POI_KINDS = {
    "viewpoint": ("sight", "◉", 13), "hut": ("hut", "☗", 13), "bivouac": ("hut", "☗", 13),
    "water": ("water", "⛲", 13), "spring": ("water", "♒", 13), "waterfall": ("water", "♒", 13),
    "castle": ("sight", "⛫", 13), "train": ("transport", "☰", 13), "lift": ("transport", "△", 13),
    "shelter": ("hut", "☂", 14), "restaurant": ("food", "♨", 14), "cafe": ("food", "☕", 14),
    "church": ("sight", "⛪", 14), "ruins": ("sight", "⛫", 14), "cave": ("sight", "◖", 14),
    "museum": ("sight", "★", 14), "camping": ("hut", "⛺", 14),
    "chapel": ("sight", "⛪", 15), "bar": ("food", "▼", 15), "fast_food": ("food", "♨", 15),
    "shop": ("food", "■", 15), "toilets": ("facility", "WC", 15), "picnic": ("facility", "⛱", 15),
    "parking": ("transport", "P", 15), "bus": ("transport", "⛟", 15), "info": ("facility", "i", 15),
    "bike_shop": ("facility", "⚙", 15), "charging": ("facility", "⚡", 15),
}
POI_PRIO = ["hut", "bivouac", "viewpoint", "water", "spring", "waterfall", "castle", "train", "lift", "shelter",
            "restaurant", "cafe", "church", "ruins", "cave", "museum", "camping", "chapel", "picnic", "toilets",
            "bar", "fast_food", "bus", "parking", "info", "bike_shop", "charging", "shop"]


def _match(values, fallback):
    expr = ["match", ["get", "kind"]]
    for k, v in values:
        expr += [k, v]
    return expr + [fallback]


def pois():
    kind = ["get", "kind"]
    color = _match([(k, POI_GROUPS[g]) for k, (g, _, _) in POI_KINDS.items()], "#777777")
    glyph = _match([(k, gl) for k, (_, gl, _) in POI_KINDS.items()], "•")
    prio = _match([(k, i) for i, k in enumerate(POI_PRIO)], 99)
    for z in (13, 14, 15):
        kinds = [k for k, (_, _, mz) in POI_KINDS.items() if mz == z]
        flt = ["in", kind, ["literal", kinds]]
        layer(id_=f"poi-dot-z{z}", type="circle", source="pois", minzoom=z, filter=flt,
              paint={"circle-color": color, "circle-radius": interp(13, 5.5, 15, 7, 17, 9, base=1),
                     "circle-stroke-color": "#ffffff", "circle-stroke-width": interp(13, 1.2, 16, 1.8, base=1),
                     "circle-pitch-alignment": "map"})
        layer(id_=f"poi-glyph-z{z}", type="symbol", source="pois", minzoom=max(z, 14), filter=flt,
              layout={"text-field": glyph, "text-font": REG,
                      "text-size": interp(14, ["case", ["==", kind, "toilets"], 6, 8],
                                         17, ["case", ["==", kind, "toilets"], 8, 11], base=1),
                      "text-allow-overlap": True, "text-ignore-placement": True, "text-padding": 0,
                      "text-line-height": 1, "text-offset": [0, 0.05]},
              paint={"text-color": "#ffffff"})
    # names: collision-aware, below place/peak labels, important kinds first
    named = ["has", "name"]
    for lid, minz, kinds in (("poi-label-major", 14, ["hut", "bivouac", "castle", "waterfall", "train", "lift"]),
                             ("poi-label", 15, None)):
        flt = ["all", named, ["in", kind, ["literal", kinds]]] if kinds else \
            ["all", named, ["in", kind, ["literal", [k for k in POI_KINDS if k not in ("bus", "parking")]]]]
        layer(id_=lid, type="symbol", source="pois", minzoom=minz, filter=flt,
              layout={"text-field": ["get", "name"], "text-font": MED if kinds else REG,
                      "text-size": interp(14, 10.5, 17, 12.5, base=1),
                      "text-variable-anchor": ["left", "right", "top", "bottom"],
                      "text-radial-offset": 0.95, "text-justify": "auto", "text-max-width": 8,
                      "text-padding": 3, "symbol-sort-key": prio},
              paint={"text-color": "#3f3a35", "text-halo-color": C["halo"],
                     "text-halo-width": 1.4})


def build():
    bg(); landcover(); hillshade(); contours(); water(); buildings(); route_bands(); roads(); paths()
    trails(); bridges(); boundaries(); pois(); labels()
    style = {
        "version": 8,
        "name": "OnPath Topo",
        "metadata": {"onpath:version": 1},
        "center": [11.16, 46.67], "zoom": 12,
        "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
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
            "pois": {"type": "geojson", "data": "file://{DATA_DIR}/pois.geojson", "maxzoom": 14,
                     "buffer": 32},
        },
        "layers": L,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(style, f, ensure_ascii=False, indent=1)
    print("wrote", os.path.abspath(OUT), len(L), "layers")


build()
