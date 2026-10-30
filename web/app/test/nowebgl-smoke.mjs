// Without WebGL2 the page says plainly that the browser cannot draw the map (owner 2026-10-30) – never a blank page.
// Usage: node test/nowebgl-smoke.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
for (const [locale, want] of [['de-DE', 'Dein Browser kann die Karte nicht zeichnen'], ['en-US', 'Your browser cannot draw the map']]) {
  const ctx = await browser.newContext({ locale });
  await ctx.addInitScript(() => { // a browser without WebGL2
    const orig = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (kind, ...a) { return kind === 'webgl2' ? null : orig.call(this, kind, ...a); };
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url + 'index.html');
  const shown = await page.waitForSelector('.nowebgl[role=alert]', { timeout: 30_000 }).then(() => true, () => false);
  const text = shown ? await page.textContent('.nowebgl') : '';
  check(shown && text.includes(want) && text.includes('WebGL2'), `${locale}: the message instead of a blank page ("${text.trim().slice(0, 60)}…")`);
  check(!(await page.$('#map')), `${locale}: no map is started`);
  check(errors.length === 0, `${locale}: no page error (${errors.join(' | ')})`);
  await ctx.close();
}
await browser.close(); server.close();
process.exit(fails.length ? 1 : 0);
