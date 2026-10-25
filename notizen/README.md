# Notizen-App (Meilenstein 2, Schritte 1+2)

Kotlin, nur Android-Framework. Notizen liegen als normale `.md`-Dateien im Ordner `Notizen` des Gerätespeichers
(`/storage/emulated/0/Notizen`; Schule, Projekte, Alltag werden angelegt). Dafür braucht die App „Zugriff auf alle
Dateien“. Alte Notizen aus dem App-Speicher werden einmalig dorthin kopiert.

- Ordnerbaum (Tippen = öffnen, lange drücken = umbenennen/löschen), Titelsuche über alle Ordner
- Tablet breit: Baum links, Editor rechts; schmal: nacheinander
- Editor: Überschriften groß, Aufzählungen farbig, Markierungszeichen grau; Speichern automatisch
- Schnellleiste: H1 H2 H3 Liste Nummer Aufgabe Fett Kursiv Code
- Tastatur: Strg+1/2/3 Überschrift, Strg+B Fett, Strg+I Kursiv, Strg+E Code; Enter setzt Aufzählungen fort,
  Enter auf leerem Punkt beendet die Liste

## Stundenplan (Slotwise + vpmobil24)
Slotwise holt den Plan von stundenplan24.de (Einstellungen → Kalender → Schulplan, Kursauswahl dort), zeigt ihn im
Kalender, blockiert Zeit für den Planer und liefert ihn unter `/school/<Abo-Token>.json`. Die Notizen-App verbindet
sich mit dem Slotwise-Abo-Link (Mehr → Stundenplan von Slotwise verbinden) und zeigt oben eine Karte mit laufender
oder nächster Stunde. Tippen öffnet den Fach-Ordner (einmalig zuordnen, langes Drücken ändert die Zuordnung).
Der Plan wird zwischengespeichert und funktioniert offline.

## Bauen
GitHub Actions (`.github/workflows/notizen.yml`) baut bei jedem Push nach `notizen/**` und veröffentlicht die APK
unter Releases (`notizen-<Nr>`). Die Signatur (`debug.keystore`, öffentlich bekanntes Standardpasswort) ist fest,
damit neue Builds über alte installiert werden können, ohne dass Notizen verloren gehen.

## Noch nicht
Zeichenblock in Notizen, Stundenplan, Sync, Backup. Deinstallieren löscht die Notizen nicht mehr, einen Papierkorb gibt es noch nicht.
