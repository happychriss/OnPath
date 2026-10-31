# Pre-release review – browser planner: code quality and robustness

Reviewer focus: the web planner's own code (web-spike `f31b9acb`): state, error handling, browsers, storage, tabs,
security, privacy, memory, TS ⇄ shared-code drift. Read-only. `node web/parity/check.mjs` → ok (0 warnings). There
are no node_modules, so tsc was not run. web-spike is 11 commits behind main (docs / voice only, nothing the planner uses).

Severity: BLOCKER = fix before tomorrow · HIGH · MEDIUM · LOW. Role: cloud-plan unless noted.

---

## 1. BLOCKER – A failed routing-data download is cached for good: planning in that area is broken until a reload
- **Where:** `web/app/src/engine/worker.ts:146-206` (`layoutLoads`, `partLoads`), also `:92-110` (`loading`) and `:215-231` (`indexing`).
- **Scenario:** a user plans on a phone and the network drops for a moment, or the host's `SEGMENTS_RATE` (30/min per IP,
  `web/host/wrangler.toml`) answers 429, or brouter.de returns a 5xx. `range()` throws. The rejected promise stays in
  `partLoads` / `layoutLoads`, because only the `putPart` mismatch path deletes its entry. Every later plan that needs that
  1° part gets the same rejected promise and shows **"Planning failed: TypeError: Failed to fetch"**, or "…HTTP 429",
  even after the network is back. Only a page reload fixes it, and nothing tells the user to reload.
- **Fix:** add `.catch(e => { map.delete(k); throw e; })` on each cached promise (layout, part, whole-file, indexArea,
  and the engine load). Map a fetch / 429 / 5xx failure to the app's RoutingDataNotice "offline" grey line, not the red
  "Planning failed". Retry once after a short delay.
- **Certainty:** high (read the code; I did not reproduce it).

## 2. HIGH (BLOCKER for iPhone users) – The engine is TeaVM **WasmGC**, with no feature check. Older Safari / iOS gets a broken planner and a misleading message
- **Where:** `web/engine/build.gradle.kts:88-95` (`wasmGC`), `worker.ts:97-98`, `main.ts:291-293`; the browser smokes run
  only Chromium (`web/app/test/*.mjs`).
- **Scenario:** a first user opens the planner on an iPhone with iOS < 18.2. Every iOS browser is WebKit, and WebKit has
  WasmGC only from 18.2. The map draws, but the engine fails to load. The status says "The routing engine could not be
  loaded – reload the page (CompileError …)". A reload never helps. Clicks on the map then do nothing (see 9).
  `web_no_webgl_text` also names only Chrome / Firefox / Edge.
- **Fix:** before starting the worker, detect WasmGC with a tiny GC-typed module through `WebAssembly.validate`. Also
  check module workers and anything else TeaVM 0.16 needs (exception handling?). If anything is missing, show one
  full-page message like the no-WebGL one ("This browser is too old for the planner – Safari 18.2+, Chrome 119+,
  Firefox 120+"). Smoke-test on Safari / Firefox today.
- **Certainty:** WasmGC is definitely required (`wasmGC` target). Which exact Safari versions fail is medium.

## 3. HIGH – An unsaved route is lost after the tab is closed and the planner is opened again (the app restores it)
- **Where:** `main.ts:676-690` (`openFromLink`), `main.ts:773-782` (`restoreDraft`), `main.ts:754-758` (`storeDraft`).
- **Scenario:** the user plans 100 km, does not save, and closes the tab (or iOS discards it). Later they open
  plan.onpath.earth from a bookmark or by typing it, so there is no `#p=`. The draft is in IndexedDB
  (`fach:<folder>:draft`). But `restoreDraft` only uses it when the address already holds the same points
  (`state.points.length && d.code === …`), so the page shows an empty map. There is no `beforeunload` warning either.
  The app restores the open plan at start (`AppViewModel` init, "Plan restored at app start"). "Never lose" is broken here.
- **Fix:** at start, with no `#p=` and an empty plan, load `sync.draft()` with `loadShared`, plus its saved state.
  Optionally add a `beforeunload` prompt while `saveState === 'save'`.
- **Certainty:** high.

## 4. HIGH – Clicks while the engine is busy are applied against stale points: wrong order, lost edits
- **Where:** `main.ts:557-564` (`addPoint`), `397-400` (`applyEdit`), `446-453` (marker `dragend`), `528` (`press` awaits
  `gesture`).
- **Scenario:** a 100 km plan keeps the one worker busy for seconds. The user clicks two more points. Both
  `engine.insertIndex(state.points, …)` calls are worked out against the same old list P0. Then each does
  `state.points.slice().splice(i, …)` on whatever the list is by then. The second click, meant as the new destination,
  lands before the first one: the route doubles back. In the same way, an `applyEdit` that resolves after a drag or delete
  overwrites that change.
- **Fix:** run edits through one queue (`editChain = editChain.then(…)`). Or check that `state.points` is still the array
  the index was computed for, and recompute if not. Or send the whole edit to the engine in one call that returns the new
  list.
- **Certainty:** high for the race. How often users hit it is medium (needs clicks during a running plan).

## 5. HIGH – Routes saved only in the browser can be wiped by the browser (Safari's 7-day limit, eviction); nothing asks for persistent storage
- **Where:** `web/app/src/sync.ts:62-99` (`Store`); no `navigator.storage.persist()` anywhere.
- **Scenario:** a user without the app saves routes in Safari, so they sit in "Without phone", and comes back two weeks
  later. WebKit's ITP deletes script-written storage, IndexedDB included, after 7 days of Safari use without a visit.
  Chrome / Firefox can evict best-effort storage when the disk is under pressure. My routes is empty, and the user was
  told "Saved".
- **Fix:** call `navigator.storage.persist()` on the first Save; it is supported in Safari 17+, Chrome and Firefox. When
  it is not granted and the browser is not paired, add one calm line under "Saved": "Kept in this browser only – pair
  your phone or export GPX to keep it for sure". Mention it on the privacy / help page.
- **Certainty:** medium-high (platform behaviour, not the code).

## 6. MEDIUM – No security headers: no CSP, no frame-ancestors / X-Frame-Options, no nosniff, no Referrer-Policy
- **Where:** `web/host/src/index.js:33-42` (`handle` only adds X-Robots-Tag); there is no `_headers` in `web/app/public`.
- **Scenario:** the page builds most of its UI with `innerHTML` (escaping is careful, but `menus.ts:17` is a raw sink –
  see 18). Without CSP there is no second line of defence. Without `frame-ancestors` any site can frame the planner and
  trick clicks such as "Pair this browser" or Unpair (see 7).
- **Fix:** have the worker add, to every asset answer: `Content-Security-Policy: default-src 'self'; script-src 'self'
  'wasm-unsafe-eval'; worker-src 'self' blob:; connect-src 'self' https://photon.komoot.io; img-src 'self' data: blob:;
  style-src 'self' 'unsafe-inline'; frame-ancestors 'none'`, plus `X-Content-Type-Options: nosniff` and
  `Referrer-Policy: no-referrer`. Test it with the maplibre worker and the Wasm engine. Workflow / host change → maybe a PR
  to the owner.
- **Certainty:** high that the headers are missing; the CSP needs a test.

## 7. MEDIUM – A pairing link from someone else: one click moves your browser routes into their mailbox
- **Where:** `plans.ts:509-524` (`joinDialog`), `sync.ts:266-275` (`join`), `sync.ts:430-434` + `477-499` (`openFolder` /
  `adoptNone`); text `web_join_text`: "This link pairs a device with **your** saved routes".
- **Scenario:** someone sends `https://plan.onpath.earth/#pair=<theirs>`. The dialog suggests these are the user's own
  routes, and the user clicks "Pair this browser". A fake "phone" hello with any name, e.g. "Pixel 8", opens that folder.
  All "Without phone" routes join it as waiting, and the next Save, now "Save to phone", sends them to the link's owner.
  The check digits are shown after the fact but nobody has to compare them.
- **Fix:** reword it ("This code belongs to whoever created it – only pair if you made it on your own phone"). Better:
  require the check digits shown on the phone before the folder opens, or do not adopt "Without phone" routes on a
  browser-side *join*, only on a pairing this browser started.
- **Certainty:** medium (design-level; flow read in code, not run).

## 8. MEDIUM – Start-up has no error handling for the map style: a network blip leaves a dead page without a word
- **Where:** `main.ts:209` (`style: await loadStyle()`, top-level await), `main.ts:259`
  (`await (await fetch(base+'style/onpath.json')).json()` – no `r.ok`, no catch), `overview.ts:19-27`.
- **Scenario:** on a slow phone line the style fetch fails or times out. The module throws at top level: the header and
  the sheet HTML are there, but nothing below line 209 runs. No map, no engine, no handlers, no message. If only the
  overview `HEAD` checks fail, the page quietly drops the world overview (blank below z10).
- **Fix:** try/catch around `loadStyle` with one retry, then a full-page HINWEIS "Could not load the map – Retry" (like
  `nowebgl`). Check `r.ok`.
- **Certainty:** high.

## 9. MEDIUM – After an engine failure every click silently does nothing; the engine is never loaded again
- **Where:** `worker.ts:92-110` (rejected `loading` kept), `client.ts:21-29` (a crashed worker is not restarted; each later
  call waits 180 s), `main.ts:522-534` / `375-388` / `787-802` (`engine.call` rejections are not caught → only
  `unhandledrejection` in the diag log), `main.ts:1436` (any later `status('')` erases the "could not be loaded" line).
- **Scenario:** the Wasm download breaks off on a phone, or the worker traps (out of memory on a big plan). The user clicks
  the map and nothing happens. After `onerror`, each new request waits 3 minutes for "did not answer".
- **Fix:** keep a sticky engine-error state that `status('')` cannot clear, with a Reload button (a HINWEIS through
  `msgcard`). In `onerror`, mark the client dead so later calls fail at once, or restart the worker. Clear `loading` on
  failure (see 1).
- **Certainty:** high.

## 10. MEDIUM – Raw technical errors are shown to users (CLAUDE.md robustness: plain language, no stack traces)
- **Where:** `main.ts:824` (`web_plan_failed` + `r.error` → "Planning failed: Unexpected: java.lang.IllegalArgumentException…"
  from `WebApi.kt:208` `"Unexpected: $e"`, or "TypeError: Failed to fetch"), `main.ts:292` (`web_engine_failed` + the error
  text), `main.ts:1100` (`web_activities_failed` + `String(e)`), `main.ts:1335` (`web_no_name` + `String(e)`),
  `sync.ts:343` (`web_sync_failed` + message).
- **Fix:** plain texts only; the detail goes to `problem()` / the diagnostics. A failed leg already gets the app's text,
  so only the catch-all paths need this.
- **Certainty:** high.

## 11. MEDIUM – Save / Rename / Delete / Save-as-copy failures are silent
- **Where:** `plans.ts:375-406` (`saveRoute`: try/finally, no catch), `166-174`, `182-186`, `175-179`; `sync.ts:88-98`
  (`Store.set` rejects on a quota or transaction error).
- **Scenario:** an IndexedDB write fails (quota, a Safari private-mode quirk, a storage reset) or an engine call fails. The
  name dialog has already closed and nothing is said; the header may even move on. "No silent errors."
- **Fix:** catch, `problem('sync', …)`, and say it where the user acted (the Save button turns red, or a line in My
  routes): "Not saved – …".
- **Certainty:** high.

## 12. MEDIUM – Two tabs share one draft per folder: a reload forgets which saved route it shows → Save makes a duplicate
- **Where:** `main.ts:754-758`, `773-782`; `sync.ts:239-243` (`fach:<folder>:draft`, last writer wins).
- **Scenario:** tab A shows saved route "Ifinger"; tab B opens another route, which rewrites the draft. The user reloads tab
  A: the address code no longer equals the draft code, so `savedId` stays null. Save then proposes a new route
  "Ifinger (2)" instead of overwriting.
- **Fix:** keep `savedId` / `savedTitle` / `savedCode` / `savedFolder` per tab in `sessionStorage`, or inside the `#p=`
  state; keep the per-folder IndexedDB draft only for folder switches and point 3.
- **Certainty:** high.

## 13. MEDIUM – Memory and mobile data grow without limit in long sessions
- **Where:** `main.ts:357-365` + `365` (prefetch on every `moveend` from z9), `worker.ts:171-206`, `WebApi.kt:73-75` +
  `141-159` (every part kept in `partBytes` **and** again in the rewritten in-memory rd5 file, nothing evicted),
  `cells.ts:45`/`385` (`pois` of every cell visited, all re-sent on each `setData`).
- **Scenario:** a user browses the Alps at z9+ for half an hour. Each new 1° area prefetches 2–10 MB with no click,
  roughly 100+ MB of mobile data, held about twice in Wasm memory. iOS Safari kills the tab (the plan survives in the
  address, but see 3 and 12).
- **Fix:** prefetch only when the view stops near the plan or is zoomed in further (z11+); skip it under
  `navigator.connection.saveData` / cellular; drop `partBytes` once the file is written; cap or LRU the POI cells.
- **Certainty:** medium (memory use inferred, not measured).

## 14. MEDIUM – A large GPX can crash the import on Safari, and file size is not limited
- **Where:** `gpximport.ts:117-121` (`Math.min(...pts.map(...))`, four times), `gpximport.ts:90-93` (the whole file goes to
  Wasm, no size check), `WebImport.kt` (no thinning).
- **Scenario:** a multi-day recording at 1 s has more than 65k points. JavaScriptCore's argument limit gives a RangeError
  in `show()`: the track is drawn but there is no panel or fit, only an unhandled rejection. A 50 MB file may run the Wasm
  heap out of memory (→ 9).
- **Fix:** compute the bounds with a loop (`LngLatBounds.extend`); refuse files over about 20 MB with a plain message;
  thin the display line.
- **Certainty:** high for the spread limit.

## 15. MEDIUM – Points list rows have drifted from the app's `PointsSection` (the parity check is green, the meaning is not)
- **Where:** `main.ts:461-470` vs app `ui/PointsSection.kt:301-318` (`roleText`, `legText`).
- **Differences:** (a) the web always writes `x.x km` (`number(legM/1000,1)`); the app uses `Format.distance` ("850 m",
  "123 km"). (b) Round-trip row: app "↺ 1.2 km" / "↺ ~1.2 km", web "+1.2 km". (c) The app hides the role when it equals
  the name, so an unnamed start shows "Start" once; the web shows "Start" with "Start" under it.
- **Fix:** move `legText` / `roleText` into the shared `routing/PlanPoints`, as a row text, and have `Engine.rows` emit it,
  so TS only renders. That one change goes on main (cloud-app); the web change is cloud-plan.
- **Role:** cloud-app (shared) + cloud-plan. **Certainty:** high.

## 16. LOW – A Points-list drag breaks when the list is redrawn mid-drag
- **Where:** `sortable.ts:47-62`, `main.ts:461` (`innerHTML` rebuild) and `main.ts:834` (`drawPoints()` after each plan).
- **Scenario:** the user drags ≡ right after adding a point and the route arrives. The rows are replaced, the grip loses
  pointer capture, and the drop is lost or lands later with stale rows.
- **Fix:** delay `drawPoints` while `list.classList.contains('sorting')`, or end the drag cleanly on `lostpointercapture`.
- **Certainty:** medium.

## 17. LOW – A developer line is shown to every user: "Routing engine ready (2345 ms)"
- **Where:** `main.ts:292` (`web_engine_ready`), which stays in `#status` until the first plan; `sync-smoke.mjs:39`
  depends on it. The owner wanted timings only with `?debug`.
- **Fix:** show it only with `DEBUG`, and give the tests a `data-ready` attribute.
- **Certainty:** high.

## 18. LOW – `holdMenu` puts the label and sub line into innerHTML unescaped
- **Where:** `menus.ts:17`. Today they come only from i18n or engine texts (`AddPreview.label`, `fromMeBlocked`), but a
  later POI or place name in a label would be an XSS.
- **Fix:** escape them (the head stays HTML on purpose).
- **Certainty:** high (latent).

## 19. LOW – Stored activity / T level are not validated
- **Where:** `main.ts:72-73` (`localStorageGet('activity') as Activity`, `Number(... 'sac')`).
- **Scenario:** a value that is corrupt or comes from an older build (a renamed enum) makes every plan fail with
  "Unexpected: IllegalArgumentException: No enum constant…" (`WebApi.kt:182` `Activity.valueOf`). A bad `sac` gives NaN.
- **Fix:** accept only the six names and 1–5, else use HIKE / 2.

## 20. LOW – Small iOS details
- `index.html` has no `viewport-fit=cover`, so the `env(safe-area-inset-*)` used in `style.css:284,291` is always 0.
- Inputs under 16 px make iOS Safari zoom in on focus: `.mchead input` (Map content filter, 14 px inherited) and
  `dialog.share input` (`.85rem`).
- `cells.ts:402-420` needs `OffscreenCanvas` 2d / `convertToBlob` (Safari 16.4+).
- `sync.ts:678` uses `AbortSignal.timeout` (Safari 16+). Without it every sync throws TypeError and shows "offline" for good.

## 21. LOW – Locate never stops watching the GPS; "Go here" can use an hours-old fix
- **Where:** `position.ts:55-80` (`watchPosition` with high accuracy, no `clearWatch` except on denial), `position.ts:43`
  (`once()` returns `this.fix` whatever its age).
- **Fix:** stop watching when following ends or the tab is hidden; in `once()`, treat a fix older than 2 minutes as no fix.

## 22. LOW – The engine starts loading only after the style is loaded
- **Where:** `main.ts:207-209` (top-level `await loadStyle()`) runs before `new EngineClient()` / `configure` (`main.ts:286-291`).
- **Fix:** create the client and call `configure` before the style await, so the multi-MB Wasm loads in parallel and the
  first plan comes sooner on phones.

## 23. LOW – The Photon request has no timeout; the search can show "Searching online…" for a long time
- **Where:** `search.ts:110`. **Fix:** `AbortSignal.timeout(8000)` (with a fallback, see 20).

---

### Checked and fine (no finding)
- XSS: plan, route, GPX, POI, search and phone names are escaped wherever they reach `innerHTML` (`escapeHtml`/`esc` in
  main, plans, search, share, gpximport); attributes are always double-quoted; marker titles use `.title`.
- Privacy: plans stay in `#…` (never sent); the pairing secret is taken out of the address at once
  (`main.ts:88-89`); only Photon (query + position rounded to about 1 km) and the Google link-out leave the host; the
  browser id for map areas is counts only (`maps.js`); there are no third-party scripts or fonts.
- Tabs: Web Locks + BroadcastChannel re-read the folders inside the lock (`sync.ts:621-628`, `132-147`).
- Version updates: `version.json` is `no-store`, the reload box appears on focus and every 10 min; the engine files carry `?v=`.
- Cells: failed tile reads back off, retry and are reported (`cells.ts`); the overview stays under them.
- Parity manifest is green; there is no TS copy of routing, snapping, gaps, warnings or rating – these come from the engine.
