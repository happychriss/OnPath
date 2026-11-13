#!/usr/bin/env python3
"""Tour-suggestions POC: the app's BRouter profiles + the POC's small additions, written to a build folder.

  make_profiles.py <app profile dir> <out dir>

The app's files stay the source (copied fresh at every service start); the additions are the few lines below, so the
difference to the app is readable in one place. Today one addition, hiking only:

  mainroad_penalty (global, default 0 = exactly the app's profile): extra cost factor for walking ALONG a busy road –
  on the road itself (primary 1.0, secondary 0.8, tertiary 0.5 of it) and on a pavement next to one
  (footway=sidewalk with noise class 4–6: 0.7, 2–3: 0.3). A parallel residential street stays free, so the router
  dodges by itself. The value comes per request from weights.json (routing.mainroad_penalty_*).
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
    else 0.0 )
""", 1)
open(p, "w", encoding="utf-8").write(s)
