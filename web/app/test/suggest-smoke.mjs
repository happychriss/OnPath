// Round-trip suggestions in the planner (suggest.ts, WebSuggest.kt; docs/design/tour-suggestions.md §7 stage 3): on by
// default since 2026-11-18 (owner); with the ?debug off-switch nothing changes (no „Rundtour von hier“). On: the first
// tap's menu has the row right below „Hier starten“ → the wish (activity, two range sliders, five Ziel chips,
// Abbrechen · OK) → the wait → cards (badge, km · h · ↑, at most 2 green + 2 yellow pills) side by side in one row along
// the map's bottom edge (sketch 8: the side panel stays the plan) with only the chosen loop on the map → „Übernehmen“ =
// a normal round-trip plan. On a phone width: one card in front, the next looking in, a swipe chooses the card it rests
// on. German and English words. Needs E5_N50.rd5 (Hilden) and the cells' suggest files (<dir>/10-x-y/suggest.bin,
// content 2) – skipped without them (says so).
// Usage: node test/suggest-smoke.mjs [segmentsDir] [suggestFilesDir]
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const filesDir = path.resolve(process.argv[3] ?? path.join(os.homedir(), 'onpath-suggest-poc/suggest-file/cells-c2'));
const HILDEN = [51.168, 6.9395];
if (!fs.existsSync(path.join(segments, 'E5_N50.rd5')) || !fs.existsSync(path.join(filesDir, '10-531-341/suggest.bin'))) {
  console.log(`skip: needs E5_N50.rd5 in ${segments} and the Hilden suggest files in ${filesDir}`);
  process.exit(0);
}
// a map store with the suggest files as the cells' current version (cells/f2/10/x/y/current.json + <version>/suggest.bin)
const store = fs.mkdtempSync(path.join(os.tmpdir(), 'suggest-smoke-'));
for (const d of fs.readdirSync(filesDir).filter((n) => /^10-\d+-\d+$/.test(n))) {
  const [, x, y] = d.split('-');
  const dir = path.join(store, 'cells/f2/10', x, y);
  fs.mkdirSync(path.join(dir, 't1'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'current.json'), JSON.stringify({ version: 't1' }));
  fs.copyFileSync(path.join(filesDir, d, 'suggest.bin'), path.join(dir, 't1', 'suggest.bin'));
}
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir: store });
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };

async function open(lang, on, viewport = { width: 1280, height: 860 }) {
  const ctx = await browser.newContext({ viewport });
  // on by default since 2026-11-18 (owner); off only through the ?debug setting ('0')
  if (!on) await ctx.addInitScript(() => { try { localStorage.setItem('onpath.suggestTest', '0'); } catch { /* expected: no storage */ } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(`${url}index.html?lang=${lang}&data=${encodeURIComponent(url + 'data/')}`);
  await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 });
  await page.evaluate(([la, lo]) => window.__map.jumpTo({ center: [lo, la], zoom: 14 }), HILDEN);
  await page.waitForTimeout(500);
  return { ctx, page, errors };
}
/** The first tap's menu at Hilden: its row labels. */
async function firstMenu(page) {
  const p = await page.evaluate(([la, lo]) => { const q = window.__map.project([lo, la]), r = window.__map.getCanvas().getBoundingClientRect(); return [q.x + r.x, q.y + r.y]; }, HILDEN);
  await page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {}); await page.mouse.click(p[0], p[1]);
  await page.waitForSelector('.pointmenu');
  return page.$$eval('.pointmenu button[role=menuitem] .txt > span:first-child', (b) => b.map((x) => x.textContent));
}

try {
  // ---- switched off (the ?debug off-switch): the planner as before ----
  {
    const { ctx, page } = await open('de', false);
    const rows = await firstMenu(page);
    check(!rows.includes('Rundtouren vorschlagen') && rows[0] === 'Als Start', `switch off: no „Rundtouren vorschlagen“ (${rows.join(' · ')})`);
    check(await page.evaluate(() => !document.body.dataset.suggest), 'switch off: nothing set up');
    await ctx.close();
  }
  // ---- switched on, German ----
  {
    const { ctx, page, errors } = await open('de', true);
    const rows = await firstMenu(page);
    check(rows[0] === 'Als Start' && rows[1] === 'Als Ziel' && rows[2] === 'Rundtouren vorschlagen', `the row last in the route group, after „Als Ziel“ (${rows.join(' · ')})`);
    const apart = await page.evaluate(() => { const r = [...document.querySelectorAll('.pointmenu button')].find((b) => b.textContent.includes('Rundtouren vorschlagen'));
      return r?.previousElementSibling?.classList.contains('menudivider') ?? false; });
    check(apart, '„Rundtouren vorschlagen“ set apart by a thin line (owner 2026-11-27)');
    await page.click('.pointmenu button:nth-of-type(3)');
    await page.waitForSelector('#suggestPanel #sgOk');
    const wish = await page.evaluate(() => ({
      head: document.querySelector('#suggestPanel .sghead')?.textContent?.trim(),
      labels: [...document.querySelectorAll('#suggestPanel .sglbl')].map((x) => x.textContent),
      ranges: document.querySelectorAll('#suggestPanel .sgrange input[type=range]').length,
      chips: [...document.querySelectorAll('#suggestPanel .sgz')].map((x) => x.textContent),
      time: document.getElementById('sgTimeVal')?.textContent, up: document.getElementById('sgUpVal')?.textContent,
      buttons: [...document.querySelectorAll('#suggestPanel .sgfoot button')].map((x) => x.textContent),
      icons: document.querySelectorAll('#suggestPanel .sgrow svg').length,
    }));
    check(wish.head === 'Rundtouren vorschlagen', `wish: title (${wish.head})`);
    check(JSON.stringify(wish.labels) === JSON.stringify(['Aktivität', 'Zeit', 'Höhenmeter', 'Ziel']), `wish: rows ${wish.labels.join(' · ')}`);
    check(wish.ranges === 4, 'wish: time and ascent as two-handle range sliders');
    check(JSON.stringify(wish.chips) === JSON.stringify(['Gipfel', 'Wasser', 'Aussicht', 'Einkehr', 'Kultur']), `wish: Ziel chips ${wish.chips.join(' · ')}`);
    check(wish.time === '2–3 h' && wish.up === 'bis 400 m', `wish: pre-set from the activity (${wish.time}, ${wish.up})`);
    check(JSON.stringify(wish.buttons) === JSON.stringify(['Abbrechen', 'OK']) && wish.icons === 0, `wish: ${wish.buttons.join(' · ')}, no icons`);
    // a slider moves its value
    await page.$eval('#sgTime .hi', (el) => { el.value = '4'; el.dispatchEvent(new Event('input')); });
    check(await page.textContent('#sgTimeVal') === '2–4 h', 'wish: the upper time handle moves the range');
    await page.$eval('#sgTime .hi', (el) => { el.value = '3'; el.dispatchEvent(new Event('input')); });
    const t0 = Date.now();
    await page.click('#sgOk');
    const waited = await page.waitForSelector('#suggestPanel .sgwait', { timeout: 5000 }).then(() => true, () => false);
    check(waited, 'the wait card while it computes');
    await page.waitForSelector('#suggestStrip .sgcard', { timeout: 120_000 });
    const ms = Date.now() - t0;
    const cards = await page.$$eval('#suggestStrip .sgcard', (cs) => cs.map((c) => ({
      badge: c.querySelector('.sgbadge')?.textContent, name: c.querySelector('.sgname')?.textContent,
      stats: c.querySelector('.sgstats')?.textContent, green: c.querySelectorAll('.sgpill[class*=" g"]').length,
      yellow: c.querySelectorAll('.sgpill[class*=" y"]').length, on: c.classList.contains('on') })));
    check(cards.length >= 1 && cards.length <= 5, `${cards.length} cards in ${ms} ms: ${cards.map((c) => c.name).join(' | ')}`);
    check(cards.every((c) => c.badge === 'Vorschlag' || /^Ausgeschildert · /.test(c.badge ?? '')), `badges ${[...new Set(cards.map((c) => c.badge))].join(', ')}`);
    check(cards.every((c) => /^\d+,\d km · \d+:\d\d h · ↑ \d+ m$/.test(c.stats ?? '')), `km · h · ↑ in German (${cards[0]?.stats})`);
    check(cards.every((c) => c.green <= 2 && c.yellow <= 2), 'at most 2 green + 2 yellow pills per card');
    check(cards[0].on && cards.filter((c) => c.on).length === 1, 'the first card is the chosen one');
    const lay = await page.evaluate(() => {
      const st = document.getElementById('suggestStrip').getBoundingClientRect(), sh = document.getElementById('sheet');
      const stage = document.getElementById('stage').getBoundingClientRect(), row = document.querySelector('#suggestStrip .sgcards');
      const cs = [...row.querySelectorAll('.sgcard')].map((c) => c.getBoundingClientRect());
      return { bottomGap: stage.bottom - st.bottom, rightOfSheet: st.left >= sh.getBoundingClientRect().right, plan: !sh.classList.contains('suggest'),
        oneRow: cs.every((r) => Math.abs(r.top - cs[0].top) < 2), snap: getComputedStyle(row).scrollSnapType,
        takes: row.querySelectorAll('.sgtake').length, head: document.querySelector('#suggestStrip .sghead')?.textContent?.replace(/\s+/g, ' ').trim() };
    });
    check(lay.plan && lay.rightOfSheet && lay.bottomGap >= 0 && lay.bottomGap < 60, `the row along the map's bottom edge, the side panel stays the plan (${lay.bottomGap} px)`);
    check(lay.oneRow && /x/.test(lay.snap) && lay.takes === cards.length, `cards side by side, scroll-snap (${lay.snap}), „Übernehmen“ on each`);
    check(/^\d Rundtouren .*Wunsch ändern ✕$/.test(lay.head ?? ''), `small head (${lay.head})`);
    if (process.env.SHOTS) { await page.waitForTimeout(800); await page.screenshot({ path: process.env.SHOTS + '/suggest-wide.png' }); } // SHOTS=<dir>: pictures to look at
    const lines = () => page.evaluate(async () => ((await window.__map.getSource('suggest-loop')?.getData())?.features ?? [])
      .filter((f) => f.geometry.type === 'LineString').map((f) => f.geometry.coordinates.length));
    const l1 = await lines();
    check(l1.length === 1 && l1[0] > 10, `only the chosen loop on the map (${l1.length} line, ${l1[0]} points)`);
    if (cards.length > 1) {
      await page.click('#suggestStrip .sgcard:nth-of-type(2) .sgname');
      await page.waitForTimeout(300);
      const on = await page.$$eval('#suggestStrip .sgcard', (cs) => cs.map((c) => c.classList.contains('on')));
      const l2 = await lines();
      check(on[1] && on.filter(Boolean).length === 1 && l2.length === 1 && l2[0] !== l1[0], 'a click on another card shows its loop, alone');
    }
    // the eye with a suggestion shown and an empty plan (owner 2026-11-20): usable; Space held hides the loop and its
    // start dot (the cards stay), let go shows them again
    {
      const vis = () => page.evaluate(() => ['suggest-loop-casing', 'suggest-loop', 'suggest-start']
        .map((id) => window.__map.getLayoutProperty(id, 'visibility') ?? 'visible'));
      check(await page.evaluate(() => window.__state.points.length === 0) && await page.isEnabled('#eye'), 'suggestion shown, empty plan: the eye is usable');
      await page.evaluate(() => (document.activeElement)?.blur?.());
      await page.keyboard.down(' ');
      const held = await vis();
      const cardsHeld = await page.isVisible('#suggestStrip .sgcard.on');
      await page.keyboard.up(' ');
      const back = await vis();
      check(held.every((v) => v === 'none') && cardsHeld, `Space held: the loop hidden, the cards stay (${held.join(', ')})`);
      check(back.every((v) => v === 'visible'), `Space let go: the loop back (${back.join(', ')})`);
    }
    // „Übernehmen“ → a normal plan, round trip
    await page.click('#suggestStrip .sgcard.on .sgtake');
    const planned = await page.waitForFunction(() => !document.getElementById('suggestStrip') && document.querySelector('#summary .big')
      && document.body.dataset.planning !== '1' && window.__state.points.length >= 3, null, { timeout: 120_000 }).then(() => true, () => false);
    const st = await page.evaluate(() => ({ n: window.__state.points.length, round: window.__state.roundTrip,
      first: window.__state.points[0], last: window.__state.points[window.__state.points.length - 1] }));
    check(planned && st.round, `„Übernehmen“ → a normal round-trip plan (${st.n} points, round trip ${st.round})`);
    // the plan has points now: „Rundtour von hier“ never again (owner 2026-11-18: only for the very first point)
    {
      const p = await page.evaluate(([la, lo]) => { const q = window.__map.project([lo + 0.01, la + 0.006]), r = window.__map.getCanvas().getBoundingClientRect(); return [q.x + r.x, q.y + r.y]; }, HILDEN);
      await page.waitForFunction(() => !document.querySelector('.waitveil') && window.__map?.areTilesLoaded() !== false, null, { timeout: 60_000 }).catch(() => {}); await page.mouse.click(p[0], p[1]);
      await page.waitForTimeout(1500);
      const rows = await page.$$eval('.pointmenu button[role=menuitem] .txt > span:first-child', (b) => b.map((x) => x.textContent));
      check(!rows.includes('Rundtouren vorschlagen') && !(await page.evaluate(() => document.body.textContent.includes('Rundtouren vorschlagen'))),
        `plan with points: no „Rundtouren vorschlagen“ (menu: ${rows.join(' · ') || 'none'})`);
      await page.keyboard.press('Escape');
    }
    const bad = errors.filter((e) => !/Failed to load resource|favicon|\[Problems\] (TRACE|INFO)/.test(e));
    check(bad.length === 0, `no page errors (${bad.slice(0, 3).join(' | ')})`);
    await ctx.close();
  }
  // ---- a phone width: the strip in the sheet's place, one card in front, a swipe chooses ----
  {
    const { ctx, page, errors } = await open('de', true, { width: 390, height: 844 });
    await firstMenu(page);
    await page.click('.pointmenu button:nth-of-type(2)');
    await page.waitForSelector('#suggestPanel #sgOk');
    await page.click('#sgOk');
    await page.waitForSelector('#suggestStrip .sgcard', { timeout: 120_000 });
    const ph = await page.evaluate(() => {
      const st = document.getElementById('suggestStrip').getBoundingClientRect(), row = document.querySelector('#suggestStrip .sgcards');
      const cs = [...row.querySelectorAll('.sgcard')].map((c) => c.getBoundingClientRect());
      return { n: cs.length, left: st.left, right: innerWidth - st.right, bottom: innerHeight - st.bottom, h: st.height,
        sheet: getComputedStyle(document.getElementById('sheet')).display, peek: cs[1] ? innerWidth - cs[1].left : 0,
        dots: document.querySelectorAll('#suggestStrip .sgdots i').length };
    });
    check(ph.left === 0 && ph.right === 0 && ph.bottom === 0 && ph.sheet === 'none' && ph.h < 844 * 0.45,
      `phone: a low strip at the bottom in the sheet's place (${Math.round(ph.h)} px high)`);
    // the text block like the app's cards (F-PLAN-29): a cut line shows „⌄ mehr“; a click on the chosen card's text opens
    // it whole with „⌃ weniger“, a second click closes it; „Übernehmen“ untouched
    {
      await page.waitForTimeout(300);
      const st0 = await page.evaluate(() => {
        const c = document.querySelector('#suggestStrip .sgcard.on'), m = c?.querySelector('.sgmore');
        const cut = [...(c?.querySelectorAll('.sgname, .sgstats, .sglt') ?? [])].some((x) => x.scrollWidth > x.clientWidth + 1);
        return { cut, shown: !!m && !m.hidden, text: m?.textContent ?? '', exp: m?.getAttribute('aria-expanded') };
      });
      check(st0.shown === st0.cut && (!st0.cut || (st0.text === '⌄ mehr' && st0.exp === 'false')),
        `phone: a cut text shows „⌄ mehr“ (cut ${st0.cut}, „${st0.text}“)`);
      if (st0.cut) {
        await page.click('#suggestStrip .sgcard.on .sgline .sglt');
        const st1 = await page.evaluate(() => {
          const c = document.querySelector('#suggestStrip .sgcard.on'), m = c?.querySelector('.sgmore');
          return { open: c?.classList.contains('open'), text: m?.textContent ?? '', exp: m?.getAttribute('aria-expanded'),
            ws: getComputedStyle(c.querySelector('.sglt')).whiteSpace, onCount: document.querySelectorAll('#suggestStrip .sgcard.on').length };
        });
        check(st1.open && st1.text === '⌃ weniger' && st1.exp === 'true' && st1.ws === 'normal' && st1.onCount === 1,
          `phone: a click on the chosen card's text shows it whole with „⌃ weniger“ (${JSON.stringify(st1)})`);
        await page.click('#suggestStrip .sgcard.on .sgline .sglt');
        check(await page.evaluate(() => !document.querySelector('#suggestStrip .sgcard.on').classList.contains('open')
          && document.querySelector('#suggestStrip .sgcard.on .sgmore').textContent === '⌄ mehr'), 'phone: a second click closes it again');
      }
    }
    if (ph.n > 1) {
      check(ph.peek >= 10 && ph.peek <= 30 && ph.dots === ph.n, `phone: the next card looks in (${Math.round(ph.peek)} px), ${ph.dots} dots`);
      const lines = () => page.evaluate(async () => ((await window.__map.getSource('suggest-loop')?.getData())?.features ?? [])
        .filter((f) => f.geometry.type === 'LineString').map((f) => JSON.stringify(f.geometry.coordinates[Math.floor(f.geometry.coordinates.length / 2)])));
      const l1 = await lines();
      await page.$eval('#suggestStrip .sgcards', (row) => row.scrollTo({ left: row.querySelectorAll('.sgcard')[1].offsetLeft - row.querySelector('.sgcard').offsetLeft }));
      await page.waitForTimeout(600);
      const st = await page.evaluate(() => ({ on: [...document.querySelectorAll('#suggestStrip .sgcard')].map((c) => c.classList.contains('on')),
        dot: [...document.querySelectorAll('#suggestStrip .sgdots i')].findIndex((d) => d.classList.contains('on')) }));
      const l2 = await lines();
      if (process.env.SHOTS) await page.screenshot({ path: process.env.SHOTS + '/suggest-phone.png' });
      check(st.on[1] && st.on.filter(Boolean).length === 1 && st.dot === 1 && l2.length === 1 && l2[0] !== l1[0], 'phone: a swipe to the second card shows its loop, alone');
    }
    await page.click('#suggestStrip #sgClose');
    check(await page.evaluate(() => !document.getElementById('suggestStrip') && getComputedStyle(document.getElementById('sheet')).display !== 'none'),
      'phone: ✕ gives the sheet back');
    const bad = errors.filter((e) => !/Failed to load resource|favicon|\[Problems\] (TRACE|INFO)/.test(e));
    check(bad.length === 0, `phone: no page errors (${bad.slice(0, 3).join(' | ')})`);
    await ctx.close();
  }
  // ---- English words ----
  {
    const { ctx, page } = await open('en', true);
    const rows = await firstMenu(page);
    check(rows[2] === 'Suggest round trips', `English: ${rows.join(' · ')}`);
    await page.click('.pointmenu button:nth-of-type(3)');
    await page.waitForSelector('#suggestPanel #sgOk');
    const chips = await page.$$eval('#suggestPanel .sgz', (b) => b.map((x) => x.textContent));
    check(chips.join(',') === 'Summit,Water,View,Food stop,Culture', `English chips ${chips.join(' · ')}`);
    await page.click('#sgCancel');
    check(await page.evaluate(() => !document.getElementById('suggestPanel')), 'Abbrechen gives the sheet back');
    await ctx.close();
  }
} finally {
  await browser.close();
  server.close();
  fs.rmSync(store, { recursive: true, force: true });
}
console.log(fails.length ? `\n${fails.length} check(s) failed` : '\nsuggest-smoke: all checks passed');
process.exit(fails.length ? 1 : 0);
