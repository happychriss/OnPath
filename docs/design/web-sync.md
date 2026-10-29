# Plan sync phone ⇄ browser – phase 2 of the web planner (cloud, 2026-10-27)

Owner decisions (private plan "App + browser, version 2", 2026-10-26): pair once like WhatsApp Web (the browser shows
a QR code, the app scans it once); phone and browser share a secret key that **never goes to the server**; plans are
encrypted on the device and pass through a small **mailbox** (Cloudflare Worker + D1) both ways; the server sees no
content, no names, no e-mail; an offline phone syncs when it is back; **the newer version wins, the older stays as a
copy**; unpair on both sides. Not in phase 2: login, payment, quotas, recorded tracks, multi-day plans.

Status: **built and tested in the cloud (branch `web-spike`; the shared Kotlin on `feat/plan-sync`), not deployed.
The app part is still to do** (§11, desktop session with the phone).

**Branches:** `feat/plan-sync` = one commit with only new files (`app/.../sync/` + tests) on top of main `cf181ae` –
it can be merged into main (or cherry-picked) as it is. Merging it into `web-spike` also brought main's newer app
code, which the web engine could not compile then (main's string resources `Txt` / `Res`); commit `72a84218` on
`web-spike` took that part out again. **Done 2026-10-29:** the engine now generates `Res.string.*` / `Res.plurals.*`
from the same English `strings/…/values/*.xml` (`generateStrings` in `web/engine/build.gradle.kts`, stand-ins
`com/onpath/strings/Txt.kt` + `org/jetbrains/compose/resources/StringResource.kt`); `72a84218` was reverted and
origin/main merged – web-spike carries main's app code again and merges main regularly (web-planner.md §14).

## 1. Pieces

| Where | What |
| --- | --- |
| `app/src/main/java/com/onpath/app/sync/` (pure Kotlin, branch `feat/plan-sync`) | `SyncCrypto.kt` (SHA-256, HMAC, HKDF, AES-256-GCM, `Aead`), `PairCode.kt` (pairing code, `SyncKeys`), `SyncItem.kt` (item + blob format), `SyncMerge.kt` (conflict rule), `SyncLedger.kt` (one device's local side). Compiled into the web engine like `PlanCode` – app and browser run the same code. |
| `app/src/test/java/com/onpath/app/sync/` | `SyncCryptoTest` (FIPS 180-4, RFC 4231, RFC 5869, FIPS 197, GCM spec vectors; byte-equal to javax.crypto on 200 random inputs), `PlanSyncTest` (formats, frozen v1 values, merge, ledger, two ledgers through a fake mailbox). Run by `./gradlew -p web/engine test`. |
| `web/engine/.../WebSync.kt` | string calls for the page (`syncKeys`, `syncApply`, `syncOutbox`, …; exports in `WebApi.kt`) |
| `web/host/src/sync.js`, `web/host/migrations/0001_sync.sql` | the mailbox (Worker route `/sync/v1/…`, D1) |
| `web/app/src/sync.ts`, `plans.ts` | browser: IndexedDB store, sync loop, ☰ → My plans / Pair phone, join dialog |
| Tests | `web/host/test/sync.test.js` (mailbox on Node's SQLite as fake D1), `web/app/test/sync-engine.mjs` (Wasm = JVM frozen bytes), `web/app/test/sync-smoke.mjs` (two browser contexts, end to end) – all in CI, none needs map data |

## 2. Threat model

**The server (Cloudflare, or anyone who reads its database) sees:** a random mailbox id (no link to a person), the
SHA-256 of the mailbox token, per item a random id, a revision number, the blob size, and when and from which IP
requests come (Cloudflare logs; the Worker stores no IP). It does **not** see plan points, titles, device names,
the number of points, the activity or the pairing secret – all of that is inside AES-256-GCM.

**The server can:** withhold or delete blobs, replay an old blob, refuse service, count items and see activity times.
**It cannot:** read or change a plan unnoticed (GCM tag), move a blob to another item or mailbox (the item id and
mailbox id are in the additional data), or write without the token. A replayed old version is just an "older
version" – the newer wins locally, nothing is lost. Rollback detection beyond that (signed revision chain) is not
built – overkill for hiking plans.

**Someone with the pairing QR / link** (shoulder-surfing, a screenshot) has everything: they can read and change the
saved plans. Mitigations: the code is shown only on request, the page takes `#pair=` out of the address at once, the
browser says "Keep this code to yourself", Unpair ends the mailbox for everyone. The six check digits show the user
that the phone scanned *this* browser's code.

**On the device:** the secret, the plain plans and the ledger live in the browser's IndexedDB / the app's private
storage – as safe as the device. Not encrypted at rest (a key next to the data would add nothing without a password).

**Lost phone:** unpair from the browser (deletes the mailbox; the phone's local copies stay on the phone).

## 3. Crypto choice

- **SHA-256, HMAC, HKDF in pure Kotlin** – TeaVM has no `javax.crypto` / `MessageDigest`; ~100 lines, proven by the
  RFC vectors and by comparison with the JVM.
- **AES-256-GCM in pure Kotlin as well** (≈150 lines), not WebCrypto: WebCrypto is async-only and lives outside the
  Wasm engine, so the blob format would be split over TypeScript and Kotlin; with pure Kotlin the browser runs exactly
  the code the JVM tests check, synchronously. Proven: GCM spec test cases 13–16, FIPS 197, every byte flip refused,
  and byte-identical to `javax.crypto` `AES/GCM/NoPadding` on random keys / nonces / AAD / lengths.
  Not constant-time (S-box lookups) – acceptable: only the device itself encrypts its own small plans, no remote party
  can time it. **The app may use either** – `Aead` is the interface; `PlanSyncTest.javaxCryptoOpensTheSameBlob`
  shows a javax.crypto `Aead` (12 lines, in the test) makes the same blob. Recommendation: javax.crypto on Android
  (platform, hardware AES), the shared one in the browser.
- **Random bytes** come from the platform (`SecureRandom` / `crypto.getRandomValues`); the shared code takes them as
  parameters (TeaVM has no `SecureRandom`). Nonces are 96-bit random per blob (safe far beyond 2³² blobs per key).

## 4. Pairing code and keys

Link: `https://plan.onpath.earth/#pair=<code>` (after "#": never sent to a server). Only text with `pair=` is read –
a bare plan code also starts with byte 1. `PlanCode.parse` ignores pairing links and `PairCode.parse` ignores plan
links (tested).

```
code = Base64url, no padding, 44 characters, of:
  [0]     version = 1
  [1..32] secret (32 random bytes)
  (a newer version may append fields; version-1 readers ignore them)
```

Derived with HKDF-SHA256 (RFC 5869), IKM = secret, salt = UTF-8 "OnPath sync v1", info = label:

| label | bytes | use |
| --- | --- | --- |
| `mailbox id` | 16 | mailbox id, Base64url (22 chars) – the only name the server knows |
| `mailbox token` | 32 | Base64url (43 chars), header `Authorization: Bearer …`; server keeps hex SHA-256 of the token text |
| `content key` | 32 | AES-256-GCM key; never leaves the device |
| `check digits` | 4 | big-endian uint32 mod 1 000 000, shown "395 514" on both screens |

Frozen test values (`PlanSyncTest`, `sync-engine.mjs`): secret bytes `i*7+1` (i = 0..31) → code
`AQEIDxYdJCsyOUBHTlVcY2pxeH-GjZSboqmwt77FzNPa`, mailbox `6_ppugL_I2cbDsZYhdOddA`, token
`wHErkFiAjM3dUXVy18TJETV0arl4vvuSa21gZUBEpjE`, check `395 514` (also checked independently with Python's hmac/hashlib).

## 5. Item and blob formats

**Item** (plain bytes, version 1):
```
version(1)=1 | kind(1): 1 plan, 2 device hello | id(16) | device(8) | updatedAt (varint, ms since 1970)
| flags(1): bit 0 = deleted | fields until the end: tag (varint), length (varint), bytes
    1 = title (UTF-8, ≤ 100 chars)   2 = plan code (ASCII, PlanCode format v1)   3 = device name (UTF-8, ≤ 60)
    unknown tags are skipped (newer apps)
```
A plan item's payload is the app's **plan code** – points, activity, T level, pinned points, point names – the same
text as in a plan link, so phase 1 and 2 share one plan format. Device hello: id = first 16 bytes of
SHA-256("OnPath device " + device id), name like "Pixel 8" / "Chrome on Linux". A deleted item is a tombstone
(no title, no plan).

**Blob** (what the mailbox stores, version 1):
```
version(1)=1 | nonce(12) | AES-256-GCM(content key, nonce, AAD, item) = ciphertext | tag(16)
AAD = version byte | mailbox id (16 bytes) | item id (16 bytes)
```
A 20-point plan with title ≈ 160 bytes; 500 points ≈ 3 KB. On the wire and in D1: Base64url text.

## 6. Conflict rule (`SyncMerge`)

- **Newer wins** – total order: `updatedAt`, then device id, then the item bytes. Every device picks the same
  winner whatever order changes arrive in (`merge(a, b) == merge(b, a)`, `merge(a, a) = a`).
- **The older stays as a copy** when it is a live plan with other content: id derived from the older version
  (SHA-256 of "OnPath copy " + id + updatedAt + device – every device derives the same copy, so it never doubles),
  title "… (copy)". Tombstones and device hellos are never copied.
- **A delete newer than an edit** still keeps the edit as a copy – a plan is never lost. A delete older than an edit
  loses quietly.
- **Not a conflict:** a remote change on an item not edited here since its base revision – the mailbox's
  compare-and-set proves the writer saw this version first.
- Local edits get `updatedAt = max(now, previous + 1)`, so a device with a slow clock still moves its own plan forward.

## 7. One device's side (`SyncLedger`) and a sync round

The ledger holds every item with its base revision and a dirty flag, plus `since` (mailbox revision read up to).
Text form (stored as one string): header `onpath-sync 1 <since>`, then a line per item `<rev> <d|-> <item Base64url>`.
Unreadable lines are kept verbatim (never dropped).

A round (the same on phone and browser):
1. `hello` (once per name change).
2. **Pull:** `GET /sync/v1/<mailbox>?since=<since>` (pages of 200) → `apply` (open blobs, reconcile, move `since`).
   404 = mailbox not claimed yet or expired → `POST` (claim, any key holder may) and read again.
3. **Push:** for each dirty item `PUT /sync/v1/<mailbox>/<id>` with `x-onpath-base: <rev>` → `acked(item, rev)`.
   409 = someone wrote first → back to 1 (the merge keeps both). At most 4 passes per round.
4. 410 / 403 = the pairing was ended → `forget()` (plans stay, marked to upload again on a new pairing), forget the
   secret, tell the user. Network error → "Offline – your plans sync when the connection is back." (INFO).

Triggers (browser): after every local change (0.4 s), every 30 s while the page is visible, on `online` and when the
tab becomes visible; every 3 s while the pairing dialog waits for the phone.

## 8. Mailbox API (`web/host/src/sync.js`)

Same hostname as the planner (`plan.onpath.earth`) → no CORS. All answers JSON, `cache-control: no-store`.
Header `Authorization: Bearer <token>` on every call.

| Call | Answer |
| --- | --- |
| `POST /sync/v1/<mb>` | 201 `{rev}` claimed (stores SHA-256 of the token); 200 same token; 403 other token; 410 ended |
| `GET /sync/v1/<mb>?since=N` | 200 `{rev, more, items:[{id, rev, blob}]}` – items with rev > N, ≤ 200; `rev` = what this page covers |
| `PUT /sync/v1/<mb>/<id>` body = blob bytes, `x-onpath-base: B` | 200 `{rev}`; 409 `{rev}` when the item is not at B; 413 > 32 KiB; 507 limits |
| `DELETE /sync/v1/<mb>` | 200 – all items deleted, the mailbox answers 410 from then on (cannot be claimed again) |
| others | 401 no/odd token, 404 unknown mailbox / path, 429 rate limit, 503 no database bound |

- **Compare-and-set in one D1 batch** (one transaction): `UPDATE mailbox SET rev = rev + 1 … WHERE` the item is
  still at B, then `INSERT … SELECT … WHERE changes() = 1` – two writers on the same item: exactly one wins, the
  other gets 409.
- **Limits:** item 32 KiB, 1000 items and 4 MiB per mailbox (tombstones and copies count), pages of 200;
  **rate limit** 120 requests / min / IP (Workers rate-limit binding `SYNC_RATE`; a round is 2–5 requests).
- **Expiry:** a daily cron (`17 3 * * *`) deletes mailboxes not used for 365 days, and ended pairings after the same
  time. `seen` is written at most once a day per mailbox.
- **Schema:** `migrations/0001_sync.sql` (`mailbox`: id, token_hash, rev, created, seen, deleted; `item`: mailbox,
  id, rev, blob). D1 database `onpath-sync-test`, EU jurisdiction.
- Not built: deleting old tombstones (they are ~60 bytes; the item limit catches abuse), a cap on new mailboxes per
  day (the rate limit covers the test), per-mailbox write rate.

## 9. Browser (web/app)

- ☰ → **My plans**: save the current plan (name field, "Save as a new plan" when the open plan is a saved one), list
  newest first with date and "from the other device", open, rename, delete (asks once: "It is also deleted on the
  paired phone."), sync status line, "Sync now" when paired. The plan sheet's ⋯ → "Save to My plans" opens the same.
- ☰ → **Pair phone**: QR code of the pairing link, "In the OnPath app: ☰ → Pair with browser, then scan this code",
  check digits, "Waiting for the phone…" → "Paired with Pixel 8"; then **Unpair** (asks: "Plans stop syncing; they
  stay on this browser and on the phone").
- A pairing link opened in a browser (e.g. the phone's camera opened the web page instead of the app): "Pairing code –
  usually you scan it with the OnPath app; you can also pair this browser instead". The secret is removed from the
  address bar at once.
- Storage: IndexedDB `onpath` / `kv` (keys `pair`, `device`, `ledger`); blocked storage → memory only, with a warning.
  Web Locks keep two tabs from writing the ledger at the same time.
- Test: `web/app/test/sync-smoke.mjs` – context A "browser" (desktop), context B "phone" (Android UA): pair by link,
  same check digits, "Paired with Chrome on Android", save → B, rename on B → A, B offline + rename, A renames later
  → both show "Browser name" + "Phone name (copy)", open a synced plan, delete with the question → gone on B, reload
  keeps plans + pairing, D1 holds no readable title / name / pairing code, unpair on B → A says "ended on the other
  device", plans stay on both, mailbox empty. 21 checks.

**Demo without deploying:** `cd web/app && npm run build && node test/sync-smoke.mjs` runs both sides headless. For a
manual look: a few lines around `serve({ …, sync: (r) => handleSync(r, { SYNC: fakeD1() }) })` from that test, then
open the page in two browser windows (one private). The claude.ai preview cannot show sync (no mailbox there).

## 10. Owner steps in Cloudflare (once, after web-planner.md §9)

1. **D1 database:** Cloudflare → Storage & Databases → D1 → Create → name `onpath-sync-test`, location **EU
   jurisdiction** (or `npx wrangler d1 create onpath-sync-test --jurisdiction eu`). Copy its **database id**.
2. **GitHub variable** `SYNC_D1_ID_TEST` = that id (Settings → Secrets and variables → Actions → Variables).
3. **API token** `CLOUDFLARE_PLANNER_TOKEN`: add the permission **Account · D1 · Edit** (keep Workers Scripts ·
   Edit, Zone · Workers Routes · Edit, DNS · Edit).
4. **Access bypass for the mailbox:** Zero Trust → Access → Applications → Add → Self-hosted, name "Planner sync",
   domain `plan.onpath.earth`, **path `sync/`**; policy action **Bypass**, include **Everyone**. (The more specific
   path wins over the planner's Allow app; the phone app cannot log in to Access – the mailbox token is the lock.)
   Check: `curl -i https://plan.onpath.earth/sync/v1/x` → 404 JSON from the Worker, not the Access login page.
5. Actions → Web spike → Run workflow → tick *deploy* (applies `migrations/` to D1, then deploys; the cron and the
   rate limit come with the deploy).
6. Later, for production: a separate database (`onpath-sync`), the privacy notice extended (§12).

## 11. App side – task list for the desktop session (donald, phone on USB)

Merge `feat/plan-sync` (or main once it is in) – the shared code is ready and tested; the app adds only platform parts.

1. **SharedCoreRuleTest:** add `"sync"` to `packages` (the sync package is pure; keep it that way).
2. **Storage:** one private file (e.g. `filesDir/sync/ledger.txt`, written atomically: temp + rename) for
   `SyncLedger.write()`, and `filesDir/sync/pair.txt` for the pairing code + device id (`SyncFormat.newDevice` from
   `SecureRandom`, once per install). App-private storage is enough (no Play Services, no EncryptedSharedPreferences:
   a key next to the data adds nothing; Android's file-based encryption covers the device). Exclude `sync/` from
   Android backup (`dataExtractionRules` / `fullBackupContent`) so a restored phone does not reuse an old mailbox.
3. **Saved routes ⇄ sync items:** sync only *plans* (saved routes made in Plan mode), not recordings. Keep a small
   table route id ⇄ item id. Saving / renaming / deleting a saved route → `ledger.save / rename / delete`
   (`plan` = `PlanCode.encode(SharedPlan(...))` of that route, title = route name). A new or changed item from the
   mailbox → create / update the saved route (route it again with the Planner like an opened plan link);
   a tombstone → move the route to the recycle bin (C11), never hard-delete. A "… (copy)" item is a normal saved route.
4. **Pairing:** ☰ → "Pair with browser" → in-app QR scanner (ZXing core, Apache-2.0; CAMERA permission asked only on
   this tap) – **or** the system camera opens the `#pair=` link and the app's link filter (A-005) catches it.
   The app's plan-link filter (`PlanLinks`, host `plan.onpath.earth`, A-005) already receives these links: send
   `#pair=` to pairing (`PairCode.parse`), `#p=` stays a plan (the two never mix, tested).
   `PairCode.parse` → show `SyncKeys.check` ("Check: 395 514 – the browser shows the same digits") → Pair / Cancel →
   store the code, `hello(device, Build.MODEL, now)`, sync at once. Already paired → "Unpair first".
5. **Sync round** (§7) in a `SyncWorker`-like class on a background thread: `HttpURLConnection` to
   `https://plan.onpath.earth/sync/v1/…`, 15 s timeouts; JSON with the app's own small parser (no org.json in shared
   code; the app layer may use org.json). Use `SyncFormat.fromText/toText` for blobs. `Aead`: javax.crypto
   (`AES/GCM/NoPadding`, 128-bit tag) or `AesGcm` – both proven equal.
6. **Triggers:** after a save / rename / delete, at app start and when the app comes to the foreground, when the
   network comes back (ConnectivityManager callback), at most every few minutes while open. No background sync when the
   app is closed (not needed for phase 2; WorkManager later if the owner wants it). Never during recording (robust
   first) – or only after the tour ends.
7. **Settings → Plan sync** (or under Privacy): "Paired with Chrome on Linux · synced 14:05", "Sync now", **Unpair**
   (question: "Plans stop syncing; they stay on this phone and in the browser." → `DELETE`, then `ledger.forget()`).
   410 / 403 from the server → "The pairing was ended in the browser. Your plans stay on this phone."
8. **Messages:** offline → INFO grey "Plans sync when you are online"; server refused → WARNING with "Try again";
   unreadable items → WARNING "n synced plans come from a newer app – update OnPath". Every catch → `Problems`.
9. **Tests (core-feature style):** `PlanSyncTest` already covers formats + merge; add: ledger file round trip and
   crash safety (temp file), route ⇄ item mapping (save, rename, delete → recycle bin, remote update replans), pairing
   link from the scanner and from the link filter, unpair, an end-to-end test against a local mailbox
   (`web/host/src/sync.js` runs in Node; or a MockWebServer copy of its rules).
10. **Privacy:** Settings → Privacy list gains the line in §12; the website privacy notice needs the same (marketing).

## 12. Privacy text (proposal, for Settings → Privacy and the website)

> **Plan sync with the web planner (only when you pair).** Your saved plans are encrypted on your phone and in your
> browser before they leave the device. They pass through our sync server (Cloudflare, EU) as locked data: the server
> cannot read them and never learns your name, e-mail or where you plan to go. It stores a random mailbox number, the
> locked plans and when they last changed. Unpairing deletes everything there at once; a mailbox nobody uses for 12
> months is deleted automatically. Whoever has your pairing code can see your saved plans – do not share it.

## 13. Open decisions (owner)

1. **Scope of "plans" on the phone:** all saved routes made in Plan mode (recommended) or only those the user marks
   "sync"? Imported GPX routes – sync (as plan points) or not (recommended: not in phase 2; they are tracks, not plans)?
2. **More than one browser / a second phone:** works already (every holder of the code joins the same mailbox);
   show "Show code again" in the app too? Recommended: yes, later.
3. **Tombstone clean-up and item limit** (1000 per mailbox): fine for phase 2; revisit with real numbers.
4. **Production mailbox:** separate D1 database and Access-free host when the planner goes public; the same code.
5. **Rate limit numbers** (120/min/IP) and idle expiry (12 months) – defaults, adjust after the test.
