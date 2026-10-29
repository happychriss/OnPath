// Plan edits with the app's code (PlanEdits, PlanPoints) and the activity switch: two points → Bike → the plan is
// routed again with the Bike profile; Round trip → loop shown once with ↺; point menu "Start & finish here";
// Reverse. Usage: node test/edit-smoke.mjs [segmentsDir] [mapStoreDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const shot = process.argv[4] ?? path.join(here, '../../engine/build/edits.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const summary = async () => (await page.textContent('#summary')).replace(/\s+/g, ' ').trim();
const planned = async (before) => {
  await page.waitForFunction((b) => { const s = document.querySelector('#summary'); return s?.querySelector('.big') && s.textContent.replace(/\s+/g, ' ').trim() !== b && !document.getElementById('status').textContent.includes('Planning'); }, before, { timeout: 120_000 });
  await page.waitForTimeout(300);
};

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu'); // first click: New route – Start here · Go here
await page.click('.pointmenu button:first-of-type'); await page.waitForTimeout(300);
await clickAt(11.1350, 46.6840); await page.waitForTimeout(300);
await clickAt(11.1500, 46.6950);
await planned('');
const hike = await summary();
console.log('hike:', hike);

// activity switch re-plans
await page.click('#activityChip');
await page.click('[data-act=BIKE_TOURING]');
check(await page.isHidden('#activityMenu'), 'picking an activity closes the drop-down (no Done)');
await planned(hike);
const bike = await summary();
console.log('bike:', bike);
check(bike !== hike, 'switching Hike → Bike plans again (summary changed)');
check((await page.textContent('#activityChip')).includes('Bike'), 'chip shows Bike');

// round trip: loop shown once with ↺
await page.click('#roundTrip');
await planned(bike);
check((await page.textContent('#points')).includes('↺'), 'round trip: start shown with ↺ (PlanPoints rows)');
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 3, 'round trip: 3 markers (closing point not drawn twice)');
check(await page.isDisabled('#roundTrip'), 'Round trip greyed out once it is one');

// point menu on point 2 → "Start & finish here" rotates the loop
const loopBefore = await page.textContent('#points');
await page.locator('.pin').nth(1).click();
await page.waitForSelector('.pointmenu');
const items = await page.$$eval('.pointmenu button .txt > span:first-child', (b) => b.map((x) => x.textContent));
check(items.join('|') === 'Start & finish here|Delete', 'point menu of a loop point: Start & finish here · Delete');
await page.click('.pointmenu button:first-of-type');
await page.waitForFunction((b) => document.getElementById('points').textContent !== b, loopBefore, { timeout: 10_000 }).catch(() => {});
check((await page.textContent('#points')) !== loopBefore, 'Start & finish here rotated the loop');
await page.screenshot({ path: shot });

// reverse
const beforeRev = await page.evaluate(() => location.hash);
await page.click('#reverse');
await page.waitForFunction((b) => location.hash !== b, beforeRev, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) !== beforeRev, 'Reverse changed the plan');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
