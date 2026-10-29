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

from gi.repository import GLib, Gtk  # noqa: E402

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


@step(17500)
def s5b():
    w = app.win
    w.open_note('Schule/Physik/Schnell.md')
    w.buffer.set_text('# Wecker nach Schlafphase\n\nText mit **fett**, *kursiv* und `code`.\n')
    w.buffer.place_cursor(w.buffer.get_end_iter())
    w._highlight()
    hidden = w.buffer.get_tag_table().lookup('hidden')
    at = lambda off: w.buffer.get_iter_at_offset(off).has_tag(hidden)  # noqa: E731
    text = w._text()
    check('Formatierungszeichen sind außerhalb der Cursor-Zeile versteckt', at(0) and at(1) and not at(2), f'# vorn: {at(0)} {at(1)} {at(2)}')
    b = text.index('**fett**')
    check('Fett-Sternchen versteckt, Wort sichtbar', at(b) and at(b + 1) and not at(b + 2) and at(b + 6) and at(b + 7))
    check('Code-Backticks versteckt', at(text.index('`code`')) and not at(text.index('`code`') + 1))
    w.buffer.place_cursor(w.buffer.get_iter_at_offset(3))  # Cursor in die Überschrift
    check('In der Cursor-Zeile sind die Zeichen sichtbar', not at(0) and not at(1))
    w.buffer.place_cursor(w.buffer.get_end_iter())
    check('Cursor weg: wieder versteckt', at(0))
    w.flush_save()
    saved = (w.root / 'Schule/Physik/Schnell.md').read_text(encoding='utf-8')
    check('Gespeicherte Datei behält alle Formatierungszeichen', saved == '# Wecker nach Schlafphase\n\nText mit **fett**, *kursiv* und `code`.\n', repr(saved))


@step(18200)
def s5c():
    import time
    from datetime import datetime
    from notizen_linux import dash
    w = app.win
    (w.root / 'Schule' / 'Optik' / 'Leer').mkdir(parents=True)
    w.refresh_tree()
    paths = []
    w.store.foreach(lambda m, p, it: (paths.append(m.get_value(it, 1)), False)[1])
    check('Leere Ordner erscheinen im Baum', 'Schule/Optik' in paths and 'Schule/Optik/Leer' in paths)
    check('Leere Ordner erscheinen als Karten', 'Schule/Optik' in [f for f, _ in dash.folders([], 'Schule', dash.dirs(w.root))])
    now = time.time()
    iso = lambda t: datetime.fromtimestamp(t).astimezone().isoformat()  # noqa: E731
    w.school.connect('http://127.0.0.1:1/ical/tok.ics')
    w.school.data['cache'] = {'lessons': [{'start': iso(now - 600), 'end': iso(now + 2100), 'period': 3, 'subject': 'Ph', 'teacher': 'Be', 'room': '12', 'changed': False, 'cancelled': False}]}
    w.update_lesson()
    check('Stundenkarte zeigt die laufende Stunde', w.lesson_btn.get_visible() and w.lesson_title.get_text() == 'Ph' and w.lesson_head.get_text().startswith('JETZT'), w.lesson_head.get_text())
    w.school.set_folder('Ph', 'Schule/Physik')
    w.on_lesson(False)
    check('Tippen auf die Karte öffnet den Fach-Ordner', w.title.get_title() == 'Physik')
    w.school.disconnect()
    w.update_lesson()
    check('Ohne Verbindung keine Karte', not w.lesson_btn.get_visible())


@step(18600)
def s5d():
    w = app.win
    pop = w.tree_menu('Schule/Physik', 40, 40)
    labels = []
    child = pop.get_child().get_first_child()
    while child:
        if isinstance(child, Gtk.Button):
            labels.append(child.get_label())
        child = child.get_next_sibling()
    check('Rechtsklick-Menü bietet Neue Notiz und Neuer Ordner', labels == ['Neue Notiz hier …', 'Neuer Ordner hier …'], str(labels))
    pop.popdown()
    # Zeile unter der Maus: die erste Zeile des Baums liegt oben links
    p = w._path_at(30, 12)
    it = w.store.get_iter(p) if p is not None else None
    check('Baumzeile unter der Maus wird gefunden', it is not None and w.store.get_value(it, 1) in w._rows, str(p))
    check('Zwischen den Zeilen (weit unten) trifft der Klick keine Zeile', w._path_at(30, 5000) is None)


@step(18800)
def s5e():
    from gi.repository import Gdk
    w = app.win
    w.open_note('Schule/Physik/Schnell.md')
    w.buffer.set_text('- Punkt eins')
    w.buffer.place_cursor(w.buffer.get_end_iter())
    handled = w._on_text_key(None, Gdk.KEY_Return, 0, 0)
    check('Enter in einer Liste setzt sie fort', handled and w._text() == '- Punkt eins\n- ', repr(w._text()))
    handled = w._on_text_key(None, Gdk.KEY_Return, 0, 0)
    check('Enter auf leerem Punkt beendet die Liste', handled and w._text() == '- Punkt eins\n', repr(w._text()))
    w.buffer.set_text('Kein Listentext')
    w.buffer.place_cursor(w.buffer.get_end_iter())
    check('Außerhalb von Listen bleibt Enter normal', not w._on_text_key(None, Gdk.KEY_Return, 0, 0))
    # Umbenennen und Löschen
    w.flush_save()
    (w.root / 'Schule/Physik/.ink').mkdir(parents=True, exist_ok=True)
    (w.root / 'Schule/Physik/.ink/_page_Schnell.json').write_text('{}')
    w.pins = ['Schule/Physik/Schnell.md']
    new = w._do_rename('Schule/Physik/Schnell.md', False, 'Umbenannt')
    check('Notiz umbenannt, Pin und offene Notiz folgen', new == 'Schule/Physik/Umbenannt.md' and w.current == new and new in w.pins and (w.root / new).exists())
    check('Zeichenebene folgt dem Namen', (w.root / 'Schule/Physik/.ink/_page_Umbenannt.json').exists() and not (w.root / 'Schule/Physik/.ink/_page_Schnell.json').exists())
    check('Name schon vergeben wird abgelehnt', w._do_rename('Schule/Physik/Umbenannt.md', False, 'Elektrisches Feld') is None)
    new_dir = w._do_rename('Schule/Optik', True, 'Licht')
    check('Ordner umbenannt', new_dir == 'Schule/Licht' and (w.root / 'Schule/Licht/Leer').is_dir())
    w._do_delete('Schule/Licht', True)
    check('Ordner gelöscht', not (w.root / 'Schule/Licht').exists())
    w._do_delete('Schule/Physik/Umbenannt.md', False)
    check('Notiz samt Zeichenebene gelöscht, Pin weg, Ansicht zurück', not (w.root / 'Schule/Physik/Umbenannt.md').exists() and not (w.root / 'Schule/Physik/.ink/_page_Umbenannt.json').exists() and not w.pins and w.current is None)


@step(18900)
def s5f():
    w = app.win
    areas = []
    i = 0
    while (r := w.nav.get_row_at_index(i)) is not None:
        areas.append(r.area)
        i += 1
    check('Oben nur noch Übersicht, Inbox, Aufgaben, Rückblick (keine Bereichs-Tabs)', areas == ['', '!ideen', '!aufgaben', '!rueckblick'], str(areas))
    w.show_area('Schule')
    _m, it = w.tree.get_selection().get_selected()
    check('Der gezeigte Ordner ist im Baum markiert', it is not None and w.store.get_value(it, 1) == 'Schule')
    w.show_area('')
    check('Übersicht: im Baum ist nichts markiert', w.tree.get_selection().get_selected()[1] is None)


@step(18950)
def s5g():
    from notizen_linux import ink, ink_ui
    w = app.win
    w.open_note('Schule/Mathe/Ableitung.md')
    w.insert_drawing()
    ids = [m.group(1) for ln in w._text().split('\n') if (m := ink.BLOCK.match(ln))]
    check('Zeichenblock steht in der Notiz', len(ids) == 1 and w.ink_win is not None, w._text()[-40:])
    win = w.ink_win
    c = win.canvas
    c.pen_down(100, 100, 0.4)
    c.pen_move(200, 150, 0.8)
    c.pen_up()
    d = w.root / 'Schule/Mathe/.ink'
    check('Strich liegt als JSON und SVG neben der Notiz', (d / f'{ids[0]}.json').exists() and (d / f'{ids[0]}.svg').exists())
    win.set_tool('marker')
    win.set_color('#2f6fed')
    c.pen_down(100, 300)
    c.pen_move(400, 300)
    c.pen_up()
    check('Marker mit Farbe gespeichert', len(ink.load(d.parent, ids[0]).strokes) == 2 and ink.load(d.parent, ids[0]).strokes[1].tool == 'marker')
    win.undo()
    check('Rückgängig nimmt den letzten Strich', len(ink.load(d.parent, ids[0]).strokes) == 1)
    win.extend()
    check('Seite lässt sich verlängern', ink.load(d.parent, ids[0]).height == 900)
    win.close()
    w._sync_ink()
    check('Vorschau erscheint in der Notiz', len(w._ink_widgets) == 1 and isinstance(w._ink_widgets[0], ink_ui.InkPreview) and len(w._ink_widgets[0].doc.strokes) == 1)
    check('Gespeicherte Notiz behält den Block', ids[0] in w._text())


@step(18980)
def s5h():
    from unittest import mock
    from notizen_linux import ideas_ui
    said = []
    entry = __import__('gi').repository.Gtk.Entry()
    with mock.patch.dict(os.environ, {'NOTIZEN_STT': ''}), mock.patch('notizen_linux.dictate.stt_argv', return_value=None):
        btn = ideas_ui.mic_button(entry, said.append)
        btn.set_active(True)
    check('Mikrofon ohne Spracherkennung: Hinweis statt Aufnahme', not btn.get_active() and said and 'Spracherkennung' in said[-1], str(said))
    check('Eingabezeile hat den Mikrofon-Knopf', ideas_ui.capture_card(app.win) is not None)


@step(19000)
def s6():
    from notizen_linux import ideas_ui, inbox
    w = app.win
    w.show_area('')
    inbox.add_idea(w.root, 'GlutTube: Wiedergabelisten nach Stimmung')
    inbox.add_idea(w.root, 'Physik: Kondensator-Versuch wiederholen')
    w.after_ideas_change()
    check('Ideen liegen in der Inbox', len(inbox.ideas(w.root)) == 2)
    check('Inbox-Notiz taucht nicht im Baum auf', 'Inbox' not in [n.title for n in __import__('notizen_linux.dash', fromlist=['scan']).scan(w.root)])
    rows = {}
    i = 0
    while (r := w.nav.get_row_at_index(i)) is not None:
        rows[r.area] = r
        i += 1
    check('Zähler der Inbox in der Seitenleiste', rows['!ideen'].count.get_text() == '2' and rows['!ideen'].count.get_visible())
    w.show_area('!ideen')
    check('Inbox-Seite baut sich auf', w.title.get_title() == 'Inbox')
    first = inbox.ideas(w.root)[0]
    inbox.add_task(w.root, 'Schule', first.text, 45, inbox.parse_due('Freitag'))
    inbox.drop_idea(w.root, first.raw)
    w.after_ideas_change()
    check('Aufgabe angelegt, Idee aus der Inbox entfernt', len(inbox.ideas(w.root)) == 1 and ideas_ui.counts(w)[1] >= 1)
    w.show_area('!aufgaben')
    check('Aufgaben-Seite baut sich auf', w.title.get_title() == 'Aufgaben')
    t = [x for x in inbox.all_tasks(w.root, [n.path for n in __import__('notizen_linux.dash', fromlist=['scan']).scan(w.root)]) if x.path == 'Schule/Aufgaben.md'][0]
    check('Aufgabe trägt Dauer und Frist', t.minutes == 45 and t.due is not None, f'{t.title} {t.minutes} {t.due}')
    check('Abhaken schreibt in die Notiz', inbox.tick_task(w.root, t) and '- [x]' in (w.root / 'Schule/Aufgaben.md').read_text(encoding='utf-8'))
    w.show_area('!rueckblick')
    check('Rückblick baut sich auf', w.title.get_title() == 'Rückblick')
    w.show_area('')
    check('Zurück in der Übersicht', w.title.get_title() == 'Übersicht')
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
