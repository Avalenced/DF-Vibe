# DiamondFire quirks — things that will bite you

Non-obvious behaviors learned the hard way. Each **passes `dfpy.py check`** (or at most
draws a WARN) but breaks (or silently misbehaves) in-game — you have to know them.

## Blocks need their required tag items (or the thread HALTS)
- `func()` / `process()` headers need `tags={'Is Hidden': 'False'}`.
- `StartProcess(...)` needs `tags={'Target Mode': '…', 'Local Variables': '…'}`.
- Omit them → in-game **"invalid chest parameters!" and the event thread halts**. The codec
  does not add them for you; `check` WARNs `MISSING_TAG` — fix it, don't ignore it. (Events
  don't need Is Hidden.) → `workflow-and-gotchas.md`

## `' SetBlock '` ≠ `SetRegion`
`' SetBlock '` (spaces) sets a block at **each** location you pass — two corners = two blocks.
To **fill** a cuboid use **`SetRegion`** (`GameAction('SetRegion', item(block), c1, c2)`, up to
100,000 blocks/action). Using the wrong one = the classic "it only placed 2 blocks." → `world-and-regions.md`

## `'line'` variables don't survive `CallFunc`
A called function is a separate code line. **LINE vars don't carry across `CallFunc`; LOCAL
vars do.** Any var read/written across a CallFunc boundary must be `'local'`. (This silently
made a whole noise-map generate flat.) → `variables-and-data.md`

## Variable scopes are `line / local / unsaved / saved`
NOT `global`/`save`. A wrong scope string passes `check` but the variable silently breaks
in-game. `unsaved` = GAME (plot-wide, wiped on stop); `saved` = persists.

## `%math` and `%round` surprises
- `%math` only evaluates inside a **`num()`** value, not `txt()`.
- `%math` is **left-to-right, NO operator precedence**: `1+2*2` = `6`, not 5.
- `%round` **truncates toward zero** (`-1.5`→`-1`), despite the in-game help saying "floor."
- Numbers hold **≤ 3 decimal places**. → `placeholders.md`

## "Sticky" types — a block remembers the first type/signature it sees (per session)
Two related traps that reset only on plot restart:
- A block accepting **multiple types** for one parameter (e.g. *Set Head* takes a head item
  **or** a UUID string) **locks to the first type used** that session. Feed *that same block*
  the other type later and it fails until the plot restarts (other blocks of the same kind are
  unaffected).
- A **dynamic `CallFunc`** (a `%code` in the function name) **memorizes the first-called
  function's full signature — parameter names included** — and reuses it for every later call.
  If the first one reached omits a parameter the others need, those others silently break.
  Keep all functions reachable through one dynamic call **identical in signature**. → `functions-vs-processes.md`

## `ShowInv` opens a menu; `SetInventory` does NOT
`ShowInv(items…)` + `SetInvName` opens a clickable GUI. `SetInventory` fills the player's
**real inventory** (no menu). Read clicks via `gval('Event Clicked Slot Item')` + `GetItemTag`.

## Cooldowns are native — don't hand-roll them
`SetItemCooldown(item, ticks)` + `IfPlayer NoItemCooldown(item)` give the vanilla cooldown
sweep and a clean gate. A custom flag+process is worse on every axis. → `common-actions.md`

## Other
- **Action args are read by type** (block vs location), so block/corner order is flexible —
  but you must use the right *action* (`SetRegion` vs `' SetBlock '`).
- **Events are live Paper events**: the triggering state isn't committed yet; `IfPlayer`
  checks may read the old value. `Control('Wait', num('0'))` [Ticks] reads the new state but
  then you can't cancel. → `execution-model.md`
- **`comp()` = MiniMessage** (full support: gradients, click/hover/lang/key); `txt()` =
  legacy string. Use `comp` + MiniMessage, never `§`/`&`. → `text-and-minimessage.md`
- **Plot commands use `@`**, never `/` (including `<click:run_command:@cmd>`). → `commands.md`
- **Code-line length cap** = `2×blocks + brackets`, ≤ `codeLength` (LARGE = 100). Split big
  lines into functions. → `plot-limits-and-geometry.md`
- **`%uuid`** (stable) for persistent per-player keys; **`%default`** is the name (changes).
