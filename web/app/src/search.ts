// Search like the app's (ui/search/SearchSession): offline on every keystroke – the app's index and ranking in the
// engine, over the map areas in view – and online (Photon, OpenStreetMap data) after 350 ms without typing for 3+
// letters, mixed in below the rows already shown. A pick opens the place card: one button that says what adding does
// ("Set as destination", "Add as stop 2", … – the app's AddPreview), "As start", "As destination" (left out when the
// main button does the same), "Google" (ui/search/PlaceCard).
import * as maplibregl from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Hit } from './engine/types';
import { icon } from './icons';
import { t } from './i18n';
import { boxesOf, NO_PADDING, offsetTo, placeCard, pointTarget } from './cardanchor';

export type PlaceRole = 'SMART' | 'START' | 'DESTINATION' | 'REMOVE';

export interface SearchHost {
  map: maplibregl.Map;
  engine: EngineClient;
  /** The map areas to search (cells in view, or the one map folder). */
  areas(): Promise<{ key: string; dir: string }[]>;
  points(): [number, number][];
  route(): [number, number][];
  add(hit: Hit, role: PlaceRole): void;
  /** "Hierhin" on an empty plan (the pin card's action): from my position to this place (the app's poiFromMyLocation). */
  goHere(hit: Hit): void;
  /** "Google": the app's Google Maps link-out for this place (a new tab). */
  google(hit: Hit): void;
  /** The place card opened or closed (the page makes room: on a phone it stands in for the plan sheet). */
  cardShown?(open: boolean): void;
  /** A result is shown on the map (the app's trigger T2: its map area is asked for – areas.ts). */
  placeShown?(lat: number, lon: number): void;
  /** The free room of the map (top bar, plan sheet or bottom card, controls) – main.ts fitPadding. */
  fitPadding(): maplibregl.PaddingOptions;
}

/** What the page can do with the place card: close it (a map click elsewhere, Escape) and ask whether it is open. */
/** [close] closes the card only – the found place's pin stays (app 2026-11-02); [removePin] takes the pin away too. */
export interface PlaceCard { close(): void; isOpen(): boolean; removePin(): void;
  /** The plan changed: the pin card's action follows ("+ Zur Route", on an empty plan "Hierhin"). */
  planChanged(): void }

/** The elements the anchored card must not cover (wide screen). */
const BLOCKERS = ['#topbar', '#sheet', '#trackPanel', '#mapnotes', '#footer', '.maplibregl-ctrl-bottom-right'];
const phone = () => matchMedia('(max-width: 700px)').matches;

const DEBOUNCE_MS = 350; // the app's SearchSession.DEBOUNCE_MS

/** Photon request exactly as the app's PhotonProvider.url (rough position, 2 decimals ≈ 1 km). */
export function photonUrl(q: string, lat: number, lon: number, limit = 20): string {
  const f = (v: number) => (Math.round(v * 100) / 100).toFixed(2);
  return `https://photon.komoot.io/api/?q=${encodeURIComponent(q.trim())}&lat=${f(lat)}&lon=${f(lon)}` +
    `&zoom=12&location_bias_scale=0.3&limit=${limit}&lang=de`;
}

/**
 * [box] holds the search pill and its results; [card] is the place card – on a wide screen a callout at the picked
 * point with a tail to it, moving with the map (owner 2026-10-30, like the app: the card belongs to the point); on a
 * phone a bottom card in place of the sheet with the point visible above it. It never covers the sheet (owner
 * 2026-10-28) and closes after any action, on ✕, Escape and a map click elsewhere.
 */
export function setupSearch(host: SearchHost, box: HTMLElement, card: HTMLElement): PlaceCard {
  box.innerHTML = `
    <div class="searchrow">${icon('search')}<input id="q" type="search" placeholder="${esc(t('search_placeholder'))}" autocomplete="off" aria-label="${esc(t('search_placeholder'))}">
      <button id="qclear" class="iconbtn" aria-label="${esc(t('search_clear_query'))}" hidden>${icon('close', 20)}</button></div>
    <div id="results" hidden></div>`;
  card.hidden = true;
  const q = box.querySelector<HTMLInputElement>('#q')!;
  const results = box.querySelector<HTMLDivElement>('#results')!;
  const clear = box.querySelector<HTMLButtonElement>('#qclear')!;
  let gen = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  /** The found place's pin and its name chip (the app's CandidateMarker + MapOverlays PinChip): they outlive the card. */
  let marker: maplibregl.Marker | null = null;
  let chip: maplibregl.Marker | null = null;
  let chipHit: Hit | null = null;
  const removePin = () => {
    marker?.remove(); marker = null; chip?.remove(); chip = null; chipHit = null; setJoin(host.map, null);
  };
  /** The pin card's action button: "+ Zur Route", on an empty plan "Hierhin" (the app's MapOverlays, design B). */
  const chipAction = () => {
    const el = chip?.getElement(), h = chipHit;
    if (!el || !h) return;
    const empty = host.points().length === 0;
    const label = t(empty ? 'plan_go_here' : 'plan_pin_add');
    const b = el.querySelector<HTMLButtonElement>('.pinadd')!;
    b.innerHTML = `${icon(empty ? 'gpsFixed' : 'add', 16)}<span>${esc(label)}</span>`;
    b.setAttribute('aria-label', t('plan_pin_action_desc', label, h.title));
    b.onclick = (e) => { e.stopPropagation(); close(); removePin(); if (empty) host.goHere(h); else host.add(h, 'SMART'); };
  };
  /** The chip picked (the app's SearchKinds.Category name), '' = none; the six chips with their labels from the engine. */
  let category = '';
  let chips: [string, string][] | null = null;
  const loadChips = async () => { chips ??= JSON.parse(await host.engine.call('searchCategories')) as [string, string][]; return chips; };
  /** The app's chip row (Orte · Essen · Sehenswert · Wasser · Bahn · Parken): one scrolling row, a click picks / unpicks. */
  const chipRow = () => `<div class="searchchips" role="toolbar">${(chips ?? []).map(([k, label]) =>
    `<button class="chip${k === category ? ' on' : ''}" data-cat="${k}" aria-pressed="${k === category}">${esc(label)}</button>`).join('')}</div>`;
  const wireChips = () => results.querySelectorAll<HTMLButtonElement>('.searchchips .chip').forEach((b) => {
    b.onclick = (e) => { e.stopPropagation(); category = category === b.dataset.cat ? '' : b.dataset.cat!; run(); q.focus(); };
  });

  const render = (hits: Hit[], note: string) => {
    results.hidden = false;
    results.innerHTML = chipRow() + (hits.length ? hits.map((h, i) => `
      <button class="hit" data-i="${i}">
        <span class="glyph" style="background:${h.color}">${esc(h.glyph)}</span>
        <span class="txt"><b>${esc(h.title)}</b><span class="muted">${esc(h.subtitle)}</span>
        ${h.address ? `<span class="muted small">${esc(h.address)}</span>` : ''}</span>
      </button>`).join('') : `<div class="muted pad">${esc(q.value.trim().length < 2 && !category ? t('web_search_type') : t('web_search_nothing'))}</div>`) +
      (note ? `<div class="muted small pad">${esc(note)}</div>` : '');
    results.querySelectorAll<HTMLButtonElement>('.hit').forEach((b) => { b.onclick = () => pick(hits[Number(b.dataset.i)]); });
    wireChips();
  };

  const run = async () => {
    const my = ++gen;
    const text = q.value;
    clear.hidden = !text;
    close();
    if (timer) clearTimeout(timer);
    await loadChips();
    // nothing typed, no chip: the next search starts afresh (the app's inactive search); the chips stay to pick from
    if (!text.trim() && !category) { void host.engine.call('searchReset'); if (my === gen && document.activeElement === q) render([], ''); else results.hidden = true; return; }
    const c = host.map.getCenter();
    await host.engine.searchAreas(await host.areas());
    const offline = await host.engine.search(text, c.lat, c.lng, host.route(), category);
    if (my !== gen) return;
    const online = text.trim().length >= 3;
    render(offline, online ? t('search_searching_online') : '');
    if (!online) return;
    timer = setTimeout(async () => {
      let mixed: Hit[] | null = null;
      try {
        const r = await fetch(photonUrl(text, c.lat, c.lng));
        if (r.ok) mixed = await host.engine.searchOnline(text, await r.text(), category);
      } catch { /* expected: offline or Photon unreachable – the offline rows stay (the app is silent too) */ }
      if (my !== gen) return;
      const list = mixed ?? offline;
      render(list, list.some((h) => h.online) ? t('web_search_online_credit') : mixed ? '' : t('web_search_unavailable'));
    }, DEBOUNCE_MS);
  };

  let picked = 0;
  /** [fly] false: the pin's chip (or the pin) tapped – the card opens again where the map is (the app's openPinMenu). */
  const pick = async (h: Hit, fly = true) => {
    const my = ++picked;
    gen++; // a search still running (online results after 350 ms) must not open the list again over the card
    if (timer) clearTimeout(timer);
    results.hidden = true;
    removePin();
    const again = () => { if (card.hidden) void pick(h, false); };
    const pinEl = candidateMarker();
    pinEl.addEventListener('click', (e) => { e.stopPropagation(); again(); });
    marker = new maplibregl.Marker({ element: pinEl, anchor: 'bottom', offset: [0, TIP_MARGIN] }).setLngLat([h.lon, h.lat]).addTo(host.map);
    const chipEl = pinChip(h.title);
    chipEl.addEventListener('click', (e) => e.stopPropagation()); // a click on the card itself is not a map click
    chipEl.querySelector<HTMLButtonElement>('.pinmore')!.onclick = (e) => { e.stopPropagation(); again(); };
    chip = new maplibregl.Marker({ element: chipEl, anchor: 'top', offset: [0, CHIP_GAP] }).setLngLat([h.lon, h.lat]).addTo(host.map);
    chipHit = h;
    chipAction();
    if (fly) host.placeShown?.(h.lat, h.lon);
    const pv = await host.engine.placePreview(host.points(), h.lat, h.lon);
    if (my === picked) setJoin(host.map, pv.join ? [[h.lon, h.lat], [pv.join[1], pv.join[0]]] : null);
    const n = host.points().length;
    const atStart = pv.existing === 0, atEnd = pv.existing != null && pv.existing === n - 1;
    // the main button already sets the start / the destination: its small button is left out (app PlaceCard)
    const planned = pv.existing != null;
    if (my !== picked) return; // a newer pick or a close came first
    card.hidden = false;
    host.cardShown?.(true);
    card.innerHTML = `
      <div class="cardhead"><span class="glyph" style="background:${h.color}">${esc(h.glyph)}</span>
        <span class="txt"><b>${esc(h.title)}</b><span class="muted">${esc(h.subtitle)}</span>
        ${h.address ? `<span class="muted small">${esc(h.address)}</span>` : ''}</span>
        <button id="cclose" class="iconbtn" aria-label="${esc(t('common_close'))}">${icon('close', 20)}</button></div>
      ${pv.existing == null
        ? `<button id="cadd" class="primary wide twoline">${icon('addLocation')}<span class="txt"><span>${esc(pv.label ?? '')}</span>${pv.subtitle ? `<span class="small">${esc(pv.subtitle)}</span>` : ''}</span></button>`
        : `<button id="cremove" class="primary wide twoline">${icon('delete')}<span class="txt"><span>${esc(t('search_card_remove'))}</span><span class="small">${esc(pv.subtitle)}</span></span></button>`}
      <div class="row three">${planned || !pv.addIsStart ? `<button id="cstart" class="outline" ${atStart ? 'disabled' : ''}>${esc(t('search_card_as_start'))}</button>` : ''}
        ${planned || !pv.addIsDestination ? `<button id="cdest" class="outline" ${atEnd ? 'disabled' : ''}>${esc(t('search_card_as_destination'))}</button>` : ''}
        <button id="cgoogle" class="outline" title="${esc(t('web_google_title'))}">${icon('map', 18)}Google</button></div>
      <button id="cunpin" class="textbtn small">${icon('close', 16)}${esc(t('plan_remove_pin'))}</button>`;
    const done = (role: PlaceRole) => { close(); removePin(); host.add(h, role); };
    card.querySelector<HTMLButtonElement>('#cunpin')!.onclick = () => { close(); removePin(); };
    card.querySelector<HTMLButtonElement>('#cadd')?.addEventListener('click', () => done('SMART'));
    card.querySelector<HTMLButtonElement>('#cremove')?.addEventListener('click', () => done('REMOVE'));
    card.querySelector<HTMLButtonElement>('#cstart')?.addEventListener('click', () => done('START'));
    card.querySelector<HTMLButtonElement>('#cdest')?.addEventListener('click', () => done('DESTINATION'));
    card.querySelector<HTMLButtonElement>('#cgoogle')!.onclick = () => { close(); host.google(h); };
    card.querySelector<HTMLButtonElement>('#cclose')!.onclick = close;
    at = [h.lon, h.lat];
    layout();
    // the camera: point and card in the free room (beside the sheet / above the bottom card). Not keepFollow – this
    // ends following my position (CLAUDE.md map camera). padding 0 resets one an earlier move may have left behind.
    if (!fly) return;
    const c = host.map.getContainer(), w = c.clientWidth, hh = c.clientHeight;
    const [tx, ty] = pointTarget(host.fitPadding(), w, hh, card.offsetHeight, !phone());
    host.map.flyTo({ center: at, zoom: Math.max(host.map.getZoom(), 14), offset: offsetTo(tx, ty, w, hh),
      padding: NO_PADDING });
  };

  /** The card at its point (wide screen): the side with room, the tail pointing at the point; off the map → hidden. */
  let at: [number, number] | null = null;
  const layout = () => {
    if (card.hidden || !at) return;
    if (phone()) { card.classList.remove('anchored'); card.style.cssText = ''; return; }
    const stage = host.map.getContainer().parentElement!, c = host.map.getContainer();
    const w = c.clientWidth, h = c.clientHeight;
    const p = host.map.project(at);
    card.classList.add('anchored');
    const off = p.x < 0 || p.y < 0 || p.x > w || p.y > h;
    const pl = placeCard(p.x, p.y, card.offsetWidth, card.offsetHeight, w, h, boxesOf(stage, BLOCKERS));
    card.style.left = pl.x + 'px'; card.style.top = pl.y + 'px';
    card.style.setProperty('--tail', pl.tail + 'px');
    card.style.visibility = off ? 'hidden' : '';
    card.dataset.side = pl.side;
  };
  host.map.on('move', layout);
  host.map.on('resize', layout);
  // the card and what it must not cover: a sheet or top bar that grows later (a route arrives, a line shows) moves the card too
  const ro = new ResizeObserver(layout);
  ro.observe(card);
  for (const q of BLOCKERS) for (const e of document.querySelectorAll<HTMLElement>(q)) ro.observe(e);

  const close = () => {
    picked++; // a pick still waiting for its preview must not open the card afterwards
    const was = !card.hidden;
    card.hidden = true; card.innerHTML = ''; at = null; setJoin(host.map, null); // the preview line is the card's (it would go stale with the plan)
    card.classList.remove('anchored'); card.style.cssText = '';
    if (was) host.cardShown?.(false);
  };
  card.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });

  q.addEventListener('input', () => { run(); });
  q.addEventListener('focus', () => { if (!q.value.trim() && !category) run(); }); // the chips to start from
  q.addEventListener('keydown', (e) => { if (e.key === 'Escape') { q.value = ''; run(); } });
  clear.onclick = () => { q.value = ''; run(); q.focus(); };
  return { close, isOpen: () => !card.hidden, removePin, planChanged: chipAction };
}

/**
 * The found place's pin (the app's map/CandidateMarker, owner 2026-11-02 – replaces the dashed ring with "+"): a search
 * hit not yet in the route is a route-blue teardrop with a white outline and a white dot, its tip exactly on the place
 * (head 11, tip → head centre 34, outline 2, dot 4.2, a soft shadow 1.2 right / down). Its name is the chip under it.
 */
const ROUTE_BLUE = '#1E3FAE';
const HEAD = 11, TIP = 34, OUTLINE = 2, DOT = 4.2, SHADOW = 1.2;
/** From the bottom edge of the pin's box up to its tip (the app's TIP_MARGIN_DP). */
export const TIP_MARGIN = SHADOW + OUTLINE + 1;
const PIN_W = 2 * (HEAD + OUTLINE) + 2 * SHADOW + 2, PIN_H = TIP + HEAD + OUTLINE + TIP_MARGIN + 1;
function pinPath(x: number, y: number): string {
  const h = HEAD, l = TIP;
  return `M${x} ${y}C${x - 0.35 * h} ${y - 0.42 * l} ${x - h} ${y - 0.62 * l} ${x - h} ${y - l}`
    + `A${h} ${h} 0 0 1 ${x + h} ${y - l}C${x + h} ${y - 0.62 * l} ${x + 0.35 * h} ${y - 0.42 * l} ${x} ${y}Z`;
}
function candidateMarker(): HTMLElement {
  const el = document.createElement('div');
  el.className = 'candidate';
  const x = PIN_W / 2, y = PIN_H - TIP_MARGIN;
  el.innerHTML = `<svg width="${PIN_W}" height="${PIN_H}" viewBox="0 0 ${PIN_W} ${PIN_H}" aria-hidden="true">
    <path d="${pinPath(x + SHADOW, y + SHADOW)}" fill="#000" fill-opacity=".25"/>
    <path d="${pinPath(x, y)}" fill="${ROUTE_BLUE}" stroke="#fff" stroke-width="${OUTLINE * 2}" stroke-linejoin="round" paint-order="stroke"/>
    <circle cx="${x}" cy="${y - TIP}" r="${DOT}" fill="#fff"/></svg>`;
  return el;
}

/** The chip's name: longer than 20 characters ends in "…" (the app's PinChipRules.label). */
export function pinChipLabel(name: string): string {
  const n = name.trim();
  return n.length <= 20 ? n : n.slice(0, 19).trimEnd() + '…';
}
/** Gap from the tip to the card's top. */
const CHIP_GAP = 6;
/**
 * The found place under its pin (the app's MapOverlays.PinChip, owner 2026-11-04 design B): a small white card – the
 * name, below it the blue action ("+ Zur Route", on an empty plan "Hierhin", set by chipAction) and "⋯ Mehr" (the place
 * card – the web's pin menu: as start / destination, Google, remove pin). Both ≥ 48 px touch targets; long names "…".
 */
function pinChip(name: string): HTMLElement {
  const d = document.createElement('div');
  d.className = 'pinchip';
  d.innerHTML = `<b>${esc(pinChipLabel(name))}</b><div class="pinbtns"><button class="pinadd"></button>` +
    `<button class="pinmore" aria-label="${esc(t('plan_pin_chip_desc', name))}">${icon('more', 16)}<span>${esc(t('plan_pin_more'))}</span></button></div>`;
  return d;
}

/**
 * The candidate's preview line (the app's MapController join line): thin (2.5), dashed (2 / 2 line widths), route blue,
 * from the marker to where adding it would join the route; null removes it. Over the route (the route points are markers).
 */
const JOIN = 'candidate-join';
function setJoin(map: maplibregl.Map, line: [number, number][] | null) {
  const data: GeoJSON.FeatureCollection = { type: 'FeatureCollection',
    features: line ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: line } }] : [] };
  const src = map.getSource(JOIN) as maplibregl.GeoJSONSource | undefined;
  if (src) { src.setData(data); return; }
  if (!line || !map.isStyleLoaded()) return;
  map.addSource(JOIN, { type: 'geojson', data });
  map.addLayer({ id: JOIN, type: 'line', source: JOIN, layout: { 'line-cap': 'round' },
    paint: { 'line-color': ROUTE_BLUE, 'line-width': 2.5, 'line-dasharray': [2, 2] } });
}

function esc(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
