// OnPath doorman – Cloudflare Worker (docs/design/map-hosting.md).
//   fetch:     the /v1 API for the app (map-cells.md §8)
//   scheduled: every minute – check the running Cloud Run execution, start the next one, housekeeping;
//              once a day (03:17 UTC) the storage cleanup (cleanup.js)
import { handle } from "./api.js";
import { config } from "./config.js";
import { housekeeping, log, pump } from "./queue.js";
import { cleanupDue, cleanupStorage } from "./cleanup.js";

export default {
  fetch(request, env, ctx) {
    return handle(request, env, ctx);
  },
  async scheduled(event, env, ctx) {
    const cfg = config(env);
    ctx.waitUntil((async () => {
      try {
        const r = await pump(env, cfg);
        if (r && !r.idle && !r.busy) log({ ev: "pump", ...r });
        await housekeeping(env);
        const now = Date.now();
        if (cleanupDue(now)) log({ ev: "cleanup", ...(await cleanupStorage(env, now)) });
      } catch (e) {
        log({ ev: "pump-error", err: String(e?.stack || e).slice(0, 500) });
      }
    })());
  },
};
