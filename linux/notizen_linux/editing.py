"""Bearbeitungsregeln des Editors als reine Funktionen (gleiches Verhalten wie in der Android-App)."""

from __future__ import annotations

import re

_LIST = re.compile(r'^(\s*)(- \[[ xX]\] |- |\* |\d+\. )')
_BOX = re.compile(r'^(\s*[-*] )\[( |x|X)\]')


def continue_list(line: str) -> str | None:
    """Enter am Ende einer Listenzeile.

    None: keine Liste, normaler Zeilenumbruch.
    '':   leerer Punkt, die Liste endet (Zeile leeren).
    sonst: Präfix der neuen Zeile (Einrückung und nächste Aufzählung; Aufgaben beginnen offen, Nummern zählen hoch).
    """
    m = _LIST.match(line)
    if not m:
        return None
    if not line[m.end():].strip():
        return ''
    indent, marker = m.group(1), m.group(2)
    if marker.startswith('- ['):
        nxt = '- [ ] '
    elif marker[0].isdigit():
        nxt = f'{int(marker.strip().rstrip(".")) + 1}. '
    else:
        nxt = marker
    return indent + nxt


def toggle_checkbox(line: str, col: int) -> str | None:
    """Klick in Spalte `col` auf das Kästchen „[ ]“ bzw. „[x]“ einer Aufgabenzeile schaltet es um; sonst None."""
    m = _BOX.match(line)
    if not m:
        return None
    start = m.end(1)  # Position von „[“
    if not start <= col <= start + 3:
        return None
    return line[:start] + ('[ ]' if m.group(2) != ' ' else '[x]') + line[start + 3:]
