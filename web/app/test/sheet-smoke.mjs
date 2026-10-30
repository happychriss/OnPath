// The plan sheet's three steps on both widths (owner 2026-10-30, option A; docs/design/web-planner.md §11.3 – the app's
// PlanSize): small = summary row (chip, time, distance, ↑/↓, badge, ⋯, chevron, a compact Share), profile = + profile,
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
  const ring = box(document.querySelector('.searchring')), pc = document.getElementById('placecard'), card = box(pc);
  const side = pc.dataset.side, t = parseFloat(pc.style.getPropertyValue('--tail'));
  const tip = !card || !side ? null : side === 'above' ? { x: card.x + t, y: card.y + card.h + 10 } : side === 'below' ? { x: card.x + t, y: card.y - 10 }
    : side === 'right' ? { x: card.x - 10, y: card.y + t } : { x: card.x + card.w + 10, y: card.y + t };
  return { ring: ring && { x: ring.x + ring.w / 2, y: ring.y + ring.h / 2 }, card, side, tip, anchored: pc.classList.contains('anchored'),
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
/** "Show whole route" after the map was moved away: pan + zoom far off, click (or Enter), every point in the free room. */
async function checkFit(page, P, how = 'click') {
  await page.evaluate(() => window.__map.jumpTo({ center: [11.45, 46.42], zoom: 16 }));
  await settle(page);
  check((await freeRoom(page)).bad > 0, `${P} moved away: the route is out of view`);
  if (how === 'enter') { await page.focus('#fitRoute'); await page.keyboard.press('Enter'); } else await page.click('#fitRoute');
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
  check(!/Send to phone/.test(header) && /Import GPX/.test(phone ? await page.textContent('#mainMenu') : header) && /Pair phone/.test(header),
    `${P} header without Send to phone, with the phone entry "Pair phone" (owner 2026-10-30)`);

  // the three steps, every control
  if (await step(page) !== 'small') { // reach small with the chevron (Show more … until Points, then Show less → small)
    for (let i = 0; i < 3 && await step(page) !== 'small'; i++) await page.click('#sheetStep');
  }
  let s = await shown(page);
  check(await step(page) === 'small' && s['#summary .big'] && s['#sheet .subline'] && !s['#profile svg'] && !s['#points'],
    `${P} small: time, distance, ↑/↓ + badge; no profile, no Points`);
  check(s['#share'] && !(await page.isDisabled('#share')) && await page.getAttribute('#share', 'aria-label') === 'Share' &&await page.getAttribute('#share', 'title') === 'Share' && !(await page.$('#sheet button.primary')),
    `${P} small: Share is the icon next to ⋯, active with two points (label + tooltip), no big green button`);
  const sh = await page.locator('#sheet').boundingBox();
  check(sh.height < 180, `${P} small step is compact (${Math.round(sh.height)} px)`);
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
  await page.click('body', { position: { x: 5, y: vp.height / 2 } }).catch(() => {});

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
  await page.waitForTimeout(500);
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
  await page.screenshot({ path: path.join(shots, `${name}-card.png`) });
  await page.click('#cdest');
  check(await page.isHidden('#placecard') && await page.isVisible('#sheet'), `${P} the card closes after its action, the sheet is back`);
  await page.waitForFunction(() => document.querySelectorAll('.pin').length === 3, null, { timeout: 20_000 }).catch(() => {});
  check(await step(page) === 'small', `${P} the step is kept`);

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
  await page.waitForFunction(() => document.getElementById('status')?.textContent?.includes('ready'), null, { timeout: 120_000 });
  check(await page.isVisible('#share') && await page.isDisabled('#share') && !(await page.$('#sheet button.primary')),
    'empty plan: Share icon shown but greyed (owner 2026-10-30), no big button');
  check(await page.getAttribute('#share', 'title') === 'Set a start and destination first' && await page.getAttribute('#share', 'aria-label') === 'Set a start and destination first',
    'empty plan: Share says "Set a start and destination first"');
  check(await page.isVisible('#fitRoute') && await page.isDisabled('#fitRoute') && await page.getAttribute('#fitRoute', 'title') === 'Set a start and destination first'
    && await page.getAttribute('#fitRoute', 'aria-label') === 'Show whole route', 'empty plan: "Show whole route" greyed, its tooltip says why');
  // header (owner 2026-10-30): "OnPath Planner" as one name; every icon with its text; no Save (⋯ and My routes have it)
  const brand = await page.evaluate(() => { const w = document.querySelector('.wordmark'), p = document.querySelector('.product');
    return { text: w.textContent.replace(/\s+/g, ' ').trim(), same: getComputedStyle(w).fontSize === getComputedStyle(p).fontSize, upper: getComputedStyle(p).textTransform }; });
  check(brand.text === 'OnPath Planner' && brand.same && brand.upper === 'none', `header: "OnPath Planner" in one size (${JSON.stringify(brand)})`);
  check(!(await page.$('#header #menuSavePlan')), 'header: no Save entry (owner 2026-10-30: ⋯ → Save route, My routes → Save current route)');
  const labels = () => page.$$eval('#mainMenu button:not([hidden])', (bs) => bs.map((b) => { const sp = b.querySelector('span'); return { id: b.id, label: !!sp && sp.getClientRects().length > 0, icon: !!b.querySelector('.ic'), title: b.title }; }));
  let l = await labels();
  check(l.map((x) => x.id).join() === 'menuPlans,syncBadge,menuImportGpx,menuAbout' && l.every((x) => x.icon && x.label), `header at 1440 px: 4 entries, every icon has its text (${l.map((x) => x.id).join(', ')}; without: ${JSON.stringify(l.filter((x) => !x.icon || !x.label))})`);
  check((await page.textContent('#syncBadge')).trim() === 'Pair phone' && await page.isHidden('#syncBadge .syncdot'), 'header: not paired, the phone entry says "Pair phone" (no dot)');
  await page.waitForSelector('#syncBadge[aria-label]', { state: 'attached', timeout: 60_000 }); // sync ready, the entry follows it
  // the paired status, for the look only: the page's own first sync (sync.init → syncSoon → refresh) may still set
  // "not paired" from its store, so stop its timer and set the status until the entry shows it
  await page.evaluate(() => clearInterval(window.__sync.timer));
  await page.waitForTimeout(1500); // a sync already started ends
  await page.waitForFunction(() => { const s = window.__sync; if (!s.status.paired) { s.status = { ...s.status, paired: true, lastSync: Date.now() }; s.changed(); }
    return document.querySelector('#syncBadge .lbl')?.textContent === 'Paired'; }, null, { timeout: 10_000, polling: 200 }).catch(() => {});
  check((await page.textContent('#syncBadge')).trim() === 'Paired' && await page.isVisible('#syncBadge .syncdot'), 'header: the paired status says "Paired" with the green dot');
  l = await labels();
  check(l.length === 4 && l.every((x) => x.icon && x.label), 'header at 1440 px, paired: 4 entries ("Paired"), every icon has its text');
  await page.setViewportSize({ width: 1000, height: 800 });
  l = await labels();
  check(l.every((x) => x.icon && !x.label && x.title), 'header at 1000 px: icons only, each with its title');
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
