#!/usr/bin/env bash
# NOT ACTIVE YET – part of the setup switch after v0.14.0 (docs/roles/setup-vorschlag-2026-11-01.md §4g, §6c).
# Nothing calls this until the owner runs `tools/dev/unit.sh install <name>` once on a computer.
# NOT TESTED on donald / the laptop yet: the three checks in docs/roles/neu/umstellung.md come first.
#
# One standing Claude session per computer and project (a "unit"), reached from the browser / phone via Remote Control,
# that survives a reboot: the same session (chat, knowledge) is re-attached instead of a new throw-away one.
#   run <name>        start Remote Control in this checkout: re-attach the saved session (--session-id), else start a
#                     new one named <name> and save its id (state: ~/.local/state/onpath-unit/<name>.id)
#   install <name>    write + enable two systemd user services:
#                       onpath-unit-<name>.service – `unit.sh run <name>` at login, restarted if it ends
#                       onpath-save.service        – tools/dev/save-on-shutdown.sh: save at shutdown, retry at start
#   uninstall <name>  stop + remove both services        status <name>   service state + saved session id
#   new <name>        forget the saved session (the next start makes a fresh one – e.g. when the chat got too long)
# Env: ONPATH_PERMISSION_MODE (passed to `claude remote-control --permission-mode`; unset = the CLI default – the owner
# decides at the switch, see umstellung.md), ROOT for save-on-shutdown (default ~/Projects/work).
set -uo pipefail
cmd=${1:-}; NAME=${2:-}
DIR=$(cd "$(dirname "$0")/../.." && pwd)
STATE=${XDG_STATE_HOME:-$HOME/.local/state}/onpath-unit
UNITS=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
mkdir -p "$STATE"
die() { echo "unit: $*" >&2; exit 2; }
need_name() { [ -n "$NAME" ] || die "$cmd needs a name, e.g. donald or hp"; case "$NAME" in *[!a-z0-9-]*) die "name: a-z 0-9 - only" ;; esac; }

run() {
  need_name; cd "$DIR" || exit 2
  command -v claude >/dev/null || die "claude CLI not found"
  local idf=$STATE/$NAME.id id log=$STATE/$NAME.log mode=()
  [ -n "${ONPATH_PERMISSION_MODE:-}" ] && mode=(--permission-mode "$ONPATH_PERMISSION_MODE")
  id=$(cat "$idf" 2>/dev/null || true)
  # a new folder must be trusted once by hand (`claude` there, accept the dialog) – an autostart cannot answer it
  # (seen on donald 2026-11-01: "Error: Workspace not trusted"); exit 3 so systemd does not retry for ever
  if [ -n "$id" ]; then
    echo "$(date '+%F %T') re-attach $id" >> "$log"
    local t0=$SECONDS
    claude remote-control --session-id "$id" 2>&1 | tee -a "$log"
    # ended within a minute = the session is gone (archived / unknown) → forget it and start fresh below
    [ $((SECONDS - t0)) -lt 60 ] || exit 0
    echo "$(date '+%F %T') re-attach ended at once – starting a new session" >> "$log"; rm -f "$idf"
  fi
  echo "$(date '+%F %T') new session $NAME" >> "$log"
  # the session id is read from the CLI's output (its claude.ai/code link) and saved for the next start
  claude remote-control --name "$NAME" "${mode[@]}" 2>&1 | while IFS= read -r line; do
    printf '%s\n' "$line" | tee -a "$log"
    if [[ $line == *"Workspace not trusted"* ]]; then
      echo "unit: this folder is not trusted yet – run \`claude\` in $DIR once, accept, /exit, then start again" | tee -a "$log"
      exit 3
    fi
    if [ ! -s "$idf" ] && [[ $line =~ (session_[A-Za-z0-9]+) ]]; then echo "${BASH_REMATCH[1]}" > "$idf"; fi
  done
}

install() {
  need_name; mkdir -p "$UNITS"
  command -v systemctl >/dev/null || die "systemctl not found (systemd user services needed)"
  cat > "$UNITS/onpath-unit-$NAME.service" <<EOF
[Unit]
Description=OnPath unit $NAME – standing Claude session (Remote Control) in $DIR
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$DIR
Environment=ONPATH_PERMISSION_MODE=${ONPATH_PERMISSION_MODE:-}
# a pseudo terminal, in case the CLI wants one
ExecStart=/usr/bin/script -qefc "$DIR/tools/dev/unit.sh run $NAME" /dev/null
Restart=on-failure
RestartSec=30
# exit 3 = folder not trusted yet (needs the owner once) – do not retry
RestartPreventExitStatus=3

[Install]
WantedBy=default.target
EOF
  cat > "$UNITS/onpath-save.service" <<EOF
[Unit]
Description=OnPath – push unsaved work at shutdown (wip/<host>-<date>-<folder>), retry at start
After=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=ROOT=${ROOT:-$HOME/Projects/work}
ExecStart=$DIR/tools/dev/save-on-shutdown.sh retry
ExecStop=$DIR/tools/dev/save-on-shutdown.sh save
TimeoutStopSec=90

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable --now onpath-save.service "onpath-unit-$NAME.service"
  echo "unit: installed – onpath-unit-$NAME + onpath-save (logs: $STATE/$NAME.log, ${XDG_STATE_HOME:-$HOME/.local/state}/onpath-save/log)"
  echo "unit: so the services also run without a desktop login: loginctl enable-linger $(id -un) (optional)"
}

case "$cmd" in
  run) run ;;
  install) install ;;
  uninstall) need_name; systemctl --user disable --now "onpath-unit-$NAME.service" onpath-save.service 2>/dev/null
    rm -f "$UNITS/onpath-unit-$NAME.service" "$UNITS/onpath-save.service"; systemctl --user daemon-reload; echo "unit: removed" ;;
  status) need_name; systemctl --user --no-pager status "onpath-unit-$NAME.service" onpath-save.service 2>&1 | grep -E "●|Active:"
    echo "session id: $(cat "$STATE/$NAME.id" 2>/dev/null || echo –)" ;;
  new) need_name; rm -f "$STATE/$NAME.id"; echo "unit: next start makes a new session (restart: systemctl --user restart onpath-unit-$NAME)" ;;
  -h|--help|"") sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//' ;;
  *) die "unknown command $cmd (run | install | uninstall | status | new)" ;;
esac
