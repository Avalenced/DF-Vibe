# Permissions — gating commands to staff

To stop non-staff from running a command (e.g. an admin-only `@generate`), check the
sender's plot permission with the **`HasPermission`** IfPlayer condition:

```python
with IfPlayer('HasPermission', tags={'Permission': 'Developer'}):
    # only plot developers reach here
    ...
with IfPlayer('HasPermission', tags={'Permission': 'Developer'}, attribute='NOT'):
    PlayerAction('SendMessage', comp('<red>You do not have permission to do that.'))
```

`Permission` tag options: **`Owner`** (the plot owner), **`Developer`** (has dev perms),
**`Builder`** (build perms), plus `Operator` and combined options. Use `Developer` (or
`Owner`) to gate admin/dev tooling.

Combine with command parsing (`commands.md`): match the command, then gate with
`HasPermission`, then act.
