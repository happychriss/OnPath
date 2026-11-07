// A long plan never holds the planner (web-planner.md §26, the app's cancelRun): a plan Meran → Hilden routed with
// "Plan anyway" keeps BRouter busy for minutes in the one engine worker; „+ Neu" drops it – the worker is stopped and a
// fresh one started (client.ts dropPlans / restart) – and a short plan right after arrives within seconds; undo still works.
// Needs the rd5 of both ends (E10_N45 Meran, E5_N50 NRW) – with only one of them the long plan fails at once and the
// check is skipped (says so). Usage: node test/stop-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
if (!fs.existsSync(path.join(segments, 'E5_N50.rd5')) || !fs.existsSync(path.join(segments, 'E10_N45.rd5'))) {
  console.log('skip: needs E10_N45.rd5 and E5_N50.rd5 in ' + segments);
  process.exit(0);
}
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); if (/\[engine\]/.test(m.text())) console.log('     ' + m.text()); });

try {
  await page.goto(url + 'index.html');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  const code = (points) => page.evaluate((p) => window.__engine.encode({ points: p, activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }), points);
  const long = await code([[46.6700, 11.1600], [51.1693, 6.9353]]); // Meran → Hilden, ~700 km
  const short = await code([[46.6700, 11.1600], [46.6850, 11.1900]]); // a few km in Meran

  await page.goto(url + 'index.html#p=' + long);
  await page.waitForSelector('dialog.longroute', { timeout: 30_000 });
  await page.click('#lrPlan'); // Plan anyway
  // the plan downloads its routing data, then routes – wait until BRouter really works (the worker is blocked then)
  // (client.ts marks a plan's start of routing – its private map, read here for the test only)
  const routing = await page.waitForFunction(() => [...window.__engine.plans.values()].some((v) => v > 0),
    null, { timeout: 180_000 }).then(() => true, () => false);
  await page.waitForTimeout(3000);
  const stillPlanning = await page.evaluate(() => document.body.dataset.planning === '1');
  if (!routing || !stillPlanning) {
    console.log('skip: the long plan ended by itself (routing data missing between the ends?) – nothing to stop');
  } else {
    const t0 = Date.now();
    await page.click('#newPlan');
    if (await page.locator('dialog.discard[open]').count()) await page.click('#discardOk');
    await page.waitForFunction(() => document.querySelectorAll('.pin').length === 0, null, { timeout: 10_000 }).catch(() => {});
    const clearMs = Date.now() - t0;
    check(clearMs < 3000, `„+ Neu" during the long plan: the map is empty at once (${clearMs} ms)`);
    await page.evaluate((c) => { location.hash = '#p=' + c; }, short);
    const ok = await page.waitForFunction(() => document.querySelector('#summary .big') && document.body.dataset.planning !== '1' && document.querySelectorAll('.pin').length === 2,
      null, { timeout: 30_000 }).then(() => true, () => false);
    const shortMs = Date.now() - t0;
    const r = await page.evaluate(() => window.__engine.restarts);
    check(ok && shortMs < 15_000, `a short plan right after arrives in ${shortMs} ms (not after the long one)`);
    check(r.count >= 1, `the blocked worker was replaced (${r.count}×, fresh worker ready in ${r.lastMs} ms)`);
    const h = JSON.parse(await page.evaluate(() => window.__engine.call('historyState')));
    check(h.canUndo, 'undo still works in the fresh worker (the history moved along)');
  }
  const bad = errors.filter((e) => !/Failed to load resource|favicon/.test(e));
  check(bad.length === 0, `no page errors (${bad.slice(0, 3).join(' | ')})`);
} finally {
  await browser.close();
  server.close();
}
console.log(fails.length ? `\n${fails.length} check(s) failed` : '\nstop-smoke: all checks passed');
process.exit(fails.length ? 1 : 0);
