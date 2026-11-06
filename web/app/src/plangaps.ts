// Plan points the route does not reach (the app's ui/PlanGaps, MapController.setPlanGaps; docs/design/web-planner.md
// §14): a summit above the T level or a point off any path – the router stops at the nearest allowed way. The sheet
// shows one red line (`Last 180 m to "Taser Alm" not possible by gravel bike`, + Show) and the map a grey dashed piece
// from the route's end to the point. Rule and text from the engine (the app's PlanResult.gaps, WebPlanRules.gapsJson).
import type * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource } from 'maplibre-gl';
import { roomFor } from './cardanchor';

type LatLon = [number, number];
interface Gap { index: number; point: LatLon; end: LatLon; show: [number, number, number] }
export interface Gaps { text: string | null; gaps: Gap[] }

const NONE: Gaps = { text: null, gaps: [] };

export class PlanGapsLayer {
  last: Gaps = NONE;
  private ensure: () => boolean = () => false;
  constructor(private map: maplibregl.Map) {
    const add = () => {
      if (map.getSource('plan-gaps')) return;
      map.addSource('plan-gaps', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      map.addLayer({ id: 'plan-gaps', type: 'line', source: 'plan-gaps', layout: { 'line-cap': 'round' },
        paint: { 'line-color': '#6B6F6A', 'line-width': 3, 'line-dasharray': [1.5, 1.5] } });
    };
    // as soon as the style takes it, also when 'load' already fired (isStyleLoaded() is false while tiles load)
    this.ensure = () => { try { add(); return true; } catch { return false; } }; // expected: style not done loading – retried
    map.on('styledata', this.ensure);
    this.ensure();
  }

  /** The gaps of the plan just applied (PlanResult.gaps, in the plan's answer), drawn; the sheet reads [last]. */
  set(g: Gaps | null | undefined) { this.last = g ?? NONE; this.draw(); }

  clear() { this.last = NONE; this.draw(); }

  /** "Show": the middle of the missing piece, close enough to see both ends (PlanGaps.showTarget). */
  show(padding: maplibregl.PaddingOptions) {
    const g = this.last.gaps[0];
    if (g) this.map.easeTo({ center: [g.show[1], g.show[0]], zoom: g.show[2], ...roomFor(this.map, padding) });
  }

  private draw() {
    this.ensure();
    (this.map.getSource('plan-gaps') as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features: this.last.gaps.map((g) => ({
      type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[g.end[1], g.end[0]], [g.point[1], g.point[0]]] },
    })) });
  }
}
