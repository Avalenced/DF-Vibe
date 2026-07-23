# Plot commands use `@`, not `/`

Plots can't register `/` commands. All plot commands use the **`@` prefix**. With a
`PlayerEvent: Command` block placed, any chat message starting with `@` is intercepted as
a command (the full string, including `@`, is available to the event). This also means
`<click:run_command:@cmd>` must use `@` (see `text-and-minimessage.md`).

## Parsing arguments
- `IfGame('CmdArgEquals', txt('expected'), …, num('index'))` — check the arg at an index
  against one or more expected strings (pass several `txt()` for aliases). Tag
  `{'Ignore Case': 'True'}` for case-insensitivity.
- `gval('Event Command Arguments')` — the full argument list (index 1 is the command word).

## Proven pattern — `@team create <name…>`
```python
event('Command')
with IfGame('CmdArgEquals', txt('team'), txt('t'), num('1'), tags={'Ignore Case': 'True'}):
    with IfGame('CmdArgEquals', txt('create'), num('2'), tags={'Ignore Case': 'True'}):
        SetVar('=', var('name','local'), gval('Event Command Arguments'))
        SetVar('RemoveListIndex', var('name','local'), num('1'), num('2'))  # drop "team"+"create"
        SetVar('JoinText', var('name','local'), var('name','local'), txt(' '))  # rejoin the rest
        # …validate, then act…
```
Match the command word(s) by index, strip them with `RemoveListIndex`, `JoinText` the
remainder for free-text arguments (names, messages). Cancel the event
(`GameAction('CancelEvent')`) if you don't want the raw `@…` echoed.
