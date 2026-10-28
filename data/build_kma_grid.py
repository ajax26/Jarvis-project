"""Builds kma_grid.json (region name -> KMA forecast grid) from the 기상청 격자_위경도 xlsx.

  python data/build_kma_grid.py      # rerun when 기상청 publishes a new 격자 file
"""

import glob
import json
from pathlib import Path

import openpyxl

here = Path(__file__).resolve().parent
src = sorted(glob.glob(str(here / "*격자_위경도*.xlsx")))[-1]
ws = openpyxl.load_workbook(src, read_only=True).worksheets[0]

rows = []
for r in ws.iter_rows(min_row=2, values_only=True):
    if r[0] != "kor" or not r[5]:
        continue
    # [시도, 시군구, 읍면동, nx, ny]
    rows.append([r[2] or "", r[3] or "", r[4] or "", int(r[5]), int(r[6])])

out = here / "kma_grid.json"
out.write_text(json.dumps(rows, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"{len(rows)} regions from {Path(src).name} -> {out.name} ({out.stat().st_size // 1024} KB)")
