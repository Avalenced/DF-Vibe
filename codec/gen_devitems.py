import re, glob, os, io

BASE = r"plots\sputt-time"   # relative to the project root; run this from there

# Targeted file list: registry first (dedup priority), then handlers, then fish.
def g(p): return sorted(glob.glob(os.path.join(BASE, p)))
registry = g("func__LoadVars*.py") + [os.path.join(BASE,f) for f in
            ("func__SetupVars_p7.py","func__SetupVars_p8.py","func__SetupVars_p9.py","func__SetupVars_p10.py")] \
          + g("func__LoadJuggVars*.py")
handlers = g("func__RightClick*.py") + [os.path.join(BASE,f) for f in
            ("event__RightClick.py","event__Consume.py","event__Jump.py","event__Fish.py",
             "func__StaffCheck.py","func__ClickMimicOrb.py","func__HellBeckonRC.py","func__PlayerSubstitute.py",
             "func__HolyWater.py","func__EatCheese.py","func__EasterEggDrop.py","func__MagicBox.py",
             "func__JesterCard.py","func__SoulLinkWarp.py","func__RCJugwand.py","func__LCJugwand.py")]
fishfiles = g("func__LoadFishes*.py")
files = registry + handlers + fishfiles

# name-identified effect items that have no hypercube tag
WHITELIST = {"Extra Nutritious Dirt","Blowpipe","Speed Rush","Smoke Bomb","Vanishing Justu",
 "Player Substitution","Empty Vessel","Absorption Vessel","Rage Eye","Honorable End","Soul Life",
 "Cursed Slab","Tasty Cheese","Reaper Scythe","Necrotic Bone","Skeleton Substitute","Warrior Stance",
 "Rush Talisman","Holy Water","Pogo Stick","Easter Egg"}

ITEM_RE = re.compile(r"item\('((?:[^'\\]|\\.)*)'\)")
TEXT_RE = re.compile(r'text:"((?:[^"\\]|\\.)*)"')

def disp_name(lit):
    i = lit.find('"minecraft:custom_name":')
    if i < 0: return ""
    j = lit.find('{', i)
    if j < 0: return ""
    depth = 0; k = j
    while k < len(lit):
        c = lit[k]
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                break
        k += 1
    cn = lit[j:k+1]
    name = "".join(TEXT_RE.findall(cn)).strip()
    return name

def basekey(name):
    return name.split(' (')[0].strip()

seen = {}
order = []
for fp in files:
    isfish = "LoadFishes" in os.path.basename(fp)
    try:
        txt = io.open(fp, encoding="utf-8").read()
    except Exception:
        continue
    for m in ITEM_RE.finditer(txt):
        lit = m.group(1)
        name = disp_name(lit)
        if not name:
            continue
        has_tag = "hypercube:" in lit
        if not (has_tag or name in WHITELIST or isfish):
            continue
        key = basekey(name)
        if key in seen:           # keep first (registry scanned first => tagged base version wins)
            continue
        seen[key] = lit
        order.append((name, lit))

def safe(s): return s.encode("ascii", "replace").decode()
print("TOTAL ITEMS:", len(order))
for n,_ in order:
    print(" -", safe(n))

# ---- build OpenDevItems.py ----  (achievement-menu layout: top border, 36/page grid 9-44, page heads @46/52)
PANE = '{DF_NBT:4671,count:1,components:{"minecraft:custom_name":{italic:0b,text:""}},id:"minecraft:black_stained_glass_pane"}'
def pane(slot=None):
    return "item('%s'%s)" % (PANE, "" if slot is None else (", slot=%d"%slot))
PREV_HEAD = '{DF_NBT:4671,count:1,components:{"minecraft:custom_model_data":{floats:[0.0f]},"minecraft:custom_name":{extra:[{bold:0b,color:"green",italic:0b,obfuscated:0b,strikethrough:0b,text:"Previous Page",underlined:0b}],text:""},"minecraft:dyed_color":10511680,"minecraft:profile":{id:[I;94183662,367086083,-1829290355,982772859],name:"DF-HEAD",properties:[{name:"textures",value:"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzdhZWU5YTc1YmYwZGY3ODk3MTgzMDE1Y2NhMGIyYTdkNzU1YzYzMzg4ZmYwMTc1MmQ1ZjQ0MTlmYzY0NSJ9fX0="}]},"minecraft:tooltip_display":{hidden_components:["minecraft:enchantments","minecraft:dyed_color"]}},id:"minecraft:player_head"}'
NEXT_HEAD = '{DF_NBT:4671,count:1,components:{"minecraft:custom_model_data":{floats:[0.0f]},"minecraft:custom_name":{extra:[{bold:0b,color:"green",italic:0b,obfuscated:0b,strikethrough:0b,text:"Next Page",underlined:0b}],text:""},"minecraft:dyed_color":10511680,"minecraft:profile":{id:[I;1941883826,-1625537537,-1141685166,534542410],name:"DF-HEAD",properties:[{name:"textures",value:"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjgyYWQxYjljYjRkZDIxMjU5YzBkNzVhYTMxNWZmMzg5YzNjZWY3NTJiZTM5NDkzMzgxNjRiYWM4NGE5NmUifX19"}]},"minecraft:tooltip_display":{hidden_components:["minecraft:enchantments","minecraft:dyed_color"]}},id:"minecraft:player_head"}'

show = "PlayerAction('ShowInv', " + ", ".join([pane()] + [pane(s) for s in range(1,9)]) + ")"
expand = "PlayerAction('ExpandInv', " + ", ".join([pane(s) for s in range(18,27)]) + ")"

lits = ["item('%s')" % lit for _,lit in order]
chunks = [lits[i:i+26] for i in range(0,len(lits),26)]
buildlines = []
for idx,ch in enumerate(chunks):
    op = "CreateList" if idx==0 else "AppendValue"
    buildlines.append("SetVar('%s', var('DevItemList', 'line'), %s)" % (op, ", ".join(ch)))

L = []
L.append("# DF line: func:OpenDevItems    (edit the code below; this comment is ignored)")
L.append("func('OpenDevItems', tags={'Is Hidden': 'True'}, hint=False)")
L.append(show)
L.append(expand)
L += buildlines
L.append("SetVar('ListLength', var('Total', 'line'), var('DevItemList', 'line'))")
L.append("SetVar('+', var('TotalCeil', 'line'), var('Total', 'line'), num('35'))")
L.append("SetVar('/', var('MaxPage', 'line'), var('TotalCeil', 'line'), num('36'), tags={'Division Mode': 'Floor result'})")
L.append("with IfVar('VarIsType', var('%default DevItemPage', 'unsaved'), tags={'Variable Type': 'Number'}, attribute='NOT'):")
L.append("    SetVar('=', var('%default DevItemPage', 'unsaved'), num('1'))")
L.append("SetVar('ClampNumber', var('%default DevItemPage', 'unsaved'), var('%default DevItemPage', 'unsaved'), num('1'), var('MaxPage', 'line'))")
L.append("SetVar('=', var('Page', 'line'), var('%default DevItemPage', 'unsaved'))")
L.append("SetVar('-', var('PageM1', 'line'), var('Page', 'line'), num('1'))")
L.append("SetVar('x', var('StartIdx', 'line'), var('PageM1', 'line'), num('36'))")
L.append("SetVar('+', var('StartIdx', 'line'), var('StartIdx', 'line'), num('1'))")
L.append("SetVar('x', var('EndIdx', 'line'), var('Page', 'line'), num('36'))")
L.append("SetVar('ClampNumber', var('EndIdx', 'line'), var('EndIdx', 'line'), num('1'), var('Total', 'line'))")
L.append("SetVar('=', var('Slot', 'line'), num('8'))")
L.append("with Repeat('Range', var('i', 'line'), var('StartIdx', 'line'), var('EndIdx', 'line')):")
L.append("    SetVar('GetListValue', var('Itm', 'line'), var('DevItemList', 'line'), var('i', 'line'))")
L.append("    SetVar('SetItemTag', var('Itm', 'line'), var('Itm', 'line'), txt('devitem'), txt('1'))")
L.append("    PlayerAction('SetMenuItem', var('Slot', 'line'), var('Itm', 'line'))")
L.append("    SetVar('+=', var('Slot', 'line'))")
L.append("with IfVar('>', var('Page', 'line'), num('1')):")
L.append("    SetVar('=', var('I', 'line'), item('%s'))" % PREV_HEAD)
L.append("    SetVar('SetItemTag', var('I', 'line'), var('I', 'line'), txt('devnav'), txt('itemsprev'))")
L.append("    PlayerAction('SetMenuItem', num('46'), var('I', 'line'))")
L.append("with IfVar('<', var('Page', 'line'), var('MaxPage', 'line')):")
L.append("    SetVar('=', var('I', 'line'), item('%s'))" % NEXT_HEAD)
L.append("    SetVar('SetItemTag', var('I', 'line'), var('I', 'line'), txt('devnav'), txt('itemsnext'))")
L.append("    PlayerAction('SetMenuItem', num('52'), var('I', 'line'))")
L.append("PlayerAction('SetInvName', comp('<gold><bold>Dev Items - Page %var(Page)'))")

out = os.path.join(BASE, "func__OpenDevItems.py")
io.open(out, "w", encoding="utf-8").write("\n".join(L) + "\n")
print("WROTE", out, "with", len(order), "items across", len(chunks), "list-chunks")
