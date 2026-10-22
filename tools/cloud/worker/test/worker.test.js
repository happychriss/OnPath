// node --test  (Node ≥ 22.13: node:sqlite, JSON import attributes) – no npm packages needed.
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
      VALUES (?, 2, '2026-01-01-s2', '2026-01-01', 5, 3, 1)`).bind(MERAN).run();
  const j = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-20"));
  assert.equal(j.cells[0].state, "ready");
  assert.equal(j.cells[0].updating, true);
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
  await env.DB.prepare("UPDATE cells SET version = '2026-10-19-s2', job = NULL WHERE id = ?").bind(MERAN).run();
  const k = await requestCells(env, cfg, { cells: [MERAN], reason: "point" }, Date.parse("2026-10-21"));
  assert.equal(k.cells[0].state, "ready");
  assert.equal(k.cells[0].updating, undefined);
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
