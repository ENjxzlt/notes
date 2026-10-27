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

## Live-Sync
`GET /api/events` (Server-Sent Events, mit Token) meldet jede Änderung sofort (`event: change`, `data: {seq, path}`), egal ob vom Tablet,
vom PC oder von Claude. Die Notizen-App (im Vordergrund) und die Web-Ansicht laden dann gleich nach, nach etwa einer Sekunde statt nach
Minuten. Alle 25 Sekunden kommt ein Lebenszeichen; bricht die Verbindung ab, verbinden sich die Clients neu und holen Verpasstes nach.
Ist die App im Hintergrund, gibt es keine Verbindung (Akku); beim Zurückkehren wird sofort abgeglichen.

## Web-Ansicht
Der Server liefert unter `/` eine Oberfläche für den PC: Baum, Suche, Markdown-Editor mit Vorschau, Format-Leiste, neue/umbenannte/gelöschte
Notizen, Konfliktbehandlung. Anmelden mit dem `NOTES_TOKEN` (bleibt nur im Browser). Zeichnungen vom Tablet werden angezeigt, nicht bearbeitet.
Die Seite ist nur über HTTPS sicher nutzbar; sie setzt eine strenge Content-Security-Policy, Notiztext wird immer maskiert.

## Schnittstelle
| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/health` | Lebenszeichen (ohne Token) |
| GET | `/api/events` | Live-Sync (SSE), meldet Änderungen sofort |
| POST | `/api/oauth/revoke-all` | alle OAuth-Zugänge (claude.ai) widerrufen |
| GET | `/api/changes?since=N` | alle Änderungen nach Nummer N: `{seq, files:[{path, rev, hash, size, mtime, deleted, seq}]}` |
| GET | `/api/file?path=P` | Inhalt, Header `X-Rev`, `X-Hash` |
| PUT | `/api/file?path=P&base=R` | schreiben (R = bekannte Revision, 0 = neu); `409` bei Konflikt |
| DELETE | `/api/file?path=P&base=R` | löschen; `409` bei Konflikt |

Erlaubt sind `.md` überall sowie `.json`/`.svg` nur in `.ink`-Ordnern (Zeichnungen); höchstens 25 MB je Datei.

## Claude-Zugriff (MCP)
Wenn `MCP_TOKEN` in der `.env` gesetzt ist, spricht der Server unter `/mcp` das MCP-Protokoll (Streamable HTTP, zustandslos).
Ohne `MCP_TOKEN` gibt es den Zugang nicht. Das Token ist **ein eigenes Geheimnis**, unabhängig von `NOTES_TOKEN`: Du kannst Claude
den Zugang nehmen (Token löschen, Container neu starten), ohne die Geräte neu zu verbinden.

Werkzeuge: `list_notes`, `read_note`, `search_notes`, `write_note`, `append_note`, `list_versions`, `restore_version`.
Bewusst **kein Löschen** und nur Markdown-Notizen (keine Zeichnungen).

Schutz vor Fehlern:
- `write_note` überschreibt nur mit der gelesenen Revision, sonst „Konflikt" und nichts geht verloren (neue Notiz: Revision 0)
- vor jeder Änderung bleibt die alte Fassung im Verlauf (`data/history/…`, die letzten 30 je Datei), `restore_version` holt sie zurück
- jede schreibende Aktion steht in `data/mcp-audit.log`
- Änderungen von Claude laufen über denselben Sync wie deine eigenen: Das Tablet bekommt sie beim nächsten Abgleich

### Als echter Connector in claude.ai
Mit `PUBLIC_URL=https://notizen.minihomelab.online` in der `.env` spricht der Server zusätzlich OAuth 2.1 (Dynamic Client Registration,
Authorization-Code mit PKCE S256, Erneuerung per Refresh-Token). Damit lässt er sich in claude.ai unter
**Einstellungen → Connectors → Benutzerdefinierten Connector hinzufügen** mit der Adresse `https://notizen.minihomelab.online/mcp`
einbinden. Beim Verbinden öffnet sich eine Freigabeseite des Servers; dort gibst du das `MCP_TOKEN` als Passwort ein.
Danach steht der Connector auch in der Claude-App auf dem Tablet zur Verfügung.
- Weiterleitungen sind nur auf `claude.ai`/`claude.com` und `localhost` erlaubt (`OAUTH_REDIRECT_HOSTS` ändert das).
- Access-Tokens laufen nach 1 Stunde ab, Refresh-Tokens nach 30 Tagen; gespeichert wird nur ein Hash.
- **Zugang entziehen:** `curl -X POST -H "Authorization: Bearer <NOTES_TOKEN>" https://notizen.minihomelab.online/api/oauth/revoke-all`
  löscht alle OAuth-Tokens und -Registrierungen. Das `MCP_TOKEN` für Claude Code ändern bzw. leeren schaltet dessen Zugang ab.
- Ob claude.ai den Server annimmt, ist **nicht** von mir gegen die echte Oberfläche geprüft, nur gegen die OAuth-Spezifikation (Test mit
  einem MCP-Client). Wenn beim Hinzufügen etwas schiefgeht, hilft die Fehlermeldung von claude.ai.

Mit Claude Code verbinden (auf dem Tablet in Termux oder am PC):
```
claude mcp add --transport http notizen https://notizen.minihomelab.online/mcp --header "Authorization: Bearer <MCP_TOKEN>"
```
Danach in Claude Code z. B.: „Fass meine Physik-Notizen von heute zusammen" oder „Hänge an Schule/Mathe/Ableitung.md eine Übungsaufgabe an".
Claude greift nur zu, wenn du es in einer Sitzung verlangst. **Nicht** unterstützt: claude.ai-Connectors, die OAuth verlangen.

## Betrieb
```
cp .env.example .env      # NOTES_TOKEN eintragen
docker compose up -d --build
```
Auf dem TrueNAS wie bei Slotwise: Stack unter `/mnt/tank/stacks/notizen-server/`, Daten in `./data`.
Entwicklung: `npm install && npm test` (braucht Node 22.13+ oder 24).
