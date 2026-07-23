# Execution model — code lines, threads, targets

DiamondFire code is organized into **code lines**, of three kinds:

| Line | Header | Runs when… | Target |
|---|---|---|---|
| **Event** | `event(...)`, `entity_event(...)`, `game_event(...)` | the game fires it (join, click, take damage…) | whoever it happened to |
| **Function** | `func('name', …)` | `CallFunc('name')` calls it | inherits caller's target/selection, **same thread** |
| **Process** | `process('name', …)` | `StartProcess('name')` starts it | configurable, **own thread** (see `functions-vs-processes.md`) |

A plot holds **one event of each type** and **uniquely-named** funcs/processes.

## Targets and selections (the core idea)
Every running line has a **target** (the player/entity that Player/Entity Action blocks
affect by default, and that game values like `Location` read from) and a **selection**
(a set you build with *Select Object* and act on at once via `target='Selection'`).

- An event fires **once per occurrence**, targeting whoever triggered it. `Join` fires
  once per joining player; `PlayerTakeDmg` fires once for the player who was hit.
- Use `%default` (target's name) / `%uuid` (target's UUID) to refer to the current target
  in text and variable names — see `placeholders.md`.

### How blocks behave under an active selection
A *Select Object* sets a selection for the rest of the thread — it **persists across
`CallFunc`** until you clear it with `SelectObj('Reset')`. While one is active, block types
react differently (this trips people up):
- **Player/Entity/Game Action, Set Variable, Control** — run **once per selected target**.
  (3 players selected + `SetVar('+=', var('x','unsaved'))` ⇒ `x` goes up by 3.)
- **If Player / If Entity** — run **once**, passing if **any** target meets the condition.
- **If Game / If Variable** — run **once**, unaffected by the selection.
- **Repeat** — the loop itself runs once, but action blocks inside it still fire per target.
- **Select Object** — runs once.

Selections **don't stack or nest** — a new *Select Object* replaces the current one. To
combine groups, append UUIDs to a list var and select by that list. **Reset with
`SelectObj('Reset')`** when done — especially before a `CallFunc` that expects different
(or no) targets.

**Block-level target** (the `target=` kwarg, e.g. `target='Damager'` / `target='Selection'`)
overrides the thread selection **for that one block only, and does not loop** — it runs the
block once in that context. It exists **only** on Player Action, Entity Action, If Player,
and If Entity. To do something for *every* player, use a *Select Object: All Players* first,
then leave the action on its default target.

### No-target threads
A thread started **"With no targets"** (or "With current selection") has no default
target. On it:
- All blocks run **exactly once** regardless of selection.
- **Player/Entity Action blocks do nothing** unless a target is set (block-level target,
  or a Select Object first).
- **Select Object** still works (selecting an empty group → dependent blocks run zero times).
- **Game Action and Set Variable always run normally.**

This is for plot-wide logic that doesn't belong to one player. It is *not* the same as an
empty selection from a Select Object block.

## Events are live Paper events — state isn't committed yet
When an event fires, the change that triggered it **hasn't been committed**, and the
event is still cancellable. The "new" state may not be readable yet.

> `PlayerEvent: Sneak` — at the start, `IfPlayer: IsSneaking` is **false** (sneak not
> committed). To read the post-event state, do `Control('Wait', num('0'))` with unit
> **Ticks** (defers to next tick). **But once you wait, the event window closes and you
> can no longer cancel it.** So you must choose: cancel now (blind), or read after the
> wait (too late to cancel).

To cancel an event's default outcome, use `GameAction('CancelEvent')` (or mark the event
`LS-CANCEL` so it auto-cancels during lag — see `performance.md`).

## LagSlayer (summary; full detail in performance.md)
If the plot's CPU stays at 100% for >3s, **LagSlayer halts all code**. The
`Plot LagSlayer Recover Event` fires when it resumes — use it to rebuild state/restart
loops. Design loops to stay under budget from the start.
