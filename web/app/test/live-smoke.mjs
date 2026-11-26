// The live tour's viewer page (web/app/live/, docs/design/live-tracking.md §3, §5.1) end to end: the built page in
// Chromium against the host's real live box (web/host/src/live.js) on a fake D1 (Node's SQLite), fed by a fake phone –
// the app's own shared code (com.onpath.app.live in the Wasm engine of an idle page: seal head / route / chunk) pushed
// from Node with the phone's write token. The server's clock is the test's (handleLive's `now`), so "vor 2 Min." and
// "kein Kontakt seit 25 Min." need no waiting.
// Guards: a wrong or missing key says so and asks nothing; the key leaves the address bar; each status line of §3 with
// its priority; the way so far and the plan are drawn; a gap is filled later as its own stretch (no straight jump); a
// stale chunk above the head's `chunks` is not drawn; the end and "no longer shared" (nothing of the tour stays); and
// the request budget of §5.1 – a watched hour ≈ 30 questions, a dead zone ≤ 13 in its first hour, none while hidden, none
// after the end.
// Needs no map data (the lines draw on an empty map). Usage: node test/live-smoke.mjs [segmentsDir – ignored] [cellStoreDir]
import path from 'node:path';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleLive } from '../../host/src/live.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const store = process.argv[3] && fs.existsSync(process.argv[3]) ? path.resolve(process.argv[3]) : null;
export const T0 = Date.UTC(2026, 10, 21, 9, 0, 0);
const b64 = (n) => crypto.randomBytes(n).toString('base64url');
const sha = (t) => crypto.createHash('sha256').update(t).digest('hex');

/** The server with its own clock (`clock.t`), the page server in front of it, and a browser. */
export async function setup({ dataDir = store, log = null } = {}) {
  const env = { SYNC: fakeD1(), MAP_KEY: 'k' };
  const clock = { t: T0 };
  const live = (r) => handleLive(r, env, clock.t);
  const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments: here, dataDir, live, log });
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  return { env, clock, live, server, url, browser };
}

/** The hike of the fixtures (a real recorded line near Meran) as [lat, lon, ele] points. */
export function hike() {
  const gpx = fs.readFileSync(path.join(here, 'fixtures/komoot-hike.gpx'), 'utf8');
  return [...gpx.matchAll(/<trkpt lat="([0-9.]+)" lon="([0-9.]+)">\s*<ele>([0-9.]+)<\/ele>/g)].map((m) => [Number(m[1]), Number(m[2]), Math.round(Number(m[3]))]);
}

/**
 * A fake phone: seals with the shared code in [idle]'s engine (an open live page), pushes to [live] with its own write
 * token. `push({route, points, head})` = one upload as the app's uploader makes it (route when given, the new points
 * as the next chunk, the head with `chunks` and `counter`).
 */
export async function phone(idle, live, url, clock) {
  const link = await idle.evaluate(([r, base]) => window.__live.engine.liveLink(r, base), [b64(32), url + 'live/']);
  const keys = await idle.evaluate((l) => JSON.parse(window.__live.engine.liveKeys(l)), link);
  const write = b64(32);
  const call = (method, p, body) => live(new Request('http://x/live/v1/' + keys.box + p, { method,
    headers: { authorization: 'Bearer ' + write, 'content-type': 'application/json' }, body: body == null ? undefined : JSON.stringify(body) }));
  let chunks = 0, counter = 0, seg = 0;
  const seal = (fn, ...args) => idle.evaluate(([f, a]) => window.__live.engine[f](...a), [fn, args]);
  return {
    link, keys, write,
    claim: async () => (await call('POST', '', { read: sha(keys.token) })).status,
    newSegment: () => { seg++; },
    /** [points] = [lat, lon, ele, timeMs]; [head] = the head's fields (state, distanceM, …) – fix and time default to the last point. */
    async push({ route = null, points = [], head = {}, ended = false, chunkAs = null }) {
      const items = [];
      if (route) items.push({ name: 'r', blob: await seal('liveSealRoute', link, route.map((p) => p[0] + ',' + p[1]).join(';'), b64(12)) });
      if (points.length) {
        const n = chunkAs ?? chunks++;
        const blob = await seal('liveSealChunk', link, n, points.map((p) => [seg, p[0], p[1], p[2] ?? '', p[3]].join(',')).join(';'), b64(12));
        if (!blob) throw new Error('chunk not sealed');
        items.push({ name: 't' + String(n).padStart(4, '0'), blob });
      }
      const last = points[points.length - 1];
      const f = { chunks, counter: counter++, ...(last ? { lat: last[0], lon: last[1], ele: last[2], time: last[3] } : {}), ...head };
      const fields = Object.entries(f).filter(([, v]) => v != null).map(([k, v]) => k + '=' + v).join('&');
      items.push({ name: 'h', blob: await seal('liveSealHead', link, fields, b64(12)) });
      const r = await call('POST', '/push', ended ? { items, ended: true } : { items });
      if (r.status !== 200) throw new Error('push ' + r.status + ' ' + await r.text());
      return (await r.json()).rev;
    },
    stop: async () => (await call('DELETE', '')).status,
    get chunks() { return chunks; },
  };
}

/** Opens the viewer page with [hash] (the link's "#t=…"), German, at phone width; waits for its engine. */
export async function openViewer(browser, url, hash, { lang = 'de', width = 390, height = 760, scale = 1, query = '' } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: scale, locale: lang === 'de' ? 'de-DE' : 'en-US', timezoneId: 'Europe/Berlin' });
  const page = await ctx.newPage();
  page.errors = [];
  page.on('pageerror', (e) => page.errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) page.errors.push(m.text()); });
  await page.goto(`${url}live/?lang=${lang}${query}${hash}`);
  await page.waitForFunction(() => window.__live?.engine, null, { timeout: 60_000 });
  return page;
}
export const lineOf = (P) => P.evaluate(() => ({ text: document.getElementById('line').textContent, tone: document.getElementById('status').dataset.tone,
  state: document.body.dataset.state, title: document.getElementById('title').textContent,
  facts: !document.getElementById('facts').hidden,
  togo: document.getElementById('facts').hidden || document.getElementById('togo').hidden ? null : document.getElementById('togoText').textContent,
  battery: document.getElementById('facts').hidden || document.getElementById('battery').hidden ? null : document.getElementById('batteryText').textContent,
  place: document.getElementById('facts').hidden || document.getElementById('place').hidden ? null : document.getElementById('placeText').textContent,
  coords: document.getElementById('coords').textContent || null,
  pill: document.getElementById('count').hidden ? null : document.getElementById('countText').textContent,
  geo: document.getElementById('geo').getAttribute('href'), map: !document.getElementById('mapbox').hidden,
  foot: document.getElementById('foot').textContent, note: document.getElementById('note').hidden ? null : document.getElementById('note').textContent }));
/** A question to the server right now, then the page drawn again. */
export const ask = async (P) => { await P.evaluate(() => window.__live.askNow()); await P.evaluate(() => window.__live.render()); };
const drawn = (P) => P.evaluate(() => window.__live.drawn);

if (import.meta.url === `file://${process.argv[1]}`) {
  const fails = [];
  const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
  const log = [];
  const { clock, live, server, url, browser } = await setup({ log });
  const asked = () => log.filter((u) => u.startsWith('/live/v1/')).length;
  const MIN = 60_000;
  const pts = hike();
  const at = (i, t) => [pts[i][0], pts[i][1], pts[i][2], t];

  // ---- 1. no key / a wrong key: one sentence, nothing asked ----
  const idle = await openViewer(browser, url, '');
  let l = await lineOf(idle);
  check(l.state === 'broken' && l.text === 'Dieser Link funktioniert nicht.' && !l.map && !l.facts, `no key in the link: "${l.text}", no map, no facts`);
  check(asked() === 0, 'no key: the server is not asked');
  const bad = await openViewer(browser, url, '#t=abc');
  check((await lineOf(bad)).state === 'broken' && asked() === 0, 'a damaged code: "Dieser Link funktioniert nicht.", the server is not asked');
  await bad.context().close();
  check(await idle.evaluate(() => document.title) === 'OnPath – geteilter Standort' && await idle.evaluate(() => document.querySelector('meta[name=robots]')?.content.includes('noindex')), 'neutral title, noindex');

  // ---- 2. a link before the phone got through: "noch keine Daten" ----
  const ph = await phone(idle, live, url, clock);
  const V = await openViewer(browser, url, '#' + ph.link.split('#')[1]);
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'no_data' && /Noch keine Daten/.test(l.text) && !l.facts, `before the first upload: "${l.text}"`);
  check(!(await V.evaluate(() => location.href)).includes('t='), 'the key is taken out of the address bar');
  check(l.foot === 'Kein Notruf-Dienst – im Notfall 112.', 'the fixed line: no emergency service');
  const viewOf = async (P) => { await P.waitForFunction(() => window.__map, null, { timeout: 30_000 }); return P.evaluate(() => { const m = window.__map, b = m.getBounds();
    return { view: document.getElementById('mapbox').dataset.view ?? null, hiddenMap: getComputedStyle(document.getElementById('map')).visibility === 'hidden',
      wait: getComputedStyle(document.getElementById('mapWait')).display !== 'none' ? document.getElementById('mapWait').textContent : null,
      w: b.getWest(), e: b.getEast(), s: b.getSouth(), n: b.getNorth() }; }); };
  const inView = (v, lat, lon) => lat >= v.s && lat <= v.n && lon >= v.w && lon <= v.e;
  let v = await viewOf(V);
  check(v.view === null && v.hiddenMap && v.wait === 'Warte auf den ersten Standort …', `before the first upload: no fixed place on the map, "${v.wait}"`);

  // ---- 2b. a tour just started (field report 2026-11-26): the plan is there, no fix yet – the plan in view, then the
  //      first place; never a far-away start view (the page used to start at a fixed place until the first fix) ----
  {
    const far = hike().map((p) => [p[0] + 4.7, p[1] - 4.4]); // the fixture's line moved near Düsseldorf
    const ph3 = await phone(idle, live, url, clock);
    await ph3.claim();
    const S = await openViewer(browser, url, '#' + ph3.link.split('#')[1]);
    await ph3.push({ route: far, head: { state: 'recording', distanceM: 0, ascentM: 0, movingS: 0, startedMs: T0, activity: 'HIKE', nextS: 120 } });
    await ask(S);
    await S.waitForFunction(() => window.__live.drawn?.route > 0, null, { timeout: 30_000 }).catch(() => {});
    v = await viewOf(S);
    check(v.view === 'route' && !v.hiddenMap && v.wait === null && inView(v, far[0][0], far[0][1]) && inView(v, far[far.length - 1][0], far[far.length - 1][1]),
      `no fix yet: the planned route is in view (${JSON.stringify(v)})`);
    const first = [far[3][0], far[3][1], 40, T0 + 60_000];
    await ph3.push({ points: [first], head: { state: 'recording', distanceM: 50, ascentM: 0, movingS: 60, startedMs: T0, activity: 'HIKE', nextS: 120 } });
    await ask(S);
    v = await viewOf(S);
    check(v.view === 'place' && inView(v, first[0], first[1]), `the first fix: its place is in view (${JSON.stringify(v)})`);
    check(S.errors.length === 0, `no page errors (${S.errors.join(' | ')})`);
    await S.context().close();
  }

  // ---- 3. on the way: plan, way so far, numbers ----
  check(await ph.claim() === 201, 'the phone claims the box');
  const route = pts.map((p) => [p[0], p[1]]);
  await ph.push({ route, points: pts.slice(0, 30).map((p, i) => [...p, T0 - 40 * MIN + i * 60_000]),
    head: { state: 'recording', distanceM: 5400, ascentM: 310, movingS: 4500, startedMs: T0 - 90 * MIN, toGoM: 6200, arrivalMs: T0 + 100 * MIN, batteryPct: 43,
      activity: 'HIKE', name: 'Christian', nextS: 120 } });
  clock.t = T0 + 2 * MIN + 5_000;
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'moving' && l.text === 'Alles okay, unterwegs' && l.tone === 'good', `moving: "${l.text}"`);
  check(l.title === 'Christian' && await V.evaluate(() => document.getElementById('initial').textContent) === 'C', `the name from the sealed data: "${l.title}", its initial`);
  check(/^ca\. \d\d:\d\d · noch 6,2 km$/.test(l.togo ?? ''), `arrival and to go: "${l.togo}"`);
  check(l.battery === '43 %', `battery: "${l.battery}"`);
  check(l.place === 'vor 2 Min.' && /^46\.\d{5}, 11\.\d{5} · Position von \d\d:\d\d$/.test(l.coords ?? '') && /^geo:46\.\d+,11\.\d+/.test(l.geo ?? ''), `last report "${l.place}", the coordinates as text – the place 11 min older than the upload says its own time "${l.coords}", geo link`);
  await V.waitForFunction(() => window.__live.drawn?.route > 0, null, { timeout: 30_000 }).catch(() => {});
  let d = await drawn(V);
  check(d?.route === route.length && d.way.length === 1 && d.way[0] === 30, `the plan (${d?.route} points) and the way so far (${JSON.stringify(d?.way)}) are on the map`);
  check(await V.evaluate(() => !!document.querySelector('.livedot')), 'the last place has its dot');

  // ---- 3b. the countdown strip: from the head's nextS (120 s) and the time since the upload, no extra question ----
  const countOf = (P) => P.evaluate(() => ({ hidden: document.getElementById('count').hidden, text: document.getElementById('countText').textContent,
    overdue: document.getElementById('count').dataset.overdue === '1', tone: document.getElementById('status').dataset.tone,
    top: document.getElementById('count').getBoundingClientRect().top, mapTop: document.getElementById('mapbox').getBoundingClientRect().top }));
  clock.t = T0 + 30_000;
  await ask(V);
  let c = await countOf(V);
  const m1 = /^Live · nächster Punkt in 1:(\d\d)$/.exec(c.text);
  check(!c.hidden && !!m1 && !c.overdue && c.tone === 'good' && c.top >= c.mapTop && c.top < c.mapTop + 40, `countdown after a report: "${c.text}", a pill at the top of the map`);
  const asks0 = asked();
  await V.waitForFunction((s) => { const m = /1:(\d\d)$/.exec(document.getElementById('countText').textContent); return m && Number(m[1]) <= s; }, Number(m1?.[1] ?? 59) - 2, { timeout: 10_000 }).catch(() => {});
  c = await countOf(V);
  check(Number(/1:(\d\d)$/.exec(c.text)?.[1] ?? 99) <= Number(m1?.[1] ?? 0) - 2 && asked() === asks0, `the countdown counts down by itself ("${c.text}"), without asking the server`);
  clock.t = T0 + 2 * MIN + 5_000; // 5 s past the planned moment: the viewer asks on, the pill says "gleich"
  await ask(V);
  c = await countOf(V);
  check(!c.overdue && c.text === 'Live · nächster Punkt gleich …' && c.tone === 'good', `at 0: "${c.text}", no alarm`);
  clock.t = T0 + 2 * MIN + 30_000; // 30 s past the planned moment
  await ask(V);
  c = await countOf(V);
  l = await lineOf(V);
  check(c.overdue && c.text === 'wartet auf das Handy …' && c.tone === 'amber' && l.state === 'moving', `overdue: "${c.text}", status line amber`);
  clock.t = T0 + 2 * MIN + 5_000;

  // ---- 4. a break ----
  clock.t = T0 + 12 * MIN;
  await ph.push({ head: { state: 'recording', stillS: 420, distanceM: 5400, ascentM: 310, movingS: 4500, lat: pts[29][0], lon: pts[29][1], time: T0 + 5 * MIN, batteryPct: 42, activity: 'HIKE', name: 'Christian', nextS: 600 } });
  clock.t = T0 + 17 * MIN;
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'pause' && l.text === 'Macht gerade Pause' && l.tone === 'calm', `a break: "${l.text}"`);

  // ---- 5. off the planned route (the app's alarm) ----
  clock.t = T0 + 18 * MIN;
  await ph.push({ points: [at(30, T0 + 17 * MIN), at(31, T0 + 18 * MIN)], head: { state: 'recording', offRouteM: 150, distanceM: 5600, ascentM: 320, movingS: 4700, toGoM: 6000, arrivalMs: T0 + 110 * MIN, batteryPct: 41, activity: 'HIKE', name: 'Christian', nextS: 120 } });
  clock.t = T0 + 18 * MIN + 30_000;
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'off_route' && l.text === 'Gerade etwas abseits der Route' && l.tone === 'amber', `off the route: "${l.text}"`);

  // ---- 6. a dead zone: no contact, the last place stays; then the whole stretch arrives as its own line ----
  clock.t = T0 + 43 * MIN;
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'no_contact' && l.text === 'Seit 25 Min. keine Meldung' && l.tone === 'grey', `no contact: "${l.text}" (the fact, no guess)`);
  check(/^Letzte Meldung \d\d:\d\d$/.test(l.pill ?? ''), `no contact: the pill says when ("${l.pill}")`);
  check(l.place === 'vor 25 Min.' && l.coords != null && l.battery === '41 %', `no contact: the last place stays, the battery without a second time ("${l.battery}")`);
  check(/^46\.\d{5}, 11\.\d{5}$/.test(l.coords ?? ''), `no contact: one moment – only the pill says a clock time, the place just its coordinates ("${l.pill}" / "${l.coords}")`);
  clock.t = T0 + 85 * MIN;
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'no_contact' && l.text === 'Seit 1 h 07 min keine Meldung' && l.tone === 'amber', `after an hour amber: "${l.text}"`);
  ph.newSegment(); // the GPS was lost in between: the app starts a new segment
  await ph.push({ points: pts.slice(40, 70).map((p, i) => [...p, T0 + 50 * MIN + i * 60_000]), head: { state: 'recording', distanceM: 8100, ascentM: 520, movingS: 7900, toGoM: 3500, arrivalMs: T0 + 150 * MIN, batteryPct: 33, activity: 'HIKE', name: 'Christian', nextS: 120 } });
  clock.t += 30_000;
  await ask(V);
  l = await lineOf(V);
  d = await drawn(V);
  check(l.state === 'moving' && d.way.length === 2 && d.way[0] === 32 && d.way[1] === 30, `back in the network: the stretch arrives (${JSON.stringify(d.way)}) as its own line – no straight jump across the gap`);

  // ---- 7. a stale chunk above the head's count is not drawn ----
  await ph.push({ points: [[46.5, 11.0, 300, T0], [46.51, 11.01, 300, T0 + 1000]], chunkAs: ph.chunks + 5, head: { state: 'recording', distanceM: 8100, ascentM: 520, movingS: 7900, lat: pts[69][0], lon: pts[69][1], time: T0 + 80 * MIN, activity: 'HIKE', name: 'Christian', nextS: 120 } });
  await ask(V);
  d = await drawn(V);
  check(d.way.length === 2 && d.way[0] + d.way[1] === 62, `a stale chunk above head.chunks is not drawn (${JSON.stringify(d.way)})`);

  // ---- 7b. later than planned (the phone's plannedArrivalMs; without it – step 3 – never) ----
  await ph.push({ head: { state: 'recording', distanceM: 8200, ascentM: 520, movingS: 8000, lat: pts[69][0], lon: pts[69][1], time: clock.t,
    toGoM: 3400, arrivalMs: clock.t + 100 * MIN, plannedArrivalMs: clock.t + 60 * MIN, batteryPct: 30, activity: 'HIKE', name: 'Christian', nextS: 120 } });
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'late' && /^Etwas später als geplant · Ankunft ca\. \d\d:\d\d$/.test(l.text) && l.tone === 'amber', `later than planned: "${l.text}"`);
  await ph.push({ head: { state: 'recording', distanceM: 8200, ascentM: 520, movingS: 8000, lat: pts[69][0], lon: pts[69][1], time: clock.t,
    toGoM: 3400, arrivalMs: clock.t + 100 * MIN, batteryPct: 30, activity: 'HIKE', name: 'Christian', nextS: 120 } });
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'moving', `the same without a planned arrival (an older app): "${l.text}"`);

  // ---- 8. the end ----
  clock.t = T0 + 130 * MIN;
  await ph.push({ points: pts.slice(70).map((p, i) => [...p, T0 + 90 * MIN + i * 60_000]), ended: true,
    head: { state: 'ended', arrived: true, distanceM: 11600, ascentM: 640, movingS: 11400, batteryPct: 28, activity: 'HIKE', name: 'Christian', nextS: 120 } });
  const before = asked();
  await ask(V);
  l = await lineOf(V);
  check(l.state === 'ended' && /^Angekommen · \d\d:\d\d$/.test(l.text) && l.togo === null && l.pill === null, `the end: "${l.text}", no "to go", no countdown any more`);
  check((await drawn(V)).way.reduce((a, b) => a + b, 0) === pts.length - 8, 'the end: the whole way is there');
  await ask(V); await ask(V);
  check(asked() === before + 1 && await V.evaluate(() => window.__live.planned) === -1, 'after the end the server is never asked again');

  // ---- 9. the request budget of §5.1 (a page with its own clock: an hour in a moment) ----
  {
    const ph2 = await phone(idle, live, url, clock);
    await ph2.claim();
    const ctx = await browser.newContext({ viewport: { width: 390, height: 760 }, locale: 'de-DE' });
    const W = await ctx.newPage();
    await W.clock.install({ time: new Date(clock.t) });
    const start = clock.t;
    let i = 0;
    const up = () => ph2.push({ points: [at(i % pts.length, clock.t)], head: { state: 'recording', distanceM: 100 * ++i, activity: 'HIKE', nextS: 120 } });
    await up();
    const n0 = asked();
    await W.goto(`${url}live/?lang=de#${ph2.link.split('#')[1]}`);
    for (let k = 0; k < 400 && !(await W.evaluate(() => window.__live?.firstLineMs > 0).catch(() => false)); k++) { await W.clock.runFor(50); await new Promise((r) => setTimeout(r, 25)); }
    const settle = () => new Promise((r) => setTimeout(r, 12));
    const run = async (ms, phoneOn) => {
      for (let s = 0; s < ms; s += 5_000) {
        clock.t += 5_000;
        if (phoneOn && (clock.t - start) % (120_000) === 0) await up();
        await W.clock.runFor(5_000);
        await settle();
      }
    };
    await run(60 * MIN, true);
    const hour = asked() - n0;
    check(hour >= 29 && hour <= 33, `a watched hour on the way: ${hour} questions (the phone uploaded 30 times; §5.1: ≤ ~30 an hour)`);
    const n1 = asked();
    await run(60 * MIN, false);
    const dead1 = asked() - n1;
    check(dead1 >= 9 && dead1 <= 13, `a dead zone, first hour: ${dead1} questions (4 quick ones at 0, then 1, 2, 5, then every 10 min)`);
    const n2 = asked();
    await run(60 * MIN, false);
    check(asked() - n2 >= 5 && asked() - n2 <= 7, `a dead zone, every further hour: ${asked() - n2} questions`);
    check((await lineOf(W)).state === 'no_contact', 'the page says "kein Kontakt" meanwhile');
    // hidden: nothing; back: at once
    await W.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange')); });
    const n3 = asked();
    await run(30 * MIN, true);
    check(asked() === n3, 'a hidden tab asks nothing');
    await W.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange')); });
    await W.clock.runFor(100); await settle(); await settle();
    check(asked() === n3 + 1 && (await lineOf(W)).state === 'moving', 'the tab comes back: one question at once, the line is fresh');
    check((await lineOf(W)).title === 'Geteilter Standort', `no name given: the heading is "${(await lineOf(W)).title}"`);
    // ---- 10. stop sharing: nothing of the tour stays ----
    check(await ph2.stop() === 200, 'the phone stops sharing');
    await run(3 * MIN, false);
    l = await lineOf(W);
    check(l.state === 'stopped' && l.text === 'Diese Tour wird nicht mehr geteilt.' && !l.map && !l.facts && l.place === null && l.coords === null, `stopped: "${l.text}", no map, no facts, no place`);
    const n4 = asked();
    await run(30 * MIN, false);
    check(asked() === n4, 'a stopped tour is never asked again');
    await ctx.close();
  }

  // ---- 11. the first page: phone width, nothing cut, no errors ----
  const over = await V.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  check(!over, 'phone width (390 px): nothing wider than the screen');
  check(V.errors.length === 0 && idle.errors.length === 0, 'no page errors' + (V.errors.length ? ': ' + V.errors.join(' | ') : ''));

  await browser.close(); server.close();
  console.log(fails.length ? `FAILED: ${fails.length}\n- ` + fails.join('\n- ') : 'all ok');
  process.exit(fails.length ? 1 : 0);
}
