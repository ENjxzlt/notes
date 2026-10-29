"""Verbindungsdaten. Das Token liegt nur in dieser Datei (Rechte 600), nie in Logs oder Fehlermeldungen."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path

CONFIG_DIR = Path(os.environ.get('XDG_CONFIG_HOME', Path.home() / '.config')) / 'notizen-linux'
DATA_DIR = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local' / 'share')) / 'notizen-linux'
CONFIG_FILE = CONFIG_DIR / 'config.json'
STATE_FILE = DATA_DIR / 'state.db'


@dataclass
class Config:
    server: str = ''
    token: str = ''
    root: str = str(Path.home() / 'Notizen')
    stt_command: str = ''  # Umschrift fürs Diktieren, {wav} = Aufnahme (siehe dictate.py)

    @property
    def ready(self) -> bool:
        return bool(self.server and self.token and self.root)


def load() -> Config:
    try:
        raw = json.loads(CONFIG_FILE.read_text(encoding='utf-8'))
        return Config(**{k: str(v) for k, v in raw.items() if k in Config.__dataclass_fields__})
    except (OSError, ValueError, TypeError):
        return Config()


def save(cfg: Config) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix('.tmp')
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump(asdict(cfg), f, indent=2)
    os.replace(tmp, CONFIG_FILE)


NAV_FILE = DATA_DIR / 'nav.json'
SCHOOL_FILE = CONFIG_DIR / 'school.json'  # Abo-Token von Slotwise: wie die Verbindungsdaten nur für den Benutzer lesbar


def load_nav() -> dict:
    """Angepinnte und zuletzt geöffnete Notizen (nur auf diesem PC)."""
    try:
        raw = json.loads(NAV_FILE.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        raw = {}
    return {k: [p for p in raw.get(k, []) if isinstance(p, str)] for k in ('pins', 'recents')}


def save_nav(state: dict) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = NAV_FILE.with_suffix('.tmp')
    tmp.write_text(json.dumps(state, ensure_ascii=False), encoding='utf-8')
    os.replace(tmp, NAV_FILE)


def normalize_server(url: str) -> str:
    url = url.strip().rstrip('/')
    if url and '://' not in url:
        url = 'https://' + url
    return url
