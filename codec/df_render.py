"""df_render.py — pixel-accurate PNG previews of DiamondFire items and menus.

Renders with the REAL Minecraft font (df_mcfont, atlases from the 26.1.1 client jar) and
Minecraft's exact tooltip chrome, so an item or chest GUI looks like an in-game screenshot:

  item_png(snbt, out)                  one item as a MC tooltip
  items_png(list_of_snbt, out, title)  a column of tooltips
  menu_png(slot_snbt_list, out)        a chest-GUI grid (9 wide) with item swatches

Needs Pillow (raises a clear error if absent).
"""
import df_item as I
import df_text as T

try:
    from PIL import Image, ImageDraw
    import df_mcfont as F
    _HAVE = True
except Exception:
    _HAVE = False

S = 4                # pixels per Minecraft pixel
LINE = 10           # tooltip line spacing (MC px)
SCENE = (24, 24, 28)

# MC tooltip chrome (TooltipRenderUtil); ARGB unpacked to RGBA
BG = (16, 0, 16, 240)        # 0xF0100010
BTOP = (80, 0, 255, 80)      # 0x505000FF
BBOT = (40, 0, 127, 80)      # 0x5028007F


def _require():
    if not _HAVE:
        raise RuntimeError("Pillow is not installed — run `pip install pillow` to use --png")


# ---------------------------------------------------------------------------
# helpers (work in MC px; blit scaled by S)
# ---------------------------------------------------------------------------
def _rect(img, x0, y0, x1, y1, rgba):
    w, h = (x1 - x0) * S, (y1 - y0) * S
    if w <= 0 or h <= 0:
        return
    img.alpha_composite(Image.new("RGBA", (w, h), rgba), (x0 * S, y0 * S))


def _vgrad(img, x0, y0, x1, y1, ctop, cbot):
    h = (y1 - y0)
    strip = Image.new("RGBA", ((x1 - x0) * S, h * S), (0, 0, 0, 0))
    px = strip.load()
    for j in range(h * S):
        t = j / max(h * S - 1, 1)
        col = tuple(round(ctop[k] + (cbot[k] - ctop[k]) * t) for k in range(4))
        for i in range((x1 - x0) * S):
            px[i, j] = col
    img.alpha_composite(strip, (x0 * S, y0 * S))


def _tooltip_chrome(img, x, y, w, h):
    """MC tooltip background + 1px gradient border. Content box is x..x+w, y..y+h (MC px)."""
    _rect(img, x - 3, y - 4, x + w + 3, y - 3, BG)        # top edge
    _rect(img, x - 3, y + h + 3, x + w + 3, y + h + 4, BG)  # bottom edge
    _rect(img, x - 3, y - 3, x + w + 3, y + h + 3, BG)     # middle
    _rect(img, x - 4, y - 3, x - 3, y + h + 3, BG)        # left edge
    _rect(img, x + w + 3, y - 3, x + w + 4, y + h + 3, BG)  # right edge
    _vgrad(img, x - 3, y - 2, x - 2, y + h + 2, BTOP, BBOT)      # left border
    _vgrad(img, x + w + 2, y - 2, x + w + 3, y + h + 2, BTOP, BBOT)  # right border
    _rect(img, x - 3, y - 3, x + w + 3, y - 2, BTOP)      # top border
    _rect(img, x - 3, y + h + 2, x + w + 3, y + h + 3, BBOT)  # bottom border


def _lines_for(snbt):
    s = I.item_summary(snbt)
    blank = [{"text": "", "color": None, **{k: False for k in T.DECOR_KEYS}}]
    lines = [s["runs_name"] or [{"text": s["id"], "color": "white", **{k: False for k in T.DECOR_KEYS}}]]
    for runs in s["runs_lore"]:
        lines.append(T.lore_base(runs) if runs else blank)
    return lines, s


def _line_w(runs):
    return sum(F.run_width(r["text"], r.get("bold")) for r in runs)


def _content_size(lines):
    w = max((_line_w(ln) for ln in lines), default=20)
    h = len(lines) * LINE - 2
    return w, h


def _draw_lines(img, x, y, lines):
    for i, ln in enumerate(lines):
        F.draw_text(img, x, y + i * LINE, ln, S)


# ---------------------------------------------------------------------------
# public renders
# ---------------------------------------------------------------------------
def render_item(snbt, *, scene=SCENE, margin=12):
    """Build and return an RGBA PIL.Image of the item tooltip (in-memory, not saved).

    Used by the desktop editor for a live preview; `item_png` is this + save."""
    _require()
    lines, _ = _lines_for(snbt)
    w, h = _content_size(lines)
    M = margin
    img = Image.new("RGBA", ((w + 8 + 2 * M) * S, (h + 8 + 2 * M) * S), scene + (255,))
    ox, oy = M + 4, M + 4
    _tooltip_chrome(img, ox, oy, w, h)
    _draw_lines(img, ox, oy, lines)
    return img


def render_tooltip(lines, *, scene=SCENE, margin=12):
    """Render an arbitrary list of line-runs (e.g. df_tooltip output) as a tooltip image."""
    _require()
    blank = [{"text": "", "color": None, **{k: False for k in T.DECOR_KEYS}}]
    norm = [ln or blank for ln in lines] or [blank]
    w, h = _content_size(norm)
    M = margin
    img = Image.new("RGBA", ((w + 8 + 2 * M) * S, (h + 8 + 2 * M) * S), scene + (255,))
    ox, oy = M + 4, M + 4
    _tooltip_chrome(img, ox, oy, w, h)
    _draw_lines(img, ox, oy, norm)
    return img


def render_text_line(runs, *, scale=S, pad=1):
    """A small transparent RGBA image of a single styled text line (e.g. the item name)."""
    _require()
    runs = runs or [{"text": "", "color": "white", **{k: False for k in T.DECOR_KEYS}}]
    w = max(1, sum(F.run_width(r["text"], r.get("bold")) for r in runs))
    img = Image.new("RGBA", ((w + 2 * pad) * scale, (LINE + 2 * pad) * scale), (0, 0, 0, 0))
    F.draw_text(img, pad, pad, runs, scale)
    return img


def item_png(snbt, out):
    _require()
    render_item(snbt).convert("RGB").save(out)
    return out


def items_png(snbt_list, out, *, title=None):
    _require()
    cards = [_lines_for(s) for s in snbt_list]
    sizes = [_content_size(c[0]) for c in cards]
    M, gap = 10, 10
    colw = max((w for w, _ in sizes), default=60)
    title_h = (LINE + 6) if title else 0
    total_h = title_h + sum((h + 8 + gap) for _, h in sizes) + gap
    img = Image.new("RGBA", ((colw + 8 + 2 * M) * S, (total_h + 2 * M) * S), SCENE + (255,))
    y = M + title_h
    if title:
        F.draw_text(img, M, M, [{"text": title, "color": "white", **{k: False for k in T.DECOR_KEYS}}], S)
    for (lines, _), (w, h) in zip(cards, sizes):
        _tooltip_chrome(img, M + 4, y + 4, w, h)
        _draw_lines(img, M + 4, y + 4, lines)
        y += h + 8 + gap
    img.convert("RGB").save(out)
    return out


# ---------------------------------------------------------------------------
# chest-GUI grid
# ---------------------------------------------------------------------------
COLS = 9
CELL = 18          # MC px per slot (vanilla)
PANEL = (198, 198, 198)
SLOT = (139, 139, 139)
SLOT_DK = (55, 55, 55)
SLOT_LT = (255, 255, 255)


def _slot_swatch(snbt):
    try:
        s = I.item_summary(snbt)
    except Exception:
        return None
    from df_menu import _PANE_RGB
    name = (s["name"] or "").strip()
    if "glass_pane" in s["id"] and not name:
        return (_PANE_RGB.get(s["id"], (90, 90, 90)), "", True)
    rgb = T.resolve_rgb(s["runs_name"][0].get("color")) if s["runs_name"] else (190, 190, 190)
    return (rgb, name[:2] if name else s["id"][:2], False)


def menu_png(slot_list, out, *, title=None):
    _require()
    rows = max(1, (len(slot_list) + COLS - 1) // COLS)
    M = 7
    title_h = (LINE + 3) if title else 0
    W = COLS * CELL + 2 * M
    H = rows * CELL + 2 * M + title_h
    img = Image.new("RGBA", (W * S, H * S), PANEL + (255,))
    if title:
        F.draw_text(img, M, M - 1, [{"text": title, "color": "dark_gray", **{k: False for k in T.DECOR_KEYS}}], S)
    y0 = M + title_h
    for i in range(rows * COLS):
        r, c = divmod(i, COLS)
        x, y = M + c * CELL, y0 + r * CELL
        # vanilla slot: dark top/left + light bottom/right bevel around a gray well
        _rect(img, x, y, x + CELL, y + CELL, SLOT_DK + (255,))
        _rect(img, x + 1, y + 1, x + CELL, y + CELL, SLOT_LT + (255,))
        _rect(img, x + 1, y + 1, x + CELL - 1, y + CELL - 1, SLOT + (255,))
        if i >= len(slot_list) or not slot_list[i]:
            continue
        sw = _slot_swatch(slot_list[i])
        if not sw:
            continue
        rgb, label, pane = sw
        ix, iy, sz = x + 1, y + 1, CELL - 2
        _rect(img, ix, iy, ix + sz, iy + sz, rgb + (255,))
        _rect(img, ix, iy, ix + sz, iy + 1, tuple(min(255, c + 40) for c in rgb) + (255,))
        _rect(img, ix, iy + sz - 1, ix + sz, iy + sz, tuple(c // 2 for c in rgb) + (255,))
        if label:
            lum = 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]
            fg = "black" if lum > 140 else "white"
            lw = F.run_width(label)
            F.draw_text(img, ix + max(0, (sz - lw) // 2), iy + (sz - 8) // 2,
                        [{"text": label, "color": fg, **{k: False for k in T.DECOR_KEYS}}], S)
    img.convert("RGB").save(out)
    return out
