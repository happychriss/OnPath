// Plan per QR code / link (web planner phase 1): opens a plan code made by the app's Kotlin (PlanCodeTest,
// formatVersion1IsFrozen), checks the page routes it, writes the same code back and shows it as a QR code.
// Usage: node test/share-smoke.mjs [segmentsDir] [screenshot.png]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const shot = process.argv[3] ?? path.join(here, '../../engine/build/share.png');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, mapDir: null });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

// 1. open the app's code from a link
await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check((await page.textContent('#points')).includes('Vellau'), 'point name from the code is shown');
check(await page.inputValue('#activity') === 'HIKE' && await page.inputValue('#sac') === '3', 'activity Hike, T3');
check(await page.evaluate(() => document.querySelectorAll('.pin').length) === 2, 'two points on the map');
await page.waitForFunction((c) => location.hash === '#p=' + c, CODE, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) === '#p=' + CODE, 'browser writes the same code as the app (address bar)');

// 2. share dialog with QR code and link
await page.click('#share');
await page.waitForSelector('dialog.share[open] .qr svg');
const link = await page.inputValue('#shareLink');
check(link.endsWith('#p=' + CODE), 'link in the dialog carries the code');
check(await page.evaluate(() => document.querySelectorAll('dialog.share .qr svg rect, dialog.share .qr svg path').length) > 0, 'QR code drawn');
await page.screenshot({ path: shot });
await page.click('#closeShare');

// 3. an edit changes the code; a reload brings the edited plan back
const before = await page.evaluate(() => location.hash);
await page.click('#reverse');
await page.waitForFunction((b) => location.hash !== b, before, { timeout: 10_000 }).catch(() => {});
check(await page.evaluate(() => location.hash) !== before, 'reversed plan → new code in the address');
await page.reload();
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check((await page.textContent('#points')).trim().startsWith('Vellau'), 'reload keeps the reversed plan (Vellau first)');

// 4. a broken link is a plain message, no crash
await page.goto(url + 'index.html#p=AQ');
await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('readable plan'), null, { timeout: 60_000 }).catch(() => {});
check((await page.textContent('#status')).includes('does not contain a readable plan'), 'broken link → plain message');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
