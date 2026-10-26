// Search like the app's (ui/search/SearchSession): offline on every keystroke – the app's index and ranking in the
// engine, over the map areas in view – and online (Photon, OpenStreetMap data) after 350 ms without typing for 3+
// letters, mixed in below the rows already shown. A pick opens the place card: "Add to route" (where it goes is said
// before the click – the app's AddPreview), "As start", "As destination".
import * as maplibregl from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Hit } from './engine/types';

export type PlaceRole = 'SMART' | 'START' | 'DESTINATION';

export interface SearchHost {
  map: maplibregl.Map;
  engine: EngineClient;
  /** The map areas to search (cells in view, or the one map folder). */
  areas(): Promise<{ key: string; dir: string }[]>;
  points(): [number, number][];
  route(): [number, number][];
  add(hit: Hit, role: PlaceRole): void;
}

const DEBOUNCE_MS = 350; // the app's SearchSession.DEBOUNCE_MS

/** Photon request exactly as the app's PhotonProvider.url (rough position, 2 decimals ≈ 1 km). */
export function photonUrl(q: string, lat: number, lon: number, limit = 20): string {
  const f = (v: number) => (Math.round(v * 100) / 100).toFixed(2);
  return `https://photon.komoot.io/api/?q=${encodeURIComponent(q.trim())}&lat=${f(lat)}&lon=${f(lon)}` +
    `&zoom=12&location_bias_scale=0.3&limit=${limit}&lang=de`;
}

export function setupSearch(host: SearchHost, box: HTMLElement) {
  box.innerHTML = `
    <div class="searchrow"><input id="q" type="search" placeholder="Search places or addresses" autocomplete="off" aria-label="Search">
      <button id="qclear" aria-label="Clear search" hidden>✕</button></div>
    <div id="results" hidden></div>
    <div id="card" hidden></div>`;
  const q = box.querySelector<HTMLInputElement>('#q')!;
  const results = box.querySelector<HTMLDivElement>('#results')!;
  const card = box.querySelector<HTMLDivElement>('#card')!;
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
    card.hidden = true;
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

  const pick = async (h: Hit) => {
    results.hidden = true;
    marker?.remove();
    const ring = document.createElement('div');
    ring.className = 'searchring';
    marker = new maplibregl.Marker({ element: ring }).setLngLat([h.lon, h.lat]).addTo(host.map);
    host.map.flyTo({ center: [h.lon, h.lat], zoom: Math.max(host.map.getZoom(), 14) });
    const pv = await host.engine.placePreview(host.points(), h.lat, h.lon);
    const n = host.points().length;
    const atStart = pv.existing === 0, atEnd = pv.existing != null && pv.existing === n - 1;
    card.hidden = false;
    card.innerHTML = `
      <div class="cardhead"><span class="glyph" style="background:${h.color}">${esc(h.glyph)}</span>
        <span class="txt"><b>${esc(h.title)}</b><span class="muted">${esc(h.subtitle)}</span>
        ${h.address ? `<span class="muted small">${esc(h.address)}</span>` : ''}</span>
        <button id="cclose" aria-label="Close">✕</button></div>
      ${pv.existing == null ? `<button id="cadd" class="primary wide">Add to route <span class="small">${esc(pv.subtitle)}</span></button>`
        : `<div class="muted pad">This place ${esc(pv.subtitle)}.</div>`}
      <div class="row"><button id="cstart" ${atStart ? 'disabled' : ''}>As start</button>
        <button id="cdest" ${atEnd ? 'disabled' : ''}>As destination</button></div>`;
    const done = (role: PlaceRole) => { host.add(h, role); close(); };
    card.querySelector<HTMLButtonElement>('#cadd')?.addEventListener('click', () => done('SMART'));
    card.querySelector<HTMLButtonElement>('#cstart')!.onclick = () => done('START');
    card.querySelector<HTMLButtonElement>('#cdest')!.onclick = () => done('DESTINATION');
    card.querySelector<HTMLButtonElement>('#cclose')!.onclick = close;
  };

  const close = () => { card.hidden = true; marker?.remove(); marker = null; };

  q.addEventListener('input', () => { run(); });
  q.addEventListener('keydown', (e) => { if (e.key === 'Escape') { q.value = ''; run(); } });
  clear.onclick = () => { q.value = ''; run(); q.focus(); };
}

function esc(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
