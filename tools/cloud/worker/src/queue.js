// Queue, dedupe and the build pump (docs/design/map-cells.md §5, docs/design/map-hosting.md §Doorman).
// State lives in D1; the builder reports per cell through status/f<format>/<cell>.json in the R2 bucket.
import { blockOf, parseCellId } from "./cells.js";
import * as gcp from "./gcp.js";
import { globalFetch } from "./gcp.js";

export const PRIORITY = { point: 0, near: 1, corridor: 2, refresh: 3 };
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

function isStale(osmDate, cfg, now) {
  const t = Date.parse(osmDate || "");
  return !Number.isFinite(t) || now - t > cfg.refreshDays * DAY;
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
  let added = 0;
  for (const c of cells) {
    const r = rows.get(c.id);
    const queue = (p) => {
      added++;
      return db.prepare(`INSERT INTO cells (id, format, job, priority, requested_at, last_request, requests, attempts, error,
          permanent, updated_at) VALUES (?1, ?2, 'queued', ?3, ?4, ?4, 1, 0, NULL, 0, ?4)
        ON CONFLICT (id, format) DO UPDATE SET job = 'queued', priority = ?3, requested_at = ?4, last_request = ?4,
          requests = requests + 1, error = NULL, execution = NULL, updated_at = ?4,
          attempts = CASE WHEN ?5 = 1 THEN 0 ELSE attempts END`).bind(c.id, format, p, now, r && r.rev !== cfg.buildRev ? 1 : 0);
    };
    const touch = (p) => db.prepare(`UPDATE cells SET last_request = ?3, requests = requests + 1, priority = MIN(priority, ?4)
        WHERE id = ?1 AND format = ?2`).bind(c.id, format, now, p);
    if (!r) writes.push(queue(prio));
    else if (r.job === "queued" || r.job === "building") writes.push(touch(prio)); // join the job
    else if (r.job === "failed") {
      const retry = !r.permanent && (r.attempts < cfg.maxAttempts || r.rev !== cfg.buildRev);
      writes.push(retry ? queue(r.version ? Math.max(prio, PRIORITY.refresh) : prio) : touch(prio));
    } else if (r.version && schemaOf(r.version) < cfg.cellSchema) {
      // built for an older style (e.g. without the "streets" layer – the map misses streets): rebuild with the asked
      // priority; the old version is served until the new one is ready
      writes.push(queue(prio));
    } else if (r.version && isStale(r.osm_date, cfg, now)) writes.push(queue(PRIORITY.refresh)); // old: refresh, low
    else if (r.version) writes.push(touch(PRIORITY.refresh));
    else writes.push(queue(prio));
  }
  if (added && pending.n + added > cfg.maxQueue) {
    throw new HttpError(429, "The map server is busy – please try again later.", { "retry-after": "600" });
  }
  if (writes.length) await db.batch(writes);
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

/** z7 blocks (cells.js blockOf) that already have a built or building cell: there the source extract is most likely
 *  in the builder's bucket. A cell elsewhere probably needs a new extract first (minutes of download, map-hosting.md
 *  §Source data) – its ETA gets cfg.newAreaS more. Any land cell is accepted; the builder answers open sea itself. */
async function knownBlocks(db, format) {
  const { results } = await db.prepare(`SELECT id, version IS NOT NULL AS built FROM cells WHERE format = ?
      AND (version IS NOT NULL OR job = 'building')`).bind(format).all();
  const known = new Map(); // block → ids (built ones as "*": they count for every cell)
  for (const r of results) {
    const c = parseCellId(r.id);
    if (!c) continue;
    const k = blockOf(c);
    if (!known.has(k)) known.set(k, new Set());
    known.get(k).add(r.built ? "*" : r.id);
  }
  return known;
}

/** GET /v1/cells/status and the answer of request: one entry per cell (map-cells.md §8). */
export async function statusFor(env, cfg, ids, format, now = Date.now()) {
  const db = env.DB;
  const rows = await rowsFor(db, ids, format);
  const { results: queued } = await db.prepare(
    "SELECT id FROM cells WHERE job = 'queued' AND format = ? ORDER BY priority, requested_at LIMIT ?").bind(format, cfg.maxQueue).all();
  const order = new Map(queued.map((r, i) => [r.id, i + 1]));
  const running = await db.prepare("SELECT started_at FROM executions WHERE state = 'running' LIMIT 1").first();
  const runLeft = running ? Math.max(30, cfg.startupS + cfg.cellsPerExecution * cfg.avgCellS - (now - running.started_at) / 1000) : 0;
  const out = [];
  let building = false;
  let waiting = false;
  let known = null;
  const extra = async (id) => {
    known ??= await knownBlocks(db, format);
    const c = parseCellId(id);
    const k = c && known.get(blockOf(c));
    // a cell being built does not make its own extract cached
    return c && !(k && (k.has("*") || [...k].some((o) => o !== id))) ? cfg.newAreaS : 0;
  };
  for (const id of ids) {
    const r = rows.get(id);
    if (!r) {
      out.push({ id, state: "none" });
    } else if (r.version && !(rebuilding(r) && schemaOf(r.version) < cfg.cellSchema)) {
      // a published version is served at once; "updating" = a newer one is being built (the app keeps asking).
      // Not while a cell of an older content schema is rebuilt: that map lacks data the style needs (streets).
      out.push({ id, state: "ready", osm_date: r.osm_date, size: r.size, version: r.version,
        ...(rebuilding(r) ? { updating: true } : {}) });
    } else if (r.job === "building") {
      building = true;
      const s = await readStatus(env, format, id);
      const mine = s && r.execution && s.execution === gcp.shortName(r.execution);
      const progress = mine && typeof s.progress === "number" ? s.progress : 0;
      // a new extract being downloaded: the builder's own estimate; before its first status: the guess by area
      const source = mine ? (typeof s.source_eta_s === "number" && s.step === "download" ? s.source_eta_s : 0)
        : cfg.startupS + (await extra(id));
      out.push({ id, state: "building", step: mine ? s.step || "starting" : "starting", progress,
        eta_s: Math.round(Math.max(20, (1 - progress) * cfg.avgCellS + source)) });
    } else if (r.job === "queued") {
      waiting = true;
      const pos = order.get(id) || queued.length + 1;
      out.push({ id, state: "queued", position: pos,
        eta_s: Math.round(runLeft + Math.ceil(pos / cfg.cellsPerExecution) * cfg.startupS + pos * cfg.avgCellS
          + (await extra(id))) });
    } else {
      out.push({ id, state: "failed", reason: r.error || "This area could not be prepared" });
    }
  }
  return { cells: out, poll_s: building || (waiting && running) ? 15 : 60 };
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
    await db.prepare(`UPDATE cells SET version = ?3, osm_date = ?4, size = ?5, job = NULL, attempts = 0, error = NULL,
        permanent = 0, execution = NULL, updated_at = ?6 WHERE id = ?1 AND format = ?2`)
      .bind(id, ex.format, s.version, s.osm_date ?? null, s.size ?? null, now).run();
    log({ ev: "cell-ready", id, format: ex.format, version: s.version });
    return;
  }
  const reason = s && s.state === "failed" ? s.reason || "Build failed" : "Build stopped unexpectedly";
  const permanent = s && s.state === "failed" && s.permanent ? 1 : 0;
  if (s && s.state === "failed" && s.defer) {
    // the builder's daily download limit: not the cell's fault – no attempt counted, asking again later queues it
    await db.prepare(`UPDATE cells SET error = ?3, execution = NULL, job = 'failed', updated_at = ?4
        WHERE id = ?1 AND format = ?2 AND job = 'building'`).bind(id, ex.format, reason, now).run();
    log({ ev: "cell-deferred", id, format: ex.format, reason });
    return;
  }
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

async function startNext(env, cfg, now, fetchImpl) {
  const db = env.DB;
  const c = await db.prepare(`SELECT COALESCE(SUM(CASE WHEN started_at > ?1 THEN 1 ELSE 0 END), 0) AS h, COUNT(*) AS d
      FROM executions WHERE started_at > ?2`).bind(now - 3_600_000, now - DAY).first();
  if (c.h >= cfg.maxExecPerHour || c.d >= cfg.maxExecPerDay) {
    log({ ev: "cost-guard", hour: c.h, day: c.d });
    return { guard: true };
  }
  const first = await db.prepare("SELECT format FROM cells WHERE job = 'queued' ORDER BY priority, requested_at LIMIT 1").first();
  if (!first) return { idle: true };
  const { results } = await db.prepare(
    "SELECT id FROM cells WHERE job = 'queued' AND format = ? ORDER BY priority, requested_at LIMIT ?")
    .bind(first.format, cfg.cellsPerExecution).all();
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
    db.prepare("INSERT INTO executions (name, format, cells, started_at, state) VALUES (?1, ?2, ?3, ?4, 'running')")
      .bind(name, first.format, JSON.stringify(ids), now),
    db.prepare(`UPDATE cells SET job = 'building', execution = ?1, updated_at = ?2
        WHERE format = ?3 AND job = 'queued' AND id IN (${placeholders(ids.length)})`).bind(name, now, first.format, ...ids),
  ]);
  log({ ev: "execution-started", name, cells: ids });
  return { started: name, cells: ids };
}

/** Check the running execution, start the next one when allowed. Called by the cron (every minute) and after a
 *  request. A D1 lease makes sure two pumps never run at once (= never two executions). */
export async function pump(env, cfg, now = Date.now(), fetchImpl = globalFetch) {
  const db = env.DB;
  const token = crypto.randomUUID();
  if (!(await lease(db, now, token))) return { busy: true };
  try {
    const { results: running } = await db.prepare("SELECT * FROM executions WHERE state = 'running'").all();
    let stillRunning = 0;
    for (const ex of running) if (!(await checkExecution(env, cfg, ex, now, fetchImpl))) stillRunning++;
    if (stillRunning) return { running: stillRunning };
    if (!cfg.buildsEnabled) return { disabled: true };
    return await startNext(env, cfg, now, fetchImpl);
  } finally {
    await db.prepare("DELETE FROM meta WHERE key = 'pump' AND value = ?").bind(token).run();
  }
}

/** Cron housekeeping: old rate-limit rows. */
export async function housekeeping(env, now = Date.now()) {
  await env.DB.prepare("DELETE FROM ratelimit WHERE win < ?").bind(Math.floor(now / 60_000) - 2).run();
}
