# Voice guidance: few, clear sentences

Status: **built 2026-10-31** (`navigation/VoiceGuide`, `navigation/VoiceText`, `recording/Speaker`, phone setup step
"Voice", Settings → Navigation); agreed with the owner (all questions of §6: yes) (backlog "Voice guidance"; F-NAV-05). Owner 2026-10-31: "every tester asks
whether it talks" – Android's own speech output (Google or the phone's engine, offline voices only), names spoken by the
same voice; "smart, clever sentences, not too much". Built after the feature stop (first the ride with the checklist).

## 1. What we learnt (sources at the end)
- **Older users and voice:** voice-only guidance did as well as a paper map for middle-aged and older walkers and felt
  less tiring; the problems were **wording and timing** – they asked for **earlier** prompts and explicit words at the
  junction [1]. Landmark words ("onto the field track", "after the bridge") help older people most [2] and lower the
  load [3]. Some **repetition gives comfort** – cutting prompts to the bone is not the goal either [4].
- **OsmAnd** (the mature open-source example): prompts are timed by distance = lead time × the profile's speed (bike
  10 km/h, walking 4 km/h); on foot it skips the "prepare to turn" prompt; the user can make all prompts earlier or
  later; off-route can be muted [5].
- **Android:** a voice reports `isNetworkConnectionRequired()` – we use only voices where it is false; a voice can need a
  data download first (`KEY_FEATURE_NOT_INSTALLED`) [6]. Navigation apps speak with the usage "navigation guidance" and
  ask for short audio focus that lets music duck (Mapbox's defaults: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`,
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) [7].

## 2. Rules ("smart, not too much")
1. **Speak only where we vibrate today** (`CueRule`: where one could go wrong). Never "continue straight on", never at a
   junction where the route simply follows the way. The voice adds no new junctions.
2. **Said early, then "now" – timed by the speed** (owner 2026-10-31, like the vibration: `SpeedWindow`, unknown speed →
   the activity's typical speed). Bike in two stages (owner 2026-11-01): "In 150 Metern links." ~25 s ahead
   (100–400 m), then the "now" "Gleich links." whose turn word is **heard ~6 s before the turn** (at least 30 m, at most
   150 m; owner field ride 2026-11-07, §9) – the fix's age, one fix a second, the speech engine's start (measured by
   `Speaker`) and the word before the turn word are counted. On foot one sentence "Gleich links." heard ~10 m before
   (8–15 m). The voice plans with at most 50 km/h (a GPS speed spike). Either voice or vibration (owner 2026-11-01).
3. **Close turns in one sentence:** "In 100 Metern links, danach gleich rechts." (the merge rule of `JunctionCues`).
4. **Say what you turn onto when it changes** – from the route's way types (owner 2026-11-01, Hilden: every park path
   was a "Feldweg"), read 20 m before / after the junction so a crossing is not "the way": "auf den Feldweg" only on a
   track, "auf den Radweg" (cycleway), "auf den Weg" (footway; a path on the bike), "auf den Pfad" (a path on foot),
   "auf die Straße". Without way types: "Weg", never "Feldweg". No street names, no guessed landmarks.
4b. **A sideways step is straight on** (`navigation/JogRule`, shared): two opposite turns closer than 20 m (on foot
   12 m) – onto a pedestrian crossing and off it, round an offset crossroads – are one manoeuvre; within 30° of
   straight on nothing is said or felt, else only the turn of the step's way. **Narrowed on the bike** (owner field bug
   2026-11-07, Hilden: right from a quiet street onto a road, 19 m along it, left onto a footway – nothing said, felt or
   shown: "warum keine Ansage, ich bin auf einer anderen Straße"): the 20 m hold only across a crossing or footway piece
   (the router's class ≤ 4 between the turns, or no class known); along a street (class ≥ 5) the step is 12 m, as on
   foot – farther apart they are two turns, said together ("Gleich rechts, danach gleich links auf den Weg."). The way
   word after a turn is read at most 0.9 × the way to the next turn said, and that turn names its own way ("danach …
   auf den Radweg"). Regression: the owner's Hilden park tour (`tools/routing/jvm-test/HildenVoiceTest.kt`) and the
   2026-11-07 bike plan (`JogHildenTest.kt`), real data; `JogRuleTest`, `VoiceGuideTest`.
5. **Comfort after a turn when the next one is far:** "…, dann 3 Kilometer geradeaus." (bike ≥ 2 km, on foot ≥ 1 km).
   One clause, so the user knows it is quiet now and need not look.
6. **Short:** at most ~8 words, one idea, the direction first, rounded numbers (50 / 100 / 150 / 200 / 300 / 500 m,
   then km to one decimal; under 30 m "gleich").
7. **Never two sentences in a row** – at least 10 s apart; a more important one (off route, arrival) replaces a waiting
   one; a sentence whose junction is already passed is dropped. **A turn's "now" never waits and is never swallowed**
   (owner 2026-11-07, §9): it cuts what is being said, a waiting sentence waits for it, a turn said as "danach" gets its
   own "now", and only a turn really passed (its word could not be heard 1 s before it) stays quiet.
8. **Questions stay on the screen:** the voice never asks; it says "bitte aufs Handy schauen" once.
9. **Quiet** while paused, during a phone call, while the user is off the route and the question is open (said once;
   again only where the phone would vibrate – the first reminder, a way found after none, asked again after Keep –
   and a question after one answered with a new route is news: said, not held back by the 2-minute guard – owner
   2026-11-07, field log tour 221: four questions in 2½ minutes, two silent),
   and when no offline voice is there (then vibration + screen as today).
10. **Plan is silent** (owner 2026-11-03: "im Planmodus soll die Stimme auf jeden Fall schweigen zu allem … Plan only"):
   while the app is in Plan during a tour – also with the screen off – nothing is said and nothing vibrates (turns,
   "Stopp N erreicht", "Kein GPS", arrival, "kein Ziel mehr" after an edit). The voice follows silently (what it would
   have said counts as said), so "Navigation fortsetzen" brings nothing from the time in Plan; waiting sentences are
   dropped then. `recording/ModeQuiet`.

## 3. What is said (DE · EN) – the owner reviews these texts

| When | Deutsch | English |
| --- | --- | --- |
| Tour starts with a route | „Los geht's. 12 Kilometer bis Lana, etwa 50 Minuten." | "Off we go. 12 kilometers to Lana, about 50 minutes." |
| Junction | „In 100 Metern links." | "In 100 meters, turn left." |
| … slight / sharp / keep | „In 100 Metern leicht rechts." · „… scharf links." · „… links halten." | "… bear right." · "… sharp left." · "… keep left." |
| … onto another kind of way | „In 50 Metern links auf den Feldweg." · „… auf den Radweg." · „… auf den Weg." | "… onto the track." · "… onto the cycle path." · "… onto the footpath." |
| … two turns close | „In 100 Metern links, danach gleich rechts." | "In 100 meters turn left, then right." |
| … then a long quiet stretch | „In 100 Metern rechts, dann 3 Kilometer geradeaus." | "In 100 meters turn right, then 3 kilometers straight on." |
| Roundabout | „Im Kreisverkehr die zweite Ausfahrt." | "At the roundabout, take the second exit." |
| Turn round (U-turn on the route) | „Bitte wenden." | "Please turn round." |
| Off route (the question appears) | „Abseits der Route. Bitte aufs Handy schauen." | "Off the route. Please look at your phone." |
| Back on the route by yourself | „Wieder auf der Route." | "Back on the route." |
| New route taken | „Neue Route. In 200 Metern rechts." (the next junction) | "New route. In 200 meters, turn right." |
| Stop reached | „Gasthof Hochmuth erreicht. Weiter nach Algund, 4 Kilometer." (no name → „Stopp 3 erreicht.") | "Gasthof Hochmuth reached. On to Algund, 4 kilometers." |
| Destination | „Ziel erreicht." (bike: within 15 m, §9) | "You have arrived." |
| … the goal not straight ahead (heading ridden) | „Ziel erreicht. Das Ziel ist rechts von dir." · „… links von dir." · „… schräg rechts vor dir." · „… hinter dir." | "You have arrived. The destination is on your right." · "… ahead on your left." |
| The goal comes, off to a side (after the last turn) | „Ziel in 50 Metern, schräg rechts." | "Destination in 50 meters, half right." |
| No point ahead (tour edit) | „Kein Ziel mehr vor dir. Bitte aufs Handy schauen." | "No destination ahead. Please look at your phone." |
| No GPS for 30 s while guided | „Kein GPS-Signal." (once; nothing when it is back) | "No GPS signal." |

Not said: time or km updates on the way, pace, POIs, steepness, "approximate position", the activity check. Names: the
names the app shows (German map names, `name:de`; stop names as in the plan) – the voice speaks the app's language.

**Budget check:** the Meran test ride (bike, 1.4 km) has 5 cues → 5 sentences + start + arrival = 7 in ~6 min. A 50 km
ride with ~40 cues today → ~40 sentences in 3 h, about one every 4–5 min.

## 4. Settings and setup
- Settings → Navigation: **Voice: Off / On**, per bike and per hike (like the vibration). Default **On** once the setup
  test was heard, else Off. No "levels" – the rules above decide (owner: not too much).
- Phone setup gets a step **"Voice"** (recommended, like the vibration test): "So klingen die Ansagen:" → test sentence
  "In 100 Metern links." → **Gehört?** Ja / Nein. No offline voice for the app language → "Stimme laden" opens the
  phone's speech settings (`ACTION_INSTALL_TTS_DATA`, else the TTS settings page); Nein → "Lautstärke prüfen" hint.
  Settings → Phone setup shows the row like the others; the check runs again on every return (a voice can be removed).
- Diagnostics: engine, voice name, offline yes / no, last error; since 2026-11-07 all engines with versions and voice counts (`VoiceSetup.describe`). Any engine, not only Google's, and an online voice as the last resort: `docs/design/onboarding-phone-check.md` addendum 2026-11-07.

## 5. How it is built
- **Pure, unit-tested `navigation/VoiceRules`** (app only, never in the web's shared code): fixes + followed line +
  events (cue, arrival, off route, …) → the sentence to say now or nothing (rules 1–9). Texts in
  `strings/…/strings_voice.xml` (EN + DE), numbers rounded there.
- **`recording/Speaker`** (one place, Android): `TextToSpeech` started with the tour (no delay at the first sentence),
  voice = the best one with `isNetworkConnectionRequired() == false` for the app language; `AudioAttributes`
  usage navigation guidance + content speech; short audio focus that lets music duck, given back after each sentence;
  nothing during a call; Bluetooth headsets work by themselves. Speech rate a little slower than default (0.9). Errors →
  `Problems` (never a crash, never silent).
- The **recording service** speaks (screen off, phone in the pocket), as it vibrates today.
- Tests: `VoiceRulesTest` (every row of §3, close turns, the 10 s gap, dropped stale sentences, quiet cases); a replay of
  the Meran ride counts the sentences (budget); the sound itself only on the owner's ride.
- Effort ~3–4 days.

## 6. Owner decisions (2026-10-31: "alles ja")
1. Start sentence ("Los geht's. 12 Kilometer bis Lana …"): yes.
2. Comfort clause ("dann 3 Kilometer geradeaus"): yes.
3. "Wieder auf der Route.": yes.
4. Default On once the setup test was heard (else Off).
5. "du" as in the rest of the app.
6. The announcement time follows the current speed (§2 rule 2).

## Sources
1. Voice-only navigation for middle-aged and older adults, field study (Yamaguchi University repository):
   https://petit.lib.yamaguchi-u.ac.jp/30544/files/169073
2. Landmark-based pedestrian navigation, older users benefit most (Glasgow, MobileHCI 2004):
   https://www.dcs.gla.ac.uk/utopia/papers/mobilehci04.html
3. Landmarks in driving navigation instructions (University of Iowa): https://pubs.lib.uiowa.edu/driving/article/28252/galley/136544/view
4. Information requirements in text-only pedestrian wayfinding (redundancy brings comfort):
   https://www.springerprofessional.de/understanding-information-requirements-in-text-only-pedestrian-w/2282354
5. OsmAnd – voice prompt triggering: https://docs.osmand.net/docs/technical/algorithms/voice-prompt-triggering ·
   voice navigation: https://docs.osmand.net/docs/user/navigation/guidance/voice-navigation
6. Android `Voice.isNetworkConnectionRequired` / `KEY_FEATURE_NOT_INSTALLED`:
   https://learn.microsoft.com/en-us/dotnet/api/android.speech.tts.voice.isnetworkconnectionrequired ·
   https://learn.microsoft.com/en-us/dotnet/api/android.speech.tts.texttospeech.engine.keyfeaturenotinstalled
7. Mapbox voice instructions player defaults (usage, focus):
   https://docs.mapbox.com/android/navigation/api/coreframework/3.30.1/voice/com.mapbox.navigation.voice.options/-voice-instructions-player-options/

## 7. A new language (owner: more languages without a big change)
The voice speaks the app's text language (`Txt.language()`), with the phone's offline voice for it. Nothing in the
code knows a language:
1. Add `strings/…/composeResources/values-xx/strings_voice.xml` – the same keys as `values/strings_voice.xml`. They are
   **whole phrases with placeholders**, so the language orders them its own way: `voice_in` = "%1$s, %2$s" in English
   ("In 100 meters, turn left"), "%1$s %2$s" in German ("In 100 Metern links"); the "in …" distance and the plain
   distance are separate keys (German dative "Metern" vs "Meter"); ordinals of roundabout exits are words
   (`voice_exit_1…6`); counts use `<plurals>`.
2. The app's other texts in `values-xx` and the language in `Txt.language()` / `docs/i18n.md` (the app is EN + DE today).
3. Nothing else: `Speaker` picks the best offline voice for that language; a phone without one gets the setup step's
   "Load voice".
`VoiceTextTest` shows every sentence in EN and DE – add the new language there.

## 8. Field bug 2026-11-01: "Los geht's" 3–4 times in the first 3 km (fixed)
**Root cause:** the start sentence was guarded only by an in-memory flag in `VoiceGuide`, a field of the
`RecordingService` instance. When the process died (an uncaught exception on `Dispatchers.Main` – the scope had a
`SupervisorJob` but no exception handler – or an OEM kill), START_STICKY brought the service back, it continued the
same tour from disk with a **fresh** guide, and the first matched fix said "Los geht's" again; Android's restart
back-off (1 s, 4 s, 16 s, 64 s) fits "3–4 times in ~3 km".
**Fix and rule:** the tour's voice memory lives with the tour, not with the service – `VoiceGuide.Memory` saved in the
tour's `trip-pace.json` and restored with `continueTour` after a sticky restart; a new tour starts a fresh guide. Every
"state" sentence has a minimum interval (no GPS, off route, arrived once per tour). The service scope reports
uncaught errors to `Problems` instead of killing the process. Test first: `VoiceGuideTest` (the restarted guide never
says Start again).

## 9. Field ride 2026-11-07: "Jetzt abbiegen" too late, a turn never swallowed, where the goal is
Owner (bike, Hilden, tour 217): "Jetzt abbiegen kommt zu spät, 2–3 s vor der Kurve"; never swallow a "now"; "Ziel
erreicht" ~10 m early is fine, but say where the goal is ("Das Ziel ist rechts von dir", "Ziel in 50 m, schräg rechts").

**What made it late – measured** (the Pixel's events log + the tour's GPS points, the plan re-routed in the JVM harness;
the rider ~15 km/h):

| Said | Seconds before the rider reached the turn |
| --- | --- |
| 12:29:58 „Gleich rechts, danach gleich leicht rechts." | 6.1 s (the bear right 25 s later: **never said again**) |
| 12:30:32 „Gleich scharf links auf den Radweg." | 5.1 s – **held 3.0 s** behind „Stopp 2 erreicht. Weiter nach …" (12:30:29), which it then cut |
| 12:31:06 „Gleich rechts auf die Straße." | 55 s – the rider stood at the road, **not said again** when riding on |
| 12:32:19 „Gleich links, danach gleich rechts." | 6.2 s (the right 19 s later: **never said again**) |
| 12:32:29 „Ziel erreicht." | 31 m before the goal, 9 s **before the line's last right turn** (never said) |

- The "now" was meant 8 s ahead (35 m minimum), but measured from a fix ~1 s old with one fix a second it was triggered
  26–29 m / 5–6 s before the turn; the speech engine starts 0.1–0.9 s after the call (1.5 s at the first sentence,
  logcat) and "Gleich" comes before the turn word: heard ~4–5 s before.
- Swallowed or late, by path (all fixed, `VoiceGuideTest` / `VoiceNowTest`, red before): a turn said as "danach" never
  got its own "now"; the 3 s pause after any sentence held a due "now"; a waiting sentence (stop reached) went first; a
  "now" that became due under 12 m (a fix gap ending close, one GPS fix jumping up to the turn) was dropped – and marked
  said, so the next good fixes said nothing either; a "now" said long before (a stop, a speed spike making it 100 m
  early) was not said again; the arrival (up to 50 m early) ended the voice before the last turn.
- The JVM harness (`tools/routing/jvm-test/VoiceTimingTest.kt`: five Hilden bike routes at 15 / 20 / 25 km/h, fix 0.8 s
  old, speech start 0.8 s): before 37 of 204 turns without their own "now" and 6 heard under 2 s (some after the turn);
  after 0 and 0, the turn word heard ~6 s before (median 5.7–7.5 s). A turn closer after the one before than ~5 s of
  riding is named as "danach" and gets its own "now" only while it can still be heard in time.

**Rules since then** (`VoiceGuide`, `GoalSide`, `Speaker`):
1. The bike's "now" ("Gleich links.") is said when its turn word will be heard 6 s before the turn (30–150 m), counting the
   fix's age (`Location.elapsedRealtimeNanos`), half a fix interval, the speech start the speaker measured
   (`Speaker.startMs`, "Speech started after N ms" in the trace) and the word before the turn word.
2. A due "now" goes first: no pause after another sentence (the speaker cuts that one – QUEUE_FLUSH), a waiting sentence
   (stop reached, back on the route, start) waits while a "now" is due within 6 s ("Neue Route." never waits).
3. A turn said as "danach" gets its own "now" once the one before is ridden.
4. A "now" is said however late it became due while its turn word can still be heard 1 s before the turn; nothing is
   marked said when it cannot (a fix jumping back gives it its chance).
5. A "now" said long before (20 s, or 1.6 × its distance) is said once more when due again and the rider moves.
6. Arrival on the bike: the gate still fires as before (question, guidance quiet), "Ziel erreicht." waits until within
   15 m of the goal or the line's end, a stop, the goal passed or 20 s; the turns before it are still said. On foot at once.
7. The goal's side from the heading of the last 12–40 m ridden (never the compass, only while moving, not within 6 m of the
   goal): ahead (nothing said) within 25°, half right to 65°, right to 115°, behind on the right to 155°, behind.
   "Ziel erreicht. Das Ziel ist rechts von dir."; after the last turn, ~12 s before the goal, only when it lies off to a
   side: "Ziel in 50 Metern, schräg rechts." (once per line, not on lines under 400 m).
