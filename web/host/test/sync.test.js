// The plan sync mailbox (src/sync.js) on a fake D1 (Node's SQLite): claim, token, compare-and-set, paging, limits,
// unpair, idle purge, and that the host routes /sync/ there.
import { test } from "node:test";
import assert from "node:assert/strict";
import { handleSync, purgeIdle, LIMITS } from "../src/sync.js";
import { handle } from "../src/index.js";
import { purgeQuota } from "../src/maps.js";
import { fakeD1 } from "./fake-d1.js";

const H = "https://plan.example.org/sync/v1/";
const BOX = "6_ppugL_I2cbDsZYhdOddA"; // the frozen test mailbox (PlanSyncTest)
const TOKEN = "wHErkFiAjM3dUXVy18TJETV0arl4vvuSa21gZUBEpjE";
const ITEM = "CQkJCQkJCQkJCQkJCQkJCQ";
const ITEM2 = "AgICAgICAgICAgICAgICAg";
const blob = (n = 40, v = 1) => new Uint8Array(n).fill(v);

function client(env, token = TOKEN, now = () => Date.now()) {
  const req = (method, path, { body, base, headers = {} } = {}) => handleSync(new Request(H + path, {
    method, body, headers: { authorization: "Bearer " + token, ...(base != null ? { "x-onpath-base": String(base) } : {}), ...headers },
  }), env, now());
  return {
    claim: () => req("POST", BOX),
    get: (since = 0) => req("GET", `${BOX}?since=${since}`),
    put: (id, base, body = blob()) => req("PUT", `${BOX}/${id}`, { body, base }),
    del: () => req("DELETE", BOX),
  };
}
const fresh = () => ({ SYNC: fakeD1() });

test("first writer claims; the same token again is fine, another is refused", async () => {
  const env = fresh();
  assert.equal((await client(env).claim()).status, 201);
  assert.equal((await client(env).claim()).status, 200);
  const other = client(env, "x".repeat(43));
  assert.equal((await other.claim()).status, 403);
  assert.equal((await other.get()).status, 403);
  assert.equal((await other.put(ITEM, 0)).status, 403);
  assert.equal((await client(env, "short").get()).status, 401);
  assert.equal((await client(fresh()).get()).status, 404, "unknown mailbox");
});

test("only the token's hash is stored, blobs are kept as they came", async () => {
  const env = fresh();
  const c = client(env);
  await c.claim();
  await c.put(ITEM, 0, blob(50, 7));
  const row = env.SYNC.sqlite.prepare("SELECT * FROM mailbox").get();
  assert.notEqual(row.token_hash, TOKEN);
  assert.equal(row.token_hash.length, 64);
  const items = (await (await c.get()).json()).items;
  assert.equal(Buffer.from(items[0].blob, "base64url").toString("hex"), "07".repeat(50));
});

test("compare-and-set: a write on an old revision is a 409 with the current revision", async () => {
  const c = client(fresh());
  await c.claim();
  assert.deepEqual(await (await c.put(ITEM, 0)).json(), { rev: 1 });
  assert.deepEqual(await (await c.put(ITEM2, 0)).json(), { rev: 2 });
  const late = await c.put(ITEM, 0); // someone else's first version, not knowing rev 1
  assert.equal(late.status, 409);
  assert.deepEqual(await late.json(), { rev: 1 });
  assert.deepEqual(await (await c.put(ITEM, 1, blob(40, 2))).json(), { rev: 3 });
  const page = await (await c.get(1)).json();
  assert.deepEqual(page.items.map((i) => [i.id, i.rev]), [[ITEM2, 2], [ITEM, 3]]);
  assert.equal(page.rev, 3);
  assert.equal(page.more, false);
  assert.equal((await (await c.get(3)).json()).items.length, 0);
});

test("pages of 200 items", async () => {
  const c = client(fresh());
  await c.claim();
  for (let i = 0; i < 205; i++) await c.put(Buffer.from(new Uint8Array(16).fill(i)).toString("base64url"), 0);
  const p1 = await (await c.get(0)).json();
  assert.equal(p1.items.length, 200);
  assert.equal(p1.more, true);
  assert.equal(p1.rev, 200);
  const p2 = await (await c.get(p1.rev)).json();
  assert.equal(p2.items.length, 5);
  assert.equal(p2.more, false);
});

test("limits: item size, item count, bad requests", async () => {
  const env = fresh();
  const c = client(env);
  await c.claim();
  assert.equal((await c.put(ITEM, 0, blob(LIMITS.blobBytes + 1))).status, 413);
  assert.equal((await c.put(ITEM, 0, blob(10))).status, 400);
  assert.equal((await c.put(ITEM, null)).status, 400);
  assert.equal((await handleSync(new Request(H + "short"), env)).status, 404);
  assert.equal((await handleSync(new Request(H + BOX + "/" + ITEM + "/x"), env)).status, 404);
  const save = LIMITS.items; LIMITS.items = 2;
  try {
    await c.put(ITEM, 0); await c.put(ITEM2, 0);
    assert.equal((await c.put("AwMDAwMDAwMDAwMDAwMDAw", 0)).status, 507);
    assert.equal((await c.put(ITEM, 1)).status, 200, "updating an existing item is still fine");
  } finally { LIMITS.items = save; }
});

test("unpair deletes everything; the mailbox then answers 410 and cannot be claimed again", async () => {
  const env = fresh();
  const c = client(env);
  await c.claim();
  await c.put(ITEM, 0);
  assert.equal((await c.del()).status, 200);
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM item").get().n, 0);
  for (const r of [await c.get(), await c.put(ITEM, 0), await c.claim(), await client(env, "y".repeat(43)).claim()]) assert.equal(r.status, 410);
});

test("rate limit binding (when bound) answers 429", async () => {
  const env = { ...fresh(), SYNC_RATE: { limit: async () => ({ success: false }) } };
  assert.equal((await client(env).claim()).status, 429);
  assert.equal((await handleSync(new Request(H + BOX), {})).status, 503, "no database bound");
});

test("idle mailboxes are deleted after 12 months", async () => {
  const env = fresh();
  const t0 = Date.parse("2026-01-01");
  const c = client(env, TOKEN, () => t0);
  await c.claim(); await c.put(ITEM, 0);
  assert.equal(await purgeIdle(env, t0 + 300 * 86_400_000), 0);
  assert.equal(await purgeIdle(env, t0 + 366 * 86_400_000), 1);
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM item").get().n, 0);
  assert.equal((await c.get()).status, 404);
});

test("the host sends /sync/ to the mailbox, not to the page", async () => {
  const env = { ...fresh(), ASSETS: { fetch: async () => new Response("page") } };
  const r = await handle(new Request(H + BOX, { method: "POST", headers: { authorization: "Bearer " + TOKEN } }), env);
  assert.equal(r.status, 201);
  assert.equal(r.headers.get("cache-control"), "no-store");
});

// a claim with its own mailbox id / token from one IP (ids: 22 chars, tokens: 43)
const claimFrom = (env, ip, i, now = Date.UTC(2026, 9, 30, 12)) => handleSync(new Request(H + ("box" + i).padEnd(22, "x"), {
  method: "POST", headers: { authorization: "Bearer " + ("tok" + i).padEnd(43, "y"), "cf-connecting-ip": ip },
}), env, now);

test("new mailboxes per IP per day are capped; joining an existing one does not count", async () => {
  const env = { ...fresh(), MAP_KEY: "k", SYNC_NEW_PER_IP_DAY: "3" };
  for (let i = 0; i < 3; i++) assert.equal((await claimFrom(env, "203.0.113.7", i)).status, 201);
  for (let i = 0; i < 3; i++) assert.equal((await claimFrom(env, "203.0.113.7", i)).status, 200, "pairing an existing mailbox");
  const over = await claimFrom(env, "203.0.113.7", 9);
  assert.equal(over.status, 429);
  assert.equal((await over.json()).code, "new-mailbox-cap");
  assert.equal(over.headers.get("retry-after"), String(12 * 3600));
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM mailbox").get().n, 3, "nothing made over the cap");
  assert.equal((await claimFrom(env, "198.51.100.1", 9)).status, 201, "another IP has its own count");
  assert.equal((await claimFrom(env, "203.0.113.7", 8, Date.UTC(2026, 9, 31, 0, 1))).status, 201, "a new day starts again");
  // counts only, the IP only as a salted hash
  const rows = env.SYNC.sqlite.prepare("SELECT * FROM map_quota").all();
  assert.ok(rows.every((r) => /^sync:[0-9a-f]{32}$/.test(r.k)));
  assert.ok(!JSON.stringify(rows).includes("203.0.113"));
  assert.deepEqual(rows.map((r) => r.n).sort(), [1, 1, 3]);
});

test("the cap defaults to LIMITS.newPerIpDay; its counters go with the daily purge", async () => {
  const env = { ...fresh(), MAP_KEY: "k" };
  for (let i = 0; i < LIMITS.newPerIpDay; i++) assert.equal((await claimFrom(env, "203.0.113.7", i)).status, 201);
  assert.equal((await claimFrom(env, "203.0.113.7", 99)).status, 429);
  await purgeQuota(env, Date.UTC(2026, 10, 2));
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM map_quota").get().n, 0);
});

test("appSeen: when the phone app last used the mailbox (to the minute); web planner requests do not count", async () => {
  const env = fresh();
  let t = Date.UTC(2026, 9, 30, 12, 0, 30);
  const req = (method, path, headers, body) => handleSync(new Request(H + path, { method, body, headers: { authorization: "Bearer " + TOKEN, ...headers } }), env, t);
  const web = { "x-onpath-client": "web", "user-agent": "Mozilla/5.0 (X11; Linux x86_64)" };
  const app = { "user-agent": "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8)" };
  assert.equal((await (await req("POST", BOX, web)).json()).appSeen, null, "claimed by the browser: no phone yet");
  assert.equal((await (await req("GET", `${BOX}?since=0`, { "user-agent": "Mozilla/5.0 (Android 14)" })).json()).appSeen, null, "a browser without the header is the web too");
  assert.equal((await (await req("GET", `${BOX}?since=0`, app)).json()).appSeen, Date.UTC(2026, 9, 30, 12, 0), "the app's GET, rounded to the minute");
  t += 3 * 86_400_000;
  assert.equal((await (await req("GET", `${BOX}?since=0`, web)).json()).appSeen, Date.UTC(2026, 9, 30, 12, 0), "3 days later the browser still sees the phone's last time");
  const r = await req("PUT", `${BOX}/${ITEM}`, { ...app, "x-onpath-base": "0" }, blob());
  assert.equal(r.status, 200);
  assert.equal((await (await req("POST", BOX, web)).json()).appSeen, Date.UTC(2026, 10, 2, 12, 0), "an app write counts too");
  assert.deepEqual(Object.keys(env.SYNC.sqlite.prepare("SELECT * FROM mailbox").get()).sort(),
    ["app_seen", "created", "deleted", "id", "rev", "seen", "token_hash"], "no IP, no device column");
});
