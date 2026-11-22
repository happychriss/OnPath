#!/usr/bin/env bash
# Multi-day tours stage 0 (docs/design/multi-day-tours.md §6 "Measured 2026-11-17"): MultiDayStage0.kt on the JVM with the
# shared app code (tools/routing/jvm-test/run.sh), plus the sync item's TrackCode with a stand-in for the rest of sync/.
# Usage: tools/multiday/run.sh <track.gpx> <segmentsDir> [workDir]
# Env: MD_RUNS="HIKE:3,HIKE:4" MD_ROUTE=1 MD_SYNC=1 TTP_MAX_CALLS=<n> JAVA_XMX=192m (see MultiDayStage0.kt).
# The GPX: python3 -I tools/multiday/wmt_to_gpx.py <relation json> <out.gpx> (Waymarked Trails, OSM relation 1736379).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
GPX=$(realpath "${1:?track.gpx}"); SEG=${2:?segments dir}; W=${3:-$(mktemp -d)}
A="$ROOT/app/src/main/java/com/onpath/app"
mkdir -p "$W"
# sync/TrackCode needs B64 (PairCode.kt) and SyncFormat.MAX_TRACK (SyncItem.kt), which pull in the whole sync package:
# copy B64 out of PairCode.kt and the one constant out of SyncItem.kt
{
  echo "package com.onpath.app.sync"
  awk 'NR>=71 && /^}/ {print; exit} NR>=71' "$A/sync/PairCode.kt" | sed -n '/^internal object B64/,$p'
  echo "object SyncFormat { $(grep -o 'const val MAX_TRACK = [0-9_]*' "$A/sync/SyncItem.kt") }"
} > "$W/SyncStub.kt"
grep -q "object B64" "$W/SyncStub.kt"; grep -q "MAX_TRACK = " "$W/SyncStub.kt"
TRACK_GPX="$GPX" KTTEST="$HERE/MultiDayStage0.kt" EXTRA_KT="$A/routing/PlanCode.kt $A/sync/TrackCode.kt $W/SyncStub.kt" \
  "$ROOT/tools/routing/jvm-test/run.sh" "$SEG" "$W"
