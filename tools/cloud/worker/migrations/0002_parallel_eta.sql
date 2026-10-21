-- Parallel builds, priority classes and the live ETA (docs/design/map-hosting.md §7f).
-- Smoothed ETA per cell: the finish time last reported (ms) and when (the next answer moves towards the new
-- estimate instead of jumping).
ALTER TABLE cells ADD COLUMN eta_at INTEGER;
ALTER TABLE cells ADD COLUMN eta_seen INTEGER;
-- 1 = the execution carries cells someone is waiting for (point / near)
ALTER TABLE executions ADD COLUMN waiting INTEGER NOT NULL DEFAULT 0;

-- Measured builds (from the builder's status file of a ready cell): the rolling averages behind the ETA.
CREATE TABLE IF NOT EXISTS builds (
  id           TEXT    NOT NULL,
  format       INTEGER NOT NULL,
  execution    TEXT,
  finished_at  INTEGER NOT NULL,            -- ms
  build_s      REAL,                        -- cell wall time without extract downloads / lock waits
  new_source   INTEGER NOT NULL DEFAULT 0,  -- 1 = an extract had to be downloaded (or was being downloaded) for it
  download_s   REAL,                        -- seconds spent downloading extracts for this cell (0 = cached)
  download_mb  REAL                         -- MB downloaded for it
);
CREATE INDEX IF NOT EXISTS builds_finished ON builds (finished_at);
