# Spawning entities & projectiles

DF spawns mobs and launches projectiles through **Game Action** (and entity/player actions).
The *what* (which mob / which projectile) is a **value item** you put in the block's chest.

## Spawn a mob — `SpawnMob`
`GameAction('SpawnMob', <spawn egg>, <location>, [number], [name], [potions…], [equipment…])`
- The mob is chosen by a **spawn egg item** in the chest (`item(...)` value). For most mobs
  that's the **vanilla creative spawn egg** (e.g. a zombie spawn egg).
- For entities that have **no creative spawn egg**, DF provides **Special Spawn Eggs** (Values
  menu → *Special Spawn Eggs*). As of this capture they are exactly: **Illusioner, Giant,
  Wither, Ender Dragon**. Use those four from that menu; everything else uses its normal egg.

Other spawners exist as Game Actions: `SpawnArmorStand`, `SpawnTNT`, `SpawnCrystal` (end
crystal), `SpawnExpOrb`, `SpawnFangs` (evoker fangs), `SpawnItem`, `SpawnVehicle`,
`SpawnTextDisplay`, `SpawnBlockDisp`, `SpawnItemDisp`, `SpawnMannequin`, … (grep `action-reference.md`).

## Launch a projectile — `LaunchProj`
`GameAction('LaunchProj', <projectile>, <location>, [name], [speed], [spread])` — also as
`PlayerAction('LaunchProj', …)` / `EntityAction('LaunchProj', …)` to launch from a player/mob.
The projectile is a **projectile value** from the Values → *Projectiles* menu. Full list:

> Snowball · Thrown Egg · Thrown Ender Pearl · Trident · Arrow · Spectral Arrow · Tipped Arrow ·
> Splash Potion · Lingering Potion · Llama Spit · Milk Bucket · Small Fireball · Fire Charge ·
> Fireball · Dragon Fireball · Dragon's Breath · **Wither Skull** · **Wither Skeleton Skull** ·
> **Charged Wither Skull** · Thrown Bottle o' Enchanting · Wind Charge · **Fishing Bobber**

**These are plain vanilla items — and a variant is encoded by the item's STACK COUNT, not by a
name or tag.** Each menu button just hands you the right item at the right count, so you could
grab the same thing from a creative inventory. The one that trips people up:
- **A blue/charged Wither Skull = a Wither Skull item with `count` = 2.** Count 1 is the normal
  black one. So "two wither skulls" and the menu's "Charged Wither Skull" are the **same value**.
  (More generally: when a projectile has variants, the menu shows the count — e.g. `x1`, `x2` —
  and that count is the selector.)
- A **Fishing Bobber** is a launchable projectile (it's a Fishing Rod item). To *detect* one,
  use the fishing-rod / projectile events (`IfEntity('IsProj')`, `ProjHit`, etc.).

## How these are stored (codec note)
The value is just a plain **`item(...)`** — an ordinary Minecraft item, with **`count` carrying
the variant** (count 2 on a wither skull = charged). Nothing DF-specific is required, so you can
hand-write them or pull them from creative. Easiest way to get the exact SNBT right is to build
the action once and `/df pull` (the codec captures it losslessly), but they're plain items.
