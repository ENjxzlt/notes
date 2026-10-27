import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderMarkdown } from '../public/md.js';

const r = (s, dir = '') => renderMarkdown(s, { dir });

test('Überschriften, Absätze, Fett, Kursiv, Code', () => {
  const html = r('# Titel\n\nText mit **fett**, *kursiv* und `code <b>`.\nzweite Zeile');
  assert.match(html, /<h1>Titel<\/h1>/);
  assert.match(html, /<strong>fett<\/strong>/);
  assert.match(html, /<em>kursiv<\/em>/);
  assert.match(html, /<code>code &lt;b&gt;<\/code>/);
  assert.match(html, /kursiv.*zweite Zeile/s);
});

test('Listen und Aufgaben', () => {
  const html = r('- a\n- b\n\n1. eins\n2. zwei\n\n- [ ] offen\n- [x] fertig');
  assert.match(html, /<ul><li>a<\/li><li>b<\/li><\/ul>/);
  assert.match(html, /<ol><li>eins<\/li><li>zwei<\/li><\/ol>/);
  assert.match(html, /<li class="task"><input type="checkbox" disabled> offen<\/li>/);
  assert.match(html, /<li class="task done"><input type="checkbox" disabled checked> fertig<\/li>/);
});

test('Codeblock wird nicht als Markdown gelesen', () => {
  const html = r('```\n# kein Titel\n**nicht fett**\n```');
  assert.match(html, /<pre><code># kein Titel\n\*\*nicht fett\*\*<\/code><\/pre>/);
  assert.doesNotMatch(html, /<h1>/);
});

test('HTML und Skripte werden maskiert', () => {
  const html = r('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n# <b>x</b>');
  assert.doesNotMatch(html, /<script|<img src=x|<b>/);
  assert.match(html, /&lt;script&gt;/);
});

test('gefährliche Links werden entschärft, normale bleiben', () => {
  let html = r('[x](javascript:alert(1)) [y](data:text/html;base64,AAAA) [z](https://example.org/a?b=1)');
  assert.doesNotMatch(html, /javascript:|data:/);
  assert.match(html, /<a href="https:\/\/example\.org\/a\?b=1" target="_blank" rel="noopener noreferrer">z<\/a>/);
  html = r('[a" onclick="alert(1)](https://e.org)');
  assert.doesNotMatch(html, /onclick="/);
});

test('fremde Bilder werden nicht geladen, Zeichnungen aus .ink schon (mit Pfad zur Notiz)', () => {
  let html = r('![tracker](https://evil.example/pixel.png)');
  assert.doesNotMatch(html, /<img/);
  html = r('![Zeichnung](.ink/ab12cd34.svg)', 'Schule/Physik');
  assert.match(html, /<img class="ink" alt="Zeichnung" data-ink="Schule\/Physik\/\.ink\/ab12cd34\.svg">/);
  html = r('![x](../../.ink/a.svg) ![y](.ink/../a.svg)');
  assert.doesNotMatch(html, /<img/);
});

test('Steuerzeichen im Text können keine Platzhalter vortäuschen', () => {
  const html = r('[a](https://e.org) \u00000\u0000 text');
  assert.doesNotMatch(html, /undefined/);
});

test('Zitat und Trennlinie', () => {
  const html = r('> Zitat\n> weiter\n\n---');
  assert.match(html, /<blockquote><p>Zitat weiter<\/p><\/blockquote>/);
  assert.match(html, /<hr>/);
});
