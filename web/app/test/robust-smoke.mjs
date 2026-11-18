// Release-day robustness (review 2026-11-01, list A 10–13): (1) a failed routing-data download is not kept – the plan
// shows the app's grey "Map loads when online" line (no "Planning failed: TypeError…") and the next plan downloads
// again; (2) an unsaved route comes back when the planner is opened again without #p= (bookmark / typed address);
// (3) no developer line ("Routing engine ready (N ms)") without ?debug; (4) a browser without WasmGC gets the
// "too old" page instead of a planner that cannot plan; (9) a long plan's calculation never looks frozen.
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
const settled = () => page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1',
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
  document.body.dataset.planning !== '1', null, { timeout: 120_000 }).catch(() => {});
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
// no #p= (a bookmark, the address typed); waitMs: the wait box (middle of the map) stays away – on a slow machine it
// came before the two quick clicks of step 5 and caught them (CI 2026-11-04); the box has its own test below
await again.goto(url + 'index.html?waitMs=600000');
await again.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
await again.waitForFunction(() => window.__state?.points.length > 0, null, { timeout: 30_000 }).catch(() => {});
await again.waitForFunction(() => location.hash.startsWith('#p='), null, { timeout: 10_000 }).catch(() => {});
const restored = await again.evaluate(() => ({ n: window.__state.points.length, hash: location.hash.slice(0, 3) }));
check(restored.n === pts && pts === 2, `the unsaved route is back after reopening (${restored.n} of ${pts} points)`);
check(restored.hash === '#p=', 'and in the address again');
// ---- 5: two quick clicks while the engine is busy go in the order clicked (review web H4) ----
await again.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
// two spots on the leg, p1 nearer the start, p2 nearer the goal: since the hold rule (F-PLAN-15, b1f8728a) a click past
// the goal asks „Einfügen“ / „Neues Ziel“ unless it is exactly straight on, so the clicks go onto the leg, where the rule
// adds at once. Decided on the plan the first click left, p2 goes between p1 and the goal; decided on the old plan (the
// bug) it would go between the start and p1. Chosen below, once the camera stands, from the drawn line.
let p1, p2;
// the engine busy first (another activity → the plan again), then both clicks: both wait behind that plan
await again.click('#activityChip'); await again.click('[data-act=BIKE_TOURING]');
// the menu closed – then the camera stands; both clicks queue behind the new plan
await again.waitForFunction(() => ![...document.querySelectorAll('[data-act]')].some((e) => e.offsetParent !== null), null, { timeout: 10_000 }).catch(() => {});
// the user takes the map first (a short drag), as a person does before clicking: it cancels the reopened route's
// pending whole-route fit (WholeRoute.SETTLE_MS after its plan – lastMapTouch). Without it that fit could start between
// the projection below and the clicks (CI 5be2031d: both clicks landed beside p1 / p2; 8f8db60d, 045e8ebf: p2's pixel
// lay outside the window – elementFromPoint gave nothing – and its click was lost).
{ const r = await again.locator('#map').boundingBox(), x = r.x + r.width * 0.6, y = r.y + r.height * 0.4;
  await again.mouse.move(x, y); await again.mouse.down(); await again.mouse.move(x + 40, y + 20, { steps: 5 }); await again.mouse.up(); }
// a fixed view of the leg's middle part; no padding left by an earlier camera move (it shifts where the centre is drawn)
await again.evaluate(() => window.__map.jumpTo({ center: [11.1554, 46.6894], zoom: 13.5, bearing: 0, pitch: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 } }));
await again.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 10_000 }).catch(() => {});
// p1 / p2: the drawn route's points nearest 35 % / 65 % of the way from start to goal (straight), in the free map
[p1, p2] = await again.evaluate(() => {
  const m = window.__map, [a, b] = window.__state.points, c = m.getCanvas();
  const line = m.queryRenderedFeatures({ layers: ['planned-route'] }).flatMap((f) => f.geometry.type === 'LineString' ? f.geometry.coordinates : f.geometry.coordinates.flat());
  const free = ([lo, la]) => { const q = m.project([lo, la]), r = c.getBoundingClientRect(), e = document.elementFromPoint(q.x + r.left, q.y + r.top); return e === c; };
  return [0.35, 0.65].map((f) => {
    const t = [a[1] + f * (b[1] - a[1]), a[0] + f * (b[0] - a[0])];
    return line.filter(free).reduce((best, p) => (Math.hypot(p[0] - t[0], p[1] - t[1]) < Math.hypot(best[0] - t[0], best[1] - t[1]) ? p : best), [0, 0]);
  });
});
// projected only now: the camera may still move while the activity menu closes (CI: both clicks landed beside p1 / p2)
// project() gives canvas pixels; the mouse clicks page pixels – the canvas starts below the top bar (was off by 56 px)
const px = await again.evaluate((ps) => { const r = window.__map.getCanvas().getBoundingClientRect();
  return ps.map(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }); }, [p1, p2]);
// what is under each click (the map's canvas, or a box over it) – said when a click is lost (CI 2026-11-03 / 11-05)
const under = await again.evaluate((ps) => ps.map(([x, y]) => { const e = document.elementFromPoint(x, y); return e ? `${e.tagName.toLowerCase()}.${[...e.classList].join('.')}` : '-'; }), px);
const camBefore = await again.evaluate(() => JSON.stringify([window.__map.getCenter(), window.__map.getZoom()]));
await again.mouse.click(px[0][0], px[0][1]); await again.mouse.click(px[1][0], px[1][1]);
const camAfter = await again.evaluate(() => JSON.stringify([window.__map.getCenter(), window.__map.getZoom()]));
await again.waitForFunction(() => window.__state.points.length === 4, null, { timeout: 60_000 }).catch(() => {});
const order = await again.evaluate(() => window.__state.points.map(([la, lo]) => [Number(lo.toFixed(3)), Number(la.toFixed(3))]));
console.log('after two quick clicks:', JSON.stringify(order));
if (order.length !== 4 || camBefore !== camAfter) console.log('  clicks at', JSON.stringify(px), 'camera', camBefore, '→', camAfter, 'under the clicks:', JSON.stringify(under), 'boxes now:', JSON.stringify(await again.evaluate(() => [...document.querySelectorAll('.msgcard')].map((c) => c.textContent.trim()))));
// the points may be snapped onto a path – compare which click each one is closer to
const d = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
check(order.length === 4 && d(order[1], p1) < d(order[1], p2) && d(order[2], p2) < d(order[2], p1), `two quick clicks on the leg: the second goes in after the first (decided on the plan the first left; p1 ${JSON.stringify(p1)}, p2 ${JSON.stringify(p2)})`);
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
// ---- 5b2: / = search, F = whole route, ? = the list (owner 2026-11-01) ----
// the four points' plan done first: a route arriving during the fit is a camera rule of its own, not the key's
await again.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 }).catch(() => {});
await again.locator('#map canvas').focus().catch(() => {});
await again.evaluate(() => window.__map.jumpTo({ center: [11.0, 46.5], zoom: 9 })); // away from the route
await again.keyboard.press('f');
// until the fit's animation has ended – a fixed 1.5 s read the camera half way on a slow CI machine (CI 2026-11-06)
await again.waitForTimeout(100);
await again.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 20_000 }).catch(() => {});
const fitted = await again.evaluate(() => { const b = window.__map.getBounds(); return b.contains([11.15, 46.70]) && window.__map.getZoom() > 11; });
check(fitted, 'F shows the whole route');
await again.locator('#map canvas').focus().catch(() => {});
await again.keyboard.press('/');
check(await again.evaluate(() => document.activeElement?.id === 'q'), '/ puts the cursor into the search');
await again.keyboard.press('Escape');
await again.evaluate(() => document.activeElement?.blur());
await again.keyboard.press('?');
const keys = await again.waitForSelector('dialog.keys[open]', { timeout: 5_000 }).then(() => again.textContent('dialog.keys'), () => '');
check(/Space/.test(keys) && /Search/.test(keys) && /Show whole route/.test(keys), `? opens the shortcut list ("${keys.replace(/\s+/g, ' ').slice(0, 60)}…")`);
await again.click('#closeKeys');

// ---- 5c: the Points rows' texts come from the app's PlanPoints (web M15) ----
const legs = await again.evaluate(() => [...document.querySelectorAll('#points .leg')].map((l) => l.textContent.trim()).filter(Boolean));
check(legs.length >= 2 && legs.every((l) => /^[+~↺]/.test(l) && /\d/.test(l)), `Points rows: leg texts from PlanPoints.legText (${JSON.stringify(legs)})`);

// ---- 5d: Save over a route the phone changed meanwhile → a copy, the other version stays (sync/SaveRule, H3) ----
const saveNamed = async (name) => {
  await again.click('#menuSavePlan');
  await again.waitForSelector('dialog.savename[open] #saveTitle', { timeout: 20_000 });
  if (name) await again.fill('#saveTitle', name);
  await again.click('#saveOk');
};
await saveNamed('Waalweg H3');
// (waitForFunction does not await an async predicate – poll here)
const until = async (f, arg, ms = 20_000) => { for (const end = Date.now() + ms; Date.now() < end; await again.waitForTimeout(250)) if (await again.evaluate(f, arg)) return true; return false; };
await until(async () => (await window.__sync.list()).some((p) => p.title === 'Waalweg H3'));
// "the phone" changes the stored route (another plan code under the same id)
await again.evaluate(async () => {
  const p = (await window.__sync.list()).find((x) => x.title === 'Waalweg H3');
  const other = await window.__engine.encode({ points: [[46.67, 11.16], [46.69, 11.15]], activity: 'HIKE', sac: 2, pinned: [], names: {}, title: null });
  await window.__sync.save(other, p.title, p.id, window.__sync.open);
});
await saveNamed(null); // the name dialog proposes the copy's name

await until(async () => (await window.__sync.list()).filter((p) => p.title.startsWith('Waalweg H3')).length === 2);
const h3 = await again.evaluate(async () => ({ titles: (await window.__sync.list()).map((p) => p.title).filter((x) => x.startsWith('Waalweg H3')),
  line: document.getElementById('status').textContent }));
check(h3.titles.length === 2 && h3.titles.includes('Waalweg H3 (2)') && /Changed on the phone/.test(h3.line),
  `changed on the phone meanwhile → saved as a copy (${JSON.stringify(h3)})`);

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

// ---- 7: the route's wait (owner 2026-11-02): after ~1 s a box in the middle of the map – spinner, text, Cancel; while the map of a
//      new area loads it is the load overlay instead (owner 2026-11-13): a bar, the text, Cancel ----
{
  const c = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  // a slow routing-data server, so the plan takes a few seconds
  await c.route('**/segments/**', async (r) => { await new Promise((res) => setTimeout(res, 2500)); return r.continue(); });
  const p = await c.newPage();
  const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau
  await p.goto(url + 'index.html#p=' + CODE);
  await p.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  const t0 = Date.now();
  const box = await p.waitForSelector('#msgcards .msgcard.busy, #mapnotes .mapnote.load:has(button.act)', { timeout: 30_000 }).then(() => true, () => false);
  const at = Date.now() - t0;
  const card = box ? await p.evaluate(() => { const c = document.querySelector('#msgcards .msgcard.busy, #mapnotes .mapnote.load:has(button.act)'), r = c.getBoundingClientRect(),
    sheet = document.getElementById('sheet').getBoundingClientRect();
    return { text: c.textContent, spin: !!c.querySelector(".msgspin, .bar"), cx: r.left + r.width / 2, sheetRight: sheet.right, w: innerWidth,
      clock: document.getElementById('status').classList.contains('busy') }; }) : null;
  // the load card names its phase: the map's tiles, a map area being prepared (maps_card_preparing), the route
  check(box && card.spin && /Cancel/.test(card.text) && /Calculating the route|Loading the map|Preparing map/.test(card.text),
    `the wait box: spinner, "${card?.text}", Cancel`);
  check(box && card.cx > card.sheetRight && !card.clock, 'in the free map beside the sheet; no small spinner in the sheet');
  await p.click('#msgcards .msgcard.busy .msgbtn, #mapnotes .mapnote.load button.act');
  await p.waitForFunction(() => !document.querySelector('#msgcards .msgcard.busy, #mapnotes .mapnote.load') && document.body.dataset.planning !== '1', null, { timeout: 5_000 }).catch(() => {});
  const after = await p.evaluate(() => ({ pts: window.__state.points.length, plan: !!window.__state.plan, line: document.getElementById('summary').textContent,
    btn: document.getElementById('planAnyway')?.textContent }));
  check(after.pts === 2 && !after.plan && /calculation stopped/.test(after.line) && after.btn === 'Calculate',
    `Cancel: the points stay, "Route not calculated – calculation stopped." + Calculate (${JSON.stringify(after)})`);
  await p.click('#planAnyway');
  await p.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 }).catch(() => {});
  await p.waitForFunction(() => !document.querySelector('#msgcards .msgcard.busy, #mapnotes .mapnote.load'), null, { timeout: 15_000 }).catch(() => {});
  check(await p.evaluate(() => !!window.__state.plan && !document.querySelector('#msgcards .msgcard.busy, #mapnotes .mapnote.load')), 'Calculate: the route comes, the box is gone');
  console.log(`(the box came after ${at} ms)`);
  await c.close();
}

// ---- 8: the points change while a plan is being prepared (sync-smoke 2026-11-17: „Plan request failed
//      (IllegalArgumentException: Failed requirement.)“) – a plan run that waited (its points drawn, the history, the
//      long-route rule) while a newer edit left fewer than two points (a folder switch, Clear) must not plan them: the
//      engine's Planner needs two points. Made certain here: the history answer comes 1.5 s late, the route is planned
//      again (another activity) and cleared (New plan) meanwhile ----
{
  const c = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e)));
  p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });
  const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau
  await p.goto(url + 'index.html#p=' + CODE);
  await p.waitForFunction(() => document.body.dataset.engine === 'ready' && document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
  await p.evaluate(() => {
    const e = window.__engine, call = e.call.bind(e), plan = e.plan.bind(e);
    window.__planSizes = [];
    e.plan = (pts, ...a) => { window.__planSizes.push(pts.length); return plan(pts, ...a); };
    e.call = (fn, ...a) => fn === 'historyState' ? new Promise((ok, no) => setTimeout(() => call(fn, ...a).then(ok, no), 1500)) : call(fn, ...a);
  });
  await p.click('#activityChip'); await p.click('[data-act=BIKE_TOURING]');
  await p.evaluate(() => document.getElementById('newPlan').click());
  await p.waitForFunction(() => window.__state.points.length === 0, null, { timeout: 10_000 }).catch(() => {});
  await p.waitForTimeout(4000); // both runs past their late history answer
  const r = await p.evaluate(() => ({ sizes: window.__planSizes, pts: window.__state.points.length, planning: document.body.dataset.planning === '1',
    error: document.getElementById('status').classList.contains('error') }));
  check(r.pts === 0 && r.sizes.every((n) => n >= 2) && !r.planning && !r.error,
    `points cleared while a plan waited: no plan of fewer than two points, no error (${JSON.stringify(r)})`);
  check(!errs.some((e) => /Plan request failed|Failed requirement/.test(e)), `no failed plan request (${errs.join(' | ')})`);
  await c.close();
}

// ---- 9: a long plan's calculation (owner 2026-11-18, Neuss → München: „98 % sehr lange – hängt das?“) – after the
//      downloads the load card leaves its percent: the engine's line („Calculating section 2 of 3…“), the km routed of
//      the plan's length and the clock („3 of 9 km · 0:07“, as the wait box – mehrtage), a running bar (moving, no
//      number), after ~10 s the calm line „A long tour can take a few minutes.“; never a card that stands still, never
//      the wait box over it, Cancel still stops it. Then the same wait without downloads (the wait box): its km bar
//      with a running stripe and the calm line. A slow router: ?slowRoute (worker.ts hold – the worker blocked like
//      BRouter on a long plan, before it routes and at each section) ----
{
  const c = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  // slow routing data, so the card comes up (the calculation alone only joins a card that is up)
  await c.route('**/segments/**', async (r) => { await new Promise((res) => setTimeout(res, 1500)); return r.continue(); });
  // no map area being prepared (the route's areas, T4): its busy strip would bring the card for the later plans too
  await c.route('**/maps/v1/**', (r) => r.fulfill({ status: 503, body: '' }));
  await c.addInitScript(() => {
    window.__card = [];
    setInterval(() => {
      const el = document.querySelector('#mapnotes .mapnote.load');
      const box = document.querySelector('#msgcards .msgcard[data-id="routing"]');
      const last = window.__card.at(-1);
      const now = el ? { pct: Number(el.dataset.pct), running: !!el.querySelector('.bar.running'), num: el.querySelector('.pct')?.textContent ?? '',
        title: el.querySelector('.title')?.textContent ?? '', lines: [...el.querySelectorAll('.line')].map((l) => l.textContent).join(' | '),
        box: !!box && box.getBoundingClientRect().height > 0 && getComputedStyle(box).visibility !== 'hidden' && getComputedStyle(box).display !== 'none' } : null;
      const key = JSON.stringify(now);
      if (!last || last.key !== key) window.__card.push({ key, t: performance.now(), ...(now ?? { gone: true }) });
    }, 50);
  });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(String(e)));
  await p.goto(url + 'index.html');
  await p.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  const code = await p.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [46.6773, 11.1302], [46.6400, 11.1500], [46.6879, 11.1111]],
    activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }));
  await p.goto(url + 'index.html?slowRoute=3500#p=' + code);
  await p.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  // the calm line comes ~10 s into the calculation (the router held 3.5 s before it routes and at each section)
  const hint = await p.waitForFunction(() => /few minutes/.test(document.querySelector('#mapnotes .mapnote.load')?.textContent ?? ''), null, { timeout: 60_000 })
    .then(() => true, () => false);
  // the stripe moves (sampled twice) and keeps its place when the card is drawn anew each second
  const stripeX = () => p.evaluate(() => { const i = document.querySelector('#mapnotes .mapnote.load .bar.running > i'), b = i?.parentElement.getBoundingClientRect();
    return i ? { x: Math.round(i.getBoundingClientRect().left - b.left), anim: getComputedStyle(i).animationName } : null; });
  const s1 = await stripeX(); await p.waitForTimeout(300); const s2 = await stripeX();
  const { log, now } = await p.evaluate(() => ({ log: window.__card, now: performance.now() }));
  const open = log.filter((x) => !x.gone);
  const calc = open.filter((x) => x.running);
  console.log('(the card: ' + open.map((x) => `${Math.round(x.t)} ${x.running ? 'run' : x.num} ${x.title}${x.lines ? ' / ' + x.lines : ''}`).join(' ; ') + ')');
  check(open.some((x) => !x.running && /^\d{1,3}\s?%$/.test(x.num)), 'long plan: the downloads show their percent');
  check(calc.length > 0 && calc.every((x) => x.num === '' && /Calculating/.test(x.title)),
    `long plan: then the calculation – its line, the running bar, no percent (${[...new Set(calc.map((x) => x.title))].join(' · ')})`);
  const sections = [...new Set(calc.map((x) => x.title.match(/section (\d) of 3/)?.[1]).filter(Boolean))];
  check(sections.length >= 2, `long plan: the section counter advances (${sections.join(' → ')})`);
  const re = /^(\d+) of (\d+) km · (\d+):(\d\d)$/;
  const kms = calc.map((x) => re.exec(x.lines.split(' | ')[0])).filter(Boolean).map((m) => ({ done: +m[1], total: +m[2], s: +m[3] * 60 + +m[4] }));
  check(kms.length > 0 && kms.every((k, i) => !i || (k.done >= kms[i - 1].done && k.s >= kms[i - 1].s)),
    `long plan: the card shows the km routed and the clock, never back (${kms.map((k) => `${k.done}/${k.total}·${k.s}`).join(' ')})`);
  check(new Set(kms.map((k) => k.s)).size >= 5, `long plan: the card's clock ticks (${[...new Set(kms.map((k) => k.s))].join(',')})`);
  check(open.every((x, i) => i === 0 || x.pct >= open[i - 1].pct), `long plan: the percent never goes back (${open.map((x) => x.pct).join(' ')})`);
  // never a card that stands still: a percent changes (or the card goes over to the calculation) within 6 s; the
  // calculation's card changes within 1.5 s (its clock)
  const held = log.filter((x, i) => {
    if (x.gone) return false;
    let j = i + 1;
    // before the router's first number (the data stored, the router starting) only its stripe moves (checked below)
    if (x.running) { if (!x.lines) return false; while (j < log.length && log[j].key === x.key) j++; return (log[j]?.t ?? now) - x.t > 1500; }
    const pctSame = (y) => !y.gone && !y.running && y.pct === x.pct;
    if (i > 0 && pctSame(log[i - 1])) return false;
    while (j < log.length && pctSame(log[j])) j++;
    return (log[j]?.t ?? now) - x.t > 6000;
  });
  check(held.length === 0, `long plan: the card never stands still (${held.map((x) => (x.running ? 'run ' : x.num + ' ') + x.title).join(' | ')})`);
  check(hint, 'long plan: after ~10 s the calm line „A long tour can take a few minutes.“');
  check(s1 && s2 && s1.anim === 'loadrun' && s1.x !== s2.x, `long plan: the running bar moves (${JSON.stringify([s1, s2])})`);
  check(open.every((x) => !x.box), 'long plan: one wait – no wait box over or beside the card');
  // Cancel still stops the calculation (the worker is stopped – client.ts dropPlans)
  await p.click('#mapnotes .mapnote.load button.act').catch(() => {});
  await p.waitForFunction(() => !document.querySelector('#mapnotes .mapnote.load') && document.body.dataset.planning !== '1', null, { timeout: 10_000 }).catch(() => {});
  const after = await p.evaluate(() => ({ plan: !!window.__state.plan, line: document.getElementById('summary').textContent, card: !!document.querySelector('#mapnotes .mapnote.load') }));
  check(!after.plan && !after.card && /calculation stopped/.test(after.line), `long plan: Cancel in the card stops the calculation (${JSON.stringify(after).slice(0, 160)})`);

  // the same wait once the routing data is here (no card): the wait box – its km bar, a running stripe, the calm line.
  // Cancel stopped the worker (its downloads went with it): Calculate fetches them again; then Reverse plans the same
  // points on the data that is now here
  // what the page showed from here on, for the failure message (CI 2026-11-19: no wait box at all, only there)
  await p.evaluate(() => {
    window.__waits = []; const t0 = performance.now();
    window.__waitsTimer = setInterval(() => {
      const box = document.querySelector('#msgcards .msgcard'), card = document.querySelector('#mapnotes .mapnote.load');
      const now = `${box ? 'box[' + box.dataset.id + ']:' + (box.querySelector('.msgtitle')?.textContent ?? '') + '/' + (box.querySelector('.msgprogline')?.textContent ?? '') : 'no box'}`
        + ` | ${card ? 'card:' + (card.querySelector('.title')?.textContent ?? '') + '/' + (card.dataset.pct ?? '') : 'no card'}`
        + ` | planning=${document.body.dataset.planning ?? ''} wait=${document.body.dataset.wait ?? ''} plan=${!!window.__state.plan}`;
      if (window.__waits.at(-1)?.s !== now) window.__waits.push({ t: Math.round(performance.now() - t0), s: now });
    }, 100);
  });
  const steps = [];
  steps.push('planAnyway ' + await p.waitForSelector('#planAnyway', { timeout: 10_000 }).then(() => 'there', () => 'missing'));
  await p.click('#planAnyway', { timeout: 10_000 }).catch((e) => steps.push('planAnyway click failed: ' + String(e).slice(0, 80)));
  steps.push('route ' + await p.waitForFunction(() => window.__state.plan && document.body.dataset.planning !== '1' && !document.querySelector('#mapnotes .mapnote.load'), null, { timeout: 120_000 })
    .then(() => 'in', () => 'not in after 120 s'));
  // the map at rest first: tiles still loading (the route's fit, a slow GPU – CI's software renderer) open the load card,
  // and the calculation then rightly joins the card instead of the box (one wait at a time). A card that still came
  // for the tiles: once more, at rest again
  let boxHint = false;
  for (let k = 0; k < 3 && !boxHint; k++) {
    await p.waitForTimeout(1000); // the route's fit starts 500 ms after the plan
    steps.push('map ' + await p.waitForFunction(() => !window.__map.isMoving() && window.__map.areTilesLoaded() && !document.querySelector('#mapnotes .mapnote.load')
      && document.body.dataset.planning !== '1', null, { timeout: 60_000 }).then(() => 'at rest', () => 'not at rest after 60 s'));
    steps.push('reverse ' + await p.evaluate(() => (document.getElementById('reverse')?.disabled ? 'disabled' : 'enabled')));
    const from = await p.evaluate(() => window.__waits.length);
    await p.click('#reverse', { timeout: 10_000 }).catch((e) => steps.push('reverse click failed: ' + String(e).slice(0, 80)));
    steps.push('plan ' + await p.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 10_000, polling: 50 }).then(() => 'started', () => 'not started in 10 s'));
    boxHint = await p.waitForFunction(() => /few minutes/.test(document.querySelector('#msgcards .msgcard[data-id="routing"] .msgproghint')?.textContent ?? '')
      || document.body.dataset.planning !== '1', null, { timeout: 60_000, polling: 100 })
      .then(() => p.evaluate(() => /few minutes/.test(document.querySelector('#msgcards .msgcard[data-id="routing"] .msgproghint')?.textContent ?? '')), () => false);
    const tilesCard = await p.evaluate((n) => window.__waits.slice(n).some((w) => /card:Loading the map/.test(w.s)), from);
    steps.push(`attempt ${k + 1}: ${boxHint ? 'box with the calm line' : tilesCard ? 'the tiles brought the card' : 'no box'}`);
    if (!boxHint && !tilesCard) break; // not the tiles: a real failure
    if (!boxHint) await p.waitForFunction(() => document.body.dataset.planning !== '1', null, { timeout: 60_000 }).catch(() => {});
  }
  const wb = await p.evaluate(() => { const b = document.querySelector('#msgcards .msgcard[data-id="routing"]');
    const s = b?.querySelector('.msgbar.running b'); const r = s?.getBoundingClientRect(), t = b?.querySelector('.msgproghint')?.getBoundingClientRect(),
      l = b?.querySelector('.msgprogline')?.getBoundingClientRect();
    return b ? { line: b.querySelector('.msgprogline').textContent, anim: s ? getComputedStyle(s).animationName : '', w: Math.round(r?.width ?? 0),
      apart: !!t && !!l && t.top >= l.bottom - 1, card: !!document.querySelector('#mapnotes .mapnote.load') } : null; });
  const waits = await p.evaluate(() => { clearInterval(window.__waitsTimer); return window.__waits; });
  const okBox = boxHint && wb && /^\d+ of \d+ km · \d+:\d\d$/.test(wb.line) && wb.anim === 'loadrun' && wb.w > 0 && wb.apart && !wb.card;
  check(okBox, `long plan without downloads: the wait box – km and clock, a running stripe, the calm line under it (${JSON.stringify(wb)})`);
  if (!okBox) console.log(`(steps: ${steps.join('; ')}\n page: ${waits.map((w) => w.t + ' ' + w.s).join('\n       ')})`);
  await p.click('#msgcards .msgcard[data-id="routing"] .msgbtn').catch(() => {});
  await p.waitForFunction(() => document.body.dataset.planning !== '1', null, { timeout: 10_000 }).catch(() => {});
  check(errs.length === 0, `long plan: no page error (${errs.join(' | ')})`);
  await c.close();
}

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
