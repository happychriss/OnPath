// Abbrechen while the routing data loads (owner 2026-11-27: „ich klicke auf Abbrechen, dann passiert gar nichts“): the
// wait window's Cancel stops for real – no route comes in afterwards, the panel shows „Route nicht berechnet –
// Berechnung abgebrochen.“ with a visible [Berechnen], the points stay with the straight line; Berechnen routes.
// The routing data is held back by the server (its segment answers wait) so the wait window is the routing data's.
// Usage: node test/cancel-smoke.mjs [segmentsDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
if (!fs.existsSync(path.join(segments, 'E10_N45.rd5'))) { console.log('skip: needs E10_N45.rd5 in ' + segments); process.exit(0); }
let hold = true;
const waiting = [];
// a held segment request is answered once [hold] ends (by the normal file server: the request is passed on then)
let pass = null;
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, intercept: (req, res) => {
  if (!hold || !req.url.startsWith('/segments/') || /^bytes=0-/.test(req.headers.range ?? '')) return false; // the index passes: the page knows what it waits for
  waiting.push(() => pass(req, res));
  return true;
} });
pass = (req, res) => server.emit('request', req, res);
const release = () => { hold = false; for (const f of waiting.splice(0)) f(); };
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'de-DE' });
const page = await ctx.newPage();
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const routes = [];
page.on('console', (m) => { if (/Route #\d+ for \d+ points ready/.test(m.text())) routes.push(m.text()); });
try {
  await page.goto(url + 'index.html?lang=de');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  const code = await page.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [46.6950, 11.2100]], activity: 'HIKE', sac: 2, pinned: [], names: {}, title: null }));
  await page.goto(url + 'index.html?lang=de#p=' + code);
  const win = await page.waitForSelector('#waitwin .wwcancel', { state: 'visible', timeout: 30_000 }).then(() => true, () => false);
  check(win, `the routing data held back: the wait window with Abbrechen shows (${waiting.length} requests held)`);
  // one wait at a time (owner 2026-11-27: „zwei Sachen bewegen sich“): while the wait window shows, nothing else moves –
  // no run bar, no sheet wait line, no other running animation outside the window
  // a spinner elsewhere on the page (like the locate button's or a busy line's) must stand still while the window shows
  await page.evaluate(() => { const sp = document.createElement('span'); sp.className = 'spin'; sp.id = 'testspin';
    sp.style.cssText = 'position:fixed;left:8px;bottom:8px;width:14px;height:14px;border:2px solid #ccc;border-top-color:#335A85;border-radius:50%;animation:spin 1s linear infinite'; document.body.append(sp); });
  await page.waitForTimeout(1500);
  const moving = await page.evaluate(() => {
    const vis = (el) => { if (!el) return false; const s = getComputedStyle(el); const r = el.getBoundingClientRect(); return s.display !== 'none' && s.visibility !== 'hidden' && !el.hidden && r.width > 0 && r.height > 0 && el.getClientRects().length > 0; };
    const others = document.getAnimations().filter((a) => a.playState === 'running').map((a) => a.effect?.target).filter((t) => t instanceof Element && !t.closest('#waitwin') && vis(t))
      .map((t) => (t.id ? '#' + t.id : '') + '.' + [...t.classList].join('.') + (t.parentElement ? ' in ' + (t.parentElement.id || t.parentElement.className) : ''));
    return { win: vis(document.getElementById('waitwin')), bar: vis(document.getElementById('runbar')), waitline: vis(document.querySelector('#summary .subline.waitline')), others: [...new Set(others)] };
  });
  check(moving.win && !moving.bar && !moving.waitline && moving.others.length === 0, `the wait window shows alone: no run bar, no sheet wait line, nothing else moving (${JSON.stringify(moving)})`);
  await page.waitForSelector('#waitwin .wwcancel', { state: 'visible', timeout: 30_000 });
  await page.click('#waitwin .wwcancel');
  await page.waitForTimeout(500);
  release(); // the data would come now – a stopped run must not route with it
  await page.waitForTimeout(6000);
  const after = await page.evaluate(() => ({ planning: document.body.dataset.planning === '1', win: !!document.querySelector('#waitwin'),
    line: document.body.innerText.match(/Route nicht berechnet[^\n]*/)?.[0] ?? '',
    btn: (() => { const b = document.getElementById('planAnyway'); if (!b) return null; const s = getComputedStyle(b); return { text: b.textContent, bg: s.backgroundColor, h: Math.round(b.getBoundingClientRect().height) }; })(),
    pins: document.querySelectorAll('.pin').length }));
  check(!after.planning && !after.win, `Abbrechen stops: no wait window, not planning (${JSON.stringify({ p: after.planning, w: after.win })})`);
  check(routes.length === 0, `no route comes in after Abbrechen (${routes.join(' | ') || 'none'})`);
  check(after.pins === 2, `the points stay (${after.pins})`);
  check(/Route nicht berechnet/.test(after.line), `the panel says „Route nicht berechnet …“ (${after.line})`);
  check(!!after.btn && after.btn.bg !== 'rgba(0, 0, 0, 0)' && after.btn.h >= 32, `[Berechnen] is a visible filled button (${JSON.stringify(after.btn)})`);
  if (after.btn) {
    await page.click('#planAnyway');
    const ok = await page.waitForFunction(() => document.body.dataset.planning !== '1' && !!document.querySelector('#summary .big'), null, { timeout: 60_000 }).then(() => true, () => false);
    check(ok && routes.length >= 1, `Berechnen routes (${routes.length} route report(s))`);
  }
} catch (e) { check(false, 'error: ' + e); }
await browser.close(); server.close();
console.log(fails.length ? `${fails.length} FAILED` : 'all ok');
process.exit(fails.length ? 1 : 0);
