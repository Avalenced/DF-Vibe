"""df_tipedit.py — a WYSIWYG, in-place Minecraft tooltip editor (canvas + bitmap font).

The item's name and lore are edited *directly in the tooltip* — rendered with the real
Minecraft bitmap font (df_mcfont), styled live (no visible MiniMessage tags), with a real
text cursor, selection, and the authentic dark/gradient tooltip chrome. Line 0 is the item
name; lines 1+ are lore. Enter adds a line; select text + a toolbar action styles it.

  TooltipEditor(parent, on_change=…)        a tk widget (subclass of Frame)
    .set_lines([runs, …])                   load (line 0 = name, rest = lore); runs are dicts
    .get_lines() -> [runs, …]               read back styled runs per line
    .apply_style(color=…/bold='toggle'/…)   style the selection (or set the typing style)
    .apply_gradient([c1, c2, …])            gradient across the selection
    .insert_text(s)                         insert at the cursor (symbols, paste)

A "run"/cell style = {color: name|'#RRGGBB'|None, bold, italic, underlined, strikethrough}.
"""
import tkinter as tk

from PIL import Image, ImageTk
import df_mcfont as F
import df_text as T

DECOR = T.DECOR_KEYS                       # bold, italic, underlined, strikethrough, obfuscated
S = 3                                      # pixels per MC pixel in the editor
SPACING = 10                               # MC px between lines
TX, TY = 7, 6                              # text origin (MC px) inside the chrome
PADR, PADB = 7, 6                          # right / bottom padding (MC px)

BG = (18, 0, 18, 244)
BTOP = (80, 0, 255, 110)
BBOT = (40, 0, 127, 110)
SEL = (130, 95, 220, 120)
CURSOR = "#d9c2ff"
PLACEHOLDER = (95, 95, 105)


def _blank_style():
    return {"color": None, **{k: False for k in DECOR}}


def _cell(ch, style):
    c = {"ch": ch}
    c.update(style)
    return c


def runs_to_cells(runs):
    cells = []
    for r in runs or []:
        st = {"color": r.get("color"), **{k: bool(r.get(k)) for k in DECOR}}
        for ch in r.get("text", ""):
            cells.append(_cell(ch, st))
    return cells


def cells_to_runs(cells):
    runs = []
    for c in cells:
        st = {"color": c["color"], **{k: c[k] for k in DECOR}}
        if runs and _same(runs[-1], st):
            runs[-1]["text"] += c["ch"]
        else:
            runs.append({"text": c["ch"], **st})
    return runs


def _same(run, st):
    return run.get("color") == st["color"] and all(run.get(k) == st[k] for k in DECOR)


class TooltipEditor(tk.Frame):
    def __init__(self, parent, on_change=None, **kw):
        super().__init__(parent, **kw)
        self.on_change = on_change
        self.lines = [[]]                  # list[ list[cell] ]; line 0 = name
        self.cur = (0, 0)                  # (line, col)
        self.anchor = None                 # selection anchor or None
        self.typing = _blank_style()
        self._photo = None
        self._blink = True
        self._focused = False

        self.canvas = tk.Canvas(self, highlightthickness=2, highlightbackground="#34363b",
                                highlightcolor="#b083f0", bd=0, takefocus=1, cursor="xterm")
        self.canvas.pack(fill="both", expand=True)
        c = self.canvas
        c.bind("<Button-1>", self._on_click)
        c.bind("<B1-Motion>", self._on_drag)
        c.bind("<Double-Button-1>", self._on_dclick)
        c.bind("<Key>", self._on_key)
        c.bind("<FocusIn>", self._on_focus)
        c.bind("<FocusOut>", self._on_blur)
        for seq in ("<Left>", "<Right>", "<Up>", "<Down>", "<Home>", "<End>",
                    "<BackSpace>", "<Delete>", "<Return>"):
            c.bind(seq, self._on_nav)
        c.bind("<Control-a>", lambda e: (self._select_all(), "break")[1])
        c.bind("<Control-c>", lambda e: (self._copy(), "break")[1])
        c.bind("<Control-x>", lambda e: (self._copy(), self._del_sel(), self._changed(), "break")[3])
        c.bind("<Control-v>", lambda e: (self._paste(), "break")[1])
        self._tick()

    # ---- public ----------------------------------------------------------
    def set_lines(self, lines):
        self.lines = [runs_to_cells(r) for r in (lines or [[]])] or [[]]
        if not self.lines:
            self.lines = [[]]
        self.cur = (0, 0)
        self.anchor = None
        self.typing = _blank_style()
        self.render()

    def get_lines(self):
        return [cells_to_runs(ln) for ln in self.lines]

    def apply_style(self, **changes):
        rng = self._sel_range()
        if rng is None:
            for k, v in changes.items():
                self.typing[k] = (not self.typing.get(k)) if v == "toggle" else v
            return
        (a, b) = rng
        cells = self._cells_in(a, b)
        for k, v in changes.items():
            if v == "toggle":
                target = not all(c.get(k) for c in cells)
                for c in cells:
                    c[k] = target
            else:
                for c in cells:
                    c[k] = v
        self._changed()

    def apply_gradient(self, stops):
        rng = self._sel_range()
        if rng is None or len(stops) < 2:
            return
        cells = self._cells_in(*rng)
        n = len(cells)
        for i, c in enumerate(cells):
            t = i / max(n - 1, 1)
            c["color"] = T._grad_color(stops, t)
        self._changed()

    def insert_text(self, s):
        self._del_sel()
        li, ci = self.cur
        for ch in s:
            if ch == "\n":
                self._split()
                li, ci = self.cur
            else:
                self.lines[li].insert(ci, _cell(ch, dict(self.typing)))
                ci += 1
                self.cur = (li, ci)
        self.anchor = None
        self._changed()

    def focus_editor(self):
        self.canvas.focus_set()

    # ---- layout ----------------------------------------------------------
    def _line_runs(self, li):
        return cells_to_runs(self.lines[li])

    def _line_w(self, li):
        return sum(F.advance(ord(c["ch"]), c["bold"]) for c in self.lines[li])

    def _x_at(self, li, ci):
        return TX + sum(F.advance(ord(c["ch"]), c["bold"]) for c in self.lines[li][:ci])

    def _content_size(self):
        w = max([self._line_w(i) for i in range(len(self.lines))] + [12])
        h = len(self.lines) * SPACING
        return w, h

    def _hit(self, px, py):
        x = px / S
        y = py / S
        li = int((y - TY) // SPACING)
        li = max(0, min(len(self.lines) - 1, li))
        line = self.lines[li]
        accx = TX
        ci = 0
        for i, c in enumerate(line):
            adv = F.advance(ord(c["ch"]), c["bold"])
            if x < accx + adv / 2:
                return (li, i)
            accx += adv
            ci = i + 1
        return (li, ci)

    # ---- rendering -------------------------------------------------------
    def render(self):
        w, h = self._content_size()
        W = (TX + w + PADR)
        H = (TY + h + PADB)
        img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        self._chrome(img, W, H)
        # selection
        rng = self._sel_range()
        if rng is not None:
            self._draw_sel(img, *rng)
        # placeholder when totally empty
        if len(self.lines) == 1 and not self.lines[0]:
            ph = [{"text": "Item name…", "color": None, **{k: False for k in DECOR}}]
            ghost = Image.new("RGBA", img.size, (0, 0, 0, 0))
            F.draw_text(ghost, TX, TY, ph, S, shadow=False)
            ghost = _tint(ghost, PLACEHOLDER)
            img.alpha_composite(ghost)
        else:
            for i in range(len(self.lines)):
                runs = self._line_runs(i)
                if runs:
                    F.draw_text(img, TX, TY + i * SPACING, runs, S)
        self._photo = ImageTk.PhotoImage(img)
        c = self.canvas
        c.delete("all")
        c.config(width=img.width, height=img.height)
        c.create_image(0, 0, anchor="nw", image=self._photo)
        self._draw_cursor()

    def _chrome(self, img, W, H):
        def rect(x0, y0, x1, y1, rgba):
            if x1 > x0 and y1 > y0:
                img.alpha_composite(Image.new("RGBA", ((x1 - x0) * S, (y1 - y0) * S), rgba), (x0 * S, y0 * S))
        rect(1, 0, W - 1, H, BG)
        rect(0, 1, W, H - 1, BG)
        # gradient L/R borders
        for col, x in ((True, 1), (True, W - 2)):
            strip = Image.new("RGBA", (S, (H - 2) * S), (0, 0, 0, 0))
            px = strip.load()
            for j in range((H - 2) * S):
                t = j / max((H - 2) * S - 1, 1)
                px_col = tuple(round(BTOP[k] + (BBOT[k] - BTOP[k]) * t) for k in range(4))
                for i in range(S):
                    px[i, j] = px_col
            img.alpha_composite(strip, (x * S, 1 * S))
        rect(1, 1, W - 1, 2, BTOP)
        rect(1, H - 2, W - 1, H - 1, BBOT)

    def _draw_sel(self, img, a, b):
        (la, ca), (lb, cb) = a, b
        for li in range(la, lb + 1):
            x0 = self._x_at(li, ca if li == la else 0)
            x1 = self._x_at(li, cb if li == lb else len(self.lines[li]))
            if li != lb:
                x1 = max(x1, self._x_at(li, len(self.lines[li])) + 2)
            y0 = TY + li * SPACING - 1
            ov = Image.new("RGBA", (max(1, (x1 - x0)) * S, (SPACING) * S), SEL)
            img.alpha_composite(ov, (x0 * S, y0 * S))

    def _draw_cursor(self):
        if not (self._focused and self._blink and self.anchor is None):
            return
        li, ci = self.cur
        x = self._x_at(li, ci) * S
        y0 = (TY + li * SPACING - 1) * S
        y1 = (TY + li * SPACING + 9) * S
        self.canvas.create_rectangle(x, y0, x + max(1, S - 1), y1, fill=CURSOR, width=0)

    # ---- input -----------------------------------------------------------
    def _on_focus(self, _):
        self._focused = True
        self._blink = True
        self._draw_cursor()

    def _on_blur(self, _):
        self._focused = False
        self.render()

    def _on_click(self, e):
        self.canvas.focus_set()
        self.cur = self._hit(e.x, e.y)
        self.anchor = None
        self._sync_typing()
        self.render()

    def _on_drag(self, e):
        if self.anchor is None:
            self.anchor = self.cur
        self.cur = self._hit(e.x, e.y)
        self.render()

    def _on_dclick(self, e):
        li, ci = self._hit(e.x, e.y)
        line = self.lines[li]
        a = ci
        while a > 0 and line[a - 1]["ch"].isalnum():
            a -= 1
        b = ci
        while b < len(line) and line[b]["ch"].isalnum():
            b += 1
        self.anchor = (li, a)
        self.cur = (li, b)
        self.render()
        return "break"

    def _on_key(self, e):
        if e.state & 0x4:                  # Control combos handled elsewhere
            return
        ch = e.char
        if ch and ch.isprintable() and ch not in ("\r", "\n", "\t"):
            self.insert_text(ch)
            return "break"

    def _on_nav(self, e):
        k = e.keysym
        shift = bool(e.state & 0x1)
        if k in ("BackSpace", "Delete"):
            if self._sel_range() is not None:
                self._del_sel()
            elif k == "BackSpace":
                self._backspace()
            else:
                self._delete()
            self._changed()
            return "break"
        if k == "Return":
            self._del_sel()
            self._split()
            self.anchor = None
            self._changed()
            return "break"
        # movement
        if shift and self.anchor is None:
            self.anchor = self.cur
        if not shift:
            self.anchor = None
        self.cur = self._moved(k)
        self._sync_typing()
        self.render()
        return "break"

    def _moved(self, k):
        li, ci = self.cur
        if k == "Left":
            if ci > 0:
                return (li, ci - 1)
            return (li - 1, len(self.lines[li - 1])) if li > 0 else (li, 0)
        if k == "Right":
            if ci < len(self.lines[li]):
                return (li, ci + 1)
            return (li + 1, 0) if li < len(self.lines) - 1 else (li, ci)
        if k == "Home":
            return (li, 0)
        if k == "End":
            return (li, len(self.lines[li]))
        if k == "Up":
            return (li - 1, min(ci, len(self.lines[li - 1]))) if li > 0 else (li, 0)
        if k == "Down":
            return (li + 1, min(ci, len(self.lines[li + 1]))) if li < len(self.lines) - 1 else (li, len(self.lines[li]))
        return (li, ci)

    # ---- edit primitives -------------------------------------------------
    def _split(self):
        li, ci = self.cur
        line = self.lines[li]
        self.lines[li] = line[:ci]
        self.lines.insert(li + 1, line[ci:])
        self.cur = (li + 1, 0)

    def _backspace(self):
        li, ci = self.cur
        if ci > 0:
            del self.lines[li][ci - 1]
            self.cur = (li, ci - 1)
        elif li > 0:
            prev = len(self.lines[li - 1])
            self.lines[li - 1].extend(self.lines[li])
            del self.lines[li]
            self.cur = (li - 1, prev)

    def _delete(self):
        li, ci = self.cur
        if ci < len(self.lines[li]):
            del self.lines[li][ci]
        elif li < len(self.lines) - 1:
            self.lines[li].extend(self.lines[li + 1])
            del self.lines[li + 1]

    # ---- selection -------------------------------------------------------
    def _sel_range(self):
        if self.anchor is None or self.anchor == self.cur:
            return None
        return tuple(sorted([self.anchor, self.cur]))

    def _cells_in(self, a, b):
        (la, ca), (lb, cb) = a, b
        out = []
        for li in range(la, lb + 1):
            s = ca if li == la else 0
            e = cb if li == lb else len(self.lines[li])
            out.extend(self.lines[li][s:e])
        return out

    def _del_sel(self):
        rng = self._sel_range()
        if rng is None:
            return False
        (la, ca), (lb, cb) = rng
        if la == lb:
            del self.lines[la][ca:cb]
        else:
            tail = self.lines[lb][cb:]
            self.lines[la] = self.lines[la][:ca] + tail
            del self.lines[la + 1:lb + 1]
        self.cur = (la, ca)
        self.anchor = None
        return True

    def _select_all(self):
        self.anchor = (0, 0)
        last = len(self.lines) - 1
        self.cur = (last, len(self.lines[last]))
        self.render()

    def _sync_typing(self):
        li, ci = self.cur
        line = self.lines[li]
        ref = line[ci - 1] if ci > 0 else (line[0] if line else None)
        if ref:
            self.typing = {"color": ref["color"], **{k: ref[k] for k in DECOR}}

    # ---- clipboard -------------------------------------------------------
    def _copy(self):
        rng = self._sel_range()
        if rng is None:
            return
        txt = "".join(c["ch"] for c in self._cells_in(*rng))
        # selections never span the newline char itself; join lines explicitly
        (la, ca), (lb, cb) = rng
        if la != lb:
            parts = ["".join(c["ch"] for c in self.lines[la][ca:])]
            for li in range(la + 1, lb):
                parts.append("".join(c["ch"] for c in self.lines[li]))
            parts.append("".join(c["ch"] for c in self.lines[lb][:cb]))
            txt = "\n".join(parts)
        try:
            self.clipboard_clear()
            self.clipboard_append(txt)
        except tk.TclError:
            pass

    def _paste(self):
        try:
            s = self.clipboard_get()
        except tk.TclError:
            return
        if s:
            self.insert_text(s)
        return "break"

    # ---- blink / change --------------------------------------------------
    def _tick(self):
        self._blink = not self._blink
        if self._focused and self.anchor is None:
            self.render()
        self.after(530, self._tick)

    def _changed(self):
        self.render()
        if self.on_change:
            self.on_change()


def _tint(img, rgb):
    out = Image.new("RGBA", img.size, rgb + (0,))
    out.putalpha(img.getchannel("A"))
    return out
