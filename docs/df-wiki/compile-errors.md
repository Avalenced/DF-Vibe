# Compile / validation errors

DiamondFire doesn't "compile" the way a normal language does — a malformed code line is
placed anyway and just **silently misbehaves** (a typo'd action becomes a dead block, a
bad var scope reads empty, an over-long line won't place at all). So the codec ships a
**validator** that catches these *before* they reach the plot.

## Run it

```
python codec/dfpy.py check  plots/<proj>/<file>.py    # one or more files (+ semantic checks)
python codec/dfpy.py validate plots/<proj>            # whole project (+ cross-line checks)
python codec/dfpy.py check file.py --plot-length 100  # override the plot's code-line limit
```

`check` first confirms the file **compiles** (recompiles to a template), then validates it.
Exit code is non-zero if anything **fails to compile** or has an **ERROR**. WARNs print but
don't fail the build. Every name problem comes with a `— did you mean 'X'?` suggestion.

- **ERROR** = DiamondFire genuinely can't represent it; fix before pushing.
- **WARN** = almost certainly wrong, but *could* be legacy/intentional — read and judge.

## What it catches

> **"Chest" = a code block's parameter container.** In DiamondFire you right-click a code
> block to open a chest GUI and drop "value items" (a `num`, `txt`, `var`, `item`, …) into its
> slots — those are the block's arguments. In a `.py` file that's everything inside the call's
> parentheses. The chest has **27 slots (0–26)**, same as a single Minecraft chest.

### Structure (ERROR)
- **NO_HEADER / HEADER_NOT_FIRST / EXTRA_HEADER** — every code line must start with exactly
  one header (`event`/`entity_event`/`game_event`/`func`/`process`), and it must be first.
- **CHEST_OVERFLOW / DUP_SLOT / BAD_SLOT** — more than 27 items in a block's chest, two items
  in one slot, or a slot outside 0–26 = malformed. (Split the action, or move data into a
  list/variable.) Note: `item` here is a *type of value* (a Minecraft item like a sword);
  it is not the chest — the chest is the container holding all the values.
- **TOO_LONG** — a line's placed length (`blocks×2 + brackets×1`) exceeds the plot's
  code-length limit (**Basic 50, Large 100, Massive/Mega 300**). DiamondFire rejects it and
  the placer skips it. Split the line into smaller functions. See `plot-limits-and-geometry.md`.
  `check`/`validate` use the `plotLength` the mod records in the project's `.df-vibe.json`
  at pull/push; before the first pull (or outside a project dir) they assume 300 (Massive) —
  pass `--plot-length 100` on a **Large** plot then, or over-long lines slip through.

### Names (WARN, with suggestion)
- **BAD_ACTION** — the action isn't real for that block type (usually a typo:
  `SendMesage`→`SendMessage`). Names are **verbatim**; grep `action-reference.md`.
- **BAD_TAG / BAD_TAG_OPTION** — the tag (or its option value) isn't valid for that action.
- **BAD_GAMEVALUE / BAD_SOUND / BAD_POTION** — unknown `gval` / `snd` / `pot` name.
- **BAD_SUBACTION** — the condition on a conditional *Select Object* / *Repeat While* isn't
  real. (DF stores these bare — `IsType` — or prefixed `P`/`E`/`G` when the same condition
  exists on multiple block types: `PIsNear` vs `EIsNear`.)
- **RANK_LOCKED** — the action needs a DiamondFire rank higher than yours, and DF **silently
  refuses to place it** (no chat message). Off by default; enable by passing `--rank
  Noble|Emperor|Mythic|Overlord` to `check`/`validate`, or set `/df config rank …` in-game
  (the mod stamps it into the project's `.df-vibe.json`, so `check`/`validate` pick it up
  automatically). Rank ladder: Noble < Emperor < Mythic < Overlord; an action's `requiredRank`
  in `actiondump.json` is the lowest rank that can place it.

### Settings & values (ERROR for scope, else WARN)
- **BAD_SCOPE** (ERROR) — a variable scope other than `line` / `local` / `unsaved` / `saved`.
  There is no "global" or "save" scope. See `variables-and-data.md`.
- **BAD_PARAM_TYPE** — a `param()` type that isn't one of `txt comp num loc vec snd part pot
  item any var list dict` (e.g. `'number'` instead of `'num'`).
- **MISSING_TAG** — a block missing a tag it needs: `func`/`process` need `Is Hidden`;
  `start_process` needs `Local Variables` + `Target Mode`. **The builder does NOT add these —
  you must pass them** (`tags={…}`). Omit them and in-game DF shows "invalid chest
  parameters!" and **the thread halts** (see `quirks.md`). (Real example caught: `kitpvp`'s
  StartProcess blocks were missing `Local Variables`.)
- **BAD_NUM** — `num('abc')`: not numeric and no `%placeholder` → reads as 0. (A `%math`
  expression or `%var` is fine.)
- **BAD_PITCH** — sound pitch outside `0.0–2.0`.
- **PARTICLE_NO_DATA** (ERROR) — a particle that requires data fields (per actiondump, e.g.
  `Soul`/`Flame`/`Sculk Soul`/`Dust` need `Motion`+`Motion Variation`, material/coloured ones
  need `Material`/`Color`/`Size`…) but whose `data` is empty `{}`. **DiamondFire silently
  refuses to place the entire line** — no chat message; deploy/fill/hand-place all no-op. A real
  Soul is `data:{x,y,z,motionVariation}`. Only genuinely dataless particles (Explosion, Ash,
  Lava, Barrier, Shriek…) may have `{}`. (Real example: anarchy's scythe funcs, where a recode
  wrote empty-data Soul particles — every one was un-placeable until the data was populated.)

### Logic (WARN)
- **TIGHT_LOOP** — a `Repeat('Forever'/'While'/'DoWhile')` whose body has **no
  `Control('Wait')`**. It runs every iteration in a single tick and trips **LagSlayer**
  (freezes the whole plot). Add a wait, or use a process timer. See `performance.md`.
- **BRACKET_UNDERFLOW / UNBALANCED / TYPE / BAD_BRACKET_OPEN** — bracket nesting is off.
  Rare for AI-written code (`with` blocks always balance); only hand-rolled
  `OpenBracket`/`CloseBracket` can do this. DF tolerates stray brackets, so these are WARN.

### Cross-line (project mode, ERROR/WARN)
- **DUP_EVENT** (ERROR) — two files define the same event (a plot allows **one of each**).
- **DUP_NAME** (ERROR) — two funcs (or two processes) share a name. *A func and a process
  may share a name* — they're separate namespaces.
- **UNKNOWN_CALL** (WARN) — `CallFunc`/`StartProcess` to a name **no file defines** (a
  misnamed call silently does nothing). Skipped for `%code` dynamic-dispatch names.

## Limits (be honest about these)
- The bundled `actiondump.json` is a **snapshot**; DF adds/renames actions. Renames are
  handled via the dump's own `aliases` (so `Text`, `SplitText`, `ChatColor`… validate clean,
  tags included). If a real action still WARNs as unknown, it's newer than the snapshot —
  verify in-game, then refresh `actiondump.json`.
- The validator does **not** check argument *types/order* (too many false positives) or
  whether your game *logic* is correct. Green means well-formed, not fun or bug-free.

## Other failure classes it can't see (you must)
- **Infinite recursion** across functions (A calls B calls A with no base case).
- **Reading post-event state** before a `Control('Wait')` (the event hasn't committed) — see
  `execution-model.md`.
- **`line`-scope vars across `CallFunc`** — use `local` (see `variables-and-data.md`).
