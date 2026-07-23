# DiamondFire Wiki — index

You build **games on DiamondFire** by editing local `.py` files (a human syncs them to a
live plot with `/df` commands). This wiki is your knowledge base. **Don't read it all.**

## How to use this wiki
1. Read this index.
2. **Open or `grep` the one or two pages relevant to your task** — each page is small and
   self-contained. Search for keywords (action names, `%codes`, tag names) across the
   folder when you don't know which page.
3. For exact builder syntax, see `../AI_GUIDE.md`. For the authoritative list of every
   block/action/tag/option, grep `codec/actiondump.json`. **Never guess a name.**
4. **Authoritative external reference: the DFOnline wiki — <https://wiki.dfonline.dev>.** It
   covers nearly all DF code mechanics, limits, and quirks. This local wiki distills the
   game-building essentials; consult the DFOnline wiki when you need depth this doesn't have.
   *Access note:* `WebFetch` gets 403'd — fetch raw wikitext with a browser User-Agent, e.g.
   `https://wiki.dfonline.dev/wiki/<Page>?action=raw`. Useful pages: `Value_types`, `Quirks`,
   `LagSlayer`, `Codespace`, `Set_Variable`, `Player_Action`, `Ranks`.

## Pages
| Page | When to open it |
|---|---|
| `common-actions.md` | **curated "don't reinvent" highlights** (cooldowns, HUD, world, items…) — read before building any system |
| `action-reference.md` | **the COMPLETE list** of every action (1100+) & game value with one-line descriptions — `grep` it before writing any workaround |
| `compile-errors.md` | **validate code before pushing** — `dfpy.py check/validate` catches typo'd names, bad chests, over-long lines, tight loops; full error catalog |
| `debugging-placement.md` | **when DF won't place code that looks fine** — a step-by-step method (does it fail by hand? what does DF say? isolate with a repro) + the scythe-particle case study + copy-paste diagnostic snippets |
| `quirks.md` | **things that will bite you** — non-obvious behaviors that pass `check` but break in-game; skim this early |
| `execution-model.md` | code lines, threads, **targets & selections**, event timing/cancel, LagSlayer basics |
| `functions-vs-processes.md` | `CallFunc` vs `StartProcess`, local-var copy/share, target modes |
| `variables-and-data.md` | the 4 variable scopes, data caps, per-player `%uuid`/`%default` keys, lists, dictionaries, **buckets/namespaces**, container-as-database |
| `entities-and-projectiles.md` | **spawning mobs (`SpawnMob` + special spawn eggs) and launching projectiles** (`LaunchProj` list incl. Charged Wither Skull, Fishing Bobber) |
| `placeholders.md` | text codes: `%var %math %round %random %index %entry` + target codes; where each works |
| `text-and-minimessage.md` | `txt` vs `comp`, **MiniMessage** formatting (colors/gradients/click/hover/lang/key), color rules |
| `players-vs-entities.md` | the player≠entity split |
| `commands.md` | `@` plot commands and argument parsing |
| `web-request-response.md` | outbound HTTP (`WebResponse`/`WebRequest`): result dict, **rate limit + size caps**, why it's unfit for streaming |
| `permissions.md` | gating commands to staff (`HasPermission`: Owner/Developer/Builder) |
| `items-and-abilities.md` | item-tag gear/abilities, armor & damage events, game values |
| `world-and-regions.md` | setting blocks (`' SetBlock '`), region fills, generating terrain, spawn protection |
| `plot-limits-and-geometry.md` | plot sizes, coordinates, and the **code-line length limit** (don't run off the edge) |
| `performance.md` | LagSlayer, the loop-dispatcher pattern, `LS-CANCEL`, keeping under CPU budget |
| `patterns.md` | proven architecture patterns (catalog) |
| `game-design.md` | **what makes a DF game actually fun** — core loop, feedback, balance, pacing (design is a requirement, not optional) |
| `styling.md` | **making it look AWESOME** — palette, MiniMessage, menus, HUD, juice, symbols |
| `workflow-and-gotchas.md` | the edit→check→push loop, push vs deploy, and the mistake checklist |
| `worked-example.md` | **a vetted end-to-end example game** (`examples/mini-arena/`, 8 files, all checks green) — imitate it when starting a NEW game |

## Golden rules (always)
- **Verify, don't assume.** DF is full of quirks (this wiki is those quirks). Unknown
  name → grep `actiondump.json`. Unknown behavior → say so; have the human test in-game.
- **Prefer native actions — don't reinvent.** DF has a built-in action for almost
  everything — even compression (`GzipCompress`), Base64, hashing, JSON, item cooldowns,
  web requests. **Grep `action-reference.md` before building a custom system** (curated
  highlights in `common-actions.md`).
- **Design & styling are requirements, not extras.** Codec-clean ≠ fun or good-looking.
  Apply `game-design.md` and `styling.md` before calling a game finished.
- **Cross-`CallFunc` variables must be `'local'`** (not `'line'`) — line vars don't carry
  between caller and callee (a silent bug that passes `check`). See `variables-and-data.md`.
- **One code line = one `.py` file.** Identity is its **header**, not the filename.
- **Self-check every file**: `python codec/dfpy.py check plots/<proj>/<file>.py`. This now
  both compiles it **and** validates names/chest/scopes/length/loops (see `compile-errors.md`).
  Green = well-formed; it does **not** mean the game logic is right — that's on you.
- **Use MiniMessage `comp('<yellow>…')`, never legacy `§`/`&` color codes.**
- **Plot commands and `<click:run_command:…>` use `@`, never `/`.**
- **Start small.** Build the smallest playable core, have the human push & test, then layer.
