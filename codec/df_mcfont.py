"""df_mcfont.py — render text in the REAL Minecraft font (bitmap atlases).

Loads the actual `ascii.png` / `nonlatin_european.png` / `accented.png` glyph atlases
extracted from the Minecraft client jar (codec/assets/mcfont/) and draws strings exactly
as Minecraft does: per-glyph pixel widths, 1px tracking, integer scaling (NEAREST), a
drop shadow at 25% brightness offset (1,1), bold = double-draw +1px, italic = shear.

This replaces the Consolas approximation so item/menu PNGs match in-game tooltips.

  glyph(cp)                  -> {cell(L alpha), w, ascent, ch} or None (missing)
  advance(cp, bold)          -> pixel advance (MC units, pre-scale)
  run_width(text, bold)      -> total advance
  draw_text(img, x, y, runs, S, shadow=True)  -> draws scaled, returns end x (px)
  ASCENT, LINE                font metrics (MC units)
"""
import json, os
from PIL import Image
import df_text as T

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.path.join(HERE, "assets", "mcfont")

ASCENT = 7      # baseline offset used to align lines (ascii ascent)
LINE = 9        # font line height (MC units); tooltips space lines by 10
_DATA = None
_SPRITE = {}    # (cp, rgb, bold, italic, S) -> RGBA sprite


def _load():
    global _DATA
    if _DATA is not None:
        return _DATA
    meta = json.load(open(os.path.join(ASSET, "meta.json"), encoding="utf-8"))
    glyphs = {}
    for prov in meta["providers"]:                       # MC order: earliest non-empty wins
        im = Image.open(os.path.join(ASSET, prov["file"])).convert("RGBA")
        cols, rows = prov["cols"], prov["rows"]
        cw, ch = prov["img_w"] // cols, prov["img_h"] // rows
        alpha = im.getchannel("A")
        for r, line in enumerate(prov["chars"]):
            for c, chx in enumerate(line):
                if not chx:
                    continue
                cp = ord(chx)
                if cp in glyphs:
                    continue
                cell = alpha.crop((c * cw, r * ch, c * cw + cw, r * ch + ch))
                bb = cell.getbbox()
                if bb is None:                            # empty cell -> not present here
                    continue
                w = bb[2]                                 # rightmost opaque col (exclusive)
                glyphs[cp] = {"cell": cell.crop((0, 0, max(w, 1), ch)),
                              "w": w, "ascent": prov["ascent"], "ch": ch}
    _DATA = (glyphs, {ord(k): v for k, v in meta["space"].items()})
    return _DATA


def glyph(cp):
    g, _ = _load()
    return g.get(cp)


def advance(cp, bold=False):
    g, space = _load()
    if cp in space:
        return space[cp]
    gl = g.get(cp)
    if gl is None:
        return 6 + (1 if bold else 0)     # missing glyph ~ unifont fallback width
    return gl["w"] + 1 + (1 if bold else 0)


def run_width(text, bold=False):
    return sum(advance(ord(c), bold) for c in text)


def _make_sprite(cp, rgb, bold, italic, S):
    key = (cp, rgb, bold, italic, S)
    if key in _SPRITE:
        return _SPRITE[key]
    gl = glyph(cp)
    if gl is None:
        _SPRITE[key] = None
        return None
    mask = gl["cell"]
    if S != 1:
        mask = mask.resize((mask.width * S, mask.height * S), Image.NEAREST)
    if italic:
        # MC italic: shear so the top leans right (~1px per 4px of height, scaled)
        w, h = mask.width, mask.height
        shear = 1.0 / 8.0
        pad = int(h * shear) + S
        big = Image.new("L", (w + pad, h), 0)
        for yy in range(h):
            dx = int((h - 1 - yy) * shear)
            row = mask.crop((0, yy, w, yy + 1))
            big.paste(row, (dx, yy))
        mask = big
    sprite = Image.new("RGBA", mask.size, rgb + (0,))
    sprite.putalpha(mask)
    if bold:
        b = Image.new("RGBA", (mask.width + S, mask.height), (0, 0, 0, 0))
        b.alpha_composite(sprite, (0, 0))
        b.alpha_composite(sprite, (S, 0))
        sprite = b
    _SPRITE[key] = sprite
    return sprite


def draw_text(img, x, y, runs, S, *, shadow=True):
    """Draw runs onto `img` (RGBA) at MC-pixel (x,y) scaled by S. y = line TOP.
    Returns the pen x in image pixels after the last glyph."""
    g, space = _load()
    baseline = (y + ASCENT) * S
    penx = x * S
    for run in runs:
        rgb = T.resolve_rgb(run.get("color"))
        srgb = tuple(c // 4 for c in rgb)               # MC shadow = 25% brightness
        bold = bool(run.get("bold"))
        italic = bool(run.get("italic"))
        for ch in run["text"]:
            cp = ord(ch)
            if cp in space:
                penx += space[cp] * S
                continue
            gl = glyph(cp)
            adv = advance(cp, bold)
            top = baseline - (gl["ascent"] if gl else ASCENT) * S
            sp = _make_sprite(cp, rgb, bold, italic, S)
            if sp is not None:
                if shadow:
                    shp = _make_sprite(cp, srgb, bold, italic, S)
                    img.alpha_composite(shp, (penx + S, top + S))
                img.alpha_composite(sp, (penx, top))
            penx += adv * S
    return penx
