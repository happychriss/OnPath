#!/usr/bin/env bash
# Tour-suggestions POC: compile (javac, ~2 s) and start the BRouter route service.
# Usage: start.sh [segmentsDir] [port]      env: PROFILE_DIR (default: the app's profiles), JAVA_XMX (default 1g), THREADS (4)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
SEG=${1:-${ONPATH_SEGMENTS:-$HOME/onpath-routing/segments}}
PORT=${2:-8791}
PROFILES=${PROFILE_DIR:-$ROOT/app/src/main/assets/brouter}
OUT=${SUGGEST_BUILD:-$HOME/.cache/onpath-suggest-poc/classes}
[ -f "$SEG/E5_N50.rd5" ] || echo "note: $SEG/E5_N50.rd5 missing (Hilden / Düsseldorf need it)" >&2
LIBS=$(ls "$ROOT"/app/libs/brouter-*.jar | tr '\n' ':')
mkdir -p "$OUT"
javac -nowarn -cp "$LIBS" -d "$OUT" "$HERE/RouteService.java"
exec nice -n 19 java -Xmx"${JAVA_XMX:-1g}" -cp "$LIBS$OUT" RouteService "$SEG" "$PROFILES" "$PORT" "${THREADS:-4}"
