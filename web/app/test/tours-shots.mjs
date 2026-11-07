// Shots of Meine Touren like the app (owner 2026-11-07): a saved route, a plan under Zuletzt and the plan on the map
// („In Bearbeitung“), each with its route preview; the large overview a row tap opens; wide and phone.
//   node test/tours-shots.mjs <segments> <cellStore> <outDir> [lang] [liveUrl]
// With [liveUrl] (e.g. https://plan.onpath.earth/) the same steps run against that page (the "before" shots).
import { chromium } from 'playwright';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const [segments, dataDir, out] = process.argv.slice(2, 5).map((p) => path.resolve(p));
const lang = process.argv[5] ?? 'de';
const live = process.argv[6];
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau, "Waalweg"
const local = live ? null : await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const url = live ?? local.url;
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const settle = (page) => page.waitForFunction(() => !window.__map.isMoving() && window.__map.areTilesLoaded(), null, { timeout: 60_000 }).catch(() => {});
const routed = (page) => page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
const clickAt = async (page, lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect(); const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
const tag = live ? 'vorher' : 'nachher';
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, deviceScaleFactor: 2, locale: lang });
const page = await ctx.newPage();
await page.goto(url + `index.html?lang=${lang}#p=` + CODE);
await routed(page);
await page.click('#appHintLine .x').catch(() => {});
// 1. save the Waalweg
await page.click('#menuSavePlan');
await page.waitForSelector('dialog.savename #saveOk', { timeout: 10_000 }).then(() => page.click('dialog.savename #saveOk')).catch(() => {});
await page.waitForTimeout(1500);
const twoPoints = async (a, b) => {
  await page.evaluate(() => window.__map.jumpTo({ center: [11.17, 46.675], zoom: 14, padding: { top: 0, bottom: 0, left: 0, right: 0 } }));
  await page.waitForTimeout(300);
  await clickAt(page, ...a); await page.waitForSelector('.pointmenu'); await page.click('.pointmenu button:first-of-type'); await page.waitForTimeout(400);
  await clickAt(page, ...b); await routed(page); await page.waitForTimeout(800);
};
// 2. a second plan, kept under Zuletzt by „+ Neu“; 3. the plan on the map (not saved)
await page.click('#newPlan'); await page.waitForTimeout(500);
await twoPoints([11.160, 46.672], [11.178, 46.680]);
await page.click('#newPlan'); await page.waitForTimeout(800);
await twoPoints([11.165, 46.668], [11.172, 46.684]);
for (const [name, vp] of [['breit', { width: 1280, height: 860 }], ['handy', { width: 390, height: 844 }]]) {
  await page.setViewportSize(vp); await page.waitForTimeout(800);
  if (name === 'handy') { await page.click('#menuBtn'); await page.waitForTimeout(300); }
  await page.click('#menuPlans');
  await page.waitForSelector('dialog.plans #planList li', { timeout: 10_000 });
  await page.waitForTimeout(600);
  await page.screenshot({ path: path.join(out, `touren-${name}-${tag}.png`) });
  const saved = page.locator('dialog.plans li.tourrow.saved .open').first();
  if (await saved.count()) { // the overview and ⋮ – only the new page has them
    await saved.click();
    await page.waitForSelector('dialog.overview .ovpic svg, dialog.overview .ovpic p', { timeout: 10_000 });
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(out, `uebersicht-${name}-${tag}.png`) });
    await page.click('dialog.overview .ovcancel');
    await page.waitForSelector('dialog.plans', { timeout: 10_000 });
    await page.waitForTimeout(400);
    await page.locator('dialog.plans li.tourrow.saved').first().evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(300);
    await page.locator('dialog.plans li.tourrow.saved .more').first().click(); await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(out, `touren-${name}-menue-${tag}.png`) });
  }
  await page.keyboard.press('Escape'); await page.keyboard.press('Escape'); await page.waitForTimeout(300);
}
await ctx.close();
await browser.close();
local?.server.close();
