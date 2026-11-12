# Handoff laptop – 2026-11-12 (evening / night)

For donald and the captain: what the laptop changed today, what it means for the app, what is open.
Planner deployed four times today; last deploy = main after the sync rule fix (run 43314677190, quick deploy green).

## 1. Built and live in the planner
- **Calm elevation profile** like the app (owner picked variant A from a sketch): route-blue fill fading, steepness only on
  the thin line, legend only for line colours. The map's steepness line: flat stretches now route blue (was green).
- **POI as route point keeps its symbol** (owner 2026-11-10, variant A) – blue ring + numbered badge, **app AND planner**
  (`poi/PinnedPoiKinds.kt`, `MapController`, `MapScreen`, `web/app/src/poipin.ts`). The app part is on main but **not yet
  seen on a device** – donald: please look at it on the emulator / with the next release.
- **Undo / redo pill** in the planner like the app's (own pill, only when something can be undone).
- **Activity list no longer slides over its chip** (real bug: the pace part arrived ~150 ms later and `place()` moved the
  list up) – this was the red "Web planner" run since 2026-11-08.
- **Pairing in the browser** (owner field report: the Pixel had lost its pairing – likely reinstall – the browser still
  showed it): phone silent ≥ 3 days → chip yellow „seit N Tagen nicht verbunden“, the Phone window one sentence +
  „Neu verbinden“ (unpair + new code in one step). The old-phone „Pläne von … entfernen“ lines are gone (owner: confusing).
  „Jetzt abgleichen“ says done / nothing new. After any pairing the browser pushes everything waiting at once (owner: a
  Save first was not ok).

## 2. Field incidents on the owner's Pixel (today)
- **„Bad Honnef → Bonn“ disappeared** after a browser Save (my advice – wrong). It was saved on the phone. Likely cause
  (now covered by tests): the browser's `adopt()` re-saved tours with a stamp 1 ms newer than the phone's, so an old
  browser state replaced the phone's tour without a copy; or an old browser delete with a newer clock sent it to the
  bin. **Not yet confirmed where it is** – owner asked to look in the phone's recycle bin and for „(Kopie)“.
- **52 phone plans came back as NEW copies** (donald's evidence, backlog „For the roles“ 23:00): the phone (reinstalled,
  sync files gone – `files/sync` is not in the Android backup) no longer knew which sync item belongs to which of its
  tours; the browser's folder carried those items (other ids) and pushed them → the phone created them as new.
  **The sync rule fix below does NOT cover this** (different ids – no conflict to resolve). Open, see §4.

## 3. Sync rules fixed (shared code – phone and browser) – owner decided after a research round
Research: `docs/design/sync-research-2026-11-12.md` (CouchDB, Syncthing, Joplin, CloudKit …). Rules now:
1. **The version that reached the mailbox first keeps the id** – `updatedAt` (the clock) decides nothing; the other
   side's live version stays as „(copy)“. Device hellos / JOIN keep the old rule (one writer each).
2. **Moving tours between folders never re-stamps** – new `SyncLedger.take` / web `syncTake`.
3. **A delete applies only to the version it saw** – otherwise the edit wins and the delete is dropped.
4. **Pull before push** (both rounds already did; pending items are now checked by 1 + 3).
Tests: incident tests in `CorePlanSyncTest` (red before), `SyncRulesRandomTest` (400 seeded runs; 100 red before, 0
now), `PlanSyncTest.takingAToursFolderNeverMakesOldDataNewer`; three old tests changed from „newer clock wins“.
Files: `sync/SyncMerge.kt`, `sync/SyncLedger.kt`, `web/engine/…/WebSync.kt`, `WebApi.kt`, `web/app/src/sync.ts`,
`docs/design/web-sync.md` §6/§7/§14.
**For the app:** the phone gets rules 1 + 3 only with a release – needs the owner's word. Old 0.17.x stays compatible
(same format; converges to one live version + one copy).

## 4. Open – laptop
- **52 copies on re-pairing** (claimed): test first (phone with lost sync files + browser folder with the same tours under
  other ids → re-pair → no new copies), then the fix. Likely: match an incoming NEW item to an existing unlinked local
  tour with the same plan content (+ title) and link it instead of creating a tour – phone side (`plansync/PlanSyncPass`)
  and the browser's adopt. After the fix is on main: tell donald (backlog) to move the 52 copies to the bin (owner's yes).
- **Tours in old browser folders are unreachable** since the „entfernen“ lines went (9817f512) – needs a quiet way to
  take them over. Not urgent for the owner (he will clear the browser).
- **Planner sort chips** like the app (donald 3b26b558) – parity marked checked to let the fix deploy; backlog item.
- **Owner's clean start** (agreed): phone ZIP export (Meine Touren → ⋮ → „Alle exportieren (ZIP)“) + Trennen on the
  phone, browser site data cleared, re-pair only after the 52-copies fix and the app release.

## 5. Decided today for donald (backlog „Next“)
- Recordings under 50 m go to the recycle bin at the end, notice with Undo (owner 2026-11-12).
