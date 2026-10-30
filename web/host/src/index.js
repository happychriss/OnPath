// The web planner's host (docs/design/web-planner.md §9): one hostname for everything the page reads, so the browser
// needs no CORS and no app key. Cloudflare Access (e-mail code) guards the whole hostname during the test; the
// workers.dev and preview addresses are off (wrangler.toml), so there is no way around it.
//   /data/<key>       map cells straight from the maps R2 bucket – the same files and code as the app's /v1/files
//   /segments/<rd5>   BRouter's rd5 files from brouter.de, passed through with Range (brouter.de sends no CORS
//                     header, so the browser cannot read it directly; the app downloads from there too)
//   /sync/v1/…        the plan sync mailbox (src/sync.js, docs/design/web-sync.md) – encrypted blobs only; Access
//                     lets this path through (the phone app cannot log in), the mailbox token guards it
//   /maps/v1/cells/… map areas on demand: request + status passed to the doorman with the app key added here
//                     (src/maps.js, web-planner.md §17) – the browser never holds the key
//   /.well-known/assetlinks.json  Android App Links: plan links (#p=) and pairing links (#pair=) open OnPath (A-016)
//   everything else   the built page (web/app/dist, static assets)
// Before the login goes (A-011, web-planner.md §18): per-IP limits for /data/ (DATA_RATE) and /segments/
// (SEGMENTS_RATE) – generous for planning, against mass downloads; and search engines are kept out until launch
// (X-Robots-Tag on every answer + the page's meta robots) – one switch: INDEXING = "on" in wrangler.toml [vars].
import { serveFile } from "../../../tools/cloud/worker/src/api.js";
import { handleSync, purgeIdle } from "./sync.js";
import { handleMaps, purgeQuota } from "./maps.js";

// Android App Links (A-016): the app's signing certificate (release key, public – not a secret). A later Play Store
// signing key is added as a second fingerprint in the same list.
export const ASSET_LINKS = JSON.stringify([{
  relation: ["delegate_permission/common.handle_all_urls"],
  target: { namespace: "android_app", package_name: "com.onpath.app",
    sha256_cert_fingerprints: ["21:F8:7F:27:34:AA:30:2E:9F:82:C5:8E:56:F3:B0:17:5E:20:D1:01:E2:FA:29:2A:D1:D3:31:DD:77:16:6D:C7"] },
}]);

const RD5 = /^\/segments\/([EW]\d{1,3}_[NS]\d{1,2}\.rd5)$/;
const UPSTREAM = "https://brouter.de/brouter/segments4/";

/** Every answer: noindex until launch; at launch (INDEXING = "on") the page's meta robots is taken out instead. */
export async function handle(request, env, fetchImpl = fetch) {
  const r = await route(request, env, fetchImpl);
  if (env.INDEXING === "on") {
    const html = (r.headers.get("content-type") ?? "").includes("text/html");
    // HTMLRewriter exists on Cloudflare (not in the Node tests)
    return html && typeof HTMLRewriter !== "undefined" ? new HTMLRewriter().on('meta[name="robots"]', { element: (e) => e.remove() }).transform(r) : r;
  }
  const out = new Response(r.body, r); // headers of an assets / fetch answer are read-only
  out.headers.set("x-robots-tag", "noindex, nofollow");
  return out;
}

const tooMany = () => new Response("too many requests – try again in a minute", { status: 429, headers: { "retry-after": "60", "cache-control": "no-store" } });

async function route(request, env, fetchImpl) {
  const url = new URL(request.url);
  const p = url.pathname;
  if (p.startsWith("/data/") || p.startsWith("/segments/")) {
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("method not allowed", { status: 405 });
    const limit = p.startsWith("/data/") ? env.DATA_RATE : env.SEGMENTS_RATE;
    if (limit && !(await limit.limit({ key: request.headers.get("cf-connecting-ip") ?? "?" })).success) return tooMany();
  }
  if (p === "/.well-known/assetlinks.json") {
    return new Response(ASSET_LINKS, { headers: { "content-type": "application/json", "cache-control": "public, max-age=3600" } });
  }
  if (p.startsWith("/.well-known/")) return new Response("not found", { status: 404 });
  if (p.startsWith("/sync/")) return handleSync(request, env);
  if (p.startsWith("/maps/")) return handleMaps(request, env, fetchImpl);
  if (p.startsWith("/data/")) return serveFile({ BUCKET: env.MAPS }, request, decodeURIComponent(p.slice(6)));
  if (p.startsWith("/segments/")) {
    const m = RD5.exec(p);
    if (!m) return new Response("not found", { status: 404 });
    const headers = new Headers({ "user-agent": "OnPath-web-planner (+https://github.com/happychriss/OnPath)" });
    const range = request.headers.get("range");
    if (range) headers.set("range", range);
    const r = await fetchImpl(UPSTREAM + m[1], { method: request.method, headers, cf: { cacheEverything: true, cacheTtl: 86400 } });
    const out = new Headers();
    for (const h of ["content-type", "content-length", "content-range", "accept-ranges", "etag", "last-modified"]) {
      const v = r.headers.get(h);
      if (v) out.set(h, v);
    }
    out.set("cache-control", "public, max-age=86400");
    return new Response(r.body, { status: r.status, headers: out });
  }
  return env.ASSETS.fetch(request);
}

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (_event, env, ctx) => ctx.waitUntil(Promise.all([purgeIdle(env), purgeQuota(env)])),
};
