// Frames per second over a dense city's POIs (app b71b1df9 density limit + f857102b invisible key text, owner
// 2026-11-13 central London): the map jumps to a city centre in the cells store, then zooms in and out (z14 ↔ z16.5)
// and pans for a few seconds per variant – with and without the density limit ("dz" on the POI features) and with
// and without the POI icons' invisible key text – and prints frames per second and frame times (p50 / p95 / worst).
// Nothing is checked (numbers for the doc). It draws with the machine's GPU (headless Chromium, ANGLE on OpenGL); the
// variants run twice, alternating, so a busy moment does not decide.
// Usage: node test/poi-fps.mjs [segmentsDir] [mapStoreDir] [lon,lat]  (default: Köln Dom – the store has no London)
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const [lon, lat] = (process.argv[4] ?? '6.9583,50.9413').split(',').map(Number);
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
// the machine's GPU (ANGLE on OpenGL) – SwiftShader draws this map at ~1 fps whatever the POIs; GL=swiftshader for it
const gpu = process.env.GL === 'swiftshader' ? ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] : ['--use-angle=gl', '--ignore-gpu-blocklist', '--enable-gpu'];
const browser = await chromium.launch({ args: gpu });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.goto(url + 'index.html');
await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
await page.evaluate(([lo, la]) => window.__map.jumpTo({ center: [lo, la], zoom: 15 }), [lon, lat]);
// the POIs of the cells in view loaded (cells.ts loadPois) and the map idle
await page.waitForFunction(() => (window.__map.getSource('pois')?.serialize().data.features?.length ?? 0) > 1000, null, { timeout: 120_000 });
await page.waitForFunction(() => window.__map.loaded() && !window.__map.isMoving(), null, { timeout: 60_000 });
const info = await page.evaluate(() => {
  const f = window.__map.getSource('pois').serialize().data.features;
  return { pois: f.length, withDz: f.filter((x) => x.properties?.dz != null).length };
});
console.log(`centre ${lat}, ${lon}: ${info.pois} POIs loaded, ${info.withDz} with "dz" (drawn only from z17)`);

/** Sets the variant: [dz] keep the density limit's "dz", [key] the POI icon layers' invisible key text. */
const variant = (dz, key) => page.evaluate(([dz, key]) => {
  const m = window.__map;
  window.__poiAll ??= structuredClone(m.getSource('pois').serialize().data);
  const data = structuredClone(window.__poiAll);
  if (!dz) for (const f of data.features) delete f.properties?.dz;
  m.getSource('pois').setData(data);
  window.__keyText ??= Object.fromEntries(m.getStyle().layers.filter((l) => l.id.startsWith('poi-') && !l.id.endsWith('-name') && l.layout?.['text-field'])
    .map((l) => [l.id, l.layout['text-field']]));
  for (const [id, tf] of Object.entries(window.__keyText)) m.setLayoutProperty(id, 'text-field', key ? tf : undefined);
}, [dz, key]);

const run = async (name) => {
  await page.evaluate(([lo, la]) => window.__map.jumpTo({ center: [lo, la], zoom: 15 }), [lon, lat]);
  await page.waitForFunction(() => window.__map.loaded() && !window.__map.isMoving(), null, { timeout: 60_000 });
  const r = await page.evaluate(async ([lo, la]) => {
    const m = window.__map;
    const frames = [];
    let last = performance.now(), on = true;
    const tick = (t) => { frames.push(t - last); last = t; if (on) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
    const t0 = performance.now();
    // zoom out and in and pan: every zoom step crosses tile zooms (the cross-tile symbol matching runs then)
    const steps = [[14, 0, 0], [16.5, 0.004, 0.002], [14.5, -0.004, -0.002], [16, 0.002, -0.003], [14, 0, 0], [16.5, -0.003, 0.002]];
    for (const [z, dx, dy] of steps) {
      m.easeTo({ center: [lo + dx, la + dy], zoom: z, duration: 1200 });
      await new Promise((res) => m.once('moveend', res));
    }
    on = false;
    const ms = performance.now() - t0;
    const sorted = frames.slice(1).sort((a, b) => a - b);
    return { fps: (frames.length / ms) * 1000, p50: sorted[Math.floor(sorted.length / 2)], p95: sorted[Math.floor(sorted.length * 0.95)], worst: sorted.at(-1), ms };
  }, [lon, lat]);
  console.log(`${name.padEnd(36)} ${r.fps.toFixed(1).padStart(5)} fps   frame p50 ${r.p50.toFixed(0)} ms, p95 ${r.p95.toFixed(0)} ms, worst ${r.worst.toFixed(0)} ms`);
  return r;
};

// the planner's click / hover lookup (main.ts poiAt, the mousemove pointer): queryRenderedFeatures over a 20 px box on
// the POI layers – the app moved its own off the main thread (796c9b84 / dffa9914: 1447 ms waiting for MapLibre
// Native's render thread); GL JS has no render thread to wait for, so this is the whole cost
const lookup = async (z) => {
  await page.evaluate(([lo, la, z]) => window.__map.jumpTo({ center: [lo, la], zoom: z }), [lon, lat, z]);
  await page.waitForFunction(() => window.__map.loaded() && !window.__map.isMoving(), null, { timeout: 60_000 });
  return page.evaluate(() => {
    const m = window.__map, c = m.getContainer(), layers = m.getStyle().layers.filter((l) => l.id.startsWith('poi-')).map((l) => l.id);
    const t = [];
    for (let i = 0; i < 200; i++) {
      const x = Math.random() * c.clientWidth, y = Math.random() * c.clientHeight, s = performance.now();
      m.queryRenderedFeatures([[x - 10, y - 10], [x + 10, y + 10]], { layers });
      t.push(performance.now() - s);
    }
    t.sort((a, b) => a - b);
    return { p50: t[100], p99: t[198], worst: t[199] };
  });
};
for (const z of [14, 15.5, 17]) {
  const r = await lookup(z);
  console.log(`POI lookup at a click (20 px box) z${z}: p50 ${r.p50.toFixed(2)} ms, p99 ${r.p99.toFixed(2)} ms, worst ${r.worst.toFixed(2)} ms`);
}
for (let round = 1; round <= 2; round++) {
  console.log(`-- round ${round}`);
  await variant(false, false); await run('before: no density limit, no key text');
  await variant(false, true); await run('key text only');
  await variant(true, false); await run('density limit only');
  await variant(true, true); await run('after: density limit + key text');
}
await browser.close(); server.close();
