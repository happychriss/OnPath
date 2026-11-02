#!/usr/bin/env bash
# NOT ACTIVE YET – part of the setup switch after v0.14.0 (docs/roles/neu/umstellung.md). Nothing calls this until the
# owner runs `tools/dev/unit.sh install <name>` once on a computer.
#
# A unit = one computer + one checkout, reached from the browser / phone via Remote Control. Tested on donald
# 2026-11-01 (docs/roles/neu/umstellung.md "Ergebnisse"):
#   - a plain `claude remote-control` is a HOST for up to 32 sessions; restarting it reconnects every session of the
#     folder ("Environment preserved") – no --session-id needed
#   - spawn mode "worktree" (press w once in the folder, saved per folder): every new session gets its own git worktree
#   - a session on this computer may start sessions here (create_session into this environment) – the cloud may not
#   - /loop dies with a restart; a send_later / scheduled task the session set for itself survives and wakes it
# So the standing session of a unit ("<name>-leitstand") is the dispatcher: an hourly wake-up it set for itself, the
# round in docs/roles/neu/start.md, one worker session per item (own worktree).
#
#   run <name>        start the host in this checkout (foreground; keys like w work; a log copy in
#                     ~/.local/state/onpath-unit/<name>.log). Exit 3 = folder not trusted yet.
#   install <name>    write + enable two systemd user services:
#                       onpath-unit-<name>.service – `unit.sh run <name>` at login, restarted if it ends
#                       onpath-save.service        – tools/dev/save-on-shutdown.sh: save at shutdown, retry at start
#   uninstall <name>  stop + remove both services        status <name>   service state
# Once per folder, by hand: `claude` (accept the trust dialog, /exit), then `claude remote-control`, press w until it
# says "worktree", Ctrl-C. Env: ONPATH_PERMISSION_MODE (→ --permission-mode for spawned sessions; unset = CLI default),
# ROOT for save-on-shutdown (default ~/Projects/work).
set -uo pipefail
cmd=${1:-}; NAME=${2:-}
DIR=$(cd "$(dirname "$0")/../.." && pwd)
STATE=${XDG_STATE_HOME:-$HOME/.local/state}/onpath-unit
UNITS=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
mkdir -p "$STATE"
die() { echo "unit: $*" >&2; exit 2; }
need_name() { [ -n "$NAME" ] || die "$cmd needs a name, e.g. donald or laptop"; case "$NAME" in *[!a-z0-9-]*) die "name: a-z 0-9 - only" ;; esac; }

run() {
  need_name; cd "$DIR" || exit 2
  command -v claude >/dev/null || die "claude CLI not found"
  local log=$STATE/$NAME.log mode=() rc
  [ -n "${ONPATH_PERMISSION_MODE:-}" ] && mode=(--permission-mode "$ONPATH_PERMISSION_MODE")
  echo "$(date '+%F %T') host start in $DIR" >> "$log"
  # `script` gives claude a real terminal (a pipe made it ignore keys on donald) and keeps a copy of the output
  script -qefa -c "claude remote-control --remote-control-session-name-prefix $NAME ${mode[*]}" "$log"; rc=$?
  # a new folder must be trusted once by hand – an autostart cannot answer the dialog (seen on donald 2026-11-01)
  if tail -n 40 "$log" | grep -q "Workspace not trusted"; then
    echo "unit: this folder is not trusted yet – run \`claude\` in $DIR once, accept, /exit, then start again" | tee -a "$log"
    exit 3
  fi
  exit $rc
}

install() {
  need_name; mkdir -p "$UNITS"
  command -v systemctl >/dev/null || die "systemctl not found (systemd user services needed)"
  cat > "$UNITS/onpath-unit-$NAME.service" <<EOF
[Unit]
Description=OnPath unit $NAME – Remote Control host (sessions reconnect after a restart) in $DIR
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$DIR
Environment=ONPATH_PERMISSION_MODE=${ONPATH_PERMISSION_MODE:-}
# a pseudo terminal, in case the CLI wants one
ExecStart=$DIR/tools/dev/unit.sh run $NAME
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
  status) need_name; systemctl --user --no-pager status "onpath-unit-$NAME.service" onpath-save.service 2>&1 | grep -E "●|Active:" ;;
  -h|--help|"") sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//' ;;
  *) die "unknown command $cmd (run | install | uninstall | status)" ;;
esac
