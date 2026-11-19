// The wait window (form A, owner 2026-11-19) in pictures: a new place's routing data and a long route, DE + EN, desktop
// and phone width – for a look before it lands (the owner treats a visible glitch as a bug). Also logs what the window
// said every 100 ms, and fails on a window half on screen or a map note beside it. Then the sign beside the map
// (form B) and its details, DE + EN, desktop and phone: whole on screen, over no other line.
// Usage: node test/waitwin-shots.mjs [segmentsDir] [outDir]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import os from 'node:os';
import { serve } from './serve.mjs';
import { handleMaps } from '../../host/src/maps.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const out = path.resolve(process.argv[3] ?? path.join(here, '../../engine/build/waitwin'));
fs.mkdirSync(out, { recursive: true });
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
let bad = 0;
for (const lang of ['de', 'en']) {
  for (const [name, vp] of [['desktop', { width: 1280, height: 800 }], ['phone', { width: 390, height: 800 }]]) {
    const c = await browser.newContext({ viewport: vp, deviceScaleFactor: 1 });
    await c.route('**/segments/**', async (r) => { await new Promise((res) => setTimeout(res, 1500)); return r.continue(); });
    await c.route('**/maps/v1/**', (r) => r.fulfill({ status: 503, body: '' }));
    const p = await c.newPage();
    await p.goto(url + `index.html?lang=${lang}`);
    await p.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
    const code = await p.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [46.6773, 11.1302], [46.6400, 11.1500], [46.6879, 11.1111]],
      activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }));
    await p.goto(url + `index.html?lang=${lang}&slowRoute=2500#p=` + code);
    await p.evaluate(() => { window.__ww = []; setInterval(() => { const w = document.getElementById('waitwin'); const s = w ? `${w.dataset.pct} | ${w.querySelector('.wwtitle').textContent} | ${[...w.querySelectorAll('.wwsteps li')].map((l) => l.className + ':' + l.textContent).join(', ')} | ${w.querySelector('.wwline').textContent}` : '-';
      if (window.__ww.at(-1)?.s !== s) window.__ww.push({ t: Math.round(performance.now()), s }); }, 100); });
    let shot = 0;
    for (let i = 0; i < 60 && shot < 3; i++) {
      await p.waitForTimeout(1000);
      const w = await p.evaluate(() => { const e = document.getElementById('waitwin'); if (!e) return null; const r = e.getBoundingClientRect();
        const kids = [...e.querySelectorAll('*')].map((k) => k.getBoundingClientRect()).filter((k) => k.width > 0);
        return { pct: +e.dataset.pct, r: { l: r.left, t: r.top, r: r.right, b: r.bottom }, vw: innerWidth, vh: innerHeight,
          out: kids.some((k) => k.left < r.left - 1 || k.right > r.right + 1 || k.top < r.top - 1 || k.bottom > r.bottom + 1),
          // one message at a time: no map note (overview only, map missing …) beside the window
          note: [...document.querySelectorAll('#mapnotes .mapnote')].filter((n) => n.getClientRects().length && getComputedStyle(n).display !== 'none').map((n) => n.textContent)[0] ?? null,
          title: e.querySelector('.wwtitle').textContent }; });
      if (!w) continue;
      const off = w.r.l < 0 || w.r.t < 0 || w.r.r > w.vw || w.r.b > w.vh;
      if (off || w.out) { bad++; console.log(`FAIL ${lang} ${name}: half on screen or overflowing ${JSON.stringify(w)}`); }
      if (w.note) { bad++; console.log(`FAIL ${lang} ${name}: a map note beside the wait window ("${w.note}")`); }
      if ((shot === 0 && w.pct > 5) || (shot === 1 && /Route|route/.test(w.title)) || (shot === 2 && w.pct > 80)) {
        await p.screenshot({ path: path.join(out, `waitwin-${lang}-${name}-${shot + 1}.png`) });
        shot++;
      }
    }
    const log = await p.evaluate(() => window.__ww);
    console.log(`--- ${lang} ${name}\n` + log.map((x) => `${x.t} ${x.s}`).join('\n'));
    await c.close();
  }
}
// ---- form B: the map areas along a loaded route, the view with its map (zoom 9) – a fake doorman builds them ----
const store = fs.mkdtempSync(path.join(os.tmpdir(), 'onpath-sign-'));
let calls = 0;
const doorman = { fetch: async (req) => {
  const u = new URL(req.url);
  const ids = u.pathname === '/v1/cells/request' ? (await req.json()).cells : u.searchParams.get('ids').split(',');
  calls++;
  return Response.json({ cells: ids.map((id) => ({ id, state: 'building', step: 'base', progress: Math.min(0.9, 0.15 * calls), eta_s: 240 })), poll_s: 1 });
} };
const s2 = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir: store, maps: (req) => handleMaps(req, { MAP_KEY: 'k', DOORMAN: doorman }) });
for (const lang of ['de', 'en']) {
  for (const [name, vp] of [['desktop', { width: 1280, height: 800 }], ['phone', { width: 390, height: 800 }]]) {
    const c = await browser.newContext({ viewport: vp, deviceScaleFactor: 1 });
    const p = await c.newPage();
    await p.goto(s2.url + `index.html?lang=${lang}&at=11.14,46.67,9`);
    await p.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
    const code = await p.evaluate(() => window.__engine.encode({ points: [[46.6700, 11.1600], [46.6773, 11.1302], [46.6400, 11.1500], [46.6879, 11.1111]],
      activity: 'BIKE_TOURING', sac: 2, pinned: [], names: {}, title: null }));
    await p.evaluate((cd) => { location.hash = 'p=' + cd; }, code);
    await p.waitForFunction(() => window.__state.plan && document.body.dataset.planning !== '1', null, { timeout: 120_000 });
  // the route's fit first (it may come late on a slow machine – CI's two cores), then out to zoom 9, and stay there
  await p.waitForFunction(() => window.__map.getZoom() >= 10 && !window.__map.isMoving(), null, { timeout: 30_000 }).catch(() => {});
  for (let k = 0; k < 5; k++) {
    await p.evaluate(() => window.__map.jumpTo({ zoom: 9 }));
    await p.waitForTimeout(1500);
    if (await p.evaluate(() => window.__map.getZoom() < 10 && !window.__map.isMoving())) break;
  }
    const came = await p.waitForFunction(() => window.__wait.beside() > 0, null, { timeout: 60_000 }).then(() => true, () => false);
    if (!came) { bad++; console.log(`FAIL ${lang} ${name}: no sign beside the map`); await c.close(); continue; }
    await p.waitForTimeout(1200);
    const fits = (sel) => p.evaluate((q) => {
      const e = document.querySelector(q); if (!e) return 'missing';
      const r = e.getBoundingClientRect();
      if (r.left < 0 || r.top < 0 || r.right > innerWidth || r.bottom > innerHeight) return `half on screen ${JSON.stringify(r)}`;
      const others = ['#topbar', '#mapnotes .mapnote', '#waitwin', '#sheet', '#msgcards .msgcard'].flatMap((o) => [...document.querySelectorAll(o)])
        .filter((o) => o.getClientRects().length && getComputedStyle(o).display !== 'none' && !e.contains(o));
      const hit = others.find((o) => { const b = o.getBoundingClientRect(); return b.left < r.right && b.right > r.left && b.top < r.bottom && b.bottom > r.top; });
      return hit ? `over ${hit.id || hit.className}` : 'ok';
    }, sel);
    const sign = await fits('#besidesign');
    if (sign !== 'ok') { bad++; console.log(`FAIL ${lang} ${name}: the sign ${sign}`); }
    await p.screenshot({ path: path.join(out, `beside-${lang}-${name}-sign.png`) });
    await p.click('#besidesign');
    await p.waitForTimeout(400);
    const det = await fits('.besidedetails');
    if (det !== 'ok') { bad++; console.log(`FAIL ${lang} ${name}: the details ${det}`); }
    await p.screenshot({ path: path.join(out, `beside-${lang}-${name}-details.png`) });
    console.log(`--- sign ${lang} ${name}: ${await p.evaluate(() => document.getElementById('besidesign').textContent)} · ${sign} · details ${det}`);
    await c.close();
  }
}
s2.server.close();
fs.rmSync(store, { recursive: true, force: true });
await browser.close(); server.close();
console.log(bad ? 'FAILED' : 'all ok');
process.exit(bad ? 1 : 0);
