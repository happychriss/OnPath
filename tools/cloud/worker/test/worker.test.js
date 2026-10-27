// node --test  (Node ≥ 24 as in CI – node:sqlite of 22.14 rejects the ?1 parameters) – no npm packages needed.
import { test } from "node:test";
import assert from "node:assert/strict";
import { verify } from "node:crypto";

import { blockOf, cellBbox, parseCellId } from "../src/cells.js";
import { config } from "../src/config.js";
import * as gcp from "../src/gcp.js";
import { handle } from "../src/api.js";
import { pump, requestCells, schemaOf, statusFor } from "../src/queue.js";
import worker from "../src/index.js";
import { fakeGoogle, serviceAccountKey, testEnv } from "./fakes.js";

const KEY = "app-key-0123456789";
const MERAN = "10-543-361";
const BOZEN = "10-544-361";

function req(path, { method = "GET", body, key = KEY, headers = {} } = {}) {
  const h = { "cf-connecting-ip": "1.2.3.4", ...headers };
  if (key) h["x-onpath-key"] = key;
  if (body !== undefined) h["content-type"] = "application/json";
  return new Request(`https://maps-test.example${path}`, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
}

const ctx = () => {
  const p = [];
  return { waitUntil: (x) => p.push(x), done: () => Promise.all(p) };
};

async function putStatus(env, id, s, format = 2) {
  await env.BUCKET.put(`status/f${format}/${id}.json`, JSON.stringify({ cell: id, format, ...s }));
}

async function publish(env, id, version = "2026-10-19") {
  const [z, x, y] = id.split("-");
  const base = `cells/f2/${z}/${x}/${y}`;
  await env.BUCKET.put(`${base}/${version}/base.pmtiles`, new Uint8Array(1000).map((_, i) => i % 256));
  await env.BUCKET.put(`${base}/${version}/manifest.json`, JSON.stringify({ cell: id, files: [{ name: "base.pmtiles", size: 1000 }] }));
  await env.BUCKET.put(`${base}/current.json`, JSON.stringify({ version }));
}

// ------------------------------------------------------------------------------------------ cells
test("cell bbox matches cells.py (Meran example, map-cells.md §2)", () => {
  const b = cellBbox(10, 543, 361);
  [10.8984, 46.5589, 11.25, 46.8001].forEach((v, i) => assert.ok(Math.abs(b[i] - v) < 1e-4, `${i}: ${b[i]}`));
});

test("cell ids", () => {
  assert.deepEqual(parseCellId("10/543/361"), { z: 10, x: 543, y: 361, id: MERAN });
  for (const bad of ["11-1-1", "10-1024-0", "x", "", null, "10-1-1-1"]) assert.equal(parseCellId(bad), null, String(bad));
});

test("z7 block of a cell", () => {
  assert.equal(blockOf(parseCellId(MERAN)), "67-45");
  assert.equal(blockOf(parseCellId(BOZEN)), "68-45");
});

// ------------------------------------------------------------------------------------------ google auth
test("JWT is RS256-signed with the service-account key", async () => {
  const k = serviceAccountKey();
  const jwt = await gcp.signedAssertion(JSON.parse(k.json), 1_700_000_000);
  const [h, c, s] = jwt.split(".");
  const claims = JSON.parse(Buffer.from(c, "base64url"));
  assert.equal(claims.iss, "doorman-invoker-test@proj.iam.gserviceaccount.com");
  assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
  assert.equal(claims.exp - claims.iat, 3600);
  assert.equal(JSON.parse(Buffer.from(h, "base64url")).alg, "RS256");
  assert.ok(verify("sha256", Buffer.from(`${h}.${c}`), k.publicKey, Buffer.from(s, "base64url")));
});

test("execution state", () => {
  assert.deepEqual(gcp.executionState({ taskCount: 1 }), { done: false, succeeded: false });
  assert.deepEqual(gcp.executionState({ taskCount: 1, completionTime: "x", succeededCount: 1 }), { done: true, succeeded: true });
  assert.deepEqual(gcp.executionState({ conditions: [{ type: "Completed", state: "CONDITION_FAILED" }], failedCount: 1 }),
    { done: true, succeeded: false });
});

// ------------------------------------------------------------------------------------------ API
test("health needs no key; everything else does", async () => {
  const env = testEnv();
  const h = await handle(req("/v1/health", { key: null }), env);
  assert.equal(h.status, 200);
  assert.equal((await h.json()).env, "test");
  assert.equal((await handle(req(`/v1/cells/status?ids=${MERAN}`, { key: null }), env)).status, 401);
  assert.equal((await handle(req(`/v1/cells/status?ids=${MERAN}`, { key: "wrong" }), env)).status, 401);
  assert.equal((await handle(req(`/v1/cells/status?ids=${MERAN}`, { key: "old-key-0123456789" }), env)).status, 200);
  const noKeys = testEnv({ APP_KEYS: "" });
  assert.equal((await handle(req(`/v1/cells/status?ids=${MERAN}`), noKeys)).status, 503);
});

test("request → queued, deduped, build starts, cell ready, manifest and file with Range", async () => {
  const env = testEnv({ PARALLEL_BUILDS: "1" }); // one builder: both cells in one execution
  const g = fakeGoogle();
  const c = ctx();
  let r = await handle(req("/v1/cells/request", { method: "POST", body: { cells: [MERAN, BOZEN], reason: "point", formats: [2] } }), env, c, g);
  assert.equal(r.status, 200);
  let j = await r.json();
  assert.deepEqual(j.cells.map((x) => x.state), ["queued", "queued"]);
  await c.done(); // the pump started one execution for both cells
  const runs = g.calls.filter((x) => x.url.endsWith(":run"));
  assert.equal(runs.length, 1);
  const override = JSON.parse(runs[0].body).overrides.containerOverrides[0].env;
  assert.deepEqual(override, [{ name: "CELLS", value: `${MERAN} ${BOZEN}` }, { name: "FORMAT", value: "2" }]);

  // same request again: joins the running job, no second execution
  const c2 = ctx();
  r = await handle(req("/v1/cells/request", { method: "POST", body: { cells: [MERAN], reason: "near" } }), env, c2, g);
  j = await r.json();
  assert.equal(j.cells[0].state, "building");
  assert.equal(j.poll_s, 15);
  await c2.done();
  assert.equal(g.calls.filter((x) => x.url.endsWith(":run")).length, 1);

  // builder progress via the status file
  const [exName] = [...g.executions.keys()];
  const short = gcp.shortName(exName);
  await putStatus(env, MERAN, { state: "building", step: "base", progress: 0.1, execution: short });
  j = await (await handle(req(`/v1/cells/status?ids=${MERAN},${BOZEN}`), env)).json();
  assert.equal(j.cells[0].step, "base");
  assert.equal(j.cells[0].progress, 0.1);
  assert.equal(j.cells[1].step, "starting");

  // Meran done while the execution still runs → published at once by the cron pump
  await publish(env, MERAN);
  await putStatus(env, MERAN, { state: "ready", version: "2026-10-19", osm_date: "2026-10-19", size: 1000, execution: short });
  const cfg = config(env);
  await pump(env, cfg, Date.now(), g);
  j = (await statusFor(env, cfg, [MERAN, BOZEN], 2));
  assert.deepEqual(j.cells.map((x) => x.state), ["ready", "building"]);

  // execution ends without a status for Bozen → failed attempt, queued again, next execution starts
  g.executions.get(exName).completionTime = "now";
  g.executions.get(exName).failedCount = 1;
  await pump(env, cfg, Date.now(), g);
  const row = await env.DB.prepare("SELECT job, attempts, error FROM cells WHERE id = ?").bind(BOZEN).first();
  assert.equal(row.attempts, 1);
  assert.equal(row.error, "Build stopped unexpectedly");
  assert.equal(row.job, "building"); // re-queued and started again in the same pump
  assert.equal(g.calls.filter((x) => x.url.endsWith(":run")).length, 2);

  // manifest + file
  r = await handle(req("/v1/cells/10/543/361/manifest?format=2"), env);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).cell, MERAN);
  assert.equal((await handle(req("/v1/cells/10/544/361/manifest"), env)).status, 404);
  assert.equal((await handle(req("/v1/cells/10/543/361/manifest?format=1"), env)).status, 404); // f1 not served
  r = await handle(req("/v1/files/cells/f2/10/543/361/2026-10-19/base.pmtiles", { headers: { range: "bytes=10-19" } }), env);
  assert.equal(r.status, 206);
  assert.equal(r.headers.get("content-range"), "bytes 10-19/1000");
  assert.match(r.headers.get("cache-control"), /immutable/);
  assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [10, 11, 12, 13, 14, 15, 16, 17, 18, 19]);
  r = await handle(req("/v1/files/cells/f2/10/543/361/2026-10-19/base.pmtiles"), env);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("content-length"), "1000");
  const etag = r.headers.get("etag");
  r = await handle(req("/v1/files/cells/f2/10/543/361/2026-10-19/base.pmtiles", { headers: { "if-none-match": etag } }), env);
  assert.equal(r.status, 304);
  r = await handle(req("/v1/files/cells/f2/10/543/361/2026-10-19/base.pmtiles", { method: "HEAD" }), env);
  assert.equal(r.headers.get("content-length"), "1000");
  assert.equal((await handle(req("/v1/files/cells/f2/../../status/f2/x.json"), env)).status, 404);
  assert.equal((await handle(req("/v1/files/status/f2/10-543-361.json"), env)).status, 404);
});

test("permanent failure and retries are limited", async () => {
  const env = testEnv({ MAX_ATTEMPTS: "2" });
  const cfg = config(env);
  const g = fakeGoogle();
  await requestCells(env, cfg, { cells: [MERAN] });
  for (let i = 0; i < 2; i++) {
    await pump(env, cfg, Date.now(), g);
    const ex = [...g.executions.values()].at(-1);
    await putStatus(env, MERAN, { state: "failed", reason: "Build step 'base' failed", execution: gcp.shortName(ex.name) });
    ex.completionTime = "now";
    ex.succeededCount = 1;
    await pump(env, cfg, Date.now(), g);
  }
  let j = await statusFor(env, cfg, [MERAN], 2);
  assert.deepEqual(j.cells[0], { id: MERAN, state: "failed", reason: "Build step 'base' failed" });
  // asking again does not queue it (same pipeline revision) ...
  j = await requestCells(env, cfg, { cells: [MERAN] });
  assert.equal(j.cells[0].state, "failed");
  // ... a new deploy (BUILD_REV) does
  j = await requestCells(env, config({ ...env, BUILD_REV: "rev2" }), { cells: [MERAN] });
  assert.equal(j.cells[0].state, "queued");
});

test("any cell is accepted (no static coverage); open sea is the builder's permanent answer", async () => {
  const env = testEnv({ PARALLEL_BUILDS: "1" });
  const cfg = config(env);
  const g = fakeGoogle();
  const SWISS = "10-536-358";
  const SEA = "10-398-405";
  const j = await requestCells(env, cfg, { cells: [SWISS, SEA] });
  assert.deepEqual(j.cells.map((x) => x.state), ["queued", "queued"]);
  await pump(env, cfg, Date.now(), g);
  const [ex] = [...g.executions.values()];
  await putStatus(env, SEA, { state: "failed", reason: "No map data exists for this area (open sea)", permanent: true,
    execution: gcp.shortName(ex.name) });
  ex.completionTime = "now";
  ex.succeededCount = 1;
  await pump(env, cfg, Date.now(), g);
  const s = (await statusFor(env, cfg, [SEA], 2)).cells[0];
  assert.deepEqual(s, { id: SEA, state: "failed", reason: "No map data exists for this area (open sea)" });
  assert.equal((await requestCells(env, cfg, { cells: [SEA] })).cells[0].state, "failed"); // not queued again
});

const INNSBRUCK = "10-544-359"; // two rows north of Bozen: not a neighbour of Meran / Bozen

test("ETA: queue simulation with measured build and download times", async () => {
  const env = testEnv({ CELLS_PER_EXECUTION: "1", PARALLEL_BUILDS: "1", ETA_SMOOTH_S: "0" });
  const cfg = config(env);
  const g = fakeGoogle();
  const now = Date.now();
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
      VALUES (?, 2, '2026-10-20-s2', '2026-10-20', 5, 3, 1)`).bind(MERAN).run();
  // no measured builds yet: configured guesses; Bozen is next to Meran (extract cached), Innsbruck needs a download
  let j = await requestCells(env, cfg, { cells: [BOZEN, INNSBRUCK] }, now);
  const one = cfg.startupS + cfg.avgCellS;
  assert.deepEqual(j.cells.map((c) => [c.state, c.position, c.eta_s]),
    [["queued", 1, one], ["queued", 2, 2 * one + cfg.pumpLagS + cfg.newAreaS]]);
  // measured: cached builds 200 s, builds with a new extract 300 s, 100 MB in 20 s → download 100 / 5 + 15 = 35 s
  for (const [b, ns, ds, mb] of [[200, 0, 0, 0], [200, 0, 0, 0], [300, 1, 20, 100], [200, 0, 0, 0]]) {
    await env.DB.prepare(`INSERT INTO builds (id, format, finished_at, build_s, new_source, download_s, download_mb)
        VALUES ('x', 2, ?, ?, ?, ?, ?)`).bind(now - 1000, b, ns, ds, mb).run();
  }
  j = await statusFor(env, cfg, [BOZEN, INNSBRUCK], 2, now);
  assert.deepEqual(j.cells.map((c) => c.eta_s), [cfg.startupS + 200, cfg.startupS + 200 + cfg.pumpLagS + cfg.startupS + 35 + 300]);
  await pump(env, cfg, now, g); // starts Bozen
  const [ex] = [...g.executions.values()];
  const short = gcp.shortName(ex.name);
  j = await statusFor(env, cfg, [BOZEN], 2, now + 30_000); // before the builder's first status: startup left
  assert.equal(j.cells[0].eta_s, cfg.startupS - 30 + 200);
  await putStatus(env, BOZEN, { state: "building", step: "download", progress: 0, source_eta_s: 400, execution: short });
  j = await statusFor(env, cfg, [BOZEN, INNSBRUCK], 2, now);
  assert.equal(j.cells[0].eta_s, 400 + 300); // builder's own download estimate + a new-extract build
  assert.equal(j.cells[0].step, "download");
  await putStatus(env, BOZEN, { state: "building", step: "base", progress: 0.5, execution: short });
  j = await statusFor(env, cfg, [BOZEN, INNSBRUCK], 2, now);
  assert.equal(j.cells[0].eta_s, 100);
  assert.equal(j.cells[1].eta_s, 100 + cfg.pumpLagS + cfg.startupS + 35 + 300); // after Bozen's slot frees
  // published by the builder, not yet recorded by the pump: almost there (not "starting" again – TEST 2026-10-21)
  await putStatus(env, BOZEN, { state: "ready", version: "2026-10-21-s2", execution: short });
  j = await statusFor(env, cfg, [BOZEN], 2, now);
  assert.deepEqual([j.cells[0].step, j.cells[0].progress, j.cells[0].eta_s], ["upload", 1, cfg.pumpLagS]);
});

test("ETA is smoothed: a new estimate is reached over ETA_SMOOTH_S, not in one jump", async () => {
  const env = testEnv({ PARALLEL_BUILDS: "1", ETA_SMOOTH_S: "60" });
  const cfg = config(env);
  const now = Date.now();
  let j = await requestCells(env, cfg, { cells: [MERAN] }, now);
  const first = j.cells[0].eta_s;
  assert.equal(first, cfg.startupS + cfg.avgCellS + cfg.newAreaS);
  // measurements arrive: the raw estimate drops by far
  await env.DB.prepare(`INSERT INTO builds (id, format, finished_at, build_s, new_source, download_s, download_mb)
      VALUES ('x', 2, ?, 100, 0, 0, 0)`).bind(now).run();
  j = await statusFor(env, cfg, [MERAN], 2, now + 15_000);
  const raw = cfg.startupS + 100 + cfg.newAreaS; // still queued, nothing running: starts "now" at every call
  const was = first - 15;
  assert.equal(j.cells[0].eta_s, Math.round(was + 0.25 * (raw - was))); // a quarter of the way after 15 s
  j = await statusFor(env, cfg, [MERAN], 2, now + 15_000 + 61_000);
  assert.equal(j.cells[0].eta_s, raw); // fully there after a minute
});

test("priority + parallel builds: waiting cells first and spread, background never takes the last slot", async () => {
  const env = testEnv(); // PARALLEL_BUILDS 4, CELLS_PER_EXECUTION 4
  const cfg = config(env);
  const g = fakeGoogle();
  const t0 = Date.now();
  const ids = (k) => Array.from({ length: k }, (_, i) => `10-${600 + i}-${300 + k}`);
  const [r1, r2] = ids(2); // background, asked first
  const [p1, p2, p3] = ids(3);
  await requestCells(env, cfg, { cells: [r1, r2], reason: "refresh" }, t0);
  await requestCells(env, cfg, { cells: ["10-700-300"], reason: "test" }, t0);
  await requestCells(env, cfg, { cells: [p1, p2, p3], reason: "point" }, t0 + 1000);
  let r = await pump(env, cfg, t0 + 2000, g);
  const runs = () => g.calls.filter((x) => x.url.endsWith(":run")).map((x) => JSON.parse(x.body).overrides.containerOverrides[0].env[0].value);
  assert.deepEqual(runs(), [p1, p2, p3]); // one cell per execution, in parallel
  assert.equal(r.running, 3);
  const job = async (id) => (await env.DB.prepare("SELECT job FROM cells WHERE id = ?").bind(id).first()).job;
  assert.equal(await job(r1), "queued"); // the 4th slot stays free for someone waiting
  // someone else waits: takes the free slot at once
  await requestCells(env, cfg, { cells: ["10-650-300"], reason: "near" }, t0 + 3000);
  await pump(env, cfg, t0 + 4000, g);
  assert.deepEqual(runs().slice(3), ["10-650-300"]);
  // one build ends → 1 free slot: background still waits; two free → the background batch (refresh before test)
  const done = async (k) => {
    const ex = [...g.executions.values()][k];
    await putStatus(env, runs()[k], { state: "ready", version: "2026-10-21-s2", execution: gcp.shortName(ex.name) });
    ex.completionTime = "now";
    ex.succeededCount = 1;
  };
  await done(0);
  r = await pump(env, cfg, t0 + 5000, g);
  assert.equal(runs().length, 4);
  await done(1);
  r = await pump(env, cfg, t0 + 6000, g);
  assert.deepEqual(runs().slice(4), [`${r1} ${r2} 10-700-300`]);
  // the status of a waiting cell queued behind running builds: eta from the earliest freeing slot
  const s = await statusFor(env, cfg, [p3], 2, t0 + 6000);
  assert.equal(s.cells[0].state, "building");
});

test("Google refuses a parallel start → one builder at a time for a while", async () => {
  const env = testEnv();
  const cfg = config(env);
  const g = fakeGoogle();
  g.failRunAfter = 1;
  const now = Date.now();
  await requestCells(env, cfg, { cells: [MERAN, INNSBRUCK], reason: "point" }, now);
  await pump(env, cfg, now, g);
  const blocked = await env.DB.prepare("SELECT until FROM meta WHERE key = 'parallel-blocked'").first();
  assert.ok(blocked.until > now);
  const before = g.calls.filter((x) => x.url.endsWith(":run")).length;
  await pump(env, cfg, now + 60_000, g); // one is running: no further start tried
  assert.equal(g.calls.filter((x) => x.url.endsWith(":run")).length, before);
  const j = await statusFor(env, cfg, [INNSBRUCK], 2, now + 60_000);
  assert.equal(j.cells[0].state, "queued");
});

test("a ready cell's measured times go into the rolling averages", async () => {
  const env = testEnv({ PARALLEL_BUILDS: "1" });
  const cfg = config(env);
  const g = fakeGoogle();
  await requestCells(env, cfg, { cells: [MERAN] });
  await pump(env, cfg, Date.now(), g);
  const [ex] = [...g.executions.values()];
  await publish(env, MERAN);
  await putStatus(env, MERAN, { state: "ready", version: "2026-10-19-s2", osm_date: "2026-10-19", size: 1000,
    execution: gcp.shortName(ex.name), build_s: 250.5, new_source: true, download_s: 31, download_mb: 420 });
  await pump(env, cfg, Date.now(), g);
  const b = await env.DB.prepare("SELECT build_s, new_source, download_s, download_mb FROM builds").first();
  assert.deepEqual({ ...b }, { build_s: 250.5, new_source: 1, download_s: 31, download_mb: 420 });
});

test("cost guard: executions per hour", async () => {
  const env = testEnv({ MAX_EXEC_PER_HOUR: "1", CELLS_PER_EXECUTION: "1" });
  const cfg = config(env);
  const g = fakeGoogle();
  await requestCells(env, cfg, { cells: [MERAN, BOZEN] });
  await pump(env, cfg, Date.now(), g);
  const ex = [...g.executions.values()][0];
  ex.completionTime = "now";
  ex.succeededCount = 1;
  const r = await pump(env, cfg, Date.now(), g);
  assert.equal(r.guard, true);
  assert.equal(g.calls.filter((x) => x.url.endsWith(":run")).length, 1);
});

test("a refused start is counted and the cells stay queued", async () => {
  const env = testEnv({ MAX_EXEC_PER_HOUR: "2" });
  const cfg = config(env);
  const g = fakeGoogle();
  g.failRun = true;
  await requestCells(env, cfg, { cells: [MERAN] });
  assert.ok((await pump(env, cfg, Date.now(), g)).error);
  assert.ok((await pump(env, cfg, Date.now(), g)).error);
  assert.equal((await pump(env, cfg, Date.now(), g)).guard, true);
  assert.equal((await statusFor(env, cfg, [MERAN], 2)).cells[0].state, "queued");
});

test("validation and limits", async () => {
  const env = testEnv({ MAX_CELLS_PER_REQUEST: "2", RATE_LIMIT_PER_MIN: "3" });
  const post = (body) => handle(req("/v1/cells/request", { method: "POST", body }), env, ctx(), fakeGoogle());
  assert.equal((await post({ cells: [MERAN, BOZEN, "10-545-361"] })).status, 400);
  assert.equal((await post({ cells: ["nope"] })).status, 400);
  assert.equal((await post({ cells: [MERAN], reason: "fun" })).status, 400);
  // 4th call in the same minute from this IP
  assert.equal((await post({ cells: [MERAN] })).status, 429);
});

test("old ready cell: answered ready (+ updating) and a low-priority refresh is queued", async () => {
  const env = testEnv();
  const cfg = config(env);
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
      VALUES (?, 2, '2026-01-01-s3', '2026-01-01', 5, 3, 1)`).bind(MERAN).run();
  const j = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-20"));
  assert.equal(j.cells[0].state, "ready");
  assert.equal(j.cells[0].updating, true);
  assert.equal(j.poll_s, 15); // the app keeps asking at this pace while the refresh builds (§7h)
  const row = await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(MERAN).first();
  assert.deepEqual({ ...row }, { job: "queued", priority: 3 });
});

test("cell of an older content schema: rebuilt with the asked priority, not served as ready meanwhile", async () => {
  const env = testEnv();
  const cfg = config(env);
  assert.equal(schemaOf("2026-10-20"), 1);
  assert.equal(schemaOf("2026-10-20-s2"), 2);
  // published on the same day (not OSM-stale) but before the "streets" layer existed (plain OSM-date version)
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
      VALUES (?, 2, '2026-10-19', '2026-10-19', 5, 3, 1)`).bind(MERAN).run();
  const j = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-21"));
  assert.equal(j.cells[0].state, "queued");
  const row = await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(MERAN).first();
  assert.deepEqual({ ...row }, { job: "queued", priority: 0 });
  // a current-schema cell is just touched
  await env.DB.prepare("UPDATE cells SET version = '2026-10-19-s7', job = NULL WHERE id = ?").bind(MERAN).run();
  const k = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-21"));
  assert.equal(k.cells[0].state, "ready");
  assert.equal(k.cells[0].updating, undefined);
  assert.equal(k.poll_s, 60); // nothing being built for it
});

test("cell of an older but servable schema (s3, no POI priority): rebuilt, served as ready meanwhile", async () => {
  const env = testEnv();
  const cfg = config(env);
  assert.equal(cfg.cellSchema, 7);
  assert.equal(cfg.cellSchemaServe, 3);
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
      VALUES (?, 2, '2026-10-19-s3', '2026-10-19', 5, 3, 1)`).bind(MERAN).run();
  const j = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-21"));
  assert.equal(j.cells[0].state, "ready");
  assert.equal(j.cells[0].version, "2026-10-19-s3");
  assert.equal(j.cells[0].updating, true);
  const row = await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(MERAN).first();
  assert.deepEqual({ ...row }, { job: "queued", priority: 3 }); // a refresh: the old map works meanwhile
});

for (const [old, why] of [["s4", "router classes of the old bike profiles"], ["s5", "no town flag / path links"],
    ["s6", "no cycle network filter / route labels / bicycle streets / footbridges"]]) {
  test(`cell of schema ${old.slice(1)} (${why}): rebuilt to s7, served as ready meanwhile`, async () => {
    const env = testEnv();
    const cfg = config(env);
    await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
        VALUES (?, 2, '2026-10-19-${old}', '2026-10-19', 5, 3, 1)`).bind(MERAN).run();
    const j = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-21"));
    assert.equal(j.cells[0].state, "ready");
    assert.equal(j.cells[0].version, `2026-10-19-${old}`);
    assert.equal(j.cells[0].updating, true);
    const row = await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(MERAN).first();
    assert.deepEqual({ ...row }, { job: "queued", priority: 3 });
  });
}

// ------------------------------------------------------------------------------------------------ fast lane (§7h)
/** What tools/dev/publish_cells.sh (job_build.py publish) leaves in R2: files, manifest, current.json. */
async function publishCurrent(env, id, version, osmDate, size = 4321) {
  const [z, x, y] = id.split("-");
  const base = `cells/f2/${z}/${x}/${y}`;
  await env.BUCKET.put(`${base}/${version}/base.pmtiles`, new Uint8Array(size));
  await env.BUCKET.put(`${base}/${version}/manifest.json`, JSON.stringify({ cell: id, format: 2, version, osm_date: osmDate,
    total_size: size, files: [{ name: "base.pmtiles", size }] }));
  await env.BUCKET.put(`${base}/current.json`, JSON.stringify({ version }));
}

const NOW = Date.parse("2026-10-26T12:00:00Z");
const row = async (env, id) => ({ ...(await env.DB.prepare("SELECT version, osm_date, size, job FROM cells WHERE id = ?").bind(id).first()) });

test("fast lane: a current version published outside a job is taken at request time (s6 → s7, no build)", async () => {
  const env = testEnv();
  const cfg = config(env);
  const g = fakeGoogle();
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
      VALUES (?, 2, '2026-10-20-s6', '2026-10-20', 5, 3, 1)`).bind(MERAN).run();
  await publishCurrent(env, MERAN, "2026-10-25-s7", "2026-10-25");
  await publishCurrent(env, BOZEN, "2026-10-25-s7", "2026-10-25"); // never asked for before
  const j = await requestCells(env, cfg, { cells: [MERAN, BOZEN], reason: "refresh" }, NOW);
  for (const c of j.cells) {
    assert.equal(c.state, "ready", c.id);
    assert.equal(c.version, "2026-10-25-s7");
    assert.equal(c.updating, undefined);
  }
  assert.deepEqual(await row(env, MERAN), { version: "2026-10-25-s7", osm_date: "2026-10-25", size: 4321, job: null });
  assert.deepEqual(await row(env, BOZEN), { version: "2026-10-25-s7", osm_date: "2026-10-25", size: 4321, job: null });
  await pump(env, cfg, NOW + 1000, g);
  assert.equal(g.calls.filter((x) => x.url.endsWith(":run")).length, 0); // nothing to build
});

test("fast lane: an old-schema or OSM-old published version is not taken – the cell is built", async () => {
  const env = testEnv();
  const cfg = config(env);
  await publishCurrent(env, MERAN, "2026-10-25-s5", "2026-10-25"); // older schema
  await publishCurrent(env, BOZEN, "2026-01-02-s7", "2026-01-02"); // older than REFRESH_DAYS
  const j = await requestCells(env, cfg, { cells: [MERAN, BOZEN], reason: "point" }, NOW);
  assert.deepEqual(j.cells.map((c) => c.state), ["queued", "queued"]);
  assert.equal((await row(env, MERAN)).job, "queued");
});

test("fast lane: the pump takes a version published while the cell waited in the queue, not one being built", async () => {
  const env = testEnv({ PARALLEL_BUILDS: "1" });
  const cfg = config(env);
  const g = fakeGoogle();
  const busy = "10-600-300";
  await requestCells(env, cfg, { cells: [busy], reason: "point" }, NOW);
  await pump(env, cfg, NOW + 1000, g); // the only slot builds `busy`
  await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, NOW + 2000);
  assert.equal((await row(env, MERAN)).job, "queued");
  await publishCurrent(env, MERAN, "2026-10-25-s7", "2026-10-25");
  await publishCurrent(env, busy, "2026-10-25-s7", "2026-10-25");
  await pump(env, cfg, NOW + 3000, g);
  assert.deepEqual(await row(env, MERAN), { version: "2026-10-25-s7", osm_date: "2026-10-25", size: 4321, job: null });
  assert.equal((await row(env, busy)).job, "building"); // the running job finishes and publishes itself
  const s = await statusFor(env, cfg, [MERAN], 2, NOW + 4000);
  assert.equal(s.cells[0].state, "ready");
  // also while builds are switched off
  const off = testEnv({ BUILDS_ENABLED: "false" });
  await requestCells(off, config(off), { cells: [BOZEN], reason: "point" }, NOW);
  await publishCurrent(off, BOZEN, "2026-10-25-s7", "2026-10-25");
  await pump(off, config(off), NOW + 1000, g);
  assert.equal((await row(off, BOZEN)).version, "2026-10-25-s7");
});

// ------------------------------------------------------------------------------------------------ background lane
test("background rebuilds are spread over the free slots (6 refresh cells → 3 × 2), one slot stays free", async () => {
  const env = testEnv(); // PARALLEL_BUILDS 4, CELLS_PER_EXECUTION 4
  const cfg = config(env);
  const g = fakeGoogle();
  const six = Array.from({ length: 6 }, (_, i) => `10-${530 + (i % 3)}-${341 + Math.floor(i / 3)}`);
  await requestCells(env, cfg, { cells: six, reason: "refresh" }, NOW);
  const r = await pump(env, cfg, NOW + 1000, g);
  const runs = g.calls.filter((x) => x.url.endsWith(":run")).map((x) => JSON.parse(x.body).overrides.containerOverrides[0].env[0].value.split(" "));
  assert.deepEqual(runs.map((x) => x.length), [2, 2, 2]); // was 4 + 2, built one after the other
  assert.deepEqual(runs.flat().sort(), [...six].sort());
  assert.equal(r.running, 3);
  // a long background queue still goes 4 per execution (the hourly cap counts executions)
  const env2 = testEnv();
  const g2 = fakeGoogle();
  const many = Array.from({ length: 20 }, (_, i) => `10-${600 + i}-300`);
  await requestCells(env2, config(env2), { cells: many, reason: "refresh" }, NOW);
  await pump(env2, config(env2), NOW + 1000, g2);
  assert.deepEqual(g2.calls.filter((x) => x.url.endsWith(":run")).map((x) => JSON.parse(x.body).overrides.containerOverrides[0].env[0].value.split(" ").length), [4, 4, 4]);
});

test("pre-warm: after a schema bump the recently used cells of an older schema are rebuilt once, in the background", async () => {
  const env = testEnv({ BUILDS_ENABLED: "true", PARALLEL_BUILDS: "1" });
  const cfg = config(env);
  const g = fakeGoogle();
  const ins = (id, version, lastRequest, job = null) => env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date,
      size, job, priority, last_request, requests) VALUES (?, 2, ?, '2026-10-20', 5, ?, 0, ?, 1)`).bind(id, version, job, lastRequest).run();
  await ins("10-530-341", "2026-10-20-s5", NOW - 2 * 86_400_000);  // used 2 days ago, old schema → rebuilt
  await ins("10-531-341", "2026-10-20-s5", NOW - 40 * 86_400_000); // not used for 40 days → left alone
  await ins("10-532-341", "2026-10-20-s7", NOW - 86_400_000);      // current → left alone
  await ins("10-533-341", "2026-10-20-s5", NOW - 86_400_000, "failed");
  await pump(env, cfg, NOW, g);
  const st = async (id) => ({ ...(await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(id).first()) });
  assert.ok(["queued", "building"].includes((await st("10-530-341")).job));
  assert.equal((await st("10-530-341")).priority, 3); // refresh: never ahead of someone waiting
  assert.equal((await st("10-531-341")).job, null);
  assert.equal((await st("10-532-341")).job, null);
  assert.equal((await st("10-533-341")).job, "failed");
  // once per schema: the same old cell is not queued again by later pumps
  await env.DB.prepare("UPDATE cells SET job = NULL WHERE id = '10-530-341'").run();
  await pump(env, cfg, NOW + 60_000, g);
  assert.equal((await st("10-530-341")).job, null);
  // a queue too full for all of them: the rest at the next pump (MAX_QUEUE)
  const small = testEnv({ MAX_QUEUE: "1", BUILDS_ENABLED: "true", PARALLEL_BUILDS: "1" });
  for (const id of ["10-540-341", "10-541-341"]) {
    await small.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, last_request, requests)
        VALUES (?, 2, '2026-10-20-s5', '2026-10-20', 5, 0, ?, 1)`).bind(id, NOW - 86_400_000).run();
  }
  await pump(small, config(small), NOW, fakeGoogle());
  const n = await small.DB.prepare("SELECT COUNT(*) AS n FROM cells WHERE job IS NOT NULL").first();
  assert.equal(n.n, 1);
  assert.equal(await small.DB.prepare("SELECT value FROM meta WHERE key = 'prewarm-schema'").first(), null);
  // off
  const off = testEnv({ PREWARM_DAYS: "0" });
  await off.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, last_request, requests)
      VALUES ('10-550-341', 2, '2026-10-20-s5', '2026-10-20', 5, 0, ?, 1)`).bind(NOW).run();
  await pump(off, config(off), NOW, fakeGoogle());
  assert.equal((await off.DB.prepare("SELECT job FROM cells").first()).job, null);
});

test("scheduled handler runs the pump", async () => {
  const env = testEnv({ BUILDS_ENABLED: "false" });
  const c = ctx();
  await worker.scheduled({}, env, c);
  await c.done();
});

// ------------------------------------------------------------------------------------------------ storage cleanup
import { cleanupDue, cleanupStorage } from "../src/cleanup.js";

test("cleanup: format 1 goes, format 2 keeps current + previous, older versions after 7 days", async () => {
  const env = testEnv();
  const now = Date.parse("2026-11-01T03:17:00Z");
  const old = now - 10 * 86_400_000;
  const fresh = now - 2 * 86_400_000;
  const B = env.BUCKET;
  await B.put("cells/f1/10/543/361/2026-10-17/base.pmtiles", "x", { _uploaded: old });
  await B.put("status/f1/10-543-361.json", "{}", { _uploaded: old });
  await env.DB.prepare("INSERT INTO cells (id, format, version) VALUES (?, 1, '2026-10-17')").bind(MERAN).run();
  const base = "cells/f2/10/543/361";
  for (const [v, t] of [["2026-10-17", old], ["2026-10-19-s2", old], ["2026-10-20-s2", fresh], ["2026-10-21-s2", fresh]]) {
    await B.put(`${base}/${v}/base.pmtiles`, "x", { _uploaded: t });
    await B.put(`${base}/${v}/manifest.json`, "{}", { _uploaded: t });
  }
  await B.put(`${base}/current.json`, JSON.stringify({ version: "2026-10-21-s2" }));
  // a cell with only two versions: nothing to do
  await B.put("cells/f2/10/544/361/2026-10-17/base.pmtiles", "x", { _uploaded: old });
  await B.put("cells/f2/10/544/361/2026-10-19-s2/base.pmtiles", "x", { _uploaded: old });
  await B.put("cells/f2/10/544/361/current.json", JSON.stringify({ version: "2026-10-19-s2" }));

  assert.equal(cleanupDue(now), true);
  assert.equal(cleanupDue(now + 60_000), false);
  const r = await cleanupStorage(env, now);
  assert.deepEqual(r, { f1Objects: 2, f1Rows: 1, oldVersions: 2, oldObjects: 4 });
  const keys = [...B.objects.keys()].sort();
  assert.ok(!keys.some((k) => k.includes("/f1/")));
  assert.ok(keys.includes(`${base}/2026-10-21-s2/base.pmtiles`) && keys.includes(`${base}/2026-10-20-s2/base.pmtiles`));
  assert.ok(!keys.some((k) => k.startsWith(`${base}/2026-10-17/`) || k.startsWith(`${base}/2026-10-19-s2/`)));
  assert.ok(keys.includes(`${base}/current.json`));
  assert.ok(keys.includes("cells/f2/10/544/361/2026-10-17/base.pmtiles")); // only two versions there
  // a version younger than 7 days stays even when it is neither current nor previous
  await B.put(`${base}/2026-10-22-s2/base.pmtiles`, "x", { _uploaded: fresh });
  await B.put(`${base}/current.json`, JSON.stringify({ version: "2026-10-22-s2" }));
  const r2 = await cleanupStorage(env, now);
  assert.equal(r2.oldVersions, 0);
});

test("cleanup: deletes at most maxDeletes per run", async () => {
  const env = testEnv();
  for (let i = 0; i < 30; i++) await env.BUCKET.put(`cells/f1/10/1/${i}/v/base.pmtiles`, "x");
  const r = await cleanupStorage(env, Date.now(), { maxDeletes: 10 });
  assert.equal(r.f1Objects, 10);
  assert.equal([...env.BUCKET.objects.keys()].length, 20);
});

// ------------------------------------------------------------------------------------------------ region packages (§7i)
import { isPackage, PACKAGE_Z } from "../src/cells.js";
const DUS = "7-66-42"; // z7 region package over Düsseldorf / Hilden

test("package ids: z7 accepted, other zooms rejected", () => {
  assert.equal(PACKAGE_Z, 7);
  assert.deepEqual(parseCellId("7/66/42"), { z: 7, x: 66, y: 42, id: DUS });
  assert.ok(isPackage(DUS) && isPackage(parseCellId(DUS)) && !isPackage(MERAN));
  for (const bad of ["9-1-1", "8-1-1", "7-128-0", "7-1-128", "11-1-1"]) assert.equal(parseCellId(bad), null, bad);
  assert.equal(blockOf(parseCellId(DUS)), "66-42");
  assert.equal(blockOf(parseCellId("10-533-341")), "66-42"); // a cell under the package: the same block
});

test("package: request (with cells) → queued → built → ready → manifest", async () => {
  const env = testEnv({ BUILDS_ENABLED: "true" });
  const g = fakeGoogle();
  const c = ctx();
  const r = await handle(req("/v1/cells/request", { method: "POST", body: { cells: ["10-533-341", DUS], reason: "refresh", formats: [2] } }), env, c, g);
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.deepEqual(j.cells.map((x) => [x.id, x.state]), [["10-533-341", "queued"], [DUS, "queued"]]);
  const row0 = await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(DUS).first();
  assert.equal(row0.priority, 3); // refresh priority: never ahead of someone waiting
  await c.done();
  const { results } = await env.DB.prepare("SELECT name, cells FROM executions WHERE state = 'running'").all();
  const ex = results.find((e) => JSON.parse(e.cells).includes(DUS)); // background work is spread over free slots
  assert.ok(ex);
  // the builder publishes the package (schema 1) and reports ready
  await publish(env, DUS, "2026-10-26-s1");
  await putStatus(env, DUS, { state: "ready", version: "2026-10-26-s1", osm_date: "2026-10-26", size: 1000,
    execution: gcp.shortName(ex.name) });
  await pump(env, config(env), Date.now(), g);
  const s = await (await handle(req(`/v1/cells/status?ids=${DUS}&format=2`), env, ctx(), g)).json();
  assert.equal(s.cells[0].state, "ready");
  assert.equal(s.cells[0].version, "2026-10-26-s1");
  assert.equal(s.cells[0].updating, undefined);
  const m = await handle(req("/v1/cells/7/66/42/manifest?format=2"), env, ctx(), g);
  assert.equal(m.status, 200);
  assert.equal((await m.json()).cell, DUS);
  const f = await handle(req("/v1/files/cells/f2/7/66/42/2026-10-26-s1/base.pmtiles"), env, ctx(), g);
  assert.equal(f.status, 200);
  assert.equal((await handle(req("/v1/cells/9/66/42/manifest?format=2"), env, ctx(), g)).status, 400);
});

test("package schema is its own: a package s1 is current while CELL_SCHEMA = 7; below PACKAGE_SCHEMA it is rebuilt, served meanwhile", async () => {
  const env = testEnv();
  const cfg = config(env);
  assert.equal(cfg.cellSchema, 7);
  assert.equal(cfg.packageSchema, 1);
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, requests)
      VALUES (?, 2, '2026-10-25-s1', '2026-10-25', 5, 3, 1)`).bind(DUS).run();
  const j = await requestCells(env, cfg, { cells: [DUS], reason: "refresh" }, NOW);
  assert.deepEqual([j.cells[0].state, j.cells[0].updating], ["ready", undefined]);
  assert.equal((await row(env, DUS)).job, null); // not rebuilt
  // a later package schema: rebuilt in the background, the old one stays "ready" meanwhile
  const cfg2 = config({ ...env, PACKAGE_SCHEMA: "2" });
  const k = await requestCells(env, cfg2, { cells: [DUS], reason: "point" }, NOW);
  assert.deepEqual([k.cells[0].state, k.cells[0].version, k.cells[0].updating], ["ready", "2026-10-25-s1", true]);
  const r2 = await env.DB.prepare("SELECT job, priority FROM cells WHERE id = ?").bind(DUS).first();
  assert.deepEqual({ ...r2 }, { job: "queued", priority: 3 });
});

test("package fast lane: a published current package is adopted (no build); an old-schema one is not", async () => {
  const env = testEnv();
  const cfg = config(env);
  const g = fakeGoogle();
  await publishCurrent(env, DUS, "2026-10-26-s1", "2026-10-26");
  const j = await requestCells(env, cfg, { cells: [DUS], reason: "refresh" }, NOW);
  assert.deepEqual([j.cells[0].state, j.cells[0].version], ["ready", "2026-10-26-s1"]);
  assert.deepEqual(await row(env, DUS), { version: "2026-10-26-s1", osm_date: "2026-10-26", size: 4321, job: null });
  await pump(env, cfg, NOW + 1000, g);
  assert.equal(g.calls.filter((x) => x.url.endsWith(":run")).length, 0);
  // PACKAGE_SCHEMA 2: the s1 package in R2 is not current → built
  const env2 = testEnv({ PACKAGE_SCHEMA: "2" });
  await publishCurrent(env2, DUS, "2026-10-26-s1", "2026-10-26");
  const k = await requestCells(env2, config(env2), { cells: [DUS], reason: "refresh" }, NOW);
  assert.equal(k.cells[0].state, "queued");
});

test("pre-warm after a cell schema bump leaves region packages alone", async () => {
  const env = testEnv({ BUILDS_ENABLED: "true", PARALLEL_BUILDS: "1" });
  await env.DB.prepare(`INSERT INTO cells (id, format, version, osm_date, size, priority, last_request, requests)
      VALUES (?, 2, '2026-10-20-s1', '2026-10-20', 5, 0, ?, 1)`).bind(DUS, NOW - 86_400_000).run();
  await pump(env, config(env), NOW, fakeGoogle());
  assert.equal((await row(env, DUS)).job, null);
});
