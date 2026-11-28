# App functions against the vision – the captain's verdict (2026-11-28)

Basis: `2026-11-28-function-inventory.md` (252 functions, main 6849a4afc, listed by a role without judging).
Judged with the test sentence of the vision draft: **does it help the person on the way to change their own plan and to
arrive?** – and the order: never lose a tour → offline on the way → clear and calm → powerful. Phone = the way,
browser = preparing. The owner's own wishes are judged like everything else. Nothing here is decided; every row under
"Question" is the owner's call.

## The numbers
- 252 functions. **75 serve the way**, 114 only the preparation, 17 only the time after the tour, 13 are tester tools,
  the rest is shared. **Corrected 08:49:** this count splits way / preparation; the owner's split is where you are / planning ahead – simple planning on the phone is wanted, so „mostly a preparation tool“ was the wrong reading. The count stands, the conclusion does not.
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

## Corrected verdict (2026-11-28 ~10:00, after three corrections by the owner)
The first version below this line's history judged by „way vs preparation“, by counting controls and by comparing with
other apps. The owner corrected all three: the split is **where you are / planning ahead** (Rundtouren stay), depth
**behind a deliberate tap** does not complicate use („Mehr“ sheet, Zeitdetails, map list stay), and every element is
judged by **the situation it serves** (the five tools in Meine Touren each have one – the activity filter most of all).
Rules now in `docs/team/captains-guide.md` §4. Judged again with them:

| What | Verdict |
| --- | --- |
| Rundtouren vorschlagen | Fits (spontaneous start from where you stand). Open: how many taps from „I stand here“ to a proposed loop; the entry is only in the hold menus. |
| „Mehr“ sheet, „Fahrten bearbeiten“, Zeitdetails, Offline-Karten list | Fit – behind a deliberate tap, facts only. |
| Meine Touren: tabs, search, sort, favourites, activity filter | Fit – each has its situation. Tabs and search get a requirement ID (no request was on record). |
| „Auge“ | Fits – the route line covers the path on the map. |
| Favourites for places | Fits (F-PLAN-14, the usual start or car park). |
| Settings → „Position auf der Route“ (switch) | **Question:** no request on record and no situation found for switching it off. |
| ☰ → „Ganz neu berechnen“ | **Question to the owner for the situation** (picture B of 2026-11-25) before any judgement. |
| In-app updater | Stays until Play is the channel – a date, not a question. |
| Off-route: three settings rows for one subject | Fits; one row with a sub-page would be calmer – a layout idea for the next settings pass, not a removal. |

**Result:** apart from the dead code above, the app holds against the vision once the vision says what the owner
means. The finding of this exercise is about the vision and the captain's method, not about the app.

## Honest about what the vision itself protects although it is big
- **Hold-lock on the tour buttons and hold-only planning** are controls of the app's own – against „platform standard“.
  They stay because rank 1 (never end a tour by accident) beats rank 3. Worth one sentence in the decisions, so nobody
  reads it as a model for new controls.
- **Setup guide (14 steps)** makes the way in longer; it stays because every step prevents a lost tour, and it already
  shows only what is missing.
- **Screen and battery behaviour** (rest view, pocket guard, three settings): serves arriving; the settings count is in #4.

## What follows
1. Dead code out (table „Out“), orphan requirements marked – one small order, no question.
2. Requirement IDs for the rows without an origin on record.
3. Two questions to the owner (snap switch, „Ganz neu berechnen“), the tap count for Rundtouren.

## Owner's answers (2026-11-28, cards and messages in the captain's chat)
- **„Ganz neu berechnen“:** removed from the menu (09:45). Plan sync keeps sending points only; phone and planner
  compute the same line (tests); different map dates between browser and phone are accepted, no hint to the user.
- **Settings → „Position auf der Route“:** stays (09:49). Situation: two stretches run close together (serpentines) and
  the snap jumps to the wrong one; switched off, the map shows the raw position.
- **„Zuhause“:** stays in the planner, gone in the app (09:50) – a deliberate exception from parity: at the desk the
  browser often has no location and a tour starts at the front door; on the phone the start is „here“. The app shows a
  synced home as the first favourite.
- **„Runde von hier“ on the start card:** not built (09:34); the loop stays in the hold menus.
- **Learning to hold:** a hint at the tapped spot on empty map and „Als Ziel“ in the place info (09:41).
