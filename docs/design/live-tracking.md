# Live tracking – my tour as a link for the family ([tracking], 2026-11-19)

Owner 2026-11-19: „Ich schicke meiner Frau eine Signal- oder WhatsApp-Message mit einem Link. Mit dem Link kommt sie auf
eine Webseite, wo sie meinen Weg tracken kann: ob ich noch unterwegs bin, richtig auf der Spur, wie es mir geht – oder
zumindest meinen letzten Ort, falls mir etwas passiert.“ He calls it the last feature that matters to him.

Status: **design with a build plan (§14), nothing built.** No product code on `main` before the weekend release
(2026-11-20) is out. Decisions: §12. Numbers marked *estimate* are not measured yet (§10 says how they get measured).

## 1. In one picture

```
 phone (tour is recording)                  plan.onpath.earth                    her phone / any browser
 ┌───────────────────────┐   locked data   ┌──────────────────┐   locked data   ┌────────────────────────┐
 │ RecordingService      │ ──────────────▶ │ live mailbox     │ ──────────────▶ │ viewer page  /live/    │
 │  writes points to DB  │  every ~2 min   │ (Worker + D1)    │  asks ~every 2 min│  opens with the key    │
 │ LiveShare reads them  │  1 request      │ cannot read it   │                  │  from the link         │
 └───────────────────────┘                 └──────────────────┘                  └────────────────────────┘
        the link (with the key after "#") goes by Signal / WhatsApp – never through our server
```

It is the plan sync's idea again (`web-sync.md`): a small mailbox that stores locked data it cannot read. New here: one
side only writes, the other only reads, and the box lives for one tour.

## 2. What the sharer sees (app)

Sketches only – before anything is built the owner gets real pictures (≤ 3 variants) and says yes to one.

**Start sharing.** While a tour records, Navigate's menu has **„Live teilen“**. A tap opens Android's share sheet at
once with one text:

    Verfolge meine Tour: https://plan.onpath.earth/live/#t=AQEIDxYd…

He picks Signal or WhatsApp and the person – done. No dialog before, no account, no setup. (Android share sheet =
`Intent.ACTION_SEND`, text/plain; no Google service involved.) The box on the server is created in the background; if
there is no network right then, the link still works – it shows „noch keine Daten“ until the phone gets through.

**While sharing.** One small sign on Navigate beside the GPS / recording indicator:

| Sign | Meaning |
| --- | --- |
| „Standort“ with a green dot | the last upload went through (age < 12 min: uploads come every 2 min moving, every 10 min at rest – plus slack; a share from before a killed app is grey until its first upload) |
| „Standort“ with a grey dot + „wartet auf Netz“ | no network; the way is kept and goes up later by itself |
| nothing | not sharing |

(Owner 2026-11-20: no „Live“ as a word – the entry is „Standort teilen“ / „Share location“, the sign „● Standort“ /
„● Location“, inside the turn card – proposal C; a tour without a turn card gets a pill.) The tour notification gets
„· Standort“ in its line (not built). A tap on the sign: **„Link nochmal teilen“** (same link, a second
person) and **„Teilen beenden“**.

**End.** „Teilen beenden“ deletes everything on the server at once; the link then says „Diese Tour wird nicht mehr
geteilt“. Ending the tour ends the sharing too: the viewer sees „Tour beendet 16:42“ with the whole way for 12 h,
then the server deletes it. A new tour = a new link (§12).

**Never in the way.** Sharing never asks anything during the tour, never shows an error box. Upload trouble is the grey
dot and a line in the Problems log – nothing else.

## 3. What the viewer sees (web page)

`https://plan.onpath.earth/live/` – a page of its own beside the planner, made for a phone screen (she opens it from
the chat), works on a desktop too. No app, no login. German / English by the browser's language.

```
┌──────────────────────────────────────┐
│ Christians Tour · Wandern            │
│ ● Unterwegs · zuletzt vor 2 Min.     │   ← the one line that matters
├──────────────────────────────────────┤
│                                      │
│      our map (same look as the app)  │
│      ─── planned route (if any)      │
│      ━━━ the way so far              │
│        ◉ last place                  │
│                                      │
├──────────────────────────────────────┤
│ 12,4 km · 3:10 h · ↑ 640 m           │
│ noch 6,2 km · Ankunft ca. 16:40      │   ← only when he follows a plan
│ Handy-Akku 43 %                      │
│ Letzter Ort 14:02 · 46.6890, 11.1400 │   ← tap: copy / open in a map app
└──────────────────────────────────────┘
```

The status line, in this order of priority (calm words – a dead zone in the mountains is normal and must not frighten):

| State | Line |
| --- | --- |
| moving, fresh | „● Unterwegs · zuletzt vor 2 Min.“ (green) |
| paused / standing > 5 min | „● Pause seit 12 Min.“ |
| away from the planned route (the app's own off-route alarm) | „● Abseits der geplanten Route (150 m)“ (amber) |
| no upload for > 10 min | „● Kein Kontakt seit 25 Min. – ohne Netz normal. Letzter Ort 14:02“ (grey, amber after 60 min) |
| tour ended | „Tour beendet 16:42“ (+ „am Ziel“ when the plan's end was reached) |
| sharing stopped / expired | „Diese Tour wird nicht mehr geteilt.“ (nothing else, no map) |
| link broken / key wrong | „Dieser Link funktioniert nicht.“ |

- **„Wie es mir geht“** in version 1 = what the phone knows by itself: moving or pausing, on the route or not, how far,
  phone battery, when last heard. A deliberate sign from him („Alles gut 👍“, a short text) is a later step (§11).
- **The last place** is always on the page with its time and coordinates as text – what one reads to the mountain rescue.
  A tap copies it; „In Karten-App öffnen“ is a `geo:` link.
- **After a gap the whole way appears**, not a straight jump: the phone uploads everything it recorded meanwhile.
- A small fixed line at the bottom: „Kein Notruf-Dienst – im Notfall 112.“
- The page asks the server **only when something new can be there**: the phone's head says when its next upload is due
  (2 min moving, 10 min standing), the page asks shortly after that; at once when the tab comes back; nothing while
  hidden; slower and slower while there is no contact; never again after the end (§5.1).
- Age („vor 2 Min.“) is computed from the **server's** clock (the answer carries the server's time and the time of the
  last upload), so a wrong clock on either phone does not matter.

## 4. Privacy

**End-to-end, the key is in the link** – the same building blocks as plan sync (`core/…/sync/SyncCrypto.kt`: HKDF,
AES-256-GCM, proven by test vectors and byte-equal to `javax.crypto`).

| Who | Sees |
| --- | --- |
| The server (Cloudflare, or anyone reading its database) | a random box number, locked blobs and their size, when uploads came, the requests' IP in Cloudflare's own logs (the Worker stores none; limits use the daily salted hash as elsewhere). So: *that* someone is on a tour and for how long – never where, who, or the route. |
| Someone with the link | everything the page shows, until sharing ends. A link can be forwarded – whoever has it, sees. |
| Someone with the link, **writing** | cannot. The link holds the read key only; uploads need a second token that never leaves the phone (§6). A viewer cannot fake a position. |
| The messenger (Signal / WhatsApp) | the link as message text, inside its own encryption. Link previews fetch only the address before „#“ – the key is never sent. The page has a neutral title („OnPath – Live-Tour“). |

- The part after „#“ never reaches any server (browser rule). The page takes it out of the address bar after reading.
- **Stop = gone:** „Teilen beenden“ deletes the box; the link in the chat history stays but is dead.
- **Nothing lives long:** a box is deleted by the server 12 h after the tour ended and in any
  case 48 h after the last upload (a phone that died – the last place stays visible for two days, then goes).
- No account, no name on the server. The name on the page („Christians Tour“) is inside the locked data; it is the
  phone's owner name field for sharing (asked once at the first share, changeable; default empty → „Live-Tour“).
- Not kept on the server after the end; the recorded tour itself stays only on the phone as today.
- Privacy text: one new paragraph (proposal §13) – goes by pull request (CLAUDE.md: privacy texts).

## 5. What goes over the network

One box per shared tour. Three kinds of items, each one sealed (AES-256-GCM, 96-bit random nonce, the box id and item
name in the additional data so the server cannot move a blob elsewhere):

| Item | Content | Size | Written |
| --- | --- | --- | --- |
| `h` head | version, state (recording / paused / ended / arrived), last fix (lat, lon, ele, time, accuracy), GPS lost, off-route metres, distance, ascent, moving time, to go + arrival (when following), battery %, activity, display name, upload counter | ~80 bytes | every upload (replaced) |
| `r` route | the followed line, thinned (≤ 2000 points), delta-coded like `TrackCode` | ≤ 12 KB | at start, again after a re-route or a new plan |
| `t<n>` track chunk | the recorded points since the last chunk: segment marks, lat / lon / ele / time as deltas | ~7 bytes a point; ≤ 16 KB a chunk | appended, never changed |

A walking hour is about 1,000 kept points (`PointFilter`) ≈ 7 KB; a ten-hour day ≈ 70 KB up in total – *estimate*.

**One request per upload:** `POST …/push` carries the new chunk(s) and the head together. One radio wake-up, not two.

**When the phone uploads** (`LivePolicy`, pure rules, unit-tested like `PointFilter`):

| Situation | Upload |
| --- | --- |
| moving | every **2 min** |
| paused, standing still, or battery saver / below 15 % | every **10 min** (head only – „still here, battery 38 %“) |
| start, pause, resume, off-route alarm, arrived, end | at once (at most one event upload per 30 s) |
| upload failed / no network | nothing is lost: next try after 30 s, doubling to 10 min; at once when Android reports the network back (`ConnectivityManager` callback, no Play Services) |
| server says busy (429 / 5xx) | wait as told (`retry-after`), like plan sync |

Why 2 minutes: every upload wakes the mobile radio for some seconds. *Estimate*: 2 min ≈ 0.5 % battery an hour on top of
the recording, 1 min about double, 5 min under 0.3 %. For „is he on his way and where“ two minutes old is fresh; events
go at once anyway. The number is one constant – measured on the Pixel before release (§10), changed if the cost is off.

### 5.1 Request budget – the server is asked as rarely as possible (owner's concern 2026-11-20)

Cloudflare's free plan gives the whole account 100,000 Worker requests a day (`map-hosting.md`) – shared with the map
server and plan sync. So live tracking counts every request:

| Who | Rule | Requests |
| --- | --- | --- |
| Phone | one `push` per upload (blocks + head together) | 30 an hour moving, 6 standing |
| Viewer page | no fixed tick. The head carries `nextS` (seconds to the phone's next planned upload); the page asks `nextS` + 10 s after the last upload it saw. Nothing new → again after 1, 2, 5, then every 10 min („kein Kontakt“). Tab hidden → nothing. Ended / stopped → never again | ≤ 30 an hour while watched and moving, ~6 an hour in a dead zone |
| Both | 429 / 5xx → wait as told, at least 1 min; no retry storms | – |

A ten-hour tour with the page open the whole day: about 300 + 300 = **600 requests** (0.6 % of a day's budget); looked at
now and then, about 350. With a 30-s tick the page alone would have cost 1,200 – hence the rule. D1: a push writes ~4
rows (≈ 1,200 a tour, limit 100,000 a day), a question reads 2–5 rows (limit 5 million).

If many people use it later, three ways down, in this order: the phone's moving interval 2 → 3 min (one constant);
the head as a small file in R2 that the page reads without the Worker (reads there do not count as Worker requests);
the paid Workers plan (10 million requests a month). None is needed for version 1.

## 6. Link, keys, server

**Link:** `https://plan.onpath.earth/live/#t=<code>`. The app's App Link filter takes only the path `/` of that host
(plan links), so `/live/` opens in the browser even on a phone with OnPath installed (checked in the manifest).

```
code = Base64url, 44 characters:  [0] version = 1 | [1..32] view secret (32 random bytes, a new one per tour)

HKDF-SHA256(view secret, salt "OnPath live v1", info = label):
  "box id"      16 bytes   the box's name on the server
  "read token"  32 bytes   Authorization for GET
  "content key" 32 bytes   AES-256-GCM key – never leaves the two devices

write token = 32 random bytes made on the phone, NOT derived from the view secret, never in the link
```

**Server:** a new file `web/host/src/live.js` in the planner's Worker, routes `/live/v1/…`, two tables in the existing D1
database (migration `0004_live.sql`: `live_box` – id, read hash, write hash, rev, created, last write, ended, deleted;
`live_item` – box, name, rev, blob). Same host as the page → no CORS. All answers JSON, `no-store`.

| Call | Who | Answer |
| --- | --- | --- |
| `POST /live/v1/<box>` + both token hashes | phone (write token) | 201 created; 200 same tokens; 403 other; 410 ended |
| `POST /live/v1/<box>/push` body = items `{name, blob}` (+ `ended: true` at the end) | phone | 200 `{rev}`; 413 too big; 507 box full; 410 deleted |
| `GET /live/v1/<box>?since=N` | viewer (read token) | 200 `{rev, now, lastWrite, ended, items:[{name, rev, blob}]}` – only what changed since N; 404 not there (yet); 410 stopped |
| `DELETE /live/v1/<box>` | phone | 200 – everything deleted, 410 from then on |

- The server stamps `lastWrite` itself – the page's „vor 2 Min.“ needs no phone clock.
- Limits (safety net, far above a real tour): chunk 16 KiB, box 2 MiB / 2,000 items, 60 pushes an hour per box;
  per IP: 120 requests a minute, 10 new boxes a day (the sync's counter with the daily salted hash).
- Clean-up by the existing daily cron, made hourly for this table: ended boxes after the keep time, any box 48 h after
  its last write.
- Cost: an upload is ~3 row writes; a ten-hour tour ≈ 900 – nothing against D1's free 100,000 a day. The viewer's
  question is one small read (§5.1).
- A down server never touches the tour: uploads fail quietly and retry (§7).

## 7. Robust first – the recording must never suffer

The rule today is „plan sync never runs during a recording“ – because an import changes tours. Live sharing is the one
deliberate exception, and it earns it by being **read-only towards the tour**:

1. **It only reads.** `LiveShare` reads points from `TourDb` (a read cursor „uploaded up to point n of tour x“) and the
   `RecState` flow. It writes nothing the recording uses – no table, no file, no flag of the service.
2. **The track database is the queue.** No second buffer that could fill or get lost: after three hours without network
   the cursor is three hours behind, and the upload catches up in chunks (≤ 16 KB each, a few per round).
3. **Its own lane.** One background coroutine / thread, never the main thread, never the service's point-writing path;
   no lock shared with it. Network calls with short timeouts (10 s connect, 15 s read), one at a time.
4. **Every failure is caught** and goes to `Problems` (`Problems.warn("live", …)`, deduped) – an upload error, a broken
   state file, an odd server answer. Three failures in a row of the same kind = the grey dot, still no dialog.
5. **Killed and continued:** the share's state (`files/live/share.json`: view secret, write token, tour id, cursor,
   chunk number – written atomically, excluded from Android backup like `files/sync/`) survives a process death.
   „Continue tour“ continues the sharing with the same link; a tour saved instead ends it (best effort `ended`, else
   the 48 h rule).
6. **No new wake lock, no new permission.** It runs only while the recording service runs (which holds the process and
   has `INTERNET` / `ACCESS_NETWORK_STATE` already). No alarm, no WorkManager, no Play Services.
7. **A switch that really stops it:** „Teilen beenden“ cancels the lane; from then on zero network.
8. **Tests first** (JVM, with a fake box and a fake clock): no network for hours then back; flaky network (every second
   call fails mid-way – no point twice, none missing); process death between upload and cursor write (a chunk sent
   twice is the same item name → harmless); server busy; box deleted from outside; and one that proves the recording's
   write path never waits for the uploader (the uploader blocked forever – points still land).

Platform facts to **prove on the Pixel before release**, not assume: network works from the foreground service with the
screen off in the pocket for hours (expected: yes – a foreground service is exempt from Doze's network block, and a
moving phone does not doze deeply); Data Saver and Battery Saver; the measured battery cost of 2-min uploads on a 3 h walk.

## 8. Where the code goes (fits the rebuild)

| Where | What |
| --- | --- |
| `:core` `live/` (pure Kotlin, also compiled by the web engine) | `LiveCode` (link code + keys), `LiveItem` (head / route / chunk formats, frozen test bytes), `LiveChunker` (points → chunks from a cursor), `LivePolicy` (when to upload), `LiveUploader` (the state machine: cursor, retry, back-off) behind two ports – `LiveBox` (the four calls) and `TrackReader` (points after n). Crypto = the existing `sync/SyncCrypto` (`Aead`). |
| `:state` `ui/live/LiveShareSlice.kt` | what Navigate shows: not sharing / live / waiting, the menu's actions; port to start / stop. Web-ready rules do not apply (phone only). |
| `:app` (Android edge only) | `HttpLiveBox` (`HttpURLConnection`, like `HttpMailbox`), the network-back callback, battery level, the share intent, the wiring into `RecordingService` – as a part beside it, so phase 4's service split moves it without change. |
| `web/host` | `src/live.js`, `migrations/0004_live.sql`, `test/live.test.js` (Node's SQLite as fake D1, as for sync) |
| `web/app` | a second small entry `live/index.html` + `src/live/*.ts` (MapLibre, our map areas as the planner loads them, the status lines). Decoding = the shared Kotlin through the engine – no hand mirror. If the planner's full engine (≈ 0.5 MB gzip, ~1.5 s on a phone) is too heavy for this page, a slim engine build with only `live/` + crypto; decided by measuring. |
| Tests | `LiveFormatTest` (frozen bytes, tamper refused, a viewer key cannot write), `LiveUploaderTest` (§7.8), `live.test.js`, `web/app/test/live-smoke.mjs` (a fake phone in Node walks a track through a real `live.js`; the page shows way, states, gap, end, stop) |

Requirements: „Live tracking / sharing position with others“ moves from *out of scope* to in scope with new IDs
F-LIVE-01… (after the owner's answers). Not a core feature at first; it becomes one (C20) once the owner relies on it.

## 9. Risks

| Risk | What we do |
| --- | --- |
| It costs battery on a long day | 2-min batching, 10 min when standing / low battery, one request per upload; measured before release; the constant can go up |
| She worries at „kein Kontakt“ in a normal dead zone | calm wording, the last place and time always shown, grey not red; battery % explains a dead phone |
| It is taken for a rescue service | said on the page and at the first share: no alarm, nobody is notified, she has to look |
| The link gets around | read-only, one tour, short life, „Teilen beenden“; later a permanent link only as a deliberate choice |
| The phone maker cuts background network | the setup guide's battery exemption already exists; the viewer simply sees „kein Kontakt“; the tour is untouched |
| The server is down or over its limit | uploads retry quietly; the page says „Server nicht erreichbar – gleich nochmal“ |
| Someone uses the box as free storage | size / rate / new-box limits, 48 h life, blobs are opaque but small |
| Sharing code hurts the recording | §7 – read-only, own lane, tests that prove it |
| The clocks differ | ages from the server's clock; track times are GPS / UTC as today |

## 10. Build order (after the release; each step green on its own)

1. `:core` `live/` formats + keys with frozen bytes and tests. Announce the shared code to [laptop].
2. `web/host` `live.js` + migration + Node tests. No app or page yet.
3. Viewer page with the smoke test (fake phone). Owner looks at real pictures of the page here.
4. `LiveUploader` + `LivePolicy` with the §7 tests; `HttpLiveBox`.
5. App UI: sketches as real pictures → the owner's yes → slice + screens, texts DE / EN.
6. Pixel: a 3 h walk with sharing on – battery beside a walk without, screen-off network, a tunnel / airplane-mode gap.
7. Privacy text (pull request), requirements IDs, help page line; then into a release.

`web/` has one writer at a time – steps 2–3 are agreed with [laptop] in the backlog before they start.

## 11. Small first version – and what waits

**In version 1:** share a running tour by link (Navigate menu → share sheet); one link per tour; way so far, planned
route, last place with age and coordinates, the status lines of §3, distance / time / ascent / to go, phone battery;
stop sharing; ends with the tour; DE / EN.

**Later, each its own small step:** a sign from him („Alles gut 👍“ / a short text) · a permanent link for the family ·
sharing switched on by itself at every Start · „zuletzt angesehen“ for the sharer · a notice to the viewer when he
arrives or contact is lost for long · height profile on the page · multi-day tours as one link · the viewer inside the
OnPath app.

## 12. Decisions

| Question | Decision | Who |
| --- | --- | --- |
| One link per tour, or a permanent family link? | **Per tour**; the permanent link later | owner, chat 2026-11-20 |
| How long stays the finished tour visible? | **12 h** after the end, then deleted | owner, chat 2026-11-20 |
| Where does „Live teilen“ sit? | **In the running tour's menu** only | owner, chat 2026-11-20 |
| „Wie es mir geht“ in version 1? | **Automatic signs only**; „Alles gut 👍“ is the first later step | owner, chat 2026-11-20 |
| Show the phone's battery level to the viewer? | **Yes** – it explains a silent phone | [tracking]'s recommendation, night 19→20 Nov (`docs/team/items/2026-11-20-night-answers.md`) – the owner may overrule |
| The name on the page? | **Asked once at the first share** (may stay empty → „Live-Tour“), changeable in Settings | [tracking]'s recommendation, same night – the owner may overrule |

| Where „Live teilen“ really sits (Navigate has no menu) | **First entry with a symbol in the ⚙ Settings opened from Navigate** | owner, chat 2026-11-20 |
| The sign that sharing is on | **„● Live“ inside the turn card at the top** (proposal C); a tour without a route: a small pill above the status card | owner, chat 2026-11-20 |
| The word (not „Live“) | **„Standort teilen“ / „Share location“**, sign „● Standort“ / „● Location“ (his pick of five proposals) | owner, chat 2026-11-20 |
| May the share UI go into the app? | **„Ja, einbauen“** – after a review of the branch | owner, chat 2026-11-20 |

Was the owner's before anything was built on screen: the real pictures of the app's share sign / menu and of the viewer
page (sketch before build).

## 13. Privacy text (proposal)

> **Live-Tour teilen (nur wenn du es einschaltest).** Teilst du eine Tour, schickt dein Handy während der Tour deinen
> Weg verschlüsselt an unseren Server (Cloudflare, EU). Der Schlüssel steckt nur in dem Link, den du selbst verschickst –
> der Server kann weder deinen Ort noch deine Route lesen und kennt deinen Namen nicht. Er speichert eine zufällige
> Nummer, die verschlossenen Daten und wann sie kamen. Wer den Link hat, sieht deine Tour, bis du das Teilen beendest.
> „Teilen beenden“ löscht alles sofort; sonst löscht der Server die Daten 12 Stunden nach Tourende, spätestens 48 Stunden
> nach dem letzten Lebenszeichen.

## 14. Build plan – pieces, ports, tests

Names are proposals; each piece is one small green commit series. Pieces 1–3 need no phone; nothing is visible in the
app until piece 6. Start: after the release of 2026-11-20 is out.

### 14.1 `:core` `com.onpath.app.live` (pure Kotlin; compiled by the web engine)

```kotlin
/** The link code and what is derived from it (§6). */
class LiveKeys(val boxId: String, val readToken: String, val contentKey: ByteArray)
object LiveCode {
    const val PREFIX = "t="                                   // https://plan.onpath.earth/live/#t=<code>
    fun make(secret: ByteArray): String                        // 32 random bytes from the platform → 44 chars
    fun parse(text: String): ByteArray?                        // a whole link or the bare code; null = not ours
    fun keys(secret: ByteArray): LiveKeys                      // HKDF, salt "OnPath live v1"
    fun link(code: String): String
}

enum class LiveState { RECORDING, PAUSED, ENDED, ARRIVED }
data class LiveHead(
    val state: LiveState, val fix: LiveFix?,                   // lat, lon, ele, timeMs, accuracyM
    val gpsLost: Boolean, val offRouteM: Int?,                 // null = on the route / nothing followed
    val distanceM: Int, val ascentM: Int, val movingS: Int, val startedMs: Long,
    val toGoM: Int?, val arrivalMs: Long?,                     // only when following a plan
    val batteryPct: Int?, val activity: String, val name: String, val counter: Int,
    val nextS: Int,                                            // seconds to the next planned upload – the page's pacing (§5.1)
)
data class LivePoint(val seg: Int, val lat: Double, val lon: Double, val eleM: Int?, val timeMs: Long)

object LiveItem {                                              // plain bytes: version | kind | fields (tag, length, bytes) –
    fun head(h: LiveHead): ByteArray;  fun readHead(b: ByteArray): LiveHead?      // unknown tags skipped, like SyncItem
    fun route(line: List<GeoPoint>): ByteArray;  fun readRoute(b: ByteArray): List<GeoPoint>?
    fun chunk(points: List<LivePoint>): ByteArray;  fun readChunk(b: ByteArray): List<LivePoint>?
    fun seal(keys: LiveKeys, name: String, plain: ByteArray, nonce: ByteArray, aead: Aead): ByteArray
    fun open(keys: LiveKeys, name: String, blob: ByteArray, aead: Aead): ByteArray?   // AAD = version | box id | name
}
```

- Item names: `h`, `r`, `t0000`, `t0001`, … (the chunk number is part of the sealed name – a server cannot reorder).
- `LiveChunker.next(points, max = 16 KiB)`: whole points only; a chunk never mixes two uploads, so a re-sent chunk is
  the same points under the same name.
- `LivePolicy.next(now, lastUploadMs, moving, paused, lowBattery, event, failures, retryAfterMs): Long` – when the next
  upload is due (§5's table as one pure function; the numbers are its constants).

**The uploader and its ports** (no thread, no clock, no Android inside – `step()` is called by the lane):

```kotlin
interface LiveBox {                                            // the calls of §6; never throws – every failure is a Reply
    fun claim(readHash: String, writeHash: String): LiveReply
    fun push(items: List<Pair<String, ByteArray>>, ended: Boolean): LiveReply
    fun delete(): LiveReply
}
data class LiveReply(val code: Int, val retryAfterS: Int? = null)      // 0 = no network / timeout

interface LiveTrack {                                          // read-only view of the recording
    fun pointsAfter(tourId: Long, seq: Int, max: Int): List<Pair<Int, LivePoint>>   // (seq, point), in order
    fun head(tourId: Long): LiveHead?                          // from RecState + battery; null = no such tour running
    fun route(tourId: Long): Pair<Int, List<GeoPoint>>?        // (version, line) – version changes on re-route / new plan
}
interface LiveStore { fun read(): LiveShare?; fun write(s: LiveShare?) }   // files/live/share.json, atomic
data class LiveShare(val secret: ByteArray, val writeToken: String, val tourId: Long, val claimed: Boolean,
                     val cursorSeq: Int, val nextChunk: Int, val routeVersion: Int, val ending: Boolean)

class LiveUploader(box: (LiveShare) -> LiveBox, track: LiveTrack, store: LiveStore, random: (Int) -> ByteArray, aead: Aead) {
    fun start(tourId: Long, name: String): String              // makes the share, stores it, returns the link – no network
    fun step(nowMs: Long, event: Boolean): LiveStatus          // at most one claim / push; moves the cursor only after a 200
    fun end()                                                  // marks ending; the next good step sends the final head with ended
    fun stop(): LiveStatus                                     // DELETE (best effort, retried by step), then forgets the share
    val status: LiveStatus                                     // OFF, LIVE(lastOkMs), WAITING(sinceMs), STOPPED_BY_SERVER
}
```

Order inside one `step`: claim if not claimed → route if its version changed → up to 3 chunks from the cursor → head.
The cursor and chunk number are stored **after** the 200; a death in between re-sends the same names (harmless). 410 /
403 = the box is gone → the share is forgotten, status `STOPPED_BY_SERVER`, one `Problems.warn`.

**Built (piece 1, 2026-11-20) – where the code differs from the sketch above, the code is right:** `LiveState` is
RECORDING / PAUSED / ENDED, „arrived“ is a flag on the head (the recording goes on after the plan's end); the head also
carries `stillS` (how long the pause / standstill lasts – a duration, so no clock is compared); keys are `LiveKeys(secret)`
/ `LiveKeys.of(link)`; a plain chunk is ≤ 12,000 bytes and ≤ 4,000 points so its Base64 text stays under the server's
16 KiB; elevation in a chunk is all-or-none; `LivePolicy.nextUpload` returns the due time and knows „the network is
back“ (10 s after the last try), a server's `retry-after` is kept (1–10 min); **„kein Kontakt“ starts at the larger of
10 min and twice the head's `nextS`** (a resting phone uploads every 10 min – it must not flicker into „no contact“);
a dead zone costs the page 9 requests in the first hour, then 6 an hour. Tests: `LiveFormatTest`, `LivePolicyTest`,
`LiveStatusLineTest` (32). Proven for the web so far: the Kotlin compile of `web/engine`; the Wasm build comes with
`WebLive.kt` (piece 3).

**Built (piece 4, 2026-11-20, `LiveUploader.kt`) – again the code is right where it differs:** only `step` ever makes a
request – `end()` / `stop()` just mark the share (stored), the next steps send the final head / the DELETE until it
lands; the store holds one text line (`LiveShare.toText` / `parse`); the share also keeps name, counter and the last
head that reached the server (the „ended“ head can be built after a kill); **the head carries `chunks`** – how many
track chunks count – and the page draws only those (a push stored whose answer was lost can leave a stale higher
chunk); 404 counts as gone like 410 / 403; dropping the plan sends one empty route; a full box (413 / 507) is a plain
failure with back-off. `LiveUploaderTest` (16, incl. 300 seeded random sequences with lost answers and kills: no point
twice, none missing). **For the Android edge:** the uploader has no locks – every call on the one live lane, the UI
reads a snapshot the lane publishes; the lane stays alive while `pending` (final head / DELETE still to send);
`LiveTrack.pointsAfter` needs a stable increasing point number per tour; `LiveNeverBlocksRecordingTest` belongs to the
edge (it needs the real lane and reader).

### 14.2 `:state` `ui/live/LiveShareSlice.kt`

State for Navigate: `sign: LiveSign` (None / Live / Waiting), `menu: List<LiveAction>` (Share / ShareAgain / Stop),
`askName: Boolean` (first share only). Port `LiveShareHost` (4 members: `start(name): String` = the link, `stop()`,
`status: LiveStatus`, `shareText(text)`) + `LivePrefs` (the name). Tests on the slice harness: the first share asks the
name once; a share while sharing gives the same link; stop clears the sign; `STOPPED_BY_SERVER` clears it with one quiet
line (`Say`, place = Navigate); nothing is offered when no tour records.

### 14.3 `:app` (Android edge)

| File | What |
| --- | --- |
| `live/HttpLiveBox.kt` | `HttpURLConnection` like `HttpMailbox`; 10 s / 15 s timeouts; every exception → `LiveReply(0)` + `Problems.warn("live", …)` |
| `live/LiveLane.kt` | one single-thread executor owned by the recording service's lifetime: calls `step` when `LivePolicy` says, on the service's events, and on `ConnectivityManager.NetworkCallback.onAvailable`; cancelled in `onDestroy` |
| `live/DbLiveTrack.kt` | `LiveTrack` over a **read-only** `TourDb` query and the service's `RecState`; battery from the sticky `ACTION_BATTERY_CHANGED` |
| `live/LiveFiles.kt` | `LiveStore`; `files/live/` added to the backup exclusions |
| wiring | `RecordingService` gets three lines (create the lane, forward start / pause / end events, destroy); `RecordingClient` gets `liveStart / liveStop / liveStatus` – the app's only way to it, as for everything else of the service. Phase 4's service split moves the lane with the recording engine unchanged. |

**Built (piece 5, 2026-11-20) – differs from the table above, the code is right:** `RecordingService` is **not
touched** (its file may only shrink – `FileSizeRuleTest`) and does not know the sharing. `LiveEdge.attach` in
`OnPathApp.onCreate` makes one lane per process; it follows the service's published `RecState` (tour id, pause, alarm,
arrived → an event step) and learns a shared tour's end from the tour's row; it outlives the service so the final
„ended“ head or the DELETE can still go out. The app's way in is `LiveShareClient` (`:core`: the `live` snapshot flow,
`liveStart(name)`, `liveStop()`, `liveSeen()`; the link comes in `LiveSnapshot.link`) – not `RecordingClient` (pinned by
the planner's parity). `TourDb` got two read-only queries (`livePointsAfter`, `liveLastPoint`). **Nothing calls
`liveStart` yet – dormant until the screens are agreed.** For every user it adds: one caught line at process start,
one idle thread, a four-field compare per state change. Tests: `LiveLaneTest` (8, incl. a hanging server never holds
up the recorder – on sqlite-jdbc in WAL mode with the app's exact SQL), `LiveEdgeTest` (9).
**Open, for the Pixel walk:** Android's own SQLite under a long catch-up read (≤ 12,000 rows) while the recorder
writes; network with the screen off; the lane's timer in device sleep (no wake lock of its own); **the final „ended“
upload can be late** when „End tour“ comes < 30 s after the last upload (the service's wake lock is gone by then – the
page shows „kein Kontakt“ until the phone next wakes; to fix before release: send the final head at once, ignoring the
30-s floor); „Save tour“ after an interruption ends the share only at the next state change or process start.

### 14.4 Server (`web/host`)

`migrations/0004_live.sql`:

```sql
CREATE TABLE live_box (id TEXT PRIMARY KEY, read_hash TEXT NOT NULL, write_hash TEXT NOT NULL, rev INTEGER NOT NULL DEFAULT 0,
  created INTEGER NOT NULL, last_write INTEGER NOT NULL, ended INTEGER, deleted INTEGER, bytes INTEGER NOT NULL DEFAULT 0,
  pushes_hour INTEGER NOT NULL DEFAULT 0, pushes_from INTEGER NOT NULL DEFAULT 0);
CREATE TABLE live_item (box TEXT NOT NULL, name TEXT NOT NULL, rev INTEGER NOT NULL, blob TEXT NOT NULL, PRIMARY KEY (box, name));
CREATE INDEX live_item_rev ON live_item (box, rev);
```

`src/live.js` (`handleLive(request, env)`, routed from `index.js` at `/live/v1/`; `/live/` itself is a static page):
the calls of §6. A push is one D1 batch: `UPDATE live_box SET rev = rev + 1, last_write = ?…` then one upsert per item
with the new rev. Token check = compare of SHA-256 hex, as in `sync.js`. `GET` answers `now`, `lastWrite`, `ended` from
the box row. Cron: an hourly entry for
`DELETE … WHERE (ended IS NOT NULL AND ended < now − 12 h) OR last_write < now − 48 h OR deleted < now − 48 h`
(a deleted box keeps its row that long so the link answers 410, not 404). `wrangler.toml`: `run_worker_first` +
`/live/v1/*`, rate-limit binding `LIVE_RATE` (120 / min / IP), the hourly cron – a config change, sent to the owner as
a pull request together with the privacy text.

**Built (piece 2, 2026-11-20, branch `feat/live-server` – on main after [laptop]'s go):** the wire format is the comment
at the top of `live.js`. Where it differs from the above: the server hashes the bearer token itself, the claim's body
carries only the read token's hash; no `bytes` column (summed per push, cannot drift); limits count Base64 characters
(16,384 an item), at most 8 items a push; **`GET` is paged** (≤ 500 items, `more` – the page loops); an expired or
stopped box answers 410 for 48 h, then 404 (the page reads 404 as „noch keine Daten / Link unbekannt“); a claim whose
read hash equals the writer's is refused; the per-IP limiter uses the daily salted hash. Tests: `live.test.js` (15).
**For the owner's `wrangler.toml` pull request** (without it the route is not reachable in production):
`run_worker_first` + `"/live/v1/*"`; rate-limit binding `LIVE_RATE` (120 / 60 s, a new namespace id); an hourly cron
beside the daily one (until then a finished tour can stay up to ~36 h instead of 12); optional var
`LIVE_NEW_PER_IP_DAY`. Proven only on the fake D1 (Node's SQLite) – no deploy, no real D1 yet.

### 14.5 Viewer page (`web/app`)

- `live/index.html` – a second Vite entry; `src/live/main.ts` (boot: read `#t=`, strip it from the address, keep the
  code in memory only – a reload needs the link again; to be shown to the owner with the pictures, the alternative is
  keeping it in the tab's session storage), `src/live/box.ts` (the `GET` loop paced by the head's `nextS`, §5.1 –
  the wait is one tested Kotlin function `LivePolicy.viewerNext`; at once on `visibilitychange` / `online`, stops for
  good on 410 or after the end), `src/live/view.ts` (status line, numbers,
  last place with copy / `geo:`), `src/live/map.ts` (MapLibre with the planner's map-area loader and style; the way, the
  route, the dot; follows the dot until the user moves the map, then a „Zur Position“ button).
- Engine: `WebLive.kt` in `web/engine` – `liveKeys(code)`, `liveOpen(code, name, blob)` → JSON of head / route / chunk
  (the shared `LiveItem`), `liveStatus(head, now, lastWrite, ended)` → the status line's key + numbers, so the rule
  „which line wins“ is one tested Kotlin function (`LiveStatusLine` in `:core`), not TypeScript.
- Texts: the web strings module (DE / EN), keys `live_*`.
- Not indexed (`noindex`), neutral title, no analytics, no third-party requests (fonts and map from our host).

### 14.6 Tests (each names what it guards)

| Test | Guards |
| --- | --- |
| `LiveFormatTest` (`:core`) | frozen bytes of code, keys, head, route, chunk (a format change cannot slip in); any flipped byte refused; a blob opened under another name or box refused; an older reader skips unknown tags |
| `LivePolicyTest` | §5's table; never more often than the floor; back-off and `retry-after`; the viewer's pacing (§5.1): a watched ten-hour tour stays under 350 page requests, a dead zone under 8 an hour |
| `LiveStatusLineTest` | §3's table and its priority (no contact beats moving; ended beats all) |
| `LiveUploaderTest` (fake box, fake track, fake clock) | no network for hours → everything arrives in order afterwards; every 2nd call fails → no point twice, none missing (random seeded sequences); death between push and cursor write → same names again; 410 → stops, share forgotten; end → final head with `ended`; stop → delete retried until it lands |
| `LiveNeverBlocksRecordingTest` | the box hangs forever → the track is still only read, the lane holds no lock the point writer takes (the writer finishes while `step` hangs) |
| `LiveShareSliceTest` (`:state`) | §14.2's rules |
| `web/host/test/live.test.js` | claim / push / get / delete, limits (413, 507, 429), the link's read token cannot push or delete, expiry query, 410 after delete, D1 holds nothing readable |
| `web/app/test/live-smoke.mjs` | a fake phone (the shared code in Node) walks a track through the real `live.js`: way and route drawn, each status line, a gap filled later without a straight jump, end, stop → „wird nicht mehr geteilt“, a broken link, phone width |
| Pixel (by hand, once) | screen-off network for 3 h, battery beside a walk without sharing, airplane-mode gap, „Continue tour“ after a kill keeps the link |

### 14.7 Order

1 formats (`:core`) → 2 server → 3 viewer page + smoke (pictures to the owner) → 4 uploader + policy → 5 Android edge →
6 slice + screens (pictures to the owner first) → 7 Pixel walk → 8 privacy text + `wrangler.toml` by pull request,
requirements IDs, help line → release. Pieces 2–3 touch `web/`: agreed with [laptop] in the backlog first (one writer).
