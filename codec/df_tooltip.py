"""df_tooltip.py — build the *in-game* tooltip line list for an item (advanced-tooltip view).

Given an item's SNBT, reproduce the ordered lines Minecraft shows when you hover it — name,
enchantments, lore, attribute groups ("When in Main Hand:" …), Unbreakable, Dyed, and, with
`advanced=True` (F3+H), the durability and item id. Respects tooltip_display.hidden_components
and hide_tooltip. Default attributes/durability for common weapons/tools are best-effort.

  tooltip_lines(snbt, *, advanced=False) -> list[runs]   (each line = list of style runs)
"""
import df_item as I
import df_text as T

DEC = {k: False for k in T.DECOR_KEYS}


def _run(text, color, **decor):
    r = {"text": text, "color": color, **DEC}
    r.update(decor)
    return r


def _line(text, color, **decor):
    return [_run(text, color, **decor)]


# --- name maps -------------------------------------------------------------
ATTR_NAMES = {
    "attack_damage": "Attack Damage", "attack_speed": "Attack Speed", "max_health": "Max Health",
    "movement_speed": "Speed", "armor": "Armor", "armor_toughness": "Armor Toughness",
    "knockback_resistance": "Knockback Resistance", "luck": "Luck", "attack_knockback": "Attack Knockback",
    "max_absorption": "Max Absorption", "block_interaction_range": "Block Interaction Range",
    "entity_interaction_range": "Entity Interaction Range", "movement_efficiency": "Movement Efficiency",
    "mining_efficiency": "Mining Efficiency", "sneaking_speed": "Sneaking Speed", "step_height": "Step Height",
    "jump_strength": "Jump Strength", "gravity": "Gravity", "scale": "Scale",
    "safe_fall_distance": "Safe Fall Distance", "fall_damage_multiplier": "Fall Damage Multiplier",
    "burning_time": "Burning Time", "oxygen_bonus": "Oxygen Bonus",
    "water_movement_efficiency": "Water Movement Efficiency", "max_absorption ": "Max Absorption",
}
SLOT_HDR = {
    "mainhand": "When in Main Hand:", "offhand": "When in Off Hand:", "hand": "When in Hand:",
    "head": "When on Head:", "chest": "When on Chest:", "legs": "When on Legs:",
    "feet": "When on Feet:", "body": "When on Body:", "armor": "When Worn:", "any": None,
}
SLOT_ORDER = ["mainhand", "offhand", "hand", "head", "chest", "legs", "feet", "body", "armor", "any"]
ENCH_NAMES = {
    "sharpness": "Sharpness", "smite": "Smite", "bane_of_arthropods": "Bane of Arthropods",
    "knockback": "Knockback", "fire_aspect": "Fire Aspect", "looting": "Looting", "sweeping_edge": "Sweeping Edge",
    "efficiency": "Efficiency", "silk_touch": "Silk Touch", "unbreaking": "Unbreaking", "fortune": "Fortune",
    "power": "Power", "punch": "Punch", "flame": "Flame", "infinity": "Infinity", "luck_of_the_sea": "Luck of the Sea",
    "lure": "Lure", "loyalty": "Loyalty", "impaling": "Impaling", "riptide": "Riptide", "channeling": "Channeling",
    "multishot": "Multishot", "quick_charge": "Quick Charge", "piercing": "Piercing", "mending": "Mending",
    "vanishing_curse": "Curse of Vanishing", "binding_curse": "Curse of Binding", "protection": "Protection",
    "fire_protection": "Fire Protection", "feather_falling": "Feather Falling", "blast_protection": "Blast Protection",
    "projectile_protection": "Projectile Protection", "respiration": "Respiration", "aqua_affinity": "Aqua Affinity",
    "thorns": "Thorns", "depth_strider": "Depth Strider", "frost_walker": "Frost Walker", "soul_speed": "Soul Speed",
    "swift_sneak": "Swift Sneak", "density": "Density", "breach": "Breach", "wind_burst": "Wind Burst",
}
ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]
CURSES = {"vanishing_curse", "binding_curse"}

# best-effort displayed defaults for common weapons (attack damage, attack speed)
_TIER = lambda m: m.split("_")[0]
SWORD = {"wooden": 4, "golden": 4, "stone": 5, "iron": 6, "diamond": 7, "netherite": 8}
AXE = {"wooden": (7, 0.8), "golden": (7, 1.0), "stone": (9, 0.8), "iron": (9, 0.9),
       "diamond": (9, 1.0), "netherite": (10, 1.0)}
PICK = {"wooden": 2, "golden": 2, "stone": 3, "iron": 4, "diamond": 5, "netherite": 6}
SHOVEL = {"wooden": 2.5, "golden": 2.5, "stone": 3.5, "iron": 4.5, "diamond": 5.5, "netherite": 6.5}
DUR = {"wooden": 59, "golden": 32, "stone": 131, "iron": 250, "diamond": 1561, "netherite": 2031}


def _num(v):
    return str(int(v)) if isinstance(v, (int, float)) and float(v).is_integer() else ("%g" % v)


def _roman(n):
    return ROMAN[n] if 0 <= n < len(ROMAN) else str(n)


def _default_attrs(mat):
    """[(slot, type, amount, base)] displayed attack lines for common weapons, or []."""
    t = _TIER(mat)
    if mat.endswith("_sword") and t in SWORD:
        return [("mainhand", "attack_damage", SWORD[t], True), ("mainhand", "attack_speed", 1.6, True)]
    if mat.endswith("_axe") and t in AXE:
        d, s = AXE[t]
        return [("mainhand", "attack_damage", d, True), ("mainhand", "attack_speed", s, True)]
    if mat.endswith("_pickaxe") and t in PICK:
        return [("mainhand", "attack_damage", PICK[t], True), ("mainhand", "attack_speed", 1.2, True)]
    if mat.endswith("_shovel") and t in SHOVEL:
        return [("mainhand", "attack_damage", SHOVEL[t], True), ("mainhand", "attack_speed", 1.0, True)]
    if mat == "trident":
        return [("mainhand", "attack_damage", 9, True), ("mainhand", "attack_speed", 1.1, True)]
    return []


def _default_durability(mat):
    t = _TIER(mat)
    if t in DUR and any(mat.endswith(s) for s in ("_sword", "_axe", "_pickaxe", "_shovel", "_hoe")):
        return DUR[t]
    return None


def _attr_line(slot, atype, amount, op, base):
    name = ATTR_NAMES.get(atype, atype.replace("_", " ").title())
    if base:                                     # merged base value: dark green, no sign
        return _line(" %s %s" % (_num(amount), name), "dark_green")
    if op in ("add_multiplied_base", "add_multiplied_total"):
        txt, val = "%+g%% %s", amount * 100
        s = ("%+g%% %s" % (val, name))
    else:
        s = "%s%s %s" % ("+" if amount >= 0 else "", _num(amount), name)
    return _line(s, "blue" if amount >= 0 else "red")


def tooltip_lines(snbt, *, advanced=False):
    try:
        d = I.parse_snbt(snbt)
    except Exception:
        return [_line("(unparseable item)", "red")]
    comp = d.get("components", {}) if isinstance(d, dict) else {}
    mat = (d.get("id") or "stone").replace("minecraft:", "") if isinstance(d, dict) else "stone"
    td = comp.get("minecraft:tooltip_display", {})
    if isinstance(td, dict) and td.get("hide_tooltip"):
        return []
    hidden = set(str(h).replace("minecraft:", "") for h in (td.get("hidden_components", []) if isinstance(td, dict) else []))
    shown = lambda key: key not in hidden
    lines = []

    # name
    if "minecraft:custom_name" in comp:
        lines.append(T.component_to_runs(comp["minecraft:custom_name"]) or _line(mat, "white"))
    elif "minecraft:item_name" in comp:
        lines.append(T.component_to_runs(comp["minecraft:item_name"]) or _line(mat, "white"))
    else:
        lines.append(_line(mat.replace("_", " ").title(), "white"))

    # enchantments
    if shown("enchantments"):
        for k, lvl in (comp.get("minecraft:enchantments") or {}).items():
            key = str(k).replace("minecraft:", "")
            nm = ENCH_NAMES.get(key, key.replace("_", " ").title())
            label = nm + ("" if lvl == 1 and key in ("silk_touch", "mending", "channeling", "infinity",
                                                      "flame", "multishot", "aqua_affinity") else " " + _roman(lvl))
            lines.append(_line(label, "light_purple" if key in CURSES else "gray"))

    # lore
    for ln in comp.get("minecraft:lore", []):
        runs = T.component_to_runs(ln)
        lines.append(T.lore_base(runs) if runs else _line("", None))

    # attribute modifiers (explicit, else defaults for weapons)
    if shown("attribute_modifiers"):
        mods = comp.get("minecraft:attribute_modifiers")
        groups = {}
        if isinstance(mods, list) and mods:
            for a in mods:
                if isinstance(a, dict):
                    slot = a.get("slot", "mainhand")
                    groups.setdefault(slot, []).append(
                        (str(a.get("type", "")).replace("minecraft:", ""), a.get("amount", 0),
                         a.get("operation", "add_value"), False))
        else:
            for slot, atype, amt, base in _default_attrs(mat):
                groups.setdefault(slot, []).append((atype, amt, "add_value", base))
        for slot in SLOT_ORDER:
            if slot not in groups:
                continue
            lines.append(_line("", None))
            hdr = SLOT_HDR.get(slot)
            if hdr:
                lines.append(_line(hdr, "gray"))
            for atype, amt, op, base in groups[slot]:
                lines.append(_attr_line(slot, atype, amt, op, base))

    # unbreakable / dyed / trim
    if "minecraft:unbreakable" in comp and shown("unbreakable"):
        lines.append(_line("Unbreakable", "blue"))
    if "minecraft:dyed_color" in comp and shown("dyed_color"):
        lines.append(_line("Dyed", "gray"))
    if "minecraft:trim" in comp and shown("trim"):
        tr = comp["minecraft:trim"]
        lines.append(_line("Armor Trim:", "gray"))
        lines.append(_line(" %s" % str(tr.get("pattern", "")).replace("minecraft:", "").replace("_", " ").title(), "gray"))

    # advanced (F3+H)
    if advanced:
        mx = comp.get("minecraft:max_damage") or _default_durability(mat)
        if mx:
            dmg = comp.get("minecraft:damage", 0) or 0
            lines.append(_line("Durability: %d / %d" % (mx - dmg, mx), "white"))
        lines.append(_line("minecraft:" + mat, "dark_gray"))

    return lines
