// A point's menu is always whole in the free map (owner 2026-11-26: on a wide screen a stop's menu opened just above the
// big profile and „Löschen“ lay under it): a route point right above the profile, one right under the top bar and one at
// the map's left edge – each menu inside the map between the top bar and the profile, nothing of it covered.
// Usage: node test/menufit-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

// Meraner Höhenweg, Hike T3, 12 points (legcache-smoke)
const CODE = 'AQUDDPiLugSE8YcBogbZH94K8yedLO898Q7VHK0ZuUWwL99M6GK6N7AHsFyWPLJgDOyUAauSAdlUAAABEU1lcmFuZXIgSMO2aGVud2VnAhEAR2FzdGhvZiBIb2NobXV0aAIKAUxlaXRlcmFsbQINAkhvY2hnYW5naGF1cwILA0dpZ2dlbGJlcmcCCgRIb2NoZm9yY2gCCwVVbnRlcnN0ZWxsAg4GS2F0aGFyaW5hYmVyZwIHB0Vpc2hvZgIRCFN0ZXR0aW5lciBIw7x0dGUCCQlQZmVsZGVycwIGClVsZmFzAhELR2FzdGhvZiBIb2NobXV0aA';
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
if (!fs.existsSync(path.join(segments, 'E10_N45.rd5'))) { console.log(`skip: no E10_N45.rd5 in ${segments}`); process.exit(0); }
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'de-DE' });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
await page.goto(url + 'index.html?lang=de&waitMs=0#p=' + CODE);
await page.waitForFunction(() => window.__engine?.lastPlanStats && document.querySelector('#summary .big') && document.body.dataset.planning !== '1', null, { timeout: 180_000 });
await page.waitForTimeout(1500);
check(await page.locator('#bigProfile').isVisible(), 'the big profile is open (wide screen)');

/** Moves the map so plan point [i] sits at screen [x, y] (page pixels), clicks it and measures its menu. */
async function menuAt(i, x, y) {
  await page.keyboard.press('Escape');
  await page.evaluate(([i, x, y]) => {
    const m = window.__map, r = m.getCanvas().getBoundingClientRect(), pin = document.querySelectorAll('.pin')[i].getBoundingClientRect();
    m.panBy([pin.left + pin.width / 2 - x, pin.top + pin.height / 2 - y], { duration: 0 });
    void r;
  }, [i, x, y]);
  await page.waitForTimeout(400);
  await page.locator('.pin').nth(i).click();
  await page.waitForSelector('.holdpopup .pointmenu', { timeout: 10_000 });
  await page.waitForTimeout(500); // a pan to make room
  return page.evaluate(() => {
    const box = (s) => { const b = document.querySelector(s)?.getBoundingClientRect(); return b && b.height > 0 ? { top: b.top, bottom: b.bottom, left: b.left, right: b.right } : null; };
    return { menu: box('.holdpopup .maplibregl-popup-content'), profile: box('#bigProfile'), bar: box('#topbar'), stage: box('#stage'), sheet: box('#sheet') };
  });
}
const inside = (b, what) => {
  const m = b.menu, lo = b.profile ? b.profile.top : b.stage.bottom, hi = b.bar ? b.bar.bottom : b.stage.top;
  const sheetRight = b.sheet ? b.sheet.right : b.stage.left;
  check(!!m && m.bottom <= lo + 1 && m.top >= hi - 1 && m.left >= Math.max(b.stage.left, 0) - 1 && m.right <= b.stage.right + 1,
    `${what}: the menu ${m && Math.round(m.top)}–${m && Math.round(m.bottom)} lies between the top bar (${Math.round(hi)}) and the profile (${Math.round(lo)})` +
    (m && m.left < sheetRight ? ' (beside the sheet edge)' : ''));
};
const geo = await page.evaluate(() => ({ prof: document.getElementById('bigProfile').getBoundingClientRect().top,
  bar: document.getElementById('topbar').getBoundingClientRect().bottom, sheet: document.getElementById('sheet').getBoundingClientRect().right }));
const midX = Math.round((geo.sheet + 1440) / 2);
inside(await menuAt(5, midX, geo.prof - 40), 'a stop just above the profile');
for (const d of [80, 120, 160, 200]) inside(await menuAt(5, midX, geo.prof - d), `a stop ${d} px above the profile`);
inside(await menuAt(5, midX, geo.bar + 30), 'a stop just under the top bar');
inside(await menuAt(5, midX, Math.round((geo.bar + geo.prof) / 2)), 'a stop in the middle');

// a low window (laptop, owner's screenshot): neither below nor above the point has room for the whole menu – the map
// moves so it fits
await page.setViewportSize({ width: 1440, height: 700 });
await page.waitForTimeout(800);
const low = await page.evaluate(() => ({ prof: document.getElementById('bigProfile').getBoundingClientRect().top,
  bar: document.getElementById('topbar').getBoundingClientRect().bottom }));
inside(await menuAt(5, midX, Math.round((low.bar + low.prof) / 2)), `a low window (${Math.round(low.prof - low.bar)} px of map): a stop in the middle`);

await browser.close(); server.close();
if (errors.length) { console.log('errors:', errors); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
