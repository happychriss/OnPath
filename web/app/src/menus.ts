// The app's hold menus (ui/MapOverlays: FirstPointMenu, PoiMenu, PointMenu): a small title, then one row per action
// (icon + label, an optional line under it); a danger row in red. Shown in a maplibre popup at the point.
import { icon, type IconName } from './icons';

export interface MenuItem { icon: IconName; label: string; sub?: string; danger?: boolean; disabled?: boolean; act: () => void }

export function holdMenu(headHtml: string, items: MenuItem[], close: () => void): HTMLElement {
  const box = document.createElement('div');
  box.className = 'pointmenu';
  box.setAttribute('role', 'menu');
  box.innerHTML = `<div class="menuhead">${headHtml}</div>`;
  for (const it of items) {
    const b = document.createElement('button');
    b.setAttribute('role', 'menuitem');
    b.className = it.danger ? 'danger' : '';
    b.disabled = !!it.disabled;
    b.innerHTML = `${icon(it.icon)}<span class="txt"><span>${it.label}</span>${it.sub ? `<span class="muted small">${it.sub}</span>` : ''}</span>`;
    b.onclick = (e) => { e.stopPropagation(); close(); it.act(); };
    box.append(b);
  }
  return box;
}
