// Map areas on demand for the web planner (docs/design/web-planner.md §17): a small proxy to the doorman's build
// requests, so the browser never holds the app key (docs/design/map-access.md). Only two calls pass, in the doorman's
// own shape (map-cells.md §8):
//   POST /maps/v1/cells/request   {"cells": ["10-543-361"], "reason": "point", "formats": [2]}
//   GET  /maps/v1/cells/status?ids=10-543-361,…
// The host adds X-OnPath-Key from its secret MAP_KEY (never logged, never answered back). Upstream: the doorman Worker
// through the service binding DOORMAN when bound, else https://<MAPS_HOST>/v1/…. Files are not fetched here – the page
// reads the built cells in place through /data/ (index.js).
// Limits on top of the doorman's own: MAPS_RATE (per IP) and MAPS_RATE_ALL (all browsers together) – Workers rate
// limit bindings; at most MAX_IDS cells per call, only zoom-10 cells, only the doorman's reasons.
// Without MAP_KEY (owner step not done yet) every call answers 503 {"error":"not available","code":"no-key"}: the page
// says the detailed map is not available on the web yet and keeps the overview – nothing hangs.
// Quotas for NEW map areas (public page, A-011 / web-planner.md §18), counted in the sync D1 database (binding SYNC,
// table map_quota – counts only, no cell id or position): per browser (its random id, header X-OnPath-Browser) per day
// and per month, per IP per day (the IP only as a salted hash), and for everyone per day. Counted are the cells the
// doorman answers as queued / building (new work); a browser over its quota gets 429 {"quota": "browser" | "ip" |
// "global"} and the page says so plainly. Defaults below, changeable per environment (wrangler.toml [vars]).

const PATHS = { "/maps/v1/cells/request": "POST", "/maps/v1/cells/status": "GET" };
export const MAX_IDS = 12;
const REASONS = new Set(["point", "near", "corridor", "refresh"]);
const CELL = /^10-\d{1,4}-\d{1,4}$/;
const MAX_BODY = 2048;
const UPSTREAM_MS = 15_000;
export const QUOTA = { browserDay: 15, browserMonth: 60, ipDay: 40, globalDay: 200 };
const BROWSER = /^[0-9a-f]{32}$/;

const quota = (env) => ({
  browserDay: Number(env.MAPS_QUOTA_BROWSER_DAY) || QUOTA.browserDay,
  browserMonth: Number(env.MAPS_QUOTA_BROWSER_MONTH) || QUOTA.browserMonth,
  ipDay: Number(env.MAPS_QUOTA_IP_DAY) || QUOTA.ipDay,
  globalDay: Number(env.MAPS_QUOTA_GLOBAL_DAY) || QUOTA.globalDay,
});

async function hex(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

/** The IP in a quota counter: SHA-256 of the host's secret MAP_KEY, the day and the IP – never the plain address; it
 *  changes every day and cannot be traced back without the secret. Also used by the sync mailbox cap (sync.js). */
export const ipHash = (env, ip, day) => hex(`${env.MAP_KEY}|${day}|${ip}`);

/** The counters a new-area request touches: [key, period, limit, name]. */
async function counters(env, ip, browser, now) {
  const day = new Date(now).toISOString().slice(0, 10), month = day.slice(0, 7);
  const q = quota(env);
  const list = [["all", day, q.globalDay, "global"], ["ip:" + await ipHash(env, ip, day), day, q.ipDay, "ip"]];
  if (browser) list.push(["b:" + browser, day, q.browserDay, "browser"], ["b:" + browser, month, q.browserMonth, "browser"]);
  return list;
}

/** The first counter at its limit, or null. */
async function overQuota(db, list) {
  for (const [k, period, limit, name] of list) {
    const n = (await db.prepare("SELECT n FROM map_quota WHERE k = ?1 AND period = ?2").bind(k, period).first("n")) ?? 0;
    if (n >= limit) return name;
  }
  return null;
}

async function count(db, list, n) {
  if (n <= 0) return;
  await db.batch(list.map(([k, period]) => db.prepare(
    "INSERT INTO map_quota (k, period, n) VALUES (?1, ?2, ?3) ON CONFLICT(k, period) DO UPDATE SET n = n + ?3").bind(k, period, n)));
}

/** Daily (index.js scheduled): counters of earlier days / months are deleted. */
export async function purgeQuota(env, now = Date.now()) {
  if (!env.SYNC) return;
  const day = new Date(now - 2 * 86_400_000).toISOString().slice(0, 10);
  const month = new Date(now).toISOString().slice(0, 7);
  await env.SYNC.prepare("DELETE FROM map_quota WHERE (length(period) = 10 AND period < ?1) OR (length(period) = 7 AND period < ?2)").bind(day, month).run();
}

const json = (body, status = 200, extra = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...extra } });

const validIds = (ids) => Array.isArray(ids) && ids.length >= 1 && ids.length <= MAX_IDS &&
  ids.every((s) => typeof s === "string" && CELL.test(s) && s.split("-").slice(1).every((n) => Number(n) < 1024));

export async function handleMaps(request, env, fetchImpl = fetch, now = Date.now()) {
  const url = new URL(request.url);
  const want = PATHS[url.pathname];
  if (!want) return json({ error: "not found" }, 404);
  if (request.method !== want) return json({ error: "method not allowed" }, 405, { allow: want });
  if (!env.MAP_KEY) return json({ error: "not available", code: "no-key" }, 503);
  const ip = request.headers.get("cf-connecting-ip") ?? "?";
  for (const [limit, key] of [[env.MAPS_RATE, ip], [env.MAPS_RATE_ALL, "all"]]) {
    if (limit && !(await limit.limit({ key })).success) return json({ error: "too many requests" }, 429, { "retry-after": "60" });
  }

  let body;
  let target = url.pathname.slice("/maps".length); // /v1/cells/…
  let quotaKeys = null;
  if (want === "POST") {
    const text = await request.text();
    if (text.length > MAX_BODY) return json({ error: "request too large" }, 413);
    let b;
    try { b = JSON.parse(text); } catch { return json({ error: "JSON body expected" }, 400); } // expected: a bad body is the caller's error
    const reason = b?.reason ?? "point";
    if (!validIds(b?.cells) || !REASONS.has(reason)) return json({ error: `cells: 1–${MAX_IDS} zoom-10 cell ids; reason: point, near, corridor, refresh` }, 400);
    body = JSON.stringify({ cells: [...new Set(b.cells)], reason, formats: [2] }); // rebuilt: nothing else passes
    if (env.SYNC) {
      const browser = (request.headers.get("x-onpath-browser") ?? "").toLowerCase();
      quotaKeys = await counters(env, ip, BROWSER.test(browser) ? browser : null, now);
      const over = await overQuota(env.SYNC, quotaKeys);
      if (over) return json({ error: "quota reached", quota: over }, 429);
    }
  } else {
    const ids = (url.searchParams.get("ids") ?? "").split(",").filter(Boolean);
    if (!validIds(ids)) return json({ error: `ids: 1–${MAX_IDS} zoom-10 cell ids` }, 400);
    target += "?ids=" + [...new Set(ids)].join(",") + "&format=2";
  }

  const headers = new Headers({ "x-onpath-key": env.MAP_KEY, "x-onpath-app": "web-planner", "content-type": "application/json" });
  // through the binding the doorman sees the browser's address, so its own per-IP limit counts each browser
  if (env.DOORMAN) headers.set("cf-connecting-ip", ip);
  const init = { method: want, headers, body, signal: AbortSignal.timeout(UPSTREAM_MS) };
  let r;
  try {
    r = env.DOORMAN ? await env.DOORMAN.fetch(new Request("https://doorman" + target, init))
      : env.MAPS_HOST ? await fetchImpl(`https://${env.MAPS_HOST}${target}`, init) : null;
  } catch (e) {
    console.warn(`maps proxy: doorman not reachable (${String(e).slice(0, 120)})`); // the page retries later
    return json({ error: "map server not reachable" }, 502);
  }
  if (!r) return json({ error: "not available", code: "no-upstream" }, 503);
  // the doorman refused our key (rotated?) – the browser cannot fix that: same answer as no key, plus a log line
  if (r.status === 401) {
    console.error("maps proxy: the doorman refused MAP_KEY (401) – update the planner's secret");
    return json({ error: "not available", code: "key-refused" }, 503);
  }
  const out = { "content-type": "application/json", "cache-control": "no-store" };
  const retry = r.headers.get("retry-after");
  if (retry) out["retry-after"] = retry;
  const text = await r.text();
  if (quotaKeys && r.ok) { // new work = the cells the doorman now queues or builds (not the ones already there)
    let fresh = 0;
    try { fresh = (JSON.parse(text).cells ?? []).filter((c) => c.state === "queued" || c.state === "building").length; }
    catch { fresh = 0; } // expected: an odd answer from the doorman – passed on, nothing counted
    try { await count(env.SYNC, quotaKeys, fresh); }
    catch (e) { console.warn(`maps proxy: quota not counted (${String(e).slice(0, 120)})`); }
  }
  return new Response(text, { status: r.status, headers: out });
}
