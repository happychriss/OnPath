# Sync: how others do it – research 2026-11-12 (laptop, after the Bad Honnef field incident)

Trigger: after a phone was silent ~4 days, one browser Save made a phone-saved tour („Bad Honnef → Bonn“) disappear
(likely to the phone's recycle bin or replaced). Owner: „ein Problem, das längst gelöst ist – schau, wie andere es machen“.

## Others (sources)
- CouchDB / PouchDB – revision trees, a write names the revision it saw, conflicts keep both, same winner everywhere, the
  loser stays readable. https://docs.couchdb.org/en/latest/replication/conflicts.html · https://pouchdb.com/guides/conflicts.html
- Syncthing – version vectors decide "concurrent", not the clock; the loser kept as `.sync-conflict-…`. https://docs.syncthing.net/users/syncing
- Joplin (E2E) – on conflict the server version wins, the local one goes to a "Conflicts" notebook. https://joplinapp.org/help/apps/conflict
- Apple CloudKit – a server change tag per record; save only if the tag still matches, else `serverRecordChanged` with
  server / own / ancestor. https://developer.apple.com/documentation/cloudkit/ckerror/serverrecordchanged
- Standard Notes (E2E) – server can't merge, conflicts become duplicates. https://standardnotes.org/help/33/how-do-i-clear-duplicates
- Figma – LWW per property, order set by the server, no timestamps. https://madebyevan.com/figma/how-figmas-multiplayer-technology-works/
- Dropbox Nucleus – three trees (local, remote, synced); randomized tests of many sequences. https://dropbox.tech/infrastructure/rewriting-the-heart-of-our-sync-engine
- Local-first (Ink & Switch). https://inkandswitch.com/local-first/ · clocks: https://jaredly.github.io/posts/hybrid-logical-clocks/

## Rules most agree on
1. The version seen decides, not the clock: overwrite only what you saw; otherwise it is a conflict.
2. A conflict keeps both versions; nothing is deleted silently.
3. An edit beats a concurrent delete; a delete applies only to the version the deleter saw.
4. Pull before push; old pending changes are checked against the newest state before sending.
5. Moving / copying / re-reading is not an edit – it must not make data "newer".
6. Test many sequences automatically, including "device offline for days".

## Our design (web-sync.md) against them
- Follows: server compare-and-set (`x-onpath-base`, 409) ≈ CloudKit tag; older kept as "(copy)"; outside deletes → recycle
  bin; unreadable ledger lines kept; same winner everywhere.
- Breaks: (1) §6 picks the winner by `updatedAt` (wall clock + re-stamps); (5) `stamp() = max(now, prev+1)` in
  `SyncLedger` – `syncSave` in `adopt()` re-stamps an item that already exists in the target folder and marks it dirty;
  (4) Save and, since add7cf4d, the first round after pairing push every pending change however old, after
  `syncRebase` with base 0; (3) „a delete newer than an edit wins“ + a re-stamped old browser delete → the phone's tour
  goes to the recycle bin, only a "(copy)" remains.

## Recommendation (not decided – owner)
Keep the design and format, change four rules in the shared `SyncMerge` / `SyncLedger` (phone and browser alike):
1. conflict by base revision – the mailbox version keeps the id, the own one becomes "(copy)"; `updatedAt` only for
   display / tie-break; 2. never re-stamp on move / adopt / rebase (keep `updatedAt`, device, base; an equal item is not
   dirty); 3. a delete applies only if its base is still current, else the edit wins; 4. pull before every push.
Tests first: the incident (4 days silent, re-paired, Save) + a randomized sequence test in `SharedCoreRuleTest`.
No CRDT library (Automerge: native Rust + Wasm, new format, migration – does not address stale deletes / re-stamps).
