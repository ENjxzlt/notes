"""Sprache diktieren: Aufnahme mit dem, was der PC hat (PipeWire, PulseAudio oder ALSA), Umschrift mit einem Befehl.

Die Umschrift läuft lokal und offline. Ohne Befehl gibt `status()` eine Anleitung zurück; es wird nichts heruntergeladen.
Den Befehl legt man in `NOTIZEN_STT` oder in der Konfiguration (`stt_command`) fest, `{wav}` steht für die Aufnahme, z. B.

    whisper-cli -m ~/.local/share/notizen-linux/models/ggml-small.bin -l de -nt -np -f {wav}

Findet sich `whisper-cli` (whisper.cpp) und ein Modell `ggml-*.bin` im Ordner `models`, wird das ohne Einstellung benutzt.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
from pathlib import Path

from . import config as cfgmod

MODELS = cfgmod.DATA_DIR / 'models'
HINT = ('Zum Diktieren fehlt noch eine lokale Spracherkennung. Installiere whisper.cpp (Befehl „whisper-cli“) und lege ein Modell '
        f'(ggml-*.bin) nach {MODELS} – oder setze NOTIZEN_STT auf einen Befehl mit {{wav}}.')


def recorder_argv(out: str) -> list[str] | None:
    """Befehl, der 16-kHz-Mono-WAV aufnimmt, bis er mit SIGINT beendet wird."""
    if shutil.which('pw-record'):
        return ['pw-record', '--rate', '16000', '--channels', '1', out]
    if shutil.which('parecord'):
        return ['parecord', '--rate=16000', '--channels=1', '--file-format=wav', out]
    if shutil.which('arecord'):
        return ['arecord', '-q', '-f', 'S16_LE', '-r', '16000', '-c', '1', out]
    return None


def stt_argv(wav: str, configured: str = '') -> list[str] | None:
    """Umschrift-Befehl für `wav`: eingestellter Befehl, sonst whisper-cli mit dem ersten Modell im Ordner."""
    cmd = os.environ.get('NOTIZEN_STT') or configured
    if cmd:
        parts = shlex.split(cmd)
        return [p.replace('{wav}', wav) for p in parts] if any('{wav}' in p for p in parts) else parts + [wav]
    exe = shutil.which('whisper-cli') or shutil.which('whisper-cpp')
    models = sorted(MODELS.glob('ggml-*.bin')) if MODELS.is_dir() else []
    if exe and models:
        return [exe, '-m', str(models[-1]), '-l', 'de', '-nt', '-np', '-f', wav]
    return None


def status(configured: str = '') -> tuple[bool, str]:
    """(bereit, Hinweis) – für die Anzeige am Mikrofon-Knopf."""
    if recorder_argv('x.wav') is None:
        return False, 'Keine Aufnahme möglich: pw-record, parecord oder arecord fehlt.'
    if stt_argv('x.wav', configured) is None:
        return False, HINT
    return True, 'Mikrofon bereit'


def clean(text: str) -> str:
    """Zeitmarken und Geräuschhinweise („[BLANK_AUDIO]“, „(Musik)“) entfernen, Zeilen zusammenziehen."""
    text = re.sub(r'\[[^\]]*\]|\([^)]*\)', ' ', text)
    text = re.sub(r'^\s*\d+:\d+(:\d+)?([.,]\d+)?\s*-->.*$', ' ', text, flags=re.M)
    return re.sub(r'\s+', ' ', text).strip()


class Recording:
    def __init__(self):
        fd, self.path = tempfile.mkstemp(prefix='notizen-diktat-', suffix='.wav')
        os.close(fd)
        os.chmod(self.path, 0o600)
        argv = recorder_argv(self.path)
        if argv is None:
            raise RuntimeError('Keine Aufnahme möglich: pw-record, parecord oder arecord fehlt.')
        self.proc = subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self) -> str:
        """Beendet die Aufnahme und liefert den Pfad der WAV-Datei."""
        if self.proc.poll() is None:
            self.proc.send_signal(signal.SIGINT)
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        return self.path

    def discard(self) -> None:
        self.stop()
        Path(self.path).unlink(missing_ok=True)


def transcribe(wav: str, configured: str = '', timeout: float = 180) -> str:
    """Umschrift der Aufnahme; löscht die WAV-Datei danach. Wirft RuntimeError mit lesbarer Meldung."""
    try:
        argv = stt_argv(wav, configured)
        if argv is None:
            raise RuntimeError(HINT)
        try:
            r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise RuntimeError(f'Umschrift fehlgeschlagen: {e}') from e
        if r.returncode != 0:
            raise RuntimeError('Umschrift fehlgeschlagen: ' + (r.stderr.strip().splitlines() or ['unbekannter Fehler'])[-1])
        return clean(r.stdout)
    finally:
        Path(wav).unlink(missing_ok=True)
