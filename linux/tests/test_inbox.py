import sys
import tempfile
import unittest
from datetime import date, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux import inbox  # noqa: E402


class InboxTest(unittest.TestCase):
    def test_inbox_anhaengen_einlesen_entfernen(self):
        c = inbox.append_idea('', 'Idee eins', '2026-10-29 12:31')
        c = inbox.append_idea(c, 'Zeile A\nZeile B', '2026-10-29 12:40')
        self.assertEqual(c, '# Inbox\n\n- 2026-10-29 12:31 · Idee eins\n- 2026-10-29 12:40 · Zeile A / Zeile B\n')
        ideas = inbox.parse_ideas(c)
        self.assertEqual([i.text for i in ideas], ['Idee eins', 'Zeile A / Zeile B'])
        self.assertEqual(inbox.remove_idea(c, ideas[0].raw), '# Inbox\n\n- 2026-10-29 12:40 · Zeile A / Zeile B\n')
        self.assertEqual(inbox.append_idea(c, '   ', 'x'), c)
        self.assertEqual(inbox.stamp(datetime(2026, 10, 29, 7, 5)), '2026-10-29 07:05')

    def test_fristen(self):
        today = date(2026, 10, 29)  # Donnerstag
        p = lambda s: inbox.parse_due(s, today)  # noqa: E731
        self.assertEqual((p('Freitag'), p('bis fr'), p('donnerstag'), p('Montag')), ('30.10.', '30.10.', '29.10.', '02.11.'))
        self.assertEqual((p('morgen'), p('3.11.')), ('30.10.', '03.11.'))
        self.assertEqual((p('31.2.'), p(''), p('irgendwann')), (None, None, None))

    def test_aufgaben(self):
        line = inbox.task_line('Übungsblatt 5 abgeben', 45, '30.10.')
        self.assertEqual(line, '- [ ] Übungsblatt 5 abgeben (~45 min · bis 30.10.)')
        self.assertEqual(inbox.task_line('Milch', 10, None), '- [ ] Milch (~10 min)')
        self.assertEqual(inbox.task_line('Nur Titel', None, None), '- [ ] Nur Titel')
        note = f'# Ableitung\n\n{line}\n- [x] fertig\n```\n- [ ] im Code\n```\n- [ ] Milch (~10 min)\n- [ ] Ohne Angaben'
        t = inbox.parse_tasks(note)
        self.assertEqual([x.title for x in t], ['Übungsblatt 5 abgeben', 'Milch', 'Ohne Angaben'])
        self.assertEqual([x.minutes for x in t], [45, 10, None])
        self.assertEqual((t[0].due, t[1].due), ('30.10.', None))
        self.assertIn('- [x] Übungsblatt 5 abgeben (~45 min · bis 30.10.)', inbox.check_task(note, t[0].line, 'Übungsblatt 5 abgeben'))
        self.assertIsNone(inbox.check_task(note, t[0].line, 'Anderer Titel'))
        self.assertEqual(inbox.append_line('', 'T', '- [ ] x'), '# T\n\n- [ ] x\n')

    def test_titel_aus_idee(self):
        self.assertEqual(inbox.title_from_idea('GlutTube: Wiedergabelisten nach Stimmung sortieren, z. B. "Fokus"'), 'GlutTube- Wiedergabelisten nach Stimmung sortier')
        self.assertEqual(inbox.title_from_idea('...'), 'Idee')

    def test_dateizugriff(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self.assertTrue(inbox.add_idea(root, 'Erste Idee'))
            self.assertFalse(inbox.add_idea(root, '  '))
            idea = inbox.ideas(root)[0]
            self.assertEqual(idea.text, 'Erste Idee')
            rel = inbox.add_task(root, 'Schule', 'Blatt abgeben', 45, '30.10.')
            self.assertEqual(rel, 'Schule/Aufgaben.md')
            tasks = inbox.all_tasks(root, [rel])
            self.assertEqual((tasks[0].title, tasks[0].minutes, tasks[0].due, tasks[0].path), ('Blatt abgeben', 45, '30.10.', rel))
            self.assertTrue(inbox.tick_task(root, tasks[0]))
            self.assertEqual(inbox.all_tasks(root, [rel]), [])
            self.assertEqual(inbox.note_from_idea(root, 'Alltag', idea.text), 'Alltag/Erste Idee.md')
            self.assertEqual(inbox.note_from_idea(root, 'Alltag', idea.text), 'Alltag/Erste Idee 2.md')
            inbox.drop_idea(root, idea.raw)
            self.assertEqual(inbox.ideas(root), [])


if __name__ == '__main__':
    unittest.main()
