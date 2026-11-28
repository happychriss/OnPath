// Plan per QR code / link (web planner phase 1): opens a plan code made by the app's Kotlin (PlanCodeTest,
// formatVersion1IsFrozen), checks the page routes it, writes the same code back and shows it as a QR code.
// Usage: node test/share-smoke.mjs [segmentsDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const shot = process.argv[3] ?? path.join(here, '../../engine/build/share.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, mapDir: null });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

// 1. open the app's code from a link
await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check((await page.textContent('#points')).includes('Vellau'), 'point name from the code is shown');
check((await page.textContent('#activityChip')).replace(/\s+/g, ' ').includes('Hike · T3'), 'activity Hike, T3');
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 2, 'two points on the map');
await page.waitForTimeout(500); // the history state comes back from the engine worker
check(await page.isHidden('#undoPill'), 'a plan opened from a link has nothing to undo – no undo pill (owner 2026-11-13)');
// the address holds the plan in the app's code; after routing the free start sits on the way the route uses (the app's
// PlanResult snapping, as the phone stores it), the pinned + named Vellau keeps its place exactly
await page.waitForTimeout(1500);
const opened = await page.evaluate((c) => window.__engine.parse('p=' + c), CODE);
const shown = await page.evaluate(() => window.__engine.parse(location.hash.slice(1)));
const metres = (a, b) => {
  const k = Math.PI / 180, x = (b[1] - a[1]) * k * Math.cos(((a[0] + b[0]) / 2) * k), y = (b[0] - a[0]) * k;
  return Math.sqrt(x * x + y * y) * 6371000;
};
const startMoved = metres(opened.points[0], shown.points[0]);
check(JSON.stringify({ ...shown, points: null }) === JSON.stringify({ ...opened, points: null }) && metres(opened.points[1], shown.points[1]) < 1.5 && startMoved < 60,
  `the address holds the app's code of the plan: same activity, names, pins, title; Vellau exactly, the free start on the way (${startMoved.toFixed(0)} m)`);
const hash = await page.evaluate(() => location.hash);

// 2. share dialog with QR code and link
await page.click('#share');
await page.waitForSelector('dialog.share[open] .qr svg');
const link = await page.inputValue('#shareLink');
check(link.endsWith(hash), 'link in the dialog carries the code of the address');
check(link === new URL(url).origin + '/p/' + hash, `plan links have their own path /p/ (owner 2026-11-28): ${link.slice(0, 60)}`);
check(await page.getAttribute('#share', 'title') === 'Share' && (await page.textContent('dialog.share .scanline')).includes('Scan with the OnPath app'), 'Share (icon next to ⋯) → "Scan with the OnPath app" above the QR code');
check(await page.evaluate(() => document.querySelectorAll('dialog.share .qr svg rect, dialog.share .qr svg path').length) > 0, 'QR code drawn');
await page.screenshot({ path: shot });
await page.click('#closeShare');

// 2b. the /p/ link opens the same plan (the page under / with the #fragment kept)
{
  const p2 = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await p2.goto(link);
  await p2.waitForFunction(() => window.__state?.points?.length >= 2, null, { timeout: 60_000 }).catch(() => {});
  check(await p2.evaluate(() => location.pathname === '/' && location.hash.length > 3 && window.__state.points.length >= 2), `/p/#p=… opens the plan (${await p2.evaluate(() => location.pathname + ' ' + (window.__state?.points?.length ?? 0))})`);
  await p2.close();
}

// 3. an edit changes the code; a reload brings the edited plan back
const before = await page.evaluate(() => location.hash);
await page.click('#reverse');
await page.waitForFunction((b) => location.hash !== b, before, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) !== before, 'reversed plan → new code in the address');
await page.reload();
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check((await page.textContent('#points li:first-child .txt > span:first-child')).trim().startsWith('Vellau'), 'reload keeps the reversed plan (Vellau first)');

// 4. a broken link is a plain message, no crash
await page.goto(url + 'index.html#p=AQ');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes("can't be read"), null, { timeout: 60_000 }).catch(() => {});
check((await page.textContent('#status')).includes("This plan link can't be read"), 'broken link → plain message');

// 5. a long link on a freshly loaded engine (its own process: a small stack, Wasm's slow tier)
check(spawnSync(process.execPath, [path.join(here, 'longlink-engine.mjs')], { stdio: 'inherit' }).status === 0, 'a long plan link opens on a fresh page');
// 6. a plan with detour points (detour-stitch.md §6 step 3): read, routed through them, shared on with the same code
await page.goto('about:blank');
await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
const made = await page.evaluate(async (c) => {
  const e = window.__engine, s = await e.parse('p=' + c), r = (await e.plan(s.points, s.activity, s.sac, s.pinned)).plan;
  const line = r.legs.flatMap((l) => l.points), m = line[line.length >> 1];
  const six = (x) => [+x[0].toFixed(6), +x[1].toFixed(6)];
  const anchors = JSON.stringify({ activity: s.activity, sac: s.sac, entries: [{ i: 0, p: six(s.points[0]), b: [], a: [six(m)] }] });
  return { code: await e.encode({ ...s, anchors }), anchor: six(m),
    long: await e.encode({ ...s, tour: { days: 2, limits: { timeMin: null, ascentM: null }, nights: {}, description: 'x'.repeat(4000) } }) };
}, CODE);
await page.goto('about:blank');
await page.goto(url + 'index.html#p=' + made.code);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForTimeout(1500); // the snapped start written back to the address
const read = await page.evaluate(async (a) => {
  const e = window.__engine, s = await e.parse(location.hash.slice(1));
  let total = 0;
  const r = (await e.plan(s.points, s.activity, s.sac, s.pinned, (p) => { total = Math.max(total, p.total ?? 0); })).plan;
  const near = r.legs.flatMap((l) => l.points).some((q) => Math.abs(q[0] - a[0]) < 2e-5 && Math.abs(q[1] - a[1]) < 2e-5);
  return { anchor: s.anchors && JSON.parse(s.anchors).entries[0]?.a[0], near, total, legs: r.legs.length, same: await e.encode(s) === location.hash.slice(3), hash: location.hash };
}, made.anchor);
check(JSON.stringify(read.anchor) === JSON.stringify(made.anchor), 'the page keeps the detour point in its address');
check(read.near, 'the route runs through the detour point');
check(read.same, 'read and written again: the same code');
check(read.total <= 1, `the wait counts the visible legs only (${read.total})`);
await page.click('#share');
await page.waitForSelector('dialog.share[open]');
check((await page.inputValue('#shareLink')).endsWith(read.hash), 'the shared link carries the detour point');
await page.click('#closeShare');
// a link too long for a QR code: the dialog offers the link alone
await page.goto('about:blank');
await page.goto(url + 'index.html#p=' + made.long);
await page.waitForFunction(() => document.body.dataset.planning !== '1' && document.querySelector('#share')?.disabled === false, null, { timeout: 120_000 });
await page.click('#share');
await page.waitForSelector('dialog.share[open]');
check(await page.evaluate(() => !document.querySelector('dialog.share .qr') && !document.querySelector('dialog.share .scanline')), 'too long for a QR code: no code, no line about scanning');
check((await page.inputValue('#shareLink')).length > 2400, 'too long for a QR code: the link is there');
await page.click('#closeShare');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
