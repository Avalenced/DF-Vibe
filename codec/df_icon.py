"""df_icon.py — render the actual in-game *item icon* (sprite) for a DF item.

Given an item's SNBT, produce the little inventory icon Minecraft would show:
  * flat item textures (swords, food, ingots, dyes, …)            -> assets/mcitems/item/<id>.png
  * blocks & glass panes (menu borders, stone, …)                 -> assets/mcitems/block/<id>.png
  * player heads (the #1 DF menu material) — decodes the profile  -> face + hat from the skin
    base64 texture, fetches the skin (cached to assets/headcache/), composites the face.
  * dyed items (leather armor, 1000+ DF uses) — tints the base    -> multiply by dyed_color (+overlay)
  * a glint sheen for glowing / enchanted items.

  icon_for_snbt(snbt, px=64) -> PIL.Image (RGBA, px x px), or a placeholder if unknown.

Textures were extracted from the Minecraft client jar into codec/assets/mcitems/.
"""
import os
import io
import json
import base64
import hashlib
import urllib.request

try:
    from PIL import Image, ImageChops
    _HAVE = True
except Exception:
    _HAVE = False

import df_item as I

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "assets", "mcitems")
HEADCACHE = os.path.join(HERE, "assets", "headcache")

_PNG = {}          # path -> Image (16x16 base), cached
_MEMO = {}         # render key -> base Image (pre-scale)
_GLINT = None
_GLINT_LOADED = False


def available():
    return _HAVE and os.path.isdir(TEX)


# --- texture loading -------------------------------------------------------
def _png(path):
    if path in _PNG:
        return _PNG[path]
    im = None
    if os.path.exists(path):
        try:
            im = Image.open(path).convert("RGBA")
        except Exception:
            im = None
    _PNG[path] = im
    return im


def _item_tex(mat):
    return _png(os.path.join(TEX, "item", mat + ".png"))


def _block_tex(mat):
    return _png(os.path.join(TEX, "block", mat + ".png"))


def _resolve_block(mat):
    """Item icon for a block id (handles panes/bars that map to a block texture)."""
    cands = [mat]
    if mat.endswith("_pane"):
        cands.append(mat[:-5])           # white_stained_glass_pane -> white_stained_glass, glass_pane -> glass
    if mat.endswith("_wall"):
        cands.append(mat[:-5])
    for c in cands:
        im = _block_tex(c)
        if im is not None:
            return im
    return None


# --- dyeing ----------------------------------------------------------------
def _dye_rgb(dyed):
    if isinstance(dyed, int):
        v = dyed & 0xFFFFFF
    elif isinstance(dyed, dict):
        v = int(dyed.get("rgb", 0)) & 0xFFFFFF
    else:
        return None
    return (v >> 16 & 255, v >> 8 & 255, v & 255)


def _multiply_tint(base, rgb):
    tint = Image.new("RGBA", base.size, rgb + (255,))
    out = ImageChops.multiply(base.convert("RGBA"), tint)
    out.putalpha(base.getchannel("A"))
    return out


def _apply_dye(mat, base, dyed):
    rgb = _dye_rgb(dyed)
    if rgb is None:
        return base
    tinted = _multiply_tint(base, rgb)
    overlay = _item_tex(mat + "_overlay")     # leather armor's undyed trim
    if overlay is not None:
        tinted.alpha_composite(overlay)
    return tinted


# --- player heads ----------------------------------------------------------
def _profile_value(prof):
    if not isinstance(prof, dict):
        return None
    for p in prof.get("properties", []) or []:
        if isinstance(p, dict) and p.get("name") == "textures" and p.get("value"):
            return p["value"]
    return None


def _fetch_skin(url):
    os.makedirs(HEADCACHE, exist_ok=True)
    p = os.path.join(HEADCACHE, hashlib.md5(url.encode()).hexdigest() + ".png")
    if os.path.exists(p):
        return _png(p)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "df-item-editor"})
        data = urllib.request.urlopen(req, timeout=4).read()
        open(p, "wb").write(data)
        return Image.open(io.BytesIO(data)).convert("RGBA")
    except Exception:
        return None


def _head_face(value_b64):
    try:
        info = json.loads(base64.b64decode(value_b64).decode("utf-8", "replace"))
        url = info["textures"]["SKIN"]["url"]
    except Exception:
        return None
    if url.startswith("http://"):
        url = "https://" + url[len("http://"):]
    skin = _fetch_skin(url)
    if skin is None:
        return None
    face = skin.crop((8, 8, 16, 16))
    out = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    out.alpha_composite(face)
    try:
        hat = skin.crop((40, 8, 48, 16))
        if hat.getbbox():
            out.alpha_composite(hat)
    except Exception:
        pass
    return out


# --- glint -----------------------------------------------------------------
def _glint():
    global _GLINT, _GLINT_LOADED
    if not _GLINT_LOADED:
        _GLINT_LOADED = True
        _GLINT = _png(os.path.join(TEX, "misc", "glint.png"))
    return _GLINT


def _apply_glint(img):
    g = _glint()
    if g is None:
        return img
    lum = g.convert("L").resize(img.size, Image.NEAREST).point(lambda v: int(v * 0.45))
    mask = ImageChops.multiply(lum, img.getchannel("A"))
    sheen = Image.new("RGBA", img.size, (190, 130, 255, 0))
    sheen.putalpha(mask)
    out = img.copy()
    out.alpha_composite(sheen)
    return out


def _placeholder(head=False):
    base = (90, 70, 120) if head else (70, 70, 78)
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            im.putpixel((x, y), (base[0], base[1], base[2], 255) if not edge else (40, 40, 46, 255))
    return im


# --- public ----------------------------------------------------------------
def _render_base(mat, comp):
    prof = comp.get("minecraft:profile")
    if mat in ("player_head", "skeleton_skull", "wither_skeleton_skull", "zombie_head",
               "creeper_head", "piglin_head", "dragon_head") and (prof or mat == "player_head"):
        val = _profile_value(prof)
        if val:
            f = _head_face(val)
            if f is not None:
                return f
        if mat != "player_head":
            bt = _block_tex(mat)
            if bt is not None:
                return bt
        return _placeholder(head=True)

    im = _item_tex(mat)
    if im is not None:
        dyed = comp.get("minecraft:dyed_color")
        if dyed is not None:
            im = _apply_dye(mat, im, dyed)
        return im

    bm = _resolve_block(mat)
    if bm is not None:
        return bm
    return None


def icon_for_snbt(snbt, px=64):
    """RGBA px*px image of the item's inventory icon (NEAREST-scaled), or a placeholder."""
    if not _HAVE:
        raise RuntimeError("Pillow not available")
    try:
        d = I.parse_snbt(snbt)
    except Exception:
        d = {}
    comp = d.get("components", {}) if isinstance(d, dict) else {}
    mat = (d.get("id") or "stone").replace("minecraft:", "") if isinstance(d, dict) else "stone"
    glow = ("minecraft:enchantment_glint_override" in comp) or ("minecraft:enchantments" in comp)

    key = (mat, repr(comp.get("minecraft:dyed_color")),
           _profile_value(comp.get("minecraft:profile")) or "", px)
    base = _MEMO.get(key)
    if base is None:
        base = _render_base(mat, comp) or _placeholder()
        _MEMO[key] = base

    scale = max(1, px // max(base.width, base.height))
    scaled = base.resize((base.width * scale, base.height * scale), Image.NEAREST)
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, ((px - scaled.width) // 2, (px - scaled.height) // 2))
    if glow:
        canvas = _apply_glint(canvas)
    return canvas
