import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from notizen_linux import dictate  # noqa: E402


class DictateTest(unittest.TestCase):
    def test_bereinigen(self):
        self.assertEqual(dictate.clean(' [BLANK_AUDIO]\n Das ist  eine (Musik) Idee. \n'), 'Das ist eine Idee.')
        self.assertEqual(dictate.clean('[00:00:00.000 --> 00:00:02.000]   Hallo Welt'), 'Hallo Welt')

    def test_befehl_mit_platzhalter(self):
        with mock.patch.dict(os.environ, {'NOTIZEN_STT': 'meinstt --lang de {wav}'}):
            self.assertEqual(dictate.stt_argv('/t/a.wav'), ['meinstt', '--lang', 'de', '/t/a.wav'])
        with mock.patch.dict(os.environ, {'NOTIZEN_STT': 'meinstt'}):
            self.assertEqual(dictate.stt_argv('/t/a.wav'), ['meinstt', '/t/a.wav'])

    def test_ohne_erkennung_klare_meldung(self):
        with mock.patch.dict(os.environ, {'NOTIZEN_STT': ''}), mock.patch('shutil.which', return_value=None):
            self.assertIsNone(dictate.stt_argv('x.wav'))
        with mock.patch('shutil.which', side_effect=lambda n: '/bin/arecord' if n == 'arecord' else None), mock.patch.dict(os.environ, {'NOTIZEN_STT': ''}):
            ok, hint = dictate.status()
            self.assertFalse(ok)
            self.assertIn('Spracherkennung', hint)

    def test_umschrift_laeuft_und_loescht_aufnahme(self):
        with tempfile.TemporaryDirectory() as d:
            script = Path(d) / 'stt.sh'
            script.write_text('#!/bin/sh\necho "[00:00 --> 00:01]  Physik Versuch wiederholen"\n')
            script.chmod(script.stat().st_mode | stat.S_IEXEC)
            wav = Path(d) / 'a.wav'
            wav.write_bytes(b'RIFF')
            with mock.patch.dict(os.environ, {'NOTIZEN_STT': f'{script} {{wav}}'}):
                self.assertEqual(dictate.transcribe(str(wav)), 'Physik Versuch wiederholen')
            self.assertFalse(wav.exists())

    def test_fehler_der_umschrift(self):
        with tempfile.TemporaryDirectory() as d:
            wav = Path(d) / 'a.wav'
            wav.write_bytes(b'x')
            with mock.patch.dict(os.environ, {'NOTIZEN_STT': 'false'}):
                with self.assertRaises(RuntimeError):
                    dictate.transcribe(str(wav))
            self.assertFalse(wav.exists())


if __name__ == '__main__':
    unittest.main()
