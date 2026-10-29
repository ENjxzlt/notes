import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appendIdea, removeIdea, parseIdeas, parseDue, taskLine, parseTasks, checkTask, appendLine, titleFromIdea, stamp } from '../public/inbox.js';

test('Inbox: anhängen, einlesen, entfernen', () => {
  let c = appendIdea('', 'Idee eins', '2026-10-29 12:31');
  c = appendIdea(c, 'Zeile A\nZeile B', '2026-10-29 12:40');
  assert.equal(c, '# Inbox\n\n- 2026-10-29 12:31 · Idee eins\n- 2026-10-29 12:40 · Zeile A / Zeile B\n');
  const ideas = parseIdeas(c);
  assert.deepEqual(ideas.map((i) => i.text), ['Idee eins', 'Zeile A / Zeile B']);
  assert.equal(removeIdea(c, ideas[0].raw), '# Inbox\n\n- 2026-10-29 12:40 · Zeile A / Zeile B\n');
  assert.equal(appendIdea(c, '   ', 'x'), c);
});

test('Zeitstempel hat das Format der Inbox', () => {
  assert.match(stamp(new Date(2026, 9, 29, 7, 5)), /^2026-10-29 07:05$/);
});

test('Fristen: Wochentage, morgen, Datum', () => {
  const today = new Date(2026, 9, 29); // Donnerstag
  assert.equal(parseDue('Freitag', today), '30.10.');
  assert.equal(parseDue('bis fr', today), '30.10.');
  assert.equal(parseDue('donnerstag', today), '29.10.');
  assert.equal(parseDue('Montag', today), '02.11.');
  assert.equal(parseDue('morgen', today), '30.10.');
  assert.equal(parseDue('3.11.', today), '03.11.');
  assert.equal(parseDue('31.2.', today), null);
  assert.equal(parseDue('', today), null);
  assert.equal(parseDue('irgendwann', today), null);
});

test('Aufgaben: Zeile bauen, einlesen, abhaken', () => {
  const line = taskLine('Übungsblatt 5 abgeben', 45, '30.10.');
  assert.equal(line, '- [ ] Übungsblatt 5 abgeben (~45 min · bis 30.10.)');
  assert.equal(taskLine('Milch', 10, null), '- [ ] Milch (~10 min)');
  assert.equal(taskLine('Nur Titel', null, null), '- [ ] Nur Titel');
  const note = `# Ableitung\n\n${line}\n- [x] fertig\n\`\`\`\n- [ ] im Code\n\`\`\`\n- [ ] Milch (~10 min)\n- [ ] Ohne Angaben`;
  const t = parseTasks(note);
  assert.deepEqual(t.map((x) => x.title), ['Übungsblatt 5 abgeben', 'Milch', 'Ohne Angaben']);
  assert.deepEqual(t.map((x) => x.minutes), [45, 10, null]);
  assert.equal(t[0].due, '30.10.');
  assert.equal(t[1].due, null);
  assert.ok(checkTask(note, t[0].line, 'Übungsblatt 5 abgeben').includes('- [x] Übungsblatt 5 abgeben (~45 min · bis 30.10.)'));
  assert.equal(checkTask(note, t[0].line, 'Anderer Titel'), null);
  assert.equal(appendLine('', 'T', '- [ ] x'), '# T\n\n- [ ] x\n');
});

test('Titel aus einer Idee', () => {
  assert.equal(titleFromIdea('GlutTube: Wiedergabelisten nach Stimmung sortieren, z. B. "Fokus"'), 'GlutTube- Wiedergabelisten nach Stimmung sortier'); // auf 48 Zeichen gekürzt, wie in der App
  assert.equal(titleFromIdea('...'), 'Idee');
});
