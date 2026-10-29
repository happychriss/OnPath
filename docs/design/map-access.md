# Map server access without registration (design note, not built)

Status: **idea for later – decide before the public launch** (owner discussion). Nothing here is built.
Related: `docs/design/map-hosting.md` (Worker, R2, Cloud Run), Settings → Privacy (what leaves the phone).

## 1. Today

- The app sends one shared app key in the header `X-OnPath-Key` (+ `X-OnPath-App` = app version) on every `/v1/`
  call (`cells/CellApi.kt`). The key is compiled in from `onpath.mapKey.test` / `onpath.mapKey.production`
  (never committed); release APKs currently carry the **test** key.
- The Cloudflare Worker (`tools/cloud/worker/src/api.js`) compares it in constant time with the secret `APP_KEYS`
  (comma list, so keys can rotate: old,new). Unknown key → 401. Only `/v1/health` is open.
- Rate limit 30 requests/min per IP for build requests and status (files are not counted); build caps per hour/day.
- No user account, no device id, no signature.

**Limits of this:** the key is readable from the public APK. It keeps random bots out, not someone who wants to
misuse it. The real protection is server-side: rate limit, build caps, and that every area is built once and then
shared from cache.

## 2. What actually costs money

- **Downloads of built areas: almost nothing** (Cloudflare R2 has no egress fees; one built area serves everyone).
- **Building new areas** (Cloud Run) and **storage** cost money.
- So limits should target *new builds* and *bulk scraping*, not normal downloads.

## 3. Ways to recognise a phone without registration

| Way | How | For | Against |
|---|---|---|---|
| A. Android ID | `Settings.Secure.ANDROID_ID`: random, per app signing key and user, no permission, survives reinstall (Android 8+), changes only on factory reset. App sends a hash. | Simple, no Google Play Services, F-Droid OK; reinstall does not reset a quota | Pseudonymous: the server can count per phone (no name, no email) |
| B. Random id at first start | App generates and stores a UUID | Not tied to the phone | Reinstall = new quota |
| C. Supporter code | A donation gives a code (e.g. `ONP-7K3F-…`), entered or opened as a link; server counts per code | No account or email; works across phones; fits voluntary support | Codes can be shared – limit phones per code |
| D. Anonymous credit tokens (Privacy Pass style, blind signatures) | Server issues signed vouchers, app spends one per area; server cannot link vouchers to a phone | Most private | Complex; overkill for now |
| E. Google Play Integrity | Google attests a genuine app | Strong against bots | Needs Google Play Services – against project principles and F-Droid |

## 4. Suggested model (for the decision)

1. **Normal use free, generous limit per phone (A):** e.g. N *new* areas per month (order of 50 areas ≈ 500 MB).
   Re-downloading areas that already exist counts little or nothing. Enough for hiking and touring; stops scraping.
2. **No region lock.** Limiting *where* ("one free area") is the paid-region model and conflicts with "maps are never
   sold" (requirements §2). Limit the amount, not the place.
3. **Supporters (C):** a code raises the limit and marks the phone as supporter. The payment provider never sees
   which areas are loaded; the map server never sees who paid.
4. **Keep:** IP rate limit, build caps, spending alarms; a separate production key with rotation.
5. **Self-hosted servers** set their own rules (concept in the backlog "Later").

**Privacy:** the server would see a hashed app id + area numbers, never a position. Settings → Privacy states it
("an anonymous app id to count new map areas per month").

## 5. Open questions for the owner

- Limit size (areas per month) and what happens at the limit (wait until next month, queue slowly, ask for support?).
- Supporter code: which donation platform; one code per person or per phone; how many phones per code.
- When: with the production map server / before Play and F-Droid.
