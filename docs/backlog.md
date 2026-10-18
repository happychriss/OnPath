# Backlog (shared by laptop and cloud Claude)

Rules: see `docs/working-agreement.md`. Claim an item under "In progress" before working on it (one line,
push immediately), remove it when done. Newest requests on top. Owner decisions are marked **(owner)**.

## In progress
<!-- - [laptop|cloud] short description – since YYYY-MM-DD -->

## Next
- Release 0.4.1 with the laptop's latest changes (SAC labels, POI tap toggles route point) – also the first real
  test of the in-app update button.
- Google Play internal test track: create app in Play Console, secret release key in GitHub secrets
  (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`,
  `PLAY_SERVICE_ACCOUNT_JSON`), upload from the release workflow. Removes Play Protect warnings. **(owner has a
  developer account)**

## Later
- Hetzner: static hosting of region packages + monthly `tools/build_region.sh` run; in-app region download.
- Worldwide use outside prepared regions: stream OpenFreeMap + AWS terrain, auto-download BRouter tiles.
- Reroute back to the route after an off-route warning (offline).
- Keep pinned POI stops when a saved tour is reopened.
- German UI strings (`strings.xml`, EN + DE).
- Tune difficulty thresholds (Easy / Moderate / Difficult) with real tours.
- Route warnings list (steep, push bike, steps, terrain above T level).
- Reorder route points in a list.
- Verify data package checksums on import (manifest.json).
- Delete the temporary `downloads` branch on GitHub **(owner – sessions cannot delete branches)**.

## Done (recent)
- 0.4.0: in-app updates via GitHub Releases, release workflow.
- 0.3.0: trail names + AVS numbers, contrast pass + high-contrast theme, stronger railways/stations,
  map data import on the phone, Clear with confirmation, map pipeline script + docs.
- Parking POIs: off by default (optional in Settings).
