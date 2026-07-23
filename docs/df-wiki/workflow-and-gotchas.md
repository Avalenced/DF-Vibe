# Workflow and gotchas

## The loop (who does what)
1. **Human, in-game:** `/df pull <project>` scans the plot → `<project>/*.py` (one file
   per code line).
2. **You:** edit the `.py` files, then **self-check**: `python codec/dfpy.py check
   plots/<proj>/<file>.py`. `[PASS]` = it recompiles; fix any `[FAIL]` before handing off.
   (Green does **not** mean the game logic is correct — that's on you.)
3. **Human, in-game:** `/df push` recompiles changed lines back into the plot
   (replace-by-header, idempotent).

You never touch the game. Tell the human exactly which command to run:
- **Add a line** → create a new `.py` with a new header; `/df push` places it.
- **Delete a line** → `/df push` does NOT delete. Tell the human it needs **`/df deploy`**
  (clear plot + place all = exact mirror; also how to clone to another plot). Deploy is
  destructive but snapshots first.

## Build discipline
- **Design the line list first** — sketch the events/funcs/processes and what each owns.
  A game is a graph of small code lines, not one big script.
- **Build the smallest playable core**, check it, have the human push & test, then layer
  features one func at a time.
- **Watch the lag budget** as you add loops (`performance.md`).

## Mistake checklist (scan before handing off)
- [ ] **Every `func()`/`process()` header has `tags={'Is Hidden': 'False'}`, and every
      `StartProcess(...)` has `tags={'Target Mode': '…', 'Local Variables': '…'}`.** These
      blocks have required dropdown items; omit them and DiamondFire shows "invalid chest
      parameters!" and **halts the thread** in-game (the codec does NOT add them for you).
      Events don't need Is Hidden. Common StartProcess tags: `'Target Mode'` =
      `'With current targets'`/`'With no targets'`/`'With current selection'`/`'For each in selection'`,
      `'Local Variables'` = `"Don't copy"`/`'Copy'`/`'Share'`.
- [ ] To open a clickable **GUI menu**, use `PlayerAction('ShowInv', items…)` +
      `SetInvName` — NOT `SetInventory` (which fills the player's real inventory).
- [ ] Variable scopes are `'line'/'local'/'unsaved'/'saved'` — never `global`/`save`.
      Persistent = `'saved'`; per-round shared = `'unsaved'`.
- [ ] Per-player counters/cooldowns are namespaced with `%uuid` (saved) or `%default`
      (transient), not a bare shared GAME var.
- [ ] `num()` args are **strings** (`num('5')`); `%math` lives inside `num()`, not `txt()`.
- [ ] Colors use MiniMessage `comp('<yellow>…')`, not `§`/`&`. Plot commands use `@`.
- [ ] Action/tag/option names are **verbatim** from `actiondump.json` — grepped, not
      guessed. (One real action is literally `' SetBlock '` with spaces.)
- [ ] Player target → Player blocks; entity target → Entity blocks (never mixed).
- [ ] Background loops that must outlive a leaving player are started *With no targets* /
      *With current selection*, not *With current targets*.
- [ ] Events that read the state they set use `Control('Wait', num('0'))` [Ticks] first
      (and you accept you can't cancel after waiting).
- [ ] Guard events (block a harmful default) are marked `LS-CANCEL`.
- [ ] Every `Repeat` is bounded and `End`s early; heavy work is on a slow cadence.
- [ ] Every changed `.py` passes `dfpy.py check`.
- [ ] You told the human push vs deploy.
