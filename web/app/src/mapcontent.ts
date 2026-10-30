// "Map content" (toolbar): the app's Settings → POIs and Map pages in one large panel (owner 2026-10-28: use the
// screen). POI kinds by group with a tri-state box per group, all / none / reset, a filter over the kind names, and the
// contour lines / hill shading switches. Applied live to the style's poi- layers with the app's rules: kinds and
// defaults from poi/PoiKinds, the layer filter from poi/PoiFilter (both in the engine, as map/LayerSettings uses them);
// group names are the app's Settings strings (engine call poiKinds). Remembered in this browser.
import type * as maplibregl from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import { icon } from './icons';
import { t } from './i18n';

interface Kind { key: string; group: string; en: string; glyph: string; color: string }
export interface MapContent { hidden: string[]; contours: boolean; hillshade: boolean }

const KEY = 'onpath.mapContent';

export class MapContentPanel {
  private kinds: Kind[] = [];
  private groups: [string, string][] = []; // [PoiGroup name, its label] in the enum's order
  private defaults: string[] = [];
  private state: MapContent | null = null;
  private original = new Map<string, unknown>(); // each poi- layer's own filter (kept, as LayerSettings does)
  private d: HTMLDialogElement | null = null;
  private filter = '';
  private ready: Promise<void>;

  constructor(private map: maplibregl.Map, private engine: EngineClient, private onProblem: (t: string) => void) {
    this.ready = engine.call('poiKinds').then((t) => {
      const j = JSON.parse(t) as { kinds: Kind[]; groups: { key: string; label: string }[]; defaultHidden: string[] };
      this.kinds = j.kinds; this.defaults = j.defaultHidden;
      this.groups = j.groups.map((g) => [g.key, g.label]);
      this.state = load() ?? { hidden: [...this.defaults], contours: true, hillshade: true };
    }).catch((e) => onProblem(t('web_mc_not_loaded', String(e))));
  }

  /** Applies the remembered choice to the map (after the style loaded). */
  async apply() {
    await this.ready;
    const s = this.state;
    if (!s) return;
    for (const l of this.map.getStyle().layers) {
      if (l.id.startsWith('poi-')) {
        if (!this.original.has(l.id)) this.original.set(l.id, 'filter' in l ? l.filter ?? null : null);
        const orig = this.original.get(l.id);
        const json = await this.engine.call('poiFilter', orig == null ? '' : JSON.stringify(orig), s.hidden.join(','));
        try { this.map.setFilter(l.id, json ? JSON.parse(json) : null); } catch (e) { this.onProblem(t('web_mc_filter_failed', l.id, String(e))); }
      } else if (l.id.startsWith('contour-') || l.id.startsWith('hillshade')) {
        const on = l.id.startsWith('contour-') ? s.contours : s.hillshade;
        this.map.setLayoutProperty(l.id, 'visibility', on ? 'visible' : 'none');
      }
    }
  }

  async open() {
    await this.ready;
    if (!this.state) return;
    this.d?.close();
    const d = document.createElement('dialog');
    d.className = 'mapcontent';
    d.setAttribute('aria-label', t('web_map_content'));
    d.innerHTML = `<div class="mchead"><h2>${esc(t('web_map_content'))}</h2>
        <input id="kindFilter" type="search" placeholder="${esc(t('web_mc_find'))}" aria-label="${esc(t('web_mc_find_label'))}" autocomplete="off">
        <button id="mcClose" class="iconbtn" aria-label="${esc(t('common_close'))}" autofocus>${icon('close')}</button></div>
      <div class="mcbody">
        <section class="mcmap"><h3>${esc(t('web_mc_map'))}</h3>
          <label class="kind"><input type="checkbox" id="mcContours"> ${esc(t('settings_map_contours'))}</label>
          <label class="kind"><input type="checkbox" id="mcHillshade"> ${esc(t('settings_map_hillshade'))}</label></section>
        <section><div class="mcpoihead"><h3>${esc(t('settings_page_poi'))}</h3><span id="mcCount" class="muted small"></span>
          <button id="mcAll" class="textbtn">${esc(t('common_show_all'))}</button><button id="mcNone" class="textbtn">${esc(t('settings_poi_hide_all'))}</button>
          <button id="mcReset" class="textbtn">${esc(t('common_reset'))}</button></div>
          <div id="mcGroups" class="mcgroups"></div></section>
      </div>`;
    document.body.append(d);
    this.d = d;
    d.addEventListener('close', () => { d.remove(); if (this.d === d) this.d = null; });
    d.addEventListener('click', (e) => { if (e.target === d) d.close(); }); // backdrop
    const q = <T extends HTMLElement>(id: string) => d.querySelector<T>('#' + id)!;
    q('mcClose').onclick = () => d.close();
    q<HTMLInputElement>('kindFilter').oninput = (e) => { this.filter = (e.target as HTMLInputElement).value; this.draw(); };
    q('mcAll').onclick = () => this.set([]);
    q('mcNone').onclick = () => this.set(this.kinds.map((k) => k.key));
    q('mcReset').onclick = () => this.set([...this.defaults]);
    const c = q<HTMLInputElement>('mcContours'), h = q<HTMLInputElement>('mcHillshade');
    c.checked = this.state.contours; h.checked = this.state.hillshade;
    c.onchange = () => this.change({ contours: c.checked });
    h.onchange = () => this.change({ hillshade: h.checked });
    q('mcGroups').addEventListener('change', (e) => {
      const t = e.target as HTMLInputElement;
      const hidden = new Set(this.state!.hidden);
      const keys = t.dataset.group ? this.kinds.filter((k) => k.group === t.dataset.group).map((k) => k.key) : [t.dataset.kind!];
      for (const k of keys) { if (t.checked) hidden.delete(k); else hidden.add(k); }
      this.set([...hidden]);
    });
    this.filter = '';
    this.draw();
    d.showModal();
  }

  private set(hidden: string[]) { this.change({ hidden }); }

  private change(p: Partial<MapContent>) {
    this.state = { ...this.state!, ...p };
    save(this.state);
    this.draw();
    this.apply();
  }

  private draw() {
    const d = this.d, s = this.state;
    if (!d || !s) return;
    const f = this.filter.trim().toLowerCase();
    const hidden = new Set(s.hidden);
    d.querySelector('#mcCount')!.textContent = t('settings_poi_shown', this.kinds.filter((k) => !hidden.has(k.key)).length, this.kinds.length);
    d.querySelector('#mcGroups')!.innerHTML = this.groups.map(([g, label]) => {
      const all = this.kinds.filter((k) => k.group === g);
      const kinds = all.filter((k) => !f || k.en.toLowerCase().includes(f) || label.toLowerCase().includes(f) || k.key.includes(f));
      if (!kinds.length) return '';
      const on = all.filter((k) => !hidden.has(k.key)).length;
      return `<fieldset class="mcgroup"><legend><label class="grouprow"><input type="checkbox" data-group="${g}" ${on === all.length ? 'checked' : ''}
          ${on > 0 && on < all.length ? 'data-mixed="1"' : ''}><span class="dot" style="background:${all[0].color}"></span><b>${esc(label)}</b>
          <span class="muted small">${esc(t('web_mc_of', on, all.length))}</span></label></legend>
        ${kinds.map((k) => `<label class="kind"><input type="checkbox" data-kind="${k.key}" ${hidden.has(k.key) ? '' : 'checked'}>
          <span class="glyph small" style="background:${k.color}">${esc(k.glyph)}</span>${esc(k.en)}</label>`).join('')}</fieldset>`;
    }).join('') || `<p class="muted">${esc(t('web_mc_no_match'))}</p>`;
    d.querySelectorAll<HTMLInputElement>('[data-mixed]').forEach((i) => { i.indeterminate = true; });
  }
}

function load(): MapContent | null {
  try {
    const j = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    return j && Array.isArray(j.hidden) ? { hidden: j.hidden, contours: j.contours !== false, hillshade: j.hillshade !== false } : null;
  } catch { return null; } // expected: storage blocked or an old value – the app's defaults apply
}
function save(s: MapContent) { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch { /* expected: storage blocked */ } }
function esc(s: string) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!); }
