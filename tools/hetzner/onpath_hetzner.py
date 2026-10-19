#!/usr/bin/env python3
"""Creates the OnPath map server on Hetzner Cloud – and nothing else. Python 3.8+, no extra packages.

  python3 tools/hetzner/onpath_hetzner.py plan            # read-only: shows what exists and what would be created
  python3 tools/hetzner/onpath_hetzner.py apply           # creates server + firewall (+ SSH key), asks first
  python3 tools/hetzner/onpath_hetzner.py status          # server address, build progress, login
  python3 tools/hetzner/onpath_hetzner.py destroy         # deletes ONLY what apply created (asks for the name)

Token: Hetzner Console → your project → Security → API tokens → "Read & Write". Pass it as HCLOUD_TOKEN or type it
when asked (never stored). Best: create a new, empty project "OnPath" for it – then this token cannot even see
your other servers.

Safety rules of this script (read the code – it is short):
- It only ever sends GET requests for things it did not create.
- It creates exactly three things, all named "onpath-maps…" and labelled app=onpath-maps: one server, one firewall,
  and an SSH key only if your key is not in the project yet (an existing key is reused, never changed).
- If anything named like that already exists without its label, it stops.
- destroy deletes only resources carrying the label app=onpath-maps, after you type the server name.

Options: --location nbg1|fsn1|hel1|… (default nbg1, falls back to fsn1, hel1 when sold out), --type <name> (default:
cheapest with ≥ 8 GB RAM and ≥ 80 GB disk), --ssh-key ~/.ssh/id_ed25519.pub, --domain maps.example.org (optional;
default <ip>.sslip.io), --yes (no question).
"""
import argparse
import base64
import getpass
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.hetzner.cloud/v1"
NAME = "onpath-maps"
LABEL = {"app": "onpath-maps"}
LABEL_SEL = "app=onpath-maps"
REPO_URL = "https://github.com/happychriss/OnPath"
MIN_MEM_GB, MIN_DISK_GB = 8, 80
LOCATIONS_FALLBACK = ["nbg1", "fsn1", "hel1"]


class Api:
    def __init__(self, token):
        self.token = token
        self.writes = []  # every create / delete this run, for the summary

    def call(self, method, path, body=None):
        if method != "GET" and not os.environ.get("ONPATH_HETZNER_ALLOW_WRITE"):
            raise SystemExit("internal: write without permission")
        req = urllib.request.Request(API + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + self.token,
                                              "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
                if method != "GET":
                    self.writes.append(f"{method} {path}")
                return json.loads(data) if data else {}
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")
            try:
                msg = json.loads(msg)["error"]["message"]
            except Exception:
                pass
            if e.code == 401:
                raise SystemExit("Hetzner says: token invalid (401). Create a Read & Write token in the project.")
            if e.code == 403 and method != "GET":
                raise SystemExit("Hetzner says: token is read-only (403). Create a Read & Write token.")
            raise SystemExit(f"Hetzner API {method} {path}: {e.code} {msg}")

    def list(self, what, **params):
        out, page = [], 1
        while True:
            q = "&".join(f"{k}={urllib.request.quote(str(v))}" for k, v in dict(params, page=page, per_page=50).items())
            r = self.call("GET", f"/{what}?{q}")
            out += r.get(what, [])
            nxt = (r.get("meta") or {}).get("pagination", {}).get("next_page")
            if not nxt:
                return out
            page = nxt


def token():
    t = os.environ.get("HCLOUD_TOKEN") or getpass.getpass("Hetzner API token (Read & Write, input hidden): ")
    t = t.strip()
    if not t:
        raise SystemExit("No token.")
    return t


def fingerprint(pub):
    """MD5 fingerprint as Hetzner shows it (aa:bb:…)."""
    raw = base64.b64decode(pub.split()[1])
    h = hashlib.md5(raw).hexdigest()
    return ":".join(h[i:i + 2] for i in range(0, 32, 2))


def read_key(path):
    path = os.path.expanduser(path)
    if not os.path.isfile(path):
        for p in ("~/.ssh/id_ed25519.pub", "~/.ssh/id_ecdsa.pub", "~/.ssh/id_rsa.pub"):
            if os.path.isfile(os.path.expanduser(p)):
                path = os.path.expanduser(p)
                break
        else:
            raise SystemExit("No SSH public key found. Create one: ssh-keygen -t ed25519   (then run again)")
    with open(path) as f:
        pub = f.read().strip()
    return path, pub


def inventory(api):
    inv = {k: api.list(k) for k in ("servers", "firewalls", "ssh_keys", "volumes", "load_balancers")}
    return inv


def ours(res):
    return (res.get("labels") or {}).get("app") == LABEL["app"]


def conflicts(inv):
    bad = []
    for kind in ("servers", "firewalls", "ssh_keys"):
        for r in inv[kind]:
            if r["name"].startswith(NAME) and not ours(r):
                bad.append(f"{kind[:-1]} '{r['name']}' exists but was not created by this script")
    return bad


def price(st, loc):
    for p in st.get("prices", []):
        if p.get("location") == loc:
            return float(p["price_monthly"]["gross"])
    return None


def available_at(t, loc):
    """Orderable at this location now (API 2026: server_types[].locations[] with available + deprecation)."""
    for l in t.get("locations") or []:
        if l.get("name") == loc:
            return bool(l.get("available")) and not l.get("deprecation")
    return False


def choose_type(api, want_type, locations):
    """(server_type, location, monthly gross price) – cheapest available type with enough RAM and disk."""
    types = [t for t in api.list("server_types") if not t.get("deprecation")]
    for loc in locations:
        ok = [t for t in types if available_at(t, loc) and price(t, loc) is not None
              and (t["name"] == want_type if want_type else (t["memory"] >= MIN_MEM_GB and t["disk"] >= MIN_DISK_GB))]
        if ok:
            t = min(ok, key=lambda t: (price(t, loc), t["memory"]))
            return t, loc, price(t, loc)
        print(f"  {loc}: {'type ' + want_type if want_type else 'no suitable type'} not available right now")
    raise SystemExit("No suitable server type available in " + ", ".join(locations) + ". Try --location or --type.")


def user_data(domain):
    script = (f"git clone --depth 50 {REPO_URL} /opt/onpath/repo && "
              f"bash /opt/onpath/repo/tools/hetzner/server/install.sh {'--domain ' + domain if domain else ''}"
              f" > /var/log/onpath-install.log 2>&1")
    return "#cloud-config\npackages: [git]\nruncmd:\n  - [bash, -c, " + json.dumps(script) + "]\n"


def show_inventory(inv):
    print("Found in this Hetzner project (read only – this script will not change any of it):")
    for kind in ("servers", "firewalls", "ssh_keys", "volumes", "load_balancers"):
        names = [r["name"] for r in inv[kind] if not ours(r)]
        print(f"  {kind:15} {len(names):3}  {', '.join(names[:8])}{' …' if len(names) > 8 else ''}")
    mine = [f"{k[:-1]} {r['name']}" for k in ("servers", "firewalls", "ssh_keys") for r in inv[k] if ours(r)]
    if mine:
        print("  created earlier by this script: " + ", ".join(mine))


def server_ip(s):
    return ((s.get("public_net") or {}).get("ipv4") or {}).get("ip")


def ssh(ip, cmd, timeout=20):
    return subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
                           "-o", f"ConnectTimeout={timeout}", f"root@{ip}", cmd],
                          capture_output=True, text=True, timeout=timeout + 30)


def wait_ready(ip, minutes=25):
    print(f"Waiting for the server to install itself (5–15 minutes). Log: ssh root@{ip} tail -f /var/log/onpath-install.log")
    end = time.time() + minutes * 60
    while time.time() < end:
        try:
            r = ssh(ip, "test -f /etc/onpath/ready && cat /etc/onpath/host /etc/onpath/password.txt")
            if r.returncode == 0 and r.stdout.strip():
                host, pw = r.stdout.split()[:2]
                return host, pw
            tail = ssh(ip, "tail -1 /var/log/onpath-install.log 2>/dev/null")
            if tail.stdout.strip():
                print("  " + tail.stdout.strip()[:100])
        except Exception:
            pass
        time.sleep(20)
    return None, None


def cmd_plan(args, api, inv=None):
    inv = inv or inventory(api)
    show_inventory(inv)
    bad = conflicts(inv)
    if bad:
        raise SystemExit("Stopping: " + "; ".join(bad) + ". Rename it or use another project.")
    if any(ours(s) for s in inv["servers"]):
        print("\nThe map server already exists: run 'status'.")
        return None
    path, pub = read_key(args.ssh_key)
    fp = fingerprint(pub)
    key = next((k for k in inv["ssh_keys"] if k["fingerprint"] == fp), None)
    locs = [args.location] + [l for l in LOCATIONS_FALLBACK if l != args.location]
    st, loc, eur = choose_type(api, args.type, locs)
    print("\nWould create (nothing else):")
    print(f"  server   {NAME}: {st['name']} ({st['cores']} vCPU, {st['memory']:g} GB RAM, {st['disk']} GB disk, "
          f"{st.get('architecture', 'x86')}), Ubuntu 24.04, {loc} – about {eur:.2f} € / month incl. VAT (+ IPv4)")
    print(f"  firewall {NAME}: in 22/tcp (SSH), 80/tcp, 443/tcp – only on this server")
    print(f"  ssh key  {'reuse existing ' + repr(key['name']) if key else NAME + '-key from ' + path}")
    print(f"  address  {'https://' + args.domain if args.domain else 'https://<ip>.sslip.io'} (password protected)")
    return {"st": st, "loc": loc, "key": key, "pub": pub}


def cmd_apply(args, api):
    inv = inventory(api)
    p = cmd_plan(args, api, inv)
    if p is None:
        return
    if not args.yes:
        a = input("\nCreate this? Type yes: ").strip().lower()
        if a not in ("yes", "ja", "y", "j"):
            raise SystemExit("Nothing created.")
    os.environ["ONPATH_HETZNER_ALLOW_WRITE"] = "1"
    key = p["key"]
    if key is None:
        key = api.call("POST", "/ssh_keys", {"name": NAME + "-key", "public_key": p["pub"], "labels": LABEL})["ssh_key"]
        print(f"created ssh key {key['name']}")
    fw = api.call("POST", "/firewalls", {"name": NAME, "labels": LABEL, "rules": [
        {"direction": "in", "protocol": "tcp", "port": port, "source_ips": ["0.0.0.0/0", "::/0"], "description": d}
        for port, d in (("22", "SSH"), ("80", "HTTP (certificate)"), ("443", "HTTPS"))]})["firewall"]
    print(f"created firewall {fw['name']}")
    s = api.call("POST", "/servers", {
        "name": NAME, "server_type": p["st"]["name"], "image": "ubuntu-24.04", "location": p["loc"],
        "ssh_keys": [str(key["id"])], "firewalls": [{"firewall": fw["id"]}], "labels": LABEL,
        "user_data": user_data(args.domain), "public_net": {"enable_ipv4": True, "enable_ipv6": True},
        "start_after_create": True})["server"]
    ip = server_ip(s)
    print(f"created server {s['name']} – {ip}")
    if args.domain:
        print(f"Point the DNS A record of {args.domain} to {ip} now (the certificate needs it).")
    host, pw = wait_ready(ip)
    print_done(ip, host, pw)


def print_done(ip, host, pw):
    print()
    if host:
        print(f"Ready:   https://{host}")
        print(f"Login:   onpath / {pw}")
    else:
        print("Still installing. Later: python3 tools/hetzner/onpath_hetzner.py status")
    print(f"Server:  ssh root@{ip}   (install log /var/log/onpath-install.log, build logs /srv/onpath/logs)")


def cmd_status(args, api):
    servers = [s for s in api.list("servers", label_selector=LABEL_SEL)]
    if not servers:
        raise SystemExit("No map server in this project (run 'plan' / 'apply').")
    s = servers[0]
    ip = server_ip(s)
    print(f"{s['name']}: {s['status']}, {s['server_type']['name']}, {ip}")
    r = ssh(ip, "cat /etc/onpath/host /etc/onpath/password.txt 2>/dev/null; echo; "
                "test -f /etc/onpath/ready && echo READY; systemctl is-active onpath-worker onpath-api caddy; "
                "ls /srv/onpath/state 2>/dev/null | wc -l; df -h /srv/onpath | tail -1")
    if r.returncode != 0:
        print("SSH not reachable yet (installing?): " + (r.stderr.strip()[:200] or ""))
        return
    lines = r.stdout.split()
    if "READY" in lines:
        print_done(ip, lines[0], lines[1])
    print(r.stdout)


def cmd_destroy(args, api):
    servers = api.list("servers", label_selector=LABEL_SEL)
    fws = api.list("firewalls", label_selector=LABEL_SEL)
    keys = api.list("ssh_keys", label_selector=LABEL_SEL)
    if not (servers or fws or keys):
        raise SystemExit("Nothing created by this script in this project.")
    print("Would delete (all labelled app=onpath-maps):")
    for kind, rs in (("server", servers), ("firewall", fws), ("ssh key", keys)):
        for r in rs:
            print(f"  {kind} {r['name']}")
    print("All maps on the server are lost (they can be built again).")
    if input(f"Type the server name '{NAME}' to delete: ").strip() != NAME:
        raise SystemExit("Nothing deleted.")
    os.environ["ONPATH_HETZNER_ALLOW_WRITE"] = "1"
    for s in servers:
        assert ours(s)
        api.call("DELETE", f"/servers/{s['id']}")
        print(f"deleted server {s['name']}")
    if servers:
        time.sleep(15)  # firewall can only go once the server is gone
    for f in fws:
        assert ours(f)
        for i in range(10):
            try:
                api.call("DELETE", f"/firewalls/{f['id']}")
                print(f"deleted firewall {f['name']}")
                break
            except SystemExit as e:
                if i == 9:
                    raise
                time.sleep(10)
    for k in keys:
        assert ours(k)
        api.call("DELETE", f"/ssh_keys/{k['id']}")
        print(f"deleted ssh key {k['name']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["plan", "apply", "status", "destroy"], nargs="?", default="plan")
    ap.add_argument("--location", default="nbg1")
    ap.add_argument("--type", default=None)
    ap.add_argument("--ssh-key", default="~/.ssh/id_ed25519.pub")
    ap.add_argument("--domain", default="")
    ap.add_argument("--yes", action="store_true")
    a = ap.parse_args()
    api = Api(token())
    {"plan": cmd_plan, "apply": cmd_apply, "status": cmd_status, "destroy": cmd_destroy}[a.command](a, api)
    if api.writes:
        print("\nChanges made in Hetzner this run: " + "; ".join(api.writes))


if __name__ == "__main__":
    main()
