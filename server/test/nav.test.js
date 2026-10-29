import { test } from 'node:test';
import assert from 'node:assert/strict';
import { folderCounts, folderPeers, pager, crumbs, pushRecent, togglePin, prune, search } from '../public/nav.js';

const paths = [
  'Schule/Physik/Elektrisches Feld.md',
  'Schule/Physik/Magnetismus.md',
  'Schule/Physik/Mechanik Grundlagen.md',
  'Schule/Mathe/Ableitung.md',
  'Schule/Chemie/Elektrolyse.md',
  'Projekte/Notizen-App.md',
  'Projekte/Basteln/Elektronik.md',
  'Alltag/Einkauf.md',
  'Eigene Notiz.md',
];
const mt = { 'Schule/Physik/Elektrisches Feld.md': 9, 'Schule/Chemie/Elektrolyse.md': 5, 'Projekte/Basteln/Elektronik.md': 7 };
const mtime = (p) => mt[p] || 1;
const text = (p) => (p === 'Schule/Mathe/Ableitung.md' ? 'hier geht es um das elektrische Potential' : undefined);

test('Ordnerzähler zählen Unterordner mit', () => {
  const c = folderCounts(paths);
  assert.equal(c.get('Schule'), 5);
  assert.equal(c.get('Schule/Physik'), 3);
  assert.equal(c.get('Projekte'), 2);
  assert.equal(c.has('Eigene Notiz'), false);
});

test('Geschwisterordner: Bereiche zuerst, aktueller markiert', () => {
  const top = folderPeers(paths, 'Schule');
  assert.deepEqual(top.map((f) => f.name), ['Schule', 'Projekte', 'Alltag']);
  const sub = folderPeers(paths, 'Schule/Physik');
  assert.deepEqual(sub.map((f) => f.name), ['Chemie', 'Mathe', 'Physik']);
  assert.equal(sub.find((f) => f.current).name, 'Physik');
  assert.equal(sub.find((f) => f.name === 'Physik').count, 3);
});

test('Blättern: Position und Nachbarn im Ordner', () => {
  const p = pager(paths, 'Schule/Physik/Magnetismus.md');
  assert.deepEqual([p.index, p.total, p.prev, p.next], [1, 3, 'Schule/Physik/Elektrisches Feld.md', 'Schule/Physik/Mechanik Grundlagen.md']);
  const first = pager(paths, 'Schule/Physik/Elektrisches Feld.md');
  assert.equal(first.prev, null);
  assert.equal(pager(paths, 'Eigene Notiz.md').folder, '');
});

test('Pfadkette nur aus Ordnern', () => {
  assert.deepEqual(crumbs('Schule/Physik/Magnetismus.md'), [{ label: 'Schule', path: 'Schule' }, { label: 'Physik', path: 'Schule/Physik' }]);
  assert.deepEqual(crumbs('Eigene Notiz.md'), []);
});

test('Verlauf, Pins und Aufräumen', () => {
  let r = pushRecent([], 'a.md');
  r = pushRecent(r, 'b.md');
  r = pushRecent(r, 'a.md');
  assert.deepEqual(r, ['a.md', 'b.md']);
  assert.equal(pushRecent(['1', '2', '3'], '4', 3).length, 3);
  const t = togglePin([], 'a.md');
  assert.deepEqual([t.pinned, t.list], [true, ['a.md']]);
  assert.deepEqual(togglePin(t.list, 'a.md').list, []);
  assert.deepEqual(prune(['a.md', 'weg.md'], (p) => p === 'a.md'), ['a.md']);
});

test('Suche: Titel vor Pfad vor Text, Treffer hervorgehoben', () => {
  const r = search({ paths, mtime, text, query: 'elek' });
  assert.deepEqual(r.map((x) => x.title), ['Elektrisches Feld', 'Elektronik', 'Elektrolyse', 'Ableitung']);
  assert.deepEqual(r[0].parts, ['', 'Elek', 'trisches Feld']);
  assert.equal(r[3].via, 'text');
  assert.equal(r[3].parts, null);
});

test('Suche: Bereichsfilter und leere Eingabe', () => {
  assert.ok(search({ paths, mtime, text, query: 'elek', scope: 'Projekte' }).every((x) => x.area === 'Projekte'));
  const recent = search({ paths, mtime, text, query: '' });
  assert.equal(recent[0].title, 'Elektrisches Feld'); // jüngste zuerst
});

test('Suche mit „/“ findet nur Ordner', () => {
  const r = search({ paths, mtime, text, query: '/phy' });
  assert.deepEqual(r.map((x) => [x.kind, x.path, x.count]), [['folder', 'Schule/Physik', 3]]);
  assert.ok(search({ paths, mtime, text, query: '/' }).every((x) => x.kind === 'folder'));
});
