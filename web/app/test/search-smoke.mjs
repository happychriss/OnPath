// Search with the app's code: offline hits from the map cell in view (POIs, base-map places and peaks, trail names),
// the place card ("Add to route" says where before the click) and adding a found place as a named, pinned stop.
// Online (Photon) only when ONLINE=1 (CI has network; the result is checked loosely).
// Usage: node test/search-smoke.mjs [segmentsDir] [mapStoreDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const shot = process.argv[4] ?? path.join(here, '../../engine/build/search.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const proxy = process.env.ONLINE === '1' && process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY, bypass: '127.0.0.1,localhost' } : undefined;
const browser = await chromium.launch({ proxy, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, ignoreHTTPSErrors: true });
if (process.env.ONLINE !== '1') await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
const titles = async () => page.$$eval('#results .hit b', (b) => b.map((x) => x.textContent));
const searchFor = async (t) => {
  await page.fill('#q', t);
  await page.waitForFunction(() => document.querySelector('#results .hit') || document.querySelector('#results .pad'), null, { timeout: 60_000 });
  await page.waitForTimeout(700);
  return titles();
};

const t0 = Date.now();
let hits = await searchFor('Vellau');
console.log('Vellau →', hits.slice(0, 5), `${Date.now() - t0} ms incl. indexing`);
check(hits.some((h) => /Vellau/.test(h)), 'a place from the base map (Vellau)');
hits = await searchFor('Ifinger');
console.log('Ifinger →', hits.slice(0, 5));
check(hits.some((h) => /Ifinger/.test(h)), 'a peak (Ifinger)');
hits = await searchFor('hutte');
console.log('hutte →', hits.slice(0, 5));
check(hits.some((h) => /Hütte/.test(h)), 'accents stripped like the app (hutte → Hütte)');
hits = await searchFor('Waalweg');
console.log('Waalweg →', hits.slice(0, 5));
check(hits.length > 0, 'trail names (Waalweg)');

// pick → card → Add to route (empty plan: as start)
await searchFor('Vellau');
await page.click('#results .hit');
await page.waitForSelector('#placecard:not([hidden]) #cadd');
const add = await page.textContent('#cadd');
console.log('card:', add.replace(/\s+/g, ' '));
check(/as start/.test(add), 'card says where it goes (empty plan → as start)');
await page.click('#cadd');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1, null, { timeout: 10_000 });
await searchFor('Ifinger');
await page.click('#results .hit');
await page.waitForSelector('#placecard:not([hidden]) #cdest');
await page.waitForTimeout(800); // the online search would have answered by now (owner 2026-10-28: the list came back)
const card = await page.locator('#placecard').boundingBox(), sheet = await page.locator('#sheet').boundingBox();
check(card.x >= sheet.x + sheet.width || card.y >= sheet.y + sheet.height, 'place card beside the plan sheet, not over it');
check(await page.isHidden('#results'), 'the results list stays closed while the card is shown');
await page.click('#cdest');
check(await page.isHidden('#placecard'), 'the card closes after its action');
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
const pts = await page.textContent('#points');
console.log('points:', pts.replace(/\s+/g, ' '));
check(/Vellau/.test(pts) && /Ifinger/.test(pts), 'found places are named stops in the Points list');
check((await page.evaluate(() => location.hash)).length > 10, 'plan with pinned, named places in the link');
await page.screenshot({ path: shot });
// Save proposes a name like the app (RouteNamer: the places the stops came from)
await page.click('#sheetMenuBtn');
await page.click('#sheetSave');
await page.waitForFunction(() => document.getElementById('saveTitle')?.value, null, { timeout: 30_000 }).catch(() => {});
const proposed = await page.inputValue('#saveTitle');
console.log('proposed name:', proposed);
check(proposed === 'Vellau → Ifinger Hütte', 'Save proposes "Vellau → Ifinger Hütte" (RouteNamer)');
await page.click('#saveCancel');

if (process.env.ONLINE === '1') {
  hits = await searchFor('Innsbruck');
  await page.waitForTimeout(2500);
  hits = await titles();
  console.log('online Innsbruck →', hits.slice(0, 5));
  check(hits.some((h) => /Innsbruck/.test(h)), 'online result (Photon) mixed in');
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/ERR_FAILED|net::|CORS policy/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
