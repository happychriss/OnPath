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
| `web/app/src/sync.ts`, `plans.ts` | browser: IndexedDB store with one folder per phone (§14), sync loop (pull by itself, push on Save), My routes, the Phone window (header entry: state dot, pairing, Sync now, Unpair), join dialog |
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

### 5.1 JOIN item and device role (A-017, 2026-10-30) – format fixed before building

Owner model A-017: one *folder* per phone in the browser; a second browser (laptop + desktop, a friend's PC) pairs
by itself and the phone **redirects** it into the phone's one mailbox. Two additions to the item format, both
backward compatible (old readers skip unknown kinds and tags):

```
kind 4 = JOIN        (only ever written by a phone into a browser's fresh mailbox)
  id      = first 16 bytes of SHA-256("OnPath join")            – one JOIN per mailbox, the same on every side
  device  = the phone's device id (8 bytes)
  fields  5 = the phone's own pairing code (ASCII, PairCode text format, the same text as in a #pair= link)
  sealed with the BROWSER's mailbox keys (the code the phone just scanned), like every item in that mailbox

kind 2 = DEVICE hello, new optional field
  6 = role (varint): 1 = phone app, 2 = browser          – missing = unknown (old app / old page)
```

Tag 5 belongs to JOIN and tag 6 to the role, so one tag never means two things (A-017 named "tag 5" for both; the
item decoder reads tags without looking at the kind, hence two numbers). `SyncItem` gets `pairCode: String?` (kind
JOIN) and `role: Int?` (kind DEVICE); `isUserItem` stays plans + tracks only, so JOIN and hellos never become copies.

**Redirect round (phone, already paired with mailbox P, scans the code B of another browser):**
1. Shows the check number of code B → the user taps Pair (no other question).
2. Derives B's keys and token; claims B's mailbox if needed (`POST /sync/v1/<B>/claim`, §8) and `PUT`s the JOIN item
   (field 5 = P's pairing code) into B. Nothing else is written into B.
3. Shows "Paired". The phone stays in P; `sync_unpair_first` goes.

**Browser side (holds code B, waits after showing the QR code):**
1. On its next pull it finds the JOIN item, opens it with B's keys, checks the code is a valid PairCode.
2. Adopts code P (stores it as the open folder's mailbox, folder key = JOIN's `device`), `DELETE`s mailbox B (its
   own transitional one), reads P from the start (`since = 0`). The check number shown stays B's (both screens
   showed it).
3. Writes its own hello into P with role 2. A phone's hello carries role 1; the browser names the folder after the
   hello with role 1 (two phones with the same name: "· gekoppelt 30.10.").

A browser that pairs directly with an unpaired phone (today's flow) gets no JOIN: the phone joins B itself and B is
the phone's mailbox from then on. The folder key is the device id of the hello with role 1.

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
4. 410 / 403 = the pairing was ended → the phone: `forget()` (plans stay, marked to upload again on a new pairing);
   the browser (A-017): the folder closes, nothing is marked to upload. Network error → "Offline – your plans sync
   when the connection is back." (INFO).

Triggers (browser, A-017 §14): **pull** at load, when the tab becomes visible, on `online`, every 60 s while visible,
every 3 s while the pairing dialog waits for the phone; **push** only on Save (the own hello also without).

## 8. Mailbox API (`web/host/src/sync.js`)

Same hostname as the planner (`plan.onpath.earth`) → no CORS. All answers JSON, `cache-control: no-store`.
Header `Authorization: Bearer <token>` on every call.

| Call | Answer |
| --- | --- |
| `POST /sync/v1/<mb>` | 201 `{rev}` claimed (stores SHA-256 of the token); 200 same token; 403 other token; 410 ended |
| `GET /sync/v1/<mb>?since=N` | 200 `{rev, more, items:[{id, rev, blob}]}` – items with rev > N, ≤ 200; `rev` = what this page covers |
| `PUT /sync/v1/<mb>/<id>` body = blob bytes, `x-onpath-base: B` | 200 `{rev}`; 409 `{rev}` when the item is not at B; 413 > 32 KiB; 507 limits |
| `DELETE /sync/v1/<mb>` | 200 – all items deleted, the mailbox answers 410 from then on (cannot be claimed again) |
| `POST` that would create a mailbox, over the daily cap | 429 `{"error": "…", "code": "new-mailbox-cap"}`, `retry-after` = seconds to the next UTC day |
| `appSeen` in every `POST` / `GET` answer | when the **phone app** last used the mailbox (ms, to the minute; `null` = not yet) – see below |
| others | 401 no/odd token, 404 unknown mailbox / path, 429 rate limit (plain `{"error"}`, no `code`), 503 no database bound |

- **Compare-and-set in one D1 batch** (one transaction): `UPDATE mailbox SET rev = rev + 1 … WHERE` the item is
  still at B, then `INSERT … SELECT … WHERE changes() = 1` – two writers on the same item: exactly one wins, the
  other gets 409.
- **Limits:** item 32 KiB, 1000 items and 4 MiB per mailbox (tombstones and copies count), pages of 200;
  **rate limit** 120 requests / min / IP (Workers rate-limit binding `SYNC_RATE`; a round is 2–5 requests).
- **Expiry:** a daily cron (`17 3 * * *`) deletes mailboxes not used for 365 days, and ended pairings after the same
  time. `seen` is written at most once a day per mailbox.
- **Phone last synced (`appSeen`, 2026-10-30):** the browser shows "Paired · phone last 3 days ago". Phone and browser
  share one token, so the host tells them apart by who asks: a request with `x-onpath-client: web` (the planner sends
  it) or a browser user agent (`Mozilla/…`) is the web; anything else is the app (`HttpURLConnection`'s
  `Dalvik/…`). **The app needs no change.** Stored in `mailbox.app_seen` (migration 0003): the minute of the app's
  last `POST` / `GET` / `PUT`, written at most once a minute – no IP, no device id, no extra table. With two phones it
  is the later of them. A browser on an old host gets no `appSeen` and shows only when it synced itself.
- **Schema:** `migrations/0001_sync.sql` (`mailbox`: id, token_hash, rev, created, seen, deleted; `item`: mailbox,
  id, rev, blob). D1 database `onpath-sync-test`, EU jurisdiction.
- **New mailboxes per IP per day:** at most `SYNC_NEW_PER_IP_DAY` (wrangler var, default 5). Only a `POST` that
  creates a mailbox counts – a second device joining an existing pairing (its `POST` answers 200) does not; the
  `GET` 404 → `POST` claim of a not-yet-claimed mailbox does (it creates one). Counted in the map-area quota table
  (`map_quota`, migration 0002): `k = "sync:<SHA-256 of MAP_KEY | day | IP>"`, period = the UTC day – counts only,
  the IP never in plain, the hash changes daily; rows of earlier days are deleted by the daily cron (`purgeQuota`).
  Over the cap: 429 with `code: "new-mailbox-cap"` (above). The browser says "Too many new pairings from this network
  today. Try again tomorrow." (`web_sync_pair_cap`). **App (§11):** map 429 + `code == "new-mailbox-cap"` to the same
  sentence (WARNING, no retry until the next day); a 429 without that code stays "busy, try again in a minute".
- Not built: deleting old tombstones (they are ~60 bytes; the item limit catches abuse), per-mailbox write rate.

## 9. Browser (web/app)

Since 2026-10-30 (owner order A-017) the browser keeps **folders** – one per phone, plus "Without phone" – and sends to
the phone only with **Save** (§14). What the page shows:

- Header: My routes · phone entry · **Save route** · Import GPX · Help (desktop inline, ☰ on phones). **Save** is bold /
  filled while anything in the open phone folder is not on the phone yet, with the quiet "Not saved on the phone yet" /
  "Noch nicht auf dem Handy gespeichert" beside it (hidden below 1180 px, then the tooltip says it); amber "Mailbox
  full – …" instead after a 507. In "Without phone" it saves in this browser only, no hint. A click saves the shown
  route into its folder (name proposal / Save or Save as new, as before) and then sends everything unsaved of the
  folder (`sendAll`); nothing changed on the route → straight to the phone.
- ☰ → **My routes**: heading "Your planned routes · Pixel 8" (or "· Without phone"), Save current route (into the
  folder only – nothing is sent), routes and imported tracks newest first; the phone's new / changed routes **bold**
  with the grey line "New from the phone" / "Changed on the phone" until opened; rename, delete (in place; in a phone
  folder the question names the phone's recycle bin, in Without phone "Delete … from this browser?"). Paired: a quiet
  "Without phone (2)" looks into the routes from before the pairing (and "Back to Pixel 8"); a route opened from there
  shows **Copy to Pixel 8** beside "Saved as …". Closed folders of other phones: "Remove routes of ‹phone› from this
  browser" (quiet, no question).
- Header phone entry → **Phone window** (web-planner.md §13 rows 29, 32): the state (not paired / paired and the phone
  synced within 24 h – green / the phone not for longer – amber, from `appSeen`), "Paired with Pixel 8", How it works,
  privacy; **Pair phone** (always a new code): QR code, steps, check digits, "Waiting for the phone…" → "Paired with
  Pixel 8"; paired: **Sync now** (a pull), **Pair another phone**, **Unpair** (no question); the closed folders'
  "Remove routes of …" lines.
- A pairing link opened in a browser: "Pairing code – usually you scan it with the OnPath app; you can also pair this
  browser instead" → it waits for a phone's hello in that mailbox like a pairing it showed itself. The secret is
  removed from the address bar at once.
- Storage: IndexedDB `onpath` / `kv` – `device`, `pair` (the open folder's code), `pending` (a pairing waiting for the
  phone), `folders` (the directory) and per folder `fach:<key>:ledger`, `:marks`, `:draft` (§14). Blocked storage →
  memory only, with a warning. Web Locks keep two tabs from writing at the same time.
- Test: `web/app/test/sync-smoke.mjs` (67 checks, §14.4).

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
> locked plans and when they last changed, and when the phone app last fetched them (to the minute, so the browser can
> say "phone last synced 3 days ago"); against misuse it counts new pairings per network for one day (your
> internet address only as a code that changes daily, deleted the next day). Unpairing deletes everything there at once; a mailbox nobody uses for 12
> months is deleted automatically. Whoever has your pairing code can see your saved plans – do not share it.

## 13. Open decisions (owner)

1. **Scope of "plans" on the phone:** all saved routes made in Plan mode (recommended) or only those the user marks
   "sync"? Imported GPX routes – sync (as plan points) or not (recommended: not in phase 2; they are tracks, not plans)?
2. **More than one browser / a second phone:** works already (every holder of the code joins the same mailbox);
   show "Show code again" in the app too? Recommended: yes, later.
3. **Tombstone clean-up and item limit** (1000 per mailbox): fine for phase 2; revisit with real numbers.
4. **Production mailbox:** separate D1 database and Access-free host when the planner goes public; the same code.
5. **Rate limit numbers** (120/min/IP) and idle expiry (12 months) – defaults, adjust after the test.

## 14. Folders – one per phone, Save sends to the phone (owner order A-017, 2026-10-30)

Owner: "So wenige Fragen wie möglich." Before, the browser had one list, every change went to the phone at once and a
pairing uploaded everything that was in the browser – a friend's PC mixed its routes with yours.

### 14.1 Model

| Rule | Browser (web/app/src/sync.ts) |
| --- | --- |
| One folder per phone, key = the phone's device id (from its hello, role 1); plus "Without phone" (`none`) | directory `folders` = `{open, list:[{key, name, mailbox, pairedAt, seen}]}`; per folder `fach:<key>:ledger` (the shared `SyncLedger` text), `:marks` (`{id: new/changed}`), `:draft` (the open route: plan code, which saved route, which folder) |
| Pairing opens that phone's folder, only its routes are seen | `openFolder` after the phone answered (below); the open route swaps to that folder's draft (an empty map for a new folder) |
| Unpair closes the folder, no question, no DELETE | the code is forgotten, this browser's hello is written as deleted (one PUT, so the phone stops naming it), Without phone opens with its draft; the routes stay |
| The same phone again → its folder comes back with unsaved changes and marks | the phone (paired) redirects with JOIN to its one mailbox → the folder with that device id; its ledger is read again from `since = 0` (`syncRebase`: same mailbox → everything kept; a new mailbox → only the unsaved changes kept, with base 0, the rest comes back from the phone) |
| Phone → browser by itself | pull triggers §7 |
| Browser → phone only with Save | `round(pair, all)`: without Save only the own hello is pushed (`syncOutboxOf` with one id); Save → `sendAll` pushes the folder's outbox. Rename, delete, GPX import, saving a route only make the ledger dirty |
| Save bold + "Not saved on the phone yet" | `status.pending` = the folder's dirty plans / tracks / tombstones (`syncState.unsaved`) > 0, or the shown route changed (`saveState`) |
| Planned before pairing stays in Without phone, never uploaded | no `forget()` / no "mark all dirty"; **Copy to ‹phone›** copies one route with a new id into the phone folder (unsaved) |
| A friend's PC | Pair phone always makes a new code; another phone opens its folder, the open one closes untouched (no DELETE); the friend's phone redirects (JOIN) or pairs directly later and its folder is back |
| Laptop + desktop | each pairs itself; the phone redirects the second (JOIN); each has its own copy of the folder |
| Marks | an item new or changed by a pull (not changed last by this browser – the phone's own and another browser's saves) → "New from the phone" / "Changed on the phone" until opened; the first fill of a folder (since = 0) is not marked |
| Conflict | `SyncMerge` as before: both stay, the older "… (copy)" – shown "… (Kopie)" in German (display only; a rename typed with "(Kopie)" stores " (copy)" again, like `SyncMerge.storedTitle`) |
| Delete | browser + Save → the phone's recycle bin (app); phone → gone here |
| Imported GPX | kept as a TRACK item (`TrackCode.fit`, the shared code) in the open folder, sent with Save; opened from My routes it shows like an imported file (`syncTrackGpx` → the app's GpxWriter → the import) |
| 410 / 403 (Unpair on the phone) | the folder closes like Unpair, quiet line "The connection was ended on the phone." / "Die Verbindung wurde am Handy getrennt." |
| 507 | amber "Mailbox full – too many saved routes. Delete some on the phone, then save again." beside Save; everything stays unsaved in the folder |
| Two phones with the same name | "Pixel 8 · paired 30 Oct" / "Pixel 8 · verbunden 30. Okt." |

### 14.2 Pairing in the browser

1. **Pair phone** (also "Pair another phone" while paired): a new code B, its mailbox claimed (`POST`), kept as
   `pending`; the open folder stays open meanwhile. The code shown before is deleted (never scanned).
2. Every 3 s (and on every pull) the page reads B: its own hello goes in (role 2, the phone shows "Paired with Chrome on
   Linux"); then
   - a **JOIN** (§5.1, `SyncJoin.secretOf`) → adopt the phone's code P, `DELETE` B, open the folder of the JOIN's
     device, read P from `since = 0`; the check digits stayed B's (both screens showed them);
   - a **hello with role 1** (an unpaired phone scanned B; or, from an app before the role, a hello with no role that
     is not ours) → B is the phone's mailbox; open the folder of that device.
3. The folder's name comes from the phone's hello in its mailbox (role 1).

A store from before the folders moves once (`migrate`): not paired → Without phone; paired → the folder of the newest
other hello (or `mb:<mailbox>` until the phone says hello, then re-keyed).

### 14.3 Engine calls (WebSync.kt, the shared com.onpath.app.sync)

`syncHelloAs` (hello with role), `syncOutboxOf` (only some ids), `syncPeek` (a page's JOIN and hellos with roles),
`syncJoinBlob` (the JOIN a phone writes – for tests and as the reference), `syncHelloId`, `syncRebase`,
`syncSaveTrack`, `syncTrackFit`, `syncTrackGpx`; `syncList` gives `kind`, `track`, `device`; `syncDevices` the device
id; `syncState` `unsaved`. The JOIN / role format is main's shared `SyncItem.pairCode` / `role` and `SyncJoin`
(f94b5050) – the web has no copy of it. JVM test `web/engine/src/test/.../WebSyncJoinTest.kt` pins the bytes of §5.1.

### 14.4 Tests

`web/app/test/sync-smoke.mjs` – desktop A, laptop L and fake phones: the app's shared sync code in the Wasm engine of
an idle page, driven from Node with the real crypto against the host's mailbox (hello role 1, routes, rename, delete,
JOIN redirect, "Unpair?" = DELETE). 67 checks: Without phone → pairing opens the phone's folder, nothing uploaded;
the Phone window (no unpair question, no Show code again, three states); unsaved edits survive a reload, a pull sends
nothing, Save sends, the hint goes; "New from the phone" / "Changed on the phone" until opened; conflict "(copy)";
delete both ways; GPX as TRACK with Save; Copy to Pixel 8; the laptop via JOIN (since 0, transitional mailbox 410); a
friend's phone (two folders, same name + date, the first untouched); Unpair without a question + "Remove routes of …";
the same phone again with its unsaved change; 507 amber + nothing lost; D1 holds nothing readable; Unpair on the
phone → both browsers close the folder with the quiet line; the new-mailbox cap. `web/host/test/sync.test.js`
unchanged.

### 14.5 Open (owner)

- **Phone reinstalled:** a new device id ⇒ a new folder; the old one stays closed, its unsaved changes reachable only
  by removing it. Proposal: the device id into the Android backup (A-017 app point 6), or allow "Copy to ‹phone›" from
  a closed folder too.
- "Copy to ‹phone›" is offered for routes; an imported track from Without phone is not copied (import it again).
