// Live smoke test of the deployed planner (docs/design/web-planner.md §9, owner step 6): run by CI after a deploy when
// the Cloudflare Access service token exists. Only reads; prints status codes, never the token.
// Usage: CF_ACCESS_CLIENT_ID=… CF_ACCESS_CLIENT_SECRET=… node web/host/live-smoke.mjs https://plan.onpath.earth/
const base = process.argv[2] ?? 'https://plan.onpath.earth/';
const auth = { 'CF-Access-Client-Id': process.env.CF_ACCESS_CLIENT_ID ?? '', 'CF-Access-Client-Secret': process.env.CF_ACCESS_CLIENT_SECRET ?? '' };
const fails = [];
async function check(what, path, ok, init = {}) {
  let r;
  for (let i = 0; i < 3 && !r; i++) { // a fresh deploy may need a few seconds everywhere
    try { r = await fetch(new URL(path, base), { ...init, headers: { ...auth, ...(init.headers ?? {}) }, redirect: 'manual' }); }
    catch (e) { if (i === 2) { fails.push(`${what}: ${e}`); console.log(`FAIL ${what}: ${e}`); return; } await new Promise((s) => setTimeout(s, 5000)); }
  }
  const body = init.method === 'HEAD' ? '' : await r.text();
  const good = await ok(r, body);
  console.log(`${good ? 'ok  ' : 'FAIL'} ${what} (HTTP ${r.status})`);
  if (!good) fails.push(what);
}

await check('the page (Access lets the service token in)', '', (r, b) => r.status === 200 && b.includes('<div id="app">'));
await check('the routing engine (Wasm)', 'engine/onpath-web-engine.wasm', (r) => r.status === 200, { method: 'HEAD' });
await check('the world overview', 'overview/base.pmtiles', (r) => r.status === 200, { method: 'HEAD' });
await check('the merged style', 'style/onpath-overview-packages.json', (r, b) => r.status === 200 && b.includes('detail-cover'));
await check('a map cell from R2 (Meran)', 'data/cells/f2/10/543/361/current.json', (r, b) => r.status === 200 && /"version"/.test(b));
await check('routing data with Range (brouter.de pass-through)', 'segments/E10_N45.rd5', (r) => r.status === 206, { headers: { Range: 'bytes=0-199' } });
await check('map areas on demand: status through the proxy (200, or 503 while the owner key step is open)', 'maps/v1/cells/status?ids=10-543-361',
  (r, b) => r.status === 200 ? /"state"/.test(b) : r.status === 503 && /no-key/.test(b));
if (fails.length) { console.log(`\n${fails.length} live check(s) failed`); process.exit(1); }
console.log('\nlive planner ok');
