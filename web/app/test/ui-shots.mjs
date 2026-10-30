// Screenshots of the page layout (header – not paired and paired –, toolbar, the sheet's three steps, place card, activity drop-down, Map content,
// My routes, the Phone window in its three states + the unpair question, the ☰ dot on phones) at desktop (1440×900) and phone (390×844) width, for looking at – nothing is checked. Needs the cell store.
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
  // the phone (owner 2026-10-30): header entry with no dot / green / amber dot, the Phone window in each state, the
  // unpair question; on phones the ☰ button with the dot. For the look only: the page's own sync is stopped and the
  // status set by hand (the window's "sync now" would reset it from the empty store)
  await page.waitForSelector('#syncBadge[aria-label]', { state: 'attached', timeout: 60_000 });
  await page.evaluate(() => { clearInterval(window.__sync.timer); window.__sync.syncNow = async () => {}; });
  await page.waitForTimeout(1500); // a sync already started ends
  const fake = (st) => page.evaluate((st) => { const s = window.__sync, now = Date.now();
    s.status = st === 'none' ? { ...s.status, paired: false, devices: [], phoneSeen: null, lastSync: null, message: '' }
      : { ...s.status, paired: true, devices: [{ name: 'Pixel 8', updatedAt: now }], lastSync: now, message: '', pending: 0,
        phoneSeen: st === 'fresh' ? now - 2 * 60_000 : now - 3 * 86_400_000 };
    s.changed(); }, st);
  const clip = { x: 0, y: 0, width: vp.width, height: 56 };
  for (const st of ['none', 'fresh', 'stale']) {
    await fake(st);
    await page.waitForTimeout(150);
    await page.screenshot({ path: path.join(out, `${name}-header-${st}.png`), clip });
    if (name === 'phone' && st !== 'stale') continue;
    if (name === 'phone') await page.click('#menuBtn');
    await page.click('#syncBadge');
    await page.waitForSelector('dialog.phone[open]');
    await page.screenshot({ path: path.join(out, `${name}-phone-${st}.png`) });
    if (st === 'stale' && name === 'desktop') {
      await page.click('dialog.phone #unpair');
      await page.waitForTimeout(200);
      await page.screenshot({ path: path.join(out, `${name}-phone-unpair.png`) });
      await page.keyboard.press('Escape');
    }
    await page.click('#closePhone');
  }
  await fake('none');
  // the sheet's three steps (sheetstep.ts): the chevron goes one step more until Points, then back to small
  for (let i = 0; i < 3; i++) {
    await page.click('#sheetStep');
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(out, `${name}-step-${await page.getAttribute('#sheet', 'data-step')}.png`) });
  }
  if (name === 'phone') await page.click('#menuBtn');
  await page.click('#menuPlans');
  await page.waitForSelector('dialog.plans[open] #planList');
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
  await page.waitForSelector('#placecard:not([hidden]) #cclose');
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
