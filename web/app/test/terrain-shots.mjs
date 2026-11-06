// Pictures of the terrain view for the owner (owner 2026-11-05, terrainview.ts): the Waalweg hike Meran → Vellau with the
// big elevation profile and its cursor, then 3D. Needs the rd5 and a map store with the Meran cells (like the R2 bucket).
// Usage: node test/terrain-shots.mjs <segmentsDir> <mapStoreDir> <outDir> [de|en]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const [segments, dataDir, out] = process.argv.slice(2, 5).map((p) => path.resolve(p));
const lang = process.argv[5] ?? 'de';
fs.mkdirSync(out, { recursive: true });
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau, "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1400, height: 860 }, deviceScaleFactor: 2, locale: lang });
page.on('pageerror', (e) => console.log('pageerror', String(e)));
await page.goto(url + `index.html?lang=${lang}#p=` + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
// the picture only once the map stands still, every tile is in and the "map is loading" line is gone
const quiet = async () => {
  await page.waitForFunction(() => !window.__map.isMoving() && window.__map.areTilesLoaded()
    && [...document.querySelectorAll('#mapnotes, #mapnotes *')].every((e) => e.hidden || !/lade|load/i.test(e.textContent ?? '')),
  null, { timeout: 90_000 }).catch(() => console.log('WARN map not quiet after 90 s'));
  await new Promise((r) => { page.evaluate(() => new Promise((ok) => window.__map.once('idle', ok))).then(r); setTimeout(r, 5000); });
  await page.waitForTimeout(1500);
};
await quiet();
await page.click('#profileBig');
await quiet();
// the cursor at about 60 % of the big profile
const box = await page.locator('#bigProfile .bpplot svg').boundingBox();
await page.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.5);
await page.waitForTimeout(800);
await page.screenshot({ path: path.join(out, `terrain-profil-${lang}.png`) });
await page.mouse.move(5, 300);
await page.click('#terrain3d'); // the button's own camera: tilted, framed on the route
await page.waitForTimeout(1500);
await quiet();
await page.waitForTimeout(3000);
await quiet();
console.log('3D camera', JSON.stringify(await page.evaluate(() => ({ pitch: window.__map.getPitch(), zoom: window.__map.getZoom(), bearing: window.__map.getBearing(),
  terrain: window.__map.getTerrain(), h: window.__map.queryTerrainElevation(window.__map.getCenter()) }))));
await page.mouse.move(box.x + box.width * 0.45, box.y + box.height * 0.5);
await page.waitForTimeout(800);
await page.screenshot({ path: path.join(out, `terrain-3d-${lang}.png`), timeout: 240_000 });
await browser.close(); server.close();
console.log('pictures in', out);
