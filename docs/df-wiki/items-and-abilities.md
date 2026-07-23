# Items, gear, and abilities

The dominant pattern for "this sword does X / this armor does Y" is **identify gear by a
custom item tag, not by its material or name.**

- Tag the item (when given/crafted) with `SetVar('SetItemTag', out, item, txt('fire_cloak'),
  …)`, or bake the tag into its NBT. In raw item SNBT a custom tag lives under
  `components."minecraft:custom_data".PublicBukkitValues."hypercube:<key>"` (DF's plugin is
  *hypercube*; always namespace tags `hypercube:`). Usually just use `SetItemTag` and let DF
  write the NBT.
- In the relevant event, read the equipped item and check the tag:
  ```python
  SetVar('GetListValue', var('chest','local'), gval('Armor Items'), num('2'))  # slot 2 = chestplate
  with IfVar('ItemHasTag', var('chest','local'), txt('fire_cloak')):
      ...
  ```

## Armor abilities hook damage events
Read context from game values, then cancel and apply your effect:
```python
with IfVar('=', gval('Damage Event Cause'), txt('lava'), txt('fire'), txt('fire_tick')):
    GameAction('CancelEvent')
    PlayerAction('SetFireTicks', num('0'))
    PlayerAction('GivePotion', pot('Fire Resistance', 5, 0), tags={'Show Icon':'False'})
```

## Active (right-click) abilities
Hook `RightClick`/`LeftClick`/`SwapHands`, check the held item's tag, apply a cooldown
(a `%uuid … cooldown` GAME var — see `variables-and-data.md`), then do the effect.

## Game values are your sensory input
`gval('Location')`, `gval('Armor Items')`, `gval('Main Hand Item')`,
`gval('Damage Event Cause')`, `gval('Event Command Arguments')`, `gval('CPU Usage')`, and
many more. Browse `actiondump.json` for what's available before writing a workaround.
A second arg targets a selection, e.g. `gval('Location', 'Selection')` — but only
**statistical, locational, item, and informational** values honor a target. **Event and
plot values** (damage, event command, player count, CPU usage…) ignore it and always read
the current event/plot context.
