# Functions vs Processes

**`CallFunc('name', args…)`** — synchronous call on the **same thread**. Runs
immediately, sees the same target and selection, **shares local variables**, and returns
to the caller when done. Use for "do this subroutine now": ability handlers, menu
builders, validation, math. Functions take parameters via `param(...)`; pass args
positionally.

**`StartProcess('name')`** — fires a **separate thread** that runs independently; the
caller does not wait. Use for things that happen *over time* or *in parallel*: loops,
timers, animations — anything with waits that shouldn't block the caller.

## Parameters and returning values
Declare parameters in the header with `param('name','type', …)`; the caller fills them
positionally in `CallFunc`/`StartProcess`. Two rules decide how data flows:
- **A `var`-type parameter is passed by reference.** If the function writes to it, the
  caller sees the change (`param('out','var')`). **Every other type is by value** — the
  function gets a copy and can't touch the caller's original.
- **Functions can't `return`.** To hand a result back, declare a `var` parameter and write
  into it; the caller passes a variable to receive the value. Need several results? Use one
  `var` parameter each.

## Dynamic dispatch (percent codes in the name)
The **name** given to `CallFunc`/`StartProcess` accepts `%codes`, so you can route at
runtime: `CallFunc('%default handler')` calls a function named after the current target.
Good for per-kit / per-state logic without a big `if` chain — but the resolved name must
match a real func/process exactly, or the call silently does nothing.

## StartProcess tags (what the new thread inherits)

**Local Variables:**
- *Don't copy* (default) — process starts with empty local storage.
- *Copy* — gets a snapshot of the caller's locals; changes don't propagate back.
- *Share* — both threads use the **same** local storage; changes are mutually visible.
  Use to let a loop process talk to whoever started it.

**Target Mode:**
- *With current targets* (default) — same targets as caller. **The process stops if all
  its targets leave the plot.**
- *With current selection* — starts with the caller's selection.
- *With no targets* — no target/selection (see no-target threads in `execution-model.md`).
- *For each in selection* — spawns **one process per selected target**, each with that
  target as its default. The standard "do this for every player" fan-out.

## Gotcha
A *With current targets* process **dies when its target leaves**. For a background loop
that must survive players joining/leaving, start it *With no targets* (or *With current
selection*) and select players inside the loop.

## Rule of thumb
Need it now, inline, sharing state → **function**. Need it looping/over-time/in parallel
→ **process**.
