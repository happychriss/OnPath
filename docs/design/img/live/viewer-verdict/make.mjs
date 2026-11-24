// Pictures of the viewer page's new top line (verdict + arrival). Not the live page: its Wasm engine needs a Gradle build,
// so this renders the page's own DOM + live.css + Manrope with fake data; the map is a plain placeholder (sage + drawn lines).
// Usage: node make.mjs   (needs playwright-core and a Chromium; paths below)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire('/tmp/render/');
const { chromium } = require('playwright-core');
const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.resolve(here, '../../../../../web/app/src');

const planned = 'M-10 520 C 90 470, 120 380, 200 360 S 262 310, 262 255 S 250 150, 330 100 S 400 60, 430 40';
const walked = 'M-10 520 C 90 470, 120 380, 200 360 S 262 310, 262 255';
const cases = [
  { file: '1-alles-im-plan', tone: 'good', line: 'Alles im Plan · Ankunft ca. 16:40', plan: true, dot: 'live',
    numbers: '1,1 km · 35 min · ↑ 85 m', togo: 'noch 1,3 km', battery: 'Handy-Akku 43 %', place: 'Letzter Ort 15:08 · 46.69567, 11.24153' },
  { file: '2-kein-kontakt', tone: 'grey', line: 'Kein Kontakt seit 25 Min. · letzter Ort 14:02', plan: true, dot: 'quiet',
    numbers: '1,3 km · 40 min · ↑ 100 m', togo: 'noch 1,1 km', battery: 'Handy-Akku 36 %', place: 'Letzter Ort 14:02 · 46.69567, 11.24153' },
  { file: '3-alles-klar-unterwegs', tone: 'good', line: 'Alles klar · unterwegs', plan: false, dot: 'live',
    numbers: '1,1 km · 35 min · ↑ 85 m', togo: null, battery: 'Handy-Akku 43 %', place: 'Letzter Ort 15:08 · 46.69567, 11.24153' },
];
const html = (c) => {
  const col = c.dot === 'quiet' ? '#676B66' : '#1E3FAE';
  return `<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="file://${app}/fonts/manrope.css"><link rel="stylesheet" href="file://${app}/live/live.css"></head>
<body><div id="live"><header id="top"><div id="title">Geteilter Standort · Wandern</div>
<div id="status" class="status" data-tone="${c.tone}"><span class="dot"></span><span id="line">${c.line}</span></div></header>
<div id="mapbox"><svg style="position:absolute;inset:0;width:100%;height:100%" viewBox="0 0 412 600" preserveAspectRatio="xMidYMid slice">
 ${c.plan ? `<path d="${planned}" fill="none" stroke="#fff" stroke-width="8" stroke-linecap="round"/><path d="${planned}" fill="none" stroke="#2E8B57" stroke-width="4" stroke-linecap="round" stroke-dasharray="1 7"/>` : ''}
 <path d="${walked}" fill="none" stroke="#fff" stroke-width="10" stroke-linecap="round"/>
 <path d="${walked}" fill="none" stroke="${col}" stroke-width="6" stroke-linecap="round"/>
 <circle cx="262" cy="255" r="9" fill="${col}" stroke="#fff" stroke-width="3"/>
</svg><div style="position:absolute;right:8px;top:8px;font-size:11px;color:#5f645e;background:#fffc;padding:2px 6px;border-radius:4px">Karte: Platzhalter</div></div>
<section id="facts"><div class="row strong">${c.numbers}</div>${c.togo ? `<div class="row">${c.togo}</div>` : ''}<div class="row">${c.battery}</div>
<div id="place" class="row"><span id="placeText">${c.place}</span><span class="acts"><button class="act">Koordinaten kopieren</button><a class="act">In Karten-App öffnen</a></span></div></section>
<footer id="foot">Kein Notruf-Dienst – im Notfall 112.</footer></div></body></html>`;
};
const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome' });
for (const c of cases) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, locale: 'de-DE' });
  const p = await ctx.newPage();
  const f = path.join(here, '.tmp-' + c.file + '.html'); fs.writeFileSync(f, html(c));
  await p.goto('file://' + f); await p.evaluate(() => document.fonts.ready);
  await p.screenshot({ path: path.join(here, c.file + '.png') }); fs.unlinkSync(f); await ctx.close();
}
// side-by-side sheet
const sheet = await (await browser.newContext({ viewport: { width: 1320, height: 960 }, deviceScaleFactor: 1 })).newPage();
const imgs = cases.map((c) => `<img src="file://${here}/${c.file}.png" style="width:412px;border:1px solid #ccc">`).join('');
const sf = path.join(here, '.tmp-sheet.html');
fs.writeFileSync(sf, `<body style="margin:0;background:#e8e8e8;display:flex;gap:20px;padding:20px">${imgs}</body>`);
await sheet.goto('file://' + sf); await sheet.waitForTimeout(500);
await sheet.screenshot({ path: path.join(here, 'sheet.png') }); fs.unlinkSync(sf);
await browser.close();
