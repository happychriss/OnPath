// Search like the app's (ui/search/SearchSession): offline on every keystroke – the app's index and ranking in the
// engine, over the map areas in view – and online (Photon, OpenStreetMap data) after 350 ms without typing for 3+
// letters, mixed in below the rows already shown. A pick opens the place card: "Add to route" (where it goes is said
// before the click – the app's AddPreview), "As start", "As destination", "Google" (ui/search/PlaceCard).
import * as maplibregl from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Hit } from './engine/types';
import { icon } from './icons';

export type PlaceRole = 'SMART' | 'START' | 'DESTINATION' | 'REMOVE';

export interface SearchHost {
  map: maplibregl.Map;
  engine: EngineClient;
  /** The map areas to search (cells in view, or the one map folder). */
  areas(): Promise<{ key: string; dir: string }[]>;
  points(): [number, number][];
  route(): [number, number][];
  add(hit: Hit, role: PlaceRole): void;
  /** "Google": the app's Google Maps link-out for this place (a new tab). */
  google(hit: Hit): void;
  /** The place card opened or closed (the page makes room: on a phone it stands in for the plan sheet). */
  cardShown?(open: boolean): void;
  /** A result is shown on the map (the app's trigger T2: its map area is asked for – areas.ts). */
  placeShown?(lat: number, lon: number): void;
}

const DEBOUNCE_MS = 350; // the app's SearchSession.DEBOUNCE_MS

/** Photon request exactly as the app's PhotonProvider.url (rough position, 2 decimals ≈ 1 km). */
export function photonUrl(q: string, lat: number, lon: number, limit = 20): string {
  const f = (v: number) => (Math.round(v * 100) / 100).toFixed(2);
  return `https://photon.komoot.io/api/?q=${encodeURIComponent(q.trim())}&lat=${f(lat)}&lon=${f(lon)}` +
    `&zoom=12&location_bias_scale=0.3&limit=${limit}&lang=de`;
}

/**
 * [box] holds the search pill and its results; [card] is the place card's own spot – a floating card beside the plan
 * sheet on a wide screen, a bottom card in place of the sheet on a phone (owner 2026-10-28: it must not cover the
 * sheet, and it closes after any action).
 */
export function setupSearch(host: SearchHost, box: HTMLElement, card: HTMLElement) {
  box.innerHTML = `
    <div class="searchrow">${icon('search')}<input id="q" type="search" placeholder="Search places or addresses" autocomplete="off" aria-label="Search places or addresses">
      <button id="qclear" class="iconbtn" aria-label="Clear search" hidden>${icon('close', 20)}</button></div>
    <div id="results" hidden></div>`;
  card.hidden = true;
  const q = box.querySelector<HTMLInputElement>('#q')!;
  const results = box.querySelector<HTMLDivElement>('#results')!;
  const clear = box.querySelector<HTMLButtonElement>('#qclear')!;
  let gen = 0;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let marker: maplibregl.Marker | null = null;

  const render = (hits: Hit[], note: string) => {
    results.hidden = false;
    results.innerHTML = (hits.length ? hits.map((h, i) => `
      <button class="hit" data-i="${i}">
        <span class="glyph" style="background:${h.color}">${esc(h.glyph)}</span>
        <span class="txt"><b>${esc(h.title)}</b><span class="muted">${esc(h.subtitle)}</span>
        ${h.address ? `<span class="muted small">${esc(h.address)}</span>` : ''}</span>
      </button>`).join('') : `<div class="muted pad">${q.value.trim().length < 2 ? 'Type a name or address.' : 'Nothing found in the map areas in view.'}</div>`) +
      (note ? `<div class="muted small pad">${note}</div>` : '');
    results.querySelectorAll<HTMLButtonElement>('.hit').forEach((b) => { b.onclick = () => pick(hits[Number(b.dataset.i)]); });
  };

  const run = async () => {
    const my = ++gen;
    const text = q.value;
    clear.hidden = !text;
    close();
    if (timer) clearTimeout(timer);
    if (!text.trim()) { results.hidden = true; return; }
    const c = host.map.getCenter();
    await host.engine.searchAreas(await host.areas());
    const offline = await host.engine.search(text, c.lat, c.lng, host.route());
    if (my !== gen) return;
    const online = text.trim().length >= 3;
    render(offline, online ? 'Searching online…' : '');
    if (!online) return;
    timer = setTimeout(async () => {
      let mixed: Hit[] | null = null;
      try {
        const r = await fetch(photonUrl(text, c.lat, c.lng));
        if (r.ok) mixed = await host.engine.searchOnline(text, await r.text());
      } catch { /* expected: offline or Photon unreachable – the offline rows stay (the app is silent too) */ }
      if (my !== gen) return;
      const list = mixed ?? offline;
      render(list, list.some((h) => h.online) ? 'Online results © OpenStreetMap (Photon)' : mixed ? '' : 'Online search unavailable.');
    }, DEBOUNCE_MS);
  };

  let picked = 0;
  const pick = async (h: Hit) => {
    const my = ++picked;
    gen++; // a search still running (online results after 350 ms) must not open the list again over the card
    if (timer) clearTimeout(timer);
    results.hidden = true;
    marker?.remove();
    const ring = document.createElement('div');
    ring.className = 'searchring';
    marker = new maplibregl.Marker({ element: ring }).setLngLat([h.lon, h.lat]).addTo(host.map);
    host.map.flyTo({ center: [h.lon, h.lat], zoom: Math.max(host.map.getZoom(), 14) });
    host.placeShown?.(h.lat, h.lon);
    const pv = await host.engine.placePreview(host.points(), h.lat, h.lon);
    const n = host.points().length;
    const atStart = pv.existing === 0, atEnd = pv.existing != null && pv.existing === n - 1;
    if (my !== picked) return; // a newer pick or a close came first
    card.hidden = false;
    host.cardShown?.(true);
    card.innerHTML = `
      <div class="cardhead"><span class="glyph" style="background:${h.color}">${esc(h.glyph)}</span>
        <span class="txt"><b>${esc(h.title)}</b><span class="muted">${esc(h.subtitle)}</span>
        ${h.address ? `<span class="muted small">${esc(h.address)}</span>` : ''}</span>
        <button id="cclose" class="iconbtn" aria-label="Close">${icon('close', 20)}</button></div>
      ${pv.existing == null
        ? `<button id="cadd" class="primary wide twoline">${icon('addLocation')}<span class="txt"><span>Add to route</span>${pv.subtitle ? `<span class="small">${esc(pv.subtitle)}</span>` : ''}</span></button>`
        : `<button id="cremove" class="primary wide twoline">${icon('delete')}<span class="txt"><span>Remove from route</span><span class="small">${esc(pv.subtitle)}</span></span></button>`}
      <div class="row three"><button id="cstart" class="outline" ${atStart ? 'disabled' : ''}>As start</button>
        <button id="cdest" class="outline" ${atEnd ? 'disabled' : ''}>As destination</button>
        <button id="cgoogle" class="outline" title="Open in Google Maps (new tab)">${icon('map', 18)}Google</button></div>`;
    const done = (role: PlaceRole) => { close(); host.add(h, role); };
    card.querySelector<HTMLButtonElement>('#cadd')?.addEventListener('click', () => done('SMART'));
    card.querySelector<HTMLButtonElement>('#cremove')?.addEventListener('click', () => done('REMOVE'));
    card.querySelector<HTMLButtonElement>('#cstart')!.onclick = () => done('START');
    card.querySelector<HTMLButtonElement>('#cdest')!.onclick = () => done('DESTINATION');
    card.querySelector<HTMLButtonElement>('#cgoogle')!.onclick = () => { close(); host.google(h); };
    card.querySelector<HTMLButtonElement>('#cclose')!.onclick = close;
  };

  const close = () => {
    picked++; // a pick still waiting for its preview must not open the card afterwards
    const was = !card.hidden;
    card.hidden = true; card.innerHTML = ''; marker?.remove(); marker = null;
    if (was) host.cardShown?.(false);
  };
  card.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });

  q.addEventListener('input', () => { run(); });
  q.addEventListener('keydown', (e) => { if (e.key === 'Escape') { q.value = ''; run(); } });
  clear.onclick = () => { q.value = ''; run(); q.focus(); };
}

function esc(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
