#!/usr/bin/env python3
"""Tour-suggestions POC: the app's BRouter profiles + the POC's small additions, written to a build folder.

  make_profiles.py <app profile dir> <out dir>

The app's files stay the source (copied fresh at every service start); the additions are the few lines below, so the
difference to the app is readable in one place. One addition:

  mainroad_penalty (global, default 0 = exactly the app's profile): extra cost factor for going ALONG a busy road.
  Hiking: on the road itself (primary 1.0, secondary 0.8, tertiary 0.5 of it) and on a pavement next to one
  (footway=sidewalk with noise class 4–6: 0.7, 2–3: 0.3).
  Bike / Gravel (touring.brf, trekking.brf): on the road itself (primary 1.0, secondary 1.0, tertiary 0.8), x 0.3 on a
  30 km/h road, x 0.5 with a cycle track / x 0.75 with a cycle lane, x 0.4 on a bridge (often the only crossing); a
  separate cycle path beside a loud road (noise class 4–6) 0.4. mtb.brf is not changed.
  Along a road is always bad, signed route or not (owner): any separate way beside a busy road (cycle path,
  shared path, footway, track) costs by the noise class – bikes 0.35 / 0.7 / 1.0 for class 4 / 5 / 6, hiking 0.25 / 0.5 / 0.7.
  A parallel residential street or a path away from traffic stays free, so the router dodges by itself. The value
  comes per request (character.py: mainroad_penalty).
"""
import os
import shutil
import sys

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for f in os.listdir(src):
    shutil.copy(os.path.join(src, f), os.path.join(dst, f))

p = os.path.join(dst, "hiking-mountain.brf")
s = open(p, encoding="utf-8").read()
a = "assign   shortest_way             0"
b = "assign trafficpenalty = 0\n"
if a not in s or b not in s:
    sys.exit("make_profiles: hiking-mountain.brf changed – anchors not found")
s = s.replace(a, "assign   mainroad_penalty         0    # POC: extra cost along busy roads\n" + a, 1)
s = s.replace(b, """assign trafficpenalty =
  multiply mainroad_penalty
  ( if highway=trunk|trunk_link|primary|primary_link then 1.0
    else if highway=secondary|secondary_link then 0.8
    else if highway=tertiary|tertiary_link then 0.5
    else if footway=sidewalk then ( if estimated_noise_class=4|5|6 then 0.7 else if estimated_noise_class=2|3 then 0.3 else 0.0 )
    else if highway=path|footway|cycleway|track|pedestrian then ( if estimated_noise_class=6 then 0.7 else if estimated_noise_class=5 then 0.5 else if estimated_noise_class=4 then 0.25 else 0.0 )
    else 0.0 )
""", 1)
open(p, "w", encoding="utf-8").write(s)

# ---- bike profiles: the same idea for riding along a busy road
BIKE = """assign mainroad_cost =
  multiply mainroad_penalty
  multiply ( if bridge=yes then 0.4 else 1.0 )
  ( if highway=cycleway|path|footway|track|pedestrian then ( if estimated_noise_class=6 then 1.0 else if estimated_noise_class=5 then 0.7 else if estimated_noise_class=4 then 0.35 else 0.0 )
    else multiply ( if maxspeed=10|20|30 then 0.3 else 1.0 )
         multiply ( if ( or cycleway=track or cycleway:right=track or cycleway:left=track or cycleway:both=track bicycle=use_sidepath ) then 0.5
                    else if ( or cycleway=lane or cycleway:right=lane or cycleway:left=lane cycleway:both=lane ) then 0.75 else 1.0 )
         ( if highway=trunk|trunk_link|primary|primary_link|secondary|secondary_link then 1.0
           else if highway=tertiary|tertiary_link then 0.8
           else 0.0 ) )

"""
for name in ("touring.brf", "trekking.brf"):
    p = os.path.join(dst, name)
    s = open(p, encoding="utf-8").read()
    g = "assign   consider_traffic "
    c = "assign costfactor\n"
    t = "  add traffic_penalty\n"
    if s.count(g) != 1 or s.count(c) != 1 or s.count(t) != 1:
        sys.exit("make_profiles: %s changed – anchors not found" % name)
    s = s.replace(g, "assign   mainroad_penalty         = 0      # POC: extra cost along busy roads\n" + g, 1)
    s = s.replace(c, BIKE + c, 1)
    s = s.replace(t, t + "  add mainroad_cost\n", 1)
    open(p, "w", encoding="utf-8").write(s)
