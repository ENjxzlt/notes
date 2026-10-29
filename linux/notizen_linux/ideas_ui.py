"""Oberflächen für Ideen-Dump, Inbox-Durchsicht, Aufgaben und Wochenrückblick (GTK 4 / libadwaita)."""

from __future__ import annotations

import threading
from datetime import date, datetime, timedelta
from pathlib import Path

import gi

gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from . import config as cfgmod  # noqa: E402
from . import dash, dictate, inbox  # noqa: E402

AREAS = ['Schule', 'Projekte', 'Alltag']
MINUTES = [15, 30, 45, 60, 90]
SPECIAL = {'!ideen': 'Inbox', '!aufgaben': 'Aufgaben', '!rueckblick': 'Rückblick'}


def _label(text, css=(), xalign=0.0, wrap=False, ellipsize=False):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap)
    for c in css:
        lb.add_css_class(c)
    if ellipsize:
        lb.set_ellipsize(3)
    return lb


def _card(spacing=8):
    b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=spacing, margin_top=2, margin_bottom=2)
    b.add_css_class('card')
    for side in ('start', 'end', 'top', 'bottom'):
        getattr(b, f'set_margin_{side}')(2)
    inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=spacing + 4, margin_start=26, margin_end=26, margin_top=22, margin_bottom=22)
    b.append(inner)
    b.inner = inner  # type: ignore[attr-defined]
    return b


def _chips(options, active, on_pick):
    """Eine Reihe von Auswahl-Knöpfen; `on_pick(wert)` wird beim Wechsel gerufen."""
    row = Gtk.Box(spacing=6)
    group = None
    for label, value in options:
        b = Gtk.ToggleButton(label=label, group=group)
        group = group or b
        b.add_css_class('pill')
        b.set_active(value == active)
        b.connect('toggled', lambda btn, v=value: btn.get_active() and on_pick(v))
        row.append(b)
    return row


def _paths(win) -> list[str]:
    return [n.path for n in dash.scan(win.root)]


def counts(win) -> tuple[int, int]:
    """(Ideen in der Inbox, offene Aufgaben) für die Seitenleiste."""
    return len(inbox.ideas(win.root)), len(inbox.all_tasks(win.root, _paths(win)))


# ---- Eingabezeile und Inbox-Karte in Übersicht und Bereichen ----

def mic_button(entry, say, configured=''):
    """Mikrofon-Knopf: erster Klick nimmt auf, zweiter schreibt die Umschrift ans Ende des Feldes. `say(text)` zeigt Meldungen."""
    btn = Gtk.ToggleButton(icon_name='audio-input-microphone-symbolic', tooltip_text='Diktieren (Klick startet, nochmal Klick beendet)')
    state = {'rec': None, 'busy': False}

    def toggled(b):
        if state['busy']:
            return
        if b.get_active():
            ok, hint = dictate.status(configured)
            if not ok:
                b.set_active(False)
                say(hint)
                return
            try:
                state['rec'] = dictate.Recording()
            except (RuntimeError, OSError) as e:
                b.set_active(False)
                say(str(e))
                return
            b.add_css_class('destructive-action')
            say('Aufnahme läuft … nochmal aufs Mikrofon klicken zum Beenden.')
            return
        b.remove_css_class('destructive-action')
        rec, state['rec'] = state['rec'], None
        if rec is None:
            return
        wav = rec.stop()
        state['busy'] = True
        b.set_sensitive(False)
        say('Schreibe mit …')

        def work():
            try:
                text, err = dictate.transcribe(wav, configured), None
            except RuntimeError as e:
                text, err = '', str(e)

            def done():
                state['busy'] = False
                b.set_sensitive(True)
                if err:
                    say(err)
                elif text:
                    cur = entry.get_text().rstrip()
                    entry.set_text((cur + ' ' if cur else '') + text)
                    entry.set_position(-1)
                    say('Diktiert – prüfen und mit Enter festhalten.')
                else:
                    say('Nichts verstanden.')
                return GLib.SOURCE_REMOVE

            GLib.idle_add(done)

        threading.Thread(target=work, daemon=True).start()

    btn.connect('toggled', toggled)
    return btn


def capture_card(win):
    card = _card()
    row = Gtk.Box(spacing=8)
    entry = Gtk.Entry(placeholder_text='Idee festhalten …', hexpand=True)
    entry.set_size_request(-1, 44)
    btn = Gtk.Button(label='Festhalten')
    btn.add_css_class('suggested-action')

    def submit(*_):
        if inbox.add_idea(win.root, entry.get_text()):
            entry.set_text('')
            win.toast('Festgehalten')
            win.after_ideas_change()

    entry.connect('activate', submit)
    btn.connect('clicked', submit)
    row.append(entry)
    row.append(mic_button(entry, win.toast, cfgmod.load().stt_command))
    row.append(btn)
    card.inner.append(row)
    card.inner.append(_label('Landet in der Inbox, sortiert wird bei der Durchsicht. Strg+Alt+I öffnet das Fenster überall in der App.', ['caption', 'dim-label'], wrap=True))
    return card


def inbox_card(win):
    ideas = inbox.ideas(win.root)
    if not ideas:
        return None
    card = _card()
    head = Gtk.Box()
    head.append(_label(f'Inbox · {len(ideas)} {"neue Idee" if len(ideas) == 1 else "neue Ideen"}', ['heading'], ))
    head.append(Gtk.Box(hexpand=True))
    head.append(_label('Durchsicht am Sonntag', ['caption', 'dim-label']))
    card.inner.append(head)
    for i in reversed(ideas[-4:]):
        r = Gtk.Box(spacing=12, margin_top=4)
        r.append(Gtk.Label(label=i.text, xalign=0, hexpand=True, ellipsize=3))
        r.append(_label(i.stamp[11:], ['mono', 'caption', 'dim-label']))
        card.inner.append(r)
    go = Gtk.Button(label='Jetzt durchsehen', halign=Gtk.Align.START, margin_top=6)
    go.add_css_class('suggested-action')
    go.connect('clicked', lambda *_: win.show_area('!ideen'))
    card.inner.append(go)
    return card


# ---- Seiten ----

def build(win, box, area: str) -> None:
    box.append(_header(area))
    {'!ideen': _ideas_page, '!aufgaben': _tasks_page, '!rueckblick': _review_page}[area](win, box)


def _header(area: str):
    head = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_bottom=6)
    eyebrow = {'!ideen': 'WOCHENDURCHSICHT · INBOX', '!aufgaben': 'ALLE NOTIZEN', '!rueckblick': ''}[area]
    if eyebrow:
        head.append(_label(eyebrow, ['eyebrow']))
    return head


def _ideas_page(win, box):
    st = win.istate
    ideas = inbox.ideas(win.root)
    if not ideas:
        box.get_first_child().append(_label('Inbox ist leer', ['title-1']))
        box.append(_label('Neue Ideen landen hier. In der Übersicht oder mit Strg+Alt+I festhalten.', ['dim-label'], wrap=True))
        go = Gtk.Button(label='Zur Übersicht', halign=Gtk.Align.START)
        go.connect('clicked', lambda *_: win.show_area(''))
        box.append(go)
        return
    st['idx'] = max(0, min(st['idx'], len(ideas) - 1))
    idea = ideas[st['idx']]
    box.get_first_child().append(_label(f'Idee {st["idx"] + 1} von {len(ideas)}', ['title-1']))
    bar = Gtk.ProgressBar(fraction=(st['idx'] + 1) / len(ideas))
    box.append(bar)

    card = _card()
    card.inner.append(_label(idea.stamp, ['mono', 'caption', 'dim-label']))
    text = _label(idea.text, ['title-2'], wrap=True)
    text.set_selectable(True)
    card.inner.append(text)
    box.append(card)

    act = _card(10)
    act.inner.append(_label('Zur Aufgabe machen', ['heading']))
    act.inner.append(_label('Bereich', ['caption', 'dim-label']))
    act.inner.append(_chips([(a, a) for a in AREAS], st['area'], lambda v: st.__setitem__('area', v)))
    act.inner.append(_label('Geschätzte Dauer', ['caption', 'dim-label']))
    act.inner.append(_chips([(f'{m} min', m) for m in MINUTES], st['min'], lambda v: st.__setitem__('min', v)))
    act.inner.append(_label('Frist (optional, z. B. Freitag oder 3.11.)', ['caption', 'dim-label']))
    due = Gtk.Entry(text=st['due'], width_chars=20, halign=Gtk.Align.START)
    due.connect('changed', lambda e: st.__setitem__('due', e.get_text()))
    act.inner.append(due)

    def redraw():
        GLib.idle_add(lambda: (win.build_dash(), GLib.SOURCE_REMOVE)[1])

    def make_task(*_):
        parsed = inbox.parse_due(st['due'])
        if st['due'].strip() and not parsed:
            return win.toast('Frist nicht verstanden: Freitag, morgen oder 3.11.')
        rel = inbox.add_task(win.root, st['area'], idea.text, st['min'], parsed)
        inbox.drop_idea(win.root, idea.raw)
        st['due'] = ''
        win.toast(f'Aufgabe in {rel}')
        win.after_ideas_change()

    def make_note(*_):
        rel = inbox.note_from_idea(win.root, st['area'], idea.text)
        inbox.drop_idea(win.root, idea.raw)
        win.toast(f'Notiz: {rel}')
        win.after_ideas_change()

    def later(*_):
        st['idx'] = (st['idx'] + 1) % len(ideas)
        redraw()

    def delete(*_):
        dlg = Adw.AlertDialog(heading='Idee löschen?', body=idea.text)
        dlg.add_response('cancel', 'Abbrechen')
        dlg.add_response('ok', 'Löschen')
        dlg.set_response_appearance('ok', Adw.ResponseAppearance.DESTRUCTIVE)

        def done(d, res):
            if d.choose_finish(res) == 'ok':
                inbox.drop_idea(win.root, idea.raw)
                win.after_ideas_change()

        dlg.choose(win, None, done)

    buttons = Gtk.Box(spacing=8, margin_top=6)
    for label, fn, css in (('Aufgabe anlegen', make_task, 'suggested-action'), ('Als Notiz', make_note, None)):
        b = Gtk.Button(label=label)
        if css:
            b.add_css_class(css)
        b.connect('clicked', fn)
        buttons.append(b)
    buttons.append(Gtk.Box(hexpand=True))
    for label, fn, css in (('Später', later, None), ('Löschen', delete, 'destructive-action')):
        b = Gtk.Button(label=label)
        if css:
            b.add_css_class(css)
        b.connect('clicked', fn)
        buttons.append(b)
    act.inner.append(buttons)
    box.append(act)

    nav = Gtk.Box(spacing=8)
    for label, step in (('‹ Vorige', -1), ('Nächste ›', 1)):
        b = Gtk.Button(label=label)
        b.connect('clicked', lambda _b, s=step: (st.__setitem__('idx', (st['idx'] + s) % len(ideas)), redraw()))
        nav.append(b)
    box.append(nav)


def _meta(t: inbox.Task) -> str:
    return ' · '.join(x for x in (f'~{t.minutes} min' if t.minutes is not None else '', f'bis {t.due}' if t.due else '') if x)


def _task_row(win, t: inbox.Task, check=True):
    row = Adw.ActionRow(title=t.title, subtitle=Path(t.path).stem, use_markup=False, activatable=True)
    if check:
        cb = Gtk.CheckButton(valign=Gtk.Align.CENTER)

        def toggled(btn):
            if btn.get_active():
                ok = inbox.tick_task(win.root, t)
                win.toast(f'Erledigt: {t.title}' if ok else 'Aufgabe hat sich geändert, bitte die Notiz öffnen')
                win.after_ideas_change()

        cb.connect('toggled', toggled)
        row.add_prefix(cb)
    meta = _meta(t)
    if meta:
        row.add_suffix(_label(meta, ['mono', 'caption', 'dim-label']))
    row.connect('activated', lambda _r, p=t.path: win.open_note(p))
    return row


def _tasks_page(win, box):
    st = win.istate
    tasks = inbox.all_tasks(win.root, [p for p in _paths(win)])
    box.get_first_child().append(_label('Aufgaben', ['title-1']))
    opts = [(f'Alle {len(tasks)}', '')] + [(f'{a} {sum(t.path.startswith(a + "/") for t in tasks)}', a) for a in AREAS]

    def pick(v):
        st['filter'] = v
        GLib.idle_add(lambda: (win.build_dash(), GLib.SOURCE_REMOVE)[1])

    box.append(_chips(opts, st['filter'], pick))
    shown = [t for t in tasks if not st['filter'] or t.path.startswith(st['filter'] + '/')]
    if not shown:
        box.append(_label('Keine offenen Aufgaben.', ['dim-label']))
        return
    groups: dict[str, list[inbox.Task]] = {}
    for t in shown:
        groups.setdefault(t.path.split('/')[0] if '/' in t.path else 'Ohne Bereich', []).append(t)
    for g in sorted(groups, key=lambda x: (AREAS.index(x) if x in AREAS else 99, x)):
        grp = Adw.PreferencesGroup(title=g)
        for t in groups[g]:
            grp.add(_task_row(win, t))
        box.append(grp)
    box.append(_label('Aufgaben sind alle „- [ ]“ aus deinen Notizen; Abhaken schreibt „- [x]“ in die Notiz.', ['caption', 'dim-label'], wrap=True))


def _bars(values, labels, today_idx):
    area = Gtk.DrawingArea(content_height=130, hexpand=True)

    def draw(_a, cr, w, h):
        top = max(values) or 1
        slot = w / 7
        bw = min(slot * 0.55, 40)
        for i, v in enumerate(values):
            cx = slot * i + slot / 2
            bh = max(2, (h - 24) * v / top)
            cr.set_source_rgba(0.114, 0.306, 0.847, 1.0 if i == today_idx else 0.35)
            cr.rectangle(cx - bw / 2, h - 18 - bh, bw, bh)
            cr.fill()
            cr.set_source_rgba(0.5, 0.5, 0.5, 1)
            cr.set_font_size(11)
            ext = cr.text_extents(labels[i])
            cr.move_to(cx - ext.width / 2, h - 4)
            cr.show_text(labels[i])

    area.set_draw_func(draw)
    return area


def _review_page(win, box):
    today = date.today()
    monday = today - timedelta(days=today.weekday())
    sunday = monday + timedelta(days=6)
    week = today.isocalendar()[1]
    notes = dash.scan(win.root)
    week_notes = [n for n in notes if monday <= datetime.fromtimestamp(n.mtime).date() <= sunday]
    tasks = inbox.all_tasks(win.root, [n.path for n in notes])
    ideas = inbox.ideas(win.root)
    box.get_first_child().append(_label(f'{monday:%d.%m.} BIS {sunday:%d.%m.}', ['eyebrow']))
    box.get_first_child().append(_label(f'Wochenrückblick · Woche {week}', ['title-1']))

    stats = Gtk.Box(spacing=14, homogeneous=True)
    for value, text in ((len(week_notes), 'Notizen bearbeitet'), (len(tasks), 'Aufgaben offen'), (len(ideas), 'Ideen in der Inbox')):
        c = _card(2)
        c.inner.append(_label(str(value), ['title-1']))
        c.inner.append(_label(text, ['caption', 'dim-label']))
        stats.append(c)
    box.append(stats)

    per_day = [0] * 7
    for n in week_notes:
        per_day[datetime.fromtimestamp(n.mtime).weekday()] += 1
    c = _card(10)
    c.inner.append(_label('Aktivität pro Tag', ['heading']))
    c.inner.append(_bars(per_day, ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'], today.weekday()))
    box.append(c)

    due = [t for t in tasks if t.due]
    grp = Adw.PreferencesGroup(title='Offen mit Frist')
    if not due:
        grp.set_description('Keine Aufgaben mit Frist.')
    for t in due[:8]:
        grp.add(_task_row(win, t, check=False))
    box.append(grp)

    c = _card(8)
    c.inner.append(_label('Zusammenfassung von Claude', ['heading']))
    c.inner.append(_label('Auf Zuruf: Bitte Claude über den Notizen-Connector, die Woche zusammenzufassen oder Lernkarten zu einem Fach zu erstellen. Claude liest nur, was in deinen Notizen steht.', wrap=True))
    box.append(c)


# ---- Kleines Fenster zum schnellen Festhalten (`notizen-linux capture`, z. B. per Tastenkürzel des Desktops) ----

class CaptureApp(Adw.Application):
    def __init__(self):
        super().__init__(application_id='online.minihomelab.NotizenLinuxCapture')
        self.win = None

    def do_activate(self):
        if self.win:
            self.win.present()
            return
        cfg = cfgmod.load()
        win = Adw.Window(application=self, title='Idee festhalten', default_width=560, default_height=190, resizable=False)
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_start=18, margin_end=18, margin_top=14, margin_bottom=18)
        entry = Gtk.Entry(placeholder_text='Idee festhalten …')
        entry.set_size_request(-1, 48)
        msg = _label('Landet in der Inbox. ↵ festhalten, Esc schließen.', ['caption', 'dim-label'])
        row = Gtk.Box(spacing=8)
        entry.set_hexpand(True)
        row.append(entry)
        row.append(mic_button(entry, msg.set_text, cfg.stt_command))
        box.append(row)
        box.append(msg)
        tv.set_content(box)
        win.set_content(tv)

        def submit(*_):
            text = entry.get_text()
            if not text.strip():
                return
            if not cfg.root:
                msg.set_text('Noch nicht eingerichtet: erst die Notizen-App starten und verbinden.')
                return
            inbox.add_idea(Path(cfg.root), text)
            entry.set_sensitive(False)
            msg.set_text('Festgehalten.')

            def sync_then_close():
                try:
                    if cfg.ready:
                        from .sync import Client, Syncer
                        Syncer(cfg.root, Client(cfg.server, cfg.token), cfgmod.STATE_FILE).sync()
                except Exception:  # noqa: BLE001  (offline: der Abgleich holt es später nach)
                    pass
                GLib.idle_add(win.close)

            threading.Thread(target=sync_then_close, daemon=True).start()

        entry.connect('activate', submit)
        key = Gtk.EventControllerKey()
        key.connect('key-pressed', lambda _c, kv, _k, _s: (win.close(), True)[1] if kv == 0xff1b else False)  # Esc
        win.add_controller(key)
        win.connect('close-request', lambda *_: (self.quit(), False)[1])
        self.win = win
        win.present()
        entry.grab_focus()


def run_capture() -> int:
    return CaptureApp().run(None)
