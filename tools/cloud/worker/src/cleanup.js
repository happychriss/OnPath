// Storage cleanup (owner-approved 2026-10-22, docs/design/map-hosting.md §7g), once a day from the cron:
//   - format-1 cells are gone for good (the app reads format 2 only): their objects (cells/f1/…, status/f1/…) and D1 rows
//   - format-2 cells keep their current version and the previous one; older versions whose newest object is older
//     than KEEP_DAYS are deleted (an app that just read the old manifest can still finish its download)
// Bounded per run (MAX_DELETES); whatever is left goes the next day.

export const KEEP_DAYS = 7;
export const MAX_DELETES = 1000;
const DAY = 86_400_000;

/** True once a day (03:17 UTC) – the cron runs every minute. */
export function cleanupDue(now) {
  return Math.floor(now / 60_000) % 1440 === 3 * 60 + 17;
}

async function listAll(bucket, prefix, max = 50_000) {
  const out = [];
  let cursor;
  do {
    const r = await bucket.list({ prefix, cursor });
    out.push(...r.objects);
    cursor = r.truncated ? r.cursor : undefined;
  } while (cursor && out.length < max);
  return out;
}

async function del(bucket, keys, budget) {
  const n = Math.min(keys.length, budget.left);
  for (let i = 0; i < n; i += 1000) await bucket.delete(keys.slice(i, Math.min(n, i + 1000)));
  budget.left -= n;
  return n;
}

/** Returns what was deleted: { f1Objects, f1Rows, oldVersions, oldObjects }. */
export async function cleanupStorage(env, now = Date.now(), { keepDays = KEEP_DAYS, maxDeletes = MAX_DELETES } = {}) {
  const budget = { left: maxDeletes };
  const res = { f1Objects: 0, f1Rows: 0, oldVersions: 0, oldObjects: 0 };

  // 1. format 1
  const f1 = [...(await listAll(env.BUCKET, "cells/f1/")), ...(await listAll(env.BUCKET, "status/f1/"))].map((o) => o.key);
  res.f1Objects = await del(env.BUCKET, f1, budget);
  const r = await env.DB.prepare("DELETE FROM cells WHERE format = 1").run();
  res.f1Rows = r?.meta?.changes ?? 0;

  // 2. superseded format-2 versions: cells/f2/<z>/<x>/<y>/<version>/<file>
  const byCell = new Map(); // base → Map(version → {keys, newest})
  for (const o of await listAll(env.BUCKET, "cells/f2/")) {
    const p = o.key.split("/");
    if (p.length < 7) continue; // current.json and the like
    const base = p.slice(0, 5).join("/");
    const v = p[5];
    if (!byCell.has(base)) byCell.set(base, new Map());
    const vs = byCell.get(base);
    if (!vs.has(v)) vs.set(v, { keys: [], newest: 0 });
    const e = vs.get(v);
    e.keys.push(o.key);
    e.newest = Math.max(e.newest, new Date(o.uploaded ?? 0).getTime());
  }
  for (const [base, vs] of byCell) {
    if (vs.size <= 2 || budget.left <= 0) continue;
    const cur = await env.BUCKET.get(`${base}/current.json`);
    let current = null;
    try { current = cur ? JSON.parse(await cur.text()).version : null; } catch { current = null; } // expected: broken file → keep the newest
    const names = [...vs.keys()].sort();
    const keep = new Set([current ?? names[names.length - 1]]);
    const prev = names.filter((n) => !keep.has(n)).pop();
    if (prev) keep.add(prev);
    for (const n of names) {
      if (keep.has(n)) continue;
      const e = vs.get(n);
      if (now - e.newest < keepDays * DAY) continue;
      const d = await del(env.BUCKET, e.keys, budget);
      res.oldObjects += d;
      if (d === e.keys.length) res.oldVersions++;
    }
  }
  return res;
}
