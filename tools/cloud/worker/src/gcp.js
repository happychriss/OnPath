// Google Cloud access without npm libraries: a service-account key (doorman-invoker-<env>, Worker secret
// GCP_INVOKER_KEY) signs a JWT with WebCrypto (RS256), Google's token endpoint swaps it for an access token, and
// the Cloud Run Admin API v2 starts / reads / cancels executions of the one builder job. The account may do nothing
// else (custom role on that job only, see tools/cloud/gcp-setup.sh).

const RUN_API = "https://run.googleapis.com/v2";
const SCOPE = "https://www.googleapis.com/auth/cloud-platform";

const enc = new TextEncoder();

/** fetch, always called with the right `this` (Workers throw "Illegal invocation" otherwise). */
export const globalFetch = (...a) => fetch(...a);

export function b64url(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode(...u8.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function pemToDer(pem) {
  const b64 = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Signed JWT (RS256) asking for an access token. */
export async function signedAssertion(key, nowS = Math.floor(Date.now() / 1000)) {
  const header = { alg: "RS256", typ: "JWT", kid: key.private_key_id };
  const claims = {
    iss: key.client_email,
    scope: SCOPE,
    aud: key.token_uri || "https://oauth2.googleapis.com/token",
    iat: nowS,
    exp: nowS + 3600,
  };
  const input = `${b64url(enc.encode(JSON.stringify(header)))}.${b64url(enc.encode(JSON.stringify(claims)))}`;
  const pk = await crypto.subtle.importKey("pkcs8", pemToDer(key.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pk, enc.encode(input));
  return `${input}.${b64url(sig)}`;
}

let cached = null; // {token, exp, email} – per Worker isolate

export async function accessToken(env, fetchImpl = globalFetch, nowMs = Date.now()) {
  if (!env.GCP_INVOKER_KEY) throw new Error("GCP_INVOKER_KEY is not set");
  const key = JSON.parse(env.GCP_INVOKER_KEY);
  if (cached && cached.email === key.client_email && cached.exp - 120_000 > nowMs) return cached.token;
  const assertion = await signedAssertion(key, Math.floor(nowMs / 1000));
  const r = await fetchImpl(key.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${assertion}`,
  });
  if (!r.ok) throw new Error(`token exchange failed: HTTP ${r.status} ${(await r.text()).slice(0, 200)}`);
  const j = await r.json();
  cached = { token: j.access_token, exp: nowMs + (j.expires_in || 3600) * 1000, email: key.client_email };
  return cached.token;
}

export function resetTokenCache() {
  cached = null;
}

async function call(env, method, url, body, fetchImpl) {
  const token = await accessToken(env, fetchImpl);
  const r = await fetchImpl(url, {
    method,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`Cloud Run API ${method} ${url.replace(RUN_API, "")}: HTTP ${r.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

/** Start one execution of the builder job for these cells. Returns the execution's full resource name. */
export async function runJob(env, cfg, cellIds, format, fetchImpl = globalFetch) {
  const url = `${RUN_API}/projects/${cfg.gcpProject}/locations/${cfg.gcpRegion}/jobs/${cfg.gcpJob}:run`;
  const op = await call(env, "POST", url, {
    overrides: {
      containerOverrides: [{ env: [{ name: "CELLS", value: cellIds.join(" ") }, { name: "FORMAT", value: String(format) }] }],
      taskCount: 1,
    },
  }, fetchImpl);
  // jobs.run returns a long-running Operation whose metadata is the new Execution
  const name = op?.metadata?.name;
  if (!name || !name.includes("/executions/")) throw new Error(`unexpected run response: ${JSON.stringify(op).slice(0, 300)}`);
  return name;
}

export async function getExecution(env, name, fetchImpl = globalFetch) {
  return call(env, "GET", `${RUN_API}/${name}`, undefined, fetchImpl);
}

export async function cancelExecution(env, name, fetchImpl = globalFetch) {
  return call(env, "POST", `${RUN_API}/${name}:cancel`, {}, fetchImpl);
}

/** {done, succeeded} from an Execution resource. */
export function executionState(ex) {
  const completed = (ex.conditions || []).find((c) => c.type === "Completed");
  const done = Boolean(ex.completionTime) || (completed && ["CONDITION_SUCCEEDED", "CONDITION_FAILED"].includes(completed.state));
  const succeeded = done && (ex.succeededCount || 0) >= (ex.taskCount || 1);
  return { done: Boolean(done), succeeded: Boolean(succeeded) };
}

export const shortName = (name) => String(name).split("/").pop();
