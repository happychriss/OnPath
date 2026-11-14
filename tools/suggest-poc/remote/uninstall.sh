#!/usr/bin/env bash
# Tour suggestions – remove the service from this computer: stops and deletes the systemd user unit(s) and the
# service's copy of the code. The password, the log, the kept runs and the map areas stay (delete
# ~/onpath-suggest-poc/remote/ by hand to remove those too).
set -uo pipefail
DATA=${SUGGEST_DATA:-$HOME/onpath-suggest-poc}
for UNIT in onpath-suggest-remote.service onpath-suggest-testplanner.service; do
  systemctl --user disable --now "$UNIT" >/dev/null 2>&1 || true
  rm -f "$HOME/.config/systemd/user/$UNIT"
done
systemctl --user daemon-reload
rm -rf "$DATA/remote/src"
echo "Dienst entfernt. Geblieben: $DATA/remote/ (Passwort, Protokoll, Läufe)."
