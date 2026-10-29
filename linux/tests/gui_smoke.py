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
os.environ['NOTIZEN_APP_ID'] = 'online.minihomelab.NotizenLinuxTest'
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


for rel, body in (('Schule/Physik/Elektrisches Feld.md', '# Elektrisches Feld\n'), ('Schule/Physik/Magnetismus.md', '# Magnetismus\n'), ('Schule/Mathe/Ableitung.md', '# Ableitung\n\n- [ ] Übungsblatt\n'), ('Projekte/Notizen-App.md', '# Notizen-App\n'), ('Alltag/Einkauf.md', '# Einkauf\n')):
    try:
        client.put(rel, body.encode(), 0)
    except Exception:  # noqa: BLE001  (gibt es schon: gut)
        pass

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
    w.show_area('Schule')
    check('Bereichsseite baut sich auf', w.title.get_title() == 'Schule')
    w.show_area('')
    check('Übersicht baut sich auf', w.title.get_title() == 'Übersicht')


PHYS = 'Schule/Physik/Elektrisches Feld.md'


@step(16000)
def s5():
    w = app.win
    items = w.qs_items('elek', '')
    check('Schnellwechsel findet nach Titel zuerst', items[0]['title'] == 'Elektrisches Feld' and items[0]['parts'] == ('', 'Elek', 'trisches Feld'), str([i['title'] for i in items]))
    check('… und bietet „Neue Notiz“ an', items[-1]['kind'] == 'create' and items[-1]['path'] == 'elek.md', items[-1]['path'])
    check('Ordnersuche mit „/“', [i['path'] for i in w.qs_items('/phy')] == ['Schule/Physik'])
    check('Bereichsfilter', all(i['area'] == 'Projekte' for i in w.qs_items('', 'Projekte') if i['kind'] == 'note'))
    w.open_note(PHYS)
    check('Pfad zeigt Ordner und Titel', w.title_stack.get_visible_child_name() == 'crumb')
    check('Blättern: nächste Notiz im Ordner', w._pg_next == 'Schule/Physik/Magnetismus.md' and w._pg_prev is None, str((w._pg_prev, w._pg_next)))
    w.pin_btn.set_active(True)
    check('Pin wird gemerkt', PHYS in w.pins)
    w._page(w._pg_next)
    check('Weiterblättern öffnet die Nachbarnotiz', w.current == 'Schule/Physik/Magnetismus.md')
    w.step_hist(-1)
    check('Zurück springt zur vorigen Notiz', w.current == PHYS, str(w.current))
    w.step_hist(1)
    check('Vor springt wieder zur nächsten', w.current == 'Schule/Physik/Magnetismus.md')
    check('Verlauf merkt die Notizen', w.recents[:2] == ['Schule/Physik/Magnetismus.md', PHYS], str(w.recents))
    saved = config.load_nav()
    check('Pins und Verlauf liegen auf der Platte', PHYS in saved['pins'] and PHYS in saved['recents'])
    w.run_item({'kind': 'create', 'title': 'Schnell', 'path': 'Schule/Physik/Schnell.md'})
    check('Neue Notiz aus dem Schnellwechsel', w.current == 'Schule/Physik/Schnell.md' and (w.root / 'Schule/Physik/Schnell.md').read_text() == '# Schnell\n\n')
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
