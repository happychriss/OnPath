// Plan per QR code / link (web planner phase 1): opens a plan code made by the app's Kotlin (PlanCodeTest,
// formatVersion1IsFrozen), checks the page routes it, writes the same code back and shows it as a QR code.
// Usage: node test/share-smoke.mjs [segmentsDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
check(await page.getAttribute('#share', 'title') === 'Share' && (await page.textContent('dialog.share .scanline')).includes('Scan with the OnPath app'), 'Share (icon next to ⋯) → "Scan with the OnPath app" above the QR code');
check(await page.evaluate(() => document.querySelectorAll('dialog.share .qr svg rect, dialog.share .qr svg path').length) > 0, 'QR code drawn');
await page.screenshot({ path: shot });
await page.click('#closeShare');

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
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('plan link'), null, { timeout: 60_000 }).catch(() => {});
check((await page.textContent('#status')).includes("This plan link can't be read"), 'broken link → plain message');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
