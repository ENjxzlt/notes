// Navigation: Suche für den Schnellwechsel, Geschwister, Blättern, Pins und Verlauf. Reine Funktionen ohne DOM.
import { TOP_AREAS } from './dash.js';

const cmp = (a, b) => a.localeCompare(b, 'de');
export const titleOf = (p) => p.split('/').pop().replace(/\.md$/, '');
export const dirOf = (p) => (p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '');

/** Anzahl der Notizen je Ordner, einschließlich aller Unterordner. */
export function folderCounts(paths) {
  const counts = new Map();
  for (const p of paths) {
    const parts = p.split('/').slice(0, -1);
    for (let i = 1; i <= parts.length; i++) {
      const f = parts.slice(0, i).join('/');
      counts.set(f, (counts.get(f) || 0) + 1);
    }
  }
  return counts;
}

/** Ordner auf derselben Ebene wie `folder` (er selbst eingeschlossen), mit Notizanzahl. Auf oberster Ebene stehen die festen Bereiche zuerst. */
export function folderPeers(paths, folder) {
  const parent = dirOf(folder);
  const counts = folderCounts(paths);
  const names = new Set();
  for (const f of counts.keys()) if (dirOf(f) === parent) names.add(f);
  if (!parent) for (const a of TOP_AREAS) names.add(a);
  const rank = (f) => (parent ? -1 : TOP_AREAS.indexOf(f));
  return [...names]
    .sort((a, b) => {
      const ra = rank(a);
      const rb = rank(b);
      if (ra >= 0 || rb >= 0) return (ra < 0 ? 99 : ra) - (rb < 0 ? 99 : rb) || cmp(a, b);
      return cmp(a, b);
    })
    .map((f) => ({ path: f, name: f.split('/').pop(), count: counts.get(f) || 0, current: f === folder }));
}

/** Position der Notiz in ihrem Ordner (nach Titel sortiert) und die Nachbarn zum Blättern. */
export function pager(paths, path) {
  const dir = dirOf(path);
  const same = paths.filter((p) => dirOf(p) === dir).sort((a, b) => cmp(titleOf(a), titleOf(b)));
  const i = same.indexOf(path);
  return { folder: dir, index: i, total: same.length, prev: i > 0 ? same[i - 1] : null, next: i >= 0 && i < same.length - 1 ? same[i + 1] : null };
}

/** Ordnerkette einer Notiz: [{label, path}] für jeden Ordner von außen nach innen. */
export function crumbs(path) {
  const parts = path.split('/').slice(0, -1);
  return parts.map((label, i) => ({ label, path: parts.slice(0, i + 1).join('/') }));
}

// ---- Pins und Verlauf (Listen von Pfaden, gespeichert wird außerhalb) ----

export function pushRecent(list, path, max = 6) {
  return [path, ...list.filter((p) => p !== path)].slice(0, max);
}

export function togglePin(list, path) {
  const pinned = !list.includes(path);
  return { pinned, list: pinned ? [...list, path] : list.filter((p) => p !== path) };
}

export const prune = (list, has) => list.filter((p) => has(p));

// ---- Schnellwechsel ----

const hit = (title, q) => {
  const i = title.toLowerCase().indexOf(q);
  return i < 0 ? null : [title.slice(0, i), title.slice(i, i + q.length), title.slice(i + q.length)];
};

/**
 * Suche nach Titel, Pfad und Text. Beginnt die Eingabe mit „/“, werden nur Ordner gesucht.
 * @param {{paths:string[], mtime:(p:string)=>number, text:(p:string)=>string|undefined, query:string, scope?:string, limit?:number}} o
 */
export function search({ paths, mtime, text, query, scope = '', limit = 12 }) {
  const inScope = (p) => !scope || p.startsWith(`${scope}/`);
  const raw = query.trim();
  const area = (p) => p.split('/')[0] || '';

  if (raw.startsWith('/')) {
    const q = raw.slice(1).trim().toLowerCase();
    const counts = folderCounts(paths.filter(inScope));
    return [...counts.keys()]
      .filter((f) => !q || f.toLowerCase().includes(q))
      .sort((a, b) => cmp(a, b))
      .slice(0, limit)
      .map((f) => ({ kind: 'folder', path: f, title: f.split('/').pop(), dir: dirOf(f), area: area(f), count: counts.get(f), parts: hit(f.split('/').pop(), q) }));
  }

  const q = raw.toLowerCase();
  const pool = paths.filter(inScope);
  const out = [];
  for (const p of pool) {
    const title = titleOf(p);
    let score = -1;
    let via = 'title';
    if (!q) score = 0;
    else if (title.toLowerCase().startsWith(q)) score = 0;
    else if (title.toLowerCase().includes(q)) score = 1;
    else if (p.toLowerCase().includes(q)) {
      score = 2;
      via = 'path';
    } else if ((text(p) || '').toLowerCase().includes(q)) {
      score = 3;
      via = 'text';
    }
    if (score < 0) continue;
    out.push({ kind: 'note', path: p, title, dir: dirOf(p), area: area(p), mtime: mtime(p), via, parts: q ? hit(title, q) : null, score });
  }
  out.sort((a, b) => a.score - b.score || b.mtime - a.mtime);
  return out.slice(0, limit);
}
