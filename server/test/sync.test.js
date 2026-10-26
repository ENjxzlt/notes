import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Vault, checkPath } from '../src/vault.js';
import { createApp } from '../src/index.js';

const TOKEN = 'test-token-test-token-test-token';
let dir;
let server;
let base;
let vault;

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notizen-test-'));
  vault = new Vault(dir);
  server = createApp(vault, TOKEN).listen(0);
  await new Promise((r) => server.once('listening', r));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  server.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

const call = (method, url, body, token = TOKEN) =>
  fetch(base + url, {
    method,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body,
  });

test('ohne Token kein Zugriff, Health ist offen', async () => {
  assert.equal((await call('GET', '/api/health', undefined, null)).status, 200);
  assert.equal((await call('GET', '/api/changes?since=0', undefined, null)).status, 401);
  assert.equal((await call('GET', '/api/changes?since=0', undefined, 'falsch')).status, 401);
});

test('Pfadprüfung lehnt Gefährliches ab', () => {
  for (const bad of ['../x.md', 'a/../../x.md', '/etc/passwd.md', 'a\\b.md', '.versteckt.md', 'a/.git/x.md', 'x.exe', 'x.json', 'a/x.md.tmp', 'a//b.md', '']) {
    assert.throws(() => checkPath(bad), /Ungültig|Versteckt|Dateityp|nur im Ordner|Temporär/, bad);
  }
  for (const ok of ['Schule/Physik/Felder.md', 'Schule/.ink/abc.json', 'Schule/.ink/_page_Felder.svg', 'a.md']) {
    assert.equal(checkPath(ok), ok);
  }
});

test('neue Datei anlegen, lesen, ändern', async () => {
  let r = await call('PUT', '/api/file?path=Schule/Test.md&base=0', Buffer.from('# Hallo\n'));
  assert.equal(r.status, 200);
  const a = await r.json();
  assert.equal(a.rev, 1);

  r = await call('GET', '/api/file?path=Schule/Test.md');
  assert.equal(r.status, 200);
  assert.equal(await r.text(), '# Hallo\n');
  assert.equal(r.headers.get('x-rev'), '1');

  r = await call('PUT', '/api/file?path=Schule/Test.md&base=1', Buffer.from('# Hallo Welt\n'));
  assert.equal((await r.json()).rev, 2);
  assert.equal(fs.readFileSync(path.join(dir, 'vault', 'Schule', 'Test.md'), 'utf8'), '# Hallo Welt\n');
});

test('Schreiben mit alter Revision ergibt Konflikt und ändert nichts', async () => {
  const r = await call('PUT', '/api/file?path=Schule/Test.md&base=1', Buffer.from('anderes Gerät\n'));
  assert.equal(r.status, 409);
  const body = await r.json();
  assert.equal(body.conflict, true);
  assert.equal(body.current.rev, 2);
  assert.equal(fs.readFileSync(path.join(dir, 'vault', 'Schule', 'Test.md'), 'utf8'), '# Hallo Welt\n');
});

test('gleicher Inhalt ist idempotent (Wiederholung nach Verbindungsabbruch)', async () => {
  const r = await call('PUT', '/api/file?path=Schule/Test.md&base=1', Buffer.from('# Hallo Welt\n'));
  assert.equal(r.status, 200);
  assert.equal((await r.json()).rev, 2);
});

test('neue Datei mit base=0, obwohl sie existiert, ist ein Konflikt', async () => {
  const r = await call('PUT', '/api/file?path=Schule/Test.md&base=0', Buffer.from('neu\n'));
  assert.equal(r.status, 409);
});

test('Änderungsliste und Löschen mit Grabstein', async () => {
  let r = await call('GET', '/api/changes?since=0');
  let j = await r.json();
  assert.ok(j.files.some((f) => f.path === 'Schule/Test.md' && f.rev === 2 && !f.deleted));
  const seqBefore = j.seq;

  r = await call('DELETE', '/api/file?path=Schule/Test.md&base=1');
  assert.equal(r.status, 409); // veraltete Revision

  r = await call('DELETE', '/api/file?path=Schule/Test.md&base=2');
  assert.equal(r.status, 200);
  assert.equal((await r.json()).deleted, true);
  assert.equal(fs.existsSync(path.join(dir, 'vault', 'Schule', 'Test.md')), false);
  assert.equal((await call('GET', '/api/file?path=Schule/Test.md')).status, 404);

  j = await (await call('GET', `/api/changes?since=${seqBefore}`)).json();
  assert.equal(j.files.length, 1);
  assert.equal(j.files[0].deleted, true);

  // Datei nach dem Löschen neu anlegen: das Gerät kennt den Grabstein nicht (base=0)
  r = await call('PUT', '/api/file?path=Schule/Test.md&base=0', Buffer.from('wieder da\n'));
  assert.equal(r.status, 200);
  assert.equal((await r.json()).rev, 4);
});

test('Zeichnungsdateien nur im Ordner .ink', async () => {
  assert.equal((await call('PUT', '/api/file?path=Schule/.ink/a.json&base=0', Buffer.from('{}'))).status, 200);
  assert.equal((await call('PUT', '/api/file?path=Schule/a.json&base=0', Buffer.from('{}'))).status, 400);
  assert.equal((await call('PUT', '/api/file?path=../a.md&base=0', Buffer.from('x'))).status, 400);
});

test('Sicherung legt Kopie an und hebt nur die letzten Tage auf', () => {
  const day = (n) => new Date(Date.UTC(2026, 0, n));
  for (let i = 1; i <= 5; i++) assert.equal(vault.backup(3, day(i)), true);
  assert.equal(vault.backup(3, day(5)), false); // am selben Tag nicht doppelt
  const list = fs.readdirSync(path.join(dir, 'backups')).sort();
  assert.deepEqual(list, ['2026-01-03', '2026-01-04', '2026-01-05']);
  assert.ok(fs.existsSync(path.join(dir, 'backups', '2026-01-05', 'vault', 'Schule', 'Test.md')));
  assert.ok(fs.existsSync(path.join(dir, 'backups', '2026-01-05', 'notes.db')));
});
