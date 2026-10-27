// MCP-Server: Claude liest und bearbeitet die Notizen, nur wenn du ihn dazu aufforderst (Claude Code, Termux, PC).
//
// Schutz:
//  - eigenes Token (MCP_TOKEN), unabhängig vom Token der Geräte: lässt sich getrennt widerrufen; ohne MCP_TOKEN ist der Zugang aus
//  - nur Markdown-Notizen, keine Zeichnungen, kein Löschen
//  - Überschreiben nur mit der gelesenen Revision (sonst Konflikt, nichts geht verloren)
//  - jede Fassung vor einer Änderung bleibt im Verlauf (list_versions / restore_version)
//  - jede schreibende Aktion steht im Protokoll data/mcp-audit.log

import fs from 'node:fs';
import path from 'node:path';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { HttpError } from './vault.js';

const MAX_TEXT = 1_000_000; // 1 MB Text je Notiz über MCP

function mdPath(p) {
  if (typeof p !== 'string' || !p.endsWith('.md')) throw new HttpError(400, 'Nur Markdown-Notizen (.md) sind erlaubt');
  if (p.split('/').includes('.ink')) throw new HttpError(400, 'Zeichnungen sind nicht erlaubt');
  return p;
}

const ok = (...texts) => ({ content: texts.map((text) => ({ type: 'text', text })) });
const fail = (text) => ({ isError: true, content: [{ type: 'text', text }] });

/** Führt eine Aktion aus und macht aus erwarteten Fehlern (Konflikt, ungültiger Pfad) eine lesbare Meldung. */
function guarded(fn) {
  return async (args) => {
    try {
      return await fn(args);
    } catch (e) {
      if (e instanceof HttpError) {
        if (e.status === 409) {
          const cur = e.extra?.current;
          return fail(`Konflikt: Die Notiz hat sich geändert (aktuelle Revision ${cur ? cur.rev : '?'}). Bitte mit read_note neu lesen und die Änderung darauf aufbauen.`);
        }
        return fail(e.message);
      }
      throw e;
    }
  };
}

export function buildMcpServer(vault, dataDir) {
  const audit = (tool, p, extra = '') => {
    try {
      fs.appendFileSync(path.join(dataDir, 'mcp-audit.log'), `${new Date().toISOString()} ${tool} ${p} ${extra}\n`);
    } catch {
      /* Protokoll ist Zusatz, kein Grund, die Aktion scheitern zu lassen */
    }
  };

  const server = new McpServer({ name: 'notizen', version: '0.1.0' });

  server.registerTool('list_notes', {
    title: 'Notizen auflisten',
    description: 'Listet Notizen (Markdown) mit Pfad, Größe und letzter Änderung. Optional nur unter einem Ordner, z. B. "Schule/Physik".',
    inputSchema: { folder: z.string().optional().describe('Ordner relativ zum Notizen-Ordner, z. B. "Schule"') },
  }, guarded(({ folder }) => {
    const files = vault.list(folder || '').filter((f) => f.path.endsWith('.md'));
    if (!files.length) return ok('Keine Notizen gefunden.');
    const shown = files.slice(0, 500);
    const lines = shown.map((f) => `${f.path}  (Rev ${f.rev}, ${f.size} B, ${new Date(f.mtime).toISOString().slice(0, 16).replace('T', ' ')})`);
    if (files.length > shown.length) lines.push(`… und ${files.length - shown.length} weitere`);
    return ok(lines.join('\n'));
  }));

  server.registerTool('read_note', {
    title: 'Notiz lesen',
    description: 'Liest eine Notiz. Gibt den Inhalt und die Revision zurück; die Revision brauchst du für write_note.',
    inputSchema: { path: z.string().describe('Pfad der Notiz, z. B. "Schule/Physik/Felder.md"') },
  }, guarded(({ path: p }) => {
    const { info, data } = vault.read(mdPath(p));
    return ok(data.toString('utf8'), `[Revision ${info.rev}]`);
  }));

  server.registerTool('search_notes', {
    title: 'Notizen durchsuchen',
    description: 'Volltextsuche (Groß-/Kleinschreibung egal) in allen Notizen. Liefert bis zu 40 Treffer mit Pfad, Zeile und Textstelle.',
    inputSchema: {
      query: z.string().min(2).describe('Suchtext'),
      folder: z.string().optional().describe('Nur unter diesem Ordner suchen'),
    },
  }, guarded(({ query, folder }) => {
    const needle = query.toLowerCase();
    const hits = [];
    for (const f of vault.list(folder || '').filter((x) => x.path.endsWith('.md'))) {
      let text;
      try {
        text = vault.read(f.path).data.toString('utf8');
      } catch {
        continue;
      }
      const lines = text.split('\n');
      for (let i = 0; i < lines.length && hits.length < 40; i++) {
        if (lines[i].toLowerCase().includes(needle) || (i === 0 && f.path.toLowerCase().includes(needle))) {
          hits.push(`${f.path}:${i + 1}: ${lines[i].trim().slice(0, 200)}`);
        }
      }
      if (hits.length >= 40) break;
    }
    return ok(hits.length ? hits.join('\n') : 'Keine Treffer.');
  }));

  server.registerTool('write_note', {
    title: 'Notiz schreiben',
    description: 'Ersetzt eine Notiz vollständig oder legt sie neu an. rev ist die Revision aus read_note; für eine NEUE Notiz rev 0. Bei veralteter Revision gibt es einen Konflikt statt eines Überschreibens. Die bisherige Fassung bleibt im Verlauf.',
    inputSchema: {
      path: z.string().describe('Pfad, z. B. "Schule/Physik/Felder.md"'),
      content: z.string().max(MAX_TEXT).describe('Kompletter neuer Inhalt (Markdown)'),
      rev: z.number().int().min(0).describe('Revision aus read_note, 0 für eine neue Notiz'),
    },
  }, guarded(({ path: p, content, rev }) => {
    const { info } = vault.put(mdPath(p), Buffer.from(content, 'utf8'), rev);
    audit('write_note', p, `rev=${info.rev}`);
    return ok(`Gespeichert (Revision ${info.rev}).`);
  }));

  server.registerTool('append_note', {
    title: 'An Notiz anhängen',
    description: 'Hängt Text ans Ende einer Notiz an (legt sie an, falls es sie nicht gibt). Gut für Ergänzungen, ohne die ganze Notiz neu zu schreiben.',
    inputSchema: {
      path: z.string().describe('Pfad der Notiz'),
      text: z.string().min(1).max(MAX_TEXT).describe('Anzuhängender Text (Markdown)'),
    },
  }, guarded(({ path: p, text }) => {
    mdPath(p);
    const cur = vault.info(p);
    let base = 0;
    let existing = '';
    if (cur && !cur.deleted) {
      base = cur.rev;
      existing = vault.read(p).data.toString('utf8');
    } else if (cur) {
      base = cur.rev;
    }
    const sep = existing && !existing.endsWith('\n') ? '\n' : '';
    const { info } = vault.put(p, Buffer.from(existing + sep + text + (text.endsWith('\n') ? '' : '\n'), 'utf8'), base);
    audit('append_note', p, `rev=${info.rev}`);
    return ok(`Angehängt (Revision ${info.rev}).`);
  }));

  server.registerTool('list_versions', {
    title: 'Verlauf einer Notiz',
    description: 'Zeigt frühere Fassungen einer Notiz (neueste zuerst), jede mit ID, Revision und Zeit.',
    inputSchema: { path: z.string() },
  }, guarded(({ path: p }) => {
    const v = vault.versions(mdPath(p));
    if (!v.length) return ok('Kein Verlauf vorhanden.');
    return ok(v.map((x) => `${x.id}  (Rev ${x.rev}, ${new Date(x.time).toISOString().slice(0, 16).replace('T', ' ')}, ${x.size} B)`).join('\n'));
  }));

  server.registerTool('restore_version', {
    title: 'Frühere Fassung wiederherstellen',
    description: 'Stellt eine Fassung aus list_versions wieder her. Die aktuelle Fassung bleibt dabei im Verlauf.',
    inputSchema: {
      path: z.string(),
      version: z.string().describe('ID aus list_versions'),
    },
  }, guarded(({ path: p, version }) => {
    mdPath(p);
    const old = vault.readVersion(p, version);
    const cur = vault.info(p);
    const { info } = vault.put(p, old, cur ? cur.rev : 0);
    audit('restore_version', p, `${version} -> rev=${info.rev}`);
    return ok(`Wiederhergestellt (Revision ${info.rev}).`);
  }));

  return server;
}
