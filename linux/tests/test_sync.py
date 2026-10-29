"""Abgleich gegen den echten Notizen-Server (Node), gestartet in einem Wegwerf-Ordner."""
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux.sync import AuthError, Client, Syncer, valid_path  # noqa: E402

SERVER = Path(__file__).resolve().parents[2] / 'server'
TOKEN = 'testtokentesttokentesttoken1234'


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


@unittest.skipUnless(shutil.which('node') and (SERVER / 'node_modules').is_dir(), 'Node oder server/node_modules fehlt')
class SyncTest(unittest.TestCase):
    def setUp(self):
        # jede Prüfung bekommt einen frischen, leeren Server in einem Wegwerf-Ordner
        self.tmp = Path(tempfile.mkdtemp(prefix='notizen-test-'))
        self.port = free_port()
        env = dict(os.environ, DATA_DIR=str(self.tmp / 'data'), PORT=str(self.port), NOTES_TOKEN=TOKEN)
        self.proc = subprocess.Popen(['node', '--disable-warning=ExperimentalWarning', 'src/index.js'], cwd=SERVER, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.syncers = []
        for _ in range(100):
            try:
                urllib.request.urlopen(f'http://127.0.0.1:{self.port}/api/health', timeout=1).read()
                break
            except OSError:
                time.sleep(0.1)
        else:
            raise RuntimeError('Server startet nicht')

    def tearDown(self):
        for s in self.syncers:
            s.db.close()
        self.proc.terminate()
        self.proc.wait(5)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def device(self, name):
        root = self.tmp / name
        client = Client(f'http://127.0.0.1:{self.port}', TOKEN)
        s = Syncer(root, client, self.tmp / f'{name}.db')
        self.syncers.append(s)
        return s, root

    def p(self, rel):
        return rel

    def write(self, root, rel, text):
        f = root / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding='utf-8')

    def test_pfadregeln_wie_der_server(self):
        self.assertTrue(valid_path('Schule/Physik/Feld.md'))
        self.assertTrue(valid_path('Schule/.ink/1.json'))
        self.assertFalse(valid_path('Schule/1.json'))
        self.assertFalse(valid_path('.versteckt/a.md'))
        self.assertFalse(valid_path('a.txt'))
        self.assertFalse(valid_path('../a.md'))
        self.assertFalse(valid_path('a.md.tmp'))

    def test_neue_notiz_kommt_auf_das_zweite_geraet(self):
        a, ra = self.device('a1')
        b, rb = self.device('b1')
        self.write(ra, self.p('Physik/Feld.md'), '# Feld\n')
        self.write(ra, self.p('notiz.txt'), 'wird ignoriert')
        self.assertEqual(a.sync().pushed, 1)
        self.assertEqual(b.sync().pulled, 1)
        self.assertEqual((rb / self.p('Physik/Feld.md')).read_text(), '# Feld\n')
        self.assertFalse((rb / self.p('notiz.txt')).exists())

    def test_aenderung_wird_uebernommen(self):
        a, ra = self.device('a2')
        b, rb = self.device('b2')
        self.write(ra, self.p('x.md'), 'eins')
        a.sync(); b.sync()
        self.write(rb, self.p('x.md'), 'zwei')
        self.assertEqual(b.sync().pushed, 1)
        self.assertEqual(a.sync().pulled, 1)
        self.assertEqual((ra / self.p('x.md')).read_text(), 'zwei')
        self.assertFalse(a.sync().changed)  # nichts mehr zu tun

    def test_konflikt_bleibt_als_kopie_erhalten(self):
        a, ra = self.device('a3')
        b, rb = self.device('b3')
        self.write(ra, self.p('k.md'), 'start')
        a.sync(); b.sync()
        self.write(ra, self.p('k.md'), 'von A')
        self.write(rb, self.p('k.md'), 'von B')
        a.sync()
        rep = b.sync()
        self.assertEqual(len(rep.conflicts), 1)
        self.assertIn('Konflikt', rep.conflicts[0])
        self.assertEqual((rb / self.p('k.md')).read_text(), 'von A')  # Server gewinnt am Originalpfad
        self.assertEqual((rb / rep.conflicts[0]).read_text(), 'von B')  # die eigene Fassung geht nicht verloren
        b.sync(); a.sync()
        self.assertEqual((ra / rep.conflicts[0]).read_text(), 'von B')  # Kopie ist auch bei A angekommen

    def test_loeschen_wird_weitergegeben(self):
        a, ra = self.device('a4')
        b, rb = self.device('b4')
        self.write(ra, self.p('weg/l.md'), 'x')
        a.sync(); b.sync()
        (ra / self.p('weg/l.md')).unlink()
        self.assertEqual(a.sync().deleted, 1)
        self.assertEqual(b.sync().deleted, 1)
        self.assertFalse((rb / self.p('weg/l.md')).exists())

    def test_lokal_geaendert_aber_remote_geloescht_bleibt_lokal(self):
        a, ra = self.device('a5')
        b, rb = self.device('b5')
        self.write(ra, self.p('b.md'), 'alt')
        a.sync(); b.sync()
        (ra / self.p('b.md')).unlink()
        a.sync()
        self.write(rb, self.p('b.md'), 'neu bearbeitet')
        b.sync()
        self.assertEqual((rb / self.p('b.md')).read_text(), 'neu bearbeitet')
        a.sync()
        self.assertEqual((ra / self.p('b.md')).read_text(), 'neu bearbeitet')  # kam als neue Datei wieder hoch

    def test_falsches_token(self):
        s = Syncer(self.tmp / 'x', Client(f'http://127.0.0.1:{self.port}', 'falsch' * 6), self.tmp / 'x.db')
        with self.assertRaises(AuthError):
            s.sync()


if __name__ == '__main__':
    unittest.main()
