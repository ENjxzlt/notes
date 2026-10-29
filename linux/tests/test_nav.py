import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux import nav  # noqa: E402

PATHS = [
    'Schule/Physik/Elektrisches Feld.md',
    'Schule/Physik/Magnetismus.md',
    'Schule/Physik/Mechanik Grundlagen.md',
    'Schule/Mathe/Ableitung.md',
    'Schule/Chemie/Elektrolyse.md',
    'Projekte/Notizen-App.md',
    'Projekte/Basteln/Elektronik.md',
    'Alltag/Einkauf.md',
    'Eigene Notiz.md',
]
MT = {'Schule/Physik/Elektrisches Feld.md': 9, 'Schule/Chemie/Elektrolyse.md': 5, 'Projekte/Basteln/Elektronik.md': 7}
mtime = lambda p: MT.get(p, 1)  # noqa: E731
text = lambda p: 'hier geht es um das elektrische Potential' if p == 'Schule/Mathe/Ableitung.md' else None  # noqa: E731


class NavTest(unittest.TestCase):
    def test_ordnerzaehler_zaehlen_unterordner_mit(self):
        c = nav.folder_counts(PATHS)
        self.assertEqual((c['Schule'], c['Schule/Physik'], c['Projekte']), (5, 3, 2))
        self.assertNotIn('Eigene Notiz', c)

    def test_geschwisterordner(self):
        self.assertEqual([f['name'] for f in nav.folder_peers(PATHS, 'Schule')], ['Schule', 'Projekte', 'Alltag'])
        sub = nav.folder_peers(PATHS, 'Schule/Physik')
        self.assertEqual([f['name'] for f in sub], ['Chemie', 'Mathe', 'Physik'])
        cur = [f for f in sub if f['current']]
        self.assertEqual((cur[0]['name'], cur[0]['count']), ('Physik', 3))

    def test_blaettern(self):
        p = nav.pager(PATHS, 'Schule/Physik/Magnetismus.md')
        self.assertEqual((p['index'], p['total'], p['prev'], p['next']), (1, 3, 'Schule/Physik/Elektrisches Feld.md', 'Schule/Physik/Mechanik Grundlagen.md'))
        self.assertIsNone(nav.pager(PATHS, 'Schule/Physik/Elektrisches Feld.md')['prev'])
        self.assertEqual(nav.pager(PATHS, 'Eigene Notiz.md')['folder'], '')

    def test_rechtsklick_ziel(self):
        self.assertEqual(nav.ctx_folder('Schule/Physik', True), 'Schule/Physik')
        self.assertEqual(nav.ctx_folder('Schule/Physik/Magnetismus.md', False), 'Schule/Physik')
        self.assertEqual(nav.ctx_folder('Eigene Notiz.md', False), '')

    def test_pfadkette(self):
        self.assertEqual(nav.crumbs('Schule/Physik/Magnetismus.md'), [{'label': 'Schule', 'path': 'Schule'}, {'label': 'Physik', 'path': 'Schule/Physik'}])
        self.assertEqual(nav.crumbs('Eigene Notiz.md'), [])

    def test_verlauf_und_pins(self):
        r = nav.push_recent(nav.push_recent(nav.push_recent([], 'a.md'), 'b.md'), 'a.md')
        self.assertEqual(r, ['a.md', 'b.md'])
        self.assertEqual(len(nav.push_recent(['1', '2', '3'], '4', 3)), 3)
        pinned, lst = nav.toggle_pin([], 'a.md')
        self.assertEqual((pinned, lst), (True, ['a.md']))
        self.assertEqual(nav.toggle_pin(lst, 'a.md'), (False, []))
        self.assertEqual(nav.prune(['a.md', 'weg.md'], lambda p: p == 'a.md'), ['a.md'])

    def test_suche_titel_vor_pfad_vor_text(self):
        r = nav.search(PATHS, mtime, text, 'elek')
        self.assertEqual([x['title'] for x in r], ['Elektrisches Feld', 'Elektronik', 'Elektrolyse', 'Ableitung'])
        self.assertEqual(r[0]['parts'], ('', 'Elek', 'trisches Feld'))
        self.assertEqual((r[3]['via'], r[3]['parts']), ('text', None))

    def test_suche_bereichsfilter_und_leere_eingabe(self):
        self.assertTrue(all(x['area'] == 'Projekte' for x in nav.search(PATHS, mtime, text, 'elek', 'Projekte')))
        self.assertEqual(nav.search(PATHS, mtime, text, '')[0]['title'], 'Elektrisches Feld')

    def test_suche_mit_schraegstrich_findet_nur_ordner(self):
        r = nav.search(PATHS, mtime, text, '/phy')
        self.assertEqual([(x['kind'], x['path'], x['count']) for x in r], [('folder', 'Schule/Physik', 3)])
        self.assertTrue(all(x['kind'] == 'folder' for x in nav.search(PATHS, mtime, text, '/')))


if __name__ == '__main__':
    unittest.main()
