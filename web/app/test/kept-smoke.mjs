// Kept places (owner 2026-11-15, „Orte merken“ – the app's b2504c3d, F-PLAN-14; web-planner.md §32): a held place's /
// found place's menu „☆ Merken“ / „★ Gemerkt“ after the route rows; the empty search shows ★ Favoriten (five, then
// „Alle Favoriten ›“), Zuletzt gesucht without repeats. „⌂ Zuhause“ is off for now (owner 2026-11-19, the app's
// HOME_ROW_SHOWN = false): no „Als Zuhause festlegen“ in any menu, no Zuhause row although a home is stored (it stays);
// a row there is picked like a search hit; kept across a reload; a blocked store never breaks the search.
// Usage: node test/kept-smoke.mjs [segmentsDir] [mapStoreDir] [shotDir]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = path.resolve(process.argv[3] ?? '/home/user/build/cell/store');
const shotDir = process.argv[4];
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];

const MENU = '.holdpopup.pinmenu .pointmenu, #placecard.pinmenucard .pointmenu';
const ROWS = '.holdpopup.pinmenu .pointmenu button, #placecard.pinmenucard .pointmenu button';

async function open(ctx, query = '?at=11.16,46.67,12') {
  await ctx.route('https://photon.komoot.io/**', (r) => r.abort()); // offline hits only: the same lists every run
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url + 'index.html' + query);
  await ready(page);
  return page;
}
/** The engine is there; its search tells which text it answered last (a reload needs this again). */
async function ready(page) {
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  await page.evaluate(() => {
    const engine = window.__engine, search = engine.search.bind(engine);
    engine.search = async (text, ...rest) => { const hits = await search(text, ...rest); window.__searched = text; return hits; };
  });
}
const searchFor = async (page, t) => {
  await page.fill('#q', t);
  await page.waitForFunction((t) => window.__searched === t && (document.querySelector('#results .hit') || document.querySelector('#results .pad')), t, { timeout: 60_000 });
  await page.waitForTimeout(500);
  return page.$$eval('#results .hit b', (b) => b.map((x) => x.textContent));
};
/** The first hit for [q] picked (map areas may still be loading: tried again). */
const pickFirst = async (page, q) => {
  let hits = await searchFor(page, q);
  for (let i = 0; i < 6 && !hits.length; i++) { await page.waitForTimeout(1500); await page.fill('#q', ''); hits = await searchFor(page, q); }
  const title = hits[0];
  await page.click('#results .hit');
  await page.waitForSelector(MENU, { timeout: 30_000 });
  await page.waitForTimeout(300);
  return title;
};
const menuRows = (page) => page.$$eval(ROWS, (b) => b.map((x) => x.querySelector('.txt > span')?.textContent?.trim() ?? ''));
const clickRow = (page, label) => page.locator(ROWS).filter({ hasText: new RegExp(`^${label}$`) }).first().click();
/** The empty search: focus the cleared field; its lists as {home, favs, more, recent} (row names, grey parts). */
const emptySearch = async (page) => {
  await page.keyboard.press('Escape'); // a menu open: closed first
  await page.fill('#q', 'x'); await page.fill('#q', '');
  await page.focus('#q');
  await page.waitForSelector('#results:not([hidden]) .searchchips', { timeout: 15_000 });
  await page.waitForTimeout(400);
  return page.evaluate(() => {
    const box = document.querySelector('#results .startlists');
    const out = { home: null, favs: [], more: false, recent: [], heads: [], hint: !!document.querySelector('#results .pad') };
    if (!box) return out;
    let part = '';
    for (const el of box.children) {
      if (el.classList.contains('smallhead')) { part = el.querySelector('[role=heading]').textContent; out.heads.push(getComputedStyle(el).textTransform === 'uppercase' ? part.toUpperCase() : part); continue; }
      if (el.classList.contains('allfavs')) { out.more = el.textContent; continue; }
      if (!el.classList.contains('hit')) continue;
      const row = { name: el.querySelector('b').textContent, line: el.querySelector('.muted').textContent, h: el.getBoundingClientRect().height,
        oneLine: el.querySelector('b').getBoundingClientRect().height < 30 };
      if (el.querySelector('.sign.home')) out.home = row;
      else if (el.querySelector('.sign.fav')) out.favs.push(row);
      else out.recent.push(row);
    }
    return out;
  });
};

// ===== English: the rows, keep / unkeep, home, five + „All favourites ›“, reload =====
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  let page = await open(ctx);
  let s = await emptySearch(page);
  check(s.hint && !s.home && !s.favs.length, 'nothing kept yet: the hint as before, no Zuhause row (the app: absent while none is set)');

  const vellau = await pickFirst(page, 'Vellau');
  let rows = await menuRows(page);
  check(rows.join('|') === 'Start here|Round trip from here|Go here|Add to favourites|Open in Google Maps|Remove pin',
    `found place's menu: the kept-place row after the route rows, before Google (${rows.join(' · ')})`);
  check(!rows.includes('Set as home') && !rows.includes('Remove home'), 'Zuhause off: no „Set as home“ in the found place\'s menu');
  check(await page.evaluate((sel) => [...document.querySelectorAll(sel)].some((d) =>
    d.nextElementSibling?.textContent.includes('Add to favourites')), '.pointmenu .menudivider'), 'a thin line before „Merken“');
  await clickRow(page, 'Add to favourites');
  await page.waitForTimeout(300);
  rows = await menuRows(page);
  check(await page.locator(MENU).count() === 1 && rows.includes('In favourites') && !rows.includes('Add to favourites'),
    'Merken: the menu stays open and shows „Gemerkt“');
  check(await page.evaluate((sel) => { const b = [...document.querySelectorAll(sel)].find((x) => x.textContent.includes('In favourites'));
    return b?.querySelector('.ic')?.style.fill; }, ROWS) === 'rgb(176, 125, 0)', 'the star of „Gemerkt“ is gold (My routes\' favourite colour)');
  s = await emptySearch(page);
  console.log('heads:', s.heads);
  check(s.favs.length === 1 && s.favs[0].name === vellau && s.heads[0] === 'FAVOURITES', `the empty search: ${vellau} under FAVOURITES (${JSON.stringify(s.favs)})`);
  check(!s.recent.some((r) => r.name === vellau), 'a favourite is not repeated under Recent');
  check(s.favs[0].h >= 47.5 && s.favs[0].oneLine, `one-line row ≥ 48 px (${s.favs[0].h})`);

  // Zuhause off (owner 2026-11-19): no „Set as home“ for another place either; it shows under Recent only
  const ifinger = await pickFirst(page, 'Ifinger');
  rows = await menuRows(page);
  check(!rows.includes('Set as home') && rows.includes('Add to favourites'), `Zuhause off: „Merken“ only (${rows.join(' · ')})`);
  s = await emptySearch(page);
  check(!s.home && s.recent.some((r) => r.name === ifinger) && !s.recent.some((r) => r.name === vellau), 'no Home row; Recent without the favourite shown above');

  // a row of the empty search is picked like a search hit: the pin and its menu
  await page.locator('#results .hit.compact', { has: page.locator('.sign.fav') }).first().click();
  await page.waitForSelector(MENU, { timeout: 30_000 });
  check(await page.isVisible('.candidate') && (await menuRows(page)).includes('In favourites'), 'picking a favourite: the pin and its menu (In favourites there)');

  // a place only searched: under Recent
  const partschins = await pickFirst(page, 'Partschins');
  // six favourites: five, then „All favourites ›“
  const more = [];
  for (const q of ['Meran', 'Algund', 'Lana', 'Tirol', 'Hochmuth']) {
    const n = await pickFirst(page, q);
    if ((await menuRows(page)).includes('Add to favourites')) { await clickRow(page, 'Add to favourites'); more.push(n); await page.waitForTimeout(200); }
  }
  s = await emptySearch(page);
  console.log('favourites:', s.favs.map((f) => f.name), 'more:', s.more, 'recent:', s.recent.map((r) => r.name));
  check(s.favs.length === 5 && s.more === 'All favourites ›', `${1 + more.length} favourites: five shown, then „All favourites ›“`);
  check(s.favs[0].name === more.at(-1), 'the newest favourite first');
  check(new Set(s.recent.map((r) => r.name)).size === s.recent.length, `Recent: no place twice (${s.recent.map((r) => r.name).join(', ')})`);
  await page.click('#results .allfavs');
  await page.waitForTimeout(400);
  const all = await page.$$eval('#results .hit.compact .sign.fav', (e) => e.length);
  check(all === 1 + more.length && await page.locator('#results .allfavs').count() === 0, `„All favourites ›“: all ${all} shown`);

  // a home stored before (or synced from the phone) stays – it is only not shown (the app's KeptPlacesE2ETest)
  const homeCode = await page.evaluate(() => {
    const k = JSON.parse(localStorage.getItem('onpath.keptPlaces'));
    k.home = k.favourites[0]; localStorage.setItem('onpath.keptPlaces', JSON.stringify(k)); return k.home;
  });
  // across a reload
  await page.reload();
  await ready(page);
  s = await emptySearch(page);
  check(!s.home && s.favs.length === 5 && s.more, 'after a reload: the favourites are still there, a stored home shows no Zuhause row');
  check(s.recent.some((r) => r.name === partschins), `after a reload: Recent too (${s.recent.map((r) => r.name).join(', ')})`);
  await page.click('#results .allfavs');
  await page.waitForTimeout(400);

  // unkeep and remove home from the empty search's own rows
  await page.locator('#results .hit.compact', { has: page.locator('.sign.fav') }).filter({ hasText: vellau }).click();
  await page.waitForSelector(MENU, { timeout: 30_000 });
  await clickRow(page, 'In favourites');
  await page.waitForTimeout(300);
  check((await menuRows(page)).includes('Add to favourites'), '„Gemerkt“ clicked: back to „Merken“');
  s = await emptySearch(page);
  const allNow = await page.evaluate(() => JSON.parse(localStorage.getItem('onpath.keptPlaces')));
  check(!s.home && !s.favs.some((f) => f.name === vellau) && allNow.favourites.length === more.length,
    'unkept: gone from the empty search and the store');
  check(allNow.home === homeCode, 'the stored home stays in the store (hidden, not removed)');
  check(s.recent.some((r) => r.name === vellau), 'no longer kept: it shows under Recent again');

  // a map POI's menu has the rows too (clicked on the map, not searched)
  // the search list closed (a map click beside it would only close it), no menu open
  await page.evaluate(() => { document.getElementById('results').hidden = true; document.activeElement?.blur(); });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  const poi = await page.evaluate(() => {
    const m = window.__map, c = m.getContainer(), layers = m.getStyle().layers.filter((l) => l.id.startsWith('poi-')).map((l) => l.id);
    const f = m.queryRenderedFeatures({ layers }).map((x) => ({ x, p: m.project(x.geometry.coordinates) }))
      .find(({ x, p }) => x.geometry.type === 'Point' && x.properties?.name && p.x > 440 && p.x < c.clientWidth - 80 && p.y > 160 && p.y < c.clientHeight - 80);
    return f ? { x: f.p.x, y: f.p.y, name: f.x.properties.name } : null;
  });
  if (poi) {
    const mb = await page.locator('#map canvas').first().boundingBox();
    await page.mouse.click(mb.x + poi.x, mb.y + poi.y);
    await page.waitForSelector('.holdpopup:not(.pinmenu) .pointmenu', { timeout: 15_000 }).catch(() => {});
    const pr = await page.$$eval('.holdpopup:not(.pinmenu) .pointmenu button', (b) => b.map((x) => x.querySelector('.txt > span')?.textContent?.trim()));
    check(pr.slice(-2).join('|') === 'Add to favourites|Open in Google Maps' && !pr.includes('Set as home'), `a map POI (${poi.name}): the same rows, no „Set as home“ (${pr.join(' · ')})`);
  } else console.log('note: no named POI in view – the map POI check skipped');
  await ctx.close();
}

// ===== a blocked store: the search and the menu still work (robust first) =====
{
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  await ctx.addInitScript(() => {
    const get = Storage.prototype.getItem, set = Storage.prototype.setItem;
    Storage.prototype.getItem = function (k) { if (/keptPlaces|searchRecent/.test(k)) throw new Error('blocked'); return get.call(this, k); };
    Storage.prototype.setItem = function (k, v) { if (/keptPlaces|searchRecent/.test(k)) throw new DOMException('blocked', 'SecurityError'); return set.call(this, k, v); };
  });
  const page = await open(ctx);
  const n = await pickFirst(page, 'Vellau');
  await clickRow(page, 'Add to favourites');
  await page.waitForTimeout(300);
  const s = await emptySearch(page);
  check(s.favs.some((f) => f.name === n), 'blocked store: kept for this page, the empty search shows it');
  check((await searchFor(page, 'Ifinger')).some((h) => /Ifinger/.test(h)), 'blocked store: the search still finds places');
  await ctx.close();
}

// ===== German: the app's words; screenshots desktop + Pixel 7 =====
for (const [name, opts] of [['desktop', { viewport: { width: 1280, height: 860 } }],
  ['pixel7', { viewport: { width: 412, height: 915 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true }]]) {
  const ctx = await browser.newContext({ ...opts, locale: 'de-DE' });
  const page = await open(ctx, '?lang=de&at=11.16,46.67,12');
  await pickFirst(page, 'Algund');
  for (const q of ['Vellau', 'Ifinger', 'Meran', 'Lana', 'Tirol', 'Hochmuth']) {
    await pickFirst(page, q);
    if ((await menuRows(page)).includes('Merken')) await clickRow(page, 'Merken');
    await page.waitForTimeout(150);
  }
  for (const q of ['Partschins', 'Marling']) { await pickFirst(page, q).catch(() => {}); }
  // the menu of a kept place (Gemerkt) and of a new one (Merken)
  await pickFirst(page, 'Vellau');
  let rows = await menuRows(page);
  if (name === 'desktop') check(rows.includes('Gemerkt') && !rows.includes('Als Zuhause festlegen'), `German menu: „Gemerkt“, no „Als Zuhause festlegen“ (${rows.join(' · ')})`);
  if (shotDir) await page.screenshot({ path: path.join(shotDir, `kept-menu-kept-${name}.png`) });
  await pickFirst(page, 'Partschins').catch(() => {});
  rows = await menuRows(page);
  if (name === 'desktop') check(rows.includes('Merken'), `German menu: „Merken“ (${rows.join(' · ')})`);
  if (shotDir) await page.screenshot({ path: path.join(shotDir, `kept-menu-keep-${name}.png`) });
  const s = await emptySearch(page);
  if (name === 'desktop') {
    check(!s.home, 'German: no „Zuhause“ row (off for now)');
    check(s.heads.join('|') === 'FAVORITEN|ZULETZT GESUCHT' && s.more === 'Alle Favoriten ›', `German heads: ${s.heads.join(' · ')}, „${s.more}“`);
  }
  if (shotDir) await page.screenshot({ path: path.join(shotDir, `kept-search-${name}.png`) });
  await ctx.close();
}

await browser.close(); server.close();
const real = errors.filter((e) => !/status of (404|409|410|429|507)/.test(e) && !/ERR_INTERNET_DISCONNECTED|ERR_FAILED|Failed to fetch/.test(e) && !/^\[Problems\] (INFO|WARN|TRACE) /.test(e));
if (real.length) { console.log('errors:', real); fails.push('page errors'); }
console.log(fails.length ? `FAILED: ${fails.length}` : 'all ok');
process.exit(fails.length ? 1 : 0);
