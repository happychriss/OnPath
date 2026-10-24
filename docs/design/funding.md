# Funding without being commercial – phone and browser (draft for the owner, 2026-10-24)

Status: **draft, nothing decided, nothing built.** Builds on `docs/design/map-access.md` (anonymous quota,
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
