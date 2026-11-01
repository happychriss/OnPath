#!/usr/bin/env bash
# PreToolUse hook for Bash (.claude/settings.json): the rules that must hold, enforced instead of asked for.
#   git push     → only when tools/ci/gate.sh was green for this HEAD (stamp .git/onpath-gate-ok) and no open
#                  an-alle issue says #push-sperre (tools/ask.sh check). Pushes of wip/* (save-on-shutdown) and
#                  --dry-run pass.
#   adb install / installDebug / installRelease → only through tools/dev/device.sh (one agent per device, no
#                  install while a tour records).
# Reads the tool call as JSON on stdin (tool_input.command). Deny = exit 2 with the reason on stderr (Claude sees it
# and can fix things). ONPATH_HOOKS=0 switches it off. Anything unexpected (no jq, no git) → allow, say nothing.
[ "${ONPATH_HOOKS:-1}" = 0 ] && exit 0
command -v jq >/dev/null || exit 0
in=$(cat 2>/dev/null) || exit 0
cmd=$(jq -r '.tool_input.command // empty' <<<"$in" 2>/dev/null) || exit 0
[ -n "$cmd" ] || exit 0
# the Bash call runs in the session's cwd (a worktree after `cd` / EnterWorktree) – judge the push there; ask.sh from the repo
cwd=$(jq -r '.cwd // empty' <<<"$in" 2>/dev/null); [ -d "$cwd" ] && cd "$cwd"
ASK=${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || echo .)}/tools/ask.sh
deny() { echo "onpath hook: $*" >&2; exit 2; }

if [[ $cmd =~ (^|[^[:alnum:]_])git[[:space:]]+(-C[[:space:]]+[^[:space:]]+[[:space:]]+)?push([[:space:]]|$) ]] && [[ $cmd != *--dry-run* ]] && [[ $cmd != *" wip/"* ]] && [[ $cmd != *":wip/"* ]] && [[ $cmd != *"refs/heads/wip/"* ]]; then
  # the work tree the push runs in: `git -C <dir> push` or `cd <dir> && git push` – take -C, else the current dir
  dir=.
  [[ $cmd =~ git[[:space:]]+-C[[:space:]]+([^[:space:]]+) ]] && dir=${BASH_REMATCH[1]}
  [[ $cmd =~ (^|&&|;)[[:space:]]*cd[[:space:]]+([^[:space:]&;]+) ]] && dir=${BASH_REMATCH[2]}
  dir=${dir/#\~/$HOME}
  if git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
    head=$(git -C "$dir" rev-parse HEAD 2>/dev/null)
    stamp=$(cat "$(git -C "$dir" rev-parse --git-path onpath-gate-ok 2>/dev/null)" 2>/dev/null)
    [ -n "$head" ] && [ "$stamp" = "$head" ] ||
      deny "git push refused – tools/ci/gate.sh has not run green for HEAD ${head:0:8} (last green: ${stamp:0:8}). Run tools/ci/gate.sh, then push."
    if [ -x "$ASK" ]; then
      out=$("$ASK" check 2>&1); rc=$?
      [ "$rc" = 3 ] && deny "git push refused – an open an-alle issue says #push-sperre: $out"
      [ "$rc" = 4 ] && deny "release push refused – an open an-alle issue says #release-sperre: $out"
    fi
  fi
fi

if [[ $cmd =~ adb[[:space:]]+(-[sd][[:space:]]+[^[:space:]]+[[:space:]]+)?install([[:space:]]|$) ]] || [[ $cmd =~ (installDebug|installRelease)([[:space:]]|$) ]]; then
  [[ $cmd == *device.sh* ]] || deny "install refused – the Pixel only through tools/dev/device.sh pixel -- <command> (one agent per device, never while a tour records)."
fi
exit 0
