// A shared plan link opened on a phone (owner 2026-11-13 field report): the person who gets a link sees the route on the
// map, not the plan sheet. While the routing data still loads: the sheet peeks (collapsed, the facts only), one short wait
// line and no box over the map; once routed the route lies whole in the map room above the peek. Wide screens unchanged.
// Usage: node test/phonelink-smoke.mjs [segmentsDir] [cellStoreDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];

/** Every wait text a person sees now: the box over the map, the map strip, the sheet's line – visible ones only. */
const waits = (page) => page.evaluate(() => {
  const seen = (el) => !!el && !el.hidden && el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
  const out = [];
  const card = document.querySelector('#msgcards .msgcard[data-id="routing"]');
  if (seen(card)) out.push('box: ' + card.innerText.replace(/\s+/g, ' ').trim());
  // a wait = a busy strip; an info strip (e.g. no detailed map for this area on the web yet) is no wait
  for (const n of document.querySelectorAll('#mapnotes .mapnote.busy')) if (seen(n)) out.push('strip: ' + n.innerText.replace(/\s+/g, ' ').trim());
  const sub = document.querySelector('#summary .subline');
  if (seen(sub) && sub.innerText.trim()) out.push('sheet: ' + sub.innerText.replace(/\s+/g, ' ').trim());
  return out;
});
/** Share of the route line's points that lie in the free map room: below the top bar, above the sheet, inside the width. */
const routeInView = (page) => page.evaluate(() => {
  const plan = window.__state.plan; if (!plan) return 0;
  const map = window.__map, stage = document.getElementById('stage').getBoundingClientRect();
  const top = document.getElementById('topbar').getBoundingClientRect().bottom, bottom = document.getElementById('sheet').getBoundingClientRect().top;
  const pts = plan.legs.flatMap((l) => l.points);
  const inside = pts.filter(([la, lo]) => {
    const p = map.project([lo, la]); const x = p.x + stage.left, y = p.y + stage.top;
    return x >= stage.left && x <= stage.right && y >= top && y <= bottom;
  });
  return inside.length / Math.max(1, pts.length);
});

// 1. phone, first visit, the routing data slow: the sheet peeks, one short wait line, no box over the map
{
  const ctx = await browser.newContext({ viewport: { width: 412, height: 839 }, deviceScaleFactor: 1, isMobile: true, hasTouch: true, locale: 'en-US' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  let release; const held = new Promise((ok) => { release = ok; });
  await page.route(/\/segments\//, async (r) => { await held; await r.continue().catch(() => {}); });
  await page.goto(url + 'index.html?waitMs=0&lang=en#p=' + CODE);
  await page.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(1500);
  check(await page.getAttribute('#sheet', 'data-step') === 'small', 'phone, link loading: the sheet peeks (collapsed)');
  const w = await waits(page);
  check(w.length === 1, `phone, link loading: one wait line, not a block (${JSON.stringify(w)})`);
  check(w.every((s) => s.replace(/^\w+: /, '').length <= 40), 'phone, link loading: the wait line is short (≤ 40 characters)');
  check(!w.some((s) => s.startsWith('box:')), 'phone, link loading: no wait box over the map');
  const peek = await page.evaluate(() => document.getElementById('sheet').getBoundingClientRect().height / innerHeight);
  check(peek < 0.35, `phone, link loading: the peek leaves the map the main thing (${Math.round(peek * 100)} % of the height)`);
  release();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  await page.waitForTimeout(1500);
  await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 20_000 }).catch(() => {});

  // 2. once routed: still collapsed (length · ↑ ↓ in the peek), the whole route in the map room above it
  check(await page.getAttribute('#sheet', 'data-step') === 'small', 'phone, link routed: the sheet stays collapsed');
  check(await page.isVisible('#summary .big.dist') && await page.isVisible('#summary .big.updown'), 'phone, link routed: the peek shows km and ↑ ↓');
  check(!await page.isVisible('#pointsSection'), 'phone, link routed: the points list waits behind the handle');
  const share = await routeInView(page);
  check(share >= 0.98, `phone, link routed: the route lies in the map above the peek (${Math.round(share * 100)} %)`);
  // the user pulls it up
  await page.click('#handle');
  check(await page.getAttribute('#sheet', 'data-step') === 'points' && await page.isVisible('#pointsSection'), 'phone: the handle pulls the sheet up');
  // a reload is the user's own plan in the address, no link from someone: the step they chose stays
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  check(await page.getAttribute('#sheet', 'data-step') === 'points', 'phone: a reload keeps the step the user chose');
  await ctx.close();
}

// 3. a plain visit on a phone (no link) keeps the remembered / default step – nothing forced
{
  const ctx = await browser.newContext({ viewport: { width: 412, height: 839 }, isMobile: true, hasTouch: true, locale: 'en-US' });
  const page = await ctx.newPage();
  await page.goto(url + 'index.html?lang=en');
  await page.waitForFunction(() => document.getElementById('sheet')?.dataset.step, null, { timeout: 60_000 });
  check(await page.getAttribute('#sheet', 'data-step') === 'points', 'phone, no link: the default step as before');
  await ctx.close();
}

// 4. wide: the side panel as before (points shown, the wait box over the map while routing)
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-US' });
  const page = await ctx.newPage();
  let release; const held = new Promise((ok) => { release = ok; });
  await page.route(/\/segments\//, async (r) => { await held; await r.continue().catch(() => {}); });
  await page.goto(url + 'index.html?waitMs=0&lang=en#p=' + CODE);
  await page.waitForFunction(() => document.body.dataset.planning === '1', null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(1500);
  check((await waits(page)).some((s) => s.startsWith('box:')), 'wide, link loading: the wait box with Cancel as before');
  release();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  check(await page.isVisible('#pointsSection'), 'wide, link routed: the side panel shows the points as before');
  await ctx.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
