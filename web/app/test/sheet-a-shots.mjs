// Shots of the plan sheet A (owner 2026-11-07): wide (points first, facts, profile along the bottom) and phone (two steps).
//   node test/sheet-a-shots.mjs <segments> <cellStore> <outDir> [lang]
import { chromium } from 'playwright';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const [segments, dataDir, out] = process.argv.slice(2, 5).map((p) => path.resolve(p));
const lang = process.argv[5] ?? 'de';
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau, "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch();
const settle = (page) => page.waitForFunction(() => !window.__map.isMoving() && window.__map.areTilesLoaded(), null, { timeout: 60_000 }).catch(() => {});
for (const [name, vp, phone] of [['breit', { width: 1440, height: 900 }, false], ['handy', { width: 390, height: 844 }, true]]) {
  const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: 2, isMobile: phone, hasTouch: phone, locale: lang });
  const page = await ctx.newPage();
  await page.goto(url + `index.html?lang=${lang}#p=` + CODE);
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  await page.click('#appHintLine .x').catch(() => {});
  await page.waitForTimeout(1500); await settle(page);
  await page.waitForFunction(() => !document.querySelector('.mapnote:not([hidden])'), null, { timeout: 30_000 }).catch(() => {}); await settle(page);
  await page.screenshot({ path: path.join(out, `plankarte-${name}.png`) });
  if (phone) {
    await page.click('#sheetStep'); await page.waitForTimeout(600); await settle(page);
    await page.screenshot({ path: path.join(out, `plankarte-${name}-punkte.png`) });
  }
  await ctx.close();
}
await browser.close();
server.close();
