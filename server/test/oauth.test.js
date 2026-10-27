import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { Vault } from '../src/vault.js';
import { createApp } from '../src/index.js';

const TOKEN = 'geraete-token-geraete-token-1234';
const MCP_TOKEN = 'claude-token-claude-token-123456';
const PUBLIC = 'https://notizen.example.test';
const REDIRECT = 'https://claude.ai/api/mcp/auth_callback';
let dir;
let server;
let base;
let vault;

const b64 = (b) => Buffer.from(b).toString('base64url');
const pkce = () => {
  const verifier = b64(crypto.randomBytes(32));
  return { verifier, challenge: b64(crypto.createHash('sha256').update(verifier).digest()) };
};
const json = (r) => r.json();
const form = (o) => new URLSearchParams(o).toString();

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notizen-oauth-'));
  vault = new Vault(dir);
  server = createApp(vault, TOKEN, MCP_TOKEN, { publicUrl: PUBLIC }).listen(0);
  await new Promise((r) => server.once('listening', r));
  base = `http://127.0.0.1:${server.address().port}`;
  vault.put('Schule/Test.md', Buffer.from('# Test\n'), 0);
});

after(() => {
  server.closeAllConnections();
  server.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

async function register(redirect = REDIRECT) {
  const r = await fetch(`${base}/oauth/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ client_name: 'Claude', redirect_uris: [redirect] }),
  });
  return r;
}

async function authorize(clientId, ch, { password = MCP_TOKEN, redirect = REDIRECT, state = 'zustand-1' } = {}) {
  const r = await fetch(`${base}/oauth/authorize`, {
    method: 'POST', redirect: 'manual',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form({ response_type: 'code', client_id: clientId, redirect_uri: redirect, code_challenge: ch, code_challenge_method: 'S256', state, password }),
  });
  return r;
}

test('Metadaten nennen Endpunkte, PKCE S256 und Registrierung', async () => {
  const meta = await json(await fetch(`${base}/.well-known/oauth-authorization-server`));
  assert.equal(meta.issuer, PUBLIC);
  assert.equal(meta.authorization_endpoint, `${PUBLIC}/oauth/authorize`);
  assert.deepEqual(meta.code_challenge_methods_supported, ['S256']);
  assert.ok(meta.registration_endpoint);
  const res = await json(await fetch(`${base}/.well-known/oauth-protected-resource`));
  assert.equal(res.resource, `${PUBLIC}/mcp`);
  assert.deepEqual(res.authorization_servers, [PUBLIC]);
});

test('/mcp ohne Anmeldung verweist auf die OAuth-Metadaten', async () => {
  const r = await fetch(`${base}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(r.status, 401);
  assert.match(r.headers.get('www-authenticate'), /resource_metadata="https:\/\/notizen\.example\.test\/\.well-known\/oauth-protected-resource"/);
});

test('Registrierung: nur erlaubte Weiterleitungen', async () => {
  assert.equal((await register(REDIRECT)).status, 201);
  assert.equal((await register('http://localhost:8123/cb')).status, 201);
  assert.equal((await register('https://evil.example/cb')).status, 400);
  assert.equal((await register('http://evil.example/cb')).status, 400);
  assert.equal((await register('javascript:alert(1)')).status, 400);
});

test('voller Ablauf: Freigabe, Token, MCP-Aufruf, Erneuerung, Widerruf', async () => {
  const reg = await json(await register());
  const { verifier, challenge } = pkce();

  // Freigabeseite
  const q = new URLSearchParams({ response_type: 'code', client_id: reg.client_id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256', state: 'zustand-1' });
  const page = await fetch(`${base}/oauth/authorize?${q}`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /Notizen freigeben/);
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);

  // falsches Passwort
  assert.equal((await authorize(reg.client_id, challenge, { password: 'falsch' })).status, 401);

  // richtiges Passwort: Weiterleitung mit Code und state
  const ok = await authorize(reg.client_id, challenge);
  assert.equal(ok.status, 302);
  const loc = new URL(ok.headers.get('location'));
  assert.equal(`${loc.origin}${loc.pathname}`, REDIRECT);
  assert.equal(loc.searchParams.get('state'), 'zustand-1');
  const code = loc.searchParams.get('code');
  assert.ok(code);

  const token = (o) => fetch(`${base}/oauth/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form(o) });

  // falscher Verifier scheitert, und der Code ist danach verbraucht
  let r = await token({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, client_id: reg.client_id, code_verifier: 'x'.repeat(50) });
  assert.equal(r.status, 400);
  r = await token({ grant_type: 'authorization_code', code, redirect_uri: REDIRECT, client_id: reg.client_id, code_verifier: verifier });
  assert.equal(r.status, 400);

  // neuer Code, diesmal richtig
  const code2 = new URL((await authorize(reg.client_id, challenge)).headers.get('location')).searchParams.get('code');
  r = await token({ grant_type: 'authorization_code', code: code2, redirect_uri: REDIRECT, client_id: reg.client_id, code_verifier: verifier });
  assert.equal(r.status, 200);
  const tok = await r.json();
  assert.equal(tok.token_type, 'Bearer');
  assert.ok(tok.access_token && tok.refresh_token);
  // Code ist einmalig
  r = await token({ grant_type: 'authorization_code', code: code2, redirect_uri: REDIRECT, client_id: reg.client_id, code_verifier: verifier });
  assert.equal(r.status, 400);

  // MCP-Aufruf mit dem OAuth-Token
  const client = new Client({ name: 'oauth-test', version: '1' });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { requestInit: { headers: { Authorization: `Bearer ${tok.access_token}` } } }));
  const res = await client.callTool({ name: 'read_note', arguments: { path: 'Schule/Test.md' } });
  assert.match(res.content[0].text, /# Test/);
  await client.close();

  // Erneuern: neues Paar, altes Refresh-Token ist danach ungültig
  r = await token({ grant_type: 'refresh_token', refresh_token: tok.refresh_token, client_id: reg.client_id });
  assert.equal(r.status, 200);
  const tok2 = await r.json();
  assert.notEqual(tok2.access_token, tok.access_token);
  r = await token({ grant_type: 'refresh_token', refresh_token: tok.refresh_token, client_id: reg.client_id });
  assert.equal(r.status, 400);

  // Widerruf mit dem Geräte-Token: alle OAuth-Tokens ungültig
  const call = (t) => fetch(`${base}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Authorization: `Bearer ${t}` }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }) });
  assert.equal((await call(tok2.access_token)).status, 200);
  assert.equal((await fetch(`${base}/api/oauth/revoke-all`, { method: 'POST', headers: { Authorization: `Bearer ${TOKEN}` } })).status, 200);
  assert.equal((await call(tok2.access_token)).status, 401);
  // das statische MCP_TOKEN für Claude Code funktioniert weiter
  assert.equal((await call(MCP_TOKEN)).status, 200);
});

test('Anfrage mit fremdem Client oder ohne PKCE wird abgelehnt', async () => {
  const { challenge } = pkce();
  const reg = await json(await register());
  const bad = async (params) => (await fetch(`${base}/oauth/authorize?${new URLSearchParams(params)}`)).status;
  assert.equal(await bad({ response_type: 'code', client_id: 'c_unbekannt', redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' }), 400);
  assert.equal(await bad({ response_type: 'code', client_id: reg.client_id, redirect_uri: 'https://claude.ai/anderer', code_challenge: challenge, code_challenge_method: 'S256' }), 400);
  assert.equal(await bad({ response_type: 'code', client_id: reg.client_id, redirect_uri: REDIRECT }), 400);
  assert.equal(await bad({ response_type: 'token', client_id: reg.client_id, redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' }), 400);
});

test('Live-Sync: Änderungen werden sofort gemeldet', async () => {
  const ctrl = new AbortController();
  const r = await fetch(`${base}/api/events`, { headers: { Authorization: `Bearer ${TOKEN}` }, signal: ctrl.signal });
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-type'), /text\/event-stream/);
  const reader = r.body.getReader();
  const dec = new TextDecoder();
  let seen = '';
  const readUntil = async (re) => {
    while (!re.test(seen)) {
      const { value, done } = await reader.read();
      if (done) break;
      seen += dec.decode(value);
    }
  };
  await readUntil(/event: hello/);
  vault.put('Schule/Live.md', Buffer.from('neu\n'), 0);
  await readUntil(/event: change/);
  assert.match(seen, /"path":"Schule\/Live\.md"/);
  ctrl.abort();
  // ohne Token kein Live-Sync
  assert.equal((await fetch(`${base}/api/events`)).status, 401);
});

test('Web-Ansicht wird ausgeliefert, mit strenger CSP', async () => {
  const r = await fetch(`${base}/`);
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-security-policy'), /script-src 'self'/);
  assert.match(await r.text(), /<title>Notizen<\/title>/);
  assert.equal((await fetch(`${base}/md.js`)).status, 200);
});
