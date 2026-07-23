# Players are not entities

Players and non-player entities (mobs, armor stands, display entities) are **separate
universes** and cannot be mixed:

- **Player Event** blocks fire only for players; **Entity Event** blocks fire only for
  non-player entities. Neither crosses over.
- **Player Action** targets players; **Entity Action** targets entities.
- A selection cannot contain players and entities at the same time.

The classic bug is using an Entity block when the target is a player (or vice versa) and
nothing happens. Rule of thumb: **player involved → player blocks; spawned mob / armor
stand / display → entity blocks.**

In the builder: `event(...)` + `PlayerAction(...)` for players; `entity_event(...)` +
`EntityAction(...)` for entities.
