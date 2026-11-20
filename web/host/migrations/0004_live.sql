-- Live tracking boxes (web/host/src/live.js, docs/design/live-tracking.md §6). Opaque blobs only: no place, route,
-- name or key is ever stored here. One box per shared tour; it lives for hours, never long (live.js purgeLive).
CREATE TABLE IF NOT EXISTS live_box (
  id          TEXT PRIMARY KEY,           -- random box id (22 chars), derived from the link's secret on the devices
  read_hash   TEXT NOT NULL,              -- hex SHA-256 of the read token (in the link); '' once deleted
  write_hash  TEXT NOT NULL,              -- hex SHA-256 of the write token (only on the phone); '' once deleted
  rev         INTEGER NOT NULL DEFAULT 0, -- last revision written (every push adds one)
  created     INTEGER NOT NULL,           -- ms since 1970
  last_write  INTEGER NOT NULL,           -- the server's time of the last push (the page's "vor 2 Min.")
  ended       INTEGER,                    -- the server's time of the push that said the tour ended
  deleted     INTEGER,                    -- set when sharing was stopped or the box expired: it then answers 410
  pushes_from INTEGER NOT NULL DEFAULT 0, -- start of the hour window of the push limit
  pushes_hour INTEGER NOT NULL DEFAULT 0  -- pushes in that window
);
CREATE TABLE IF NOT EXISTS live_item (
  box  TEXT NOT NULL,
  name TEXT NOT NULL,                     -- "h" head, "r" route, "t0000"… track chunks
  rev  INTEGER NOT NULL,                  -- box revision of this version
  blob TEXT NOT NULL,                     -- Base64url of the sealed item (version, nonce, AES-256-GCM)
  PRIMARY KEY (box, name)
);
CREATE INDEX IF NOT EXISTS live_item_rev ON live_item (box, rev);
CREATE INDEX IF NOT EXISTS live_box_last_write ON live_box (last_write);
