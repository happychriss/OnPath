"""Design tokens for the OnPath map style (themes in tools/themes/<name>.json).

A theme is a flat JSON object of readable token names -> values:
  "path.color": "#3a2a1d"                      colour (any CSS colour MapLibre Native accepts)
  "path.dash": [2.2, 1.2]                       arrays (dash patterns, font stacks) are taken as-is
  "path.width.z14": 1.4, "path.width.z16": 2.3  zoom stops -> zoom(name) builds an interpolate expression
  "path.width.base": 1.2                        optional exponential base for those stops (default 1.2)
  "extends": "classic"                          inherit every token of another theme, override some

Usage (also from other style modules, e.g. style_trail_names):
  import style_theme as T
  T.token("label.halo.color")       -> raw value (KeyError names the missing token)
  T.zoom("path.width")              -> ["interpolate", ...] from the .z<N> stops, or the scalar token
make_style.py selects the theme with T.use(name) before building; make_style.token is the same function.
"""
import json
import os

THEMES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "themes")
_active = {}
_active_name = None


def load(name, _seen=()):
    if name in _seen:
        raise ValueError(f"theme 'extends' loop: {' -> '.join(_seen + (name,))}")
    # a name, or a path to a theme file outside tools/themes (a test variant: tools/dev/push_style.sh)
    path = name if name.endswith(".json") else os.path.join(THEMES_DIR, f"{name}.json")
    with open(path) as f:
        data = json.load(f)
    parent = data.pop("extends", None)
    merged = load(parent, _seen + (name,)) if parent else {}
    merged.update({k: v for k, v in data.items() if not k.startswith("//")})
    return merged


def use(name):
    global _active, _active_name
    _active = load(name)
    _active_name = name
    return _active


def name():
    return _active_name


def _require():
    if not _active:
        use("classic")


def token(key, default=KeyError):
    _require()
    if key in _active:
        return _active[key]
    if default is KeyError:
        raise KeyError(f"theme '{_active_name}' has no token '{key}'")
    return default


def stops(key):
    """Sorted [(zoom, value)] of the tokens '<key>.z<N>'."""
    _require()
    pre = key + ".z"
    out = []
    for k, v in _active.items():
        if k.startswith(pre):
            try:
                out.append((float(k[len(pre):]), v))
            except ValueError:
                pass
    return sorted(out)


def zoom(key, default=KeyError):
    """Interpolate expression from '<key>.z<N>' stops; falls back to the scalar token '<key>'."""
    st = stops(key)
    if not st:
        return token(key, default)
    if len(st) == 1:
        return st[0][1]
    base = token(key + ".base", 1.2)
    expr = ["interpolate", ["linear"] if base == 1 else ["exponential", base], ["zoom"]]
    for z, v in st:
        expr += [int(z) if z == int(z) else z, v]
    return expr
