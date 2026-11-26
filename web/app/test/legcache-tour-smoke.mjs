// A multi-day tour made from a GPX track, opened again (owner 2026-11-26: „man macht den Browser wieder auf, dann lädt er die
// alte Tour – München–Venedig – und routet ganz von vorne“): the plan from the track is kept in the browser; the page
// reloaded and a fresh tab in the same browser (no address – the draft comes back) must read its legs from the browser
// and route none. Needs only the rd5 (E10_N45). Usage: node test/legcache-tour-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
if (!fs.existsSync(path.join(segments, 'E10_N45.rd5'))) { console.log(`skip: no E10_N45.rd5 in ${segments}`); process.exit(0); }
const gpx = path.join(here, 'fixtures', 'komoot-hike.gpx');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const watch = (page) => {
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|status of 404|\[Problems\] (INFO|WARN|TRACE)/.test(m.text())) errors.push(m.text()); });
};
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'de-DE' });
await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ contentType: 'application/json', body: '{"type":"FeatureCollection","features":[]}' }));
const settled = (page) => page.waitForFunction(() => document.body.dataset.planning !== '1' && !!document.querySelector('#summary .big'), null, { timeout: 180_000 });
/** The last plan's leg numbers once the page routed it (a fresh plan request after [before]). */
async function legsAfter(page, before) {
  await page.waitForFunction((b) => window.__engine?.lastPlanStats?.legs && window.__engine.lastPlanStats !== b, before, { timeout: 180_000 });
  await settled(page);
  await page.waitForTimeout(1500);
  return page.evaluate(() => ({ legs: window.__engine.lastPlanStats.legs, pins: document.querySelectorAll('.pin').length, days: window.__days?.lastView?.days ?? 0 }));
}

const page = await ctx.newPage(); watch(page);
await page.goto(url + 'index.html?lang=de&waitMs=0');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
// a tour from a track: Mehrtagestour planen → GPX-Track laden → Route daraus planen → Weiter → 3 days → Fertig
await page.click('#menuMultiDay');
await page.waitForSelector('#tourGuide:not([hidden])');
const [fc] = await Promise.all([page.waitForEvent('filechooser'), page.click('#tgGpx')]);
await fc.setFiles(gpx);
await page.waitForSelector('#planFromTrack');
await page.click('#planFromTrack');
await settled(page);
await page.waitForFunction(() => !document.getElementById('tgNext')?.disabled, null, { timeout: 120_000 });
await page.click('#tgNext');
await page.waitForSelector('dialog.daysbox[open]');
for (let k = 0; k < 4 && !/^3\b/.test(await page.textContent('#dbN')); k++) await page.click(/^[12]\b/.test(await page.textContent('#dbN')) ? '#dbPlus' : '#dbMinus');
await page.click('#dbDone');
await page.waitForFunction(() => window.__days?.lastView?.days === 3, null, { timeout: 60_000 });
await settled(page);
await page.waitForTimeout(2500); // the plan into the address and the draft, its legs into IndexedDB
const made = await page.evaluate(() => ({ pins: document.querySelectorAll('.pin').length, hash: location.hash.length }));
console.log(`the tour: ${made.pins} points, 3 days, address ${made.hash} chars`);
if (process.env.LEGDEBUG) console.log('stored legs:', await page.evaluate(() => new Promise((res) => { const r = indexedDB.open('onpath-legs');
  r.onsuccess = () => { const q = r.result.transaction('legs').objectStore('legs').getAllKeys(); q.onsuccess = () => res(q.result.map((k) => String(k).slice(0, 120))); }; r.onerror = () => res('no db'); })));

// 1. reload (the address keeps the plan)
await page.reload();
const reload = await legsAfter(page, null);
const n = reload.legs.routed + reload.legs.cached + (reload.legs.stored ?? 0);
check(reload.legs.routed === 0, `reload: no leg routed (${JSON.stringify(reload.legs)}, ${reload.pins} points, ${reload.days} days)`);

// 2. the browser opened again: a new tab without an address – the page brings the tour back from the browser
await page.close();
const tab = await ctx.newPage(); watch(tab);
await tab.goto(url + 'index.html?lang=de&waitMs=0');
const reopened = await legsAfter(tab, null);
check(reopened.pins === made.pins, `reopened: the tour is back (${reopened.pins} of ${made.pins} points, ${reopened.days} days)`);
check(reopened.legs.routed === 0, `reopened: no leg routed (${JSON.stringify(reopened.legs)} of ${n})`);

await browser.close(); server.close();
if (errors.length) { console.log('errors:', errors); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
