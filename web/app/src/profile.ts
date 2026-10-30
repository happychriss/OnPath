// Elevation profile as a small SVG. Distances and gradient classes come from the engine – the app's own
// profile data + RouteSummary.steepness (100 m window) – so the colours match the phone (ui/ElevationProfile).
import type { PlanResult } from './engine/types';
import { t, number } from './i18n';

const COLOURS = ['#7fb77e', '#e3b23c', '#e07b39', '#c0392b']; // flat, moderate, steep, very steep

export function profileSvg(p: PlanResult['profile']): string {
  const { dist: d, ele, steep } = p;
  if (d.length < 2) return '';
  const total = d[d.length - 1] || 1;
  const lo = Math.min(...ele), hi = Math.max(...ele), span = Math.max(hi - lo, 50);
  const W = 300, H = 90, x = (m: number) => ((m / total) * W).toFixed(1), y = (e: number) => (H - 6 - ((e - lo) / span) * (H - 16)).toFixed(1);
  let bars = '';
  for (let i = 1; i < d.length; i++) {
    if (d[i] <= d[i - 1]) continue;
    bars += `<path d="M${x(d[i - 1])},${H} L${x(d[i - 1])},${y(ele[i - 1])} L${x(d[i])},${y(ele[i])} L${x(d[i])},${H} Z" fill="${COLOURS[steep[i - 1]]}" stroke="${COLOURS[steep[i - 1]]}" stroke-width="0.4"/>`;
  }
  return `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${t('plan_elevation_profile')}">${bars}</svg>
    <div class="axis muted"><span>${Math.round(lo)} m</span><span>${number(total / 1000, 1)} km</span><span>${Math.round(hi)} m</span></div>`;
}
