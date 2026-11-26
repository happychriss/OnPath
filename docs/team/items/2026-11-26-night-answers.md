# Night answers 2026-11-26 / 27

Questions the night's roles answered themselves (owner asleep, docs/team/owner-preferences.md "Night jobs"), checked
against `VISION.md`. One line each: `Q … → A … (role, time)`.

- Q „Übernachten“ – which kinds? → A `PoiKinds.LODGING` = hut, bivouac, camping, hotel, guest_house, hostel; not shelter (a roof against rain, not planned to sleep in); not tourism=apartment / chalet (holiday flats rented by the week). (laptop, 23:20)
- Q Huts are in the chip „Essen“ (owner 2026-10-31) – also in „Übernachten“? → A yes, the one kind in two chips (a hut feeds and beds you); `Category.of(hut)` stays FOOD. (laptop, 23:20)
- Q Chip place and English word? → A after „Essen“ (Orte · Essen · Übernachten · Sehenswert · …); EN „Lodging“ (one word like the other chips). (laptop, 23:20)
- Q Hotels on the map by default? → A off (calm map, VISION); found with the chip, turned on in Settings. `DEFAULTS_VERSION` 3: a user on the old default gets the new one; a user who changed the POI filter sees hotels until he hides them. (laptop, 23:20)
- Q A Gasthof tagged amenity=restaurant + tourism=hotel? → A stays a restaurant (as today, the map does not change); only plain hotels / guest houses / hostels are new. (laptop, 23:20)
- Q Settings group „Hütten & Camping“ now holds hotels too – rename? → A kept for now (one less changed word overnight); ask the owner: „Übernachten“? (laptop, 23:20)
- Q Map icon? → A one bed icon (Maki „lodging“, CC0) for hotel / guest house / hostel in the hut red. (laptop, 23:20)
- Q: Which POI / search kinds count as a place to sleep for „Als Übernachtung“ (hotel at once vs blue moon)? → A: POI kinds hut, bivouac, camping; online results whose OSM tourism class is hotel, guest_house, hostel, motel, chalet, apartment, alpine_hut, wilderness_hut, camp_site, caravan_site. Shelter is not lodging. TODO in `multiday.ts` isLodging: switch to the core helper from `work/laptop-overnight-pois`. (laptop, 00:20)
- Q: A lodging dropped on a point that is already an Übernachtung? → A: it becomes that night's hotel (green bed), N stays – no second night on the same spot. A plain place on a night, or a hotel on a hotel night: nothing changes (the night's own menu „Hotel ändern“ does that). (laptop, 00:20)
- Q: Where does „Als Übernachtung“ sit in a place menu? → A: last row of the Route group (after the add rows); a multi-day tour has no „Rundtour ab hier“, so nothing competes for „last“. (laptop, 00:20)
- Q: The night pill before the days are worked out (plan just loaded)? → A: the symbol alone, „Tag N“ appears once the overview is computed – never a wrong number. (laptop, 00:20)
- Q: „Hotel entfernen“ red? → A: no – the night stays (blue); red only when something leaves the plan (item 2026-11-26: Löschen, Übernachtung entfernen). (laptop, 00:20)
