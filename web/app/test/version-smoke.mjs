// A newer planner deployed while a tab is open (backlog "Pairing in the browser – bugs" (2)): the page knows its build,
// version.json says what is deployed. Same build: no box. Another build: one box (msgcard.ts, the app's MessageCard
// HINWEIS) "New version of the planner" [Reload], 12 px under the search bar – never a reload by itself; Reload keeps
// the route (it is in the address). The engine is loaded with ?v=<build>.
// Usage: node test/version-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Meran → Vellau, Hike T3 (the app's frozen PlanCode)
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const log = [];
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, log });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const box = () => page.evaluate(() => { const c = document.querySelector('#msgcards:not([hidden]) .msgcard');
  return c ? { kind: c.className, text: c.querySelector('.msgtitle').textContent + ' | ' + (c.querySelector('.msgdetail')?.textContent ?? ''),
    buttons: [...c.querySelectorAll('button')].map((b) => b.textContent), top: c.getBoundingClientRect().top,
    bar: document.querySelector('.topbar').getBoundingClientRect().bottom } : null; });

await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
const deployed = JSON.parse(await (await fetch(url + 'version.json')).text()).build;
check(/^[0-9a-z]+-[0-9a-z]+$/.test(deployed), `version.json names the build (${deployed})`);
check(log.some((u) => u.includes('engine/onpath-web-engine.wasm?v=' + deployed)), 'the engine is loaded with ?v=<build> (never an old one from a cache)');
const diag = await page.evaluate(() => window.__diag());
check(diag.includes('build: ' + deployed), 'the diagnostics name the page\'s build');

await page.evaluate(() => window.dispatchEvent(new Event('focus')));
await page.waitForTimeout(800);
check(await box() === null, 'the same build deployed: no box');

// a newer build is deployed while the tab is open
await page.route('**/version.json', (r) => r.fulfill({ contentType: 'application/json', body: '{"build":"0123456789ab-newer"}' }));
await page.evaluate(() => { window.__stays = 1; });
await page.waitForTimeout(31_000); // the check runs at most every 30 s
await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
await page.waitForFunction(() => document.querySelector('#msgcards:not([hidden]) .msgcard'), null, { timeout: 10_000 }).catch(() => {});
const b = await box();
check(!!b && /hinweis/.test(b.kind) && b.text === 'New version of the planner | Reload to use it – your route stays.' && b.buttons.join() === 'Reload',
  `a newer build: one box "New version of the planner" with [Reload] (${JSON.stringify(b)})`);
check(!!b && Math.abs(b.top - b.bar - 12) <= 1, `the box sits 12 px under the search bar (${b && (b.top - b.bar).toFixed(1)} px)`);
check(await page.evaluate(() => window.__stays === 1), 'the page did not reload by itself');
await Promise.all([page.waitForEvent('load'), page.click('#msgcards .msgbtn')]);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check(await page.evaluate(() => window.__stays === undefined) && (await page.textContent('#points')).includes('Vellau'),
  'Reload: the page loads again, the route stays (it is in the address)');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
