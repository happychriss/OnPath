// A #pair= link opened in a PHONE browser (Android) must not start the browser join: it shows "Open in OnPath" (an
// intent:// link to the app) and the in-app way. A desktop browser keeps the join dialog. Also the pairing dialog says
// plainly: scan with OnPath, not with the camera app (owner field 2026-11-07, Samsung camera opens the browser).
// Usage: node test/pairphone-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, sync: (r) => handleSync(r, env) });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const ANDROID = 'Mozilla/5.0 (Linux; Android 12; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const PAIR = 'AAAA';

async function open(opts, lang) {
  const ctx = await browser.newContext(opts);
  const P = await ctx.newPage();
  P.on('pageerror', (e) => errors.push(e.message));
  await P.goto(`${url}index.html?lang=${lang}&x=${Math.random()}#pair=${PAIR}`);
  return { ctx, P };
}

for (const lang of ['de', 'en']) {
  const want = lang === 'de'
    ? { title: 'In OnPath öffnen', path: '☰ → Mit Browser verbinden' }
    : { title: 'Open in OnPath', path: '☰ → Connect to browser' };
  // phone: the card, no join
  {
    const { ctx, P } = await open({ userAgent: ANDROID, viewport: { width: 412, height: 839 }, isMobile: true, hasTouch: true }, lang);
    await P.waitForSelector('dialog.pairapp[open]', { timeout: 60000 }).catch(() => {});
    check(await P.locator('dialog.pairapp[open]').count() === 1, `${lang}: phone shows the Open-in-OnPath card`);
    check(await P.locator('dialog.join').count() === 0, `${lang}: phone does not start the browser join`);
    const text = await P.locator('dialog.pairapp').innerText().catch(() => '');
    check(text.includes(want.title), `${lang}: card says "${want.title}"`);
    check(text.includes(want.path), `${lang}: card names the in-app way "${want.path}"`);
    const href = await P.locator('dialog.pairapp a.openapp').getAttribute('href').catch(() => null);
    check(!!href && href.startsWith(`intent://plan.onpath.earth/#pair=${PAIR}#Intent;scheme=https;package=com.onpath.app;S.browser_fallback_url=`) && href.endsWith(';end'), `${lang}: intent link ${href}`);
    check(!!href && decodeURIComponent(href.split('S.browser_fallback_url=')[1].split(';end')[0]).includes(`#pair=${PAIR}`), `${lang}: fallback url is the current link`);
    await ctx.close();
  }
  // desktop: unchanged
  {
    const { ctx, P } = await open({ viewport: { width: 1280, height: 800 } }, lang);
    await P.waitForSelector('dialog.join[open]', { timeout: 60000 }).catch(() => {});
    check(await P.locator('dialog.join[open]').count() === 1, `${lang}: desktop keeps the join dialog`);
    check(await P.locator('dialog.pairapp').count() === 0, `${lang}: desktop shows no card`);
    await ctx.close();
  }
  // the pairing dialog: scan with OnPath, not with the camera app
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const P = await ctx.newPage();
    P.on('pageerror', (e) => errors.push(e.message));
    await P.goto(`${url}index.html?lang=${lang}&x=${Math.random()}`);
    await P.click('#syncBadge');
    await P.waitForSelector('#pairLink', { timeout: 60000 }).catch(() => {});
    const how = await P.locator('#pairBody').innerText().catch(() => '');
    const re = lang === 'de' ? /mit OnPath[^.]*nicht mit der Kamera-App/ : /with OnPath, not with the camera app/;
    check(re.test(how), `${lang}: pairing dialog says scan with OnPath, not the camera app`);
    check(!(lang === 'de' ? /koppel|kopplung/i : /pair/i).test(how), `${lang}: pairing dialog has no koppeln / pair`);
    await ctx.close();
  }
}
await browser.close();
server.close();
check(errors.length === 0, `no page errors (${errors.slice(0, 3).join(' | ')})`);
console.log(fails.length ? `\n${fails.length} FAILED` : '\nall ok');
process.exit(fails.length ? 1 : 0);
