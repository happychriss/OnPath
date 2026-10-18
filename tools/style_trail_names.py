"""Trail and route NAME labels for the OnPath style (e.g. "Algunder Waalweg", AVS numbers "25").

Called by make_style.py: sources() is merged into the style sources, layers() is inserted
above trails/POIs and below place/peak labels. Owned separately from the colour scheme.

Data: trailnames.geojson (OPTIONAL file, written by tools/extract_overlays.py):
  t="n"  trail name along merged lines; tier 1 (Waalweg/Höhenweg/long named trail, from z13),
         tier 2 (other named trails, z14), tier 3 (short ones / named access tracks, z15); len in m.
  t="r"  hiking route number(s) of the way, e.g. "25" or "24 · 25" (z14+).
If the file is missing the app should strip source "trailnames" and layers "trail-*"
(like stripPoisIfMissing); otherwise nothing is drawn.

Why not the base tiles' transportation_name? It carries path names only at z14 (cut per tile,
bilingual "DE - IT" strings, a handful at z13), so long names drop at tile edges and cannot be
prioritised. The overlay has merged lines, German names and a priority tier.
"""

MED, ITA = ["Noto Sans Medium"], ["Noto Sans Italic"]
HALO = "rgba(255,255,255,0.92)"
NAME_T1 = "#5a3a1e"  # dark trail brown (paths are #5b4636)
NAME_T2 = "#6a4f38"
REF_COL = "#b0281c"  # AVS red


def interp(*stops):
    return ["interpolate", ["linear"], ["zoom"], *stops]


def sources():
    return {
        "trailnames": {"type": "geojson", "data": "file://{DATA_DIR}/trailnames.geojson",
                       "maxzoom": 14, "tolerance": 0.6},
    }


def _name_layer(tier, minzoom, font, color, size):
    return {
        "id": f"trail-name-t{tier}", "type": "symbol", "source": "trailnames", "minzoom": minzoom,
        "filter": ["all", ["==", ["get", "t"], "n"], ["==", ["get", "tier"], tier]],
        "layout": {
            "symbol-placement": "line",
            "symbol-spacing": interp(13, 200, 16, 320),
            "symbol-sort-key": ["-", 0, ["get", "len"]],  # longer trails first
            "text-field": ["get", "name"],
            "text-font": font,
            "text-size": size,
            "text-letter-spacing": 0.04,
            "text-max-angle": 45,
            "text-padding": 3,
            "text-offset": [0, -0.75],  # just beside the line, so the dashed path stays visible
        },
        "paint": {"text-color": color, "text-halo-color": HALO, "text-halo-width": 1.7,
                  "text-halo-blur": 0.3},
    }


def layers():
    # Bottom-most first; MapLibre places symbols of upper layers first (= higher priority):
    # route numbers < short names < normal names < Waalwege / long trails.
    ref = {
        "id": "trail-ref", "type": "symbol", "source": "trailnames", "minzoom": 14,
        "filter": ["==", ["get", "t"], "r"],
        "layout": {
            "symbol-placement": "line",
            "symbol-spacing": interp(14, 220, 17, 320),
            "text-field": ["get", "ref"],
            "text-font": MED,
            "text-size": interp(14, 10, 17, 11.5),
            "text-rotation-alignment": "viewport",  # upright numbers, like a trail sign
            "text-padding": 8,
        },
        "paint": {"text-color": REF_COL, "text-halo-color": "#ffffff", "text-halo-width": 2.2},
    }
    return [
        ref,
        _name_layer(3, 15, ITA, NAME_T2, interp(15, 10.5, 18, 12.5)),
        _name_layer(2, 14, ITA, NAME_T2, interp(14, 10.5, 16, 12, 18, 13)),
        _name_layer(1, 13, MED, NAME_T1, interp(13, 11, 15, 12.5, 17, 14)),
    ]
