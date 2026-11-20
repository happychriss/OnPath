// The live tracking box (src/live.js) on a fake D1 (Node's SQLite). Each test names what it guards: the link's read
// token can never write; the page's times are the server's; nothing outlives its time; limits hold; a database error is
// a readable 503; the database holds nothing but opaque blobs.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { handleLive, purgeLive, LIMITS, NEW_BOX_CAP } from "../src/live.js";
import { handle } from "../src/index.js";
import { fakeD1 } from "./fake-d1.js";

const H = "https://plan.example.org/live/v1/";
// test vector from the dummy secret i*7+1 (or a made-up test token) – not a credential
const BOX = "0dPnimRkezLc3fCguuX6rg";                          // the frozen test box and read token (LiveFormatTest) // ggignore
// test vector from the dummy secret i*7+1 (or a made-up test token) – not a credential
const READ = "aFVOTMzUKm5qEBrhELBD-BkTFhqMML-Q55w9JiKjmjo"; // ggignore
const WRITE = "W".repeat(43);                                    // the phone's own random token
const sha = (t) => createHash("sha256").update(t).digest("hex");
const blob = (n = 60, c = "A") => c.repeat(n);
const T0 = Date.UTC(2026, 10, 20, 9, 0, 0);

/** A phone (write token) or a viewer (read token) talking to one box; `clock.t` is the server's time. */
function side(env, token, clock = { t: T0 }, box = BOX, headers = {}) {
  const req = (method, path, body) => handleLive(new Request(H + path, {
    method, body: body === undefined ? undefined : (typeof body === "string" ? body : JSON.stringify(body)),
    headers: { authorization: "Bearer " + token, ...headers },
  }), env, clock.t);
  return {
    claim: (read = sha(READ)) => req("POST", box, { read }),
    push: (items, ended) => req("POST", box + "/push", { items, ...(ended === undefined ? {} : { ended }) }),
    raw: req,
    get: (since = 0) => req("GET", `${box}?since=${since}`),
    del: () => req("DELETE", box),
  };
}
const fresh = () => ({ SYNC: fakeD1(), MAP_KEY: "k" });
const item = (name, n = 60, c = "A") => ({ name, blob: blob(n, c) });
const body = async (r) => { assert.equal(r.headers.get("cache-control"), "no-store"); return r.json(); };

test("claim: the phone makes the box; the same tokens again are fine; any other token is refused", async () => {
  const env = fresh();
  const phone = side(env, WRITE);
  const first = await phone.claim();
  assert.equal(first.status, 201);
  assert.deepEqual(await body(first), { rev: 0 });
  assert.equal((await phone.claim()).status, 200, "a claim sent twice (answer lost) is the same box");
  assert.equal((await side(env, "X".repeat(43)).claim()).status, 403, "another writer");
  assert.equal((await phone.claim(sha("another read token"))).status, 403, "the same writer with another read token");
  assert.equal((await side(env, READ).claim()).status, 400, "a link holder cannot claim with the link's own token as writer");
  assert.equal((await phone.claim("nothex")).status, 400);
  assert.equal((await phone.raw("POST", BOX, "{not json")).status, 400);
  assert.equal((await side(env, "short").claim()).status, 401);
  assert.equal((await side(env, WRITE).raw("GET", "short")).status, 404, "an odd box id");
  assert.equal((await side(env, WRITE).raw("GET", BOX + "/push/x")).status, 404, "an odd path");
});

test("only the hashes of both tokens are stored", async () => {
  const env = fresh();
  await side(env, WRITE).claim();
  const row = env.SYNC.sqlite.prepare("SELECT * FROM live_box").get();
  assert.equal(row.write_hash, sha(WRITE));
  assert.equal(row.read_hash, sha(READ));
  assert.ok(!JSON.stringify(row).includes(WRITE) && !JSON.stringify(row).includes(READ));
});

test("push: all items under one new revision; the viewer reads only what changed since", async () => {
  const env = fresh();
  const clock = { t: T0 };
  const phone = side(env, WRITE, clock), viewer = side(env, READ, clock);
  assert.equal((await viewer.get()).status, 404, "before the phone got through: no such box yet");
  await phone.claim();
  const empty = await body(await viewer.get());
  assert.deepEqual(empty, { rev: 0, now: T0, lastWrite: T0, ended: null, more: false, items: [] });

  clock.t = T0 + 120_000;
  assert.deepEqual(await body(await phone.push([item("r", 80, "R"), item("t0000", 70, "T"), item("h", 60, "H")])), { rev: 1 });
  clock.t = T0 + 240_000;
  assert.deepEqual(await body(await phone.push([item("t0001", 70, "U"), item("h", 60, "I")])), { rev: 2 });

  clock.t = T0 + 250_000;
  const all = await body(await viewer.get(0));
  assert.deepEqual(all.items.map((i) => [i.name, i.rev]), [["r", 1], ["t0000", 1], ["h", 2], ["t0001", 2]]);
  assert.equal(all.items.find((i) => i.name === "h").blob, blob(60, "I"), "the head is replaced, blobs come back as they were given");
  assert.equal(all.rev, 2);
  const news = await body(await viewer.get(1));
  assert.deepEqual(news.items.map((i) => i.name), ["h", "t0001"], "since 1: only the second push");
  assert.deepEqual((await body(await viewer.get(2))).items, [], "nothing new");
  assert.equal((await phone.get(0)).status, 200, "the phone's own token may read too");
});

test("the page's times are the server's: now, lastWrite and ended come from the server clock, not from any body", async () => {
  const env = fresh();
  const clock = { t: T0 };
  const phone = side(env, WRITE, clock), viewer = side(env, READ, clock);
  await phone.claim();
  clock.t = T0 + 60_000;
  await phone.push([item("h")]);
  clock.t = T0 + 185_000;
  let g = await body(await viewer.get());
  assert.equal(g.now, T0 + 185_000);
  assert.equal(g.lastWrite, T0 + 60_000);
  assert.equal(g.now - g.lastWrite, 125_000, "'vor 2 Min.' without a phone clock");
  assert.equal(g.ended, null);
  clock.t = T0 + 200_000;
  g = await body(await viewer.get());
  assert.equal(g.lastWrite, T0 + 60_000, "a GET writes nothing – looking does not make the tour look alive");
  clock.t = T0 + 300_000;
  assert.equal((await phone.push([item("h")], true)).status, 200);
  clock.t = T0 + 400_000;
  await phone.push([item("h")]); // a late head after the end keeps the end's time
  g = await body(await viewer.get());
  assert.equal(g.ended, T0 + 300_000);
  assert.equal((await phone.push([], true)).status, 200, "an end without items is a push");
  assert.equal((await phone.push([])).status, 400, "nothing at all is not");
});

test("the link's read token can read – and never claim over, push or delete", async () => {
  const env = fresh();
  const phone = side(env, WRITE), viewer = side(env, READ);
  await phone.claim();
  await phone.push([item("h"), item("t0000")]);
  assert.equal((await viewer.get()).status, 200);
  const before = env.SYNC.sqlite.prepare("SELECT name, rev, blob FROM live_item ORDER BY name").all().map((r) => ({ ...r }));
  assert.equal((await viewer.push([item("h", 60, "Z")])).status, 403);
  assert.equal((await viewer.push([], true)).status, 403);
  assert.equal((await viewer.del()).status, 403);
  assert.equal((await viewer.claim(sha(WRITE))).status, 403);
  assert.deepEqual(env.SYNC.sqlite.prepare("SELECT name, rev, blob FROM live_item ORDER BY name").all().map((r) => ({ ...r })), before, "nothing changed");
  const row = env.SYNC.sqlite.prepare("SELECT rev, ended, deleted FROM live_box").get();
  assert.deepEqual({ ...row }, { rev: 1, ended: null, deleted: null });
  const stranger = side(env, "S".repeat(43));
  for (const r of [await stranger.get(), await stranger.push([item("h")]), await stranger.del()]) assert.equal(r.status, 403);
});

test("limits: item size, names, shape, items in a push, box size and count", async () => {
  const env = fresh();
  const phone = side(env, WRITE);
  await phone.claim();
  assert.equal((await phone.push([item("h", LIMITS.blobChars)])).status, 200, "exactly 16 KiB is fine");
  assert.equal((await phone.push([item("h", LIMITS.blobChars + 1)])).status, 413);
  for (const name of ["x", "t1", "t00000", "H", "h2", "../h", ""]) assert.equal((await phone.push([item(name)])).status, 413, `name "${name}"`);
  assert.equal((await phone.push([item("t0001"), item("t0001")])).status, 413, "one name twice");
  assert.equal((await phone.push([item("h", 20)])).status, 400, "shorter than a sealed item can be");
  assert.equal((await phone.push([{ name: "h", blob: blob(60) + "+/" }])).status, 400, "not Base64url");
  assert.equal((await phone.push([{ name: "h" }])).status, 400);
  assert.equal((await phone.raw("POST", BOX + "/push", { items: "h" })).status, 400);
  assert.equal((await phone.raw("POST", BOX + "/push", { items: [], ended: "yes" })).status, 400);
  assert.equal((await phone.raw("POST", BOX + "/push", "[]")).status, 400);
  assert.equal((await phone.push(Array.from({ length: LIMITS.pushItems + 1 }, (_, i) => item("t" + String(i).padStart(4, "0"))))).status, 413);
  assert.equal((await phone.raw("PUT", BOX, {})).status, 405);

  const save = { ...LIMITS };
  try {
    LIMITS.items = 3;
    await phone.push([item("t0000"), item("t0001")]); // with h: 3
    assert.equal((await phone.push([item("t0002")])).status, 507, "a 4th item");
    assert.equal((await phone.push([item("h"), item("t0001", 80)])).status, 200, "replacing existing items is still fine");
    LIMITS.items = save.items;
    LIMITS.boxChars = 500;
    assert.equal((await phone.push([item("h", 60)])).status, 200, "a smaller head makes room");
    assert.equal((await phone.push([item("t0003", 400)])).status, 507, "over the box's size");
    assert.equal((await phone.push([item("t0003", 100)])).status, 200);
  } finally { Object.assign(LIMITS, save); }
});

test("60 pushes an hour per box, then 429 with the seconds to wait; the next hour is free again", async () => {
  const env = fresh();
  const clock = { t: T0 };
  const phone = side(env, WRITE, clock);
  await phone.claim();
  for (let i = 0; i < LIMITS.pushesPerHour; i++) { clock.t = T0 + i * 30_000; assert.equal((await phone.push([item("h")])).status, 200); }
  clock.t = T0 + 40 * 60_000;
  const over = await phone.push([item("h")]);
  assert.equal(over.status, 429);
  assert.equal(over.headers.get("retry-after"), String(20 * 60));
  assert.equal((await body(await side(env, READ, clock).get())).rev, LIMITS.pushesPerHour, "the refused push stored nothing");
  clock.t = T0 + 60 * 60_000;
  assert.equal((await phone.push([item("h")])).status, 200);
});

test("a long tour is read in pages that never cut a push in two", async () => {
  const env = fresh();
  const phone = side(env, WRITE), viewer = side(env, READ);
  await phone.claim();
  const save = { ...LIMITS };
  try {
    LIMITS.page = 5; LIMITS.pushesPerHour = 1000;
    for (let i = 0; i < 6; i++) await phone.push([item("t" + String(i).padStart(4, "0")), item("h")]); // h moves to the newest rev each time
    const seen = [];
    let since = 0, pages = 0;
    for (;;) {
      const g = await body(await viewer.get(since));
      seen.push(...g.items.map((i) => i.name));
      assert.ok(g.items.length <= 5);
      since = g.rev; pages++;
      if (!g.more) break;
      assert.ok(pages < 10, "paging ends");
    }
    assert.deepEqual(seen.sort(), ["h", "t0000", "t0001", "t0002", "t0003", "t0004", "t0005"]);
    assert.equal(since, 6);
    assert.ok(pages >= 2);
  } finally { Object.assign(LIMITS, save); }
});

test("stop sharing: everything is gone at once and both sides get 410 from then on", async () => {
  const env = fresh();
  const phone = side(env, WRITE), viewer = side(env, READ);
  await phone.claim();
  await phone.push([item("h"), item("r"), item("t0000")]);
  const d = await phone.del();
  assert.equal(d.status, 200);
  assert.deepEqual(await body(d), { deleted: true });
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM live_item").get().n, 0);
  const row = env.SYNC.sqlite.prepare("SELECT read_hash, write_hash FROM live_box").get();
  assert.deepEqual({ ...row }, { read_hash: "", write_hash: "" }, "the hashes are forgotten too");
  for (const r of [await viewer.get(), await phone.get(), await phone.push([item("h")]), await phone.claim(), await phone.del(),
    await side(env, "N".repeat(43)).claim()]) assert.equal(r.status, 410);
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM live_item").get().n, 0, "a push after the stop stored nothing");
});

test("nothing lives long: a finished tour goes 12 h after its end, any box 48 h after its last push, the 410 row 48 h later", async () => {
  const env = fresh();
  const clock = { t: T0 };
  const HOUR = 3_600_000;
  const ended = side(env, WRITE, clock, "ended".padEnd(22, "x")), silent = side(env, WRITE, clock, "silent".padEnd(22, "x")),
    alive = side(env, WRITE, clock, "alive".padEnd(22, "x"));
  for (const s of [ended, silent, alive]) { await s.claim(); await s.push([item("h"), item("t0000")]); }
  clock.t = T0 + HOUR;
  await ended.push([item("h")], true);

  assert.deepEqual(await purgeLive(env, T0 + 12 * HOUR + HOUR - 1), [0, 0], "just before 12 h after the end");
  clock.t = T0 + 13 * HOUR + 1;
  await alive.push([item("h")]);
  assert.deepEqual(await purgeLive(env, clock.t), [1, 0]);
  assert.equal((await ended.get()).status, 410, "the finished tour: no longer shared");
  assert.equal((await silent.get()).status, 200, "the phone that fell silent: its last place stays");

  clock.t = T0 + 47 * HOUR;
  await alive.push([item("h")]);
  assert.deepEqual(await purgeLive(env, T0 + 48 * HOUR - 1), [0, 0]);
  clock.t = T0 + 48 * HOUR + 1;
  assert.deepEqual(await purgeLive(env, clock.t), [1, 0]);
  assert.equal((await silent.get()).status, 410);
  assert.equal((await alive.get()).status, 200, "a tour still pushing is untouched");
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM live_item").get().n, 2, "only the living box's items are left");

  clock.t = T0 + 13 * HOUR + 1 + 48 * HOUR + 1;
  await alive.push([item("h")]);
  assert.deepEqual(await purgeLive(env, clock.t), [0, 1], "the ended box's 410 row is forgotten");
  assert.equal((await ended.get()).status, 404);
  assert.deepEqual(await purgeLive({}, clock.t), [0, 0], "no database bound: nothing to do, no throw");
});

test("new boxes per IP per day are capped; counted without the IP", async () => {
  const env = { ...fresh(), LIVE_NEW_PER_IP_DAY: "3" };
  const now = Date.UTC(2026, 10, 20, 12);
  const claim = (ip, i, t = now) => side(env, WRITE, { t }, ("box" + i).padEnd(22, "x"), { "cf-connecting-ip": ip }).claim();
  for (let i = 0; i < 3; i++) assert.equal((await claim("203.0.113.7", i)).status, 201);
  assert.equal((await claim("203.0.113.7", 1)).status, 200, "the same box again does not count");
  const over = await claim("203.0.113.7", 9);
  assert.equal(over.status, 429);
  assert.equal((await over.json()).code, NEW_BOX_CAP);
  assert.equal(over.headers.get("retry-after"), String(12 * 3600));
  assert.equal(env.SYNC.sqlite.prepare("SELECT COUNT(*) AS n FROM live_box").get().n, 3, "nothing made over the cap");
  assert.equal((await claim("198.51.100.1", 9)).status, 201, "another network has its own count");
  assert.equal((await claim("203.0.113.7", 8, Date.UTC(2026, 10, 21, 0, 1))).status, 201, "a new day starts again");
  const rows = env.SYNC.sqlite.prepare("SELECT * FROM map_quota").all();
  assert.ok(rows.every((r) => /^live:[0-9a-f]{32}$/.test(r.k)));
  assert.ok(!JSON.stringify(rows).includes("203.0.113"));

  const def = fresh();
  for (let i = 0; i < LIMITS.newPerIpDay; i++) assert.equal((await side(def, WRITE, { t: now }, ("d" + i).padEnd(22, "x")).claim()).status, 201);
  assert.equal((await side(def, WRITE, { t: now }, "last".padEnd(22, "x")).claim()).status, 429, "the default cap");
});

test("the per-IP rate limit answers 429 when bound, is skipped when not bound, and lets through when it fails", async () => {
  const base = fresh();
  let key = null;
  const limited = { ...base, LIVE_RATE: { limit: async (o) => { key = o.key; return { success: false }; } } };
  const r = await side(limited, WRITE, { t: T0 }, BOX, { "cf-connecting-ip": "203.0.113.7" }).claim();
  assert.equal(r.status, 429);
  assert.equal(r.headers.get("retry-after"), "60");
  assert.ok(/^[0-9a-f]{32}$/.test(key) && !key.includes("203"), "the limiter never sees the plain IP");
  assert.equal((await side(base, WRITE).claim()).status, 201, "no binding (before the owner's config change): it works");
  const broken = { ...base, LIVE_RATE: { limit: async () => { throw new Error("limiter down"); } } };
  assert.equal((await side(broken, WRITE).get()).status, 200);
});

test("a database error is a readable 503 with retry-after, never a thrown 500; no database bound is a 503 too", async () => {
  const env = fresh();
  await side(env, WRITE).claim();
  const broken = { ...env, SYNC: { prepare: () => { throw new Error("D1_ERROR: overloaded"); } } };
  for (const r of [await side(broken, WRITE).push([item("h")]), await side(broken, READ).get(), await side(broken, WRITE).claim(), await side(broken, WRITE).del()]) {
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("retry-after"), "30");
    assert.ok((await r.json()).error);
  }
  const none = await side({}, READ).get();
  assert.equal(none.status, 503);
  // a failing batch stores nothing half: the revision does not move without its items
  const real = env.SYNC;
  const half = { ...env, SYNC: { ...real, batch: async () => { throw new Error("D1_ERROR: timeout"); } } };
  assert.equal((await side(half, WRITE).push([item("h")])).status, 503);
  assert.equal((await body(await side(env, READ).get())).rev, 0);
});

test("the database holds nothing readable: ids, hashes, numbers and the blobs exactly as given", async () => {
  const env = fresh();
  const phone = side(env, WRITE);
  await phone.claim();
  await phone.push([item("h", 60, "H"), item("r", 90, "R"), item("t0000", 120, "T")], true);
  const boxes = env.SYNC.sqlite.prepare("SELECT * FROM live_box").all().map((r) => ({ ...r }));
  assert.deepEqual(Object.keys(boxes[0]).sort(),
    ["created", "deleted", "ended", "id", "last_write", "pushes_from", "pushes_hour", "read_hash", "rev", "write_hash"]);
  const items = env.SYNC.sqlite.prepare("SELECT * FROM live_item ORDER BY name").all().map((r) => ({ ...r }));
  assert.deepEqual(items, [
    { box: BOX, name: "h", rev: 1, blob: blob(60, "H") },
    { box: BOX, name: "r", rev: 1, blob: blob(90, "R") },
    { box: BOX, name: "t0000", rev: 1, blob: blob(120, "T") },
  ]);
  const tables = env.SYNC.sqlite.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'live%' ORDER BY name").all().map((r) => r.name);
  assert.deepEqual(tables, ["live_box", "live_item"]);
});

test("the host sends /live/v1/ to the box and leaves /live/ to the page; the daily job cleans up", async () => {
  const env = { ...fresh(), ASSETS: { fetch: async () => new Response("page", { headers: { "content-type": "text/html" } }) } };
  const r = await handle(new Request(H + BOX, { method: "POST", body: JSON.stringify({ read: sha(READ) }), headers: { authorization: "Bearer " + WRITE } }), env);
  assert.equal(r.status, 201);
  assert.equal(r.headers.get("cache-control"), "no-store");
  assert.equal(r.headers.get("x-robots-tag"), "noindex, nofollow");
  const page = await handle(new Request("https://plan.example.org/live/"), env);
  assert.equal(await page.text(), "page");
  const mod = await import("../src/index.js");
  env.SYNC.sqlite.prepare("UPDATE live_box SET last_write = 0").run();
  const waits = [];
  mod.default.scheduled({}, env, { waitUntil: (p) => waits.push(p) });
  await Promise.all(waits);
  assert.equal(env.SYNC.sqlite.prepare("SELECT deleted IS NOT NULL AS gone FROM live_box").get().gone, 1, "the scheduled job ran purgeLive");
});
