// The phone's Plan screen features in the browser (docs/design/web-planner.md §11): first-point menu (Start here ·
// Go here from my position), undo / redo (buttons and keys – the app's PlanHistory), the eye (hold hides route and
// markers), the compass (turned map → north up), the point menu's "Open in Google Maps" (a new tab with the app's
// link), Export GPX, Points delete, route warnings, and the bottom sheet at phone width. Needs only the rd5 (no map data, CI).
// Usage: node test/parity-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, permissions: ['geolocation'],
  geolocation: { latitude: 46.6705, longitude: 11.1605, accuracy: 10 } });
await ctx.route('https://www.google.com/**', (r) => r.fulfill({ body: 'Google Maps stand-in', contentType: 'text/html' })); // never the real site
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const pins = () => page.evaluate(() => document.querySelectorAll('.pin').length);
const waitPins = (n) => page.waitForFunction((k) => document.querySelectorAll('.pin').length === k, n, { timeout: 15_000 }).catch(() => {});
const planned = () => page.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').textContent.includes('Planning'), null, { timeout: 120_000 });
const clickAt = async (lon, lat, button = 'left') => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1], { button });
};
const menuItems = () => page.$$eval('.pointmenu button .txt > span:first-child', (b) => b.map((x) => x.textContent));

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });

// 1. first click on an empty plan: "New route" – Start here · Go here (MapGestures.FirstPointMenu)
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu');
check((await page.textContent('.menuhead')).includes('New route') && (await menuItems()).join('|') === 'Start here|Go here', 'first click: New route – Start here · Go here');
check(await page.isHidden('#undo'), 'no undo before the first change');
await page.click('.pointmenu button:first-of-type');
await waitPins(1);
check(await pins() === 1, 'Start here sets the start');
check(await page.isVisible('#undo') && await page.isHidden('#redo'), 'undo shown, redo hidden');
await clickAt(11.1350, 46.6840);
await planned();
check(await pins() === 2, 'next click adds the destination (no menu)');
check(await page.isVisible('#eye'), 'eye shown once there is a route');

// 2. undo / redo (buttons and keys)
await page.click('#undo'); await waitPins(1);
check(await pins() === 1 && await page.isVisible('#redo'), 'undo takes the destination back, redo appears');
await page.click('#redo'); await waitPins(2);
check(await pins() === 2, 'redo brings it back');
await page.keyboard.press('Control+z'); await waitPins(1);
check(await pins() === 1, 'Ctrl+Z undoes');
await page.keyboard.press('Control+Shift+z'); await waitPins(2);
check(await pins() === 2, 'Ctrl+Shift+Z redoes');
await planned();

// 3. the eye: held = route and markers hidden, released = back
const box = await page.locator('#eye').boundingBox();
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
await page.mouse.down();
const held = await page.evaluate(() => ({ line: window.__map.getLayoutProperty('planned-route', 'visibility'),
  pin: getComputedStyle(document.querySelector('.pin')).visibility }));
await page.mouse.up();
const released = await page.evaluate(() => ({ line: window.__map.getLayoutProperty('planned-route', 'visibility'),
  pin: getComputedStyle(document.querySelector('.pin')).visibility }));
check(held.line === 'none' && held.pin === 'hidden', 'eye held: route and markers hidden');
check(released.line !== 'none' && released.pin === 'visible', 'eye released: shown again');

// 4. orientation: a turned map shows it on the compass; a click turns it north up
await page.evaluate(() => window.__map.setBearing(45));
await page.waitForTimeout(100);
check((await page.getAttribute('#compass', 'title')).includes('turned') &&
  (await page.getAttribute('#compass .needle', 'style')).includes('rotate(-45deg)'), 'compass shows the turned map');
await page.click('#compass');
await page.waitForFunction(() => Math.abs(window.__map.getBearing()) < 0.5, null, { timeout: 5_000 }).catch(() => {});
check(Math.abs(await page.evaluate(() => window.__map.getBearing())) < 0.5, 'compass click: north up');

// 5. point menu on a middle point says what Start here removes; Delete via the Points list ✕
await clickAt(11.1500, 46.6950); await planned(); // a third point (new destination or a via, as the app decides)
await page.locator('.pin').nth(1).click();
await page.waitForSelector('.pointmenu');
const mid = await menuItems();
check(mid[0] === 'Start here' && mid.at(-1) === 'Delete', 'point menu of a middle point: Start here … Delete');
await page.keyboard.press('Escape');
await page.click('#points li:nth-child(2) .del');
await waitPins(2);
check(await pins() === 2, 'Points list ✕ deletes the point');

// 6. Export GPX (the app's GpxWriter)
await planned();
await page.click('#sheetMenuBtn');
const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#exportGpx')]);
const gpx = await (await import('node:fs')).promises.readFile(await dl.path(), 'utf8');
check(dl.suggestedFilename().endsWith('.gpx') && gpx.includes('<trkpt') && gpx.includes('<type>'), 'Export GPX downloads a track with the sport type');

// 7. Go here from my position (browser location)
await page.click('#sheetMenuBtn'); await page.click('#clear'); await waitPins(0);
await clickAt(11.1350, 46.6840);
await page.waitForSelector('.pointmenu');
await page.click('.pointmenu button:nth-of-type(2)'); // Go here
await planned();
const first = await page.evaluate(() => window.__engine && location.hash);
check(await pins() === 2 && first.length > 10, 'Go here: route from my position to the clicked spot');

// 8. Google Maps link-out from a named point (new tab, the app's PoiLinks link, nothing drawn here)
await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.locator('.pin').nth(1).click(); // Vellau, the named destination
await page.waitForSelector('.pointmenu');
check((await menuItems()).includes('Open in Google Maps'), 'named point: Open in Google Maps in the menu');
const [tab] = await Promise.all([ctx.waitForEvent('page'), page.click('.pointmenu button:has-text("Open in Google Maps")')]);
await tab.waitForLoadState().catch(() => {});
console.log('google tab:', tab.url());
check(/^https:\/\/www\.google\.com\/maps\/search\/Vellau\/@46\.\d+,11\.\d+,16z$/.test(tab.url()), 'Google Maps opens in a new tab with the place name near its point');
await tab.close();

// 9. route warnings (the app's RouteWarnings): MTB up the Ifinger → amber line, click → "On this route" rows
const steep = await page.evaluate(() => window.__engine.encode({ points: [[46.6870, 11.2350], [46.7006, 11.2585]], activity: 'MTB', sac: 2, pinned: [], names: {}, title: null }));
await page.goto(url + 'index.html#p=' + steep); // only the hash changes: the page opens the plan (hashchange)
await page.waitForFunction(() => !document.getElementById('warning').hidden, null, { timeout: 120_000 }).catch(() => {});
const wl = await page.textContent('#warning');
console.log('warning:', wl);
check(await page.isVisible('#warning') && /steep/.test(wl), 'route warning line (steep sections) shown');
await page.click('#warning');
check(await page.locator('#spots li').count() > 0, 'click → the spots on this route');

// 10. phone width: bottom sheet; the handle shows the Points
await page.setViewportSize({ width: 412, height: 860 });
await page.waitForTimeout(300);
const sheet = await page.locator('#sheet').boundingBox();
check(sheet.y > 300 && sheet.y + sheet.height >= 855, 'phone: the plan sheet sits at the bottom');
check(await page.isHidden('#points'), 'phone: Points folded away');
await page.click('#handle');
check(await page.isVisible('#points'), 'phone: the handle shows the Points');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
