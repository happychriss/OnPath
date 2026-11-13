# Tour suggestions – testing on the road (the service on the computer at home)

Owner 2026-11-14: he wants to try the suggestions in the planner on his phone while travelling; donald stays on at home
and computes; he reaches it through a VPN he sets up himself. Design: `docs/design/tour-suggestions.md` ("Test on the
road", on the planner branch `wip/donald-2026-11-13-planner-suggest`). The owner's sheet (German):
`~/onpath-suggest-poc/remote/MORGEN.md`.

**Nothing here opens anything to the outside.** Every service binds 127.0.0.1 unless started with `--bind`; no tunnel,
no port forwarding – the VPN is the owner's own step.

| File | What |
| --- | --- |
| `remote_server.py` | The locked front for the POC: `GET /v1/ping`, `POST /v1/suggest`. Password (`X-OnPath-Token`, from `~/onpath-suggest-poc/remote/token`, mode 600) on every request; CORS only for `https://plan.onpath.earth` and localhost; one computation at a time (queue 3), 90 s time limit (the worker process is replaced on overrun), 16 kB body limit, 30 requests / 5 min per address; missing map areas are fetched in the background → `{"preparing": true}`; no routing data for the region → a plain message. Log `remote.log` with the start rounded to ~1 km; the runs are kept in `remote/runs/` (`--no-save` to turn off). Method: `remote/method` (one word) → weights.json `default_method` → `auto` if the POC knows it → `b`. |
| `install.sh` / `uninstall.sh` | Copies this checkout's POC + BRouter jars + profiles to `~/onpath-suggest-poc/remote/src/` and runs the service from there as the systemd **user** unit `onpath-suggest-remote.service` (port 8780, its own route service 8781; restart on failure, nice 19). Run again from a newer checkout to update. `--bind <address>` for the owner's step. |
| `selftest.py` | Against the running service: password, CORS, limits, queue, a real answer (21 checks). |
| `test_planner.py`, `testplanner_install.sh` | The **test planner** served by this computer (port 8785, unit `onpath-suggest-testplanner.service`): a copy of the planner branch's build + `/suggest-remote/` → the service + routing data from local files + map files passed on to the live host. For a plain VPN (http only) and for a quick look at home. |
| `setup_link.sh` | Prints the set-up link for a phone (`…/?debug#suggest-test=<address>|<password>`) on the owner's own screen. |
| `fetch_rd5.sh` | Fetches routing-data tiles this computer lacks (resumable). |

A user unit runs while the user is logged in; `loginctl enable-linger <user>` keeps it running without a login.
