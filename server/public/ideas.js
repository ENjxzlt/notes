// Ansichten für Inbox-Durchsicht, Aufgaben-Übersicht und Wochenrückblick (reine Funktionen, die HTML bauen).
// Aller Text aus Notizen wird maskiert.
import { esc } from './md.js';
import { TOP_AREAS } from './dash.js';
import { titleOf } from './nav.js';

const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const chip = (label, on, attrs) => `<button type="button" class="chip${on ? ' on' : ''}" ${attrs}>${esc(label)}</button>`;
const MINUTES = [15, 30, 45, 60, 90];

/** Wochendurchsicht: eine Idee nach der anderen als Karte. */
export function ideasHtml({ ideas, idx, area = 'Schule', minutes = 45, due = '' }) {
  if (!ideas.length) {
    return `<header class="dash-head"><div><div class="dash-eyebrow">WOCHENDURCHSICHT · INBOX</div><h1>Inbox ist leer</h1></div></header>
      <p class="muted">Neue Ideen landen hier. In der Übersicht oder mit Strg+Alt+I festhalten.</p>
      <div><button type="button" class="btn" data-go="">Zur Übersicht</button></div>`;
  }
  const i = Math.max(0, Math.min(idx, ideas.length - 1));
  const idea = ideas[i];
  return `<header class="dash-head"><div><div class="dash-eyebrow">WOCHENDURCHSICHT · INBOX</div><h1>Idee ${i + 1} von ${ideas.length}</h1></div></header>
    <div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="${ideas.length}" aria-valuenow="${i + 1}" aria-label="Fortschritt"><span style="width:${Math.round(((i + 1) / ideas.length) * 100)}%"></span></div>
    <article class="panel idea"><div class="dash-eyebrow">${esc(idea.stamp)}</div><p>${esc(idea.text)}</p></article>
    <section class="panel">
      <h2>Zur Aufgabe machen</h2>
      <div class="field"><span class="muted small">Bereich</span><div class="chips">${TOP_AREAS.map((a) => chip(a, a === area, `data-area="${esc(a)}"`)).join('')}</div></div>
      <div class="field"><span class="muted small">Geschätzte Dauer</span><div class="chips">${MINUTES.map((m) => chip(`${m} min`, m === minutes, `data-min="${m}"`)).join('')}</div></div>
      <div class="field"><label class="muted small" for="dueInput">Frist (optional, z. B. Freitag oder 3.11.)</label><input id="dueInput" class="due" type="text" value="${esc(due)}" autocomplete="off"></div>
      <div class="actions">
        <button type="button" class="btn primary" data-idea="task">Aufgabe anlegen</button>
        <button type="button" class="btn" data-idea="note">Als Notiz</button>
        <span class="grow"></span>
        <button type="button" class="btn" data-idea="later">Später</button>
        <button type="button" class="btn warn" data-idea="delete">Löschen</button>
      </div>
    </section>
    <div class="actions"><button type="button" class="btn" data-idea="prev">‹ Vorige</button><button type="button" class="btn" data-idea="next">Nächste ›</button></div>`;
}

const metaOf = (t) => [t.minutes != null ? `~${t.minutes} min` : '', t.due ? `bis ${t.due}` : ''].filter(Boolean).join(' · ');

function taskRow(t, idx) {
  const meta = metaOf(t);
  return `<div class="item task"><button type="button" class="box" data-check="${idx}" aria-label="${esc(t.title)} abhaken"></button>
    <button type="button" class="grow2 link" data-note="${esc(t.path)}"><span class="t">${esc(t.title)}</span><span class="p">${esc(titleOf(t.path))}</span></button>
    ${meta ? `<span class="when wide">${esc(meta)}</span>` : ''}</div>`;
}

/** Alle offenen Aufgaben, nach Bereich gruppiert. */
export function tasksHtml({ tasks, filter = '' }) {
  const shown = tasks.filter((t) => !filter || t.path.startsWith(`${filter}/`));
  const chips = [['', `Alle ${tasks.length}`], ...TOP_AREAS.map((a) => [a, `${a} ${tasks.filter((t) => t.path.startsWith(`${a}/`)).length}`])];
  const groups = new Map();
  tasks.forEach((t, idx) => {
    if (!shown.includes(t)) return;
    const g = t.path.includes('/') ? t.path.split('/')[0] : 'Ohne Bereich';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push([t, idx]);
  });
  const order = [...groups.keys()].sort((a, b) => (TOP_AREAS.indexOf(a) + 1 || 99) - (TOP_AREAS.indexOf(b) + 1 || 99) || a.localeCompare(b, 'de'));
  return `<header class="dash-head"><div><div class="dash-eyebrow">ALLE NOTIZEN</div><h1>Aufgaben</h1></div></header>
    <div class="chips">${chips.map(([f, l]) => chip(l, f === filter, `data-tf="${esc(f)}"`)).join('')}</div>
    ${shown.length ? order.map((g) => `<section class="panel"><div class="split"><h2>${esc(g)}</h2><span class="muted small mono">${groups.get(g).length}</span></div><div class="list">${groups.get(g).map(([t, idx]) => taskRow(t, idx)).join('')}</div></section>`).join('') : '<p class="muted">Keine offenen Aufgaben.</p>'}
    <p class="muted small">Aufgaben sind alle <code>- [ ]</code> aus deinen Notizen; Abhaken schreibt <code>- [x]</code> in die Notiz.</p>`;
}

/** Wochenrückblick der laufenden Woche (Montag bis Sonntag) aus den Änderungszeiten der Notizen. */
export function reviewHtml({ notes, tasks, ideas, now = new Date() }) {
  const dow = (now.getDay() + 6) % 7;
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - dow);
  const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6, 23, 59, 59);
  const fmt = (d) => `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.`;
  const inWeek = notes.filter((n) => n.mtime >= monday.getTime() && n.mtime <= sunday.getTime());
  const perDay = Array(7).fill(0);
  inWeek.forEach((n) => perDay[(new Date(n.mtime).getDay() + 6) % 7]++);
  const max = Math.max(1, ...perDay);
  const days = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  const withDue = tasks.filter((t) => t.due);
  // ISO-Kalenderwoche
  const thu = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 3);
  const week = Math.ceil(((thu - new Date(thu.getFullYear(), 0, 1)) / 86400000 + 1) / 7);
  return `<header class="dash-head"><div><div class="dash-eyebrow">${fmt(monday)} BIS ${fmt(new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6))}</div><h1>Wochenrückblick · Woche ${week}</h1></div></header>
    <section class="stats">
      <div class="panel"><b>${inWeek.length}</b><span class="muted">Notizen bearbeitet</span></div>
      <div class="panel"><b>${tasks.length}</b><span class="muted">Aufgaben offen</span></div>
      <div class="panel"><b>${ideas.length}</b><span class="muted">Ideen in der Inbox</span></div>
    </section>
    <section class="panel"><h2>Aktivität pro Tag</h2><div class="bars" role="img" aria-label="Bearbeitete Notizen pro Wochentag: ${days.map((d, i) => `${d} ${perDay[i]}`).join(', ')}">${perDay.map((v, i) => `<div class="bar"><i class="${i === dow ? 'today' : ''}" style="height:${Math.round((v / max) * 100)}%"></i><span>${days[i]}</span></div>`).join('')}</div></section>
    <section class="panel"><h2>Offen mit Frist</h2>${withDue.length ? `<div class="list">${withDue.slice(0, 8).map((t) => taskRow(t, -1).replace(/<button type="button" class="box"[^>]*><\/button>/, '')).join('')}</div>` : '<p class="muted">Keine Aufgaben mit Frist.</p>'}</section>
    <section class="panel"><h2>Zusammenfassung von Claude</h2><p>Auf Zuruf: Bitte Claude über den Notizen-Connector, die Woche zusammenzufassen oder Lernkarten zu einem Fach zu erstellen. Claude liest nur, was in deinen Notizen steht.</p></section>`;
}

export { plural };
