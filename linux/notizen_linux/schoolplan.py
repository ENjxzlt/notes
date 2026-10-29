"""Stundenplan aus Slotwise (das ihn von vpmobil24 holt), wie in der Android-App.

Verbunden wird mit dem Abo-Link aus Slotwise → Einstellungen → Kalender („…/ical/<Token>.ics“); der Token reicht auch für
`/school/<Token>.json`. Die Stunden werden zwischengespeichert, damit die Karte auch offline funktioniert.
"""

from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

USER_AGENT = 'notizen-linux/0.1'  # Cloudflare sperrt den Standardwert von Python
_LINK = re.compile(r'^(https?://[^/]+)(?:/.*)?/ical/([A-Za-z0-9_-]+)(?:\.ics)?/?$')


@dataclass
class Lesson:
    start: float
    end: float
    period: int
    subject: str
    teacher: str
    room: str
    changed: bool
    cancelled: bool


@dataclass
class Card:
    head: str
    title: str
    sub: str


def parse_link(link: str) -> tuple[str, str] | None:
    """Abo-Link einlesen: Basisadresse und Token."""
    m = _LINK.match(re.sub(r'^webcals?://', 'https://', link.strip()))
    return (m.group(1), m.group(2)) if m else None


def fetch(base: str, token: str, today: date | None = None, timeout: float = 15) -> dict:
    """Stunden der nächsten 7 Tage von Slotwise holen."""
    today = today or date.today()
    url = f'{base}/school/{token}.json?from={today}&to={today + timedelta(days=7)}'
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        e.close()
        raise RuntimeError(f'Slotwise antwortet mit HTTP {e.code}') from e
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        raise RuntimeError(f'Slotwise nicht erreichbar: {getattr(e, "reason", e)}') from e


def _ts(s: str) -> float:
    return datetime.fromisoformat(s.replace('Z', '+00:00')).timestamp()


def lessons_of(data: dict | None) -> list[Lesson]:
    out = []
    for o in (data or {}).get('lessons', []):
        try:
            out.append(Lesson(_ts(o['start']), _ts(o['end']), int(o.get('period') or 0), o.get('subject', ''), o.get('teacher', ''),
                              o.get('room', ''), bool(o.get('changed')), bool(o.get('cancelled'))))
        except (KeyError, ValueError, TypeError):
            continue
    return out


def upcoming(lessons: list[Lesson], now: float | None = None) -> tuple[Lesson, bool] | None:
    """Laufende oder nächste Stunde von heute (Entfall übersprungen); None, wenn heute nichts mehr kommt."""
    now = datetime.now().timestamp() if now is None else now
    today = datetime.fromtimestamp(now).date()
    cand = [lsn for lsn in lessons if not lsn.cancelled and lsn.end > now and datetime.fromtimestamp(lsn.start).date() == today]
    if not cand:
        return None
    nxt = min(cand, key=lambda x: x.start)
    return nxt, nxt.start <= now


def card(lsn: Lesson, running: bool) -> Card:
    t = lambda ts: datetime.fromtimestamp(ts).strftime('%H:%M')  # noqa: E731
    head = ('Jetzt' if running else 'Als Nächstes') + (f' · {lsn.period}. Std' if lsn.period > 0 else '') + f' · {t(lsn.start)}–{t(lsn.end)}'
    sub = ' · '.join(x for x in (f'Raum {lsn.room}' if lsn.room else '', lsn.teacher if lsn.teacher and lsn.teacher != '---' else '', 'Vertretung' if lsn.changed else '') if x)
    return Card(head, lsn.subject, sub)


class School:
    """Verbindung, Zwischenspeicher und Zuordnung Fach → Ordner in einer Datei (nur für den Benutzer lesbar)."""

    def __init__(self, file: Path):
        self.file = Path(file)
        try:
            self.data = json.loads(self.file.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            self.data = {}

    def _save(self) -> None:
        self.file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.file.with_suffix('.tmp')
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(self.data, f, ensure_ascii=False)
        os.replace(tmp, self.file)

    @property
    def connected(self) -> bool:
        return bool(self.data.get('token') and self.data.get('base'))

    def link(self) -> str:
        return f'{self.data["base"]}/ical/{self.data["token"]}.ics' if self.connected else ''

    def connect(self, link: str) -> bool:
        parsed = parse_link(link)
        if not parsed:
            return False
        self.data.update(base=parsed[0], token=parsed[1])
        self.data.pop('cache', None)
        self._save()
        return True

    def disconnect(self) -> None:
        for k in ('base', 'token', 'cache'):
            self.data.pop(k, None)
        self._save()

    def refresh(self) -> int:
        """Stunden holen und zwischenspeichern; liefert die Anzahl. Wirft RuntimeError bei Fehlern."""
        if not self.connected:
            return 0
        self.data['cache'] = fetch(self.data['base'], self.data['token'])
        self._save()
        return len(self.data['cache'].get('lessons', []))

    def lessons(self) -> list[Lesson]:
        return lessons_of(self.data.get('cache'))

    def next_card(self, now: float | None = None) -> tuple[Lesson, Card] | None:
        up = upcoming(self.lessons(), now) if self.connected else None
        return (up[0], card(*up)) if up else None

    # Zuordnung Fach-Kürzel → Ordner (relativ zum Notizen-Ordner)
    def folder_for(self, root: Path, subject: str) -> str | None:
        rel = (self.data.get('folders') or {}).get(subject, '')
        return rel if rel and (Path(root) / rel).is_dir() else None

    def set_folder(self, subject: str, rel: str) -> None:
        self.data.setdefault('folders', {})[subject] = rel
        self._save()
