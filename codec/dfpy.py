#!/usr/bin/env python3
"""dfpy.py - the DF VIBE codec CLI. Pure subprocess: template JSON <-> .py text.

The Fabric mod owns the CodeClient wire format (scan/place, gzip+base64) and the
filesystem; this CLI only maps between DiamondFire template JSON and .py source,
batched so one process call handles a whole sync.

The mod speaks CodeClient's wire format ("lines" = the base64+gzip tokens that
`scan` returns and `place` consumes); this CLI owns all DF-format knowledge incl.
the gzip/base64 wire codec. Batched so one process call handles a whole sync.

Commands (all read JSON on stdin, write JSON on stdout unless noted):

  decompile        in:  {"lines": ["<b64gzip>", ...]}        (scan output)
                   out: {"files": [ {id,type,key,file,source,hash}, ... ],
                         "warnings": ["..."]}   (e.g. duplicate-name files uniquified)

  recompile        in:  {"files": [ {file,source}, ... ]}
                   out: {"files": [ {file,id,line,hash}, ... ]}   (line=b64gzip ready
                        for place; hash=content hash for change detection)

  identify         in:  {"lines": ["<b64gzip>", ...]}
                   out: {"files": [ {id,type,key,file}, ... ]}

  check  FILE...   compile each .py file AND validate it as real DiamondFire
                   (action/tag/value names, chest, brackets, scopes, line length,
                   loops). Prints a report; exit 1 if any file fails to compile or
                   has an ERROR. (For the AI to self-check edits.)

  validate PATH... like check but accepts a project DIR too, adding cross-line checks
                   (duplicate events/func names, calls to undefined funcs).
                   --plot-length N sets the plot's code-line limit (default 300=Massive).

  selftest         round-trip Research/scan_decoded.json; exit 0 if all lossless.
                   --backups [DIR] instead round-trips every raw scan token in
                   plots/.df-backups (real-plot regression corpus).

Options:
  --actiondump PATH   path to actiondump.json (else DFPY_ACTIONDUMP, else auto).

On errors, prints {"error": "..."} to stdout and exits 2, so the mod can surface
the message in chat.
"""
import sys, json, argparse, pathlib

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

import df_codec as C

HEADER_TMPL = "# DF line: {id}    (edit the code below; this comment is ignored)\n"


def _read_stdin_json():
    raw = sys.stdin.buffer.read().decode("utf-8")
    if not raw.strip():
        return {}
    return json.loads(raw)


def cmd_decompile(args):
    req = _read_stdin_json()
    files, errors, warnings = [], [], []
    seen, seen_ids = set(), set()  # case-folded: Windows filesystems collapse case, so 'Shop' and 'shop' collide
    for i, line in enumerate(req.get("lines", [])):
        try:
            tpl = C.decode_line(line)
            ident = C.identify(tpl)
            if ident["file"].casefold() in seen:
                stem = ident["file"][:-3]
                n = 2
                while f"{stem}__{n}.py".casefold() in seen:
                    n += 1
                why = (f"plot has more than one '{ident['id']}' line" if ident["id"] in seen_ids
                       else f"'{ident['id']}' differs from another line's name only by case/symbols")
                ident = {**ident, "file": f"{stem}__{n}.py"}
                warnings.append(f"{why} - saved as {ident['file']} so nothing is lost "
                                f"(rename one header, then /df deploy)")
            seen.add(ident["file"].casefold())
            seen_ids.add(ident["id"])
            source = HEADER_TMPL.format(id=ident["id"]) + C.decompile(tpl)
            files.append({**ident, "source": source, "hash": C.canon_hash(tpl)})
        except Exception as e:
            # keep going; capture the raw line so the codec can be taught this shape
            errors.append({"index": i, "error": f"{type(e).__name__}: {e}", "line": line})
    json.dump({"files": files, "errors": errors, "warnings": warnings,
               "codec_version": C.CODEC_VERSION}, sys.stdout, ensure_ascii=False)


def _err_line(e, filename):
    """Line number of the deepest traceback frame inside the exec'd source."""
    if isinstance(e, SyntaxError) and e.filename == filename:
        return e.lineno
    lineno, tb = None, e.__traceback__
    while tb is not None:
        if tb.tb_frame.f_code.co_filename == filename:
            lineno = tb.tb_lineno
        tb = tb.tb_next
    return lineno


def cmd_recompile(args):
    import df_validate as V
    req = _read_stdin_json()
    out, errors, issues = [], [], []
    for f in req.get("files", []):
        fname = f.get("file") or "<string>"
        try:
            tpl = C.recompile(f["source"], fname)
            ident = C.identify(tpl)
            out.append({"file": f.get("file"), "id": ident["id"],
                        "line": C.encode_line(tpl), "hash": C.canon_hash(tpl)})
            if C.LAST_EXEC_OUTPUT:
                issues.append({"file": f.get("file"), "code": "STRAY_OUTPUT", "where": fname,
                               "msg": "file print()s output - remove it (not DiamondFire code)"})
            # surface ERROR-level validation problems (broken-but-compiles) so the mod can
            # warn before placing. Skip the length check — the mod enforces length itself
            # against the live plot size. WARNs are left to `dfpy.py check` (AI self-check).
            try:
                for i in V.validate_template(tpl, plot_code_length=0):
                    if i.sev == "ERROR":
                        issues.append({"file": f.get("file"), "code": i.code,
                                       "where": i.where, "msg": i.msg})
            except Exception:
                pass  # validation must never block a recompile
        except Exception as e:
            loc = _err_line(e, fname)
            errors.append({"file": f.get("file"),
                           "error": f"{type(e).__name__}: {e}" + (f" (line {loc})" if loc else "")})
    json.dump({"files": out, "errors": errors, "issues": issues,
               "codec_version": C.CODEC_VERSION}, sys.stdout, ensure_ascii=False)


def cmd_identify(args):
    req = _read_stdin_json()
    files = [C.identify(C.decode_line(l)) for l in req.get("lines", [])]
    json.dump({"files": files}, sys.stdout, ensure_ascii=False)


def _plot_length(args, paths):
    """--plot-length, else the plotLength the mod recorded in the project's
    .df-vibe.json at pull/push, else 300 (Massive)."""
    if getattr(args, "plot_length", None):
        return args.plot_length
    for p in paths:
        cfg = (p if p.is_dir() else p.parent) / ".df-vibe.json"
        if cfg.exists():
            try:
                v = json.loads(cfg.read_text(encoding="utf-8")).get("plotLength")
                if v:
                    return int(v)
            except Exception:
                pass
    return 300


def _apply_rank(args, paths):
    """Set the rank the RANK_LOCKED check validates against: --rank, else the 'rank' the mod
    recorded in the project's .df-vibe.json, else none (check disabled)."""
    import df_validate as V
    rank = getattr(args, "rank", None)
    if not rank:
        for p in paths:
            cfg = (p if p.is_dir() else p.parent) / ".df-vibe.json"
            if cfg.exists():
                try:
                    rank = json.loads(cfg.read_text(encoding="utf-8")).get("rank")
                    if rank:
                        break
                except Exception:
                    pass
    V.set_user_rank(rank)


def cmd_check(args):
    import df_validate as V
    paths = [pathlib.Path(p) for p in args.files]
    plen = _plot_length(args, paths)
    _apply_rank(args, paths)
    ok_all = True
    for path in paths:
        try:
            src = path.read_text(encoding="utf-8-sig")
            tpl = C.recompile(src, str(path))
            n = len(tpl["blocks"])
            ident = C.identify(tpl)
        except Exception as e:
            ok_all = False
            loc = _err_line(e, str(path))
            at = f" at {path.name}:{loc}" if loc and not isinstance(e, SyntaxError) else ""
            print(f"[FAIL] {path.name:<32} {type(e).__name__}: {e}{at}  (does not compile)")
            continue
        issues = V.validate_template(tpl, plot_code_length=plen)
        if C.LAST_EXEC_OUTPUT:
            issues.append(V.Issue("WARN", "STRAY_OUTPUT", path.name,
                                  "file print()s output - remove it (not DiamondFire code)"))
        errs = [i for i in issues if i.sev == "ERROR"]
        warns = [i for i in issues if i.sev == "WARN"]
        if errs:
            ok_all = False
        tag = "[FAIL]" if errs else ("[WARN]" if warns else "[PASS]")
        print(f"{tag} {path.name:<32} {ident['id']:<30} ({n} blocks"
              + (f", {len(errs)} error" if errs else "") + (f", {len(warns)} warn" if warns else "") + ")")
        for i in errs + warns:
            print(f"        {i.sev:5} {i.code:<16} {i.where}: {i.msg}")
    return 0 if ok_all else 1


def _load_project(d):
    """A project dir -> {filename: (block_count|None, content_hash|None, id, compile_error|None)}.
    Compiles every .py line so we can compare two pulls of the same plot."""
    out = {}
    for f in sorted(d.glob("*.py")):
        if f.name.startswith("."):
            continue
        try:
            src = f.read_text(encoding="utf-8-sig")
            tpl = C.recompile(src, str(f))
            out[f.name] = (len(tpl["blocks"]), C.canon_hash(tpl), C.identify(tpl)["id"], None)
        except Exception as e:
            out[f.name] = (None, None, f.name, f"{type(e).__name__}: {e}")
    return out


def cmd_diff(args):
    """Compare two pulled projects of the SAME plot (e.g. a normal pull vs a `-chests` pull) and flag where
    they differ - in particular lines that have FEWER blocks in B, which is the -chests truncation symptom.
    Match is by filename (both pulls derive the same filename from a line's starter, even when truncated)."""
    da, db = pathlib.Path(args.a), pathlib.Path(args.b)
    if not da.is_dir() or not db.is_dir():
        print("diff needs two project directories: dfpy.py diff <projA> <projB>")
        return 2
    A, B = _load_project(da), _load_project(db)
    names = sorted(set(A) | set(B))
    same = 0
    diffs, only_a, only_b, errs = [], [], [], []
    for n in names:
        if n in A and n in B:
            ba, ha, ida, ea = A[n]
            bb, hb, idb, eb = B[n]
            if ea or eb:
                errs.append((n, ea or eb))
            elif ha == hb:
                same += 1
            else:
                diffs.append((n, ida, ba, bb))
        elif n in A:
            only_a.append(A[n][2])
        else:
            only_b.append(B[n][2])

    print(f"diff  A={da.name}  B={db.name}")
    print(f"  {len(names)} file(s): {same} identical, {len(diffs)} differ, "
          f"{len(only_a)} only in A, {len(only_b)} only in B, {len(errs)} failed to compile")
    trunc = [d for d in diffs if d[2] is not None and d[3] is not None and d[3] < d[2]]
    if diffs:
        print("\n  DIFFERENT (blocks A -> B; negative = B has FEWER = likely -chests truncation):")
        for n, idd, ba, bb in sorted(diffs, key=lambda d: (d[3] - d[2]) if (d[2] and d[3]) else 0):
            delta = f"{bb - ba:+d}" if (ba is not None and bb is not None) else "?"
            if ba is not None and bb is not None and bb < ba:
                flag = "  <== TRUNCATED in B"
            elif ba == bb:
                flag = "  (same size, content differs)"
            else:
                flag = ""
            print(f"    {idd:<34} {ba} -> {bb}  ({delta}){flag}")
    if only_a:
        print(f"\n  ONLY IN A ({len(only_a)}): " + ", ".join(only_a))
    if only_b:
        print(f"\n  ONLY IN B ({len(only_b)}): " + ", ".join(only_b))
    if errs:
        print(f"\n  FAILED TO COMPILE ({len(errs)}):")
        for n, e in errs:
            print(f"    {n}: {e}")
    if trunc:
        print(f"\n  ==> {len(trunc)} line(s) are TRUNCATED in B (fewer blocks). If B is the -chests pull, "
              "that's the bug; the normal pull (A) is ground truth.")
    return 1 if trunc else 0


def _parse_palette(palette):
    """palette[0] is air; non-air entries 'id' or 'id|k=v,k=v' (props sorted) ->
    keylist for schem_gen.split_boxes (keylist[k-1] = (id, ((k,v),...)))."""
    keylist = []
    for entry in palette[1:]:
        if "|" in entry:
            bid, props = entry.split("|", 1)
            kv = tuple(sorted(tuple(p.split("=", 1)) for p in props.split(",") if p))
        else:
            bid, kv = entry, ()
        keylist.append((bid, kv))
    return keylist


def cmd_genschem(args):
    """Generate a build-loader project from a mod-written capture directory.
    in (stdin): {"captureDir","outDir","name","plotLength"}
    The big block volume (blocks.gz) is read from disk here, never through the pipe."""
    import gzip
    import array
    import schem_gen as G
    import df_validate as V

    req = _read_stdin_json()
    cap = pathlib.Path(req["captureDir"])
    out = pathlib.Path(req["outDir"])
    name = req.get("name") or out.name
    plen = int(req.get("plotLength") or 300)

    meta = json.loads((cap / "meta.json").read_text(encoding="utf-8"))
    palette = json.loads((cap / "palette.json").read_text(encoding="utf-8"))
    entities = json.loads((cap / "entities.json").read_text(encoding="utf-8")) if (cap / "entities.json").exists() else []
    W, H, L = meta["dims"]
    typecode = {"uint8": "B", "uint16": "H", "uint32": "I"}.get(meta.get("dtype", "uint16"), "H")

    raw = gzip.decompress((cap / "blocks.gz").read_bytes())
    flat = array.array(typecode)
    flat.frombytes(raw)
    if sys.byteorder != "little":                # blocks.gz is little-endian (mod writes LE)
        flat.byteswap()

    keylist = _parse_palette(palette)
    boxes = G.greedy(flat, W, H, L)             # numpy if present, else pure-python fallback
    boxes = G.split_large(boxes)                # a >100k box would overflow the txn batch budgeting
    plain, dataB = G.split_boxes(boxes, keylist)
    # Air is WRITTEN too (bulk async transactions with a literal 'air' arg); content goes through
    # SetRegion. The two sets are disjoint and together cover every cell exactly once, so there
    # is no wipe/place ordering to get wrong.
    air = G.mesh_air(flat, W, H, L)
    ac = G.chunk(G.insert_apply_markers(air))
    pc = G.chunk([e for e, _ in plain])
    dc = G.chunk([e for e, _ in dataB])

    af, anames = G.emit_data_funcs(ac, 'airBoxes', 'schemAir')
    pf, pnames = G.emit_data_funcs(pc, 'plainBoxes', 'schemPlain')
    df_, dnames = G.emit_data_funcs(dc, 'dataBoxes', 'schemData')
    place_names = anames + pnames + dnames + (['schemPlace'] if (ac or pc or dc) else [])
    fix_files, fix_names, fix_warnings = G.emit_fixtures(entities, plen)

    files = list(af) + list(pf) + list(df_) + list(fix_files)
    if ac or pc or dc:
        files.append(("func__schemPlace.py", G.build_place(bool(ac), bool(pc), bool(dc))))
    files.append(("func__waitCPU.py", G.WAITCPU))
    files.append(("func__waitTxn.py", G.WAITTXN))
    files.append(("func__schemLoad.py", G.build_load_func(place_names, fix_names)))
    files.append(("game_event__PlotStartup.py", G.build_startup_event()))
    files.append(("game_event__LagSlayRecover.py", G.build_recover_event()))
    files.append(("event__Command.py", G.build_event(place_names, fix_names, (W, H, L))))

    out.mkdir(parents=True, exist_ok=True)
    # clear stale generated lines from a previous save (keep .capture/, .df-vibe.json, user files)
    # (event__Join.py was one generation's lagslay watchdog - superseded by LagSlayRecover, delete it)
    for old in out.glob("func__schem*.py"):
        old.unlink()
    for nm in ("func__schemPlace.py", "func__waitCPU.py", "func__waitTxn.py", "func__schemLoad.py",
               "game_event__PlotStartup.py", "game_event__LagSlayRecover.py",
               "event__Join.py", "event__Command.py"):
        p = out / nm
        if p.exists():
            p.unlink()
    for fn, src in files:
        (out / fn).write_text(src, encoding="utf-8")

    # validate the generated project; surface any ERROR (e.g. a fixture line over the byte/length cap)
    warnings = list(fix_warnings)
    try:
        issues = V.validate_project([(fn, src) for fn, src in files], plot_code_length=plen)
        for i in issues:
            if i.sev == "ERROR":
                warnings.append(f"{i.code} {i.where}: {i.msg}")
    except Exception as e:
        warnings.append(f"validation skipped: {type(e).__name__}: {e}")

    json.dump({"files": len(files), "boxes": len(boxes) + len(air), "fixtures": len(fix_names),
               "lines": len(files), "name": name, "warnings": warnings,
               "codec_version": C.CODEC_VERSION}, sys.stdout, ensure_ascii=False)
    return 0


def cmd_validate(args):
    import df_validate as V
    paths = [pathlib.Path(p) for p in args.files]
    plen = _plot_length(args, paths)
    _apply_rank(args, paths)
    # Expand directories into their *.py files; run project-level checks when a dir is given.
    py_files, project_mode = [], False
    for path in paths:
        if path.is_dir():
            project_mode = True
            for f in sorted(path.glob("*.py")):
                if not f.name.startswith("."):
                    py_files.append((f.name, f.read_text(encoding="utf-8-sig")))
        else:
            py_files.append((path.name, path.read_text(encoding="utf-8-sig")))

    if project_mode:
        issues = V.validate_project(py_files, plot_code_length=plen)
    else:
        issues = []
        for name, src in py_files:
            try:
                tpl = C.recompile(src)
            except Exception as e:
                issues.append(V.Issue("ERROR", "PARSE", name, f"{type(e).__name__}: {e}"))
                continue
            for i in V.validate_template(tpl, plot_code_length=plen):
                i.where = f"{name}: {i.where}" if i.where else name
                issues.append(i)

    errs = [i for i in issues if i.sev == "ERROR"]
    warns = [i for i in issues if i.sev == "WARN"]
    for i in errs + warns:
        print(f"{i.sev:5} {i.code:<16} {i.where}: {i.msg}")
    print(f"\n{len(py_files)} file(s): {len(errs)} ERROR, {len(warns)} WARN")
    return 0 if not errs else 1


def cmd_selftest(args):
    if getattr(args, "backups", None) is not None:
        bdir = pathlib.Path(args.backups) if args.backups else C.HERE.parent / "plots" / ".df-backups"
        corpora = sorted(bdir.glob("*.txt"))
        if not corpora:
            print(f"no backup corpora (*.txt) in {bdir}")
            return 2
        total = passed = 0
        for f in corpora:
            toks = [t for t in f.read_text(encoding="utf-8").splitlines()
                    if t.strip() and t.strip() != "empty"]  # "empty" = the API's no-code sentinel
            n = 0
            for tok in toks:
                try:
                    tpl = C.decode_line(tok)
                    n += C.canon(C.recompile(C.decompile(tpl))) == C.canon(tpl)
                except Exception:
                    pass
            print(f"[{'PASS' if n == len(toks) else 'FAIL':4}] {f.name:<48} {n}/{len(toks)}")
            total += len(toks); passed += n
        print(f"\n{passed}/{total} backup lines round-trip losslessly.")
        return 0 if passed == total else 1
    scan = C.HERE.parent / "Research" / "scan_decoded.json"
    if not scan.exists():
        print(f"selftest data not found: {scan}")
        return 2
    data = json.loads(scan.read_text(encoding="utf-8"))
    passed = 0
    for tpl in data:
        ident = C.identify(tpl)
        try:
            rebuilt = C.recompile(C.decompile(tpl))
            ok = C.canon(rebuilt) == C.canon(tpl)
        except Exception as e:
            print(f"[FAIL] {ident['id']:<30} {type(e).__name__}: {e}")
            continue
        passed += ok
        print(f"[{'PASS' if ok else 'DIFF':4}] {ident['id']:<30} ({len(tpl['blocks'])} blocks)")
    print(f"\n{passed}/{len(data)} lines round-trip losslessly.")
    return 0 if passed == len(data) else 1


def _parse_tags(pairs):
    out = {}
    for p in pairs or []:
        if "=" not in p:
            raise SystemExit(f"bad --tag {p!r}; use key=value")
        k, v = p.split("=", 1)
        try:
            v = float(v) if ("." in v or v.lstrip("-").isdigit()) else v
            if isinstance(v, float) and v.is_integer():
                v = int(v) if v == int(v) else v
        except ValueError:
            pass
        out[k.strip()] = v
    return out


def _parse_kv(pairs, what, cast=None):
    out = {}
    for p in pairs or []:
        if "=" not in p:
            raise SystemExit(f"bad --{what} {p!r}; use name=value")
        k, v = p.split("=", 1)
        out[k.strip()] = cast(v) if cast else v
    return out


def _parse_attrs(specs):
    """--attr type=amount[:operation[:slot]] -> build_item attribute dicts."""
    out = []
    for p in specs or []:
        if "=" not in p:
            raise SystemExit(f"bad --attr {p!r}; use type=amount[:operation[:slot]]")
        k, v = p.split("=", 1)
        parts = v.split(":")
        a = {"type": k.strip(), "amount": float(parts[0])}
        if len(parts) > 1 and parts[1]: a["operation"] = parts[1]
        if len(parts) > 2 and parts[2]: a["slot"] = parts[2]
        out.append(a)
    return out


def cmd_mkitem(args):
    import df_item as Itm, df_text as Txt
    Txt.enable_ansi()
    trim = None
    if args.trim:
        if ":" not in args.trim:
            raise SystemExit(f"bad --trim {args.trim!r}; use material:pattern")
        trim = tuple(args.trim.split(":", 1))
    snbt = Itm.build_item(
        args.material, name=args.name, lore=args.lore or None, count=args.count,
        tags=_parse_tags(args.tag) or None, glow=args.glow, head=args.head,
        head_texture=args.head_texture, dyed=args.dyed,
        model=(float(args.model) if args.model is not None else None),
        unbreakable=args.unbreakable, hide=args.hide or None,
        enchantments=_parse_kv(args.enchant, "enchant", int) or None,
        stored_enchantments=_parse_kv(args.stored_enchant, "stored-enchant", int) or None,
        attributes=_parse_attrs(args.attr) or None,
        item_name=args.item_name, potion=args.potion, trim=trim, rarity=args.rarity,
        max_stack=args.max_stack, damage=args.damage, max_damage=args.max_damage,
        repair_cost=args.repair_cost,
        block_state=_parse_kv(args.block_state, "block-state") or None)
    if args.snbt:
        print(snbt); return 0
    lit = "item('%s')" % snbt.replace("\\", "\\\\").replace("'", "\\'")
    if args.literal:
        print(lit); return 0
    print(Itm.preview(snbt))
    print()
    print(lit)
    return 0


def cmd_text(args):
    import df_text as Txt
    Txt.enable_ansi()
    mm = args.text
    print(Txt.ansi(Txt.parse_mm(mm)))
    if not args.preview_only:
        print()
        call = "txt" if args.txt else "comp"
        print("%s('%s')" % (call, mm.replace("\\", "\\\\").replace("'", "\\'")))
    return 0


def cmd_preview(args):
    import df_item as Itm, df_text as Txt
    Txt.enable_ansi()
    if args.snbt:
        print(Itm.preview(args.snbt))
        if args.png:
            import df_render as R
            R.item_png(args.snbt, args.png); print("[png] %s" % args.png)
        return 0
    paths = []
    for p in args.paths:
        pp = pathlib.Path(p)
        paths += sorted(pp.glob("*.py")) if pp.is_dir() else [pp]
    total = 0
    for path in paths:
        src = path.read_text(encoding="utf-8", errors="replace")
        items = Itm.extract_items(src)
        named = [s for s in items if (lambda x: x["name"] or x["lore"])(Itm.item_summary(s))]
        if not named:
            continue
        print("\n=== %s : %d item(s) with name/lore (of %d) ===" % (path.name, len(named), len(items)))
        for i, s in enumerate(named, 1):
            print(Itm.preview(s, index=i))
        total += len(named)
        if args.png:
            import df_render as R
            out = args.png if len(paths) == 1 else "%s.%s.png" % (args.png.rsplit(".png", 1)[0], path.stem)
            R.items_png(named, out, title=path.name); print("[png] %s" % out)
    if total == 0:
        print("(no items with a name or lore found)")
    return 0


def cmd_menu(args):
    import df_menu as M, df_text as Txt
    Txt.enable_ansi()
    if args.new:
        print(M.scaffold(rows=args.rows, title=args.title, border=args.border, fill=args.fill))
        return 0
    if not args.path:
        raise SystemExit("menu: give a .py file to preview, or --new to scaffold")
    src = pathlib.Path(args.path).read_text(encoding="utf-8", errors="replace")
    items = M.grid_from_source(src)
    if not items:
        print("(no ShowInv/ExpandInv menu found in %s)" % args.path); return 0
    print(M.render_grid(items, title=args.title if args.new else None))
    if args.png:
        import df_render as R
        R.menu_png(items, args.png); print("[png] %s" % args.png)
    return 0


def cmd_style(args):
    import df_style as S
    palette = None
    py_files, proj_dir = [], None
    for p in args.paths:
        path = pathlib.Path(p)
        if path.is_dir():
            proj_dir = path
            for f in sorted(path.glob("*.py")):
                if not f.name.startswith("."):
                    py_files.append((f.name, f.read_text(encoding="utf-8", errors="replace")))
        else:
            py_files.append((path.name, path.read_text(encoding="utf-8", errors="replace")))
    # optional palette.json (in a given dir, or alongside a single file)
    for cand in ([proj_dir / "palette.json"] if proj_dir else []) + \
                [pathlib.Path(args.paths[0]).parent / "palette.json"]:
        if cand and cand.exists():
            palette = json.loads(cand.read_text(encoding="utf-8")).get("colors")
            break

    issues, counts = S.lint_project(py_files, palette=palette)
    for i in sorted(issues, key=lambda x: (x.code, x.where)):
        print(f"{i.sev:5} {i.code:<12} {i.where}: {i.msg}")
    print(f"\n{len(py_files)} file(s): {len(issues)} style issue(s)"
          + ("" if palette else "  (no palette.json — add one to enforce a palette)"))
    if counts and args.palette:
        print("\nPalette in use (color: uses):")
        for c, n in sorted(counts.items(), key=lambda kv: -kv[1]):
            print(f"  {c:<10} {n}")
    return 0


def cmd_edit(args):
    import df_editor
    df_editor.main(getattr(args, "file", None))
    return 0


def main():
    ap = argparse.ArgumentParser(prog="dfpy", description="DF VIBE codec CLI")
    ap.add_argument("--actiondump", help="path to actiondump.json")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("decompile")
    sub.add_parser("recompile")
    sub.add_parser("identify")
    cp = sub.add_parser("check"); cp.add_argument("files", nargs="+")
    cp.add_argument("--plot-length", type=int, default=None, dest="plot_length",
                    help="code-line limit (default: the project's .df-vibe.json plotLength, else 300)")
    cp.add_argument("--rank", default=None,
                    help="your DF rank (Noble/Emperor/Mythic/Overlord) - warns on actions above it")
    vp = sub.add_parser("validate"); vp.add_argument("files", nargs="+")
    vp.add_argument("--rank", default=None,
                    help="your DF rank (Noble/Emperor/Mythic/Overlord) - warns on actions above it")
    dp = sub.add_parser("diff", help="compare two pulled projects (e.g. normal vs -chests) - flags truncated lines")
    dp.add_argument("a", help="project A (the reference - a normal /df pull)")
    dp.add_argument("b", help="project B (the one under test - e.g. a -chests pull)")
    sub.add_parser("genschem", help="generate a build-loader project from a mod capture dir (stdin JSON)")
    vp.add_argument("--plot-length", type=int, default=None, dest="plot_length",
                    help="code-line limit (default: the project's .df-vibe.json plotLength, else 300)")
    st_ = sub.add_parser("selftest")
    st_.add_argument("--backups", nargs="?", const="", default=None, metavar="DIR",
                     help="round-trip every scan token in plots/.df-backups (or DIR) instead")

    # --- item / text / preview / menu tooling ---
    mk = sub.add_parser("mkitem", help="generate an item('...') literal from a friendly spec")
    mk.add_argument("material")
    mk.add_argument("--name")
    mk.add_argument("--lore", action="append", help="a lore line (repeatable), MiniMessage")
    mk.add_argument("--count", type=int, default=1)
    mk.add_argument("--tag", action="append", help="DF custom_data tag key=value (repeatable)")
    mk.add_argument("--glow", action="store_true", help="enchantment glint")
    mk.add_argument("--head", help="player-name head")
    mk.add_argument("--head-texture", dest="head_texture", help="base64 texture value head")
    mk.add_argument("--dyed", help="leather/dye color: int, #RRGGBB, or name")
    mk.add_argument("--model", help="custom_model_data (float)")
    mk.add_argument("--unbreakable", action="store_true")
    mk.add_argument("--hide", action="append", help="hide a tooltip component, e.g. enchantments")
    mk.add_argument("--enchant", action="append", help="enchantment name=level (repeatable)")
    mk.add_argument("--stored-enchant", action="append", dest="stored_enchant",
                    help="stored enchantment name=level, for enchanted books (repeatable)")
    mk.add_argument("--attr", action="append",
                    help="attribute type=amount[:operation[:slot]] (repeatable)")
    mk.add_argument("--item-name", dest="item_name", help="item_name component (not custom_name)")
    mk.add_argument("--potion", help="potion id for potion_contents, e.g. strength")
    mk.add_argument("--trim", help="armor trim material:pattern, e.g. gold:sentry")
    mk.add_argument("--rarity", choices=["common", "uncommon", "rare", "epic"])
    mk.add_argument("--max-stack", dest="max_stack", type=int)
    mk.add_argument("--damage", type=int)
    mk.add_argument("--max-damage", dest="max_damage", type=int)
    mk.add_argument("--repair-cost", dest="repair_cost", type=int)
    mk.add_argument("--block-state", dest="block_state", action="append",
                    help="block_state prop=value (repeatable)")
    mk.add_argument("--snbt", action="store_true", help="print raw SNBT only")
    mk.add_argument("--literal", action="store_true", help="print the item('...') literal only")

    tx = sub.add_parser("text", help="preview MiniMessage + emit a comp('...') value")
    tx.add_argument("text")
    tx.add_argument("--txt", action="store_true", help="emit txt(...) instead of comp(...)")
    tx.add_argument("--preview-only", action="store_true", dest="preview_only")

    pv = sub.add_parser("preview", help="render items in .py file(s) as ANSI tooltips")
    pv.add_argument("paths", nargs="*")
    pv.add_argument("--snbt", help="preview a single raw SNBT string instead of files")
    pv.add_argument("--png", help="also write a PNG to this path")

    mn = sub.add_parser("menu", help="preview a menu's chest grid, or --new to scaffold")
    mn.add_argument("path", nargs="?")
    mn.add_argument("--new", action="store_true")
    mn.add_argument("--rows", type=int, default=6)
    mn.add_argument("--title", default="<gradient:#5c9eff:#3d5aff><b>✦ Menu")
    mn.add_argument("--border", default="gray_stained_glass_pane")
    mn.add_argument("--fill", default=None)
    mn.add_argument("--png", help="also write a PNG to this path")

    st = sub.add_parser("style", help="lint items/text against styling.md (italic, legacy codes, palette)")
    st.add_argument("paths", nargs="+")
    st.add_argument("--palette", action="store_true", help="also print the palette-in-use report")

    ed = sub.add_parser("edit", help="launch the DF Item Editor desktop app")
    ed.add_argument("file", nargs="?", help="optional .py line to open on launch")

    args = ap.parse_args()

    if args.actiondump:
        C.set_actiondump_path(args.actiondump)

    handlers = {"decompile": cmd_decompile, "recompile": cmd_recompile,
                "identify": cmd_identify, "check": cmd_check, "validate": cmd_validate,
                "diff": cmd_diff, "genschem": cmd_genschem,
                "selftest": cmd_selftest, "mkitem": cmd_mkitem, "text": cmd_text,
                "preview": cmd_preview, "menu": cmd_menu, "style": cmd_style,
                "edit": cmd_edit}
    try:
        rc = handlers[args.cmd](args)
    except Exception as e:
        json.dump({"error": f"{type(e).__name__}: {e}"}, sys.stdout, ensure_ascii=False)
        sys.exit(2)
    sys.exit(rc or 0)


if __name__ == "__main__":
    main()
