# Context menus: one scheme for order and wording (owner 2026-11-26)

**(owner)** Decided with the owner in his chat on 2026-11-26, answered step by step. Applies to the app's Plan view
(hold) and the web planner (click) alike – the rows come from shared code (`MapGestures.pointMenu`, `PlaceAddMenu`,
`PinMenuItems`, `ui/MapOverlays`, `web/app/src/main.ts` / `multiday.ts` / `search.ts`). **This replaces the owner's
words of 2026-11-15** („Einfügen“ / „Neues Ziel“, „never reworded“ in `PlaceAddMenu`'s KDoc) – update that comment too.
Not part of 0.18.11 unless the captain says so.

## The words
Three words for points, everywhere: **Start · Stopp · Ziel** (EN Start · Stop · Destination). No „Zwischenpunkt“,
no „Zwischenziel“. Every route action reads **„Als …“**.

| Deutsch | English | replaces |
|---|---|---|
| Als Start | Set as start | Hier starten (new place) · Hier starten (stop in the plan) · Hier starten & enden (round trip) |
| Als Stopp | Set as stop | Einfügen (hold menu, place menu, multi-day line) |
| Als Ziel | Set as destination | Als Ziel setzen · Neues Ziel · Hierhin |
| ↳ Start: dein Standort | ↳ Start: your position | the line under „Hierhin“ (Ab deinem Standort …); the blocked reasons (Keine Standortberechtigung …) stay |
| Als Übernachtung | Set as overnight stop | Übernachtung hier |
| Als Hotel | Set as hotel | Hotel für diese Tour |
| Rundtour ab hier | Round trip from here | Rundtour von hier |

Unchanged: Hotel hinterlegen / ändern / entfernen, Übernachtung entfernen, Merken / Gemerkt, In Google Maps öffnen,
Löschen, Nadel entfernen, the grey lines „Löscht den Punkt davor / die N Punkte davor“ and „Gleiche Rundtour, startet hier“.
EN fix: the plural's one-form says „Removes the point before it“ (today „Removes the 1 point before it“).

## Groups, always in this order, a thin line between them
1. **Route** – what the point becomes. Fixed order Start → Stopp → Ziel (no more „cheaper first“); the likelier row is
   only shown by the blue tint.
2. **Ort** – Merken · In Google Maps öffnen.
3. **Entfernen** – always last; red only when something leaves the plan (Löschen, Übernachtung entfernen).

A title only where the menu belongs to something (a place, a route point, a night). **No title** on the empty map
(„Neuer Plan“ goes) and on the hold / multi-day-line choice.

## Every case
New point:

| Where | Plan | Title | ① Route | ② Ort | ③ Entfernen |
|---|---|---|---|---|---|
| Empty map | empty | – | Als Start · Als Ziel ↳ Start: dein Standort · Rundtour ab hier¹ | – | – |
| Empty map | 1 point | no menu (set as goal at once, as today) | | | |
| Empty map | 2+, doubtful | – | Als Stopp · Als Ziel | – | – |
| POI / search pin | empty | name · kind · elevation | Als Start · Als Ziel ↳ Start: dein Standort · Rundtour ab hier¹ | Merken · Google | Nadel entfernen (pin only, not red) |
| POI / search pin | 1 point | same | Als Ziel | Merken · Google | Nadel entfernen |
| POI / search pin | 2+ | same | Als Stopp and/or Als Ziel · Als Hotel² | Merken · Google | Nadel entfernen |
| App, tour running | empty | same | Als Ziel ↳ Start: dein Standort | Merken · Google | Nadel entfernen |

Point in the plan:

| Point | Title | ① Route | ② Ort | ③ Entfernen |
|---|---|---|---|---|
| Start | ① name / Start | – | Merken³ · Google³ | Löschen |
| Stopp | ② name / Stopp 2 | Als Übernachtung² · Als Start ↳ Löscht die N Punkte davor | Merken³ · Google³ | Löschen |
| Ziel | 🏁 name / Ziel | – | Merken³ · Google³ | Löschen |
| Round-trip start | ↺ name / Start | – | Merken³ · Google³ | Löschen |
| Round-trip point | number / name | Als Start ↳ Gleiche Rundtour, startet hier | Merken³ · Google³ | Löschen |

Start and Ziel get no route row (owner: „Umkehren braucht nicht ins Menü“ – it is in the plan sheet).

Multi-day tour (planner):

| What | Title | ① Route | ③ Entfernen |
|---|---|---|---|
| Click on the line | – | Als Übernachtung · Als Stopp | – |
| Grey night | Nacht 2 · Ende Tag 2 von 4 | Als Übernachtung · Hotel hinterlegen | – („Ein Tag weniger“ goes, owner 2026-11-20) |
| Blue moon | Nacht 2 | Hotel hinterlegen | Übernachtung entfernen |
| Green bed | hotel name | Hotel ändern | Hotel entfernen (bed icon, not the moon) · Übernachtung entfernen |

¹ tour suggestions on · ² multi-day tour · ³ named place / POI only; **Merken on a route point is new**.

## Outside the menus (same words)
- Errors „Setze Zwischenpunkte …“ / „Ein Zwischenpunkt“ → „Setze Stopps …“ / „Ein Stopp“ (EN stops / a stop).
- Planner hint „… für Ziel und Zwischenpunkte“ → „… für Ziel und Stopps“.
- Planner flyover button „Stopp“ (`web_fly_stop`) → „Beenden“ / „End“ (Stopp is the point now).

## Bugs found on the way
- Planner: „Hotel für diese Tour“ (key `HOTEL`) is dropped from the search pin's menu – `PinMenuItems.of` does not know
  the row, `search.ts` showMenu keeps only the rows it returns. The POI menu shows it.
- Planner: „Hotel entfernen“ uses the moon icon.
- Planner shows „Ab deinem Standort“ under „Hierhin“ always, the app only the blocked reason – solved by the new line.
