// Tour suggestions – the debug setting "Tourvorschläge (Test)" and the mechanism `remote` (src/suggest/testsetting.ts,
// remote.ts; the owner's test on the road, 2026-11-14) in headless Chromium, against a FAKE remote (a local test
// server with a password and CORS, standing in for tools/suggest-poc/remote/remote_server.py).
// (a) off: no button without ?debug, with ?debug the button but still two menu rows, no request, no storage key;
// (b) the setting: "Verbindung prüfen" says unreachable / wrong password / connected; OK stores one key and reloads;
// (c) on: "Rundtour von hier" → the wish → OK → cards from the remote → Übernehmen → a normal round-trip plan; the
//     request carries the password in its header and the wish, nothing else;
// (d) wrong password / computer away / map being prepared → one calm message each, the plan untouched;
// (e) a set-up link fills the setting, stores nothing before OK;
// (f) switched off again: every key of the feature is gone, two rows, no button without ?debug.
// Usage: node test/suggest-remote-smoke.mjs [segmentsDir] [mapStoreDir] [screenshotDir]
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';
import { serve } from './serve.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const segments = path.resolve(process.argv[2] ?? '/home/user/build/routing-test/segments');
const dataDir = process.argv[3] && process.argv[3] !== '-' ? path.resolve(process.argv[3]) : null;
const shots = process.argv[4] ? path.resolve(process.argv[4]) : null;
if (shots) fs.mkdirSync(shots, { recursive: true });
const log = [];
const { server, url } = await serve({ dist: path.resolve(here, '../dist'), segments, dataDir, log });
const START = [11.1600, 46.6700]; // Meran
const TOKEN = 'test-password-123';

// ---- the fake remote ----
const remote = { mode: 'ok', calls: [] };
function loop(lon, lat, bearingDeg, km) { // a circle through the start, as a line [lon, lat] …
  const r = km / 1.35 / (2 * Math.PI), kmLat = 111.32, kmLon = 111.32 * Math.cos((lat * Math.PI) / 180), b = (bearingDeg * Math.PI) / 180;
  const cLat = lat + (r * Math.cos(b)) / kmLat, cLon = lon + (r * Math.sin(b)) / kmLon, back = b + Math.PI, out = [];
  for (let i = 0; i <= 24; i++) { const a = back + (i / 24) * 2 * Math.PI; out.push([cLon + (r * Math.sin(a)) / kmLon, cLat + (r * Math.cos(a)) / kmLat]); }
  return out;
}
const fake = http.createServer(async (req, res) => {
  const origin = req.headers.origin;
  const cors = origin ? { 'Access-Control-Allow-Origin': origin, 'Access-Control-Allow-Headers': 'X-OnPath-Token, Content-Type', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', Vary: 'Origin' } : {};
  const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json', ...cors }); res.end(JSON.stringify(obj)); };
  if (req.method === 'OPTIONS') { res.writeHead(204, cors); res.end(); return; }
  const chunks = []; for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks).toString('utf8') : '';
  remote.calls.push({ method: req.method, url: req.url, token: req.headers['x-onpath-token'] ?? null, body });
  if (req.headers['x-onpath-token'] !== TOKEN) return send(401, { error: 'token' });
  if (req.url.endsWith('/v1/ping')) return send(200, { ok: true, busy: false });
  if (!req.url.endsWith('/v1/suggest')) return send(404, { error: 'not found' });
  if (remote.mode === 'preparing') return send(200, { preparing: true, message: 'Die Karte für diese Gegend wird vorbereitet – gleich noch einmal versuchen.', message_en: 'The map is being prepared.' });
  const w = JSON.parse(body);
  const km = w.hours * 4;
  send(200, { method: 'b', seconds: 0.1, candidates: [0, 120, 240].map((b, i) => ({ id: i + 1, name: `Testrunde ${i + 1}`, chosen: true,
    coords: loop(w.start[0], w.start[1], b, km), numbers: { km, forest_pct: 50, known: [['XX · Testweg', 2.5, 'rwn']] }, tips: [] })) });
});
await new Promise((r) => fake.listen(0, '127.0.0.1', r));
const REMOTE = `http://127.0.0.1:${fake.address().port}/`;
const dead = http.createServer(); await new Promise((r) => dead.listen(0, '127.0.0.1', r));
const DEAD = `http://127.0.0.1:${dead.address().port}/`; dead.close(); // an address nobody listens on

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const fails = [];
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) fails.push(what); };
const errors = [];
const ready = async (page) => { await page.waitForFunction(() => document.body.dataset.engine === 'ready', null, { timeout: 60_000 }); await page.waitForTimeout(500); };
async function open(query, viewport = { width: 1280, height: 800 }, ctx = null) {
  ctx ??= await browser.newContext({ viewport });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url + 'index.html' + query);
  await ready(page);
  return page;
}
const clickAt = async (page, lon, lat) => {
  const p = await page.evaluate(([lo, la]) => { const q = window.__map.project([lo, la]); const r = window.__map.getCanvas().getBoundingClientRect(); return [q.x + r.left, q.y + r.top]; }, [lon, lat]);
  await page.mouse.click(p[0], p[1]);
};
const menuRows = (page) => page.$$eval('.pointmenu button', (bs) => bs.map((b) => b.querySelector('.txt > span')?.textContent ?? ''));
const points = (page) => page.evaluate(() => window.__state.points.length);
const keys = (page) => page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('onpath.suggest')).sort());
const shot = async (page, name) => { if (shots) await page.screenshot({ path: path.join(shots, name + '.png') }); };
const AT = '&at=11.16,46.67,13';
const msg = (page) => page.evaluate(() => document.querySelector('#msgcards .msgcard[data-id="suggest"]')?.textContent ?? '');
async function askLoops(page) {
  await clickAt(page, ...START); await page.waitForSelector('.pointmenu');
  await page.click('.pointmenu button:nth-of-type(3)'); await page.waitForSelector('#suggestPanel .sgok');
  await page.click('#suggestPanel .sg[data-name=hours] button[data-v="1"]');
  await page.click('#suggestPanel .sgok');
}

// ---- (a) off ----
{
  const page = await open('?lang=de' + AT);
  check(!(await page.$('#suggestTestBtn')), 'off, no ?debug: no test button');
  await page.context().close();
  const p2 = await open('?lang=de&debug' + AT);
  check(await page.isClosed() && !!(await p2.$('#suggestTestBtn')), 'off, ?debug: the button "Tourvorschläge (Test)" is there');
  check((await p2.textContent('#suggestTestBtn')) === 'Tourvorschläge (Test)', 'the button says "Tourvorschläge (Test)"');
  await clickAt(p2, ...START); await p2.waitForSelector('.pointmenu');
  check((await menuRows(p2)).length === 2, 'off, ?debug: the first-tap menu still has its two rows');
  await p2.keyboard.press('Escape');
  check((await keys(p2)).length === 0 && !(await p2.evaluate(() => document.body.dataset.suggestReady)), 'off: no storage key, the feature is not set up');
  check(remote.calls.length === 0, 'off: no request to any remote');

  // ---- (b) the setting ----
  await p2.click('#suggestTestBtn');
  await p2.waitForSelector('dialog#suggestTest[open]');
  const labels = await p2.$$eval('dialog#suggestTest label', (ls) => ls.map((l) => l.querySelector('span').textContent));
  check(JSON.stringify(labels) === '["Eingeschaltet","Rechner","Passwort"]', `the setting: switch, Rechner, Passwort (${labels.join(' · ')})`);
  check(JSON.stringify(await p2.$$eval('dialog#suggestTest .row.end button', (b) => b.map((x) => x.textContent))) === '["Abbrechen","OK"]', 'the setting: Abbrechen · OK');
  await shot(p2, 'r1-setting');
  const ping = async () => { await p2.click('#stPing'); await p2.waitForFunction(() => { const t = document.getElementById('stResult').textContent; return t && t !== '…'; }, null, { timeout: 15_000 }); return p2.textContent('#stResult'); };
  check((await ping()) === 'Bitte die Adresse des Rechners eintragen.', 'Verbindung prüfen without an address: asks for it');
  await p2.fill('#stUrl', DEAD); await p2.fill('#stToken', TOKEN);
  check((await ping()) === 'Rechner nicht erreichbar. Ist das VPN an?', 'Verbindung prüfen, nobody there: "Rechner nicht erreichbar. Ist das VPN an?"');
  await p2.fill('#stUrl', REMOTE); await p2.fill('#stToken', 'wrong');
  check((await ping()) === 'Falsches Passwort.', 'Verbindung prüfen, wrong password: "Falsches Passwort."');
  await p2.fill('#stToken', TOKEN);
  check((await ping()) === 'Verbindung steht.', 'Verbindung prüfen, right: "Verbindung steht."');
  check((await keys(p2)).length === 0, 'nothing is stored before OK');
  await p2.check('#stOn');
  await Promise.all([p2.waitForNavigation(), p2.click('#stOk')]);
  await ready(p2);
  check(JSON.stringify(await keys(p2)) === '["onpath.suggest.remote"]', 'OK: one storage key, the page reloaded');
  check(!/debug/.test(p2.url()) && /lang=de/.test(p2.url()) && !(await p2.$('#suggestTestBtn')) && !(await p2.$('#diagCopy')),
    'switched on: the address lost ?debug (no debug buttons over the cards), the rest of the address stays');
  check(await p2.evaluate(() => document.body.dataset.suggestReady === '1'), 'on: the feature is set up after the reload');

  // ---- (c) on: the whole flow against the remote ----
  remote.calls.length = 0;
  await clickAt(p2, ...START); await p2.waitForSelector('.pointmenu');
  const rows = await menuRows(p2);
  check(rows.length === 3 && rows[2] === 'Rundtour von hier', `on: third row "Rundtour von hier" (${JSON.stringify(rows)})`);
  await p2.click('.pointmenu button:nth-of-type(3)'); await p2.waitForSelector('#suggestPanel .sgok');
  await p2.click('#suggestPanel .sg[data-name=hours] button[data-v="1"]');
  await p2.click('#suggestPanel .sgok');
  await p2.waitForSelector('#suggestPanel .sgagain', { timeout: 240_000 });
  const cards = await p2.$$eval('.sgcard', (cs) => cs.map((c) => ({ title: c.querySelector('b').textContent, facts: c.querySelector('.sgfacts').textContent, reasons: c.querySelector('.sgreasons')?.textContent ?? '' })));
  console.log('cards:', JSON.stringify(cards));
  check(cards.length >= 1 && cards.every((c) => /^Testrunde \d$/.test(c.title) && /km · .+ · ↑ \d+ m/.test(c.facts)), `cards from the remote with the planner's own numbers (${cards.length})`);
  check(cards.every((c) => /2,5 km Testweg/.test(c.reasons)), 'the remote\'s reasons are on the cards');
  check(/remote/.test(await p2.textContent('#suggestPanel .sgvia')), 'the mechanism line says remote');
  check((await p2.textContent('#suggestPanel .sgvia .sgtest')) === 'Tourvorschläge (Test)', 'the cards carry the link to the test setting (the way to switch off)');
  const post = remote.calls.filter((c) => c.method === 'POST');
  const sent = post.length ? JSON.parse(post[0].body) : {};
  check(post.length === 1 && post[0].token === TOKEN && post[0].url.endsWith('/v1/suggest'), 'one request to /v1/suggest with the password in the header');
  check(Array.isArray(sent.start) && Math.abs(sent.start[0] - START[0]) < 0.01 && sent.activity === 'hike' && sent.hours === 1 && !('method' in sent),
    `the request carries the wish (start, activity, 1 h) and no method (${Object.keys(sent).join(', ')})`);
  check(!log.some((u) => /suggest|v1\//.test(u)), 'the page\'s own host got no suggestion request');
  await shot(p2, 'r2-cards');
  await p2.click('.sgcard:first-of-type .sgadopt');
  await p2.waitForFunction(() => document.querySelector('#summary .big') && !window.__state.planning, null, { timeout: 120_000 });
  await p2.waitForTimeout(600);
  const st = await p2.evaluate(() => { const s = window.__state, p = s.points; return { n: p.length, loop: p.length > 2 && p[0][0] === p[p.length - 1][0] && p[0][1] === p[p.length - 1][1], roundTrip: s.roundTrip, canUndo: s.canUndo, hash: location.hash.slice(0, 3) }; });
  check(st.n >= 3 && st.loop && st.roundTrip && st.canUndo && st.hash === '#p=', `Übernehmen: a normal round-trip plan (${st.n} points), undoable, in the address`);
  await p2.click('#undo');
  await p2.waitForFunction(() => window.__state.points.length === 0, null, { timeout: 15_000 }).catch(() => {});
  check((await points(p2)) === 0, 'Undo: the empty plan');
  {
    // on, the page opened WITHOUT ?debug (the owner's normal use): no floating button, the link in the wish opens the setting
    const p4 = await open('?lang=de' + AT, undefined, p2.context());
    check(!(await p4.$('#suggestTestBtn')), 'on, no ?debug: no floating button on the map');
    await clickAt(p4, ...START); await p4.waitForSelector('.pointmenu');
    await p4.click('.pointmenu button:nth-of-type(3)'); await p4.waitForSelector('#suggestPanel .sgok');
    await p4.click('#suggestPanel .sgmore summary');
    await p4.click('#suggestPanel .sgtest');
    await p4.waitForSelector('dialog#suggestTest[open]');
    check(await p4.isChecked('#stOn'), 'on, no ?debug: the link in the wish ("Mehr") opens the setting');
    await p4.close();
  }

  // ---- (d) calm messages, the plan untouched ----
  const calm = async (what, expect) => {
    await askLoops(p2);
    await p2.waitForFunction((e) => (document.querySelector('#msgcards .msgcard[data-id="suggest"]')?.textContent ?? '').includes(e), expect, { timeout: 30_000 }).catch(() => {});
    const m = await msg(p2);
    const s = await p2.evaluate(() => ({ panel: !!document.getElementById('suggestPanel'), n: window.__state.points.length, cls: document.getElementById('sheet').className }));
    check(m.includes(expect) && /unverändert/.test(m), `${what}: "${expect}" + the plan is unchanged ("${m.trim().slice(0, 110)}")`);
    check(!s.panel && s.n === 0 && !/suggest/.test(s.cls), `${what}: the plan sheet is back, no point`);
    await p2.click('#msgcards .msgcard[data-id="suggest"] button'); await p2.waitForTimeout(200);
  };
  remote.mode = 'preparing';
  await calm('map being prepared', 'Die Karte für diese Gegend wird vorbereitet');
  remote.mode = 'ok';
  await p2.evaluate(([u]) => localStorage.setItem('onpath.suggest.remote', JSON.stringify({ url: u, token: 'wrong' })), [REMOTE]);
  await calm('wrong password', 'Falsches Passwort für den Rechner.');
  await p2.evaluate(([u, t]) => localStorage.setItem('onpath.suggest.remote', JSON.stringify({ url: u, token: t })), [DEAD, TOKEN]);
  await calm('computer away', 'Rechner nicht erreichbar. Ist das VPN an?');
  await p2.evaluate(([u, t]) => localStorage.setItem('onpath.suggest.remote', JSON.stringify({ url: u, token: t })), [REMOTE, TOKEN]);

  // ---- (f) switched off again ----
  check((await keys(p2)).includes('onpath.suggest.wish'), 'on: the wish was remembered (a second key of the group)');
  await clickAt(p2, ...START); await p2.waitForSelector('.pointmenu');
  await p2.click('.pointmenu button:nth-of-type(3)'); await p2.waitForSelector('#suggestPanel .sgok');
  await p2.click('#suggestPanel .sgmore summary');
  await p2.click('#suggestPanel .sgtest'); await p2.waitForSelector('dialog#suggestTest[open]');
  check(await p2.isChecked('#stOn') && (await p2.inputValue('#stUrl')) === REMOTE, 'the setting (from the wish\'s link) shows on + the address');
  await p2.uncheck('#stOn');
  await Promise.all([p2.waitForNavigation(), p2.click('#stOk')]);
  await ready(p2);
  check((await keys(p2)).length === 0, 'off again: every key of the feature is gone');
  await clickAt(p2, ...START); await p2.waitForSelector('.pointmenu');
  check((await menuRows(p2)).length === 2 && !(await p2.evaluate(() => document.body.dataset.suggestReady)), 'off again: two rows, the feature is not set up');
  await p2.keyboard.press('Escape');
  const p3 = await open('?lang=de' + AT, undefined, p2.context());
  check(!(await p3.$('#suggestTestBtn')), 'off again, no ?debug: no test button');
  await p2.context().close();
}

// ---- (e) a set-up link ----
{
  const page = await open('?lang=de&debug' + AT + '#suggest-test=' + encodeURIComponent(REMOTE + '|' + TOKEN));
  await page.waitForSelector('dialog#suggestTest[open]');
  check((await page.inputValue('#stUrl')) === REMOTE && (await page.inputValue('#stToken')) === TOKEN && await page.isChecked('#stOn'), 'a set-up link fills address + password, switch on');
  check((await keys(page)).length === 0, 'a set-up link stores nothing before OK');
  await page.click('#stCancel'); await page.waitForTimeout(200);
  check((await keys(page)).length === 0 && !(await page.$('dialog#suggestTest')), 'Abbrechen: nothing stored');
  await page.context().close();
}

// ---- phone width: the setting fits ----
{
  const page = await open('?lang=de&debug' + AT, { width: 412, height: 915 });
  await page.click('#suggestTestBtn'); await page.waitForSelector('dialog#suggestTest[open]');
  const box = await page.evaluate(() => { const d = document.getElementById('suggestTest'), r = d.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, scroll: d.scrollHeight > d.clientHeight + 1 }; });
  check(box.top >= 0 && box.bottom <= 915 && box.left >= 0 && box.right <= 412 && !box.scroll, `phone: the setting fits the screen (${Math.round(box.bottom - box.top)} px high)`);
  await shot(page, 'r3-phone-setting');
  await page.context().close();
}

await browser.close();
server.close(); fake.close();
if (errors.length) console.log('page errors:', errors);
check(errors.length === 0, 'no page errors');
if (fails.length) { console.error(`FAILED (${fails.length}): ` + fails.join(' | ')); process.exit(1); }
console.log('suggest-remote-smoke: green');
