// Pictures of the context menus (owner 2026-11-26: groups Route · Ort · Entfernen, „Als …“) and of a multi-day tour's
// night markers („Tag N“ pills – one undecided night, one Übernachtung, one hotel) in the real planner, for the owner to
// look at – no checks. deviceScaleFactor 2, desktop and phone width, German.
// Usage: node test/menus-shots.mjs [segmentsDir] [mapStoreDir] [outDir]  → outDir/menus-real/*.png, outDir/multiday-real/*.png
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const out = path.resolve(process.argv[4] ?? 'shots');
const menusDir = path.join(out, 'menus-real'), daysDir = path.join(out, 'multiday-real');
fs.mkdirSync(menusDir, { recursive: true }); fs.mkdirSync(daysDir, { recursive: true });
const gpx = path.join(here, 'fixtures', 'komoot-hike.gpx');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const log = (s) => console.log(s);

async function newPage(viewport, query) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, locale: 'de-DE' });
  await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ contentType: 'application/json', body: '{"type":"FeatureCollection","features":[]}' }));
  const page = await ctx.newPage();
  page.on('pageerror', (e) => log('pageerror ' + e));
  await page.goto(url + 'index.html' + query);
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  await page.evaluate(() => {
    const engine = window.__engine, search = engine.search.bind(engine);
    engine.search = async (text, ...rest) => { const hits = await search(text, ...rest); window.__searched = text; return hits; };
  });
  return { ctx, page };
}
const idle = (page) => page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {});
const settled = (page) => page.waitForFunction(() => document.body.dataset.planning !== '1' && !!document.querySelector('#summary .big'), null, { timeout: 120_000 }).catch(() => {});
async function clickLngLat(page, lon, lat) {
  const p = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect();
    const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await idle(page);
  await page.mouse.click(p[0], p[1]);
}
const shot = async (page, dir, name) => { await page.waitForTimeout(600); await page.screenshot({ path: path.join(dir, name + '.png') }); log('shot ' + path.join(dir, name + '.png')); };
const menuItem = async (page, label) => { await page.waitForSelector('.holdpopup .pointmenu', { timeout: 15_000 });
  await page.locator('.holdpopup .pointmenu button', { hasText: label }).first().click(); };
const linePoint = (page, share) => page.evaluate((s) => { const pts = window.__engine.lastPlan.legs.flatMap((l) => l.points); return pts[Math.floor(pts.length * s)]; }, share);
const smallSheet = async (page, vp) => {
  if (vp.width > 700) return;
  for (let k = 0; k < 3 && await page.$eval('#sheet', (s) => s.dataset.step) !== 'small'; k++) await page.click('#sheetStep');
};

async function menus(vp, tag) {
  const { ctx, page } = await newPage(vp, '?at=11.16,46.67,13&waitMs=0');
  await idle(page);
  // 1 · the empty map: no title, Als Start · Als Ziel (Start: dein Standort) · Rundtour ab hier
  await clickLngLat(page, 11.16, 46.67);
  await page.waitForSelector('.holdpopup .pointmenu');
  await shot(page, menusDir, `${tag}-1-empty-map`);
  await page.keyboard.press('Escape'); await page.mouse.click(5, vp.height / 2).catch(() => {});
  // 2 · a found place's pin on an empty plan
  await page.fill('#q', 'Vellau');
  await page.waitForFunction(() => window.__searched === 'Vellau' && document.querySelector('#results .hit'), null, { timeout: 60_000 }).catch(() => {});
  await page.click('#results .hit');
  await page.waitForSelector('.holdpopup.pinmenu .pointmenu, #placecard.pinmenucard .pointmenu', { timeout: 30_000 });
  await shot(page, menusDir, `${tag}-2-pin-empty-plan`);
  // Als Start, then a goal and a stop on the line
  await page.locator('.holdpopup.pinmenu .pointmenu button, #placecard.pinmenucard .pointmenu button', { hasText: 'Als Start' }).first().click();
  await page.waitForFunction(() => document.querySelectorAll('.pin').length === 1, null, { timeout: 20_000 });
  const s = await page.evaluate(() => window.__state.points[0]);
  await clickLngLat(page, s[1] + 0.03, s[0] + 0.02);
  await page.waitForFunction(() => document.querySelectorAll('.pin').length === 2, null, { timeout: 20_000 });
  await settled(page);
  await page.click('#fitRoute').catch(() => {}); await page.waitForTimeout(1200);
  const mid = await linePoint(page, 0.5);
  await clickLngLat(page, mid[1], mid[0]);
  await page.waitForFunction(() => document.querySelectorAll('.pin').length === 3, null, { timeout: 30_000 }).catch(() => log('no stop on the line'));
  await settled(page);
  await smallSheet(page, vp);
  // 3 · the stop's menu: Als Start (Löscht den Punkt davor) | Google? (unnamed: no) | Löschen
  await page.locator('.pin').nth(1).click();
  await page.waitForSelector('.holdpopup .pointmenu');
  await shot(page, menusDir, `${tag}-3-stop-in-plan`);
  await page.keyboard.press('Escape');
  // 4 · the found place's pin with a plan: Als Stopp / Als Ziel | Merken · Google | Nadel entfernen
  await page.fill('#q', ''); await page.fill('#q', 'Vellau');
  await page.waitForFunction(() => window.__searched === 'Vellau' && document.querySelector('#results .hit'), null, { timeout: 60_000 }).catch(() => {});
  await page.click('#results .hit');
  await page.waitForSelector('.holdpopup.pinmenu .pointmenu, #placecard.pinmenucard .pointmenu', { timeout: 30_000 });
  await shot(page, menusDir, `${tag}-4-pin-with-plan`);
  await ctx.close();
}

async function multiday(vp, tag) {
  const { ctx, page } = await newPage(vp, '?waitMs=0');
  await page.click(vp.width <= 700 ? '#menuBtn' : '#menuMultiDay');
  if (vp.width <= 700) await page.click('#menuMultiDay');
  await page.waitForSelector('#tourGuide:not([hidden])');
  const [fc] = await Promise.all([page.waitForEvent('filechooser'), page.click('#tgGpx')]);
  await fc.setFiles(gpx);
  await page.waitForSelector('#planFromTrack');
  await page.click('#planFromTrack');
  await settled(page);
  await page.waitForFunction(() => !document.getElementById('tgNext')?.disabled, null, { timeout: 60_000 });
  await page.click('#tgNext');
  await page.waitForSelector('dialog.daysbox[open]');
  await page.fill('#dbHours', '');
  await page.waitForFunction(() => document.getElementById('dbPer')?.textContent?.includes('≈'), null, { timeout: 20_000 }).catch(() => {});
  for (let k = 0; k < 6 && !/^4\b/.test(await page.textContent('#dbN')); k++) await page.click(/^[123]\b/.test(await page.textContent('#dbN')) ? '#dbPlus' : '#dbMinus');
  await page.waitForTimeout(500);
  await page.click('#dbDone');
  await page.waitForFunction(() => window.__days.lastView?.days === 4, null, { timeout: 60_000 });
  await smallSheet(page, vp);
  await page.click('#fitRoute'); await page.waitForTimeout(1500);
  // 5 · a grey night's menu: Als Übernachtung · Hotel hinterlegen
  await idle(page);
  await page.locator('.dayend.proposal').first().click();
  await page.waitForSelector('.holdpopup .pointmenu');
  await shot(page, menusDir, `${tag}-5-grey-night`);
  await page.keyboard.press('Escape');
  // one Übernachtung (the first grey one, R7: a click on its menu row) and one hotel (the second, Hotel hinterlegen)
  await page.locator('.dayend.proposal').first().click();
  await menuItem(page, 'Als Übernachtung');
  await page.waitForFunction(() => window.__days.lastView?.ends.some((e) => e.kind === 'OVERNIGHT'), null, { timeout: 60_000 });
  await settled(page);
  await idle(page);
  await page.locator('.dayend.proposal').first().click();
  await menuItem(page, 'Hotel hinterlegen');
  await page.waitForSelector('dialog.hoteldlg[open]');
  const e = await page.evaluate(() => window.__days.lastView.ends.find((x) => x.kind === 'PROPOSAL'));
  await page.fill('#hotelQ', `Gasthof Sonne\nhttps://www.google.com/maps/place/Gasthof+Sonne/@${(e.lat + 0.001).toFixed(5)},${(e.lon + 0.001).toFixed(5)},17z`);
  await page.waitForSelector('#hotelRes button', { timeout: 10_000 });
  await page.click('#hotelRes button');
  await page.waitForFunction(() => window.__days.lastView?.ends.some((x) => x.kind === 'HOTEL'), null, { timeout: 60_000 });
  await settled(page);
  await page.click('#fitRoute'); await page.waitForTimeout(1500); await idle(page);
  log(`${tag}: ends ${JSON.stringify(await page.evaluate(() => window.__days.lastView.ends.map((x) => x.kind)))}`);
  await shot(page, daysDir, `${tag}-tour`);
  // 6 · the blue and the green night's menus
  await page.locator('.pin.night .daypill.blue').first().click();
  await page.waitForSelector('.holdpopup .pointmenu');
  await shot(page, menusDir, `${tag}-6-blue-night`);
  await page.keyboard.press('Escape');
  await page.locator('.pin.night .daypill.green').first().click();
  await page.waitForSelector('.holdpopup .pointmenu');
  await shot(page, menusDir, `${tag}-7-hotel-night`);
  await ctx.close();
}

try {
  for (const [vp, tag] of [[{ width: 1440, height: 900 }, 'desktop'], [{ width: 390, height: 844 }, 'phone']]) {
    try { await menus(vp, tag); } catch (e) { log(`${tag} menus failed: ${e}`); }
    try { await multiday(vp, tag); } catch (e) { log(`${tag} multiday failed: ${e}`); }
  }
} finally {
  await browser.close();
  server.close();
}
