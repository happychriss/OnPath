# Round trip from here: two honest texts – proposal, not built (donald / Wartefenster, 2026-11-28)

Context: owner 2026-11-28 ~09:34 – the chip „Runde von hier“ on the empty start card is **not** built (app and
planner); the start card stays as it is, the entry stays in the hold menu („Rundtouren vorschlagen“). While checking the
chip's scenarios against `VISION.md` („surprises the user“, „works offline in loaded areas“) two cases of today's flow
showed up. The captain asks the owner; nothing is built.

Code: `state/src/main/kotlin/com/onpath/app/ui/suggest/SuggestSlice.kt` (`answer`, `run`, `ROUTING_WAIT_MS`).

## 1 – No OnPath map here (no suggest file for the cells)
- **Today:** the run asks the host to fetch the cells' files (`fetchCells`); without a net, or where no map exists yet,
  the run ends with no cards and the note „Von hier ließ sich keine passende Runde finden.“ (`suggest_note_none`) – it
  reads as „no loop exists here“, not „no map here“.
- **Proposal:** „Für hier gibt es noch keine Karte.“ (with a net: the map loads / is made, then the run starts once more
  by itself).

## 2 – Routing data not loaded, no net
- **Today:** phase LOADING „Wegedaten werden geladen …“; the slice polls up to `ROUTING_WAIT_MS` = **180 s**, then
  FAILED „Gerade keine Vorschläge möglich – bitte noch einmal versuchen.“ Offline the data cannot come, so the user
  waits three minutes for a message that does not say why.
- **Proposal:** offline + data missing → at once „Ohne Netz fehlen hier die Wegedaten.“; with a net unchanged.

Both: texts EN + DE (strings_suggest), a JVM test in the slice's tests each, planner parity (`web/app/src/suggest.ts`).
