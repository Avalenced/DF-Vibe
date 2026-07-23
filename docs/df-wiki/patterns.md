# Proven architecture patterns (catalog)

Reach for these before inventing your own — they're how shipping DiamondFire games are
built. Details live in the linked pages. **Copy the linked exemplar, then adapt** —
exemplars are from `plots/Fable` (validates clean). A worked mini-game lives in
`df-wiki/examples/` — start there when building a new game.

- **Per-player loop dispatcher** — one `For each in selection` process → staggered cadence
  funcs (1-tick / 3-tick / 1-second) via `%uuid` GAME counters → one feature func each.
  See `performance.md`. The backbone of most plots.
  Exemplar: `plots/Fable/process__playerLoop.py` (started per player from `event__Join.py`).
- **Event → handler fan-out** — a single event (e.g. `PlayerTakeDmg`) calls a list of
  small handler funcs (`fire cloak`, `royalty pants`, …), each checking "is this my
  gear?" and returning fast if not. Add gear = add one func + one call. See
  `items-and-abilities.md`.
  Exemplar: `plots/Fable/event__RightClick.py` (lectern / book / dash each checked, then
  dispatched to its func).
- **Item-tag abilities** — identity by `ItemHasTag`, not material; cooldowns via `%uuid`
  GAME vars. See `items-and-abilities.md`.
  Exemplar: `plots/Fable/event__RightClick.py` (`GetItemTag` identity + native
  `SetItemCooldown`/`NoItemCooldown` on the dash feather).
- **Container-as-database** — chests/barrels on a grid as records; items as rows; item
  tags as fields; grid-scan for free/used slots. For persistence richer than flat
  save-vars (teams, shops, profiles). See `variables-and-data.md`. (No vetted exemplar
  in `plots/Fable` yet — follow the page.)
- **`@` command router** — one `Command` event (or a func per command) matching
  `CmdArgEquals` on indices and dispatching. See `commands.md`.
  Exemplar: `plots/Fable/event__Command.py` (incl. `HasPermission`-gated dev commands).
- **Menus (chest GUIs)** — set items in slots, open the inventory, handle clicks in a
  `ClickMenuSlot`/`ClickInvSlot` event keyed by slot or the clicked item's tag. Track
  "which menu is open" in a `%uuid menu` GAME var so the click handler knows context.
  Exemplar: `plots/Fable/func__openBook.py` (bordered `ShowInv` build) +
  `plots/Fable/event__ClickMenuSlot.py` (clicks keyed by item tag).
- **Load / reset on start** — a process (or the `Plot LagSlayer Recover Event`) that
  rebuilds holograms, leaderboards, and world state from `saved` data when the plot starts.
  Exemplar: `plots/Fable/game_event__LagSlayRecover.py` (+ `func__initPlot.py`, run once
  from `event__Join.py`).
