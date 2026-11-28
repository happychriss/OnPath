// Plan and pairing links have their own path (owner 2026-11-28): https://plan.onpath.earth/p/#p=… and /p/#pair=… – an app
// with the /p/ App Link opens them directly; in a browser the host sends /p/ to the planner page and the browser keeps the
// #fragment (web/host/src/index.js). Old links /#p=… and /#pair=… keep working. The app builds the same links
// (routing/PlanLinks).

/** The page a plan or pairing link points to, on the host this page runs on: "<origin>/p/". */
export function linkPage(href: string = location.href): string {
  return new URL(href).origin + '/p/';
}
