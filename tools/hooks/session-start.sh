#!/usr/bin/env bash
# SessionStart hook (.claude/settings.json): what every OnPath session must know before it works – printed to stdout,
# which Claude Code adds to the session's context. Replaces the "read at the start" prose: the owner's word to all,
# answers to open questions, unpushed work saved at a shutdown, the top of the backlog. Quiet and short (≤ ~40 lines);
# never fails the session (every part is optional, 20 s budget). ONPATH_HOOKS=0 switches it off.
[ "${ONPATH_HOOKS:-1}" = 0 ] && exit 0
cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}" || exit 0
[ -f tools/ask.sh ] || exit 0
T=$(command -v timeout >/dev/null && echo "timeout 20" || echo "")
echo "== OnPath session start ($(git rev-parse --abbrev-ref HEAD 2>/dev/null) @ $(git rev-parse --short HEAD 2>/dev/null)) =="
echo "Rules: CLAUDE.md · questions to the owner only via tools/ask.sh (label frage) · an-alle = the owner's word to all."
echo
echo "-- an-alle (the owner's word to every session; confirm with: tools/ask.sh an-alle --von <unit>) --"
$T tools/ask.sh an-alle 2>&1 | head -20
echo
echo "-- open questions to the owner (tools/ask.sh offen; act on answered ones, then tools/ask.sh erledigt) --"
$T tools/ask.sh offen 2>&1 | head -24
echo
git fetch -q origin 2>/dev/null
wip=$(git branch -r 2>/dev/null | grep -o 'origin/wip/[^ ]*' | head -5)
if [ -n "$wip" ]; then
  echo "-- unpushed work saved at a shutdown (carry it on, the owner deletes the branch) --"; echo "$wip"; echo
fi
if [ -f docs/backlog.md ]; then
  echo "-- backlog: In progress (claims) --"
  awk '/^## In progress/{f=1;next} /^## /{f=0} f && /^- /' docs/backlog.md | cut -c1-140 | head -6
  echo "-- backlog: Next (top, the captain orders it) --"
  awk '/^## Next/{f=1;next} /^## /{f=0} f && /^- /' docs/backlog.md | cut -c1-140 | head -8
fi
exit 0
