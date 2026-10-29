# Notizen für Linux

Nativer Client (GTK 4 / libadwaita, Python) mit Offline-Kopie. Die Notizen liegen als echte `.md`-Dateien in einem lokalen
Ordner (Standard `~/Notizen`); die App gleicht ihn im Hintergrund mit dem Notizen-Server ab. Ohne Netz arbeitet man einfach weiter,
beim nächsten Abgleich wird nachgeholt. Der Ordner lässt sich auch mit jedem anderen Editor oder mit Claude Code benutzen.

## Funktionen
- **Übersicht** und je ein Dashboard für **Schule**, **Projekte**, **Alltag** (und jeden Unterordner): Bereichskarten, Aktivitäts-Graph,
  zuletzt bearbeitet, offene Aufgaben (`- [ ]` im Text). Alles aus dem lokalen Ordner, also auch offline.
- Editor mit Format-Leiste und Kürzeln (Strg+1/2/3 Überschrift, Strg+B fett, Strg+I kursiv, Strg+E Code), Markdown bleibt sichtbar,
  Überschriften, Fett, Code und erledigte Aufgaben sind hervorgehoben. Speichert selbst.
- **Abgleich** wie in der Android-App: Revision je Datei, nie stilles Überschreiben. Bei Abweichung bleibt die eigene Fassung als
  „Name (Konflikt Datum Uhrzeit).md“ erhalten. Änderungen von anderen Geräten (und von Claude) erscheinen sofort (Server-Sent Events).
- Löschen wird weitergegeben; war eine Notiz lokal geändert und auf dem Server gelöscht, bleibt die lokale Fassung und geht neu hoch.
- **Zeichnen**: „✎ Zeichnen“ (Strg+Umschalt+D) fügt einen Zeichenblock ein und öffnet den Editor mit Stift (druckempfindlich), Marker, Radierer, Farben, Rückgängig und Seite verlängern. Stift und Maus zeichnen, Finger nicht (Handballen). Dateiformat wie in der Android-App (`.ink/<id>.json` und `.svg`), Vorschau in der Notiz, Klick öffnet den Editor. Die Zeichenebene über der ganzen Notiz gibt es hier noch nicht.

## Ideen-Dump
- Eingabezeile „Idee festhalten“ oben in Übersicht und Bereichen, in der App außerdem **Strg+Alt+I**. Ideen landen als Zeilen in `Inbox.md`
  (`- 2026-10-29 12:31 · Text`) und werden wie alle Notizen abgeglichen.
- **Globales Tastenkürzel:** `notizen-linux capture` öffnet ein kleines Fenster, auch wenn die App zu ist. In den Systemeinstellungen
  (KDE: Kurzbefehle → Benutzerdefiniert) ein Kürzel auf diesen Befehl legen. Der Eintrag „Idee festhalten“ steht auch im Menü.
- **Inbox** (Wochendurchsicht): Idee für Idee zur Aufgabe machen (Bereich, Dauer, Frist wie „Freitag“), als Notiz ablegen, auf später schieben
  oder löschen. **Aufgaben** zeigt alle `- [ ]` aus allen Notizen mit Dauer und Frist, Abhaken schreibt `- [x]`. **Rückblick** fasst die Woche zusammen.
- Format der Aufgaben: `- [ ] Titel (~45 min · bis 30.10.)`. Foto gibt es hier nicht (nur in Web und App).
- **Diktieren**: Mikrofon-Knopf neben der Ideen-Zeile (und im Fenster Strg+Alt+I, `notizen-linux capture`). Aufnahme mit `pw-record`/`parecord`/`arecord`, Umschrift lokal mit einem Befehl: `whisper-cli` (whisper.cpp) mit einem Modell `ggml-*.bin` in `~/.local/share/notizen-linux/models/`, oder eigener Befehl in `NOTIZEN_STT` (`{wav}` = Aufnahme). Ohne Spracherkennung zeigt die App eine Anleitung, es wird nichts heruntergeladen.

## Installieren
Voraussetzung: Python 3.11+, GTK 4, libadwaita 1.5+ und PyGObject (Arch: `python-gobject gtk4 libadwaita`).

```bash
./install.sh
```

Das legt den Starter `~/.local/bin/notizen-linux` und einen Eintrag im Anwendungsmenü an. Beim ersten Start fragt die App nach
Server-Adresse (`https://notizen.minihomelab.online`), dem `NOTES_TOKEN` und dem Ordner. Die Verbindungsdaten liegen in
`~/.config/notizen-linux/config.json` (Rechte 600), der Abgleichstand in `~/.local/share/notizen-linux/state.db`.

Ohne Oberfläche, z. B. für einen Timer: `notizen-linux sync` gleicht einmal ab.

## Tests
```bash
python3 -I -m unittest discover -s tests          # Abgleich gegen einen echten, lokal gestarteten Server (braucht node und server/node_modules)
```
`tests/gui_smoke.py` steuert die Oberfläche direkt (Kopfzeile des Skripts erklärt den Aufruf, z. B. mit `gtk4-broadwayd`).

## Noch nicht
Verschieben von Notizen, Zeichenebene über der ganzen Notiz, Vorschau mit gerendertem Markdown,
Stundenplan-Karte aus Slotwise, „Von Claude geändert“ mit Rückgängig.
