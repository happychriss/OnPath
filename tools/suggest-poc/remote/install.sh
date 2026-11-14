#!/usr/bin/env bash
# Tour suggestions – install (or update) the service on this computer so it stays up without a terminal.
#
#   tools/suggest-poc/remote/install.sh            listens on 127.0.0.1:8780 (this computer only)
#   tools/suggest-poc/remote/install.sh --bind 192.168.1.35     the owner's step: reachable in the home network / VPN
#   PORT=8780 SERVICE_PORT=8781 … install.sh       other ports
#
# What it does – nothing needs sudo:
# 1. copies the POC (tools/suggest-poc), the BRouter jars and the profiles of THIS checkout to
#    ~/onpath-suggest-poc/remote/src/ – the service runs from that copy, so git worktrees can come and go.
#    Run it again from a newer checkout to update (it restarts the service).
# 2. writes the systemd user unit ~/.config/systemd/user/onpath-suggest-remote.service and starts it
#    (restarts on failure, lowest priority).
# A user unit runs while the user is logged in on this computer (the desktop session counts). To keep it running
# without a login: `loginctl enable-linger $USER` (may ask for the password).  Remove everything: uninstall.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
BIND=127.0.0.1
while [ $# -gt 0 ]; do case "$1" in --bind) BIND=$2; shift 2;; *) echo "unknown option $1" >&2; exit 2;; esac; done
PORT=${PORT:-8780}; SERVICE_PORT=${SERVICE_PORT:-8781}
DATA=${SUGGEST_DATA:-$HOME/onpath-suggest-poc}
SRC=$DATA/remote/src
UNIT_DIR=$HOME/.config/systemd/user
UNIT=onpath-suggest-remote.service

mkdir -p "$SRC/tools" "$SRC/app/libs" "$SRC/app/src/main/assets" "$UNIT_DIR"
chmod 700 "$DATA/remote"
rsync -a --delete --exclude '__pycache__' "$ROOT/tools/suggest-poc/" "$SRC/tools/suggest-poc/"
rsync -a --delete --include 'brouter-*.jar' --exclude '*' "$ROOT/app/libs/" "$SRC/app/libs/"
rsync -a --delete "$ROOT/app/src/main/assets/brouter/" "$SRC/app/src/main/assets/brouter/"
(cd "$ROOT" && { git rev-parse --short HEAD 2>/dev/null || echo unknown; }) > "$SRC/VERSION"

cat > "$UNIT_DIR/$UNIT" <<EOF
[Unit]
Description=OnPath tour suggestions – service for testing on the road (tools/suggest-poc/remote)
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$SRC/tools/suggest-poc/remote
Environment=SUGGEST_DATA=$DATA
Environment=PYTHONUNBUFFERED=1
Environment=PATH=$PATH
ExecStart=$(command -v python3) $SRC/tools/suggest-poc/remote/remote_server.py --bind $BIND --port $PORT --service-port $SERVICE_PORT
Restart=always
RestartSec=3
Nice=19
KillMode=control-group
TimeoutStopSec=10

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable "$UNIT" >/dev/null 2>&1 || true
systemctl --user restart "$UNIT"
sleep 2
systemctl --user --no-pager --lines=0 status "$UNIT" | sed -n 1,4p
echo "Dienst: http://$BIND:$PORT/v1/   Stand: $(cat "$SRC/VERSION")   Passwort: $DATA/remote/token   Protokoll: $DATA/remote/remote.log"
