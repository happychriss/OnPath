// HTTP API /v1 (docs/design/map-cells.md §8).
import { config } from "./config.js";
import { parseCellId } from "./cells.js";
import { globalFetch } from "./gcp.js";
import { HttpError, log, pickFormat, pump, requestCells, statusFor } from "./queue.js";

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

export const json = (body, status = 200, headers = {}) =>
  new Response(JSON.stringify(body), { status, headers: { ...JSON_HEADERS, ...headers } });

const enc = new TextEncoder();

async function sha(s) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s)));
}

/** Constant-time check of X-OnPath-Key against the comma-separated secret APP_KEYS (rotation: old,new). */
export async function keyOk(env, given) {
  const keys = String(env.APP_KEYS || "").split(",").map((k) => k.trim()).filter(Boolean);
  if (!given || !keys.length) return false;
  const g = await sha(given);
  let ok = false;
  for (const k of keys) {
    const h = await sha(k);
    let diff = 0;
    for (let i = 0; i < h.length; i++) diff |= h[i] ^ g[i];
    ok = ok || diff === 0;
  }
  return ok;
}

/** Fixed-window counter per IP and minute in D1. Fails open (a broken counter must not block the app). */
export async function rateLimited(env, cfg, ip, now = Date.now()) {
  if (!cfg.rateLimitPerMin) return false;
  const win = Math.floor(now / 60_000);
  try {
    const r = await env.DB.prepare(`INSERT INTO ratelimit (key, win, count) VALUES (?1, ?2, 1)
        ON CONFLICT (key) DO UPDATE SET count = CASE WHEN win = ?2 THEN count + 1 ELSE 1 END, win = ?2
        RETURNING count`).bind(`ip:${ip}`, win).first();
    return r.count > cfg.rateLimitPerMin;
  } catch (e) {
    log({ ev: "ratelimit-error", err: String(e) });
    return false;
  }
}

const FILE_KEY = /^(cells|overview)\/[A-Za-z0-9._\-/]+$/;

function fileHeaders(key, obj) {
  const h = new Headers();
  obj.writeHttpMetadata?.(h);
  h.set("etag", obj.httpEtag);
  h.set("accept-ranges", "bytes");
  // version folders (…/2026-10-17/…) never change; current.json does
  h.set("cache-control", /\/\d{4}-\d{2}-\d{2}\//.test(key) ? "public, max-age=31536000, immutable" : "no-cache");
  if (!h.has("content-type")) h.set("content-type", key.endsWith(".json") ? "application/json" : "application/octet-stream");
  return h;
}

/** GET/HEAD /v1/files/<key> straight from R2, with Range (resume, R-02) and If-None-Match. */
export async function serveFile(env, request, key) {
  if (!FILE_KEY.test(key) || key.split("/").some((p) => p === "" || p === "." || p === "..")) {
    return json({ error: "not found" }, 404);
  }
  if (request.method === "HEAD") {
    const o = await env.BUCKET.head(key);
    if (!o) return json({ error: "not found" }, 404);
    const h = fileHeaders(key, o);
    h.set("content-length", String(o.size));
    return new Response(null, { headers: h });
  }
  let obj;
  try {
    obj = await env.BUCKET.get(key, { range: request.headers, onlyIf: request.headers });
  } catch (e) {
    // R2 throws on a range it cannot satisfy
    const o = await env.BUCKET.head(key);
    if (!o) return json({ error: "not found" }, 404);
    return new Response(null, { status: 416, headers: { "content-range": `bytes */${o.size}` } });
  }
  if (!obj) return json({ error: "not found" }, 404);
  const h = fileHeaders(key, obj);
  if (!("body" in obj) || obj.body === undefined) return new Response(null, { status: 304, headers: h });
  const r = obj.range;
  if (request.headers.has("range") && r) {
    const offset = r.offset ?? (r.suffix !== undefined ? obj.size - r.suffix : 0);
    const length = r.length ?? (r.suffix !== undefined ? r.suffix : obj.size - offset);
    h.set("content-range", `bytes ${offset}-${offset + length - 1}/${obj.size}`);
    h.set("content-length", String(length));
    return new Response(obj.body, { status: 206, headers: h });
  }
  h.set("content-length", String(obj.size));
  return new Response(obj.body, { headers: h });
}

async function manifest(env, cfg, url, z, x, y) {
  const fmt = url.searchParams.has("format") ? pickFormat(cfg, [Number(url.searchParams.get("format"))]) : pickFormat(cfg);
  if (fmt === null) return json({ error: "format not served" }, 404);
  const base = `cells/f${fmt}/${z}/${x}/${y}`;
  const cur = await env.BUCKET.get(`${base}/current.json`);
  if (!cur) return json({ error: "not ready" }, 404);
  const { version } = JSON.parse(await cur.text());
  const m = await env.BUCKET.get(`${base}/${version}/manifest.json`);
  if (!m) return json({ error: "not ready" }, 404);
  return new Response(m.body, { headers: { ...JSON_HEADERS, "cache-control": "no-cache", etag: m.httpEtag } });
}

export async function handle(request, env, ctx, fetchImpl = globalFetch) {
  const cfg = config(env);
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method;
  try {
    if (path === "/v1/health" && method === "GET") {
      const q = await env.DB.prepare(`SELECT SUM(CASE WHEN job = 'queued' THEN 1 ELSE 0 END) AS queued,
          SUM(CASE WHEN job = 'building' THEN 1 ELSE 0 END) AS building FROM cells`).first();
      return json({ ok: true, env: cfg.environment, queue: q?.queued || 0, building: q?.building || 0,
        builds_enabled: cfg.buildsEnabled, formats: cfg.formats });
    }
    if (!path.startsWith("/v1/")) return json({ error: "not found" }, 404);
    if (!env.APP_KEYS) return json({ error: "server not configured" }, 503); // fail closed
    if (!(await keyOk(env, request.headers.get("x-onpath-key")))) return json({ error: "unknown app key" }, 401);

    if (path.startsWith("/v1/files/") && (method === "GET" || method === "HEAD")) {
      return await serveFile(env, request, decodeURIComponent(path.slice("/v1/files/".length)));
    }
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    if (await rateLimited(env, cfg, ip)) return json({ error: "too many requests" }, 429, { "retry-after": "60" });

    if (path === "/v1/cells/request" && method === "POST") {
      let body;
      try {
        body = await request.json();
      } catch {
        throw new HttpError(400, "JSON body expected");
      }
      const res = await requestCells(env, cfg, body);
      if (res.cells.some((c) => c.state === "queued")) ctx?.waitUntil?.(pump(env, cfg, Date.now(), fetchImpl).catch((e) => log({ ev: "pump-error", err: String(e) })));
      return json(res);
    }
    if (path === "/v1/cells/status" && method === "GET") {
      const ids = (url.searchParams.get("ids") || "").split(",").map((s) => s.trim()).filter(Boolean);
      if (!ids.length || ids.length > cfg.maxCellsPerRequest) throw new HttpError(400, `ids: 1–${cfg.maxCellsPerRequest} cell ids`);
      const cells = ids.map(parseCellId);
      if (cells.some((c) => !c)) throw new HttpError(400, "bad cell id");
      const fmt = url.searchParams.has("format") ? pickFormat(cfg, [Number(url.searchParams.get("format"))]) : pickFormat(cfg);
      if (fmt === null) throw new HttpError(400, "format not served");
      return json(await statusFor(env, cfg, [...new Set(cells.map((c) => c.id))], fmt));
    }
    const m = /^\/v1\/cells\/(\d+)\/(\d+)\/(\d+)\/manifest$/.exec(path);
    if (m && method === "GET") {
      const c = parseCellId(`${m[1]}-${m[2]}-${m[3]}`);
      if (!c) throw new HttpError(400, "bad cell id");
      return await manifest(env, cfg, url, c.z, c.x, c.y);
    }
    return json({ error: "not found" }, 404);
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status, e.headers);
    log({ ev: "error", path, err: String(e?.stack || e).slice(0, 500) });
    return json({ error: "internal error" }, 500);
  }
}
