# Multi-day tours – concept (owner 2026-11-16/17; browser ⇄ phone split 2026-11-28)

Status: **built in stages 1–4** (planner and phone, 2026-11-17 … 11-27); the phone side is rebuilt to §0 after release
0.18.12 (`docs/team/items/2026-11-28-app-multiday-one-day.md`). §0 is the current concept; §1–§10 hold the model, the
planner flows, the measurements and the history. Before any UI is built: a sketch of the exact picture and his yes
(owner rule). Earlier: „concept complete, nothing built“ (2026-11-17), replaced when the stages were built.

## 0. Current concept – browser plans the trip, the phone guides one day (owner 2026-11-28 07:59 / 08:05 / 08:11, „kristallklar“)

This section is the one current source; the rows keep the IDs of `docs/team/items/2026-11-28-multiday-concept.md` and
are requirements F-PLAN-16 … F-PLAN-22 and F-NAV-08 … F-NAV-12 in `docs/requirements.md`. The sections below are
aligned to it; where they still describe how it was built, they point to the rebuild.

| Row | Requirement |
| --- | --- |
| MD-0 | **Split.** The browser planner edits the whole trip: the days, from the day overview into one day, re-plan it, back to the multi-day view and the next day, the nights. It stays as built. The phone **guides through** the trip: open it in Meine Touren (it unfolds), one day goes into the phone's Plan sheet, changes only to that day, then go – **never the whole plan**. |
| MD-1 | A multi-day tour is **planned in the browser planner**. The phone does not plan one: no whole-trip view in the app's Plan sheet, no day count that follows activity / pace / route choice. |
| MD-2 | On the phone it lies in **Meine Touren as one tour** (badge „N Tage“). A tap shows its days; a tap on a day puts **that day** into the Plan sheet to ride it. „‹ Alle Tage“ leads back to this day list. |
| MD-3 | In the Plan sheet the day can be changed (add / move / delete a point). **While its start and goal stay, it stays part of the trip** – saving writes the day into the trip. |
| MD-4 | **Start or goal changed** → the notice „Start und Ziel geändert – wird als eigene Tour gespeichert“ and a **copy as a standalone tour**; the trip's day stays as it was. |
| MD-5 | A day is **done** when it was navigated from its start **to its goal** (goal within 200 m, as the ticks today). The recording stays tied to its day, also when the plan got a new point on the way. |
| MD-6 | On the phone the **plan and the ridden days belong together**: Meine Touren shows which days are done (✓, „gefahren am …“). Ridden days stay on the phone (the planner plans routes – VISION). |
| MD-7 | Later (backlog): the ridden day's GPX goes back to the browser. Today it does not. |
| MD-8 | A changed day with the same start / goal (MD-3) goes back to the browser by sync like any saved route. |
| MD-9 | A day stopped before its goal is **not done**; the recording stays tied to the day as „angefangen“. |
| MD-10 | A day ridden twice keeps its ✓; the newest finished recording counts, older ones stay in Meine Touren. |
| MD-11 | Opening a day of a multi-day trip loads the maps of **all days of the trip automatically** („sonst übernachte ich auf einer Hütte im Nirvana und mir fehlt der nächste Tag“) – no separate question; the normal map loading window shows the line „Es werden alle Karten der Mehrtagestour geladen, damit du jederzeit offline loslegen kannst. Das kann einen Moment dauern.“ |

Several days in a row („Tag 3–4“, scenario O) on the phone: „Genau ein Tag, mehrere Tage am Stück gibt es nicht (Chriss 2026-11-28)“ – the phone takes exactly one day out of the trip
and rides it (owner 2026-11-28 08:17).

Where today's app differs, and the code to align: `docs/team/items/2026-11-28-app-multiday-one-day.md` (rebuilt after
release 0.18.12).

## 1. What the owner decided

| # | Decision | His words |
| --- | --- | --- |
| 1 | A multi-day tour is **one normal plan** plus a number of days and its overnight stops. Days are parts of it, not stored routes. | "plan mit tagesenden" (2026-11-16) |
| 2 | The route **leads to the hotel**; the detour counts. | "Punkt 1 stimme ich dir zu" |
| 3 | On the phone the user **always picks the day** himself, in the trip's day list in Meine Touren (MD-2). No guessing from the position – a break, a day by train, a skipped day need no rule. | "Einen Tag werden wir immer auswählen" |
| 4 | **Planned in the browser planner first**, then sent to the phone. No multi-day planning on the phone in the first version. | "erstmal nur auf dem Planer im Netz" |
| 5 | The phone loads **all maps of the tour** automatically as soon as a day is opened, in the normal map loading window with its own line – no question (MD-11, §7). | 2026-11-28 08:09 / 08:11: „sonst … fehlt mir der nächste Tag“. Earlier (2026-11-17): ask once at the first opening („Beim ersten Öffnen fragen“), replaced 2026-11-28 |
| 6 | Multi-day tours often start from a **downloaded GPX track**: trim start and end, make a route from it, glue several together. | "GPX zuschneiden macht Sinn … Zusammensetzen finde ich auch gut" (2026-11-17) |
| 7 | Gluing is only gluing – **the joint is nothing special**; the days are set afterwards. | "warum die Nahtstelle ein Tagesende werden soll, verstehe ich nicht" |
| 8 | **The number of days is set once in the browser planner** (and can be changed there); after that the user only adds and removes overnight stops – no "split into days" tool, no "split / join day", no fine-tuning page. The phone never changes the number of days (MD-1). | "im Prinzip muss ich nur ein einziges Mal die Anzahl der Tage festlegen" |
| 9 | **Three states, one colour each, and the shape says it too** (round 3, variant A): very light grey **moon** = night not decided (the app's proposal, orientation only) · blue **moon** = **Übernachtung** (I sleep here) · green **bed** = **Hotel** (booked, name / address stored, the route goes exactly there). | "Grau heißt, ich habe noch nichts. Blau … Übernachtung festgelegt. Grün … Hotel gebucht." · symbol 2026-11-17: „A · Mond → Bett“ |
| 10 | **Saving as for every plan** – "Speichern" as today, unsaved work stays under "Zuletzt". In the planner it saves the whole trip; on the phone it saves the one day into the trip while start and goal stay (MD-3), else a standalone copy (MD-4). | 2026-11-17 late: "das muss konsistent sein zu einem normalen Tourenplan … es ist ja auch eine normale Tour" (replaces his earlier "sofort gespeichert"); phone part 2026-11-28 |
| 11 | The way in must **guide** the user: where do I start, what do I do next (load a GPX track, open or plan a route → set the days). | "das Menü, wie ich in diese Mehrtagesplanung reinkomme" |
| 12 | Words: **GPX-Track** (glossary: "Track" only for GPX), **Übernachtung**, **Hotel**. | "Spur war nicht vereinbart" |
| 13 | Little effort, robust, extendable later. | |
| 14 | **Editing stays as always:** add, move, delete points and re-route like in any plan – in the planner on the whole trip, on the phone on the one picked day (also "Planen" while riding it). A phone change with the same start and goal goes back into the trip (MD-3, MD-8); a changed start or goal makes a standalone copy (MD-4). Night marks travel with their point; greys re-spread (R6). | 2026-11-17: „Ich kann ja immer noch genauso neue Routenpunkte hinzufügen und umrouten, korrekt?“ – yes; phone part 2026-11-28. Earlier: the phone's change went back into the whole tour whatever it was, replaced 2026-11-28 |

Left out of the first version (owner 2026-11-17: „Beide draußen“): rest days / several nights at one place, and day
trips from one base.

**Limits per day stay** (owner 2026-11-17: "Natürlich bleiben die Grenzen pro Tag"): at most time and ascent per day,
set in the days box under the number of days. Two jobs: (1) they propose the number of days ("mindestens 6 Tage"),
the user may still change it; (2) afterwards a day above a limit gets an orange row with the value that is too high.
The grey proposals are spread so that no day is above a limit where that is possible; without limits evenly by
time. The app never adds a day by itself.

## 2. The model (round 2, owner 2026-11-17 – replaces free / fixed / stay)

- A multi-day tour = a plan + **N** (number of days) + **overnight stops** (points of the plan with a mark).
- **Übernachtung (blue):** a point on the route the user chose: "here I end the day". It does not change the route.
- **Hotel (green):** an overnight stop with a hotel – name, and an address or link (Google Maps / Booking). The point
  sits at the hotel; the route leads there and back. Name and place are kept so the phone can show them.
- **Proposals (very light grey):** the days the user has not decided yet. They are **not stored and not plan
  points** – the app works them out every time: N − 1 − (number of overnight stops) of them, spread along the route
  so the open days stay within the limits per day and are otherwise about equal (by time), between start, overnight
  stops and finish. They never change the route.
- That is all of the "floating": grey = worked out, moves when anything changes; blue and green = yours, never moved.
- **Day n** runs from the (n−1)th day end to the nth – a day end being a grey proposal, an Übernachtung or a Hotel.

Rules (each is one undo step, then saved):

| Rule | What happens |
| --- | --- |
| R1 Übernachtung added | N stays. One grey proposal fewer; the others spread again. No grey one left → N + 1 (said in one line). |
| R2 Übernachtung removed | N stays. A grey proposal comes back; spread again. |
| R3 Hotel added to an Übernachtung | Blue → green; the point moves to the hotel, the route leads there. |
| R4 Hotel removed | Green → blue at the place on the route nearest the hotel (the night stays planned, only the hotel is gone). Owner 2026-11-17: „Nacht bleibt, wird blau“. |
| R5 N changed (− / +) | Only grey proposals come or go. "−" with no grey left: not possible ("Erst eine Übernachtung entfernen"). |
| R6 Route changed (another pass, a point moved) | Grey proposals spread again; Übernachtung and Hotel stay where they are. |
| R8 A day above a limit | Its row turns orange with the value that is too high; nothing moves by itself. Fix: + a day, or move / add an Übernachtung. |
| R7 A grey proposal dragged (owner 2026-11-17: „Wird dort blau“) | It becomes a blue Übernachtung where it is dropped – R1 at that place. |

Built in stage 1 (2026-11-17, `routing/PlanDays.kt`, `PlanCode` tags 3–6, `PlanEdits.follow`) – details the concept
did not say, decided while building:
- Marks follow their point **by value** (as pins and names already do in app and planner), one step after any edit.
- R2 on a hotel deletes the hotel point (the detour goes); on an Übernachtung only the mark goes.
- R3: the hotel point takes the hotel's name and is not pinned; R4 puts the Übernachtung where the route turns off to
  the hotel (no out-and-back detour → it stays where it is).
- A dragged Übernachtung keeps its night; a dragged hotel point becomes an Übernachtung.
- No mark on the start or the last point. R1: a tap within 25 m of a plan point marks that point, else a new point.
- With limits: days stay within them and the longest day is as short as possible; a limit that cannot be met → days
  equal by time and flagged orange.
- Text limits: link 500, address 200, description 2000 characters; at most 99 days.
- Until the phone stores days (stage 4, `RouteItems.PHONE_KEEPS_DAYS`), sync compares plans without them, so the phone
  never wipes the browser's nights.

Built in stages 2 + 3 (2026-11-17, session „mehrtage“, planner `web/`) – details decided while building:
- Engine: `web/engine/…/WebDays.kt` (the page's tour text, the plan code with the tour, `daysView`, `daysRule` = the
  rules of `PlanDays`, `hotelText`), `trackCut` / `trackAppend` (`TrackToPlan.cut` / `append`). The page:
  `web/app/src/multiday.ts` (guide, days box, overview, symbols, menus, hotel dialog), `tourtext.ts`; trim handles in
  `gpximport.ts`. Texts: `strings/…/strings_days.xml`.
- The way in: "Mehrtagestour planen" in the plan bar (its word from 1281 px, icon below) and in Plan ▾; "Als
  Mehrtagestour planen" in Speichern ▾ and Plan ▾ (the planner has no ⋯ of a plan any more).
- Undo: the tour is kept beside the engine's point history, one snapshot per step – every tour action is one undo step.
- A plan point with a night is drawn as its blue moon / green bed (no number); its menu is the night menu. Grey
  proposals are their own markers (draggable → blue, R7; a click: "Übernachtung hier", "Hotel hinterlegen").
- A click on the route of a multi-day plan opens "Übernachtung hier" · "Einfügen" instead of adding a point at once.
- Saving: a change of days or nights counts as unsaved (Speichern turns dark), points as before.
- Stats per day as the app writes them ("94 km · ↑ 780 m · 5 h 30 min"), not "hm"; day ends named after the nearest
  place of the planner's place index, else "km 94".
- Trim handles only while the guide is open (a plain GPX import is unchanged). `TrackToPlan`: calls scale with the
  track (1 per 1.5 km, at least 60), a seed point every 25 km, progress in the busy box, Cancel stops the worker.
  `LegCache` keeps 500 legs, bounded by 250 000 line points.
- A pasted link: Google Maps links with a position are taken directly; Booking links name the hotel in the path →
  searched; an address → searched (offline index + Photon). Short links (maps.app.goo.gl) cannot be resolved in the
  browser – their text is searched. `SharedPlaceParser` moved to the pure `search/SharedPlaceText.kt` for this.

## 3. The flows

### Browser planner (where the tour is made)

1. **The way in.** ☰ → "Mehrtagestour planen". A short guide panel with two steps (owner 2026-11-17, round 3: no
   "Selbst planen" – who plans himself saves the route and opens it here):
   **1 · Route** – "GPX-Track laden" (with the line "Anfang und Ende kannst du danach zuschneiden") or "Gespeicherte
   Route öffnen". Once the first route is there, step 1 shows it with ✓ and below it **"Noch etwas anhängen:"** with
   the same two ways – that is where gluing comes, no menu item of its own;
   **2 · Tage** – "Weiter: Tage festlegen" (active once there is a route).
   The same "Als Mehrtagestour planen" sits in the ⋯ menu of any plan on the map, so a long route planned the normal
   way gets there too.
2. **Trim (new).** On a loaded GPX track: two handles, start and end, on the line and in the height profile; the part
   outside is grey. "Route daraus planen" takes only the part between them. The file is not changed.
3. **Glue (new).** A GPX-Track loaded under "Noch etwas anhängen" offers "An die Route anhängen" after trimming: the trimmed track's
   route goes behind the last point. The joint is a normal point. If the track runs the other way (its end is nearer),
   it is turned round. The guide panel stays at step 1 until "Weiter: Tage festlegen".
4. **Days.** One small box, everything under each other: name (proposal "München – Venedig"), limits per day (time,
   ascent), number of days with − / + (proposed from the limits), below it live "≈ 94 km · 970 hm · 5 h 20 min pro
   Tag". "Fertig" → the overview opens with N − 1 grey proposals.
5. **Overview** = the tour from now on: name, totals, N with − / +, the limits, the height profile with the day borders,
   one row per day (km, hm, time, the bed of its end; orange above a limit). Saving as for every plan ("Speichern").
6. **Overnight stops.** Tap the route → "Übernachtung hier". Tap a bed → its menu: blue "Hotel hinterlegen" ·
   "Übernachtung entfernen"; green "Hotel ändern" · "Hotel entfernen" · "Übernachtung entfernen". "Hotel hinterlegen":
   search the hotel, or paste a Google Maps / Booking link or an address. A found place's menu also has "Hotel für
   diese Tour" → the nearest day end is offered.

### Phone (where it is ridden)

1. The tour arrives by sync. Meine Touren shows one row "München – Venedig" with the badge „6 Tage“; a tap opens its
   day list (MD-2) – not a whole-trip overview in the Plan sheet (MD-1). Earlier: it opened to the overview in the
   Plan sheet, replaced 2026-11-28.
2. Maps: §7 (MD-11).
3. **Pick a day** in the day list → that day goes into the Plan sheet as a normal plan; a grey day end becomes its
   destination point. It can be changed there (MD-3 / MD-4); „‹ Alle Tage“ leads back to the day list. The recording
   stays tied to its day; the day is done (✓) only when it was navigated to its goal (MD-5, MD-9, MD-10). Several days
   in a row („Tag 3–4“): „Genau ein Tag, mehrere Tage am Stück gibt es nicht (Chriss 2026-11-28)“.
4. Later stage: share a hotel to OnPath → "Hotel in der Tour" → which night. Syncs back.

Built in stage 4 (2026-11-17, mehrtage) – details decided while building. Marked **[rebuild]**: changed by §0, see
`docs/team/items/2026-11-28-app-multiday-one-day.md`:
- Store: `tours.days` (schema 9, JSON: N, limits, night marks with hotel, description; old rows = none); synced with
  `PHONE_KEEPS_DAYS = true`; the GPX backup keeps them in its OnPath record only (the GPX itself stays a normal route).
- On the phone the night marks are kept **by value** like the pins (`ui/MultiDayFlow`); the days count only while the
  plan still belongs to the saved tour (a cleared plan is a normal plan, Undo brings the tour back).
- The day sheet in Meine Touren (badge „N Tage“, one tap = that day is the plan, ✓ on ridden days) – holds (MD-2, MD-6).
- **[rebuild]** Today a tour can also open as a whole-trip overview in the Plan sheet (day table, profile, „Planung“
  chips, tap / hold rows incl. „Tage 3–4“, "Tag n fahren"), and "‹ Alle Tage" brings the whole tour back as the plan.
  After the rebuild: no whole-trip view in the Plan sheet (MD-1), "‹ Alle Tage" → the day list (MD-2); „Tag 3–4“: „Genau ein Tag, mehrere Tage am Stück gibt es nicht (Chriss 2026-11-28)“.
- **[rebuild]** Today the number of days follows a change of activity / pace / route choice on the phone (with limits).
  After the rebuild: the phone never changes it (MD-1).
- Every change of the day's plan is written back into the whole tour (`domain/plan/DayFollow`); Save (also at the tour
  start and under Zuletzt) stores the whole tour, its line = the stored line with the day's new line spliced in. A
  save that would drop a tour's days (lost day context) saves a copy instead. **[rebuild]** This holds only while the
  day's start and goal stay (MD-3); a changed start or goal → notice + standalone copy, the trip unchanged (MD-4).
- **[rebuild]** Today the day link of a recording (`tour_plan.day`) drops after any edit and every saved recording of
  the day counts as ridden; after the rebuild the link stays through edits in between and ✓ only on arrival at the
  goal (MD-5, MD-9, MD-10).
- Maps: the whole plan never loads its corridor by itself; the question loads the 10 km corridor, all cells
  (`MapCells.tourCells` keeps them through later plans, asked on mobile data they wait for Wi-Fi unless a point / the
  position on tour asks for them). Sizes: the server's where known, else 12.5 MB per area. **[rebuild]** Today the
  question offers *Ganze Tour* / *Nur Tag n* / *Später*; by MD-11 there is no question – opening a day loads all
  days' maps in the normal map loading window.

## 4. Scenarios with the round-2 model (owner 2026-11-17: "betrachte jeden Satz … überlege potenzielle Szenarien")

Every row is solved with R1–R7; none needs another tool.

| # | Situation | What the user does | Result |
| --- | --- | --- | --- |
| A | Start from a downloaded GPX track | Mehrtagestour planen → GPX-Track laden → trim → Route daraus planen → Weiter: Tage festlegen → 6 → Fertig | 5 grey beds, 6 day rows. |
| A2 | Planned the route myself | plan as always → Speichern → Mehrtagestour planen → Gespeicherte Route öffnen (or ⋯ → Als Mehrtagestour planen) | as A from step 2. |
| B | Start from two tracks (München–Brenner, Brenner–Venedig) | as A, then in step 1 „Noch etwas anhängen“ → GPX-Track laden → trim → An die Route anhängen | one route; days set afterwards. |
| C | Start from a route planned normally | ⋯ → Als Mehrtagestour planen | straight to the days box. |
| D | I know where I sleep on night 2, not the hotel yet | tap the route there → Übernachtung hier | blue bed; days 1–2 and 3–6 even out (R1). |
| E | Hotel booked | its blue bed → Hotel hinterlegen → search or paste the link | green bed at the hotel, the route goes there (R3). |
| F | Hotel found before any Übernachtung | search the hotel → Hotel für diese Tour → night 2 (preselected: the nearest) | green straight away (R1 + R3). |
| G | Hotel cancelled, the night stays | green bed → Hotel entfernen | blue at the route (R4). |
| H | Night not needed any more | bed → Übernachtung entfernen | grey proposal back, days even out (R2). |
| I | A day shorter / longer | move the night: remove it and add it elsewhere – or drag a blue bed (green: change the hotel) | the grey ones even out. |
| J | One day more / fewer | − / + at "6 Tage" | grey ones come or go (R5). |
| K | Every night decided and one more stop wanted | tap the route → Übernachtung hier | N + 1, said in one line (R1). |
| L | Another pass on day 4 | edit the route as always | grey ones even out; the user's beds stay (R6). |
| M | A day ends at a grey proposal and I want it 10 km later | drag the grey moon | it becomes blue there (R7). |
| N | A point of the route that is an Übernachtung is deleted | ✕ on that point | = Übernachtung entfernen; with a hotel it asks first. |
| O | On the way: not reached the day's end, or rode on | phone: pick the day again (or the next) in Meine Touren | followed from where I am; the day stopped early is not done, its recording stays „angefangen“ (MD-9). Several days in a row („Tag 3–4“): „Genau ein Tag, mehrere Tage am Stück gibt es nicht (Chriss 2026-11-28)“. |
| O2 | On the way: change the route of today (another path, a detour) | phone: „Planen“ on the day as always | same start and goal → the change goes into the trip and to the browser (MD-3, MD-8); start or goal changed → notice + standalone copy, the trip unchanged (MD-4). Earlier: always back into the whole tour, replaced 2026-11-28. |
| P | On the way: new hotel | later stage: share to OnPath | as E. |
| Q | Older app / phone not updated | – | sees a normal long route; hotels are named points. |
| R | Rest day, two nights in one hotel | – | not in version 1 (later: "Nächte: 2" on a hotel). |

Found while checking:
- **Saving:** as for every plan (owner 2026-11-17 late) – "Speichern", unsaved work under "Zuletzt".
- **Grey days have no place yet:** on the phone the day's end is the worked-out point on the route – enough to follow.
- **Limits:** they stay (R8); a removed Übernachtung or a far hotel can push a day over – the orange row says so.

## 5. Why this fits the architecture

| Piece | Today | Change |
| --- | --- | --- |
| Plan format `routing/PlanCode.kt` (shared with the planner) | points, pinned bits, optional fields by tag; **unknown tags are skipped** by older readers | new optional tags: number of days, overnight mark per point (Übernachtung / Hotel), hotel address or link, the description. No new version, no new sync item kind. |
| Sync `sync/SyncItem.kt` | a plan item carries the plan code | unchanged |
| Index bookkeeping `routing/PlanEdits.kt`, `PlanPoints.kt` | every insert / delete / move / reverse keeps `pinned` and `names` on the right points | the overnight mark is the **third per-point attribute** and must travel the same way – the one place where a bug would be nasty; tests first. Grey proposals are worked out, never stored, so they need no bookkeeping. |
| Days | – | new pure file (shared), e.g. `routing/PlanDays.kt`: plan + N + overnight stops → grey proposals and day ranges; per-day distance / ascent / time from the existing leg results; rules R1–R7. No UI, no Android. |
| Routing `routing/Planner.kt`, `LegCache.kt` | per leg, changed legs only | unchanged. A hotel routes two legs; grey proposals route nothing. |
| Track → plan `routing/TrackToPlan.kt` (shared) | whole track | takes a range (trim); "append" turns the track round when its end is nearer. |
| Following a day | Navigator / recording take a plan | they get the day's part of the plan – from their view a normal plan. `navigation/TourSplit.kt` (edits during a tour) keeps working on that part; an edit is written back into the whole plan by index offset. |
| Phone store `data/TourDb.kt` | `tours.waypoints`, `pinned` | N and the overnight marks beside them (one column / field in the waypoint text) |
| Maps `cells/CellTriggers.kt` | corridor along every saved route, automatic when online (F-OFF-02) | a multi-day plan must not start this silently – §7 |

What stays untouched: recording, the route line, the mailbox server, the map files.

## 6. Limits to measure before building (stage 0)

- A plan code holds at most **500 points** and **16 000 characters**. München–Venedig (~560 km) as a plan from a
  track: how many route points does `TrackToPlan` need (it stops at 60 router calls today)? If it does not fit, the
  limits or the track-to-plan step need work first.
- First route of ~560 km in the browser and on the phone: time, memory. (113 km on the desktop: ~1 s; the Pixel was
  5–8× slower, `docs/design/long-bike-routes.md`.)
- Map size for the whole corridor (bike today: 20 km either side). A narrower corridor for multi-day tours may be
  needed.
- An imported track as a sync item is thinned to 16 KB – fine for looking, another reason the tour travels as a plan.

**Measured (donald, 2026-11-17)** – the official GPX of the cycle route, `Muenchen-Venezia_west.gpx` from
muenchen-venezia.info (17 391 points, 563.7 km: Bad Tölz – Achensee – Innsbruck – Brenner – Pustertal – Cortina –
Belluno – Treviso – Venezia), Bike (`touring.brf`), E10_N45.rd5, desktop JVM `-Xmx192m` (phone-like; nothing ran out of
memory). Harness: `tools/routing/jvm-test/MultiDayTourTest.kt` (its header says how to run it).

| What | Result |
| --- | --- |
| `TrackToPlan`, today (`MAX_CALLS` 60) | **stops at 60 calls**: 36 route points, follows the track **9 %**, 44.5 s. It splits depth-first, so the first ~100 km get points every ~0.8 km and the rest stays legs of up to 157 km – the plan lies a median **6.5 km** off the route (max 65 km) |
| `TrackToPlan`, no call limit | **189 route points, 351 calls**, follows 99.8 %, 62 s (phone ×5–8: **5–8 min**). Most of the time goes into the first huge legs (start → goal 564 km, then the halves): 60 calls 44.5 s, the other 291 only 17 s |
| Plan of those 189 points vs the track | 99.9 % of the track within 40 m, max 46 m. Plan 594 km (track 564 km): ~20 km of the plan leaves the track by > 150 m (short detours where the profile avoids the track's way or the GPX has straight gaps) |
| Plan code, 189 points | **1 015 chars**; + title + 6 hotel names 1 282; the whole 7-day tour (N, limits, 6 hotels with name, address, link, a 263-char description) **2 563 chars**. ~5.5 chars per point → 500 points ≈ 2 900 chars |
| Limits | points 189 / **500**; text 2 563 / 16 000 (sync plan 16 000, blob 24 000) – all fit with room |
| First routing of the 189-point plan, cold | **3.5 s**, 188 legs, longest leg 0.2 s (phone ×5–8: **~20–30 s**) |
| The same plan again, same `LegCache` | 1.8 s – only **64 of 188 legs** from the cache (`LegCache.CAPACITY` 64): every run routes ~120 legs again |
| Hand plan, 10 points (Marienplatz … Piazzale Roma) | 3.8 s cold, longest leg 1.7 s, 527 km; follows the official route only 54 % (34 % > 1 km off) – BRouter's own way, a different tour |
| Map cells along the track (`CellId.alongLine`) | 20 km: **44 cells**, 5 region packages, 7 routing parts 61 MB · 10 km: **27 cells**, 4 packages, 6 parts 53 MB · 5 km: 26 · 1 km: 23 cells, 5 parts 43 MB. `alongLine`'s cap of 200 is far; `CORRIDOR_MAX_CELLS` 30 cuts the 20 km corridor |
| Download estimate (cells 10 MB each: Innsbruck 11.9, Trentino 7–9, Ljubljana 13.6; packages 3.3 MB) | 20 km ≈ **520 MB**, 10 km ≈ **340 MB**, 1 km ≈ 290 MB (range ±30 %) |

Conclusions:
- **The plan fits** – 189 points, 2.6 k characters; no plan-code or sync limit needs to change. What must change is
  `TrackToPlan`: `MAX_CALLS` 60 → scaled with the track (≈ 1 call per 1.5 km, at least 60; 400 for this tour), and
  seed it with a point every ~25 km on the track first so no call routes hundreds of km (on the phone 5–8 min as is;
  better: run it in the browser only, stage 3, with a progress bar).
- `LegCache.CAPACITY` 64 → **500** (= `PlanCode.MAX_POINTS`), else every edit of a multi-day plan routes ~120 legs again.
- The first routing (3.5 s desktop, ~20–30 s phone) is acceptable with the wait bar; on the phone a day is routed
  alone anyway (stage 4).
- Maps: the whole tour is ~0.5 GB with today's 20 km bike corridor, not gigabytes. **Recommendation: 10 km for a
  multi-day tour** (−40 %, ≈ 340 MB; 1 km would save only 50 MB more), and the §7 question loads all cells, not only
  `CORRIDOR_MAX_CELLS` 30. The browser could not be measured here (no browser run in this measurement; the planner runs the
  same `Planner` and `TrackToPlan` compiled to Wasm, so expect the desktop times or slower).

## 7. Maps on the phone – when (owner 2026-11-28 08:11, MD-11: all days, automatically, as soon as a day is opened)

Not silently on arrival: the whole tour is about 0.3–0.5 GB (stage 0: München–Venedig ≈ 340 MB with a 10 km corridor, ≈ 520 MB with 20 km), possibly on mobile data. Multi-day tours use the **10 km corridor** and load **all** its cells (not capped at `CORRIDOR_MAX_CELLS` 30).

- The tour arrives → its row in My routes says "Maps missing" (no dialog out of nowhere).
- As soon as a day of the trip is opened, the maps of **all days** load automatically – on the way a night in a hut
  must not leave the next day without maps (owner: „sonst übernachte ich auf einer Hütte im Nirvana und mir fehlt der
  nächste Tag“). No separate question: the normal map loading window shows it, with the line
  „Es werden alle Karten der Mehrtagestour geladen, damit du jederzeit offline loslegen kannst. Das kann einen Moment dauern.“ Earlier (2026-11-17): a question at the first opening with *Whole tour* / *Only day n* / *Later*, replaced
  2026-11-28.
- Picking a day always checks that day's maps and says so before the start if something is missing – the robust
  net under everything else.

## 8. Build order

Each stage is usable alone and leaves the app releasable.

| Stage | What | Where | Done when |
| --- | --- | --- | --- |
| 0 | Measure §6 with a real München–Venedig GPX. No UI. | tools / tests | numbers on this page; go or change of limits |
| 1 | N + overnight marks in the plan code, `PlanDays` (R1–R7), index bookkeeping. No UI. | shared code | unit tests: round trip old ⇄ new reader, every edit keeps the marks, scenarios D–N of §4 as tests |
| 2 | Planner: the way in, days box, overview, Übernachtung / Hotel menus, saving by itself | `web/` | sketch approved → built → planner smoke |
| 3 | Planner: trim handles on a GPX-Track, "An die Route anhängen" | `web/` + `TrackToPlan` | same |
| 4 | Phone: tour row with days, pick a day, follow it; maps question (§7) – rebuilt to §0 after 0.18.12 | app | E2E: synced 3-day plan → pick day 2 → start → recording saved |
| 5 | Phone: shared hotel → Hotel in der Tour; tour description shown | app | – |

Later, not designed here: several nights / rest days, day trips from one base. ("Done" ticks per day came with stage
4 and are set by MD-5 / MD-9 / MD-10; planning the whole trip on the phone is out by MD-1.)

## 9. Open points for the owner

Answered 2026-11-28 08:17 („Tag 3–4“, several days in a row, scenario O): „Genau ein Tag, mehrere Tage am Stück gibt es nicht (Chriss 2026-11-28)“. Decided 2026-11-17: Hotel entfernen → the night stays blue (R4); a dragged grey moon becomes blue (R7); maps:
ask at the first opening (§7, replaced 2026-11-28 by MD-11); rest days and base-camp tours out of version 1; symbol A (moon → bed). The words
"bed" in §2–§4 mean the day-end symbol: moon for grey / blue, bed for the hotel.
Next: the phone rebuild to §0 after release 0.18.12 (`docs/team/items/2026-11-28-app-multiday-one-day.md`).

## 10. UI rounds

- Round 1 (2026-11-17): https://claude.ai/artifact/TdtKJFYzsmyQKJFTWSuCR1 – owner: way in missing, "Spur" wrong, joint
  no day end, no tabs in the days box, no fine-tuning page, the three bed colours, saving by itself (§1 #7–#12).
- Round 2 (2026-11-17): https://claude.ai/artifact/XUY1sgh15wLP8CLBZFujqc – the round-2 model of §2 and the way in.
  Owner: days box, overview, Übernachtung menus "okay"; way in with two ways only; when does gluing come; symbols?
- Round 3 (2026-11-17): https://claude.ai/artifact/G6py3AKgGsNnaQjT7jLFMk – the way in (two ways, "Noch etwas anhängen"), three
  symbol variants. Owner: "ansonsten haben wir alles" – next is trying it out on a real tour.

Before any build: his yes to the exact picture.
