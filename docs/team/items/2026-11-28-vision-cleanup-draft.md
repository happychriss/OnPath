# VISION cleanup – draft for the owner (captain, 2026-11-28)

Owner 08:23/08:24: split the vision into **what OnPath is** (direction) and **how the vision is used** (functions →
scenarios → check → build or ask); tidy it with his past decisions in mind. **Entered into `VISION.md` on the owner's word 2026-11-28 09:43** („ja, die vision so anpassen wie du gesagt hast“).

## What the owner's decisions show (looking back)
- 27.11. „keine einfache App“: powerful like established apps, clear UI – so the vision must **not** say „fewer functions“
  (the captain's 08:17 proposal „Fewer and simpler functions beat many“ is withdrawn: it contradicts that decision).
- 27.11. „zu kleinkariert“: detail rules do not belong in the vision; it gives the direction.
- 27.11. / 28.11.: the planner plans routes, the phone guides; longer and multi-day tours are planned in the browser.
- 28.11. „Tag 3–4“: the gap was not a missing feature rule but a missing **process** – a scenario nobody asked for was
  built on an assumption instead of checked and asked.
- Many questions of 27.11. were answerable by the vision („Vision first“) – the process part should say that too.

## Changes
000. **Final pass (captain, 09:30):** rule of thumb – nothing in the vision needs the words chip, box, button or row.
   The message rule and the list of dropped ideas left (both in „Decisions“); the flow reads „find today's tour, check
   it, go“; the network at the start is said honestly; „never controls of its own“ now says the truth (holding protects
   the tour and the plan); the browser line is one sentence.
00. **Owner 09:04 – the heart:** „Ich bin irgendwo. Das Wetter ist gut, die Landschaft ist toll. Was kann ich tun? Dann
   habe ich mein Handy und los geht's.“ The first paragraph is rewritten around **today's tour: finding, walking,
   keeping**; the unbounded „holds its own against established apps“ line left the first paragraph (Komoot stays the
   bar in „Powerful, clear and calm“).
0. **Owner 08:49 (the freedom thought):** „Überall, wo ich bin, kann ich mit meinem Handy loslegen“ – the split is not
   way / preparing but **where you are / planning ahead**; simple planning and a spontaneous loop belong on the phone.
   The test sentence and the browser line say so; the process rules moved to the captain's guide (owner 08:41).
1. **New last section „How the vision is used“** (process), and the two „aligns / resisted“ lines move into it.
2. **Direction instead of detail:** these lines leave the vision; they already stand in `docs/project-status.md`
   „Decisions“ (nothing is lost): hold-only editing · exactly six activities · field bug test first · public text tone ·
   dated comparisons · private material in the private repo · the owner's UI texts stay.
3. **Browser line** rewritten: longer tours are best planned in the browser so the phone stays simple.
4. **Consistency (owner 08:29 „Nordstern, keine Sammlung“, 08:31 „es ist richtig“):** one test sentence and one order of
   priority after the first paragraph; the unbounded „match established apps“ said three times is now said once, bounded
   (first paragraph: „where it matters on the way“; cut from the Komoot line; „need on the way“ in the aligns line); the
   browser line names the split phone = way, browser = preparing.
5. Everything else stays word for word, incl. the whole „Scope“ section (minus the private-repo line, see 2). The two
   „aligns / resisted“ lines stay the last two lines of the file (`CLAUDE.md` points at them).

## The draft (full text)

```
# Vision

OnPath is for the moment you are somewhere, the weather is good, the land is beautiful, and you want to go: you have your phone, and off you go.
It owns exactly one thing: today's tour – finding it, walking it, keeping it.
Finding: plan a route on the phone in a moment, take a tour you saved or loaded, or let the phone propose a loop from where you stand.
Walking: guided, re-planned on the way, also without reception, and the recording never breaks.
Keeping: saved at the end, to look back on and to show.
It serves people who decide on the day, on foot or by bike, on Android; a tour planned long ahead or over several days is prepared in the browser planner and reaches the phone one day at a time.
The test for everything: does it help someone who has only the phone to set off today, to change the plan on the way and to arrive?
When principles collide, this order decides: never lose a tour, then offline on the way, then clear and calm, then powerful.

## Never lose a tour

A recorded track is never lost to a dead zone, GPS loss, a phone call, an empty battery or a crash.
Every point is saved at once, and a recording ends only on purpose.
Changing the plan never ends the recording.
Missing maps, data or network never crash the app; the old map stays until the new one is complete.
When robustness and a new feature get in each other's way, robustness wins.
A change to recording, navigation or the map ships only after it has run on a real tour; other changes may ship with green tests.

## Change it on the way, offline too

Planning, re-planning, search and navigation run on the phone in loaded areas, without a server.
The maps for a plan load by themselves while you plan; nobody has to manage regions.
Setting off in a new area needs the network once; a map that has to be built first takes a few minutes, and the app says so.
The network may help with downloads, online search and optional pairing, but the running tour never needs it.
Offline is promised honestly: only for fully loaded areas.
Everything you need to set off and on the tour works on the phone, simply and quickly; the browser planner adds what planning ahead needs, such as multi-day tours or 3D, so the phone stays simple.
In the main controls, app and planner behave the same and look as alike as they can, unless something else was decided on purpose; the planner plans routes, so ridden tours and ridden days stay on the phone.

## No surprises

A new route comes only after asking.
Nothing flashes, and nothing that waits for your answer disappears by itself.
What Plan shows is exactly what Navigate follows.

## Powerful, clear and calm

The flow is: find today's tour, check it, go.
Komoot is the bar for ease of use and the map.
Complex functions are welcome; the work is to keep them intuitive and clear, so the basic flow stays short and the screen stays calm.
The screen shows only what the hiker needs right now; extra information that brings nothing at the moment stays away.
OnPath is made for normal and experienced hikers, not for gadget or app enthusiasts: no technical gimmicks for their own sake; a feature earns its place by what it does for planning or on the tour.
Texts state facts, not guesses.
Every text exists in German and English, with the same meaning.
The app looks modern, clear and friendly, never technical, also where the function behind it is complex, and not in the style of navigation or hiking apps.
Its base is the platform standard of today's Apple and Android apps, as users expect it, so the threshold to use it stays low; its own character comes from colour and type; a control of its own exists only where it protects the tour or the plan, such as holding instead of tapping.
After the tour you save and put the phone away, with no prompts for photos, sharing or ratings.

## Open, free, no account

The code is public under Apache-2.0, and the maps come from OpenStreetMap.
Planning, navigating, recording and pairing phone and browser stay free and need no OnPath account.
There are no ads, no analytics and no Google Play Services; Google content is never stored or drawn.
Tours belong to the user: GPX in and out, backups and a recycle bin.
Later paid extras cover running costs, development work and a donation, shown openly, and take nothing from the free core.
A paid extra may need an account, for example one that backs up your tours; pairing stays the free way without an account, with its limits.

## Scope

OnPath is not a social network: no profile, no feed, no followers, no leaderboards.
Sharing your location sends it one way to people you choose, with no groups and no shared map of each other.
For browsing, OnPath shows only signposted trails from OpenStreetMap, never other users' tours, photos or ratings.
Android comes first; the iPhone comes only after the public launch, and until then only the core stays free of Android.

## How the vision is used

How a function and its scenarios are checked against this vision is described in `docs/team/captains-guide.md` §4 (reusable, not about this product).
A change aligns when it makes the outing more reliable, the plan easier to change on the way or OnPath stronger than established apps in something hikers need on the way, works offline in loaded areas, and keeps the three steps as short and the screen as clear as they are.
A change should be resisted when it could lose a point, surprises the user, needs an account or a server for the running tour, adds social or catalogue features, is technology for its own sake, or makes the way in longer.
```
