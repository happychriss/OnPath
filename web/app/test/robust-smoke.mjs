// Release-day robustness (review 2026-11-01, list A 10–13): (1) a failed routing-data download is not kept – the plan
// shows the app's grey "Map loads when online" line (no "Planning failed: TypeError…") and the next plan downloads
// again; (2) an unsaved route comes back when the planner is opened again without #p= (bookmark / typed address);
// (3) no developer line ("Routing engine ready (N ms)") without ?debug; (4) a browser without WasmGC gets the
// "too old" page instead of a planner that cannot plan.
// Usage: node test/robust-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

// ---- 1 + 3: download failure, then back ----
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
let blockData = true, blocked = 0;
await page.route('**/segments/**', (r) => { if (blockData) { blocked++; return r.abort('internetdisconnected'); } return r.continue(); });
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
const statusText = () => page.textContent('#status');
check(!/ready|bereit|\d+ ms/.test(await statusText()), `no developer line without ?debug ("${(await statusText()).trim()}")`);
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu');
await page.click('.pointmenu button:first-of-type'); await page.waitForTimeout(300);
await clickAt(11.1500, 46.6950);
const settled = () => page.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').classList.contains('busy'),
  null, { timeout: 120_000 });
await settled();
const offline = await page.evaluate(() => ({ info: document.getElementById('infoline').hidden ? '' : document.getElementById('infoline').textContent,
  status: document.getElementById('status').textContent, error: document.getElementById('status').classList.contains('error'),
  red: document.getElementById('notice')?.hidden === false ? document.getElementById('notice').textContent : '' }));
console.log('offline plan:', JSON.stringify(offline), `(${blocked} requests blocked)`);
check(blocked > 0, 'the routing data was asked for (and blocked)');
check(/online/i.test(offline.info), 'download failed → the grey "Map loads when online" line');
check(!offline.error && !/TypeError|Failed to fetch|java\.|Unexpected/.test(offline.status + offline.red), 'no "Planning failed" / raw error text');
// back online: the next plan downloads again (the failure was not kept) – the "online" event plans again by itself
blockData = false;
await page.evaluate(() => dispatchEvent(new Event('online')));
await page.waitForFunction(() => document.getElementById('infoline').hidden && document.querySelector('#summary .big') &&
  !document.getElementById('status').classList.contains('busy'), null, { timeout: 120_000 }).catch(() => {});
const back = await page.evaluate(() => ({ info: document.getElementById('infoline').hidden ? '' : document.getElementById('infoline').textContent,
  straight: window.__state.plan?.legs.every((l) => l.straight) }));
console.log('back online:', JSON.stringify(back));
check(!back.info && back.straight === false, 'back online → routed on the ways (no reload needed)');

// ---- 2: unsaved route back without #p= ----
const pts = await page.evaluate(() => window.__state.points.length);
await page.waitForTimeout(1500); // the draft is written after the address update
await page.close();
const again = await ctx.newPage();
again.on('pageerror', (e) => errors.push(String(e)));
await again.goto(url + 'index.html'); // no #p= (a bookmark, the address typed)
await again.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
await again.waitForFunction(() => window.__state?.points.length > 0, null, { timeout: 30_000 }).catch(() => {});
await again.waitForFunction(() => location.hash.startsWith('#p='), null, { timeout: 10_000 }).catch(() => {});
const restored = await again.evaluate(() => ({ n: window.__state.points.length, hash: location.hash.slice(0, 3) }));
check(restored.n === pts && pts === 2, `the unsaved route is back after reopening (${restored.n} of ${pts} points)`);
check(restored.hash === '#p=', 'and in the address again');
// ---- 5: two quick clicks while the engine is busy go in the order clicked (review web H4) ----
await again.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').classList.contains('busy'), null, { timeout: 120_000 });
const p1 = [11.1440, 46.7040], p2 = [11.1380, 46.7110]; // beyond the destination, one after the other
const px = await again.evaluate((ps) => ps.map(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }), [p1, p2]);
// the engine busy first (another activity → the plan again), then both clicks: both wait behind that plan
await again.click('#activityChip'); await again.click('[data-act=BIKE_TOURING]');
await again.mouse.click(px[0][0], px[0][1]); await again.mouse.click(px[1][0], px[1][1]);
await again.waitForFunction(() => window.__state.points.length === 4, null, { timeout: 60_000 }).catch(() => {});
const order = await again.evaluate(() => window.__state.points.map(([la, lo]) => [Number(lo.toFixed(3)), Number(la.toFixed(3))]));
console.log('after two quick clicks:', JSON.stringify(order));
// the points may be snapped onto a path – compare which click each one is closer to
const d = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
check(order.length === 4 && d(order[2], p1) < d(order[2], p2) && d(order[3], p2) < d(order[3], p1), 'two quick clicks: the second is the new destination (not put before the first)');
// ---- 5b: Space held = the eye held (owner 2026-11-01) ----
await again.waitForFunction(() => !document.getElementById('eye').disabled, null, { timeout: 60_000 }).catch(() => {});
await again.locator('#map canvas').focus().catch(() => {});
const vis = () => again.evaluate(() => window.__map.getLayoutProperty('planned-route', 'visibility') ?? 'visible');
await again.keyboard.down(' ');
const whileHeld = await vis();
await again.keyboard.up(' ');
const after = await vis();
check(whileHeld === 'none' && after === 'visible', `Space held hides the route, let go shows it (${whileHeld} → ${after})`);
check((await again.getAttribute('#eye', 'title')).includes('Space'), 'the eye\'s tooltip names the Space bar');
// ---- 6: a failed write is said, never silent (review web M11) ----
await again.evaluate(() => { window.__sync.save = () => Promise.reject(new DOMException('quota', 'QuotaExceededError')); });
await again.click('#menuSavePlan');
await again.waitForSelector('dialog.savename[open] #saveOk', { timeout: 20_000 });
await again.click('#saveOk');
await again.waitForFunction(() => document.getElementById('status').classList.contains('error'), null, { timeout: 10_000 }).catch(() => {});
const saveLine = await again.textContent('#status');
check(/Not saved/.test(saveLine) && !/quota|DOMException/i.test(saveLine), `Save fails → a plain red line ("${saveLine.trim()}")`);
await ctx.close();
check(errors.length === 0, `no page error (${errors.join(' | ')})`);

// ---- 4: no WasmGC ----
for (const [locale, want] of [['de-DE', 'zu alt'], ['en-US', 'too old']]) {
  const c = await browser.newContext({ locale });
  await c.addInitScript(() => { // a browser without WebAssembly GC (Safari < 18.2)
    const orig = WebAssembly.validate;
    WebAssembly.validate = (b) => (b.byteLength === 15 ? false : orig(b));
  });
  const p = await c.newPage();
  await p.goto(url + 'index.html');
  const shown = await p.waitForSelector('.nowebgl[role=alert]', { timeout: 30_000 }).then(() => true, () => false);
  const text = shown ? await p.textContent('.nowebgl') : '';
  check(shown && text.includes(want) && text.includes('18.2'), `${locale}: no WasmGC → "browser too old" page ("${text.trim().slice(0, 50)}…")`);
  check(!(await p.$('#map')), `${locale}: no planner is started`);
  await c.close();
}
await browser.close(); server.close();
process.exit(fails.length ? 1 : 0);
