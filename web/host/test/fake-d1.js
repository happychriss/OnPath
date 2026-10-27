// A stand-in for Cloudflare D1 in tests: the same SQL on Node's built-in SQLite (node:sqlite), with D1's small API
// (prepare → bind → first / all / run, batch = one transaction). Used by the host tests and the browser tests.
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const migrations = path.join(path.dirname(fileURLToPath(import.meta.url)), "../migrations");

export function fakeD1() {
  const db = new DatabaseSync(":memory:");
  for (const f of fs.readdirSync(migrations).sort()) db.exec(fs.readFileSync(path.join(migrations, f), "utf8"));
  const plain = (r) => (r == null ? null : { ...r });
  function run(sql, args) {
    const st = db.prepare(sql);
    if (/^\s*select/i.test(sql)) return { success: true, results: st.all(...args).map(plain), meta: { changes: 0 } };
    const r = st.run(...args);
    return { success: true, results: [], meta: { changes: Number(r.changes) } };
  }
  const make = (sql, args = []) => ({
    sql, args,
    bind: (...a) => make(sql, a),
    first: async (col) => { const r = plain(db.prepare(sql).get(...args)); return r && col ? r[col] : r; },
    all: async () => ({ success: true, results: db.prepare(sql).all(...args).map(plain), meta: {} }),
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
