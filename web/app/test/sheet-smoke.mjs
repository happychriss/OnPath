// The plan sheet's three steps on both widths (owner 2026-10-30, option A; docs/design/web-planner.md §11.3 – the app's
// PlanSize): small = summary row (chip, time, distance, ↑/↓, badge, chevron, a compact Share) + ⇅ Reverse · ↻ Round trip, profile = + profile,
// points = + Points. Grab handle (the app's handle tap), chevron (Show more / Show less), a click on the summary, Arrow
// keys; remembered after a reload; the camera fit leaves the sheet free (also the toolbar's "Show whole route" after a
// pan / zoom away, in the small and Points step, with a GPX track); the place card, the GPX track panel and the
// header without Send to phone / Pair phone. Needs only the rd5 (CI); Photon is a fake answer.
// Usage: node test/sheet-smoke.mjs [segmentsDir] [screenshotDir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const shots = path.resolve(process.argv[3] ?? path.join(here, '../../engine/build/sheet'));
fs.mkdirSync(shots, { recursive: true });
const CODE = 'AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU'; // Hike T3, Meran → Vellau, "Waalweg"
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const photon = { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [11.2585, 46.7006] },
  properties: { name: 'Ifinger', osm_key: 'natural', osm_value: 'peak', state: 'Trentino-Südtirol', country: 'Italien' } }] };
// a far result (owner 2026-10-30: "hilden, fabriciusstr. 11" picked online was not centred, its card far from it)
const far = { type: 'FeatureCollection', features: [{ type: 'Feature', geometry: { type: 'Point', coordinates: [6.9339, 51.1683] },
  properties: { name: 'Fabriciusstraße', housenumber: '11', osm_key: 'place', osm_value: 'house', city: 'Hilden', country: 'Deutschland' } }] };

const settle = (page) => page.waitForFunction(() => !window.__map.isMoving(), null, { timeout: 30_000 }).then(() => page.waitForTimeout(300));
/** The search ring (the picked point), the card, its side and tail tip, sheet and top bar – page pixels. */
const cardGeom = (page) => page.evaluate(() => {
  const box = (e) => { if (!e || e.hidden || !e.offsetParent) return null; const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width, h: r.height }; };
  const ring = box(document.querySelector('.candidate')), pc = document.getElementById('placecard'), card = box(pc);
  const side = pc.dataset.side, t = parseFloat(pc.style.getPropertyValue('--tail'));
  const tip = !card || !side ? null : side === 'above' ? { x: card.x + t, y: card.y + card.h + 10 } : side === 'below' ? { x: card.x + t, y: card.y - 10 }
    : side === 'right' ? { x: card.x - 10, y: card.y + t } : { x: card.x + card.w + 10, y: card.y + t };
  // the pin's tip is the place: 4.2 px above the pin box's bottom (search.ts TIP_MARGIN)
  return { ring: ring && { x: ring.x + ring.w / 2, y: ring.y + ring.h - 4.2 }, card, side, tip, anchored: pc.classList.contains('anchored'),
    visible: getComputedStyle(pc).visibility !== 'hidden', sheet: box(document.getElementById('sheet')), bar: box(document.getElementById('topbar')) };
});
/** Every route point, the planned line and a shown GPX track (map coordinates) inside the free map room: in the map,
 *  outside the plan sheet and the top bar. */
const freeRoom = (page) => page.evaluate(() => {
  const m = window.__map, st = window.__state, track = window.__gpx.shown;
  const map = m.getContainer().getBoundingClientRect();
  const sheet = document.getElementById('sheet').getBoundingClientRect(), bar = document.getElementById('topbar').getBoundingClientRect();
  const ll = (a) => a.map(([la, lo]) => [lo, la]);
  const pts = [...ll(st.points), ...(st.plan?.legs ?? []).flatMap((l) => ll(l.points)), ...(track ? ll(track.segments.flat()) : [])];
  const inside = (p, r) => p.x >= r.left && p.x <= r.right && p.y >= r.top && p.y <= r.bottom;
  const bad = pts.map((c) => { const p = m.project(c); return { x: p.x + map.left, y: p.y + map.top }; })
    .filter((p) => !(p.x >= map.left + 4 && p.x <= map.right - 4 && p.y >= map.top + 4 && p.y <= map.bottom - 4) || inside(p, sheet) || inside(p, bar));
  return { n: pts.length, bad: bad.length, first: bad[0] && { x: Math.round(bad[0].x), y: Math.round(bad[0].y) }, zoom: +m.getZoom().toFixed(1) };
});
/** Waits until the sheet's height has not changed for 300 ms (its step animation is over). */
async function sheetStill(page) {
  let last = -1;
  for (let i = 0; i < 40; i++) {
    const h = await page.evaluate(() => Math.round(document.getElementById('sheet').getBoundingClientRect().height));
    if (h === last) return;
    last = h; await page.waitForTimeout(300);
  }
}
/** "Show whole route" after the map was moved away: pan + zoom far off, click (or Enter), every point in the free room. */
async function checkFit(page, P, how = 'click') {
  await sheetStill(page); // a step change still growing the sheet would give the fit an old height (seen under load)
  await page.evaluate(() => window.__map.jumpTo({ center: [11.45, 46.42], zoom: 16 }));
  await settle(page);
  check((await freeRoom(page)).bad > 0, `${P} moved away: the route is out of view`);
  if (how === 'enter') { await page.focus('#fitRoute'); await page.keyboard.press('Enter'); } else await page.click('#fitRoute');
  // under load the camera may not have started yet – wait for the move to begin (≤ 1 s), then for it to end
  await page.waitForFunction(() => window.__map.isMoving(), null, { timeout: 1_000 }).catch(() => {});
  await settle(page);
  const r = await freeRoom(page);
  check(r.n >= 2 && r.bad === 0, `${P} "Show whole route" (${how}): all ${r.n} route points in the free map room, clear of sheet and top bar (zoom ${r.zoom}${r.bad ? `, ${r.bad} outside, e.g. ${JSON.stringify(r.first)}` : ''})`);
}
const overlaps = (a, b) => !!a && !!b && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
function checkAnchored(g, vp, P) {
  const across = g.tip && g.ring ? (g.side === 'above' || g.side === 'below' ? Math.abs(g.tip.x - g.ring.x) : Math.abs(g.tip.y - g.ring.y)) : 99;
  const along = g.tip && g.ring ? Math.hypot(g.tip.x - g.ring.x, g.tip.y - g.ring.y) : 99;
  check(g.anchored && g.visible && across <= 3 && along <= 16, `${P} the card's tail points at the point (${g.side}, tip ${across.toFixed(1)} px across, ${along.toFixed(1)} px from it)`);
  check(g.card.x >= 0 && g.card.y >= 0 && g.card.x + g.card.w <= vp.width && g.card.y + g.card.h <= vp.height && g.ring.x > 0 && g.ring.x < vp.width && g.ring.y > 0 && g.ring.y < vp.height,
    `${P} card and point inside the window`);
  const r = (b) => b && [b.x, b.y, b.w, b.h].map(Math.round).join(',');
  check(!overlaps(g.card, g.sheet) && !overlaps(g.card, g.bar), `${P} the card covers neither the plan sheet nor the top bar`
    + (overlaps(g.card, g.sheet) || overlaps(g.card, g.bar) ? ` (${g.side}: card ${r(g.card)}, sheet ${r(g.sheet)}, bar ${r(g.bar)}, point ${r({ ...g.ring, w: 0, h: 0 })})` : ''));
}

async function open(vp, phone) {
  const ctx = await browser.newContext({ viewport: vp, isMobile: phone, hasTouch: phone });
  await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ body: JSON.stringify(/hilden/i.test(r.request().url()) ? far : photon), contentType: 'application/json' }));
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url + 'index.html#p=' + CODE);
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  await page.click('#appHintLine .x').catch(() => {}); // the one line about the app (a shared link) – the step sizes without it
  return { ctx, page };
}
const step = (page) => page.getAttribute('#sheet', 'data-step');
const shown = async (page) => Object.fromEntries(await Promise.all(['#summary .big', '#sheet .subline', '#profile svg', '#points', '#share', '#handle', '#sheetStep']
  .map(async (s) => [s, await page.isVisible(s)])));

for (const [name, vp, phone] of [['desktop', { width: 1440, height: 900 }, false], ['phone', { width: 390, height: 844 }, true]]) {
  const { ctx, page } = await open(vp, phone);
  const P = `${name}:`;
  // nothing remembered: the side panel shows everything (as before), the bottom sheet the profile (the app's default)
  check(await step(page) === (phone ? 'profile' : 'points'), `${P} first step without storage = ${phone ? 'profile' : 'points'}`);
  check(await page.isVisible('#handle') && await page.isVisible('#sheetStep'), `${P} grab handle and chevron visible`);
  const header = await page.textContent('#header');
  check(!/Send to phone/.test(header) && /Import GPX/.test(await page.textContent('#mainMenu')) && /To phone/.test(header),
    `${P} header without Send to phone, with "To phone" (owner 2026-11-06)`);

  // the three steps, every control
  if (await step(page) !== 'small') { // reach small with the chevron (Show more … until Points, then Show less → small)
    for (let i = 0; i < 3 && await step(page) !== 'small'; i++) await page.click('#sheetStep');
  }
  let s = await shown(page);
  check(await step(page) === 'small' && s['#summary .big'] && s['#sheet .subline'] && !s['#profile svg'] && !s['#points'],
    `${P} small: time, distance, ↑/↓ + badge; no profile, no Points`);
  check(s['#share'] && !(await page.isDisabled('#share')) && await page.getAttribute('#share', 'aria-label') === 'Share' &&await page.getAttribute('#share', 'title') === 'Share' && !(await page.$('#sheet button.primary')),
    `${P} small: Share is the icon next to the chevron, active with two points (label + tooltip), no big green button`);
  const sh = await page.locator('#sheet').boundingBox();
  // the app's summary step holds the action row ⇅ Reverse · ↻ Round trip too (owner 2026-10-31; ✕ left it 2026-11-06)
  check(sh.height < 230, `${P} small step is compact (${Math.round(sh.height)} px)`);
  check(await page.getAttribute('#sheetStep', 'aria-label') === 'Show more' && await page.getAttribute('#sheetStep', 'title') === 'Show more', `${P} chevron says "Show more" (label + tooltip)`);
  await page.screenshot({ path: path.join(shots, `${name}-small.png`) });

  await page.click('#handle');
  s = await shown(page);
  check(await step(page) === 'profile' && s['#profile svg'] && !s['#points'] && s['#share'], `${P} handle → profile step (profile, Share icon, no Points)`);
  await page.screenshot({ path: path.join(shots, `${name}-profile.png`) });
  await page.click('#summary .big');
  s = await shown(page);
  check(await step(page) === 'points' && s['#points'] && s['#profile svg'], `${P} a click on the summary → Points step`);
  check(await page.getAttribute('#sheetStep', 'aria-label') === 'Show less', `${P} chevron says "Show less" on the Points step`);
  await page.screenshot({ path: path.join(shots, `${name}-points.png`) });
  await page.click('#handle');
  check(await step(page) === 'profile', `${P} handle on Points → back to the profile (the app's tapped())`);
  await page.click('#activityChip');
  check(await step(page) === 'profile' && await page.isVisible('#activityMenu'), `${P} the chip opens its list, the step stays`);
  await page.keyboard.press('Escape');
  // only when Escape left the list open: a click at the map's edge with the list already closed adds a route point
  // (seen under load: a third point, a taller sheet, the later fit checks fail)
  if (await page.isVisible('#activityMenu')) await page.click('body', { position: { x: 5, y: vp.height / 2 } }).catch(() => {});

  // keyboard: Arrow towards the open edge = more (desktop: down, phone: up), Enter on the chevron
  await page.focus('#handle');
  await page.keyboard.press(phone ? 'ArrowUp' : 'ArrowDown');
  check(await step(page) === 'points', `${P} ${phone ? 'Arrow up' : 'Arrow down'} on the handle → more`);
  await page.keyboard.press(phone ? 'ArrowDown' : 'ArrowUp');
  await page.keyboard.press(phone ? 'ArrowDown' : 'ArrowUp');
  check(await step(page) === 'small', `${P} the other arrow → less, down to small`);
  await page.focus('#sheetStep');
  await page.keyboard.press('Enter');
  check(await step(page) === 'profile', `${P} Enter on the chevron → one step more`);
  await page.keyboard.press('Enter'); await page.keyboard.press('Enter');
  check(await step(page) === 'small', `${P} chevron on Points → small`);

  // remembered after a reload; the camera fit keeps the route clear of the small sheet
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
  // the first-route fit comes WholeRoute.SETTLE_MS (500 ms) after the route, then the camera eases – wait for both
  await page.waitForTimeout(900); await page.waitForFunction(() => window.__map.isMoving(), null, { timeout: 1_000 }).catch(() => {}); await settle(page);
  check(await step(page) === 'small' && await page.evaluate(() => localStorage.getItem('onpath.sheetStep')) === 'small', `${P} small step remembered after a reload`);
  const clear = await page.evaluate(() => { const r = document.getElementById('sheet').getBoundingClientRect();
    return [...document.querySelectorAll('.pin')].every((p) => { const b = p.getBoundingClientRect(); return b.right < r.left || b.left > r.right || b.bottom < r.top || b.top > r.bottom; }); });
  check(clear, `${P} after the fit both points are clear of the sheet`);

  // the toolbar's "Show whole route" (owner 2026-10-30): next to locate, a normal button, the whole route in the free room
  check(!(await page.isDisabled('#fitRoute')) && await page.getAttribute('#fitRoute', 'aria-label') === 'Show whole route' && await page.getAttribute('#fitRoute', 'title') === 'Show whole route'
    && await page.evaluate(() => document.getElementById('fitRoute').previousElementSibling?.id === 'locate'), `${P} "Show whole route" active next to locate (label + tooltip)`);
  await checkFit(page, `${P} small step:`);
  if (!phone) {
    await checkFit(page, `${P} small step:`, 'enter');
    // a padding left behind by an older move (route-warning spot / gap "Show") must not shrink or shift the room
    await page.evaluate(() => window.__map.easeTo({ padding: { top: 300, bottom: 50, left: 446, right: 90 }, duration: 0 }));
    await checkFit(page, `${P} after a left-over padding:`);
  }
  await page.click('#handle'); await page.click('#summary .big');
  check(await step(page) === 'points', `${P} Points step for the fit`);
  await checkFit(page, `${P} Points step:`);
  await page.screenshot({ path: path.join(shots, `${name}-fit-route.png`) });
  await page.focus('#handle');
  await page.keyboard.press(phone ? 'ArrowDown' : 'ArrowUp'); await page.keyboard.press(phone ? 'ArrowDown' : 'ArrowUp');
  check(await step(page) === 'small', `${P} back to the small step`);

  // a new error line shows in the small step (the app keeps route lines above its MINI step end)
  await page.evaluate(() => { const n = document.getElementById('notice'); n.hidden = false; n.textContent = '⚠ test'; });
  check(await page.isVisible('#notice'), `${P} the red route line is visible in the small step`);
  await page.evaluate(() => { document.getElementById('notice').hidden = true; });

  // the place card still works in the small step – and belongs to its point (owner 2026-10-30, like the app): wide →
  // a callout whose tail points at the search ring, clear of sheet and top bar, following a pan; phone → the bottom
  // card with the point visible above it
  const pick = async (q) => {
    await page.fill('#q', q);
    await page.waitForSelector('#results .hit', { timeout: 30_000 });
    await page.click('#results .hit');
    await page.waitForSelector('#placecard:not([hidden]) #cdest', { timeout: 20_000 });
    await settle(page);
  };
  await pick('Ifinger');
  const card = await page.locator('#placecard').boundingBox();
  let g = await cardGeom(page);
  if (phone) {
    check(await page.isHidden('#sheet') && card.y + card.height >= vp.height - 2, `${P} place card stands in for the sheet at the bottom`);
    check(g.ring && g.ring.y + 13 < g.card.y && g.ring.y - 13 > g.bar.y + g.bar.h && g.ring.x > 0 && g.ring.x < vp.width,
      `${P} the picked point is visible above the bottom card (ring ${JSON.stringify(g.ring)}, card top ${Math.round(g.card.y)})`);
  } else {
    checkAnchored(g, vp, `${P} small step:`);
    await page.evaluate(() => window.__map.panBy([-140, 70], { duration: 0 })); await settle(page);
    const g2 = await cardGeom(page);
    checkAnchored(g2, vp, `${P} after a pan:`);
    check(Math.abs(g2.ring.x - g.ring.x - 140) < 3 && Math.abs(g2.ring.y - g.ring.y + 70) < 3, `${P} the card followed the point during the pan`);
    // no room above (the point just under the top bar): the card flips to another side
    await page.evaluate((dy) => window.__map.panBy([0, dy], { duration: 0 }), Math.round(g2.ring.y - (g2.bar.y + g2.bar.h + 90))); await settle(page);
    const g3 = await cardGeom(page);
    check(g3.side !== 'above', `${P} point under the top bar: the card flips (${g3.side})`);
    checkAnchored(g3, vp, `${P} flipped:`);
    // a map click beside the card closes it and adds nothing; Escape closes it too
    const pins = await page.locator('.pin').count();
    await page.mouse.click(vp.width - 120, vp.height - 200);
    await page.waitForTimeout(400);
    check(await page.isHidden('#placecard') && await page.locator('.pin').count() === pins, `${P} a map click beside the card closes it (nothing added)`);
    await pick('Ifinger');
    await page.keyboard.press('Escape');
    check(await page.isHidden('#placecard'), `${P} Escape closes the card`);
    // a far result after a move that left a padding behind (spot / gap "show"): centred in the free room, card at it
    await page.evaluate(() => window.__map.easeTo({ padding: { top: 90, bottom: 50, left: 446, right: 90 }, duration: 0 }));
    await pick('hilden fabriciusstr 11');
    g = await cardGeom(page);
    checkAnchored(g, vp, `${P} far result (Hilden):`);
    check(g.ring.x > g.sheet.x + g.sheet.w && g.ring.y > g.bar.y + g.bar.h && g.ring.y < vp.height - 40, `${P} far result: the point is in the free map room (${JSON.stringify(g.ring)})`);
    await page.screenshot({ path: path.join(shots, `${name}-card-far.png`) });
    await page.keyboard.press('Escape');
    await pick('Ifinger');
  }
  // the found place's pin (the app's CandidateMarker, 2026-11-02): a route-blue pin, its name as a chip "Ifinger ›" under
  // it, a thin dashed line to where adding it would join the route
  const joinCount = () => page.evaluate(() => window.__map.getSource('candidate-join')?.serialize().data.features.length ?? 0);
  const cand = await page.evaluate(() => ({ pin: !!document.querySelector('.candidate svg path[fill="#1E3FAE"]'), chip: document.querySelector('.pinchip b')?.textContent,
    chipBelow: document.querySelector('.pinchip').getBoundingClientRect().y >= document.querySelector('.candidate').getBoundingClientRect().bottom - 6,
    orange: !!document.querySelector('.searchring') }));
  check(cand.pin && cand.chip === 'Ifinger' && cand.chipBelow && !cand.orange && await joinCount() === 1, `${P} found place: blue pin, the name chip below, a dashed line to the route (${JSON.stringify(cand)})`);
  await page.screenshot({ path: path.join(shots, `${name}-card.png`) });
  // the pin card (owner 2026-11-04, design B – the app's PinChip): the name, a blue "+ Add" (owner 2026-11-05, onpath.private#26) and "⋯ More", both ≥ 48 px
  const pc = await page.evaluate(() => { const r = (s) => document.querySelector(s)?.getBoundingClientRect();
    return { add: document.querySelector('.pinchip .pinadd')?.textContent, more: document.querySelector('.pinchip .pinmore')?.textContent,
      h: Math.min(r('.pinchip .pinadd')?.height ?? 0, r('.pinchip .pinmore')?.height ?? 0),
      blue: getComputedStyle(document.querySelector('.pinchip .pinadd'), '::after').backgroundColor }; });
  check(pc.add === 'Add' && pc.more === 'More' && pc.h >= 48 && pc.blue === 'rgb(46, 106, 158)',
    `${P} the pin card: "+ Add" (blue) · "⋯ More", touch targets ≥ 48 px (${JSON.stringify(pc)})`);
  // closing the card keeps the pin (the app 2026-11-02); its chip opens the card again; "Remove pin" takes it away
  await page.click('#cclose');
  check(await page.isHidden('#placecard') && await page.isVisible('.candidate') && await page.isVisible('.pinchip'), `${P} the card closed, the pin and its chip stay`);
  await page.click('.pinchip .pinmore');
  await page.waitForSelector('#placecard:not([hidden]) #cunpin', { timeout: 20_000 }).catch(() => {});
  check(await page.isVisible('#placecard') && await page.isVisible('#cunpin'), `${P} "⋯ More" opens the place card again (with "Remove pin")`);
  await page.click('#cunpin');
  check(await page.isHidden('#placecard') && await page.locator('.candidate').count() === 0 && await page.locator('.pinchip').count() === 0, `${P} "Remove pin": card, pin and chip gone`);
  // "+ Add" on the pin card adds the place where it fits (the card's main button), pin and card go
  {
    const before = await page.locator('.pin').count();
    await pick('Ifinger');
    await page.click('#cclose');
    await page.click('.pinchip .pinadd');
    await page.waitForFunction((n) => document.querySelectorAll('.pin').length === n + 1, before, { timeout: 20_000 }).catch(() => {});
    check(await page.locator('.pin').count() === before + 1 && await page.locator('.pinchip').count() === 0, `${P} "+ Add" adds the place, the pin card goes`);
    await page.click('#undo');
    await page.waitForFunction((n) => document.querySelectorAll('.pin').length === n, before, { timeout: 20_000 }).catch(() => {});
  }
  await pick('Ifinger');
  await page.click('#cdest');
  check(await page.isHidden('#placecard') && await page.isVisible('#sheet') && await joinCount() === 0 && await page.locator('.candidate').count() === 0, `${P} the card closes after its action, the sheet is back, pin and preview line gone`);
  await page.waitForFunction(() => document.querySelectorAll('.pin').length === 3, null, { timeout: 20_000 }).catch(() => {});
  check(await step(page) === 'small', `${P} the step is kept`);
  // a place added from the card: the new route is fitted once it is there (the app's WholeRoute.fitAfterPlace, add-on 2)
  // wait for the NEW plan (three points) – the old two-point plan is still in state.plan while the new one is routed;
  // then the fit (WholeRoute.SETTLE_MS = 500 ms after the route) and the camera's ease
  await page.waitForFunction(() => !window.__state.planning && window.__state.plan?.points.length === 3, null, { timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(900); await settle(page);
  const fitted = await freeRoom(page);
  check(fitted.bad === 0, `${P} after "As destination" the whole new route shows (${JSON.stringify(fitted)})`);

  // the GPX track panel shows fully in any step; closing it brings the remembered step back
  await page.evaluate(() => window.__gpx.importFile(new File([`<?xml version="1.0"?><gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Test track</name><trkseg>
    <trkpt lat="46.6705" lon="11.1605"><ele>320</ele></trkpt><trkpt lat="46.6750" lon="11.1550"><ele>360</ele></trkpt><trkpt lat="46.6800" lon="11.1500"><ele>420</ele></trkpt>
    </trkseg></trk></gpx>`], 'test.gpx', { type: 'application/gpx+xml' })));
  await page.waitForSelector('#trackPanel', { timeout: 20_000 }).catch(() => {});
  check(await page.isVisible('#trackPanel .trackname') && await page.isVisible('#trackPanel #planFromTrack'), `${P} GPX track panel complete in the small step`);
  await checkFit(page, `${P} with the GPX track:`); // the track is in the room too (the panel stands in for the sheet)
  await page.click('#closeTrack').catch(() => {});
  check(!(await page.$('#trackPanel')) && await step(page) === 'small' && await page.isVisible('#summary'), `${P} Close track: the small sheet is back`);

  if (name === 'desktop') {
    // Share opens the dialog: "Scan with the OnPath app" above the QR code
    await page.click('#share');
    await page.waitForSelector('dialog.share[open] .qr svg');
    check((await page.textContent('dialog.share h2')).trim() === 'Share' && (await page.textContent('dialog.share .scanline')).includes('Scan with the OnPath app'),
      `${P} Share dialog: "Scan with the OnPath app" above the QR code`);
    await page.click('#closeShare');
  }
  await ctx.close();
}

// empty plan (owner screenshot 2026-10-30): no Share, "Plan a route" on one line; developer timing only with ?debug
{
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url + 'index.html');
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 120_000 });
  check(await page.isVisible('#share') && await page.isDisabled('#share') && !(await page.$('#sheet button.primary')),
    'empty plan: Share icon shown but greyed (owner 2026-10-30), no big button');
  check(await page.getAttribute('#share', 'title') === 'Set a start and destination first' && await page.getAttribute('#share', 'aria-label') === 'Set a start and destination first',
    'empty plan: Share says "Set a start and destination first"');
  check(await page.isVisible('#fitRoute') && await page.isDisabled('#fitRoute') && await page.getAttribute('#fitRoute', 'title') === 'Set a start and destination first'
    && await page.getAttribute('#fitRoute', 'aria-label') === 'Show whole route', 'empty plan: "Show whole route" greyed, its tooltip says why');
  // header (owner 2026-11-06, A): "OnPath Planner" as one name; on the left ONE plan bar + New · My tours · Save ▾ · To phone ▾
  // (every icon with its text on a wide screen), on the right Help, the app and the language; phone width: "Plan ▾"
  const brand = await page.evaluate(() => { const w = document.querySelector('.wordmark'), p = document.querySelector('.product');
    return { text: w.textContent.replace(/\s+/g, ' ').trim(), same: getComputedStyle(w).fontSize === getComputedStyle(p).fontSize, upper: getComputedStyle(p).textTransform }; });
  check(brand.text === 'OnPath Planner' && brand.same && brand.upper === 'none', `header: "OnPath Planner" in one size (${JSON.stringify(brand)})`);
  const bar = () => page.$$eval('#mainMenu .pb', (bs) => bs.filter((b) => b.getClientRects().length > 0).map((b) => { const l = b.querySelector('.lbl'), r = b.getBoundingClientRect();
    return { id: b.id, label: l && l.getClientRects().length > 0 ? l.textContent : '', icon: !!b.querySelector('.ic'), title: b.title, h: Math.round(r.height), x: Math.round(r.x), w: Math.round(r.width) }; }));
  const over = () => page.evaluate(() => document.getElementById('header').scrollWidth - document.getElementById('header').clientWidth);
  let l = await bar();
  check(l.map((x) => x.id).join() === 'newPlan,menuPlans,menuSavePlan,saveMore,syncBadge,phoneMore'
    && l.filter((x) => !x.id.endsWith('More')).map((x) => x.label).join() === 'New,My tours,Save,To phone' && l.every((x) => x.icon && x.h === 36) && l.at(-1).x + l.at(-1).w < 1440 / 2,
    `header at 1440 px: the plan bar on the left – New · My tours · Save ▾ · To phone ▾ (the app's words), 36 px, every icon with its text (${JSON.stringify(l.map((x) => [x.id, x.label, x.x]))})`);
  const help = await page.locator('#menuAbout').boundingBox();
  check(help && help.x > 1000 && await page.isVisible('#langSwitch') && await page.isVisible('#headRight a.navlink'), 'header at 1440 px: Help, the app and the language on the right');
  check(await page.isDisabled('#menuSavePlan') && await page.getAttribute('#menuSavePlan', 'data-state') === 'empty' && await page.isDisabled('#newPlan'), 'header: Save and New greyed on an empty route');
  check(await page.isHidden('#syncBadge .syncdot') && await page.isHidden('#syncBadge .sub'), 'header: not paired, "To phone" has no dot and no second line');
  await page.waitForSelector('#syncBadge[data-ready]', { state: 'attached', timeout: 60_000 }); // sync ready, the button follows it
  // the paired status, for the look only: the page's own first sync (sync.init → syncSoon → refresh) may still set
  // "not paired" from its store, so stop its timer and set the status until the button shows it
  await page.evaluate(() => clearInterval(window.__sync.timer));
  await page.waitForTimeout(1500); // a sync already started ends
  await page.waitForFunction(() => { const s = window.__sync; if (!s.status.paired) { s.status = { ...s.status, paired: true, phone: 'Pixel 8', lastSync: Date.now() }; s.changed(); }
    return document.querySelector('#syncBadge.paired .lbl')?.textContent === 'Pixel 8'; }, null, { timeout: 10_000, polling: 200 }).catch(() => {});
  check(await page.isVisible('#syncBadge .lbl') && (await page.textContent('#syncBadge .lbl')) === 'Pixel 8' && await page.isVisible('#syncBadge .syncdot')
    && (await page.textContent('#syncBadge .sub')) === 'synced just now' && await page.isVisible('#syncBadge .sub')
    && (await page.getAttribute('#syncBadge', 'title')).startsWith('Pixel 8 · synced just now') && await page.isHidden('#phoneMore'),
    'header: paired, To phone is the phone\'s status "📱 Pixel 8 🟢 / synced just now" (owner 2026-11-07: big enough to see), no ▾');
  check(await page.locator('#menuSavePlan .savephone').count() === 1, 'header: paired, Save carries a small phone (it sends too)');
  check((await page.textContent('#menuSavePlan')).trim() === 'Save', 'header: paired, Save still says "Save" (it sends too)');
  await page.setViewportSize({ width: 1000, height: 800 });
  l = await bar();
  const lb = Object.fromEntries(l.map((x) => [x.id, x]));
  check(!lb.newPlan.label && lb.newPlan.title && !lb.menuPlans.label && lb.menuPlans.title && lb.menuSavePlan.label === 'Save' && lb.syncBadge.label === 'Pixel 8' && lb.syncBadge.title && await over() <= 0,
    `header at 1000 px: New and My tours icons only (each with its title), Save keeps its text, the paired 📱 status keeps the phone's name (the second line goes), nothing overflows (${JSON.stringify(l.map((x) => [x.id, x.label]))})`);
  for (const w of [720, 860, 1180]) { await page.setViewportSize({ width: w, height: 800 }); check(await over() <= 0, `header at ${w} px: nothing overflows`); }
  await page.setViewportSize({ width: 390, height: 844 });
  check(await page.isVisible('#menuBtn') && await page.isHidden('#menuPlans') && await page.isHidden('#syncBadge') && await over() <= 0,
    'phone width: the plan bar is one "Plan ▾" button, nothing overflows');
  check(await page.isVisible('#phoneMini') && await page.isVisible('#phoneMini .syncdot') && (await page.getAttribute('#phoneMini', 'title')).startsWith('Pixel 8 · synced'),
    'phone width, paired: the phone status stays in the header – 📱 with its dot, the title "Pixel 8 · synced …" (owner 2026-11-07)');
  await page.click('#menuBtn');
  check(await page.isVisible('#syncBadge .syncdot') && (await page.textContent('#syncBadge .lbl')).trim() === 'Pixel 8' && await page.isVisible('#menuSavePlan') && await page.isHidden('#menuPhone'),
    'phone width, paired: Plan ▾ holds Save and the phone (its name and dot – it opens the Phone window), no second Phone entry');
  await page.keyboard.press('Escape');
  check(await page.isHidden('#syncBadge'), 'phone width: Escape closes Plan ▾');
  await page.setViewportSize({ width: 320, height: 700 });
  check(await over() <= 0, 'header at 320 px: nothing overflows');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForFunction(() => document.querySelector('#summary .title')?.getBoundingClientRect().height > 0, null, { timeout: 30_000 }).catch(() => {});
  const title = await page.evaluate(() => { const e = document.querySelector('#summary .title');
    return e ? { h: e.getBoundingClientRect().height, text: e.textContent } : { h: 0, text: document.querySelector('.sheethead').outerHTML.slice(0, 300) }; });
  check(title.h > 0 && title.h < 40, `empty plan: "Plan a route" on one line (${Math.round(title.h)} px, ${JSON.stringify(title.text)})`);
  for (const [q, want] of [['', false], ['?debug', true]]) {
    await page.goto(url + 'index.html' + q + '#p=' + CODE);
    await page.waitForFunction(() => document.querySelector('#summary .big'), null, { timeout: 120_000 });
    await page.waitForTimeout(300);
    const st = await page.textContent('#status');
    check(/Planned in/.test(st) === want, `${q || 'no ?debug'}: developer timing ${want ? 'shown' : 'hidden'} (${JSON.stringify(st)})`);
  }
  await ctx.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of 404/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
process.exit(fails.length ? 1 : 0);
