// Pictures: the countdown to the phone's next update (3 variants). Same placeholder method as make.mjs (page DOM + live.css +
// Manrope, fake data, map = plain placeholder). Usage: node countdown.mjs
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
const NEXT = 120; // head.nextS while moving
const mmss = (s) => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');

/** v = 1|2|3; left = seconds until the next expected update (0 = overdue); age = seconds since the last report. */
const html = (v, left, age) => {
  const f = Math.min(1, left / NEXT), overdue = left <= 0;
  const ageText = age < 60 ? `${age} s` : `${Math.floor(age / 60)} Min. ${age % 60} s`;
  const line = 'Unterwegs · zuletzt vor ' + ageText;
  const sub = 'zuletzt vor ' + ageText;
  const R = 20, C = 2 * Math.PI * R;
  const v1 = v === 1 ? `<div style="height:4px;background:#e3e8de"><div style="height:4px;width:100%;background:var(--go);transform-origin:right;transform:scaleX(${f})"></div></div>
    <div style="display:flex;justify-content:flex-end;padding:3px 16px 4px;font-size:12px;color:${overdue ? 'var(--warn)' : 'var(--muted)'};font-weight:600;background:var(--paper);border-bottom:1px solid var(--line)"><span></span><span>${overdue ? 'wartet auf das Handy …' : 'nächster Punkt in ' + mmss(left)}</span></div>` : '';
  const ring = v === 2 ? `<circle cx="262" cy="255" r="${R}" fill="none" stroke="#1E3FAE" stroke-opacity=".18" stroke-width="3"/>
    <circle cx="262" cy="255" r="${R}" fill="none" stroke="#1E3FAE" stroke-opacity=".5" stroke-width="3" stroke-linecap="round" stroke-dasharray="${C * f} ${C}" transform="rotate(-90 262 255)"/>` : '';
  const pill = v === 3 ? `<div style="position:absolute;left:12px;bottom:12px;background:var(--paper);border-radius:16px;padding:7px 12px 8px;box-shadow:0 2px 8px var(--shadow);font-size:13px;font-weight:700;color:${overdue ? 'var(--warn)' : 'var(--fg)'}">${overdue ? 'wartet auf das Handy …' : 'nächster Punkt ~ ' + mmss(left)}
    <div style="margin-top:5px;height:3px;border-radius:2px;background:#e3e8de"><div style="height:3px;border-radius:2px;width:${f * 100}%;background:var(--go)"></div></div></div>` : '';
  const sub2 = v === 1 ? '' : '';
  return `<!doctype html><html lang="de"><head><meta charset="utf-8"><link rel="stylesheet" href="file://${app}/fonts/manrope.css"><link rel="stylesheet" href="file://${app}/live/live.css"></head>
<body><div id="live"><header id="top" style="${v === 1 ? 'border-bottom:none' : ''}"><div id="title">Geteilter Standort · Wandern</div>
<div id="status" class="status" data-tone="${overdue ? 'amber' : 'good'}"><span class="dot"></span><span id="line">${line}</span></div></header>${v1}
<div id="mapbox"><svg style="position:absolute;inset:0;width:100%;height:100%" viewBox="0 0 412 600" preserveAspectRatio="xMidYMid slice">
 <path d="${planned}" fill="none" stroke="#fff" stroke-width="8" stroke-linecap="round"/><path d="${planned}" fill="none" stroke="#2E8B57" stroke-width="4" stroke-linecap="round" stroke-dasharray="1 7"/>
 <path d="${walked}" fill="none" stroke="#fff" stroke-width="10" stroke-linecap="round"/><path d="${walked}" fill="none" stroke="#1E3FAE" stroke-width="6" stroke-linecap="round"/>
 ${ring}<circle cx="262" cy="255" r="9" fill="#1E3FAE" stroke="#fff" stroke-width="3"/></svg>
<div style="position:absolute;right:8px;top:8px;font-size:11px;color:#5f645e;background:#fffc;padding:2px 6px;border-radius:4px">Karte: Platzhalter</div>${pill}</div>
<section id="facts"><div class="row strong">1,1 km · 35 min · ↑ 85 m</div><div class="row">noch 1,3 km</div><div class="row">Handy-Akku 43 %</div>
<div id="place" class="row"><span id="placeText">Letzter Ort 15:08 · 46.69567, 11.24153</span><span class="acts"><button class="act">Koordinaten kopieren</button><a class="act">In Karten-App öffnen</a></span></div></section>
<footer id="foot">Kein Notruf-Dienst – im Notfall 112.</footer></div></body></html>`;
};
const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome' });
async function shot(name, v, left, age) {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, locale: 'de-DE' });
  const p = await ctx.newPage(); const f = path.join(here, '.tmp-' + name + '.html'); fs.writeFileSync(f, html(v, left, age));
  await p.goto('file://' + f); await p.evaluate(() => document.fonts.ready);
  await p.screenshot({ path: path.join(here, '.' + name + '.png') }); fs.unlinkSync(f); await ctx.close();
}
async function pair(out, names) {
  const pg = await (await browser.newContext({ viewport: { width: 412 * names.length + 20 * (names.length + 1), height: 975 } })).newPage();
  const sf = path.join(here, '.tmp-pair.html');
  fs.writeFileSync(sf, `<body style="margin:0;background:#e8e8e8;display:flex;gap:20px;padding:20px">${names.map((n) => `<img src="file://${here}/.${n}.png" style="width:412px;border:1px solid #ccc">`).join('')}</body>`);
  await pg.goto('file://' + sf); await pg.waitForTimeout(400); await pg.screenshot({ path: path.join(here, out) }); fs.unlinkSync(sf);
}
for (const v of [1, 2, 3]) { await shot(`a${v}`, v, 115, 5); await shot(`b${v}`, v, 20, 100); await pair(`countdown-v${v}.png`, [`a${v}`, `b${v}`]); }
await shot('o1', 1, 0, 140); await shot('o3', 3, 0, 140);
await pair('countdown-overdue.png', ['o1']);
await pair('countdown-sheet.png', ['a1', 'a2', 'a3']);
for (const f of fs.readdirSync(here)) if (/^\.[abo]\d\.png$/.test(f)) fs.unlinkSync(path.join(here, f));
await browser.close();
