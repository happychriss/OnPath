# Funding without being commercial – phone and browser (draft for the owner, 2026-10-24)

Status: **draft, nothing built.** Asked the owner the base model 2026-10-24: no preference given → working assumption **A** (free + voluntary supporter code), to be confirmed. Builds on `docs/design/map-access.md` (anonymous quota,
supporter code) and the costs in `docs/design/map-hosting.md` §3. New: the web planner (`web-planner.md`) must fit
in. Owner's order: settle this first, then how a plan gets from the browser to the phone.

## 1. Fixed rules (requirements §1, §2, §8 – not up for change here)

No account, no ads, no tracking, no paid map regions ("maps are never sold"), open source, no location leaves the
device. Self-hosted servers stay free and set their own rules.

## 2. What costs money – and what the browser adds

| | Cost | Phone today | Browser adds |
|---|---|---|---|
| **Building a new area** (Cloud Run) | ≈ 0.01 € per area after ~300 free/month | yes | yes, the same areas |
| Storing areas (R2) | ≈ 0.015 €/GB-month, 1 000 areas ≈ 10 GB | yes | no (shared) |
| Downloading areas (R2) | **free** (no egress fee) | yes | yes – many small range reads (map tiles while panning) |
| Requests through the Worker | 100 000/day free, then ≈ 5 €/month for 10 M | per area | **many** – browser reads tiles piece by piece |
| Routing | 0 (on the device) | phone | 0 – the browser computes (spike result) |

Conclusion: the money goes into **new area builds**; viewing and routing are almost free. The browser mainly adds
requests – solved by serving finished areas to the web straight from a public read-only R2 address with CORS
(no Worker in between, no key), and keeping the Worker only for "please build this area".

## 3. The browser cannot keep a secret

Everything in a web page is public, so the web can never hold an app key. The phone's key is readable from the APK
too (map-access §1). So access control must not rest on a secret – only on **limits for new builds**:

| Who | How recognised (no account) | Free limit (proposal) |
|---|---|---|
| Phone | hashed install id (map-access §3 A/B) | generous, e.g. 50 new areas / month |
| Browser | random id in the browser + IP rate limit; a privacy-friendly bot check (e.g. Cloudflare Turnstile) only when a **new** area is requested | smaller, e.g. 10 new areas / month |
| Supporter (phone **and** browser) | supporter code (bearer code, works on any device, max N devices) | high, e.g. 500 / month |

Viewing finished areas and planning: free and unlimited for everyone.

## 4. Models

| | A. Free + voluntary supporter code (proposal) | B. App costs once in Google Play, rest free | C. Tip jar only, no unlock at all |
|---|---|---|---|
| Idea | All features free. A voluntary contribution gives a code that only raises the **amount** of new areas and shows "supporter". Same code on phone and web. | Play price e.g. 3–5 € once; F-Droid, web and self-hosted free | Donation link, nothing changes in the app |
| Privacy | Payment platform knows who paid but not what is loaded; map server knows the code, not who paid (only a hash of the code is stored, no email) | Google knows the purchase (as for any Play app) | best |
| Non-commercial feel | high ("pay what you want", costs shown openly) | medium (a price tag) | highest |
| Abuse protection | quotas + code | quotas | quotas only |
| Work | code issuing (small page / webhook), entry in app + web | Play setup only | almost none |

**Transparency (all models):** a public page with monthly costs vs. contributions; a surplus goes to
OpenStreetMap / BRouter (said up front). That keeps it clearly "covering costs", not a business.

## 5. Points to check before launch (not legal / tax advice)

- **Google Play payment rules:** a code bought outside Play that unlocks something in a Play app may have to go
  through Play Billing; plain donation links in Play apps are also restricted. Common way: the Play build sells the
  supporter level via Play Billing (or shows no link), the F-Droid build and the web use the external platform.
  Check the current Play policy when the Play listing is set up.
- **Platform:** Liberapay (non-profit, recurring, open-source friendly), Open Collective (costs public), GitHub
  Sponsors, Ko-fi. Choice affects fees and how the code is delivered.
- **Germany:** contributions to a private person are not tax-deductible "Spenden" and may count as income – ask a
  tax adviser before money flows.

## 6. After this: plan from browser to phone

Separate step (owner's order). Leading idea from web-planner §1: the plan's points in a link / QR code that OnPath
opens – nothing stored on a server, no account, works with every model above.

## 7. Owner's three wishes (2026-10-24) → proposal "fair core + OnPath Plus, open split"

Owner: (1) OnPath should not be taken for granted – a price exists; part of every payment goes to an aid
organisation, part to the owner (fixed costs, own time). (2) Message in the background: AI lets one person build
what took others years – so software gets cheaper and the price must be fair. (3) Hikers care about a simple, robust
app without Komoot's drawbacks and a price model they find fair; without one there is no market. Include the web
planner and future multi-day planning.

**Shape:**

| | Free, forever (the core never gets worse) | OnPath Plus (proposal ~1 €/month, 10 €/year or ~30 € once) |
|---|---|---|
| Maps | every place in the world, offline; N new areas / month | many more new areas, pre-load a whole trip region at once |
| Plan / navigate / record | full: planning, routing per activity, navigation, re-routing, recording, GPX | same |
| Web planner | plan a day, send to the phone by link / QR | save plans in the browser, sync web ⇄ phone (end-to-end encrypted, below) |
| Multi-day planning (future) | – (or 1 trip at a time) | stages by time / huts / overnight places, day split, per-day profile and times |
| Thanks | – | supporter mark, early access to new features |

Rules kept: no paid places (Plus raises **amount and comfort**, never unlocks a region), no ads, no account,
nothing sold about users. Plus pays for what costs work or servers: builds, storage, sync, multi-day planning.

**Open split, shown on a public page (the "cash book"):** every month: income, fees, server costs, the
aid-organisation share with the receipt, the owner's share. Example for 10 €/year: ~1 € fees, ~0.5 € servers,
**3 € to the aid organisation**, ~5.5 € owner. The split is fixed and public before launch.

**No account, still one purchase for phone and web:** buying gives an **OnPath Pass** (a code, e.g. `ONP-7K3F-…`,
max N devices). In Google Play bought through Play Billing (required for digital features there); on the web and for
F-Droid through a "merchant of record" shop (Paddle / Lemon Squeezy type: they handle EU VAT and invoices for every
country – a private seller otherwise must do VAT for each EU country). The server stores only a hash of the code
and a counter, never a name or email; the shop knows the payer but never what is planned or loaded.

**Sync without account (Plus):** the phone makes a secret key; pairing with the browser by QR code; plans are
encrypted on the device and stored on the server only as unreadable blobs. Lose the key = lose the copy, never
someone else reading it. Same "everything stays yours" story as today.

**Not taken for granted, without nagging:** the price is visible in the app and on the web from day one; after a
few finished tours a quiet card "OnPath is free. If you like it: Plus – 3 € of 10 € go to <aid organisation>."
Never during navigation, never a blocking pop-up.

**The AI message:** on the About page / website, plainly: one person + AI, open source, costs and split public –
that is why it can be fair. Not on the hiker's main path.

**Open for the owner (one at a time):** price level and form (year / once / both); which aid organisation (e.g.
mountain rescue, a humanitarian aid organisation, or OSM); the split; what exactly is free vs Plus; shop platform;
tax adviser before the first euro (Kleingewerbe, VAT via the shop).

## 8. Price models – which exist, how they are received (owner asked 2026-10-24, before choosing a charity)

| Model | Who does it (2025/26) | Reception | Fit for OnPath |
|---|---|---|---|
| 1. Free, donations / grants only | Organic Maps, many F-Droid apps | well liked; income small – case studies ~0.3 % of users donate; a yearly in-app ask (Thunderbird) doubled donations | fits principles; won't carry the owner's time |
| 2. Free + "pay what you want" with a suggested price | small open-source apps | liked; a bit more than 1 when the price is shown | good start, weak "not taken for granted" |
| 3. Free core + one-time purchase (lifetime) | OsmAnd Maps+ (one-time, ~40–70 $), Komoot until 2025 (regions 3.99 £, world 29.99 £) | **best received by outdoor users** ("pay once, it's mine"); Komoot's world pack was the classic | very good; but no recurring money for servers – keep server-heavy extras (sync) apart |
| 4. Free core + subscription (month / year) | Komoot Premium (~60 €/year, since 2025 needed for new users to sync to devices), AllTrails+ (~36 $/year), OsmAnd Pro (~40 $/year) | common, but strong backlash when free things move behind it (Komoot 2025) | fair only for things with ongoing cost (sync, many new areas) |
| 5. Trial, then one-time | classic paid apps | OK, but a barrier where free apps exist (Organic Maps) | only if the core were paid – it isn't |
| 6. Trial, then subscription | AllTrails (7-day trial), most fitness apps | accepted, but auto-renew traps annoy | not needed: the free core is the "trial" |
| 7. Paid app up front | rare today | low | no |
| 8. **Hybrid: subscription OR one-time** | OsmAnd (Maps+ once or yearly) | well received – the user chooses | **proposal** |

Proposal: free core (model 1 as base) + **OnPath Plus as one-time or yearly, user's choice** (model 8); a
14–30-day free try of Plus features instead of a trial of the whole app. Charity share and public cash book apply
to every payment. Prices: check the shops before deciding (sources disagree).
