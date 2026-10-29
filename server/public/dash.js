// Übersicht und Bereiche (Schule, Projekte, Alltag): reine Funktionen, die HTML aus der Dateiliste bauen.
// Aller Text aus Dateinamen wird maskiert; die Funktionen greifen weder auf das DOM noch auf das Netz zu.
import { esc } from './md.js';

export const TOP_AREAS = ['Schule', 'Projekte', 'Alltag'];
const MONTHS = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];
const WEEKS = 53;

const titleOf = (p) => p.split('/').pop().replace(/\.md$/, '');
export const inArea = (p, area) => !area || p.startsWith(`${area}/`);
const dayKey = (d) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/** Offene Aufgaben (`- [ ] …`) einer Notiz; Codeblöcke werden übersprungen. */
export function openTasks(text) {
  const out = [];
  let fence = false;
  for (const line of String(text).split('\n')) {
    if (/^\s*```/.test(line)) {
      fence = !fence;
      continue;
    }
    if (fence) continue;
    const m = line.match(/^\s*[-*] \[ \] (.+)$/);
    if (m) out.push(m[1].trim());
  }
  return out;
}

/** 53 Wochen (Montag zuerst) bis heute; `null` = Tag in der Zukunft. Gezählt wird je Notiz der Tag der letzten Änderung. */
export function activity(mtimes, now = new Date()) {
  const counts = new Map();
  for (const t of mtimes) {
    const k = dayKey(new Date(t));
    counts.set(k, (counts.get(k) || 0) + 1);
  }
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dow = (today.getDay() + 6) % 7;
  const cells = [];
  const months = [];
  let total = 0;
  for (let i = 0; i < WEEKS * 7; i++) {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - dow - (WEEKS - 1) * 7 + i);
    if (d > today) {
      cells.push(null);
      continue;
    }
    const n = counts.get(dayKey(d)) || 0;
    total += n;
    cells.push({ d, n, level: n === 0 ? 0 : n === 1 ? 1 : n <= 3 ? 2 : n <= 6 ? 3 : 4 });
    if (i % 7 === 0 && d.getDate() <= 7) months.push({ name: MONTHS[d.getMonth()], col: i / 7 + 1 });
  }
  return { cells, months, total };
}

/** Uhrzeit für heute, sonst „gestern“ oder Tag.Monat. */
export function when(t, now = new Date()) {
  const d = new Date(t);
  const a = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const b = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diff = Math.round((a - b) / 86400000);
  if (diff === 0) return d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  if (diff === 1) return 'gestern';
  return d.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
}

function graph(paths, files, now) {
  const a = activity(paths.map((p) => files.get(p).mtime), now);
  const label = `${plural(a.total, 'Notiz', 'Notizen')} im letzten Jahr nach Tag der letzten Änderung`;
  const months = a.months.map((m) => `<span style="grid-column: ${m.col} / span 4">${m.name}</span>`).join('');
  const cells = a.cells
    .map((c) => {
      if (!c) return '<i class="fut"></i>';
      const day = c.d.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' });
      return `<i class="lv${c.level}" title="${esc(`${plural(c.n, 'Notiz', 'Notizen')} am ${day}`)}"></i>`;
    })
    .join('');
  return `<section class="panel">
    <div class="split"><h2>Aktivität</h2><span class="muted small">${esc(label)}</span></div>
    <div class="act-scroll"><div class="act" role="img" aria-label="${esc(label)}">
      <div class="act-days" aria-hidden="true"><span>Mo</span><span></span><span>Mi</span><span></span><span>Fr</span><span></span><span></span></div>
      <div><div class="act-months" aria-hidden="true">${months}</div><div class="act-grid">${cells}</div></div>
    </div></div>
    <div class="act-legend" aria-hidden="true">Weniger <i class="lv0"></i><i class="lv1"></i><i class="lv2"></i><i class="lv3"></i><i class="lv4"></i> Mehr</div>
  </section>`;
}

function folderCards(paths, files, tasks, area, allPaths) {
  const groups = new Map();
  if (!area) for (const a of TOP_AREAS) groups.set(a, []);
  for (const p of paths) {
    const rest = area ? p.slice(area.length + 1) : p;
    if (!rest.includes('/')) continue;
    const name = rest.split('/')[0];
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(p);
  }
  const names = [...groups.keys()].sort((a, b) => {
    const ia = area ? -1 : TOP_AREAS.indexOf(a);
    const ib = area ? -1 : TOP_AREAS.indexOf(b);
    if (ia >= 0 || ib >= 0) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
    return a.localeCompare(b, 'de');
  });
  if (!names.length) return '';
  const cards = names.map((name) => {
    const ps = groups.get(name);
    const full = area ? `${area}/${name}` : name;
    const loaded = ps.every((p) => tasks.has(p));
    const open = ps.reduce((n, p) => n + (tasks.get(p)?.list.length || 0), 0);
    const last = [...ps].sort((a, b) => files.get(b).mtime - files.get(a).mtime)[0];
    return `<button type="button" class="card" data-go="${esc(full)}">
      <span class="t">${esc(name)}<span>öffnen →</span></span>
      <span class="s">${last ? `Zuletzt: ${esc(titleOf(last))}` : 'Noch leer'}</span>
      <span class="m"><span>${esc(plural(ps.length, 'Notiz', 'Notizen'))}</span><span>${loaded && ps.length ? `${open} offen` : ''}</span></span>
    </button>`;
  });
  return `<section><h2>${area ? 'Ordner' : 'Bereiche'}</h2><div class="cards">${cards.join('')}</div></section>`;
}

function recentList(paths, files, area, now) {
  const top = [...paths].sort((a, b) => files.get(b).mtime - files.get(a).mtime).slice(0, 8);
  if (!top.length) return '<p class="muted">Noch keine Notizen. Mit „Neue Notiz“ geht es los.</p>';
  return `<div class="list">${top
    .map((p) => {
      const dir = p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '';
      return `<button type="button" class="item" data-note="${esc(p)}">
        <span class="grow2"><span class="t">${esc(titleOf(p))}</span><span class="p">${esc(p)}</span></span>
        ${!area && dir ? `<span class="chip">${esc(dir.split('/')[0])}</span>` : ''}
        <span class="when">${esc(when(files.get(p).mtime, now))}</span>
      </button>`;
    })
    .join('')}</div>`;
}

function taskList(paths, files, tasks) {
  const items = [];
  for (const p of [...paths].sort((a, b) => files.get(b).mtime - files.get(a).mtime)) {
    for (const t of tasks.get(p)?.list || []) items.push({ p, t });
  }
  const loaded = paths.every((p) => tasks.has(p));
  if (!items.length) return `<p class="muted">${loaded ? 'Keine offenen Aufgaben.' : 'Lädt …'}</p>`;
  return `<div class="list">${items
    .slice(0, 8)
    .map(({ p, t }) => `<button type="button" class="item" data-note="${esc(p)}"><span class="box" aria-hidden="true"></span><span class="grow2"><span class="t">${esc(t)}</span><span class="p">${esc(titleOf(p))}</span></span></button>`)
    .join('')}</div>${items.length > 8 ? `<p class="muted small">und ${items.length - 8} weitere</p>` : ''}`;
}

/** @param {{area:string, files:Map<string,{mtime:number}>, tasks:Map<string,{list:string[]}>, now?:Date}} ctx */
/** Eingabezeile „Idee festhalten“ (Übersicht, Bereiche und das Fenster mit Strg+Alt+I). */
export function captureHtml(id = 'ideaInput') {
  return `<form class="capture" data-capture>
    <label class="sr-only" for="${id}">Idee festhalten</label>
    <input id="${id}" type="text" placeholder="Idee festhalten …" autocomplete="off">
    <button type="button" class="icon-btn boxed mic" data-act="dictate" aria-label="Diktieren" title="Diktieren" hidden><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>
    <button type="submit" class="btn primary">Festhalten</button>
  </form>`;
}

function inboxCard(ideas) {
  if (!ideas.length) return '';
  const rows = ideas.slice(-4).reverse().map((i) => `<div class="item"><span class="grow2"><span class="t">${esc(i.text)}</span></span><span class="when">${esc(i.stamp.slice(11))}</span></div>`).join('');
  return `<section class="panel"><div class="split"><h2>Inbox · ${plural(ideas.length, 'neue Idee', 'neue Ideen')}</h2><span class="muted small">Durchsicht am Sonntag</span></div>
    <div class="list">${rows}</div><div class="actions"><button type="button" class="btn primary" data-go="!ideen">Jetzt durchsehen</button></div></section>`;
}

export function dashHtml({ area, files, tasks, ideas = [], now = new Date() }) {
  const all = [...files.keys()].filter((p) => p.endsWith('.md') && p !== 'Inbox.md');
  const paths = all.filter((p) => inArea(p, area));
  const eyebrow = area
    ? esc(`${area}/`)
    : esc(now.toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' }).toUpperCase());
  const head = `<header class="dash-head">
    <div><div class="dash-eyebrow">${eyebrow}</div><h1>${esc(area ? area.split('/').pop() : 'Übersicht')}</h1></div>
    <div class="dash-actions"><button type="button" class="btn back" data-act="tree">Alle Notizen</button><button type="button" class="btn primary" data-act="new">Neue Notiz</button></div>
  </header>`;
  return `${head}
    <section class="panel capture-panel">${captureHtml()}<p class="muted small">Landet in der Inbox, sortiert wird bei der Durchsicht. Strg+Alt+I öffnet das Fenster überall in der App.</p></section>
    ${area ? '' : inboxCard(ideas)}
    ${folderCards(paths, files, tasks, area, all)}
    ${graph(paths, files, now)}
    <section class="two">
      <div class="panel"><h2>Zuletzt bearbeitet</h2>${recentList(paths, files, area, now)}</div>
      <div class="panel"><h2>Offene Aufgaben</h2>${taskList(paths, files, tasks)}</div>
    </section>`;
}
