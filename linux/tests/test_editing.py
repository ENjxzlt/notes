import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux.editing import continue_list, toggle_checkbox  # noqa: E402


class EditingTest(unittest.TestCase):
    def test_liste_fortsetzen(self):
        self.assertEqual(continue_list('- Punkt'), '- ')
        self.assertEqual(continue_list('* Punkt'), '* ')
        self.assertEqual(continue_list('  - eingerückt'), '  - ')
        self.assertEqual(continue_list('- [x] erledigt'), '- [ ] ')
        self.assertEqual(continue_list('- [ ] offen'), '- [ ] ')
        self.assertEqual(continue_list('7. Sieben'), '8. ')

    def test_leerer_punkt_beendet_die_liste(self):
        self.assertEqual(continue_list('- '), '')
        self.assertEqual(continue_list('- [ ] '), '')
        self.assertEqual(continue_list('3. '), '')

    def test_keine_liste(self):
        self.assertIsNone(continue_list('Normaler Text'))
        self.assertIsNone(continue_list('# Überschrift'))
        self.assertIsNone(continue_list('-kein Leerzeichen'))

    def test_kaestchen_umschalten(self):
        self.assertEqual(toggle_checkbox('- [ ] Milch', 2), '- [x] Milch')
        self.assertEqual(toggle_checkbox('- [x] Milch', 3), '- [ ] Milch')
        self.assertEqual(toggle_checkbox('  * [X] Milch', 5), '  * [ ] Milch')
        self.assertIsNone(toggle_checkbox('- [ ] Milch', 8))  # Klick im Text, nicht im Kästchen
        self.assertIsNone(toggle_checkbox('- Milch [ ]', 9))
        self.assertIsNone(toggle_checkbox('Text', 1))


if __name__ == '__main__':
    unittest.main()
