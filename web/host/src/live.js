// The live tracking box (docs/design/live-tracking.md §6): the phone of someone on a tour leaves sealed blobs here,
// the viewer page (opened from the link he sent by messenger) reads them. The server sees a random box id, opaque
// blobs under fixed names, revision numbers, sizes and times – no place, no route, no name, no key.
//
// Two tokens, the server keeps only the SHA-256 (hex, of the token's text) of each:
//   write token  32 random bytes made on the phone, never in the link  → claim, push, delete (and read)
//   read token   derived from the link's secret                         → read only
//
// Wire format (all answers JSON, cache-control: no-store; every call has "Authorization: Bearer <token>", a token is
// 43 Base64url characters, a box id 22):
//
//   POST /live/v1/<box>                        claim, with the WRITE token
//        body  {"read": "<64 hex: SHA-256 of the read token's text>"}
//        → 201 {"rev": 0} made · 200 {"rev"} the same two tokens again · 403 another token · 410 deleted
//          429 {"error", "code": "new-box-cap"} + retry-after: too many new boxes from this network today
//   POST /live/v1/<box>/push                   with the WRITE token
//        body  {"items": [{"name": "h" | "r" | "t0000"…, "blob": "<Base64url>"}, …], "ended": true (only at the end)}
//        → 200 {"rev"} – all items stored under one new revision, last write = the server's time
//          400 not that shape · 413 an item over 16 KiB, a bad name, too many items · 507 the box is full
//          429 + retry-after: more than 60 pushes in an hour · 403 not the write token · 404 no such box · 410 deleted
//   GET  /live/v1/<box>?since=N                with the READ token (the write token works too)
//        → 200 {"rev", "now", "lastWrite", "ended", "more", "items": [{"name", "rev", "blob"}]}
//          items changed after revision N, oldest first, at most 500 ("more": true → ask again with since = rev);
//          now / lastWrite / ended are the SERVER's clock in ms (ended null while the tour runs) – the page computes
//          "vor 2 Min." as now − lastWrite, no phone clock involved. A GET writes nothing.
//          404 no such box (not claimed yet, or gone for good) · 403 · 410 sharing was stopped / expired
//   DELETE /live/v1/<box>                      with the WRITE token
//        → 200 {"deleted": true} – all items gone at once; the box answers 410 to everyone from then on
//   any call: 401 no / odd token · 404 odd path or id · 429 + retry-after: 60 per-IP limit (only when the binding
//        LIVE_RATE exists) · 503 + retry-after: 30 the database is busy or not bound – never a bare 500
//
// Storage: the planner's D1 (binding SYNC), tables live_box / live_item (migrations/0004_live.sql). New boxes per IP
// per day are capped like new sync mailboxes (table map_quota, k = "live:<daily salted hash of the IP>"; var
// LIVE_NEW_PER_IP_DAY). Nothing lives long: purgeLive (scheduled).
import { ipHash } from "./maps.js";

export const LIMITS = {
  blobChars: 16 * 1024,          // one item as Base64url text (the phone's largest sealed chunk is ~16,040 characters)
  pushItems: 8,                  // items in one push (the phone sends route + ≤ 3 chunks + head)
  items: 2000,                   // per box
  boxChars: Math.floor(2 * 1024 * 1024 * 4 / 3), // per box: 2 MiB of blobs, as Base64url text
  pushesPerHour: 60,             // per box (the phone pushes 30 times an hour when moving)
  page: 500,                     // items in one GET answer
  newPerIpDay: 10,               // new boxes per IP per UTC day
  endedMs: 12 * 3_600_000,       // a finished tour stays visible this long (owner 2026-11-20), then 410
  idleMs: 48 * 3_600_000,        // any box this long after its last push: 410; a 410 row is forgotten after the same time
};
export const NEW_BOX_CAP = "new-box-cap";

const ID = /^[A-Za-z0-9_-]{22}$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const HASH = /^[0-9a-f]{64}$/;
const NAME = /^(?:h|r|t\d{4})$/;
const BLOB = /^[A-Za-z0-9_-]+$/;
const HOUR = 3_600_000, DAY = 86_400_000;
const BUSY_RETRY_S = 30;
/** A push body can hold [LIMITS.pushItems] full blobs plus JSON around them. */
const maxBody = () => LIMITS.pushItems * (LIMITS.blobChars + 64) + 256;

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

/** The body as a JSON object, or null (too long, not JSON, not an object) – the caller answers 400 / 413. */
async function readJson(request) {
  const text = await request.text();
  if (text.length > maxBody()) return null;
  try {
    const v = JSON.parse(text);
    return v && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch { // expected: a body that is not JSON is the client's mistake – the caller answers 400
    return null;
  }
}

/**
 * A live request – never an uncaught exception (a Worker that throws answers Cloudflare's bare 500, which neither the
 * phone nor the page can read): a failure here is transient (D1 busy), answered 503 with retry-after; both sides
 * retry quietly. Logged without IP or token.
 */
export async function handleLive(request, env, now = Date.now()) {
  try {
    return await box(request, env, now);
  } catch (e) {
    console.error(`live ${request.method}: ${String(e?.message ?? e).slice(0, 200)} – answered 503`);
    return json(503, { error: "the live server is busy – try again shortly" }, { "retry-after": String(BUSY_RETRY_S) });
  }
}

async function box(request, env, now) {
  const url = new URL(request.url);
  const m = /^\/live\/v1\/([^/]+)(\/push)?$/.exec(url.pathname);
  if (!m || !ID.test(m[1])) return fail(404, "not found");
  const db = env.SYNC;
  if (!db) return json(503, { error: "live tracking is not set up on this server" }, { "retry-after": String(BUSY_RETRY_S) });
  const ip = request.headers.get("cf-connecting-ip") ?? "?";
  const day = new Date(now).toISOString().slice(0, 10);
  if (env.LIVE_RATE) {
    let success = true;
    try { ({ success } = await env.LIVE_RATE.limit({ key: await ipHash(env, ip, day) })); } catch (e) {
      // the limiter itself failed: let the request through (the box's own limits still hold), and say so
      console.warn(`live: rate limiter failed (${String(e?.message ?? e).slice(0, 120)}) – request let through`);
    }
    if (!success) return json(429, { error: "too many requests – try again in a minute" }, { "retry-after": "60" });
  }
  const id = m[1], push = !!m[2], method = request.method;
  const token = /^Bearer (.+)$/.exec(request.headers.get("authorization") ?? "")?.[1] ?? "";
  if (!TOKEN.test(token)) return fail(401, "missing token");
  const hash = await sha256(token);
  const row = await db.prepare("SELECT read_hash, write_hash, rev, last_write, ended, deleted, pushes_from, pushes_hour FROM live_box WHERE id = ?1").bind(id).first();
  if (row?.deleted) return fail(410, "this tour is no longer shared");

  if (method === "POST" && !push) { // claim
    const read = (await readJson(request))?.read;
    if (typeof read !== "string" || !HASH.test(read)) return fail(400, "missing read hash");
    if (sameText(read, hash)) return fail(400, "the read token must differ from the write token");
    if (row) {
      return sameText(row.write_hash, hash) && sameText(row.read_hash, read) ? json(200, { rev: row.rev }) : fail(403, "wrong token");
    }
    const k = "live:" + await ipHash(env, ip, day);
    const cap = Number(env.LIVE_NEW_PER_IP_DAY) || LIMITS.newPerIpDay;
    const made = (await db.prepare("SELECT n FROM map_quota WHERE k = ?1 AND period = ?2").bind(k, day).first("n")) ?? 0;
    if (made >= cap) {
      const wait = Math.max(60, Math.ceil((DAY - (now % DAY)) / 1000));
      return json(429, { error: "too many new live tours from this network today – try again tomorrow", code: NEW_BOX_CAP }, { "retry-after": String(wait) });
    }
    const ins = await db.prepare("INSERT INTO live_box (id, read_hash, write_hash, rev, created, last_write) VALUES (?1, ?2, ?3, 0, ?4, ?4) ON CONFLICT(id) DO NOTHING")
      .bind(id, read, hash, now).run();
    if (ins.meta?.changes) { // this call made the box (not a parallel claim of the same one)
      await db.prepare("INSERT INTO map_quota (k, period, n) VALUES (?1, ?2, 1) ON CONFLICT(k, period) DO UPDATE SET n = n + 1").bind(k, day).run();
      return json(201, { rev: 0 });
    }
    const again = await db.prepare("SELECT read_hash, write_hash, rev FROM live_box WHERE id = ?1").bind(id).first();
    return again && sameText(again.write_hash, hash) && sameText(again.read_hash, read) ? json(200, { rev: again.rev }) : fail(403, "wrong token");
  }

  if (!row) return fail(404, "no such live tour");
  const writer = sameText(row.write_hash, hash);
  if (!writer && !sameText(row.read_hash, hash)) return fail(403, "wrong token");

  if (method === "GET" && !push) {
    const since = Math.max(0, Number(url.searchParams.get("since") ?? 0) || 0);
    const { results } = await db.prepare("SELECT name, rev, blob FROM live_item WHERE box = ?1 AND rev > ?2 ORDER BY rev, name LIMIT ?3")
      .bind(id, since, LIMITS.page + 1).all();
    // a push stores all its items under one revision: a page never ends inside a revision's items
    let items = results;
    const more = results.length > LIMITS.page;
    if (more) {
      const cut = results[LIMITS.page].rev;
      items = results.filter((i) => i.rev < cut);
      if (!items.length) items = results.filter((i) => i.rev === cut).slice(0, LIMITS.page); // a revision larger than a page (page < pushItems, tests only): never loop
    }
    return json(200, { rev: more ? items[items.length - 1].rev : row.rev, now, lastWrite: row.last_write, ended: row.ended ?? null, more, items });
  }

  // everything below writes: the write token only – the link's read token can never push or delete
  if (!writer) return fail(403, "this token can only read");

  if (method === "POST" && push) {
    if (Number(request.headers.get("content-length") ?? 0) > maxBody()) return fail(413, "push too large");
    const body = await readJson(request);
    const list = body?.items;
    if (!Array.isArray(list) || (body.ended !== undefined && typeof body.ended !== "boolean")) return fail(400, "not a live push");
    if (list.length > LIMITS.pushItems) return fail(413, "too many items in one push");
    const names = new Set();
    let chars = 0;
    for (const it of list) {
      if (!it || typeof it.name !== "string" || typeof it.blob !== "string") return fail(400, "not a live push");
      if (!NAME.test(it.name) || names.has(it.name)) return fail(413, "bad item name");
      if (it.blob.length > LIMITS.blobChars) return fail(413, "item too large");
      if (it.blob.length < 39 || !BLOB.test(it.blob)) return fail(400, "not a live item"); // version + nonce + tag = 29 bytes
      names.add(it.name);
      chars += it.blob.length;
    }
    if (!list.length && !body.ended) return fail(400, "nothing to push");
    // at most pushesPerHour in an hour window per box
    const sameHour = now >= row.pushes_from && now - row.pushes_from < HOUR;
    if (sameHour && row.pushes_hour >= LIMITS.pushesPerHour) {
      const wait = Math.max(1, Math.ceil((row.pushes_from + HOUR - now) / 1000));
      return json(429, { error: "too many uploads for this tour – try again later" }, { "retry-after": String(wait) });
    }
    if (list.length) {
      const marks = list.map((_, i) => "?" + (i + 2)).join(", ");
      const use = await db.prepare("SELECT COUNT(*) AS items, COALESCE(SUM(length(blob)), 0) AS chars FROM live_item WHERE box = ?1").bind(id).first();
      const old = await db.prepare(`SELECT COUNT(*) AS items, COALESCE(SUM(length(blob)), 0) AS chars FROM live_item WHERE box = ?1 AND name IN (${marks})`)
        .bind(id, ...list.map((i) => i.name)).first();
      if (use.items - old.items + list.length > LIMITS.items) return fail(507, "this live tour holds too many items");
      if (use.chars - old.chars + chars > LIMITS.boxChars) return fail(507, "this live tour is too large");
    }
    // one transaction: the revision moves, then every item is written under it (only while the box is there)
    const out = await db.batch([
      db.prepare("UPDATE live_box SET rev = rev + 1, last_write = ?2, ended = CASE WHEN ?3 = 1 THEN COALESCE(ended, ?2) ELSE ended END, " +
        "pushes_from = ?4, pushes_hour = ?5 WHERE id = ?1 AND deleted IS NULL")
        .bind(id, now, body.ended ? 1 : 0, sameHour ? row.pushes_from : now, sameHour ? row.pushes_hour + 1 : 1),
      ...list.map((it) => db.prepare("INSERT INTO live_item (box, name, rev, blob) SELECT ?1, ?2, rev, ?3 FROM live_box WHERE id = ?1 AND deleted IS NULL " +
        "ON CONFLICT(box, name) DO UPDATE SET rev = excluded.rev, blob = excluded.blob").bind(id, it.name, it.blob)),
    ]);
    if (!out[0].meta.changes) return fail(410, "this tour is no longer shared"); // deleted between the read and the write
    const after = await db.prepare("SELECT rev FROM live_box WHERE id = ?1").bind(id).first();
    return json(200, { rev: after.rev });
  }

  if (method === "DELETE" && !push) {
    await gone(db, id, now);
    return json(200, { deleted: true });
  }
  return fail(405, "method not allowed");
}

/** The box's items go; its row stays as "deleted" (410 for everyone) and forgets both token hashes. */
const gone = (db, id, now) => db.batch([
  db.prepare("DELETE FROM live_item WHERE box = ?1").bind(id),
  db.prepare("UPDATE live_box SET deleted = ?2, read_hash = '', write_hash = '' WHERE id = ?1").bind(id, now),
]);

/**
 * Scheduled: nothing lives long. A finished tour is taken away [LIMITS.endedMs] after its end, any box
 * [LIMITS.idleMs] after its last push (a phone that died: the last place stays two days) – both answer 410 from then
 * on ("no longer shared"); a 410 row is forgotten after another [LIMITS.idleMs] (404 then). Returns [expired, forgotten].
 */
export async function purgeLive(env, now = Date.now()) {
  const db = env.SYNC;
  if (!db) return [0, 0];
  const { results } = await db.prepare("SELECT id FROM live_box WHERE deleted IS NULL AND ((ended IS NOT NULL AND ended < ?1) OR last_write < ?2) LIMIT 500")
    .bind(now - LIMITS.endedMs, now - LIMITS.idleMs).all();
  for (const { id } of results) await gone(db, id, now);
  const old = await db.prepare("DELETE FROM live_box WHERE deleted IS NOT NULL AND deleted < ?1").bind(now - LIMITS.idleMs).run();
  return [results.length, old.meta?.changes ?? 0];
}
