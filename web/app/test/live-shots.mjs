// Pictures of the live tour's viewer page for the owner (docs/design/live-tracking.md §3 – "sketch before build"):
// the built page at phone width, German, fed by live-smoke.mjs's fake phone with the fixtures' hike near Meran.
// With a cell store (and the overview files in the build) the map is the real one; without, the lines lie on an empty map.
// Usage: node test/live-shots.mjs <outDir> [cellStoreDir]
import path from 'node:path';
import fs from 'node:fs';
import { setup, phone, openViewer, hike, ask, lineOf, T0 } from './live-smoke.mjs';

const out = path.resolve(process.argv[2] ?? 'live-shots');
const store = process.argv[3] && fs.existsSync(process.argv[3]) ? path.resolve(process.argv[3]) : null;
fs.mkdirSync(out, { recursive: true });
const { clock, live, server, url, browser } = await setup({ dataDir: store });
const MIN = 60_000;
const pts = hike();
const route = pts.map((p) => [p[0], p[1]]);
const idle = await openViewer(browser, url, '');
const base = { activity: 'HIKE', name: 'Christian', nextS: 120 };

/** One tour state: a fresh box, [feed] uploads, the page opened [after] ms later, one picture. */
async function shot(name, feed, after) {
  clock.t = T0;
  const ph = await phone(idle, live, url, clock);
  await ph.claim();
  const stop = (await feed(ph)) === true;
  clock.t += after;
  const V = await openViewer(browser, url, '#' + ph.link.split('#')[1], { scale: 2 });
  await V.waitForFunction(() => window.__live.firstLineMs > 0, null, { timeout: 60_000 });
  if (stop) { await ph.stop(); await ask(V); }
  else {
    await V.waitForFunction(() => window.__live.drawn?.way.length > 0, null, { timeout: 60_000 }).catch(() => {});
    await V.waitForFunction(() => window.__map.loaded() && window.__map.areTilesLoaded(), null, { timeout: 45_000 }).catch(() => {});
    await V.waitForTimeout(1500);
  }
  await V.evaluate(() => window.__live.render());
  await V.screenshot({ path: path.join(out, name + '.png') });
  console.log(name, JSON.stringify((await lineOf(V)).text));
  await V.context().close();
}
const walked = (n, t0) => pts.slice(0, n).map((p, i) => [...p, t0 + i * 90_000]);

await shot('1-unterwegs', (ph) => ph.push({ route, points: walked(48, T0 - 72 * MIN),
  head: { ...base, state: 'recording', distanceM: 1100, ascentM: 85, movingS: 2100, toGoM: 1300, arrivalMs: T0 + 45 * MIN, batteryPct: 43 } }), 2 * MIN + 5000);
await shot('2-pause', (ph) => ph.push({ route, points: walked(60, T0 - 100 * MIN),
  head: { ...base, state: 'recording', stillS: 600, nextS: 600, distanceM: 1400, ascentM: 110, movingS: 2600, toGoM: 1000, arrivalMs: T0 + 50 * MIN, batteryPct: 39 } }), 2 * MIN);
await shot('3-abseits', (ph) => { // the way leaves the plan after point 40 (made up: the real hike stays on it)
  const off = pts.slice(40, 52).map((p, i) => [p[0] - 0.00012 * (i + 1), p[1] + 0.00022 * (i + 1), p[2], T0 - 12 * MIN + i * 60_000]);
  return ph.push({ route, points: [...walked(40, T0 - 75 * MIN), ...off],
    head: { ...base, state: 'recording', offRouteM: 150, distanceM: 950, ascentM: 70, movingS: 1800, toGoM: 1500, arrivalMs: T0 + 55 * MIN, batteryPct: 47 } });
}, 1 * MIN);
await shot('4-kein-kontakt', (ph) => ph.push({ route, points: walked(55, T0 - 85 * MIN),
  head: { ...base, state: 'recording', distanceM: 1250, ascentM: 100, movingS: 2400, toGoM: 1150, arrivalMs: T0 + 40 * MIN, batteryPct: 36 } }), 25 * MIN + 5000);
await shot('5-beendet', (ph) => ph.push({ route, points: pts.map((p, i) => [...p, T0 - 190 * MIN + i * 100_000]), ended: true,
  head: { ...base, state: 'ended', arrived: true, distanceM: 2400, ascentM: 190, movingS: 4500, batteryPct: 28 } }), 40 * MIN);
await shot('6-nicht-mehr-geteilt', async (ph) => { await ph.push({ route, points: walked(20, T0 - 30 * MIN), head: { ...base, state: 'recording', distanceM: 2000 } }); return true; }, 1 * MIN);

await browser.close(); server.close();
process.exit(0);
