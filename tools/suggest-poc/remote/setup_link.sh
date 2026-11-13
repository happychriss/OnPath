#!/usr/bin/env bash
# Tour suggestions – print the set-up link for a phone: opening it fills the planner's debug setting "Tourvorschläge
# (Test)" with the computer's address and the password (nothing is stored before OK there).
#
#   setup_link.sh <planner address> <address of this computer's service as the phone reaches it>
#   setup_link.sh https://plan.onpath.earth/ https://donald.<name>.ts.net/        the live planner, VPN with an https name
#   setup_link.sh http://192.168.1.35:8785/ /suggest-remote/                      the test planner on this computer
#
# THE LINK CONTAINS THE PASSWORD: it is printed here only, on your own screen – send it to your phone yourself
# (or type address + password into the setting by hand). The part after # never goes to a server.
set -euo pipefail
PLANNER=${1:?usage: setup_link.sh <planner address> <service address>}; SERVICE=${2:?service address missing}
TOKEN=$(cat "${SUGGEST_DATA:-$HOME/onpath-suggest-poc}/remote/token")
ENC=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1] + "|" + sys.argv[2], safe=""))' "$SERVICE" "$TOKEN")
LINK="${PLANNER%/}/?debug#suggest-test=$ENC"
echo "$LINK"
if command -v qrencode >/dev/null 2>&1; then qrencode -t ANSIUTF8 "$LINK"; fi
