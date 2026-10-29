"""Zeichnen in GTK 4: Fläche mit Stift-Druck, Editor-Fenster und Vorschau für Notizen."""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
gi.require_version('Gdk', '4.0')
from gi.repository import Adw, Gdk, GLib, Gtk  # noqa: E402

from . import ink  # noqa: E402


def _dark() -> bool:
    return Adw.StyleManager.get_default().get_dark()


def _theme():
    """(Papier, Linien, Textfarbe) passend zum Hell/Dunkel-Modus."""
    if _dark():
        return (0.14, 0.14, 0.16), (1, 1, 1), (0.93, 0.93, 0.95)
    return (1, 1, 1), (0, 0, 0), (0.1, 0.1, 0.12)


class InkCanvas(Gtk.DrawingArea):
    """Seite von 1000 Einheiten Breite; die Höhe folgt aus doc.height. Stift, Maus; Finger werden ignoriert (Handballen)."""

    def __init__(self, model: ink.Model, on_change=None):
        super().__init__(hexpand=True, halign=Gtk.Align.FILL)
        self.model = model
        self.on_change = on_change or (lambda: None)
        self.set_draw_func(self._draw)
        self.set_content_height(int(model.doc.height * 0.6))
        self.connect('resize', lambda *_: self.queue_draw())
        stylus = Gtk.GestureStylus()
        stylus.connect('down', self._stylus_down)
        stylus.connect('motion', self._stylus_motion)
        stylus.connect('up', self._stylus_up)
        self.add_controller(stylus)
        drag = Gtk.GestureDrag(button=1)
        drag.connect('drag-begin', self._drag_begin)
        drag.connect('drag-update', self._drag_update)
        drag.connect('drag-end', self._drag_end)
        self.add_controller(drag)
        self._drag_start = (0.0, 0.0)
        self._by_stylus = False

    # Seite → Einheiten
    def _scale(self) -> float:
        return max(1, self.get_width()) / ink.W

    def _pos(self, x, y):
        s = self._scale()
        return x / s, y / s

    def refit(self) -> None:
        self.set_content_height(int(self.model.doc.height * 0.6))
        self.queue_draw()

    # ---------- Stift ----------
    def _stylus_down(self, g, x, y):
        self._by_stylus = True
        tool = g.get_device_tool()
        erase = bool(tool and tool.get_tool_type() == Gdk.DeviceToolType.ERASER)
        ok, p = g.get_axis(Gdk.AxisUse.PRESSURE)
        self.pen_down(*self._pos(x, y), p if ok else 0.5, erase)

    def _stylus_motion(self, g, x, y):
        if self._by_stylus:
            ok, p = g.get_axis(Gdk.AxisUse.PRESSURE)
            self.pen_move(*self._pos(x, y), p if ok else 0.5)

    def _stylus_up(self, g, x, y):
        if self._by_stylus:
            self.pen_up()
            self._by_stylus = False

    # ---------- Maus ----------
    def _drag_begin(self, g, x, y):
        ev = g.get_last_event(None)
        dev = ev.get_device() if ev else None
        if self._by_stylus or (ev and ev.get_device_tool()):
            return  # kommt vom Stift und wird dort behandelt
        if dev and dev.get_source() == Gdk.InputSource.TOUCHSCREEN:
            return  # Finger/Handballen zeichnen nicht
        self._drag_start = (x, y)
        self._mouse = True
        self.pen_down(*self._pos(x, y), 0.5, False)

    def _drag_update(self, g, dx, dy):
        if getattr(self, '_mouse', False):
            x, y = self._drag_start
            self.pen_move(*self._pos(x + dx, y + dy), 0.5)

    def _drag_end(self, g, dx, dy):
        if getattr(self, '_mouse', False):
            self._mouse = False
            self.pen_up()

    # ---------- gemeinsame Schritte (auch von Tests benutzt) ----------
    def pen_down(self, x, y, p=0.5, erase=False):
        if self.model.down(x, y, p, erase):
            self.queue_draw()

    def pen_move(self, x, y, p=0.5):
        if self.model.move(x, y, p) or self.model.current:
            self.queue_draw()

    def pen_up(self):
        if self.model.up():
            self.on_change()
        self.queue_draw()

    def _draw(self, _a, cr, w, h):
        paper, line, text = _theme()
        scale = w / ink.W
        ink.draw_paper(cr, w, h, scale, paper, line)
        for s in self.model.doc.strokes:
            ink.draw_stroke(cr, s, scale, text)
        if self.model.current:
            ink.draw_stroke(cr, self.model.current, scale, text)


class InkWindow(Adw.Window):
    """Vollbild-Editor für eine Zeichnung. Speichert beim Schließen und bei jedem fertigen Strich."""

    def __init__(self, parent, note_dir: Path, ink_id: str, on_saved=None):
        super().__init__(transient_for=parent, modal=True, title='Zeichnung', default_width=1000, default_height=760)
        self.note_dir, self.ink_id, self.on_saved = Path(note_dir), ink_id, on_saved
        self.model = ink.Model(ink.load(self.note_dir, ink_id))
        self.canvas = InkCanvas(self.model, self._changed)
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        done = Gtk.Button(label='Fertig')
        done.add_css_class('suggested-action')
        done.connect('clicked', lambda *_: self.close())
        hb.pack_end(done)
        self.undo_btn = Gtk.Button(icon_name='edit-undo-symbolic', tooltip_text='Rückgängig (Strg+Z)')
        self.undo_btn.connect('clicked', lambda *_: self.undo())
        hb.pack_start(self.undo_btn)
        tv.add_top_bar(hb)
        tv.add_top_bar(self._tools())
        sc = Gtk.ScrolledWindow(vexpand=True)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, margin_start=24, margin_end=24, margin_top=12, margin_bottom=24, spacing=12)
        box.append(self.canvas)
        more = Gtk.Button(label='Seite verlängern', halign=Gtk.Align.CENTER)
        more.add_css_class('pill')
        more.connect('clicked', lambda *_: self.extend())
        box.append(more)
        sc.set_child(Adw.Clamp(maximum_size=1100, child=box))
        tv.set_content(sc)
        self.set_content(tv)
        keys = Gtk.EventControllerKey()
        keys.connect('key-pressed', self._on_key)
        self.add_controller(keys)
        self.connect('close-request', self._on_close)
        self._saved_once = False
        self._dirty = False

    def _tools(self):
        bar = Gtk.Box(spacing=6, margin_start=12, margin_end=12, margin_top=6, margin_bottom=6, halign=Gtk.Align.CENTER)
        group = None
        self.tool_btns = {}
        for tool, text in (('pen', 'Stift'), ('marker', 'Marker'), ('eraser', 'Radierer')):
            b = Gtk.ToggleButton(label=text)
            if group:
                b.set_group(group)
            else:
                group = b
                b.set_active(True)
            b.connect('toggled', lambda btn, t=tool: btn.get_active() and self.set_tool(t))
            self.tool_btns[tool] = b
            bar.append(b)
        bar.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL, margin_start=6, margin_end=6))
        cgroup = None
        self.color_btns = {}
        for c in ink.COLORS:
            b = Gtk.ToggleButton(tooltip_text='Textfarbe' if c == 'text' else c)
            b.add_css_class('circular')
            dot = Gtk.DrawingArea(content_width=18, content_height=18)
            dot.set_draw_func(lambda _a, cr, w, h, col=c: self._dot(cr, w, h, col))
            b.set_child(dot)
            if cgroup:
                b.set_group(cgroup)
            else:
                cgroup = b
                b.set_active(True)
            b.connect('toggled', lambda btn, col=c: btn.get_active() and self.set_color(col))
            self.color_btns[c] = b
            bar.append(b)
        return bar

    def _dot(self, cr, w, h, col):
        import math
        cr.set_source_rgb(*ink.resolve(col, _theme()[2]))
        cr.arc(w / 2, h / 2, min(w, h) / 2 - 1, 0, 2 * math.pi)
        cr.fill()

    def set_tool(self, t):
        self.model.tool = t

    def set_color(self, c):
        self.model.color = c
        if self.model.tool == 'eraser':
            self.tool_btns['pen'].set_active(True)

    def undo(self):
        if self.model.undo():
            self._changed()
            self.canvas.queue_draw()

    def extend(self):
        d = self.model.doc
        d.height = min(ink.MAX_H, d.height + 300)
        self.canvas.refit()
        self._changed()

    def _on_key(self, _c, keyval, _code, state):
        if keyval in (Gdk.KEY_z, Gdk.KEY_Z) and state & Gdk.ModifierType.CONTROL_MASK:
            self.undo()
            return True
        if keyval == Gdk.KEY_Escape:
            self.close()
            return True
        return False

    def _changed(self):
        self._dirty = True
        self.save()

    def save(self):
        if not self.model.doc.strokes and not self._saved_once and not (self.note_dir / '.ink' / f'{self.ink_id}.json').exists():
            return  # leere, nie gezeichnete Seite legt keine Dateien an
        ink.save(self.note_dir, self.ink_id, self.model.doc)
        self._saved_once = True
        if self.on_saved:
            self.on_saved(self.ink_id)

    def _on_close(self, *_):
        if self._dirty:
            self.save()
        elif self.on_saved:
            self.on_saved(self.ink_id)
        return False


class InkPreview(Gtk.DrawingArea):
    """Vorschau einer Zeichnung in der Notiz; Klick öffnet den Editor."""

    def __init__(self, note_dir: Path, ink_id: str, width: int, on_open):
        super().__init__()
        self.ink_id = ink_id
        self.doc = ink.load(note_dir, ink_id)
        self.set_content_width(width)
        self.set_content_height(int(self.doc.height * width / ink.W))
        self.set_cursor_from_name('pointer')
        self.set_draw_func(self._draw)
        click = Gtk.GestureClick(button=1)
        click.connect('released', lambda *_: on_open(ink_id))
        self.add_controller(click)

    @staticmethod
    def height_for(note_dir: Path, ink_id: str, width: int) -> int:
        return int(ink.load(note_dir, ink_id).height * width / ink.W)

    def _draw(self, _a, cr, w, h):
        paper, line, text = _theme()
        scale = w / ink.W
        cr.save()
        cr.rectangle(0, 0, w, h)
        cr.clip()
        ink.draw_paper(cr, w, h, scale, paper, line)
        for s in self.doc.strokes:
            ink.draw_stroke(cr, s, scale, text)
        if not self.doc.strokes:
            cr.set_source_rgba(*text, 0.5)
            cr.select_font_face('sans')
            cr.set_font_size(15)
            ext = cr.text_extents('Zeichnung · anklicken zum Zeichnen')
            cr.move_to((w - ext.width) / 2, h / 2)
            cr.show_text('Zeichnung · anklicken zum Zeichnen')
        cr.restore()
