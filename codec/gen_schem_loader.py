"""One-off generator: litematica .litematic -> DF VIBE loader code in plots/sputt-loader/.

Reads the schematic, greedy-meshes ALL cells - air included - into disjoint boxes (grouped
by block state) and bakes them as `local` list literals across several data-functions plus
a `schemPlace` loop that writes each box as a block TRANSACTION (batched under the 1M cap
by in-stream 'A' apply markers). Loads on plot startup / @load. The shared codegen lives in
schem_gen.py (also used by `dfpy.py genschem`, which additionally captures signs/heads/chests
this litematic path can't see).

Run with:  py -3.11 codec/gen_schem_loader.py
"""
import os, json
import numpy as np
from litemapy import Schematic

import schem_gen as G

SRC = r"PATH\TO\your.litematic"      # set to your exported litematica schematic
OUT = r"plots\sputt-loader"          # relative to the project root; run this from there

s = Schematic.load(SRC)
reg = list(s.regions.values())[0]
W, H, L = reg.width, reg.height, reg.length
x0, y0, z0 = min(reg.range_x()), min(reg.range_y()), min(reg.range_z())

keymap = {}
keylist = []
grid = np.zeros((W, H, L), dtype=np.int32)
for x in reg.range_x():
    for y in reg.range_y():
        for z in reg.range_z():
            b = reg[x, y, z]
            if b.id == "minecraft:air":
                continue
            kk = (b.id, tuple(sorted(dict(b.properties()).items())))
            if kk not in keymap:
                keymap[kk] = len(keylist) + 1
                keylist.append(kk)
            grid[x - x0, y - y0, z - z0] = keymap[kk]

boxes = G.split_large(G.greedy(grid))   # >100k boxes would overflow SetRegion / txn budgeting
plain, dataB = G.split_boxes(boxes, keylist)
air = G.mesh_air(grid)                  # air is written too (literal-arg txns) - disjoint, no race
ac = G.chunk(G.insert_apply_markers(air))
pc = G.chunk([e for e, _ in plain])
dc = G.chunk([e for e, _ in dataB])
print(f"boxes={len(boxes)} air={len(air)} plain={len(plain)} data={len(dataB)}")
print(f"air chunks={len(ac)} plain chunks={len(pc)} data chunks={len(dc)}")

os.makedirs(OUT, exist_ok=True)
af, anames = G.emit_data_funcs(ac, 'airBoxes', 'schemAir')
pf, pnames = G.emit_data_funcs(pc, 'plainBoxes', 'schemPlain')
df, dnames = G.emit_data_funcs(dc, 'dataBoxes', 'schemData')
files = list(af) + list(pf) + list(df)
files.append(("func__schemPlace.py", G.build_place(bool(ac), bool(pc), bool(dc))))
files.append(("func__waitCPU.py", G.WAITCPU))
files.append(("func__waitTxn.py", G.WAITTXN))
files.append(("func__schemLoad.py", G.build_load_func(anames + pnames + dnames + ['schemPlace'], [])))
files.append(("game_event__PlotStartup.py", G.build_startup_event()))
files.append(("game_event__LagSlayRecover.py", G.build_recover_event()))
files.append(("event__Command.py", G.build_event(anames + pnames + dnames + ['schemPlace'], [], (W, H, L))))
for fn, src in files:
    with open(os.path.join(OUT, fn), "w", encoding="utf-8") as fh:
        fh.write(src)

with open(os.path.join(OUT, ".df-vibe.json"), "w", encoding="utf-8") as fh:
    json.dump({"project": "sputt-loader", "plotLength": 300, "codecVersion": 3}, fh)

print(f"data funcs: plain={len(pnames)} data={len(dnames)}  total files={len(files) + 1}")
