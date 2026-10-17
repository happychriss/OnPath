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


def build(pr):
    images = {}
    for kind, (group, icon) in KINDS.items():
        col = GROUPS[group]
        images[f"poi-{kind}"] = badge(col, icon, pr)
        images[f"poi-{kind}-small"] = badge(col, None, pr, canvas=12, r_out=4.6, ring=1.3, shadow=True)
    # simple shelf packing
    cols = 8
    cell = 24 * pr
    names = list(images)
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell, rows * cell), (0, 0, 0, 0))
    index = {}
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
