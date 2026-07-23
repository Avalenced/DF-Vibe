# Text and MiniMessage

## Two text value types
- **`txt('…')`** — a **plain string**. Supports `%placeholders` (see `placeholders.md`).
- **`comp('…')`** — **"Styled Text", which DiamondFire parses as MiniMessage.** This is
  the preferred way to format text.

**Rule: always color with MiniMessage via `comp()` — never legacy `§`/`&` codes.** Use
`comp('<yellow>hi')`, not `txt('§ehi')`.

## MiniMessage support (live-tested on DF — all of the below render)
- **Named colors**: `<red> <green> <aqua> <yellow> <gold> <gray> <dark_gray>` … (all 16).
- **Hex**: `<#ff8800>` (and `<color:#ff8800>` / `<c:…>`).
- **Decorations**: `<bold>`/`<b>`, `<italic>`/`<i>`, `<underlined>`/`<u>`,
  `<strikethrough>`/`<st>`, `<obfuscated>`/`<obf>`. Close with `</tag>`; `<reset>` clears.
- **Gradients**: `<gradient:red:blue>…</gradient>`, 3+ colors
  `<gradient:#ff0000:#00ff00:#0000ff>…</gradient>`, optional phase.
- **Rainbow**: `<rainbow>…</rainbow>`, reversed/phased `<rainbow:!3>…</rainbow>`.
- **Content tags (confirmed working):**
  - `<lang:key:arg1:arg2…>` — translatable text. e.g.
    `<lang:death.attack.onFire:Steve>` → "Steve burned to death".
  - `<key:keybind>` — the player's keybind, e.g. `<key:key.jump>` → "Space".
  - `<click:ACTION:value>` — clickable. **For plot commands use `@`, not `/`:**
    `<click:run_command:@spawn>[click]</click>`. Other actions: `copy_to_clipboard`,
    `suggest_command`, `open_url`, `change_page`. **DF restricts some:** `run_command`
    only fires `@` commands; `open_file` is **blocked entirely**; `open_url` is limited to a
    **domain whitelist**: mcdiamondfire.com, mojang.com, minecraft.net, minecraft-heads.com,
    mineskin.org, regex101.com, regexr.com, pronouns.page, namemc.com, dfonline.dev,
    dfplots.net, minecraft.wiki, minecraft.fandom.com, wiki.vg, tk2217.com, plus YouTube /
    Twitch / Discord / Twitter(X) / Wikipedia links. Anything else is stripped.
  - `<hover:show_text:'<red>tooltip'>[hover]</hover>` — tooltip (MiniMessage inside).
  - `<insert:text>[shift-click]</insert>` — shift-click inserts text into chat.
- **Nesting** works: `<green>a <bold>b <red>c</red> b</bold> a`.
- `<newline>`/`<br>`, `<empty>`, `<space>`, `<font:namespace:key>` also available
  (`<empty>`/`<space>` are DF server-specific tags).

**Escaping:** to show a literal `<`, escape with `\`. Tag names are case-insensitive.

## Other text utilities
- Process user-entered colors with `SetVar('TranslateColors', …)` and a *Translation
  Type* (`From & to color`, `From hex to color`, `Strip color`).
- The **Text Value Merging** tag (`Add spaces` / `No spaces`) on `SendMessage` and string
  set-vars controls whether multiple value chunks are space-joined.
- Numbers display with **up to 3 decimal places** (DF's numeric precision limit).
