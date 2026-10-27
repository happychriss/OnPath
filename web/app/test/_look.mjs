import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
const out = '/tmp/claude-0/-home-user-OnPath/9b2d68db-6261-5f2f-8b1a-99d9bb207754/scratchpad/shots/';
const { server, url } = await serve({ dist: '/home/user/OnPath/web/app/dist', segments: '/home/user/build/routing-test/segments', dataDir: '/home/user/build/cell/store' });
const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
for (const [name, vp] of [['desk', { width: 1280, height: 800 }], ['phone', { width: 412, height: 860 }]]) {
  const p = await b.newPage({ viewport: vp });
  p.on('pageerror', (e) => console.log('ERR', String(e)));
  p.on('console', (m) => { if (m.type() === 'error' && !/404/.test(m.text())) console.log('[c]', m.text()); });
  await p.goto(url + 'index.html');
  await p.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 60000 });
  await p.waitForTimeout(1500);
  const at = async (lon, lat) => p.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); return [q.x, q.y]; }, [lon, lat]);
  let q = await at(11.16, 46.67); await p.mouse.click(q[0], q[1]); await p.waitForSelector('.pointmenu');
  await p.screenshot({ path: out + name + '-1menu.png' });
  await p.click('.pointmenu button:first-of-type');
  await p.waitForTimeout(400);
  q = await at(11.135, 46.684); await p.mouse.click(q[0], q[1]);
  await p.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 60000 });
  await p.waitForTimeout(800);
  await p.screenshot({ path: out + name + '-2plan.png' });
  if (name === 'phone') { await p.click('#handle'); await p.waitForTimeout(300); await p.screenshot({ path: out + name + '-3expanded.png' }); }
  else { await p.locator('.pin').nth(1).click(); await p.waitForSelector('.pointmenu'); await p.screenshot({ path: out + name + '-3pointmenu.png' }); }
  await p.click('#activityChip'); await p.waitForTimeout(300); await p.screenshot({ path: out + name + '-4picker.png' });
  await p.close();
}
await b.close(); server.close();
