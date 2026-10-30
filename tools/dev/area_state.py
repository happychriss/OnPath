"""Print "<id> <state> <version>" per cell id from the R2 bucket of the env (credentials from the process env – run
inside the job container with docker --env-file, never on the host): current (published, schema ok, osm_date within
<fresh days>), stale, old-schema, missing, cloud-building, unknown.
Usage: test_state.py <tools/cloud dir> <fresh days> <cell schema> <pkg schema> ids…"""
import calendar
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, sys.argv[1])
import r2  # noqa: E402

if os.environ.get("R2_BUCKET") != "onpath-maps-test":
    sys.exit("test bucket only")
store = r2.from_env(os.environ)
fresh_days, cs, ps = float(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])


def one(c):
    z, x, y = c.split("-")
    try:
        raw = store.get_bytes(f"status/f2/{c}.json")
        s = json.loads(raw) if raw else {}
        t = calendar.timegm(time.strptime(s["updated"], "%Y-%m-%dT%H:%M:%SZ")) if s.get("updated") else 0
        if (s.get("state") == "building" and time.time() - t < 70 * 60
                and not str(s.get("execution", "")).startswith("donald-")):
            return f"{c} cloud-building -"
        raw = store.get_bytes(f"cells/f2/{z}/{x}/{y}/current.json")
        if not raw:
            return f"{c} missing -"
        v = json.loads(raw)["version"]
        d, _, sch = v.rpartition("-s")
        if int(sch) < (ps if z == "7" else cs):
            return f"{c} old-schema {v}"
        age = (time.time() - calendar.timegm(time.strptime(d[:10], "%Y-%m-%d"))) / 86400
        return f"{c} {'current' if age <= fresh_days else 'stale'} {v}"
    except Exception as e:  # noqa: BLE001 – unknown = build it
        return f"{c} unknown {type(e).__name__}"


with ThreadPoolExecutor(8) as ex:
    for line in ex.map(one, sys.argv[5:]):
        print(line, flush=True)
