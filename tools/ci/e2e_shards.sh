#!/usr/bin/env bash
# The full E2E run (all but the Release-Rundgang) of one commit, split over N GitHub runs side by side – for a
# checkpoint or before a release, as long as e2e.yml itself has no shards (tools/ci/pending/e2e-nightly-shards-*.patch;
# once that is merged, `tools/ci/e2e_github.sh --wait` alone does the same on four emulators and this script can go).
# How: e2e.yml has one queue per ref, so the commit is pushed to N short-lived branches ci/<name>-<sha>-<i> and each
# gets a list of tests (tools/ci/e2e_github.sh --ref … --tests …). Big classes (≥ 8 tests) are split by method.
#   tools/ci/e2e_shards.sh [--sha REF] [--shards 4] [--name umbau] [--dry-run]
# Exit: 0 all green, 1 a shard red, 2 usage / GitHub error. The ci/ branches stay; remove them afterwards with
#   MIN_AGE_H=0 tools/dev/prune_branches.sh delete <branches…>   (own ci/ branches whose commit is in main: no owner question)
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 2
SHA=HEAD N=4 NAME=umbau DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) SHA=${2:?}; shift ;;
    --shards) N=${2:?}; shift ;;
    --name) NAME=${2:?}; shift ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "e2e_shards: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
SHA=$(git rev-parse --verify "$SHA^{commit}") || exit 2
SHORT=${SHA:0:7}
OUT=${TMPDIR:-/tmp}/e2e-shards-$SHORT
mkdir -p "$OUT"

# The test lists: one line per shard, comma-separated (the tests of the commit itself, not of the work tree)
python3 - "$SHA" "$N" > "$OUT/lists.txt" <<'PY' || exit 2
import re, subprocess, sys
sha, n = sys.argv[1], int(sys.argv[2])
d = "app/src/androidTest/java/com/onpath/app/e2e/"
skip = {"ReleaseRundgang", "CrashHuntSetup", "MainThreadMeasure", "E2EBase", "RundgangMailbox"}  # the Rundgang + tools
files = subprocess.run(["git", "ls-tree", "--name-only", sha, d], capture_output=True, text=True, check=True).stdout.split()
units = []  # (weight, test spec)
for f in sorted(files):
    cls = f.rsplit("/", 1)[1].removesuffix(".kt")
    if cls in skip: continue
    src = subprocess.run(["git", "show", f"{sha}:{f}"], capture_output=True, text=True, check=True).stdout
    methods = re.findall(r"@Test(?:\([^)]*\))?\s+(?:@\w+(?:\([^)]*\))?\s+)*fun\s+(\w+)\s*\(", src)
    if not methods: continue
    full = "com.onpath.app.e2e." + cls
    if len(methods) >= 8 and "Parameterized" not in src:
        units += [(1, f"{full}#{m}") for m in methods]
    else:
        units.append((len(methods), full))
shards = [[0, []] for _ in range(n)]
for w, spec in sorted(units, key=lambda u: -u[0]):
    s = min(shards, key=lambda s: s[0]); s[0] += w; s[1].append(spec)
for w, specs in shards:
    print(",".join(specs))
print("e2e_shards: %d tests in %d shards (%s)" % (sum(s[0] for s in shards), n, " / ".join(str(s[0]) for s in shards)), file=sys.stderr)
PY

pids=() refs=()
i=0
while read -r tests; do
  ref="ci/$NAME-$SHORT-$i"; refs+=("$ref")
  if [ "$DRY" = 1 ]; then echo "$ref: $tests"; i=$((i + 1)); continue; fi
  git push -q origin "$SHA:refs/heads/$ref" || { echo "e2e_shards: cannot push $ref" >&2; exit 2; }
  tools/ci/e2e_github.sh --ref "$ref" --tests "$tests" --wait --out "$OUT/$i" > "$OUT/$i.log" 2>&1 &
  pids+=($!)
  i=$((i + 1))
  sleep 20 # e2e_github finds its run by "the newest dispatch on the ref" – keep the dispatches apart
done < "$OUT/lists.txt"
[ "$DRY" = 1 ] && exit 0

rc=0
for i in "${!pids[@]}"; do
  wait "${pids[$i]}"; r=$?
  [ $r = 0 ] || rc=1
  echo "── shard $i (${refs[$i]}): $([ $r = 0 ] && echo green || echo "RED rc=$r")"
  grep -E "✗|e2e_github: (run|[a-z_]+ after|[0-9]+ test case)|refused|no run found|no e2e-report" "$OUT/$i.log"
done
echo "e2e_shards: $([ $rc = 0 ] && echo "all green" || echo "RED") · $SHA · reports in $OUT · branches: ${refs[*]}"
exit $rc
