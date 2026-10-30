// Map areas on demand (src/maps.js): only request + status pass, the key is added here and never answered back, the
// bodies are rebuilt, limits per IP and for all, no key → a plain "not available" (the page degrades), errors upstream.
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";
import { MAX_IDS, purgeQuota } from "../src/maps.js";
import { fakeD1 } from "./fake-d1.js";

const KEY = "test-key-123";
const H = "https://plan.example.org";

/** A fake doorman (map-cells.md §8 shapes): records what it got. */
function fakeDoorman(answer = null) {
  const seen = [];
  const fetch = async (req) => {
    const r = req instanceof Request ? req : new Request(req);
    const body = r.method === "POST" ? await r.text() : null;
    seen.push({ url: r.url, method: r.method, key: r.headers.get("x-onpath-key"), app: r.headers.get("x-onpath-app"), ip: r.headers.get("cf-connecting-ip"), body });
    if (answer) return answer(r);
    if (r.headers.get("x-onpath-key") !== KEY) return Response.json({ error: "unknown app key" }, { status: 401 });
    return Response.json({ cells: [{ id: "10-531-341", state: "queued", position: 2, eta_s: 300 }], poll_s: 15 });
  };
  return { seen, binding: { fetch } };
}
const limiter = (max) => { const n = new Map(); return { async limit({ key }) { n.set(key, (n.get(key) ?? 0) + 1); return { success: n.get(key) <= max }; } }; };
const post = (body, ip = "1.2.3.4") => new Request(H + "/maps/v1/cells/request", { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body),
  headers: { "content-type": "application/json", "cf-connecting-ip": ip } });

test("request passes to the doorman binding with the key added; the answer comes back without it", async () => {
  const d = fakeDoorman();
  const r = await handle(post({ cells: ["10-531-341", "10-531-341"], reason: "point", formats: [1, 2], extra: "x" }), { MAP_KEY: KEY, DOORMAN: d.binding });
  assert.equal(r.status, 200);
  const text = await r.text();
  assert.ok(!text.includes(KEY));
  assert.equal(JSON.parse(text).cells[0].state, "queued");
  assert.equal(d.seen[0].url, "https://doorman/v1/cells/request");
  assert.equal(d.seen[0].key, KEY);
  assert.equal(d.seen[0].app, "web-planner");
  assert.equal(d.seen[0].ip, "1.2.3.4", "the doorman's per-IP limit sees the browser");
  assert.deepEqual(JSON.parse(d.seen[0].body), { cells: ["10-531-341"], reason: "point", formats: [2] }, "body rebuilt, deduped, format 2 only");
});

test("status passes as GET with format 2; over HTTPS to MAPS_HOST when there is no binding", async () => {
  const d = fakeDoorman();
  const r = await handle(new Request(H + "/maps/v1/cells/status?ids=10-531-341,10-531-342&x=1"), { MAP_KEY: KEY, MAPS_HOST: "maps.example.org" },
    (url, init) => d.binding.fetch(new Request(url, init)));
  assert.equal(r.status, 200);
  assert.equal(d.seen[0].url, "https://maps.example.org/v1/cells/status?ids=10-531-341,10-531-342&format=2");
  assert.equal(d.seen[0].method, "GET");
  assert.equal(d.seen[0].key, KEY);
});

test("only the two calls, only their methods, only zoom-10 ids and known reasons", async () => {
  const d = fakeDoorman();
  const env = { MAP_KEY: KEY, DOORMAN: d.binding };
  assert.equal((await handle(new Request(H + "/maps/v1/cells/10/531/341/manifest"), env)).status, 404);
  assert.equal((await handle(new Request(H + "/maps/v1/files/cells/x"), env)).status, 404);
  assert.equal((await handle(new Request(H + "/maps/v1/health"), env)).status, 404);
  assert.equal((await handle(new Request(H + "/maps/v1/cells/request"), env)).status, 405);
  assert.equal((await handle(new Request(H + "/maps/v1/cells/status?ids=10-1-1", { method: "POST", body: "{}" }), env)).status, 405);
  for (const bad of [{ cells: ["7-68-44"] }, { cells: ["10-5000-1"] }, { cells: [] }, { cells: ["10-1-1"], reason: "all" },
    { cells: Array.from({ length: MAX_IDS + 1 }, (_, i) => `10-1-${i}`) }, "not json", "x".repeat(5000)])
    assert.ok([400, 413].includes((await handle(post(bad), env)).status), JSON.stringify(bad).slice(0, 40));
  assert.equal((await handle(new Request(H + "/maps/v1/cells/status?ids=10-1-1,abc"), env)).status, 400);
  assert.equal(d.seen.length, 0, "nothing bad reached the doorman");
});

test("no key (owner step not done): 503 not available, nothing sent", async () => {
  const d = fakeDoorman();
  const r = await handle(post({ cells: ["10-531-341"] }), { DOORMAN: d.binding });
  assert.equal(r.status, 503);
  assert.equal((await r.json()).code, "no-key");
  assert.equal(d.seen.length, 0);
});

test("a key the doorman refuses → not available (key-refused), never the doorman's 401", async () => {
  const d = fakeDoorman();
  const r = await handle(post({ cells: ["10-531-341"] }), { MAP_KEY: "old", DOORMAN: d.binding });
  assert.equal(r.status, 503);
  assert.equal((await r.json()).code, "key-refused");
});

test("rate limits per IP and for all browsers; the doorman's 429 and Retry-After pass", async () => {
  const d = fakeDoorman();
  const env = { MAP_KEY: KEY, DOORMAN: d.binding, MAPS_RATE: limiter(2), MAPS_RATE_ALL: limiter(3) };
  assert.equal((await handle(post({ cells: ["10-1-1"] }, "a"), env)).status, 200);
  assert.equal((await handle(post({ cells: ["10-1-1"] }, "a"), env)).status, 200);
  const third = await handle(post({ cells: ["10-1-1"] }, "a"), env);
  assert.equal(third.status, 429);
  assert.equal(third.headers.get("retry-after"), "60");
  assert.equal((await handle(post({ cells: ["10-1-1"] }, "b"), env)).status, 200); // another IP: its own limit
  assert.equal((await handle(post({ cells: ["10-1-1"] }, "c"), env)).status, 429); // all together: 3 per minute
  const busy = fakeDoorman(() => Response.json({ error: "The map server is busy" }, { status: 429, headers: { "retry-after": "600" } }));
  const r = await handle(post({ cells: ["10-1-1"] }), { MAP_KEY: KEY, DOORMAN: busy.binding });
  assert.equal(r.status, 429);
  assert.equal(r.headers.get("retry-after"), "600");
});

test("doorman not reachable → 502, no upstream configured → 503", async () => {
  const r = await handle(post({ cells: ["10-1-1"] }), { MAP_KEY: KEY, DOORMAN: { fetch: async () => { throw new Error("network down"); } } });
  assert.equal(r.status, 502);
  assert.equal((await handle(post({ cells: ["10-1-1"] }), { MAP_KEY: KEY })).status, 503);
});

// ---- quotas for new map areas (A-011): per browser / IP / everyone, counts only ----
const B1 = "0123456789abcdef0123456789abcdef", B2 = "fedcba9876543210fedcba9876543210";
const postAs = (browser, ip = "1.2.3.4", cells = ["10-531-341"]) => new Request(H + "/maps/v1/cells/request", { method: "POST",
  body: JSON.stringify({ cells, reason: "point" }), headers: { "content-type": "application/json", "cf-connecting-ip": ip, ...(browser ? { "x-onpath-browser": browser } : {}) } });

test("quota: new areas counted per browser per day; over it → 429 {quota: browser}; another browser goes on", async () => {
  const d = fakeDoorman(), db = fakeD1();
  const env = { MAP_KEY: KEY, DOORMAN: d.binding, SYNC: db, MAPS_QUOTA_BROWSER_DAY: "2" };
  assert.equal((await handle(postAs(B1), env)).status, 200);
  assert.equal((await handle(postAs(B1), env)).status, 200);
  const r = await handle(postAs(B1), env);
  assert.equal(r.status, 429);
  assert.equal((await r.json()).quota, "browser");
  assert.equal(d.seen.length, 2, "nothing goes to the doorman over the quota");
  assert.equal((await handle(postAs(B2), env)).status, 200);
  // only counts: keys and numbers, no cell id or IP in the table
  const rows = db.sqlite.prepare("SELECT k, period, n FROM map_quota").all();
  assert.ok(rows.every((x) => !/10-531-341|1\.2\.3\.4/.test(x.k + x.period)), JSON.stringify(rows));
  assert.ok(rows.some((x) => x.k === "b:" + B1 && x.period.length === 7 && x.n === 2), "the month is counted too");
});

test("quota: per IP (hashed) and for everyone; cells already built do not count; status calls never count", async () => {
  const ready = fakeDoorman(() => Response.json({ cells: [{ id: "10-531-341", state: "ready" }] }));
  const db = fakeD1();
  const env = { MAP_KEY: KEY, DOORMAN: ready.binding, SYNC: db, MAPS_QUOTA_IP_DAY: "1" };
  for (let i = 0; i < 3; i++) assert.equal((await handle(postAs(B1), env)).status, 200, "ready cells are no new work");
  const d = fakeDoorman();
  const env2 = { ...env, DOORMAN: d.binding };
  assert.equal((await handle(postAs(B1), env2)).status, 200);
  const r = await handle(postAs(B2), env2); // another browser, same IP
  assert.equal((await r.json()).quota, "ip");
  assert.equal((await handle(postAs(null, "9.9.9.9"), env2)).status, 200, "no browser id: the IP and global caps still apply");
  const g = await handle(postAs(B2, "8.8.8.8"), { ...env2, MAPS_QUOTA_IP_DAY: "9", MAPS_QUOTA_GLOBAL_DAY: "2" });
  assert.equal((await g.json()).quota, "global");
  for (let i = 0; i < 3; i++) assert.equal((await handle(new Request(H + "/maps/v1/cells/status?ids=10-531-341"), env2)).status, 200);
});

test("quota: old counters are deleted by the daily job", async () => {
  const db = fakeD1();
  db.sqlite.exec("INSERT INTO map_quota VALUES ('all','2026-10-01',5), ('all','2026-10-30',1), ('b:x','2026-09',3), ('b:x','2026-10',3)");
  await purgeQuota({ SYNC: db }, Date.parse("2026-10-30T12:00:00Z"));
  assert.deepEqual(db.sqlite.prepare("SELECT period FROM map_quota ORDER BY period").all().map((x) => x.period), ["2026-10", "2026-10-30"]);
});
