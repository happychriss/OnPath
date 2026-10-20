# OnPath – Review nach deinen Anforderungen (19.–20.10.)

Status: review of main 0.7.20; follow-up fixes in 0.7.21 (camera revert, point at finger-down) and 0.7.22 (guard removed, compass after free look, no extra north move, label). Open: owner decision 2.1 (Plan turning by itself after locate), "where I face" arrow as own symbol (2.2).

Geprüft wurde der Stand `main` 0.7.20 (bef6b65). **Achtung:** Im Arbeitsordner liegt gerade eine *nicht committete* Änderung
an HeadingEngine/MapController/MapHost (zurück zu MapLibres eigener Kompass-Verfolgung, eine einzige Richtung).
Sie ist unten berücksichtigt.

## 4. Kompass springt zurück – Ursache gefunden ✅
**Ursache:** Seit 0.7.15 dreht die App die Karte selbst, mit `map.moveCamera(bearingTo…)` (MapController.kt ~303, bei
jeder Richtungsänderung und seit 0.7.12 auch bei jedem GPS-Fix über `reemit`). In MapLibre 13.7.1 meldet **jede**
App-Kamerabewegung (moveCamera/easeCamera/animateCamera) eine „Entwickler-Animation“. Daraufhin schaltet die
LocationComponent die Verfolgung aus (`setCameraMode(NONE)`) und ruft `onCameraTrackingDismissed` auf. Das habe ich im
Bytecode der Bibliothek nachgeprüft. Bei uns läuft das in `dismissed()` → `vm.onMapGesture()` → `followMe=false` →
der Knopf zeigt die Nadel. Zeitlich passt das: 0,75 s Übergang, dann der nächste Fix → „nach wenigen Sekunden“.
**Folge des Schutzes in 0.7.20:** Er versteckt den Fehler nur. Der falsche Abbruch wird ignoriert und `recentreTick++`
startet die Verfolgung neu. Die nächste Drehung bricht sie wieder ab. So entsteht eine Schleife etwa im Sekundentakt:
Ruckeln, der Punkt wandert kurz aus, und Diagnose zeigt laufend „Following stop without a touch … ignored“.
**Fix:** entweder (a) die nicht committete Rückkehr zu `TRACKING_COMPASS`, dann muss aber der 1,5-s-Schutz wieder
raus, oder (b) im Blick-nach-vorne-Modus die Kamera ganz selbst führen: `CameraMode.NONE` plus pro Frame ein
`moveCamera` mit Position + Richtung + Abstand oben. Gesten erkennt man dann über eigene Move-/Rotate-Listener.
(b) erhält die zwei Richtungen (siehe 1.2).

## 1. Widersprüche / unklare Anforderungen
1. **Kompass im Planen:** Am 19.10. hast du gesagt: Planen = Norden oben, mit der Hand drehen, Kompass → Norden →
   Gehrichtung. Seit 0.7.19 gilt eine Einstellung für beide Modi (Standard „Gehrichtung“). Folge: Im Planen dreht sich
   die Karte nach dem **Orten-Knopf** von selbst mit (MapScreen.kt:75–76 → setCamera mit headingUp). Bitte entscheiden:
   Soll Planen jemals von selbst drehen?
2. **„Weg gerade vor mir“ (08:13) + „Pfeil dreht sich, wenn ich mich 90° drehe“ (12:13):** Das geht nur mit zwei
   Richtungen: Karte nach der Route, Pfeil nach Blickrichtung. Die nicht committete Änderung hat nur noch **eine**
   Richtung. Dann zeigt der Pfeil auf dem Weg wieder nach vorne, und 12:13 ist nicht mehr erfüllt.
3. **Freies Umschauen, dann Kompass tippen:** Du willst „zurück zur gewählten Ausrichtung“. Der Code schaltet aber auf
   **Norden** und speichert das dauerhaft (AppViewModel.kt:170, `mapTurned`). Nach einem falschen Abbruch (Punkt 4)
   wirkt das wie ein zweites Zurückspringen.
4. Erledigt und widerspruchsfrei ersetzt: Kegel → Pfeil, GPS aus in Pause → langsames GPS, Bildraten-Begrenzung → keine
   Begrenzung beim Hinschauen, getrennter Plan-Kompass → eine Einstellung, Suchring lädt keine Karte (T2 abgelehnt).

## 2. Umgesetzt (im Code gefunden)
- **Navigation:** Route nach vorne, 40 m voraus (MapScreen.kt:250–258); nie auf dem Kopf (HeadingFilter, 15 m);
  größere, getrennte Knöpfe; Punkt über allen Linien; Rückkehr zur Position beim Öffnen; Höhenprofil-Punkt (4 s);
  freies Umschauen mit Rückkehr nach 25 m.
- **Aufnahme-Sicherheit:** keine Stopp-Aktion in der Benachrichtigung; laufende Tour nicht löschbar; Update fragt
  während einer Tour; Neustart/Update → Weiterlaufen oder Hinweis; „Immer erlauben“ wird einmal angeboten; Pause:
  GPS alle 10 s und Erinnerung nach 150 m.
- **Strom:** Bildschirm aus in der Tasche (Annäherungssensor, 60 s); keine Bildraten-Begrenzung; Dienst ohne WLAN-Ortung.
- **Vibration bei Abweichung:** als Alarm, auch bei dunklem Bildschirm; abschaltbar.
- **Position:** WLAN-Position nach 30 s ohne GPS, ≤ 150 m, nie Funkzelle; der Track bleibt reines GPS.
- **Karte:** grüne Wege je nach Aktivität (`greenway-foot` / `greenway-bike`); Fußgängerzonen wieder gezeichnet;
  Farben/Wege nach der Analyse.
- **Rad-Routing:** falsche Richtung in Einbahnstraßen kostet ×100 (Rad-, Trekking- und MTB-Profil).
- **Menüs:** „Start here · Go here“; kein „Check for updates“ im ☰; Route nach dem Speichern automatisch vom Bildschirm.
- **Google-Teilen:** Ein Punkt von der Google-Seite wird gegen die Adresse geprüft (> 3 km → Adresse).
- **Karten-Nachladen:** nur dokumentiert (map-cells §9, T1/T3–T6); wie gewünscht noch nicht gebaut.

## 3. Probleme / Risiken (wichtigste zuerst)
1. **Kompass-Schleife** (siehe 4) – MapController.kt ~303 und HeadingEngine `reemit` → eigene Kamera-Drehung beenden.
2. **Der Schutz aus 0.7.20 macht eigene Kamerafahrten rückgängig** (AppViewModel.kt:300–303). Wenn die Karte gerade
   folgt (z. B. nach dem Orten-Knopf), wird jeder Kameraflug der App als falscher Abbruch ignoriert, und die Karte
   springt zurück zur Position. Betroffen: Suchergebnis (`pickSearchResult`, :795, setzt `followMe` nicht auf aus),
   GPX/Route öffnen (`Fit`), Kompass → Norden (`northUp`). Fix: vor jedem eigenen Kameraflug `followMe=false` setzen
   und den 1,5-s-Schutz entfernen.
3. **Kompass in Navigate von Gehrichtung → Norden:** Zuerst startet die Norden-Verfolgung, danach ruft `northUp()`
   noch `animateCamera` auf (MapScreen.kt:297/299). Ohne Schutz endet damit die Verfolgung. Fix: `northTick` nur
   erhöhen, wenn danach nicht verfolgt wird.
4. **Kompass nach freiem Umschauen** → Norden statt zurück (siehe 1.3). Fix: in Navigate bei `headingUp &&
   !followMe` → `followMe=true`.
5. **Planen dreht nach dem Orten-Knopf** (siehe 1.1). Es braucht deine Entscheidung.
6. **Kleinigkeiten:**
   - Die Einstellung heißt noch „Map direction while navigating“ (SettingsScreen.kt:255), gilt aber auch fürs Planen.
   - `toggleNavOrientation` wird nicht mehr benutzt.
   - Veralteter Kommentar „paused = no GPS“ (RecordingService.kt:293).
   - Die App-Ortung fragt auch während einer Tour WLAN-Positionen ab, solange die App offen ist. Das passt zur
     Strom-Regel, aber die Notiz zu 0.7.3 sagt „keine WLAN-Ortung“.

**Empfehlung:** erst Punkt 4 + 3.1–3.3 zusammen in **einem** Release reparieren. Die vielen kleinen Einzel-Fixes
(0.7.15 → 0.7.20) haben sich gegenseitig ausgelöst.
