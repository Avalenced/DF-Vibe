# Native actions — use them, don't reinvent

**The #1 mistake when building DF games: reinventing something that already has a native
action.** DiamondFire has a built-in action for almost everything. *Before* you build a
custom system (cooldowns, timers, HUDs, borders, knockback, compression, JSON…), assume DF
already has it and **grep `action-reference.md`** (the complete, one-line-per-action list)
for the keyword. A native action is shorter, more reliable, and usually has built-in visuals
players already recognize.

> This page is the *curated highlights*. The full machine-generated catalog of all 1100+
> actions + game values is **`action-reference.md`** — grep that when this page doesn't cover it.

> Real example of getting this wrong: a kit ability "cooldown" was hand-rolled with a GAME
> flag + a separate process that cleared it. **Every DF builder just uses
> `SetItemCooldown`** — it shows the vanilla cooldown sweep on the item *and* you gate with
> `IfPlayer: NoItemCooldown`. No flag, no process, better UX. Don't hand-roll what's native.

## Cooldowns (the canonical pattern)
```python
with IfPlayer('NoItemCooldown', gval('Main Hand Item')):     # not on cooldown?
    PlayerAction('SetItemCooldown', gval('Main Hand Item'), num('80'))   # 80t = 4s sweep
    # …do the ability…
```
`GetItemCooldown(var, item)` reads remaining ticks if you want a number. Cooldowns apply to
the item **type**.

## The catalog (by need — grep `actiondump.json` for exact tags/args)
- **HUD / display:** `ActionBar`, `SendTitle`, `BossBar` / `RemoveBossBar`,
  `SetSidebar` / `ShowSidebar` / `HideSidebar` (scoreboard), `SetTabListInfo` (tab header/footer).
- **Menus / inventory:** `ShowInv` (open a clickable GUI) + `SetInvName` + `SetMenuItem`;
  `GiveItems`, `SetEquipment`, `SetSlot`, `ClearInv`, `SetItemTag` / `GetItemTag`.
- **World:** `SetRegion` (fill a cuboid), `' SetBlock '` (single blocks), `CloneRegion`,
  `SetBlockData`, `SetWorldBorder` / `ShiftWorldBorder` / `RmWorldBorder`, `BreakBlock`.
- **Player state:** `SetMaxHealth`, `Heal`, `Damage`, `GivePotion` / `RemovePotion` /
  `ClearPotions`, `SetInvulTicks` (spawn protection), `AdventureMode` / `SurvivalMode` /
  `CreativeMode` / `SpectatorMode`, `SetAllowPVP`, `SetFoodLevel`, `SetItemCooldown`.
- **Movement / combat:** `Teleport`, `LaunchUp`, `LaunchProj`, `LaunchToward`,
  `SetVelocity`, `Knockback`-type actions.
- **Effects / juice:** particle actions (`Particle…`), `PlaySound`, `SendTitle`.
- **Conditions (IF PLAYER / IF GAME / IF VAR):** `IsHolding`, `IsWearing`, `IsSneaking`,
  `IsSprinting`, `HasPermission` (Owner/Developer/Builder), `NoItemCooldown`,
  `InWorldBorder`, `CmdArgEquals`, `BlockEquals`, `ItemHasTag`, `ListContains`, `VarExists`.
- **Data:** `CreateList` / `AppendValue` / `GetListValue` / `RemoveListIndex`,
  `CreateDict` / `SetDictValue` / `GetDictValue`, `SetCase`, `RandomLoc`, `RandomNumber`.

## How to discover an action (do this every time)
```
python codec/dfpy.py … (no) — instead:  grep the dump
```
Use the inspector `Research/adump.py act <Name>` (tags/options) or `gv <substr>` (game
values), or `grep '"name": "…"' codec/actiondump.json`. If you're writing more than ~5
blocks to do something a player would call "a basic feature," stop and check the dump first.
