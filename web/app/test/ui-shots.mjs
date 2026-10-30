// Screenshots of the page layout (header – not paired and paired –, toolbar, the sheet's three steps, place card, activity drop-down, Map content,
// My routes) at desktop (1440×900) and phone (390×844) width, for looking at – nothing is checked. Needs the cell store.
// Usage: node test/ui-shots.mjs [segmentsDir] [mapStoreDir] [outDir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const out = path.resolve(process.argv[4] ?? path.join(here, '../../engine/build/shots'));
fs.mkdirSync(out, { recursive: true });
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau, "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
for (const [name, vp] of [['desktop', { width: 1440, height: 900 }], ['phone', { width: 390, height: 844 }]]) {
  const ctx = await browser.newContext({ viewport: vp, isMobile: name === 'phone', hasTouch: name === 'phone' });
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
  const page = await ctx.newPage();
  await page.goto(url + 'index.html#p=' + CODE);
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(out, `${name}-plan.png`) });
  // the header's phone entry (owner 2026-10-30): "Pair phone" while not paired, phone + green dot + "Paired" while paired
  if (name === 'desktop') {
    await page.waitForSelector('#syncBadge[aria-label]', { state: 'attached', timeout: 60_000 });
    const clip = { x: 0, y: 0, width: vp.width, height: 56 };
    await page.screenshot({ path: path.join(out, `${name}-header-unpaired.png`), clip });
    await page.evaluate(() => { const s = window.__sync; s.status = { ...s.status, paired: true, lastSync: Date.now() }; s.changed(); }); // the look only
    await page.screenshot({ path: path.join(out, `${name}-header-paired.png`), clip });
    await page.evaluate(() => { const s = window.__sync; s.status = { ...s.status, paired: false, lastSync: null }; s.changed(); });
  }
  // the sheet's three steps (sheetstep.ts): the chevron goes one step more until Points, then back to small
  for (let i = 0; i < 3; i++) {
    await page.click('#sheetStep');
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(out, `${name}-step-${await page.getAttribute('#sheet', 'data-step')}.png`) });
  }
  if (name === 'phone') await page.click('#menuBtn');
  await page.click('#menuPlans');
  await page.waitForSelector('dialog.plans[open] .syncsec');
  await page.screenshot({ path: path.join(out, `${name}-myroutes.png`) });
  await page.click('#closePlans');
  await page.click('#activityChip');
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(out, `${name}-activity.png`) });
  await page.keyboard.press('Escape');
  await page.click('#mapContent');
  await page.waitForSelector('dialog.mapcontent[open] .kind');
  await page.screenshot({ path: path.join(out, `${name}-mapcontent.png`) });
  await page.click('#mcClose');
  await page.fill('#q', 'Ifinger');
  await page.waitForSelector('#results .hit', { timeout: 60_000 });
  await page.click('#results .hit');
  await page.waitForSelector('#placecard:not([hidden]) #cdest');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(out, `${name}-card.png`) });
  if (name === 'phone') {
    await page.click('#cclose');
    await page.click('#menuBtn');
    await page.screenshot({ path: path.join(out, `${name}-menu.png`) });
  }
  await ctx.close();
}
await browser.close(); server.close();
console.log('screenshots in', out);
