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

const PATHS = { "/maps/v1/cells/request": "POST", "/maps/v1/cells/status": "GET" };
export const MAX_IDS = 12;
const REASONS = new Set(["point", "near", "corridor", "refresh"]);
const CELL = /^10-\d{1,4}-\d{1,4}$/;
const MAX_BODY = 2048;
const UPSTREAM_MS = 15_000;

const json = (body, status = 200, extra = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...extra } });

const validIds = (ids) => Array.isArray(ids) && ids.length >= 1 && ids.length <= MAX_IDS &&
  ids.every((s) => typeof s === "string" && CELL.test(s) && s.split("-").slice(1).every((n) => Number(n) < 1024));

export async function handleMaps(request, env, fetchImpl = fetch) {
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
  if (want === "POST") {
    const text = await request.text();
    if (text.length > MAX_BODY) return json({ error: "request too large" }, 413);
    let b;
    try { b = JSON.parse(text); } catch { return json({ error: "JSON body expected" }, 400); } // expected: a bad body is the caller's error
    const reason = b?.reason ?? "point";
    if (!validIds(b?.cells) || !REASONS.has(reason)) return json({ error: `cells: 1–${MAX_IDS} zoom-10 cell ids; reason: point, near, corridor, refresh` }, 400);
    body = JSON.stringify({ cells: [...new Set(b.cells)], reason, formats: [2] }); // rebuilt: nothing else passes
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
  return new Response(await r.text(), { status: r.status, headers: out });
}
