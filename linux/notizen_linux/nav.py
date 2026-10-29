"""Navigation: Suche für den Schnellwechsel, Geschwister, Blättern, Pins und Verlauf (gleiche Regeln wie die Web-Ansicht, nav.js)."""

from __future__ import annotations

from .dash import TOP_AREAS


def title_of(p: str) -> str:
    return p.rsplit('/', 1)[-1].removesuffix('.md')


def dir_of(p: str) -> str:
    return p.rsplit('/', 1)[0] if '/' in p else ''


def _key(s: str) -> str:
    return s.casefold()


def folder_counts(paths) -> dict[str, int]:
    """Anzahl der Notizen je Ordner, einschließlich aller Unterordner."""
    counts: dict[str, int] = {}
    for p in paths:
        parts = p.split('/')[:-1]
        for i in range(1, len(parts) + 1):
            f = '/'.join(parts[:i])
            counts[f] = counts.get(f, 0) + 1
    return counts


def folder_peers(paths, folder: str) -> list[dict]:
    """Ordner auf derselben Ebene wie `folder` (er selbst eingeschlossen); oben stehen die festen Bereiche zuerst."""
    parent = dir_of(folder)
    counts = folder_counts(paths)
    names = {f for f in counts if dir_of(f) == parent}
    if not parent:
        names.update(TOP_AREAS)

    def rank(f):
        return TOP_AREAS.index(f) if not parent and f in TOP_AREAS else 99

    ordered = sorted(names, key=lambda f: (rank(f), _key(f)))
    return [{'path': f, 'name': f.rsplit('/', 1)[-1], 'count': counts.get(f, 0), 'current': f == folder} for f in ordered]


def pager(paths, path: str) -> dict:
    """Position der Notiz in ihrem Ordner (nach Titel sortiert) und die Nachbarn zum Blättern."""
    d = dir_of(path)
    same = sorted((p for p in paths if dir_of(p) == d), key=lambda p: _key(title_of(p)))
    i = same.index(path) if path in same else -1
    return {
        'folder': d,
        'index': i,
        'total': len(same),
        'prev': same[i - 1] if i > 0 else None,
        'next': same[i + 1] if 0 <= i < len(same) - 1 else None,
    }


def crumbs(path: str) -> list[dict]:
    parts = path.split('/')[:-1]
    return [{'label': lb, 'path': '/'.join(parts[: i + 1])} for i, lb in enumerate(parts)]


# ---- Pins und Verlauf ----

def push_recent(lst, path, maxn=6):
    return ([path] + [p for p in lst if p != path])[:maxn]


def toggle_pin(lst, path):
    if path in lst:
        return False, [p for p in lst if p != path]
    return True, [*lst, path]


def prune(lst, has):
    return [p for p in lst if has(p)]


# ---- Schnellwechsel ----

def _hit(title: str, q: str):
    i = title.lower().find(q)
    return None if i < 0 else (title[:i], title[i:i + len(q)], title[i + len(q):])


def search(paths, mtime, text, query: str, scope: str = '', limit: int = 12) -> list[dict]:
    """Suche nach Titel, Pfad und Text. Beginnt die Eingabe mit „/“, werden nur Ordner gesucht."""
    in_scope = lambda p: not scope or p.startswith(scope + '/')  # noqa: E731
    raw = query.strip()
    area = lambda p: p.split('/')[0] if '/' in p else ''  # noqa: E731

    if raw.startswith('/'):
        q = raw[1:].strip().lower()
        counts = folder_counts([p for p in paths if in_scope(p)])
        found = sorted((f for f in counts if not q or q in f.lower()), key=_key)[:limit]
        return [
            {'kind': 'folder', 'path': f, 'title': f.rsplit('/', 1)[-1], 'dir': dir_of(f), 'area': area(f + '/x'), 'count': counts[f], 'parts': _hit(f.rsplit('/', 1)[-1], q) if q else None}
            for f in found
        ]

    q = raw.lower()
    out = []
    for p in (p for p in paths if in_scope(p)):
        title = title_of(p)
        score, via = -1, 'title'
        if not q:
            score = 0
        elif title.lower().startswith(q):
            score = 0
        elif q in title.lower():
            score = 1
        elif q in p.lower():
            score, via = 2, 'path'
        elif q in (text(p) or '').lower():
            score, via = 3, 'text'
        if score < 0:
            continue
        out.append({'kind': 'note', 'path': p, 'title': title, 'dir': dir_of(p), 'area': area(p), 'mtime': mtime(p), 'via': via, 'parts': _hit(title, q) if q else None, 'score': score})
    out.sort(key=lambda r: (r['score'], -r['mtime']))
    return out[:limit]
