"""POI names never without their symbol: cd tools && python3 -m unittest -v test_check_poi_names
(the check itself: tools/check_poi_names.py; make_style.py must stay clean for every theme x map format)."""
import copy
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_poi_names as c  # noqa: E402
import make_style  # noqa: E402


def built(theme, fmt):
    make_style.build(theme, fmt, out=os.devnull)
    return {"layers": json.loads(json.dumps(make_style.L))}


def causes(style, sprite=None):
    return {cause for cause, _ in c.check(style, sprite or c.sprite_names_of(), c.all_kinds(style))}


class GeneratedStyles(unittest.TestCase):
    def test_every_theme_and_format_is_clean(self):
        themes = sorted(f[:-5] for f in os.listdir(os.path.join(c.HERE, "themes")) if f.endswith(".json"))
        for theme in themes:
            for fmt in (1, 2):
                with self.subTest(theme=theme, fmt=fmt):
                    st = built(theme, fmt)
                    self.assertEqual(c.check(st, c.sprite_names_of(), c.all_kinds(st)), [])

    def test_every_kind_has_both_sprite_images(self):
        sprite = c.sprite_names_of()
        for k in c.all_kinds(built("classic", 2)):
            self.assertIn(f"poi-{k}", sprite)
            self.assertIn(f"poi-{k}-small", sprite)


class CheckFindsBreakage(unittest.TestCase):
    def setUp(self):
        self.style = built("classic", 2)

    def named(self, st):
        return [l for l in st["layers"] if l["id"].endswith("-named")]

    def test_missing_sprite_image(self):
        sprite = c.sprite_names_of() - {"poi-bus", "poi-bus-small"}
        self.assertIn("NO_SPRITE", causes(self.style, sprite))

    def test_text_only_name_layer(self):
        st = copy.deepcopy(self.style)
        for l in self.named(st):
            l["layout"].pop("icon-image")
        self.assertIn("NAME_NO_ICON", causes(st))

    def test_icon_optional(self):
        st = copy.deepcopy(self.style)
        self.named(st)[0]["layout"]["icon-optional"] = True
        self.assertIn("ICON_OPTIONAL", causes(st))

    def test_name_where_no_icon_is_drawn(self):
        st = copy.deepcopy(self.style)
        for l in self.named(st):   # the names stay, the icons of the same layers go
            l["layout"].pop("icon-image")
        self.assertIn("ICON_GAP", causes(st))

    def test_name_layer_not_a_poi_layer(self):
        st = copy.deepcopy(self.style)
        st["layers"].append({"id": "pois-names", "type": "symbol", "source": "pois", "source-layer": "pois",
                             "layout": {"text-field": ["get", "name"]}})
        self.assertIn("NOT_POI_LAYER", causes(st))

    def test_kind_without_layer(self):
        st = copy.deepcopy(self.style)
        st["layers"] = [l for l in st["layers"] if not l["id"].startswith("poi-bus")]
        self.assertIn("NO_LAYER", causes(st))


if __name__ == "__main__":
    unittest.main()
