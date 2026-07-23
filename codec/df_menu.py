"""df_menu.py — preview and scaffold DiamondFire chest-GUI menus.

A DF menu is placed by `PlayerAction('ShowInv', <27 items>)` (rows 0-2) and optionally
`PlayerAction('ExpandInv', <more items>)` (rows 3-5 of a double chest). This module reads
those item lists out of a .py code line and lays them out as a 9-wide grid you can see in
the terminal, and scaffolds a bordered menu skeleton you can fill in.

  grid_from_source(src)   -> list[str]   ordered raw SNBT, one per slot ('' = empty)
  render_grid(items, ...) -> str         ANSI 9-wide grid + legend
  scaffold(rows, title, ...) -> str      a ShowInv(/ExpandInv) PlayerAction line, bordered
"""
import re
import df_item as I
import df_text as T

COLS = 9
SHOWINV_RE = re.compile(r"PlayerAction\(\s*'(ShowInv|ExpandInv)'\s*,(.*?)\)\s*$", re.S | re.M)


def _split_items(arglist):
    return [s for s, _ in I.extract_items_slots(arglist)]


def grid_from_source(src):
    """Concatenate ShowInv then ExpandInv item lists into a flat slot array.
    Items carry their explicit slot= when the chest is sparse; gaps stay ''."""
    show, expand = [], []
    for m in SHOWINV_RE.finditer(src):
        kind, body = m.group(1), m.group(2)
        pairs = I.extract_items_slots("PlayerAction('x'," + body + ")")
        _place(show if kind == "ShowInv" else expand, pairs)
    if expand and len(show) < 27:        # ExpandInv items start at slot 27
        show += [""] * (27 - len(show))
    return show + expand


def _place(slots, pairs):
    cur = len(slots)
    for snbt, slot in pairs:
        if slot is not None:
            cur = slot
        while len(slots) <= cur:
            slots.append("")
        slots[cur] = snbt
        cur += 1


def _cell_color(snbt):
    """Pick a representative RGB for a slot: its name's first color, else a material tint."""
    try:
        s = I.item_summary(snbt)
    except Exception:
        return None, " ", False
    runs = s["runs_name"]
    pane = "glass_pane" in s["id"]
    sym = " "
    rgb = None
    if runs:
        rgb = T.resolve_rgb(runs[0].get("color"))
        first = (s["name"] or "").strip()
        sym = first[0] if first else " "
    if pane and not (s["name"] or "").strip():
        # blank named pane -> a colored frame block tinted by the pane's dye color
        rgb = _PANE_RGB.get(s["id"], (60, 60, 60))
        sym = "█"
    return rgb, sym, pane


_PANE_RGB = {
    "white_stained_glass_pane": (240, 240, 240), "light_gray_stained_glass_pane": (150, 150, 150),
    "gray_stained_glass_pane": (80, 80, 80), "black_stained_glass_pane": (30, 30, 30),
    "blue_stained_glass_pane": (60, 90, 200), "light_blue_stained_glass_pane": (90, 160, 230),
    "red_stained_glass_pane": (200, 60, 60), "green_stained_glass_pane": (80, 190, 80),
    "lime_stained_glass_pane": (130, 220, 90), "yellow_stained_glass_pane": (230, 210, 80),
    "purple_stained_glass_pane": (150, 70, 200), "magenta_stained_glass_pane": (210, 90, 200),
    "orange_stained_glass_pane": (230, 140, 60), "cyan_stained_glass_pane": (70, 180, 190),
    "pink_stained_glass_pane": (235, 150, 190), "brown_stained_glass_pane": (130, 90, 60),
}


def render_grid(items, *, title=None, legend=True):
    out = []
    if title:
        out.append("  " + T.ansi(T.parse_mm(title)))
    rows = (len(items) + COLS - 1) // COLS
    legend_items, seen = [], {}
    out.append("  ┌" + "───" * COLS + "┐")
    for r in range(rows):
        line = ["  │"]
        for c in range(COLS):
            idx = r * COLS + c
            if idx >= len(items) or not items[idx]:
                line.append("   ")
                continue
            rgb, sym, pane = _cell_color(items[idx])
            if not pane:
                key = items[idx]
                if key not in seen:
                    seen[key] = len(legend_items) + 1
                    legend_items.append((seen[key], items[idx]))
                label = "%2d" % seen[key]
                rr, gg, bb = rgb or (200, 200, 200)
                line.append("\x1b[38;2;%d;%d;%dm%s \x1b[0m" % (rr, gg, bb, label))
            else:
                rr, gg, bb = rgb
                line.append("\x1b[38;2;%d;%d;%dm █ \x1b[0m" % (rr, gg, bb))
        line.append("│")
        out.append("".join(line))
    out.append("  └" + "───" * COLS + "┘")
    if legend and legend_items:
        out.append("  legend:")
        for n, snbt in legend_items:
            out.append("   " + I.preview(snbt, index=n))
    return "\n".join(out)


# ---------------------------------------------------------------------------
# scaffold a bordered menu
# ---------------------------------------------------------------------------
def scaffold(rows=6, *, title="<gradient:#5c9eff:#3d5aff><b>✦ Menu", border="gray_stained_glass_pane",
             fill=None):
    """Return PlayerAction ShowInv(+ExpandInv) lines for an empty bordered menu.
    rows: 1..6 (a double chest is 6). Border = outer frame; fill = inner blank
    (or None = empty interior, emitted as slot= gaps)."""
    rows = max(1, min(6, rows))
    n = rows * COLS
    border_item = I.build_literal(border, name=" ")
    fill_item = I.build_literal(fill, name=" ") if fill else None
    slots = []
    for i in range(n):
        r, c = divmod(i, COLS)
        edge = (r == 0 or r == rows - 1 or c == 0 or c == COLS - 1)
        slots.append(border_item if edge else fill_item)

    lines = []
    lines.append("PlayerAction('SetInvName', comp('%s'))" % title.replace("'", "\\'"))
    lines.append("PlayerAction('ShowInv', %s)" % _slot_args(slots[:27]))
    if n > 27:
        lines.append("PlayerAction('ExpandInv', %s)" % _slot_args(slots[27:n]))
    return "\n".join(lines)


def _slot_args(slots):
    """Item literals for one chest page; explicit slot= once the page has gaps."""
    present = [(i, s) for i, s in enumerate(slots) if s]
    if len(present) == len(slots):
        return ", ".join(s for _, s in present)
    return ", ".join("%s, slot=%d)" % (s[:-1], i) for i, s in present)
