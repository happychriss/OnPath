#!/usr/bin/env bash
# Delete old branches on GitHub – only ones whose work is already in main or web-spike, only after the owner said yes
# (owner 2026-11-06: "mit einer Bestätigung von mir, aber nicht jeden Branch einzeln"). The bare git delete commands stay
# denied in .claude/settings.json; this script is the one way.
#   tools/dev/prune_branches.sh list                 candidates (safe to delete) and the branches kept, with the reason
#   tools/dev/prune_branches.sh delete <branch>…     re-checks each, logs it, deletes it – ONLY after the owner's yes to
#                                                    exactly this list (question box in the chat)
#   tools/dev/prune_branches.sh delete --by-hand "<why>" <branch>…   the same for branches whose work a session checked
#                                                    by hand (finished differently on main, so git cannot see it): skips
#                                                    only the "work is in main" check; the owner's box must name it so
#   tools/dev/prune_branches.sh restore <branch>     brings a deleted branch back from the log
# A branch is a candidate when: not main / web-spike / HEAD; no open pull request; last commit older than 12 h (a
# session may still be on it); and every commit of it is in main or in web-spike (ancestor, or the same patch – git cherry).
# Every deletion goes to docs/history/deleted-branches.md (date, branch, last commit) – commit and push that file after.
# Exit: 0 ok, 2 usage, 3 refused (a branch is not safe – nothing of it is deleted).
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 2
LOG=docs/history/deleted-branches.md
MIN_AGE_H=${MIN_AGE_H:-12}
PROTECTED=" main web-spike HEAD "
usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; }

# why_kept <branch> → prints the reason it must stay, nothing when it is safe to delete
why_kept() {
  local b=$1 ref="origin/$1" age open by_hand=${2:-}
  [[ $PROTECTED == *" $b "* ]] && { echo "protected"; return; }
  git rev-parse -q --verify "$ref^{commit}" >/dev/null || { echo "not on origin"; return; }
  age=$(( ( $(date +%s) - $(git log -1 --format=%ct "$ref") ) / 3600 ))
  (( age < MIN_AGE_H )) && { echo "last commit ${age} h ago (< ${MIN_AGE_H} h – a session may be on it)"; return; }
  open=$(gh api -X GET search/issues -f q="repo:happychriss/OnPath is:pr is:open head:$b" --jq '.total_count' 2>/dev/null) \
    || { echo "could not check for an open pull request"; return; }
  [[ $open != 0 ]] && { echo "open pull request"; return; }
  [[ -n $by_hand ]] && return
  local base
  for base in main web-spike; do
    git merge-base --is-ancestor "$ref" "origin/$base" && return
    [[ -z $(git cherry "origin/$base" "$ref" | grep '^+') ]] && return
  done
  local n; n=$(git cherry origin/main "$ref" | grep -c '^+')
  echo "$n commit(s) not in main or web-spike"
}

case ${1:-} in
  list)
    git fetch -q --prune origin || { echo "fetch failed"; exit 1; }
    echo "== can be deleted (work is in main / web-spike):"
    keep=()
    while read -r b; do
      r=$(why_kept "$b")
      if [[ -z $r ]]; then echo "  $b  ($(git log -1 --format='%cs %h %s' "origin/$b" | cut -c1-80))"; else keep+=("  $b – $r"); fi
    done < <(git for-each-ref refs/remotes/origin --format='%(refname:lstrip=3)' | grep -v '^HEAD$')
    echo "== kept:"; printf '%s\n' "${keep[@]}" ;;
  delete)
    shift; why=""
    if [[ ${1:-} == --by-hand ]]; then why=${2:-}; shift 2 || true; [[ -n $why ]] || { usage; exit 2; }; fi
    (( $# )) || { usage; exit 2; }
    git fetch -q --prune origin || { echo "fetch failed"; exit 1; }
    for b in "$@"; do r=$(why_kept "$b" "$why"); [[ -n $r ]] && { echo "refused: $b – $r (nothing deleted)"; exit 3; }; done
    [[ -f $LOG ]] || printf '# Deleted branches\n\nWritten by `tools/dev/prune_branches.sh` (owner said yes in the chat). Bring one back:\n`tools/dev/prune_branches.sh restore <branch>`.\n\n| Date | Branch | Last commit | Work is in |\n| --- | --- | --- | --- |\n' > "$LOG"
    for b in "$@"; do
      sha=$(git rev-parse "origin/$b")
      if [[ -n $why ]]; then in="checked by hand: ${why//|/\\|}"
      elif git merge-base --is-ancestor "$sha" origin/main || [[ -z $(git cherry origin/main "$sha" | grep '^+') ]]; then in=main
      else in=web-spike; fi
      echo "| $(date +%F) | \`$b\` | \`$sha\` | $in |" >> "$LOG"
      git push -q origin --delete "$b" && echo "deleted $b ($sha)" || { echo "delete failed: $b"; exit 1; }
    done
    echo "now: commit + push $LOG" ;;
  restore)
    b=${2:-}; [[ -n $b ]] || { usage; exit 2; }
    sha=$(grep -F "| \`$b\` |" "$LOG" 2>/dev/null | tail -1 | awk -F'`' '{print $4}')
    [[ -n $sha ]] || { echo "$b is not in $LOG"; exit 3; }
    git cat-file -e "$sha^{commit}" 2>/dev/null || git fetch -q origin "$sha" 2>/dev/null \
      || { echo "commit $sha is not in this clone – try another computer's clone"; exit 1; }
    git push -q origin "$sha:refs/heads/$b" && echo "restored $b at $sha" ;;
  *) usage; exit 2 ;;
esac
