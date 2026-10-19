# Style proposals (one map style, fine and high-contrast)

Owner request 2026-10-19: replace "classic" + "high-contrast" by one style where green routes and black paths are
always clear, at every zoom. Three candidates (themes extend `classic` / `high-contrast`, not built into the app):

- `a-fein` – lighter forest, vivid route green, white edge up to z13, thin green edge above; paths a bit darker/wider.
- `b-rand` – route band with a dark-green edge; black paths with a white casing from z12.
- `c-sonne` – very light areas, reduced relief, pure black wide paths, dark-edged band.

Comparison (z11–16, Algund / Ulten / Meran 2000): https://claude.ai/artifact/S46QZFW8BpfDYCVjAxpa9a

To adopt one: copy its tokens into `tools/themes/classic.json`, delete `high-contrast.json` (+ the theme switch in
Settings / `StyleLoader`), add `"-edge"` to the route layer suffixes in `MapController.setOverlay`, run
`python3 tools/make_style.py --all`, check FontGlyphsTest.

## d-final (owner pick, 2026-10-19)
`d-final.json` + `d-final-post.py` (post-processing of the generated style: own exact paths, track variant A,
symbol layers, 40 % route band). Rationale and numbers: `docs/design/map-colour-analysis.md`. A/B/C above were
rejected (no band edges, no white).
