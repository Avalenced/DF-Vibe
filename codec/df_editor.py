#!/usr/bin/env python3
"""df_editor.py — DF Item Editor: a native desktop app to design DiamondFire items.

The tooltip is the editor: you type the name and lore *directly in the in-game tooltip*
(real Minecraft bitmap font, WYSIWYG styling). Components are added on demand — only the
ones that make sense for the item — as clean removable cards. It renders the item's icon
(item / block / player-head face / dyed), emits a paste-ready item('...') literal, and can
open a plot .py file, edit one of its items, and write it back.

Run:  python codec/df_editor.py        (or the "DF Item Editor.bat" launcher)
"""
import os
import sys
import re
import glob

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PLOTS = os.path.join(ROOT, "plots")
sys.path.insert(0, HERE)

import tkinter as tk
from tkinter import ttk, filedialog, colorchooser, font as tkfont

import df_text as T
import df_item as I

try:
    import df_render as R
    import df_icon as ICON
    import df_tipedit as TIP
    import df_tooltip as TT
    from PIL import ImageTk
    HAVE_RENDER = R._HAVE
except Exception as e:                  # pragma: no cover
    HAVE_RENDER = False
    ICON = TIP = TT = None

# --- theme -----------------------------------------------------------------
BG = "#191a1d"
PANEL = "#212327"
CARD = "#2a2c31"
ENTRY = "#16171a"
TIPBG = "#101014"
FG = "#e7e8ea"
MUTED = "#8b9097"
FAINT = "#5c6066"
ACCENT = "#b083f0"
ACCENT2 = "#7c5cff"
BORDER = "#313338"
ERRCOL = "#ff6b6b"
OKCOL = "#5ad17a"

COMMON_MATERIALS = [
    "wooden_sword", "stone_sword", "iron_sword", "golden_sword", "diamond_sword",
    "netherite_sword", "bow", "crossbow", "trident", "mace", "fishing_rod", "stick",
    "flint_and_steel", "shears", "diamond_pickaxe", "netherite_pickaxe", "diamond_axe",
    "netherite_axe", "diamond_hoe", "netherite_hoe", "diamond_shovel", "shield",
    "totem_of_undying", "arrow", "spectral_arrow", "tipped_arrow", "elytra", "turtle_helmet",
    "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots",
    "iron_chestplate", "diamond_chestplate", "netherite_chestplate", "golden_chestplate",
    "coal", "iron_ingot", "gold_ingot", "diamond", "emerald", "netherite_ingot",
    "copper_ingot", "lapis_lazuli", "redstone", "quartz", "amethyst_shard", "nether_star",
    "dragon_egg", "heart_of_the_sea", "nautilus_shell", "echo_shard", "ancient_debris",
    "stone", "cobblestone", "andesite", "dirt", "grass_block", "oak_planks", "oak_log",
    "glass", "glowstone", "sea_lantern", "obsidian", "crying_obsidian", "bedrock", "barrier",
    "beacon", "spawner", "chest", "ender_chest", "barrel", "furnace", "crafting_table",
    "enchanting_table", "anvil", "bookshelf", "tnt", "sponge", "slime_block", "honey_block",
    "note_block", "jukebox", "target", "lodestone", "respawn_anchor", "lectern", "bell",
    "conduit", "end_crystal", "blackstone", "mushroom_stem",
    "player_head", "zombie_head", "skeleton_skull", "creeper_head", "piglin_head",
    "wither_skeleton_skull", "dragon_head",
    "glass_pane", "white_stained_glass_pane", "light_gray_stained_glass_pane",
    "gray_stained_glass_pane", "black_stained_glass_pane", "red_stained_glass_pane",
    "orange_stained_glass_pane", "yellow_stained_glass_pane", "lime_stained_glass_pane",
    "green_stained_glass_pane", "cyan_stained_glass_pane", "light_blue_stained_glass_pane",
    "blue_stained_glass_pane", "purple_stained_glass_pane", "magenta_stained_glass_pane",
    "pink_stained_glass_pane", "brown_stained_glass_pane",
    "apple", "golden_apple", "enchanted_golden_apple", "bread", "cooked_beef",
    "golden_carrot", "cake", "honey_bottle", "potion", "splash_potion", "lingering_potion",
    "book", "writable_book", "written_book", "enchanted_book", "paper", "map", "filled_map",
    "compass", "recovery_compass", "clock", "name_tag", "lead", "saddle", "ender_pearl",
    "ender_eye", "blaze_rod", "blaze_powder", "ghast_tear", "magma_cream", "glowstone_dust",
    "gunpowder", "fire_charge", "firework_rocket", "experience_bottle", "slime_ball", "bone",
    "string", "feather", "leather", "rabbit_foot", "phantom_membrane", "prismarine_shard",
    "shulker_shell", "dragon_breath", "chorus_fruit", "music_disc_pigstep",
    "poppy", "dandelion", "sunflower", "torchflower", "lantern", "soul_lantern", "torch",
]

SYMBOLS = ["❤", "⚔", "⚡", "✦", "✧", "★", "☆", "✪", "◆", "◇", "●", "○", "■", "□", "▪", "▫",
           "➤", "►", "◄", "▶", "◀", "▲", "▼", "»", "«", "›", "‹", "•", "·", "☠", "☃", "❄",
           "⏳", "⌛", "✔", "✖", "✗", "✚", "⛨", "☄", "♦", "♠", "♣", "♥", "♪", "♫", "☀", "☁",
           "→", "←", "↑", "↓", "⇒", "∞", "±", "×", "÷", "≡", "█", "▓", "▒", "░", "▬", "─"]

COMPONENT_META = {
    "custom_data":        ("⛃", "Custom tags"),
    "enchantments":       ("✦", "Enchantments"),
    "attribute_modifiers": ("⚔", "Attributes"),
    "unbreakable":        ("∞", "Unbreakable"),
    "glow":               ("✧", "Glow (glint)"),
    "damage":             ("▤", "Damage"),
    "max_damage":         ("▥", "Max durability"),
    "custom_model_data":  ("◆", "Model data"),
    "dyed_color":         ("■", "Dye color"),
    "profile":            ("☻", "Player head"),
    "potion_contents":    ("✚", "Potion"),
    "block_state":        ("▦", "Block state"),
    "trim":               ("◈", "Armor trim"),
    "stored_enchantments": ("✦", "Stored enchants"),
    "rarity":             ("★", "Rarity"),
    "repair_cost":        ("✦", "Repair cost"),
    "tooltip_display":    ("⊘", "Hide parts"),
    "max_stack_size":     ("≡", "Max stack"),
    "item_name":          ("✎", "Item name"),
    "raw":                ("{ }", "Raw component"),
}
COMPONENT_ORDER = ["custom_data", "enchantments", "attribute_modifiers", "unbreakable",
                   "glow", "damage", "max_damage", "custom_model_data", "dyed_color", "profile",
                   "potion_contents", "block_state", "trim", "stored_enchantments", "rarity",
                   "repair_cost", "tooltip_display", "max_stack_size", "item_name", "raw"]
HIDE_PARTS = ["enchantments", "attribute_modifiers", "unbreakable", "dyed_color", "trim"]
BLOCK_HINTS = ("stone", "glass", "planks", "log", "wool", "concrete", "terracotta", "ore",
               "block", "bricks", "_pane", "andesite", "diorite", "granite", "deepslate",
               "obsidian", "dirt", "sand", "wood", "leaves", "_slab", "_stairs", "_wall",
               "blackstone", "basalt", "netherrack", "prismarine", "copper", "quartz")


def _lighten(hexc, amt=18):
    h = hexc.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    f = lambda v: max(0, min(255, v + amt))
    return "#%02x%02x%02x" % (f(r), f(g), f(b))


def parse_tags(s):
    out = {}
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        k, v = (part.split("=", 1) if "=" in part else (part, "1"))
        k, v = k.strip(), v.strip()
        if not k:
            continue
        try:
            fv = float(v)
            out[k] = int(fv) if fv == int(fv) else fv
        except ValueError:
            out[k] = v
    return out


def parse_kv_str(s):
    """'facing=north, lit=true' -> {'facing':'north','lit':'true'} (values kept as strings)."""
    out = {}
    for part in s.split(","):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        if k.strip():
            out[k.strip()] = v.strip()
    return out


def parse_ench(s):
    out = {}
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        k, v = (part.split("=", 1) if "=" in part else (part, "1"))
        try:
            out[k.strip()] = int(float(v))
        except ValueError:
            continue
    return out


def parse_attrs(s):
    out = []
    for part in s.split(","):
        part = part.strip()
        if not part or "=" not in part:
            continue
        k, v = part.split("=", 1)
        k, v = k.strip(), v.strip()
        slot = "mainhand"
        if "@" in v:
            v, slot = (x.strip() for x in v.split("@", 1))
        try:
            out.append({"type": k, "amount": float(v), "slot": slot})
        except ValueError:
            continue
    return out


def load_materials():
    base = set(COMMON_MATERIALS)
    try:
        for fp in glob.glob(os.path.join(PLOTS, "**", "*.py"), recursive=True):
            try:
                txt = open(fp, encoding="utf-8").read()
            except Exception:
                continue
            for mm in re.findall(r'id:"minecraft:([a-z0-9_]+)"', txt):
                base.add(mm)
    except Exception:
        pass
    return sorted(base)


def _fmt_num(v):
    if isinstance(v, float) and v.is_integer():
        return str(int(v))
    return str(v)


def _pretty_snbt(s, step="  "):
    """Indent an SNBT string for the raw view (newline after { [ , and before } ])."""
    res, depth, instr, q, i, n = [], 0, False, "", 0, len(s)
    nl = lambda: res.append("\n" + step * depth)
    while i < n:
        c = s[i]
        if instr:
            res.append(c)
            if c == "\\" and i + 1 < n:
                res.append(s[i + 1])
                i += 2
                continue
            if c == q:
                instr = False
            i += 1
            continue
        if c in "\"'":
            instr, q = True, c
            res.append(c)
        elif c in "{[":
            depth += 1
            res.append(c)
            nl()
        elif c in "}]":
            depth -= 1
            nl()
            res.append(c)
        elif c == ",":
            res.append(c)
            nl()
        else:
            res.append(c)
        i += 1
    return "".join(res)


# ===========================================================================
class DFEditor(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("DF Item Editor")
        self.configure(bg=BG)
        self.geometry("1240x800")
        self.minsize(1040, 660)

        self._after = None
        self._icon_photo = None
        self.active_components = []        # [{key, frame, value()}]
        self._addpop = None
        self.loaded_file = self.loaded_lit = self.loaded_span = None
        self.materials = load_materials()

        self._fonts()
        self._ttk_style()
        self._build()
        self._new_item(sample=True)
        self.after(60, self.render_now)

    def _fonts(self):
        self.ui = tkfont.Font(family="Segoe UI", size=10)
        self.uib = tkfont.Font(family="Segoe UI Semibold", size=10)
        self.mono = tkfont.Font(family="Consolas", size=10)
        self.sym = tkfont.Font(family="Segoe UI Symbol", size=13)
        self.title_f = tkfont.Font(family="Segoe UI Semibold", size=14)
        self.small = tkfont.Font(family="Segoe UI", size=8)

    def _ttk_style(self):
        st = ttk.Style(self)
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        st.configure("Dark.TCombobox", fieldbackground=ENTRY, background=CARD, foreground=FG,
                     arrowcolor=FG, bordercolor=BORDER, lightcolor=BORDER, darkcolor=BORDER,
                     insertcolor=FG, selectbackground=ACCENT, selectforeground="#fff", padding=5)
        st.map("Dark.TCombobox", fieldbackground=[("readonly", ENTRY)], foreground=[("readonly", FG)])
        for opt, val in (("background", ENTRY), ("foreground", FG),
                         ("selectBackground", ACCENT), ("selectForeground", "#ffffff")):
            self.option_add("*TCombobox*Listbox." + opt, val)
        self.option_add("*TCombobox*Listbox.font", self.mono)
        st.configure("Dark.Vertical.TScrollbar", troughcolor=PANEL, background=CARD,
                     bordercolor=PANEL, arrowcolor=MUTED, relief="flat")

    # -- widget helpers -----------------------------------------------------
    def _btn(self, parent, text, cmd, kind="default", **kw):
        bg, fg = {"default": (CARD, FG), "accent": (ACCENT, "#160d22"),
                  "ghost": (PANEL, MUTED)}.get(kind, (CARD, FG))
        b = tk.Button(parent, text=text, command=cmd, bg=bg, fg=fg, bd=0, relief="flat",
                      activebackground=_lighten(bg), activeforeground=fg, font=self.ui,
                      padx=11, pady=5, cursor="hand2", **kw)
        b.bind("<Enter>", lambda e: b.config(bg=_lighten(bg)))
        b.bind("<Leave>", lambda e: b.config(bg=bg))
        return b

    def _entry(self, parent, var, width=None):
        e = tk.Entry(parent, textvariable=var, bg=ENTRY, fg=FG, insertbackground=ACCENT,
                     relief="flat", bd=0, font=self.mono, highlightthickness=1,
                     highlightbackground=BORDER, highlightcolor=ACCENT,
                     **({"width": width} if width else {}))
        return e

    def _placeheld(self, parent, var, hint, width=None):
        wrap = tk.Frame(parent, bg=ENTRY)
        e = self._entry(wrap, var, width)
        e.pack(fill="x", ipady=3)
        ph = tk.Label(wrap, text=hint, bg=ENTRY, fg=FAINT, font=self.mono)

        def upd(*_):
            if var.get() or e is self.focus_get():
                ph.place_forget()
            else:
                ph.place(x=6, y=4)
        ph.bind("<Button-1>", lambda ev: e.focus_set())
        e.bind("<FocusIn>", lambda ev: ph.place_forget())
        e.bind("<FocusOut>", lambda ev: upd())
        var.trace_add("write", lambda *a: (upd(), self.schedule_render()))
        upd()
        return wrap

    # -- layout -------------------------------------------------------------
    def _build(self):
        self.columnconfigure(0, weight=1)
        self.rowconfigure(1, weight=1)

        bar = tk.Frame(self, bg=BG)
        bar.grid(row=0, column=0, sticky="ew", padx=12, pady=(9, 5))
        tk.Label(bar, text="◆ DF Item Editor", bg=BG, fg=ACCENT, font=self.title_f).pack(side="left")
        for txt, cmd, kind in (("Copy", self.copy_literal, "accent"), ("Save", self.save_to_file, "default"),
                               ("Check", self.check_file, "ghost"), ("PNG", self.export_png, "ghost"),
                               ("Open", self.open_file, "default"), ("New", lambda: self._new_item(False), "ghost")):
            self._btn(bar, txt, cmd, kind).pack(side="right", padx=3)

        body = tk.Frame(self, bg=BG)
        body.grid(row=1, column=0, sticky="nsew", padx=10, pady=4)
        body.rowconfigure(0, weight=1)
        body.columnconfigure(0, weight=3, uniform="x")
        body.columnconfigure(1, weight=2, uniform="x")
        left = tk.Frame(body, bg=PANEL)
        right = tk.Frame(body, bg=PANEL)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 5))
        right.grid(row=0, column=1, sticky="nsew", padx=(5, 0))
        self._build_left(left)
        self._build_right(right)

        self.status = tk.Label(self, text="", bg=BG, fg=MUTED, font=self.ui, anchor="w", padx=14)
        self.status.grid(row=2, column=0, sticky="ew", pady=(0, 4))

    def _build_left(self, left):
        left.columnconfigure(0, weight=1)
        left.rowconfigure(6, weight=1)       # spacer below the literal absorbs extra height

        # item row: icon + material + count
        head = tk.Frame(left, bg=PANEL)
        head.grid(row=0, column=0, sticky="ew", padx=14, pady=(14, 6))
        slot = tk.Frame(head, bg=CARD, width=52, height=52, highlightthickness=1, highlightbackground=BORDER)
        slot.pack(side="left")
        slot.pack_propagate(False)
        self.icon_label = tk.Label(slot, bg=CARD)
        self.icon_label.place(relx=0.5, rely=0.5, anchor="center")
        self.mat_var = tk.StringVar()
        cb = ttk.Combobox(head, textvariable=self.mat_var, values=self.materials,
                          style="Dark.TCombobox", font=self.mono)
        cb.pack(side="left", fill="x", expand=True, padx=(10, 8))
        self.count_var = tk.StringVar(value="1")
        tk.Spinbox(head, from_=1, to=99, textvariable=self.count_var, width=3, bg=ENTRY, fg=FG,
                   buttonbackground=CARD, relief="flat", bd=0, font=self.mono, insertbackground=ACCENT,
                   highlightthickness=1, highlightbackground=BORDER).pack(side="left")
        self.mat_var.trace_add("write", self.schedule_render)
        self.count_var.trace_add("write", self.schedule_render)

        hintrow = tk.Frame(left, bg=PANEL)
        hintrow.grid(row=1, column=0, sticky="ew", padx=14)
        tk.Label(hintrow, text="Type the name and lore right in the tooltip — Enter adds a line.",
                 bg=PANEL, fg=FAINT, font=self.small, anchor="w").pack(side="left")
        self.adv_var = tk.BooleanVar(value=False)
        tk.Checkbutton(hintrow, text="Advanced tooltip (F3+H)", variable=self.adv_var,
                       command=self._toggle_advanced, bg=PANEL, fg=MUTED, selectcolor=ENTRY,
                       activebackground=PANEL, activeforeground=FG, font=self.small, bd=0,
                       highlightthickness=0, cursor="hand2").pack(side="right")

        # the tooltip area: editable (tip) OR read-only advanced render
        self.holder = tk.Frame(left, bg=PANEL)
        self.holder.grid(row=2, column=0, sticky="nsew", padx=14, pady=6)
        if TIP is not None:
            self.tip = TIP.TooltipEditor(self.holder, on_change=self.schedule_render, bg=PANEL)
            self.tip.pack(anchor="nw")
        else:
            self.tip = None
            tk.Label(self.holder, text="(install Pillow to edit)", bg=PANEL, fg=MUTED).pack()
        self.adv_label = tk.Label(self.holder, bg=PANEL, anchor="nw")   # shown in advanced mode

        self.toolbar = self._build_toolbar(left)
        self.toolbar.grid(row=3, column=0, sticky="ew", padx=14, pady=(2, 8))

        lh = tk.Frame(left, bg=PANEL)
        lh.grid(row=4, column=0, sticky="ew", padx=14, pady=(0, 2))
        self.view_mode = "literal"
        self._seg_lit = self._btn(lh, "item('…')", lambda: self._set_view("literal"), "accent")
        self._seg_raw = self._btn(lh, "raw", lambda: self._set_view("raw"), "ghost")
        self._seg_lit.pack(side="left")
        self._seg_raw.pack(side="left", padx=(4, 0))
        self._btn(lh, "copy", self.copy_literal, "ghost").pack(side="right")
        self.literal_text = tk.Text(left, height=7, bg=ENTRY, fg=MUTED, relief="flat", bd=0,
                                    font=self.mono, wrap="word", highlightthickness=1,
                                    highlightbackground=BORDER, padx=8, pady=6)
        self.literal_text.grid(row=5, column=0, sticky="nsew", padx=14, pady=(0, 12))
        self.literal_text.configure(state="disabled")
        left.rowconfigure(5, weight=1)
        left.rowconfigure(6, weight=0)

    def _build_toolbar(self, parent):
        bar = tk.Frame(parent, bg=CARD)
        row = tk.Frame(bar, bg=CARD)
        row.pack(fill="x", padx=7, pady=6)
        for name, rgb in T.NAMED.items():
            hexc = "#%02x%02x%02x" % rgb
            tk.Button(row, bg=hexc, width=2, height=1, relief="flat", bd=0, activebackground=hexc,
                      cursor="hand2", command=lambda n=name: self._style(color=n)).pack(side="left", padx=1)
        self._btn(row, "#", self._pick_hex, "ghost").pack(side="left", padx=(7, 1))
        self._btn(row, "grad", self._gradient, "ghost").pack(side="left", padx=1)
        row2 = tk.Frame(bar, bg=CARD)
        row2.pack(fill="x", padx=7, pady=(0, 6))
        for lbl, k in (("B", "bold"), ("I", "italic"), ("U", "underlined"), ("S", "strikethrough")):
            self._btn(row2, lbl, lambda k=k: self._style(**{k: "toggle"})).pack(side="left", padx=2)
        self._btn(row2, "clear", self._clear_style, "ghost").pack(side="left", padx=(7, 2))
        self._btn(row2, "✦ symbols", self._symbols, "ghost").pack(side="left", padx=6)
        return bar

    def _build_right(self, right):
        right.columnconfigure(0, weight=1)
        right.rowconfigure(2, weight=1)
        hdr = tk.Frame(right, bg=PANEL)
        hdr.grid(row=0, column=0, sticky="ew", padx=12, pady=(14, 2))
        tk.Label(hdr, text="COMPONENTS", bg=PANEL, fg=ACCENT, font=self.uib).pack(side="left")
        self.add_btn = self._btn(hdr, "+ Add", self._toggle_add_menu, "accent")
        self.add_btn.pack(side="right")
        self.defaults_label = tk.Label(right, text="", bg=PANEL, fg=FAINT, font=self.small, anchor="w")
        self.defaults_label.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 2))

        wrap = tk.Frame(right, bg=PANEL)
        wrap.grid(row=2, column=0, sticky="nsew", padx=8, pady=2)
        canvas = tk.Canvas(wrap, bg=PANEL, highlightthickness=0)
        vsb = ttk.Scrollbar(wrap, orient="vertical", command=canvas.yview, style="Dark.Vertical.TScrollbar")
        self.comp_holder = tk.Frame(canvas, bg=PANEL)
        self.comp_holder.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        win = canvas.create_window((0, 0), window=self.comp_holder, anchor="nw")
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=vsb.set)
        canvas.pack(side="left", fill="both", expand=True)
        vsb.pack(side="right", fill="y")
        canvas.bind_all("<MouseWheel>", lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"))
        self.empty_hint = tk.Label(self.comp_holder, text="No components.\nClick  + Add  to attach one.",
                                   bg=PANEL, fg=FAINT, font=self.ui, justify="left")

    # -- styling toolbar -> tooltip ----------------------------------------
    def _style(self, **changes):
        if self.tip is not None:
            self.tip.apply_style(**changes)
            self.tip.focus_editor()

    def _clear_style(self):
        self._style(color=None, bold=False, italic=False, underlined=False, strikethrough=False)

    def _pick_hex(self):
        c = colorchooser.askcolor(title="Pick a color")
        if c and c[1]:
            self._style(color=c[1].upper())

    def _symbols(self):
        top = tk.Toplevel(self)
        top.title("Symbols")
        top.configure(bg=PANEL)
        top.transient(self)
        grid = tk.Frame(top, bg=PANEL)
        grid.pack(padx=10, pady=10)
        for i, sym in enumerate(SYMBOLS):
            tk.Button(grid, text=sym, font=self.sym, width=3, bg=CARD, fg=FG, relief="flat", bd=0,
                      activebackground=ACCENT, cursor="hand2",
                      command=lambda s=sym: (self.tip.insert_text(s), self.tip.focus_editor())
                      ).grid(row=i // 12, column=i % 12, padx=2, pady=2)

    def _gradient(self):
        top = tk.Toplevel(self)
        top.title("Gradient")
        top.configure(bg=PANEL)
        top.transient(self)
        top.grab_set()
        top.resizable(False, False)
        st = {"a": "#FF5E62", "b": "#FF9966"}
        tk.Label(top, text="Gradient across the selection", bg=PANEL, fg=MUTED,
                 font=self.ui).pack(padx=14, pady=(12, 6))
        canvas = tk.Canvas(top, width=300, height=22, highlightthickness=1, highlightbackground=BORDER, bd=0)

        def redraw():
            canvas.delete("all")
            ra, rb = T.resolve_rgb(st["a"]), T.resolve_rgb(st["b"])
            for x in range(300):
                t = x / 299
                canvas.create_line(x, 0, x, 22, fill="#%02x%02x%02x" %
                                   tuple(round(ra[k] + (rb[k] - ra[k]) * t) for k in range(3)))

        def pick(w, b):
            c = colorchooser.askcolor(color=st[w], title="Gradient " + w)
            if c and c[1]:
                st[w] = c[1].upper()
                b.config(bg=st[w])
                redraw()
        rowf = tk.Frame(top, bg=PANEL)
        rowf.pack(padx=14, pady=4)
        ba = tk.Button(rowf, text="Start", bg=st["a"], fg="#000", width=8, relief="flat", bd=0, cursor="hand2")
        bb = tk.Button(rowf, text="End", bg=st["b"], fg="#000", width=8, relief="flat", bd=0, cursor="hand2")
        ba.config(command=lambda: pick("a", ba))
        bb.config(command=lambda: pick("b", bb))
        ba.pack(side="left", padx=6)
        bb.pack(side="left", padx=6)
        canvas.pack(padx=14, pady=6)
        act = tk.Frame(top, bg=PANEL)
        act.pack(pady=(6, 14))

        def go():
            self.tip.apply_gradient([st["a"], st["b"]])
            self.tip.focus_editor()
            top.destroy()
        self._btn(act, "Apply", go, "accent").pack(side="left", padx=6)
        self._btn(act, "Cancel", top.destroy, "ghost").pack(side="left", padx=6)
        redraw()

    # -- components ---------------------------------------------------------
    def _suggested_for(self, mat):
        m = mat
        has = lambda *s: any(x in m for x in s)
        out = []
        if has("sword", "axe", "pickaxe", "shovel", "hoe", "bow", "crossbow", "trident",
               "mace", "shears", "fishing_rod", "stick", "flint", "_spade"):
            out = ["enchantments", "attribute_modifiers", "damage", "unbreakable"]
        elif has("helmet", "chestplate", "leggings", "boots", "elytra", "turtle"):
            out = ["enchantments", "attribute_modifiers", "unbreakable", "trim"]
            if "leather" in m:
                out.append("dyed_color")
        elif "head" in m or "skull" in m:
            out = ["profile"]
        elif has("potion", "tipped_arrow"):
            out = ["potion_contents"]
        elif "enchanted_book" in m or has("book"):
            out = ["stored_enchantments", "enchantments"]
        elif any(h in m for h in BLOCK_HINTS):
            out = ["block_state"]
        base = ["custom_data", "glow", "custom_model_data", "rarity", "tooltip_display", "raw"]
        seen, res = set(), []
        for k in out + base:
            if k not in seen:
                seen.add(k)
                res.append(k)
        return res

    def _toggle_add_menu(self):
        if self._addpop is not None:
            self._close_add_menu()
            return
        pop = tk.Toplevel(self)
        pop.overrideredirect(True)
        pop.configure(bg=BORDER)
        inner = tk.Frame(pop, bg=CARD)
        inner.pack(padx=1, pady=1)
        active = {r["key"] for r in self.active_components}
        suggested = [k for k in self._suggested_for(self.mat_var.get()) if k not in active]
        others = [k for k in COMPONENT_ORDER if k not in active and k not in suggested]

        def row(key, dim=False):
            icon, label = COMPONENT_META[key]
            b = tk.Button(inner, text="  %s   %s" % (icon, label), anchor="w", width=20, bd=0,
                          relief="flat", bg=CARD, fg=(MUTED if dim else FG), font=self.ui,
                          activebackground=ACCENT, activeforeground="#fff", cursor="hand2",
                          command=lambda k=key: (self._close_add_menu(), self._add_component(k)))
            b.pack(fill="x")
            b.bind("<Enter>", lambda e: b.config(bg=_lighten(CARD)))
            b.bind("<Leave>", lambda e: b.config(bg=CARD))
        if suggested:
            tk.Label(inner, text="  SUGGESTED", bg=CARD, fg=ACCENT, font=self.small, anchor="w").pack(fill="x", pady=(4, 0))
            for k in suggested:
                row(k)
            tk.Frame(inner, bg=BORDER, height=1).pack(fill="x", pady=3)
        for k in others:
            row(k, dim=True)
        if not suggested and not others:
            tk.Label(inner, text="  all components added", bg=CARD, fg=MUTED, font=self.ui).pack(padx=6, pady=6)
        self.add_btn.update_idletasks()
        x = self.add_btn.winfo_rootx()
        y = self.add_btn.winfo_rooty() + self.add_btn.winfo_height() + 3
        pop.geometry("+%d+%d" % (x, y))
        pop.bind("<FocusOut>", lambda e: self._close_add_menu())
        pop.bind("<Escape>", lambda e: self._close_add_menu())
        self._addpop = pop
        pop.focus_set()

    def _close_add_menu(self):
        if self._addpop is not None:
            try:
                self._addpop.destroy()
            except tk.TclError:
                pass
            self._addpop = None

    def _add_component(self, key, initial=None, render=True):
        self.empty_hint.pack_forget()
        icon, label = COMPONENT_META[key]
        card = tk.Frame(self.comp_holder, bg=CARD, highlightthickness=1, highlightbackground=BORDER)
        card.pack(fill="x", pady=4, padx=2)
        hd = tk.Frame(card, bg=CARD)
        hd.pack(fill="x", padx=8, pady=(6, 2))
        tk.Label(hd, text="%s  %s" % (icon, label), bg=CARD, fg=FG, font=self.uib).pack(side="left")
        rec = {"key": key, "frame": card}
        tk.Button(hd, text="✕", bg=CARD, fg=MUTED, bd=0, relief="flat", font=self.ui, cursor="hand2",
                  activebackground=CARD, activeforeground=ERRCOL,
                  command=lambda: self._remove_component(rec)).pack(side="right")
        body = tk.Frame(card, bg=CARD)
        body.pack(fill="x", padx=8, pady=(0, 8))
        rec["value"] = self._component_body(key, body, initial)
        self.active_components.append(rec)
        if render:
            self.schedule_render()

    def _remove_component(self, rec):
        if rec in self.active_components:
            self.active_components.remove(rec)
        rec["frame"].destroy()
        if not self.active_components:
            self.empty_hint.pack(anchor="w", padx=10, pady=20)
        self.schedule_render()

    def _component_body(self, key, body, initial):
        """Build a component card's body widgets; return value() -> build_item kwargs dict."""
        if key in ("custom_data", "enchantments", "attribute_modifiers", "stored_enchantments", "block_state"):
            hint = {"custom_data": "menu=1, ability=soul_reap",
                    "enchantments": "sharpness=5, unbreaking=3",
                    "stored_enchantments": "sharpness=5, mending=1",
                    "attribute_modifiers": "attack_damage=7, attack_speed=-2.4",
                    "block_state": "facing=north, lit=true"}[key]
            var = tk.StringVar(value=initial or "")
            self._placeheld(body, var, hint).pack(fill="x")
            if key == "custom_data":
                return lambda: ({"tags": parse_tags(var.get())} if parse_tags(var.get()) else {})
            if key == "enchantments":
                return lambda: ({"enchantments": parse_ench(var.get())} if parse_ench(var.get()) else {})
            if key == "stored_enchantments":
                return lambda: ({"stored_enchantments": parse_ench(var.get())} if parse_ench(var.get()) else {})
            if key == "block_state":
                return lambda: ({"block_state": parse_kv_str(var.get())} if parse_kv_str(var.get()) else {})
            return lambda: ({"attributes": parse_attrs(var.get())} if parse_attrs(var.get()) else {})

        if key in ("damage", "max_damage", "repair_cost", "custom_model_data", "max_stack_size",
                   "potion_contents", "item_name", "rarity"):
            hint = {"damage": "durability used (int)", "max_damage": "total durability (int)",
                    "repair_cost": "anvil XP cost (int)", "custom_model_data": "number",
                    "max_stack_size": "1–99", "potion_contents": "strength, healing, …",
                    "item_name": "default name (MiniMessage)",
                    "rarity": "common · uncommon · rare · epic"}[key]
            var = tk.StringVar(value=initial or "")
            self._placeheld(body, var, hint).pack(fill="x")

            def val():
                s = var.get().strip()
                if not s:
                    return {}
                intkeys = {"damage": "damage", "max_damage": "max_damage",
                           "repair_cost": "repair_cost", "max_stack_size": "max_stack"}
                if key in intkeys:
                    try:
                        return {intkeys[key]: int(float(s))}
                    except ValueError:
                        return {}
                if key == "custom_model_data":
                    try:
                        return {"model": float(s)}
                    except ValueError:
                        return {}
                if key == "potion_contents":
                    return {"potion": s}
                if key == "rarity":
                    return {"rarity": s.lower()}
                return {"item_name": s}
            return val

        if key == "trim":
            mv = tk.StringVar(value=(initial or ("", ""))[0])
            pv = tk.StringVar(value=(initial or ("", ""))[1])
            self._placeheld(body, mv, "material (e.g. netherite)").pack(fill="x", pady=(0, 4))
            self._placeheld(body, pv, "pattern (e.g. sentry)").pack(fill="x")
            return lambda: ({"trim": (mv.get().strip(), pv.get().strip())}
                            if (mv.get().strip() and pv.get().strip()) else {})

        if key == "raw":
            kv = tk.StringVar(value=(initial or ("", ""))[0])
            sv = tk.StringVar(value=(initial or ("", ""))[1])
            self._placeheld(body, kv, "component id, e.g. minecraft:food").pack(fill="x", pady=(0, 4))
            self._placeheld(body, sv, "SNBT value, e.g. {nutrition:4,saturation:0.3f}").pack(fill="x")
            return lambda: ({"extra": {kv.get().strip(): T.Raw(sv.get().strip())}}
                            if (kv.get().strip() and sv.get().strip()) else {})

        if key == "profile":
            ov = tk.StringVar(value=(initial or ("", ""))[0])
            tv = tk.StringVar(value=(initial or ("", ""))[1])
            self._placeheld(body, ov, "owner name").pack(fill="x", pady=(0, 4))
            self._placeheld(body, tv, "base64 texture value").pack(fill="x")
            return lambda: ({"head": ov.get().strip() or None,
                             "head_texture": tv.get().strip() or None}
                            if (ov.get().strip() or tv.get().strip()) else {})

        if key == "dyed_color":
            state = {"hex": initial}
            rowf = tk.Frame(body, bg=CARD)
            rowf.pack(fill="x")
            sw = tk.Label(rowf, width=3, bg=(initial or CARD), highlightthickness=1, highlightbackground=BORDER)
            sw.pack(side="left")

            def pick():
                c = colorchooser.askcolor(color=state["hex"] or "#a06540", title="Dye color")
                if c and c[1]:
                    state["hex"] = c[1].upper()
                    sw.config(bg=state["hex"])
                    self.schedule_render()
            self._btn(rowf, "pick…", pick, "ghost").pack(side="left", padx=6)
            return lambda: ({"dyed": state["hex"]} if state["hex"] else {})

        if key == "tooltip_display":
            vars_ = {p: tk.BooleanVar(value=(p in (initial or []))) for p in HIDE_PARTS}
            grid = tk.Frame(body, bg=CARD)
            grid.pack(fill="x")
            for i, p in enumerate(HIDE_PARTS):
                tk.Checkbutton(grid, text=p.replace("_", " "), variable=vars_[p], command=self.schedule_render,
                               bg=CARD, fg=FG, selectcolor=ENTRY, activebackground=CARD, activeforeground=FG,
                               font=self.small, bd=0, highlightthickness=0).grid(row=i // 2, column=i % 2, sticky="w")
            return lambda: ({"hide": [p for p in HIDE_PARTS if vars_[p].get()]}
                            if any(v.get() for v in vars_.values()) else {})

        if key in ("glow", "unbreakable"):
            note = {"glow": "Shows the enchanted-item glint.",
                    "unbreakable": "Never loses durability."}[key]
            tk.Label(body, text=note, bg=CARD, fg=MUTED, font=self.small, anchor="w").pack(fill="x")
            return (lambda: {"glow": True}) if key == "glow" else (lambda: {"unbreakable": True})

        return lambda: {}

    def _load_component(self, key, comp):
        """Initial value for `key` if present in an item's components dict, else None."""
        m = lambda k: comp.get("minecraft:" + k)
        if key == "custom_data":
            cd = m("custom_data")
            if isinstance(cd, dict):
                pb = cd.get("PublicBukkitValues", {})
                if pb:
                    return ", ".join("%s=%s" % (k.replace("hypercube:", ""), _fmt_num(v)) for k, v in pb.items())
            return None
        if key == "enchantments":
            e = m("enchantments")
            return ", ".join("%s=%s" % (k.replace("minecraft:", ""), v) for k, v in e.items()) if isinstance(e, dict) and e else None
        if key == "attribute_modifiers":
            a = m("attribute_modifiers")
            if isinstance(a, list) and a:
                parts = []
                for x in a:
                    if isinstance(x, dict):
                        seg = "%s=%s" % (str(x.get("type", "")).replace("minecraft:", ""), _fmt_num(x.get("amount", 0)))
                        if x.get("slot", "mainhand") != "mainhand":
                            seg += "@" + str(x.get("slot"))
                        parts.append(seg)
                return ", ".join(parts)
            return None
        if key == "stored_enchantments":
            e = m("stored_enchantments")
            return ", ".join("%s=%s" % (k.replace("minecraft:", ""), v) for k, v in e.items()) if isinstance(e, dict) and e else None
        if key == "block_state":
            b = m("block_state")
            return ", ".join("%s=%s" % (k, v) for k, v in b.items()) if isinstance(b, dict) and b else None
        if key == "trim":
            t = m("trim")
            if isinstance(t, dict):
                return (str(t.get("material", "")).replace("minecraft:", ""),
                        str(t.get("pattern", "")).replace("minecraft:", ""))
            return None
        if key == "rarity":
            v = m("rarity")
            return str(v) if isinstance(v, str) else None
        if key in ("damage", "max_damage", "repair_cost"):
            v = m(key)
            return str(v) if isinstance(v, int) else None
        if key == "custom_model_data":
            c = m("custom_model_data")
            f = c.get("floats") if isinstance(c, dict) else None
            return str(f[0]) if f else None
        if key == "max_stack_size":
            v = m("max_stack_size")
            return str(v) if isinstance(v, int) else None
        if key == "potion_contents":
            p = m("potion_contents")
            return str(p.get("potion", "")).replace("minecraft:", "") if isinstance(p, dict) else None
        if key == "item_name":
            v = m("item_name")
            return T.runs_to_mm(T.component_to_runs(v)) if v else None
        if key == "profile":
            p = m("profile")
            if isinstance(p, dict):
                tex = ICON._profile_value(p) if ICON else None
                return (p.get("name", "") or "", tex or "")
            return None
        if key == "dyed_color":
            v = m("dyed_color")
            if isinstance(v, int):
                return "#%06X" % (v & 0xFFFFFF)
            return None
        if key == "tooltip_display":
            td = m("tooltip_display")
            if isinstance(td, dict):
                hid = [str(h).replace("minecraft:", "") for h in td.get("hidden_components", [])]
                return [h for h in hid if h in HIDE_PARTS] or None
            return None
        if key == "glow":
            return "" if "minecraft:enchantment_glint_override" in comp else None
        if key == "unbreakable":
            return "" if "minecraft:unbreakable" in comp else None
        return None

    # -- build / render -----------------------------------------------------
    def current_snbt(self):
        mat = (self.mat_var.get() or "stone").strip() or "stone"
        try:
            count = max(1, int(float(self.count_var.get() or 1)))
        except ValueError:
            count = 1
        name, lore = None, None
        if self.tip is not None:
            lines = self.tip.get_lines()
            if lines:
                name = T.runs_to_mm(lines[0]) if lines[0] else None
                lore = [T.runs_to_mm(r) for r in lines[1:]]
                while lore and not lore[-1].strip():
                    lore.pop()
                lore = lore or None
        kw, extra = {}, {}
        for rec in self.active_components:
            try:
                v = rec["value"]() or {}
            except Exception:
                continue
            if "extra" in v:
                extra.update(v.pop("extra"))
            kw.update(v)
        if extra:
            kw["extra"] = extra
        return I.build_item(mat, name=name, lore=lore, count=count, **kw)

    def schedule_render(self, *_):
        if self._after is not None:
            try:
                self.after_cancel(self._after)
            except Exception:
                pass
        self._after = self.after(110, self.render_now)

    def render_now(self):
        self._after = None
        try:
            snbt = self.current_snbt()
        except Exception as e:
            self.set_status("Build error: %s" % e, error=True)
            return
        self._lit_str = "item('%s')" % snbt.replace("\\", "\\\\").replace("'", "\\'")
        self._raw_str = self._build_raw(snbt)
        self._show_view()
        if ICON is not None:
            try:
                self._icon_photo = ImageTk.PhotoImage(ICON.icon_for_snbt(snbt, px=44))
                self.icon_label.config(image=self._icon_photo)
            except Exception:
                self.icon_label.config(image="")
        self._show_defaults(snbt)
        # tooltip area: editable vs advanced read-only render
        if self.adv_var.get() and TT is not None and HAVE_RENDER:
            try:
                img = R.render_tooltip(TT.tooltip_lines(snbt, advanced=True))
                self._adv_photo = ImageTk.PhotoImage(img)
                self.adv_label.config(image=self._adv_photo)
            except Exception as e:
                self.adv_label.config(image="", text="(%s)" % e)
            if self.tip is not None:
                self.tip.pack_forget()
            self.toolbar.grid_remove()
            self.adv_label.pack(anchor="nw")
        else:
            self.adv_label.pack_forget()
            if self.tip is not None:
                self.tip.pack(anchor="nw")
            self.toolbar.grid()

    def _toggle_advanced(self):
        self.render_now()

    def _set_view(self, mode):
        self.view_mode = mode
        self._seg_lit.config(bg=(ACCENT if mode == "literal" else PANEL),
                             fg=("#160d22" if mode == "literal" else MUTED))
        self._seg_raw.config(bg=(ACCENT if mode == "raw" else PANEL),
                            fg=("#160d22" if mode == "raw" else MUTED))
        self._show_view()

    def _show_view(self):
        txt = getattr(self, "_raw_str", "") if self.view_mode == "raw" else getattr(self, "_lit_str", "")
        self.literal_text.configure(state="normal")
        self.literal_text.delete("1.0", "end")
        self.literal_text.insert("1.0", txt)
        self.literal_text.configure(state="disabled")

    def _build_raw(self, snbt):
        out = []
        if self.tip is not None:
            lines = self.tip.get_lines()
            if lines:
                out.append("NAME   " + (T.runs_to_mm(lines[0]) or "(none)"))
                lore = lines[1:]
                if lore:
                    out.append("LORE")
                    for i, r in enumerate(lore):
                        out.append("  %2d   %s" % (i + 1, T.runs_to_mm(r) or ""))
                out.append("")
        out.append(_pretty_snbt(snbt))
        return "\n".join(out)

    def _show_defaults(self, snbt):
        if TT is None:
            return
        try:
            d = I.parse_snbt(snbt)
            mat = (d.get("id") or "").replace("minecraft:", "")
        except Exception:
            mat = self.mat_var.get()
        bits = []
        for slot, atype, amt, base in TT._default_attrs(mat):
            bits.append("%s %s" % (TT._num(amt), TT.ATTR_NAMES.get(atype, atype)))
        dur = TT._default_durability(mat)
        if dur:
            bits.append("Durability %d" % dur)
        if bits:
            self.defaults_label.config(text="Defaults:  " + " · ".join(bits))
            self.defaults_label.grid()
        else:
            self.defaults_label.grid_remove()

    # -- new / open / save --------------------------------------------------
    def _clear_components(self):
        for rec in list(self.active_components):
            self._remove_component(rec)

    def _new_item(self, sample=False):
        self.loaded_file = self.loaded_lit = self.loaded_span = None
        self._clear_components()
        self.count_var.set("1")
        if hasattr(self, "adv_var"):
            self.adv_var.set(False)
        if sample:
            self.mat_var.set("diamond_sword")
            if self.tip is not None:
                self.tip.set_lines([
                    T.parse_mm("<gradient:#5c9eff:#3d5aff><b>Sample Blade"),
                    T.parse_mm("<gray><i>Type to edit me."), [],
                    T.parse_mm("<yellow>⚔ <white>Damage <dark_gray>» <gray>7"), [],
                    T.parse_mm("<gradient:#5c9eff:#3d5aff><b>★ RARE")])
            self._add_component("attribute_modifiers", "attack_damage=7", render=False)
            self.set_status("Type in the tooltip; add components on the right.")
        else:
            self.mat_var.set("stone")
            if self.tip is not None:
                self.tip.set_lines([[]])
            self.set_status("New item.")
        if not self.active_components:
            self.empty_hint.pack(anchor="w", padx=10, pady=20)
        self.schedule_render()

    def open_file(self):
        init = PLOTS if os.path.isdir(PLOTS) else ROOT
        path = filedialog.askopenfilename(initialdir=init, title="Open a DF .py line",
                                          filetypes=[("DF code line", "*.py"), ("All files", "*.*")])
        if path:
            self.open_path(path)

    def open_path(self, path):
        try:
            text = open(path, encoding="utf-8").read()
        except Exception as e:
            self.set_status("Could not read file: %s" % e, error=True)
            return
        items = []
        for mt in I.ITEM_SLOT_RE.finditer(text):
            body = mt.group(1)
            if "\\" in body:
                try:
                    import ast
                    body = ast.literal_eval("'" + body + "'")
                except Exception:
                    pass
            items.append({"lit": mt.group(0), "span": (mt.start(), mt.end()), "snbt": body,
                          "slot": mt.group(2)})
        if not items:
            self.set_status("No item('…') literals in that file.", error=True)
            return
        self.loaded_file = path
        if len(items) == 1:
            self._load_item(items[0])
        else:
            self._pick_item(items)

    def _pick_item(self, items):
        top = tk.Toplevel(self)
        top.title("Pick an item")
        top.configure(bg=PANEL)
        top.transient(self)
        top.grab_set()
        top.geometry("440x380")
        tk.Label(top, text="%d items in %s" % (len(items), os.path.basename(self.loaded_file)),
                 bg=PANEL, fg=FG, font=self.uib).pack(padx=12, pady=(12, 6), anchor="w")
        lb = tk.Listbox(top, bg=ENTRY, fg=FG, relief="flat", bd=0, font=self.mono, selectbackground=ACCENT,
                        selectforeground="#fff", activestyle="none", highlightthickness=1, highlightbackground=BORDER)
        lb.pack(fill="both", expand=True, padx=12, pady=4)
        for idx, it in enumerate(items):
            try:
                s = I.item_summary(it["snbt"])
                lb.insert("end", "%2d. %s  (%s)" % (idx + 1, (s["name"] or "—")[:36], s["id"]))
            except Exception:
                lb.insert("end", "%2d. (unparseable)" % (idx + 1))

        def choose():
            sel = lb.curselection()
            if sel:
                top.destroy()
                self._load_item(items[sel[0]])
        lb.bind("<Double-Button-1>", lambda e: choose())
        self._btn(top, "Edit selected", choose, "accent").pack(pady=(4, 12))

    def _load_item(self, item):
        snbt = item["snbt"]
        try:
            d = I.parse_snbt(snbt)
            s = I.item_summary(snbt)
        except Exception as e:
            self.set_status("Could not parse item: %s" % e, error=True)
            return
        comp = d.get("components", {}) if isinstance(d, dict) else {}
        self.loaded_lit, self.loaded_span = item["lit"], item["span"]
        self.loaded_slot = item.get("slot")  # preserve a pinned chest slot on save
        self.mat_var.set(s["id"])
        self.count_var.set(str(s.get("count", 1)))
        if self.tip is not None:
            self.tip.set_lines([s["runs_name"]] + [T.lore_base(r) for r in s["runs_lore"]])
        self._clear_components()
        for key in COMPONENT_ORDER:
            init = self._load_component(key, comp)
            if init is not None:
                self._add_component(key, init, render=False)
        if not self.active_components:
            self.empty_hint.pack(anchor="w", padx=10, pady=20)
        where = os.path.basename(self.loaded_file) if self.loaded_file else "item"
        self.set_status("Loaded %s — edit, then Save." % where)
        self.render_now()

    def save_to_file(self):
        if not self.loaded_file or self.loaded_span is None:
            self.set_status("Open a .py file and pick an item first (or use Copy).", error=True)
            return
        try:
            text = open(self.loaded_file, encoding="utf-8").read()
        except Exception as e:
            self.set_status("Could not read file: %s" % e, error=True)
            return
        new_lit = "item('%s'%s)" % (self.current_snbt().replace("\\", "\\\\").replace("'", "\\'"),
                                    ", slot=%s" % self.loaded_slot if getattr(self, "loaded_slot", None) else "")
        a, b = self.loaded_span
        if text[a:b] == self.loaded_lit:
            text = text[:a] + new_lit + text[b:]
        elif self.loaded_lit and self.loaded_lit in text:
            text = text.replace(self.loaded_lit, new_lit, 1)
            a = text.find(new_lit)
        else:
            self.set_status("File changed on disk — re-open it.", error=True)
            return
        try:
            open(self.loaded_file, "w", encoding="utf-8").write(text)
        except Exception as e:
            self.set_status("Write failed: %s" % e, error=True)
            return
        self.loaded_lit, self.loaded_span = new_lit, (a, a + len(new_lit))
        self.set_status("Saved to %s ✓  (Check, then /df push)" % os.path.basename(self.loaded_file))

    def check_file(self):
        if not self.loaded_file:
            self.set_status("Open a .py file first to check it.", error=True)
            return
        import subprocess
        try:
            r = subprocess.run([sys.executable, os.path.join(HERE, "dfpy.py"), "check", self.loaded_file],
                               capture_output=True, text=True, timeout=60)
        except Exception as e:
            self.set_status("Check failed to run: %s" % e, error=True)
            return
        out = (r.stdout + r.stderr).strip().splitlines()
        ok = r.returncode == 0 and "[FAIL]" not in (r.stdout + r.stderr)
        self.status.config(text=("✓ " if ok else "✗ ") + (out[-1][:140] if out else ""), fg=(OKCOL if ok else ERRCOL))

    def export_png(self):
        if not HAVE_RENDER:
            self.set_status("Pillow needed for PNG.", error=True)
            return
        path = filedialog.asksaveasfilename(defaultextension=".png", initialdir=os.path.join(ROOT, "previews"),
                                            filetypes=[("PNG", "*.png")], title="Export tooltip PNG")
        if path:
            try:
                R.item_png(self.current_snbt(), path)
                self.set_status("Exported %s ✓" % os.path.basename(path))
            except Exception as e:
                self.set_status("Export failed: %s" % e, error=True)

    def copy_literal(self):
        lit = self.literal_text.get("1.0", "end").strip()
        if lit:
            self.clipboard_clear()
            self.clipboard_append(lit)
            self.set_status("Copied item('…') to clipboard ✓")

    def set_status(self, msg, error=False):
        self.status.config(text=msg, fg=(ERRCOL if error else MUTED))


def main(open_file=None):
    app = DFEditor()
    if open_file and os.path.isfile(open_file):
        app.after(80, lambda: app.open_path(open_file))
    app.mainloop()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
