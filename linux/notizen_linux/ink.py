"""Zeichnungen, dateikompatibel zur Android-App.

Neben der Notiz liegt im versteckten Ordner `.ink` je Zeichnung `<id>.json` (bearbeitbar, mit Druck) und `<id>.svg`
(Vorschau für andere Programme). In der Notiz steht nur die Zeile `![Zeichnung](.ink/<id>.svg)`.
Die Seite ist 1000 Einheiten breit und `h` Einheiten hoch (200 bis 3000).
"""

from __future__ import annotations

import json
import os
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path

W = 1000
MAX_H = 3000
MIN_H = 200
MARKER_W = 16.0
BLOCK = re.compile(r'^!\[Zeichnung\]\(\.ink/([A-Za-z0-9_-]+)\.svg\)\s*$')

# Farben der Werkzeugleiste; „text“ folgt der Textfarbe (hell/dunkel)
COLORS = ['text', '#e5484d', '#2f6fed', '#30a46c', '#f5a524']


@dataclass
class Stroke:
    tool: str  # "pen" oder "marker"
    color: str  # "text" oder "#rrggbb"
    pts: list = field(default_factory=list)  # [x, y, Druck]


class Doc:
    def __init__(self, height: int = 600):
        self.height = height
        self.strokes: list[Stroke] = []


def new_id() -> str:
    return uuid.uuid4().hex[:8]


def width_for(p: float) -> float:
    return 1.2 + 4.5 * min(max(p, 0.0), 1.0)


def block_line(ink_id: str) -> str:
    return f'![Zeichnung](.ink/{ink_id}.svg)'


def ink_dir(note_dir: Path) -> Path:
    return Path(note_dir) / '.ink'


class Model:
    """Striche aufnehmen, radieren (ganze Striche), rückgängig machen – wie InkModel in der Android-App."""

    def __init__(self, doc: Doc):
        self.doc = doc
        self.tool = 'pen'  # pen, marker, eraser
        self.color = 'text'
        self.current: Stroke | None = None
        self._ops: list[tuple] = []  # (hinzugefügt, [(index, Strich)])
        self._erasing = False
        self._erased: list = []

    def down(self, x, y, p=0.5, force_erase=False) -> bool:
        self._erasing = force_erase or self.tool == 'eraser'
        if self._erasing:
            self._erased = []
            return self._erase_at(x, y)
        self.current = Stroke(self.tool, self.color, [[x, y, p]])
        return False

    def move(self, x, y, p=0.5) -> bool:
        if self._erasing:
            return self._erase_at(x, y)
        if self.current:
            self.current.pts.append([x, y, p])
        return False

    def up(self) -> bool:
        changed = False
        if self._erasing:
            if self._erased:
                self._ops.append((None, self._erased))
                changed = True
        elif self.current:
            self.doc.strokes.append(self.current)
            self._ops.append((self.current, []))
            changed = True
        self.current, self._erasing = None, False
        return changed

    def cancel(self) -> None:
        if self._erasing and self._erased:
            self._ops.append((None, self._erased))
        self.current, self._erasing = None, False

    def undo(self) -> bool:
        if not self._ops:
            return False
        added, removed = self._ops.pop()
        if added in self.doc.strokes:
            self.doc.strokes.remove(added)
        for i, s in sorted(removed, key=lambda t: t[0]):
            self.doc.strokes.insert(min(i, len(self.doc.strokes)), s)
        return True

    def _erase_at(self, x, y, radius=14.0) -> bool:
        changed = False
        for i in range(len(self.doc.strokes) - 1, -1, -1):
            s = self.doc.strokes[i]
            if any(((p[0] - x) ** 2 + (p[1] - y) ** 2) ** 0.5 < radius for p in s.pts):
                self._erased.append((i, s))
                del self.doc.strokes[i]
                changed = True
        return changed


# ---------- Laden und Speichern ----------

def load(note_dir: Path, ink_id: str) -> Doc:
    f = ink_dir(note_dir) / f'{ink_id}.json'
    doc = Doc()
    if not f.is_file():
        return doc
    try:
        o = json.loads(f.read_text(encoding='utf-8'))
        doc.height = min(max(int(o.get('h', 600)), MIN_H), MAX_H)
        for so in o['strokes']:
            p = so['p']
            pts = [[float(p[j]), float(p[j + 1]), float(p[j + 2])] for j in range(0, len(p) - 2, 3)]
            doc.strokes.append(Stroke(str(so.get('t', 'pen')), str(so.get('c', 'text')), pts))
    except (OSError, ValueError, KeyError, TypeError):
        try:  # beschädigte Datei nicht überschreiben, sondern zur Seite legen
            f.rename(f.with_name(f.name + '.defekt'))
        except OSError:
            pass
        return Doc()
    return doc


def _write(path: Path, text: str) -> None:
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(text, encoding='utf-8')
    os.replace(tmp, path)


def save(note_dir: Path, ink_id: str, doc: Doc) -> None:
    d = ink_dir(note_dir)
    d.mkdir(parents=True, exist_ok=True)
    strokes = [{'t': s.tool, 'c': s.color, 'p': [v for p in s.pts for v in (round(p[0], 1), round(p[1], 1), round(p[2], 2))]} for s in doc.strokes]
    _write(d / f'{ink_id}.json', json.dumps({'v': 1, 'w': W, 'h': doc.height, 'strokes': strokes}, separators=(',', ':')))
    _write(d / f'{ink_id}.svg', to_svg(doc))


def delete(note_dir: Path, ink_id: str) -> None:
    for ext in ('json', 'svg'):
        (ink_dir(note_dir) / f'{ink_id}.{ext}').unlink(missing_ok=True)


def to_svg(doc: Doc) -> str:
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {doc.height}" width="{W}" height="{doc.height}">']
    for s in doc.strokes:
        if not s.pts:
            continue
        col = s.color if re.fullmatch(r'#[0-9a-fA-F]{6}', s.color) else '#18181b'
        marker = s.tool == 'marker'
        w = MARKER_W if marker else sum(width_for(p[2]) for p in s.pts) / len(s.pts)
        d = 'M' + ' L'.join(f'{p[0]:.1f} {p[1]:.1f}' for p in s.pts)
        if len(s.pts) == 1:
            d += f' L{s.pts[0][0] + 0.1:.1f} {s.pts[0][1]:.1f}'
        out.append(f'<path d="{d}" fill="none" stroke="{col}" stroke-width="{w:.2f}" stroke-linecap="{"butt" if marker else "round"}"'
                   f' stroke-linejoin="round"{" stroke-opacity=\"0.35\"" if marker else ""}/>')
    out.append('</svg>')
    return '\n'.join(out) + '\n'


# ---------- Zeichnen mit cairo ----------

def resolve(color: str, text_rgb=(0.1, 0.1, 0.12)) -> tuple[float, float, float]:
    if color == 'text':
        return text_rgb
    m = re.fullmatch(r'#([0-9a-fA-F]{6})', color)
    if not m:
        return text_rgb
    v = int(m.group(1), 16)
    return (v >> 16) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255


def draw_stroke(cr, s: Stroke, scale: float, text_rgb=(0.1, 0.1, 0.12)) -> None:
    """Zeichnet einen Strich auf einen cairo-Kontext (Einheiten × scale = Pixel)."""
    import cairo
    if not s.pts:
        return
    r, g, b = resolve(s.color, text_rgb)
    cr.set_line_join(cairo.LINE_JOIN_ROUND)
    if s.tool == 'marker':
        cr.set_source_rgba(r, g, b, 0.35)
        cr.set_line_width(MARKER_W * scale)
        cr.set_line_cap(cairo.LINE_CAP_BUTT)
        cr.move_to(s.pts[0][0] * scale, s.pts[0][1] * scale)
        for p in s.pts[1:]:
            cr.line_to(p[0] * scale, p[1] * scale)
        if len(s.pts) == 1:
            cr.line_to(s.pts[0][0] * scale + 0.1, s.pts[0][1] * scale)
        cr.stroke()
        return
    cr.set_source_rgb(r, g, b)
    cr.set_line_cap(cairo.LINE_CAP_ROUND)
    if len(s.pts) == 1:
        cr.set_line_width(width_for(s.pts[0][2]) * scale)
        cr.move_to(s.pts[0][0] * scale, s.pts[0][1] * scale)
        cr.line_to(s.pts[0][0] * scale + 0.1, s.pts[0][1] * scale)
        cr.stroke()
        return
    for a, c in zip(s.pts, s.pts[1:]):
        cr.set_line_width(width_for((a[2] + c[2]) / 2) * scale)
        cr.move_to(a[0] * scale, a[1] * scale)
        cr.line_to(c[0] * scale, c[1] * scale)
        cr.stroke()


def draw_paper(cr, w: float, h: float, scale: float, paper_rgb, line_rgb) -> None:
    """Papier: Fläche mit feinen Linien alle 50 Einheiten."""
    cr.set_source_rgb(*paper_rgb)
    cr.rectangle(0, 0, w, h)
    cr.fill()
    cr.set_source_rgba(*line_rgb, 0.5)
    cr.set_line_width(1)
    y = 50 * scale
    while y < h - 1:
        cr.move_to(0, y + 0.5)
        cr.line_to(w, y + 0.5)
        y += 50 * scale
    cr.stroke()
