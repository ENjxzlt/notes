# Anleitung für einen lokalen Agenten (Claude Code auf deinem PC)

Was die Cloud-Sitzung **nicht** kann, weil sie dein Heimnetz nicht erreicht: Server auf dem TrueNAS ausrollen, den
Cloudflare-Tunnel anpassen, Android-Builds von Slotwise. Das erledigt ein lokaler Agent mit SSH-Zugang.

## So startest du ihn
1. Auf dem PC im Ordner mit den Repos `notes` und `slotwise` (je `git pull` auf `main`) Claude Code starten.
2. Den **Auftrag unten** komplett einfügen. Er enthält Regeln, Reihenfolge und Prüfungen.
3. Der Agent soll nach jedem Schritt kurz berichten und bei Fehlern **anhalten**, nicht raten.

## Voraussetzungen (prüf das vorher)
- SSH auf den TrueNAS: `ssh -i ~/.ssh/truenas_ed25519 -o IdentitiesOnly=yes truenas_admin@192.168.178.179`
  (der Standardschlüssel wird abgelehnt)
- `sudo -n docker …` geht ohne Passwort, sonst kein sudo. `/mnt/tank/stacks` ist nicht direkt lesbar, der Agent
  arbeitet dort über `sudo -n docker run --rm -v /mnt/tank/stacks:/s alpine …`
- Lokal oft kein Node/Docker: Tests laufen in Wegwerf-Containern auf dem Server (`node:24-slim`)
- Cloudflare: Zugang zum Zero-Trust-Dashboard (Tunnel → Public Hostnames) oder zur `config.yml` des Tunnels

## Auftrag zum Einfügen

> Du bist mein lokaler Agent für das Heimnetz. Arbeite die Schritte der Reihe nach ab, melde nach jedem Schritt
> kurz das Ergebnis und halte bei Fehlern an. Gib **niemals** Zugangsdaten, Tokens oder Passwörter in Commits,
> Logs oder Berichte aus; Ausnahme: das neue NOTES_TOKEN zeigst du mir genau einmal im Terminal.
> Lies zuerst `slotwise/CLAUDE.md` (Abschnitt Deployment) und `notes/server/README.md`.
>
> **Schritt 1 – Notizen-Server ausrollen**
> 1. Auf dem TrueNAS den Stack `/mnt/tank/stacks/notizen-server/` anlegen (Besitzer uid 1000, Unterordner `data`).
> 2. Den Inhalt von `notes/server/` (ohne `node_modules`, `data`, `.env`) per tar über einen alpine-Container dorthin kopieren.
> 3. `.env` auf dem Server erzeugen: `NOTES_TOKEN=$(openssl rand -hex 24)`, `MCP_TOKEN=$(openssl rand -hex 24)` (ein **anderer** Wert),
>    `PUBLIC_URL=https://notizen.minihomelab.online` (nötig für den claude.ai-Connector) und `TZ=Europe/Berlin`. Rechte `600`.
>    Bei einem schon laufenden Stack: nur `MCP_TOKEN` ergänzen, die Dateien aus `notes/server/` neu kopieren und mit `up -d --build` neu bauen.
> 4. Prüfen, dass Port 3090 frei ist (`sudo -n docker ps`), sonst `HOST_PORT` in der `.env` ändern.
> 5. Tests in einem Wegwerf-Container: `sudo -n docker run --rm -v <Stack>:/app -w /app node:24-slim sh -c "npm install && npm test"` – es müssen alle 32 Tests bestehen.
> 6. Starten: `sudo -n docker compose --project-directory /mnt/tank/stacks/notizen-server -f /mnt/tank/stacks/notizen-server/compose.yaml up -d --build`
> 7. Prüfen: `curl http://192.168.178.179:3090/api/health` liefert `{"ok":true}`; ohne Token liefert `/api/changes?since=0` 401.
>
> **Schritt 2 – HTTPS über den Cloudflare-Tunnel**
> 1. Öffentlichen Hostnamen `notizen.minihomelab.online` auf `http://192.168.178.179:3090` im bestehenden Tunnel anlegen
>    (Dashboard oder `config.yml`; wenn du keinen Zugriff hast, sag mir genau, was ich anklicken soll).
> 2. Prüfen: `curl https://notizen.minihomelab.online/api/health`.
> 3. Ein Test-Roundtrip mit dem Token (Token nicht ausgeben, nur in einer Shell-Variable halten): eine Datei `Test/Sync.md`
>    per PUT mit `base=0` anlegen, per GET lesen, per DELETE (`base` = Revision) wieder löschen.
> 4. Web-Ansicht prüfen: `https://notizen.minihomelab.online/` zeigt die Anmeldung. Live-Sync prüfen: `curl -N -H "Authorization: Bearer $NOTES_TOKEN" …/api/events`
>    zeigt `event: hello`, und eine geschriebene Datei löst `event: change` aus (Token nicht ausgeben).
> 5. OAuth prüfen: `curl https://notizen.minihomelab.online/.well-known/oauth-authorization-server` liefert JSON mit `registration_endpoint`.
> 6. MCP prüfen: `POST https://notizen.minihomelab.online/mcp` ohne Token muss 401 liefern, mit dem **MCP_TOKEN** eine gültige
>    MCP-Antwort (`tools/list`); mit dem NOTES_TOKEN muss es 401 geben.
>
> **Schritt 3 – Slotwise von `main` ausrollen**
> 1. In `slotwise`: `git checkout main && git pull`.
> 2. Vor dem Rollout `data/` sichern: `data/backup-<heutiges Datum>-schulplan/` (SQLite-Dateien).
> 3. Dateien (src, public, package.json, Dockerfile …) per tar in den Stack `/mnt/tank/stacks/slotwise/` kopieren,
>    dann `sudo -n docker compose --project-directory /mnt/tank/stacks/slotwise -f /mnt/tank/stacks/slotwise/compose.yaml up -d --build`.
> 4. Prüfen: Login klappt, Kalender lädt, Einstellungen → Kalender zeigt den Abschnitt „Schulplan".
>    Logs ansehen (`sudo -n docker logs --tail 50 slotwise`), keine Fehler.
>
> **Schritt 4 – Bericht**
> Schreib mir eine Liste: was läuft (Adressen, Ports), was fehlgeschlagen ist, was ich noch von Hand tun muss
> (Token in die Notizen-App eintragen, Schulplan-Zugang in Slotwise eintragen).

## Claude verbinden
**Als Connector in claude.ai (empfohlen, funktioniert auch in der Claude-App auf dem Tablet):** Einstellungen → Connectors →
Benutzerdefinierten Connector hinzufügen → `https://notizen.minihomelab.online/mcp`. Auf der Freigabeseite das MCP_TOKEN als Passwort eingeben.

**Mit Claude Code (Termux auf dem Tablet oder PC):**
```
claude mcp add --transport http notizen https://notizen.minihomelab.online/mcp --header "Authorization: Bearer <MCP_TOKEN>"
```
Das MCP_TOKEN holst du dir von dem Agenten (er zeigt es einmal an) oder liest es in der `.env` auf dem Server.

## Danach von Hand (am Tablet)
1. Notizen-App: **Mehr → Notizen-Server verbinden**, Adresse `https://notizen.minihomelab.online` und das Token eintragen.
2. Slotwise: **Einstellungen → Kalender → Schulplan**, Schulnummer, Zugang und Klasse eintragen, „Plan laden & Kurse wählen".
3. Notizen-App: **Mehr → Stundenplan von Slotwise verbinden**, Abo-Link aus Slotwise einfügen.

## Wenn etwas schiefgeht
| Symptom | Ursache / Abhilfe |
|---|---|
| SSH „Permission denied" | Schlüssel explizit angeben (`-i … -o IdentitiesOnly=yes`) |
| `/mnt/tank/stacks` nicht lesbar | nur über den alpine-Container arbeiten (siehe Voraussetzungen) |
| Container startet nicht, „NOTES_TOKEN fehlt" | `.env` fehlt oder Token unter 24 Zeichen |
| App meldet „Token falsch" | Token in der App stimmt nicht mit der `.env` überein, Leerzeichen am Ende prüfen |
| App meldet „Bitte eine https://-Adresse" | Tunnel-Hostname statt IP eintragen |
| Viele „Konflikt"-Dateien nach dem ersten Sync | Es gab vorher schon abweichende Fassungen auf Server und Tablet; Kopien prüfen und zusammenführen |
