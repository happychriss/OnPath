// The map look per activity (the app's map/ActivityLayers, docs/handoff-web-activity-layers.md): the shipped style has
// the Hike look; the page switches it with the engine call activityLayers on load and on every activity / T change –
// the same layers as the phone. Hike T2 → Bike (bike map, loose surfaces, cycle band, no hiking green ways) → Hike T4
// (the hiking band's filter at T4), then a reload with the remembered Bike gets the bike look straight away.
// Usage: node test/activity-look-smoke.mjs [segmentsDir] [mapStoreDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] ? path.resolve(process.argv[3]) : null;
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

/** Layer id → visible ('none' = hidden; a missing layer = null). */
const vis = (ids) => page.evaluate((list) => Object.fromEntries(list.map((id) => {
  const m = window.__map;
  return [id, m.getLayer(id) ? m.getLayoutProperty(id, 'visibility') !== 'none' : null];
})), ids);
/** Waits until the page applied the look for [activity] / [sac] (main.ts applyActivityLook sets __look). */
const looked = (activity, sac) => page.waitForFunction(([a, s]) => window.__look === `${a}/${s}`, [activity, sac], { timeout: 30_000 });
const IDS = ['greenway-foot', 'greenway-touring', 'surface-loose-path', 'surface-loose-track', 'bike-oneway-streets',
  'routes-hike', 'routes-hike-over', 'routes-bike', 'access-no-dash-foot', 'access-no-dash-touring', 'too-hard-foot'];

await page.goto(url + 'index.html');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60_000 });
await page.waitForFunction(() => window.__map?.isStyleLoaded(), null, { timeout: 60_000 });
await looked('HIKE', 2);
let v = await vis(IDS);
check(Object.values(v).every((x) => x !== null), `the style has every layer the test reads (${JSON.stringify(v)})`);
check(v['greenway-foot'] && v['routes-hike'] && v['access-no-dash-foot'], 'Hike T2: hiking green ways, hiking band, forbidden-for-walkers');
check(!v['greenway-touring'] && !v['bike-oneway-streets'] && !v['surface-loose-path'] && !v['routes-bike'], 'Hike T2: no bike layers');

await page.click('#activityChip');
await page.click('#activityMenu [data-act=BIKE_TOURING]');
await looked('BIKE_TOURING', 2);
v = await vis(IDS);
check(v['greenway-touring'] && v['surface-loose-path'] && v['surface-loose-track'] && v['bike-oneway-streets'],
  'Bike: greenway-touring, surface-loose-path / -track and bike-oneway-streets visible');
check(v['routes-bike'] && !v['routes-hike'] && !v['routes-hike-over'], 'Bike: the cycle band, not the hiking band');
check(!v['greenway-foot'] && !v['access-no-dash-foot'] && !v['too-hard-foot'] && v['access-no-dash-touring'], 'Bike: greenway-foot hidden, forbidden ways for touring.brf');
const bikeview = await page.evaluate(() => window.__map.getStyle().layers.filter((l) => l.id.endsWith('-bikeview'))
  .every((l) => window.__map.getLayoutProperty(l.id, 'visibility') !== 'none'));
check(bikeview, 'Bike: every "-bikeview" layer visible');

await page.click('#activityChip');
await page.click('#activityMenu [data-sac="4"]');
await looked('HIKE', 4);
v = await vis(IDS);
check(v['greenway-foot'] && v['routes-hike'] && !v['greenway-touring'] && !v['bike-oneway-streets'], 'Hike T4: the hiking look again');
const f = await page.evaluate(() => [JSON.stringify(window.__map.getFilter('routes-hike')), JSON.stringify(window.__map.getFilter('routes-hike-over'))]);
check(f[0].includes('["<=",["coalesce",["get","sac"],0],4]'), `Hike T4: routes-hike up to T4 (${f[0]})`);
check(f[1].includes('[">",["coalesce",["get","sac"],0],4]'), `Hike T4: routes-hike-over above T4 (${f[1]})`);

// remembered Bike: the look comes with the first load
await page.click('#activityChip');
await page.click('#activityMenu [data-act=MTB]');
await looked('MTB', 4);
await page.reload();
await page.waitForFunction(() => window.__map?.isStyleLoaded(), null, { timeout: 60_000 });
await looked('MTB', 4);
v = await vis(['greenway-mtb', 'greenway-foot', 'trails-mtb-label', 'trails-sac-label', 'routes-bike']);
check(v['greenway-mtb'] && v['trails-mtb-label'] && v['routes-bike'] && !v['greenway-foot'] && !v['trails-sac-label'], 'reload with MTB remembered: the MTB look');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
