#!/usr/bin/env python3
"""Machine check: can a POI's NAME be drawn without its SYMBOL?  (owner 2026-11-15, 2026-11-20)

  python3 tools/check_poi_names.py            generate every theme in tools/themes x map format 1 / 2 and check it
  python3 tools/check_poi_names.py STYLE.json check generated style files (sprite: app/src/main/assets/sprites)

For every POI kind (core PoiKinds.kt + extract_pois.classify + the style's own kind lists) x feature variant (named /
unnamed, "merged" / "detail" bus flags, "dz" set or not) x zoom 0..22 (step 0.25) it works out which layers draw the
feature (minzoom / maxzoom / filter, evaluated here) and what each draws. Findings, by cause:

  NO_SPRITE      the icon expression names an image the sprite lacks (in 1x or 2x): MapLibre then draws the TEXT ALONE
  NAME_NO_ICON   a layer draws the name but has no icon-image (a text-only layer), or its icon is not in the sprite
  ICON_OPTIONAL  a layer with a name and icon-optional (the name survives, the icon is dropped)
  ICON_GAP       a name is drawn at a zoom where no layer draws that feature's icon
  NO_LAYER       a kind (data / Settings) that no poi- layer draws at any zoom
  NOT_POI_LAYER  a layer reads the POIs (source "pois" / base "poi") but its id is not poi-*: the Settings kind filter
                 (LayerSettings / PoiFilter touch poi- ids only) would not hide its symbols and names
  KIND_FILTER    a poi- icon layer whose filter has no kind test (the Settings filter ANDs one onto it)

MapLibre Native placement rules relied on: icon required + text-optional = the name is placed only together with its
icon; a sprite image that does not exist = the symbol is text only.
Exit code 1 when anything is found.  Pure Python (no MapLibre).
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = os.path.join(HERE, "..")
SPRITES = os.path.join(ROOT, "app", "src", "main", "assets", "sprites")
POI_LAYER_PREFIX = "poi-"
ZOOMS = [z / 4 for z in range(0, 22 * 4 + 1)]
NAME = "Some Name"


# ---- a small expression evaluator (the operators make_style.py uses for POI layers) ----------------------------------
def ev(x, f, z):
    if not isinstance(x, list):
        return x
    op, a = x[0], x[1:]
    if op == "literal":
        return a[0]
    if op == "zoom":
        return z
    if op == "get":
        return f.get(a[0])
    if op == "has":
        return a[0] in f
    if op == "!":
        return not ev(a[0], f, z)
    if op == "all":
        return all(ev(i, f, z) for i in a)
    if op == "any":
        return any(ev(i, f, z) for i in a)
    if op == "==":
        return ev(a[0], f, z) == ev(a[1], f, z)
    if op == "!=":
        return ev(a[0], f, z) != ev(a[1], f, z)
    if op in (">=", "<=", ">", "<"):
        l, r = ev(a[0], f, z), ev(a[1], f, z)
        return {">=": l >= r, "<=": l <= r, ">": l > r, "<": l < r}[op]
    if op == "in":
        return ev(a[0], f, z) in ev(a[1], f, z)
    if op == "to-number":
        for i in a:
            v = ev(i, f, z)
            try:
                if v is not None:
                    return float(v)
            except (TypeError, ValueError):
                pass
        return 0.0
    if op == "to-string":
        return str(ev(a[0], f, z))
    if op == "coalesce":
        for i in a:
            v = ev(i, f, z)
            if v is not None:
                return v
        return None
    if op == "concat":
        return "".join(str(ev(i, f, z)) for i in a)
    if op == "step":
        v = ev(a[0], f, z)
        out = ev(a[1], f, z)
        for stop, val in zip(a[2::2], a[3::2]):
            if v >= stop:
                out = ev(val, f, z)
        return out
    if op == "case":
        for c, v in zip(a[0:-1:2], a[1:-1:2]):
            if ev(c, f, z):
                return ev(v, f, z)
        return ev(a[-1], f, z)
    if op == "match":
        v = ev(a[0], f, z)
        for labels, out in zip(a[1:-1:2], a[2:-1:2]):
            if v == labels or (isinstance(labels, list) and v in labels):
                return ev(out, f, z)
        return ev(a[-1], f, z)
    if op == "interpolate":
        return 1.0  # sizes / opacities: any positive number
    raise ValueError(f"expression operator not known to check_poi_names: {op}")


def shown(layer, f, z):
    """Whether layer draws feature f at zoom z (MapLibre: minzoom <= z < maxzoom)."""
    if layer.get("layout", {}).get("visibility") == "none":
        return False
    if z < layer.get("minzoom", 0) or z >= layer.get("maxzoom", 24):
        return False
    return bool(ev(layer["filter"], f, z)) if "filter" in layer else True


def mentions_name(expr):
    return bool(re.search(r'"(name|name:\w+|name_\w+)"', json.dumps(expr)))


def poi_layers(style):
    return [l for l in style["layers"] if l.get("type") == "symbol"
            and (l.get("source") == "pois" or l["id"].startswith(POI_LAYER_PREFIX)
                 or l.get("source-layer") in ("poi", "pois"))]


# ---- the kinds ------------------------------------------------------------------------------------------------------
def kinds_from_kotlin():
    p = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "onpath", "app", "poi", "PoiKinds.kt")
    with open(p, encoding="utf-8") as fh:
        return re.findall(r'PoiKind\("(\w+)", PoiGroup\.(?!OTHER)', fh.read())


def kinds_from_extract():
    p = os.path.join(HERE, "extract_pois.py")
    with open(p, encoding="utf-8") as fh:
        src = fh.read()
    body = src[src.index("def classify"):src.index("def clean")]
    return re.findall(r'return "(\w+)"', body) + re.findall(r'return "(\w+)" if', body)


def style_kinds(style):
    """Kinds named in the style's own POI filters (["in", kind, ["literal", [...]]], == "bus" ...)."""
    s = set()
    for l in poi_layers(style):
        txt = json.dumps(l.get("filter", []))
        for m in re.finditer(r'\["literal", \[([^\]]*)\]\]', txt):
            s |= set(re.findall(r'"(\w+)"', m.group(1)))
        s |= set(re.findall(r'"==", \["get", "kind"\], "(\w+)"', txt))
        s |= set(re.findall(r'"==", "kind", "(\w+)"', txt))
    return s


def all_kinds(style):
    return sorted(set(kinds_from_kotlin()) | set(kinds_from_extract()) | style_kinds(style))


def variants(kind):
    for named in (True, False):
        for flag in (None, "merged", "detail"):
            for dz in (None, 0):
                f = {"kind": kind, "id": "n1", "pr": 100}
                if named:
                    f["name"] = NAME
                if flag:
                    f[flag] = 1
                if dz is not None:
                    f["dz"] = dz
                yield f


# ---- the check ------------------------------------------------------------------------------------------------------
def check(style, sprite_names, kinds):
    """Findings: a sorted list of (cause, text)."""
    out = set()
    layers = poi_layers(style)

    for l in layers:
        lay = l.get("layout", {})
        has_text = "text-field" in lay
        if not l["id"].startswith(POI_LAYER_PREFIX) and (has_text or "icon-image" in lay):
            out.add(("NOT_POI_LAYER", f"{l['id']}: reads POIs but its id is not poi-* (Settings kind filter misses it)"))
            continue
        if has_text and mentions_name(lay["text-field"]) and lay.get("icon-optional") is True:
            out.add(("ICON_OPTIONAL", f"{l['id']}: icon-optional with a name (the name can stay alone)"))
        if "icon-image" in lay and "kind" not in json.dumps(l.get("filter", [])) and "kind" in json.dumps(lay["icon-image"]):
            out.add(("KIND_FILTER", f"{l['id']}: no kind test in its filter (the Settings kind filter ANDs one on)"))

    for kind in kinds:
        any_layer = False
        for f0 in variants(kind):
            icon_zooms, name_zooms = set(), set()
            for z in ZOOMS:
                for l in layers:
                    if not shown(l, f0, z):
                        continue
                    any_layer = True
                    lay, paint = l.get("layout", {}), l.get("paint", {})
                    icon = None
                    if "icon-image" in lay:
                        icon = ev(lay["icon-image"], f0, z)
                        if icon in sprite_names:
                            icon_zooms.add(z)
                        else:
                            out.add(("NO_SPRITE", f"{kind}: {l['id']} icon '{icon}' not in the sprite"))
                    if "text-field" in lay and ev(lay["text-field"], f0, z) == NAME and ev(paint.get("text-opacity", 1), f0, z):
                        name_zooms.add(z)
                        if icon is None:
                            out.add(("NAME_NO_ICON", f"{kind}: {l['id']} draws the name, no icon-image"))
                        elif icon not in sprite_names:
                            out.add(("NAME_NO_ICON", f"{kind}: {l['id']} name with missing icon '{icon}' = text alone"))
            gap = sorted(name_zooms - icon_zooms)
            if gap:
                out.add(("ICON_GAP", f"{kind}: a name is drawn at z{gap[0]:g} where no layer draws the icon"))
        if not any_layer:
            out.add(("NO_LAYER", f"{kind}: no poi- layer draws it at any zoom"))
    return sorted(out)


def sprite_names_of(path=SPRITES):
    names = None
    for fn in ("onpath.json", "onpath@2x.json"):
        with open(os.path.join(path, fn), encoding="utf-8") as fh:
            n = set(json.load(fh))
        names = n if names is None else names & n   # an image must exist in both resolutions
    return names


def main(argv):
    sprite = sprite_names_of()
    styles = []
    if argv:
        for p in argv:
            with open(p, encoding="utf-8") as fh:
                styles.append((p, json.load(fh)))
    else:
        import make_style
        for th in sorted(f[:-5] for f in os.listdir(os.path.join(HERE, "themes")) if f.endswith(".json")):
            for fmt in (1, 2):
                make_style.build(th, fmt, out=os.devnull)
                styles.append((f"theme {th} format {fmt}", {"layers": json.loads(json.dumps(make_style.L))}))
    total = 0
    for name, st in styles:
        found = check(st, sprite, all_kinds(st))
        total += len(found)
        print(f"{name}: {len(found)} finding(s)")
        for cause, text in found:
            print(f"  {cause}: {text}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
