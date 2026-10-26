// Ablage der Notizen: echte Dateien im Ordner vault/ (Markdown und .ink-Zeichnungen),
// Metadaten (Revision je Datei, Hash, Änderungsnummer) in SQLite.
//
// Revisionen statt Zeitstempel: jede Änderung erhöht die Revision der Datei. Ein Gerät darf nur
// schreiben, wenn es die aktuelle Revision kennt (base). Sonst gibt es einen Konflikt, und das Gerät
// legt eine Kopie an. Nichts wird stillschweigend überschrieben.

import { DatabaseSync } from 'node:sqlite';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const MAX_SIZE = 25 * 1024 * 1024;

export class HttpError extends Error {
  constructor(status, message, extra = {}) {
    super(message);
    this.status = status;
    this.extra = extra;
  }
}

const ALLOWED_EXT = new Set(['.md', '.json', '.svg']);

/** Prüft und normalisiert einen Pfad relativ zum Vault. Wirft 400 bei allem, was nicht erlaubt ist. */
export function checkPath(p) {
  if (typeof p !== 'string' || p.length === 0 || p.length > 300) throw new HttpError(400, 'Ungültiger Pfad');
  if (/[\0-\x1f\\]/.test(p) || p.startsWith('/') || p.endsWith('/')) throw new HttpError(400, 'Ungültiger Pfad');
  const parts = p.split('/');
  for (const seg of parts) {
    if (seg === '' || seg === '.' || seg === '..') throw new HttpError(400, 'Ungültiger Pfad');
    if (seg.startsWith('.') && seg !== '.ink') throw new HttpError(400, 'Versteckte Dateien werden nicht synchronisiert');
    if (seg.endsWith('.tmp') || seg.endsWith('.defekt')) throw new HttpError(400, 'Temporäre Dateien werden nicht synchronisiert');
  }
  const ext = path.extname(p).toLowerCase();
  if (!ALLOWED_EXT.has(ext)) throw new HttpError(400, 'Dateityp wird nicht synchronisiert');
  if (ext !== '.md' && !parts.slice(0, -1).includes('.ink')) throw new HttpError(400, 'JSON/SVG nur im Ordner .ink');
  return p;
}

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

export class Vault {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.root = path.join(dataDir, 'vault');
    fs.mkdirSync(this.root, { recursive: true });
    this.db = new DatabaseSync(path.join(dataDir, 'notes.db'));
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS files (
        path    TEXT PRIMARY KEY,
        rev     INTEGER NOT NULL,
        hash    TEXT,
        size    INTEGER NOT NULL DEFAULT 0,
        mtime   INTEGER NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0,
        seq     INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_files_seq ON files(seq);
      CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL);
      INSERT OR IGNORE INTO meta (k, v) VALUES ('seq', 0);
    `);
  }

  get seq() {
    return this.db.prepare("SELECT v FROM meta WHERE k = 'seq'").get().v;
  }

  #nextSeq() {
    this.db.prepare("UPDATE meta SET v = v + 1 WHERE k = 'seq'").run();
    return this.seq;
  }

  #row(p) {
    return this.db.prepare('SELECT * FROM files WHERE path = ?').get(p);
  }

  #file(p) {
    return path.join(this.root, ...p.split('/'));
  }

  static #info(r) {
    return { path: r.path, rev: r.rev, hash: r.hash, size: r.size, mtime: r.mtime, deleted: !!r.deleted, seq: r.seq };
  }

  /** Alle Änderungen nach der Nummer [since] (auch gelöschte Dateien als Grabstein). */
  changes(since) {
    const rows = this.db.prepare('SELECT * FROM files WHERE seq > ? ORDER BY seq').all(since);
    return { seq: this.seq, files: rows.map(Vault.#info) };
  }

  info(p) {
    const r = this.#row(checkPath(p));
    return r ? Vault.#info(r) : null;
  }

  read(p) {
    checkPath(p);
    const r = this.#row(p);
    if (!r || r.deleted) throw new HttpError(404, 'Nicht gefunden');
    return { info: Vault.#info(r), data: fs.readFileSync(this.#file(p)) };
  }

  /** Schreibt eine Datei, wenn [base] der aktuellen Revision entspricht (0 = neue Datei). */
  put(p, data, base) {
    checkPath(p);
    if (!Buffer.isBuffer(data)) throw new HttpError(400, 'Kein Inhalt');
    if (data.length > MAX_SIZE) throw new HttpError(413, 'Datei zu groß');
    const row = this.#row(p);
    const current = row ? row.rev : 0;
    // Nach einem Löschen darf ein Gerät, das davon nichts weiß, dieselbe Datei neu anlegen
    const okBase = base === current || (row?.deleted && base === 0);
    const hash = sha256(data);
    if (row && !row.deleted && row.hash === hash) return { ok: true, info: Vault.#info(row) }; // schon identisch
    if (!okBase) throw new HttpError(409, 'Konflikt', { conflict: true, current: row ? Vault.#info(row) : null });

    const file = this.#file(p);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, data);
    fs.renameSync(tmp, file);

    const rev = current + 1;
    const seq = this.#nextSeq();
    this.db.prepare(`
      INSERT INTO files (path, rev, hash, size, mtime, deleted, seq) VALUES (?, ?, ?, ?, ?, 0, ?)
      ON CONFLICT(path) DO UPDATE SET rev = excluded.rev, hash = excluded.hash, size = excluded.size,
        mtime = excluded.mtime, deleted = 0, seq = excluded.seq
    `).run(p, rev, hash, data.length, Date.now(), seq);
    return { ok: true, info: Vault.#info(this.#row(p)) };
  }

  /** Löscht eine Datei (Grabstein bleibt, damit andere Geräte das Löschen erfahren). */
  del(p, base) {
    checkPath(p);
    const row = this.#row(p);
    if (!row) throw new HttpError(404, 'Nicht gefunden');
    if (row.deleted) return { ok: true, info: Vault.#info(row) };
    if (base !== row.rev) throw new HttpError(409, 'Konflikt', { conflict: true, current: Vault.#info(row) });
    fs.rmSync(this.#file(p), { force: true });
    const seq = this.#nextSeq();
    this.db.prepare('UPDATE files SET rev = rev + 1, deleted = 1, hash = NULL, size = 0, mtime = ?, seq = ? WHERE path = ?')
      .run(Date.now(), seq, p);
    return { ok: true, info: Vault.#info(this.#row(p)) };
  }

  /** Tägliche Sicherung: Kopie des Vaults und der Datenbank, die letzten [keep] Tage bleiben. */
  backup(keep = 14, now = new Date()) {
    const day = now.toISOString().slice(0, 10);
    const dir = path.join(this.dataDir, 'backups', day);
    if (fs.existsSync(dir)) return false;
    fs.mkdirSync(dir, { recursive: true });
    fs.cpSync(this.root, path.join(dir, 'vault'), { recursive: true });
    this.db.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    fs.copyFileSync(path.join(this.dataDir, 'notes.db'), path.join(dir, 'notes.db'));
    const all = fs.readdirSync(path.join(this.dataDir, 'backups')).filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d)).sort();
    for (const old of all.slice(0, Math.max(0, all.length - keep))) {
      fs.rmSync(path.join(this.dataDir, 'backups', old), { recursive: true, force: true });
    }
    return true;
  }
}
