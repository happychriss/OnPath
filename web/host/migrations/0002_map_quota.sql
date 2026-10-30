-- New map areas per browser, per IP and for everyone (web/host/src/maps.js, docs/design/web-planner.md §18): counts
-- only. k = "b:<random browser id>" | "ip:<hash of the IP, salted with the host's secret and the day>" | "all";
-- period = "2026-10-30" (day) or "2026-10" (month). No cell id, no position, nothing else. Old rows are deleted daily.
CREATE TABLE IF NOT EXISTS map_quota (
  k      TEXT NOT NULL,
  period TEXT NOT NULL,
  n      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (k, period)
);
