// How calm the flyover is (owner 2026-11-07: "könnte ruhiger sein"): plans Meran → Schenna on real map data, flies the
// whole route at 1× and reports the camera's turn rate (°/s) and how much the centre's direction of travel swings.
// Usage: [FLY_GPU=1] [FLY_SHOT=file.png] node test/fly-calm.mjs segmentsDir mapStoreDir [seconds=32] (shot at 40 %)
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2]);
const dataDir = path.resolve(process.argv[3]);
const secs = Number(process.argv[4] ?? 25);
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: process.env.FLY_GPU ? ['--use-gl=angle', '--use-angle=gl', '--ignore-gpu-blocklist']
  : ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
await page.evaluate(() => window.__map.jumpTo({ center: [11.175, 46.68], zoom: 13 }));
await page.waitForTimeout(800);
const clickAt = async (lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const r = window.__map.getCanvas().getBoundingClientRect();
    const q = window.__map.project([lo, la]); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
await clickAt(11.1600, 46.6700);
await page.waitForSelector('.pointmenu');
await page.click('.pointmenu button:first-of-type');
await page.waitForTimeout(300);
await clickAt(11.1900, 46.6900);
await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
await page.waitForTimeout(1500);
await page.click('#terrain3d');
await page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 });
await page.waitForTimeout(1500);
// record every frame's camera in the page (the flyover's own trace keeps only 200)
await page.evaluate(() => { window.__rec = []; const m = window.__map;
  m.on('render', () => { if (window.__fly.status.state !== 'flying') return; const c = m.getCenter(); window.__rec.push({ t: performance.now(), b: m.getBearing(), lng: c.lng, lat: c.lat, z: m.getZoom() }); }); });
await page.click('#flyStart');
await page.waitForTimeout(secs * 400);
await page.screenshot({ path: process.env.FLY_SHOT ?? '/tmp/fly.png' });
await page.waitForTimeout(secs * 600);
const st = await page.evaluate(() => window.__fly.status);
const rec = await page.evaluate(() => window.__rec);
const box = await page.evaluate(() => { const b = document.getElementById('flyBar').getBoundingClientRect(); return [b.x, b.y, b.width, b.height]; });
await browser.close(); server.close();
// turn rate per frame (°/s), smoothed over 0.25 s windows so one long frame does not dominate
const rates = [], course = [], zooms = [];
for (let i = 1; i < rec.length; i++) {
  const dt = (rec[i].t - rec[i - 1].t) / 1000; if (dt <= 0) continue;
  rates.push(Math.abs(((rec[i].b - rec[i - 1].b + 540) % 360) - 180) / dt);
  zooms.push(Math.abs(rec[i].z - rec[i - 1].z) / dt);
}
// the centre's direction of travel over 0.5 s steps, and how fast it swings (°/s)
let j = 0; const pts = [];
for (const r of rec) if (!pts.length || r.t - pts[pts.length - 1].t >= 500) pts.push(r);
for (let i = 1; i < pts.length; i++) {
  const a = pts[i - 1], b = pts[i];
  course.push(Math.atan2((b.lng - a.lng) * Math.cos(a.lat * Math.PI / 180), b.lat - a.lat) * 180 / Math.PI);
}
const swing = []; for (let i = 1; i < course.length; i++) swing.push(Math.abs(((course[i] - course[i - 1] + 540) % 360) - 180) / 0.5);
const q = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : 0; };
const r1 = (x) => Math.round(x * 10) / 10;
console.log(JSON.stringify({ frames: rec.length, flown: Math.round(st.d), total: Math.round(st.total),
  turnMax: r1(Math.max(...rates)), turnP95: r1(q(rates, 0.95)), turnMean: r1(rates.reduce((s, x) => s + x, 0) / rates.length),
  courseSwingMax: r1(Math.max(0, ...swing)), courseSwingP95: r1(q(swing, 0.95)), zoomRateMax: Math.round(Math.max(...zooms) * 100) / 100, flyBar: box }));
