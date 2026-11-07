#!/usr/bin/env bash
# PreToolUse hook for Bash (.claude/settings.json): the rules that must hold, enforced instead of asked for.
#   git push     → only when tools/ci/gate.sh was green for this HEAD or the same own change after a rebase
#                  (stamp .git/onpath-gate-ok: HEAD + patch-id). Pushes of wip/* (work saved before a shutdown) and
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
# the Bash call runs in the session's cwd (a worktree after `cd` / EnterWorktree) – judge the push there
cwd=$(jq -r '.cwd // empty' <<<"$in" 2>/dev/null); [ -d "$cwd" ] && cd "$cwd"
deny() { echo "onpath hook: $*" >&2; exit 2; }

if [[ $cmd =~ (^|[^[:alnum:]_])git[[:space:]]+(-C[[:space:]]+[^[:space:]]+[[:space:]]+)?push([[:space:]]|$) ]] && [[ $cmd != *--dry-run* ]] && [[ $cmd != *" wip/"* ]] && [[ $cmd != *":wip/"* ]] && [[ $cmd != *"refs/heads/wip/"* ]]; then
  # the work tree the push runs in: `git -C <dir> push` or `cd <dir> && git push` – take -C, else the current dir
  dir=.
  [[ $cmd =~ git[[:space:]]+-C[[:space:]]+([^[:space:]]+) ]] && dir=${BASH_REMATCH[1]}
  [[ $cmd =~ (^|&&|;)[[:space:]]*cd[[:space:]]+([^[:space:]&;]+) ]] && dir=${BASH_REMATCH[2]}
  dir=${dir/#\~/$HOME}
  # only in an OnPath checkout (it has the gate) – pushes to onpath.private, onpath.earth etc. are not gated
  # (bug seen 2026-11-03: the hook refused donald's pushes to onpath.private)
  if git -C "$dir" rev-parse --git-dir >/dev/null 2>&1 && [ -x "$(git -C "$dir" rev-parse --show-toplevel)/tools/ci/gate.sh" ]; then
    head=$(git -C "$dir" rev-parse HEAD 2>/dev/null)
    read -r s_head s_patch < "$(git -C "$dir" rev-parse --git-path onpath-gate-ok 2>/dev/null)" 2>/dev/null
    ok=0
    [ -n "$head" ] && [ "${s_head:-}" = "$head" ] && ok=1
    # same own change after a rebase onto a newer main → no second gate run (unit.yml checks the combination)
    if [ "$ok" = 0 ] && [ -n "${s_patch:-}" ]; then
      base=$(git -C "$dir" merge-base origin/main HEAD 2>/dev/null)
      now=$(git -C "$dir" diff "$base" HEAD 2>/dev/null | git patch-id --stable 2>/dev/null | cut -d' ' -f1)
      [ -n "$now" ] && [ "$now" = "$s_patch" ] && ok=1
    fi
    [ "$ok" = 1 ] ||
      deny "git push refused – tools/ci/gate.sh has not run green for this change (HEAD ${head:0:8}, last green ${s_head:0:8}). Run tools/ci/gate.sh, then push."
  fi
fi

if [[ $cmd =~ adb[[:space:]]+(-[sd][[:space:]]+[^[:space:]]+[[:space:]]+)?install([[:space:]]|$) ]] || [[ $cmd =~ (installDebug|installRelease)([[:space:]]|$) ]]; then
  [[ $cmd == *device.sh* ]] || deny "install refused – the Pixel only through tools/dev/device.sh pixel -- <command> (one agent per device, never while a tour records)."
fi
exit 0
