import express from 'express';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { mountOAuth } from './oauth.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { Vault, HttpError, MAX_SIZE } from './vault.js';
import { buildMcpServer } from './mcp.js';

/**
 * Baut die App; opts.publicUrl (https://…) schaltet zusätzlich OAuth für claude.ai-Connectors frei, opts.redirectHosts
 * erlaubt weitere Hosts für die Weiterleitung nach der Freigabe (Standard: claude.ai, claude.com).
 * [token] ist das gemeinsame Geheimnis der Geräte, [mcpToken] (optional) schaltet den MCP-Zugang für Claude
 * frei und ist ein eigenes Geheimnis, damit sich beides getrennt widerrufen lässt.
 */
export function createApp(vault, token, mcpToken = '', opts = {}) {
  const app = express();
  app.disable('x-powered-by');

  const wanted = Buffer.from(token);
  const attempts = new Map(); // ip -> { count, until } gegen Raten des Tokens
  const limited = (ip) => {
    const a = attempts.get(ip);
    return a && a.until > Date.now() && a.count >= 10;
  };
  const fail = (ip) => {
    const a = attempts.get(ip);
    if (!a || a.until < Date.now()) attempts.set(ip, { count: 1, until: Date.now() + 15 * 60_000 });
    else a.count++;
  };

  // Web-Ansicht für den PC (statische Dateien); Zugriff auf Notizen erst über die API mit Token
  const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
  app.use((req, res, next) => {
    res.set({
      'Content-Security-Policy': "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'",
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
    });
    next();
  });
  app.use(express.static(publicDir, { index: 'index.html', maxAge: 0 }));

  app.get('/api/health', (req, res) => res.json({ ok: true }));

  // ---- MCP für Claude (nur mit eigenem Token; mit publicUrl zusätzlich als OAuth-Connector für claude.ai) ----
  let oauth = null;
  if (mcpToken) {
    const wantedMcp = Buffer.from(mcpToken);
    if (opts.publicUrl) {
      oauth = mountOAuth(app, {
        db: vault.db,
        publicUrl: opts.publicUrl,
        password: mcpToken,
        limited,
        fail,
        redirectHosts: opts.redirectHosts || ['claude.ai', 'claude.com'],
      });
    }
    const mcpAuth = (req, res, next) => {
      if (limited(req.ip)) return res.status(429).json({ error: 'Zu viele Fehlversuche' });
      const bearer = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      const given = Buffer.from(bearer);
      if (given.length === wantedMcp.length && crypto.timingSafeEqual(given, wantedMcp)) return next();
      if (oauth?.verify(bearer)) return next();
      fail(req.ip);
      if (oauth) res.set('WWW-Authenticate', `Bearer resource_metadata="${oauth.resourceMetadataUrl}"`);
      res.status(401).json({ error: 'Nicht angemeldet' });
    };
    app.post('/mcp', mcpAuth, express.json({ limit: '3mb' }), async (req, res) => {
      // zustandslos: je Anfrage ein eigener Server, nichts bleibt im Speicher
      const server = buildMcpServer(vault, vault.dataDir);
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
      res.on('close', () => {
        transport.close();
        server.close();
      });
      try {
        await server.connect(transport);
        await transport.handleRequest(req, res, req.body);
      } catch (e) {
        console.error(e);
        if (!res.headersSent) res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: 'Interner Fehler' }, id: null });
      }
    });
    const notAllowed = (req, res) => res.status(405).json({ jsonrpc: '2.0', error: { code: -32000, message: 'Nur POST erlaubt' }, id: null });
    app.get('/mcp', notAllowed);
    app.delete('/mcp', notAllowed);
  }

  app.use('/api', (req, res, next) => {
    const ip = req.ip;
    if (limited(ip)) return res.status(429).json({ error: 'Zu viele Fehlversuche' });
    const given = Buffer.from((req.headers.authorization || '').replace(/^Bearer\s+/i, ''));
    if (given.length === wanted.length && crypto.timingSafeEqual(given, wanted)) return next();
    fail(ip);
    res.status(401).json({ error: 'Nicht angemeldet' });
  });

  // Live-Sync: Server-Sent Events. Jede Änderung (Tablet, PC, Claude) wird sofort gemeldet, die Geräte laden dann nach.
  app.get('/api/events', (req, res) => {
    res.set({ 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
    res.flushHeaders();
    res.write(`event: hello\ndata: ${JSON.stringify({ seq: vault.seq })}\n\n`);
    const onChange = (info) => res.write(`event: change\ndata: ${JSON.stringify({ seq: info.seq, path: info.path })}\n\n`);
    vault.events.on('change', onChange);
    const beat = setInterval(() => res.write(': keep-alive\n\n'), 25_000);
    req.on('close', () => {
      clearInterval(beat);
      vault.events.off('change', onChange);
    });
  });

  // Nimmt allen claude.ai-Connectors den Zugang (OAuth-Tokens und -Registrierungen löschen)
  app.post('/api/oauth/revoke-all', (req, res) => {
    oauth?.revokeAll();
    res.json({ ok: true });
  });

  const q = (req, name) => String(req.query[name] ?? '');
  const baseOf = (req) => {
    const b = Number(req.query.base ?? 0);
    if (!Number.isInteger(b) || b < 0) throw new HttpError(400, 'Ungültige Basis-Revision');
    return b;
  };
  const h = (fn) => (req, res, next) => {
    try {
      fn(req, res);
    } catch (e) {
      next(e);
    }
  };

  app.get('/api/changes', h((req, res) => {
    const since = Number(req.query.since ?? 0);
    if (!Number.isInteger(since) || since < 0) throw new HttpError(400, 'Ungültige Änderungsnummer');
    res.json(vault.changes(since));
  }));

  app.get('/api/file', h((req, res) => {
    const { info, data } = vault.read(q(req, 'path'));
    res.set({ 'X-Rev': String(info.rev), 'X-Hash': info.hash, 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.send(data);
  }));

  app.put('/api/file', express.raw({ type: () => true, limit: MAX_SIZE }), h((req, res) => {
    const { info } = vault.put(q(req, 'path'), Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0), baseOf(req));
    res.json(info);
  }));

  app.delete('/api/file', h((req, res) => {
    const { info } = vault.del(q(req, 'path'), baseOf(req));
    res.json(info);
  }));

  app.use((err, req, res, next) => {
    const status = err.status || (err.type === 'entity.too.large' ? 413 : 500);
    if (status >= 500) console.error(err);
    res.status(status).json({ error: err.message || 'Fehler', ...(err.extra || {}) });
  });

  return app;
}

// Start (nicht beim Import in Tests)
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const token = process.env.NOTES_TOKEN || '';
  if (token.length < 24) {
    console.error('NOTES_TOKEN fehlt oder ist zu kurz (mindestens 24 Zeichen, z. B. `openssl rand -hex 24`).');
    process.exit(1);
  }
  const vault = new Vault(process.env.DATA_DIR || path.resolve('data'));
  const port = Number(process.env.PORT || 3090);
  const mcpToken = process.env.MCP_TOKEN || '';
  if (mcpToken && mcpToken.length < 24) {
    console.error('MCP_TOKEN ist zu kurz (mindestens 24 Zeichen).');
    process.exit(1);
  }
  if (mcpToken === token) {
    console.error('MCP_TOKEN muss sich von NOTES_TOKEN unterscheiden.');
    process.exit(1);
  }
  const publicUrl = (process.env.PUBLIC_URL || '').trim();
  if (publicUrl && !/^https:\/\//.test(publicUrl)) {
    console.error('PUBLIC_URL muss mit https:// beginnen.');
    process.exit(1);
  }
  const redirectHosts = (process.env.OAUTH_REDIRECT_HOSTS || 'claude.ai,claude.com').split(',').map((h) => h.trim()).filter(Boolean);
  createApp(vault, token, mcpToken, { publicUrl, redirectHosts }).listen(port, () => console.log(`Notizen-Server läuft auf Port ${port}${mcpToken ? (publicUrl ? ' (MCP + OAuth-Connector aktiv)' : ' (MCP mit Token aktiv)') : ''}`));

  const backup = () => {
    try {
      if (vault.backup()) console.log('Sicherung angelegt');
    } catch (e) {
      console.error('Sicherung fehlgeschlagen:', e.message);
    }
  };
  setTimeout(backup, 5000);
  setInterval(backup, 6 * 3600_000);
}
