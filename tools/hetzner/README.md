# OnPath map server on Hetzner

> **Superseded (2026-10-20).** Map hosting is now Cloudflare (Worker + R2) + Google Cloud Run Jobs, built per cell –
> see `docs/design/map-hosting.md` and `tools/cloud/`. This region server is kept only for reference and for the
> Geofabrik source choice (`server/onpath_maps/sources.py`), which the cloud builder may reuse.

A small server of its own that builds OnPath map packages **on demand** for the area you plan to hike in
(a 100, 150 or 200 km square around a place), keeps them for download and refreshes the ones you use. You
open a web page on the phone, search "Zoggler Stausee" (or tap "My position"), pick the size, and 15–60 minutes
later a download link is there; import the zip in OnPath (Settings → Map data → Import map data).

It does **not** build the world: every region is built only when someone asks for it. A ready region that already
covers the area is reused at once.

## Set up (10 minutes of your time, then it runs by itself)

1. **Hetzner Console → New project "OnPath"** (recommended: the token then cannot see your other servers at all).
2. In that project: **Security → API tokens → Generate API token**, permission **Read & Write**. Copy it.
3. On your laptop (Linux / macOS, Python 3, an SSH key in `~/.ssh/`):

   ```
   cd OnPath && git pull
   python3 tools/hetzner/onpath_hetzner.py plan     # read-only: what exists, what would be created, price
   python3 tools/hetzner/onpath_hetzner.py apply    # asks "Type yes", creates, waits, prints URL + password
   ```

   The token is asked for (hidden) or taken from `HCLOUD_TOKEN`; it is never written to disk.
4. Open the printed `https://<ip>.sslip.io` on the phone, log in (`onpath` + the printed password), save it as a
   bookmark or "Add to home screen".

Later: `… status` (address, password, services), `… destroy` (removes only what `apply` created).

## What the setup script does – and what it never does

- Reads (GET) the project's servers, firewalls, SSH keys, volumes, load balancers and **shows** them as "will not
  be touched".
- Creates exactly: server `onpath-maps` (cheapest type with ≥ 8 GB RAM and ≥ 80 GB disk that is orderable, Ubuntu
  24.04, nbg1 → fsn1 → hel1), firewall `onpath-maps` (in: 22, 80, 443; attached only to that server), and SSH key
  `onpath-maps-key` only if your key is not in the project yet (an existing key is reused, not changed). All three
  carry the label `app=onpath-maps`.
- Stops if something named `onpath-maps…` exists that it did not create.
- A write request without the explicit "apply" path is refused in code (`ONPATH_HETZNER_ALLOW_WRITE`);
  `destroy` deletes only labelled resources after you type the server name.
- Nothing is installed on your existing servers. The new server installs itself (cloud-init → `server/install.sh`).

Cost: one small cloud server (shown by `plan` with the current Hetzner price, typically 8–20 € / month incl. VAT
after the 2026 price change) plus the IPv4 address. Traffic for downloads is included (20 TB).

## On the server

| Part | What |
| --- | --- |
| Caddy | HTTPS with a free certificate for `<ip>.sslip.io` (or `--domain your.domain`); password for the page and API |
| `onpath-api` | JSON API on 127.0.0.1:8090 (`server/onpath_maps/api.py`) |
| `onpath-worker` | builds one region at a time (`server/onpath_maps/worker.py`) |
| `onpath-refresh.timer` | weekly: rebuilds regions older than 25 days that were asked for in the last 6 months; deletes the least used ones when the disk gets full (< 15 GB free) |
| `/srv/onpath` | `state/` (one JSON per region), `work/` (Geofabrik + Planetiler caches), `logs/`, `public/dl/` (zips) |

A build: Geofabrik extracts that cover the square (country / province level, cached for 6 days, md5-checked, all of
the same day) → `osmium extract` + `osmium merge` → `tools/build_region.sh --no-segments` → zip under
`/dl/<region>-<random>/onpath-data-<region>.zip`. Download links are public (the random part makes them
unguessable), so they work in any browser and later directly from the app; the page and the build API need the
password. Routing tiles are not in the zip: the app downloads them from brouter.de (Settings → Map data).

The worker runs `git pull` on its copy of this repo before each build, so pipeline and style fixes pushed to `main`
are used automatically.

Sizes and times (measured: 30 km around Zoggler Stausee = 31 MB zip, 2 min; scaled): 100 km ≈ 250–350 MB, 10–25 min; 200 km ≈ 1–1.3 GB, 30–60 min, plus the first Geofabrik downloads
(the relief `terrain.pmtiles` is most of it).

## Maintenance

- `ssh root@<ip>` then `journalctl -u onpath-worker -f` (builds), `tail -f /srv/onpath/logs/<region>.log`.
- Update the server software after big pipeline changes: `bash /opt/onpath/repo/tools/hetzner/server/install.sh`
  (safe to repeat; keeps password, host name and regions).
- Ubuntu security updates install automatically (unattended-upgrades).

## Tests

`cd tools/hetzner && python3 -m unittest -v test_hetzner` – region grid and reuse, Geofabrik source choice, the
setup script's no-write guard, name-conflict stop and server-type choice (offline, no Hetzner account needed).
