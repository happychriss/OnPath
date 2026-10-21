// Test doubles: D1 on node:sqlite (real SQL, same migrations), an in-memory R2 bucket, a fake Google API.
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { generateKeyPairSync } from "node:crypto";

const MIGRATIONS = new URL("../migrations/", import.meta.url);

class Stmt {
  constructor(db, sql, params = []) {
    this.db = db;
    this.sql = sql;
    this.params = params;
  }
  bind(...p) {
    return new Stmt(this.db, this.sql, p);
  }
  _s() {
    return this.db.prepare(this.sql);
  }
  async first() {
    return this._s().get(...this.params) ?? null;
  }
  async all() {
    return { results: this._s().all(...this.params) };
  }
  async run() {
    const r = this._s().run(...this.params);
    return { meta: { changes: Number(r.changes) } };
  }
  _runSync() {
    return this._s().run(...this.params);
  }
}

export function fakeD1() {
  const db = new DatabaseSync(":memory:");
  for (const f of readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort()) {
    db.exec(readFileSync(new URL(f, MIGRATIONS), "utf8"));
  }
  return {
    raw: db,
    prepare: (sql) => new Stmt(db, sql),
    async batch(stmts) {
      db.exec("BEGIN");
      try {
        const out = stmts.map((s) => s._runSync());
        db.exec("COMMIT");
        return out;
      } catch (e) {
        db.exec("ROLLBACK");
        throw e;
      }
    },
  };
}

/** Minimal R2Bucket: get (range / onlyIf from Headers), head, put. */
export function fakeR2() {
  const objects = new Map();
  const obj = (key, data, extra = {}) => ({
    key,
    size: data.length,
    httpEtag: `"${key.length}-${data.length}"`,
    writeHttpMetadata(h) {
      if (extra.contentType) h.set("content-type", extra.contentType);
    },
    ...extra,
  });
  return {
    objects,
    async put(key, value, opts = {}) {
      const data = typeof value === "string" ? new TextEncoder().encode(value) : new Uint8Array(value);
      objects.set(key, { data, contentType: opts.httpMetadata?.contentType });
    },
    async head(key) {
      const o = objects.get(key);
      return o ? obj(key, o.data) : null;
    },
    async get(key, opts = {}) {
      const o = objects.get(key);
      if (!o) return null;
      const h = opts.onlyIf instanceof Headers ? opts.onlyIf : null;
      if (h?.get("if-none-match") === obj(key, o.data).httpEtag) return obj(key, o.data); // no body
      let data = o.data;
      let range;
      const rh = opts.range instanceof Headers ? opts.range.get("range") : null;
      if (rh) {
        const m = /^bytes=(\d*)-(\d*)$/.exec(rh);
        if (!m) throw new Error("bad range");
        if (m[1] === "") {
          range = { suffix: Number(m[2]) };
          data = o.data.subarray(o.data.length - range.suffix);
        } else {
          const a = Number(m[1]);
          const b = m[2] === "" ? o.data.length - 1 : Math.min(Number(m[2]), o.data.length - 1);
          if (a >= o.data.length) throw new Error("range not satisfiable");
          range = { offset: a, length: b - a + 1 };
          data = o.data.subarray(a, b + 1);
        }
      }
      const body = new Blob([data]).stream();
      return obj(key, o.data, { body, range, text: async () => new TextDecoder().decode(data) });
    },
  };
}

export function serviceAccountKey() {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  return {
    publicKey,
    json: JSON.stringify({
      type: "service_account",
      client_email: "doorman-invoker-test@proj.iam.gserviceaccount.com",
      private_key_id: "kid1",
      private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
      token_uri: "https://oauth2.googleapis.com/token",
    }),
  };
}

/** Fake fetch for oauth2 + Cloud Run Admin API. `executions` maps name → Execution resource (tests mutate it). */
export function fakeGoogle() {
  const calls = [];
  const executions = new Map();
  let n = 0;
  const fetchImpl = async (url, init = {}) => {
    calls.push({ url, method: init.method || "GET", body: init.body });
    if (url === "https://oauth2.googleapis.com/token") {
      return Response.json({ access_token: "tok", expires_in: 3600 });
    }
    let m = /\/jobs\/([^/:]+):run$/.exec(url);
    if (m) {
      if (fetchImpl.failRun) return new Response("permission denied", { status: 403 });
      const name = `projects/proj/locations/europe-west1/jobs/${m[1]}/executions/${m[1]}-x${++n}`;
      executions.set(name, { name, taskCount: 1 });
      return Response.json({ name: "projects/proj/locations/europe-west1/operations/op1", metadata: { name } });
    }
    m = /\/v2\/(projects\/.+\/executions\/[^/:]+)(:cancel)?$/.exec(url);
    if (m) {
      const ex = executions.get(m[1]);
      if (!ex) return new Response("not found", { status: 404 });
      if (m[2]) ex.cancelled = true;
      return Response.json(ex);
    }
    return new Response("unexpected " + url, { status: 500 });
  };
  fetchImpl.calls = calls;
  fetchImpl.executions = executions;
  return fetchImpl;
}

export function testEnv(extra = {}) {
  return {
    ENVIRONMENT: "test",
    FORMATS: "2",
    GCP_PROJECT: "proj",
    GCP_REGION: "europe-west1",
    GCP_JOB: "onpath-cell-test",
    APP_KEYS: "old-key-0123456789,app-key-0123456789",
    BUILD_REV: "rev1",
    DB: fakeD1(),
    BUCKET: fakeR2(),
    GCP_INVOKER_KEY: serviceAccountKey().json,
    ...extra,
  };
}
