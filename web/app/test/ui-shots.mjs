// Screenshots of the page layout (header with Save and the Phone button in their states – web-sync.md §14.6 –, toolbar, the sheet's three steps,
// place card, activity drop-down, Map content, My routes, the Phone window in its three states) at desktop (1440×900) and phone (390×844) width,
// for looking at – nothing is checked. Needs the cell store. [lang]: the browser's language (en, de; default en).
// Usage: node test/ui-shots.mjs [segmentsDir] [mapStoreDir] [outDir] [lang]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const out = path.resolve(process.argv[4] ?? path.join(here, '../../engine/build/shots'));
const locale = process.argv[5] === 'de' ? 'de-DE' : 'en-GB';
fs.mkdirSync(out, { recursive: true });
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau, "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
for (const [name, vp] of [['desktop', { width: 1440, height: 900 }], ['phone', { width: 390, height: 844 }]]) {
  const ctx = await browser.newContext({ viewport: vp, isMobile: name === 'phone', hasTouch: name === 'phone', locale });
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort());
  const page = await ctx.newPage();
  await page.goto(url + 'index.html#p=' + CODE);
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(out, `${name}-plan.png`) });
  // Save and the Phone button (web-sync.md §14.6): not paired – the shown route new (Save dark), saved (light ✓);
  // paired – something waiting (dark), all on the phone (light ✓), the phone away for days (amber dot); the Phone
  // window in each state; My routes paired with 🖥 / 📱 / both and a waiting (bold) row. For the look only: the page's
  // own sync is stopped and the status (and My routes' list) set by hand
  await page.waitForSelector('#syncBadge[data-ready]', { state: 'attached', timeout: 60_000 });
  await page.evaluate(() => { clearInterval(window.__sync.timer); window.__sync.syncNow = async () => {}; window.__sync.sendAll = async () => {}; });
  await page.waitForTimeout(1500); // a sync already started ends
  const fake = (st, pending = 0) => page.evaluate(([st, pending]) => { const s = window.__sync, now = Date.now();
    s.status = st === 'none' ? { ...s.status, paired: false, devices: [], phone: null, phoneSeen: null, lastSync: null, message: '', pending: 0, closed: [] }
      : { ...s.status, paired: true, devices: [{ name: 'Pixel 8', updatedAt: now }], phone: 'Pixel 8', lastSync: now, message: '', pending,
        closed: [{ key: 'x', name: 'Pixel 6a' }], phoneSeen: st === 'fresh' ? now - 2 * 60_000 : now - 3 * 86_400_000 };
    s.changed(); }, [st, pending]);
  const clip = { x: 0, y: 0, width: vp.width, height: 56 };
  const header = async (file) => { await page.waitForTimeout(200); await page.screenshot({ path: path.join(out, `${name}-header-${file}.png`), clip }); };
  await fake('none');
  await header('none-dark'); // the shared route is new here: Save dark
  await page.click('#menuSavePlan'); // the name field under Save (rule 5, "as in Word")
  await page.waitForSelector('.savepop #savePopName');
  await page.waitForTimeout(800); // the name proposal
  await page.screenshot({ path: path.join(out, `${name}-savefield.png`) });
  await page.press('#savePopName', 'Enter');
  await page.waitForFunction(() => document.getElementById('menuSavePlan')?.dataset.state === 'done', null, { timeout: 10_000 }).catch(() => {});
  await header('none-done');
  await fake('fresh', 2);
  await header('fresh-dark');
  await fake('fresh', 0);
  await header('fresh-done');
  await fake('stale', 0);
  await header('stale');
  for (const st of ['none', 'fresh', 'stale']) {
    if (name === 'phone' && st !== 'stale') continue;
    await fake(st);
    await page.click('#syncBadge');
    await page.waitForSelector('dialog.phone[open]');
    await page.screenshot({ path: path.join(out, `${name}-phone-${st}.png`) });
    if (st === 'fresh') {
      await page.click('#howToggle');
      await page.screenshot({ path: path.join(out, `${name}-phone-${st}-how.png`) });
    }
    await page.click('#closePhone');
  }
  // My routes, paired: the saved route (open, light green) plus rows from the phone, waiting for it, and on both
  await fake('fresh', 2);
  await page.evaluate(() => {
    const s = window.__sync, real = s.list.bind(s), day = 86_400_000, now = Date.now();
    const row = (id, title, kind, mine, dirty, onPhone, ago) => ({ id, kind, title, plan: '', track: '', updatedAt: now - ago * day, device: '', mine, dirty, onPhone });
    s.list = async (f) => [...(await real(f)).map((p) => ({ ...p, onPhone: true, dirty: false })),
      row('a', 'Haus Witt → Dorothea-Erxleben-Straße', 'plan', true, true, false, 0.2),
      row('b', 'Ifinger Hütte', 'plan', false, false, true, 1),
      row('c', 'Meran 2000 & Ifinger Hütte', 'track', true, false, true, 3),
      row('d', 'Algund → Vellau (Kopie)', 'plan', true, true, true, 6)];
  });
  if (name === 'phone') await page.click('#menuBtn');
  await page.click('#menuPlans');
  await page.waitForSelector('dialog.plans[open] #planList li[data-id]');
  await page.screenshot({ path: path.join(out, `${name}-myroutes.png`) });
  await page.click('#planList li[data-id="b"] .more');
  await page.screenshot({ path: path.join(out, `${name}-myroutes-menu.png`) });
  await page.click('#closePlans');
  await page.evaluate(() => { delete window.__sync.list; });
  await fake('none');
  // the sheet's three steps (sheetstep.ts): the chevron goes one step more until Points, then back to small
  for (let i = 0; i < 3; i++) {
    await page.click('#sheetStep');
    await page.waitForTimeout(400);
    await page.screenshot({ path: path.join(out, `${name}-step-${await page.getAttribute('#sheet', 'data-step')}.png`) });
  }
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
