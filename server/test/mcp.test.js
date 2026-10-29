import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { Vault } from '../src/vault.js';
import { createApp } from '../src/index.js';

const TOKEN = 'geraete-token-geraete-token-1234';
const MCP_TOKEN = 'claude-token-claude-token-123456';
let dir;
let server;
let base;
let vault;
let client;

const text = (r) => r.content.map((c) => c.text).join('\n');

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notizen-mcp-'));
  vault = new Vault(dir);
  server = createApp(vault, TOKEN, MCP_TOKEN).listen(0);
  await new Promise((r) => server.once('listening', r));
  base = `http://127.0.0.1:${server.address().port}`;
  client = new Client({ name: 'test', version: '1' });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`), {
    requestInit: { headers: { Authorization: `Bearer ${MCP_TOKEN}` } },
  }));
  // Geräte legen eine Notiz an (wie die App per Sync)
  vault.put('Schule/Physik/Felder.md', Buffer.from('# Felder\n\nDie Feldstärke E = F / q.\n'), 0);
  vault.put('Schule/Mathe/Ableitung.md', Buffer.from('# Ableitung\nf\'(x) = 2x\n'), 0);
  vault.put('Schule/.ink/x.json', Buffer.from('{}'), 0);
});

after(async () => {
  await client.close();
  server.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test('ohne MCP-Token kein Zugang, das Geräte-Token reicht nicht', async () => {
  for (const auth of [undefined, `Bearer ${TOKEN}`, 'Bearer falsch']) {
    const r = await fetch(`${base}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...(auth ? { Authorization: auth } : {}) },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
    });
    assert.equal(r.status, 401, String(auth));
  }
});

test('ohne MCP_TOKEN gibt es den Zugang gar nicht', async () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'notizen-mcp-off-'));
  const s = createApp(new Vault(d), TOKEN).listen(0);
  await new Promise((r) => s.once('listening', r));
  const r = await fetch(`http://127.0.0.1:${s.address().port}/mcp`, { method: 'POST' });
  assert.equal(r.status, 404);
  s.close();
  fs.rmSync(d, { recursive: true, force: true });
});

test('Werkzeuge: Lesen, Schreiben, Anhängen, Aufgaben, Verlauf; kein Löschen', async () => {
  const { tools } = await client.listTools();
  const names = tools.map((t) => t.name).sort();
  assert.deepEqual(names, ['add_task', 'append_note', 'list_notes', 'list_tasks', 'list_versions', 'read_note', 'restore_version', 'search_notes', 'write_note']);
});

test('Aufgabe anlegen mit Dauer und Frist, wieder auflisten', async () => {
  let r = await client.callTool({ name: 'add_task', arguments: { title: 'Übungsblatt 5 abgeben', area: 'Schule', minutes: 45, due: 'Freitag' } });
  assert.ok(!r.isError, text(r));
  assert.match(text(r), /Schule\/Aufgaben\.md/);
  assert.match(text(r), /- \[ \] Übungsblatt 5 abgeben \(~45 min · bis \d{2}\.\d{2}\.\)/);
  r = await client.callTool({ name: 'add_task', arguments: { title: 'Vokabeln lernen', area: 'Schule', minutes: 30 } });
  assert.ok(!r.isError);
  r = await client.callTool({ name: 'read_note', arguments: { path: 'Schule/Aufgaben.md' } });
  assert.match(text(r), /^# Aufgaben\n\n- \[ \] Übungsblatt 5/);
  assert.match(text(r), /- \[ \] Vokabeln lernen \(~30 min\)\n/);
  r = await client.callTool({ name: 'list_tasks', arguments: { folder: 'Schule' } });
  assert.match(text(r), /Schule\/Aufgaben\.md: Vokabeln lernen \(~30 min\)/);
  r = await client.callTool({ name: 'add_task', arguments: { title: 'Mit unklarer Frist', area: 'Alltag', minutes: 10, due: 'irgendwann' } });
  assert.equal(r.isError, true);
  assert.match(text(r), /Frist nicht verstanden/);
  r = await client.callTool({ name: 'add_task', arguments: { title: 'In eigener Notiz', area: 'Projekte', minutes: 20, note: 'Projekte/Notizen-App.md' } });
  assert.ok(!r.isError);
  r = await client.callTool({ name: 'add_task', arguments: { title: 'Unzulässiger Pfad', area: 'Alltag', minutes: 10, note: 'Alltag/datei.txt' } });
  assert.equal(r.isError, true);
});

test('Auflisten, Lesen, Suchen', async () => {
  let r = await client.callTool({ name: 'list_notes', arguments: {} });
  assert.match(text(r), /Schule\/Physik\/Felder\.md/);
  assert.match(text(r), /Schule\/Mathe\/Ableitung\.md/);
  assert.doesNotMatch(text(r), /\.ink/);

  r = await client.callTool({ name: 'list_notes', arguments: { folder: 'Schule/Physik' } });
  assert.doesNotMatch(text(r), /Mathe/);

  r = await client.callTool({ name: 'read_note', arguments: { path: 'Schule/Physik/Felder.md' } });
  assert.match(text(r), /Feldstärke/);
  assert.match(text(r), /\[Revision 1\]/);

  r = await client.callTool({ name: 'search_notes', arguments: { query: 'feldstärke' } });
  assert.match(text(r), /Felder\.md:3:/);
  r = await client.callTool({ name: 'search_notes', arguments: { query: 'gibtsnicht' } });
  assert.match(text(r), /Keine Treffer/);
});

test('Schreiben mit Revision, Konflikt bei veralteter Revision, Verlauf und Wiederherstellen', async () => {
  let r = await client.callTool({ name: 'write_note', arguments: { path: 'Schule/Physik/Felder.md', content: '# Felder\n\nneu von Claude\n', rev: 1 } });
  assert.match(text(r), /Revision 2/);

  r = await client.callTool({ name: 'write_note', arguments: { path: 'Schule/Physik/Felder.md', content: 'veraltet', rev: 1 } });
  assert.equal(r.isError, true);
  assert.match(text(r), /Konflikt/);
  assert.equal(vault.read('Schule/Physik/Felder.md').data.toString(), '# Felder\n\nneu von Claude\n');

  r = await client.callTool({ name: 'list_versions', arguments: { path: 'Schule/Physik/Felder.md' } });
  const id = /^(\d+-r1\.md)/m.exec(text(r))?.[1];
  assert.ok(id, text(r));

  r = await client.callTool({ name: 'restore_version', arguments: { path: 'Schule/Physik/Felder.md', version: id } });
  assert.match(text(r), /Wiederhergestellt/);
  assert.match(vault.read('Schule/Physik/Felder.md').data.toString(), /Feldstärke/);
});

test('Neue Notiz anlegen und anhängen', async () => {
  let r = await client.callTool({ name: 'write_note', arguments: { path: 'Projekte/Idee.md', content: '# Idee', rev: 0 } });
  assert.match(text(r), /Revision 1/);
  r = await client.callTool({ name: 'append_note', arguments: { path: 'Projekte/Idee.md', text: '- Punkt 1' } });
  assert.match(text(r), /Angehängt/);
  assert.equal(vault.read('Projekte/Idee.md').data.toString(), '# Idee\n- Punkt 1\n');
  r = await client.callTool({ name: 'append_note', arguments: { path: 'Projekte/Neu.md', text: 'erste Zeile' } });
  assert.match(text(r), /Angehängt/);
  assert.equal(vault.read('Projekte/Neu.md').data.toString(), 'erste Zeile\n');
});

test('Nur Markdown: Zeichnungen, fremde Dateitypen und Pfadtricks werden abgelehnt', async () => {
  for (const p of ['Schule/.ink/x.json', '../x.md', 'a.txt', '/etc/passwd.md']) {
    const r = await client.callTool({ name: 'read_note', arguments: { path: p } });
    assert.equal(r.isError, true, p);
  }
  const w = await client.callTool({ name: 'write_note', arguments: { path: 'Schule/.ink/x.md', content: 'x', rev: 0 } });
  assert.equal(w.isError, true);
});

test('Schreibende Aktionen stehen im Protokoll', () => {
  const log = fs.readFileSync(path.join(dir, 'mcp-audit.log'), 'utf8');
  assert.match(log, /write_note Schule\/Physik\/Felder\.md/);
  assert.match(log, /append_note Projekte\/Idee\.md/);
  assert.match(log, /restore_version/);
});
