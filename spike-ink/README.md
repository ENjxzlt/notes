# Ink-Spike

Winziger Test: Fühlt sich der S Pen in einer eigenen Android-App so gut an wie in Samsung Notes?
Reines Kotlin mit dem Android-Framework, keine Zusatzbibliotheken.

**Stand: Code geschrieben, aber noch nicht gebaut oder getestet** (in der Cloud-Umgebung war kein Android-SDK erreichbar).
Beim ersten Build können Kleinigkeiten zu korrigieren sein.

## Bauen und installieren
```
./build.sh                  # braucht Docker, wie android/build.sh bei Slotwise
adb install dist/ink-spike.apk   # oder APK aufs Tablet kopieren und antippen
```

## Was die App kann
- Stift zeichnet, Strichstärke folgt dem Druck
- Textmarker (feste Breite, transparent), Radierer (ganze Striche), Farben, Rückgängig, Alles löschen
- Finger zeichnet nicht (einfache Palm Rejection); meldet das System eine Berührung als Handballen
  (`ACTION_CANCEL`), wird der Strich verworfen
- Stifttaste (S Pen) radiert, solange sie gedrückt ist
- Zeile unten zeigt Werkzeugtyp, Druck, Neigung und Punkte pro Sekunde

## Testliste (auf dem Tablet, mit Samsung Notes daneben vergleichen)
1. Schnell schreiben und Kreise zeichnen: Hinkt der Strich dem Stift hinterher? Wirkt die Linie eckig?
2. Leicht und fest drücken: Ändert sich die Strichstärke sichtbar?
3. Handballen beim Schreiben auflegen: Entstehen Striche oder Punkte?
4. Stifttaste gedrückt halten und über Striche fahren: Radiert er?
5. Punkte pro Sekunde im Infotext: Bei 120–240 liefert das Gerät dichte Stiftdaten. Deutlich weniger = Problem.
6. Zwei Minuten lang viel zeichnen: Wird es langsamer? (Bitmap plus Neuzeichnen des aktuellen Strichs)

## Bekannte Grenzen des Spikes
- Kein Lasso, kein Verschieben, kein Zoomen/Scrollen, kein Speichern
- Latenz-Bestwerte bräuchten `androidx.graphics` (Front-Buffer-Rendering); wenn Test 1 enttäuscht, ist das der nächste Schritt
- Radierer löscht ganze Striche, kein Teilradieren
