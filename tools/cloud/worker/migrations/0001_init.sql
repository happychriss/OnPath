-- Doorman state (Cloudflare D1 = SQLite), docs/design/map-cells.md §5 "Job queue and dedupe".
-- One row per cell and format: the published data (version/osm_date/size) and the build job (job/priority/...).
CREATE TABLE IF NOT EXISTS cells (
  id            TEXT    NOT NULL,           -- "10-543-361"
  format        INTEGER NOT NULL,
  version       TEXT,                       -- published version folder (= OSM date); NULL = never built
  osm_date      TEXT,
  size          INTEGER,
  job           TEXT,                       -- NULL | 'queued' | 'building' | 'failed'
  priority      INTEGER NOT NULL DEFAULT 9, -- 0 point, 1 near, 2 corridor, 3 refresh (lower = sooner)
  requested_at  INTEGER,                    -- ms, first request of the current job (queue order)
  last_request  INTEGER,                    -- ms, any request (cells unused for 12 months can go)
  requests      INTEGER NOT NULL DEFAULT 0,
  attempts      INTEGER NOT NULL DEFAULT 0,
  error         TEXT,                       -- plain reason of the last failure
  permanent     INTEGER NOT NULL DEFAULT 0, -- 1 = do not retry (e.g. no source data)
  rev           TEXT,                       -- BUILD_REV when it failed: a new deploy retries failed cells
  execution     TEXT,                       -- Cloud Run execution building it
  updated_at    INTEGER,
  PRIMARY KEY (id, format)
);
CREATE INDEX IF NOT EXISTS cells_queue ON cells (job, priority, requested_at);

-- Cloud Run executions started (also the cost guard: executions per hour / day).
CREATE TABLE IF NOT EXISTS executions (
  name        TEXT    PRIMARY KEY,          -- full resource name, or 'start-failed-<ms>' for a refused start
  format      INTEGER,
  cells       TEXT,                         -- JSON array of cell ids
  started_at  INTEGER NOT NULL,
  finished_at INTEGER,
  state       TEXT    NOT NULL,             -- 'running' | 'succeeded' | 'failed' | 'start_failed'
  error       TEXT
);
CREATE INDEX IF NOT EXISTS executions_started ON executions (started_at);

-- Per-IP request counter (one row per IP and minute window).
CREATE TABLE IF NOT EXISTS ratelimit (
  key    TEXT    PRIMARY KEY,
  win    INTEGER NOT NULL,
  count  INTEGER NOT NULL
);

-- Small key/value rows: the queue pump lease.
CREATE TABLE IF NOT EXISTS meta (
  key    TEXT PRIMARY KEY,
  value  TEXT,
  until  INTEGER
);
