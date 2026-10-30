// The plan sheet's three steps on both widths (owner 2026-10-30, option A; docs/design/web-planner.md §11.3 – the app's
// PlanSize): small = summary row (chip, time, distance, ↑/↓, badge, ⋯, chevron, a compact Share), profile = + profile,
// points = + Points. Grab handle (the app's handle tap), chevron (Show more / Show less), a click on the summary, Arrow
// keys; remembered after a reload; the camera fit leaves the sheet free; the place card, the GPX track panel and the
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

async function open(vp, phone) {
  const ctx = await browser.newContext({ viewport: vp, isMobile: phone, hasTouch: phone });
  await ctx.route('https://photon.komoot.io/**', (r) => r.fulfill({ body: JSON.stringify(photon), contentType: 'application/json' }));
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
  check(!/Send to phone|Pair phone/.test(header) && /Import GPX/.test(phone ? await page.textContent('#mainMenu') : header),
    `${P} header without Send to phone / Pair phone (owner 2026-10-30)`);

  // the three steps, every control
  if (await step(page) !== 'small') { // reach small with the chevron (Show more … until Points, then Show less → small)
    for (let i = 0; i < 3 && await step(page) !== 'small'; i++) await page.click('#sheetStep');
  }
  let s = await shown(page);
  check(await step(page) === 'small' && s['#summary .big'] && s['#sheet .subline'] && !s['#profile svg'] && !s['#points'],
    `${P} small: time, distance, ↑/↓ + badge; no profile, no Points`);
  check(s['#share'] && await page.getAttribute('#share', 'aria-label') === 'Share' && await page.getAttribute('#share', 'title') === 'Share' && !(await page.$('#sheet button.primary')),
    `${P} small: Share is the icon next to ⋯ (label + tooltip), no big green button`);
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

  // a new error line shows in the small step (the app keeps route lines above its MINI step end)
  await page.evaluate(() => { const n = document.getElementById('notice'); n.hidden = false; n.textContent = '⚠ test'; });
  check(await page.isVisible('#notice'), `${P} the red route line is visible in the small step`);
  await page.evaluate(() => { document.getElementById('notice').hidden = true; });

  // the place card still works in the small step
  await page.fill('#q', 'Ifinger');
  await page.waitForSelector('#results .hit', { timeout: 30_000 });
  await page.click('#results .hit');
  await page.waitForSelector('#placecard:not([hidden]) #cdest', { timeout: 20_000 });
  const card = await page.locator('#placecard').boundingBox(), sheet = await page.locator('#sheet').boundingBox().catch(() => null);
  check(phone ? await page.isHidden('#sheet') && card.y + card.height >= vp.height - 2 : card.x >= sheet.x + sheet.width,
    `${P} place card ${phone ? 'stands in for the sheet at the bottom' : 'beside the small sheet'}`);
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
  check(await page.isHidden('#share') && !(await page.$('#sheet button.primary')), 'empty plan: no Share, no big button');
  await page.waitForSelector("#summary .title", { timeout: 20_000 }).catch(() => {});
  const title = await page.locator('#summary .title').boundingBox();
  check(title && title.height < 40, `empty plan: "Plan a route" on one line (${Math.round(title?.height ?? 0)} px)`);
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
