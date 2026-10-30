// The plan sync mailbox (docs/design/web-sync.md §8): phone and browser leave encrypted blobs here for each other.
// The server sees a random mailbox id, opaque blobs with random item ids, revision numbers, sizes and times – no
// plan, no name, no e-mail, no key. A mailbox is claimed by its first writer: the server keeps only SHA-256 of the
// mailbox token (derived from the pairing secret on the devices) and refuses every other token.
//
//   POST   /sync/v1/<mailbox>          claim (first time) or check the token          → {rev}
//   GET    /sync/v1/<mailbox>?since=N  items changed after revision N (pages of 200)   → {rev, more, items:[{id, rev, blob}]}
//   PUT    /sync/v1/<mailbox>/<item>   body = blob, header x-onpath-base = the revision the writer saw (0 = new item)
//                                      → {rev}; 409 {rev} when someone wrote first (read, merge, try again)
//   DELETE /sync/v1/<mailbox>          unpair: all items gone, the mailbox answers 410 from then on
//
// "Phone last synced" (owner 2026-10-30): POST / GET answers carry appSeen = when the phone app last used this
// mailbox (ms, to the minute; null = not yet). The app needs no change: a request is the app's unless it comes from
// the web planner (header x-onpath-client: web, or a browser user agent "Mozilla/…"). Stored as mailbox.app_seen,
// written at most once a minute; no IP, no device id.
//
// Auth: "Authorization: Bearer <token>". Cloudflare Access must let /sync/* through (bypass policy) – the phone app
// cannot pass Access; the token is the lock here. Storage: D1 (binding SYNC), blobs as Base64url text.
//
// New mailboxes per IP per day are capped (var SYNC_NEW_PER_IP_DAY, default LIMITS.newPerIpDay), counted like the
// map-area quota (maps.js): table map_quota, k = "sync:<daily salted hash of the IP>", period = the UTC day – counts
// only, old days deleted by the daily purgeQuota. Over the cap a claim answers 429 {error, code: "new-mailbox-cap"}
// with retry-after = seconds to the next UTC day. Only a claim that creates a mailbox counts; another device using an
// existing mailbox (pairing) does not.
import { ipHash } from "./maps.js";

export const LIMITS = {
  blobBytes: 32 * 1024,       // one item (a plan of 500 points is ~3 KB)
  items: 1000,                // per mailbox, tombstones and conflict copies included
  mailboxBytes: 4 * 1024 * 1024,
  page: 200,
  idleDays: 365,              // a mailbox nobody used for this long is deleted (scheduled job)
  newPerIpDay: 5,             // new mailboxes (pairings) per IP per UTC day
};
export const NEW_MAILBOX_CAP = "new-mailbox-cap";

const ID = /^[A-Za-z0-9_-]{22}$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const DAY = 86_400_000;
const MINUTE = 60_000;

const json = (status, body, extra = {}) => new Response(JSON.stringify(body), {
  status, headers: { "content-type": "application/json", "cache-control": "no-store", ...extra },
});
const fail = (status, error) => json(status, { error });

async function sha256(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return [...d].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function sameText(a, b) { // constant time for equal lengths
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
function b64url(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function handleSync(request, env, now = Date.now()) {
  const url = new URL(request.url);
  const m = /^\/sync\/v1\/([^/]+)(?:\/([^/]+))?$/.exec(url.pathname);
  if (!m || !ID.test(m[1]) || (m[2] !== undefined && !ID.test(m[2]))) return fail(404, "not found");
  if (!env.SYNC) return fail(503, "sync is not set up on this server");
  const ip = request.headers.get("cf-connecting-ip") ?? "?";
  if (env.SYNC_RATE) {
    const { success } = await env.SYNC_RATE.limit({ key: ip });
    if (!success) return fail(429, "too many requests – try again in a minute");
  }
  const [, box, item] = m;
  const token = /^Bearer (.+)$/.exec(request.headers.get("authorization") ?? "")?.[1] ?? "";
  if (!TOKEN.test(token)) return fail(401, "missing token");
  const hash = await sha256(token);
  const db = env.SYNC;
  const row = await db.prepare("SELECT token_hash, rev, deleted, seen, app_seen FROM mailbox WHERE id = ?1").bind(box).first();
  if (row?.deleted) return fail(410, "this pairing was ended");

  if (request.method === "POST" && !item) {
    if (!row) {
      const day = new Date(now).toISOString().slice(0, 10);
      const k = "sync:" + await ipHash(env, ip, day);
      const cap = Number(env.SYNC_NEW_PER_IP_DAY) || LIMITS.newPerIpDay;
      const made = (await db.prepare("SELECT n FROM map_quota WHERE k = ?1 AND period = ?2").bind(k, day).first("n")) ?? 0;
      if (made >= cap) {
        const wait = Math.max(60, Math.ceil((DAY - (now % DAY)) / 1000));
        return json(429, { error: "too many new pairings from this network today – try again tomorrow", code: NEW_MAILBOX_CAP }, { "retry-after": String(wait) });
      }
      const ins = await db.prepare("INSERT INTO mailbox (id, token_hash, rev, created, seen) VALUES (?1, ?2, 0, ?3, ?3) ON CONFLICT(id) DO NOTHING")
        .bind(box, hash, now).run();
      if (ins.meta?.changes) { // this call made the mailbox (not a parallel claim of the same one)
        await db.prepare("INSERT INTO map_quota (k, period, n) VALUES (?1, ?2, 1) ON CONFLICT(k, period) DO UPDATE SET n = n + 1")
          .bind(k, day).run();
      }
      const again = await db.prepare("SELECT token_hash, rev, app_seen FROM mailbox WHERE id = ?1").bind(box).first();
      if (!again || !sameText(again.token_hash, hash)) return fail(403, "wrong token");
      return json(201, { rev: again.rev, appSeen: await appSeen(db, box, now, again, request) });
    }
    if (!sameText(row.token_hash, hash)) return fail(403, "wrong token");
    await touch(db, box, now, row);
    return json(200, { rev: row.rev, appSeen: await appSeen(db, box, now, row, request) });
  }
  if (!row) return fail(404, "no such mailbox");
  if (!sameText(row.token_hash, hash)) return fail(403, "wrong token");

  if (request.method === "GET" && !item) {
    const since = Math.max(0, Number(url.searchParams.get("since") ?? 0) || 0);
    const { results } = await db.prepare("SELECT id, rev, blob FROM item WHERE mailbox = ?1 AND rev > ?2 ORDER BY rev LIMIT ?3")
      .bind(box, since, LIMITS.page + 1).all();
    const more = results.length > LIMITS.page;
    const items = results.slice(0, LIMITS.page);
    await touch(db, box, now, row);
    return json(200, { rev: more ? items[items.length - 1].rev : row.rev, more, items, appSeen: await appSeen(db, box, now, row, request) });
  }

  if (request.method === "PUT" && item) {
    const raw = request.headers.get("x-onpath-base") ?? "";
    if (!/^\d{1,15}$/.test(raw)) return fail(400, "missing x-onpath-base");
    const base = Number(raw);
    const len = Number(request.headers.get("content-length") ?? 0);
    if (len > LIMITS.blobBytes) return fail(413, "item too large");
    await appSeen(db, box, now, row, request);
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.length > LIMITS.blobBytes) return fail(413, "item too large");
    if (body.length < 29) return fail(400, "not a sync item");
    const blob = b64url(body);
    const cur = await db.prepare("SELECT rev, length(blob) AS n FROM item WHERE mailbox = ?1 AND id = ?2").bind(box, item).first();
    if ((cur?.rev ?? 0) !== base) return json(409, { rev: cur?.rev ?? 0 });
    const use = await db.prepare("SELECT COUNT(*) AS items, COALESCE(SUM(length(blob)), 0) AS bytes FROM item WHERE mailbox = ?1").bind(box).first();
    if (!cur && use.items >= LIMITS.items) return fail(507, "too many saved plans – delete some first");
    if (use.bytes - (cur?.n ?? 0) + blob.length > LIMITS.mailboxBytes * 4 / 3) return fail(507, "the saved plans are too large – delete some first");
    // compare-and-set in one transaction: the revision moves only when the item is still at [base]; the item is
    // written only when that happened (changes() = rows the UPDATE changed)
    const [upd] = await db.batch([
      db.prepare("UPDATE mailbox SET rev = rev + 1, seen = ?3 WHERE id = ?1 AND deleted IS NULL AND " +
        "COALESCE((SELECT rev FROM item WHERE mailbox = ?1 AND id = ?2), 0) = ?4").bind(box, item, now, base),
      db.prepare("INSERT INTO item (mailbox, id, rev, blob) SELECT ?1, ?2, rev, ?3 FROM mailbox WHERE id = ?1 AND changes() = 1 " +
        "ON CONFLICT(mailbox, id) DO UPDATE SET rev = excluded.rev, blob = excluded.blob").bind(box, item, blob),
    ]);
    if (!upd.meta.changes) {
      const now2 = await db.prepare("SELECT rev FROM item WHERE mailbox = ?1 AND id = ?2").bind(box, item).first();
      return json(409, { rev: now2?.rev ?? 0 });
    }
    const after = await db.prepare("SELECT rev FROM item WHERE mailbox = ?1 AND id = ?2").bind(box, item).first();
    return json(200, { rev: after.rev });
  }

  if (request.method === "DELETE" && !item) {
    await db.batch([
      db.prepare("DELETE FROM item WHERE mailbox = ?1").bind(box),
      db.prepare("UPDATE mailbox SET deleted = ?2, seen = ?2, token_hash = '' WHERE id = ?1").bind(box, now),
    ]);
    return json(200, { deleted: true });
  }
  return fail(405, "method not allowed");
}

/** A request from the web planner (its header, or any browser) – everything else is the phone app. */
export function fromWeb(request) {
  return request.headers.get("x-onpath-client") === "web" || /^Mozilla\//.test(request.headers.get("user-agent") ?? "");
}

/** When the phone app last used the mailbox (to the minute, written at most once a minute); null = not yet. */
async function appSeen(db, box, now, row, request) {
  if (fromWeb(request)) return row.app_seen ?? null;
  const minute = now - (now % MINUTE);
  if (row.app_seen !== minute) await db.prepare("UPDATE mailbox SET app_seen = ?2 WHERE id = ?1").bind(box, minute).run();
  row.app_seen = minute;
  return minute;
}

/** Last use, at most once a day per mailbox (fewer writes). */
async function touch(db, box, now, row) {
  if (row.seen != null && now - row.seen < DAY) return;
  await db.prepare("UPDATE mailbox SET seen = ?2 WHERE id = ?1").bind(box, now).run();
}

/** Scheduled (daily): deletes mailboxes idle for [LIMITS.idleDays]; ended pairings are forgotten after the same time. */
export async function purgeIdle(env, now = Date.now()) {
  if (!env.SYNC) return 0;
  const before = now - LIMITS.idleDays * DAY;
  const { results } = await env.SYNC.prepare("SELECT id FROM mailbox WHERE seen < ?1 LIMIT 500").bind(before).all();
  for (const { id } of results) {
    await env.SYNC.batch([
      env.SYNC.prepare("DELETE FROM item WHERE mailbox = ?1").bind(id),
      env.SYNC.prepare("DELETE FROM mailbox WHERE id = ?1").bind(id),
    ]);
  }
  return results.length;
}
