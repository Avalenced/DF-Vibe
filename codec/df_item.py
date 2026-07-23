"""df_item.py — generate and preview DiamondFire item NBT.

`build_item(...)` turns a friendly spec (material + MiniMessage name/lore + DF tags) into
the exact `{DF_NBT:4671,components:{...},count:N,id:"minecraft:..."}` SNBT that DiamondFire
bakes into a code block — in the same canonical shape the game produces (sorted keys, all
style booleans present, `italic:0b` so names/lore aren't italic in-game).

It also parses an existing item literal back (`parse_snbt`) and renders an item as an ANSI
tooltip (`preview`) so items can be eyeballed in the terminal without pushing to the plot.

  build_item(material, name=, lore=, count=, tags=, glow=, head=, head_texture=,
             dyed=, model=, unbreakable=, hide=)              -> SNBT str (no item('') wrap)
  build_literal(...)        -> the full  item('...')  builder call, ready to paste
  parse_snbt(snbt)          -> python dict (tolerant SNBT reader)
  item_summary(snbt)        -> {id, count, name, lore, runs_name, runs_lore, tags}
  preview(snbt, *, width=)  -> ANSI tooltip string
  extract_items(text)       -> list of raw SNBT strings from a .py file's item('...') calls
  extract_items_slots(text) -> list of (snbt, slot|None) — slot from item('...', slot=N)
"""
import re
import df_text as T
from df_text import Byte, Double, Float, IntArray, snbt as _snbt

DF_NBT_VERSION = 4671
ITEM_RE = re.compile(r"item\('((?:\\.|[^'\\])*)'\)")
ITEM_SLOT_RE = re.compile(r"item\('((?:\\.|[^'\\])*)'(?:,\s*slot=(\d+))?\)")


# ---------------------------------------------------------------------------
# build
# ---------------------------------------------------------------------------
def build_item(material, *, name=None, lore=None, count=1, tags=None, glow=False,
               head=None, head_texture=None, dyed=None, model=None,
               unbreakable=False, hide=None, enchantments=None, attributes=None,
               damage=None, item_name=None, max_stack=None, potion=None,
               block_state=None, trim=None, rarity=None, stored_enchantments=None,
               max_damage=None, repair_cost=None, extra=None):
    """Friendly spec -> canonical item SNBT string."""
    material = material.strip()
    if material.startswith("minecraft:"):
        material = material[len("minecraft:"):]

    comp = {}
    if name is not None:
        comp["minecraft:custom_name"] = T.name_component(name)
    if item_name is not None:
        comp["minecraft:item_name"] = T.name_component(item_name)
    if lore:
        comp["minecraft:lore"] = T.lore_components(lore if isinstance(lore, (list, tuple)) else [lore])
    if tags:
        comp["minecraft:custom_data"] = {
            "PublicBukkitValues": {_hyperkey(k): _tagval(v) for k, v in tags.items()}
        }
    if glow:
        comp["minecraft:enchantment_glint_override"] = Byte(1)
    if enchantments:                       # {name: level} -> {"minecraft:name": level}
        comp["minecraft:enchantments"] = {_mc(k): int(v) for k, v in enchantments.items()}
    if attributes:                         # list of {type, amount, operation?, slot?, id?}
        comp["minecraft:attribute_modifiers"] = [_attr(a, i) for i, a in enumerate(attributes)]
    if damage is not None:
        comp["minecraft:damage"] = int(damage)
    if max_stack is not None:
        comp["minecraft:max_stack_size"] = int(max_stack)
    if potion:
        comp["minecraft:potion_contents"] = {"potion": _mc(potion)}
    if dyed is not None:
        comp["minecraft:dyed_color"] = _color_int(dyed)
    if model is not None:
        comp["minecraft:custom_model_data"] = {"floats": [Float(model)]}
    if unbreakable:
        comp["minecraft:unbreakable"] = {}
    if head is not None or head_texture is not None:
        comp["minecraft:profile"] = _profile(head, head_texture)
        if material == "stone":
            material = "player_head"
    if hide:
        comp["minecraft:tooltip_display"] = {
            "hidden_components": [_mc(h) for h in (hide if isinstance(hide, (list, tuple)) else [hide])]
        }
    if block_state:                        # {prop: value} -> all values are strings
        comp["minecraft:block_state"] = {str(k): str(v) for k, v in block_state.items()}
    if trim:                               # (material, pattern)
        comp["minecraft:trim"] = {"material": _mc(trim[0]), "pattern": _mc(trim[1])}
    if rarity:
        comp["minecraft:rarity"] = rarity
    if stored_enchantments:
        comp["minecraft:stored_enchantments"] = {_mc(k): int(v) for k, v in stored_enchantments.items()}
    if max_damage is not None:
        comp["minecraft:max_damage"] = int(max_damage)
    if repair_cost is not None:
        comp["minecraft:repair_cost"] = int(repair_cost)
    if extra:                              # {component_id: value|Raw} — arbitrary components
        for k, v in extra.items():
            comp[_mc(k)] = v

    item = {"DF_NBT": DF_NBT_VERSION, "components": comp, "count": int(count),
            "id": "minecraft:" + material}
    return _snbt(item)


def _attr(a, i):
    """One attribute_modifiers entry (keys serialize alphabetically: amount,id,operation,slot,type)."""
    return {
        "amount": Double(a["amount"]),
        "id": a.get("id") or ("minecraft:df_mod_%d" % i),
        "operation": a.get("operation", "add_value"),
        "slot": a.get("slot", "mainhand"),
        "type": _mc(a["type"]),
    }


def build_literal(material, **kw):
    """Same as build_item but wrapped as the builder call you paste into a .py file."""
    snbt = build_item(material, **kw)
    return "item('%s')" % snbt.replace("\\", "\\\\").replace("'", "\\'")


def _hyperkey(k):
    return k if ":" in k else "hypercube:" + k


def _tagval(v):
    if isinstance(v, (int, float)):
        return Double(v)          # DF custom_data numbers are doubles (1.0d)
    return v                       # string tag


def _mc(s):
    return s if str(s).startswith("minecraft:") else "minecraft:" + s


def _color_int(c):
    """Accept an int, '#RRGGBB', or a named color -> packed RGB int."""
    if isinstance(c, int):
        return c
    r, g, b = T.resolve_rgb(c if str(c).startswith("#") else (c))
    return (r << 16) | (g << 8) | b


def _profile(name, texture):
    p = {}
    if name:
        p["name"] = name
    if texture:
        p["name"] = p.get("name", "DF-HEAD")
        p["properties"] = [{"name": "textures", "value": texture}]
    return p


# ---------------------------------------------------------------------------
# tolerant SNBT reader  (subset Minecraft uses for item components)
# ---------------------------------------------------------------------------
class _P:
    def __init__(self, s):
        self.s, self.i, self.n = s, 0, len(s)

    def err(self, m):
        raise ValueError("SNBT parse error at %d: %s" % (self.i, m))

    def ws(self):
        while self.i < self.n and self.s[self.i] in " \t\r\n":
            self.i += 1

    def parse(self):
        self.ws()
        v = self.value()
        return v

    def value(self):
        self.ws()
        c = self.s[self.i] if self.i < self.n else ""
        if c == "{":
            return self.compound()
        if c == "[":
            return self.array()
        if c in '"\'':
            return self.qstring(c)
        return self.scalar()

    def compound(self):
        self.i += 1  # {
        out = {}
        self.ws()
        if self.peek() == "}":
            self.i += 1
            return out
        while True:
            self.ws()
            key = self.qstring(self.peek()) if self.peek() in '"\'' else self.bareword()
            self.ws()
            if self.peek() != ":":
                self.err("expected ':' after key %r" % key)
            self.i += 1
            out[key] = self.value()
            self.ws()
            c = self.peek()
            if c == ",":
                self.i += 1
                continue
            if c == "}":
                self.i += 1
                break
            self.err("expected ',' or '}'")
        return out

    def array(self):
        self.i += 1  # [
        # typed array [I; ...] / [B; ...] / [L; ...]
        m = re.match(r"\s*([IBL]);", self.s[self.i:])
        if m:
            self.i += m.end()
            vals = []
            self.ws()
            if self.peek() == "]":
                self.i += 1
                return vals
            while True:
                vals.append(self.value())
                self.ws()
                c = self.peek()
                if c == ",":
                    self.i += 1
                    continue
                if c == "]":
                    self.i += 1
                    break
                self.err("bad typed array")
            return vals
        out = []
        self.ws()
        if self.peek() == "]":
            self.i += 1
            return out
        while True:
            out.append(self.value())
            self.ws()
            c = self.peek()
            if c == ",":
                self.i += 1
                continue
            if c == "]":
                self.i += 1
                break
            self.err("expected ',' or ']'")
        return out

    def qstring(self, q):
        self.i += 1  # opening quote
        buf = []
        while self.i < self.n:
            c = self.s[self.i]
            if c == "\\":
                self.i += 1
                buf.append(self.s[self.i] if self.i < self.n else "")
            elif c == q:
                self.i += 1
                return "".join(buf)
            else:
                buf.append(c)
            self.i += 1
        self.err("unterminated string")

    def bareword(self):
        m = re.match(r"[A-Za-z0-9_+.\-]+", self.s[self.i:])
        if not m:
            self.err("expected bareword")
        self.i += m.end()
        return m.group(0)

    def scalar(self):
        w = self.bareword()
        # number with optional type suffix
        m = re.fullmatch(r"(-?(?:\d+\.?\d*|\.\d+))([bslfdBSLFD]?)", w)
        if m:
            num, suf = m.group(1), m.group(2).lower()
            if suf in ("b", "s", "l") or (not suf and "." not in num):
                try:
                    return int(num)
                except ValueError:
                    return float(num)
            return float(num)
        if w in ("true", "false"):
            return w == "true"
        return w  # bare enum/string

    def peek(self):
        return self.s[self.i] if self.i < self.n else ""


def parse_snbt(s):
    return _P(s).parse()


# ---------------------------------------------------------------------------
# summarize / preview
# ---------------------------------------------------------------------------
def item_summary(snbt):
    d = parse_snbt(snbt)
    comp = d.get("components", {}) if isinstance(d, dict) else {}
    iid = (d.get("id") or "?") if isinstance(d, dict) else "?"
    iid = iid.replace("minecraft:", "")
    count = d.get("count", 1) if isinstance(d, dict) else 1
    runs_name = T.component_to_runs(comp["minecraft:custom_name"]) if "minecraft:custom_name" in comp else []
    runs_lore = [T.component_to_runs(ln) for ln in comp.get("minecraft:lore", [])]
    tags = {}
    cd = comp.get("minecraft:custom_data", {})
    for k, v in (cd.get("PublicBukkitValues", {}) or {}).items():
        tags[k.replace("hypercube:", "")] = v
    return {
        "id": iid, "count": count,
        "name": _plain(runs_name), "lore": [_plain(r) for r in runs_lore],
        "runs_name": runs_name, "runs_lore": runs_lore, "tags": tags,
        "glow": "minecraft:enchantment_glint_override" in comp,
        "head": "minecraft:profile" in comp,
    }


def _plain(runs):
    return "".join(r["text"] for r in runs)


_GRAY = (170, 170, 170)


def preview(snbt, *, index=None, show_material=True):
    """Render an item as an ANSI tooltip (name + lore lines, true color)."""
    try:
        s = item_summary(snbt)
    except Exception as e:
        return "  [unparseable item: %s]" % e
    out = []
    head = ("%2d. " % index) if index is not None else ""
    nm = T.ansi(s["runs_name"]) if s["runs_name"] else "\x1b[3;37m(no name)\x1b[0m"
    badges = ""
    if s["count"] and s["count"] != 1:
        badges += " \x1b[37mx%d\x1b[0m" % s["count"]
    if s["glow"]:
        badges += " \x1b[35m✦glow\x1b[0m"
    if s["head"]:
        badges += " \x1b[33m☻head\x1b[0m"
    out.append("%s%s%s" % (head, nm, badges))
    pad = "    " if index is not None else "  "
    for runs in s["runs_lore"]:
        out.append(pad + (T.ansi(T.lore_base(runs)) if runs else ""))
    meta = []
    if show_material:
        meta.append("\x1b[38;2;85;85;85m%s%s\x1b[0m" % (pad, s["id"]))
    if s["tags"]:
        meta.append("\x1b[38;2;85;85;85m%stags: %s\x1b[0m"
                    % (pad, ", ".join("%s=%s" % (k, v) for k, v in s["tags"].items())))
    out += meta
    return "\n".join(out)


def extract_items(text):
    """All raw SNBT strings from item('...') calls in a .py source (un-escaped),
    slotted or not."""
    return [s for s, _ in extract_items_slots(text)]


def extract_items_slots(text):
    """All (snbt, slot|None) pairs from item('...'[, slot=N]) calls in a .py source.
    Each capture is the body of a Python single-quoted literal; decode its escapes
    without touching unicode (✦ etc.)."""
    import ast
    out = []
    for m in ITEM_SLOT_RE.finditer(text):
        s = m.group(1)
        if "\\" in s:
            try:
                s = ast.literal_eval("'" + s + "'")
            except Exception:
                pass
        out.append((s, int(m.group(2)) if m.group(2) else None))
    return out
