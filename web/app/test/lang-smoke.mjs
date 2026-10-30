// German first, multilingual base (A-011, docs/design/web-planner.md §18): a German browser gets the German page
// (header, sheet, engine texts with decimal comma), the website links point at the German pages, a shared link shows
// the one calm line about the app in the sheet (never over the route; once per session, ✕), the QR dialog has it only
// when it was not shown yet; the language switch is remembered and reloads in English with the plan kept; noindex meta.
// Usage: node test/lang-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (pinned, named), "Waalweg"
const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, mapDir: null });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'de-DE' });
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const text = (sel) => page.evaluate((s) => document.querySelector(s)?.textContent ?? '', sel);

// 1. a German browser, a shared link
await page.goto(url + 'index.html#p=' + CODE);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check(await page.evaluate(() => document.documentElement.lang) === 'de', 'html lang = de');
check((await text('#header')).includes('Meine Routen') && (await text('#header')).includes('Handy verbinden'), 'header in German');
check((await text('#activityChip')).includes('Wandern'), `activity from the engine in German: ${(await text('#activityChip')).trim()}`);
check(/\d,\d km/.test(await text('#summary')), `distance with a decimal comma: ${(await text('#summary .dist')).trim()}`);
check((await text('#pointsSection')).includes('Punkte') && (await text('#share')).includes('Aufs Handy'), 'sheet in German');
const links = await page.evaluate(() => [...document.querySelectorAll('a[href^="https://onpath.earth"]')].map((a) => a.getAttribute('href')));
for (const u of ['https://onpath.earth/de/', 'https://onpath.earth/de/datenschutz/', 'https://onpath.earth/de/impressum/'])
  check(links.includes(u), `website link ${u}`);
check(!links.some((u) => /play\.google|apps\.apple/.test(u)), 'no store link');
check(await page.evaluate(() => document.querySelector('meta[name="robots"]')?.getAttribute('content')) === 'noindex, nofollow', 'meta robots noindex until launch');
// the one line about the app: in the sheet, with the try page, closable; the route is not covered (sheet beside it)
const hint = await page.$('#sheet #appHintLine');
check(!!hint, 'shared link: the line about the app is in the plan sheet');
check((await text('#appHintLine')).includes('Mit der App: offline unterwegs, Navigation, neue Route ohne Netz.'), 'its text (German)');
check(await page.evaluate(() => document.querySelector('#appHintLine a')?.getAttribute('href')) === 'https://onpath.earth/de/ausprobieren/', '"App holen" → the try page');
const pins = await page.evaluate(() => { const s = document.getElementById('sheet').getBoundingClientRect();
  return [...document.querySelectorAll('.pin')].every((p) => p.getBoundingClientRect().left > s.right); });
check(pins, 'both points are beside the sheet (the hint never covers the route)');
await page.click('#appHintLine .x');
check(!(await page.$('#appHintLine')), '✕ closes it');
// QR dialog afterwards: once per session → not again
await page.click('#share');
await page.waitForSelector('dialog.share[open] .qr svg');
check(!(await page.$('dialog.share #appHintLine')), 'QR dialog: not again in this session');
check((await text('dialog.share h2')).includes('Aufs Handy · Teilen'), 'QR dialog title in German');
await page.click('#closeShare');

// 2. a new session: the QR dialog shows the line
const ctx3 = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'de-DE' });
const p3 = await ctx3.newPage();
await p3.goto(url + 'index.html');
await p3.waitForFunction(() => document.getElementById('status')?.textContent?.includes('bereit'), null, { timeout: 120_000 }).catch(() => {});
await p3.click('#map', { position: { x: 700, y: 400 } });
await p3.waitForSelector('.holdpopup button', { timeout: 20_000 });
await p3.click('.holdpopup button');
await p3.click('#map', { position: { x: 900, y: 300 } });
await p3.waitForFunction(() => !document.getElementById('share').disabled, null, { timeout: 60_000 });
await p3.click('#share');
await p3.waitForSelector('dialog.share[open] .qr svg');
check(!!(await p3.$('dialog.share #appHintLine')), 'Send to phone in a new session: the QR dialog has the line about the app');
await ctx3.close();

// 3. the switch: English, remembered, the plan kept
const hash = await page.evaluate(() => location.hash);
await Promise.all([page.waitForNavigation({ timeout: 60_000 }), page.click('#langSwitch [data-lang="en"]')]);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check((await text('#header')).includes('My routes') && (await text('#activityChip')).includes('Hike'), 'after the switch: English page and engine texts');
check(await page.evaluate(() => location.hash) === hash, 'the plan stays (address)');
check(/\d\.\d km/.test(await text('#summary')), 'decimal point in English');
const en = await page.evaluate(() => [...document.querySelectorAll('a[href^="https://onpath.earth"]')].map((a) => a.getAttribute('href')));
check(en.includes('https://onpath.earth/privacy/') && en.includes('https://onpath.earth/legal/') && en.includes('https://onpath.earth/'), 'English website links');
await page.reload();
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
check((await text('#header')).includes('My routes'), 'remembered after a reload (German browser, English chosen)');

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
