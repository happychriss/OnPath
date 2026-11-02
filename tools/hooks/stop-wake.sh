#!/usr/bin/env bash
# Stop hook with "asyncRewake" (.claude/settings.json) – the unit's alarm clock (owner 2026-11-02, idea from
# github.com/kunchenguid/firstmate). After every turn Claude Code runs this in the background: the session stays free
# (the owner can type), this waits without tokens, and only when there is something new for this unit it exits 2 –
# its stderr then wakes the session with a new turn. Replaces the 10-minute /loop.
#   new = a new [sofort] item for this unit in docs/backlog.md "Next", or a new an-alle issue (tools/dev/new_items.sh)
# Only in a unit session on a computer (ONPATH_UNIT set by tools/dev/unit.sh, else derived from the host name);
# cloud sessions and other folders: exit 0 at once. Claude does not dedupe async hooks → one waiter per session
# (flock). Gives up after ONPATH_WAKE_MAX s (default 3300 – the hourly wake-up starts the next waiter); a hook killed at
# its timeout does not wake the session, so the max stays below the timeout. ONPATH_HOOKS=0 switches it off.
[ "${ONPATH_HOOKS:-1}" = 0 ] && exit 0
[ "${CLAUDE_CODE_REMOTE:-}" = true ] && exit 0                    # cloud session: no unit, no wake
in=$(cat 2>/dev/null)
ROOT=${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}
cd "$ROOT" 2>/dev/null || exit 0
[ -x tools/dev/new_items.sh ] || exit 0
UNIT=${ONPATH_UNIT:-}
if [ -z "$UNIT" ]; then
  case "$(hostname -s 2>/dev/null)" in donald*) UNIT=donald ;; hp*|laptop*) UNIT=laptop ;; *) exit 0 ;; esac
fi
sid=$(jq -r '.session_id // empty' <<<"$in" 2>/dev/null); sid=${sid:-unknown}
command -v flock >/dev/null || exit 0
LOCK=$(git rev-parse --git-path "onpath-wake-$sid.lock" 2>/dev/null) || exit 0
exec 9>>"$LOCK"
flock -n 9 || exit 0                                              # a waiter for this session already runs
MAX=${ONPATH_WAKE_MAX:-3300} STEP=${ONPATH_WAKE_STEP:-60} t=0
CHECK=${ONPATH_NEW_ITEMS_CMD:-tools/dev/new_items.sh}
while [ "$t" -lt "$MAX" ]; do
  out=$($CHECK "$UNIT" 2>/dev/null)
  if [ -n "$out" ]; then
    { echo "Wecker ($UNIT): neu seit deiner letzten Antwort –"; echo "$out";
      echo "Nimm es jetzt (Claim im Backlog, lange Arbeit als Hintergrund-Agent), oder sag kurz, warum nicht."; } >&2
    exit 2
  fi
  sleep "$STEP"; t=$((t + STEP))
done
exit 0
