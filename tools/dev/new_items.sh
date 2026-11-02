#!/usr/bin/env bash
# The unit's 10-minute check (owner 2026-11-02): is there something new and urgent for this computer?
# Prints nothing when there is nothing to do, so a loop tick that finds nothing stays cheap.
#   tools/dev/new_items.sh <unit>        e.g. donald, laptop
# Looks at docs/backlog.md "Next" on origin/main (fetch only – the working tree is not touched): lines tagged
# [sofort] that are new since the last check and are for this unit (tag [<unit>], or no unit tag at all), and not
# claimed under "In progress". Also prints open an-alle issues once when they are new. State: .git/onpath-new-items-<unit>.
set -uo pipefail
UNIT=${1:?usage: new_items.sh <unit>}
cd "$(git rev-parse --show-toplevel)" || exit 0
git fetch -q origin main 2>/dev/null || exit 0
STATE=$(git rev-parse --git-path "onpath-new-items-$UNIT")
backlog=$(git show origin/main:docs/backlog.md 2>/dev/null) || exit 0

# the [sofort] items of "Next" as one line each (first line of the bullet is enough to identify it)
# for this unit: its own tag, or no unit tag at all – and a device tag only where the device is ([pixel] / [emu] → donald)
mine() {
  local l=$1 u
  for u in donald laptop cloud-plan cloud-web captain; do
    [[ $l == *"[$u]"* ]] && { [ "$u" = "$UNIT" ] && return 0 || return 1; }
  done
  [[ $l == *"[pixel]"* || $l == *"[emu]"* ]] && [ "$UNIT" != donald ] && return 1
  return 0
}
items=$(awk '/^## Next/{f=1;next} /^## /{f=0} f && /^- /' <<<"$backlog" | grep -F '[sofort]' | cut -c1-200 |
  while IFS= read -r l; do mine "$l" && printf '%s\n' "$l"; done)
claimed=$(awk '/^## In progress/{f=1;next} /^## /{f=0} f && /^- /' <<<"$backlog")
anall=""
[ -x tools/ask.sh ] && anall=$(tools/ask.sh an-alle 2>/dev/null | grep '^an-alle #' || true)

new=""
while IFS= read -r line; do
  [ -n "$line" ] || continue
  key=$(printf '%s' "$line" | sha1sum | cut -c1-12)
  grep -qx "$key" "$STATE" 2>/dev/null && continue
  # claimed already? (the first 40 characters of the item text after the tags)
  text=$(printf '%s' "$line" | sed 's/^- //; s/\*\*//g; s/\[[^]]*\]//g; s/^ *//; s/ – .*//; s/ *$//' | cut -c1-40)
  [ -n "$text" ] && grep -qF "$text" <<<"$claimed" && { echo "$key" >> "$STATE"; continue; }
  new+="$line"$'\n'
  echo "$key" >> "$STATE"
done <<<"$items"
while IFS= read -r line; do
  [ -n "$line" ] || continue
  key=a$(printf '%s' "$line" | sha1sum | cut -c1-11)
  grep -qx "$key" "$STATE" 2>/dev/null && continue
  new+="$line"$'\n'; echo "$key" >> "$STATE"
done <<<"$anall"

[ -n "$new" ] && { echo "NEU für $UNIT:"; printf '%s' "$new"; }
exit 0
