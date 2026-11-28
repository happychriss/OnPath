# App functions against the vision – the captain's verdict (2026-11-28)

Basis: `2026-11-28-function-inventory.md` (252 functions, main 6849a4afc, listed by a role without judging).
Judged with the test sentence of the vision draft: **does it help the person on the way to change their own plan and to
arrive?** – and the order: never lose a tour → offline on the way → clear and calm → powerful. Phone = the way,
browser = preparing. The owner's own wishes are judged like everything else. Nothing here is decided; every row under
"Question" is the owner's call.

## The numbers
- 252 functions. **75 serve the way**, 114 only the preparation, 17 only the time after the tour, 13 are tester tools,
  the rest is shared. The app says "on the way" and is, counted, mostly a preparation tool.
- Origin: almost everything traces to a requirement or an owner word; only 10 rows have none. The growth did not come
  from roles inventing things – it came from wishes, each sensible alone. (The exception was the scenarios, e.g. "Tag 3–4".)
- Size: Plan ~6,700 lines, Navigate + recording ~6,300, Meine Touren ~4,000.

## Fits – no question (about 200 of 252)
Map, position, planning by holding, route and profile, warnings, undo, „Los geht's“, Navigate with turn and status
card, off-route question, recording service and its notifications, interrupted tour, backup and recycle bin, automatic
map corridor, GPX in / out, search, the setup guide, pairing and plan sync with the browser, one-way location sharing
(named in Scope). This is the core and it is what the vision describes.

## Out – no question needed (dead or replaced)
| What | Why |
| --- | --- |
| „Zuhause“ row + „Als Zuhause festlegen“ (switched off since 2026-11-19, code and sync part remain) | owner: „so wichtig ist das Zuhause eigentlich nicht“ – dead code is not kept |
| „Nach Updates suchen“ in ☰ (not wired) | Settings has it |
| Requirements without a function: F-POS-05, F-MAP-07, F-MAP-08, F-PLAN-09, R-10; F-PLAN-04 „drag“ | mark „not planned“ in `requirements.md` (drag contradicts the hold-only decision) – a requirement nobody builds misleads the next role |
| Dev switch „Day table: all columns in one row“ | goes with the multi-day rebuild |

## Question – ranked by how clearly it misses the test and how much hangs on it
| # | Function (size) | Verdict | Others | Recommendation |
| --- | --- | --- | --- | --- |
| 1 | **Rundtouren vorschlagen** – 7 functions, wish sheet ~525 lines + the suggestion engine and its map-data wait | Misses the test. It is discovering, not „your own plan from a destination“; pure preparation; the vision resists „catalogue features“. The largest block that is not about the way. | Komoot: this is its core business (discover). Google Maps: none. | Off the phone. If it stays in the product: in the browser planner (preparing). |
| 2 | **After the tour: „Mehr“ sheet (planned vs needed, clock bar, rates ~305), „Fahrten bearbeiten“ with map + timeline (~182), pace line „schneller / langsamer als geplant“** | The vision says: „After the tour you save and put the phone away.“ The one-screen Rückblick fits (F-NAV-07); the second layer is analysis for enthusiasts. | Komoot / Strava: analysis is their hook. | Keep Rückblick and the ride question (keeps the track honest). „Mehr“ and the ride editor out. |
| 3 | **Meine Touren: five list tools** – tabs, search, sort (5 orders), favourites chip + stars, activity filter (~4,000 lines area) | Clear and calm: five tools for one list. Search and the tabs have no request on record. | Komoot: filter + sort. Google Maps lists: none. | Tabs + sort stay. Search, favourites and activity filter: keep one, not three. |
| 4 | **Settings: 27 rows** – e.g. „Position auf der Route“ (no request found), „Warnung abseits“ + „Warnen nach“ + „Vibrieren abseits“ (3 rows for one thing), hold-time slider, „Vibration beim Planen“ | Each switch is a decision the app passed on to the hiker. | Komoot ~10 navigation settings; Google Maps fewer. | Fix a default and remove: snap switch, hold slider (one fixed time), merge the off-route rows into one. |
| 5 | **Favoriten / „Merken“ ☆ for places** (max 5, „Alle Favoriten“, in backup and browser sync) | Helps choosing a destination (preparation); costs sync both ways. Borderline. | Google Maps: yes (core). Komoot: no. | Keep only if used – the owner's call; otherwise out together with Zuhause. |
| 6 | **„Ganz neu berechnen“ in ☰** | A repair button for the app's own detour state. A hiker cannot know when to press it; „never technical“. | none has it | Out of the menu (developer tools), and fix what makes it necessary. |
| 7 | **Zeitdetails screen** (how the time was calculated, ~172) | For the curious, not for the way; the formulas are in the developer tools already. | Komoot: none | Out of Plan. |
| 8 | **Manual map handling:** Offline-Karten list with per-area delete, „Kartendaten (.zip) importieren“, screen „Offline-Kartendaten fehlen“ with import | The vision: „nobody has to manage regions.“ The import path is from before the automatic loading. | Komoot: region list with delete. | Keep one line „Speicher: x GB · alles löschen“; import into developer tools. |
| 9 | **„Auge“ (hold to hide the route)** – a fourth map control | Own control, rarely needed. | none has it | Out, unless the owner uses it. |
| 10 | **In-app updater** (daily check, box, download) | Needed while builds are sideloaded. | – | Stays until Play is the channel, then out. A date, not a question. |

## Honest about what the vision itself protects although it is big
- **Hold-lock on the tour buttons and hold-only planning** are controls of the app's own – against „platform standard“.
  They stay because rank 1 (never end a tour by accident) beats rank 3. Worth one sentence in the decisions, so nobody
  reads it as a model for new controls.
- **Setup guide (14 steps)** makes the way in longer; it stays because every step prevents a lost tour, and it already
  shows only what is missing.
- **Screen and battery behaviour** (rest view, pocket guard, three settings): serves arriving; the settings count is in #4.

## What follows
If the owner follows the recommendations 1–9, about 35 functions and roughly 3,000–4,000 lines leave the phone, most of
them preparation or analysis. Nothing of the core is touched. Order of work after his answers: dead code first (no
question), then one block per thread, each with the three checks.
