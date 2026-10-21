// Live ETA (docs/design/map-hosting.md §7f): when will each queued / building cell be ready?
// A small simulation of the pump: the running executions' remaining work, then the queue in pump order on the free
// build slots. Times come from measured builds (table builds, rolling average) instead of fixed guesses.
import { parseCellId } from "./cells.js";
import { shortName } from "./gcp.js";

/** Priorities 0..WAITING_MAX = someone is waiting for the map (point, near); higher = background. */
export const WAITING_MAX = 1;
export const isWaiting = (priority) => priority <= WAITING_MAX;

const avg = (a) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : null);

/** Rolling averages of the last cfg.etaSamples measured builds: build time per cache state, download rate. */
export async function buildStats(db, cfg) {
  const { results } = await db.prepare(`SELECT build_s, new_source, download_s, download_mb FROM builds
      WHERE build_s IS NOT NULL ORDER BY finished_at DESC LIMIT ?`).bind(cfg.etaSamples * 4).all();
  const last = (f) => results.filter(f).slice(0, cfg.etaSamples);
  const all = avg(last(() => true).map((r) => r.build_s));
  const cached = avg(last((r) => !r.new_source).map((r) => r.build_s));
  const fresh = avg(last((r) => r.new_source).map((r) => r.build_s));
  const dl = last((r) => r.download_s > 0 && r.download_mb > 0);
  const mb = dl.reduce((s, r) => s + r.download_mb, 0);
  const secs = dl.reduce((s, r) => s + r.download_s, 0);
  const rate = secs > 0 ? mb / secs : null; // MB/s
  const typicalMb = dl.length ? mb / dl.length : null;
  return {
    buildCached: cached ?? all ?? cfg.avgCellS,
    buildNew: fresh ?? all ?? cfg.avgCellS,
    // an extract we have not got: its typical size ÷ measured rate (+ index/lock overhead); else the configured guess
    download: rate && typicalMb ? typicalMb / rate + 15 : cfg.newAreaS,
    rate,
    typicalMb,
  };
}

function neighbours(id) {
  const c = parseCellId(id);
  if (!c) return [id];
  const out = [];
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) out.push(`${c.z}-${c.x + dx}-${c.y + dy}`);
  return out;
}

/** Effective number of build slots: cfg.parallelBuilds, or 1 while Google refused a parallel start (meta row). */
export async function slotCount(db, cfg, now) {
  if (cfg.parallelBuilds <= 1) return 1;
  const b = await db.prepare("SELECT until FROM meta WHERE key = 'parallel-blocked'").first();
  return b && b.until > now ? 1 : cfg.parallelBuilds;
}

/** Cells per execution for waiting cells: spread over the free slots (faster), at most cellsPerExecution. */
export function batchSize(cfg, waiting, freeSlots) {
  return Math.max(1, Math.min(cfg.cellsPerExecution, Math.ceil(waiting / Math.max(1, freeSlots))));
}

/**
 * Finish time (ms) of every queued / building cell, keyed "<format>:<id>", plus the builder's step/progress for
 * building ones. readStatus(env, format, id) → the builder's status file or null.
 */
export async function plan(env, cfg, now, readStatus) {
  const db = env.DB;
  const stats = await buildStats(db, cfg);
  const slotsN = await slotCount(db, cfg, now);
  const { results: active } = await db.prepare(`SELECT id, format, job, priority, execution FROM cells
      WHERE job IN ('queued', 'building') ORDER BY priority, requested_at`).all();
  const { results: built } = await db.prepare("SELECT id FROM cells WHERE version IS NOT NULL").all();
  const { results: running } = await db.prepare(
    "SELECT name, format, cells, started_at FROM executions WHERE state = 'running' ORDER BY started_at").all();
  const { results: recent } = await db.prepare("SELECT started_at FROM executions WHERE started_at > ?")
    .bind(now - 3_600_000).all();
  const builtSet = new Set(built.map((r) => r.id));
  const buildingSet = new Set(active.filter((r) => r.job === "building").map((r) => r.id));
  const row = new Map(active.map((r) => [`${r.format}:${r.id}`, r]));
  const dlDone = new Map(); // cell id → when the extract for its area is (expected to be) in the bucket
  const out = new Map();

  // extract cached? Built cells around it (or itself) → yes; another cell around it being built → that one fetches it
  const cachedNear = (id) => neighbours(id).some((n) => builtSet.has(n) || (n !== id && buildingSet.has(n)));
  /** seconds a not yet started cell waits for its extract when it starts at t (ms) */
  const downloadWait = (id, t) => {
    if (cachedNear(id)) return 0;
    let done = null;
    for (const n of neighbours(id)) if (dlDone.has(n)) done = Math.min(done ?? Infinity, dlDone.get(n));
    if (done !== null) return Math.max(0, (done - t) / 1000);
    dlDone.set(id, t + stats.download * 1000);
    return stats.download;
  };
  const buildS = (newSource) => (newSource ? stats.buildNew : stats.buildCached);

  // 1. running executions: their cells one after the other
  const slots = [];
  for (const ex of running) {
    const ids = JSON.parse(ex.cells || "[]");
    const short = shortName(ex.name);
    const st = await Promise.all(ids.map(async (id) => {
      const s = await readStatus(env, ex.format, id);
      return s && s.execution === short ? s : null;
    }));
    const elapsed = (now - ex.started_at) / 1000;
    let t = now + (st.some(Boolean) ? 0 : Math.max(10, cfg.startupS - elapsed) * 1000);
    ids.forEach((id, i) => {
      const r = row.get(`${ex.format}:${id}`);
      const s = st[i];
      if (!r || r.job !== "building" || s?.state === "ready" || s?.state === "failed") return;
      let step = "starting";
      let progress = 0;
      if (s?.state === "building") {
        step = s.step || "starting";
        progress = typeof s.progress === "number" ? s.progress : 0;
        const dl = step === "download" && typeof s.source_eta_s === "number" ? s.source_eta_s : 0;
        if (dl) dlDone.set(id, now + dl * 1000);
        t += (dl + (1 - progress) * buildS(dl > 0 || !cachedNear(id))) * 1000;
      } else {
        const w = downloadWait(id, t);
        t += (w + buildS(w > 0)) * 1000;
      }
      out.set(`${ex.format}:${id}`, { finishAt: t, step, progress });
    });
    slots.push(t);
  }
  while (slots.length < slotsN) slots.push(now);

  // 2. the queue, as the pump will start it: waiting cells spread over the slots, background batched
  const starts = recent.map((r) => r.started_at);
  const queued = active.filter((r) => r.job === "queued");
  let waitingLeft = queued.filter((r) => isWaiting(r.priority)).length;
  const position = new Map();
  for (let i = 0; i < queued.length;) {
    slots.sort((a, b) => a - b);
    let start = slots.shift();
    start = start > now ? start + cfg.pumpLagS * 1000 : now;
    // cost guard: no more than maxExecPerHour starts within any hour
    const inHour = starts.filter((s) => s > start - 3_600_000).sort((a, b) => a - b);
    if (inHour.length >= cfg.maxExecPerHour) start = inHour[inHour.length - cfg.maxExecPerHour] + 3_600_000;
    starts.push(start);
    const head = queued[i];
    const waiting = isWaiting(head.priority);
    const n = waiting ? batchSize(cfg, waitingLeft, slotsN) : cfg.cellsPerExecution;
    let t = start + cfg.startupS * 1000;
    let k = 0;
    while (k < n && i < queued.length && queued[i].format === head.format && isWaiting(queued[i].priority) === waiting) {
      const c = queued[i++];
      const w = downloadWait(c.id, t);
      t += (w + buildS(w > 0)) * 1000;
      position.set(c.format, (position.get(c.format) || 0) + 1);
      out.set(`${c.format}:${c.id}`, { finishAt: t, position: position.get(c.format) });
      if (waiting) waitingLeft--;
      k++;
    }
    slots.push(t);
  }
  return { cells: out, stats, slots: slotsN };
}

/** Move the last reported finish time towards the new estimate (no jumps): fully after cfg.etaSmoothS seconds,
 *  at least a quarter of the way per answer. A finish time already passed is replaced at once. */
export function smooth(cfg, prevAt, seenAt, rawAt, now) {
  if (!cfg.etaSmoothS || !prevAt || !seenAt || prevAt <= now) return rawAt;
  const a = Math.min(1, Math.max(0.25, (now - seenAt) / 1000 / cfg.etaSmoothS));
  return prevAt + a * (rawAt - prevAt);
}
