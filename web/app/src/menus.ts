// The app's hold menus (ui/MapOverlays: FirstPointMenu, PoiMenu, PointMenu): a small title – only where the menu belongs
// to something (a place, a route point, a night) – then one row per action (icon + label, an optional line under it); a
// danger row in red. The rows come in three groups, always in this order with a thin line between them (owner 2026-11-26,
// docs/team/items/2026-11-26-context-menus-wording.md – the app's MenuGroups): ROUTE (what the point becomes – the order
// from the shared code), PLACE (Merken · In Google Maps öffnen), REMOVE (always last). Shown in a maplibre popup at the point.
import { icon, type IconName } from './icons';

/** The app's MenuGroups.Group. */
export type MenuGroup = 'ROUTE' | 'PLACE' | 'REMOVE';

/** The app's MenuGroups.lineBefore: row [i] starts a new group (it and the row before have a group, and they differ). */
export function lineBefore(groups: (MenuGroup | undefined)[], i: number): boolean {
  return i >= 1 && i < groups.length && groups[i] != null && groups[i - 1] != null && groups[i] !== groups[i - 1];
}

/** The group of a place menu's row by its key – the app's PinMenuItems.group (+ the planner's OVERNIGHT, a route row). */
export function placeRowGroup(key: string | undefined): MenuGroup | undefined {
  switch (key) {
    case 'START_HERE': case 'ROUND_TRIP_HERE': case 'GO_HERE': case 'ADD': case 'SET_DESTINATION': case 'ADD_STOP':
    case 'NEW_DESTINATION': case 'OVERNIGHT': return 'ROUTE';
    case 'FAVOURITE': case 'HOME': case 'GOOGLE': return 'PLACE';
    case 'REMOVE_PIN': return 'REMOVE';
    default: return undefined;
  }
}

/** The group of a point menu's row – the app's MapGestures.pointGroup (+ the planner's „Als Übernachtung“, a route row). */
export function pointRowGroup(action: string): MenuGroup {
  if (action === 'KEEP' || action === 'GOOGLE') return 'PLACE';
  if (action === 'DELETE') return 'REMOVE';
  return 'ROUTE';
}

export interface MenuItem { icon: IconName; label: string; sub?: string; danger?: boolean; disabled?: boolean;
  /** [row] = the clicked row (a [stay] row shows its new state there – setRow). */
  act: (row: HTMLButtonElement) => void;
  /** The menu stays open after the click (the app's kept-place rows, owner 2026-11-15: the menu shows the new state). */
  stay?: boolean;
  /** A thin line above the row in any case (a list without groups). */
  divider?: boolean;
  /** The row's group (owner 2026-11-26): a thin line where it changes ([lineBefore]). */
  group?: MenuGroup;
  /** The icon's own colour (the app's HoldMenuItem iconTint – the gold star of „★ Gemerkt“). */
  iconColor?: string;
  /** The app's row name (PinMenuItems: START_HERE, GO_HERE, ADD, …), for the rows' order. */
  key?: string;
  /** The likelier add row (the app's HoldMenuItem strong, owner 2026-11-15): tinted in the plan blue, a bold label. */
  strong?: boolean }

/** [headHtml] '' = no title (the empty map, the hold / multi-day-line choice – owner 2026-11-26). */
export function holdMenu(headHtml: string, items: MenuItem[], close: () => void): HTMLElement {
  const box = document.createElement('div');
  box.className = 'pointmenu';
  box.setAttribute('role', 'menu');
  if (headHtml) box.innerHTML = `<div class="menuhead">${headHtml}</div>`;
  else box.classList.add('nohead');
  const groups = items.map((it) => it.group);
  items.forEach((it, i) => {
    if (it.divider || lineBefore(groups, i)) {
      const hr = document.createElement('div'); hr.className = 'menudivider'; hr.setAttribute('role', 'separator'); box.append(hr);
    }
    const b = document.createElement('button');
    b.setAttribute('role', 'menuitem');
    b.className = it.danger ? 'danger' : it.strong ? 'strong' : '';
    b.disabled = !!it.disabled;
    setRow(b, it);
    b.onclick = (e) => { e.stopPropagation(); if (!it.stay) close(); it.act(b); };
    box.append(b);
  });
  return box;
}

/** A row's icon and texts – again after a [MenuItem.stay] row changed its state. */
export function setRow(b: HTMLButtonElement, it: Pick<MenuItem, 'icon' | 'label' | 'sub' | 'iconColor'>) {
  b.innerHTML = `${icon(it.icon)}<span class="txt"><span>${it.label}</span>${it.sub ? `<span class="muted small">${it.sub}</span>` : ''}</span>`;
  const ic = b.querySelector<SVGElement>('.ic');
  if (ic && it.iconColor) ic.style.fill = it.iconColor;
}
