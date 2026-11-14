// Meine Touren like the app's My routes of 2026-11-13 (owner: „Die Favoriten sind wirklich wichtig“; app 21c0eac1 L1 +
// favourites C, 725b68e9 / 6ba34792 map pictures, 21dcc5f5 / de1afd21 activity filter, 870bff05 „↻ Rundtour“, 82167d11
// „opens the same way every time“): the chips „☆ Favoriten“ · activity ▾ · the sort; the activity preset from the planning
// activity, exactly that activity, „Alle“ at the bottom of its menu, every choice filters at once; Zuletzt shows whatever
// the filters; the star per saved row (one tap, stored in the sync ledger as field 9, „No favorites yet“ with the pill on);
// the activity line („Hike · T3“, „· Round trip“, ", Round trip" in the row's label); the map pictures (made off screen
// without route networks, the whole route with start and goal, kept in memory and IndexedDB, the drawn line until then, a
// row without a line keeps the kind's sign); each opening: Newest first and the planning activity, the pill kept; the
// German words. Writes the screenshots (desktop + Pixel 7, DE) when [shotDir] is given.
// Usage: node test/routes-smoke.mjs [segmentsDir] [cellStoreDir] [shotDir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';
import { handleSync } from '../../host/src/sync.js';
import { fakeD1 } from '../../host/test/fake-d1.js';

const A = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau (PlanCodeTest)
const here = path.dirname(fileURLToPath(import.meta.url));
const segArg = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const segments = fs.existsSync(segArg) ? segArg : here;
const cellArg = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const dataDir = fs.existsSync(cellArg) ? cellArg : null;
const shotDir = process.argv[4] ?? null;
if (shotDir) fs.mkdirSync(shotDir, { recursive: true });
const env = { SYNC: fakeD1() };
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir, sync: (r) => handleSync(r, env) });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

async function open(ctx, q = '') {
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url + 'index.html' + q);
  await page.waitForFunction(() => window.__sync?.status && document.body.dataset.engine === 'ready', null, { timeout: 120_000 }).catch(() => {});
  await page.waitForFunction(() => document.getElementById('syncBadge')?.dataset.ready === '1', null, { timeout: 60_000 });
  return page;
}
const openList = async (P) => { await P.click('#menuPlans'); await P.waitForSelector('dialog.plans[open] #planList li', { timeout: 60_000 }); await P.waitForTimeout(200); };
const closeList = (P) => P.click('#closePlans');
const names = (P) => P.$$eval('#planList li[data-id]:not([data-zuletzt]) .name', (els) => els.map((e) => e.textContent));
const chipRow = (P) => P.$$eval('#planSort .sortchip', (els) => els.map((e) => ({ cls: e.className.replace('sortchip', '').trim(), text: e.textContent.trim(),
  on: e.classList.contains('on'), label: e.getAttribute('aria-label') })));
const rowOf = (P, name) => P.evaluate((n) => {
  const li = [...document.querySelectorAll('#planList li[data-id]:not([data-zuletzt])')].find((l) => l.querySelector('.name')?.textContent === n);
  if (!li) return null;
  const star = li.querySelector('.favstar');
  return { act: [...li.querySelectorAll('.actline > span')].map((s) => s.textContent.trim()).join(' '), label: li.querySelector('.open').getAttribute('aria-label'),
    star: star ? star.getAttribute('aria-pressed') : null, starLabel: star?.getAttribute('aria-label') ?? '', thumb: li.dataset.thumb ?? null,
    img: !!li.querySelector('.tileface img.thumb'), svg: !!li.querySelector('.tileface svg.routeshape'), kind: !!li.querySelector('.tileface .kindic'),
    starBelowMenu: (() => { const m = li.querySelector('.more'); return !!(m && star && star.getBoundingClientRect().top >= m.getBoundingClientRect().bottom - 1); })(),
    tileW: li.querySelector('.tile').getBoundingClientRect().width, chev: !!li.querySelector('.chev') };
}, name);
/** Waits until the list was drawn again after [before] (plans.ts counts its renders) – a busy machine takes its time. */
const renders = (P) => P.evaluate(() => Number(document.getElementById('planList')?.dataset.renders ?? 0));
const drawnAfter = (P, before) => P.waitForFunction((b) => Number(document.getElementById('planList')?.dataset.renders ?? 0) > b, before, { timeout: 30_000 }).catch(() => {});
const clickDrawn = async (P, sel) => { const before = await renders(P); await P.click(sel); await drawnAfter(P, before); };
const pickAct = async (P, key) => {
  const before = await renders(P);
  await P.click('#planSort .actchip');
  await P.waitForSelector('#actFilterMenu:not([hidden])');
  // the swiftshader GL of the off-screen picture map can starve the page's frames for seconds – Playwright's „stable“ check
  // then never passes; the item is there and visible (checked above), so the click goes straight to it
  const t0 = Date.now();
  await P.click(`#actFilterMenu .actpick[data-act="${key}"]`, { timeout: 5_000 }).catch(async () => {
    console.log(`  (the menu item was not "stable" for ${Date.now() - t0} ms – clicked directly)`);
    await P.click(`#actFilterMenu .actpick[data-act="${key}"]`, { force: true });
  });
  await drawnAfter(P, before);
};

// --- five tours: a hike, an MTB round trip, a bike tour, a hike this browser never routed (no line), a hiking track
const loop = (la, lo, r, n = 40) => Array.from({ length: n + 1 }, (_, i) => { const a = (i / n) * 2 * Math.PI; return [la + r * Math.sin(a), lo + r * 1.4 * (1 - Math.cos(a))]; });
async function seed(P) {
  return P.evaluate(async ({ A, loopMtb, loopTrack }) => {
    const s = window.__sync, st = window.__planStats, e = window.__engine, wait = () => new Promise((r) => setTimeout(r, 20));
    const enc = (points, activity, sac, title) => e.encode({ points, activity, sac, pinned: [], names: {}, title });
    const line = (pts) => [pts];
    await s.save(A, 'Waalweg'); st.keepStats(st.planKey(A), { m: 8100, up: 210, down: 30, s: 7560 });
    st.keepShape(st.planKey(A), line([[46.6713, 11.1594], [46.675, 11.155], [46.68, 11.145], [46.685, 11.14], [46.69, 11.13]])); await wait();
    const R = await enc([loopMtb[0], loopMtb[10], loopMtb[25], loopMtb[0]], 'MTB', 2, null);
    await s.save(R, 'Vigiljoch MTB-Runde'); st.keepStats(st.planKey(R), { m: 22400, up: 980, down: 980, s: 12240 }); st.keepShape(st.planKey(R), line(loopMtb)); await wait();
    const B = await enc([[46.6713, 11.1594], [46.55, 11.27], [46.4983, 11.3548]], 'BIKE_TOURING', 2, null);
    await s.save(B, 'Etschradweg Meran – Bozen'); st.keepStats(st.planKey(B), { m: 31500, up: 120, down: 300, s: 7200 });
    st.keepShape(st.planKey(B), line([[46.6713, 11.1594], [46.62, 11.2], [46.55, 11.27], [46.4983, 11.3548]])); await wait();
    const N = await enc([[46.6713, 11.1594], [46.6826, 11.2186]], 'HIKE', 4, null);
    await s.save(N, 'Ifinger Steig'); await wait(); // never routed here: no line, no picture – the kind's sign
    const id = await s.saveTrack([loopTrack.map(([la, lo]) => [la, lo, 400])], [], 'HIKE', 2, 'Runde ab Algund');
    st.keepStats(st.trackKey(id), { m: 6000, up: 300, down: 300, s: 7000 }); st.keepShape(st.trackKey(id), line(loopTrack));
    return { R, B, N };
  }, { A, loopMtb: loop(46.65, 11.08, 0.03), loopTrack: loop(46.677, 11.13, 0.012) });
}

// ===== English, desktop, the planning activity Hike =====
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'en-GB' });
await ctx.addInitScript(() => { window.__thumbsOn = true; }); // automated browsers make map pictures only when asked (routethumbs.ts)
await ctx.addInitScript(() => { try { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('activity', 'HIKE'); localStorage.setItem('sac', '3'); sessionStorage.setItem('seeded', '1'); } } catch { /* expected: none */ } });
let P = await open(ctx, '?at=11.16,46.67,12');
await seed(P);
await openList(P);
// 1. the chips: ☆ Favorites, the activity (preset: the planning activity Hike), then the sort, Newest first
let c = await chipRow(P);
check(same(c.map((x) => x.cls.replace(/\bon\b/, '').trim()), ['favchip', 'actchip', '', '', '', '', '']) && c[0].text === 'Favorites' && !c[0].on && c[1].text === 'Hike' && c[1].on && c[2].on,
  'the chips: ☆ Favorites (off), the activity preset with the planning activity (Hike), Newest first – ' + c.map((x) => x.text).join(' · '));
check(c[0].label === 'Favorites: show only favorites' && c[1].label === 'Activity: Hike. Tap to change', 'the app\'s spoken texts (' + c[0].label + ' / ' + c[1].label + ')');
// 2. exactly the activity: the hikes and the hiking track, newest first
check(same(await names(P), ['Runde ab Algund', 'Ifinger Steig', 'Waalweg']), 'Hike: only the hikes (the track too) – ' + (await names(P)).join(', '));
let r = await rowOf(P, 'Waalweg');
check(r.act === 'Hike · T3' && r.label === 'Open Waalweg' && r.star === 'false' && r.starLabel === 'Add Waalweg to favorites' && r.starBelowMenu && !r.chev && r.tileW === 84,
  `the row (L1): "Hike · T3", the star under ⋮, an 84 px tile, no › (${JSON.stringify(r)})`);
r = await rowOf(P, 'Runde ab Algund');
check(r.act === 'Hike · T2 · Round trip' && r.label === 'Open Runde ab Algund, Round trip', `a round track: "· ↻ Round trip" (a guess from its line), said in the label too (${JSON.stringify(r)})`);
check((await rowOf(P, 'Ifinger Steig')).act === 'Hike · T4', 'a hike never routed here: its activity from the plan code');
// 3. the menu: the six, a line, All at the bottom; every choice filters at once
await P.click('#planSort .actchip');
const menu = await P.$$eval('#actFilterMenu > *', (els) => els.map((e) => e.tagName === 'HR' ? '—' : `${e.querySelector('.alabel').textContent}${e.getAttribute('aria-selected') === 'true' ? '●' : ''}`));
check(same(menu, ['Hike●', 'Bike', 'Gravelbike', 'E-bike', 'MTB', 'E-MTB', '—', 'All']), 'the menu: the six (Hike chosen), a line, All at the bottom – ' + menu.join(' '));
await P.keyboard.press('Escape');
await pickAct(P, 'MTB');
check(same(await names(P), ['Vigiljoch MTB-Runde']) && (await chipRow(P))[1].text === 'MTB', 'MTB at once: only the MTB tour');
r = await rowOf(P, 'Vigiljoch MTB-Runde');
check(r.act === 'MTB · Round trip', `a planned round trip (its points end at the start – the plan sheet's rule): "MTB · Round trip" (${r.act})`);
await pickAct(P, 'BIKE_TOURING');
check(same(await names(P), ['Etschradweg Meran – Bozen']), 'Bike at once: only the bike tour (exactly Bike)');
await pickAct(P, 'EBIKE');
check((await P.textContent('#planList')).trim() === 'No E-bike tours here.', 'E-bike: the app\'s empty text – ' + (await P.textContent('#planList')).trim());
await pickAct(P, 'ALL');
c = await chipRow(P);
check(c[1].text === 'All' && !c[1].on && (await names(P)).length === 5, 'All: every tour, the chip outlined');

// 4. the star: one tap, stored as the sync's field 9 (an edit to send with Save); the pill shows only the starred ones
await clickDrawn(P, '#planList li[data-id]:has(.name:text-is("Waalweg")) .favstar');
r = await rowOf(P, 'Waalweg');
const stored = await P.evaluate(async () => (await window.__sync.list()).find((p) => p.title === 'Waalweg'));
check(r.star === 'true' && r.starLabel === 'Waalweg is a favorite. Tap to remove.' && stored.favourite === true && stored.dirty === true, `☆ → ★: at once, stored (favourite ${stored.favourite}, to send ${stored.dirty})`);
check((await P.evaluate(async () => (await window.__sync.list()).length)) === 5, 'a star makes no copy');
await clickDrawn(P, '#planSort .favchip');
c = await chipRow(P);
check(c[0].on && c[0].label === 'Only favorites shown. Tap to show all.' && same(await names(P), ['Waalweg']), 'Favorites on: only the starred one');
await clickDrawn(P, '#planList li[data-id]:has(.name:text-is("Waalweg")) .favstar');
check((await P.textContent('#planList')).trim() === 'No favorites yet. Tap ☆ on a route.', 'the star off with the pill on: the app\'s empty text');
await clickDrawn(P, '#planSort .favchip');
await clickDrawn(P, '#planList li[data-id]:has(.name:text-is("Vigiljoch MTB-Runde")) .favstar');

// 4b. ⋮ on the last row, below the fold (field: sync-smoke 2026-11-14 – the taller L1 rows): the list scrolls it into view
// first, the scroll's event comes after the tap – the menu must stay open; a real scroll afterwards closes it
{
  await P.evaluate(() => { document.getElementById('planList').scrollTop = 0; });
  const lastName = (await names(P)).at(-1);
  const below = await P.evaluate((n) => { const ul = document.getElementById('planList'), li = [...ul.querySelectorAll('li[data-id]')].find((l) => l.querySelector('.name').textContent === n);
    return li.querySelector('.more').getBoundingClientRect().bottom > ul.getBoundingClientRect().bottom; }, lastName);
  await P.click(`#planList li[data-id]:has(.name:text-is("${lastName}")) .more`);
  const open = await P.waitForSelector(`#planList li[data-id]:has(.name:text-is("${lastName}")) .rowmenu:not([hidden])`, { timeout: 3_000 }).then(() => true).catch(() => false);
  check(below && open, `⋮ on the last row below the fold (${lastName}): scrolled into view, the menu stays open (below ${below}, open ${open})`);
  // the sync-smoke case: a render redraws the list (its scroll goes to 0 and back in one task) right before the tap – the
  // scroll event comes in the next frame, after the menu opened; the list did not move, so the menu stays
  await P.evaluate(() => document.querySelectorAll('#planList .rowmenu').forEach((m) => { m.hidden = true; }));
  await P.evaluate((n) => { const ul = document.getElementById('planList'), s = ul.scrollTop;
    ul.scrollTop = 0; ul.scrollTop = s;
    [...ul.querySelectorAll('li[data-id]')].find((l) => l.querySelector('.name').textContent === n).querySelector('.more').click(); }, lastName);
  await P.waitForTimeout(300);
  const stays = await P.evaluate(() => !!document.querySelector('#planList .rowmenu:not([hidden])'));
  check(stays, `a redraw of the list right before ⋮ (its scroll back where it was): the menu stays open (${stays})`);
  await P.evaluate(() => { document.getElementById('planList').scrollTop -= 120; });
  const closed = await P.waitForFunction(() => !document.querySelector('#planList .rowmenu:not([hidden])'), null, { timeout: 3_000 }).then(() => true).catch(() => false);
  check(closed, 'a real scroll of the list afterwards closes the menu (it is fixed: its row moves away)');
}

// 5. the map pictures: made off screen one at a time, the drawn line until then; a row without a line keeps its sign
const pictures = await P.waitForFunction(() => [...document.querySelectorAll('#planList li[data-id]')].filter((l) => l.dataset.thumb === 'map').length >= 4, null, { timeout: 90_000 }).then(() => true).catch(() => false);
const st = await P.evaluate(() => ({ ...window.__thumbs.stats }));
check(pictures && st.made >= 4 && st.failed === 0, `map pictures for the 4 rows with a line (${JSON.stringify(st)})`);
r = await rowOf(P, 'Ifinger Steig');
check(r.kind && !r.img && r.thumb == null, 'a route this browser has no line for keeps the kind\'s sign, no picture');
const look = await P.evaluate(async () => {
  const img = [...document.querySelectorAll('#planList li[data-id]')].find((l) => l.querySelector('.name').textContent === 'Etschradweg Meran – Bozen').querySelector('img.thumb');
  await img.decode();
  const c = document.createElement('canvas'); c.width = img.naturalWidth; c.height = img.naturalHeight;
  const g = c.getContext('2d'); g.drawImage(img, 0, 0);
  const d = g.getImageData(0, 0, c.width, c.height).data;
  let red = 0, blue = 0, colours = new Set();
  for (let i = 0; i < d.length; i += 4) {
    const [R, G, B] = [d[i], d[i + 1], d[i + 2]];
    if (R > 140 && G < 70 && B < 70) red++;
    if (Math.abs(R - 0x33) < 30 && Math.abs(G - 0x5a) < 30 && Math.abs(B - 0x85) < 30) blue++;
    colours.add((R >> 4) << 8 | (G >> 4) << 4 | (B >> 4));
  }
  const thumbLayers2 = window.__thumbs.stats.layers;
  return { w: c.width, red, blue, colours: colours.size, type: (await (await fetch(img.src)).blob()).type,
    networksInThumb: thumbLayers2.length ? thumbLayers2.filter((id) => id.startsWith('routes-')).length : -1,
    networksOnMap: window.__map.getStyle().layers.filter((l) => l.id.startsWith('routes-')).length, planInThumb: thumbLayers2.length ? thumbLayers2.filter((id) => id.startsWith('planned-')).length : -1 };
});
check(look.w >= 84 && look.red > 10 && look.blue > 30 && look.colours > (dataDir ? 40 : 3), `the picture: the line in plan blue, the red goal, ${dataDir ? 'the map under it' : '(no map data here)'} (${JSON.stringify(look)})`);
check(look.networksInThumb === 0 && look.networksOnMap > 0 && look.planInThumb === 0, `no route networks (the map has ${look.networksOnMap} routes-* layers, the picture none) and none of the page's own layers (the plan)`);
check(look.type === 'image/webp', 'WebP, as the app');

// 6. closed and opened again: Newest first and the planning activity again, the pill kept, the pictures at once
await P.click('#planSort .sortchip[data-key="LENGTH"]');
await P.click('#planSort .favchip');
await closeList(P);
await openList(P);
c = await chipRow(P);
check(c[0].on && c[1].text === 'Hike' && c[2].on && !c[4].on, 'opened again: Favorites still on, the activity back on Hike, Newest first again (not kept)');
check(await P.evaluate(() => localStorage.getItem('onpath.routesSort')) == null, 'no sort kept in this browser (app 82167d11: no prefs)');
await clickDrawn(P, '#planSort .favchip');
await pickAct(P, 'ALL');
check((await P.$$eval('#planList li[data-thumb="map"] img.thumb', (els) => els.length)) >= 4, 'opened again: the pictures at once from memory');

// 7. Zuletzt shows whatever the filters: the plan on the map stays under Recent
await closeList(P);
await P.evaluate((code) => { location.hash = '#p=' + code; }, A);
await P.waitForFunction(() => document.getElementById('points')?.textContent?.includes('Vellau'), null, { timeout: 60_000 });
await openList(P);
await pickAct(P, 'BIKE_TOURING');
const recentShown = await P.$('#planList li[data-live]');
check(!!recentShown && same(await names(P), ['Etschradweg Meran – Bozen']), 'Bike chosen: the plan on the map still under Recent, the saved ones filtered');
await closeList(P);
await P.close();

// 8. a reload: the pictures come from this browser's store, none made again
P = await open(ctx, '?at=11.16,46.67,12');
await openList(P);
await pickAct(P, 'ALL');
await P.waitForFunction(() => document.querySelectorAll('#planList li[data-thumb="map"]').length >= 4, null, { timeout: 30_000 }).catch(() => {});
const st2 = await P.evaluate(() => ({ ...window.__thumbs.stats }));
check(st2.fromStore >= 4 && st2.made === 0, `after a reload the pictures come from the store (${JSON.stringify(st2)})`);
await ctx.close();

// ===== German: the app's words; the screenshots (desktop + Pixel 7) =====
for (const [name, opts] of [['desktop', { viewport: { width: 1280, height: 900 } }], ['pixel7', { viewport: { width: 412, height: 915 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true }]]) {
  const cx = await browser.newContext({ ...opts, locale: 'de-DE' });
  await cx.addInitScript(() => { window.__thumbsOn = true; });
  await cx.addInitScript(() => { try { if (!sessionStorage.getItem('seeded')) { localStorage.setItem('activity', 'HIKE'); localStorage.setItem('sac', '2'); sessionStorage.setItem('seeded', '1'); } } catch { /* expected: none */ } });
  P = await open(cx, '?lang=de&at=11.16,46.67,12');
  await seed(P);
  await P.evaluate(async () => { const s = window.__sync; for (const p of await s.list()) if (/Vigiljoch|Waalweg/.test(p.title ?? '')) await s.setFavourite(p.id, true); });
  if (await P.isVisible('#menuBtn')) await P.click('#menuBtn');
  await P.click('#menuPlans');
  await P.waitForSelector('dialog.plans[open] #planList li', { timeout: 60_000 }).catch(async (e) => { await P.screenshot({ path: '/tmp/claude-1001/-home-development-Projects-work-onpath-plan/1b30e014-3c2f-5f5a-b403-9afc9bea0d9e/scratchpad/fail-' + name + '.png' }); throw e; });
  c = await chipRow(P);
  if (name === 'desktop') {
    check(c[0].text === 'Favoriten' && c[1].text === 'Wandern' && c[2].text === 'Neueste zuerst', 'German chips: Favoriten · Wandern · Neueste zuerst');
    check((await rowOf(P, 'Runde ab Algund')).act === 'Wandern · T2 · Rundtour', 'German activity line: "Wandern · T2 · Rundtour"');
    check(await P.evaluate(() => { const s = document.querySelector('dialog.plans .actline .asep'), a = s?.parentElement.querySelector('.atext');
      if (!s || !a) return false; const r = s.getBoundingClientRect(), q = a.getBoundingClientRect(); return r.width > 4 && Math.abs(r.top - q.top) < 1 && Math.abs(r.height - q.height) < 1; }),
      'the „·“ before Rundtour sits on the text line (not squeezed low)');
  }
  await pickAct(P, 'ALL');
  await P.waitForFunction(() => document.querySelectorAll('#planList li[data-thumb="map"]').length >= 4, null, { timeout: 90_000 }).catch(() => {});
  if (shotDir) {
    await P.screenshot({ path: path.join(shotDir, `routes-${name}.png`) });
    await P.click('#planSort .actchip');
    await P.waitForTimeout(200);
    await P.screenshot({ path: path.join(shotDir, `routes-${name}-activity-menu.png`) });
    await P.keyboard.press('Escape');
    await P.click('#planSort .favchip');
    await P.waitForTimeout(300);
    await P.screenshot({ path: path.join(shotDir, `routes-${name}-favourites.png`) });
  }
  if (name === 'desktop') {
    await P.click('#planSort .favchip');
    await pickAct(P, 'ALL');
    const de = await P.evaluate(() => [...document.querySelectorAll('#actFilterMenu .alabel')].map((e) => e.textContent));
    check(de.at(-1) === 'Alle', 'German menu: „Alle“ at the bottom');
  }
  await cx.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429|507)/.test(e) && !/ERR_INTERNET_DISCONNECTED|Failed to fetch/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED: ${fails.length}` : 'all ok');
process.exit(fails.length ? 1 : 0);
