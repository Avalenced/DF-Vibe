"""df_style.py — lint DiamondFire items/text against styling.md (the executable style guide).

`dfpy.py check` proves code is *valid*; this proves it *looks* right. It flags the visual
bugs that pass `check` but read as amateur in-game:

  ITALIC      a custom_name / lore line that will render *italic* in-game (Minecraft
              italicizes custom names unless a run explicitly sets italic:0b — the #1
              hand-written-item bug; df_item.build_item never trips it).
  LEGACY      a legacy §/& color code in a text value (styling.md: MiniMessage only).
  LORE_COLOR  a visible lore run with no color — renders dark_purple in-game, not white.
  NO_LORE     a menu/clickable item (has a hypercube tag) with a name but no lore.
  OFF_PALETTE a color not in the project's palette.json (only when that file exists).

Plus a palette report: every color used and how often, so inconsistency is visible.

  lint_source(name, src, palette=None) -> list[Issue]
  palette_counts(src) -> dict(color -> count)
  lint_project(py_files, palette=None) -> (issues, palette_counts)
"""
import re
import df_item as I
import df_text as T
from df_validate import Issue  # reuse the validator's record type / printer

LEGACY_SECTION = re.compile("\xa7[0-9a-fk-orA-FK-OR]")
LEGACY_AMP = re.compile(r"(?<![\w&])&(?:[0-9](?!=)|[a-fk-or](?![a-z=]))")
URL_RE = re.compile(r"(?:https?://|www\.)\S+")
TEXTVAL_RE = re.compile(r"(?:txt|comp)\('((?:\\.|[^'\\])*)'\)")
DECOR_KEYS = T.DECOR_KEYS


def _visible(runs_text):
    return bool(runs_text and runs_text.strip())


def _italic_problems(comp, where, out):
    """Walk a custom_name/lore component; flag visible text that renders italic.
    italic inherits; for a *name* an unset italic means ITALIC ON (MC default)."""
    def walk(node, inherited):
        if isinstance(node, str):
            if _visible(node) and inherited is not False:
                reason = "no explicit italic (Minecraft italicizes names by default)" \
                    if inherited is None else "italic is set true"
                out.append((node[:24], reason))
            return
        if isinstance(node, list):
            for n in node:
                walk(n, inherited)
            return
        if not isinstance(node, dict):
            return
        eff = inherited
        if "italic" in node:
            eff = _truthy(node["italic"])
        txt = node.get("text", "")
        if _visible(txt) and eff is not False:
            reason = "no explicit italic (Minecraft italicizes names by default)" \
                if eff is None else "italic is set true"
            out.append((txt[:24], reason))
        for c in node.get("extra", []):
            walk(c, eff)
    walk(comp, None)


def _truthy(v):
    if isinstance(v, str):
        return v not in ("0", "0b", "false", "False", "")
    return bool(v)


def lint_source(name, src, palette=None):
    out = []
    # 1. items: italic + no-lore
    for snbt in I.extract_items(src):
        try:
            d = I.parse_snbt(snbt)
        except Exception:
            continue
        comp = d.get("components", {}) if isinstance(d, dict) else {}
        bad = []
        if "minecraft:custom_name" in comp:
            _italic_problems(comp["minecraft:custom_name"], name, bad)
        for ln in comp.get("minecraft:lore", []):
            _italic_problems(ln, name, bad)
        for txt, why in bad:
            out.append(Issue("WARN", "ITALIC", name,
                             f"{txt!r} will render italic in-game — {why}; add italic:0b"))
        # uncolored lore renders dark_purple (MC's lore base style), not white
        plain = []
        for ln in comp.get("minecraft:lore", []):
            for r in T.component_to_runs(ln):
                if _visible(r["text"]) and not r.get("color"):
                    plain.append(r["text"][:24])
                    break
        if plain:
            out.append(Issue("WARN", "LORE_COLOR", name,
                             f"{len(plain)} lore line(s) (e.g. {plain[0]!r}) with no color render "
                             f"dark_purple in-game — set an explicit color, e.g. <gray>"))
        # no-lore on a menu *button* (a tag that names a menu/tab/button/category),
        # not on gameplay-tagged items (price/dupeability/etc.) or unnamed data items
        cd = comp.get("minecraft:custom_data", {})
        pbv = (cd.get("PublicBukkitValues") if isinstance(cd, dict) else None) or {}
        menu_tag = any(re.search(r"menu|button|tab|categor|shop|select|page",
                                 str(k), re.I) for k in pbv)
        if menu_tag and "minecraft:custom_name" in comp and "minecraft:lore" not in comp:
            s = I.item_summary(snbt)
            if (s["name"] or "").strip():
                out.append(Issue("WARN", "NO_LORE", name,
                                 f"menu button {s['name']!r} has a name but no lore — add a description"))
    # 2. legacy codes (grouped: one issue per file, with a count + sample)
    sec = LEGACY_SECTION.findall(src)
    if sec:
        m = LEGACY_SECTION.search(src)
        sample = src[max(0, m.start() - 6):m.start() + 8]
        out.append(Issue("WARN", "LEGACY", name,
                         f"{len(sec)} legacy § color code(s) (e.g. {sample!r}) — use MiniMessage comp('<color>…')"))
    amp = [tv for tv in TEXTVAL_RE.findall(src) if LEGACY_AMP.search(URL_RE.sub("", tv))]
    if amp:
        out.append(Issue("WARN", "LEGACY", name,
                         f"{len(amp)} legacy & color code(s) (e.g. {amp[0][:24]!r}) — use MiniMessage <color> tags"))
    # 3. off-palette
    if palette:
        pal = {c.lower() for c in palette}
        for color, _ in palette_counts(src).items():
            if color and color.lower() not in pal:
                out.append(Issue("WARN", "OFF_PALETTE", name,
                                 f"color {color!r} is not in the project palette"))
    return out


def palette_counts(src):
    counts = {}
    for snbt in I.extract_items(src):
        try:
            for runs in _all_runs(I.parse_snbt(snbt)):
                for r in runs:
                    c = r.get("color")
                    if c:
                        counts[c] = counts.get(c, 0) + 1
        except Exception:
            continue
    for tv in TEXTVAL_RE.findall(src):
        for c in re.findall(r"<(#[0-9a-fA-F]{6}|[a-z_]+)>", tv):
            cc = T._norm_color(c) or (c if c in T.NAMED else None)
            if cc:
                counts[cc] = counts.get(cc, 0) + 1
    return counts


def _all_runs(d):
    comp = d.get("components", {}) if isinstance(d, dict) else {}
    if "minecraft:custom_name" in comp:
        yield T.component_to_runs(comp["minecraft:custom_name"])
    for ln in comp.get("minecraft:lore", []):
        yield T.component_to_runs(ln)


def lint_project(py_files, palette=None):
    issues, counts = [], {}
    for name, src in py_files:
        issues += lint_source(name, src, palette=palette)
        for c, n in palette_counts(src).items():
            counts[c] = counts.get(c, 0) + n
    return issues, counts
