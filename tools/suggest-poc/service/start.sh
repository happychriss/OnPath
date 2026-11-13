#!/usr/bin/env bash
# Tour-suggestions POC: compile (javac, ~2 s) and start the BRouter route service.
# Usage: start.sh [segmentsDir] [port]      env: PROFILE_DIR (default: the app's profiles + make_profiles.py), JAVA_XMX (default 1g), THREADS (4)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
SEG=${1:-${ONPATH_SEGMENTS:-$HOME/onpath-routing/segments}}
PORT=${2:-8791}
BUILD=${SUGGEST_BUILD:-$HOME/.cache/onpath-suggest-poc}
OUT=$BUILD/classes-$PORT
# the app's profiles + the POC's additions (make_profiles.py); PROFILE_DIR=<dir> uses a folder as it is
if [ -n "${PROFILE_DIR:-}" ]; then PROFILES=$PROFILE_DIR; else
  PROFILES=$BUILD/profiles-$PORT
  python3 "$HERE/make_profiles.py" "$ROOT/app/src/main/assets/brouter" "$PROFILES"
fi
[ -f "$SEG/E5_N50.rd5" ] || echo "note: $SEG/E5_N50.rd5 missing (Hilden / Düsseldorf need it)" >&2
LIBS=$(ls "$ROOT"/app/libs/brouter-*.jar | tr '\n' ':')
mkdir -p "$OUT"
javac -nowarn -cp "$LIBS" -d "$OUT" "$HERE/RouteService.java"
exec nice -n 19 java -Xmx"${JAVA_XMX:-1g}" -cp "$LIBS$OUT" RouteService "$SEG" "$PROFILES" "$PORT" "${THREADS:-4}"
