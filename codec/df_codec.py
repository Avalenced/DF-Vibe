"""DF VIBE codec library: lossless .py <-> DiamondFire template codec.

decompile(template) -> readable .py source
recompile(py_source) -> template dict   (via exec against a builder API)
identify(template)   -> {type, key, file}   (header identity, for file naming + matching)

The .py file IS Python: recompile runs it with exec() against the builder API
below, so parsing is free and lossless by construction. Tag slots come from
actiondump.json (authoritative). Proven 18/18 lossless on a real plot.

actiondump is loaded lazily and only when recompiling (decompile/identify don't
need it), so the common read path never pays the 7MB parse.
"""
import json, gzip, base64, pathlib, io, os, re, hashlib, contextlib

HERE = pathlib.Path(__file__).parent

# Bumped whenever decompile/recompile output changes. The mod stores this at pull
# time and nudges a re-pull on push if a project was captured by an older codec
# (their files may have drifted / be slightly lossy). v2 = tolerant brackets,
# explicit slots, legacy-tag fallback, else-args fix, txt/comp/vec, raw_block.
# v3 = alias-aware tags (dynamic/sub-action/internal names), pull dedup, strict
# value shapes (unknown extras stay raw), type-matched brackets, process hints.
CODEC_VERSION = 3

# ===========================================================================
# actiondump -> tag slot table (lazy, path-configurable)
# ===========================================================================
_TAG_SLOT = None
_TAG_WRITE = None
_ACTIONDUMP_PATH = None


def set_actiondump_path(path):
    """Override where actiondump.json is loaded from. Call before recompile()."""
    global _ACTIONDUMP_PATH, _TAG_SLOT, _TAG_WRITE
    _ACTIONDUMP_PATH = path
    _TAG_SLOT = None  # force reload
    _TAG_WRITE = None


def _resolve_actiondump():
    if _ACTIONDUMP_PATH:
        return pathlib.Path(_ACTIONDUMP_PATH)
    env = os.environ.get("DFPY_ACTIONDUMP")
    if env:
        return pathlib.Path(env)
    for cand in (HERE / "actiondump.json", HERE.parent / "Research" / "actiondump.json"):
        if cand.exists():
            return cand
    raise FileNotFoundError(
        "actiondump.json not found. Put it next to df_codec.py, set DFPY_ACTIONDUMP, "
        "or call set_actiondump_path().")


def _load_tag_slots():
    global _TAG_SLOT, _TAG_WRITE
    if _TAG_SLOT is not None:
        return _TAG_SLOT
    dump = json.loads(_resolve_actiondump().read_text(encoding="utf-8"))
    cb_ident = {cb["name"]: cb["identifier"] for cb in dump["codeblocks"]}
    ts, tw = {}, {}
    # Two passes so a dump NAME always wins over another action's alias of the
    # same string. The game stores an action's INTERNAL name inside bl_tag items:
    # the P/E/V/G-prefixed alias when one exists (VItemEquals, PIsWearing), else
    # the dump name (so a legacy alias like 'Text' writes 'String').
    for use_aliases in (True, False):
        for a in dump["actions"]:
            ident = cb_ident.get(a["codeblockName"])
            name = a["name"]
            aliases = a.get("aliases") or []
            internal = next((al for al in aliases
                             if al in ("P" + name, "E" + name, "V" + name, "G" + name)), name)
            for key in (aliases if use_aliases else [name]):
                tw[(ident, key)] = internal
                for t in a.get("tags", []):
                    ts[(ident, key, t["name"])] = t["slot"]
    _TAG_SLOT = ts
    _TAG_WRITE = tw
    return ts


IF_BIDS = ("if_player", "if_entity", "if_game", "if_var")


def _tag_lookup(block_id, action, sub_action, tagname):
    """Resolve a tag the way the game stores it -> (slot, action, block) for its
    bl_tag data. Tags on a block with a subAction belong to the sub-condition and
    carry the owning if-block's id; otherwise data carries the host action's
    internal name. Raises KeyError when the actiondump doesn't know the tag."""
    ts = _load_tag_slots()
    cands = []
    if sub_action is not None:
        # a bare (unprefixed) sub-condition name can be owned by several if-blocks;
        # the game resolves it by what the host selects: players for PlayersCond,
        # entities otherwise (verified against real plot data)
        order = IF_BIDS if action == "PlayersCond" else ("if_entity", "if_player", "if_game", "if_var")
        cands += [(b, sub_action, sub_action, b) for b in order]
    act = action if action is not None else "dynamic"
    cands.append((block_id, act, None, block_id))
    for bid, a, write_act, write_bid in cands:
        for k in (a, (a or "").strip()):
            if (bid, k, tagname) in ts:
                wa = write_act if write_act is not None else _TAG_WRITE.get((bid, k), k)
                return ts[(bid, k, tagname)], wa, write_bid
    raise KeyError(f"no slot for tag {tagname!r} on {block_id}/{action!r}")


def _tag_slot(block_id, action, tagname):
    return _tag_lookup(block_id, action, None, tagname)[0]


# ===========================================================================
# RECOMPILE side: builder API injected into the exec() namespace
# ===========================================================================
_BLOCKS = []


def _reset():
    global _BLOCKS
    _BLOCKS = []


def _mk(block_id, action=None, data=None, args=(), tags=None, hint=None,
        attribute=None, target=None, sub_action=None):
    items = []
    for i, a in enumerate(args):
        a = dict(a)
        slot = a.pop("_slot", i)
        items.append({"item": {"id": a["id"], "data": a["data"]}, "slot": slot})
    if hint is None:
        hint = (block_id == "func")
    if hint:
        items.append({"item": {"id": "hint", "data": {"id": "function" if block_id == "func" else "process"}}, "slot": 25})
    for tagname, opt in (tags or {}).items():
        try:
            slot, act, tag_bid = _tag_lookup(block_id, action, sub_action, tagname)
        except KeyError:
            # legacy/renamed action the actiondump doesn't know: tags live in the
            # top slots, so fall back to the highest free slot (best-effort, won't crash).
            act = action if action is not None else "dynamic"
            tag_bid = block_id
            used = {it["slot"] for it in items}
            slot = next((s for s in range(26, -1, -1) if s not in used), 26)
        items.append({"item": {"id": "bl_tag", "data": {
            "option": opt, "tag": tagname, "action": act, "block": tag_bid}},
            "slot": slot})
    blk = {"id": "block", "block": block_id}
    if items or block_id != "else":  # DF omits "args" entirely on a bare else
        blk["args"] = {"items": items}
    if action is not None:
        blk["action"] = action
    if data is not None:
        blk["data"] = data
    if attribute is not None:
        blk["attribute"] = attribute
    if target is not None:
        blk["target"] = target
    # SELECT OBJECT (EntitiesCond/PlayersCond/FilterCondition) and REPEAT While carry the
    # condition type in a top-level "subAction" field. DF needs it or the select/loop matches
    # nothing — round-trip it explicitly (it has no tag to ride on for HasCustomTag/IsType/etc).
    if sub_action is not None:
        blk["subAction"] = sub_action
    return blk


class _Cont:
    def __init__(self, blk):
        self.blk = blk
        self.btype = "repeat" if blk["block"] == "repeat" else "norm"

    def __enter__(self):
        _BLOCKS.append(self.blk)
        _BLOCKS.append({"id": "bracket", "direct": "open", "type": self.btype})

    def __exit__(self, *a):
        _BLOCKS.append({"id": "bracket", "direct": "close", "type": self.btype})


# value-arg constructors (return item dicts)
def num(v, slot=None): return _arg("num", {"name": str(v)}, slot)
def var(name, scope="line", slot=None): return _arg("var", {"name": name, "scope": scope}, slot)
def bucketvar(name, key, namespace_type="DEFAULT", namespace_alias="", slot=None): return _arg("bucket_var", {"name": name, "key": key, "namespace_type": namespace_type, "namespace_alias": namespace_alias}, slot)
def gval(type, target="Default", slot=None): return _arg("g_val", {"type": type, "target": target}, slot)
def item(snbt, slot=None): return _arg("item", {"item": snbt}, slot)
def txt(name, slot=None): return _arg("txt", {"name": name}, slot)
def comp(name, slot=None): return _arg("comp", {"name": name}, slot)
def vec(x, y, z, slot=None): return _arg("vec", {"x": x, "y": y, "z": z}, slot)
def param(name, type, plural=False, optional=False, slot=None): return _arg("pn_el", {"name": name, "type": type, "plural": plural, "optional": optional}, slot)
def pot(p, dur, amp, slot=None): return _arg("pot", {"pot": p, "dur": dur, "amp": amp}, slot)
def loc(x, y, z, pitch=0.0, yaw=0.0, isBlock=False, slot=None): return _arg("loc", {"isBlock": isBlock, "loc": {"x": x, "y": y, "z": z, "pitch": pitch, "yaw": yaw}}, slot)
def raw_item(id, data, slot=None): return _arg(id, data, slot)


def snd(sound, pitch, vol, variant=None, slot=None):
    d = {"pitch": pitch, "vol": vol, "sound": sound}
    if variant is not None:
        d["variant"] = variant
    return _arg("snd", d, slot)


def _arg(id, data, slot):
    d = {"id": id, "data": data}
    if slot is not None:
        d["_slot"] = slot
    return d


# header + statement blocks (append, return None)
def event(action, *args, **kw): _BLOCKS.append(_mk("event", action=action, args=args, **kw))
def entity_event(action, *args, **kw): _BLOCKS.append(_mk("entity_event", action=action, args=args, **kw))
def game_event(action, *args, **kw): _BLOCKS.append(_mk("game_event", action=action, args=args, **kw))
def func(name, *params, **kw): _BLOCKS.append(_mk("func", data=name, args=params, **kw))
def process(name, *params, **kw): _BLOCKS.append(_mk("process", data=name, args=params, **kw))
def PlayerAction(action, *args, **kw): _BLOCKS.append(_mk("player_action", action=action, args=args, **kw))
def EntityAction(action, *args, **kw): _BLOCKS.append(_mk("entity_action", action=action, args=args, **kw))
def GameAction(action, *args, **kw): _BLOCKS.append(_mk("game_action", action=action, args=args, **kw))
def SetVar(action, *args, **kw): _BLOCKS.append(_mk("set_var", action=action, args=args, **kw))
def Control(action, *args, **kw): _BLOCKS.append(_mk("control", action=action, args=args, **kw))
def CallFunc(name, *args, **kw): _BLOCKS.append(_mk("call_func", data=name, args=args, **kw))
def StartProcess(name, *args, **kw): _BLOCKS.append(_mk("start_process", data=name, args=args, **kw))
def SelectObj(action, *args, **kw): _BLOCKS.append(_mk("select_obj", action=action, args=args, **kw))


# container blocks (return context managers, used via `with`)
def IfPlayer(action, *args, **kw): return _Cont(_mk("if_player", action=action, args=args, **kw))
def IfEntity(action, *args, **kw): return _Cont(_mk("if_entity", action=action, args=args, **kw))
def IfGame(action, *args, **kw): return _Cont(_mk("if_game", action=action, args=args, **kw))
def IfVar(action, *args, **kw): return _Cont(_mk("if_var", action=action, args=args, **kw))
def Repeat(action, *args, **kw): return _Cont(_mk("repeat", action=action, args=args, **kw))
def Else(*args, **kw): return _Cont(_mk("else", args=args, **kw))


# stray/explicit bracket tokens (for the rare unbalanced-bracket line; keeps it lossless)
def OpenBracket(type="norm"): _BLOCKS.append({"id": "bracket", "direct": "open", "type": type})
def CloseBracket(type="norm"): _BLOCKS.append({"id": "bracket", "direct": "close", "type": type})


# verbatim fallback for an unknown/new block type (keeps decompile lossless + crash-free)
def raw_block(d): _BLOCKS.append(d)


_API_NAMES = ("event", "entity_event", "game_event", "func", "process",
              "PlayerAction", "EntityAction", "GameAction", "SetVar", "Control",
              "CallFunc", "StartProcess", "SelectObj", "IfPlayer", "IfEntity",
              "IfGame", "IfVar", "Repeat", "Else", "OpenBracket", "CloseBracket",
              "raw_block", "num", "var", "bucketvar", "gval", "item", "txt", "comp", "vec",
              "param", "pot", "loc", "snd", "raw_item")


# whatever the last recompile()'d source print()ed (a stray print would otherwise
# corrupt the JSON-on-stdout protocol the mod parses; callers may warn on it)
LAST_EXEC_OUTPUT = ""


def recompile(src: str, filename: str = "<string>") -> dict:
    """Run .py source through the builder API, return a template dict.

    Pass the real path as `filename` so SyntaxErrors / tracebacks carry it."""
    global LAST_EXEC_OUTPUT
    _reset()
    src = src.lstrip(chr(0xFEFF))  # tolerate a UTF-8 BOM
    ns = {k: globals()[k] for k in _API_NAMES}
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        exec(compile(src, filename, "exec"), ns)
    LAST_EXEC_OUTPUT = buf.getvalue()
    return {"blocks": list(_BLOCKS)}


# ===========================================================================
# DECOMPILE side: template dict -> .py source
# ===========================================================================
CLASS = {"event": "event", "entity_event": "entity_event", "game_event": "game_event",
         "func": "func", "process": "process", "player_action": "PlayerAction",
         "entity_action": "EntityAction", "game_action": "GameAction", "set_var": "SetVar",
         "control": "Control", "call_func": "CallFunc", "start_process": "StartProcess",
         "select_obj": "SelectObj", "if_player": "IfPlayer", "if_entity": "IfEntity",
         "if_game": "IfGame", "if_var": "IfVar", "repeat": "Repeat", "else": "Else"}
DATA_BLOCKS = {"func", "process", "call_func", "start_process"}


def _isnum(v):
    return type(v) in (int, float)


def _zerof(v):
    # exactly the float the builders write for an omitted pitch/yaw (not -0.0, not int 0)
    return type(v) is float and repr(v) == "0.0"


def _fnum(v):
    # str(v) for everything finite (byte-identical to before), but a re-parseable
    # token for the non-finite floats DF allows (e.g. a sound's vol:Infinity). A bare
    # `inf`/`nan` in the .py would NameError on recompile.
    if type(v) is float and (v != v or v == float("inf") or v == float("-inf")):
        if v != v: return "float('nan')"
        return "float('inf')" if v > 0 else "float('-inf')"
    return str(v)


def _shape(d, required, optional=frozenset()):
    keys = set(d)
    return required <= keys and keys <= (required | optional)


def _arg_expr(it, slot=None):
    iid = it["item"]["id"]; d = it["item"]["data"]
    s = "" if slot is None else f", slot={slot}"
    expr = _builder_expr(iid, d, s)
    if expr is None:
        # a shape the dedicated builders can't regenerate exactly -> keep it verbatim
        return f"raw_item({iid!r}, {d!r}{s})"
    return expr


def _builder_expr(iid, d, s):
    """The builder call for a value item, or None when its data carries anything
    the builder wouldn't write back byte-for-byte (extra keys, odd types)."""
    if not isinstance(d, dict):
        return None
    if iid == "num":
        if _shape(d, {"name"}) and isinstance(d["name"], str):
            return f"num({d['name']!r}{s})"
    elif iid == "var":
        if _shape(d, {"name", "scope"}) and isinstance(d["name"], str) and isinstance(d["scope"], str):
            return f"var({d['name']!r}, {d['scope']!r}{s})"
    elif iid == "bucket_var":
        if _shape(d, {"name", "key", "namespace_type", "namespace_alias"}) \
                and all(isinstance(d[k], str) for k in d):
            ex = ""
            if d["namespace_type"] != "DEFAULT": ex += f", namespace_type={d['namespace_type']!r}"
            if d["namespace_alias"]: ex += f", namespace_alias={d['namespace_alias']!r}"
            return f"bucketvar({d['name']!r}, {d['key']!r}{ex}{s})"
    elif iid == "g_val":
        if _shape(d, {"type", "target"}) and isinstance(d["type"], str) and isinstance(d["target"], str):
            t = "" if d["target"] == "Default" else f", {d['target']!r}"
            return f"gval({d['type']!r}{t}{s})"
    elif iid == "item":
        if _shape(d, {"item"}) and isinstance(d["item"], str):
            return f"item({d['item']!r}{s})"
    elif iid == "txt":
        if _shape(d, {"name"}) and isinstance(d["name"], str):
            return f"txt({d['name']!r}{s})"
    elif iid == "comp":
        if _shape(d, {"name"}) and isinstance(d["name"], str):
            return f"comp({d['name']!r}{s})"
    elif iid == "vec":
        if _shape(d, {"x", "y", "z"}) and all(_isnum(d[k]) for k in d):
            return f"vec({_fnum(d['x'])}, {_fnum(d['y'])}, {_fnum(d['z'])}{s})"
    elif iid == "pn_el":
        if _shape(d, {"name", "type", "plural", "optional"}) \
                and isinstance(d["name"], str) and isinstance(d["type"], str) \
                and type(d["plural"]) is bool and type(d["optional"]) is bool:
            ex = ""
            if d["plural"]: ex += ", plural=True"
            if d["optional"]: ex += ", optional=True"
            return f"param({d['name']!r}, {d['type']!r}{ex}{s})"
    elif iid == "pot":
        if _shape(d, {"pot", "dur", "amp"}) and isinstance(d["pot"], str) \
                and _isnum(d["dur"]) and _isnum(d["amp"]):
            return f"pot({d['pot']!r}, {_fnum(d['dur'])}, {_fnum(d['amp'])}{s})"
    elif iid == "snd":
        if _shape(d, {"sound", "pitch", "vol"}, {"variant"}) and isinstance(d["sound"], str) \
                and _isnum(d["pitch"]) and _isnum(d["vol"]) \
                and isinstance(d.get("variant", ""), str):
            v = "" if "variant" not in d else f", variant={d['variant']!r}"
            return f"snd({d['sound']!r}, {_fnum(d['pitch'])}, {_fnum(d['vol'])}{v}{s})"
    elif iid == "loc":
        L = d.get("loc")
        if _shape(d, {"isBlock", "loc"}) and type(d["isBlock"]) is bool \
                and isinstance(L, dict) and set(L) == {"x", "y", "z", "pitch", "yaw"} \
                and all(_isnum(v) for v in L.values()):
            ex = ""
            if not _zerof(L["pitch"]): ex += f", pitch={_fnum(L['pitch'])}"
            if not _zerof(L["yaw"]): ex += f", yaw={_fnum(L['yaw'])}"
            if d["isBlock"]: ex += ", isBlock=True"
            return f"loc({_fnum(L['x'])}, {_fnum(L['y'])}, {_fnum(L['z'])}{ex}{s})"
    return None


BLOCK_KEYS = {"id", "block", "args", "action", "data", "attribute", "target", "subAction"}


def _block_shape_ok(blk, bid):
    """True when _mk can regenerate this block dict exactly; anything else
    (extra keys, missing args, data on the wrong block class) goes raw_block."""
    if blk.get("id") != "block" or set(blk) - BLOCK_KEYS:
        return False
    if ("data" in blk) != (bid in DATA_BLOCKS):
        return False
    if "action" in blk and (bid in DATA_BLOCKS or bid == "else"):
        return False
    args = blk.get("args")
    if args is None:
        return bid == "else"
    if set(args) != {"items"} or (bid == "else" and not args["items"]):
        return False  # _mk omits args entirely on a bare else
    return all(isinstance(it, dict) and set(it) == {"item", "slot"}
               and isinstance(it["item"], dict) and set(it["item"]) == {"id", "data"}
               for it in args["items"])


def _call_expr(blk):
    bid = blk.get("block")
    name = CLASS.get(bid)
    if name is None or not _block_shape_ok(blk, bid):
        # unknown/new block type or shape: round-trip it verbatim so nothing is lost
        return f"raw_block({blk!r})"
    items = blk.get("args", {}).get("items", [])
    action = blk.get("action")
    vals = [it for it in items if it["item"]["id"] not in ("bl_tag", "hint")]

    # The UI hint item is implicit when it has the canonical shape (slot 25,
    # matching id); any other hint stays an explicit value item.
    clean_hint = False
    hint_data = {"id": "function" if bid == "func" else "process"}
    for it in (h for h in items if h["item"]["id"] == "hint"):
        if bid in ("func", "process") and not clean_hint \
                and it["slot"] == 25 and it["item"]["data"] == hint_data:
            clean_hint = True
        else:
            vals.append(it)

    # A tag goes in the clean tags={} dict only if actiondump confirms the exact
    # slot/action/block the game stores (incl. dynamic, sub-action and internal-name
    # forms); otherwise (legacy drift) keep it as an explicit-slot item.
    clean_tags = []
    for it in [t for t in items if t["item"]["id"] == "bl_tag"]:
        d = it["item"]["data"]
        try:
            slot, act, tag_bid = _tag_lookup(bid, action, blk.get("subAction"), d.get("tag"))
            ok = (isinstance(d, dict) and set(d) == {"option", "tag", "action", "block"}
                  and it["slot"] == slot and d["action"] == act and d["block"] == tag_bid)
        except Exception:
            ok = False
        (clean_tags if ok else vals).append(it)
    vals.sort(key=lambda it: it["slot"])
    clean_tags.sort(key=lambda it: it["slot"])

    parts = []
    if bid in DATA_BLOCKS:
        parts.append(repr(blk.get("data")))
    elif bid != "else":
        parts.append(repr(action))
    for idx, it in enumerate(vals):
        slot = it["slot"]
        parts.append(_arg_expr(it, None if slot == idx else slot))  # emit slot only when non-contiguous
    if blk.get("subAction") is not None:
        parts.append(f"sub_action={blk['subAction']!r}")
    if blk.get("target") is not None:
        parts.append(f"target={blk['target']!r}")
    if blk.get("attribute") is not None:
        parts.append(f"attribute={blk['attribute']!r}")
    if clean_tags:
        td = ", ".join(f"{it['item']['data']['tag']!r}: {it['item']['data']['option']!r}" for it in clean_tags)
        parts.append("tags={" + td + "}")
    if bid == "func" and not clean_hint:
        parts.append("hint=False")
    if bid == "process" and clean_hint:
        parts.append("hint=True")
    return f"{name}({', '.join(parts)})"


CONTAINERS = {"if_player", "if_entity", "if_game", "if_var", "repeat", "else"}


def _tree(blocks):
    # Tolerant of malformed bracket streams (real plots contain stray opens/closes
    # and truncated lines). An open attaches to a preceding container block only
    # when the bracket types agree; a close pops only the matching type. Everything
    # else round-trips verbatim as OpenBracket()/CloseBracket() strays, and a
    # container still open at the end is emitted flat (raw_block + OpenBracket)
    # so the source never grows a close bracket the template doesn't have.
    root = []
    stack = [(root, None, None)]  # (children, expected close type, owner node)
    for e in blocks:
        if e.get("id") == "bracket":
            t = e.get("type", "norm")
            if e["direct"] == "open":
                cur = stack[-1][0]
                prev = cur[-1] if cur else None
                if (prev is not None and "blk" in prev and prev["body"] is None
                        and prev["blk"].get("block") in CONTAINERS
                        and t == ("repeat" if prev["blk"]["block"] == "repeat" else "norm")):
                    prev["body"] = []
                    stack.append((prev["body"], t, prev))
                else:
                    cur.append({"stray": "open", "type": t})
            else:
                if len(stack) > 1 and stack[-1][1] == t:
                    stack.pop()
                else:
                    stack[-1][0].append({"stray": "close", "type": t})
        else:
            stack[-1][0].append({"blk": e, "body": None})
    for _, _, owner in stack[1:]:
        owner["unclosed"] = True
    return root


def _emit(nodes, indent, out):
    pad = "    " * indent
    for n in nodes:
        if "stray" in n:
            fn = "OpenBracket" if n["stray"] == "open" else "CloseBracket"
            t = n.get("type", "norm")
            arg = "" if t == "norm" else repr(t)
            out.write(f"{pad}{fn}({arg})\n")
            continue
        if n.get("unclosed"):
            # the template ends inside this container: a `with` would add a close
            # bracket on recompile, so emit the block + bracket verbatim instead
            t = "repeat" if n["blk"].get("block") == "repeat" else "norm"
            arg = "" if t == "norm" else repr(t)
            out.write(f"{pad}raw_block({n['blk']!r})\n")
            out.write(f"{pad}OpenBracket({arg})\n")
            if n["body"]:
                _emit(n["body"], indent, out)
            continue
        try:
            expr = _call_expr(n["blk"])
        except Exception as e:
            # One codeblock we can't decode (e.g. a missing sign / malformed chest). Don't drop the
            # WHOLE line over it - skip just this block so the rest of the line still compiles. The
            # raw block is kept in a comment so it can be recovered (uncomment) if it was actually OK.
            blk = n["blk"]
            if n["body"] is None:
                out.write(f"{pad}# [codec] skipped an unreadable codeblock "
                          f"({type(e).__name__}; likely a missing sign / malformed chest)\n")
                out.write(f"{pad}#raw_block({blk!r})\n")
            else:
                # A container we can't read: keep it verbatim so its bracket body stays structurally valid.
                t = "repeat" if blk.get("block") == "repeat" else "norm"
                arg = "" if t == "norm" else repr(t)
                out.write(f"{pad}raw_block({blk!r})\n")
                out.write(f"{pad}OpenBracket({arg})\n")
                if n["body"]:
                    _emit(n["body"], indent, out)
            continue
        if n["body"] is None:
            out.write(f"{pad}{expr}\n")
        else:
            out.write(f"{pad}with {expr}:\n")
            if n["body"]:
                _emit(n["body"], indent + 1, out)
            else:
                out.write(f"{pad}    pass\n")


def decompile(template: dict) -> str:
    out = io.StringIO()
    _emit(_tree(template["blocks"]), 0, out)
    return out.getvalue()


# ===========================================================================
# IDENTITY: header -> stable filename + human key (for the mod's registry)
# ===========================================================================
def identify(template: dict) -> dict:
    """Return {type, key, id, file} from a template's header block.

    type = codeblock id (event / func / process / ...).
    key  = the action (events) or the name (func/process).
    id   = "type:key" human label.
    file = filesystem-safe filename (cosmetic; real identity lives in the body).
    """
    blocks = template.get("blocks") or []
    if not blocks:
        return {"type": "empty", "key": "", "id": "empty:", "file": "empty_line.py"}
    h = blocks[0]
    bid = h.get("block")
    if bid in DATA_BLOCKS:
        key = h.get("data")
    else:
        key = h.get("action")
    safe = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", str(key)).strip().strip(".") or "unnamed"
    safe = re.sub(r"\s+", "_", safe)
    return {"type": bid, "key": key, "id": f"{bid}:{key}", "file": f"{bid}__{safe}.py"}


# ===========================================================================
# Helpers: CodeClient wire format (gzip+base64) + canonical compare
# ===========================================================================
def decode_line(b64: str) -> dict:
    """gzip+base64 string -> template dict."""
    return json.loads(gzip.decompress(base64.b64decode(b64)))


def encode_line(template: dict) -> str:
    """template dict -> gzip+base64 string (compact JSON)."""
    raw = json.dumps(template, separators=(",", ":")).encode()
    return base64.b64encode(gzip.compress(raw)).decode()


def canon(tpl: dict) -> str:
    """Canonical form for lossless comparison: items sorted by slot, keys sorted."""
    t = json.loads(json.dumps(tpl))
    for b in t.get("blocks", []):
        its = b.get("args", {}).get("items")
        if its:
            its.sort(key=lambda it: it.get("slot", 0))
    return json.dumps(t, sort_keys=True, separators=(",", ":"))


def canon_hash(tpl: dict) -> str:
    """Stable content hash of a line (sha256 of canonical JSON). Equal hash == same code,
    regardless of gzip bytes or item ordering. Used to push only changed lines."""
    return hashlib.sha256(canon(tpl).encode()).hexdigest()
