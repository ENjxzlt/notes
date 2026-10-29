import json
import sys
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux import schoolplan as sp  # noqa: E402


def iso(y, mo, d, h, mi):
    return datetime(y, mo, d, h, mi).astimezone().isoformat()


def ts(y, mo, d, h, mi):
    return datetime(y, mo, d, h, mi).timestamp()


PLAN = {'lessons': [
    {'start': iso(2026, 10, 29, 8, 0), 'end': iso(2026, 10, 29, 8, 45), 'period': 1, 'subject': 'Ma', 'teacher': 'Kr', 'room': '204', 'changed': False, 'cancelled': False},
    {'start': iso(2026, 10, 29, 8, 50), 'end': iso(2026, 10, 29, 9, 35), 'period': 2, 'subject': 'En', 'teacher': '---', 'room': '', 'changed': False, 'cancelled': True},
    {'start': iso(2026, 10, 29, 9, 50), 'end': iso(2026, 10, 29, 10, 35), 'period': 3, 'subject': 'Ph', 'teacher': 'Be', 'room': '12', 'changed': True, 'cancelled': False},
    {'start': iso(2026, 10, 30, 8, 0), 'end': iso(2026, 10, 30, 8, 45), 'period': 1, 'subject': 'Bi', 'teacher': 'Su', 'room': '3', 'changed': False, 'cancelled': False},
]}


class SchoolTest(unittest.TestCase):
    def test_link(self):
        self.assertEqual(sp.parse_link('https://slotwise.minihomelab.online/ical/abc_DEF-123.ics'), ('https://slotwise.minihomelab.online', 'abc_DEF-123'))
        self.assertEqual(sp.parse_link('webcals://x.de/ical/tok'), ('https://x.de', 'tok'))
        self.assertIsNone(sp.parse_link('https://x.de/kalender'))

    def test_naechste_stunde(self):
        ls = sp.lessons_of(PLAN)
        self.assertEqual(len(ls), 4)
        lsn, running = sp.upcoming(ls, ts(2026, 10, 29, 8, 20))
        self.assertEqual((lsn.subject, running), ('Ma', True))
        lsn, running = sp.upcoming(ls, ts(2026, 10, 29, 8, 46))  # Entfall wird übersprungen
        self.assertEqual((lsn.subject, running), ('Ph', False))
        self.assertIsNone(sp.upcoming(ls, ts(2026, 10, 29, 11, 0)))  # heute nichts mehr, morgen zählt nicht

    def test_karte(self):
        ls = sp.lessons_of(PLAN)
        c = sp.card(ls[0], True)
        self.assertEqual((c.head, c.title, c.sub), ('Jetzt · 1. Std · 08:00–08:45', 'Ma', 'Raum 204 · Kr'))
        c = sp.card(ls[2], False)
        self.assertEqual((c.head, c.sub), ('Als Nächstes · 3. Std · 09:50–10:35', 'Raum 12 · Be · Vertretung'))

    def test_laden_speichern_zuordnen(self):
        seen = {}

        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                seen['path'], seen['ua'] = self.path, self.headers.get('User-Agent')
                body = json.dumps(PLAN).encode()
                self.send_response(200 if '/school/tok123.json' in self.path else 404)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        srv = HTTPServer(('127.0.0.1', 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as d:
                school = sp.School(Path(d) / 'school.json')
                self.assertFalse(school.connected)
                self.assertFalse(school.connect('kein link'))
                self.assertTrue(school.connect(f'http://127.0.0.1:{srv.server_port}/ical/tok123.ics'))
                self.assertEqual(school.refresh(), 4)
                self.assertIn('/school/tok123.json?from=', seen['path'])
                self.assertEqual(seen['ua'], 'notizen-linux/0.1')
                self.assertEqual(oct((Path(d) / 'school.json').stat().st_mode & 0o777), '0o600')
                self.assertEqual(len(sp.School(Path(d) / 'school.json').lessons()), 4)  # überlebt einen Neustart (offline)
                root = Path(d) / 'Notizen'
                (root / 'Schule/Mathe').mkdir(parents=True)
                self.assertIsNone(school.folder_for(root, 'Ma'))
                school.set_folder('Ma', 'Schule/Mathe')
                self.assertEqual(school.folder_for(root, 'Ma'), 'Schule/Mathe')
                school.connect(f'http://127.0.0.1:{srv.server_port}/ical/falsch.ics')
                with self.assertRaises(RuntimeError):
                    school.refresh()
                school.disconnect()
                self.assertFalse(school.connected)
        finally:
            srv.shutdown()


if __name__ == '__main__':
    unittest.main()
