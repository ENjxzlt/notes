"""Daten für Übersicht und Bereiche, direkt aus dem lokalen Ordner (also auch offline). Gleiche Regeln wie die Web-Ansicht."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

TOP_AREAS = ['Schule', 'Projekte', 'Alltag']
MONTHS = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez']
WEEKS = 53
_TASK = re.compile(r'^\s*[-*] \[ \] (.+)$')


@dataclass
class Note:
    path: str  # relativ, mit /
    mtime: float

    @property
    def title(self) -> str:
        return self.path.rsplit('/', 1)[-1].removesuffix('.md')


def scan(root: Path) -> list[Note]:
    out = []
    for p in Path(root).rglob('*.md'):
        rel = p.relative_to(root).as_posix()
        if any(seg.startswith('.') for seg in rel.split('/')) or p.name.endswith('.tmp') or rel == 'Inbox.md':
            continue  # die Inbox hat ihre eigene Ansicht
        out.append(Note(rel, p.stat().st_mtime))
    return sorted(out, key=lambda n: -n.mtime)


def in_area(path: str, area: str) -> bool:
    return not area or path.startswith(area + '/')


def open_tasks(text: str) -> list[str]:
    out, fence = [], False
    for line in text.split('\n'):
        if re.match(r'^\s*```', line):
            fence = not fence
            continue
        if not fence and (m := _TASK.match(line)):
            out.append(m.group(1).strip())
    return out


def tasks_of(root: Path, notes: list[Note]) -> list[tuple[Note, str]]:
    out = []
    for n in notes:
        try:
            text = (Path(root) / n.path).read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        out.extend((n, t) for t in open_tasks(text))
    return out


def dirs(root: Path) -> list[str]:
    """Alle Ordner im Notizen-Ordner (relativ, ohne versteckte), auch leere."""
    out = []
    for p in Path(root).rglob('*'):
        if p.is_dir():
            rel = p.relative_to(root).as_posix()
            if not any(seg.startswith('.') for seg in rel.split('/')):
                out.append(rel)
    return out


def folders(notes: list[Note], area: str, empty: list[str] | tuple = ()) -> list[tuple[str, list[Note]]]:
    """Unterordner von `area` (Wurzel: Schule, Projekte, Alltag zuerst), mit ihren Notizen; `empty` sind alle Ordner, auch leere."""
    groups: dict[str, list[Note]] = {a: [] for a in TOP_AREAS} if not area else {}
    for d in empty:
        if not area or d.startswith(area + '/'):
            groups.setdefault((d[len(area) + 1:] if area else d).split('/', 1)[0], [])
    for n in notes:
        rest = n.path[len(area) + 1:] if area else n.path
        if '/' not in rest:
            continue
        groups.setdefault(rest.split('/', 1)[0], []).append(n)

    def key(name):
        if not area and name in TOP_AREAS:
            return (0, TOP_AREAS.index(name), name)
        return (1, 0, name.lower())

    return [(f'{area}/{k}' if area else k, groups[k]) for k in sorted(groups, key=key)]


def activity(mtimes: list[float], today: date | None = None):
    """53 Wochen, Montag zuerst; None = Zukunft. Gezählt wird je Notiz der Tag der letzten Änderung."""
    today = today or date.today()
    counts: dict[date, int] = {}
    for t in mtimes:
        d = datetime.fromtimestamp(t).date()
        counts[d] = counts.get(d, 0) + 1
    start = today - timedelta(days=today.weekday() + (WEEKS - 1) * 7)
    cells, months, total = [], [], 0
    for i in range(WEEKS * 7):
        d = start + timedelta(days=i)
        if d > today:
            cells.append(None)
            continue
        n = counts.get(d, 0)
        total += n
        level = 0 if n == 0 else 1 if n == 1 else 2 if n <= 3 else 3 if n <= 6 else 4
        cells.append((d, n, level))
        if i % 7 == 0 and d.day <= 7:
            months.append((MONTHS[d.month - 1], i // 7))
    return cells, months, total


def when(t: float, now: datetime | None = None) -> str:
    now = now or datetime.now()
    d = datetime.fromtimestamp(t)
    diff = (now.date() - d.date()).days
    if diff == 0:
        return d.strftime('%H:%M')
    if diff == 1:
        return 'gestern'
    return d.strftime('%d.%m.')
