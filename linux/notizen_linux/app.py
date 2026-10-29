"""Notizen für Linux: GTK 4 / libadwaita. Arbeitet auf einem lokalen Ordner mit .md-Dateien (auch offline)
und gleicht ihn im Hintergrund mit dem Notizen-Server ab."""

from __future__ import annotations

import os
import re
import threading
from datetime import datetime
from pathlib import Path

import gi

gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from . import config as cfgmod  # noqa: E402
from . import dash  # noqa: E402
from . import ideas_ui, nav, schoolplan  # noqa: E402
from . import inbox  # noqa: E402
from .sync import AuthError, Client, ServerError, Syncer, sha256  # noqa: E402

APP_ID = os.environ.get('NOTIZEN_APP_ID', 'online.minihomelab.NotizenLinux')  # Tests nehmen eine eigene ID, damit sie nicht an eine laufende Instanz weiterleiten

# Farben und Maße wie in Slotwise: sachlich, ein Blau-Akzent, feine Linien statt Schatten.
CSS = """
@define-color accent_bg_color #1d4ed8;
@define-color accent_color #2f5fe8;
window, textview { font-family: "IBM Plex Sans", "Cantarell", sans-serif; }
.mono, .eyebrow, .when { font-family: "IBM Plex Mono", monospace; }
.eyebrow { font-size: 11px; letter-spacing: 1px; opacity: .7; }
.area-card { padding: 22px 26px; border-radius: 12px; background: @card_bg_color; border: 1px solid alpha(@window_fg_color, .12); box-shadow: none; }
.area-card:hover { border-color: @accent_bg_color; }
.when { font-size: 12px; opacity: .7; }
.lv0, .lv1, .lv2, .lv3, .lv4 { min-width: 13px; min-height: 13px; border-radius: 3px; }
.lv0 { background: alpha(@window_fg_color, .10); }
.lv1 { background: mix(@card_bg_color, @accent_bg_color, .30); }
.lv2 { background: mix(@card_bg_color, @accent_bg_color, .55); }
.lv3 { background: mix(@card_bg_color, @accent_bg_color, .80); }
.lv4 { background: @accent_bg_color; }
.fmt button { min-width: 36px; min-height: 32px; }
.lesson-card { padding: 12px 16px; border-radius: 10px; background: alpha(@accent_bg_color, .12); border: 1px solid alpha(@accent_bg_color, .35); box-shadow: none; }
.kbd { font-family: "IBM Plex Mono", monospace; font-size: 11px; padding: 1px 6px; border-radius: 4px; border: 1px solid alpha(@window_fg_color, .25); opacity: .8; }
.sec-label { font-size: 11px; letter-spacing: 1px; opacity: .65; margin: 18px 18px 6px; }
.side-tree { background: transparent; padding: 0 6px; }
.side-tree:selected, .side-tree row:selected { border-radius: 8px; }
.side-foot { padding: 10px 18px; border-top: 1px solid alpha(@window_fg_color, .10); font-size: 12px; }
.dash-head { margin-bottom: 4px; }
.side-row { padding: 4px 8px; min-height: 32px; }
.qs-row { padding: 8px 12px; }
.qs-hit { font-weight: 700; color: @accent_color; }
.checkbox-empty { min-width: 14px; min-height: 14px; border-radius: 4px; border: 1.5px solid alpha(@window_fg_color, .4); }
"""

ANY_PREFIX = re.compile(r'^(#{1,6} |- \[[ xX]\] |- |\* |\d+\. )')
PREFIX = {'h1': '# ', 'h2': '## ', 'h3': '### ', 'ul': '- ', 'task': '- [ ] '}


def esc(s: str) -> str:
    return GLib.markup_escape_text(s)


def label(text, css=(), xalign=0.0, wrap=False, ellipsize=False):
    lb = Gtk.Label(label=text, xalign=xalign, wrap=wrap)
    for c in css:
        lb.add_css_class(c)
    if ellipsize:
        from gi.repository import Pango
        lb.set_ellipsize(Pango.EllipsizeMode.END)
    return lb


def clean_path(s: str) -> str:
    """Wie in der Web-Ansicht: Schrägstriche normalisieren, verbotene Zeichen ersetzen, nichts Verstecktes."""
    parts = []
    for seg in s.strip().replace('\\', '/').split('/'):
        seg = re.sub(r'[:*?"<>|]', '-', seg).strip()
        if seg and seg not in ('.', '..') and not seg.startswith('.'):
            parts.append(seg)
    return '/'.join(parts)


class Worker:
    """Gleicht im Hintergrund ab: auf Anforderung, bei Meldung des Servers (SSE) und mindestens jede Minute."""

    def __init__(self, syncer: Syncer, on_report, on_status):
        self.syncer, self.on_report, self.on_status = syncer, on_report, on_status
        self.wake, self.stop = threading.Event(), threading.Event()

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()
        threading.Thread(target=self._live, daemon=True).start()
        self.wake.set()

    def request(self):
        self.wake.set()

    def _live(self):
        self.syncer.client.events(self.stop, self.request, lambda s: GLib.idle_add(self.on_status, s))

    def _loop(self):
        while not self.stop.is_set():
            self.wake.wait(60)
            self.wake.clear()
            if self.stop.is_set():
                break
            try:
                rep = self.syncer.sync()
                GLib.idle_add(self.on_report, rep)
            except AuthError:
                GLib.idle_add(self.on_status, 'Token ungültig')
            except ServerError:
                GLib.idle_add(self.on_status, 'offline')
            except Exception as e:  # nie den Hintergrunddienst sterben lassen
                GLib.idle_add(self.on_status, f'Fehler: {type(e).__name__}')


class SetupWindow(Adw.Window):
    def __init__(self, app, cfg: cfgmod.Config, parent=None, on_done=None):
        super().__init__(application=app, title='Mit dem Notizen-Server verbinden', modal=bool(parent), default_width=480, default_height=520)
        if parent:
            self.set_transient_for(parent)
        self.app, self.on_done = app, on_done
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        page = Adw.PreferencesPage()
        grp = Adw.PreferencesGroup(title='Verbindung', description='Das Token bleibt nur in deinem Benutzerordner (Rechte 600).')
        self.server = Adw.EntryRow(title='Server-Adresse', text=cfg.server or 'https://notizen.minihomelab.online')
        self.token = Adw.PasswordEntryRow(title='Token (NOTES_TOKEN)', text=cfg.token)
        self.root = Adw.EntryRow(title='Ordner für die Notizen', text=cfg.root)
        for r in (self.server, self.token, self.root):
            grp.add(r)
        page.add(grp)
        self.msg = label('', ['error'], wrap=True)
        self.btn = Gtk.Button(label='Verbinden', halign=Gtk.Align.CENTER)
        self.btn.add_css_class('suggested-action')
        self.btn.add_css_class('pill')
        self.btn.connect('clicked', self._connect)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_bottom=24, margin_start=24, margin_end=24)
        box.append(self.msg)
        box.append(self.btn)
        tv.set_content(page)
        tv.add_bottom_bar(box)
        self.set_content(tv)

    def _connect(self, _b):
        cfg = cfgmod.Config(cfgmod.normalize_server(self.server.get_text()), self.token.get_text().strip(), os.path.expanduser(self.root.get_text().strip()))
        if not cfg.ready:
            self.msg.set_text('Bitte alle drei Felder ausfüllen.')
            return
        if not cfg.server.startswith('https://') and not re.match(r'http://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$', cfg.server):
            self.msg.set_text('Bitte eine https://-Adresse eintragen, sonst läuft das Token im Klartext.')
            return
        self.btn.set_sensitive(False)
        self.msg.set_text('Verbinde …')

        def work():
            try:
                Client(cfg.server, cfg.token, timeout=10).changes(0)
                err = ''
            except AuthError:
                err = 'Token falsch.'
            except ServerError as e:
                err = str(e)
            GLib.idle_add(done, err)

        def done(err):
            self.btn.set_sensitive(True)
            self.msg.set_text(err)
            if not err:
                cfgmod.save(cfg)
                self.close()
                if self.on_done:
                    self.on_done()
            return GLib.SOURCE_REMOVE

        threading.Thread(target=work, daemon=True).start()


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app, cfg: cfgmod.Config):
        super().__init__(application=app, title='Notizen', default_width=1180, default_height=780)
        self.app, self.cfg = app, cfg
        self.root = Path(cfg.root)
        self.syncer = Syncer(self.root, Client(cfg.server, cfg.token), cfgmod.STATE_FILE)
        self.area, self.current = '', None
        self.loaded_hash, self.dirty, self._loading = None, False, False
        self.open_folders: set[str] = set()
        self._save_timer = self._sync_timer = 0
        self.query = ''
        saved = cfgmod.load_nav()
        self.pins, self.recents = saved['pins'], saved['recents']  # nur auf diesem PC
        self.hist: list[tuple[str, str]] = []  # Zurück/Vor: ('n', Notiz) oder ('a', Bereich)
        self.hi, self._nav_lock, self._pin_lock = -1, False, False
        self._text_cache: dict[str, tuple[float, str]] = {}
        self.school = schoolplan.School(cfgmod.SCHOOL_FILE)  # Stundenplan aus Slotwise
        self._lesson = None
        self._lp = False
        self.istate = {'idx': 0, 'area': 'Schule', 'min': 45, 'due': '', 'filter': ''}  # Zustand der Inbox-Durchsicht und Aufgaben
        self._build()
        self.worker = Worker(self.syncer, self.on_report, self.on_status)
        self.worker.start()
        self.refresh_tree()
        self.show_area('')
        self.update_lesson()
        self.refresh_school()
        GLib.timeout_add_seconds(60, self._school_tick)
        self.connect('close-request', self._on_close)

    # ---------- Aufbau ----------

    def _action(self, name, fn, accel=None):
        act = Gio.SimpleAction.new(name, None)
        act.connect('activate', lambda *_: fn())
        self.add_action(act)
        if accel:
            self.app.set_accels_for_action(f'win.{name}', [accel])

    def _build(self):
        for name, key in (('h1', '<Control>1'), ('h2', '<Control>2'), ('h3', '<Control>3'), ('ul', None), ('task', None)):
            self._action(name, lambda n=name: self.line_prefix(PREFIX[n]), key)
        self._action('bold', lambda: self.wrap('**'), '<Control>b')
        self._action('italic', lambda: self.wrap('*'), '<Control>i')
        self._action('code', lambda: self.wrap('`'), '<Control>e')
        self._action('new', self.new_note, '<Control>n')
        self._action('save', self.flush_save, '<Control>s')
        self._action('sync', self.request_sync, '<Control>r')
        self._action('settings', self.open_settings)
        self._action('delete', self.delete_note)
        self._action('capture', self.open_capture, '<Control><Alt>i')
        self._action('school', self.open_school)
        self._action('newfolder', self.new_folder, '<Control><Shift>n')
        self._action('quick', self.open_quick, '<Control>k')
        self._action('back', lambda: self.step_hist(-1), '<Alt>Left')
        self._action('forward', lambda: self.step_hist(1), '<Alt>Right')

        self.toasts = Adw.ToastOverlay()
        split = Adw.NavigationSplitView(min_sidebar_width=250, max_sidebar_width=320)
        split.set_sidebar(Adw.NavigationPage.new(self._sidebar(), 'Notizen'))
        split.set_content(Adw.NavigationPage.new(self._content(), 'Inhalt'))
        self.toasts.set_child(split)
        self.set_content(self.toasts)

    def _sidebar(self):
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        hb.set_title_widget(Adw.WindowTitle(title='', subtitle=''))
        add = Gtk.Button(icon_name='list-add-symbolic', tooltip_text='Neue Notiz (Strg+N)')
        add.set_action_name('win.new')
        hb.pack_start(add)
        new_dir = Gtk.Button(icon_name='folder-new-symbolic', tooltip_text='Neuer Ordner (Strg+Umschalt+N)')
        new_dir.set_action_name('win.newfolder')
        hb.pack_start(new_dir)
        self.back_btn = Gtk.Button(icon_name='go-previous-symbolic', tooltip_text='Zurück (Alt+←)', sensitive=False)
        self.back_btn.set_action_name('win.back')
        self.fwd_btn = Gtk.Button(icon_name='go-next-symbolic', tooltip_text='Vor (Alt+→)', sensitive=False)
        self.fwd_btn.set_action_name('win.forward')
        hb.pack_start(self.back_btn)
        hb.pack_start(self.fwd_btn)
        menu = Gio.Menu()
        menu.append('Neuer Ordner …', 'win.newfolder')
        menu.append('Jetzt abgleichen', 'win.sync')
        menu.append('Stundenplan von Slotwise verbinden …', 'win.school')
        menu.append('Verbindung …', 'win.settings')
        menu.append('Beenden', 'app.quit')
        hb.pack_end(Gtk.MenuButton(icon_name='open-menu-symbolic', menu_model=menu))
        tv.add_top_bar(hb)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.nav = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE, margin_start=8, margin_end=8, margin_top=6)
        self.nav.add_css_class('navigation-sidebar')
        for name, area, icon in (
            ('Übersicht', '', 'view-grid-symbolic'), ('Inbox', '!ideen', 'mail-inbox-symbolic'),
            ('Aufgaben', '!aufgaben', 'object-select-symbolic'), ('Rückblick', '!rueckblick', 'x-office-calendar-symbolic'),
            ('Schule', 'Schule', 'folder-symbolic'), ('Projekte', 'Projekte', 'folder-symbolic'), ('Alltag', 'Alltag', 'folder-symbolic'),
        ):
            row = Gtk.ListBoxRow()
            row.area = area
            b = Gtk.Box(spacing=10, margin_top=6, margin_bottom=6)
            b.append(Gtk.Image(icon_name=icon))
            b.append(Gtk.Label(label=name, xalign=0, hexpand=True))
            row.count = label('', ['kbd'])
            row.count.set_visible(False)
            b.append(row.count)
            row.set_child(b)
            self.nav.append(row)
        self.nav.connect('row-activated', lambda _l, r: self.show_area(r.area))
        box.append(self.nav)

        qs = Gtk.Button(margin_start=12, margin_end=12, margin_top=6, margin_bottom=2)
        qsbox = Gtk.Box(spacing=8)
        qsbox.append(Gtk.Image(icon_name='system-search-symbolic'))
        qsbox.append(Gtk.Label(label='Suchen oder springen', xalign=0, hexpand=True))
        qsbox.append(label('Strg K', ['kbd']))
        qs.set_child(qsbox)
        qs.set_action_name('win.quick')
        box.append(qs)

        self.lesson_btn = Gtk.Button(margin_start=12, margin_end=12, margin_top=4, margin_bottom=4, visible=False)
        self.lesson_btn.add_css_class('lesson-card')
        li = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3, halign=Gtk.Align.START)
        self.lesson_head, self.lesson_title, self.lesson_sub = label('', ['eyebrow']), label('', ['title-4']), label('', ['caption', 'dim-label'])
        for w in (self.lesson_head, self.lesson_title, self.lesson_sub):
            li.append(w)
        self.lesson_btn.set_child(li)
        self.lesson_btn.connect('clicked', lambda *_: self._lesson_click())
        press = Gtk.GestureLongPress()
        press.connect('pressed', lambda *_: (setattr(self, '_lp', True), self.on_lesson(True)))
        self.lesson_btn.add_controller(press)
        box.append(self.lesson_btn)

        self.pins_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.recents_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(self.pins_box)
        box.append(self.recents_box)
        box.append(label('ORDNER', ['sec-label']))

        self.store = None
        self.tree = Gtk.TreeView(headers_visible=False, enable_search=False, activate_on_single_click=True, level_indentation=10)
        self.tree.add_css_class('side-tree')
        col = Gtk.TreeViewColumn('Name')
        icon_cell = Gtk.CellRendererPixbuf(xpad=6)
        name_cell = Gtk.CellRendererText(ellipsize=3, ypad=8, xpad=4)
        count_cell = Gtk.CellRendererText(xalign=1.0, foreground='#8b8b93', scale=0.85, xpad=10)
        col.pack_start(icon_cell, False)
        col.pack_start(name_cell, True)
        col.pack_start(count_cell, False)
        col.add_attribute(icon_cell, 'icon-name', 4)
        col.add_attribute(name_cell, 'text', 0)
        col.add_attribute(count_cell, 'text', 3)
        self.tree.append_column(col)
        self.tree.get_selection().connect('changed', self._on_tree_selection)
        self.tree.connect('row-expanded', lambda _t, it, path: self._remember(it, True))
        self.tree.connect('row-collapsed', lambda _t, it, path: self._remember(it, False))
        sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        sc.set_child(self.tree)
        box.append(sc)

        self.status = label('', ['mono', 'dim-label'], ellipsize=True)
        self.status.set_margin_start(18)
        self.status.set_margin_end(12)
        self.status.set_margin_top(8)
        self.status.set_margin_bottom(8)
        box.append(self.status)
        tv.set_content(box)
        return tv

    def _content(self):
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        self.title = Adw.WindowTitle(title='Übersicht', subtitle='')
        self.crumb_box = Gtk.Box(spacing=2, halign=Gtk.Align.CENTER)
        self.title_stack = Gtk.Stack()
        self.title_stack.add_named(self.title, 'title')
        self.title_stack.add_named(self.crumb_box, 'crumb')
        hb.set_title_widget(self.title_stack)
        self.del_btn = Gtk.Button(icon_name='user-trash-symbolic', tooltip_text='Notiz löschen', visible=False)
        self.del_btn.set_action_name('win.delete')
        self.pin_btn = Gtk.ToggleButton(icon_name='non-starred-symbolic', tooltip_text='Anpinnen', visible=False)
        self.pin_btn.connect('toggled', self._on_pin)
        self.next_btn = Gtk.Button(icon_name='go-next-symbolic', visible=False)
        self.next_btn.connect('clicked', lambda *_: self._page(self._pg_next))
        self.prev_btn = Gtk.Button(icon_name='go-previous-symbolic', visible=False)
        self.prev_btn.connect('clicked', lambda *_: self._page(self._pg_prev))
        self._pg_prev = self._pg_next = None
        for b in (self.del_btn, self.pin_btn, self.next_btn, self.prev_btn):
            hb.pack_end(b)
        tv.add_top_bar(hb)

        self.stack = Gtk.Stack()
        self.dash_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=32, margin_top=44, margin_bottom=56, margin_start=44, margin_end=44)
        clamp = Adw.Clamp(maximum_size=1100, child=self.dash_box)
        self.stack.add_named(Gtk.ScrolledWindow(child=clamp, hscrollbar_policy=Gtk.PolicyType.NEVER), 'dash')
        self.stack.add_named(self._note_page(), 'note')
        tv.set_content(self.stack)
        return tv

    def _note_page(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        bar = Gtk.Box(spacing=2, margin_start=12, margin_top=6, margin_bottom=6)
        bar.add_css_class('fmt')
        for text, act, tip in (('H1', 'h1', 'Überschrift 1 (Strg+1)'), ('H2', 'h2', 'Überschrift 2 (Strg+2)'), ('H3', 'h3', 'Überschrift 3 (Strg+3)'), ('|', None, None),
                               ('B', 'bold', 'Fett (Strg+B)'), ('I', 'italic', 'Kursiv (Strg+I)'), ('</>', 'code', 'Code (Strg+E)'), ('|', None, None),
                               ('Liste', 'ul', 'Aufzählung'), ('Aufgabe', 'task', 'Aufgabe')):
            if act is None:
                bar.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL, margin_start=6, margin_end=6, margin_top=6, margin_bottom=6))
                continue
            b = Gtk.Button(label=text, tooltip_text=tip)
            b.add_css_class('flat')
            b.set_action_name(f'win.{act}')
            bar.append(b)
        box.append(bar)
        box.append(Gtk.Separator())

        self.buffer = Gtk.TextBuffer()
        t = self.buffer.create_tag
        t('h1', scale=1.7, weight=700)
        t('h2', scale=1.35, weight=700)
        t('h3', scale=1.15, weight=700)
        t('bold', weight=700)
        t('italic', style=2)
        t('code', family='monospace', background='#8884')
        t('quote', foreground='#888888')
        t('done', strikethrough=True, foreground='#888888')
        t('hidden', invisible=True)  # Formatierungszeichen (#, **, *, `) außerhalb der Cursor-Zeile
        self.text = Gtk.TextView(buffer=self.buffer, wrap_mode=Gtk.WrapMode.WORD_CHAR, left_margin=28, right_margin=28, top_margin=22, bottom_margin=60, pixels_below_lines=4)
        self.text.add_css_class('note-text')
        self.buffer.connect('changed', self._on_changed)
        self.buffer.connect('notify::cursor-position', self._on_cursor)
        self._hl_line = -1
        sc = Gtk.ScrolledWindow(vexpand=True)
        sc.set_child(Adw.Clamp(maximum_size=860, child=self.text))
        box.append(sc)
        return box

    # ---------- Navigation ----------

    def _select_nav(self, area):
        top = area.split('/')[0] if area else ''
        row = None
        if self.current is not None and not area:
            top = None
        i = 0
        while (r := self.nav.get_row_at_index(i)) is not None:
            if top is not None and r.area == top:
                row = r
            i += 1
        if row:
            self.nav.select_row(row)
        else:
            self.nav.unselect_all()

    def show_area(self, area: str):
        self.flush_save()
        self.area, self.current = area, None
        self._tools_visible(False)
        self.title_stack.set_visible_child_name('title')
        special = area.startswith('!')
        self.title.set_title(ideas_ui.SPECIAL[area] if special else area.split('/')[-1] if area else 'Übersicht')
        self.title.set_subtitle('' if special or not area else area + '/')
        self._select_nav(area)
        if not special and self._reveal(area + '/x' if area else ''):
            GLib.idle_add(self._idle_tree)  # nicht mitten im Auswahl-Signal des Baums neu aufbauen
        self._render_side()
        self.build_dash()
        self.stack.set_visible_child_name('dash')
        self._record('a', area)

    def _tools_visible(self, v: bool):
        for b in (self.del_btn, self.pin_btn, self.next_btn, self.prev_btn):
            b.set_visible(v)

    def _reveal(self, rel: str) -> bool:
        """Klappt alle Ordner über `rel` im Baum auf; wahr, wenn sich etwas geändert hat."""
        before = len(self.open_folders)
        for d in nav.crumbs(rel) if rel else []:
            self.open_folders.add(d['path'])
        return len(self.open_folders) != before

    def _idle_tree(self):
        self.refresh_tree()
        return GLib.SOURCE_REMOVE

    # ---------- Baum ----------

    def refresh_tree(self):
        notes = dash.scan(self.root)
        counts = nav.folder_counts([n.path for n in notes])
        store = Gtk.TreeStore(str, str, bool, str, str)
        nodes = {}
        # erst alle Ordner (auch leere), dann die Notizen: Ordner stehen so vor den Notizen ihrer Ebene
        for d in sorted(dash.dirs(self.root), key=str.lower):
            acc = ''
            for seg in d.split('/'):
                parent = nodes.get(acc)
                acc = f'{acc}/{seg}' if acc else seg
                if acc not in nodes:
                    nodes[acc] = store.append(parent, [seg, acc, True, str(counts.get(acc, 0)), 'folder-symbolic'])
        for n in sorted(notes, key=lambda n: (nav.dir_of(n.path).lower(), nav.title_of(n.path).lower())):
            store.append(nodes.get(nav.dir_of(n.path)), [nav.title_of(n.path), n.path, False, dash.when(n.mtime), 'text-x-generic-symbolic'])
        self._tree_lock = True
        self.tree.set_model(store)
        self.store = store
        for acc, it in nodes.items():
            if acc in self.open_folders:
                self.tree.expand_row(store.get_path(it), False)
        self._tree_lock = False
        self._render_side()

    def _remember(self, it, opened):
        if getattr(self, '_tree_lock', False):
            return
        path = self.store.get_value(it, 1)
        (self.open_folders.add if opened else self.open_folders.discard)(path)

    def _on_tree_selection(self, sel):
        if getattr(self, '_tree_lock', False):
            return
        model, it = sel.get_selected()
        if not it:
            return
        path, is_folder = model.get_value(it, 1), model.get_value(it, 2)
        if is_folder:
            self.open_folders.add(path)
            self.tree.expand_row(model.get_path(it), False)
            self.show_area(path)
        else:
            self.open_note(path)

    # ---------- Übersicht ----------

    def build_dash(self):
        box = self.dash_box
        while (c := box.get_first_child()):
            box.remove(c)
        if self.area.startswith('!'):
            ideas_ui.build(self, box, self.area)
            self._update_counts()
            return
        notes = dash.scan(self.root)
        scoped = [n for n in notes if dash.in_area(n.path, self.area)]
        tasks = dash.tasks_of(self.root, scoped)

        head = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_bottom=6)
        head.append(label((self.area + '/') if self.area else datetime.now().strftime('%d.%m.%Y'), ['eyebrow']))
        head.append(label(self.area.split('/')[-1] if self.area else 'Übersicht', ['title-1']))
        box.append(head)
        box.append(ideas_ui.capture_card(self))
        if not self.area and (card := ideas_ui.inbox_card(self)):
            box.append(card)
        self._update_counts()

        groups = dash.folders(scoped, self.area, dash.dirs(self.root))
        if groups:
            box.append(label('Ordner' if self.area else 'Bereiche', ['heading']))
            flow = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=22, row_spacing=22, homogeneous=True, min_children_per_line=1, max_children_per_line=4)
            for full, ns in groups:
                b = Gtk.Button()
                b.add_css_class('area-card')
                b.add_css_class('flat')
                inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
                top = Gtk.Box()
                top.append(label(full.split('/')[-1], ['title-4']))
                inner.append(top)
                last = ns[0].title if ns else None
                inner.append(label(f'Zuletzt: {last}' if last else 'Noch leer', ['dim-label', 'caption'], ellipsize=True))
                open_n = sum(len(dash.open_tasks((self.root / n.path).read_text(encoding='utf-8', errors='replace'))) for n in ns)
                foot = Gtk.Box(margin_top=8)
                foot.append(label(f'{len(ns)} {"Notiz" if len(ns) == 1 else "Notizen"}', ['mono', 'caption', 'dim-label'], wrap=False))
                sp = Gtk.Box(hexpand=True)
                foot.append(sp)
                if ns:
                    foot.append(label(f'{open_n} offen', ['mono', 'caption', 'dim-label']))
                inner.append(foot)
                b.set_child(inner)
                b.connect('clicked', lambda _b, p=full: self.show_area(p))
                flow.append(b)
            box.append(flow)

        box.append(self._graph(scoped))

        row = Gtk.Box(spacing=26, homogeneous=True, valign=Gtk.Align.START)
        rec = Adw.PreferencesGroup(title='Zuletzt bearbeitet')
        if not scoped:
            rec.set_description('Noch keine Notizen. Mit Strg+N geht es los.')
        for n in scoped[:8]:
            r = Adw.ActionRow(title=n.title, subtitle=n.path, use_markup=False, activatable=True)
            r.add_suffix(label(dash.when(n.mtime), ['when']))
            r.connect('activated', lambda _r, p=n.path: self.open_note(p))
            rec.add(r)
        row.append(rec)
        tg = Adw.PreferencesGroup(title='Offene Aufgaben')
        if not tasks:
            tg.set_description('Keine offenen Aufgaben.')
        for n, t in tasks[:8]:
            r = Adw.ActionRow(title=t, subtitle=n.title, use_markup=False, activatable=True)
            r.add_prefix(Gtk.Box(css_classes=['checkbox-empty'], valign=Gtk.Align.CENTER))
            r.connect('activated', lambda _r, p=n.path: self.open_note(p))
            tg.add(r)
        row.append(tg)
        box.append(row)

    def _graph(self, scoped):
        cells, months, total = dash.activity([n.mtime for n in scoped])
        frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        top = Gtk.Box()
        top.append(label('Aktivität', ['heading']))
        top.append(Gtk.Box(hexpand=True))
        top.append(label(f'{total} {"Notiz" if total == 1 else "Notizen"} im letzten Jahr nach Tag der letzten Änderung', ['caption', 'dim-label']))
        frame.append(top)
        grid = Gtk.Grid(column_spacing=3, row_spacing=3)
        for r, name in ((1, 'Mo'), (3, 'Mi'), (5, 'Fr')):
            grid.attach(label(name, ['mono', 'caption', 'dim-label']), 0, r + 1, 1, 1)
        for name, col in months:
            grid.attach(label(name, ['mono', 'caption', 'dim-label']), col + 1, 0, 4, 1)
        for i, c in enumerate(cells):
            if not c:
                continue
            d, n, level = c
            cell = Gtk.Box(css_classes=[f'lv{level}'], tooltip_text=f'{n} {"Notiz" if n == 1 else "Notizen"} am {d.strftime("%d.%m.%Y")}')
            grid.attach(cell, i // 7 + 1, i % 7 + 1, 1, 1)
        sc = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True)
        sc.set_child(grid)
        frame.append(sc)
        legend = Gtk.Box(spacing=4, halign=Gtk.Align.END)
        legend.append(label('Weniger ', ['caption', 'dim-label']))
        for lv in range(5):
            legend.append(Gtk.Box(css_classes=[f'lv{lv}']))
        legend.append(label(' Mehr', ['caption', 'dim-label']))
        frame.append(legend)
        adj = sc.get_hadjustment()
        GLib.timeout_add(80, lambda: (adj.set_value(adj.get_upper()), GLib.SOURCE_REMOVE)[1])  # wie bei GitHub: heute steht rechts
        return frame

    # ---------- Notiz ----------

    def open_note(self, rel: str):
        self.flush_save()
        try:
            data = (self.root / rel).read_bytes()
        except OSError:
            return
        self._loading = True
        self.buffer.set_text(data.decode('utf-8', 'replace'))
        self.buffer.place_cursor(self.buffer.get_end_iter())  # wie in der Android-App: Cursor ans Ende, die Überschrift bleibt ohne „#“
        self._loading = False
        self.current, self.loaded_hash, self.dirty = rel, sha256(data), False
        self._highlight()
        parts = rel.removesuffix('.md').split('/')
        self.title.set_title(parts[-1])
        self.title.set_subtitle(' › '.join(parts[:-1]))
        self._tools_visible(True)
        self._select_nav(parts[0] if len(parts) > 1 else '')
        self.recents = nav.push_recent(self.recents, rel)
        self._save_nav()
        if self._reveal(rel):
            GLib.idle_add(self._idle_tree)  # nicht mitten im Auswahl-Signal des Baums neu aufbauen
        self._render_side()
        self._render_note_nav(rel)
        self.title_stack.set_visible_child_name('crumb')
        self.stack.set_visible_child_name('note')
        self.text.grab_focus()
        self._record('n', rel)

    # ---------- Pfad, Blättern, Pins, Verlauf ----------

    def _all_paths(self):
        return [n.path for n in dash.scan(self.root)]

    def _render_note_nav(self, rel: str):
        while (c := self.crumb_box.get_first_child()):
            self.crumb_box.remove(c)
        paths = self._all_paths()
        cs = nav.crumbs(rel)
        for i, c in enumerate(cs):
            if i == len(cs) - 1:
                self.crumb_box.append(self._folder_menu(c['path'], c['label'], paths))
            else:
                b = Gtk.Button(label=c['label'])
                b.add_css_class('flat')
                b.connect('clicked', lambda _b, p=c['path']: self.show_area(p))
                self.crumb_box.append(b)
            self.crumb_box.append(label('›', ['dim-label']))
        self.crumb_box.append(label(nav.title_of(rel), ['heading']))
        pg = nav.pager(paths, rel)
        self._pg_prev, self._pg_next = pg['prev'], pg['next']
        for btn, target, what in ((self.prev_btn, pg['prev'], 'Vorherige'), (self.next_btn, pg['next'], 'Nächste')):
            btn.set_sensitive(bool(target))
            btn.set_tooltip_text(f'{what} Notiz im Ordner: {nav.title_of(target)}' if target else f'{what} Notiz im Ordner')
        self.title_stack.set_tooltip_text(f'{pg["index"] + 1} von {pg["total"]} in {pg["folder"].split("/")[-1] or "Notizen"}')
        self._pin_lock = True
        pinned = rel in self.pins
        self.pin_btn.set_active(pinned)
        self.pin_btn.set_icon_name('starred-symbolic' if pinned else 'non-starred-symbolic')
        self.pin_btn.set_tooltip_text('Nicht mehr anpinnen' if pinned else 'Anpinnen')
        self._pin_lock = False

    def _folder_menu(self, folder: str, name: str, paths):
        """Letzter Ordner im Pfad: Klick zeigt die Geschwister-Ordner zum direkten Wechsel."""
        pop = Gtk.Popover()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        parent = nav.dir_of(folder)
        box.append(label(f'ORDNER IN {parent.split("/")[-1].upper() or "NOTIZEN"}', ['sec-label']))
        for f in nav.folder_peers(paths, folder):
            row = Gtk.Button()
            row.add_css_class('flat')
            rb = Gtk.Box(spacing=8)
            rb.append(Gtk.Label(label='✓' if f['current'] else ' ', width_chars=2))
            rb.append(Gtk.Label(label=f['name'], xalign=0, hexpand=True))
            rb.append(label(str(f['count']), ['kbd']))
            row.set_child(rb)
            row.connect('clicked', lambda _b, p=f['path']: (pop.popdown(), self.show_area(p)))
            box.append(row)
        pop.set_child(box)
        mb = Gtk.MenuButton(label=f'{name} ▾', popover=pop)
        mb.add_css_class('flat')
        return mb

    def _page(self, target):
        if target:
            self.open_note(target)

    def _on_pin(self, btn):
        if self._pin_lock or not self.current:
            return
        _pinned, self.pins = nav.toggle_pin(self.pins, self.current)
        self._save_nav()
        self._pin_lock = True
        pinned = self.current in self.pins
        btn.set_icon_name('starred-symbolic' if pinned else 'non-starred-symbolic')
        btn.set_tooltip_text('Nicht mehr anpinnen' if pinned else 'Anpinnen')
        self._pin_lock = False
        self._render_side()

    def _save_nav(self):
        try:
            cfgmod.save_nav({'pins': self.pins, 'recents': self.recents})
        except OSError:
            pass  # Pins und Verlauf sind Komfort, kein Grund für einen Fehler

    # ---------- Stundenplan (Slotwise) ----------

    def update_lesson(self):
        """Karte mit laufender oder nächster Stunde; ohne Verbindung oder ohne Stunden heute bleibt sie weg."""
        nl = self.school.next_card()
        self._lesson = nl[0] if nl else None
        self.lesson_btn.set_visible(bool(nl))
        if nl:
            c = nl[1]
            self.lesson_head.set_text(c.head.upper())
            self.lesson_title.set_text(c.title)
            self.lesson_sub.set_text(c.sub)
            self.lesson_sub.set_visible(bool(c.sub))

    def refresh_school(self, manual=False):
        if not self.school.connected:
            return

        def work():
            try:
                n = self.school.refresh()
                err = ''
            except RuntimeError as e:
                n, err = 0, str(e)
            GLib.idle_add(done, n, err)

        def done(n, err):
            self.update_lesson()
            if manual:
                self.toast(f'Stundenplan laden fehlgeschlagen: {err}' if err else f'{n} Stunden geladen')
            return GLib.SOURCE_REMOVE

        threading.Thread(target=work, daemon=True).start()

    def _school_tick(self):
        self._school_ticks = getattr(self, '_school_ticks', 0) + 1
        self.update_lesson()
        if self._school_ticks % 30 == 0:  # alle 30 Minuten neu laden
            self.refresh_school()
        return GLib.SOURCE_CONTINUE

    def _lesson_click(self):
        if self._lp:  # nach langem Drücken kommt noch ein Klick: ignorieren
            self._lp = False
            return
        self.on_lesson(False)

    def on_lesson(self, long_press: bool):
        """Karte angetippt: Fach-Ordner öffnen; beim ersten Mal (oder langem Drücken) Ordner zuordnen."""
        lsn = self._lesson
        if not lsn:
            return
        folder = self.school.folder_for(self.root, lsn.subject)
        if long_press or not folder:
            self.choose_folder(lsn.subject)
        else:
            self.show_area(folder)

    def choose_folder(self, subject: str):
        folders = sorted((p.relative_to(self.root).as_posix() for p in self.root.rglob('*') if p.is_dir() and not any(s.startswith('.') for s in p.relative_to(self.root).parts) and len(p.relative_to(self.root).parts) <= 3), key=str.lower)
        options = folders + [f'Neuer Ordner für „{subject}“ …']
        drop = Gtk.DropDown.new_from_strings(options)
        dlg = Adw.AlertDialog(heading=f'Ordner für {subject}', body='Beim nächsten Tippen auf die Karte öffnet sich dieser Ordner.')
        dlg.set_extra_child(drop)
        dlg.add_response('cancel', 'Abbrechen')
        dlg.add_response('ok', 'Zuordnen')
        dlg.set_response_appearance('ok', Adw.ResponseAppearance.SUGGESTED)

        def done(d, res):
            if d.choose_finish(res) != 'ok':
                return
            i = drop.get_selected()
            if i < len(folders):
                rel = folders[i]
            else:
                rel = f'{"Schule/" if (self.root / "Schule").is_dir() else ""}{clean_path(subject) or "Fach"}'
                (self.root / rel).mkdir(parents=True, exist_ok=True)
            self.school.set_folder(subject, rel)
            self.refresh_tree()
            self.show_area(rel)

        dlg.choose(self, None, done)

    def open_school(self):
        entry = Gtk.Entry(text=self.school.link(), placeholder_text='https://…/ical/….ics')
        dlg = Adw.AlertDialog(
            heading='Stundenplan von Slotwise',
            body='Kopiere in Slotwise unter Einstellungen → Kalender den Abo-Link („Slotwise abonnieren“) und füge ihn hier ein. '
                 'In Slotwise muss unter „Schulplan“ dein vpmobil24-Zugang eingetragen sein. Leer lassen trennt die Verbindung.',
        )
        dlg.set_extra_child(entry)
        dlg.add_response('cancel', 'Abbrechen')
        dlg.add_response('ok', 'Verbinden')
        dlg.set_response_appearance('ok', Adw.ResponseAppearance.SUGGESTED)

        def done(d, res):
            if d.choose_finish(res) != 'ok':
                return
            link = entry.get_text().strip()
            if not link:
                self.school.disconnect()
                self.update_lesson()
            elif not self.school.connect(link):
                self.toast('Das ist kein Slotwise-Abo-Link')
            else:
                self.refresh_school(manual=True)

        dlg.choose(self, None, done)

    def toast(self, msg: str):
        self.toasts.add_toast(Adw.Toast(title=msg, timeout=4))

    def _update_counts(self):
        n_ideas, n_tasks = ideas_ui.counts(self)
        i = 0
        while (r := self.nav.get_row_at_index(i)) is not None:
            n = n_ideas if r.area == '!ideen' else n_tasks if r.area == '!aufgaben' else 0
            r.count.set_text(str(n))
            r.count.set_visible(n > 0)
            i += 1

    def after_ideas_change(self):
        """Nach Festhalten, Sortieren oder Abhaken: Listen, Zähler und aktuelle Seite neu zeichnen und abgleichen."""
        self.request_sync(delay=800)
        self.refresh_tree()
        if self.current is None:
            self.build_dash()
        else:
            self._update_counts()

    def open_capture(self):
        """Strg+Alt+I: kleines Fenster zum schnellen Festhalten über der App."""
        dlg = Adw.Dialog(title='Idee festhalten', content_width=560)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_start=18, margin_end=18, margin_top=18, margin_bottom=18)
        entry = Gtk.Entry(placeholder_text='Idee festhalten …')
        entry.set_size_request(-1, 48)
        box.append(entry)
        box.append(label('Landet in der Inbox. ↵ festhalten, Esc schließen.', ['caption', 'dim-label']))

        def submit(*_):
            if inbox.add_idea(self.root, entry.get_text()):
                dlg.close()
                self.toast('Festgehalten')
                self.after_ideas_change()

        entry.connect('activate', submit)
        dlg.set_child(box)
        dlg.set_focus(entry)
        dlg.present(self)

    def _render_side(self):
        exists = lambda p: (self.root / p).is_file()  # noqa: E731
        self.pins = nav.prune(self.pins, exists)
        self.recents = nav.prune(self.recents, exists)
        for box, title, items in ((self.pins_box, 'ANGEPINNT', self.pins), (self.recents_box, 'ZULETZT GEÖFFNET', [p for p in self.recents if p not in self.pins][:4])):
            while (c := box.get_first_child()):
                box.remove(c)
            if not items:
                continue
            box.append(label(title, ['sec-label']))
            for p in items:
                b = Gtk.Button(margin_start=8, margin_end=8)
                b.add_css_class('flat')
                row = Gtk.Box(spacing=8)
                row.append(Gtk.Image(icon_name='starred-symbolic' if title == 'ANGEPINNT' else 'document-open-recent-symbolic'))
                row.append(Gtk.Label(label=nav.title_of(p), xalign=0, hexpand=True, ellipsize=3))
                row.append(label(nav.dir_of(p).split('/')[-1], ['dim-label', 'caption']))
                b.set_child(row)
                if p == self.current:
                    b.add_css_class('suggested-action')
                b.connect('clicked', lambda _b, q=p: self.open_note(q))
                box.append(b)

    def _update_hist(self):
        self.back_btn.set_sensitive(self.hi > 0)
        self.fwd_btn.set_sensitive(self.hi < len(self.hist) - 1)

    def _record(self, kind: str, p: str):
        if self._nav_lock:
            return
        if 0 <= self.hi < len(self.hist) and self.hist[self.hi] == (kind, p):
            return
        del self.hist[self.hi + 1:]
        self.hist.append((kind, p))
        self.hist = self.hist[-50:]
        self.hi = len(self.hist) - 1
        self._update_hist()

    def step_hist(self, d: int):
        i = self.hi + d
        if not 0 <= i < len(self.hist):
            return
        kind, p = self.hist[i]
        if kind == 'n' and not (self.root / p).is_file():
            del self.hist[i]  # Notiz gibt es nicht mehr
            if d < 0:
                self.hi -= 1
            self._update_hist()
            return self.step_hist(d)
        self._nav_lock, self.hi = True, i
        try:
            self.open_note(p) if kind == 'n' else self.show_area(p)
        finally:
            self._nav_lock = False
            self._update_hist()

    # ---------- Schnellwechsel (Strg+K) ----------

    def _text_of(self, p: str):
        try:
            mt = (self.root / p).stat().st_mtime
            hit = self._text_cache.get(p)
            if hit and hit[0] == mt:
                return hit[1]
            text = (self.root / p).read_text(encoding='utf-8', errors='replace')
        except OSError:
            return ''
        self._text_cache[p] = (mt, text)
        return text

    def qs_items(self, query: str, scope: str = '') -> list[dict]:
        """Treffer für den Schnellwechsel; als letzter Eintrag die Aktion „Neue Notiz“, wenn etwas eingegeben ist."""
        notes = dash.scan(self.root)
        mt = {n.path: n.mtime for n in notes}
        items = nav.search(list(mt), lambda p: mt[p], self._text_of, query, scope)
        raw = query.strip()
        if raw and not raw.startswith('/'):
            base = scope or (nav.dir_of(self.current) if self.current else self.area)
            rel = clean_path(raw).removesuffix('.md')
            if rel:
                items.append({'kind': 'create', 'title': raw, 'path': (rel if '/' in raw or not base else f'{base}/{rel}') + '.md'})
        return items

    def run_item(self, it: dict):
        if it['kind'] == 'folder':
            self.show_area(it['path'])
        elif it['kind'] == 'create':
            self.create_note(it['path'])
        else:
            self.open_note(it['path'])

    def open_quick(self):
        QuickSwitcher(self).present(self)

    def create_note(self, rel: str):
        p = self.root / rel
        if not p.exists():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(f'# {rel.removesuffix(".md").split("/")[-1]}\n\n', encoding='utf-8')
            self.request_sync(delay=500)
        self.refresh_tree()
        self.open_note(rel)

    def _on_changed(self, _b):
        if self._loading:
            return
        self._highlight()
        self.dirty = True
        if self._save_timer:
            GLib.source_remove(self._save_timer)
        self._save_timer = GLib.timeout_add(800, self._autosave)

    def _autosave(self):
        self._save_timer = 0
        self.flush_save()
        return GLib.SOURCE_REMOVE

    def _text(self):
        s, e = self.buffer.get_bounds()
        return self.buffer.get_text(s, e, True)

    def flush_save(self):
        if self._save_timer:
            GLib.source_remove(self._save_timer)
            self._save_timer = 0
        if not (self.current and self.dirty):
            return
        data = self._text().encode('utf-8')
        p = self.root / self.current
        tmp = p.with_name(p.name + '.tmp')
        tmp.write_bytes(data)
        os.replace(tmp, p)
        self.loaded_hash, self.dirty = sha256(data), False
        self.on_status('gespeichert')
        self.request_sync(delay=1500)

    def _cursor_line(self) -> int:
        return self.buffer.get_iter_at_mark(self.buffer.get_insert()).get_line()

    def _on_cursor(self, *_):
        """Wechselt der Cursor die Zeile, werden die Formatierungszeichen der alten Zeile wieder versteckt und die der neuen gezeigt."""
        if self._loading or self._cursor_line() == self._hl_line:
            return
        self._highlight()

    def _highlight(self):
        b = self.buffer
        s, e = b.get_bounds()
        b.remove_all_tags(s, e)
        text = b.get_text(s, e, True)
        pos, fence = 0, False
        cur = self._cursor_line()
        self._hl_line = cur
        at = lambda a, z, tag: b.apply_tag_by_name(tag, b.get_iter_at_offset(a), b.get_iter_at_offset(z))
        for ln, line in enumerate(text.split('\n')):
            end = pos + len(line)
            hide = ln != cur  # Zeichen wie #, ** und ` nur in der Zeile zeigen, in der man gerade schreibt
            if re.match(r'^\s*```', line):
                fence = not fence
                at(pos, end, 'code')
            elif fence:
                at(pos, end, 'code')
            else:
                if (m := re.match(r'^(#{1,6}) ', line)):
                    at(pos, end, f'h{min(len(m.group(1)), 3)}')
                    if hide:
                        at(pos, pos + m.end(), 'hidden')
                elif line.startswith('> '):
                    at(pos, end, 'quote')
                elif re.match(r'^\s*[-*] \[[xX]\] ', line):
                    at(pos, end, 'done')
                for m in re.finditer(r'\*\*(.+?)\*\*', line):
                    at(pos + m.start(), pos + m.end(), 'bold')
                    if hide:
                        at(pos + m.start(), pos + m.start() + 2, 'hidden')
                        at(pos + m.end() - 2, pos + m.end(), 'hidden')
                for m in re.finditer(r'(?<![*\w])\*(?!\s|\*)(.+?)(?<!\s)\*(?![*\w])', line):
                    at(pos + m.start(), pos + m.end(), 'italic')
                    if hide:
                        at(pos + m.start(), pos + m.start() + 1, 'hidden')
                        at(pos + m.end() - 1, pos + m.end(), 'hidden')
                for m in re.finditer(r'`(.+?)`', line):
                    at(pos + m.start(), pos + m.end(), 'code')
                    if hide:
                        at(pos + m.start(), pos + m.start() + 1, 'hidden')
                        at(pos + m.end() - 1, pos + m.end(), 'hidden')
            pos = end + 1

    # ---------- Formatieren (Markdown bleibt im Text sichtbar) ----------

    def _line_range(self):
        b = self.buffer
        if b.get_has_selection():
            s, e = b.get_selection_bounds()
        else:
            s = e = b.get_iter_at_mark(b.get_insert())
        first, last = s.get_line(), e.get_line()
        if e.starts_line() and last > first:
            last -= 1
        return first, last

    def line_prefix(self, prefix: str):
        if not self.current:
            return
        b = self.buffer
        first, last = self._line_range()
        b.begin_user_action()
        for ln in range(first, last + 1):
            start = b.get_iter_at_line(ln)[1]
            end = start.copy()
            if not end.ends_line():
                end.forward_to_line_end()
            line = b.get_text(start, end, True)
            new = line[len(prefix):] if line.startswith(prefix) else prefix + ANY_PREFIX.sub('', line, count=1)
            b.delete(start, end)
            b.insert(start, new)
        b.end_user_action()
        self.text.grab_focus()

    def wrap(self, mark: str):
        if not self.current:
            return
        b = self.buffer
        b.begin_user_action()
        if b.get_has_selection():
            s, e = b.get_selection_bounds()
            sel = b.get_text(s, e, True)
            if len(sel) >= 2 * len(mark) and sel.startswith(mark) and sel.endswith(mark):
                new = sel[len(mark):-len(mark)]
            else:
                new = mark + sel + mark
            off = s.get_offset()
            b.delete(s, e)
            b.insert(b.get_iter_at_offset(off), new)
            b.select_range(b.get_iter_at_offset(off), b.get_iter_at_offset(off + len(new)))
        else:
            b.insert_at_cursor(mark * 2)
            it = b.get_iter_at_mark(b.get_insert())
            it.backward_chars(len(mark))
            b.place_cursor(it)
        b.end_user_action()
        self.text.grab_focus()

    # ---------- Neu, Löschen ----------

    def _ask(self, heading, body, ok, on_ok, entry=None, destructive=False):
        dlg = Adw.AlertDialog(heading=heading, body=body)
        dlg.add_response('cancel', 'Abbrechen')
        dlg.add_response('ok', ok)
        dlg.set_response_appearance('ok', Adw.ResponseAppearance.DESTRUCTIVE if destructive else Adw.ResponseAppearance.SUGGESTED)
        dlg.set_default_response('ok')
        if entry is not None:
            dlg.set_extra_child(entry)
            entry.connect('activate', lambda *_: dlg.response('ok') if hasattr(dlg, 'response') else None)

        def done(d, res):
            if d.choose_finish(res) == 'ok':
                on_ok()

        dlg.choose(self, None, done)

    def new_note(self):
        entry = Gtk.Entry(placeholder_text='Schule/Physik/Titel', activates_default=True)
        if self.area:
            entry.set_text(self.area + '/')
            entry.set_position(-1)

        def create():
            rel = clean_path(entry.get_text())
            if not rel:
                return
            self.create_note(rel.removesuffix('.md') + '.md')

        self._ask('Neue Notiz', 'Ordner und Titel, getrennt mit /. Fehlende Ordner werden angelegt.', 'Anlegen', create, entry)

    def new_folder(self):
        """Neuer Ordner (auch verschachtelt, z. B. „Schule/Physik/Optik“); Standard ist der Ordner, in dem man gerade ist."""
        here = nav.dir_of(self.current) if self.current else ('' if self.area.startswith('!') else self.area)
        entry = Gtk.Entry(placeholder_text='Schule/Physik/Optik', activates_default=True, text=f'{here}/' if here else '')
        entry.set_position(-1)

        def create():
            rel = clean_path(entry.get_text())
            if not rel:
                return
            if (self.root / rel).exists():
                self.toast('Den Ordner gibt es schon')
            else:
                (self.root / rel).mkdir(parents=True)
                self.toast('Ordner angelegt. Zum Server geht er, sobald eine Notiz darin liegt.')
            self.open_folders.update(d['path'] for d in nav.crumbs(rel + '/x'))
            self.refresh_tree()
            self.show_area(rel)

        self._ask('Neuer Ordner', 'Ordner und Unterordner, getrennt mit /. Fehlende Ebenen werden angelegt.', 'Anlegen', create, entry)

    def delete_note(self):
        if not self.current:
            return
        rel = self.current

        def remove():
            self.dirty = False
            (self.root / rel).unlink(missing_ok=True)
            self.request_sync(delay=300)
            self.refresh_tree()
            self.show_area(self.area if self.area and rel.startswith(self.area + '/') else '')

        self._ask('Notiz löschen?', f'„{rel}“ wird auch auf dem Server und auf anderen Geräten gelöscht.', 'Löschen', remove, destructive=True)

    # ---------- Abgleich ----------

    def request_sync(self, delay=0):
        if self._sync_timer:
            GLib.source_remove(self._sync_timer)
            self._sync_timer = 0
        if delay:
            self._sync_timer = GLib.timeout_add(delay, self._fire_sync)
        else:
            self._fire_sync()

    def _fire_sync(self):
        self._sync_timer = 0
        self.flush_save()
        self.worker.request()
        return GLib.SOURCE_REMOVE

    def on_status(self, s):
        self.status.set_text(s if s.startswith('Token') or s.startswith('Fehler') else f'{s} · {datetime.now().strftime("%H:%M")}')
        return GLib.SOURCE_REMOVE

    def on_report(self, rep):
        self.on_status('abgeglichen' if not rep.errors else f'{len(rep.errors)} Fehler')
        for c in rep.conflicts:
            self.toasts.add_toast(Adw.Toast(title=f'Konflikt: eigene Fassung als „{Path(c).name}“ gesichert', timeout=8))
        if rep.changed:
            self.refresh_tree()
            if self.current:
                self._reload_if_changed()
            else:
                self.build_dash()
        return GLib.SOURCE_REMOVE

    def _reload_if_changed(self):
        """Wurde die offene Notiz beim Abgleich geändert, wird sie neu geladen; eigene ungesicherte Änderungen bleiben als Kopie erhalten."""
        p = self.root / self.current
        try:
            disk = p.read_bytes()
        except OSError:
            self.toasts.add_toast(Adw.Toast(title='Die Notiz wurde auf einem anderen Gerät gelöscht.'))
            self.show_area(self.area)
            return
        if sha256(disk) == self.loaded_hash:
            return
        if self.dirty:
            keep = self.syncer._conflict_copy(self.current, self._text().encode('utf-8'))
            self.toasts.add_toast(Adw.Toast(title=f'Konflikt: eigene Fassung als „{Path(keep).name}“ gesichert', timeout=8))
        self.dirty = False
        rel = self.current
        self.current = None
        self.open_note(rel)

    # ---------- Fenster ----------

    def open_settings(self):
        SetupWindow(self.app, self.cfg, parent=self, on_done=self.app.restart).present()

    def _on_close(self, *_):
        self.flush_save()
        self.worker.stop.set()
        self.worker.request()
        return False


class QuickSwitcher(Adw.Dialog):
    """Strg+K: Notizen, Ordner und Aktionen suchen. ↑↓ wählen, ↵ öffnen, Strg+↵ neu anlegen, Tab wechselt den Bereich, „/“ sucht Ordner."""

    SCOPES = ['', 'Schule', 'Projekte', 'Alltag']

    def __init__(self, win: MainWindow):
        super().__init__(title='Schnellwechsel', content_width=680, content_height=520)
        self.win, self.scope, self.items, self.rows, self.sel = win, '', [], [], 0
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.entry = Gtk.Entry(placeholder_text='Notiz, Ordner oder neue Notiz', margin_start=14, margin_end=14, margin_top=14, margin_bottom=8, primary_icon_name='system-search-symbolic')
        self.entry.connect('changed', lambda *_: self._changed())
        keys = Gtk.EventControllerKey()
        keys.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        keys.connect('key-pressed', self._key)
        self.entry.add_controller(keys)
        box.append(self.entry)

        chips = Gtk.Box(spacing=6, margin_start=14, margin_end=14, margin_bottom=8)
        self.chips = []
        group = None
        for s in self.SCOPES:
            b = Gtk.ToggleButton(label=s or 'Alle', group=group)
            group = group or b
            b.scope = s
            b.add_css_class('pill')
            b.connect('toggled', self._chip)
            chips.append(b)
            self.chips.append(b)
        self.chips[0].set_active(True)
        chips.append(Gtk.Box(hexpand=True))
        chips.append(label('Tab wechselt den Bereich', ['caption', 'dim-label']))
        box.append(chips)
        box.append(Gtk.Separator())

        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.list.add_css_class('navigation-sidebar')
        self.list.connect('row-activated', lambda _l, r: self._run(r.idx))
        self.sc = Gtk.ScrolledWindow(vexpand=True, child=self.list, hscrollbar_policy=Gtk.PolicyType.NEVER)
        box.append(self.sc)
        box.append(Gtk.Separator())
        foot = Gtk.Box(spacing=14, margin_start=14, margin_end=14, margin_top=8, margin_bottom=8)
        for k, t in (('↑↓', 'wählen'), ('↵', 'öffnen'), ('/', 'nur Ordner'), ('Strg ↵', 'neu anlegen')):
            f = Gtk.Box(spacing=5)
            f.append(label(k, ['kbd']))
            f.append(label(t, ['caption', 'dim-label']))
            foot.append(f)
        foot.append(Gtk.Box(hexpand=True))
        foot.append(label('Titel, Pfad und Text', ['caption', 'dim-label']))
        box.append(foot)
        self.set_child(box)
        self.set_focus(self.entry)
        self.refresh()

    def _chip(self, b):
        if b.get_active() and b.scope != self.scope:
            self.scope = b.scope
            self.sel = 0
            self.refresh()
            self.entry.grab_focus()

    def _changed(self):
        self.sel = 0
        self.refresh()

    def refresh(self):
        q = self.entry.get_text()
        self.items = self.win.qs_items(q, self.scope)
        while (c := self.list.get_first_child()):
            self.list.remove(c)
        self.rows = []
        last = ''
        for i, it in enumerate(self.items):
            group = 'AKTIONEN' if it['kind'] == 'create' else 'ORDNER' if it['kind'] == 'folder' else 'NOTIZEN' if q.strip() else 'ZULETZT GEÄNDERT'
            if group != last:
                head = Gtk.ListBoxRow(selectable=False, activatable=False)
                head.set_child(label(group, ['sec-label']))
                self.list.append(head)
                last = group
            row = Gtk.ListBoxRow()
            row.idx = i
            row.add_css_class('qs-row')
            row.set_child(self._row(it))
            self.list.append(row)
            self.rows.append(row)
        if not self.items:
            self.list.append(Gtk.ListBoxRow(selectable=False, activatable=False, child=label('Nichts gefunden.', ['dim-label'])))
        self._select(self.sel, scroll=False)

    def _row(self, it):
        b = Gtk.Box(spacing=12)
        ico = '+' if it['kind'] == 'create' else '▸' if it['kind'] == 'folder' else '·'
        b.append(label(ico, ['heading']))
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        if it['kind'] == 'create':
            t = Gtk.Label(xalign=0, ellipsize=3)
            t.set_markup(f'Neue Notiz „{esc(it["title"])}“ anlegen')
        else:
            t = Gtk.Label(xalign=0, ellipsize=3)
            p = it.get('parts')
            t.set_markup(f'{esc(p[0])}<span weight="bold" underline="single">{esc(p[1])}</span>{esc(p[2])}' if p else esc(it['title']))
        col.append(t)
        col.append(label(it['path'], ['mono', 'caption', 'dim-label'], ellipsize=True))
        b.append(col)
        if it.get('via') == 'text':
            b.append(label('im Text', ['caption', 'dim-label']))
        if it['kind'] == 'create':
            b.append(label('Strg ↵', ['kbd']))
        else:
            if it.get('area'):
                b.append(label(it['area'], ['caption', 'dim-label']))
            b.append(label(f'{it["count"]} Notizen' if it['kind'] == 'folder' else dash.when(it['mtime']), ['when']))
        return b

    def _select(self, i: int, scroll=True):
        if not self.rows:
            return
        self.sel = max(0, min(i, len(self.rows) - 1))
        row = self.rows[self.sel]
        self.list.select_row(row)
        if scroll:
            adj = self.sc.get_vadjustment()
            a = row.get_allocation()
            if a.y < adj.get_value():
                adj.set_value(a.y)
            elif a.y + a.height > adj.get_value() + adj.get_page_size():
                adj.set_value(a.y + a.height - adj.get_page_size())

    def _key(self, _c, keyval, _code, state):
        ctrl = bool(state & Gdk.ModifierType.CONTROL_MASK)
        if keyval in (Gdk.KEY_Down, Gdk.KEY_Up):
            self._select(self.sel + (1 if keyval == Gdk.KEY_Down else -1))
            return True
        if keyval in (Gdk.KEY_Return, Gdk.KEY_KP_Enter):
            if ctrl:
                create = [i for i, it in enumerate(self.items) if it['kind'] == 'create']
                if create:
                    self._run(create[0])
            else:
                self._run(self.sel)
            return True
        if keyval in (Gdk.KEY_Tab, Gdk.KEY_ISO_Left_Tab):
            step = -1 if keyval == Gdk.KEY_ISO_Left_Tab else 1
            self.chips[(self.SCOPES.index(self.scope) + step) % len(self.SCOPES)].set_active(True)
            return True
        return False

    def _run(self, i: int):
        if not 0 <= i < len(self.items):
            return
        it = self.items[i]
        self.close()
        GLib.idle_add(lambda: (self.win.run_item(it), GLib.SOURCE_REMOVE)[1])


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.win = None

    def do_startup(self):
        Adw.Application.do_startup(self)
        quit_ = Gio.SimpleAction.new('quit', None)
        quit_.connect('activate', lambda *_: self.quit())
        self.add_action(quit_)
        self.set_accels_for_action('app.quit', ['<Control>q'])
        from gi.repository import Gdk
        prov = Gtk.CssProvider()
        prov.load_from_string(CSS)
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    def do_activate(self):
        if self.win:
            self.win.present()
            return
        cfg = cfgmod.load()
        if cfg.ready:
            self.win = MainWindow(self, cfg)
            self.win.present()
        else:
            SetupWindow(self, cfg, on_done=self.restart).present()

    def restart(self):
        if self.win:
            self.win.close()
            self.win = None
        self.do_activate()


def main():
    return App().run(None)
