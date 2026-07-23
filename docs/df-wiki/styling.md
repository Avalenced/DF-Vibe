# Styling — making a DF game look AWESOME

Great-looking DF games are not an accident — they apply a consistent visual language
everywhere. Plain `§e` text, default item names, and a bare HUD read as amateur. Aim for a
cohesive, premium look. Use `comp()` (MiniMessage) for **all** text — never legacy `§`/`&`.

## 1. A consistent visual identity
- **Pick a palette** — 1–2 brand colors + an accent + grays for body text. Use it
  *everywhere* (titles, menus, messages, HUD). Inconsistent colors look messy.
- **Gradients for anything important:** `<gradient:#ff5e62:#ff9966>TITLE</gradient>`. Reserve
  gradients/bold for headers; keep body text `<gray>` so the important things pop.
- **A prefix/brand mark** on system messages, e.g. `<gradient:…>✦ Game</gradient> <dark_gray>»<gray>`.

## 2. Typography & symbols
- **Hierarchy:** bold + gradient for titles, `<white>` for values, `<gray>` for labels,
  `<dark_gray>` for separators/decoration.
- **Unicode symbols** add huge polish (sparingly, consistently): `✦ ✧ ❖ ◆ ➤ ► ⬩ ★ ⚔ ⚡ ❤ ⛨ ✔ ✖ ⏳ »`.
  Use a symbol per concept (⚔ kills, ❤ health, ⏳ cooldown).
- **Small-caps / fancy fonts** (`ᴋɪᴛ ᴘᴠᴘ`) and decorative separators (`▬▬▬▬▬`, `───────`) make
  menus and tab lists look designed. Many plots use a resource-pack font via `<font:…>`.

## 3. Menus that look designed (not a row of items)
- Build with `ShowInv` + `SetInvName` (`world-and-regions.md` / `common-actions.md`).
- **Border the GUI** with named-blank glass panes (`custom_name:{text:" "}`) so items sit in
  a frame; center your real items; leave breathing room.
- Every clickable item: a **gradient/colored name** + **lore lines** (`minecraft:lore`)
  describing it (`<gray>` body, `<yellow>` key stats), and a fitting icon material/head.
- Consistent slot layout across menus; a title with your brand mark.

## 4. A full HUD (this alone makes a game feel "real")
Layer multiple displays — top games use all of them:
- **Action bar** — live per-tick stats (`❤ 18  ⚔ Streak 3`).
- **Boss bar** — current objective / timer / event ("Next event in 2:00").
- **Sidebar scoreboard** (`SetSidebar`) — a right-side panel: kills, streak, kit, players online.
- **Tab list** (`SetTabListInfo`) — branded header/footer with stats.
- **Titles/subtitles** for big moments (deploy, victory, milestone).

## 5. Juice — particles, sound, motion
Pair `styling` with feedback (`game-design.md` §2). Every meaningful event gets a
**particle + sound**. Examples: ability cast → particle burst + themed sound; kill → crit
particles + `Player Level Up`; level/streak milestone → fireworks + broadcast. A title for
the big stuff. This is what makes a game feel alive and expensive. Author particles as
`raw_item('part', …)` — shape + copy-paste examples in `../AI_GUIDE.md` (Particles
section); never an empty `data:{}` on a particle that has fields.

## 6. The world itself
- **Cohesive, themed builds** — not random blocks. A consistent palette/biome reads as
  intentional (see the noise terrain in `world-and-regions.md`: coherent grass/stone/sand,
  not random texture).
- **Holograms** (text-display entities / armor stands) for signs, leaderboards, labels.
- **Custom heads / item models** for icons and decoration.
- Detail passes: edges, props, lighting, particles in the ambient world.

## 7. Rules of thumb
- MiniMessage everywhere; one palette; symbols with meaning; gradients for headers only.
- If a screen has only default-white text and no symbols/borders, it's not done.
- Compare against a polished reference game and match the bar before calling it finished.

## 8. Tools — generate it, see it, lint it (don't hand-write SNBT)
This guide is executable. Use the codec tools (full syntax in `../AI_GUIDE.md`):
- **Make** items/text: `dfpy.py mkitem <mat> --name "<mm>" --lore "<mm>" …` and
  `dfpy.py text "<mm>"` — write names/lore in MiniMessage; the tool emits the exact
  canonical SNBT with `italic:0b` baked in (no italic-name bug).
- **See** it before pushing: `dfpy.py preview <file.py>` (terminal tooltips, `--png` for an
  image) and `dfpy.py menu <file.py>` (renders the chest GUI grid; `--new` scaffolds a
  bordered menu).
- **Lint** the look: `dfpy.py style <project> [--palette]` flags italic names, legacy `§`/`&`
  codes, no-lore menu buttons, and off-palette colors (drop a `palette.json` with
  `{"colors":[…]}` in the project to enforce one palette).
- **Edit** by hand (human): the **DF Item Editor** desktop app (`DF Item Editor.bat` or
  `dfpy.py edit [file.py]`) — a live in-game-accurate tooltip preview with the fancy gradient
  border, a MiniMessage palette (the `<>` codes only), and lore/material/tag editing. Open a
  `.py` line, tweak an item visually, write it back (then `/df push`), or copy the literal.
