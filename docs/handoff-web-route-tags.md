# Handoff: "New" / "Changed" tags in My routes (web planner)

**App side (done on main, owner 2026-10-31).** My routes → Planned no longer marks a route or imported track from the
browser with a bold title + the grey line "New from the browser" / "Changed in the browser" – hardly distinguishable.
Instead a small **tag** sits after the name on the title row:

| Tag | EN | DE | When |
| --- | --- | --- | --- |
| new | New | Neu | came new from the browser, not opened yet (`SyncMark.NEW`) |
| changed | Changed | Geändert | changed in the browser since it was last opened (`SyncMark.CHANGED`) |

Same marks as before (`plansync/SyncMarks`: an import marks, opening clears, recycle bin / unpair clear; a "new" one
changed again stays "new"). Code: `app/src/main/java/com/onpath/app/ui/RouteTag.kt`; rules and look:
`docs/design/ui-style.md` → "Tags".

**Look:** light blue pill `#DDEAFB` (`OnPathColors.TagContainer`), dark blue bold text `#0B3D91` (`TagInk`), 12 px
bold, padding 3 px × 8 px, `border-radius: 999px`, 8 px after the name. The name ellipsizes before the tag is cut; at a
large font (narrow row) the tag goes under the name. Pictures: `app/src/test/snapshots/images/com.onpath.app.ui_ScreenshotTest_toursFromBrowser.png`
(+ `GermanScreenshotTest_…`, `RouteTagLargeFontTest_…`).

## Web side (to do on web-spike)

The browser keeps the phone's marks as data (`sync.ts`: `mark?: 'new' | 'changed'`, "New from the phone" /
"Changed on the phone", cleared when the route is opened – A-017 rule 11) but shows nothing since §14.6; **bold** in
the web's My routes means "waiting – not saved to the phone yet".

1. In `plans.ts` (My routes rows) show the same tag after the name from `mark`: "New" / "Neu", "Changed" /
   "Geändert" (one word; the source "from the phone" is clear from context, as in the app).
2. CSS: one `.tag` class with the colours above (two tokens beside the others in `style.css`, e.g. `--tag-bg`,
   `--tag-fg`); the name `overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0`, the tag
   `flex: none` so it is never cut.
3. Keep the mark logic as it is (opening clears it). Bold for "waiting" is the web's own state – if the owner wants it
   as a tag too, that is a separate question (no colour other than the tag family for a tag).
4. Texts in the web's EN + DE strings; a ui-shot with both tags (EN + DE).
5. web-sync.md §14.6: "marks" are shown again – as tags.
