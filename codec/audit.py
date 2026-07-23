"""Audit a pulled project for codec health.

Two checks per .py file:
  1. SELF-CONSISTENCY (the real test of the codec): recompile -> T, then
     decompile(T) -> py2, recompile(py2) -> T2. canon(T) must equal canon(T2).
     If it holds for every template, then a fresh pull of the real plot will
     round-trip losslessly. We have all data needed for this, no plot required.
  2. DRIFT vs original (informational): canon_hash(T) vs the .df-hashes.json
     baseline written at pull time. A diff means the OLD decompiler that wrote
     these files was lossy for that line (a re-pull with the new codec fixes it).

Usage:  python audit.py [projectDir] [--diff]
"""
import sys, json, pathlib
sys.stdout.reconfigure(encoding="utf-8")
import df_codec as C

args = [a for a in sys.argv[1:] if not a.startswith("--")]
show_diff = "--diff" in sys.argv
proj = pathlib.Path(args[0] if args else C.HERE.parent / "plots" / "anarchy")
hashes = json.loads((proj / ".df-hashes.json").read_text(encoding="utf-8")) if (proj / ".df-hashes.json").exists() else {}

crash, noniso, drift, ok = [], [], 0, 0
for p in sorted(proj.glob("*.py")):
    src = p.read_text(encoding="utf-8-sig")
    try:
        t = C.recompile(src)
        t2 = C.recompile(C.decompile(t))
    except Exception as e:
        crash.append((p.name, f"{type(e).__name__}: {e}"))
        continue
    ct = C.canon(t)
    if ct != C.canon(t2):
        noniso.append((p.name, t, t2))
    else:
        ok += 1
    if hashes.get(p.name) and C.canon_hash(t) != hashes[p.name]:
        drift += 1

total = ok + len(noniso) + len(crash)
print(f"=== {proj.name}: {total} files | self-consistent {ok} | NON-ISO {len(noniso)} | CRASH {len(crash)} | (drift-vs-original {drift}) ===")

if crash:
    from collections import Counter
    print("\n-- CRASHES --")
    kinds = Counter(e.split(" on ")[0] if " on " in e else e.split(":")[0] for _, e in crash)
    for k, n in kinds.most_common():
        print(f"  [{n:3}] {k}")
    for n, e in crash[:15]:
        print(f"    {n:44} {e[:80]}")

if noniso:
    print("\n-- NON-IDEMPOTENT (codec round-trip bug) --")
    for n, t, t2 in noniso[:12]:
        print(f"    {n}")
    if show_diff and noniso:
        import itertools
        n, t, t2 = noniso[0]
        print(f"\n  first diff in {n}:")
        a, b = json.loads(C.canon(t))["blocks"], json.loads(C.canon(t2))["blocks"]
        for i, (x, y) in enumerate(itertools.zip_longest(a, b)):
            if x != y:
                print(f"    block[{i}] T : {json.dumps(x)[:240]}")
                print(f"    block[{i}] T2: {json.dumps(y)[:240]}")
                break

sys.exit(1 if crash or noniso else 0)
