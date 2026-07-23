"""df_text.py — styled-text core for DF VIBE item/menu/text tooling.

One job: turn a friendly **MiniMessage** string (the same syntax DiamondFire's `comp()`
accepts and `styling.md` teaches) into the exact Minecraft **text-component** runs that
DiamondFire bakes into item NBT — with `italic` defaulting to FALSE so item names/lore
don't render italic in-game (the #1 visual bug when hand-writing items).

It also goes the other way (component JSON -> runs) so items can be previewed, and renders
runs to 24-bit ANSI for an in-terminal tooltip.

Public API:
  parse_mm(s)                 -> list[Run]            MiniMessage -> styled runs
  runs_to_components(runs)    -> list[dict]           runs -> MC text-component run dicts
  name_component(s)           -> dict                 MiniMessage -> a custom_name component
  lore_components(lines)      -> list[dict]           list[MiniMessage] -> a lore list
  component_to_runs(comp)     -> list[Run]            MC component (dict) -> runs (for preview)
  lore_base(runs)             -> list[Run]            colorless runs -> dark_purple (lore base)
  ansi(runs, *, reset=True)   -> str                  runs -> colored terminal string
  snbt(value)                 -> str                  typed python value -> SNBT text
  Byte/Double/Float/IntArray  typed scalar wrappers for the SNBT emitter

A Run is a dict: {text, color (name|'#RRGGBB'|None), bold, italic, underlined,
strikethrough, obfuscated} — booleans always present.
"""
import re

# --- the 16 Minecraft named text colors -> RGB (vanilla values) --------------
NAMED = {
    "black": (0, 0, 0), "dark_blue": (0, 0, 170), "dark_green": (0, 170, 0),
    "dark_aqua": (0, 170, 170), "dark_red": (170, 0, 0), "dark_purple": (170, 0, 170),
    "gold": (255, 170, 0), "gray": (170, 170, 170), "dark_gray": (85, 85, 85),
    "blue": (85, 85, 255), "green": (85, 255, 85), "aqua": (85, 255, 255),
    "red": (255, 85, 85), "light_purple": (255, 85, 255), "yellow": (255, 255, 85),
    "white": (255, 255, 255),
}
# common aliases MiniMessage accepts
ALIASES = {"grey": "gray", "dark_grey": "dark_gray"}

# legacy §/& color+format codes -> names (used by the linter / for tolerant parsing)
LEGACY = {
    "0": "black", "1": "dark_blue", "2": "dark_green", "3": "dark_aqua", "4": "dark_red",
    "5": "dark_purple", "6": "gold", "7": "gray", "8": "dark_gray", "9": "blue",
    "a": "green", "b": "aqua", "c": "red", "d": "light_purple", "e": "yellow", "f": "white",
}

# decoration tag -> canonical component key
DECOR = {
    "bold": "bold", "b": "bold",
    "italic": "italic", "i": "italic", "em": "italic",
    "underlined": "underlined", "underline": "underlined", "u": "underlined",
    "strikethrough": "strikethrough", "st": "strikethrough", "s": "strikethrough",
    "obfuscated": "obfuscated", "obf": "obfuscated", "obfuscate": "obfuscated",
}
DECOR_KEYS = ("bold", "italic", "underlined", "strikethrough", "obfuscated")


def resolve_rgb(color):
    """A color token (name or '#RRGGBB') -> (r,g,b). None -> white for rendering."""
    if not color:
        return (255, 255, 255)
    color = ALIASES.get(color, color)
    if color in NAMED:
        return NAMED[color]
    if color.startswith("#") and len(color) == 7:
        return tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
    return (255, 255, 255)


def _norm_color(tok):
    """Normalize a color tag argument to a name or '#RRGGBB', or None if not a color."""
    t = tok.strip().lower()
    t = ALIASES.get(t, t)
    if t in NAMED:
        return t
    if re.fullmatch(r"#[0-9a-f]{6}", t):
        return "#" + t[1:].upper()
    if re.fullmatch(r"[0-9a-f]{6}", t):
        return "#" + t.upper()
    return None


# ---------------------------------------------------------------------------
# MiniMessage AST  (Text leaves + tag Nodes with children)
# ---------------------------------------------------------------------------
class _Node:
    __slots__ = ("kind", "arg", "children")

    def __init__(self, kind, arg=None):
        self.kind, self.arg, self.children = kind, arg, []


_TOKEN = re.compile(r"<(/?)([a-zA-Z0-9_#]+)((?::[^<>]*)?)>")


def _tokenize(s):
    """Yield ('text', str) | ('open', name, arg) | ('close', name). `\\<` escapes a literal."""
    out, i = [], 0
    # protect escaped \< and \>
    s = s.replace("\\<", "\x00").replace("\\>", "\x01")
    for m in _TOKEN.finditer(s):
        if m.start() > i:
            out.append(("text", s[i:m.start()]))
        closing, name, arg = m.group(1), m.group(2).lower(), m.group(3)
        arg = arg[1:] if arg.startswith(":") else ""
        out.append(("close", name) if closing else ("open", name, arg))
        i = m.end()
    if i < len(s):
        out.append(("text", s[i:]))
    # restore escapes
    return [(("text", t[1].replace("\x00", "<").replace("\x01", ">")) if t[0] == "text" else t)
            for t in out]


def _tag_family(name, arg):
    """Classify an opening tag -> (kind, payload) or None to treat as literal text."""
    if name in ("reset", "r"):
        return ("reset", None)
    if name in DECOR:
        return ("decor", DECOR[name])
    if name in ("gradient", "g"):
        stops = [c for c in (_norm_color(x) for x in arg.split(":") if x) if c]
        return ("gradient", stops) if len(stops) >= 2 else None
    if name == "rainbow":
        return ("rainbow", None)
    # <color:NAME> / <c:NAME>
    if name in ("color", "c") and arg:
        c = _norm_color(arg)
        return ("color", c) if c else None
    # bare <#rrggbb> or <red>
    c = _norm_color(name)
    if c:
        return ("color", c)
    return None


def _parse_ast(s):
    root = _Node("root")
    stack = [root]
    for tok in _tokenize(s):
        if tok[0] == "text":
            if tok[1]:
                n = _Node("text", tok[1])
                stack[-1].children.append(n)
        elif tok[0] == "open":
            fam = _tag_family(tok[1], tok[2])
            if fam is None:                       # unknown tag -> literal text
                stack[-1].children.append(_Node("text", _retag(tok)))
                continue
            if fam[0] == "reset":
                stack[:] = [root]
                continue
            node = _Node(fam[0], fam[1])
            stack[-1].children.append(node)
            stack.append(node)
        else:  # close
            name = tok[1]
            if name in DECOR:
                kind, decor_key = "decor", DECOR[name]
            elif name in ("gradient", "g"):
                kind, decor_key = "gradient", None
            elif name == "rainbow":
                kind, decor_key = "rainbow", None
            elif name in ("color", "c") or _norm_color(name):
                kind, decor_key = "color", None   # </red> closes the nearest color
            else:                                 # unknown close tag -> literal text
                stack[-1].children.append(_Node("text", _retag(tok)))
                continue
            for k in range(len(stack) - 1, 0, -1):
                nd = stack[k]
                if nd.kind == kind and (kind != "decor" or nd.arg == decor_key):
                    del stack[k:]
                    break
    return root


def _retag(tok):
    return "<" + ("/" if tok[0] == "close" else "") + tok[1] + ((":" + tok[2]) if len(tok) > 2 and tok[2] else "") + ">"


# ---------------------------------------------------------------------------
# AST -> runs
# ---------------------------------------------------------------------------
def _grad_len(node):
    """Chars a gradient will actually color: text not claimed by a nested color/gradient."""
    if node.kind == "text":
        return len(node.arg)
    return sum(_grad_len(c) for c in node.children
               if c.kind not in ("color", "gradient", "rainbow"))


def _lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _grad_color(stops, t):
    """Multi-stop gradient color at fraction t in [0,1] -> '#RRGGBB'."""
    if t <= 0:
        rgb = resolve_rgb(stops[0])
    elif t >= 1:
        rgb = resolve_rgb(stops[-1])
    else:
        seg = t * (len(stops) - 1)
        i = int(seg)
        rgb = _lerp(resolve_rgb(stops[i]), resolve_rgb(stops[i + 1]), seg - i)
    return "#%02X%02X%02X" % rgb


def _rainbow_color(t):
    import colorsys
    return "#%02X%02X%02X" % tuple(round(c * 255) for c in colorsys.hsv_to_rgb(t, 0.9, 1.0))


def parse_mm(s):
    """MiniMessage string -> list of Run dicts (coalesced)."""
    ast = _parse_ast(s)
    runs = []

    def style0():
        return {"color": None, **{k: False for k in DECOR_KEYS}}

    # gradient/rainbow need a per-span character counter
    def walk(node, style, grad):
        if node.kind == "text":
            for ch in node.arg:
                st = dict(style)
                if grad is not None and st["color"] is None:
                    kind, stops, total, ctr = grad
                    t = (ctr[0] / max(total - 1, 1)) if total > 1 else 0.0
                    st["color"] = _grad_color(stops, t) if kind == "gradient" else _rainbow_color(t)
                    ctr[0] += 1
                _emit(runs, ch, st)
            return
        st = dict(style)
        g = grad
        if node.kind == "color":
            st["color"] = node.arg
        elif node.kind == "decor":
            st[node.arg] = True
        elif node.kind in ("gradient", "rainbow"):
            g = (node.kind, node.arg, _grad_len(node), [0])
        for c in node.children:
            walk(c, st, g)
        if not node.children and node.kind == "color":
            _emit(runs, "", st)   # keep a colored empty span (blank colored pane names)

    for c in ast.children:
        walk(c, style0(), None)
    return runs


def mm_escape(s):
    """Escape literal < and > so they survive a MiniMessage round-trip."""
    return s.replace("<", "\\<").replace(">", "\\>")


_SHORT = (("bold", "b"), ("italic", "i"), ("underlined", "u"),
          ("strikethrough", "st"), ("obfuscated", "obf"))


def runs_to_mm(runs):
    """Runs (from component_to_runs) -> a MiniMessage string. The inverse of parse_mm,
    used to load an existing item's name/lore back into the editor as editable markup.
    Each run is wrapped in its own tags (correct, if verbose for per-char gradients)."""
    parts = []
    for r in runs:
        opens, closes = [], []
        c = r.get("color")
        if c:
            tag = c if c in NAMED else c            # name or '#RRGGBB'
            opens.append("<%s>" % tag)
            closes.append("</%s>" % tag)
        for key, short in _SHORT:
            if r.get(key):
                opens.append("<%s>" % short)
                closes.append("</%s>" % short)
        parts.append("".join(opens) + mm_escape(r["text"]) + "".join(reversed(closes)))
    return "".join(parts)


def _emit(runs, ch, st):
    if runs:
        last = runs[-1]
        if (last["color"] == st["color"]
                and all(last[k] == st[k] for k in DECOR_KEYS)):
            last["text"] += ch
            return
    runs.append({"text": ch, **st})


# ---------------------------------------------------------------------------
# runs <-> Minecraft text components
# ---------------------------------------------------------------------------
def runs_to_components(runs):
    """Runs -> list of MC component run dicts (all 5 booleans present; italic default off)."""
    out = []
    for r in runs:
        d = {k: Byte(1 if r[k] else 0) for k in DECOR_KEYS}
        if r.get("color"):
            d["color"] = r["color"]
        d["text"] = r["text"]
        out.append(d)
    return out


def name_component(mm):
    """MiniMessage -> a `custom_name`/lore-line component: {extra:[runs],text:""}."""
    runs = parse_mm(mm) or [{"text": "", "color": None, **{k: False for k in DECOR_KEYS}}]
    return {"extra": runs_to_components(runs), "text": ""}


def lore_components(lines):
    """list[MiniMessage] -> a `minecraft:lore` value (list of line components)."""
    return [name_component(ln) for ln in lines]


def component_to_runs(comp):
    """An MC text component (dict/str/list) -> flat list of Run dicts, for preview.
    Inherits parent style into `extra` children the way Minecraft does."""
    runs = []

    def walk(node, inherited):
        if isinstance(node, str):
            if node:
                runs.append({"text": node, **inherited})
            return
        if isinstance(node, list):
            for n in node:
                walk(n, inherited)
            return
        if not isinstance(node, dict):
            return
        st = dict(inherited)
        if "color" in node and node["color"] is not None:
            st["color"] = _norm_color(str(node["color"])) or str(node["color"])
        for k in DECOR_KEYS:
            if k in node:
                st[k] = bool(_truthy(node[k]))
        txt = node.get("text", "")
        if txt or (st["color"] and not node.get("extra")):
            runs.append({"text": txt, **st})   # keep empty runs that carry a color
        for child in node.get("extra", []):
            walk(child, st)

    walk(comp, {"color": None, **{k: False for k in DECOR_KEYS}})
    # coalesce
    merged = []
    for r in runs:
        if merged and merged[-1]["color"] == r["color"] and all(merged[-1][k] == r[k] for k in DECOR_KEYS):
            merged[-1]["text"] += r["text"]
        else:
            merged.append(dict(r))
    return merged


def _truthy(v):
    if isinstance(v, Byte):
        return v.v != 0
    if isinstance(v, str):
        return v not in ("0", "0b", "false", "False", "")
    return bool(v)


LORE_BASE_COLOR = "dark_purple"   # MC's lore base style — uncolored lore renders 170,0,170


def lore_base(runs):
    """Apply Minecraft's lore base color to colorless runs (names default white; lore
    defaults dark_purple, so previews must too)."""
    return [dict(r, color=LORE_BASE_COLOR) if not r.get("color") else r for r in runs]


# ---------------------------------------------------------------------------
# ANSI rendering (24-bit truecolor terminal preview)
# ---------------------------------------------------------------------------
def ansi(runs, *, reset=True):
    out = []
    for r in runs:
        codes = []
        cr = resolve_rgb(r.get("color"))
        codes.append("38;2;%d;%d;%d" % cr)
        if r.get("bold"):
            codes.append("1")
        if r.get("italic"):
            codes.append("3")
        if r.get("underlined"):
            codes.append("4")
        if r.get("strikethrough"):
            codes.append("9")
        out.append("\x1b[%sm%s" % (";".join(codes), r["text"]))
    return "".join(out) + ("\x1b[0m" if reset else "")


def ansi_mm(mm):
    return ansi(parse_mm(mm))


def enable_ansi():
    """Turn on ANSI/VT escape processing in the Windows console (no-op elsewhere)."""
    import sys
    if sys.platform != "win32":
        return
    try:
        import ctypes
        k = ctypes.windll.kernel32
        for handle in (-11, -12):  # stdout, stderr
            h = k.GetStdHandle(handle)
            mode = ctypes.c_uint32()
            if k.GetConsoleMode(h, ctypes.byref(mode)):
                k.SetConsoleMode(h, mode.value | 0x0004)  # ENABLE_VIRTUAL_TERMINAL_PROCESSING
    except Exception:
        pass


# ---------------------------------------------------------------------------
# typed SNBT emitter — reproduces Minecraft's component serialization exactly
# (keys sorted alphabetically; booleans -> 0b/1b; doubles -> d; floats -> f)
# ---------------------------------------------------------------------------
class Byte:
    __slots__ = ("v")

    def __init__(self, v): self.v = int(v)


class Double:
    __slots__ = ("v")

    def __init__(self, v): self.v = float(v)


class Float:
    __slots__ = ("v")

    def __init__(self, v): self.v = float(v)


class IntArray:
    __slots__ = ("v")

    def __init__(self, v): self.v = list(v)


class Raw:
    """Pre-serialized SNBT (e.g. a profile blob) inserted verbatim."""
    __slots__ = ("s")

    def __init__(self, s): self.s = s


_BAREKEY = re.compile(r"[A-Za-z0-9_]+")


def _key(k):
    return k if _BAREKEY.fullmatch(k) else _str(k)


def _str(s):
    return '"' + str(s).replace("\\", "\\\\").replace('"', '\\"') + '"'


def snbt(v):
    """Serialize a typed python value to SNBT text (Minecraft component form)."""
    if isinstance(v, Raw):
        return v.s
    if isinstance(v, Byte):
        return "%db" % v.v
    if isinstance(v, Double):
        return _num(v.v) + "d"
    if isinstance(v, Float):
        return _num(v.v) + "f"
    if isinstance(v, bool):
        return "1b" if v else "0b"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return _num(v)
    if isinstance(v, str):
        return _str(v)
    if isinstance(v, IntArray):
        return "[I;" + ",".join(str(int(x)) for x in v.v) + "]"
    if isinstance(v, dict):
        return "{" + ",".join("%s:%s" % (_key(k), snbt(v[k])) for k in sorted(v)) + "}"
    if isinstance(v, (list, tuple)):
        return "[" + ",".join(snbt(x) for x in v) + "]"
    raise TypeError("cannot serialize %r" % type(v))


def _num(f):
    """Render a float the way MC does: 1.0 -> '1.0', 0.25 -> '0.25'."""
    if f == int(f):
        return "%d.0" % int(f)
    return repr(f)
