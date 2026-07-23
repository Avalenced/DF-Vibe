# World: setting blocks, regions, and generating terrain

## Setting blocks — TWO different actions (don't confuse them!)
- **One block (or a few individual blocks):** **`' SetBlock '`** (with surrounding
  spaces — that exact string). It sets a block at EACH location you pass; passing two
  locations places **two separate blocks**, it does NOT fill between them.
  `GameAction(' SetBlock ', loc(x,y,z, isBlock=True), item('{…id:"minecraft:stone"}'))`
- **Fill a cuboid region:** **`SetRegion`** (NOT `' SetBlock '`). Fills the whole box
  between two corners with a block, up to 100,000 blocks per action:
  `GameAction('SetRegion', item('{…}'), loc(corner1), loc(corner2))`
  DiamondFire reads the args by type, so block/corner order is flexible — but use
  `SetRegion`, not `' SetBlock '`, or you'll only get the corner blocks. The block may be
  `item('{…}')` or `txt('block_name')` (e.g. `txt('air')`).

Other world ops (grep `actiondump.json`): `CloneRegion`, `SetBlockData`, `BreakBlock`.

`SetVar('RandomLoc', out, loc1, loc2)` picks a random location inside a box — handy for
random spawns or random feature placement.

## Generating terrain that looks good (and won't lag)
**Heavy generation can trigger LagSlayer** (`performance.md`). Placing thousands of single
blocks in one tick will freeze the plot. Instead:
- **Use region fills, not per-column loops.** Fill the solid base (e.g. stone/dirt from
  the lowest Y up) with ONE `' SetBlock '` region call.
- **Build height variation from a few region fills, not random per-block.** Random
  *per-column* height looks spiky and random *per-block* textures look bad. For smooth,
  natural terrain in a small Y band (e.g. 46–52), place a modest number of overlapping
  **mounds**: pick random centers (`RandomLoc`) and fill small stacked boxes
  (e.g. 7×7 at y+1, then 5×5 at y+2) so each reads as a rounded hill; repeat ~10–20 times.
- **Coherent palette:** fill the body as dirt/stone, then set just the **top layer** to
  grass (a thin region fill per height band). One material per layer, not random — that's
  what looks polished.
- **Spread big jobs over ticks** if needed: do a few fills, `Control('Wait', num('1'))`
  [Ticks], continue — so you never spike CPU. Run generation off a trigger (a command or a
  timer), not every tick.
- **Stay inside the plot** (`plot-limits-and-geometry.md`) — keep the arena within the
  play area, and keep each generation code line under the length limit (split the
  generator into several functions).

## Spawn protection / invulnerability
`PlayerAction('SetInvulTicks', num('60'))` makes the target invulnerable for N ticks
(60 = 3s) — the clean way to do spawn protection (no manual flag needed for *taking*
damage). To also stop a protected player from *dealing* damage, set a `%uuid protected`
GAME flag on spawn, clear it after the same delay, and cancel outgoing damage while it's set.
