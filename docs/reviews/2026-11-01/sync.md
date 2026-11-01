# Pre-release review – phone ⇄ browser plan sync and app/web parity

Reviewed: main 15de6d99, web-spike f31b9acb (live on plan.onpath.earth: version.json build `f31b9acbba08-…`).

**Parity baseline (confirmed, OK):** the web engine compiles the app's shared files in place (`web/engine/build.gradle.kts`
`appFiles`, from the web-spike tree's `app/`). `git diff origin/main origin/web-spike -- app/` touches only VoiceGuide +
PhoneSetupE2ETest, which the web does not use. So `sync/*`, PlanCode, PlanResult, RoutingFingerprint, PlanGpx,
ActivityLayers, BRouterEngine/Planner, the profiles and the jars are byte-identical on both sides, and
`node web/parity/check.mjs` reports ok. Nothing in the shared sync code has drifted. Every finding below is about logic,
not drift.

---

## 1. HIGH – Re-pairing the same phone with a new mailbox silently drops the browser's synced routes
- **Where:** web-spike `web/engine/src/main/kotlin/com/onpath/web/WebSync.kt:205-214` (`rebase`, `sameMailbox=false`:
  `if (!sameMailbox && (!e.dirty || DEVICE)) continue`), called from `web/app/src/sync.ts:412-415` (`openFolder`).
- **Scenario:** user saves route X on the web and it is pushed to mailbox P (non-dirty in the browser). The phone does not
  pull it (app closed or offline). The user taps Unpair on the phone (DELETE P), or reinstalls and gets a restore, since
  `sync/device.txt` *is* backed up and the DB snapshot can be older. Later they pair again, which makes a new mailbox
  P2 with the same phone device id. `openFolder` finds the existing folder, `f.mailbox !== P2`, so `rebase(…, 0)` throws
  away every non-dirty item. The design assumes "the rest comes back from the phone" (web-sync.md table at line 376),
  but the phone never had X. X is now gone from both sides with no copy, no bin and no message.
- **Fix (cloud-plan):** in `rebase(sameMailbox=false)`, keep the live user items and mark them `dirty`, base 0 (the
  same as the phone's `SyncLedger.forget()`). Under rule 8 they still wait for Save. The merge dedups by id (same
  content → no copy). Drop only DEVICE / tombstones. Add a test: "saved on web, phone never pulled, phone unpaired,
  re-paired → X still listed".
- **Certainty:** confirmed by reading the code.

## 2. HIGH – After any unpair, the routes of a closed folder can't be seen, and one tap with no question deletes them
- **Where:** web-spike `web/app/src/sync.ts:178-197` (`list(folder = this.dir.open)`, `closeFolder`), `sync.ts:270-279`
  (`removeFolder`), `web/app/src/plans.ts:196-207` (`removeLines` – "Remove routes of ‹phone› from this browser",
  `onclick = () => sync.removeFolder(f.key)`, no confirmation).
- **Scenario:** (a) the phone unpairs, or the browser unpairs, or the phone gets 410. The folder closes, "Without phone"
  opens empty, and My routes shows "no routes". To the user that looks like everything was deleted. (b) New phone or
  reinstall without backup gives a new device id, so pairing opens a *new* folder. The old folder's routes, including
  unsaved (dirty) ones that exist only in this browser, are never shown again. The only action offered is a quiet text
  button that deletes them for good in one tap. That is the opposite of CLAUDE.md's "never lose a track".
- **Fix (cloud-plan):** show closed folders' routes read-only in My routes (a section "From ‹Pixel 8› (not paired)"),
  with "Move to current folder" (`syncSave` keeps the routing fields – the code is already there for `adoptNone`). Put
  a FRAGE before `removeFolder` when the folder holds dirty items (or always). Owner decision A-017 rule 16 ("no
  question") should be re-checked with the owner.
- **Certainty:** confirmed (no code path lists a non-open folder).

## 3. HIGH – Saving over a route that sync changed underneath loses the other side's edit (no conflict copy)
- **Where:** main `app/src/main/java/com/onpath/app/ui/AppViewModel.kt:3112-3150` (`overwriteSaved` → `db.replacePlanned`
  with no version check). Same on the web: web-spike `web/app/src/plans.ts:383-391` (`saveRoute` OVERWRITE →
  `sync.save(cur.code, …, existing.id)`), which goes to `SyncLedger.save` and just stacks a new version on whatever the
  ledger holds.
- **Scenario:** route X is open in Plan on the phone. The phone polls every 60 s while Plan is visible (`SyncPoll`). On
  the laptop the user, or a partner, edits X and clicks Save. The phone's pass imports it (`PlanSyncPass.import` →
  `replacePlan`, "Changed" tag only in My routes). The phone user then moves a point and taps Save. `overwriteSaved`
  replaces the DB row, export sees a plain new version (not dirty-vs-dirty), and the browser's edit is overwritten
  without a copy. The reverse also happens: the web has X open, the phone saves X, the web pulls, then the web saves.
  The merge rule "newer wins, older stays as a copy" only protects edits that were both still unsent. It does not
  protect an editor that holds a stale base. The same applies, with a smaller window, to a DB edit made while a pass is
  between export and import (`PlanSyncPass.run`, import overwrites without looking at the DB).
- **Fix:** remember the base version when a saved route is opened. App (cloud-app): the item's `updatedAt` / plan from
  `files.ledger()`, or a DB "changed" stamp. Web (cloud-plan): `existing.updatedAt` / `existing.plan` vs
  `state.savedCode`. If the stored route changed since it was opened and the change was not made here, save as
  "X (copy)" (or ask with a FRAGE) instead of overwriting. Same rule on both sides, so ideally as one shared pure
  function in `sync/`.
- **Certainty:** confirmed. Neither save path compares against the version it started from.

## 4. MEDIUM – A mailbox that comes back empty (idle purge, D1 reset) stops sync silently and forever
- **Where:** main `app/src/main/java/com/onpath/app/sync/SyncRound.kt:72-98` (404 → claim → continue,
  `ledger.apply(…, page.rev)` with `since = maxOf(since, upTo)`), `SyncLedger.kt:139` . Server: web-spike
  `web/host/src/sync.js` (`purgeIdle` deletes the row; a new claim starts at rev 0; PUT on a missing item with
  base ≠ 0 → `409 {rev:0}`).
- **Scenario:** the mailbox row disappears. Causes: 365 days idle, or the owner recreating or migrating the D1 database.
  It is named **`onpath-sync-test`** and real users' data will sit in it from tomorrow. The next round gets 404 and
  re-claims. `since` stays at e.g. 57, so GET returns nothing and the other side's new items (rev 1…56) are never
  read. Dirty items PUT with base = old rev get 409 every time. Non-dirty items are never uploaded again. The round ends
  `Done(left > 0)`, and `PlanSync.refreshState` maps Done with `left > 0` to *no problem*. The web loops 4× and says
  nothing. Both sides look "synced" and nothing moves.
- **Fix (cloud-app – shared `sync/`):** detect a revision regression (`page.rev < ledger.since`, or a 409 whose rev is
  lower than `entry.rev`). Then do a forget-style reset: `since = 0`, every live user item dirty with base 0, and a
  `Problems.warn`. Also show `Done.left > 0` after N passes as a Problem in Settings (cloud-app) and as a line on the
  web (cloud-plan). Owner: don't recycle the "test" D1 once real users are on it.
- **Certainty:** confirmed for the logic. The D1 reset is a risk, not an observed event.

## 5. MEDIUM – Item cap counts tombstones forever: once a mailbox is full, deleting doesn't make room
- **Where:** web-spike `web/host/src/sync.js` `LIMITS.items: 1000` ("tombstones and conflict copies included"), PUT
  `if (!cur && use.items >= LIMITS.items) return fail(507, "too many saved plans – delete some first")`. No code ever
  removes tombstone rows.
- **Scenario:** a heavy user (or many conflict copies, or repeated re-pairs into the same mailbox) reaches 1000 rows.
  Every new route gets 507, and the message tells the user to delete. But a delete only turns a row into a tombstone
  row, so the count stays and the user is stuck for good. The byte cap (about 250 tracks of 16 KB) has the same
  dead-end, only softer, because tombstones are small.
- **Fix (cloud-plan, host):** don't count deleted blobs in `items`. A tombstone blob is about 29–60 bytes, so count rows
  with `length(blob) > ~100`, or store a `deleted` flag the client can't hide (it can: it is encrypted, so use a size
  heuristic). Alternatively, purge tombstones older than N days, which both clients would tolerate because a missing
  item is simply never read.
- **Certainty:** confirmed. Not urgent for day-one users.

## 6. MEDIUM – "Unreadable" items (a newer format) are skipped for good, so "update OnPath" doesn't help
- **Where:** main `app/src/main/java/com/onpath/app/sync/SyncLedger.kt:131-140` (`apply`: bad blobs counted, `since`
  still moves to `upTo`). The app shows `Problem.NewerApp` ("update OnPath").
- **Scenario:** the web deploys continuously and the app updates late, so the realistic case is a future web that writes
  ITEM_VERSION/BLOB_VERSION 2. The old app skips those blobs, moves `since` past them, and after updating it never
  reads them again unless they change. Unknown *fields* and *kinds* are handled well. Only a version bump loses data.
- **Fix (cloud-app):** keep the ids/revs of blobs that could not be opened in the ledger (like `unread` lines) and retry
  them, or re-read from `since = 0` once after an app version change. This can still ship in a later version (a
  one-time re-read), but decide the policy before the first format bump. Same in `WebSync.apply`, where it comes for
  free because it is shared.
- **Certainty:** confirmed.

## 7. MEDIUM – The browser deletes its previous pending mailbox, which may be the one the phone just paired with
- **Where:** web-spike `web/app/src/sync.ts:255-262` (`pairing()`: `if (old) this.endMailbox(old.pair)`).
- **Scenario:** the user scans the QR and the phone stores the pairing. The phone's first pass is slow (bad network,
  JOIN_PULL delay) or skipped (a tour is recording and the handshake fails offline). The browser still shows "waiting",
  so the impatient user clicks "Pair phone" again. The browser sends DELETE to the old mailbox. The phone gets 410 →
  `Outcome.Ended` → `forgetHere()` → "pairing ended in the browser". No data is lost, but on day one this reads as
  "pairing is broken". Each click also uses one of the 50/day new-mailbox quota per IP.
- **Fix (cloud-plan):** don't DELETE a pending mailbox that has any item from someone other than this browser (peek
  first), or keep the old pending code alive and watch both until one answers.
- **Certainty:** confirmed by reading the code. The user behaviour is my assumption.

## 8. MEDIUM – App: a stuck outbox is never reported (413 swallowed, `left > 0` is not a problem)
- **Where:** main `app/src/main/java/com/onpath/app/sync/SyncRound.kt:91` (`413 -> Unit // cannot happen`) and
  `plansync/PlanSync.kt:327-336` (Done → problem only for `failed` / `unreadable`).
- **Scenario:** any item that keeps failing (413 after a server limit change, or the 409 loop from finding 4) stays in
  the outbox for ever. Settings → Browser sync shows "synced just now" and nothing goes to `Problems`. CLAUDE.md says
  "No silent errors".
- **Fix (cloud-app):** `Problems.warn("sync", "Item … refused as too large")` on a 413. Report `left > 0` that
  persists over 3 passes as `Problem.Refused` / a new `Problem.Stuck`.
- **Certainty:** confirmed.

## 9. LOW – Very large plans: silent truncation corrupts the item and makes it re-upload every pass
- **Where:** main `sync/SyncItem.kt` `encode`: `item.plan?.filter(…)?.take(MAX_PLAN)` (16 000 chars, cut in the middle
  of the structure). `routing/PlanCode.kt:63` (`points.take(MAX_POINTS)`, 500, also silent).
- **Scenario:** a plan with many named points (500 × ~40-char names comes to about 30 KB of code) gets cut. The other
  side can't decode it: the phone says "items from a newer app", the web has a row that won't open. On the phone,
  `export` then sees `!samePlan(planOf(t), item.plan)` on every pass and saves a new version each time, so the browser
  keeps showing "Changed". Unlikely with today's planners (TrackToPlan ≤ about 60 calls), but it is a silent cut.
- **Fix (cloud-app):** have `SyncLedger.save` refuse (return null + `Problems.warn`) instead of truncating, or drop
  names until the code fits. Cap `MAX_POINTS` in the editor with a message on both sides. The web's Share QR
  (`share.ts` `qr.make()`) also throws on long links with no catch (cloud-plan).
- **Certainty:** confirmed for the code. Real-world size is an estimate.

## 10. LOW – Merge order depends on device clocks
- **Where:** main `sync/SyncMerge.kt:43-48` (`updatedAt` first), stamps `System.currentTimeMillis()` / `Date.now()`.
- **Scenario:** a laptop whose clock is hours wrong edits at the same time as the phone. The "older" (stale) version can
  win and the fresh one turns into "X (copy)". Nothing is lost (a copy is kept), but the user sees the wrong one as
  the main route. `stamp = max(now, old+1)` keeps edits made one after the other in order, so only truly concurrent
  edits are affected.
- **Fix:** acceptable for release. Later, a Lamport-style stamp (max seen updatedAt + 1) would remove the skew.
- **Certainty:** confirmed. Low impact.

## 11. LOW – App export/import oddities
- `PlanSyncPass.run`: if `store.tours()` throws in `export`, the error is reported as "Sync files could not be written"
  (`Problem.Storage`), so the label is wrong (main `plansync/PlanSync.kt:189-195`). (cloud-app)
- Items still pending import (`links.isPending`) are not exported (`PlanSyncPass.kt` export loop). If an import keeps
  failing, local edits to that route are later overwritten by the import with no copy. Part of the same window as #3.
  (cloud-app)
- First pairing: the phone's hello is the *last* outbox entry (LinkedHashMap insertion order). Any refusal mid-push
  (e.g. 507) aborts the round before the hello is sent, so the browser keeps "waiting for the phone". Push the hello
  first. (cloud-app, shared `SyncRound`)

## 12. LOW – Web storage failures during Save are only logged
- web-spike `plans.ts` `saveRoute` (try/finally, no catch) and `main.ts:1389` `sync.saveTrack(...)` with no
  `.catch`. An IndexedDB failure (quota) closes the dialog, nothing is saved, and only `diag.ts`'s unhandled-rejection
  hook records it. Show the red HINWEIS. (cloud-plan)

## 13. LOW / info – security and hosting notes
- The JOIN item carries the phone's master pairing secret into browser B's mailbox, sealed only with B's QR-derived key.
  If B never adopts it (tab closed), the item stays until the 365-day purge. Anyone who photographed B's QR before the
  scan can read it later. Consider a short server TTL for mailboxes holding only a JOIN plus B's hello. (cloud-plan)
- `wrangler.toml` top-level comment says "the custom domain, which Cloudflare Access guards", but plan.onpath.earth is
  public (HTTP 200), which is intended for launch. `/sync` answers `401 missing token` correctly. Real users' plans
  will live in the `env.test` worker / `onpath-sync-test` D1 with `SYNC_NEW_PER_IP_DAY=50`. Fine for launch, but note
  it with #4.
