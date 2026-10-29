"""Rauchtest der Oberfläche gegen einen laufenden Notizen-Server (nicht Teil von `unittest discover`).

    NOTIZEN_SERVER=http://127.0.0.1:3099 NOTIZEN_TOKEN=... GDK_BACKEND=broadway BROADWAY_DISPLAY=:5 python3 tests/gui_smoke.py

Steuert die App direkt: Notiz öffnen, formatieren, speichern, Abgleich in beide Richtungen.
"""
import os
import sys
import tempfile
from pathlib import Path

tmp = Path(tempfile.mkdtemp(prefix='notizen-gui-'))
os.environ['XDG_CONFIG_HOME'] = str(tmp / 'cfg')
os.environ['XDG_DATA_HOME'] = str(tmp / 'data')
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from notizen_linux import config  # noqa: E402

SERVER, TOKEN = os.environ['NOTIZEN_SERVER'], os.environ['NOTIZEN_TOKEN']
config.save(config.Config(SERVER, TOKEN, str(tmp / 'notizen')))

from gi.repository import GLib  # noqa: E402

from notizen_linux.app import App  # noqa: E402
from notizen_linux.sync import Client  # noqa: E402

REL = 'Schule/Mathe/Ableitung.md'
client = Client(SERVER, TOKEN)
results = []


def check(name, ok, extra=''):
    results.append(ok)
    print(('OK   ' if ok else 'FEHLT'), name, extra)


app = App()
steps = []


def step(delay):
    def deco(fn):
        steps.append((delay, fn))
        return fn
    return deco


@step(2500)
def s1():
    w = app.win
    check('Baum zeigt Notizen', w.store is not None and len(w.store) >= 3)
    w.open_note(REL)
    check('Notiz geöffnet', 'Ableitung' in w._text(), repr(w._text()[:30]))
    w.buffer.place_cursor(w.buffer.get_end_iter())
    w.buffer.insert_at_cursor('\nNeue Aufgabe')
    w.line_prefix('- [ ] ')
    check('Aufgaben-Präfix gesetzt', w._text().splitlines()[-1] == '- [ ] Neue Aufgabe', repr(w._text().splitlines()[-1]))
    w.line_prefix('- [ ] ')
    check('Präfix lässt sich wieder abschalten', w._text().splitlines()[-1] == 'Neue Aufgabe')
    w.line_prefix('## ')
    s = w.buffer.get_iter_at_line(w.buffer.get_line_count() - 1)[1]
    s.forward_chars(3)  # hinter „## “
    e = w.buffer.get_end_iter()
    w.buffer.select_range(s.copy(), e)
    w.wrap('**')
    check('Fett umschließt Auswahl', w._text().splitlines()[-1] == '## **Neue Aufgabe**', repr(w._text().splitlines()[-1]))
    check('Notiz ist als geändert markiert', w.dirty)


@step(5000)
def s2():
    w = app.win
    local = (w.root / REL).read_text(encoding='utf-8')
    check('Autosave schreibt lokal', '## **Neue Aufgabe**' in local)
    got = client.get(REL)
    check('Änderung ist auf dem Server', got is not None and b'## **Neue Aufgabe**' in got[0])
    check('Nicht mehr als geändert markiert', not w.dirty)
    # Änderung von einem anderen Gerät
    client.put(REL, b'# Ableitung\n\nvon draussen geaendert\n', got[1])


@step(9000)
def s3():
    w = app.win
    check('Fremde Änderung wird übernommen (Live-Sync)', 'von draussen' in w._text(), repr(w._text()[:50]))
    # offene, ungesicherte Änderung trifft auf fremde Änderung
    w.buffer.place_cursor(w.buffer.get_end_iter())
    w.buffer.insert_at_cursor('MEINE UNGESICHERTE ZEILE')
    got = client.get(REL)
    client.put(REL, b'# Ableitung\n\nzweite fremde Aenderung\n', got[1])


@step(14000)
def s4():
    w = app.win
    names = [p.name for p in (w.root / 'Schule/Mathe').iterdir()]
    kopie = [n for n in names if 'Konflikt' in n]
    check('Ungesicherte Eingabe bleibt als Konfliktkopie erhalten', bool(kopie) and 'MEINE UNGESICHERTE ZEILE' in (w.root / 'Schule/Mathe' / kopie[0]).read_text(encoding='utf-8'), str(names))
    check('Offene Notiz zeigt die Serverfassung', 'zweite fremde' in w._text())
    w.new_note  # Dialoge werden hier nicht geöffnet
    w.show_area('Schule')
    check('Bereichsseite baut sich auf', w.title.get_title() == 'Schule')
    w.show_area('')
    check('Übersicht baut sich auf', w.title.get_title() == 'Übersicht')
    app.quit()


def schedule(*_):
    for delay, fn in steps:
        def run(f=fn):
            try:
                f()
            except Exception as e:  # noqa: BLE001
                import traceback
                traceback.print_exc()
                results.append(False)
                print('FEHLT Ausnahme', type(e).__name__, e)
                app.quit()
            return GLib.SOURCE_REMOVE
        GLib.timeout_add(delay, run)


app.connect('activate', schedule)
app.run([])
print(f'{sum(results)}/{len(results)} Prüfungen bestanden')
sys.exit(0 if results and all(results) else 1)
