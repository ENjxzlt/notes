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
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from . import config as cfgmod  # noqa: E402
from . import dash  # noqa: E402
from .sync import AuthError, Client, ServerError, Syncer, sha256  # noqa: E402

APP_ID = 'online.minihomelab.NotizenLinux'

# Farben und Maße wie in Slotwise: sachlich, ein Blau-Akzent, feine Linien statt Schatten.
CSS = """
@define-color accent_bg_color #1d4ed8;
@define-color accent_color #2f5fe8;
window, textview { font-family: "IBM Plex Sans", "Cantarell", sans-serif; }
.mono, .eyebrow, .when { font-family: "IBM Plex Mono", monospace; }
.eyebrow { font-size: 11px; letter-spacing: 1px; opacity: .7; }
.area-card { padding: 16px 20px; border-radius: 12px; background: @card_bg_color; border: 1px solid alpha(@window_fg_color, .12); box-shadow: none; }
.area-card:hover { border-color: @accent_bg_color; }
.when { font-size: 12px; opacity: .7; }
.lv0, .lv1, .lv2, .lv3, .lv4 { min-width: 13px; min-height: 13px; border-radius: 3px; }
.lv0 { background: alpha(@window_fg_color, .10); }
.lv1 { background: mix(@card_bg_color, @accent_bg_color, .30); }
.lv2 { background: mix(@card_bg_color, @accent_bg_color, .55); }
.lv3 { background: mix(@card_bg_color, @accent_bg_color, .80); }
.lv4 { background: @accent_bg_color; }
.fmt button { min-width: 36px; min-height: 32px; }
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
        super().__init__(title='Mit dem Notizen-Server verbinden', modal=bool(parent), default_width=480)
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
        self._build()
        self.worker = Worker(self.syncer, self.on_report, self.on_status)
        self.worker.start()
        self.refresh_tree()
        self.show_area('')
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

        self.toasts = Adw.ToastOverlay()
        split = Adw.NavigationSplitView(min_sidebar_width=250, max_sidebar_width=320)
        split.set_sidebar(Adw.NavigationPage.new(self._sidebar(), 'Notizen'))
        split.set_content(Adw.NavigationPage.new(self._content(), 'Inhalt'))
        self.toasts.set_child(split)
        self.set_content(self.toasts)

    def _sidebar(self):
        tv = Adw.ToolbarView()
        hb = Adw.HeaderBar()
        hb.set_title_widget(Adw.WindowTitle(title='Notizen', subtitle=''))
        add = Gtk.Button(icon_name='list-add-symbolic', tooltip_text='Neue Notiz (Strg+N)')
        add.set_action_name('win.new')
        hb.pack_start(add)
        menu = Gio.Menu()
        menu.append('Jetzt abgleichen', 'win.sync')
        menu.append('Verbindung …', 'win.settings')
        menu.append('Beenden', 'app.quit')
        hb.pack_end(Gtk.MenuButton(icon_name='open-menu-symbolic', menu_model=menu))
        tv.add_top_bar(hb)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.nav = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE, margin_start=8, margin_end=8, margin_top=6)
        self.nav.add_css_class('navigation-sidebar')
        for name, area in (('Übersicht', ''), ('Schule', 'Schule'), ('Projekte', 'Projekte'), ('Alltag', 'Alltag')):
            row = Gtk.ListBoxRow()
            row.area = area
            b = Gtk.Box(spacing=10, margin_top=6, margin_bottom=6)
            b.append(Gtk.Image(icon_name='view-grid-symbolic' if not area else 'folder-symbolic'))
            b.append(label(name))
            row.set_child(b)
            self.nav.append(row)
        self.nav.connect('row-activated', lambda _l, r: self.show_area(r.area))
        box.append(self.nav)

        self.search = Gtk.SearchEntry(placeholder_text='Titel suchen', margin_start=12, margin_end=12, margin_top=6, margin_bottom=6)
        self.search.connect('search-changed', self._on_search)
        box.append(self.search)

        self.store = None
        self.tree = Gtk.TreeView(headers_visible=False, enable_search=False, activate_on_single_click=True)
        col = Gtk.TreeViewColumn('Name', Gtk.CellRendererText(ellipsize=3), text=0)
        self.tree.append_column(col)
        self.tree.get_selection().connect('changed', self._on_tree_selection)
        self.tree.connect('row-expanded', lambda _t, it, path: self._remember(it, True))
        self.tree.connect('row-collapsed', lambda _t, it, path: self._remember(it, False))
        sc = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        sc.set_child(self.tree)
        box.append(sc)

        self.status = label('', ['mono', 'dim-label'], ellipsize=True)
        self.status.set_margin_start(12)
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
        hb.set_title_widget(self.title)
        self.del_btn = Gtk.Button(icon_name='user-trash-symbolic', tooltip_text='Notiz löschen', visible=False)
        self.del_btn.set_action_name('win.delete')
        hb.pack_end(self.del_btn)
        tv.add_top_bar(hb)

        self.stack = Gtk.Stack()
        self.dash_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=22, margin_top=24, margin_bottom=32, margin_start=24, margin_end=24)
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
        self.text = Gtk.TextView(buffer=self.buffer, wrap_mode=Gtk.WrapMode.WORD_CHAR, left_margin=28, right_margin=28, top_margin=22, bottom_margin=60, pixels_below_lines=4)
        self.text.add_css_class('note-text')
        self.buffer.connect('changed', self._on_changed)
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
        for i in range(4):
            r = self.nav.get_row_at_index(i)
            if top is not None and r.area == top:
                row = r
        if row:
            self.nav.select_row(row)
        else:
            self.nav.unselect_all()

    def show_area(self, area: str):
        self.flush_save()
        self.area, self.current = area, None
        self.del_btn.set_visible(False)
        self.title.set_title(area.split('/')[-1] if area else 'Übersicht')
        self.title.set_subtitle(area + '/' if area else datetime.now().strftime('%A, %d. %B').upper())
        self._select_nav(area)
        self.build_dash()
        self.stack.set_visible_child_name('dash')

    # ---------- Baum ----------

    def refresh_tree(self):
        notes = dash.scan(self.root)
        q = self.query.strip().lower()
        store = Gtk.TreeStore(str, str, bool)
        nodes = {}
        key = lambda n: [(0 if i < len(p) - 1 else 1, seg.lower()) for p in [n.path.split('/')] for i, seg in enumerate(p)]
        for n in sorted(notes, key=key):
            if q and q not in n.path.lower():
                continue
            parts = n.path.split('/')
            parent, acc = None, ''
            for seg in parts[:-1]:
                acc = f'{acc}/{seg}' if acc else seg
                if acc not in nodes:
                    nodes[acc] = store.append(parent, [seg, acc, True])
                parent = nodes[acc]
            store.append(parent, [parts[-1].removesuffix('.md'), n.path, False])
        self._tree_lock = True
        self.tree.set_model(store)
        self.store = store
        for acc, it in nodes.items():
            if q or acc in self.open_folders:
                self.tree.expand_row(store.get_path(it), False)
        self._tree_lock = False

    def _remember(self, it, opened):
        if getattr(self, '_tree_lock', False):
            return
        path = self.store.get_value(it, 1)
        (self.open_folders.add if opened else self.open_folders.discard)(path)

    def _on_search(self, entry):
        self.query = entry.get_text()
        self.refresh_tree()

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
        notes = dash.scan(self.root)
        scoped = [n for n in notes if dash.in_area(n.path, self.area)]
        tasks = dash.tasks_of(self.root, scoped)

        head = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        head.append(label((self.area + '/') if self.area else datetime.now().strftime('%d.%m.%Y'), ['eyebrow']))
        head.append(label(self.area.split('/')[-1] if self.area else 'Übersicht', ['title-1']))
        box.append(head)

        groups = dash.folders(scoped, self.area)
        if groups:
            box.append(label('Ordner' if self.area else 'Bereiche', ['heading']))
            flow = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=14, row_spacing=14, homogeneous=True, min_children_per_line=1, max_children_per_line=4)
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

        row = Gtk.Box(spacing=18, homogeneous=True, valign=Gtk.Align.START)
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
        self._loading = False
        self.current, self.loaded_hash, self.dirty = rel, sha256(data), False
        self._highlight()
        parts = rel.removesuffix('.md').split('/')
        self.title.set_title(parts[-1])
        self.title.set_subtitle(' › '.join(parts[:-1]))
        self.del_btn.set_visible(True)
        self._select_nav(parts[0] if len(parts) > 1 else '')
        self.stack.set_visible_child_name('note')
        self.text.grab_focus()

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
        return self.buffer.get_text(s, e, False)

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

    def _highlight(self):
        b = self.buffer
        s, e = b.get_bounds()
        b.remove_all_tags(s, e)
        text = b.get_text(s, e, False)
        pos, fence = 0, False
        at = lambda a, z, tag: b.apply_tag_by_name(tag, b.get_iter_at_offset(a), b.get_iter_at_offset(z))
        for line in text.split('\n'):
            end = pos + len(line)
            if re.match(r'^\s*```', line):
                fence = not fence
                at(pos, end, 'code')
            elif fence:
                at(pos, end, 'code')
            else:
                if (m := re.match(r'^(#{1,3}) ', line)):
                    at(pos, end, f'h{len(m.group(1))}')
                elif line.startswith('> '):
                    at(pos, end, 'quote')
                elif re.match(r'^\s*[-*] \[[xX]\] ', line):
                    at(pos, end, 'done')
                for m in re.finditer(r'\*\*(.+?)\*\*', line):
                    at(pos + m.start(), pos + m.end(), 'bold')
                for m in re.finditer(r'(?<![*\w])\*(?!\s|\*)(.+?)(?<!\s)\*(?![*\w])', line):
                    at(pos + m.start(), pos + m.end(), 'italic')
                for m in re.finditer(r'`(.+?)`', line):
                    at(pos + m.start(), pos + m.end(), 'code')
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
            line = b.get_text(start, end, False)
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
            sel = b.get_text(s, e, False)
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
            rel = rel.removesuffix('.md') + '.md'
            p = self.root / rel
            if not p.exists():
                p.parent.mkdir(parents=True, exist_ok=True)
                title = rel.removesuffix('.md').split('/')[-1]
                p.write_text(f'# {title}\n\n', encoding='utf-8')
                self.request_sync(delay=500)
            self.refresh_tree()
            self.open_note(rel)

        self._ask('Neue Notiz', 'Ordner und Titel, getrennt mit /. Fehlende Ordner werden angelegt.', 'Anlegen', create, entry)

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
