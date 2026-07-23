"""Shared engine for generating DF VIBE *build-loader* code.

A build is stored AS DiamondFire code: a set of data-functions holding greedy-meshed
block boxes + sparse "fixtures" (signs / heads / container contents), plus a `schemPlace`
loop and an `event:Command` (`@load`) trigger that rebuilds everything at
`gval('Standing Block Location')`.

Two callers share this module:
  - `gen_schem_loader.py` - the one-off litematica->loader script (blocks only).
  - `dfpy.py genschem`     - the live-capture path (blocks + the fixtures the litematic
                             path can't see: sign text, head textures, chest contents).

Everything here emits plain DF VIBE `.py` source text; `df_codec.recompile` turns it into
real templates, so the output round-trips through the normal check/validate/place pipeline.
"""
try:
    import numpy as np
    HAVE_NUMPY = True
except Exception:                       # numpy is optional - we fall back to a pure-python mesher
    np = None
    HAVE_NUMPY = False

# --- tuning (match gen_schem_loader's historical values) ---
CHUNK_CAP = 1500        # max chars per packed text value (one DF text arg)
VALS_PER_BLOCK = 25     # text values per SetVar chest
BLOCKS_PER_FUNC = 3     # CreateList/AppendValue blocks per data-function line

# fixtures budgeting (each fixtures func is ONE code line; the BLOCK cap is computed per plot
# size in emit_fixtures - every non-bracket code block is 2 blocks of physical line)
FIX_BYTE_CAP = 30000    # max payload chars per func (gzip keeps the real token well under 65535)
WAIT_EVERY = 20         # insert CallFunc('waitCPU') after ~this many fixture blocks


# ----------------------------------------------------------------------------
# block meshing (verbatim behaviour from gen_schem_loader)
# ----------------------------------------------------------------------------
def greedy(grid, W=None, H=None, L=None):
    """Greedy-mesh into axis-aligned same-key boxes (0 = air). Returns (x,y,z,dx,dy,dz,key) list.
    grid is either a numpy (W,H,L) array (W/H/L omitted - the litematic path), or a FLAT sequence
    of length W*H*L in x,y,z C-order with W/H/L given (the live-capture path, no numpy needed)."""
    if W is None:                       # numpy array passed
        return _greedy_np(grid)
    if HAVE_NUMPY:                       # flat data but numpy present -> use the fast path
        return _greedy_np(np.asarray(grid, dtype=np.int32).reshape((W, H, L)))
    return _greedy_flat(grid, W, H, L)


def _greedy_np(grid):
    boxes = []
    used = np.zeros_like(grid, dtype=bool)
    W, H, L = grid.shape
    for (x, y, z) in np.argwhere(grid > 0):
        if used[x, y, z]:
            continue
        k = grid[x, y, z]
        x1 = x
        while x1 + 1 < W and grid[x1 + 1, y, z] == k and not used[x1 + 1, y, z]:
            x1 += 1
        y1 = y
        while y1 + 1 < H and np.all(grid[x:x1 + 1, y1 + 1, z] == k) and not used[x:x1 + 1, y1 + 1, z].any():
            y1 += 1
        z1 = z
        while z1 + 1 < L and np.all(grid[x:x1 + 1, y:y1 + 1, z1 + 1] == k) and not used[x:x1 + 1, y:y1 + 1, z1 + 1].any():
            z1 += 1
        used[x:x1 + 1, y:y1 + 1, z:z1 + 1] = True
        boxes.append((int(x), int(y), int(z), int(x1 - x), int(y1 - y), int(z1 - z), int(k)))
    return boxes


def _greedy_flat(flat, W, H, L):
    """Pure-python greedy mesh (numpy-free fallback). Same semantics as _greedy_np; slower on big solid
    builds, so numpy is recommended there - but this keeps genschem working with a stock Python."""
    used = bytearray(W * H * L)
    boxes = []
    HL = H * L
    for x in range(W):
        xHL = x * HL
        for y in range(H):
            base_xy = xHL + y * L
            for z in range(L):
                i0 = base_xy + z
                k = flat[i0]
                if k == 0 or used[i0]:
                    continue
                x1 = x
                while x1 + 1 < W:
                    ii = (x1 + 1) * HL + y * L + z
                    if flat[ii] != k or used[ii]:
                        break
                    x1 += 1
                y1 = y
                while y1 + 1 < H:
                    yy = y1 + 1
                    ok = True
                    for xx in range(x, x1 + 1):
                        ii = xx * HL + yy * L + z
                        if flat[ii] != k or used[ii]:
                            ok = False
                            break
                    if not ok:
                        break
                    y1 = yy
                z1 = z
                while z1 + 1 < L:
                    zz = z1 + 1
                    ok = True
                    for xx in range(x, x1 + 1):
                        bx = xx * HL
                        for yy in range(y, y1 + 1):
                            ii = bx + yy * L + zz
                            if flat[ii] != k or used[ii]:
                                ok = False
                                break
                        if not ok:
                            break
                    if not ok:
                        break
                    z1 = zz
                for xx in range(x, x1 + 1):
                    bx = xx * HL
                    for yy in range(y, y1 + 1):
                        b2 = bx + yy * L
                        for zz in range(z, z1 + 1):
                            used[b2 + zz] = 1
                boxes.append((x, y, z, x1 - x, y1 - y, z1 - z, int(k)))
    return boxes


def split_large(boxes, cap=100_000):
    """Split any meshed box over `cap` blocks along its longest axis (halving until under).
    A solid build can greedy-mesh into a single multi-million-block box; SetRegion silently drops
    anything over 100k and a WriteTransaction batch tops out at 1M, so oversized boxes would just
    vanish from the build. Box tuples are (x,y,z,dx,dy,dz,key) with dx/dy/dz = extent-1."""
    out = []
    stack = list(boxes)
    while stack:
        (x, y, z, dx, dy, dz, k) = stack.pop()
        if (dx + 1) * (dy + 1) * (dz + 1) <= cap:
            out.append((x, y, z, dx, dy, dz, k))
            continue
        if dx >= dy and dx >= dz:
            h = dx // 2
            stack.append((x, y, z, h, dy, dz, k))
            stack.append((x + h + 1, y, z, dx - h - 1, dy, dz, k))
        elif dy >= dz:
            h = dy // 2
            stack.append((x, y, z, dx, h, dz, k))
            stack.append((x, y + h + 1, z, dx, dy - h - 1, dz, k))
        else:
            h = dz // 2
            stack.append((x, y, z, dx, dy, h, k))
            stack.append((x, y, z + h + 1, dx, dy, dz - h - 1, k))
    return out


def chunk(enc, cap=CHUNK_CAP):
    """Pack '/'-joined encoded box strings into <=cap-char values."""
    out = []
    cur = ""
    for e in enc:
        if cur and len(cur) + 1 + len(e) > cap:
            out.append(cur)
            cur = e
        else:
            cur = e if not cur else cur + "/" + e
    if cur:
        out.append(cur)
    return out


def split_boxes(boxes, keylist):
    """Split meshed boxes into plain (no block-state) vs data (has block-state) lists of
    (encoded, volume) pairs; encoded = 'x|y|z|dx|dy|dz|material[|prop=val,...]'.
    keylist[k-1] = (id, ((k,v),...)). Volumes feed insert_apply_markers' txn budgeting."""
    mat = lambda kk: kk[0].split(":")[1]
    data = lambda kk: ",".join(f"{a}={b}" for a, b in kk[1])
    plain, dataB = [], []
    for (x, y, z, dx, dy, dz, k) in boxes:
        kk = keylist[k - 1]
        d = data(kk)
        vol = (dx + 1) * (dy + 1) * (dz + 1)
        if d:
            dataB.append((f"{x}|{y}|{z}|{dx}|{dy}|{dz}|{mat(kk)}|{d}", vol))
        else:
            plain.append((f"{x}|{y}|{z}|{dx}|{dy}|{dz}|{mat(kk)}", vol))
    return plain, dataB


def mesh_air(grid, W=None, H=None, L=None):
    """Mesh the AIR cells of the capture into (encoded 'x|y|z|dx|dy|dz', volume) pairs (no
    material - the air loop hardcodes it). Air boxes and content boxes are DISJOINT and
    together cover every cell exactly once, so the async air transactions can never race the
    synchronous SetRegion content placement - no wipe/place ordering exists to get wrong.
    Strategy: group consecutive Y layers with an identical air mask (pillar/tower builds have
    long identical runs), 2D-greedy each group's mask once, volume-split to <=100k.
    grid is a numpy (W,H,L) array (W/H/L omitted) or a flat x-major sequence with W/H/L given."""
    if W is None:
        W, H, L = grid.shape                    # numpy (W,H,L) array
    elif HAVE_NUMPY:                            # flat sequence but numpy present -> fast path
        return mesh_air(np.asarray(grid, dtype=np.int32).reshape((W, H, L)))
    if HAVE_NUMPY and hasattr(grid, "shape"):
        air = (grid == 0)
        layer = lambda y: air[:, y, :]
        eq = lambda a, b: bool((a == b).all())
        mesh2d = lambda m: _greedy_np(m.astype(np.int32)[:, None, :])
    else:
        HL = H * L
        layer = lambda y: bytes(1 if grid[x * HL + y * L + z] == 0 else 0
                                for x in range(W) for z in range(L))
        eq = lambda a, b: a == b
        mesh2d = lambda m: _greedy_flat(m, W, 1, L)
    out = []
    y = 0
    m = layer(0) if H else None
    while y < H:
        y1 = y + 1
        m2 = None
        while y1 < H:
            m2 = layer(y1)
            if not eq(m2, m):
                break
            y1 += 1
            m2 = None
        gh = y1 - y
        for (x, _, z, dx, _, dz, _) in mesh2d(m):
            out.append((x, y, z, dx, gh - 1, dz, 0))
        y = y1
        if m2 is not None:
            m = m2
        elif y < H:
            m = layer(y)
    out = split_large(out)
    return [(f"{x}|{yy}|{z}|{dx}|{dy}|{dz}", (dx + 1) * (dy + 1) * (dz + 1))
            for (x, yy, z, dx, dy, dz, _) in out]


APPLY_MARK = "A"        # in-stream marker: the place loop runs ApplyTransaction here
TXN_BUDGET = 900_000    # blocks per transaction batch (1M hard cap, with headroom)


def insert_apply_markers(pairs, budget=TXN_BUDGET):
    """Turn (encoded, volume) pairs into the final entry stream, inserting 'A' markers so the
    blocks written between two ApplyTransactions never exceed the 1,000,000/txn cap. Batching
    by actual VOLUME (not box count) keeps big air slabs and tiny detail boxes equally legal.
    Never emits a leading/trailing marker; build_place's trailing ApplyTransaction flushes."""
    out, acc = [], 0
    for enc, vol in pairs:
        if acc and acc + vol > budget:
            out.append(APPLY_MARK)
            acc = 0
        out.append(enc)
        acc += vol
    return out


def emit_data_funcs(chunks, listvar, prefix):
    """Bake packed box chunks into schem{Plain,Data}N data-functions.
    Returns (files=[(filename, src)], names=[func name]). The very first SetVar uses
    CreateList; the rest AppendValue to the same `local` list."""
    pylit = lambda v: "txt(%r)" % v
    blocks = [chunks[i:i + VALS_PER_BLOCK] for i in range(0, len(chunks), VALS_PER_BLOCK)]
    funcs = [blocks[i:i + BLOCKS_PER_FUNC] for i in range(0, len(blocks), BLOCKS_PER_FUNC)]
    files, names = [], []
    for fi, fblocks in enumerate(funcs):
        name = f"{prefix}{fi}"
        names.append(name)
        lines = [f"# DF line: func:{name}    (auto-generated build data; do not edit)",
                 f"func({name!r}, tags={{'Is Hidden': 'True'}})"]
        for bi, blk in enumerate(fblocks):
            vals = ", ".join(pylit(c) for c in blk)
            act = "CreateList" if (fi == 0 and bi == 0) else "AppendValue"
            lines.append(f"SetVar({act!r}, var({listvar!r}, 'local'), {vals})")
        files.append((f"func__{name}.py", "\n".join(lines) + "\n"))
    return files, names


# ----------------------------------------------------------------------------
# static helper lines (schemPlace loop body + waitCPU) and templates
# ----------------------------------------------------------------------------
def _air_loop():
    """The bulk-air loop: WriteTransaction with a LITERAL txt('air') block arg (the one
    transaction form field-proven to execute - the placeholder form never visibly placed a
    block; see _place_loop). 'A' marker entries (insert_apply_markers keeps each batch under
    the 1M-block txn cap) run ApplyTransaction, throttled by waitTxn. Air boxes are disjoint
    from the content boxes, so these async transactions can never race the SetRegions."""
    return """with Repeat('ForEach', var('chunk', 'local'), var('airBoxes', 'local'), tags={'Allow List Changes': 'True'}):
    SetVar('SplitString', var('boxes', 'local'), var('chunk', 'local'), txt('/'), tags={'Strip Spaces': 'Disable'})
    with Repeat('ForEach', var('bs', 'local'), var('boxes', 'local'), tags={'Allow List Changes': 'True'}):
        CallFunc('waitCPU')
        with IfVar('=', var('bs', 'local'), txt('A')):
            GameAction('ApplyTransaction')
            CallFunc('waitTxn', num('3'))
        with IfVar('!=', var('bs', 'local'), txt('A')):
            SetVar('SplitString', var('f', 'local'), var('bs', 'local'), txt('|'), tags={'Strip Spaces': 'Disable'})
            SetVar('ShiftAllAxes', var('cA', 'local'), var('origin', 'local'), num('%index(f,1)'), num('%index(f,2)'), num('%index(f,3)'))
            SetVar('ShiftAllAxes', var('cB', 'local'), var('cA', 'local'), num('%index(f,4)'), num('%index(f,5)'), num('%index(f,6)'))
            GameAction('WriteTransaction', txt('air'), var('cA', 'local'), var('cB', 'local'))"""


def _place_loop(listvar, has_data):
    """One ForEach loop that fills each content box with SetRegion - the ONLY bulk-write action
    field-proven to resolve a %index placeholder in its BLOCK arg (the whole build placed with
    this exact loop shape). WriteTransaction with the same placeholder args silently placed
    NOTHING in the field ('only the heads appeared' - fixtures self-place, blocks never came),
    so transactions are reserved for the literal-arg air path (_air_loop). Boxes are pre-split
    to <=100k (SetRegion's per-action cap); waitCPU paces the synchronous work per box."""
    region = ("GameAction('SetRegion', txt('%index(f,7)'), var('cA', 'local'), var('cB', 'local'), txt('%index(f,8)'))"
              if has_data else
              "GameAction('SetRegion', txt('%index(f,7)'), var('cA', 'local'), var('cB', 'local'))")
    return f"""with Repeat('ForEach', var('chunk', 'local'), var({listvar!r}, 'local'), tags={{'Allow List Changes': 'True'}}):
    SetVar('SplitString', var('boxes', 'local'), var('chunk', 'local'), txt('/'), tags={{'Strip Spaces': 'Disable'}})
    with Repeat('ForEach', var('bs', 'local'), var('boxes', 'local'), tags={{'Allow List Changes': 'True'}}):
        CallFunc('waitCPU')
        SetVar('SplitString', var('f', 'local'), var('bs', 'local'), txt('|'), tags={{'Strip Spaces': 'Disable'}})
        SetVar('ShiftAllAxes', var('cA', 'local'), var('origin', 'local'), num('%index(f,1)'), num('%index(f,2)'), num('%index(f,3)'))
        SetVar('ShiftAllAxes', var('cB', 'local'), var('cA', 'local'), num('%index(f,4)'), num('%index(f,5)'), num('%index(f,6)'))
        {region}"""


def build_place(has_air, has_plain, has_data):
    """schemPlace: air first (async transactions, literal args), then the content SetRegion
    loops. Only includes a loop for a category that actually has data (an unset list var would
    error at runtime); the ApplyTransaction after the air loop flushes its last partial batch."""
    body = ["# DF line: func:schemPlace    (auto-generated; fills regions from baked box lists)",
            "func('schemPlace', tags={'Is Hidden': 'True'})"]
    if has_air:
        body.append(_air_loop())
        body.append("GameAction('ApplyTransaction')")
    if has_plain:
        body.append(_place_loop('plainBoxes', False))
    if has_data:
        body.append(_place_loop('dataBoxes', True))
    if not (has_air or has_plain or has_data):
        body.append("PlayerAction('SendMessage', comp('<gray>(no blocks in this build)'))")
    return "\n".join(body) + "\n"


WAITCPU = """# DF line: func:waitCPU    (yields when CPU is busy so a big build spreads over ticks)
func('waitCPU', item('{DF_NBT:4671,count:1,id:"minecraft:command_block"}'), raw_item('pn_el', {'name': 'cpu', 'type': 'num', 'default_value': {'id': 'num', 'data': {'name': '98'}}, 'plural': False, 'optional': True}), tags={'Is Hidden': 'False'})
SetVar('MinNumber', var('players', 'line'), gval('Player Count'), num('8'))
SetVar('-', var('check', 'line'), gval('Microseconds Since Startup'), var('microseconds', 'local'))
with IfVar('>=', var('check', 'line'), num('%math(%var(players)-1*2850+%math(%var(cpu)*200))')):
    Control('Wait', tags={'Time Unit': 'Ticks'})
    SetVar('=', var('microseconds', 'local'), gval('Microseconds Since Startup'))
"""

WAITTXN = """# DF line: func:waitTxn    (waits until the plot's Active Block Transactions drain to <= cap)
# Purely a THROTTLE + settle aid - correctness never depends on it: every box the loader writes
# is disjoint (air included), so even if this plot value reads 0 (it's Overlord-gated in the
# actiondump) the worst case is all transactions executing at once, not a wrong result.
func('waitTxn', item('{DF_NBT:4671,count:1,id:"minecraft:command_block"}'), raw_item('pn_el', {'name': 'cap', 'type': 'num', 'default_value': {'id': 'num', 'data': {'name': '0'}}, 'plural': False, 'optional': True}), tags={'Is Hidden': 'True'})
SetVar('=', var('tpoll', 'line'), num('0'))
with Repeat('Forever'):
    with IfVar('<=', gval('Active Block Transactions'), num('%var(cap)')):
        Control('StopRepeat')
    SetVar('+=', var('tpoll', 'line'), num('1'))
    with IfVar('>', var('tpoll', 'line'), num('6000')):
        Control('StopRepeat')
    Control('Wait', tags={'Time Unit': 'Ticks'})
"""


def build_load_func(place_names, fixture_names):
    """func:schemLoad - the whole load, shared by the PlotStartup auto-load, the manual @load command
    and the LagSlayRecover event (locals travel through CallFunc, so all run identically).
    Loads at the FIXED plot origin loc(0, 0, 0) - standing position never matters.
    There is NO separate wipe pass: mesh_air bakes the region's air cells into the same box stream
    as the content, so the placement writes every cell exactly once through disjoint transactions -
    the wipe/place race (parallel applied transactions striped the build in the field) is impossible
    by construction. The only ordering that still matters is blocks-before-fixtures (sign text /
    container contents need their host block to exist), covered by a waitTxn drain plus a short
    settle wait. schemLoadDone ('unsaved' = plot-session scope) is 1 only after a COMPLETE load -
    what LagSlayRecover checks."""
    parts = ["# DF line: func:schemLoad    (auto-generated build loader - runs on plot startup, or type @load in play mode)",
             "func('schemLoad', tags={'Is Hidden': 'True'})",
             "SetVar('=', var('schemLoadDone', 'unsaved'), num('0'))",
             "SetVar('=', var('origin', 'local'), loc(0, 0, 0))",
             "SetVar('=', var('microseconds', 'local'), gval('Microseconds Since Startup'))"]
    parts += [f"CallFunc({n!r})" for n in place_names]
    if fixture_names:
        # blocks-before-fixtures: drain executing transactions, then a short settle wait as a
        # fallback in case the plot value is unavailable at this rank (it reads 0 then).
        parts += ["CallFunc('waitTxn')",
                  "Control('Wait', num('20'), tags={'Time Unit': 'Ticks'})"]
    parts += [f"CallFunc({n!r})" for n in fixture_names]
    parts += ["SetVar('=', var('schemLoadDone', 'unsaved'), num('1'))"]
    return "\n".join(parts) + "\n"


def build_recover_event():
    """game_event:LagSlayRecover - DF's own recovery hook: fires when the plot recovers from a
    LagSlayer halt. The halt killed every running thread, so if the load hadn't finished
    (schemLoadDone != 1) it is dead - not in-progress - and safe to re-run from scratch."""
    return ("# DF line: game_event:LagSlayRecover    (re-runs the build load if the lag slayer killed it)\n"
            "game_event('LagSlayRecover')\n"
            "with IfVar('!=', var('schemLoadDone', 'unsaved'), num('1')):\n"
            "    CallFunc('schemLoad')\n")


def build_startup_event():
    """game_event:PlotStartup - the build loads itself every time the plot starts up (no command needed)."""
    return ("# DF line: game_event:PlotStartup    (auto-loads the build whenever the plot starts up)\n"
            "game_event('PlotStartup')\n"
            "CallFunc('schemLoad')\n")


def build_event(place_names, fixture_names, dims):
    """event:Command (`@load`): OPTIONAL manual re-load (e.g. after building over the area) - the
    build already auto-loads on plot startup via game_event:PlotStartup. Args kept for signature
    compatibility; the actual load lives in func:schemLoad."""
    return ("# DF line: event:Command    (optional manual re-load: type @load in play mode; the build auto-loads on plot startup)\n"
            "event('Command')\n"
            "with IfGame('CmdArgEquals', txt('load'), num('1'), tags={'Ignore Case': 'True'}):\n"
            "    GameAction('CancelEvent')\n"
            "    PlayerAction('SendMessage', comp('<yellow>Loading build... please wait, this takes a bit.'))\n"
            "    CallFunc('schemLoad')\n"
            "    PlayerAction('SendMessage', comp('<green>Build placed!'))\n")


# ----------------------------------------------------------------------------
# fixtures: signs / heads / containers as explicit GameAction blocks
# ----------------------------------------------------------------------------
def _shift(x, y, z):
    return ("SetVar('ShiftAllAxes', var('p', 'local'), var('origin', 'local'), "
            f"num({str(int(x))!r}), num({str(int(y))!r}), num({str(int(z))!r}))")


def _container_lines(items):
    """items: list of {slot,snbt} for one container. SetContainer fills from slot 0 in order,
    so only use it when the items are contiguous from slot 0; otherwise SetItemInSlot per slot
    (slot-accurate). A code-block chest holds 27 arg slots => location + <=26 items per block."""
    items = sorted(items, key=lambda it: it["slot"])
    slots = [it["slot"] for it in items]
    contiguous = slots == list(range(len(slots)))
    out = []
    if contiguous and len(items) <= 26:
        args = ", ".join("item(%r)" % it["snbt"] for it in items)
        out.append(f"GameAction('SetContainer', var('p', 'local'), {args})")
    elif contiguous and len(items) == 27:
        args = ", ".join("item(%r)" % it["snbt"] for it in items[:26])
        out.append(f"GameAction('SetContainer', var('p', 'local'), {args})")
        out.append("GameAction('SetItemInSlot', var('p', 'local'), item(%r), num('26'))" % items[26]["snbt"])
    else:
        for it in items:
            out.append("GameAction('SetItemInSlot', var('p', 'local'), item(%r), num(%r))"
                       % (it["snbt"], str(it["slot"])))
    return out


def _lock_key(lock_snbt):
    """Best-effort lock KEY out of a captured minecraft:lock component (for LockContainer).
    Two forms exist in the wild: a bare string key (pre-1.21.2 / plugin-set), and the modern
    item-predicate form whose custom_name predicate holds the key. Returns None if no key
    can be recovered (the container stays unlocked, with a warning upstream)."""
    if not lock_snbt:
        return None
    s = str(lock_snbt).strip()
    # bare string: "key" (quoted SNBT string) or key
    if s.startswith('"') and s.endswith('"') and "custom_name" not in s:
        return s[1:-1].replace('\\"', '"') or None
    if "{" not in s:
        return s or None
    # predicate form: extract the custom_name value; it's either a plain string or a JSON text component
    import json as _json
    import re as _re
    m = _re.search(r'(?:minecraft:)?custom_name["\']?\s*[:=]\s*("(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\')', s)
    if not m:
        return None
    val = m.group(1)[1:-1].replace('\\"', '"').replace("\\'", "'")
    try:
        parsed = _json.loads(val)
        if isinstance(parsed, str):
            return parsed or None
        if isinstance(parsed, dict):
            return parsed.get("text") or None
    except Exception:
        pass
    return val or None


def _df_dye(name):
    """Minecraft dye name -> DF 'Text Color' tag option ('light_blue' -> 'Light blue')."""
    return str(name).replace("_", " ").capitalize()


def _sign_lines(ent):
    """ChangeSign per non-blank line, front then back (DF line numbers are 1-based; the back side
    is selected with the 'Sign Side' tag), plus SignColor for a side dyed non-black or glowing."""
    out = []
    for side in ("front", "back"):
        lines = ent.get(side, []) or []
        has_text = any(l and l.strip() for l in lines)
        back_tag = ", tags={'Sign Side': 'Back'}" if side == "back" else ""
        for i, line in enumerate(lines, start=1):
            if line and line.strip():
                out.append("GameAction('ChangeSign', var('p', 'local'), num(%r), comp(%r)%s)"
                           % (str(i), line, back_tag))
        color = (ent.get(side + "Color") or "black").lower()
        glow = bool(ent.get(side + "Glow"))
        if has_text and (color != "black" or glow):
            tags = {"Text Color": _df_dye(color)}
            if glow:
                tags["Glowing"] = "Enable"
            if side == "back":
                tags["Sign Side"] = "Back"
            out.append("GameAction('SignColor', var('p', 'local'), tags=%r)" % (tags,))
    return out


def _fixture_unit(ent):
    """Turn one captured entity into (block_count, payload_bytes, [src_lines], [warnings]) anchored
    to origin. Returns None for an entity we can't reproduce."""
    kind = ent.get("kind")
    body = []
    warnings = []
    if kind == "sign":
        body.extend(_sign_lines(ent))
    elif kind == "head":
        snbt = ent.get("snbt")
        if snbt:
            body.append("GameAction('SetHead', var('p', 'local'), item(%r))" % snbt)
    elif kind == "container":
        items = ent.get("items") or []
        if items:
            body.extend(_container_lines(items))
        name = ent.get("name")
        if name:                                # custom container name -> SetContainerName
            body.append("GameAction('SetContainerName', var('p', 'local'), comp(%r))" % name)
        lock = ent.get("lock")
        if lock:                                # captured lock -> LockContainer(loc, key)
            key = _lock_key(lock)
            if key:
                body.append("GameAction('LockContainer', var('p', 'local'), txt(%r))" % key)
            else:
                warnings.append(f"container at ({ent.get('x')},{ent.get('y')},{ent.get('z')}) is locked "
                                f"but the key couldn't be recovered from {lock!r} - left unlocked")
    if not body:
        return None
    lines = [_shift(ent["x"], ent["y"], ent["z"])] + body
    payload = sum(len(s) for s in lines)
    return (len(lines), payload, lines, warnings)


def emit_fixtures(entities, plot_length=300):
    """Pack sign/head/container fixtures into schemFixturesN funcs under block + byte budgets,
    inserting CallFunc('waitCPU') periodically. Returns (files, names, warnings).
    The block budget comes from the plot size: a code line physically holds plot_length blocks
    and every non-bracket code block is 2 wide (func header included), so a fixtures func can
    hold at most plot_length/2 - 1 body blocks - minus margin so it ALWAYS fits its plot."""
    block_cap = max(8, int(plot_length or 300) // 2 - 3)   # body blocks; 300 -> 147 (296 long)
    units = []
    warnings = []
    for ent in entities:
        u = _fixture_unit(ent)
        if u is None:
            continue
        bc, by, lines, unit_warnings = u
        warnings.extend(unit_warnings)
        if bc > block_cap or by > FIX_BYTE_CAP:
            warnings.append(f"large {ent.get('kind')} fixture at "
                            f"({ent.get('x')},{ent.get('y')},{ent.get('z')}): {bc} blocks / {by} bytes "
                            f"- placed on its own line, may exceed limits")
        units.append((bc, by, lines))

    files, names = [], []
    cur, cur_blocks, cur_bytes, since_wait = [], 0, 0, 0
    idx = 0

    def flush():
        nonlocal cur, cur_blocks, cur_bytes, since_wait, idx
        if not cur:
            return
        name = f"schemFixtures{idx}"
        idx += 1
        src = "\n".join([f"# DF line: func:{name}    (auto-generated build fixtures; do not edit)",
                         f"func({name!r}, tags={{'Is Hidden': 'True'}})"] + cur)
        files.append((f"func__{name}.py", src + "\n"))
        names.append(name)
        cur, cur_blocks, cur_bytes, since_wait = [], 0, 0, 0

    for bc, by, lines in units:
        if cur and (cur_blocks + bc + 1 > block_cap or cur_bytes + by > FIX_BYTE_CAP):
            flush()
        if cur and since_wait >= WAIT_EVERY:
            cur.append("CallFunc('waitCPU')")
            cur_blocks += 1
            since_wait = 0
        cur.extend(lines)
        cur_blocks += bc
        cur_bytes += by
        since_wait += bc
    flush()
    return files, names, warnings
