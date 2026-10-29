"""Abgleich eines lokalen Ordners mit dem Notizen-Server.

Gleiche Regeln wie die Android-App: Der Server ist die Wahrheit, jede Datei hat eine Revision,
geschrieben wird nur mit bekannter Revision (`base`). Bei Abweichung bleibt die lokale Fassung als
Kopie „Name (Konflikt …).md“ erhalten, nichts wird stillschweigend überschrieben.
Nur Standardbibliothek.
"""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

MAX_SIZE = 25 * 1024 * 1024
ALLOWED_EXT = {'.md', '.json', '.svg'}


class AuthError(Exception):
    pass


class Conflict(Exception):
    def __init__(self, current):
        super().__init__('Konflikt')
        self.current = current


class ServerError(Exception):
    pass


def valid_path(rel: str) -> bool:
    """Spiegelt checkPath() des Servers: nur Pfade, die er auch annimmt, werden gesendet."""
    if not rel or len(rel) > 300 or rel.startswith('/') or rel.endswith('/') or '\\' in rel:
        return False
    if any(ord(c) < 32 for c in rel):
        return False
    parts = rel.split('/')
    for seg in parts:
        if seg in ('', '.', '..'):
            return False
        if seg.startswith('.') and seg != '.ink':
            return False
        if seg.endswith('.tmp') or seg.endswith('.defekt'):
            return False
    ext = os.path.splitext(rel)[1].lower()
    if ext not in ALLOWED_EXT:
        return False
    if ext != '.md' and '.ink' not in parts[:-1]:
        return False
    return True


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Client:
    def __init__(self, base: str, token: str, timeout: float = 20):
        self.base = base.rstrip('/')
        self.token = token
        self.timeout = timeout

    def _open(self, method, path, params=None, body=None, timeout=None):
        url = f'{self.base}{path}'
        if params:
            url += '?' + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, data=body, method=method, headers={'Authorization': f'Bearer {self.token}'})
        return urllib.request.urlopen(req, timeout=timeout or self.timeout)

    def _call(self, method, path, params=None, body=None):
        try:
            with self._open(method, path, params, body) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            data = e.read()
            e.close()
            if e.code == 401:
                raise AuthError('Token ungültig') from e
            if e.code == 409:
                try:
                    cur = json.loads(data).get('current')
                except ValueError:
                    cur = None
                raise Conflict(cur) from e
            if e.code == 404:
                return 404, e.headers, data
            raise ServerError(f'Server antwortet {e.code}') from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ServerError(f'Server nicht erreichbar: {getattr(e, "reason", e)}') from e

    def changes(self, since: int) -> dict:
        _, _, data = self._call('GET', '/api/changes', {'since': since})
        return json.loads(data)

    def get(self, path: str):
        """(Inhalt, Revision, Hash) oder None, wenn es die Datei nicht (mehr) gibt."""
        status, headers, data = self._call('GET', '/api/file', {'path': path})
        if status == 404:
            return None
        return data, int(headers.get('X-Rev', 0)), headers.get('X-Hash', '')

    def put(self, path: str, data: bytes, base: int) -> dict:
        _, _, body = self._call('PUT', '/api/file', {'path': path, 'base': base}, data)
        return json.loads(body)

    def delete(self, path: str, base: int) -> dict:
        _, _, body = self._call('DELETE', '/api/file', {'path': path, 'base': base})
        return json.loads(body)

    def events(self, stop, on_change, on_state=None):
        """Blockiert: meldet Änderungen sofort (Server-Sent Events). Bei Abbruch Wiederverbindung nach 3 s, bis `stop` gesetzt ist."""
        while not stop.is_set():
            try:
                with self._open('GET', '/api/events', timeout=60) as r:
                    if on_state:
                        on_state('live')
                    event = ''
                    for raw in r:  # der Server sendet alle 25 s ein Lebenszeichen, sonst läuft der Timeout ab
                        if stop.is_set():
                            return
                        line = raw.decode('utf-8', 'replace').rstrip('\n')
                        if line.startswith('event:'):
                            event = line[6:].strip()
                        elif line == '' and event:
                            if event == 'change':
                                on_change()
                            event = ''
            except urllib.error.HTTPError as e:
                if e.code == 401 and on_state:
                    on_state('Token ungültig')
            except (urllib.error.URLError, TimeoutError, OSError):
                pass
            if on_state:
                on_state('getrennt')
            stop.wait(3)


@dataclass
class Report:
    pulled: int = 0
    pushed: int = 0
    deleted: int = 0
    conflicts: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)

    @property
    def changed(self) -> bool:
        return bool(self.pulled or self.pushed or self.deleted or self.conflicts)


class Syncer:
    def __init__(self, root: Path, client: Client, state_file: Path):
        self.root = Path(root)
        self.client = client
        self.root.mkdir(parents=True, exist_ok=True)
        Path(state_file).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(state_file), check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, rev INTEGER NOT NULL, hash TEXT NOT NULL)')
        self.db.execute('CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
        self.db.commit()

    # ---- Zustand ----

    @property
    def seq(self) -> int:
        row = self.db.execute("SELECT value FROM meta WHERE key='seq'").fetchone()
        return int(row[0]) if row else 0

    def _set_seq(self, n: int):
        self.db.execute("INSERT INTO meta (key, value) VALUES ('seq', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (str(n),))

    def _state(self, path):
        row = self.db.execute('SELECT rev, hash FROM files WHERE path=?', (path,)).fetchone()
        return {'rev': row[0], 'hash': row[1]} if row else None

    def _set_state(self, path, rev, h):
        self.db.execute('INSERT INTO files (path, rev, hash) VALUES (?, ?, ?) ON CONFLICT(path) DO UPDATE SET rev=excluded.rev, hash=excluded.hash', (path, rev, h))

    def _drop_state(self, path):
        self.db.execute('DELETE FROM files WHERE path=?', (path,))

    # ---- lokale Dateien ----

    def _abs(self, rel):
        return self.root.joinpath(*rel.split('/'))

    def _local_hash(self, rel):
        try:
            return sha256(self._abs(rel).read_bytes())
        except FileNotFoundError:
            return None

    def _write(self, rel, data: bytes, mtime_ms=None):
        p = self._abs(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + '.tmp')
        tmp.write_bytes(data)
        if mtime_ms:
            os.utime(tmp, (mtime_ms / 1000, mtime_ms / 1000))
        os.replace(tmp, p)

    def _remove(self, rel):
        p = self._abs(rel)
        p.unlink(missing_ok=True)
        # leere Ordner aufräumen, bis zur Wurzel
        d = p.parent
        while d != self.root and d.is_dir() and not any(d.iterdir()):
            d.rmdir()
            d = d.parent

    def local_files(self):
        out = []
        for dirpath, dirnames, filenames in os.walk(self.root):
            dirnames[:] = [d for d in dirnames if not d.startswith('.') or d == '.ink']
            for name in filenames:
                rel = os.path.relpath(os.path.join(dirpath, name), self.root).replace(os.sep, '/')
                if valid_path(rel):
                    out.append(rel)
        return sorted(out)

    def _conflict_copy(self, rel, data: bytes) -> str:
        """Legt die lokale Fassung als „Name (Konflikt Datum Uhrzeit).md“ ab; Name wird bei Bedarf hochgezählt."""
        stem, ext = os.path.splitext(rel)
        stamp = time.strftime('%Y-%m-%d %H-%M')
        n = 1
        while True:
            suffix = f' (Konflikt {stamp})' if n == 1 else f' (Konflikt {stamp} {n})'
            cand = f'{stem}{suffix}{ext}'
            if not self._abs(cand).exists():
                self._write(cand, data)
                return cand
            n += 1

    # ---- Abgleich ----

    def sync(self) -> Report:
        rep = Report()
        try:
            self._pull(rep)
            self._push(rep)
            self._push(rep)  # zweiter Durchgang für frisch angelegte Konfliktkopien
        except (AuthError, ServerError):
            self.db.rollback()
            raise
        finally:
            self.db.commit()
        return rep

    def _pull(self, rep: Report):
        j = self.client.changes(self.seq)
        for f in j['files']:
            self._apply_remote(f, rep)
        self._set_seq(j['seq'])
        self.db.commit()

    def _apply_remote(self, f, rep: Report):
        path = f['path']
        if not valid_path(path):
            return
        st = self._state(path)
        local = self._local_hash(path)
        if f['deleted']:
            if st is None:
                return
            if local is None:
                self._drop_state(path)
            elif local == st['hash']:
                self._remove(path)
                self._drop_state(path)
                rep.deleted += 1
            else:
                self._drop_state(path)  # lokal geändert, auf dem Server gelöscht: lokal behalten, geht als neue Datei wieder hoch
            return
        if st and st['rev'] == f['rev']:
            return  # unser eigener Stand (fehlt die Datei lokal, wartet das Löschen auf den Abgleich)
        got = self.client.get(path)
        if got is None:
            return
        data, rev, remote_hash = got
        if local is None or local == remote_hash:
            if local is None:
                self._write(path, data, f.get('mtime'))
                rep.pulled += 1
            self._set_state(path, rev, remote_hash)
        elif st and local == st['hash']:
            self._write(path, data, f.get('mtime'))
            self._set_state(path, rev, remote_hash)
            rep.pulled += 1
        else:
            keep = self._conflict_copy(path, self._abs(path).read_bytes())
            self._write(path, data, f.get('mtime'))
            self._set_state(path, rev, remote_hash)
            rep.conflicts.append(keep)

    def _push(self, rep: Report):
        local = set(self.local_files())
        for rel in sorted(local):
            try:
                p = self._abs(rel)
                if p.stat().st_size > MAX_SIZE:
                    rep.errors.append(f'{rel}: größer als 25 MB')
                    continue
                data = p.read_bytes()
                h = sha256(data)
                st = self._state(rel)
                if st and st['hash'] == h:
                    continue
                try:
                    info = self.client.put(rel, data, st['rev'] if st else 0)
                except Conflict as c:
                    self._resolve_conflict(rel, data, h, c.current, rep)
                    continue
                self._set_state(rel, info['rev'], info['hash'])
                rep.pushed += 1
            except FileNotFoundError:
                continue
        gone = [r[0] for r in self.db.execute('SELECT path FROM files').fetchall() if r[0] not in local]
        for rel in gone:
            st = self._state(rel)
            try:
                self.client.delete(rel, st['rev'])
                rep.deleted += 1
            except Conflict:
                got = self.client.get(rel)  # auf dem Server inzwischen geändert: die neue Fassung gewinnt gegen das Löschen
                if got:
                    self._write(rel, got[0])
                    self._set_state(rel, got[1], got[2])
                    rep.pulled += 1
                    continue
            self._drop_state(rel)
        self.db.commit()

    def _resolve_conflict(self, rel, data, h, current, rep: Report):
        got = self.client.get(rel)
        if got is None:  # inzwischen gelöscht: lokale Fassung bleibt, geht beim nächsten Durchgang als neue Datei hoch
            self._drop_state(rel)
            return
        rdata, rev, rhash = got
        if rhash == h:
            self._set_state(rel, rev, rhash)
            return
        keep = self._conflict_copy(rel, data)
        self._write(rel, rdata)
        self._set_state(rel, rev, rhash)
        rep.conflicts.append(keep)
