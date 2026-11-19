// A plan near München arrives (2026-11-19: every plan there failed at once with "RangeError: Maximum call stack size
// exceeded" – BRouter's peninsula cleanup, OsmNodesMap.minVisitIdInSubtree, recursed once per node of a long chain and
// the browser worker's stack is small; the web build's OsmNodesMap walks it with its own stack, OsmNodesMapTest).
// München → Holzkirchen as Bike and Hike in the real page (engine worker), then Innsbruck → Venedig as before.
// Needs E10_N45.rd5. Usage: node test/deep-smoke.mjs [segmentsDir] [dist]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dist = path.resolve(process.argv[3] ?? path.join(here, '../dist'));
if (!fs.existsSync(path.join(segments, 'E10_N45.rd5'))) {
  console.log('skip: needs E10_N45.rd5 in ' + segments);
  process.exit(0);
}
const { server, url } = await serve({ dist, segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

const plans = [
  ['München → Holzkirchen, Bike', [[48.1374, 11.5755], [47.8833, 11.6997]], 'BIKE_TOURING'],
  ['München → Holzkirchen, Hike', [[48.1374, 11.5755], [47.8833, 11.6997]], 'HIKE'],
  ['Innsbruck → Venedig, Bike', [[47.2692, 11.4041], [45.4408, 12.3155]], 'BIKE_TOURING'],
];
try {
  await page.goto(url + 'index.html');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  for (const plan of plans) {
    const [name, points, activity] = plan;
    const code = await page.evaluate(([p, a]) => window.__engine.encode({ points: p, activity: a, sac: 2, pinned: [], names: {}, title: null }), [points, activity]);
    const before = errors.length;
    const t0 = Date.now();
    await page.goto(url + `index.html?n=${plans.indexOf(plan)}#p=` + code); // a fresh page per plan (a new query = a real load)
    // a long plan may ask first (dialog.longroute) – plan anyway
    const lr = page.waitForSelector('dialog.longroute[open]', { timeout: 15_000 }).then(() => page.click('#lrPlan'), () => {});
    // done = a distance and no plan running; or the plan failed (the page says so – no need to wait any longer)
    const done = await page.waitForFunction(() => {
      if (/could not be planned/.test(document.body.innerText)) return 'failed';
      return /\d/.test(document.querySelector('#summary .big')?.textContent ?? '') && document.body.dataset.planning !== '1' && 'ok';
    }, null, { timeout: 120_000 }).then((h) => h.jsonValue(), () => 'timeout');
    const ok = done === 'ok';
    const ms = Date.now() - t0;
    await lr;
    const km = (await page.evaluate(() => document.querySelector('#summary .big')?.textContent ?? '')).trim();
    const stack = errors.slice(before).filter((e) => /call stack|RangeError/.test(e));
    if (!ok) console.log('     page: ' + (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 400));
    check(ok && stack.length === 0, `${name}: planned in ${ms} ms (${km})${stack.length ? ' – ' + stack[0].slice(0, 120) : ''}`);
  }
  const bad = errors.filter((e) => !/Failed to load resource|favicon/.test(e));
  check(bad.length === 0, `no page errors (${bad.slice(0, 3).join(' | ')})`);
} finally {
  await browser.close();
  server.close();
}
console.log(fails.length ? `\n${fails.length} check(s) failed` : '\ndeep-smoke: all checks passed');
process.exit(fails.length ? 1 : 0);
