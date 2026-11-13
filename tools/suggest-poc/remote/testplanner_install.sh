#!/usr/bin/env bash
# Tour suggestions – install (or update) the TEST PLANNER on this computer (test_planner.py): a copy of a built planner
# with the tour suggestions, served together with the suggestion service on one address. Needs install.sh first.
#
#   testplanner_install.sh <path to a built web/app/dist>                 listens on 127.0.0.1:8785
#   testplanner_install.sh <dist> --bind 192.168.1.35                     the owner's step: reachable in the home network / VPN
#
# The build comes from the planner branch (wip/donald-2026-11-13-planner-suggest):  cd web/app && npm run build
# Nothing needs sudo. Remove: uninstall.sh (removes both units).
set -euo pipefail
DIST=${1:?usage: testplanner_install.sh <web/app/dist> [--bind address]}; shift
BIND=127.0.0.1
while [ $# -gt 0 ]; do case "$1" in --bind) BIND=$2; shift 2;; *) echo "unknown option $1" >&2; exit 2;; esac; done
PORT=${PORT:-8785}; SERVICE_PORT=${SERVICE_PORT:-8780}
DATA=${SUGGEST_DATA:-$HOME/onpath-suggest-poc}
SRC=$DATA/remote/src/tools/suggest-poc/remote
TP=$DATA/remote/testplanner
UNIT_DIR=$HOME/.config/systemd/user
UNIT=onpath-suggest-testplanner.service
[ -f "$SRC/test_planner.py" ] || { echo "run install.sh first (the service's copy is missing)" >&2; exit 1; }
[ -f "$DIST/index.html" ] || { echo "$DIST has no index.html – build the planner first" >&2; exit 1; }
mkdir -p "$TP" "$UNIT_DIR"
if [ "$(cd "$DIST" && pwd)" != "$(mkdir -p "$TP/dist" && cd "$TP/dist" && pwd)" ]; then rsync -a --delete "$DIST/" "$TP/dist/"; fi

cat > "$UNIT_DIR/$UNIT" <<EOF
[Unit]
Description=OnPath test planner with tour suggestions, served by this computer (tools/suggest-poc/remote)
After=network-online.target onpath-suggest-remote.service

[Service]
Type=simple
Environment=SUGGEST_DATA=$DATA
Environment=PYTHONUNBUFFERED=1
ExecStart=$(command -v python3) $SRC/test_planner.py --bind $BIND --port $PORT --service-port $SERVICE_PORT --dist $TP/dist
Restart=always
RestartSec=3
Nice=10

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable "$UNIT" >/dev/null 2>&1 || true
systemctl --user restart "$UNIT"
sleep 1
systemctl --user --no-pager --lines=0 status "$UNIT" | sed -n 1,3p
echo "Test-Planer: http://$BIND:$PORT/   Planer-Stand: $(cat "$TP/dist/version.json" 2>/dev/null)"
