"""Crops the empty map above the sheet from the Paparazzi pictures and copies them to pics/ (run from the repo root)."""
import os
from PIL import Image
import numpy as np

SRC = 'app/src/test/snapshots/images/com.onpath.app.ui.dayrows_'
OUT = 'docs/sketches/2026-11-21-day-rows/pics/'
names = {
    'DayRowsSketchGermanTest_today': 'app-heute', 'DayRowsSketchGermanTest_a_valuesRight': 'app-a',
    'DayRowsSketchGermanTest_b_valuesBeside': 'app-b', 'DayRowsSketchLargeFontGermanTest_today': 'app-gross-heute',
    'DayRowsSketchLargeFontGermanTest_a_valuesRight': 'app-gross-a', 'DayRowsSketchLargeFontGermanTest_b_valuesBeside': 'app-gross-b',
}
os.makedirs(OUT, exist_ok=True)
for k, v in names.items():
    im = Image.open(SRC + k + '.png').convert('RGB')
    a = np.array(im)
    col = a[:, im.width // 2].astype(int)
    top = next(y for y in range(im.height) if col[y][2] > col[y][0] + 40 and col[y][0] < 80)  # the sheet's blue top edge
    im.crop((0, max(0, top - 24), im.width, im.height)).save(OUT + v + '.png')
    print(v, im.size, top)
