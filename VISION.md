# Vision

OnPath exists so that people on a hike or bike ride can change their plan along the way, even without reception, without the recording stopping.
It serves people who plan their own tours, on foot or by bike, from an after-work loop to a multi-day alpine tour, on Android.
From a destination, OnPath makes a checked route, guides you there and keeps the tour.
In function it holds its own against established apps and goes beyond them where it matters on the way: re-planning offline, a recording that never breaks, multi-day tours.
It owns exactly one thing: your own plan for today's outing, from the destination to the saved tour.

## Never lose a tour

A recorded track is never lost to a dead zone, GPS loss, a phone call, an empty battery or a crash.
Every point is saved at once, and a recording ends only on purpose.
Changing the plan never ends the recording.
Missing maps, data or network never crash the app; the old map stays until the new one is complete.
A field bug in a core feature first gets a test that reproduces it, then the fix.
When robustness and a new feature get in each other's way, robustness wins.
A change to recording, navigation or the map ships only after it has run on a real tour; other changes may ship with green tests.

## Change it on the way, offline too

Planning, re-planning, search and navigation run on the phone in loaded areas, without a server.
The maps for a plan load by themselves while you plan; nobody has to manage regions.
The network may help with downloads, online search and optional pairing, but the running tour never needs it.
Offline is promised honestly: only for fully loaded areas.
Everything you need on the tour works on the phone; the browser planner may add things for preparing and previewing, such as creating a multi-day tour or 3D.
In the main controls, app and planner behave the same and look as alike as they can, unless something else was decided on purpose.

## No surprises

In planning you edit only by holding, with no quick-tap actions and no dragging of points.
A new route comes only after asking.
Nothing with a button disappears by itself and nothing flashes; a message stays until it is answered or the situation is gone.
Only a short information line without a button may go by itself after a few seconds.
What Plan shows is exactly what Navigate follows.
A UI text the owner chose stays; a change to it is proposed as a question.

## Powerful, clear and calm

The flow is: choose a destination, check the route, go.
Komoot is the bar for ease of use and the look of the map; in function OnPath aims to match established apps and to go beyond them in single areas.
Complex functions are welcome; the work is to keep them intuitive and clear, so the basic flow stays short and the screen stays calm.
The screen shows only what the hiker needs right now; extra information that brings nothing at the moment stays away.
OnPath is made for normal and experienced hikers, not for gadget or app enthusiasts: no technical gimmicks for their own sake; a feature earns its place by what it does for planning or on the tour.
Texts state facts, not guesses.
The app looks modern, clear and friendly, never technical, also where the function behind it is complex, and not in the style of navigation or hiking apps.
Its base is the platform standard of today's Apple and Android apps, as users expect it, so the threshold to use it stays low; its own character comes only from colour and type, never from controls of its own.
There are exactly six activities.
After the tour you save and put the phone away, with no prompts for photos, sharing or ratings.
Public texts say plainly what happens, without superlatives and without running down other apps.
A dated, sourced comparison with other apps is fair, even where it shows what they lack.

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
Weather, sunset warnings, an emergency screen and a battery check at the start are deliberately left out; weather may return one day only as a paid extra.
Android comes first; the iPhone comes only after the public launch, and until then only the core stays free of Android.
Marketing, private tour data and internal planning live only in the private repository.

A change aligns when it makes the outing more reliable, the plan easier to change on the way or OnPath stronger than established apps in something hikers use, works offline in loaded areas, and keeps the three steps as short and the screen as clear as they are.
A change should be resisted when it could lose a point, surprises the user, needs an account or a server for the running tour, adds social or catalogue features, is technology for its own sake, or makes the way in longer.
