// Search like the app's (ui/search/SearchSession): offline on every keystroke – the app's index and ranking in the
// engine, over the map areas in view – and online (Photon, OpenStreetMap data) after 350 ms without typing for 3+
// letters, mixed in below the rows already shown. A pick sets the found place's pin and, once the map is there, opens
// the pin's own hold menu – the very rows a hold on that spot gives (the app's SearchFlow.pickSearchResult since
// 2026-11-13, owner "A mit Ausweich B"): above the pin, else under it, else the same rows as a card at the bottom
// (pinmenu.ts – the app's PinMenuPlace).
import * as maplibregl from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Hit } from './engine/types';
import { icon } from './icons';
import { t } from './i18n';
import { NO_PADDING, offsetTo, pointTarget } from './cardanchor';
import { holdMenu, type MenuItem } from './menus';
import { cardPan, decidePinMenu, MENU_GAP, pinRows, TIP_ROOM, type PinMenuDecision } from './pinmenu';

export type PlaceRole = 'SMART' | 'START' | 'DESTINATION' | 'REMOVE';

/** A held place's menu (main.ts placeRows – the app's PoiMenu): its head and rows; [more] waits behind "⋯ More". */
export interface PinRows { head: string; main: MenuItem[]; more: MenuItem[]; google: MenuItem }

export interface SearchHost {
  map: maplibregl.Map;
  engine: EngineClient;
  /** The map areas to search (cells in view, or the one map folder). */
  areas(): Promise<{ key: string; dir: string }[]>;
  points(): [number, number][];
  route(): [number, number][];
  /** The found place's menu: the rows a hold on it gives (the app's PinMenuItems), each add a place from the search. */
  pinMenu(hit: Hit): Promise<PinRows>;
  /** The bottom card opened or closed (the page makes room: on a phone it stands in for the plan sheet). */
  cardShown?(open: boolean): void;
  /** A result is shown on the map (the app's trigger T2: its map area is asked for – areas.ts). */
  placeShown?(lat: number, lon: number): void;
  /** The free map between the top bar and the plan sheet (phone) / the map's bottom (wide), map pixels: where the found
   *  place's menu may go (the app's PinMenuOverlay topPx / bottomPx). */
  menuRoom(): { top: number; bottom: number };
  /** The free room of the map (top bar, plan sheet or bottom card, controls) – main.ts fitPadding. */
  fitPadding(): maplibregl.PaddingOptions;
}

/** What the page can do with the found place's menu: close it (a map click elsewhere, Escape) and ask whether it is open. */
/** [close] closes the menu only – the found place's pin stays (app 2026-11-02); [removePin] takes the pin away too. */
export interface PlaceCard { close(): void; isOpen(): boolean; removePin(): void;
  /** The pin of [hit] again, without its menu (the app's TakenPin: a Cancel took back the add of this place). */
  showPin(hit: Hit): void; hasPin(): boolean;
  /** The plan changed: the pin's dashed preview line follows. */
  planChanged(): void }

const phone = () => matchMedia('(max-width: 700px)').matches;

const DEBOUNCE_MS = 350; // the app's SearchSession.DEBOUNCE_MS

/** Photon request exactly as the app's PhotonProvider.url (rough position, 2 decimals ≈ 1 km). */
export function photonUrl(q: string, lat: number, lon: number, limit = 20): string {
  const f = (v: number) => (Math.round(v * 100) / 100).toFixed(2);
  return `https://photon.komoot.io/api/?q=${encodeURIComponent(q.trim())}&lat=${f(lat)}&lon=${f(lon)}` +
    `&zoom=12&location_bias_scale=0.3&limit=${limit}&lang=de`;
}

/**
 * [box] holds the search pill and its results; [card] is the bottom card the found place's menu becomes where it fits
 * neither above nor under its pin (the app's PinMenuPlace B – on a phone in place of the plan sheet). The menu closes
 * after any action, on Escape and a map click elsewhere; the pin and its name chip stay.
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
  /** The found place's pin and its name chip (the app's CandidateMarker + MapOverlays PinChip): they outlive its menu. */
  let marker: maplibregl.Marker | null = null;
  let chip: maplibregl.Marker | null = null;
  let chipHit: Hit | null = null;
  const removePin = () => {
    close();
    marker?.remove(); marker = null; chip?.remove(); chip = null; chipHit = null; setJoin(host.map, null);
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
  /**
   * A pick: the pin and its name chip, the map flies there and – once it is there and was not moved by hand on the way
   * (the app's SharedPlaceMenu) – the pin's menu opens by itself. [fly] false: the pin again where the map is, its menu
   * at once; [pinOnly]: only the pin and its chip (TakenPin) – the menu opens on a click as usual.
   */
  const pick = (h: Hit, fly = true, pinOnly = false) => {
    gen++; // a search still running (online results after 350 ms) must not open the list again over the menu
    if (timer) clearTimeout(timer);
    results.hidden = true;
    close();
    removePin();
    const my = ++picked;
    const pinEl = candidateMarker();
    pinEl.addEventListener('click', (e) => { e.stopPropagation(); void openPinMenu(h); });
    marker = new maplibregl.Marker({ element: pinEl, anchor: 'bottom', offset: [0, TIP_MARGIN] }).setLngLat([h.lon, h.lat]).addTo(host.map);
    const chipEl = pinChip(h.title);
    chipEl.addEventListener('click', (e) => { e.stopPropagation(); void openPinMenu(h); });
    chip = new maplibregl.Marker({ element: chipEl, anchor: 'top', offset: [0, CHIP_GAP] }).setLngLat([h.lon, h.lat]).addTo(host.map);
    chipHit = h;
    void updateJoin();
    if (fly) host.placeShown?.(h.lat, h.lon);
    if (pinOnly) return;
    if (!fly) { void openPinMenu(h); return; }
    // the camera: the place in the free room (beside the sheet / above it), on a wide screen low enough for the menu
    // above it. Not keepFollow – this ends following my position (CLAUDE.md map camera). padding 0 resets one an earlier
    // move may have left behind.
    const map = host.map, c = map.getContainer(), w = c.clientWidth, hh = c.clientHeight;
    const [tx, mid] = pointTarget(host.fitPadding(), w, hh, 0, false);
    const room = host.menuRoom();
    const ty = phone() ? mid : Math.max(room.top, Math.min(room.bottom - TIP_ROOM, room.top + MENU_GAP + PIN_HEAD + MENU_ROOM));
    let byHand = false;
    const hand = () => { byHand = true; };
    const events = ['mousedown', 'touchstart', 'wheel'] as const;
    for (const e of events) map.on(e, hand);
    const arrived = () => {
      for (const e of events) map.off(e, hand);
      if (my !== picked || !marker) return; // a newer pick, a close or Remove pin came first
      if (byHand) { console.info('[search] found place: menu not opened – map moved by hand'); return; }
      void openPinMenu(h);
    };
    map.flyTo({ center: [h.lon, h.lat], zoom: Math.max(map.getZoom(), 14), offset: offsetTo(tx, ty, w, hh), padding: NO_PADDING });
    if (map.isMoving()) map.once('moveend', arrived); else arrived();
  };

  /** The open menu: a popup at the pin (above / under it) or the bottom card; [menuGen] drops a menu still being built. */
  let popup: maplibregl.Popup | null = null;
  let menuGen = 0;
  let menuAt: Hit | null = null;
  /** The pin's menu: its rows from the page, placed once per opened menu (pinmenu.ts – the app's PinMenuPlace). */
  const openPinMenu = async (h: Hit) => {
    close();
    const my = ++menuGen;
    let rows: PinRows;
    try { rows = await host.pinMenu(h); } catch (e) { console.warn('[Problems] WARN search: the found place\'s menu not built', e); return; }
    if (my !== menuGen || chipHit !== h) return; // closed, another pick or the pin removed meanwhile
    showMenu(h, rows, false, null);
  };
  /** The rows in the app's order (PinMenuItems), then placed: [decided] keeps the place of a menu that is open already. */
  const showMenu = (h: Hit, rows: PinRows, moreOpen: boolean, decided: PinMenuDecision | null) => {
    const byKey = new Map([...rows.main, ...rows.more].map((m) => [m.key ?? '', m] as const));
    const remove: MenuItem = { icon: 'wrongLocation', label: t('plan_remove_pin'), act: () => removePin() };
    const more: MenuItem = { icon: 'more', label: t('common_more'), act: () => showMenu(h, rows, true, decision) };
    // an add takes the place: its pin goes (the app: the mark stays until the place is added)
    const adds = (m: MenuItem): MenuItem => ({ ...m, act: () => { removePin(); m.act(); } });
    const items = pinRows(rows.main.map((m) => m.key ?? ''), rows.more.map((m) => m.key ?? ''), true, moreOpen).map((r) =>
      r === 'GOOGLE' ? rows.google : r === 'REMOVE_PIN' ? remove : r === 'MORE' ? more : adds(byKey.get(r)!));
    const map = host.map, c = map.getContainer();
    const { top, bottom } = host.menuRoom();
    const tip = map.project([h.lon, h.lat]).y, head = tip - PIN_HEAD;
    menuAt = h;
    chip?.getElement().style.setProperty('display', 'none'); // the menu has the name on top
    let decision: PinMenuDecision;
    if (decided?.kind === 'CARD') decision = decided;
    else {
      const p = pinPopup(holdMenu(rows.head, items, close), decided?.kind === 'BELOW' ? 'BELOW' : 'ABOVE', h);
      decision = decided ?? decidePinMenu(head, tip, p.getElement().offsetHeight, top, bottom);
      if (!decided) console.info(`[search] found place menu: ${decision.kind}${decision.panPx ? `, map moved ${decision.panPx} px` : ''} (menu ${p.getElement().offsetHeight} px, map ${Math.round(top)}..${Math.round(bottom)} px)`);
      if (decision.kind === 'ABOVE') { popup = p; if (decision.panPx) map.panBy([0, -decision.panPx], { duration: 300 }); return; }
      p.remove();
      if (decision.kind === 'BELOW') { popup = pinPopup(holdMenu(rows.head, items, close), 'BELOW', h); return; }
    }
    // B: the same rows as a card at the bottom, the name and address on top; the map moves up so the pin stays visible
    const addr = h.address ? `<span class="muted small addr">${esc(h.address)}</span>` : '';
    card.replaceChildren(holdMenu(rows.head.replace(/<\/span>$/, addr + '</span>'), items, close));
    card.classList.add('pinmenucard');
    const was = !card.hidden;
    card.hidden = false;
    if (!was) host.cardShown?.(true);
    if (decided) return;
    // the pin stays visible above the card (once) – the card's top counts where it lies under the pin (the app's card
    // spans the width; the planner's on a wide screen stands beside the plan sheet), else the free map's bottom
    const cb = card.getBoundingClientRect(), mb = c.getBoundingClientRect(), x = map.project([h.lon, h.lat]).x + mb.left;
    const under = x >= cb.left - PIN_HALF_W && x <= cb.right + PIN_HALF_W;
    const pan = cardPan(head, tip, under ? Math.min(cb.top - mb.top, bottom) : bottom, top);
    if (pan) map.panBy([0, -pan], { duration: 300 });
  };
  /** The menu as a popup above the pin's head (A) or under its tip; it moves with the map. */
  const pinPopup = (el: HTMLElement, kind: 'ABOVE' | 'BELOW', h: Hit) =>
    new maplibregl.Popup({ anchor: kind === 'ABOVE' ? 'bottom' : 'top', offset: kind === 'ABOVE' ? [0, -(PIN_HEAD + MENU_GAP)] : [0, MENU_GAP],
      closeButton: false, closeOnClick: false, maxWidth: '320px', className: 'holdpopup pinmenu' })
      .setDOMContent(el).setLngLat([h.lon, h.lat]).addTo(host.map);
  // the place leaves the visible map: its menu closes (the app's onGone)
  host.map.on('move', () => {
    if (!popup || !menuAt) return;
    const c = host.map.getContainer(), p = host.map.project([menuAt.lon, menuAt.lat]);
    if (p.x < 0 || p.y < 0 || p.x > c.clientWidth || p.y > c.clientHeight) close();
  });

  /** The pin's dashed preview line to where adding it would join the route (the app's candidateJoin). */
  const updateJoin = async () => {
    const h = chipHit;
    if (!h) return;
    const pv = await host.engine.placePreview(host.points(), h.lat, h.lon).catch(() => null);
    if (chipHit !== h) return;
    setJoin(host.map, pv?.join && pv.existing == null ? [[h.lon, h.lat], [pv.join[1], pv.join[0]]] : null);
  };

  /** Closes the menu (popup or bottom card); the pin and its name chip stay. */
  const close = () => {
    menuGen++; // a menu still being built must not open afterwards
    popup?.remove(); popup = null; menuAt = null;
    chip?.getElement().style.removeProperty('display');
    const was = !card.hidden;
    card.hidden = true; card.replaceChildren(); card.classList.remove('pinmenucard');
    if (was) host.cardShown?.(false);
  };
  card.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });

  q.addEventListener('input', () => { run(); });
  q.addEventListener('focus', () => { if (!q.value.trim() && !category) run(); }); // the chips to start from
  q.addEventListener('keydown', (e) => { if (e.key === 'Escape') { q.value = ''; run(); } });
  clear.onclick = () => { q.value = ''; run(); q.focus(); };
  return { close, isOpen: () => popup != null || !card.hidden, removePin, planChanged: () => { void updateJoin(); },
    showPin: (h: Hit) => { pick(h, false, true); }, hasPin: () => marker != null };
}

/** A wide screen's room kept above the place for its menu when the map flies there (a menu of 4–5 rows, its head 2 lines). */
const MENU_ROOM = 300;

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
/** Gap from the tip to the chip's top. */
const CHIP_GAP = 6;
/** From the pin's tip up to the top of its head (the app's CandidateMarker.PIN_HEIGHT_DP). */
const PIN_HEAD = TIP + HEAD + OUTLINE;
/** Half the pin's width. */
const PIN_HALF_W = HEAD + OUTLINE;
/**
 * The found place's name under its pin, "Name ›" (the app's MapOverlays.PinChip – since 2026-11-13 the name only: the
 * pick opens the pin's menu itself, owner "A mit Ausweich B"): one ≥ 48 px button that opens the menu again after
 * Escape / a click elsewhere; hidden while the menu is open. Long names end in "…".
 */
function pinChip(name: string): HTMLElement {
  const d = document.createElement('button');
  d.className = 'pinchip';
  d.setAttribute('aria-label', t('plan_pin_chip_desc', name));
  d.innerHTML = `<b>${esc(pinChipLabel(name))}</b>${icon('chevronRight', 20)}`;
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
