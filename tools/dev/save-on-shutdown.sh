#!/usr/bin/env bash
# NOT ACTIVE YET – part of the setup switch after v0.14.0 (docs/roles/setup-vorschlag-2026-11-01.md §6c). Nothing calls
# this until the owner installs the user service (tools/dev/unit.sh install).
#
# Saves unpushed work when the computer shuts down, so the owner can switch off any time (owner 2026-11-01).
# For every git checkout under ROOT (and its worktrees): if it has changed / new files or commits that no remote branch
# holds, a snapshot commit (parent = HEAD, all changes incl. new files, .gitignore respected) is pushed to
#   wip/<host>-<YYYYMMDD-HHMM>-<repo folder>-<branch>
# The checkout itself is NOT touched: no branch switch, no stash, index and files stay as they are (the snapshot is
# built with a temporary index). Never pushes to main, never force-pushes. Without network the snapshot is kept as a
# local ref (refs/onpath-wip/…) and pushed by `retry` at the next start.
# The next session sees it at its start (`git branch -r | grep wip/`), carries the work on, and leaves deleting the
# branch to the owner.
#
# Usage: tools/dev/save-on-shutdown.sh save|retry|dry   (ROOT=~/Projects/work by default; env ROOT, MAXMB=50, TIMEOUT=20)
#   save  – snapshot + push (systemd ExecStop)        retry – push snapshots that could not be pushed (ExecStart)
#   dry   – only print what would be saved
set -uo pipefail
MODE=${1:-dry}
ROOT=${ROOT:-$HOME/Projects/work}
MAXMB=${MAXMB:-50}
TIMEOUT=${TIMEOUT:-20}
STATE=${XDG_STATE_HOME:-$HOME/.local/state}/onpath-save
HOST=$(hostname -s 2>/dev/null || hostname)
STAMP=$(date +%Y%m%d-%H%M)
mkdir -p "$STATE"
LOG=$STATE/log
log() { echo "$(date '+%F %T') $*" | tee -a "$LOG"; }

checkouts() { # every work tree of every repo directly under ROOT (depth 1–2)
  find "$ROOT" -mindepth 1 -maxdepth 3 -name .git \( -type d -o -type f \) 2>/dev/null | while read -r g; do
    git -C "$(dirname "$g")" worktree list --porcelain 2>/dev/null | sed -n 's/^worktree //p'
  done | sort -u
}

save_one() { # dir
  local d=$1 name dirty ahead tmp tree commit branch big key
  cd "$d" 2>/dev/null || return 0
  git rev-parse --verify -q HEAD >/dev/null || return 0
  git remote get-url origin >/dev/null 2>&1 || return 0
  # name = the repo's main folder + this work tree's branch (an agent's worktree in /tmp is named "wt" or so –
  # the branch says where the work belongs; seen on donald 2026-11-01)
  local repo br
  repo=$(basename "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")")
  br=$(git rev-parse --abbrev-ref HEAD 2>/dev/null); [ "$br" = HEAD ] && br="detached-$(git rev-parse --short HEAD)"
  name="$repo-$(printf '%s' "$br" | tr -c 'A-Za-z0-9._-' '-' | sed 's/-\{2,\}/-/g; s/-$//')"
  dirty=$(git status --porcelain --untracked-files=normal | wc -l)
  # commits no remote branch holds (what we know of the remote – no fetch at shutdown)
  ahead=$(git rev-list --count HEAD --not --remotes=origin 2>/dev/null || echo 0)
  [ "$dirty" = 0 ] && [ "$ahead" = 0 ] && return 0
  branch="wip/$HOST-$STAMP-$name"
  if [ "$MODE" = dry ]; then log "would save $d: $dirty changed file(s), $ahead unpushed commit(s) → $branch"; return 0; fi
  commit=$(git rev-parse HEAD)
  if [ "$dirty" != 0 ]; then
    tmp=$(mktemp); cp "$(git rev-parse --git-path index)" "$tmp" 2>/dev/null || rm -f "$tmp"
    export GIT_INDEX_FILE=$tmp
    git add -A . 2>>"$LOG"
    # never push huge files (map data, APKs that slipped past .gitignore)
    big=$(git diff --cached --name-only HEAD | while read -r f; do
      [ -f "$f" ] && [ "$(stat -c %s "$f" 2>/dev/null || echo 0)" -gt $((MAXMB * 1024 * 1024)) ] && echo "$f"; done)
    [ -n "$big" ] && { echo "$big" | while read -r f; do git rm -q --cached -- "$f"; done; log "$d: left out (> ${MAXMB} MB): $(echo $big)"; }
    tree=$(git write-tree)
    unset GIT_INDEX_FILE; rm -f "$tmp"
    commit=$(git commit-tree "$tree" -p HEAD -m "WIP snapshot at shutdown ($HOST, $STAMP) – not reviewed, not tested

Saved by tools/dev/save-on-shutdown.sh: $dirty changed file(s) on top of $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD)).
The next session carries this on; the owner deletes the branch.") || { log "$d: snapshot failed"; return 0; }
  fi
  # the same state as at the last shutdown (same HEAD, same files) → already saved, no second branch
  key=$(printf '%s' "$d" | sha1sum | cut -c1-12)
  if [ "$(cat "$STATE/last-$key" 2>/dev/null)" = "$(git rev-parse HEAD) $(git rev-parse "$commit^{tree}")" ]; then
    log "$d: unchanged since the last snapshot – nothing new to save"; return 0
  fi
  git update-ref "refs/onpath-wip/${branch#wip/}" "$commit"
  if timeout "$TIMEOUT" git push -q origin "$commit:refs/heads/$branch" 2>>"$LOG"; then
    git update-ref -d "refs/onpath-wip/${branch#wip/}"
    echo "$(git rev-parse HEAD) $(git rev-parse "$commit^{tree}")" > "$STATE/last-$key"
    log "saved $d → $branch (${commit:0:8}; $dirty file(s), $ahead commit(s))"
  else
    log "$d: push failed – kept as refs/onpath-wip/${branch#wip/}, pushed at the next start"
  fi
}

retry_one() { # dir: push snapshots that were kept locally
  local d=$1 ref
  cd "$d" 2>/dev/null || return 0
  git for-each-ref --format='%(refname)' refs/onpath-wip/ 2>/dev/null | while read -r ref; do
    if timeout "$TIMEOUT" git push -q origin "$ref:refs/heads/wip/${ref#refs/onpath-wip/}" 2>>"$LOG"; then
      git update-ref -d "$ref"; log "pushed later: $d → wip/${ref#refs/onpath-wip/}"
    else log "$d: still cannot push wip/${ref#refs/onpath-wip/}"; fi
  done
}

case "$MODE" in
  save|dry) checkouts | while read -r d; do save_one "$d"; done ;;
  retry) checkouts | while read -r d; do retry_one "$d"; done ;;
  *) echo "usage: $0 save|retry|dry" >&2; exit 2 ;;
esac
exit 0
