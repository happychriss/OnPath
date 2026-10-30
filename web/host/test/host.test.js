// The host's routing: map files from R2 (with Range), rd5 passed through from brouter.de, the page from the assets.
import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/index.js";

const files = { "cells/f2/10/543/361/current.json": '{"version":"2026-10-23-s4"}', "cells/f2/10/543/361/2026-10-23-s4/base.pmtiles": "0123456789",
  // a region package (z7 tile, map-cells.md §20): same layout as a cell
  "cells/f2/7/68/44/current.json": '{"version":"2026-10-28-s1"}', "cells/f2/7/68/44/2026-10-28-s1/hillshade.pmtiles": "abcdefghij" };
const bucket = {
  async head(k) { return k in files ? obj(k) : null; },
  async get(k, opts = {}) {
    if (!(k in files)) return null;
    const range = opts.range?.get?.("range");
    const m = range && /bytes=(\d+)-(\d+)/.exec(range);
    const body = m ? files[k].slice(Number(m[1]), Number(m[2]) + 1) : files[k];
    return { ...obj(k), body: new Response(body).body, range: m ? { offset: Number(m[1]), length: Number(m[2]) - Number(m[1]) + 1 } : undefined };
  },
};
const obj = (k) => ({ size: files[k].length, httpEtag: '"e"', writeHttpMetadata() {} });
const env = { MAPS: bucket, ASSETS: { fetch: async (r) => new Response("page " + new URL(r.url).pathname) } };
const H = "https://planer.example.org";

test("map cell file with Range", async () => {
  const r = await handle(new Request(H + "/data/cells/f2/10/543/361/2026-10-23-s4/base.pmtiles", { headers: { range: "bytes=2-4" } }), env);
  assert.equal(r.status, 206);
  assert.equal(await r.text(), "234");
  assert.equal(r.headers.get("content-range"), "bytes 2-4/10");
});

test("current.json is not cached long; missing cell is 404", async () => {
  const r = await handle(new Request(H + "/data/cells/f2/10/543/361/current.json"), env);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("cache-control"), "no-cache");
  assert.equal((await handle(new Request(H + "/data/cells/f2/10/1/1/current.json"), env)).status, 404);
});

test("region package files (z7) are served like cells", async () => {
  const c = await handle(new Request(H + "/data/cells/f2/7/68/44/current.json"), env);
  assert.equal(c.status, 200);
  assert.equal((await c.json()).version, "2026-10-28-s1");
  const r = await handle(new Request(H + "/data/cells/f2/7/68/44/2026-10-28-s1/hillshade.pmtiles", { headers: { range: "bytes=0-2" } }), env);
  assert.equal(r.status, 206);
  assert.equal(await r.text(), "abc");
  assert.equal((await handle(new Request(H + "/data/cells/f2/7/1/1/current.json"), env)).status, 404);
});

test("only cell files are readable from the bucket", async () => {
  for (const p of ["/data/status/f2/x.json", "/data/cells/../secret", "/data/"])
    assert.equal((await handle(new Request(H + p), env)).status, 404, p);
  assert.equal((await handle(new Request(H + "/data/cells/f2/10/543/361/current.json", { method: "PUT", body: "x" }), env)).status, 405);
});

test("rd5 passes through from brouter.de with Range", async () => {
  let seen;
  const fake = async (url, init) => { seen = { url, range: init.headers.get("range") };
    return new Response("abc", { status: 206, headers: { "content-range": "bytes 0-2/200", "content-type": "application/octet-stream" } }); };
  const r = await handle(new Request(H + "/segments/E10_N45.rd5", { headers: { range: "bytes=0-2" } }), env, fake);
  assert.equal(seen.url, "https://brouter.de/brouter/segments4/E10_N45.rd5");
  assert.equal(seen.range, "bytes=0-2");
  assert.equal(r.status, 206);
  assert.equal(r.headers.get("content-range"), "bytes 0-2/200");
});

test("only rd5 file names go upstream", async () => {
  const fake = async () => { throw new Error("must not fetch"); };
  // "../" is resolved by the URL parser before we see it (→ a page path); odd names never reach brouter.de
  for (const p of ["/segments/../../etc", "/segments/x.rd5", "/segments/E10_N45.rd5.bak", "/segments/a/E10_N45.rd5"])
    await handle(new Request(H + p), env, fake); // the fake throws if called
  assert.equal((await handle(new Request(H + "/segments/x.rd5"), env, fake)).status, 404);
});

test("everything else is the page", async () => {
  assert.equal(await (await handle(new Request(H + "/index.html"), env)).text(), "page /index.html");
});

const limiter = (max) => { const n = new Map(); return { async limit({ key }) { n.set(key, (n.get(key) ?? 0) + 1); return { success: n.get(key) <= max }; } }; };

test("per-IP limits for /data/ and /segments/ (A-011): over the limit → 429 with Retry-After, other IPs go on", async () => {
  const e = { ...env, DATA_RATE: limiter(2), SEGMENTS_RATE: limiter(1) };
  const get = (p, ip) => handle(new Request(H + p, { headers: { "cf-connecting-ip": ip } }), e, async () => new Response("rd5"));
  const file = "/data/cells/f2/10/543/361/current.json";
  assert.equal((await get(file, "1.1.1.1")).status, 200);
  assert.equal((await get(file, "1.1.1.1")).status, 200);
  const r = await get(file, "1.1.1.1");
  assert.equal(r.status, 429);
  assert.equal(r.headers.get("retry-after"), "60");
  assert.equal((await get(file, "2.2.2.2")).status, 200);
  assert.equal((await get("/segments/E10_N45.rd5", "1.1.1.1")).status, 200, "segments count on their own");
  assert.equal((await get("/segments/E10_N45.rd5", "1.1.1.1")).status, 429);
  assert.equal((await get("/index.html", "1.1.1.1")).status, 200, "the page itself is not limited");
});

test("search engines kept out until launch: X-Robots-Tag on every answer; INDEXING = on drops it", async () => {
  for (const p of ["/", "/data/cells/f2/10/543/361/current.json", "/sync/v1/x"]) {
    assert.equal((await handle(new Request(H + p), env)).headers.get("x-robots-tag"), "noindex, nofollow", p);
  }
  assert.equal((await handle(new Request(H + "/"), { ...env, INDEXING: "on" })).headers.get("x-robots-tag"), null);
});
