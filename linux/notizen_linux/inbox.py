"""Ideen-Dump und Aufgaben (gleiches Format wie Notizen-App und Web-Ansicht).

Inbox: eine Notiz `Inbox.md` im Notizen-Ordner, je Idee eine Zeile `- 2026-10-29 12:31 · Text`.
Aufgaben: `- [ ] Titel (~45 min · bis 30.10.)`, Dauer und Frist sind optional.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

INBOX_FILE = 'Inbox.md'
_IDEA = re.compile(r'^- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) · (.+)$')
_TASK = re.compile(r'^(\s*[-*] )\[ \] (.+)$')
_META = re.compile(r'^(.*?)\s*\(~(\d+) min(?: · bis (\d{2}\.\d{2}\.))?\)\s*$')
_DATE = re.compile(r'^(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?$')
_WEEKDAYS = {'montag': 1, 'mo': 1, 'dienstag': 2, 'di': 2, 'mittwoch': 3, 'mi': 3, 'donnerstag': 4, 'do': 4,
             'freitag': 5, 'fr': 5, 'samstag': 6, 'sa': 6, 'sonntag': 7, 'so': 7}


@dataclass
class Idea:
    raw: str
    stamp: str
    text: str


@dataclass
class Task:
    line: int
    title: str
    minutes: int | None
    due: str | None
    path: str = ''


def stamp(now: datetime | None = None) -> str:
    return (now or datetime.now()).strftime('%Y-%m-%d %H:%M')


def format_idea(st: str, text: str) -> str:
    return f'- {st} · ' + re.sub(r'\s*\n\s*', ' / ', text.strip())


def parse_ideas(content: str) -> list[Idea]:
    out = []
    for line in content.split('\n'):
        m = _IDEA.match(line.rstrip())
        if m:
            out.append(Idea(line.rstrip(), m.group(1), m.group(2)))
    return out


def _attach(content: str, line: str) -> str:
    """Hängt `line` an; steht im Text nur eine Überschrift, bleibt eine Leerzeile dazwischen."""
    body = content.rstrip('\n')
    return body + ('\n' if '\n' in body else '\n\n') + line + '\n'


def append_idea(content: str, idea: str, st: str) -> str:
    if not idea.strip():
        return content
    return _attach(content if content.strip() else '# Inbox\n\n', format_idea(st, idea))


def remove_idea(content: str, raw: str) -> str:
    lines = content.split('\n')
    for i, line in enumerate(lines):
        if line.rstrip() == raw:
            del lines[i]
            break
    return '\n'.join(lines)


def parse_due(text: str, today: date | None = None) -> str | None:
    """Frist aus „Freitag“, „morgen“ oder „3.11.“ als „dd.mm.“ (nächster passender Tag); None, wenn unklar."""
    today = today or date.today()
    s = text.strip().lower().removeprefix('bis ').strip()
    fmt = lambda d: f'{d.day:02d}.{d.month:02d}.'  # noqa: E731
    if not s:
        return None
    if s == 'heute':
        return fmt(today)
    if s == 'morgen':
        return fmt(today + timedelta(days=1))
    if s == 'übermorgen':
        return fmt(today + timedelta(days=2))
    if s in _WEEKDAYS:
        return fmt(today + timedelta(days=(_WEEKDAYS[s] - today.isoweekday()) % 7))
    m = _DATE.match(s)
    if not m:
        return None
    try:
        return fmt(date(today.year, int(m.group(2)), int(m.group(1))))
    except ValueError:
        return None


def task_line(title: str, minutes: int | None, due: str | None) -> str:
    if minutes is not None and due:
        meta = f' (~{minutes} min · bis {due})'
    elif minutes is not None:
        meta = f' (~{minutes} min)'
    else:
        meta = ''
    return f'- [ ] {title.strip()}{meta}'


def parse_tasks(text: str) -> list[Task]:
    """Offene Aufgaben einer Notiz mit Zeilennummer (Codeblöcke ausgenommen)."""
    out, fence = [], False
    for i, line in enumerate(text.split('\n')):
        if re.match(r'^\s*```', line):
            fence = not fence
            continue
        m = None if fence else _TASK.match(line)
        if not m:
            continue
        body = m.group(2).strip()
        meta = _META.match(body)
        if meta:
            out.append(Task(i, meta.group(1).strip(), int(meta.group(2)), meta.group(3) or None))
        else:
            out.append(Task(i, body, None, None))
    return out


def check_task(text: str, line: int, expect_title: str) -> str | None:
    """Hakt die Aufgabe in Zeile `line` ab; None, wenn die Zeile keine offene Aufgabe (mehr) ist."""
    lines = text.split('\n')
    if not 0 <= line < len(lines):
        return None
    m = _TASK.match(lines[line])
    if not m or not m.group(2).startswith(expect_title):
        return None
    lines[line] = lines[line].replace('[ ]', '[x]', 1)
    return '\n'.join(lines)


def append_line(content: str, title: str, line: str) -> str:
    return _attach(content if content.strip() else f'# {title}\n\n', line)


def title_from_idea(text: str) -> str:
    t = re.sub(r'[\\/:*?"<>|]', '-', text[:48]).strip()
    t = re.sub(r'[.,\s]+$', '', t).lstrip('.')
    return t or 'Idee'


# ---- Dateizugriff auf den Notizen-Ordner ----

def _read(root: Path, rel: str) -> str:
    try:
        return (root / rel).read_text(encoding='utf-8')
    except OSError:
        return ''


def _write(root: Path, rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + '.tmp')
    tmp.write_text(text, encoding='utf-8')
    tmp.replace(p)


def ideas(root: Path) -> list[Idea]:
    return parse_ideas(_read(root, INBOX_FILE))


def add_idea(root: Path, text: str) -> bool:
    if not text.strip():
        return False
    _write(root, INBOX_FILE, append_idea(_read(root, INBOX_FILE), text, stamp()))
    return True


def drop_idea(root: Path, raw: str) -> None:
    _write(root, INBOX_FILE, remove_idea(_read(root, INBOX_FILE), raw))


def all_tasks(root: Path, paths: list[str]) -> list[Task]:
    out = []
    for p in paths:
        for t in parse_tasks(_read(root, p)):
            t.path = p
            out.append(t)
    return out


def tick_task(root: Path, t: Task) -> bool:
    new = check_task(_read(root, t.path), t.line, t.title)
    if new is None:
        return False
    _write(root, t.path, new)
    return True


def add_task(root: Path, area: str, title: str, minutes: int | None, due: str | None) -> str:
    rel = f'{area}/Aufgaben.md'
    _write(root, rel, append_line(_read(root, rel), 'Aufgaben', task_line(title, minutes, due)))
    return rel


def note_from_idea(root: Path, area: str, text: str) -> str:
    base = title_from_idea(text)
    name, n = base, 2
    while (root / area / f'{name}.md').exists():
        name, n = f'{base} {n}', n + 1
    rel = f'{area}/{name}.md'
    _write(root, rel, f'# {name}\n\n{text}\n')
    return rel
