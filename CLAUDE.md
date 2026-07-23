# DF VIBE

Vibe-coding workflow for **DiamondFire** (a Minecraft creative-server visual
language). Plot code ⇄ local `.py` files: scan the plot → edit files → place back.

## Layout
- `mod-standalone/` — **the shipping mod.** A fork of CodeClient with its API built in,
  `/auth` removed, and the `/df` tooling (`com.dfvibe`) merged in. **Dev mode only** — the
  scan/place loop uses CodeClient's standard location-teleport movement (no custom flying).
  In-game `/df` chat commands drive the loop; it talks to the bundled API over
  `ws://localhost:31375` for scan/place and shells out to the codec. One jar, no separate
  CodeClient, never asks `/auth`. Build → `dfvibe-1.0.0.jar`.
- `codec/` — Python codec (`dfpy.py` CLI + `df_codec.py` lib + `actiondump.json`).
  Lossless template↔`.py` translation.
- workspace: one folder per project (set with `/df path workspace`), holding the `.py` files **you edit**.
- `docs/AI_GUIDE.md` — **read this before editing `.py` files** (builder API + rules).
- `docs/SETUP.md` — human build/install/config.

## Editing DiamondFire code (the vibe-coding path)
Each `.py` file = one code line (event/func/process). They have **no imports**;
builder functions (`event`, `func`, `PlayerAction`, `IfPlayer`, `num`, `var`, …)
are injected when the codec runs the file. After editing, **always self-check**:

```
python codec/dfpy.py check plots/<project>/<file>.py     # compile + validate edited file(s)
python codec/dfpy.py validate plots/<project>            # whole project (+ cross-line checks)
python codec/audit.py plots/<project>                    # self-consistency over a whole project
```

`check`/`validate` catch the DF "compile" errors — typo'd action/tag/value names (with
"did you mean"), bad chests, over-long lines, illegal var scopes, tight loops, duplicate
events/names. Full catalog: `docs/df-wiki/compile-errors.md`. Pass `--rank
Noble|Emperor|Mythic|Overlord` (or set `/df config rank …` in-game, which stamps it into the
project's `.df-vibe.json`) to also flag actions above your rank (`RANK_LOCKED`).

**Building or changing gameplay logic?** First read `docs/df-wiki/INDEX.md` (golden rules
+ which page to open) and skim `docs/df-wiki/quirks.md` — those bugs pass `check`.

**Items/menus/styled text — generate, don't hand-write** the ~1000-char component SNBT:

```
python codec/dfpy.py mkitem <mat> --name "<mm>" --lore "<mm>" [--glow --tag k=v …]
python codec/dfpy.py text "<minimessage>"           # preview + comp('...') value
python codec/dfpy.py preview plots/<proj>/<file>.py  # ANSI tooltips (+ --png image)
python codec/dfpy.py menu plots/<proj>/<file>.py     # chest-GUI grid (--new scaffolds)
python codec/dfpy.py style plots/<proj>              # lint look: italic, legacy §, palette
```

Names/lore are MiniMessage; the tool bakes in `italic:0b` (the italic-name footgun) and
emits the exact canonical shape the game produces. Details: `docs/AI_GUIDE.md`.

**Human-facing desktop editor:** `DF Item Editor.bat` (or `python codec/dfpy.py edit [file.py]`)
— a native GUI to view/edit items with a live in-game-accurate tooltip preview (fancy gradient
border) and a MiniMessage palette (`<>` codes only). Opens a `.py` line, edits an item, writes
it back (→ `/df push`), or copies the `item('...')` literal. The human drives it; you use the CLI.

Key rules: `num()` takes a **string**; action/tag names are **verbatim**; a file's
identity is its **header** (don't rename funcs/events casually). Builders include
`txt/comp/vec` for text/vectors and rare lossless escapes (`raw_item`, `raw_block`,
`OpenBracket/CloseBracket`, `slot=`) — leave those as-is. Full reference and gotchas in
`docs/AI_GUIDE.md`. **Before hand-rolling anything (compression, cooldowns, JSON…), grep
`docs/df-wiki/action-reference.md`** — DF likely has a one-block action for it. Authoritative
name list: `codec/actiondump.json`.

## Who runs what
The **human** runs the in-game chat commands (**in dev mode**): `/df pull` (plot→files),
`/df push` (files→plot, replace/add only — only changed lines), `/df deploy` (clear+place
all — needed to delete lines or clone to another plot), `/df fill` (place only missing
lines), `/df backup [list|load]`. **You** only read/write the `.py` files and run the codec
checks above. You don't touch the game. Deleting a file needs `/df deploy`, not push
— tell the human that.
