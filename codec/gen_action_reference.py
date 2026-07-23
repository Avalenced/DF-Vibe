"""Generate docs/df-wiki/action-reference.md — a complete, grep-able catalog of every
DiamondFire code action + game value, straight from actiondump.json.

Why this exists: the AI kept reinventing things DF already has a one-block action for
(it hand-rolled compression before learning DF has `GzipCompress`; same with cooldowns).
actiondump.json is 7 MB and unreadable; this distills it to names + one-line descriptions
the AI can skim or grep before writing a workaround.

Run:  python codec/gen_action_reference.py
Regenerate whenever actiondump.json is refreshed. Do NOT hand-edit the output.
"""
import json, re, pathlib

HERE = pathlib.Path(__file__).parent
OUT = HERE.parent / "docs" / "df-wiki" / "action-reference.md"
_STRIP = re.compile("\xa7.")


def strip(s):
    return _STRIP.sub("", s or "").strip()


def desc(a):
    d = " ".join(a.get("icon", {}).get("description", []))
    return strip(d)


# DF argument types -> the short builder-ish names used in this project's .py files.
_ARGT = {
    "VARIABLE": "var", "NUMBER": "num", "TEXT": "txt", "COMPONENT": "comp",
    "LOCATION": "loc", "VECTOR": "vec", "ITEM": "item", "SOUND": "snd",
    "POTION": "pot", "PARTICLE": "particle", "BLOCK": "block", "BLOCK_TAG": "blocktag",
    "ENTITY_TYPE": "entity", "SPAWN_EGG": "egg", "PROJECTILE": "projectile",
    "VEHICLE": "vehicle", "DICT": "dict", "LIST": "list", "ANY_TYPE": "any", "BYTE": "byte",
}


def sig(a):
    """Render an action's argument signature, e.g. (var, num, txt?, item...)."""
    parts = []
    for arg in a.get("icon", {}).get("arguments", []):
        t = arg.get("type")
        if t in (None, "NONE"):
            continue  # blank/separator slot, not a real parameter
        s = _ARGT.get(t, t.lower())
        if arg.get("plural"):
            s += "..."
        if arg.get("optional"):
            s = "[" + s + "]"   # standard "optional" notation (clearer than a trailing ?)
        parts.append(s)
    return "(" + ", ".join(parts) + ")" if parts else ""


# Codeblocks in a teaching-friendly order, with the builder name used in .py files.
ORDER = [
    ("event", "event(...)", "Player Events"),
    ("entity_event", "entity_event(...)", "Entity Events"),
    ("game_event", "game_event(...)", "Game Events"),
    ("player_action", "PlayerAction(...)", "Player Actions"),
    ("entity_action", "EntityAction(...)", "Entity Actions"),
    ("game_action", "GameAction(...)", "Game Actions"),
    ("set_var", "SetVar(...)", "Set Variable (data, math, text, lists, dicts, I/O)"),
    ("if_player", "IfPlayer(...)", "If Player"),
    ("if_entity", "IfEntity(...)", "If Entity"),
    ("if_game", "IfGame(...)", "If Game"),
    ("if_var", "IfVar(...)", "If Variable"),
    ("control", "Control(...)", "Control (wait, return, stop…)"),
    ("repeat", "Repeat(...)", "Repeat"),
    ("select_obj", "SelectObj(...)", "Select Object"),
]

# Power tools the AI tends not to know exist — surfaced up top so it stops hand-rolling them.
HIGHLIGHTS = [
    ("set_var", "GzipCompress"), ("set_var", "GzipDecompress"),
    ("set_var", "Base64Encode"), ("set_var", "Base64Decode"),
    ("set_var", "WebRequest"), ("set_var", "GetWebResponse"), ("set_var", "WebResponse"),
    ("set_var", "JsonValue"), ("set_var", "ValueToJson"), ("set_var", "JsonToValue"),
    ("set_var", "ParseJson"), ("set_var", "LoadBucketVar"), ("set_var", "SaveBucketVar"),
    ("set_var", "SetMapTexture"), ("set_var", "Hash"), ("set_var", "Encrypt"),
    ("set_var", "Decrypt"), ("set_var", "RandomNumber"), ("set_var", "RandomObject"),
    ("player_action", "SetItemCooldown"), ("player_action", "GetItemCooldown"),
    ("if_player", "NoItemCooldown"), ("game_action", "SpawnTextDisplay"),
    ("set_var", "Text"), ("set_var", "ReplaceText"), ("set_var", "SplitText"),
]


def main():
    dump = json.loads((HERE / "actiondump.json").read_text(encoding="utf-8"))
    cb_ident = {cb["name"]: cb["identifier"] for cb in dump["codeblocks"]}
    by_block = {}
    index = {}
    for a in dump["actions"]:
        bid = cb_ident.get(a["codeblockName"])
        by_block.setdefault(bid, []).append(a)
        index[(bid, a["name"])] = a

    lines = []
    w = lines.append
    w("# DiamondFire action reference (complete)")
    w("")
    w("> **Generated** from `codec/actiondump.json` by `codec/gen_action_reference.py`. "
      "Do not hand-edit. Names are **verbatim** — use them exactly in builder calls "
      "(`PlayerAction('SendMessage', …)`). **Before writing a workaround, grep this file** — DF "
      "probably already has a block for it.")
    w(">")
    w("> **Signature notation:** `(var, num, [txt], item...)` — arg types in order; "
      "`[x]` = optional, `x...` = repeatable. Types map to builders: `var`→`var()`, "
      "`num`→`num()`, `txt`→`txt()`, `comp`→`comp()`, `loc`→`loc()`, `item`→`item()`, "
      "`snd`→`snd()`, `pot`→`pot()`. For Set Variable, the **first `var` is the output**.")
    w("")

    # --- highlights -------------------------------------------------------
    w("## Don't reinvent these — DF has a block for it")
    w("")
    w("Common things people hand-roll that are a single action in DiamondFire:")
    w("")
    for bid, name in HIGHLIGHTS:
        a = index.get((bid, name))
        if a:
            w(f"- **`{name}{sig(a)}`** ({bid}) — {desc(a) or 'see in-game tooltip'}")
    w("")
    w("Also built in: **variable scopes** for persistence (no DB needed), **dictionaries & "
      "lists** with dozens of ops (see Set Variable below), **Web Request** for external data, "
      "**Variable Buckets** for cross-plot storage, **text displays / holograms** as game "
      "actions. Browse the relevant section before building from primitives.")
    w("")

    # --- per-codeblock full lists ----------------------------------------
    for bid, builder, title in ORDER:
        acts = [a for a in by_block.get(bid, []) if a["name"] != "dynamic"]
        if not acts:
            continue
        w(f"## {title}")
        w(f"`{builder}` — {len(acts)} actions")
        w("")
        for a in sorted(acts, key=lambda x: x["name"].strip().lower()):
            nm = a["name"].strip()
            d = desc(a)
            w(f"- `{nm}{sig(a)}`" + (f" — {d}" if d else ""))
        w("")

    # --- game values ------------------------------------------------------
    w("## Game values")
    w("`gval('Name')` — read live state. Grouped by category.")
    w("")
    cats = {}
    for g in dump["gameValues"]:
        cats.setdefault(g["category"], []).append(g)
    for cat in sorted(cats):
        w(f"### {cat}")
        for g in sorted(cats[cat], key=lambda x: strip(x["icon"]["name"]).lower()):
            nm = strip(g["icon"]["name"])
            d = strip(" ".join(g["icon"].get("description", [])))
            w(f"- `{nm}`" + (f" — {d}" if d else ""))
        w("")

    OUT.write_text("\n".join(lines), encoding="utf-8")
    n_act = sum(len([a for a in by_block.get(b, []) if a['name'] != 'dynamic']) for b, _, _ in ORDER)
    print(f"wrote {OUT}  ({n_act} actions, {len(dump['gameValues'])} game values, {len(lines)} lines)")


if __name__ == "__main__":
    main()
