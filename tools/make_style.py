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
    # Ground symbols (sprite "pattern-*", tools/make_sprite.py) from z14, very light: special ground at a glance.
    # None for forest and meadow, none for settlements (owner: too busy when cycling through a town).
    for cls in ("wetland", "farmland", "rock", "sand"):
        layer(id_=f"landcover-{cls}-pattern", type="fill", source="base", **{"source-layer": "landcover"}, minzoom=14,
              filter=["==", ["get", "class"], cls], paint={"fill-pattern": f"pattern-{cls}"})
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
        # busier roads stand out by a yellow-to-orange scale by road class (OSM has no traffic counts)
        ("tertiary", ["tertiary"], 10, "tertiary"),
        ("secondary", ["secondary"], 8, "secondary"),
        ("primary", ["primary"], 7, "primary"),
        ("motorway", ["motorway", "trunk"], 6, "motorway"),
    ]
    # casings (non-tunnel), then fills; tunnels faint
    street = {"service", "minor"}  # small streets: two grey edges, no fill (owner: no white lines)
    for gid, classes, minz, col in groups:
        f = ["all", ["in", cls, ["literal", classes]], not_tunnel]
        paint = ({"line-color": T("street.edge.color"), "line-gap-width": Z(f"street.{gid}.gap"),
                  "line-width": Z("street.edge.width")} if gid in street else
                 {"line-color": T(f"{col}.casing"), "line-gap-width": Z(f"road.{gid}.width"),
                  "line-width": Z("road.casing.width")})
        layer(id_=f"road-{gid}-casing", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=max(minz, 10), filter=f, layout={"line-join": "round", "line-cap": "round"}, paint=paint)
    for gid, classes, minz, col in groups:
        layer(id_=f"road-{gid}-tunnel", type="line", source="base", **{"source-layer": "transportation"},
              minzoom=12, filter=["all", ["in", cls, ["literal", classes]], tunnel],
              layout={"line-join": "round"},
              paint={"line-color": T("tunnel.color"), "line-width": Z(f"road.{gid}.width"),
                     "line-opacity": T("tunnel.opacity"), "line-dasharray": [2, 1]})
    for gid, classes, minz, col in groups:
        if gid in street:
            continue
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
    # One transparent green band without edges for every activity (owner 2026-10-19, colour from the analysis in
    # docs/design/map-colour-analysis.md), drawn under roads, paths and trails, so the ways stay readable on it.
    # extract_overlays.py merges route pieces into continuous lines (the transparent band would darken where pieces
    # meet); the original pieces of major routes come again as "majoronly" copies for the pink highlight and labels.
    # The app shows the band of the current activity (MapController.setOverlay toggles routes-<kind>*).
    for kind, minz in (("hike", 10), ("bike", 9), ("mtb", 9)):
        vis = "visible" if kind == "hike" else "none"
        flt = ["==", ["get", "kind"], kind]
        band_w = Z("route.band.width", interp(9, 1.2, 12, 2.0, 13, 2.6, 14, 7, 15, 12, 18, 18, base=1))
        # optional dark edge on both sides of the band (theme token): the band reads on forest green and on pale land
        if T("route.band.edge.color", None):
            layer(id_=f"routes-{kind}-edge", type="line", source="routes", minzoom=minz, filter=flt,
                  layout={"line-join": "round", "line-cap": "round", "visibility": vis},
                  paint={"line-color": T("route.band.edge.color"), "line-width": Z("route.band.edge.width")})
        layer(id_=f"routes-{kind}", type="line", source="routes", minzoom=minz,
              filter=["all", flt, ["!", ["has", "majoronly"]]],
              layout={"line-join": "round", "line-cap": "round", "visibility": vis},
              # Zoomed out the route IS a thin line on the way (no empty band); from z13.5 it widens into the
              # soft band. Our own look (owner: similar to Komoot, not a copy).
              paint={"line-color": ["interpolate", ["linear"], ["zoom"], 13, T("route.line.color"), 14, T("route.band.color")],
                     "line-opacity": T("route.opacity"),
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
    # fallback without paths.geojson
    layer(id_="track-base", type="line", **tl, minzoom=LOW_ZOOM_SOLID, filter=base_track, metadata=fb,
          layout={"line-join": "round"},
          paint={"line-color": T("track.color"), "line-width": Z("track.single.width"), "line-dasharray": T("track.dash.single")})
    layer(id_="path-base", type="line", **tl, minzoom=LOW_ZOOM_SOLID, filter=["all", foot, ["!", steps]], metadata=fb,
          layout={"line-join": "round"}, paint={"line-color": T("path.color"), "line-width": pw})
    # cycleways (solid; unpaved dashed)
    cyc = ["all", ["==", cls, "path"], ["==", ["get", "subclass"], "cycleway"], not_tunnel]
    cw = Z("cycleway.width")
    layer(id_="cycleway", type="line", **tl, minzoom=13, filter=["all", cyc, ["!=", ["get", "surface"], "unpaved"]],
          paint={"line-color": T("cycleway.color"), "line-width": cw})
    layer(id_="cycleway-unpaved", type="line", **tl, minzoom=13, filter=["all", cyc, ["==", ["get", "surface"], "unpaved"]],
          paint={"line-color": T("cycleway.color"), "line-width": cw, "line-dasharray": T("cycleway.dash")})
    layer(id_="path-steps", type="line", **tl, minzoom=14, filter=["all", foot, steps],
          paint={"line-color": T("path.color"), "line-width": Z("steps.width"), "line-dasharray": T("steps.dash")})
    # own exact data
    own = {"source": "paths", "minzoom": LOW_ZOOM_SOLID}
    k = ["get", "k"]
    track = ["all", ["==", k, "t"], ["!", ["has", "pv"]]]
    tt = ["coalesce", ["get", "tt"], 0]
    layer(id_="paths-own", type="line", **own,
          filter=["all", ["==", k, "p"], ["!", ["has", "st"]], ["<", ["get", "sac"], 2], ["<", ["coalesce", ["get", "vis"], 0], 3]],
          layout={"line-join": "round", "line-cap": "round"}, paint={"line-color": T("path.color"), "line-width": pw})
    layer(id_="track-own-paved", type="line", **own, filter=["all", ["==", k, "t"], ["has", "pv"]],
          layout={"line-join": "round"},
          paint={"line-color": T("street.edge.color"), "line-width": Z("street.edge.width"), "line-gap-width": Z("street.minor.gap")})
    layer(id_="track-own-single", type="line", **own, maxzoom=TRACK_DOTS_ZOOM, filter=track, layout={"line-join": "round"},
          paint={"line-color": T("track.color"), "line-width": Z("track.single.width"), "line-dasharray": T("track.dash.single")})
    for sid, f in (("compact", ["==", tt, 1]), ("medium", ["any", ["==", tt, 2], ["==", tt, 0]]),
                   ("loose", ["==", tt, 3]), ("soft", [">=", tt, 4])):
        layer(id_=f"track-own-{sid}", type="line", source="paths", minzoom=TRACK_DOTS_ZOOM, filter=["all", track, f],
              layout={"line-cap": "round", "line-join": "round"},
              paint={"line-color": T("track.color"), "line-width": Z("track.dot.width"), "line-gap-width": Z("track.gap"),
                     "line-dasharray": T(f"track.dash.{sid}")})
    # Lane fill over ALL track edges (owner 2026-10-19: crossings looked messy). A two-edge track has no fill of its
    # own, so at a crossing / T-junction the edges of one way ran straight across the other way's lane, and the round
    # caps closed every way piece with a ring / "c"-hook at each junction. Drawn after every edge layer (all edges
    # below all fills, like road casings), with round caps, the lanes join into one clean road at junctions and the
    # end caps disappear under the neighbour's lane. Exactly the gap width, so the own edges stay fully visible.
    # Slightly transparent (token), so the route band under the track still tints the lane a little.
    lane = {"line-color": T("track.lane.color"), "line-opacity": T("track.lane.opacity")}
    layer(id_="track-own-paved-lane", type="line", **own, filter=["all", ["==", k, "t"], ["has", "pv"]],
          layout={"line-join": "round", "line-cap": "round"}, paint={**lane, "line-width": Z("street.minor.gap")})
    layer(id_="track-own-lane", type="line", source="paths", minzoom=TRACK_DOTS_ZOOM, filter=track,
          layout={"line-join": "round", "line-cap": "round"}, paint={**lane, "line-width": Z("track.gap")})


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
    # MTB scale ("S0" … "S5"), same look; only in MTB / MTB e-bike (MapController.setOverlay swaps T- and S-labels).
    layer(id_="trails-mtb-label", type="symbol", source="trails", minzoom=14, filter=["has", "mtb"],
          layout={"symbol-placement": "line", "symbol-spacing": interp(14, 220, 17, 320),
                  "text-field": ["concat", "S", ["get", "mtb"]], "text-font": T("font.regular"),
                  "text-size": Z("trail.sac.label.size"), "text-max-angle": 30, "text-padding": 2,
                  "text-offset": [0, 0.75], "visibility": "none"},
          paint={"text-color": T("trail.sac.label.color"), "text-halo-color": T("label.halo.color"),
                 "text-halo-width": 1.2})


def access_restrictions():
    """Ways you may not use, like Komoot's blocked sign: a light red tint along the way (z14+) and a small red
    no-entry sign repeated along it (z15+). OpenMapTiles sets "access": "no" for OSM access=no/private, but many
    marked trails are "access=no + foot=yes" (closed to vehicles, open to walkers) – so the marking depends on
    the activity: the "-foot" layers (hiking, visible by default) skip ways with foot=yes, the "-bike" layers
    (bike profiles, hidden by default) skip ways with bicycle=yes. The app switches them with the activity
    (MapController.setOverlay). Only paths, tracks and non-driveway service roads – private driveways would put
    a sign at every house. Routing (BRouter) already respects these tags."""
    cls = ["get", "class"]
    allowed = ["literal", ["yes", "designated", "permissive"]]
    base = ["all", ["==", ["get", "access"], "no"],
            ["any", ["in", cls, ["literal", ["path", "track"]]],
             ["all", ["==", cls, "service"],
              ["!", ["in", ["get", "service"], ["literal", ["driveway", "parking_aisle"]]]]]]]
    for mode, tag, visible in (("foot", "foot", True), ("bike", "bicycle", False)):
        blocked = ["all", base, ["!", ["in", ["coalesce", ["get", tag], ""], allowed]]]
        vis = {"visibility": "visible" if visible else "none"}
        layer(id_=f"access-no-tint-{mode}", type="line", source="base", **{"source-layer": "transportation"}, minzoom=14,
              filter=blocked, layout={"line-join": "round", "line-cap": "round", **vis},
              paint={"line-color": T("access.no.tint.color"), "line-width": Z("access.no.tint.width")})
        layer(id_=f"access-no-sign-{mode}", type="symbol", source="base", **{"source-layer": "transportation"}, minzoom=15,
              filter=blocked,
              layout={"symbol-placement": "line", "symbol-spacing": interp(15, 90, 18, 160),
                      "icon-image": "access-no", "icon-size": interp(15, 0.75, 18, 1.0, base=1),
                      "icon-rotation-alignment": "viewport", "icon-allow-overlap": False, "icon-padding": 1, **vis})


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


def build(theme=DEFAULT_THEME):
    style_theme.use(theme)
    L.clear()
    # Order matters: route bands sit below roads/paths/trails so they never wash out the trail lines.
    bg(); landcover(); hillshade(); contours(); water(); buildings(); route_bands(); roads(); paths()
    trails(); surface_loose(); access_restrictions(); bridges(); boundaries(); pois(); stations()
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
