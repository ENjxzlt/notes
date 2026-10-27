// OAuth 2.1 für den MCP-Zugang, damit claude.ai den Server als „echten“ Connector einbinden kann:
// Metadaten (RFC 9728/8414), Dynamic Client Registration (RFC 7591), Authorization-Code-Flow mit PKCE (S256).
//
// Es gibt genau einen Benutzer: du. Die Freigabe im Browser verlangt das MCP_TOKEN als Passwort.
// Ausgestellte Tokens werden nur als SHA-256-Hash gespeichert und laufen ab (Access 1 h, Refresh 30 Tage).

import crypto from 'node:crypto';
import express from 'express';

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const rand = () => crypto.randomBytes(32).toString('base64url');
const b64sha = (s) => crypto.createHash('sha256').update(s).digest('base64url');
const eq = (a, b) => {
  const x = Buffer.from(sha(a));
  const y = Buffer.from(sha(b));
  return crypto.timingSafeEqual(x, y);
};

const ACCESS_TTL = 3600_000;
const REFRESH_TTL = 30 * 24 * 3600_000;
const CODE_TTL = 5 * 60_000;
const MAX_CLIENTS = 50;

const html = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/**
 * Hängt die OAuth-Endpunkte an [app]. [publicUrl] ist die öffentliche Adresse (z. B. https://notizen.minihomelab.online),
 * [password] das MCP_TOKEN. Gibt `verify(token)` zurück: true, wenn es ein gültiges OAuth-Access-Token ist.
 */
export function mountOAuth(app, { db, publicUrl, password, limited, fail, redirectHosts }) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS oauth_clients (id TEXT PRIMARY KEY, redirect_uris TEXT NOT NULL, name TEXT, created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS oauth_tokens (hash TEXT PRIMARY KEY, kind TEXT NOT NULL, client_id TEXT NOT NULL, expires INTEGER NOT NULL);
  `);
  const codes = new Map(); // code-Hash -> { clientId, redirectUri, challenge, exp }
  const issuer = publicUrl.replace(/\/+$/, '');
  const allowedHosts = new Set(redirectHosts);

  const cors = (req, res, next) => {
    res.set({ 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, Authorization, MCP-Protocol-Version', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS' });
    if (req.method === 'OPTIONS') return res.sendStatus(204);
    next();
  };

  const redirectOk = (u) => {
    let url;
    try {
      url = new URL(u);
    } catch {
      return false;
    }
    if (url.hash) return false;
    if (url.protocol === 'http:') return ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
    return url.protocol === 'https:' && (allowedHosts.has(url.hostname) || [...allowedHosts].some((h) => url.hostname.endsWith(`.${h}`)));
  };

  // ---- Metadaten ----
  const resourceMeta = (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.json({ resource: `${issuer}/mcp`, authorization_servers: [issuer], bearer_methods_supported: ['header'], scopes_supported: ['notes'] });
  };
  app.get('/.well-known/oauth-protected-resource', cors, resourceMeta);
  app.get('/.well-known/oauth-protected-resource/mcp', cors, resourceMeta);
  app.get('/.well-known/oauth-authorization-server', cors, (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.json({
      issuer,
      authorization_endpoint: `${issuer}/oauth/authorize`,
      token_endpoint: `${issuer}/oauth/token`,
      registration_endpoint: `${issuer}/oauth/register`,
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none'],
      scopes_supported: ['notes'],
    });
  });

  // ---- Registrierung ----
  app.options(['/oauth/register', '/oauth/token'], cors);
  app.post('/oauth/register', cors, express.json({ limit: '20kb' }), (req, res) => {
    const err = (error, description) => res.status(400).json({ error, error_description: description });
    const uris = req.body?.redirect_uris;
    if (!Array.isArray(uris) || uris.length < 1 || uris.length > 5 || !uris.every((u) => typeof u === 'string' && redirectOk(u))) {
      return err('invalid_redirect_uri', 'Ungültige oder nicht erlaubte redirect_uris');
    }
    const count = db.prepare('SELECT COUNT(*) AS n FROM oauth_clients').get().n;
    if (count >= MAX_CLIENTS) return err('invalid_client_metadata', 'Zu viele registrierte Clients');
    const id = `c_${rand().slice(0, 32)}`;
    const name = String(req.body?.client_name || 'Client').slice(0, 80);
    db.prepare('INSERT INTO oauth_clients (id, redirect_uris, name, created) VALUES (?, ?, ?, ?)').run(id, JSON.stringify(uris), name, Date.now());
    res.status(201).json({
      client_id: id, client_id_issued_at: Math.floor(Date.now() / 1000), client_name: name, redirect_uris: uris,
      token_endpoint_auth_method: 'none', grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'],
    });
  });

  const client = (id) => {
    const row = db.prepare('SELECT * FROM oauth_clients WHERE id = ?').get(String(id || ''));
    return row ? { ...row, uris: JSON.parse(row.redirect_uris) } : null;
  };

  // ---- Freigabe im Browser ----
  const page = (res, status, body) => {
    res.status(status).set({
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'",
      'Cache-Control': 'no-store',
      'X-Frame-Options': 'DENY',
    }).send(`<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Notizen freigeben</title>
<style>body{font:16px/1.5 system-ui,sans-serif;background:#fafaf9;color:#18181b;display:grid;place-items:center;min-height:100vh;margin:0;padding:16px}
main{max-width:420px;width:100%;background:#fff;border:1px solid #e4e4e7;border-radius:12px;padding:28px}h1{font-size:22px;margin:0 0 8px}
p{color:#52525b}input{width:100%;height:42px;padding:0 12px;border:1px solid #d4d4d8;border-radius:8px;font:inherit;box-sizing:border-box;margin:6px 0 12px}
button{height:42px;padding:0 18px;border:0;border-radius:8px;background:#1d4ed8;color:#fff;font:inherit;font-weight:500;cursor:pointer}.err{color:#b42318}</style></head><body><main>${body}</main></body></html>`);
  };

  const authParams = (src) => ({
    response_type: String(src.response_type || ''),
    client_id: String(src.client_id || ''),
    redirect_uri: String(src.redirect_uri || ''),
    code_challenge: String(src.code_challenge || ''),
    code_challenge_method: String(src.code_challenge_method || ''),
    state: src.state === undefined ? '' : String(src.state),
  });

  /** Prüft die Anfrage; Fehler bei Client oder Weiterleitung werden NICHT per Weiterleitung gemeldet. */
  const validate = (p) => {
    const c = client(p.client_id);
    if (!c) return 'Unbekannter Client';
    if (!c.uris.includes(p.redirect_uri)) return 'Weiterleitungsadresse nicht registriert';
    if (p.response_type !== 'code') return 'response_type muss „code“ sein';
    if (p.code_challenge_method !== 'S256' || !/^[A-Za-z0-9_-]{43,128}$/.test(p.code_challenge)) return 'PKCE (S256) ist Pflicht';
    return null;
  };

  const form = (p, error = '') => `<h1>Notizen freigeben</h1>
<p>„${html(client(p.client_id)?.name || 'Ein Programm')}“ möchte deine Notizen lesen und bearbeiten. Gib zur Freigabe das MCP-Passwort (<code>MCP_TOKEN</code>) ein.</p>
${error ? `<p class="err">${html(error)}</p>` : ''}
<form method="post" action="/oauth/authorize">
${Object.entries(p).map(([k, v]) => `<input type="hidden" name="${html(k)}" value="${html(v)}">`).join('')}
<label for="pw">Passwort</label><input id="pw" name="password" type="password" autocomplete="current-password" required autofocus>
<button type="submit">Freigeben</button></form>`;

  app.get('/oauth/authorize', (req, res) => {
    const p = authParams(req.query);
    const problem = validate(p);
    if (problem) return page(res, 400, `<h1>Anfrage ungültig</h1><p class="err">${html(problem)}</p>`);
    page(res, 200, form(p));
  });

  app.post('/oauth/authorize', express.urlencoded({ extended: false, limit: '20kb' }), (req, res) => {
    const p = authParams(req.body || {});
    const problem = validate(p);
    if (problem) return page(res, 400, `<h1>Anfrage ungültig</h1><p class="err">${html(problem)}</p>`);
    if (limited(req.ip)) return page(res, 429, form(p, 'Zu viele Fehlversuche, bitte später erneut versuchen.'));
    if (!eq(String(req.body?.password || ''), password)) {
      fail(req.ip);
      return page(res, 401, form(p, 'Passwort falsch.'));
    }
    const code = rand();
    codes.set(sha(code), { clientId: p.client_id, redirectUri: p.redirect_uri, challenge: p.code_challenge, exp: Date.now() + CODE_TTL });
    for (const [k, v] of codes) if (v.exp < Date.now()) codes.delete(k);
    const target = new URL(p.redirect_uri);
    target.searchParams.set('code', code);
    if (p.state) target.searchParams.set('state', p.state);
    res.redirect(302, target.toString());
  });

  // ---- Token ----
  const issue = (clientId) => {
    const access = rand();
    const refresh = rand();
    const now = Date.now();
    db.prepare('DELETE FROM oauth_tokens WHERE expires < ?').run(now);
    db.prepare('INSERT INTO oauth_tokens (hash, kind, client_id, expires) VALUES (?, ?, ?, ?)').run(sha(access), 'access', clientId, now + ACCESS_TTL);
    db.prepare('INSERT INTO oauth_tokens (hash, kind, client_id, expires) VALUES (?, ?, ?, ?)').run(sha(refresh), 'refresh', clientId, now + REFRESH_TTL);
    return { access_token: access, token_type: 'Bearer', expires_in: ACCESS_TTL / 1000, refresh_token: refresh, scope: 'notes' };
  };

  app.post('/oauth/token', cors, express.urlencoded({ extended: false, limit: '20kb' }), express.json({ limit: '20kb' }), (req, res) => {
    res.set('Cache-Control', 'no-store');
    const b = req.body || {};
    const bad = (error, description) => res.status(400).json({ error, error_description: description });
    if (limited(req.ip)) return res.status(429).json({ error: 'slow_down' });

    if (b.grant_type === 'authorization_code') {
      const entry = codes.get(sha(String(b.code || '')));
      codes.delete(sha(String(b.code || ''))); // jeder Code gilt nur einmal
      if (!entry || entry.exp < Date.now()) { fail(req.ip); return bad('invalid_grant', 'Code ungültig oder abgelaufen'); }
      if (entry.clientId !== b.client_id || entry.redirectUri !== b.redirect_uri) return bad('invalid_grant', 'Client oder Weiterleitung passen nicht');
      if (b64sha(String(b.code_verifier || '')) !== entry.challenge) { fail(req.ip); return bad('invalid_grant', 'PKCE-Prüfung fehlgeschlagen'); }
      return res.json(issue(entry.clientId));
    }
    if (b.grant_type === 'refresh_token') {
      const h = sha(String(b.refresh_token || ''));
      const row = db.prepare("SELECT * FROM oauth_tokens WHERE hash = ? AND kind = 'refresh'").get(h);
      db.prepare('DELETE FROM oauth_tokens WHERE hash = ?').run(h); // Refresh-Tokens werden bei jeder Nutzung erneuert
      if (!row || row.expires < Date.now() || (b.client_id && b.client_id !== row.client_id)) { fail(req.ip); return bad('invalid_grant', 'Refresh-Token ungültig'); }
      return res.json(issue(row.client_id));
    }
    bad('unsupported_grant_type', 'Nur authorization_code und refresh_token');
  });

  return {
    /** true, wenn [token] ein gültiges, nicht abgelaufenes Access-Token ist */
    verify(token) {
      if (!token) return false;
      const row = db.prepare("SELECT expires FROM oauth_tokens WHERE hash = ? AND kind = 'access'").get(sha(token));
      return !!row && row.expires > Date.now();
    },
    /** nimmt allen OAuth-Clients den Zugang (Tokens und Registrierungen löschen) */
    revokeAll() {
      db.exec('DELETE FROM oauth_tokens; DELETE FROM oauth_clients;');
      codes.clear();
    },
    resourceMetadataUrl: `${issuer}/.well-known/oauth-protected-resource`,
  };
}
