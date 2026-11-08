#!/usr/bin/env bash
# Which website pictures (docs/design/website-screens.md, flows A–L) must be taken again after an app change.
#   tools/dev/pictures_affected.sh <since-ref>      e.g. v0.17.5 (against the working tree) or v0.17.4..v0.17.5
#   tools/dev/pictures_affected.sh --check          lists snapshot names in the picture list that match no PNG
# A step is affected when one of its Paparazzi snapshots (column "Snapshot (Paparazzi)") changed in
# app/src/test/snapshots/images/, or – for a step without a snapshot ("–") – when a file of its last column
# ("Change here" / "Planner file") changed. A snapshot name without "_" matches that test in every class (German,
# English, large font: `navigatePausedLocked`); `Class_method` matches only that class.
# Output: one line per affected step – the step and its two picture files, DE + EN – and nothing else.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
[ $# -eq 1 ] || { sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 2; }
if [ "$1" = --check ]; then changed=""; else changed=$(git -C "$ROOT" diff --name-only "$1"); fi
CHANGED="$changed" python3 - "$ROOT" "$1" <<'EOF'
import os, re, sys
root, mode = sys.argv[1], sys.argv[2]
shots_dir = 'app/src/test/snapshots/images/'
steps = []  # (step, file stem, snapshot tokens, source tokens)
for line in open(os.path.join(root, 'docs/design/website-screens.md'), encoding='utf-8'):
    if not re.match(r'^\| [A-Z]\d\d \|', line):
        continue
    cells = [c.strip() for c in re.split(r'(?<!\\)\|', line.rstrip('\n'))[1:-1]]
    stem = cells[1].strip('`')
    snaps = re.findall(r'`([^`]+)`', cells[-2])
    sources = [t for t in re.findall(r'`([^`]+)`', cells[-1]) if '.' in t or t.endswith('/')]
    steps.append((cells[0], stem, snaps, sources))

def split_shot(path):
    name = os.path.basename(path)[:-len('.png')]
    cls_method = name.split('_', 1)[1] if '_' in name else name  # drop the package
    return cls_method, cls_method.rsplit('_', 1)[-1]

def hits(token, shots):
    return any(token == cm if '_' in token else token == m for cm, m in shots)

if mode == '--check':
    shots = [split_shot(f) for f in os.listdir(os.path.join(root, shots_dir)) if f.endswith('.png')]
    bad = [f'{s}: {t}' for s, _, snaps, _ in steps for t in snaps if not hits(t, shots)]
    print('\n'.join(bad) if bad else 'all snapshot names found')
    sys.exit(1 if bad else 0)

changed = [p for p in os.environ.get('CHANGED', '').split('\n') if p]
shots = [split_shot(p) for p in changed if p.startswith(shots_dir) and p.endswith('.png')]

def source_changed(token):
    for p in changed:
        if token.endswith('/'):
            if p.startswith('app/src/main/') and '/' + token in p:
                return True
        elif '/' in token:
            if p.startswith('app/src/main/') and p.endswith('/' + token):
                return True
        elif p.startswith('web/') and p.endswith('/' + token):  # a bare file name is a planner file
            return True
    return False

for step, stem, snaps, sources in steps:
    if (snaps and any(hits(t, shots) for t in snaps)) or (not snaps and any(source_changed(t) for t in sources)):
        print(f'{step}  {stem}-de.png  {stem}-en.png')
EOF
