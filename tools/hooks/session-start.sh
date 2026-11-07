#!/usr/bin/env bash
# SessionStart hook (.claude/settings.json): what every OnPath session must know before it works – printed to stdout,
# which Claude Code adds to the session's context. Replaces the "read at the start" prose: the rules line,
# the owner's word (backlog "For the roles"), wip/* branches (work saved before a shutdown), the top of the backlog. Quiet and short (≤ ~40 lines);
# never fails the session (every part is optional). ONPATH_HOOKS=0 switches it off.
[ "${ONPATH_HOOKS:-1}" = 0 ] && exit 0
cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}" || exit 0
[ -f docs/backlog.md ] || exit 0
echo "== OnPath session start ($(git rev-parse --abbrev-ref HEAD 2>/dev/null) @ $(git rev-parse --short HEAD 2>/dev/null)) =="
echo "Rules: CLAUDE.md · ask the owner in your chat; unanswered at the end → backlog 'Waiting for the owner' · the owner's word to all = backlog 'For the roles'."
echo
git fetch -q origin 2>/dev/null
wip=$(git branch -r 2>/dev/null | grep -o 'origin/wip/[^ ]*' | head -5)
if [ -n "$wip" ]; then
  echo "-- wip/* branches (work saved before a shutdown; bring to main – docs/roles/start.md step 4) --"; echo "$wip"; echo
fi
if [ -f docs/backlog.md ]; then
  echo "-- backlog: For the roles (the owner's / captain's word; remove your tag when read) --"
  awk '/^## For the roles/{f=1;next} /^## /{f=0} f && /^- /' docs/backlog.md | cut -c1-140 | head -6
  echo "-- backlog: In progress (claims) --"
  awk '/^## In progress/{f=1;next} /^## /{f=0} f && /^- /' docs/backlog.md | cut -c1-140 | head -6
  echo "-- backlog: Next (top, the captain orders it) --"
  awk '/^## Next/{f=1;next} /^## /{f=0} f && /^- /' docs/backlog.md | cut -c1-140 | head -8
fi
exit 0
