# Debugging "it won't place / won't sync"

When DF refuses code that looks fine — a line that won't deploy, fill, or place by hand —
follow this method. It comes from the scythe-particle hunt, which took **three sessions**:
the first two failed by **theorizing instead of testing** (a "phantom action" theory, then a
"deploy drops lines, just re-place them" theory — both wrong). The third solved it by gathering
hard evidence, getting the one fact only the human could see, and building an isolation repro.

The meta-lesson: **never reason past an unknown.** If a single fact would split your
hypotheses, go get that fact before writing another paragraph of theory.

---

## The method (in order)

### 1. Nail the exact symptom — distinguish the failure modes
"It won't place" is several different bugs. Pin down which:

- **Does it fail BY HAND too?** Manual placement (hold the template item, right-click an empty
  codespace spot) **bypasses the mod entirely** — DF reads the item NBT directly. So:
  - fails on deploy/fill but **hand-places fine** → the problem is the **mod/layout/deploy** path.
  - **hand-place also fails** → the problem is the **template content** (or DF plot state).
- **What does DF actually SAY?** This is the highest-signal fact:
  - `"you already have a function/process with that name"` → it's a **duplicate** (already on the plot).
  - `"plot is full"` / `"too many code blocks"` → **capacity**.
  - `"Invalid template placement"` → off-plot / **too long** for the plot.
  - **nothing at all, no message** → DF silently refused: either a **duplicate** (function already
    exists) or the template is **malformed in a way DF rejects wholesale** (e.g. a particle with
    empty data — see `compile-errors.md` `PARTICLE_NO_DATA`).
- **Is it actually missing, or already there?** Ask the human to look. "Genuinely absent" vs
  "present but the scan can't see it" are opposite bugs with opposite fixes.

### 2. Clear the codec first (cheap, local, no game needed)
Rule the tool out before blaming the game:

- **Decode the real template** and eyeball its structure (block list, header, the suspect value).
- **Identity round-trip**: `source → recompile → encode → decode → identify` must return the same
  `id` both ways. If it does, the codec isn't mis-reading the line.
- **Placed length vs plot size**: `length = blocks×2 + brackets×1`; compare to the plot's
  `codeLength` (BASIC 50 / LARGE 100 / MASSIVE·MEGA 300). Rules out "too long".

If template, identity, and length are all clean, **it's not the codec** — stop suspecting it.

### 3. Get the ground truth you can't see
There's always one fact only the human (or the live plot) can provide — what DF says, whether the
line is physically there, the plot size. **Ask for exactly that one fact** (a focused question or
a 10-second in-game check). The first two sessions burned themselves by guessing here.

### 4. Isolate with a repro project
Make a fresh project: the **suspect lines** + **control lines that each vary ONE thing**. Deploy
to a **scratch plot** (deploy clears it). The pattern of *what places vs. what fails* names the
cause far faster than reading code. Design controls so each isolates a single variable (a flag, a
value type, one action), plus a trivial **baseline** that must place (if it doesn't, the deploy
itself is broken) and, once you have a hypothesis, a **fixed variant** to prove the cure.

### 5. Confirm the fix with the same repro
Apply the candidate fix to the repro copies but **keep one negative control broken**. Redeploy:
the fixed lines place, the broken control still fails. That's the before/after proof — *then*
touch the real project, not before.

### 6. Blast radius + a durable guard
- **Scan the whole project** for the same pattern — the bug is rarely in just the lines you noticed
  (the scythe bug was in 3 files; the user only knew about 2).
- **Add a validator check** so it can never silently recur (`df_validate.py` → `dfpy check`/`validate`,
  and the mod surfaces ERROR-level issues before deploy). Drive it from `actiondump.json` so it's
  authoritative, not guesswork.

---

## Worked example: the scythe Soul particle

**Symptom.** Two anarchy funcs (`scythe damage`, `player 3 tick loop`) wouldn't deploy, fill, or
**hand-place** — right-click did *nothing*, no chat message. Plot is MASSIVE.

**Wrong trails (rejected).** (a) "CombatAttribute is a phantom action" — it's in `actiondump.json`,
valid; and `scythe damage` doesn't even use it. (b) "deploy silently drops lines, re-deploy
`-batch 20` / hand-place them" — can't be right, because **hand-placing also did nothing**.

**Decisive facts gathered.**
- Decoded the real failed item → byte-valid 22-block func; identity round-trips; length 34 < 300.
  → **not the codec, not length.**
- Asked the human: *what does DF say on hand-place?* → **"nothing, no message."** Plot = **MASSIVE**.
- Asked: *are they physically on the plot?* → **no.** → genuinely absent + valid template + silent
  refusal ⇒ the **template content** is the culprit.

**Isolation repro** (`plots/placetest/`): the 2 suspects + controls — baseline, `hint=False`,
Soul particle alone, event-values-in-a-func, scythe-content-renamed. Deploy result:

| Placed ✅ | Failed ❌ |
|---|---|
| ctrl simple, ctrl nohint, ctrl eventfunc | **ctrl particle** (Soul, nothing else), ctrl scythe clone, scythe damage, player 3 tick loop |

Every failure had a `PlayerAction('Particle', raw_item('part', …))`; every success lacked one. And
`ctrl particle` — *just* a func + one Soul particle — failed. `hint=False` and event-values-in-func
were cleared (they placed).

**Root cause.** The Soul particle had **empty `data: {}`**. Comparing to a native plot scan
(`.df-backups/*-pull.txt` = raw scan tokens): native Soul particles carry
`data:{x,y,z,motionVariation}`; only genuinely dataless particles (Explosion, Ash, Lava, Barrier…)
have `{}`. `actiondump.json` confirms it authoritatively — `SOUL.fields == ["Motion","Motion
Variation"]`. **A particle with required fields but empty data makes DF silently refuse the whole
line.** It was introduced when a prior session *recoded* anarchy.

**Confirm.** Populated the data on the repro copies, left `ctrl particle` broken → redeploy placed
**7/8**, only the broken control failed.

**Fix + guard.** Set `data:{x:0,y:3,z:0,motionVariation:100}` in the **3** affected files
(`scythe_damage`, `player_3_tick_loop`, `death_scythe` — the third was the silent one). Added
`PARTICLE_NO_DATA` (ERROR) to `df_validate.py`, driven by actiondump's per-particle `fields`, so
`dfpy check` and the mod catch this class before it reaches the game.

---

## Diagnostic snippets (copy-paste)

**Decode a live template item / token:**
```python
import sys, base64, gzip, json; sys.path.insert(0, "codec")
import df_codec as C
tpl = C.decode_line(token)                 # token = the base64 gzip string
print(C.identify(tpl))                      # {type,key,id,file}
for i, b in enumerate(tpl["blocks"]):
    print(i, b.get("id"), b.get("block",""), b.get("action",""), b.get("data",""))
```

**Identity round-trip (does place→scan keep the identity?):**
```python
tpl = C.recompile(open(path, encoding="utf-8").read())
print(C.identify(tpl)["id"] == C.identify(C.decode_line(C.encode_line(tpl)))["id"])
```

**Compare against NATIVE (what DF really produces).** Backups in `plots/.df-backups/*-pull.txt`
are **raw scan tokens** — decode them to see the game's own structure for a value:
```python
for ln in open("plots/.df-backups/<name>-pull.txt", encoding="utf-8"):
    d = json.loads(gzip.decompress(base64.b64decode(ln.strip())))
    # …walk d["blocks"][*]["args"]["items"][*]["item"] and compare to your output…
```

**actiondump = source of truth.** It carries authoritative metadata — e.g. each particle's
required `fields`:
```python
d = json.load(open("codec/actiondump.json", encoding="utf-8"))
print({p["particle"]: p["fields"] for p in d["particles"] if p["fields"]})
```

**Scan a whole project for a structural pattern** (find the real blast radius):
```python
import glob, os
for fn in glob.glob("plots/<project>/*.py"):
    tpl = C.recompile(open(fn, encoding="utf-8").read())
    for b in tpl["blocks"]:
        for it in b.get("args", {}).get("items", []):
            # …flag the bad shape, print os.path.basename(fn)…
            pass
```

See also: `compile-errors.md` (the validator catalog, incl. `PARTICLE_NO_DATA`),
`plot-limits-and-geometry.md` (length/size), `../SETUP.md` (`/df deploy|fill|push` behavior).
