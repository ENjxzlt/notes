# Notizen-App – Spezifikation (Entwurf aus dem Interview, 2026-10-25)

## Motivation (ehrlich)
Eigenes Werkzeug statt Obsidian. Obsidian bleibt für Alltag/Projekte erst einmal bestehen. Die Entscheidung "etwas
Eigenes" ist gesetzt; ein konkreter Fall, an dem Obsidian im Unterricht gescheitert ist, wurde nicht genannt.
→ Beim ersten echten Schultag-Test protokollieren, was bremst (Zeit bis zur Notiz, Fummelei mit Stift/Tastatur).

## Nutzung
- Hauptgerät: Samsung-Android-Tablet mit S Pen und Tastatur, in der Schule, überwiegend Tastatur
- Fächer gemischt: Mathe/Physik/Info, Sprachen, Bio/Chemie → Text, Skizzen, später Formeln
- Zusätzlich Alltag und Projekte, auch gemeinsam mit Claude

## Entscheidungen
| Thema | Entscheidung |
|---|---|
| Wahrheit | Markdown-Dateien (Klartext, damit Claude und andere Tools sie lesen können) |
| Handschrift | Zeichenblöcke als angehängte Dateien (offenes Format: SVG oder Strich-JSON), in die Notiz eingebettet; kein freier Canvas |
| Struktur | Eine Ablage, feste Oberbereiche (Schule, Projekte, Alltag); Unterordner statt Tags |
| Navigation | Ordnerbaum (max. 2–3 Taps), Schnellsuche nach Titel, Stundenplan-Verknüpfung |
| Tablet | Nativ in Kotlin |
| PC | Web-Ansicht (Slotwise-Stack): Text bearbeiten, Zeichnungen nur anzeigen |
| Sync | Eigener Server auf TrueNAS, Server ist die Wahrheit, Tablet ist Offline-Cache |
| Claude | Zugriff nur über einen Remote-MCP-Server auf dem Server, nur auf Aufforderung (siehe unten) |
| Editor | Formatieren ohne Markdown-Syntax tippen: Überschrift/Liste/Fett per Tastenkürzel oder Schnellleiste; gespeichert wird trotzdem Markdown |
| PDF-Annotation | V2 |
| Formeln, Handschrifterkennung | später, nicht im MVP |

## Claude-Zugriff (Schulnotizen)
Auslöser: Bisher läuft Notizbearbeitung mit Claude nur über Claude Code in Termux auf dem Tablet. Das soll durch einen
MCP-Server ersetzt werden, sodass Claude Notizen remote lesen und bearbeiten kann.
- **Nur auf Aufforderung, nichts Automatisches.** Technisch durchgesetzt, nicht nur Konvention: Freigabe ist standardmäßig
  aus; in der App schaltest du sie ein (pro Notiz oder Ordner, mit Ablaufzeit, z. B. 1 h). Ohne Freigabe liefert der
  MCP-Server nichts, auch wenn der Connector in Claude verbunden bleibt
- Lesen und Schreiben getrennt freigebbar
- Jede Änderung von Claude erzeugt eine Version; in der App sichtbar und mit einem Tap rückgängig zu machen
- Wird eine Notiz gerade auf dem Tablet bearbeitet, schreibt Claude als Vorschlag statt direkt
- Remote-MCP über HTTPS (Cloudflare-Tunnel wie bei Slotwise) mit OAuth bzw. Token-Authentifizierung
- Werkzeuge (Entwurf): Ordner/Notizen auflisten, Notiz lesen, Notiz ersetzen, Abschnitt anhängen, suchen

## Editor-Anforderungen
- Kein Markdown-Syntax tippen müssen, um eine Überschrift zu bekommen: Schnellleiste und Tastenkürzel (z. B. Strg+1/2/3),
  Vorschau direkt im Text
- Kein "dauerhaftes Formatieren": der Text soll beim Schreiben nicht ständig springen oder umgebaut werden
- Markdown-Teilmenge für V1: Überschriften, Listen, Checkboxen, Fett/Kursiv, Code; Tabellen später

## Alleinstellungsmerkmal
Stundenplan-Verknüpfung: aktuelle Stunde (Quelle: Slotwise `/api/events`) öffnet direkt die Notiz des Fachs.
Regeln nötig für: Freistunde, Vertretung, Doppelstunde, Klausurtag, kein passendes Fach → zuletzt geöffnete Notiz.

## Meilensteine
0. Eine Woche Obsidian (zweiter Vault) + Excalidraw im Schulalltag, Samsung Notes als Rückfall; Mängel notieren
1. Spike: Zeichenfläche in Kotlin, S-Pen-Latenz und Palm Rejection. Fühlt es sich nicht besser an als Samsung Notes → Technik überdenken
2. Lokale V1 nur für Schule: Markdown-Editor, Ordnerbaum, Titelsuche, ein Zeichenblock je Notiz, Stundenplan-Öffnen
3. Server-Sync: Revisionsnummer je Datei, Konflikt = Kopie mit Zeitstempel, nie stillschweigend überschreiben; automatisches Backup
4. Claude-Zugriff: Remote-MCP mit Authentifizierung, Freigabe-Schalter mit Ablaufzeit, Versionen mit Rückgängig; Vorschlagsmodus bei offener Notiz
5. Web-Ansicht für den PC
6. Projekte/Alltag, [[Links]] und Backlinks, Volltextsuche
7. V2: PDF-Annotation

## Risiken
- Scope: Tablet-App + Server + Web + Claude-Schnittstelle ist für eine Person ein Jahr Arbeit → Reihenfolge strikt einhalten
- "Langfristig, kein Termin": ohne Meilenstein 2 im Unterricht stirbt das Projekt; bis dahin Samsung Notes/Obsidian benutzen
- Native Ink mit niedriger Latenz braucht `androidx.graphics`/`androidx.ink` (Zusatzbibliotheken, anders als Slotwise)
- Gleichzeitiges Schreiben von dir und Claude auf derselben Notiz → Konflikte
- Schulnotizen mit Namen von Personen: Claude sieht sie nur bei bewusster Freigabe (siehe Claude-Zugriff)
- Rich-Text-Editor mit Markdown-Speicherung: Hin-und-Zurück-Umwandlung darf Text nicht verändern; in Android gibt es dafür
  nichts Fertiges, der Editor ist ein eigenes Teilprojekt
- Remote-MCP für claude.ai braucht HTTPS und OAuth; schlecht abgesichert liest jeder mit der URL deine Notizen
- Unübersichtlichkeit ist auch ein Strukturproblem: feste Oberbereiche und Namensregeln von Anfang an festlegen

## Offene Fragen
- Typischer Schultag: Zahl und Länge der Notizen?
- Formeln später: LaTeX oder Handschrift?
- Nur persönlich, oder sollen Mitschüler es nutzen (Mehrbenutzer)?
- Stundenplan: Quelle weiterhin Slotwise, oder eigener Plan in der App?
