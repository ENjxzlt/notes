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
- Zeichnungen (`.ink`-Ordner) werden mit abgeglichen, aber nicht angezeigt oder bearbeitet.

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
Umbenennen und Verschieben von Notizen, Anzeige und Bearbeiten von Zeichnungen, Vorschau mit gerendertem Markdown,
Stundenplan-Karte aus Slotwise, „Von Claude geändert“ mit Rückgängig.
