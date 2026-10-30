// Following my position (locate button) ends on any camera move the page makes for something else – the app's rule
// (CLAUDE.md "Map camera"). Owner 2026-10-30: after picking a search result the next GPS fix pulled the map back.
// Usage: node test/follow-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
// a fake GPS the test drives: window.__fix(lat, lon)
await ctx.addInitScript(() => {
  let cb = null;
  navigator.geolocation.watchPosition = (ok) => { cb = ok; return 1; };
  navigator.geolocation.clearWatch = () => {};
  window.__fix = (lat, lon) => cb?.({ coords: { latitude: lat, longitude: lon, accuracy: 5 }, timestamp: Date.now() });
});
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const centre = () => page.evaluate(() => { const c = window.__map.getCenter(); return [c.lat, c.lng]; });
const near = (a, b) => Math.abs(a[0] - b[0]) < 1e-3 && Math.abs(a[1] - b[1]) < 1e-3;
const settle = () => page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 10_000 }).then(() => page.waitForTimeout(200));

await page.goto(url + 'index.html');
await page.waitForFunction(() => window.__map && document.querySelector('#locate'), null, { timeout: 60_000 }).catch(() => {});
const locate = (await page.$('#locate')) ? '#locate' : 'button[aria-label*="osition"], button[aria-label*="tandort"]';
await page.click(locate);
await page.evaluate(() => window.__fix(46.6700, 11.1600)); await settle();
check(near(await centre(), [46.67, 11.16]), 'locate: the map goes to the position');
await page.evaluate(() => window.__fix(46.6750, 11.1650)); await settle();
check(near(await centre(), [46.675, 11.165]), 'following: a new fix moves the map');
// a move the page makes for something else (here: what a search pick does)
await page.evaluate(() => window.__map.flyTo({ center: [11.20, 46.70], zoom: 14, duration: 0 })); await settle();
await page.evaluate(() => window.__fix(46.6760, 11.1660)); await settle();
check(near(await centre(), [46.70, 11.20]), 'after a search-like fly, the next fix does not pull the map back');
// compass north keeps following
await page.evaluate(() => window.__map.rotateTo(30, { duration: 0 })); await settle(); // turned map (by hand, before)
await page.click(locate); await page.evaluate(() => window.__fix(46.6770, 11.1670)); await settle();
await page.click('#compass').catch(() => {}); await settle();
await page.evaluate(() => window.__fix(46.6780, 11.1680)); await settle();
check(near(await centre(), [46.678, 11.168]), 'compass "north up" keeps following');

await browser.close(); server.close();
process.exit(fails.length ? 1 : 0);
