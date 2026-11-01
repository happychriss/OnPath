# Field bug: "Los geht's" said 3–4 times in the first 3 km (Pixel 2026-11-01, German voice)

Read-only analysis of `main` (b3def4aa, includes 3649b9cc). Nothing was run.

## Root cause

**Inside one RecordingService instance the start sentence cannot repeat.** Only the guide's in-memory flag guards it,
and that flag dies with the service. The tour itself lives on from disk.

1. `navigation/VoiceGuide.kt:102` `private var started = false`. It is set only at `:131-135` (first fix matched on the
   line → `queue(Phrase.Start…)`) and is **never reset**. `Phrase.Start` has no other producer (grep: only `:134`).
2. `recording/RecordingService.kt:144` `private val voice = VoiceGuide()` is a field of the service instance. Nothing
   rebuilds it: `loadFollow` (`:554`), re-routes, a new `Navigator`, FOLLOW_CHANGED, poor → good fix and off-route
   cycles all keep the same guide. `Speaker` (`recording/Speaker.kt:96-112`) speaks with QUEUE_FLUSH and keeps one
   `pending`, so it does not replay anything.
3. All the guide's memory is lost when the process dies (crash anywhere in the process, an OEM / low-memory kill, an
   update). START_STICKY then brings the service back (`onCreate` → `onStartCommand(null)`, `:260-336`).
   `db.findActiveRecording()` → the `existing != null` branch (`:282`) continues **the same tour**: `loadFollow()`
   restores `nav-session.json`, and `loadTrip()` restores the trip pace. But the service has a **fresh `VoiceGuide`**
   with `started = false`.
4. The first fix matched on the line → `VoiceGuide.update` → "Los geht's. N Kilometer …" again. This repeats on every
   restart. Android's restart back-off (about 1 s, 4 s, 16 s, 64 s …) fits "3–4 times in ~3 km".

**Certainty:** high that a repeat needs a new service instance, because the code allows no other path. Medium on *what*
killed the process on the Pixel. Likely candidates with a poor fix at the start: an exception in code on
`Dispatchers.Main` with no guard. The scope at `:110` has a `SupervisorJob` but no exception handler, so an uncaught
exception kills the process. These paths have no guard:
- the re-route result `navigator?.let { handle(it.onResult(job.id, out)) }` (`:643`), hit right after the start
  (FarStart / "Guide me" re-route 5 s after the start, see 3649b9cc);
- the ticker's `navigator?.onGpsLost()` (`:376`) and `publish()`.

Recording review M-x describes the same unguarded `onResult` / `take` paths. **Confirm** with the owner's diagnostics
export:
- "OnPath stopped unexpectedly" CRASH entries;
- several "Voice ready: …" lines (one per Speaker = one per service instance);
- several "Said: Los geht's …" traces with shrinking km.

Side issue (same cause, other direction): the guide is never reset between tours. Say End is followed by Start before
`onDestroy` (`finish()` → `stopSelf()`). The same instance then takes the new tour with `started = true` (no start
sentence) and with a cancelled `scope` (`finish()` calls `scope.cancel()`, so `startTicker()` never ticks).

## Minimal fix
1. `VoiceGuide`: add `fun continueTour() { started = true; lastVisited = null }`. It means "this tour already began; the
   start sentence is not news". Optionally add `fun newTour()` to reset everything (or make `voice` a `var` and
   replace it).
2. `RecordingService.onStartCommand`: in the `existing != null` branch (`:282`, sticky restart *and* Continue), call
   `voice.continueTour()`. In the new-tour branch (`:296`), use a fresh guide / `voice.newTour()`.
   *Exact variant* (keeps the sentence when the process died before the route was ever reached): persist
   `"voiceStarted": true` in the per-tour `trip-pace.json` (`saveTrip` `:1025`, keyed by `tourId`, deleted at
   `finish`). Call `saveTrip()` right after `voiceStep` returned a `Phrase.Start`. In `loadTrip` (`:1035`), if the flag
   is set, call `voice.continueTour()`.
3. Hardening (stops the restarts themselves): wrap `:643` in `try { … } catch (e: Exception) { Problems.error("navigation",
   "Re-route result could not be applied; the tour goes on", e) }`. Do the same for the ticker body (`:376`, `publish`).
   Or give `scope` a `CoroutineExceptionHandler` that reports to `Problems`.

## Test that reproduces it first (`VoiceGuideTest`)
```kotlin
/** Pixel 2026-11-01: "Los geht's" 3–4× in the first 3 km – the process died, the service came back with a fresh guide. */
@Test fun theStartSentenceIsNotSaidAgainWhenTheServiceContinuesTheTour() {
    val l = line(3000, emptyMap())
    val before = VoiceGuide()
    assertTrue(before.update(VoiceGuide.Input(0, l, 1, 0.0, onRoute = true, speedMps = 5.0, bike = true)) is Phrase.Start)
    // sticky restart: same tour, same line, a new guide
    val after = VoiceGuide().apply { continueTour() }
    val said = (1..120).mapNotNull { s ->
        after.update(VoiceGuide.Input(s * 1000L, l, 1, 10.0 + s * 5, onRoute = true, speedMps = 5.0, bike = true))
    }
    assertTrue("$said", said.none { it is Phrase.Start })
}
```
Before the fix, the test fails (it does not compile: `continueTour` is missing). With `VoiceGuide()` alone it says
Start twice, which is the bug. If the persisted variant is chosen, add a small test that `saveTrip` / `loadTrip`
round-trip `voiceStarted`. Optional on the emulator: a demo tour, `adb shell am crash com.onpath.app` after the start
sentence, then the problem log has exactly one "Said: Los geht's".

## Other "one-time" sentences – can they repeat?
| Sentence | Guard today | Can repeat? |
| --- | --- | --- |
| Start "Los geht's" | `started` (memory only) | **Yes, after every service restart** (above). Also never said for a 2nd tour in the same instance. |
| No GPS "Kein GPS-Signal" (`:126-129`) | `gpsSaid`, reset by **every** good fix | **Yes, no minimum interval**: lost 30 s → said, one good fix, lost 30 s → said again. That is typical with a poor fix at the start. Also said before the tour has started (indoors; review L2). Fix: only when `started`, and at most once per ~10 min (or reset `gpsSaid` only after ≥ 60 s of good fixes). |
| Off route "Abseits der Route…" (`:116`) | edge of `offRoute` | Yes, once per question opening. With jumping fixes the question can open and close again and again; this sentence skips the 10 s gap. After a restart a restored open question is said again (`lastOffRoute` = false). Fix: a minimum interval (~2 min) between two OffRoute sentences. |
| Back on route "Wieder auf der Route." (`:117-120`) | edge of `offRoute` closing | Yes, once per cycle (same flapping). There is also a false one after a plan edit while off route (`loadFollow` builds a Navigator with no prompt, review L1). Fix: drop it within ~1 min of the OffRoute sentence, and reset `lastOffRoute` on FOLLOW_CHANGED. |
| New route "Neue Route." (`:123`) | `!lastRerouted && started` | Rather the opposite: `session.rerouted` stays true after the first re-route, so a 2nd taken route is **never** said. It does not repeat. |
| Stop reached (`:137-146`) | diff of `visited` against the last fix | Once per stop index. A plan edit that renumbers stops (`afterPlanEdit`) can make a stop "new" again. Suspicion only. After a restart `lastVisited` = null, so it does not repeat. |
| Arrived "Ziel erreicht." (`ACTION_SAY`, `AppViewModel.kt:3669`) | `arrivalAsk` in the ViewModel only; the guide has no guard | Yes, each time `ArrivalGate` fires again after "Not yet". Also after the ViewModel or process is recreated. Only while the app is in Navigate. Fix: at most once per tour in the service (or once per ≥ 5 min). |
| No destination ahead (`AppViewModel.kt:1847`) | none | Once per edit that leaves nothing ahead. That is a state change, so it is acceptable. |
| Junction | `said` per line, cleared on every new line load (`:196`) | After every re-route / line version a junction that was already announced can be said again if it is still ahead. Mostly fine; with flapping re-routes at the start it adds repeats. |

Proposed general rule: keep the tour's voice memory (start said, last OffRoute / NoGps / Arrived time) with the tour,
not with the service instance. Give every "state" sentence a minimum interval (NoGps 10 min, OffRoute 2 min, Arrived
once per tour).
