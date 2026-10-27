// The web planner's host (docs/design/web-planner.md §9): one hostname for everything the page reads, so the browser
// needs no CORS and no app key. Cloudflare Access (e-mail code) guards the whole hostname during the test; the
// workers.dev and preview addresses are off (wrangler.toml), so there is no way around it.
//   /data/<key>       map cells straight from the maps R2 bucket – the same files and code as the app's /v1/files
//   /segments/<rd5>   BRouter's rd5 files from brouter.de, passed through with Range (brouter.de sends no CORS
//                     header, so the browser cannot read it directly; the app downloads from there too)
//   /sync/v1/…        the plan sync mailbox (src/sync.js, docs/design/web-sync.md) – encrypted blobs only; Access
//                     lets this path through (the phone app cannot log in), the mailbox token guards it
//   everything else   the built page (web/app/dist, static assets)
import { serveFile } from "../../../tools/cloud/worker/src/api.js";
import { handleSync, purgeIdle } from "./sync.js";

const RD5 = /^\/segments\/([EW]\d{1,3}_[NS]\d{1,2}\.rd5)$/;
const UPSTREAM = "https://brouter.de/brouter/segments4/";

export async function handle(request, env, fetchImpl = fetch) {
  const url = new URL(request.url);
  const p = url.pathname;
  if (p.startsWith("/data/") || p.startsWith("/segments/")) {
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("method not allowed", { status: 405 });
  }
  if (p.startsWith("/sync/")) return handleSync(request, env);
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
  scheduled: (_event, env, ctx) => ctx.waitUntil(purgeIdle(env)),
};
