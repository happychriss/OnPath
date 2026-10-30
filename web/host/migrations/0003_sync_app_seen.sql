-- When the phone app last used a mailbox (web/host/src/sync.js, docs/design/web-sync.md §8): ms since 1970, rounded
-- down to the minute, written at most once a minute. The browser shows it ("Paired · phone last 3 days ago"). No IP,
-- no device id – only "a request that did not come from the web planner" (header x-onpath-client: web, browser UA).
ALTER TABLE mailbox ADD COLUMN app_seen INTEGER;
