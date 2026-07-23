"""DF VIBE semantic validator — catch DiamondFire "compile" errors before push/deploy.

`recompile()` only proves a .py file *parses* into a template. This module proves the
template is *valid DiamondFire*: real action / tag / value names, a well-formed chest,
balanced brackets, a sane header, legal var scopes, a code line that fits the plot, and
the common runtime footgun (an unbounded loop with no wait). Everything is grounded in
`actiondump.json` (authoritative names) + the real plot geometry — no guessing.

Public API:
  validate_template(tpl, *, plot_code_length=300, defined_names=None) -> list[Issue]
  validate_source(src,  *, plot_code_length=300, defined_names=None) -> list[Issue]
  validate_project(py_files, *, plot_code_length=300) -> list[Issue]   (cross-line checks)

Severity: "ERROR" = DiamondFire will reject or silently break it; "WARN" = very likely
wrong but could be legacy/intentional (these never block, just flag).

Design note: WARN, not ERROR, for unknown *names* — the codec tolerates legacy/renamed
actions, so an unrecognized name might be real on an old plot. We attach a "did you mean"
so the usual case (a typo) is one glance to fix. Structure / chest / scope / length are
hard ERRORs because DF genuinely cannot represent them.
"""
import json, re, os, difflib

import df_codec as C

# ---------------------------------------------------------------------------
# Issue record
# ---------------------------------------------------------------------------
class Issue:
    __slots__ = ("sev", "code", "where", "msg")

    def __init__(self, sev, code, where, msg):
        self.sev, self.code, self.where, self.msg = sev, code, where, msg

    def __repr__(self):
        w = f" {self.where}" if self.where else ""
        return f"[{self.sev}] {self.code}{w}: {self.msg}"

    def as_dict(self):
        return {"sev": self.sev, "code": self.code, "where": self.where, "msg": self.msg}


# ---------------------------------------------------------------------------
# actiondump-derived tables (lazy; reuses df_codec's path resolution)
# ---------------------------------------------------------------------------
_T = None

# DF's four real variable scopes (verified vs the DFOnline editor's SCOPE_TO_NAME_MAP:
# saved=SAVED, unsaved=GAME, local=LOCAL, line=LINE). NOT "global"/"save".
VALID_SCOPES = {"line", "local", "unsaved", "saved"}

# Valid function/process PARAMETER types (DFOnline editor: ParameterTypes).
PARAM_TYPES = {"txt", "comp", "num", "loc", "vec", "snd", "part", "pot", "item",
               "any", "var", "list", "dict"}

# Tags a block MUST carry or the thread misbehaves/halts in-game (DFOnline editor:
# DEFAULT_DATA_BLOCKS_TAGS). 100% of the live anarchy plot's blocks carry these, so a
# missing one means a hand-written block forgot it.
REQUIRED_TAGS = {
    "func": {"Is Hidden"},
    "process": {"Is Hidden"},
    "start_process": {"Local Variables", "Target Mode"},
}

# Block ids that legitimately open a code bracket (a `with` body).
CONTAINER_BLOCKS = {"if_player", "if_entity", "if_game", "if_var", "repeat", "else"}

# sub-action disambiguation prefixes: 'PIsNear' = the if_player IsNear condition.
PREFIX_BLOCK = {"P": "if_player", "E": "if_entity", "G": "if_game", "V": "if_var"}

# Header blocks — exactly one, and it must come first.
HEADER_BLOCKS = {"event", "entity_event", "game_event", "func", "process"}

# Blocks whose identity is `data` (a name), so they carry no action to validate.
DATA_BLOCKS = {"func", "process", "call_func", "start_process"}

# Repeat actions that loop without a built-in bound — dangerous with no wait inside.
UNBOUNDED_REPEATS = {"While", "DoWhile", "Forever"}

# DiamondFire rank ladder (low → high). An action's `requiredRank` in the actiondump names the
# LOWEST rank that can place it; "" = everyone. "Dev" is staff-only. A player's rank clears every
# action at or below it. Used by the optional RANK_LOCKED check (opt-in via set_user_rank / --rank).
RANK_ORDER = {"": 0, "Noble": 1, "Emperor": 2, "Mythic": 3, "Overlord": 4, "Dev": 99}
# The player's rank for RANK_LOCKED checks; None = don't check (default, never noisy).
_USER_RANK = None


def set_user_rank(name):
    """Set the rank RANK_LOCKED validates against. Blank/None/unknown disables the check;
    'none'/'free' means a free player (flags every rank-locked action); otherwise a rank name.
    Returns the normalized rank ('' when disabled, 'none' for free)."""
    global _USER_RANK
    if not name:
        _USER_RANK = None
        return ""
    n = name.strip().lower()
    if n in ("none", "free", "default", "norank", "no-rank"):
        _USER_RANK = ""  # free player: everything with a requiredRank is out of reach
        return "none"
    for r in RANK_ORDER:
        if r and r.lower() == n:
            _USER_RANK = r
            return r
    _USER_RANK = None
    return ""

# Legal target selectors. actiondump.json has no targets section, so these are hardcoded
# from docs/df-wiki/execution-model.md + dfpyre's TARGETS enum (matches every selector
# seen on real plots). `target=` only exists on the four blocks below; g_val targets and
# anything else check against the full union.
PLAYER_TARGETS = {"Selection", "Default", "Killer", "Damager", "Shooter", "Victim",
                  "AllPlayers"}
ENTITY_TARGETS = {"Selection", "Default", "Killer", "Damager", "Shooter", "Victim",
                  "AllEntities", "AllMobs", "LastEntity", "Projectile"}
BLOCK_TARGETS = {"player_action": PLAYER_TARGETS, "if_player": PLAYER_TARGETS,
                 "entity_action": ENTITY_TARGETS, "if_entity": ENTITY_TARGETS}
ALL_TARGETS = PLAYER_TARGETS | ENTITY_TARGETS

_STRIP = re.compile("\xa7.")  # strip §x Minecraft color codes from display names


def _strip(s):
    """Drop color codes AND surrounding whitespace. actiondump display names carry both
    §-codes and (notoriously) stray trailing spaces ('Name ', 'Open Inventory Title ');
    real templates store the clean form, so we normalize both sides before comparing."""
    return _STRIP.sub("", s or "").strip()


def _build():
    global _T
    if _T is not None:
        return _T
    dump = json.loads(C._resolve_actiondump().read_text(encoding="utf-8"))
    cb_ident = {cb["name"]: cb["identifier"] for cb in dump["codeblocks"]}

    actions = {}            # bid -> set(stripped action names)
    tag_opts = {}           # (bid, stripped action, stripped tag) -> set(option names)
    sub_blocks = {}         # (bid, stripped action) -> list of allowed condition block ids
    ranks = {}              # (bid, stripped action) -> requiredRank name ("" = free)
    for a in dump["actions"]:
        bid = cb_ident.get(a["codeblockName"])
        name = a["name"].strip()
        actions.setdefault(bid, set()).add(name)
        rr = (a.get("icon", {}) or {}).get("requiredRank", "") or ""
        if rr:
            ranks[(bid, name)] = rr
        for t in a.get("tags", []):
            tag_opts[(bid, name, t["name"].strip())] = {o["name"] for o in t.get("options", [])}
        if a.get("subActionBlocks"):
            sub_blocks[(bid, name)] = list(a["subActionBlocks"])
    # second pass: register every alias too ('String'->'Text', 'ChatStyle'->'ChatColor', …)
    # — real templates use them freely. setdefault so an alias that collides with a real
    # action name ('BossBar' aliases 'SetBossBar') never shadows the canonical schema.
    for a in dump["actions"]:
        bid = cb_ident.get(a["codeblockName"])
        for al in a.get("aliases", []):
            nm = al.strip()
            actions.setdefault(bid, set()).add(nm)
            for t in a.get("tags", []):
                tag_opts.setdefault((bid, nm, t["name"].strip()),
                                    {o["name"] for o in t.get("options", [])})
            if a.get("subActionBlocks"):
                sub_blocks.setdefault((bid, nm), list(a["subActionBlocks"]))
            rr = (a.get("icon", {}) or {}).get("requiredRank", "") or ""
            if rr:
                ranks.setdefault((bid, nm), rr)

    gvals = {_strip(g["icon"]["name"]) for g in dump["gameValues"]}
    for g in dump["gameValues"]:
        for al in g.get("aliases", []):
            gvals.add(_strip(al))
    sounds = {_strip(s["icon"]["name"]) for s in dump["sounds"]}
    potions = {_strip(p["icon"]["name"]) for p in dump["potions"]}

    # particle -> required data fields (e.g. SOUL -> ["Motion","Motion Variation"]).
    # A particle with required fields but EMPTY data{} makes DiamondFire silently refuse to
    # place the whole line (no chat message). Keyed by an upper/space-normalized name so the
    # template's "Sculk Soul" matches actiondump's "SCULK_SOUL".
    particles = {p["particle"].upper().replace("_", " ").strip(): p.get("fields", [])
                 for p in dump.get("particles", [])}

    _T = dict(actions=actions, tag_opts=tag_opts, sub_blocks=sub_blocks, ranks=ranks,
              gvals=gvals, sounds=sounds, potions=potions, particles=particles)
    return _T


def _suggest(name, candidates):
    m = difflib.get_close_matches(str(name), [str(c) for c in candidates], n=1, cutoff=0.6)
    return f" — did you mean {m[0]!r}?" if m else ""


# ---------------------------------------------------------------------------
# per-line template validation
# ---------------------------------------------------------------------------
def _label(blk, i):
    bid = blk.get("block", "?")
    key = blk.get("data") if bid in DATA_BLOCKS else blk.get("action")
    return f"block {i} ({bid}{'/' + str(key) if key else ''})"


def _items(blk):
    return blk.get("args", {}).get("items", [])


def validate_template(tpl, *, plot_code_length=300, defined_names=None):
    """Validate one code line (template dict). Returns a list of Issue."""
    T = _build()
    out = []
    blocks = [b for b in tpl.get("blocks", []) if b.get("id") != "bracket"]
    raw = tpl.get("blocks", [])

    # ---- S1: empty -------------------------------------------------------
    if not raw:
        out.append(Issue("ERROR", "EMPTY", "", "code line has no blocks"))
        return out
    if not blocks:
        out.append(Issue("ERROR", "NO_BLOCKS", "",
                         "code line contains only brackets — no actual code blocks"))
        return out

    # ---- S2/S3: header presence, position, uniqueness --------------------
    head_idx = [i for i, b in enumerate(blocks) if b.get("block") in HEADER_BLOCKS]
    if not head_idx:
        out.append(Issue("ERROR", "NO_HEADER", _label(blocks[0], 0),
                         "first block must be an event/func/process header"))
    else:
        if head_idx[0] != 0:
            out.append(Issue("ERROR", "HEADER_NOT_FIRST", _label(blocks[head_idx[0]], head_idx[0]),
                             "the header block must be the first block of the line"))
        for i in head_idx[1:]:
            out.append(Issue("ERROR", "EXTRA_HEADER", _label(blocks[i], i),
                             "a code line may only have one header block"))

    # ---- S4/S5/S6: bracket balance, type match, valid opener -------------
    # WARN, not ERROR: DiamondFire tolerates stray brackets and real plots contain them
    # (the codec round-trips them losslessly). AI-written code uses `with` blocks, which
    # are always balanced, so this only ever fires on hand-rolled raw brackets.
    depth, stack, prev = 0, [], None
    for e in raw:
        if e.get("id") == "bracket":
            if e.get("direct") == "open":
                if prev is None or prev.get("block") not in CONTAINER_BLOCKS:
                    pb = (prev or {}).get("block", "start of line")
                    out.append(Issue("WARN", "BAD_BRACKET_OPEN", "",
                                     f"a bracket opened after {pb}, which is not an if/else/repeat"))
                stack.append(e.get("type", "norm"))
                depth += 1
            else:
                if depth == 0:
                    out.append(Issue("WARN", "BRACKET_UNDERFLOW", "",
                                     "a closing bracket has no matching opener"))
                else:
                    open_t = stack.pop()
                    depth -= 1
                    if open_t != e.get("type", "norm"):
                        out.append(Issue("WARN", "BRACKET_TYPE", "",
                                         f"bracket type mismatch: opened {open_t!r}, closed {e.get('type')!r}"))
        else:
            prev = e
    if depth != 0:
        out.append(Issue("WARN", "BRACKET_UNBALANCED", "",
                         f"{depth} bracket(s) opened but never closed"))

    # ---- L1: code line too long for the plot -----------------------------
    length = sum(1 if e.get("id") == "bracket" else 2 for e in raw)
    if plot_code_length and length > plot_code_length:
        out.append(Issue("ERROR", "TOO_LONG", "",
                         f"line is {length} blocks long but this plot only fits {plot_code_length} "
                         f"— split it into smaller functions"))

    # ---- per-block semantic checks --------------------------------------
    for i, b in enumerate(blocks):
        bid = b.get("block")
        where = _label(b, i)
        action = b.get("action")

        # N1: action name valid for the block type
        action_known = True
        if bid not in DATA_BLOCKS and bid not in ("else",) and action is not None:
            valid = T["actions"].get(bid, set())
            if action.strip() not in valid:
                action_known = False
                out.append(Issue("WARN", "BAD_ACTION", where,
                                 f"{action!r} is not a known {bid} action" + _suggest(action, valid)))

            # N1b: rank-locked action above the player's rank (opt-in; _USER_RANK None = skip).
            # DiamondFire silently refuses to place an action your rank can't use, so this is worth
            # flagging before a push. requiredRank names the LOWEST rank that can place the action.
            if action_known and _USER_RANK is not None:
                req = T["ranks"].get((bid, action.strip()))
                if req and RANK_ORDER.get(req, 99) > RANK_ORDER.get(_USER_RANK, 0):
                    out.append(Issue("WARN", "RANK_LOCKED", where,
                                     f"{action!r} needs rank {req}; your rank is "
                                     f"{_USER_RANK or 'none'}, so DiamondFire won't place it"))

        # N9: block-level target selector must be a real one
        tgt = b.get("target")
        if tgt is not None:
            legal = BLOCK_TARGETS.get(bid, ALL_TARGETS)
            if tgt not in legal:
                out.append(Issue("ERROR", "BAD_TARGET", where,
                                 f"target {tgt!r} is not a valid {bid} target" + _suggest(tgt, legal)))

        # N8: subAction (select-by-condition / repeat-while) must be a condition action of a
        # block type this host accepts (its subActionBlocks: select_obj 'EntitiesCond' takes
        # if_entity/if_var/if_game conditions, not if_player ones). DF stores the bare
        # condition name ('HasCustomTag', '=', 'IsType'), but prefixes it with P/E/G/V
        # (player/entity/game/var) when the same condition exists on more than one block
        # type ('PIsNear' vs 'EIsNear'). Accept both forms.
        sub = b.get("subAction")
        sub_hosts = T["sub_blocks"].get((bid, (action or "").strip()))
        if sub is not None:
            s = sub.strip()
            if sub_hosts is None:
                out.append(Issue("WARN", "BAD_SUBACTION_HOST", where,
                                 f"{bid}/{action} doesn't take a sub-action condition, but one is set ({sub!r})"))
            else:
                cond = set()
                for cb in sub_hosts:
                    cond |= T["actions"].get(cb, set())
                valid = s in cond
                if not valid and len(s) > 1 and s[0] in PREFIX_BLOCK:
                    pb = PREFIX_BLOCK[s[0]]  # the prefix pins the block type — it must be accepted
                    valid = pb in sub_hosts and s[1:] in T["actions"].get(pb, set())
                if not valid:
                    out.append(Issue("WARN", "BAD_SUBACTION", where,
                                     f"sub-action {sub!r} is not a condition {bid}/{action} accepts "
                                     f"({'/'.join(sub_hosts)})" + _suggest(s.lstrip("PEGV"), cond)))

        # chest: gather items, check tags, options, slots, overflow, value sanity
        items = _items(b)
        slots_seen = {}
        present_tags = set()
        tag_action = action if action is not None else "dynamic"
        for it in items:
            iid = it["item"]["id"]
            data = it["item"]["data"]
            slot = it.get("slot")

            # C2/C3: slot collisions and range
            if slot is not None:
                if slot in slots_seen:
                    out.append(Issue("ERROR", "DUP_SLOT", where, f"two items share chest slot {slot}"))
                slots_seen[slot] = True
                if not (0 <= slot <= 26):
                    out.append(Issue("ERROR", "BAD_SLOT", where, f"chest slot {slot} is outside 0..26"))

            if iid == "bl_tag":
                tag = data.get("tag")
                present_tags.add(tag)
                key = (bid, str(tag_action).strip(), str(tag).strip())
                opts = T["tag_opts"].get(key)
                if sub is not None:
                    # a conditional select/repeat's chest also carries its sub-condition's
                    # tags (select_obj FilterCondition+IsNear holds IsNear's tags): merge the
                    # condition's schema from every if-block this host accepts, then skip a
                    # tag neither the host nor the condition knows.
                    s = sub.strip()
                    names = {s, s[1:]} if len(s) > 1 and s[0] in "PEGV" else {s}
                    for cb in sub_hosts or ():
                        for nm in names:
                            o = T["tag_opts"].get((cb, nm, str(tag).strip()))
                            if o is not None:
                                opts = (opts or set()) | o
                    if opts is None:
                        continue
                if opts is None:
                    # suppressed when the action itself is unknown — BAD_ACTION is the root cause
                    if action_known:
                        alltags = {k[2] for k in T["tag_opts"] if k[0] == bid}
                        out.append(Issue("WARN", "BAD_TAG", where,
                                         f"{tag!r} is not a tag of {bid}/{tag_action}" + _suggest(tag, alltags)))
                elif data.get("option") not in opts:
                    out.append(Issue("WARN", "BAD_TAG_OPTION", where,
                                     f"tag {tag!r} option {data.get('option')!r} invalid; valid: {sorted(opts)}"))

            elif iid == "var":
                if data.get("scope") not in VALID_SCOPES:
                    out.append(Issue("ERROR", "BAD_SCOPE", where,
                                     f"variable {data.get('name')!r} has scope {data.get('scope')!r}; "
                                     f"valid: {sorted(VALID_SCOPES)}"))
            elif iid == "g_val":
                t = data.get("type")
                if _strip(t) not in T["gvals"]:
                    out.append(Issue("WARN", "BAD_GAMEVALUE", where,
                                     f"game value {t!r} is unknown" + _suggest(t, T["gvals"])))
                tg = data.get("target")
                if tg is not None and tg not in ALL_TARGETS:
                    out.append(Issue("ERROR", "BAD_TARGET", where,
                                     f"game value {t!r} target {tg!r} is not a valid target"
                                     + _suggest(tg, ALL_TARGETS)))
            elif iid == "snd":
                s = data.get("sound")
                if _strip(s) not in T["sounds"]:
                    out.append(Issue("WARN", "BAD_SOUND", where,
                                     f"sound {s!r} is unknown" + _suggest(s, T["sounds"])))
                p = data.get("pitch")
                if isinstance(p, (int, float)) and not (0.0 <= p <= 2.0):
                    out.append(Issue("WARN", "BAD_PITCH", where, f"sound pitch {p} is outside DF's 0.0..2.0"))
            elif iid == "pot":
                p = data.get("pot")
                if _strip(p) not in T["potions"]:
                    out.append(Issue("WARN", "BAD_POTION", where,
                                     f"potion {p!r} is unknown" + _suggest(p, T["potions"])))
            elif iid == "num":
                n = data.get("name", "")
                # a num holds a literal OR a %placeholder/%math/variable expression. Only flag
                # a value that is neither parseable as a number nor contains a % token.
                if "%" not in n:
                    try:
                        float(n)
                    except (TypeError, ValueError):
                        out.append(Issue("WARN", "BAD_NUM", where,
                                         f"num({n!r}) is not numeric and has no %placeholder — will read as 0"))
            elif iid == "pn_el":
                pt = data.get("type")
                if pt not in PARAM_TYPES:
                    out.append(Issue("WARN", "BAD_PARAM_TYPE", where,
                                     f"param {data.get('name')!r} type {pt!r} invalid; valid: {sorted(PARAM_TYPES)}"))
            elif iid == "part":
                # A MOTION particle (Soul, Sculk Soul, Soul Fire Flame, ...) whose data{} is empty
                # makes DiamondFire SILENTLY refuse to place the whole line — no chat message,
                # deploy/fill/hand-place all no-op. Particles whose only fields are optional
                # (Color/Size/Material — e.g. Flash) default fine and DO place with empty data, so
                # gate on a Motion field to avoid false-flagging them.
                pname = data.get("particle")
                req = T["particles"].get(str(pname).upper().replace("_", " ").strip())
                if req and "Motion" in req and not (data.get("data") or {}):
                    out.append(Issue("ERROR", "PARTICLE_NO_DATA", where,
                                     f"particle {pname!r} needs {req} but its data is empty {{}} — "
                                     f"DiamondFire will silently refuse to place this line"))

        # C1: chest overflow (a code block chest is 27 slots)
        if len(items) > 27:
            out.append(Issue("ERROR", "CHEST_OVERFLOW", where,
                             f"{len(items)} items but a code block chest only has 27 slots"))

        # required default tags (missing one halts/misbehaves in-game)
        miss = REQUIRED_TAGS.get(bid, set()) - present_tags
        for tag in sorted(miss):
            out.append(Issue("WARN", "MISSING_TAG", where,
                             f"{bid} is missing its required {tag!r} tag — set it or the thread may not run as expected"))

    # ---- P1: unbounded loop with no wait inside --------------------------
    out += _loop_checks(raw)

    return out


def _loop_checks(raw):
    """Flag a Repeat Forever/While/DoWhile whose body contains no Control('Wait')."""
    out = []
    i = 0
    while i < len(raw):
        e = raw[i]
        if e.get("id") == "block" and e.get("block") == "repeat" and e.get("action") in UNBOUNDED_REPEATS:
            # find this repeat's bracket body and scan for a Wait
            depth, j, has_wait, opened = 0, i + 1, False, False
            while j < len(raw):
                f = raw[j]
                if f.get("id") == "bracket":
                    if f.get("direct") == "open":
                        depth += 1; opened = True
                    else:
                        depth -= 1
                        if depth == 0:
                            break
                elif opened and f.get("block") == "control" and f.get("action") == "Wait":
                    has_wait = True
                j += 1
            if opened and not has_wait:
                out.append(Issue("WARN", "TIGHT_LOOP", f"block {i} (repeat/{e.get('action')})",
                                 "loop has no Control('Wait') inside — it runs every iteration in one "
                                 "tick and can trip LagSlayer (freezes the plot). Add a wait."))
        i += 1
    return out


# ---------------------------------------------------------------------------
# convenience wrappers
# ---------------------------------------------------------------------------
def validate_source(src, *, plot_code_length=300, defined_names=None):
    return validate_template(C.recompile(src), plot_code_length=plot_code_length,
                             defined_names=defined_names)


def validate_project(py_files, *, plot_code_length=300):
    """Cross-line checks over a whole project: duplicate event types, duplicate
    func/process names, calls to names nothing defines, and line-scoped vars shared
    across a CallFunc.

    py_files: list of (name, source) pairs.
    """
    out = []
    events = {}     # (bid, action) -> [files]
    defined = {}    # (bid, name) -> [files]   (func/process are separate namespaces)
    calls = []      # (file, called_name)
    templates = []
    linevars = {}   # file -> set of 'line'-scoped var names used anywhere in the line
    params = {}     # file -> set of declared parameter names (DF params ARE line vars)

    for fname, src in py_files:
        try:
            tpl = C.recompile(src)
        except Exception as e:
            out.append(Issue("ERROR", "PARSE", fname, f"{type(e).__name__}: {e}"))
            continue
        templates.append((fname, tpl))
        blocks = [b for b in tpl.get("blocks", []) if b.get("id") != "bracket"]
        if not blocks:
            continue
        h = blocks[0]
        bid = h.get("block")
        if bid in ("event", "entity_event", "game_event"):
            events.setdefault((bid, h.get("action")), []).append(fname)
        elif bid in ("func", "process"):
            defined.setdefault((bid, h.get("data")), []).append(fname)
        for b in blocks:
            if b.get("block") == "call_func":
                calls.append((fname, "func", b.get("data")))
            elif b.get("block") == "start_process":
                calls.append((fname, "process", b.get("data")))
            for it in _items(b):
                d = it["item"]["data"]
                if it["item"]["id"] == "var" and d.get("scope") == "line":
                    linevars.setdefault(fname, set()).add(d.get("name"))
                elif it["item"]["id"] == "pn_el":
                    params.setdefault(fname, set()).add(d.get("name"))

    for (bid, act), names in events.items():
        if len(names) > 1:
            out.append(Issue("ERROR", "DUP_EVENT", ", ".join(names),
                             f"{bid} {act!r} is defined in {len(names)} files — a plot allows only one of each event"))
    for (bid, name), names in defined.items():
        if len(names) > 1:
            out.append(Issue("ERROR", "DUP_NAME", ", ".join(names),
                             f"{bid} {name!r} is defined {len(names)} times — names must be unique"))
    known = set(defined)   # {(bid, name)}
    for fname, want_bid, called in calls:
        # flag a call to a name nothing defines (likely typo). %code names (dynamic
        # dispatch) resolve at runtime, so skip those. CallFunc->func, StartProcess->process.
        if called and "%" not in str(called) and (want_bid, called) not in known:
            pool = [n for (b, n) in known if b == want_bid]
            verb = "CallFunc" if want_bid == "func" else "StartProcess"
            out.append(Issue("WARN", "UNKNOWN_CALL", fname,
                             f"{verb} {called!r} but no {want_bid} defines it" + _suggest(called, pool)))

    # line vars don't survive CallFunc (the flat-terrain bug, variables-and-data.md): the
    # same line-scoped NAME in a caller and its direct callee is almost always a value that
    # was meant to cross the call. Callee parameters are exempt — DF passes arguments by
    # setting the callee's params as line vars, so sharing the name there is the idiom.
    seen = set()
    for fname, want_bid, called in calls:
        if want_bid != "func":
            continue
        for callee in defined.get(("func", called), []):
            if callee == fname:
                continue
            shared = (linevars.get(fname, set()) & linevars.get(callee, set())) \
                     - params.get(callee, set())
            for v in sorted(shared, key=str):
                if (fname, callee, v) in seen:
                    continue
                seen.add((fname, callee, v))
                out.append(Issue("WARN", "LINE_VAR_ACROSS_CALL", f"{fname} -> {callee}",
                                 f"line-scoped var {v!r} used in both caller and callee — "
                                 f"line vars don't survive CallFunc; use 'local'"))

    # per-line checks too, so validate_project is a superset
    for fname, tpl in templates:
        for iss in validate_template(tpl, plot_code_length=plot_code_length):
            iss.where = f"{fname}: {iss.where}" if iss.where else fname
            out.append(iss)
    return out
