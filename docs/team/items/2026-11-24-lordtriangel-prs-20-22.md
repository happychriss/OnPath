# LordTriangel's pull requests 20–22 (MyPath) – parked

Owner 2026-11-24: „wir packen es erstmal auf die todo liste, erstmal die anderen dinge erledigen“. Read only so far –
never approve, never run their CI, never check out and run their code on donald. All three merge cleanly on main
(checked with `git merge-tree` 2026-11-24). The owner decides each one.

- **PR 20 „Offizielle Routen bevorzugen“ (Aus / Normal / Stark):** clean, tested; Off = default, nothing changes.
  Missing: the planner has no such setting → with Normal / Strong app and planner route the same plan differently
  („same routes“ rule); the reroute during a tour ignores it; values (0.8 / 2.0, 0.4 / 1.0) not measured on real tours;
  three explanation lines in the menu (owner: menus minimal); no i18n meta entries. Risk medium.
- **PR 21 slope under the finger („+12 %“ in the profile label):** small, display only, tested. Missing: a large-font
  picture; the planner's profile. Risk low.
- **PR 22 drag a route point on the map:** against the owner's decision „Hold-only in Plan, no dragging points“
  (`docs/project-status.md` Decisions); the code says „owner 2026-11-14“, not found in our notes. Touches the core hold
  gesture (just rebuilt for slow phones), no E2E; a plan change during the drag can move the wrong point (index).
  Risk high.
- Planner part: the laptop's notes went to the owner (all three lack the planner side).
