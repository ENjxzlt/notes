import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux import ink  # noqa: E402


class InkTest(unittest.TestCase):
    def test_strich_und_rueckgaengig(self):
        m = ink.Model(ink.Doc())
        m.down(10, 10, 0.5)
        m.move(20, 20, 0.7)
        self.assertTrue(m.up())
        self.assertEqual(len(m.doc.strokes), 1)
        self.assertTrue(m.undo())
        self.assertEqual(m.doc.strokes, [])
        self.assertFalse(m.undo())

    def test_radieren_ganzer_striche_und_undo(self):
        m = ink.Model(ink.Doc())
        for x in (10, 100):
            m.down(x, 10)
            m.move(x + 5, 10)
            m.up()
        m.tool = 'eraser'
        self.assertTrue(m.down(12, 10))
        self.assertTrue(m.up())
        self.assertEqual(len(m.doc.strokes), 1)
        self.assertEqual(m.doc.strokes[0].pts[0][0], 100)
        m.undo()
        self.assertEqual([s.pts[0][0] for s in m.doc.strokes], [10, 100])

    def test_speichern_laden_und_svg(self):
        with tempfile.TemporaryDirectory() as d:
            doc = ink.Doc(900)
            doc.strokes.append(ink.Stroke('pen', 'text', [[1.04, 2.0, 0.5], [3, 4, 1]]))
            doc.strokes.append(ink.Stroke('marker', '#e5484d', [[5, 5, 0.5]]))
            ink.save(Path(d), 'abc12345', doc)
            raw = json.loads((Path(d) / '.ink/abc12345.json').read_text())
            self.assertEqual((raw['v'], raw['w'], raw['h']), (1, 1000, 900))
            self.assertEqual(raw['strokes'][0], {'t': 'pen', 'c': 'text', 'p': [1.0, 2.0, 0.5, 3.0, 4.0, 1.0]})
            svg = (Path(d) / '.ink/abc12345.svg').read_text()
            self.assertIn('viewBox="0 0 1000 900"', svg)
            self.assertIn('stroke-opacity="0.35"', svg)
            self.assertIn('#18181b', svg)  # „text“ wird im SVG dunkel
            back = ink.load(Path(d), 'abc12345')
            self.assertEqual((back.height, len(back.strokes), back.strokes[1].color), (900, 2, '#e5484d'))

    def test_android_datei_wird_gelesen(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / '.ink').mkdir()
            (Path(d) / '.ink/x.json').write_text('{"v":1,"w":1000,"h":99999,"strokes":[{"t":"pen","c":"text","p":[1,2,0.5,3,4,0.6]}]}')
            doc = ink.load(Path(d), 'x')
            self.assertEqual((doc.height, len(doc.strokes[0].pts)), (3000, 2))

    def test_defekte_datei_wird_zur_seite_gelegt(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / '.ink').mkdir()
            (Path(d) / '.ink/k.json').write_text('{kaputt')
            self.assertEqual(ink.load(Path(d), 'k').strokes, [])
            self.assertTrue((Path(d) / '.ink/k.json.defekt').exists())

    def test_blockzeile(self):
        self.assertEqual(ink.BLOCK.match(ink.block_line('ab12cd34')).group(1), 'ab12cd34')
        self.assertIsNone(ink.BLOCK.match('Text ![Zeichnung](.ink/ab.svg)'))


if __name__ == '__main__':
    unittest.main()
