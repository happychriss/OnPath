#!/usr/bin/env python3
"""Generate the OnPath POI sprite (app/src/main/assets/sprites/onpath[.json|.png|@2x.json|@2x.png]).

Each POI kind gets two images:
  poi-<kind>        round badge in the group colour, white pictogram, white ring, soft shadow (~22 px at 1x)
  poi-<kind>-small  small coloured dot with white ring, no pictogram (for far zooms)
plus a neutral fallback "poi-default" / "poi-default-small".

Pictograms: Maki (Mapbox, CC0-1.0) and Temaki (Rapid editor, CC0-1.0), copied into tools/icons/;
"custom-hut.svg" and "custom-cave.svg" are our own (CC0). Colours must match app poi/PoiKinds.kt (PoiGroup).

Requires: pip install cairosvg pillow   (libcairo2 must be installed)
Run:      python3 tools/make_sprite.py
"""
import io
import json
import os

import cairosvg
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ICONS = os.path.join(HERE, "icons")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "sprites")

# Same values as PoiGroup in app/src/main/java/com/onpath/app/poi/PoiKinds.kt
GROUPS = {
    "food": "#d9822b", "hut": "#b8392e", "water": "#2f86c9", "sight": "#7d5ba6",
    "transport": "#4a6382", "facility": "#5e7f73", "other": "#777777",
}
# kind -> (group, pictogram file in tools/icons/)
KINDS = {
    "viewpoint": ("sight", "temaki-binoculars"),
    "hut": ("hut", "custom-hut"),
    "bivouac": ("hut", "temaki-sleep_shelter"),
    "shelter": ("hut", "maki-shelter"),
    "camping": ("hut", "maki-campsite"),
    "restaurant": ("food", "maki-restaurant"),
    "cafe": ("food", "maki-cafe"),
    "bar": ("food", "maki-beer"),
    "fast_food": ("food", "maki-fast-food"),
    "shop": ("food", "maki-grocery"),
    "water": ("water", "maki-drinking-water"),
    "spring": ("water", "maki-water"),
    "waterfall": ("water", "maki-waterfall"),
    "castle": ("sight", "maki-castle"),
    "ruins": ("sight", "temaki-ruins"),
    "church": ("sight", "maki-religious-christian"),
    "chapel": ("sight", "maki-religious-christian"),
    "cave": ("sight", "custom-cave"),
    "museum": ("sight", "maki-museum"),
    "bus": ("transport", "maki-bus"),
    "train": ("transport", "maki-rail"),
    "lift": ("transport", "temaki-gondola_lift"),
    "parking": ("transport", "maki-parking"),
    "toilets": ("facility", "maki-toilet"),
    "picnic": ("facility", "maki-picnic-site"),
    "info": ("facility", "maki-information"),
    "bike_shop": ("facility", "temaki-bicycle_repair"),
    "charging": ("facility", "maki-charging-station"),
    "default": ("other", None),
}

SS = 8  # supersampling factor


def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def pictogram(name, px):
    """White pictogram of px*px pixels (alpha from the SVG)."""
    png = cairosvg.svg2png(url=os.path.join(ICONS, name + ".svg"), output_width=px, output_height=px)
    a = Image.open(io.BytesIO(png)).convert("RGBA").getchannel("A")
    white = Image.new("RGBA", (px, px), (255, 255, 255, 0))
    white.putalpha(a)
    return white


def disc(size, cx, cy, r, fill):
    im = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)
    return im


def badge(color, icon, pr, canvas=24, r_out=10.5, ring=1.6, pict=12.5, shadow=True):
    """Render one badge at pixel ratio pr. Sizes are in 1x pixels."""
    n = canvas * pr * SS
    c = n / 2
    sc = pr * SS
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if shadow:
        sh = disc((n, n), c, c + 0.9 * sc, r_out * sc, (40, 30, 20, 110))
        sh = sh.filter(ImageFilter.GaussianBlur(1.1 * sc))
        im = Image.alpha_composite(im, sh)
    im = Image.alpha_composite(im, disc((n, n), c, c, r_out * sc, (255, 255, 255, 255)))
    im = Image.alpha_composite(im, disc((n, n), c, c, (r_out - ring) * sc, hex_rgb(color) + (255,)))
    if icon:
        p = int(round(pict * sc))
        pic = pictogram(icon, p)
        layer = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        layer.paste(pic, (int(c - p / 2), int(c - p / 2)))
        im = Image.alpha_composite(im, layer)
    return im.resize((canvas * pr, canvas * pr), Image.LANCZOS)


def no_entry(pr, canvas=16):
    """Red 'no entry' sign (white ring, white bar) for ways with access=no/private."""
    n = canvas * pr * SS
    c = n / 2
    sc = pr * SS
    im = disc((n, n), c, c, 7.4 * sc, (255, 255, 255, 255))
    im = Image.alpha_composite(im, disc((n, n), c, c, 6.2 * sc, (200, 32, 38, 255)))
    ImageDraw.Draw(im).rectangle((c - 3.8 * sc, c - 1.1 * sc, c + 3.8 * sc, c + 1.1 * sc), fill=(255, 255, 255, 255))
    return im.resize((canvas * pr, canvas * pr), Image.LANCZOS)


# Ground symbols (fill-pattern tiles, drawn from z14 by make_style.py landcover()): very light, a darker tone of
# the ground colour. Ground colours = tools/themes/classic.json; design: docs/design/map-colour-analysis.md.
# name -> (ground colour, tile w, h (1x px), darken towards ink, opacity, filled?, stroke width, SVG content)
PATTERNS = {
    "pattern-wetland": ("#d4e6d0", 32, 26, .55, .50, False, .8,
                        '<path d="M2 8h9M4.5 8v-2.4M6.5 8v-3.4M8.5 8v-2.4M18 21h9M20.5 21v-2.4M22.5 21v-3.4M24.5 21v-2.4"/>'),
    "pattern-farmland": ("#f1f0de", 18, 8, .45, .24, False, .8, '<path d="M1 2h8M10 6h7"/>'),
    "pattern-rock": ("#e7e2d9", 32, 32, .55, .45, False, .7,
                     '<path d="M3 11l2.5-5 5.5-1.5 2 4.5-2.5 3zM8 6.5l1 3.5M18 26l1.5-4.5 5-1 1.5 3.5-3 3zM21.5 21.5l.5 3.5'
                     'M22 9.5l2.8-3.5 3.2 2-1 2.5z"/>'),
    "pattern-sand": ("#efe6c9", 9, 9, .45, .38, True, 0, '<circle cx="2" cy="2" r=".5"/><circle cx="6.5" cy="6.5" r=".5"/>'),
}


def pattern(name, pr):
    ground, w, h, dk, op, filled, sw, body = PATTERNS[name]
    g = hex_rgb(ground)
    ink = "#%02x%02x%02x" % tuple(round(v * (1 - dk) + t * dk) for v, t in zip(g, (38, 34, 26)))
    paint = (f'fill="{ink}" stroke="none"' if filled else
             f'fill="none" stroke="{ink}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w * pr}" height="{h * pr}" viewBox="0 0 {w} {h}">'
           f'<g {paint} opacity="{op}">{body}</g></svg>')
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode()))).convert("RGBA")


def build(pr):
    images = {}
    for kind, (group, icon) in KINDS.items():
        col = GROUPS[group]
        images[f"poi-{kind}"] = badge(col, icon, pr)
        images[f"poi-{kind}-small"] = badge(col, None, pr, canvas=12, r_out=4.6, ring=1.3, shadow=True)
    images["access-no"] = no_entry(pr)
    # simple shelf packing
    cols = 8
    cell = 24 * pr
    names = list(images)
    rows = (len(names) + cols - 1) // cols
    pats = {n: pattern(n, pr) for n in PATTERNS}
    prow = max(im.height for im in pats.values())
    sheet = Image.new("RGBA", (max(cols * cell, sum(im.width for im in pats.values())), rows * cell + prow), (0, 0, 0, 0))
    index = {}
    # pattern tiles in one row below the icons (they are wider than an icon cell)
    x = 0
    for name, img in pats.items():
        sheet.paste(img, (x, rows * cell))
        index[name] = {"x": x, "y": rows * cell, "width": img.width, "height": img.height, "pixelRatio": pr}
        x += img.width
    for i, name in enumerate(names):
        img = images[name]
        x, y = (i % cols) * cell, (i // cols) * cell
        sheet.paste(img, (x, y))
        index[name] = {"x": x, "y": y, "width": img.width, "height": img.height, "pixelRatio": pr}
    suffix = "" if pr == 1 else f"@{pr}x"
    os.makedirs(OUT, exist_ok=True)
    sheet.save(os.path.join(OUT, f"onpath{suffix}.png"), optimize=True)
    with open(os.path.join(OUT, f"onpath{suffix}.json"), "w") as f:
        json.dump(index, f, indent=1, sort_keys=True)
    print("wrote", os.path.abspath(os.path.join(OUT, f"onpath{suffix}.png")), sheet.size, len(index), "images")


if __name__ == "__main__":
    for pr in (1, 2):
        build(pr)
