// Queue, dedupe and the build pump (docs/design/map-cells.md §5, docs/design/map-hosting.md §Doorman).
// State lives in D1; the builder reports per cell through status/f<format>/<cell>.json in the R2 bucket.
import { isPackage, parseCellId } from "./cells.js";
import { batchSize, isWaiting, plan, slotCount, smooth, WAITING_MAX } from "./eta.js";
import * as gcp from "./gcp.js";
import { globalFetch } from "./gcp.js";

// Lower = sooner. 0–1 (WAITING_MAX): someone waits for the map – the point / searched place / own position (T3) /
// confirmed tap and its close neighbours. 2+: background – route corridor, the next cell on tour (T5, "ahead"),
// refresh, smoke tests ("test"). Within a priority: first come, first served (map-hosting.md §7f).
export const PRIORITY = { point: 0, near: 1, corridor: 2, ahead: 2, refresh: 3, test: 4 };
const DAY = 86_400_000;

export class HttpError extends Error {
  constructor(status, message, headers = {}) {
    super(message);
    this.status = status;
    this.headers = headers;
  }
}

export const log = (o) => console.log(JSON.stringify(o));

/** Newest format both sides understand, or null. Missing list = the server's newest. */
export function pickFormat(cfg, asked) {
  const ok = Array.isArray(asked) ? cfg.formats.filter((f) => asked.map(Number).includes(f)) : cfg.formats;
  return ok.length ? Math.max(...ok) : null;
}

const placeholders = (n) => Array(n).fill("?").join(",");

async function rowsFor(db, ids, format) {
  if (!ids.length) return new Map();
  const { results } = await db.prepare(`SELECT * FROM cells WHERE format = ? AND id IN (${placeholders(ids.length)})`)
    .bind(format, ...ids).all();
  return new Map(results.map((r) => [r.id, r]));
}

/** Content schema of a published version: "2026-10-20-s2" → 2; plain OSM-date versions are schema 1. */
export function schemaOf(version) {
  const m = /-s(\d+)$/.exec(String(version || ""));
  return m ? Number(m[1]) : 1;
}

/** Schema a published version must have to count as current: the cells' CELL_SCHEMA, or PACKAGE_SCHEMA for a region
 *  package (z7 id, map-hosting.md §7i). */
export const wantSchema = (cfg, id) => (isPackage(id) ? cfg.packageSchema : cfg.cellSchema);

/** Lowest schema still served while a rebuild runs: CELL_SCHEMA_SERVE for cells; any version for packages (they only
 *  add the region view below z10 – an older one is better than none). */
export const serveSchema = (cfg, id) => (isPackage(id) ? 0 : Math.min(cfg.cellSchemaServe, cfg.cellSchema));

function isStale(osmDate, cfg, now) {
  const t = Date.parse(osmDate || "");
  return !Number.isFinite(t) || now - t > cfg.refreshDays * DAY;
}

// ------------------------------------------------------------------------------------------------ fast lane
// A version can be published into R2 outside a Cloud Run job: tools/dev/publish_cells.sh on a developer PC uploads
// exactly what job_build.py would (files, manifest, then current.json) – test bucket only (map-hosting.md §7h). D1
// does not know it; before a build is queued or started, the doorman therefore looks at the cell's current.json and
// takes a current version as it is ("adopt": no build, the app gets it at once).

/** {version, osm_date, size} of the version current.json points at (its manifest must exist), or null. */
async function published(env, format, id) {
  const c = parseCellId(id);
  if (!c) return null;
  const base = `cells/f${format}/${c.z}/${c.x}/${c.y}`;
  try {
    const cur = await env.BUCKET.get(`${base}/current.json`);
    if (!cur) return null;
    const version = JSON.parse(await cur.text())?.version;
    if (typeof version !== "string" || !version) return null;
    const m = await env.BUCKET.get(`${base}/${version}/manifest.json`);
    if (!m) return null;
    const man = JSON.parse(await m.text());
    return { version, osm_date: man.osm_date ?? null, size: man.total_size ?? null };
  } catch (e) {
    log({ ev: "published-unreadable", id, format, err: String(e).slice(0, 200) }); // then it is simply built
    return null;
  }
}

/** The published version when it is current (schema ≥ cellSchema, not OSM-stale) and not the one D1 knows. */
export async function adoptable(env, cfg, format, id, row, now) {
  const p = await published(env, format, id);
  if (!p || p.version === row?.version) return null;
  if (schemaOf(p.version) < wantSchema(cfg, id) || isStale(p.osm_date, cfg, now)) return null;
  return p;
}

/** Marks the cell ready with the published version – never while a Cloud Run job builds it (that one finishes). */
function adoptStmt(db, id, format, p, now) {
  return db.prepare(`INSERT INTO cells (id, format, version, osm_date, size, job, priority, requested_at, last_request,
        requests, attempts, error, permanent, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, NULL, 3, ?6, ?6, 1, 0, NULL, 0, ?6)
      ON CONFLICT (id, format) DO UPDATE SET version = ?3, osm_date = ?4, size = ?5, job = NULL, attempts = 0,
        error = NULL, permanent = 0, execution = NULL, eta_at = NULL, eta_seen = NULL, updated_at = ?6,
        last_request = ?6, requests = requests + 1
      WHERE cells.job IS NULL OR cells.job IN ('queued', 'failed')`).bind(id, format, p.version, p.osm_date, p.size, now);
}

/** Pump: queued cells (first ADOPT_SCAN in queue order) whose current version is already published → ready. */
const ADOPT_SCAN = 40;

async function adoptQueued(env, cfg, now) {
  const db = env.DB;
  const { results } = await db.prepare(`SELECT id, format, version FROM cells WHERE job = 'queued'
      ORDER BY priority, requested_at LIMIT ?`).bind(ADOPT_SCAN).all();
  const found = await Promise.all(results.map((r) => adoptable(env, cfg, r.format, r.id, r, now)));
  const writes = [];
  const ids = [];
  results.forEach((r, i) => {
    if (!found[i]) return;
    ids.push(r.id);
    writes.push(adoptStmt(db, r.id, r.format, found[i], now));
  });
  if (writes.length) {
    await db.batch(writes);
    log({ ev: "adopted", how: "pump", cells: ids });
  }
  return ids.length;
}

// ------------------------------------------------------------------------------------------------ pre-warm
/**
 * After a CELL_SCHEMA bump (map-hosting.md §7h): every cell asked for in the last PREWARM_DAYS whose published version
 * is of an older schema is queued once at refresh priority – rebuilt in the background (hourly cap, the waiting
 * reserve), so phones find the new map ready instead of waiting for it. Cells only: region packages (z7) have their own
 * schema and are rebuilt when the app asks for them again. Done when meta "prewarm-schema" = CELL_SCHEMA;
 * a queue that is too full for all of them goes on at the next pump.
 */
async function prewarm(env, cfg, now) {
  if (!cfg.prewarmDays) return 0;
  const db = env.DB;
  const m = await db.prepare("SELECT value FROM meta WHERE key = 'prewarm-schema'").first();
  if (m && Number(m.value) >= cfg.cellSchema) return 0;
  const pend = await db.prepare("SELECT COUNT(*) AS n FROM cells WHERE job IN ('queued', 'building')").first();
  const room = Math.max(0, cfg.maxQueue - pend.n);
  const { results } = await db.prepare(`SELECT id, format, version FROM cells WHERE version IS NOT NULL AND job IS NULL
      AND last_request > ? ORDER BY last_request DESC`).bind(now - cfg.prewarmDays * DAY).all();
  const old = results.filter((r) => cfg.formats.includes(r.format) && !isPackage(r.id) && schemaOf(r.version) < cfg.cellSchema);
  const take = old.slice(0, room);
  const writes = take.map((r) => db.prepare(`UPDATE cells SET job = 'queued', priority = ?3, requested_at = ?4, attempts = 0,
      error = NULL, execution = NULL, eta_at = NULL, eta_seen = NULL, updated_at = ?4
      WHERE id = ?1 AND format = ?2 AND job IS NULL`).bind(r.id, r.format, PRIORITY.refresh, now));
  if (take.length === old.length) {
    writes.push(db.prepare(`INSERT INTO meta (key, value, until) VALUES ('prewarm-schema', ?1, NULL)
        ON CONFLICT (key) DO UPDATE SET value = ?1`).bind(String(cfg.cellSchema)));
  }
  if (writes.length) await db.batch(writes);
  if (take.length) log({ ev: "prewarm", schema: cfg.cellSchema, cells: take.length, left: old.length - take.length });
  return take.length;
}

/** POST /v1/cells/request */
export async function requestCells(env, cfg, body, now = Date.now()) {
  if (!body || !Array.isArray(body.cells) || body.cells.length === 0) throw new HttpError(400, "cells: list of cell ids expected");
  if (body.cells.length > cfg.maxCellsPerRequest) throw new HttpError(400, `at most ${cfg.maxCellsPerRequest} cells per request`);
  const reason = body.reason ?? "point";
  if (!(reason in PRIORITY)) throw new HttpError(400, `reason must be one of ${Object.keys(PRIORITY).join(", ")}`);
  const format = pickFormat(cfg, body.formats);
  if (format === null) throw new HttpError(400, `no common format (server builds ${cfg.formats.join(", ")})`);
  const cells = [];
  for (const s of body.cells) {
    const c = parseCellId(s);
    if (!c) throw new HttpError(400, `bad cell id: ${String(s).slice(0, 40)}`);
    if (!cells.some((o) => o.id === c.id)) cells.push(c);
  }
  const db = env.DB;
  const pending = await db.prepare("SELECT COUNT(*) AS n FROM cells WHERE job IN ('queued', 'building')").first();
  const rows = await rowsFor(db, cells.map((c) => c.id), format);
  const prio = PRIORITY[reason];
  const writes = [];
  const want = []; // cells that need a build – unless a current version is already published (adopt)
  for (const c of cells) {
    const r = rows.get(c.id);
    const queue = (p) => {
      want.push({ c, r, p });
      return null;
    };
    const touch = (p) => db.prepare(`UPDATE cells SET last_request = ?3, requests = requests + 1, priority = MIN(priority, ?4)
        WHERE id = ?1 AND format = ?2`).bind(c.id, format, now, p);
    if (!r) writes.push(queue(prio));
    else if (r.job === "queued" || r.job === "building") writes.push(touch(prio)); // join the job
    else if (r.job === "failed") {
      const retry = !r.permanent && (r.attempts < cfg.maxAttempts || r.rev !== cfg.buildRev);
      writes.push(retry ? queue(r.version ? Math.max(prio, PRIORITY.refresh) : prio) : touch(prio));
    } else if (r.version && schemaOf(r.version) < wantSchema(cfg, c.id)) {
      // built for an older style (e.g. without the "streets" layer – the map misses streets): rebuild with the asked
      // priority. From cellSchemaServe on the old version still works and is served meanwhile (statusFor): a
      // low-priority refresh like an OSM-old cell (e.g. s3 → s4 = POI priority only)
      const servable = schemaOf(r.version) >= serveSchema(cfg, c.id);
      writes.push(queue(servable ? Math.max(prio, PRIORITY.refresh) : prio));
    } else if (r.version && isStale(r.osm_date, cfg, now)) writes.push(queue(PRIORITY.refresh)); // old: refresh, low
    else if (r.version) writes.push(touch(PRIORITY.refresh));
    else writes.push(queue(prio));
  }
  // fast lane (map-hosting.md §7h): a current version published into R2 outside a Cloud Run job is taken as it is
  const found = await Promise.all(want.map((w) => adoptable(env, cfg, format, w.c.id, w.r, now)));
  let added = 0;
  const adopted = [];
  want.forEach((w, i) => {
    if (found[i]) {
      adopted.push(w.c.id);
      writes.push(adoptStmt(db, w.c.id, format, found[i], now));
      return;
    }
    added++;
    writes.push(db.prepare(`INSERT INTO cells (id, format, job, priority, requested_at, last_request, requests, attempts, error,
        permanent, updated_at) VALUES (?1, ?2, 'queued', ?3, ?4, ?4, 1, 0, NULL, 0, ?4)
      ON CONFLICT (id, format) DO UPDATE SET job = 'queued', priority = ?3, requested_at = ?4, last_request = ?4,
        requests = requests + 1, error = NULL, execution = NULL, updated_at = ?4, eta_at = NULL, eta_seen = NULL,
        attempts = CASE WHEN ?5 = 1 THEN 0 ELSE attempts END`).bind(w.c.id, format, w.p, now, w.r && w.r.rev !== cfg.buildRev ? 1 : 0));
  });
  if (adopted.length) log({ ev: "adopted", how: "request", format, cells: adopted });
  if (added && pending.n + added > cfg.maxQueue) {
    throw new HttpError(429, "The map server is busy – please try again later.", { "retry-after": "600" });
  }
  const batch = writes.filter(Boolean); // queue() placeholders were replaced by the inserts / adoptions above
  if (batch.length) await db.batch(batch);
  if (added) log({ ev: "request", reason, format, cells: cells.map((c) => c.id), added });
  return statusFor(env, cfg, cells.map((c) => c.id), format, now);
}

async function readStatus(env, format, id) {
  const o = await env.BUCKET.get(`status/f${format}/${id}.json`);
  if (!o) return null;
  try {
    return JSON.parse(await o.text());
  } catch (e) {
    log({ ev: "bad-status", id, err: String(e) });
    return null;
  }
}

const rebuilding = (r) => r.job === "queued" || r.job === "building";

/** GET /v1/cells/status and the answer of request: one entry per cell (map-cells.md §8). eta_s = live estimate
 *  (eta.js plan), smoothed per cell so it never jumps (map-hosting.md §7f). */
export async function statusFor(env, cfg, ids, format, now = Date.now()) {
  const db = env.DB;
  const rows = await rowsFor(db, ids, format);
  const out = [];
  const writes = [];
  let active = false;
  let p = null;
  for (const id of ids) {
    const r = rows.get(id);
    if (!r) {
      out.push({ id, state: "none" });
    } else if (r.version && !(rebuilding(r) && schemaOf(r.version) < serveSchema(cfg, id))) {
      // a published version is served at once; "updating" = a newer one is being built (the app keeps asking).
      // Not while a cell below cellSchemaServe is rebuilt: that map lacks data the style needs (streets).
      out.push({ id, state: "ready", osm_date: r.osm_date, size: r.size, version: r.version,
        ...(rebuilding(r) ? { updating: true } : {}) });
      // the app polls an updating cell at poll_s (map-hosting.md §7h): the new version soon after it is built
      if (rebuilding(r)) active = true;
    } else if (r.job === "building" || r.job === "queued") {
      active = true;
      p ??= await plan(env, cfg, now, readStatus);
      const e = p.cells.get(`${format}:${id}`) || { finishAt: now + cfg.avgCellS * 1000 };
      const at = Math.max(now + 20_000, smooth(cfg, r.eta_at, r.eta_seen, e.finishAt, now));
      if (!r.eta_at || Math.abs(at - r.eta_at) > 2000) {
        writes.push(db.prepare("UPDATE cells SET eta_at = ?3, eta_seen = ?4 WHERE id = ?1 AND format = ?2")
          .bind(id, format, Math.round(at), now));
      }
      const eta_s = Math.round((at - now) / 1000);
      out.push(r.job === "building"
        ? { id, state: "building", step: e.step || "starting", progress: e.progress || 0, eta_s }
        : { id, state: "queued", position: e.position || 1, eta_s });
    } else {
      out.push({ id, state: "failed", reason: r.error || "This area could not be prepared" });
    }
  }
  if (writes.length) await db.batch(writes);
  return { cells: out, poll_s: active ? 15 : 60 };
}


// ------------------------------------------------------------------------------------------------ pump
async function lease(db, now, token) {
  const r = await db.prepare(`INSERT INTO meta (key, value, until) VALUES ('pump', ?1, ?2)
      ON CONFLICT (key) DO UPDATE SET value = ?1, until = ?2 WHERE meta.until < ?3`).bind(token, now + 60_000, now).run();
  return r.meta.changes === 1;
}

async function finishCell(env, cfg, ex, id, s, now) {
  const db = env.DB;
  if (s && s.state === "ready") {
    const n = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);
    const writes = [db.prepare(`UPDATE cells SET version = ?3, osm_date = ?4, size = ?5, job = NULL, attempts = 0,
        error = NULL, permanent = 0, execution = NULL, updated_at = ?6, eta_at = NULL, eta_seen = NULL
        WHERE id = ?1 AND format = ?2`).bind(id, ex.format, s.version, s.osm_date ?? null, s.size ?? null, now)];
    // measured times for the ETA's rolling averages (eta.js buildStats); older builders send none
    if (n(s.build_s) !== null) {
      writes.push(db.prepare(`INSERT INTO builds (id, format, execution, finished_at, build_s, new_source, download_s,
          download_mb) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)`).bind(id, ex.format, gcp.shortName(ex.name), now,
        n(s.build_s), s.new_source ? 1 : 0, n(s.download_s) ?? 0, n(s.download_mb) ?? 0));
    }
    await db.batch(writes);
    log({ ev: "cell-ready", id, format: ex.format, version: s.version });
    return;
  }
  const reason = s && s.state === "failed" ? s.reason || "Build failed" : "Build stopped unexpectedly";
  const permanent = s && s.state === "failed" && s.permanent ? 1 : 0;
  await db.prepare(`UPDATE cells SET attempts = attempts + 1, error = ?3, permanent = ?4, rev = ?5, execution = NULL,
        job = CASE WHEN ?4 = 0 AND attempts + 1 < ?6 THEN 'queued' ELSE 'failed' END, updated_at = ?7
      WHERE id = ?1 AND format = ?2 AND job = 'building'`)
    .bind(id, ex.format, reason, permanent, cfg.buildRev, cfg.maxAttempts, now).run();
  log({ ev: "cell-failed", id, format: ex.format, reason, detail: s?.detail?.slice?.(0, 300) });
}

async function checkExecution(env, cfg, ex, now, fetchImpl) {
  const db = env.DB;
  const ids = JSON.parse(ex.cells || "[]");
  const short = gcp.shortName(ex.name);
  const st = {};
  for (const id of ids) {
    const s = await readStatus(env, ex.format, id);
    st[id] = s && s.execution === short ? s : null;
    // ready cells are published at once – the app need not wait for the rest of the execution
    if (st[id]?.state === "ready") {
      const r = await db.prepare("SELECT job FROM cells WHERE id = ? AND format = ?").bind(id, ex.format).first();
      if (r?.job === "building") await finishCell(env, cfg, ex, id, st[id], now);
    }
  }
  let state = null;
  try {
    state = gcp.executionState(await gcp.getExecution(env, ex.name, fetchImpl));
  } catch (e) {
    log({ ev: "execution-check-failed", name: ex.name, err: String(e).slice(0, 300) });
  }
  // Cloud Run stops the task itself after timeoutSeconds (× 2 with one retry); this is only the safety net
  const timedOut = now - ex.started_at > (2 * cfg.jobTimeoutS + 600) * 1000;
  if (!(state?.done || timedOut)) return false;
  if (timedOut && !state?.done) {
    try {
      await gcp.cancelExecution(env, ex.name, fetchImpl);
    } catch (e) {
      log({ ev: "cancel-failed", name: ex.name, err: String(e).slice(0, 300) });
    }
  }
  for (const id of ids) {
    const r = await db.prepare("SELECT job FROM cells WHERE id = ? AND format = ?").bind(id, ex.format).first();
    if (r?.job === "building") await finishCell(env, cfg, ex, id, st[id], now);
  }
  const final = state?.succeeded ? "succeeded" : "failed";
  await db.prepare("UPDATE executions SET state = ?2, finished_at = ?3 WHERE name = ?1").bind(ex.name, final, now).run();
  log({ ev: "execution-done", name: ex.name, state: final, timedOut });
  return true;
}

/** Start one execution on a free slot. Waiting cells (priority ≤ WAITING_MAX) first and spread over the free slots;
 *  background cells only while another slot stays free for someone waiting, and within a smaller hourly/daily
 *  budget (the last `slots` starts are kept for waiting cells). */
async function startNext(env, cfg, now, fetchImpl, free, slots) {
  const db = env.DB;
  const c = await db.prepare(`SELECT COALESCE(SUM(CASE WHEN started_at > ?1 THEN 1 ELSE 0 END), 0) AS h, COUNT(*) AS d
      FROM executions WHERE started_at > ?2`).bind(now - 3_600_000, now - DAY).first();
  if (c.h >= cfg.maxExecPerHour || c.d >= cfg.maxExecPerDay) {
    log({ ev: "cost-guard", hour: c.h, day: c.d });
    return { guard: true };
  }
  const first = await db.prepare("SELECT format, priority FROM cells WHERE job = 'queued' ORDER BY priority, requested_at LIMIT 1").first();
  if (!first) return { idle: true };
  const waiting = isWaiting(first.priority);
  if (!waiting && slots > 1) {
    if (free < 2) return { reserved: true }; // the last free slot is for someone waiting
    const keep = Math.min(slots, Math.floor(cfg.maxExecPerHour / 2));
    if (c.h >= cfg.maxExecPerHour - keep || c.d >= cfg.maxExecPerDay - keep) {
      log({ ev: "cost-guard-background", hour: c.h, day: c.d });
      return { guard: true };
    }
  }
  let n = cfg.cellsPerExecution;
  if (waiting) {
    const w = await db.prepare("SELECT COUNT(*) AS n FROM cells WHERE job = 'queued' AND format = ? AND priority <= ?")
      .bind(first.format, WAITING_MAX).first();
    n = batchSize(cfg, w.n, free);
  } else if (slots > 1) {
    // background (refresh, schema rebuild, corridor) spread over the free slots but the one kept for someone waiting:
    // 6 cells on 3 slots = 3 × 2 cells, not 4 + 2 built one after the other (field 2026-10-26: s5 → s6 for Hilden,
    // the last of 4 serial cells came 36 min after the start). Long queues still go 4 per execution (hourly cap).
    const b = await db.prepare("SELECT COUNT(*) AS n FROM cells WHERE job = 'queued' AND format = ?").bind(first.format).first();
    n = batchSize(cfg, b.n, free - 1);
  }
  const { results } = await db.prepare(`SELECT id FROM cells WHERE job = 'queued' AND format = ?1
      AND (?2 = 0 OR priority <= ?3) ORDER BY priority, requested_at LIMIT ?4`)
    .bind(first.format, waiting ? 1 : 0, WAITING_MAX, n).all();
  const ids = results.map((r) => r.id);
  let name;
  try {
    name = await gcp.runJob(env, cfg, ids, first.format, fetchImpl);
  } catch (e) {
    // counts towards the hourly limit, so a broken setup cannot hammer the API
    await db.prepare(`INSERT INTO executions (name, format, cells, started_at, finished_at, state, error)
        VALUES (?1, ?2, ?3, ?4, ?4, 'start_failed', ?5)`)
      .bind(`start-failed-${now}-${crypto.randomUUID().slice(0, 8)}`, first.format, JSON.stringify(ids), now, String(e).slice(0, 500)).run();
    log({ ev: "start-failed", cells: ids, err: String(e).slice(0, 300) });
    return { error: String(e) };
  }
  await db.batch([
    db.prepare("INSERT INTO executions (name, format, cells, started_at, state, waiting) VALUES (?1, ?2, ?3, ?4, 'running', ?5)")
      .bind(name, first.format, JSON.stringify(ids), now, waiting ? 1 : 0),
    db.prepare(`UPDATE cells SET job = 'building', execution = ?1, updated_at = ?2
        WHERE format = ?3 AND job = 'queued' AND id IN (${placeholders(ids.length)})`).bind(name, now, first.format, ...ids),
  ]);
  log({ ev: "execution-started", name, cells: ids, waiting });
  return { started: name, cells: ids };
}

/** Check the running executions, start new ones on free slots (cfg.parallelBuilds) when allowed. Called by the cron
 *  (every minute) and after a request. A D1 lease makes sure two pumps never run at once, so a cell is never put
 *  into two executions. When Google refuses a start while others run (quota, permissions), the pump falls back to
 *  one build at a time for PARALLEL_BLOCK_MS. */
const PARALLEL_BLOCK_MS = 30 * 60_000;

export async function pump(env, cfg, now = Date.now(), fetchImpl = globalFetch) {
  const db = env.DB;
  const token = crypto.randomUUID();
  if (!(await lease(db, now, token))) return { busy: true };
  try {
    const { results: running } = await db.prepare("SELECT * FROM executions WHERE state = 'running'").all();
    let stillRunning = 0;
    for (const ex of running) if (!(await checkExecution(env, cfg, ex, now, fetchImpl))) stillRunning++;
    await adoptQueued(env, cfg, now); // also while builds are off: published versions need no builder
    if (!cfg.buildsEnabled) return { disabled: true, running: stillRunning };
    await prewarm(env, cfg, now);
    const slots = await slotCount(db, cfg, now);
    const started = [];
    let last = { running: stillRunning };
    while (stillRunning < slots) {
      const r = await startNext(env, cfg, now, fetchImpl, slots - stillRunning, slots);
      if (r.error && stillRunning > 0) {
        await db.prepare(`INSERT INTO meta (key, value, until) VALUES ('parallel-blocked', ?1, ?2)
            ON CONFLICT (key) DO UPDATE SET value = ?1, until = ?2`).bind(r.error.slice(0, 300), now + PARALLEL_BLOCK_MS).run();
        log({ ev: "parallel-blocked", running: stillRunning, err: r.error.slice(0, 300) });
      }
      if (!r.started) {
        last = r;
        break;
      }
      started.push(r.started);
      stillRunning++;
    }
    if (started.length) return { started: started[0], all: started, running: stillRunning };
    return stillRunning && (last.idle || last.reserved) ? { running: stillRunning } : last;
  } finally {
    await db.prepare("DELETE FROM meta WHERE key = 'pump' AND value = ?").bind(token).run();
  }
}


/** Cron housekeeping: old rate-limit rows. */
export async function housekeeping(env, now = Date.now()) {
  await env.DB.prepare("DELETE FROM ratelimit WHERE win < ?").bind(Math.floor(now / 60_000) - 2).run();
}
