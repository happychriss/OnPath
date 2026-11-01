#!/usr/bin/env bash
# The release check (docs/roles/cloud-captain.md "Release check", as a script – setup concept 2026-11-01 §4f): may the
# app release and the planner deploy go out together? App and planner must route and sync identically:
#   1. routing fingerprint inputs equal: BRouter version + PLANNER_VERSION (routing/RoutingFingerprint.kt), the
#      profiles + lookups.dat (app/src/main/assets/brouter/), the BRouter jars (app/libs/)
#   2. sync format equal: the shared sync/ files
#   3. every other app file the planner compiles (appFiles in web/engine/build.gradle.kts) equal
#   4. CI green on both heads: unit.yml on the app ref, "Web spike" on the planner ref (needs gh; else "unknown")
# Two branches today (main = app, web-spike = planner). After web-spike is merged into main both refs are main:
# 1–3 hold by construction and only CI is checked.
#
# Usage: tools/ci/release_check.sh [--app REF] [--web REF] [--no-ci] [--go vX.Y.Z]
#   defaults: --app origin/main, --web origin/web-spike (falls back to the app ref when it does not exist)
#   --go vX.Y.Z  print the go line for the backlog / release issue when everything holds
# Exit: 0 = go, 1 = not equal / red, 2 = usage or git error. Reads only – fetches, never pushes or writes.
set -uo pipefail
APP=origin/main WEB=origin/web-spike CI=1 GO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP=${2:?}; shift ;;
    --web) WEB=${2:?}; shift ;;
    --no-ci) CI=0 ;;
    --go) GO=${2:?}; shift ;;
    -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "release_check: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
cd "$(git rev-parse --show-toplevel)" || exit 2
REPO=${ONPATH_REPO:-happychriss/OnPath}
SRC=app/src/main/java/com/onpath/app

for r in "$APP" "$WEB"; do
  case "$r" in origin/*) git fetch -q origin "${r#origin/}" 2>/dev/null || true ;; esac
done
git rev-parse -q --verify "$APP^{commit}" >/dev/null || { echo "release_check: no ref $APP" >&2; exit 2; }
if ! git rev-parse -q --verify "$WEB^{commit}" >/dev/null; then
  echo "release_check: $WEB not found – one branch: checking $APP alone"; WEB=$APP
fi
APP_SHA=$(git rev-parse "$APP") WEB_SHA=$(git rev-parse "$WEB")
echo "release_check: app $APP ${APP_SHA:0:8} · planner $WEB ${WEB_SHA:0:8}"

fail=0
ok() { echo "  ✓ $*"; }
bad() { echo "  ✗ $*"; fail=1; }
blob() { git rev-parse -q --verify "$1:$2" 2>/dev/null || echo "-"; }

# 1. routing fingerprint
echo "1. Routing fingerprint"
fpv() { git show "$1:$SRC/routing/RoutingFingerprint.kt" 2>/dev/null | grep -E "const val (BROUTER_VERSION|PLANNER_VERSION)" | sed 's/^ *//'; }
if [ "$(fpv "$APP")" = "$(fpv "$WEB")" ]; then ok "$(fpv "$APP" | tr '\n' ' ')"; else
  bad "versions differ:"; diff <(fpv "$APP") <(fpv "$WEB") | sed 's/^/      /'; fi
for dir in app/src/main/assets/brouter app/libs; do
  a=$(git ls-tree -r "$APP" -- "$dir" | awk '{print $3, $4}') w=$(git ls-tree -r "$WEB" -- "$dir" | awk '{print $3, $4}')
  if [ "$a" = "$w" ]; then ok "$dir equal ($(wc -l <<<"$a") files)"; else
    bad "$dir differs:"; diff <(echo "$a") <(echo "$w") | grep '^[<>]' | awk '{print "      " $1 " " $3}'; fi
done

# 2 + 3. the app files the planner compiles
BUILD=web/engine/build.gradle.kts
SPEC=$WEB; git cat-file -e "$WEB:$BUILD" 2>/dev/null || SPEC=$APP
if [ "$APP_SHA" = "$WEB_SHA" ]; then
  echo "2. Sync format"; ok "one commit for app and planner – equal by construction"
  echo "3. Shared app files"; ok "one commit for app and planner – equal by construction"
elif ! git cat-file -e "$SPEC:$BUILD" 2>/dev/null; then
  bad "no $BUILD on $WEB or $APP – cannot list the shared files"
else
  mapfile -t FILES < <(git show "$SPEC:$BUILD" | awk '/^val appFiles = listOf\(/{on=1; next} on && /^\)/{exit} on' |
    grep -v '^\s*//' | grep -o '"[^"]*\.kt"' | tr -d '"')
  sync_diff=() other_diff=()
  for f in "${FILES[@]}"; do
    [ "$(blob "$APP" "$SRC/$f")" = "$(blob "$WEB" "$SRC/$f")" ] && continue
    case "$f" in sync/*) sync_diff+=("$f") ;; *) other_diff+=("$f") ;; esac
  done
  echo "2. Sync format"
  if [ ${#sync_diff[@]} = 0 ]; then ok "sync/ files equal"; else bad "sync/ differs: ${sync_diff[*]}"; fi
  echo "3. Shared app files (${#FILES[@]} from $BUILD on ${SPEC#origin/})"
  if [ ${#other_diff[@]} = 0 ]; then ok "all equal"; else
    bad "${#other_diff[@]} differ – merge $APP into $WEB first (web/parity/merge-main.sh):"
    printf '      %s\n' "${other_diff[@]}"
  fi
fi

# 4. CI
echo "4. CI"
run_state() { # workflow file, sha → "success" / "failure" / "in_progress" / "none" / "unknown"
  command -v gh >/dev/null || { echo unknown; return; }
  gh api "repos/$REPO/actions/workflows/$1/runs?head_sha=$2&per_page=5" \
    --jq '[.workflow_runs[] | select(.conclusion != "cancelled")][0] | if . == null then "none" elif .status != "completed" then .status else .conclusion end' \
    2>/dev/null || echo unknown
}
if [ "$CI" = 0 ]; then echo "  – skipped (--no-ci)"; else
  for pair in "unit.yml $APP_SHA app" "web-spike.yml $WEB_SHA planner"; do
    set -- $pair
    [ "$3" = planner ] && [ "$WEB_SHA" = "$APP_SHA" ] && ! git cat-file -e "$APP:.github/workflows/web-spike.yml" 2>/dev/null && continue
    st=$(run_state "$1" "$2")
    case "$st" in
      success) ok "$1 green on ${2:0:8} ($3)" ;;
      unknown) bad "$1 on ${2:0:8}: unknown (no gh access) – check Actions by hand" ;;
      *) bad "$1 on ${2:0:8} ($3): $st" ;;
    esac
  done
fi

echo
if [ "$fail" = 0 ]; then
  echo "release_check: GO – app ${APP_SHA:0:8} and planner ${WEB_SHA:0:8} route and sync the same"
  [ -n "$GO" ] && echo "- [captain] release $GO: app ${APP_SHA:0:8} + planner ${WEB_SHA:0:8} OK (tools/ci/release_check.sh) – $(date -u +%F)"
  exit 0
fi
echo "release_check: NO GO – fix the ✗ lines above"
exit 1
