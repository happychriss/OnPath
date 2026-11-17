// GPX import like the app's My routes → Import GPX (AppViewModel.importGpx / showTrack / planFromShown, ui/Panels
// TrackPanel; docs/design/web-planner.md §15). Header / ☰ → "Import GPX" or a .gpx file dropped on the map: the engine reads it
// with the app's GpxParser, Komoot's <trk><type> sets the activity and T level (SportType, said in one grey line), the
// track is shown with its panel, and the app's choice follows: keep it as a track (Close track ends it) or
// "Plan a route from it" (TrackToPlan: few route points, our router, the current activity; one undo step).
import type * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import type { EngineClient } from './engine/client';
import type { Activity, PlanResult } from './engine/types';
import { profileSvg } from './profile';
import './gpximport.css';
import { t, distance } from './i18n';
import * as maplibre from 'maplibre-gl';

const text = t; // inside panel() the track is called t

type LatLon = [number, number];

export interface GpxHost {
  map: maplibregl.Map;
  engine: EngineClient;
  activity(): { activity: Activity; sac: number };
  /** Like the app's selectActivity(userChange = false): the plan (if any) is planned again for it. */
  setActivity(a: Activity, sac: number): void;
  /** The track's route points become the plan (one undo step); [names] = point index → name (a plan GPX's <wpt>). */
  /** [pinned] per point: passed exactly (an exported plan's <type>pinned</type>, PlanGpx.pinnedOf). */
  plan(points: LatLon[], title: string, names?: (string | null)[], pinned?: boolean[]): Promise<void>;
  /**
   * Before the track replaces the plan: an unsaved plan asks "Plan speichern?" first (the app's planFromShown →
   * unlessUnsaved, save paths P3, 2026-11-06); false = cancelled, nothing changes.
   */
  mayReplacePlan?(): Promise<boolean>;
  status(msg: string, kind?: 'info' | 'error'): void;
  /**
   * Multi-day tours (docs/design/multi-day-tours.md §3.2 / §3.3, stage 3): 'first' = the guide's step 1 has no route yet,
   * 'append' = it has one ("Noch etwas anhängen"); both show the trim handles. Missing / 'off' = the track panel as ever.
   */
  tourMode?(): 'off' | 'first' | 'append';
  /** "An die Route anhängen": the trimmed track's route points go behind the plan's last point (one undo step). */
  append?(points: LatLon[], title: string): Promise<void>;
  /** A file was imported and shown (not a synced track opened again): the page keeps it in My routes. */
  imported?(t: Track): void;
  fitPadding(): maplibregl.PaddingOptions;
}

export interface Track {
  name: string; activity: Activity; sac: number; switch: boolean; note: string | null; skippedNote: string | null;
  distanceM: number; ascentM: number; descentM: number; durationS: number;
  texts: { distance: string; duration: string; climb: string; plan: string; close: string };
  segments: [number, number, number | null][][];
  waypoints: { lat: number; lon: number; name: string | null }[];
  /** An exported OnPath plan (the app's PlanGpx.stopsOf): its <wpt> are the route points; null = a track to fit. */
  stops: { lat: number; lon: number; name: string | null; pinned?: boolean }[] | null;
  profile: PlanResult['profile'];
  error?: string;
}

/** The app's GPX colour (OnPathColors.Gpx). */
const GPX = '#8E24AA';
/** The part of a track outside the trim handles (multi-day tours §3.2: "der Rest wird grau"). */
const TRIM_OUT = '#B8BDB5';
/** The trim handles never come closer than this along the track. */
const TRIM_MIN_M = 200;

/** Distance in metres between two points (display only: the handles' place; the cut itself is the engine's TrackToPlan.cut). */
function metres(a: LatLon, b: LatLon): number {
  const r = Math.PI / 180, x = (b[1] - a[1]) * r * Math.cos(((a[0] + b[0]) / 2) * r), y = (b[0] - a[0]) * r;
  return Math.sqrt(x * x + y * y) * 6371000;
}

export function setupGpxImport(h: GpxHost) {
  let shown: Track | null = null;
  /** The trim of the shown track (multi-day tours): [from, to] metres along its points, and its handles. */
  let trim: { from: number; to: number; cum: number[]; pts: LatLon[]; marks: maplibre.Marker[] } | null = null;
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.gpx,application/gpx+xml,application/xml,text/xml';
  input.hidden = true;
  input.id = 'gpxFile';
  document.body.append(input);
  input.onchange = () => { const f = input.files?.[0]; input.value = ''; if (f) importFile(f); };

  // a .gpx file dropped on the map
  const box = h.map.getContainer();
  const hasFiles = (e: DragEvent) => !!e.dataTransfer && [...e.dataTransfer.types].includes('Files');
  box.addEventListener('dragover', (e) => { if (!hasFiles(e)) return; e.preventDefault(); e.dataTransfer!.dropEffect = 'copy'; box.classList.add('gpxdrop'); });
  box.addEventListener('dragleave', () => box.classList.remove('gpxdrop'));
  box.addEventListener('drop', (e) => {
    box.classList.remove('gpxdrop');
    if (!hasFiles(e)) return;
    e.preventDefault();
    const f = [...e.dataTransfer!.files].find((x) => /\.gpx$/i.test(x.name)) ?? e.dataTransfer!.files[0];
    if (f) importFile(f);
  });

  // the layers go in as soon as the style can take them – also when 'load' fired before this ran (isStyleLoaded() is
  // false while tiles load, so waiting for 'load' alone could wait forever)
  const ensureLayers = () => {
    if (h.map.getSource('gpx-track')) return true;
    try { addLayers(); return true; } catch { return false; } // expected: style not done loading yet – retried on styledata
  };
  h.map.on('styledata', ensureLayers);
  const addLayers = () => {
    h.map.addSource('gpx-track', { type: 'geojson', data: empty() });
    h.map.addLayer({ id: 'gpx-track-casing', type: 'line', source: 'gpx-track', filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#fff', 'line-width': 8 } });
    h.map.addLayer({ id: 'gpx-track', type: 'line', source: 'gpx-track', filter: ['==', '$type', 'LineString'],
      layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': ['case', ['==', ['get', 'out'], true], TRIM_OUT, GPX], 'line-width': 4 } });
    h.map.addLayer({ id: 'gpx-waypoints', type: 'circle', source: 'gpx-track', filter: ['==', '$type', 'Point'],
      paint: { 'circle-radius': 5, 'circle-color': '#fff', 'circle-stroke-color': GPX, 'circle-stroke-width': 3 } });
  };
  ensureLayers();

  async function importFile(f: File, keep = true) {
    h.status(t('web_gpx_importing', f.name));
    let track: Track;
    try {
      const { activity, sac } = h.activity();
      track = JSON.parse(await h.engine.gpxRead(new Uint8Array(await f.arrayBuffer()), f.name, activity, sac)) as Track;
    } catch (e) {
      h.status(t('web_gpx_unreadable'), 'error');
      console.warn('GPX import failed', e);
      return;
    }
    if (track.error) { h.status(track.error, 'error'); return; }
    // like the app: the activity is chosen before the track is shown
    if (track.switch) h.setActivity(track.activity, track.sac);
    show(track);
    if (keep) { h.status(t('web_gpx_imported', track.name)); h.imported?.(track); }
  }

  function show(t: Track) {
    shown = t;
    if (!ensureLayers()) { h.map.once('idle', () => { if (shown === t) show(t); }); }
    startTrim(t);
    drawTrack(t);
    const pts: LatLon[] = [...t.segments.flat().map(([la, lo]) => [la, lo] as LatLon), ...t.waypoints.map((w) => [w.lat, w.lon] as LatLon)];
    if (pts.length) {
      const w = Math.min(...pts.map((p) => p[1])), e = Math.max(...pts.map((p) => p[1]));
      const s = Math.min(...pts.map((p) => p[0])), n = Math.max(...pts.map((p) => p[0]));
      h.map.fitBounds([[w, s], [e, n]], { padding: h.fitPadding(), maxZoom: 15, duration: 0 });
    }
    panel(t);
  }

  /** The track on the map; with a trim the parts before the start and after the end handle grey. */
  function drawTrack(t: Track) {
    const src = h.map.getSource('gpx-track') as GeoJSONSource | undefined;
    const feats: GeoJSON.Feature[] = [];
    let along = 0, prev: LatLon | null = null;
    for (const seg of t.segments) {
      if (seg.length < 2) { if (seg.length) { const q: LatLon = [seg[0][0], seg[0][1]]; if (prev) along += metres(prev, q); prev = q; } continue; }
      // split at the handles: [out before | in | out after]; the jump between segments counts as the engine counts it
      let part: number[][] = [], out: boolean | null = null;
      const flush = () => { if (part.length >= 2) feats.push({ type: 'Feature', properties: { out }, geometry: { type: 'LineString', coordinates: part } }); };
      for (const [la, lo] of seg) {
        const q: LatLon = [la, lo];
        if (prev) along += metres(prev, q);
        prev = q;
        const o = !!trim && (along < trim.from || along > trim.to);
        if (out !== null && o !== out) { part.push([lo, la]); flush(); part = [part[part.length - 1]]; }
        else part.push([lo, la]);
        out = o;
      }
      flush();
    }
    for (const w of t.waypoints) feats.push({ type: 'Feature', properties: { name: w.name }, geometry: { type: 'Point', coordinates: [w.lon, w.lat] } });
    src?.setData({ type: 'FeatureCollection', features: feats });
  }

  /** The point [m] metres along the trimmed track. */
  function posAt(m: number): LatLon {
    const tr = trim!; const c = tr.cum;
    const i = c.findIndex((x) => x >= m);
    if (i <= 0) return tr.pts[i < 0 ? tr.pts.length - 1 : 0];
    const f = (m - c[i - 1]) / Math.max(1e-9, c[i] - c[i - 1]), a = tr.pts[i - 1], b = tr.pts[i];
    return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
  }

  /** The two handles (start / end) on a track shown for a multi-day tour – a plain import has none. */
  function startTrim(t: Track) {
    stopTrim();
    const mode = h.tourMode?.() ?? 'off';
    const pts = t.segments.flat().map(([la, lo]) => [la, lo] as LatLon);
    if (mode === 'off' || pts.length < 2 || t.stops) return;
    const cum = [0];
    for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + metres(pts[i - 1], pts[i]));
    const total = cum[cum.length - 1];
    if (total < 3 * TRIM_MIN_M) return;
    const tr = { from: 0, to: total, cum, pts, marks: [] as maplibre.Marker[] };
    trim = tr;
    for (const which of ['from', 'to'] as const) {
      const el = document.createElement('div');
      el.className = 'trimhandle ' + which;
      el.innerHTML = `<span>${esc(text(which === 'from' ? 'days_trim_start' : 'days_trim_end'))}</span>`;
      el.title = text('days_trim_hint');
      const p = posAt(tr[which]);
      const mk = new maplibre.Marker({ element: el, draggable: true, anchor: 'bottom' }).setLngLat([p[1], p[0]]).addTo(h.map);
      mk.on('drag', () => { const q = mk.getLngLat(); setTrim(which, nearestM(q.lat, q.lng), false); });
      mk.on('dragend', () => setTrim(which, trim ? trim[which] : 0, true));
      tr.marks.push(mk);
    }
  }

  function stopTrim() { trim?.marks.forEach((m) => m.remove()); trim = null; }

  /** Metres along the track of its point nearest [lat] / [lon]. */
  function nearestM(lat: number, lon: number): number {
    const tr = trim!; let best = 0, bd = Infinity;
    for (let i = 0; i < tr.pts.length; i++) { const d = metres(tr.pts[i], [lat, lon]); if (d < bd) { bd = d; best = i; } }
    return tr.cum[best];
  }

  /** A handle moved to [m] (from the map or the profile): clamped, the line and the profile follow; [settle] puts the marker on the track. */
  function setTrim(which: 'from' | 'to', m: number, settle: boolean) {
    if (!trim || !shown) return;
    const total = trim.cum[trim.cum.length - 1];
    trim[which] = which === 'from' ? Math.max(0, Math.min(m, trim.to - TRIM_MIN_M)) : Math.min(total, Math.max(m, trim.from + TRIM_MIN_M));
    drawTrack(shown);
    paintTrim();
    if (settle) { const p = posAt(trim[which]); trim.marks[which === 'from' ? 0 : 1]?.setLngLat([p[1], p[0]]); }
    (window as unknown as { __trim: unknown }).__trim = { from: trim.from, to: trim.to, total }; // for the browser tests
  }

  /** The handles and grey parts in the panel's profile, and the line naming the part. */
  function paintTrim() {
    const panel = document.getElementById('trackPanel');
    if (!trim || !panel) return;
    const total = trim.cum[trim.cum.length - 1] || 1;
    const a = (trim.from / total) * 100, b = (trim.to / total) * 100;
    const plot = panel.querySelector<HTMLElement>('.trackprofile .profplot');
    if (plot?.querySelector('.trimbar')) {
      plot.querySelector<HTMLElement>('.trimout.a')!.style.width = `${a}%`;
      const after = plot.querySelector<HTMLElement>('.trimout.b')!; after.style.left = `${b}%`; after.style.width = `${100 - b}%`;
      plot.querySelector<HTMLElement>('.trimbar.from')!.style.left = `${a}%`;
      plot.querySelector<HTMLElement>('.trimbar.to')!.style.left = `${b}%`;
    }
    const line = panel.querySelector<HTMLElement>('#trimLine');
    if (line) line.textContent = `${text('days_trim_start')} ${distance(trim.from)} · ${text('days_trim_end')} ${distance(trim.to)}`;
  }

  /** The profile's two bars: dragged like the handles on the map (pointer events, mouse and touch). */
  function profileHandles(panel: HTMLElement) {
    const plot = panel.querySelector<HTMLElement>('.trackprofile .profplot');
    if (!trim || !plot) { paintTrim(); return; }
    plot.insertAdjacentHTML('beforeend', '<i class="trimout a"></i><i class="trimout b"></i>' +
      `<button class="trimbar from" aria-label="${esc(text('days_trim_start'))}"></button><button class="trimbar to" aria-label="${esc(text('days_trim_end'))}"></button>`);
    for (const which of ['from', 'to'] as const) {
      const bar = plot.querySelector<HTMLButtonElement>('.trimbar.' + which)!;
      bar.addEventListener('pointerdown', (e) => {
        e.preventDefault(); e.stopPropagation();
        bar.setPointerCapture(e.pointerId);
        const move = (ev: PointerEvent) => {
          const r = plot.getBoundingClientRect();
          if (!trim || r.width <= 0) return;
          setTrim(which, Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)) * trim.cum[trim.cum.length - 1], true);
        };
        const up = () => { bar.removeEventListener('pointermove', move); bar.removeEventListener('pointerup', up); bar.removeEventListener('pointercancel', up); };
        bar.addEventListener('pointermove', move); bar.addEventListener('pointerup', up); bar.addEventListener('pointercancel', up);
      });
    }
    paintTrim();
  }

  /** The track to plan: the part between the handles (the engine's TrackToPlan.cut), else all of it. */
  async function trimmed(track: LatLon[]): Promise<LatLon[]> {
    if (!trim) return track;
    const total = trim.cum[trim.cum.length - 1];
    if (trim.from <= 1 && trim.to >= total - 1) return track;
    const enc = (p: LatLon[]) => p.map(([la, lo]) => `${la.toFixed(6)},${lo.toFixed(6)}`).join(';');
    const cut = await h.engine.call('trackCut', enc(track), trim.from, trim.to);
    return cut ? cut.split(';').map((q) => q.split(',').map(Number) as LatLon) : track;
  }

  function close() {
    stopTrim();
    shown = null;
    (h.map.getSource('gpx-track') as GeoJSONSource | undefined)?.setData(empty());
    document.getElementById('trackPanel')?.remove();
    document.getElementById('sheet')?.classList.remove('track');
  }

  /** The app's TrackPanel: name + Close, distance and time, climb, the grey lines, the profile, the two ways on. */
  function panel(t: Track) {
    const sheet = document.getElementById('sheet');
    if (!sheet) return;
    document.getElementById('trackPanel')?.remove();
    const p = document.createElement('div');
    p.id = 'trackPanel';
    const track = t.segments.flat().map(([la, lo]) => [la, lo] as LatLon);
    // a track without a line (the app's TrackLine.has: < 2 points or < 20 m – field bug 2026-11-06): one quiet line
    // instead of the profile, and nothing to make a plan of
    const line = track.length >= 2 && t.distanceM >= 20;
    p.innerHTML = `<div class="trackhead"><span class="trackname">${esc(t.name)}</span>
        <button class="iconbtn" id="closeTrack" aria-label="${esc(t.texts.close)}" title="${esc(t.texts.close)}">
          <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
      <div class="headline"><span class="big">${esc(t.texts.distance)}</span><span class="dist">${esc(t.texts.duration)}</span></div>
      <div class="muted">${esc(t.texts.climb)}</div>
      ${t.note ? `<div class="infoline">${esc(t.note)}</div>` : ''}
      ${t.skippedNote ? `<div class="infoline">${esc(t.skippedNote)}</div>` : ''}
      <div class="trackprofile">${!line ? `<div class="infoline">${esc(text('plan_no_track_recorded'))}</div>` : t.profile ? profileSvg(t.profile, undefined, undefined, '', '', GPX, false) : `<div class="muted small noele">${esc(text('plan_no_elevation'))}</div>`}</div>
      ${trim ? `<div class="muted small" id="trimLine"></div>` : ''}
      <div class="trackerror" id="trackError" hidden></div>
      ${line && trim && h.tourMode?.() === 'append' ? `<button class="primary" id="appendTrack">${esc(text('days_append_to_route'))}</button>` : ''}
      ${(line || t.stops) ? `<button class="primary" id="planFromTrack"><svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>${esc(t.texts.plan)}</button>` : ''}`;
    sheet.prepend(p);
    sheet.classList.add('track');
    p.querySelector<HTMLButtonElement>('#closeTrack')!.onclick = close;
    profileHandles(p);
    const planBtn = p.querySelector<HTMLButtonElement>('#planFromTrack');
    const appendBtn = p.querySelector<HTMLButtonElement>('#appendTrack');
    /** TrackToPlan on the trimmed track with the busy box (progress, Cancel stops it); null = cancelled / failed (said). */
    const fit = async (btn: HTMLButtonElement): Promise<LatLon[] | null> => {
      btn.disabled = true; btn.classList.add('busy');
      const err = p.querySelector<HTMLDivElement>('#trackError')!;
      err.hidden = true;
      const { activity, sac } = h.activity();
      let cancelled = false;
      const box = busyBox(() => { cancelled = true; h.engine.dropPlans(); });
      let r: { points?: LatLon[]; error?: string; cancelled?: boolean };
      try {
        r = JSON.parse(await h.engine.planFromTrack(await trimmed(track), activity, sac, (pr) => { if (pr.phase === 'legs') box.progress(pr.text); }));
      } catch (e) { r = { error: text('plan_from_track_failed') }; console.warn(e); }
      box.close();
      btn.disabled = false; btn.classList.remove('busy');
      if (cancelled || r.cancelled || shown !== t) return null;
      if (!r.points || r.points.length < 2) { err.textContent = r.error ?? ''; err.hidden = false; return null; }
      return r.points;
    };
    if (appendBtn) appendBtn.onclick = async () => {
      if (appendBtn.disabled) return;
      const pts = await fit(appendBtn);
      if (pts) { close(); await h.append?.(pts, t.name); }
    };
    if (planBtn) planBtn.onclick = async () => {
      if (planBtn.disabled) return;
      if (h.mayReplacePlan && !(await h.mayReplacePlan())) return;
      // a plan GPX (exported by OnPath, app or web): the plan comes back exactly, with its names – no fitting
      // (the app's planFromShown with PlanGpx.stopsOf)
      if (t.stops) { close(); await h.plan(t.stops.map((w) => [w.lat, w.lon] as LatLon), t.name, t.stops.map((w) => w.name), t.stops.map((w) => !!w.pinned)); return; }
      // the app's PlanFromTrackDialog (owner 2026-11-01): a modal box with Cancel over everything while the track is
      // routed – nothing else at the same time. Cancel stops the engine's run (multi-day tours stage 3: a 564 km track
      // takes minutes – client.ts dropPlans starts a fresh worker); the box shows how far it is.
      const pts = await fit(planBtn);
      if (!pts) return; // cancelled, failed (said), or closed / replaced meanwhile
      close();
      await h.plan(pts, t.name);
    };
  }

  /** ☰ → Import GPX (main.ts NAV entry): the file picker. */
  const pick = () => input.click();
  /** A synced track from My routes (GPX text from the engine), shown like an imported file – not kept again. */
  const showGpx = (text: string, name: string) => importFile(new File([text], name + '.gpx'), false);
  return { importFile, pick, close, showGpx, get shown() { return shown; } };
}

/** The modal "Planning a route from the tour…" box with a spinner and Cancel (the app's PlanFromTrackDialog). */
function busyBox(onCancel: () => void): HTMLDialogElement & { progress(text: string): void } {
  const d = document.createElement('dialog');
  d.className = 'share trackbusy';
  d.innerHTML = `<h2>${esc(text('plan_from_track_busy_title'))}</h2>
    <p class="busyline"><span class="msgspin" aria-hidden="true"></span><span>${esc(text('plan_from_track_busy_text'))} <b class="busypct"></b></span></p>
    <div class="row end"><button id="cancelTrackPlan">${esc(text('common_cancel'))}</button></div>`;
  document.body.append(d);
  d.addEventListener('cancel', (e) => e.preventDefault()); // Escape does not dismiss it (the app: no outside dismiss)
  d.querySelector<HTMLButtonElement>('#cancelTrackPlan')!.onclick = () => { onCancel(); d.close(); };
  d.addEventListener('close', () => d.remove());
  d.showModal();
  return Object.assign(d, { progress: (s: string) => { const b = d.querySelector('.busypct'); if (b) b.textContent = s; } });
}

const empty = () => ({ type: 'FeatureCollection' as const, features: [] });
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
