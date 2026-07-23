# Performance and LagSlayer

DiamondFire enforces a CPU budget. **If the plot's `CPU Usage` stays at 100% for >3
seconds, LagSlayer activates and stops ALL code** — events stop firing, processes halt,
everything freezes. Recovery time grows each trigger (1s, 2s, 3s…). If the last player
leaves while it's active, the counter doesn't tick down — it stays lagslaying for the
next joiner.

The **`Plot LagSlayer Recover Event`** (a Game Event) fires when the plot resumes — use
it to restart processes and restore state.

## The four LagSlayer triggers (know which one you hit)
1. **Codeline CPU limit** — a *single thread* uses too much CPU. The classic cause: a
   `Repeat Forever`/`While` with no `Control('Wait')` inside (this is what the codec's
   `TIGHT_LOOP` check flags). Message mentions "may have been caused by an infinite loop."
2. **Plot CPU limit** — the *whole plot* over budget. Brief spikes over 100% are tolerated
   (up to ~3 ticks for chunk loads/startup), but **300% instantly kills all tasks**. Halt
   time grows with each lagslay and resets when all players leave. Monitor with the
   `Plot CPU Usage` game value or `/lagslayer`.
3. **Active wait limit — max 5000 waiting threads** at tick's end. Over that, DF kills the
   *oldest* threads first (newer ones survive). Threads that never wait don't count, so you
   can run >5000 if none of them wait.
4. **Event nesting limit — 30 deep in one tick.** A process that starts a process that
   starts a process… 30 levels within a single tick gets all 30 lagslain (also recursive
   events, e.g. a damage action inside a take-damage event). *One* process starting 30
   processes is fine — each has only one ancestor. Depth is the problem, not fan-out.

Non-code CPU also counts against you: chunk loading, block updates, entity pathfinding.

## CPU-heavy blocks to use sparingly
Disguises · displaying client-side blocks · CoreProtect rollbacks · long `String`/MiniMessage
parses · long list/dict ops (incl. copying a list) · raycasting · setting large block volumes ·
Select Object over many entities · expensive Select conditions (Filter by Raycast) ·
synchronous Mojang fetches (`SetHeadTexture`). Excess use of these is a common lagslay source.

## Detecting a lagslay in code
Run a frequent loop that writes `gval('Timestamp')` (or `Microseconds Since Plot Startup`) to
a GAME var; on any event, if that var is far behind "now," the plot was lagslain — rebuild state.

## Design rules (build for the budget from the start)
1. **One loop, staggered cadences — the dispatcher pattern.** Don't start a separate
   repeating process per feature. Run *one* per-player loop and subdivide it with
   counters so heavy work runs every N ticks, not every tick:
   ```python
   # process started For-each-in-selection over online players
   CallFunc('player 1 tick loop')                      # cheap, every tick
   SetVar('+=', var('%uuid 3tick','unsaved'))
   with IfVar('>=', var('%uuid 3tick','unsaved'), num('3')):
       SetVar('=', var('%uuid 3tick','unsaved'), num('0'))
       CallFunc('player 3 tick loop')                  # medium, every 3 ticks
   SetVar('+=', var('%uuid sec','unsaved'))
   with IfVar('>=', var('%uuid sec','unsaved'), num('20')):
       SetVar('=', var('%uuid sec','unsaved'), num('0'))
       CallFunc('player 1 second loop')                # heavy, once a second
   ```
   Each cadence func calls one func per feature — move a feature to a cheaper cadence by
   moving one `CallFunc`.
2. **Prefer selections over per-target loops** — acting on a selection once is far cheaper
   than looping players and acting individually.
3. **Bound every `Repeat`** — grid/region scans are expensive; search the smallest area
   and `Control('End')` the moment you're done.
4. **Mark guard events `LS-CANCEL`** (`attribute='LS-CANCEL'`). When the plot lagslays and
   your cancelling code can't run, an `LS-CANCEL` event auto-cancels its default instead
   of firing — so players can't, e.g., drop items freely during a freeze. Any event whose
   job is to *prevent* something (drop item, inventory click, block place in spawn) should
   be `LS-CANCEL`. Tell the human which events you've marked.
