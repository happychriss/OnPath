// The app's long-route question and GPX import in the browser (docs/design/web-planner.md §15):
// 1. a plan ~600 km long is asked about first ("This route is very long", the app's words) – no routing data is
//    fetched until it is answered; Cancel of a link's plan keeps the points unrouted ("Plan anyway"), Cancel of a
//    clicked point takes that point back (PlanStatus.onCancel);
// 2. ☰ → Import GPX with a Komoot hike: the track panel, Activity set to Hike T2 from the file, "Plan a route from it"
//    (TrackToPlan) → a plan with few points along the track;
// 3. a .gpx dropped on the map (route + waypoints, <type>MTB</type>), Close track;
// 4. Export GPX ⇄ Import GPX keeps the sport and the line.
// Needs only the rd5 (no map data, CI). Usage: node test/import-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const fixture = (n) => path.join(here, 'fixtures', n);
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, acceptDownloads: true });
await ctx.addInitScript(() => { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('onpath.activity', 'BIKE_TOURING'); sessionStorage.setItem('seeded', '1'); } });
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
let rd5Requests = 0;
page.on('request', (r) => { if (/\/segments\//.test(r.url())) rd5Requests++; });
const pins = () => page.evaluate(() => document.querySelectorAll('.pin').length);
const planned = () => page.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').textContent.includes('Planning'), null, { timeout: 120_000 });
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });

// 1a. a link with two points ~600 km apart: asked first, nothing fetched meanwhile
const far = await page.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [52.0500, 11.1600]], activity: 'HIKE', sac: 2, pinned: [], names: {}, title: null }));
await page.waitForTimeout(1500); // the prefetch of the start view is done
rd5Requests = 0;
await page.goto(url + 'index.html#p=' + far);
await page.waitForSelector('dialog.longroute', { timeout: 30_000 });
const q = await page.textContent('dialog.longroute');
check(/This route is very long/.test(q) && /days on foot\. Calculating and loading its maps takes long\./.test(q), `long plan asked first: "${q.replace(/\s+/g, ' ').trim()}"`);
await page.waitForTimeout(1500);
check(rd5Requests === 0, `no routing data fetched while the question is open (${rd5Requests} requests)`);
await page.click('#lrCancel');
await page.waitForSelector('#planAnyway');
check((await page.textContent('#summary .subline')).includes('Route not calculated – very long plan.'), 'Cancel (a loaded plan): points kept, "Route not calculated – very long plan." + Plan anyway');
check(await pins() === 2, 'both points still there');
// 1b. Plan anyway: no second question, the planner runs (no routing data up north here: its plain error)
await page.click('#planAnyway');
await page.waitForFunction(() => !document.querySelector('dialog.longroute') && /Planned|failed|Planning/.test(document.getElementById('status').textContent), null, { timeout: 120_000 });
check(!(await page.$('dialog.longroute')), 'Plan anyway: routed without asking again');

// 1c. a clicked point that makes a short plan very long: Cancel takes it back (PlanStatus.onCancel → UNDO)
await page.goto(url + 'index.html#p=' + CODE);
await planned();
check(await pins() === 2, 'short plan from a link: 2 points, routed, no question');
await page.evaluate(() => window.__map.jumpTo({ center: [11.16, 51.2], zoom: 5.5 }));
await page.waitForTimeout(500);
await clickAt(11.16, 52.05);
await page.waitForSelector('dialog.longroute', { timeout: 30_000 });
await page.click('#lrCancel');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 2 && document.querySelector('#summary .big'), null, { timeout: 60_000 }).catch(() => {});
check(await pins() === 2 && !!(await page.$('#summary .big')), 'Cancel (a clicked point): the point is taken back, the short route stays');

// 2. ☰ → Import GPX (Komoot hike) while the activity is Bike
await page.evaluate(() => { localStorage.setItem('onpath.activity', 'BIKE_TOURING'); });
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
if (await page.isVisible('#menuBtn')) await page.click('#menuBtn'); // phone width: ☰; wide: the header line
check(await page.isVisible('#menuImportGpx') && (await page.textContent('#menuImportGpx')).includes('Import GPX'), 'header / ☰ has Import GPX');
const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('#menuImportGpx')]);
await chooser.setFiles(fixture('komoot-hike.gpx'));
await page.waitForSelector('#trackPanel', { timeout: 30_000 });
const tp = await page.textContent('#trackPanel');
console.log('track panel:', tp.replace(/\s+/g, ' ').trim());
check(tp.includes('Meran 2000 & Ifinger Hütte') && /2[.,]4 km/.test(tp) && tp.includes('1 h 21 min'), 'track panel: name, distance, the file\'s own time');
check(tp.includes('Activity set to Hike T2 from the file'), 'Komoot <type>hike</type> → Hike T2, said in one grey line');
check((await page.textContent('#activityChip')).includes('T2') && (await page.getAttribute('#activityChip', 'aria-label')).includes('Hike'), 'the activity chip shows Hike · T2');
check(await page.evaluate(() => window.__map.getSource('gpx-track').serialize().data.features.length) >= 1, 'the track is drawn (gpx-track)');
// … "Plan a route from it": the app's TrackToPlan
await page.click('#planFromTrack');
await page.waitForFunction(() => !document.getElementById('trackPanel'), null, { timeout: 120_000 });
await planned();
const n = await pins();
const sum = await page.textContent('#summary');
check(n >= 2 && n <= 8 && /2[.,][345] km/.test(sum), `Plan a route from it: ${n} route points, ${sum.match(/[\d.,]+ km/)?.[0]} along the track`);
check(await page.evaluate(() => window.__map.getSource('gpx-track').serialize().data.features.length) === 0, 'the track is gone once it is a plan');

// 4. Export GPX ⇄ Import GPX: the sport and the line come back
await page.click('#sheetMenuBtn');
const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#exportGpx')]);
const exported = await dl.path();
const gpx = fs.readFileSync(exported, 'utf8');
check(gpx.includes('<type>hike</type>'), 'Export GPX writes <type>hike</type>');
const planDist = sum.match(/[\d.,]+ km/)?.[0];
await page.evaluate(async (text) => {
  const f = new File([text], 'exported.gpx', { type: 'application/gpx+xml' });
  await window.__gpx.importFile(f);
}, gpx);
await page.waitForSelector('#trackPanel');
const tp2 = await page.textContent('#trackPanel');
check(tp2.includes(planDist) && !tp2.includes('Activity set to'), `re-import of the export: same ${planDist}, activity unchanged (no grey line)`);
await page.click('#closeTrack');
check(!(await page.$('#trackPanel')) && await page.isVisible('#summary'), 'Close track: the plan sheet is back');

// 3. a .gpx dropped on the map: route + waypoints, <type>MTB</type>
const dropText = fs.readFileSync(fixture('mtb-route.gpx'), 'utf8');
await page.evaluate((text) => {
  const dt = new DataTransfer();
  dt.items.add(new File([text], 'mtb-route.gpx', { type: 'application/gpx+xml' }));
  const box = document.getElementById('map');
  box.dispatchEvent(new DragEvent('dragover', { dataTransfer: dt, bubbles: true, cancelable: true }));
  box.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true }));
}, dropText);
await page.waitForSelector('#trackPanel');
const tp3 = await page.textContent('#trackPanel');
check(tp3.includes('Lana – Völlan <MTB>') && tp3.includes('Activity set to MTB from the file') && tp3.includes('Some parts of the file were skipped.'),
  'dropped GPX: its name, MTB from the file, the skipped point said');
check(await page.evaluate(() => window.__map.getSource('gpx-track').serialize().data.features.filter((f) => f.geometry.type === 'Point').length) === 2, 'its two waypoints are drawn');
await page.click('#closeTrack');

// a broken file: the readable part, never an error box
await page.evaluate(async (text) => { await window.__gpx.importFile(new File([text], 'broken.gpx')); }, fs.readFileSync(fixture('broken.gpx'), 'utf8'));
await page.waitForSelector('#trackPanel');
check((await page.textContent('#trackPanel')).includes('Some parts of the file were skipped.'), 'broken GPX: the readable part with the grey line');
await page.click('#closeTrack');
await page.evaluate(async () => { await window.__gpx.importFile(new File(['<gpx></gpx>'], 'empty.gpx')); });
await page.waitForFunction(() => document.getElementById('status').classList.contains('error'), null, { timeout: 10_000 }).catch(() => {});
check((await page.textContent('#status')).includes('Could not import this file: No track, route or waypoint found in this file.'), 'empty GPX: the app\'s plain error');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE|ERROR) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED (${fails.length})` : 'all ok');
process.exit(fails.length ? 1 : 0);
