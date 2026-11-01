// Manual check (not in CI): the planner in Firefox and WebKit (Safari's engine) – engine ready or the "too old" page,
// two clicks → a route on the ways. Needs `npx playwright install firefox webkit` once.
// Usage: node test/browsers-check.mjs [segmentsDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { firefox, webkit } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
for (const [name, type] of [['firefox', firefox], ['webkit', webkit]]) {
  let browser;
  try { browser = await type.launch(); } catch (e) { check(false, `${name}: not started (${String(e).split('\n')[0]})`); continue; }
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url + 'index.html');
  const state = await page.waitForFunction(() => document.body.dataset.engine || (document.querySelector('.nowebgl') ? 'stop' : ''),
    null, { timeout: 180_000 }).then((h) => h.jsonValue(), () => 'timeout');
  const ver = browser.version();
  if (state !== 'ready') {
    const text = await page.textContent('body');
    check(false, `${name} ${ver}: engine ${state} – "${text.trim().replace(/\s+/g, ' ').slice(0, 120)}"`);
    await browser.close(); continue;
  }
  check(true, `${name} ${ver}: routing engine ready`);
  const clickAt = async (lon, lat) => {
    const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
    await page.mouse.click(p[0], p[1]);
  };
  await clickAt(11.1600, 46.6700);
  await page.waitForSelector('.pointmenu', { timeout: 20_000 }).catch(() => {});
  await page.click('.pointmenu button:first-of-type').catch(() => {});
  await page.waitForTimeout(300);
  await clickAt(11.1500, 46.6950);
  await page.waitForFunction(() => document.querySelector('#summary .big') && !document.getElementById('status').classList.contains('busy'),
    null, { timeout: 180_000 }).catch(() => {});
  const r = await page.evaluate(() => ({ sum: document.getElementById('summary').textContent.replace(/\s+/g, ' ').trim(),
    routed: window.__state.plan ? !window.__state.plan.legs.every((l) => l.straight) : false }));
  check(r.routed, `${name}: two clicks → a route on the ways ("${r.sum.slice(0, 60)}")`);
  check(errors.length === 0, `${name}: no page error (${errors.join(' | ').slice(0, 300)})`);
  await browser.close();
}
server.close();
process.exit(fails.length ? 1 : 0);
