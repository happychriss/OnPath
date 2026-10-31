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
import { t } from './i18n';

const text = t; // inside panel() the track is called t

type LatLon = [number, number];

export interface GpxHost {
  map: maplibregl.Map;
  engine: EngineClient;
  activity(): { activity: Activity; sac: number };
  /** Like the app's selectActivity(userChange = false): the plan (if any) is planned again for it. */
  setActivity(a: Activity, sac: number): void;
  /** The track's route points become the plan (one undo step); [names] = point index → name (a plan GPX's <wpt>). */
  plan(points: LatLon[], title: string, names?: (string | null)[]): Promise<void>;
  status(msg: string, kind?: 'info' | 'error'): void;
  /** A file was imported and shown (not a synced track opened again): the page keeps it in My routes. */
  imported?(t: Track): void;
  fitPadding(): maplibregl.PaddingOptions;
}

export interface Track {
  name: string; activity: Activity; sac: number; switch: boolean; note: string | null; skippedNote: string | null;
  distanceM: number; durationS: number;
  texts: { distance: string; duration: string; climb: string; plan: string; close: string };
  segments: [number, number, number | null][][];
  waypoints: { lat: number; lon: number; name: string | null }[];
  /** An exported OnPath plan (the app's PlanGpx.stopsOf): its <wpt> are the route points; null = a track to fit. */
  stops: { lat: number; lon: number; name: string | null }[] | null;
  profile: PlanResult['profile'];
  error?: string;
}

/** The app's GPX colour (OnPathColors.Gpx). */
const GPX = '#8E24AA';

export function setupGpxImport(h: GpxHost) {
  let shown: Track | null = null;
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
      layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': GPX, 'line-width': 4 } });
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
    const src = h.map.getSource('gpx-track') as GeoJSONSource | undefined;
    src?.setData({ type: 'FeatureCollection', features: [
      ...t.segments.filter((s) => s.length >= 2).map((s) => ({ type: 'Feature' as const, properties: {},
        geometry: { type: 'LineString' as const, coordinates: s.map(([la, lo]) => [lo, la]) } })),
      ...t.waypoints.map((w) => ({ type: 'Feature' as const, properties: { name: w.name },
        geometry: { type: 'Point' as const, coordinates: [w.lon, w.lat] } })),
    ] });
    const pts: LatLon[] = [...t.segments.flat().map(([la, lo]) => [la, lo] as LatLon), ...t.waypoints.map((w) => [w.lat, w.lon] as LatLon)];
    if (pts.length) {
      const w = Math.min(...pts.map((p) => p[1])), e = Math.max(...pts.map((p) => p[1]));
      const s = Math.min(...pts.map((p) => p[0])), n = Math.max(...pts.map((p) => p[0]));
      h.map.fitBounds([[w, s], [e, n]], { padding: h.fitPadding(), maxZoom: 15, duration: 0 });
    }
    panel(t);
  }

  function close() {
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
    p.innerHTML = `<div class="trackhead"><span class="trackname">${esc(t.name)}</span>
        <button class="iconbtn" id="closeTrack" aria-label="${esc(t.texts.close)}" title="${esc(t.texts.close)}">
          <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg></button></div>
      <div class="headline"><span class="big">${esc(t.texts.distance)}</span><span class="dist">${esc(t.texts.duration)}</span></div>
      <div class="muted">${esc(t.texts.climb)}</div>
      ${t.note ? `<div class="infoline">${esc(t.note)}</div>` : ''}
      ${t.skippedNote ? `<div class="infoline">${esc(t.skippedNote)}</div>` : ''}
      <div class="trackprofile">${t.profile ? profileSvg(t.profile) : `<div class="muted small noele">${esc(text('plan_no_elevation'))}</div>`}</div>
      <div class="trackerror" id="trackError" hidden></div>
      ${track.length >= 2 || t.stops ? `<button class="primary" id="planFromTrack">${esc(t.texts.plan)}</button>` : ''}`;
    sheet.prepend(p);
    sheet.classList.add('track');
    p.querySelector<HTMLButtonElement>('#closeTrack')!.onclick = close;
    const planBtn = p.querySelector<HTMLButtonElement>('#planFromTrack');
    if (planBtn) planBtn.onclick = async () => {
      if (planBtn.disabled) return;
      // a plan GPX (exported by OnPath, app or web): the plan comes back exactly, with its names – no fitting
      // (the app's planFromShown with PlanGpx.stopsOf)
      if (t.stops) { close(); await h.plan(t.stops.map((w) => [w.lat, w.lon] as LatLon), t.name, t.stops.map((w) => w.name)); return; }
      planBtn.disabled = true; planBtn.classList.add('busy');
      const err = p.querySelector<HTMLDivElement>('#trackError')!;
      err.hidden = true;
      const { activity, sac } = h.activity();
      let r: { points?: LatLon[]; error?: string };
      try { r = JSON.parse(await h.engine.planFromTrack(track, activity, sac)); }
      catch (e) { r = { error: text('plan_from_track_failed') }; console.warn(e); }
      planBtn.disabled = false; planBtn.classList.remove('busy');
      if (shown !== t) return; // closed or replaced meanwhile
      if (!r.points || r.points.length < 2) { err.textContent = r.error ?? ''; err.hidden = false; return; }
      close();
      await h.plan(r.points, t.name);
    };
  }

  /** ☰ → Import GPX (main.ts NAV entry): the file picker. */
  const pick = () => input.click();
  /** A synced track from My routes (GPX text from the engine), shown like an imported file – not kept again. */
  const showGpx = (text: string, name: string) => importFile(new File([text], name + '.gpx'), false);
  return { importFile, pick, close, showGpx, get shown() { return shown; } };
}

const empty = () => ({ type: 'FeatureCollection' as const, features: [] });
const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
