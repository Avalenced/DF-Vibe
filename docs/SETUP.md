# DF VIBE — Setup

Two parts: the **Python codec** (already here, no build needed) and the **Fabric
mod** (you build once).

The mod is **`mod-standalone/`** — a fork of CodeClient with its API built in, the
`/auth` prompt removed, and the `/df` tooling merged in. **One jar, no separate
CodeClient, never asks for `/auth`.**

## Prerequisites

- **Python 3** (you have 3.14). The codec uses only the standard library.
- **A JDK** to build — your JDK 25 works as-is. The build runs Gradle on it and
  auto-downloads a Java 21 toolchain (via the foojay resolver) just for compiling, so
  you don't need to install anything. (Verified: it compiles clean here.)
- You on your dev plot in code mode. (CodeClient is **not** a separate requirement —
  it's baked into the jar.)

## 1. Build + install the mod

Built from `mod-standalone/` (a CodeClient fork; MC 1.21.11, Loom 1.16.2, Java 21). Build it:

`<DF-Vibe>` below = wherever you put this project folder.

```powershell
cd "<DF-Vibe>\mod-standalone"
.\gradlew build
```

Install into your Minecraft instance's `mods\` folder, e.g.:

```
<your Minecraft instance>\mods\
```

**Important — do NOT also install stock CodeClient.** DF Vibe bundles CodeClient's dev
utilities, so it declares itself incompatible with the `codeclient` mod (`breaks`) and won't
load alongside it. Remove any `CodeClient-*.jar` from `mods\` first.

So `mods\` ends up with `dfvibe-1.0.0.jar` + Fabric API (+ YACL/ModMenu if
you want the config screen). Launch the game — no `/auth`, ever.

(The `-sources.jar` the build also produces is for development only — don't install it.)

## 2. Configure (once, in-game)

Easiest: **`/df path auto`**. It creates a `DF Vibe` folder in your Minecraft directory with
`plots\`, `builds\` and a ready-to-run `codec\` (extracted from the mod), and points the config
at them. You just need Python installed. Then `/df status` to confirm.

Or set the folders by hand:

```
/df path plots  <DF-Vibe>\plots
/df path codec  <DF-Vibe>\codec
/df path python <DF-Vibe>\.venv\Scripts\python.exe
```

(`/df path` with no arguments lists all four folders. `/df path python` is optional if
`python` on PATH works; pointing at the venv is safest.)

## 3. The loop

```
/df pull myGame      # scan plot -> plots\myGame\*.py  (first time: names + links the plot)
/df pull             # later: no name -- detects the plot via /locate and resumes its project
# ...you edit plots\myGame\*.py...
/df push             # place changed/added lines back (swap; safe to repeat, never deletes)
```

- **No `confirm` needed.** Commands run immediately. If you're standing on a *different*
  plot than the project came from, push/deploy/fill just print a **heads-up warning** and
  proceed — nothing blocks. (`/df backup` first if you're unsure.)
- **`/df deploy`** — CLEAR the plot and place ALL your files (exact mirror). Use this to
  (a) delete lines you removed locally, or (b) clone a whole codebase onto a fresh or different
  plot. By default deploy lays the codespace out **organized into layer-bands** (a "layer" =
  one Y-level, every 5 blocks; ~6 lines fit across a layer):
  - **Each type gets its own band of layers** — events at the bottom (player, then game, then
    entity), then functions, then processes. **A type never shares a layer with another type.**
  - Within a band, **every line gets its own column** (one line per line) — 6 across a layer,
    then up to the next — **as uncompact as possible**, using as many layers as the code needs.
  - **If it can't all fit one-per-line**, events still stay one-per-line, but functions and
    processes **pack multiple per column** just enough to fit: each type gets a run of columns
    **proportional to its total line length**, and lines are balanced so the columns are evenly
    dense (roughly equally uncompacted). Only if even that overflows are the extra lines reported
    in chat (then use `-underground` / `-compact` / a bigger plot).
  - **The whole layout is computed up front**, so `-batch` only paces placement — it can no longer
    make chunks overlap or misalign (that old bug is gone). A plain `/df deploy` auto-chunks (50 at
    a time) so it won't stall on one giant all-at-once place.
- **`/df deploy -gap N`** — leave **N empty layers between the bands** (Tab-completes). Plain
  `-gap` = 1; `-gap 2` = two blank layers between each band; **`-gap -1`** = don't separate bands
  at all (one continuous fill, types may share a layer). Empty layers are auto-trimmed if they'd
  push code out of the codespace.
- **`/df deploy -compact`** — **minimal layout**: ignore type bands entirely and pack *every* line
  end-to-end down columns — the smallest possible footprint. It orders lines **largest-first** and
  bin-packs each column close to the plot's code length (it does **not** lead with events). Use this
  for big games that won't fit one-per-line, even underground.
- **`/df deploy -sort alpha|size|type`** — order **within a band** (Tab to autocomplete):
  - `alpha` (default) — line name A→Z.
  - `size` — largest line first, so **small lines cluster with other small lines**.
  - `type` (or `none`) — keep file order within a type.
- **Progress bar:** every place op (deploy/push/fill/restore) shows a **live bar in your action
  bar** (above the hotbar): `Deploy [████████░░░░] 53% 204/388 ~1m12s left`. The ETA is projected
  from the *observed* placement rate, so it self-corrects (it doesn't guess a fixed per-line cost).
- **`-batch` is now safe for deploy.** Because deploy computes the full layout up front and places
  at fixed positions, chunking only adds the anti-flood breather — the layout is identical whether
  you batch or not. (Plain `/df deploy` already chunks 50 at a time.)
- **`/df deploy -underground`** — place into the **underground** codespace, starting from the
  bottom (floor y=5) instead of y=50, which gives far more vertical room for big games. It
  **runs `/p codespace underground create` for you** (idempotent), waits for the space to build,
  then forces placement to the bottom (CodeClient's own underground auto-detect is flaky because
  it samples under your player while the placer flies you around). Aliases: `-ug`, `underground`.
  (Combine freely, e.g. `-underground -gap 1 -batch 50`.)
  **First-time gotcha:** creating an underground codespace puts down a **glass divider layer**
  (around y=49) that the placer can't fly through. If a deploy stalls there, stand above it and
  run **`/p codespace remove`** once to clear it; after that the underground is reused and it
  won't come back.
- **`/df fill`** — place **only the lines that are missing** from the plot, without clearing or
  touching what's already there. Big deploys on DiamondFire occasionally fail to seat a few lines
  (the placer flies you around and the anti-cheat rubber-bands it); `fill` scans the plot, compares
  to your files by identity, and re-places just the gaps. Run it once or twice after a deploy and
  you converge to 100% — far better than re-`deploy`ing (which clears and re-rolls all of them).
  Takes the same flags (`-batch`, `-sort`, `-gap`, `-compact`, `-underground`, `-log`).
- **`/df backup`** snapshots the plot to `.df-backups/`; **`/df backup list`** shows them;
  **`/df backup load <file>`** restores one (clear + place). The `<file>` arg tab-completes
  from your backups.
- **`/df end`** (aliases `cancel`/`stop`) — abort the current pull/push/deploy early. If you
  stop mid-place the plot may be left partially changed.
- **Big games — `-batch` and `-log`:** placing happens by flying your player block-by-block, so
  a huge deploy in one shot can time out (or get you kicked for "Timed out"). Place in chunks
  instead: `/df deploy -batch 50`, with a 1-second breather between chunks that keeps DiamondFire's
  anti-cheat from flood-disconnecting you. (For deploy/restore the layout is computed up front, so
  batching only paces it — it can't change the layout. `/df deploy` already auto-chunks 50 at a
  time.) Add **`-log`** (e.g. `/df push -batch 50 -log`) to write a step-by-step diagnostic log —
  raw place responses and any stack traces — to `<workspace>\.df-log.txt`. Errors are logged there
  even without `-log`.
- All these flags work on **push/deploy/fill** and Tab-autocomplete, so you don't have to
  remember them. `/df use <name>` switches the active project; `/df status` / `/df whereami` for info.

**Auth:** there is **no `/auth`** — the bundled API auto-approves the tool, so sync
just works.

**After updating the mod/codec, re-pull your projects.** Files pulled by an older
codec can be slightly off; `/df push` will warn ("pulled with an older codec") until
you `/df pull` to re-sync. (This matters most for big/old plots like `anarchy`.)

**Identity:** the mod reads your plot id from `/locate` (e.g. `parkour practice
[173110]`). `pull <name>` links that id to the folder, so later a bare `/df pull`
knows which project to use, and `push`/`deploy` warn if you've wandered to another plot.
If DF ever renames `/locate`, set it with the `locateCommand` field in `df-vibe.json`.

## How it fits together

```
 Minecraft ── CodeClient (ws://localhost:31375)
     │                 ▲ scan / place
   DF VIBE mod ────────┘
     │  /df pull,push          spawns
     ├─ reads plot id via /locate (auto-detect)
     └─ subprocess ─► codec\dfpy.py  ◄─► plots\<project>\*.py  ◄─ you edit
```

The mod handles the game side (chat commands, CodeClient socket, files, backups).
The Python codec does the lossless template↔`.py` translation. See `AI_GUIDE.md`
for how to edit the `.py` files.

## Troubleshooting

- **`Pull failed: ConnectException` / "can't reach CodeClient"** — make sure you're on
  the plot in dev mode. The API is built into the jar and always on, so this should be
  rare; if it persists, check nothing else is using port 31375.
- **It asks for `/auth`** — shouldn't happen (there's no `/auth`). If it does, you're
  probably still running an old `df-vibe` jar + stock CodeClient; remove both and install
  `dfvibe-*.jar` instead.
- **Game won't load / "incompatible mod" involving codeclient** — you have stock CodeClient
  installed alongside DF Vibe. DF Vibe bundles CodeClient's utilities and refuses to run with
  it. Remove any `CodeClient-*.jar` from `mods\`.
- **`/df push` says "pulled with an older codec"** — run `/df pull` to re-sync that
  project; its files were captured by an older codec and may have drifted.
- **`/df push` says "unusually many lines changed"** — if you didn't edit that many,
  `/df pull` first (old/drifted baseline) before pushing.
- **Deploy of a big plot fails / "no response"** — CodeClient's place can choke on a
  huge codebase, and a single line that's too large hits DiamondFire's template-size
  limit ("Unable to create code template"). Use a larger plot size (MASSIVE/MEGA) for
  the target, and split oversized lines into smaller functions. The standalone fork now
  **auto-skips any line too long for your plot's code length** (with a warning) so the
  place can't run off the plot edge — but those lines won't appear until you split them
  or use a bigger plot. Normal `/df push` is unaffected (it only re-places changed lines).
- **A line is too big for DF to even scan ("Exceeded the code data size limit")** — on
  `/df pull` the fork can't get a template for that line, so it **rebuilds it from the
  physical code blocks** (signs + chest contents via pick-item + bracket pistons) and
  includes it in the pull instead of dropping it. This is automatic on every pull. Such a
  line still can't be re-deployed until you split it (it's over the template limit), but at
  least you can now see and edit it locally. Salvage equips an offhand +reach item so it picks
  chests from a distance (toggle with `/df config salvageReach off`).
- **`/df config`** — view/change all settings (Tab-completes keys and values); e.g.
  `/df config salvageReach off`, `/df config rank Mythic`.
- **Deploy "ran out of space" / vertical room** — the default organized layout already packs
  functions/processes densely, but events take one column each. For a very big game, add
  **`-compact`** for the minimal layout (everything packed, smallest footprint), and/or
  **`-underground`** for ~9× the vertical layers (it creates the underground codespace for you).
  e.g. `/df deploy -underground -compact`.
- **A few lines didn't place / "Invalid template placement"** — on a big deploy, CodeClient
  occasionally fails to seat a line (it flies your player to each spot and DiamondFire's
  anti-cheat can rubber-band the movement). It now **retries each line, then skips it** after a
  few tries (it won't freeze) and tells you in chat (`» ... timed out` / `» ... skipped`). To
  fill the skipped ones, run **`/df fill`** — it places only what's missing (don't re-`deploy`,
  which clears and re-rolls everything). `-batch 25` also helps by pacing the place gentler.
- **A line was skipped on pull (`.df-failed.json`)** — that line had a shape the codec
  couldn't read; the file is saved there for diagnosis. Everything else still pulled.
