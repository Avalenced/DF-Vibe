# DF VIBE — Guide for AI editors

You (the AI) edit DiamondFire code as local **`.py` files**. Each file is one *code
line* (one event / function / process and its blocks). A Fabric mod syncs these
files to/from the live plot via chat commands the **human** runs in-game.

This guide is the **builder syntax**. Building or changing gameplay logic? First read
`df-wiki/INDEX.md` (golden rules + which page to open) and skim `df-wiki/quirks.md` —
those bugs pass `check`.

## The loop (who does what)

1. **Human, in-game:** `/df pull <project>` → the plot is scanned and decompiled
   into `<workspace>/<project>/*.py` (one file per code line).
2. **You:** edit those `.py` files. After editing, **self-check** (see below) —
   you do not need the game to validate.
3. **Human, in-game:** `/df push` → your files are recompiled and placed back into
   the plot (replace-by-header, so it's idempotent — re-pushing identical code is a
   no-op).

You never touch the game or the mod. You only read/write `.py` files and run the
codec's `check` to validate.

**Creating / deleting code lines (tell the human the right command):**
- **Add** a line by creating a new `.py` file with a new header (e.g.
  `func('newThing')`). `/df push` places it. One header per file; the filename is
  cosmetic — identity is the header call.
- **Delete** is NOT done by `/df push` (push only replaces/adds). If you delete a
  `.py` file, tell the human it takes **`/df deploy`** (clear the plot + place all =
  exact mirror) to actually remove it in-game. Deploy is also how they clone the
  whole codebase onto another plot. It's destructive and does **not** auto-snapshot —
  tell the human to run `/df backup` first if they want a restore point.

## Self-check after every edit (do this)

```
python codec/dfpy.py check plots/<project>/<file>.py ...
```

Prints `[PASS] file  type:key  (N blocks)` or `[FAIL] file  Error: ...`. A FAIL
means the file won't recompile — fix it before telling the human to push. The
codec is lossless and strict: a wrong tag name, action, or item shape fails here
rather than silently corrupting the plot.

`check`/`validate` read the plot's code-length limit from the project's
`.df-vibe.json` (the mod records `plotLength` at pull/push). Before the first
pull — or when checking a file outside a project dir — they assume 300 (Massive);
pass `--plot-length 100` on a **Large** plot then, or over-long lines slip through.

## File format

A file is plain Python **with no imports** — the builder functions below are
injected when the codec runs it. The first line is a `# DF line: type:key` comment
that the codec ignores (don't rely on it; the real identity is the header call).

Example (`func__profile.py`):

```python
# DF line: func:profile    (edit the code below; this comment is ignored)
func('profile', tags={'Is Hidden': 'False'})
with Repeat('Grid', var('gridLoc', 'line'), loc(0.5, 255.5, 0.5, isBlock=True), loc(100.5, 251.5, 100.5, isBlock=True)):
    with IfGame('BlockEquals', var('gridLoc', 'line'), item('{DF_NBT:4671,count:1,id:"minecraft:barrel"}'), attribute='NOT'):
        CallFunc('createProfile', var('gridLoc', 'line'))
        Control('End')
    SetVar('ContainerName', var('uuid', 'line'), var('gridLoc', 'line'))
```

- **Indentation / `with`** = code-block nesting (the `{ }` brackets in-game).
  `IfPlayer/IfEntity/IfGame/IfVar/Repeat/Else` open a bracket; their body is the
  indented block under `with`.
- **Order matters** — blocks run top to bottom.

## Builder API reference

### Headers (first call in the file)
| Call | Meaning |
|---|---|
| `event('Join', tags=…)` | Player Event. `entity_event(...)`, `game_event(...)` likewise. |
| `func('name', *params, tags={'Is Hidden': 'False'})` | Function definition. **Always pass `tags={'Is Hidden': 'False'}`** — without it DiamondFire flags the block as missing its True/False parameter item (the codec does not add it automatically). |
| `process('name', *params, tags={'Is Hidden': 'False'})` | Process definition. Same `Is Hidden` requirement as `func`. |

Function/process params use `param(...)` as the `*params` (see below).

### Statements (one block each; just call them, no `with`)
`PlayerAction('action', *args, …)`, `EntityAction(...)`, `GameAction(...)`,
`SetVar('action', *args)`, `Control('action', *args)`, `CallFunc('name', *args)`,
`StartProcess('name', *args, tags=…)`, `SelectObj('action', *args, …)`.

### Containers (open a bracket; use with `with ... :`)
`IfPlayer('action', *args, …)`, `IfEntity(...)`, `IfGame(...)`, `IfVar('=', a, b)`,
`Repeat('Grid'|'Multiple'|…, *args)`, `Else()`.

### Value arguments (the `*args`)
| Call | Notes |
|---|---|
| `num('5')` / `num('%math(...)')` | **Always a string.** Holds plain numbers, math, or variables. |
| `var('name', 'line')` | scope is `'line'`, `'local'`, `'unsaved'` (GAME — plot-wide, wiped on plot stop), or `'saved'` (SAVE — persists). |
| `bucketvar('var', 'bucket')` | a bucket variable (cross-plot storage); add `namespace_type=`/`namespace_alias=` for a shared namespace. See `df-wiki/variables-and-data.md`. |
| `gval('Type', 'Default')` | game value; 2nd arg is the target (omit if `'Default'`). |
| `item('{DF_NBT:4671,count:1,id:"minecraft:barrel"}')` | a minecraft item as SNBT. |
| `txt('hi %var(x)')` | styled text (the common DF text value). |
| `comp('<red>hi')` | MiniMessage text component. |
| `vec(0.0, 1.0, 0.0)` | a vector (x, y, z). |
| `param('name', 'num', plural=False, optional=False)` | function parameter. |
| `pot('Speed', 1000000, 0)` | potion effect (id, duration, amplifier). |
| `loc(0.5, 64.0, 0.5, pitch=0.0, yaw=0.0, isBlock=False)` | a location. |
| `snd('Note Block Bell', 2.0, 1.0, variant=None)` | a sound (pitch, volume). |
| `raw_item('id', {...})` | fallback for any item type not above (keeps it lossless). Particles use this — see the Particles section. |

Any value arg can take a trailing `slot=N` to pin its slot (you'll see this only on
unusual lines with gaps; normally slots are implicit — don't add it yourself).

### Block keyword arguments (on any block call)
| kwarg | Use |
|---|---|
| `tags={'Tag Name': 'Option'}` | block tags. Names/options must match DiamondFire exactly; slots are filled automatically. |
| `attribute='NOT'` | invert a conditional (the in-game NOT). |
| `attribute='LS-CANCEL'` | event auto-cancels its action. |
| `target='Default'` | action target selector (e.g. `'Selection'`, `'Damager'`). |
| `sub_action='IsNear'` | the condition on a conditional `SelectObj` (`EntitiesCond`/`PlayersCond`/`FilterCondition`) and `Repeat('While'/'DoWhile')`. **Required** — omit it and the select/loop matches nothing. Names are bare (`IsChunkLoaded`) or `P`/`E`/`G`-prefixed when the same condition exists on several block types (`PIsNear` vs `EIsNear`). e.g. `Repeat('While', gval('Location'), sub_action='IsChunkLoaded', attribute='NOT')` (`plots/anticheat/func__ac_scan.py`); `SelectObj('EntitiesCond', txt('billy'), num('1'), sub_action='HasCustomTag')` (`plots/anarchy/event__ChangeSlot.py`). |
| `hint=False` | rare; `func` gets a UI hint item by default (`process` does not) — set False only if a pulled file shows it had none. |

## Rules and gotchas (important)

- **`num()` is a string.** `num('1')` not `num(1)`. DF stores numbers as text so
  they can hold variables/`%math%`.
- **Action and tag names are verbatim** from DiamondFire — do not "tidy" spacing or
  capitalization. (One real action is literally `' SetBlock '` with spaces.)
- **Identity = the header.** A file's code line is identified by its header:
  events by their action (`event('Join')`), funcs/processes by name
  (`func('profile')`). Changing the header makes it a *different* line.
  - A plot holds only **one event of each type** and **unique** func/process names.
  - Don't rename funcs/processes casually — call sites (`CallFunc('name')`) won't
    follow a rename, and on push the renamed line is placed as a new line.
- **No imports, no extra top-level code.** Only builder calls (and `with` blocks).
  Don't add helper functions or Python logic — it isn't DiamondFire code.
- **Tags must be valid.** A misspelled tag name/option fails `check` (good — fix it).
- **Vars crossing a `CallFunc` must be `'local'`.** `'line'` vars reset per code line:
  `SetVar('=', var('x', 'line'), num('1'))` then `CallFunc('f')` — `f` reads `x` as empty.
  See `df-wiki/variables-and-data.md`.
- **Every `StartProcess` needs `tags={'Target Mode': '…', 'Local Variables': '…'}`** —
  omit them and the thread halts in-game. See `df-wiki/functions-vs-processes.md`.
- **Pull overwrites local files** (a backup is saved to `<workspace>/.df-backups/`
  first). If the human changed the plot, pull before editing.
- **One code line per file.** Creating a new file with a new header *should* create
  a new line on push, but that path is less tested — flag it to the human.

## Making items, menus, and styled text — use the tools, don't hand-write SNBT

An item is ~1000 chars of component SNBT (`item('{DF_NBT:4671,components:{…}}')`). **Do
not author it by hand** — it's error-prone and has a footgun: a name/lore run without
`italic:0b` renders *italic* in-game. Use the codec tools, which emit the exact canonical
shape the game produces (byte-for-byte) and handle `italic:0b` for you. Write names/lore in
**MiniMessage** (the same syntax `comp()` and `styling.md` use).

```
# generate an item literal to paste into a .py line:
python codec/dfpy.py mkitem nether_star --name "<gold>✦ The Menu" \
       --lore "<gray>Click to open" --tag menu=1
#   prints an ANSI tooltip preview + the ready  item('...')  literal
#   flags: --glow  --count N  --head <player>  --head-texture <b64>  --dyed <color>
#          --model <f>  --unbreakable  --hide enchantments  --snbt  --literal
#          --enchant sharpness=5  --stored-enchant mending=1  --rarity epic
#          --attr attack_damage=10[:operation[:slot]]  --item-name <mm>
#          --potion strength  --trim gold:sentry  --max-stack N  --damage N
#          --max-damage N  --repair-cost N  --block-state prop=value

# preview the items already in a file (terminal tooltips; --png for an image):
python codec/dfpy.py preview plots/<proj>/<file>.py [--png out.png]

# see a whole menu's chest grid (parses ShowInv/ExpandInv), or scaffold a bordered one:
python codec/dfpy.py menu plots/<proj>/<file>.py [--png out.png]
python codec/dfpy.py menu --new --rows 6 --title "<gradient:#5c9eff:#3d5aff><b>✦ Shop"

# preview MiniMessage and get the comp('...') value to paste:
python codec/dfpy.py text "<gradient:#ff5e62:#ff9966><b>TITLE</gradient> <gray>ready"

# lint items/text for look (italic bug, legacy §/& codes, no-lore buttons, palette):
python codec/dfpy.py style plots/<proj> [--palette]
```

**For the human: a desktop editor.** There's a native GUI app (`DF Item Editor.bat`, or
`python codec/dfpy.py edit [file.py]`) that wraps these tools — a live real-Minecraft tooltip
preview (with the fancy gradient border), a MiniMessage palette (only the `<>` codes:
colors, gradients, b/i/u/st/obf, symbols), and lore/material/tag/flag editing. It opens a
plot `.py` file, lets the human pick and edit one of its items, and writes it back (then
`/df push`), or just emits a paste-ready `item('...')` literal. You (the AI) still use the
CLI above; point the human at the app when they want to eyeball/tweak items by hand.

A few rules these encode: MiniMessage everywhere (never legacy `§`/`&`); names/lore are
**not** italic (the tool sets `italic:0b`); DF custom_data tags are doubles
(`--tag menu=1` → `hypercube:menu:1.0d`). Gradients in a name resolve to per-character
color runs. After generating, the normal `dfpy check` still applies — a generated item in
a code line recompiles and validates like any other. See `df-wiki/styling.md` for the look.

## Particles — author them as `raw_item('part', …)`

There is no `part()` builder; a particle value is a `raw_item` you write yourself (the
one *exception* to "leave `raw_item` as-is" below):

```python
raw_item('part', {'particle': '<Name>', 'cluster': {'amount': N, 'horizontal': X, 'vertical': Y}, 'data': {...}})
```

- `particle` — the display name, verbatim (`'Soul'`, `'Critical Hit'`, `'Dust'`). The
  authoritative list is `actiondump.json` → `particles` (each entry's `fields` tells you
  what `data` it takes).
- `cluster` — count and spread: `{'amount': 5, 'horizontal': 0.4, 'vertical': 0.75}`.
- `data` — per the particle's `fields`: Motion → `x`/`y`/`z` + `motionVariation` (0–100);
  Color → `rgb` (decimal RGB int) + `colorVariation`; Size → `size` + `sizeVariation`;
  Material → `material`. A particle with no `fields` takes `'data': {}`.

**Never leave `data` empty `{}` on a particle that has fields** (Soul, Flame, Critical Hit, Dust…) —
DiamondFire silently refuses to place the entire line (`df-wiki/compile-errors.md` →
PARTICLE_NO_DATA).

Copy-paste examples (from working plots):

```python
# dataless (Explosion has no fields):
PlayerAction('Particle', raw_item('part', {'particle': 'Explosion', 'cluster': {'amount': 3, 'horizontal': 0.8, 'vertical': 0.2}, 'data': {}}), var('loc', 'local'), target='AllPlayers')
# Motion particle (Soul — x/y/z + motionVariation REQUIRED):
PlayerAction('Particle', raw_item('part', {'particle': 'Soul', 'cluster': {'amount': 20, 'horizontal': 0.5, 'vertical': 0.9}, 'data': {'x': 0.0, 'y': 3.0, 'z': 0.0, 'motionVariation': 100}}), var('loc', 'local'), target='AllPlayers')
# Dust (color + size):
PlayerAction('ParticleCircle', raw_item('part', {'particle': 'Dust', 'cluster': {'amount': 2, 'horizontal': 0.1, 'vertical': 0.0}, 'data': {'rgb': 12575743, 'colorVariation': 0, 'size': 1.5, 'sizeVariation': 0}}), var('loc', 'local'), num('2.7'), target='Selection')
```

## Rare constructs you may see (leave them as-is)

In big or old plots a few lines use these — they're lossless escape hatches; don't
"clean them up" unless you intend to change behavior:
- `raw_item('id', {...})` / `raw_block({...})` — an item/block type with no dedicated
  builder, kept verbatim. Exception: `raw_item('part', …)` particles — you author those
  (see the Particles section above).
- `OpenBracket()` / `CloseBracket()` — an explicit (stray/unbalanced) bracket from
  malformed legacy code. Keep them where they are.
- `slot=N` on a value arg — a pinned slot for a non-standard layout.

## When you're unsure of a name

The authoritative list of every block, action, tag, and option is
`codec/actiondump.json` (DiamondFire's own dump). If you need to know whether an
action or tag exists or what its options are, grep that file rather than guessing.
