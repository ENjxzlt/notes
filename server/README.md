# Notizen-Server

Sync für die Notizen-App: Der Server hält eine Kopie des Notizen-Ordners (`.md`-Dateien und `.ink`-Zeichnungen) und
verteilt Änderungen an alle Geräte. Gedacht für den TrueNAS-Server neben Slotwise.

## Funktionsweise
- Jede Datei hat eine **Revision**. Ein Gerät darf nur schreiben, wenn es die aktuelle Revision kennt (`base`).
  Sonst antwortet der Server mit `409 Konflikt`, und die App legt eine Kopie „Name (Konflikt …).md" an.
  Nichts wird stillschweigend überschrieben.
- Gelöschte Dateien bleiben als Grabstein in der Datenbank, damit auch Geräte, die lange offline waren, das Löschen erfahren.
- **Sicherung:** täglich eine Kopie von `vault/` und `notes.db` nach `data/backups/<Datum>/`, die letzten 14 Tage bleiben.
- Zugriff nur mit `Authorization: Bearer <NOTES_TOKEN>`; nach 10 Fehlversuchen pro IP 15 Minuten Sperre.
  **Nur über HTTPS erreichbar machen** (z. B. Cloudflare-Tunnel auf `http://192.168.178.179:3090`), das Token läuft sonst im Klartext.

## Schnittstelle
| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/health` | Lebenszeichen (ohne Token) |
| GET | `/api/changes?since=N` | alle Änderungen nach Nummer N: `{seq, files:[{path, rev, hash, size, mtime, deleted, seq}]}` |
| GET | `/api/file?path=P` | Inhalt, Header `X-Rev`, `X-Hash` |
| PUT | `/api/file?path=P&base=R` | schreiben (R = bekannte Revision, 0 = neu); `409` bei Konflikt |
| DELETE | `/api/file?path=P&base=R` | löschen; `409` bei Konflikt |

Erlaubt sind `.md` überall sowie `.json`/`.svg` nur in `.ink`-Ordnern (Zeichnungen); höchstens 25 MB je Datei.

## Betrieb
```
cp .env.example .env      # NOTES_TOKEN eintragen
docker compose up -d --build
```
Auf dem TrueNAS wie bei Slotwise: Stack unter `/mnt/tank/stacks/notizen-server/`, Daten in `./data`.
Entwicklung: `npm install && npm test` (braucht Node 22.13+ oder 24).
