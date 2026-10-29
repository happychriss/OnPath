// The plan sync mailbox (web/host/src/sync.js, docs/design/web-sync.md §8) on this machine, for the app's end-to-end
// test (app/src/test/.../plansync/SyncMailboxE2ETest.kt, started by tools/sync/e2e.sh): Node's HTTP server in front of
// the real handleSync, with Cloudflare D1 replaced by Node's built-in SQLite (the same stand-in as
// web/host/test/fake-d1.js) and the real migrations. No Cloudflare, no phone.
//
//   node tools/sync/mailbox-server.mjs <sync.js> <migrations dir>   → prints "listening <port>" (127.0.0.1, a free port)
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { DatabaseSync } from "node:sqlite";

const [syncJs, migrations] = process.argv.slice(2);
if (!syncJs || !migrations) {
  console.error("usage: node mailbox-server.mjs <sync.js> <migrations dir>");
  process.exit(2);
}
const { handleSync } = await import(pathToFileURL(path.resolve(syncJs)).href);

/** D1's small API (prepare → bind → first / all / run, batch = one transaction) on an in-memory SQLite. */
function fakeD1() {
  const db = new DatabaseSync(":memory:");
  for (const f of fs.readdirSync(migrations).filter((f) => f.endsWith(".sql")).sort()) {
    db.exec(fs.readFileSync(path.join(migrations, f), "utf8"));
  }
  const plain = (r) => (r == null ? null : { ...r });
  // D1 binds "?1", "?2" … by number; some Node versions' node:sqlite do not ("column index out of range" on 22.14):
  // each "?N" becomes a plain "?" with its value in place
  const positional = (sql, args) => {
    const out = [];
    return [sql.replace(/\?(\d+)/g, (_, n) => { out.push(args[Number(n) - 1]); return "?"; }), out];
  };
  const prep = (sql, args) => { const [q, a] = positional(sql, args); return [db.prepare(q), a]; };
  function run(sql, args) {
    const [st, a] = prep(sql, args);
    if (/^\s*select/i.test(sql)) return { success: true, results: st.all(...a).map(plain), meta: { changes: 0 } };
    const r = st.run(...a);
    return { success: true, results: [], meta: { changes: Number(r.changes) } };
  }
  const make = (sql, args = []) => ({
    sql, args,
    bind: (...a) => make(sql, a),
    first: async (col) => { const [st, a] = prep(sql, args); const r = plain(st.get(...a)); return r && col ? r[col] : r; },
    all: async () => { const [st, a] = prep(sql, args); return { success: true, results: st.all(...a).map(plain), meta: {} }; },
    run: async () => run(sql, args),
  });
  return {
    sqlite: db,
    prepare: (sql) => make(sql),
    batch: async (list) => {
      db.exec("BEGIN");
      try { const out = list.map((st) => run(st.sql, st.args)); db.exec("COMMIT"); return out; } catch (e) { db.exec("ROLLBACK"); throw e; }
    },
  };
}

const env = { SYNC: fakeD1() };

const server = http.createServer(async (req, res) => {
  try {
    // test hook: every stored blob as text, so the test can check the server holds nothing readable
    if (req.url === "/_test/blobs") {
      const rows = env.SYNC.sqlite.prepare("SELECT blob FROM item").all();
      res.writeHead(200, { "content-type": "text/plain" });
      res.end(rows.map((r) => Buffer.from(r.blob, "base64url").toString("latin1")).join("\n"));
      return;
    }
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string") headers.set(k, v);
    const body = req.method === "GET" || req.method === "HEAD" ? undefined : Buffer.concat(chunks);
    const out = await handleSync(new Request("http://127.0.0.1" + req.url, { method: req.method, headers, body }), env);
    res.writeHead(out.status, Object.fromEntries(out.headers));
    res.end(Buffer.from(await out.arrayBuffer()));
  } catch (e) {
    res.writeHead(500, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: String(e) }));
  }
});
server.listen(0, "127.0.0.1", () => console.log("listening " + server.address().port));
