import express from 'express';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Vault, HttpError, MAX_SIZE } from './vault.js';

/** Baut die App; [token] ist das gemeinsame Geheimnis der Geräte. */
export function createApp(vault, token) {
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

  app.get('/api/health', (req, res) => res.json({ ok: true }));

  app.use('/api', (req, res, next) => {
    const ip = req.ip;
    if (limited(ip)) return res.status(429).json({ error: 'Zu viele Fehlversuche' });
    const given = Buffer.from((req.headers.authorization || '').replace(/^Bearer\s+/i, ''));
    if (given.length === wanted.length && crypto.timingSafeEqual(given, wanted)) return next();
    fail(ip);
    res.status(401).json({ error: 'Nicht angemeldet' });
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
  createApp(vault, token).listen(port, () => console.log(`Notizen-Server läuft auf Port ${port}`));

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
