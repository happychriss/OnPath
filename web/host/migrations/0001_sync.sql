-- Plan sync mailbox (docs/design/web-sync.md §8). Opaque blobs only: no plan, name or key is ever stored here.
CREATE TABLE IF NOT EXISTS mailbox (
  id         TEXT PRIMARY KEY,          -- random mailbox id (22 chars), derived from the pairing secret on the devices
  token_hash TEXT NOT NULL,             -- hex SHA-256 of the mailbox token; '' once the pairing was ended
  rev        INTEGER NOT NULL DEFAULT 0,-- last revision written (every PUT adds one)
  created    INTEGER NOT NULL,          -- ms since 1970
  seen       INTEGER NOT NULL,          -- last use (updated at most daily); idle mailboxes are deleted
  deleted    INTEGER                    -- set when unpaired: the mailbox then answers 410
);
CREATE TABLE IF NOT EXISTS item (
  mailbox TEXT NOT NULL,
  id      TEXT NOT NULL,                -- random item id (22 chars)
  rev     INTEGER NOT NULL,             -- mailbox revision of this version
  blob    TEXT NOT NULL,                -- Base64url of the encrypted item (version, nonce, AES-256-GCM)
  PRIMARY KEY (mailbox, id)
);
CREATE INDEX IF NOT EXISTS item_rev ON item (mailbox, rev);
CREATE INDEX IF NOT EXISTS mailbox_seen ON mailbox (seen);
