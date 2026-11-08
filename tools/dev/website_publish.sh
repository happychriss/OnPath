#!/usr/bin/env bash
# Website pictures (flow PNGs, docs/design/website-screens.md) → the WebP files of an onpath.earth checkout.
#   tools/dev/website_publish.sh <onpath.earth checkout> <png…>
#     e.g. tools/dev/website_publish.sh ~/onpath.earth ~/onpath.private/marketing/bilder/aktuell/E0{1,4,6}-*.png
# Help pages (assets/help/<key>-de|en.webp + sizes.json): done by the site's own converter
#   scripts/help_shots.py – it knows which flow PNG is which key (help_shots.IMAGES), the width (540, browser
#   screens up to 1200), WebP quality 78 and sizes.json, so nothing here can drift from it.
# Home page (assets/app/<key>-de|en.webp): only files that are already there are replaced, with the same name and
#   width as the old file and the same WebP setting (quality 78, method 6); the key comes from the picture list's
#   "Key" column (`ruhe (help), ruheanzeige (home)` → assets/app/ruheanzeige-de.webp).
# The page texts need no change (same names). Needs Python with Pillow (the site's converter does too).
# Afterwards in the checkout: python3 build.py; python3 scripts/check_site.py; node --check assets/app.js; push.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
[ $# -ge 2 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
SITE=$(cd "$1" && pwd); shift
[ -f "$SITE/scripts/help_shots.py" ] && [ -f "$SITE/help_shots.py" ] || { echo "$SITE is not an onpath.earth checkout"; exit 1; }
python3 -c 'import PIL' 2>/dev/null || { echo "needs Pillow (pip install Pillow)"; exit 1; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for png in "$@"; do
  [ -f "$png" ] || { echo "not found: $png"; exit 1; }
  ln -s "$(cd "$(dirname "$png")" && pwd)/$(basename "$png")" "$TMP/$(basename "$png")"
done
# help pages: the site's converter; its long "Not found" line lists every picture not handed over – not needed here
(cd "$SITE" && python3 scripts/help_shots.py "$TMP") | grep -v '^Not found' || true
# what the help pages took, and the home page pictures
python3 - "$SITE" "$ROOT/docs/design/website-screens.md" "$TMP" <<'EOF'
import os, re, sys
from pathlib import Path
from PIL import Image
site, listing, tmp = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
sys.path.insert(0, str(site))
from help_shots import IMAGES  # noqa: E402
help_src = {src: name for name, (de, en, *_) in IMAGES.items() for src in (de, en) if src}
keys = {}  # step -> keys of its "Key" column
for line in open(listing, encoding='utf-8'):
    if re.match(r'^\| [A-Z]\d\d \|', line):
        cells = [c.strip() for c in re.split(r'(?<!\\)\|', line.rstrip('\n'))[1:-1]]
        part = cells[3].split(' · ')[0]
        keys[cells[0]] = [k for k in (re.sub(r'\(.*?\)', '', p).strip() for p in part.split(',')) if re.fullmatch(r'[a-z0-9-]+', k)]
for png in sorted(tmp.iterdir()):
    m = re.match(r'^([A-Z]\d\d)-.*-(de|en)\.png$', png.name)
    done = []
    if png.name in help_src:
        done.append(f'assets/help/{help_src[png.name]}-{m.group(2) if m else "de"}.webp')
    if m:
        for key in keys.get(m.group(1), []):
            dest = site / 'assets' / 'app' / f'{key}-{m.group(2)}.webp'
            if not dest.exists():
                continue
            width = Image.open(dest).width
            im = Image.open(png).convert('RGB')
            im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
            im.save(dest, 'WEBP', quality=78, method=6)
            done.append(f'assets/app/{dest.name}')
    print(f'{png.name} → {", ".join(done) if done else "not on the website (no key / no existing file)"}')
EOF
