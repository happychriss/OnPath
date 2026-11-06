# Flow map – every state, every button, and where it leads (2026-11-06)

Owner 2026-11-06: "alle Abläufe durchspielen … ob wir noch ähnliche Sackgassen oder unlogische Sachen haben". Read from
the code of main 7259f41e (AppViewModel, MapScreen, NavigateScreen, PlanPanel, TourBar, TourActionBar, PlanTopBar,
MapOverlays, MapHost, Panels, RouteDialogs, search/PlaceCard). Unsure findings were probed on the emulator
(`FlowMapProbeE2ETest`, kept off main – each probe passes when the suspected behaviour is there; all three passed again on
7259f41e, 2026-11-06 17:20). Data-loss paths are in
`docs/design/save-paths.md` and are only referenced here; the Pixel review of 2026-11-06 (backlog "Pixel review
2026-11-06 – open bugs") likewise.

## Main states

```mermaid
stateDiagram-v2
    [*] --> P0: app start, no tour
    [*] --> NU: app start, unfinished tour
    P0: Plan · empty
    P1: Plan · 1 point
    P2: Plan · 2+ points (route)
    PS: Plan · shown track / place card
    N: Navigate · tour follows a line
    NF: Navigate · free tour (record only)
    TP: Plan during a tour
    TS: Plan during a tour · shown track / place card
    END: End sheet / ride summary / arrival
    SAVE: Save dialog (name / overwrite)
    NU: Navigate · unfinished tour question
    P0 --> P1: hold → Start here
    P0 --> P2: hold → Go here (from my position)
    P1 --> P2: hold
    P2 --> P0: ✕ Neu planen (asks when unsaved)
    P0 --> PS: ☰ My tours → item / search → place
    P2 --> PS: ☰ My tours → item / search → place
    PS --> P2: ✕ / Als Plan übernehmen
    P2 --> N: Los geht's
    PS --> N: Los geht's (track)
    N --> TP: slide → Plan
    NF --> TP: slide → Plan
    TP --> N: ‹ Zur Tour / Android back
    TP --> NF: ✕ Neu planen → Plan leeren
    TP --> TS: ☰ My tours / search
    TS --> N: Tour nachfahren / ‹ / Weiter
    N --> END: unlock → Tour beenden / arrival
    TP --> END: (only in TS) Tour beenden
    END --> N: Weiterfahren / ✕
    END --> P2: Tour speichern / Löschen (the plan stays, 2026-11-06)
    NU --> N: Continue
    NU --> P0: Delete / Save
```

## State table (what is on screen → where each button leads)

Axes: mode (Plan / Navigate locked / unlocked), plan (empty / 1 / 2+ unsaved / saved / saved + changed / track shown),
tour (none / follows a line / free / paused / lead to start / arrived), open layer (sheet step, ☰, dialog, box, My tours,
search, pin card, activity sheet).

| # | State | Buttons and gestures → result (saved / dropped) |
|---|-------|-------------------------------------------------|
| S1 | Plan · empty · no tour | hold → first-point menu (Start here → S2 / Go here → S3, disabled without GPS fix with the reason); tap → "hold to set a point" line; search → S9; ☰ → My tours (S8) / Settings / Pair; activity button → activity dialog; ☰ → "Ohne Plan aufzeichnen" → plain recording (H1, owner 2026-11-06: in ☰, not in the sheet) |
| S2 | Plan · 1 point · no tour | hold → second point (S3); hold the point → point menu (Delete…); Undo; ☰ → "Ohne Plan aufzeichnen" → plain recording, the point stays (H1) |
| S3 | Plan · 2+ points · no tour | green "Los geht's" (sheet foot / rolled-up row) → straight legs? "Navigate anyway?" → N1; ✕ Neu planen → unsaved: "Plan leeren?" (Save · Verwerfen · Abbrechen) else clears (Undo); ⇅ Umkehren (greyed out when backwards is the same, e.g. A → B → A – #40); ↻ Rundtour = a switch (#40: on a round trip filled ✓, a tap takes the way back out: A → B → A → A → B, one Undo step); hold map → smart insert / append as new goal; hold point → point menu; Undo / Redo; ☰ Save route → Save dialog; ☰ My tours → S8 |
| S4 | Plan · shown track (My tours / import) · no tour | ✕ → back to the plan; "Als Plan übernehmen" → plan replaced, no question (save-paths 8); green "Los geht's · <activity>" → follows the track, recording starts (N1) |
| S5 | Plan · place card (search / pin) · no tour | Hierhin / + Hinzufügen / Start / Ziel / Google / ✕ |
| S6 | Plan · during a tour (TP) | band "‹ Zur Tour" (= Android back) → Navigate (pause kept); ✕ Neu planen → "Plan leeren? Deine Tour läuft weiter" → free tour; plan edits → the tour follows the new plan; an edit that leaves **one point** (start of start → goal deleted, Undo / Redo to one point) → plan = position → that point, the tour follows it (like Hierhin; no GPS: guidance ends as for an empty plan; owner 2026-11-06, onpath.private#39 – was: old line incl. the deleted start followed, sheet "Neuer Plan"); the goal deleted → "Route back to the start?" as before; rolled-up row and foot have **no** green button; **no Pause / End here** (only in S7) |
| S7 | Plan · during a tour · shown track or place card (TS) | tour card on top: green "Weiter · <activity>" (resumes a pause, back to the OLD guidance), "Pausieren", red "Tour beenden"; track sheet: "Als Plan übernehmen", green "Tour nachfahren" → follows the track, **old plan stays in Plan** (M1); place card: its own primary button (F1 in save-paths.md) |
| S8 | My tours (overlay) | Geplant / Gemacht / Tracks tabs, search; tap planned → no tour: "Plan ersetzen?" when unsaved / during a tour: box "Mit dieser Route weiter · Tour beenden und laden · Abbrechen"; tap done / imported → S4 / S7 (no question); ⋮ → export, copy, delete (bin) |
| S9 | Search (full screen) | result → S5 (pin + card); back → Plan |
| N1 | Navigate · locked · follows a line | slide "» Entsperren" → N2; activity in the status card → activity check sheet; ⚙ navigation settings; eye; whole route; locate; off-route boxes (take / keep / via), far start (lead / just record), mid start (join / lead) |
| N2 | Navigate · unlocked (30 s, a map tap or back locks) | status card: Pause, Tour beenden; bottom: Sperren · blue "Plan" → S6 |
| N3 | Navigate · paused | stays unlocked; "Fortsetzen"; Tour beenden; Plan |
| N4 | Navigate · free tour ("Freie Tour · nur Aufnahme") | as N1/N2 without turn card |
| N5 | Arrival sheet (Navigate only; in Plan it waits) | Tour speichern / Weiterfahren / ✕ / Löschen – nothing about the plan (2026-11-06) |
| N6 | End sheet "Tour beenden?" | **Owner 2026-11-06, the tour and the plan are two things:** Tour speichern → (rides found: ride summary) → "Tour gespeichert" box (OK) → Plan with the plan still on the map; Löschen → the tour to the bin, the plan stays; Weiterfahren / ✕ → back. A plan never saved was saved at "Los geht's" (`PlanAutoSave`); changes made on the way show "Nicht gespeichert ›". (Was: a plan switch + Save dialog after – H2, easy to miss.) |
| N7 | Unfinished tour (after a restart / kill) | Continue · Delete · Save |

16 states (S1–S9, N1–N7) plus 5 dialogs / boxes reached from them (Save, "Plan leeren?", "Plan ersetzen?", tour-load box,
ride summary); 39 transitions written out above (gestures that only move the map not counted).

## Inputs for the common goals (from the code)

| Goal | Inputs | Steps |
|------|--------|-------|
| New plan and go (GPS fix) | **3** | hold → Hierhin → Los geht's (+1 when straight legs ask) |
| Record without a plan | 2 taps: ☰ → "Ohne Plan aufzeichnen" (H1, owner 2026-11-06) | – |
| Pause / resume (locked Navigate) | **2 / 1** | slide → Pause / Fortsetzen |
| End and save the tour | **3–5** | slide → Tour beenden → Tour speichern (→ Save dialog Speichern when the switch is on) → "Tour gespeichert" Fertig |
| Change the destination on the way | **6** | slide → Plan → hold new spot (appended; the old goal becomes a stop) → hold old goal → Delete → ‹ Zur Tour (M3) |
| Follow a saved (planned) route, no tour | **4–5** | ☰ → My tours → tap → (Plan ersetzen? when unsaved) → Los geht's |
| Follow a done tour during a tour | **7** | see save-paths.md "Flow" (F1–F3 there) |
| Clear the plan on the way and start a new one | **7** | slide → Plan → ✕ → Plan leeren (or Speichern first) → hold → Hierhin → ‹ Zur Tour |
| From Plan back to Navigate (during a tour) | **1**, but top left | the round blue "‹" in the band (or Android back) – far from the thumb; **being built:** C, a green "Navigation" button at the very bottom instead (owner 2026-11-06) |

## Findings

### High

| # | Finding | Code | Emulator | Proposal (one sentence to decide) |
|---|---------|------|----------|-----------------------------------|
| H1 | **FIXED 2026-11-06 – Aufnahme ohne Plan über ☰ (Owner 2026-11-06: „Los geht's ohne Route“ im Blatt war verwirrend; ☰ → „Ohne Plan aufzeichnen“, nur ohne laufende Tour; also ☰ → „Neu planen“). E2E CorePlanE2ETest.anEmptyPlanStartsARecordingWithoutARoute.** **A tour without a plan cannot be started.** With 0 or 1 plan points there is no start button in any sheet step (`PlanBarRow.shown` and `CompactGo` need 2 points since R5, 2026-11-05) and ☰ has no "Aufnahme starten" (removed 2026-11-02, #15, because "the green button records without a route"). The label "Los geht's · ohne Route" (`PlanStart.RECORD_ONLY`) is unreachable; the comment in `PlanBarRow` still says "one point only records: the rolled-up row / ☰". | `PlanPanel.kt:487`, `:650`; `PlanTopBar.kt:264` | **yes** – 0 and 1 point, every sheet step; ☰ = My tours · Pair · Settings | With an empty or one-point plan the sheet shows the green "▶ Los geht's · ohne Route" again (recording only) – yes / no? |
| H2 | **"Löschen" in the end / arrival sheet also drops the unsaved plan, although the switch "… Plan auch speichern" is ON.** `onDelete` sets the switch off and ends the tour; `RecordingService.finished` clears the plan; no question, Undo only. The switch sits right above, so "delete the tour, keep the plan" is what the sheet suggests. | `MapScreen.kt:227`, `:239`; `AppViewModel.kt` finished collector | **yes** – plan 0 points after, nothing in Planned, no question | "Löschen" with the switch ON saves the plan first (same Save dialog as "Tour speichern") – yes / no? |

### Medium

| # | Finding | Code | Emulator | Proposal |
|---|---------|------|----------|----------|
| M1 | **Two routes after "Tour nachfahren" during a tour.** The followed done tour is drawn blue as the guidance, and the old plan stays in Plan (blue line, numbers, km, "Gespeichert als …") – Plan no longer shows what Navigate follows (owner rule "what Plan shows = what Navigate follows"). One edit of that old plan (or Undo) and "‹ Zur Tour" silently switches the guidance from the track back to the plan. | `AppViewModel.followShown` (:3333), `continueNavigation` (:2880), `PlanGuidance.planReplacesTrack` | **yes** – old plan (2 points) in Plan while the track is followed; after ⇅ + ‹ Zur Tour the track is no longer followed | Following a done tour during a tour asks once "Plan durch diese Tour ersetzen?" (Ersetzen = the track becomes the plan via TrackToPlan, like "Als Plan übernehmen") – yes / no? |
| M1 ✓ | **Done 2026-11-06 (donald):** "Folgen" / "Tour nachfahren" during a tour: the plan leaves the map first (one Undo step, `followShownNow`), the tour follows the track alone; an unsaved plan asks "Plan speichern?" before (`ReplaceAsk`, Speichern · Verwerfen · Abbrechen); a saved plan goes at once. A new point afterwards is a new plan the tour follows. | `AppViewModel.followShownDuringTour` / `unlessUnsaved` | **yes** – red before (plan stays, 2 points), green after: `CoreFieldE2ETest.followingATrack*` | — |
| M2 | **Pause / End only sometimes in Plan.** *(being built: F1, onpath.private#33 – the status line only)* During a tour the plan sheet has no Pause / End (R1: "‹ Zur Tour" leads back), but over a shown track or a place card a tour card with Weiter / Pausieren / red Tour beenden appears (the 14:37 screen). Same tour, two different control sets in Plan. | `MapScreen.kt:348` | no (code is clear) | Take F1 of save-paths.md for both: during a tour Plan never shows tour buttons, only the status line – yes / no? |
| M3 | **Changing the destination on the way = 6 inputs**, and a hold on the open map appends a new goal: the old goal silently becomes a stop that must be deleted by hand. | `MapHost.kt:233` `addPoint`, `PlanEdits` | no | The hold menu during a tour gets "Neues Ziel" (replaces the goal) beside "Als Stopp" – yes / no? |
| M4 | **"‹ Zur Tour" and "Weiter" do different things to a pause.** *(the new bottom button C must decide it once)* The band's ← (and Android back) goes to Navigate and keeps the pause; the tour card's green "Weiter" (S7) and the rolled-up `goClick` resume it. | `PlanTopBar onBackToTour` → `continueNavigation`; `TourBar.kt:326`; `PlanPanel.kt:636` | no | Back to the tour never changes the pause (Navigate shows "Fortsetzen" anyway) – yes / no? |

### Small

| # | Finding | Code | Emulator | Proposal |
|---|---------|------|----------|----------|
| K1 | The activity button opens two different things: without a tour the activity dialog (all six + T level), during a tour the check-and-switch sheet (Hike only with the remembered T, Pixel review). | `MapScreen.kt:163` | no | One sheet for both, with T1–T5 during a tour too – yes / no? |
| K2 | `TourStatusRow` (one-row status + red "Beenden") is no longer used anywhere. | `TourBar.kt:368` | – | Delete it (tidy, no owner decision). |
| K3 | `RouteMenuState.startRecording` is computed but no menu item reads it (left over from the removed ☰ item). | `RouteRules.kt:73` | – | Delete with H1's decision. |

Referenced, not repeated: save-paths.md 1 (fixed 894ab407), 2, 6, 8, 12 and the follow-a-done-tour flow (F1–F3); the
Pixel review's "PLAN ÄNDERN" without a plan, "Geänderten Plan" for a new plan, the second "Tour gespeichert" box, the
past arrival time, the triangle over the activity sheet (pin card text done 8123d06a; its size was a wrong density – 48 dp).
Being built in parallel, not repeated: F1 (shown tour during a tour: 2 buttons), no second "Tour gespeichert" box
(onpath.private#35), C (green "Navigation" at the bottom of Plan during a tour instead of the band's "‹").

## Colour rule audit

Owner 2026-11-06: "es ist alles blau, und was zur Navigation geht, grün" (ui-style.md colour rule, owner 2026-11-02;
#27: Pause and Tour beenden are green, nothing red on stopping). Green = into Navigate / start, continue, pause, end the
tour (`OnPathColors.ActionGo`, theme `primary` = `Green`); blue = plan / change (`ActionEdit`, `PlanAccent`); neutral
(cancel, OK, close, external) = grey / outlined; delete = red allowed. **Pitfall:** the theme's `primary` is green, so
every `Button` / `TextButton` / `OutlinedButton` without its own colours is green – most violations below are buttons
that never chose a colour. 152 buttons scanned (`Button`, `TextButton`, `OutlinedButton`, `FilledTonalButton`, FABs)
plus the hand-made ones (plan pills, pin card, go row, slider); Settings / setup / backup / offline maps / sync / update
pages (80 buttons) are one group (C17).

### Violations

| # | Where (file:line) | Text | Today | Does | Should be |
|---|-------------------|------|-------|------|-----------|
| C1 | `search/PlaceCard.kt:108` | "Als Stopp 2 einfügen" / add (search result / candidate card) | green filled (default) | plans | blue filled |
| C2 | `search/PlaceCard.kt:125`, `:129` | "Als Start", "Als Ziel" | green outlined (default) | plans | blue outlined |
| C3 | `Panels.kt:143` | "Als Plan übernehmen" (shown track) | green outlined (default) | plans | blue (F1 builds it) |
| C4 | `TourBar.kt:382` (`TourRunningBar`, Plan over a shown track / place) | "Tour beenden" | **red** outlined | ends the tour | green outlined (#27) – F1 removes this card |
| C5 | `TourBar.kt:432`, `:437` (`TourPauseEnd`, same card) | "Pausieren" / "Tour beenden" | light **blue** tonal (`secondaryContainer`) / **red** + ■ | pause / end | green tonal / green outlined (#27) |
| C6 | `PlanTopBar.kt:148` | round "‹" back to the tour | **blue** | into Navigate | green – C replaces it with the green bottom button |
| C7 | `RouteDialogs.kt:107` (Save dialog) | "Speichern" | green text (default) | saves the plan | blue (the "Plan speichern?" decision dialog already uses blue, `RouteDialogs.kt:166`) |
| C8 | `Screens.kt:425` ("Plan ersetzen?") | "Ersetzen" / "Zuerst speichern" | green text (default) | plans | blue |
| C9 | `RouteDialogs.kt:75` (straight legs) | "Route korrigieren" | green text (default) | plans | blue ("Trotzdem navigieren" beside it is green – right) |
| C10 | `TourBar.kt:492` (`LongRouteDialog`) | "Trotzdem planen" | green text (default) | plans | blue |
| C11 | `MapScreen.kt:387` (slow plan box) | "Weiter warten" (main button) | green filled (`MessageCard` main = always `Green`, `MessageCard.kt:265`) | plans | blue – `MessageCard` needs a colour per box |
| C12 | `PlanPanel.kt:659` (`NotRoutedBar`) | "Berechnen" / "Trotzdem planen" | green text (default) | plans | blue |
| C13 | `MapScreen.kt:375` (shared place unsure) | "Suchen" | green filled (`MessageCard`) | search / plan | blue |
| C14 | `poi/PoiSheet.kt:117` | "In Google Maps öffnen" | green filled (default) | leaves the app | neutral outlined |
| C15 | `PlanPanel.kt:352` and the dialogs' "Abbrechen" / "OK" / "Schließen" (`MapLoad.kt`, `BackgroundDownloads.kt`, `Screens.kt:203`, `:413`, `:523`, `RouteDialogs.kt:57`, `:108`) | cancel / close | green text (default) | neutral | grey (`onSurfaceVariant`) |
| C16 | `MessageCard.kt:268` – every second button of a box ("Abbrechen", "Nicht jetzt", "Nur aufzeichnen" …) | | green text | mixed | per box: neutral grey or the box's colour |
| C17 | Settings, setup guide, backup, offline maps, sync, updates (80 buttons) | "Laden", "Prüfen", "Exportieren", "Installieren" … | green (default) | neither Navigate nor plan | neutral or blue – the owner decides once for "settings pages" |

### Follows the rule (checked)

Green for Navigate: plan sheet "▶ Los geht's" / "Navigation" (`PlanPanel.kt:596`, `:544`, rolled-up row `:548`), track
panel "Los geht's · <activity>" / "Tour nachfahren" (`Panels.kt:155`), slider "» Entsperren" (`SlideToUnlock.kt:100`),
Navigate "Pause" (tonal green, `TourActionBar.kt:56`) and "Tour beenden" (green outlined, `:61`), "Sperren" (`:89`),
paused "Fortsetzen" (`NavigateScreen.kt:521`), "Weiter · <activity>" (`TourBar.kt:326`, `:389`), end / arrival sheet
"Tour speichern" (green filled, `TourBar.kt:257`) and "Weiterfahren" (`:215`), "Zurück zum Start" (`:468`), "Trotzdem
navigieren" (`RouteDialogs.kt:74`), the tour-load box "Mit dieser Route weiter" (`Notices.kt:131`), off-route / far-start /
mid-start boxes in Navigate (`NavigateScreen.kt:746`–`784`), the activity sheet "… beibehalten" during a tour
(`ActivitySwitchSheet.kt:109`). Blue for the plan: "Plan" in Navigate (`TourActionBar.kt:97`), pin card "+ Hinzufügen" /
"Hierhin" (`MapOverlays.kt:255`), plan pills ✕ Neu planen · ⇅ Umkehren · ↻ Rundtour (`PlanPanel.kt:242`, `PlanAccent`),
search glass / ☰ / map buttons in Plan (`PlanTopBar.kt`, `MapFooter.kt:140`), the "Plan speichern?" / "Plan leeren?"
decision dialog (`RouteDialogs.kt:166`). Red only for deleting: end sheet "Löschen" (`TourBar.kt:220`), "Tour löschen"
(`:273`), "Verwerfen" (`RouteDialogs.kt:167`), menu "Löschen" rows (`MapOverlays.kt:395`).

**Proposal (one sentence each):** give `MessageCard` and the plan dialogs an explicit colour (blue in Plan, green in
Navigate, grey for cancel) and stop relying on the green theme `primary` – yes / no? Settings pages (C17) neutral grey –
yes / no?

## Novice walkthrough

Owner 2026-11-06: "nicht nur versehentliches Löschen … sondern auch komplizierte Rückwege oder komplizierte Handlungen,
die einen technisch unbedarften Rentner oder Wanderer unter Umständen überfordern." A cognitive walkthrough of main
844c8401 (code + the German screenshots in `app/src/test/snapshots/images`, `…GermanScreenshotTest_<name>.png`).

**Persona:** 70, hikes and rides, uses the phone for WhatsApp and photos; does not know "Modus", "Sheet", "Track", "GPX";
does not like reading; outdoors in sun, with a glove or one hand; under stress (wrong turn, rain) presses "Zurück".
Each step is checked with the four questions: **want** (does he want to do this here?), **see** (is there a visible
button, or a hidden gesture: hold, slide, drag the sheet, 7 taps?), **understand** (does the word / sign say it does
this?), **feedback** (does he notice it worked, or does something change silently?). Plus: the way back from each
state, Android's back key, timers, targets < 48 dp, sun, words.

What already helps (kept): the empty sheet says "Wohin soll's gehen? Halte dein Ziel auf der Karte gedrückt – oder such
es oben in der Suchleiste." (`planEmpty`); a plain tap on the map says "hold" (`planTapHoldHint`); "Hierhin" without a
GPS fix is greyed with the reason; a tap on the slider nudges the thumb ("this slides", `SlideToUnlock.kt:82`); no
message goes away by itself (owner rule – the only timer is the 30 s re-lock of unlocked Navigate, and paused it stays
open); every target is ≥ 48 dp (round pills 40 dp in a 48 dp box, `PlanPanel.kt:582`; the pin card's 107 px are 48 dp on
the Pixel's 356 dpi); taking the phone out of the pocket in Navigate jumps back to the own position
(`AppViewModel.onReturnToApp`, :354); the bike rest view says "Tippen für die Karte"; the end sheet keeps a ✕ and
"Weiterfahren".

### The goals step by step

| Goal | Steps a novice takes | Where he stumbles |
|------|----------------------|-------------------|
| First use, no explanation | map opens → reads the sheet line → holds the map → "Hierhin" → "Los geht's · <Ziel>" | **see:** holding is the only way besides the search – the sheet line says it, fine. With 0–1 points there is no start button at all (H1) – "just record" is impossible. |
| Plan and go | hold → Hierhin → Los geht's | fine (3 inputs). |
| Pause / continue (lunch) | Navigate shows "» Entsperren" only → ? | **see / understand:** nothing visible says that Pause and Tour beenden are behind "Entsperren" (only TalkBack hears it) → **N2**. |
| End and save | slide → Tour beenden → Tour speichern | **see:** same as N2; afterwards fine (the second "Tour gespeichert" box goes away, being built). |
| Change the destination on the way | slide → Plan → hold the new place → (old goal stays, the new one is put in silently) → hold the old goal → Löschen → back to the tour | **feedback / understand:** the old goal silently stays as a stop and the route makes a detour through it → **N3** (M3). |
| Back from Plan to the guidance | the round "‹" top left (being built: C, green "Navigation" at the bottom) | **see:** far from the thumb (C fixes it). **feedback:** if he puts the phone away in Plan, the guidance is silent and nothing outside the app says so → **N1**. |
| Clear the plan on the way, start anew | slide → Plan → ✕ Neu planen → Plan leeren → hold → Hierhin → back | fine with C; the empty sheet's hint "geh ‹ Zur Tour" changes with C. |
| Follow a saved plan | ☰ → Meine Touren → Geplant → tap → (Plan ersetzen?) → Los geht's | **see:** ☰ is a sign only – known from other apps, acceptable. |
| Ride a done tour again | ☰ → Meine Touren → Gemacht → tap → Tour nachfahren | fine without a tour; during a tour 7 inputs and 5 buttons (F1, being built). |
| Phone out of the pocket – "where am I?" | Navigate: map back on the own position; bike rest view: "Tippen für die Karte" | fine in Navigate. **Left in Plan:** the map stays where he edited, the plan screen shows no tour, the guidance is silent → **N1**. |
| A slip – get back to a known state | Plan: Undo round button; a deleted tour: bin; back key | **see:** Undo is a sign only (↶) → **N8**; the bin is 3 hidden taps away → **N5**; back in locked Navigate leaves the app → **N4**. |
| Under stress: off the route | box "Du bist 120 m von der Route weg" · "Original behalten" / "Neue Route" | **understand:** "Original" is a technical word; "Neue Route" can read as "plan something new" → **N6**. |

### Findings

Severity for the novice: **high** = he does not get on or loses something; **medium** = he needs help or guesses;
**small** = cumbersome. Known findings are only referenced: H1 (no start without a plan), M1–M4, K1, C1–C17; the Pixel
review's open bugs; being built: F1, C, no second "Tour gespeichert" box.

| # | Sev. | Finding | Code | Screenshot | Emulator | Proposal (one sentence to decide) |
|---|------|---------|------|------------|----------|-----------------------------------|
| N1 | high | **Plan during a tour silences the guidance, and nothing outside the app says so.** Owner rule 2026-11-03 "Plan bleibt still" (no voice, no turn vibration, no off-route question, also with the screen off) – right in the restaurant, but a novice who changed the plan and put the phone away without "‹ Zur Tour" walks on without guidance. The tour notification still reads "Aufnahme · Route: …", and taking the phone out shows Plan (no jump to the position – `onReturnToApp` recentres only in Navigate). | `StopQuiet.kt:3`, `RecordingService.kt:1448` (`buildNotification`), `AppViewModel.kt:354` | `planTourRunningCollapsed` | no (code is clear) | While Plan keeps the tour quiet, the tour notification says "Navigation pausiert – tippen zum Weiterfahren" and a tap opens Navigate (the quiet rule stays) – yes / no? |
| N2 | high | **Pause and Tour beenden are hidden behind "» Entsperren".** The slider's visible word names the lock, not what is behind it; the words "Pause, Tour beenden und Plan" exist only in the TalkBack text. A novice who wants to stop for lunch or end the tour does not know he has to slide. | `SlideToUnlock.kt:100`; `strings_navigate.xml:176`–`177` (DE) | `navigate` | no (screenshot) | The slider gets a small second line "Pause · Beenden · Plan" under "Entsperren" (as the old slider had "Pause · Route ändern · Beenden") – yes / no? |
| N3 | high | **Changing the destination on the way leaves the old goal in.** A hold puts the new place in silently where it fits (`WaypointInsertion`) – the old goal stays and the route makes a detour through it; removing it needs a hold on a marker → menu → Löschen. A novice ends up walking to the old goal. (Novice view of M3.) | `MapHost.kt:233`, `AppViewModel.kt:1473` | `navigateNextGoal` | no | M3's proposal: during a tour the hold menu offers "Neues Ziel" (replaces the goal) beside adding a stop – yes / no? |
| N4 | medium | **Android back in locked Navigate leaves the app.** No back handler there (`MainActivity.kt:332` covers Plan only), so under stress "Zurück" sends OnPath away; the recording goes on (notification), but the novice thinks the app closed and may start a second tour. | `NavigateScreen.kt:261` (back only closes the unlocked bar), `MainActivity.kt:332` | `navigate` | no (Android default) | Back in locked Navigate nudges the slider like a tap instead of leaving the app – yes / no? |
| N5 | medium | **A deleted tour is 3 hidden taps from coming back.** The end sheet says "Kommt für 30 Tage in den Papierkorb." but not where; the bin is ☰ → Meine Touren → ⋮ → Papierkorb. | `Screens.kt:469`; `TourBar.kt` `EndKeepAndDelete` | `endTourConfirm` | no | After "Löschen" the map shows a line "Tour im Papierkorb · Zurückholen" (like the "Tour gespeichert" line being built) – yes / no? |
| N6 | medium | **The off-route box speaks in system words under stress.** "Du bist 120 m von der Route weg" with "Original behalten" / "Neue Route": "Original" is technical, "Neue Route" can read as "plan something new". (App texts the owner may have chosen – ask, do not reword.) | `strings_navigate.xml` `nav_keep_original`, `nav_take_new_route`; `NavigateScreen.kt:746` | `navigateRerouteQuestion` | no | Rename to "Zurück zur Route" / "Von hier neu" – yes / no / own words? |
| N7 | medium | **Far start and mid start use guide words.** "Hinführen" / "Ohne Route weiter" and "Zu ① führen" / "Hier einsteigen" – understandable with the title, but "Hinführen" is unusual German. | `strings_navigate.xml` `nav_far_lead`, `nav_mid_lead` | `navigateFarStart` | no | "Hinführen" → "Weg zum Start" – yes / no? |
| N8 | small | **Undo is a sign only.** The round ↶ appears only after an edit; a novice who deleted a point by mistake does not read it as "Rückgängig". | `MapFooter` map controls (content description "Rückgängig") | `planChangedNotSaved` | no | After a delete or "Neu planen" the sheet's line says once "Gelöscht · Rückgängig" (an ANZEIGE line, gone with the next edit) – yes / no? |
| N9 | small | **Grey 12 sp lines carry things he must read in the sun:** the bin note, "Wird als neuer Plan gespeichert", the stop times "an 16:35", "In Bewegung 0:00". | `TourBar.kt` (bin note, plan line), `NavigateScreen.kt:382` (stop rows) | `endTourConfirm`, `navigateProfileStops` | no | Lines that decide something (bin, which plan is saved) at 14 sp in the normal text colour – yes / no? |

**The three that matter most** (few, simple, no intro carousel): N1 – the notification says when the guidance is
paused, and a tap goes back (keeps the owner's quiet rule); N2 – "Pause · Beenden · Plan" under "Entsperren";
N3 / M3 – "Neues Ziel" in the hold menu during a tour.

## How the probes ran

`app/src/androidTest/java/com/onpath/app/e2e/FlowMapProbeE2ETest.kt` on the donald emulator via
`FLOWS=com.onpath.app.e2e.FlowMapProbeE2ETest tools/dev/device.sh emulator -- tools/ci/release_rundgang.sh`: `probeNoWayToRecordWithoutARoute` (H1),
`probeDeleteTourDropsTheUnsavedPlanDespiteTheSwitch` (H2), `probeTrackFollowKeepsOldPlanAndAnEditTakesOver` (M1). Not on
main: a probe asserts the faulty behaviour and would turn red once it is fixed – each finding's fix gets a real test.
