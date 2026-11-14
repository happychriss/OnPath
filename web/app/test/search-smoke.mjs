// Search with the app's code: offline hits from the map cell in view (POIs, base-map places and peaks, trail names),
// a pick opens the found place's pin with its hold menu (the app's 6714ad6f – above the pin, else a card at the bottom) and
// adding a found place as a named, pinned stop.
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
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); if (process.env.DEBUG && /\[(search|map)\]/.test(m.text())) console.log('  page:', m.text()); });

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
const titles = async () => page.$$eval('#results .hit b', (b) => b.map((x) => x.textContent));
// the list shown may still be an older one – the focus's "Type a name" hint or the last word's rows – while this word's
// search runs (the first one indexes the map areas, > 1 s on a slow machine): wait until the engine answered THIS text
await page.evaluate(() => {
  const engine = window.__engine, search = engine.search.bind(engine);
  engine.search = async (text, ...rest) => { const hits = await search(text, ...rest); window.__searched = text; return hits; };
});
const searchFor = async (t) => {
  await page.fill('#q', t);
  await page.waitForFunction((t) => window.__searched === t && (document.querySelector('#results .hit') || document.querySelector('#results .pad')), t, { timeout: 60_000 });
  await page.waitForTimeout(700);
  return titles();
};

const t0 = Date.now();
// the map areas in view are found a moment after the engine is ready (cells.ts): the first search may come too early
let hits = await searchFor('Vellau');
for (let i = 0; i < 5 && !hits.length; i++) { await page.waitForTimeout(1500); await page.fill('#q', ''); hits = await searchFor('Vellau'); }
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

// pick → the pin and, once the map is there, the pin's own hold menu opens by itself (app 6714ad6f, owner 2026-11-13
// "A mit Ausweich B"): the very rows a hold gives, in the same order (PinMenuItems), "Remove pin" last (before "More");
// above the pin when it fits, else under it, else the same rows as a card at the bottom. No "Set as start" card any more.
const menuRows = () => page.$$eval('.holdpopup.pinmenu .pointmenu button, #placecard.pinmenucard .pointmenu button',
  (b) => b.map((x) => x.querySelector('.txt > span')?.textContent?.trim() ?? ''));
const pinMenuOpen = () => page.waitForSelector('.holdpopup.pinmenu .pointmenu, #placecard.pinmenucard .pointmenu', { timeout: 30_000 });
const pickFirst = async (q) => { await searchFor(q); await page.click('#results .hit'); await pinMenuOpen(); await page.waitForTimeout(400); };
const box = (sel) => page.evaluate((s) => { const r = document.querySelector(s)?.getBoundingClientRect(); return r ? { x: r.x, y: r.y, w: r.width, h: r.height } : null; }, sel);
await pickFirst('Vellau');
let rows = await menuRows();
console.log('empty plan, menu:', rows.join(' · '));
check(rows.join('|') === 'Start here|Go here|Open in Google Maps|Remove pin', `empty plan: the pin's menu opens by itself with the hold's rows + Remove pin (${rows.join(' · ')})`);
check(await page.locator('#placecard #cadd, #placecard #cstart, .pinchip .pinadd').count() === 0, 'no old place card / "Hierhin" button');
const pinBox = await box('.candidate'), menuBox = await box('.holdpopup.pinmenu');
check(!!menuBox && menuBox.y + menuBox.h <= pinBox.y + 2, `wide map: the menu above the pin (menu bottom ${menuBox && Math.round(menuBox.y + menuBox.h)}, pin top ${Math.round(pinBox.y)})`);
check(await page.locator('.pinchip').isHidden(), 'the name chip hides while the menu is open (the menu has the name on top)');
await page.click('.holdpopup.pinmenu button:has-text("Start here")');
await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1, null, { timeout: 10_000 });
check(await page.locator('.candidate').count() === 0 && await page.locator('.holdpopup.pinmenu').count() === 0, 'Start here: the place is the start, pin and menu gone');
await pickFirst('Ifinger');
rows = await menuRows();
check(rows.join('|') === 'Set as destination|Open in Google Maps|Remove pin', `only a start: "Set as destination", Google, Remove pin (${rows.join(' · ')})`);
await page.waitForTimeout(800); // the online search would have answered by now (owner 2026-10-28: the list came back)
check(await page.isHidden('#results'), 'the results list stays closed while the menu is shown');
// a click elsewhere closes the menu; the pin and its chip "Name ›" stay, the chip opens the same menu again
{ const m = await page.locator('#map').boundingBox(); await page.mouse.click(m.x + m.width - 120, m.y + m.height - 160); }
await page.waitForTimeout(300);
check(await page.locator('.holdpopup.pinmenu').count() === 0 && await page.isVisible('.candidate') && await page.isVisible('.pinchip'),
  'a click elsewhere: the menu closes, pin and name chip stay');
check(/Ifinger.*›?/.test(await page.textContent('.pinchip')) && await page.locator('.pinchip button, .pinchip .pinmore').count() === 0, 'the chip is the name only');
await page.click('.pinchip');
await pinMenuOpen();
check((await menuRows())[0] === 'Set as destination', 'the chip opens the pin\'s menu again');
await page.click('.holdpopup.pinmenu button:has-text("Set as destination")');
check(await page.locator('.holdpopup.pinmenu').count() === 0, 'the menu closes after its action');
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
const pts = await page.textContent('#points');
console.log('points:', pts.replace(/\s+/g, ' '));
check(/Vellau/.test(pts) && /Ifinger/.test(pts), 'found places are named stops in the Points list');
check((await page.evaluate(() => location.hash)).length > 10, 'plan with pinned, named places in the link');
check(/Vellau/.test(pts.split(/Ifinger/)[0] ?? ''), 'the second place became the destination (after the start)');
// a start and a destination: a third place is a stop or a new destination (the app's PlaceAddMenu), "Remove pin" before "More"
await pickFirst('Meran');
rows = await menuRows();
console.log('third place:', rows.join(' · '));
check(/^(Add as a stop|Make it the destination)$/.test(rows[0]) && rows.includes('Open in Google Maps') &&
  rows.indexOf('Remove pin') > rows.indexOf('Open in Google Maps') && (!rows.includes('More') || rows.at(-1) === 'More'),
  `two points: "Add as a stop" / "Make it the destination", Google, Remove pin, More last (${rows.join(' · ')})`);
await page.keyboard.press('Escape');
check(await page.locator('.holdpopup.pinmenu').count() === 0 && await page.isVisible('.candidate'), 'Escape closes the menu, the pin stays');
// software GL (SwiftShader) can lag the page by many seconds on a busy machine; the screenshot waits for it within its
// own 30 s – the map's frames drawn so far are finished first (a 1-pixel read waits for them)
await page.evaluate(() => { const gl = window.__map.getCanvas().getContext('webgl2'); gl?.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(4)); });
await page.screenshot({ path: shot });
// B: a map too low for the menu above or under the pin – the same rows as a card at the bottom, name on top; the pin
// stays visible above it
await page.setViewportSize({ width: 1280, height: 430 });
await page.waitForTimeout(500);
await pickFirst('Meran');
const cardRows = await menuRows();
const cardBox = await box('#placecard.pinmenucard'), pinB = await box('.candidate');
console.log('low map:', cardRows.join(' · '), cardBox, pinB);
check(await page.isVisible('#placecard.pinmenucard') && await page.locator('.holdpopup.pinmenu').count() === 0 && cardRows.join('|') === rows.join('|'),
  `low map: the same rows as a card at the bottom (${cardRows.join(' · ')})`);
const overlap = cardBox && pinB && pinB.x < cardBox.x + cardBox.w && pinB.x + pinB.w > cardBox.x && pinB.y < cardBox.y + cardBox.h && pinB.y + pinB.h > cardBox.y;
check(!!cardBox && !!pinB && !overlap && pinB.y >= 0 && pinB.y + pinB.h <= 430, 'the pin stays visible, never under the card');
await page.click('#placecard.pinmenucard button:has-text("Remove pin")');
check(await page.isHidden('#placecard') && await page.locator('.candidate').count() === 0, 'Remove pin: card, pin and chip gone');
await page.setViewportSize({ width: 1280, height: 800 });
await page.waitForTimeout(500);
// Save proposes a name like the app (RouteNamer: the places the stops came from)
await page.click('#menuSavePlan');
// the dialog opens with the date name; the place proposal replaces it when it arrives (5 s at most)
await page.waitForFunction(() => document.getElementById('saveTitle')?.value?.includes('→'), null, { timeout: 30_000 }).catch(() => {});
const proposed = await page.inputValue('#saveTitle');
console.log('proposed name:', proposed);
check(proposed === 'Vellau → Ifinger Hütte', 'Save proposes "Vellau → Ifinger Hütte" (RouteNamer)');
await page.click('#saveCancel');

// A POI's menu → „Als neues Ziel“ / "Make it the destination" (the app 0.17.10, PlaceAddMenu.keepsOldGoal, owner
// 2026-11-14 „beim Planen anhängen, unterwegs ersetzen“): the planner has no tours – it always appends, the old goal
// stays as stop 2 and the place becomes the destination. A POI in view, away from the markers (a marker opens its own menu).
const rowNames = () => page.$$eval('#points li .txt > span:first-child', (s) => s.map((x) => x.textContent));
const before = await rowNames();
const poi = await page.waitForFunction(() => {
  const map = window.__map;
  if (!map.loaded() || map.isMoving()) return null;
  const layers = map.getStyle().layers.filter((l) => l.id.startsWith('poi-') && !l.id.endsWith('-name')).map((l) => l.id);
  const c = map.getCanvas(), w = c.clientWidth, h = c.clientHeight;
  const pins = [...document.querySelectorAll('.pin')].map((e) => e.getBoundingClientRect()), cr = c.getBoundingClientRect();
  const fs = map.queryRenderedFeatures([[w * 0.35, h * 0.1], [w * 0.95, h * 0.9]], { layers }).filter((f) => {
    if (f.geometry.type !== 'Point' || !f.properties?.kind || f.properties.kind === 'bus' || !f.properties.name) return false;
    const p = map.project(f.geometry.coordinates);
    return pins.every((r) => Math.hypot(cr.x + p.x - (r.x + r.width / 2), cr.y + p.y - (r.y + r.height / 2)) > 70);
  });
  if (!fs.length) return null;
  const p = map.project(fs[0].geometry.coordinates);
  return { name: String(fs[0].properties.name), x: p.x, y: p.y };
}, null, { timeout: 60_000, polling: 500 }).then((h) => h.jsonValue());
console.log('POI for a new destination:', poi);
const mapBox = await page.locator('#map canvas').first().boundingBox();
await page.mouse.click(mapBox.x + poi.x, mapBox.y + poi.y);
await page.waitForSelector('.pointmenu', { timeout: 15_000 });
const entry = () => page.locator('.pointmenu button', { hasText: 'Make it the destination' });
if (await entry().count() === 0) await page.locator('.pointmenu button', { hasText: 'More' }).click(); // a clear case hides it behind „⋯ Mehr“
await page.waitForSelector('.pointmenu button:has-text("Make it the destination")', { timeout: 10_000 });
await entry().first().click();
await page.waitForFunction(() => document.querySelectorAll('#points li').length === 3, null, { timeout: 15_000 }).catch(() => {});
const after = await rowNames();
console.log('after "Make it the destination":', before, '→', after);
check(after.length === 3 && after[0] === before[0] && after[1] === before[1] && after[2] !== before[1],
  `"Make it the destination" appends: the old goal is stop 2, the place the destination (${after.join(' → ')})`);

// the density limit for city POIs (app b71b1df9, owner 2026-11-13): in Köln (a dense cell of the store) restaurants /
// cafés / bars / snack bars / shops beyond 24 per map tile carry "dz" = 17 and are drawn only from z17 (the style's
// `zoom >= dz`); huts, toilets, bus stops … never
if (await page.evaluate(() => fetch('/data/cells/f2/10/531/342/current.json').then((r) => r.ok, () => false))) {
  await page.evaluate(() => window.__map.jumpTo({ center: [6.9583, 50.9413], zoom: 15.5 }));
  await page.waitForFunction(() => (window.__map.getSource('pois')?.serialize().data.features ?? []).some((f) => f.geometry.coordinates[0] > 6.9 && f.geometry.coordinates[0] < 7),
    null, { timeout: 60_000 }).catch(() => {});
  await page.waitForFunction(() => window.__map.loaded() && !window.__map.isMoving(), null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(800);
  const LIMITED = ['restaurant', 'cafe', 'bar', 'fast_food', 'shop'];
  const shown = () => page.evaluate((lim) => {
    const m = window.__map, layers = m.getStyle().layers.filter((l) => l.id.startsWith('poi-') && !l.id.endsWith('-name')).map((l) => l.id);
    const f = m.queryRenderedFeatures({ layers }).filter((x) => lim.includes(x.properties.kind));
    return { n: f.length, late: f.filter((x) => Number(x.properties.dz ?? 0) > m.getZoom()).length, dz17: f.filter((x) => x.properties.dz === 17).length };
  }, LIMITED);
  const data = await page.evaluate((lim) => { const f = window.__map.getSource('pois').serialize().data.features;
    return { limited: f.filter((x) => lim.includes(x.properties?.kind)).length, dz: f.filter((x) => x.properties?.dz != null).length,
      otherDz: f.filter((x) => x.properties?.dz != null && !lim.includes(x.properties?.kind)).length }; }, LIMITED);
  let z15 = await shown();
  for (let i = 0; i < 20 && !z15.n; i++) { await page.waitForTimeout(500); z15 = await shown(); } // the symbols placed a moment after the tiles
  await page.evaluate(() => window.__map.jumpTo({ zoom: 17.2 }));
  await page.waitForFunction(() => window.__map.loaded() && !window.__map.isMoving(), null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(800);
  let z17 = await shown();
  for (let i = 0; i < 20 && !z17.dz17; i++) { await page.waitForTimeout(500); z17 = await shown(); }
  console.log('Köln POIs:', data, 'z15.5:', z15, 'z17.2:', z17);
  check(data.dz > 0 && data.otherDz === 0, `Köln: ${data.dz} of ${data.limited} restaurants / cafés / bars / snack bars / shops wait for z17, no other kind`);
  check(z15.n > 0 && z15.late === 0, `z15.5: ${z15.n} of them drawn, none held back for z17 among them`);
  check(z17.dz17 > 0, `z17: the held-back ones are drawn (${z17.dz17})`);
} else console.log('skip: no Köln cell in this store (the density check needs a dense city)');

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
