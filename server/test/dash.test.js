import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openTasks, activity, dashHtml, when } from '../public/dash.js';

const NOW = new Date(2026, 9, 29, 13, 0); // Donnerstag
const at = (y, m, d, h = 10) => new Date(y, m, d, h).getTime();

test('offene Aufgaben: erledigte und Codeblöcke zählen nicht', () => {
  const t = '- [ ] eins\n- [x] fertig\n* [ ] zwei\n```\n- [ ] im Code\n```\n- [ ] drei';
  assert.deepEqual(openTasks(t), ['eins', 'zwei', 'drei']);
});

test('Aktivität: 53 Wochen, Zukunft leer, Änderungen am richtigen Tag', () => {
  const a = activity([at(2026, 9, 29), at(2026, 9, 29, 15), at(2026, 9, 26), at(2020, 0, 1)], NOW);
  assert.equal(a.cells.length, 371);
  assert.equal(a.cells[367].n, 2); // Donnerstag der letzten Woche
  assert.equal(a.cells[364].n, 1); // Montag
  assert.equal(a.cells[367].level, 2);
  assert.equal(a.cells[368], null); // Freitag liegt in der Zukunft
  assert.equal(a.total, 3); // die Änderung von 2020 liegt außerhalb
  assert.ok(a.months.some((m) => m.name === 'Okt'));
});

test('Datumsanzeige: heute Uhrzeit, gestern, sonst Tag.Monat', () => {
  assert.match(when(at(2026, 9, 29, 9), NOW), /^09:00$/);
  assert.equal(when(at(2026, 9, 28), NOW), 'gestern');
  assert.equal(when(at(2026, 9, 20), NOW), '20.10.');
});

const files = new Map([
  ['Schule/Physik/Feld.md', { mtime: at(2026, 9, 29) }],
  ['Schule/Mathe/Ableitung.md', { mtime: at(2026, 9, 28) }],
  ['Projekte/<b>Plan</b>.md', { mtime: at(2026, 9, 27) }],
  ['Schule/Physik/.ink/1.json', { mtime: at(2026, 9, 29) }],
]);
const tasks = new Map([['Schule/Physik/Feld.md', { list: ['Formel abschreiben'] }]]);

test('Übersicht: feste Bereiche zuerst, Zähler, Aufgaben, maskierte Namen', () => {
  const html = dashHtml({ area: '', files, tasks, now: NOW });
  assert.ok(html.indexOf('data-go="Schule"') < html.indexOf('data-go="Projekte"'));
  assert.ok(html.indexOf('data-go="Projekte"') < html.indexOf('data-go="Alltag"'));
  assert.match(html, /2 Notizen/);
  assert.match(html, /Noch leer/); // Alltag
  assert.match(html, /Formel abschreiben/);
  assert.ok(!html.includes('<b>Plan</b>'));
  assert.match(html, /&lt;b&gt;Plan&lt;\/b&gt;/);
  assert.ok(!html.includes('.ink'));
});

test('Bereich: Unterordner als Karten, nur eigene Notizen', () => {
  const html = dashHtml({ area: 'Schule', files, tasks, now: NOW });
  assert.match(html, /data-go="Schule\/Physik"/);
  assert.match(html, /data-go="Schule\/Mathe"/);
  assert.ok(!html.includes('Projekte/'));
  assert.match(html, /<h1>Schule<\/h1>/);
});

test('leerer Server: Hinweis statt Fehler', () => {
  const html = dashHtml({ area: '', files: new Map(), tasks: new Map(), now: NOW });
  assert.match(html, /Noch keine Notizen/);
});
