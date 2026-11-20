// What the live tour's viewer page costs to open (docs/design/live-tracking.md §14.5 "slim engine?" is decided by this):
// the files it pulls (raw and gzip) and the time from the page's start to the first status line with data, in headless
// Chromium against the local test server (no network delay – add the download time of the gzip size on a phone line).
// Usage: node test/live-load.mjs [cellStoreDir]
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setup, phone, openViewer, hike, T0 } from './live-smoke.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const store = process.argv[2] && fs.existsSync(process.argv[2]) ? path.resolve(process.argv[2]) : null;
const log = [];
const { clock, live, server, url, browser } = await setup({ dataDir: store, log });
const idle = await openViewer(browser, url, '');
const ph = await phone(idle, live, url, clock);
await ph.claim();
const pts = hike();
await ph.push({ route: pts.map((p) => [p[0], p[1]]), points: pts.slice(0, 48).map((p, i) => [...p, T0 + i * 1000]), head: { state: 'recording', distanceM: 1100, activity: 'HIKE', nextS: 120 } });
const times = [];
for (let i = 0; i < 5; i++) {
  log.length = 0;
  const V = await openViewer(browser, url, '#' + ph.link.split('#')[1]);
  await V.waitForFunction(() => window.__live.firstLineMs > 0, null, { timeout: 60_000 });
  times.push(Math.round(await V.evaluate(() => window.__live.firstLineMs)));
  if (i === 0) {
    await V.waitForTimeout(3000);
    const dist = path.resolve(here, '../dist');
    let raw = 0, gz = 0;
    const rows = [];
    for (const u of new Set(log.map((x) => x.split('?')[0]))) {
      if (u.startsWith('/live/v1/') || u.startsWith('/data/') || u.startsWith('/overview/')) continue; // the box and the map's tiles
      const f = path.join(dist, u.endsWith('/') ? u + 'index.html' : u);
      if (!fs.existsSync(f)) continue;
      const b = fs.readFileSync(f), g = zlib.gzipSync(b).length;
      raw += b.length; gz += g; rows.push([u, b.length, g]);
    }
    rows.sort((a, b) => b[2] - a[2]);
    console.log('file  bytes  gzip\n' + rows.slice(0, 8).map((r) => r.join('  ')).join('\n'));
    console.log(`page files (without map tiles): ${rows.length} files, ${raw} bytes, ${gz} gzip; all requests of the first 3 s: ${log.length}`);
  }
  await V.context().close();
}
console.log('first status line with data, ms after the page script started (5 loads): ' + times.join(', '));
await browser.close(); server.close();
process.exit(0);
