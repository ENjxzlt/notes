import { renderMarkdown, esc } from './md.js';
import { dashHtml, openTasks, when } from './dash.js';
import { folderCounts, folderPeers, pager, crumbs, pushRecent, togglePin, prune, search, titleOf } from './nav.js';

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

// ---------- Zugang ----------

let token = localStorage.getItem('notizen-token') || '';

async function api(method, url, body) {
  const res = await fetch(url, { method, headers: { Authorization: `Bearer ${token}` }, body });
  if (res.status === 401) {
    showLogin('Token ungültig');
    throw new Error('unauthorized');
  }
  return res;
}

function showLogin(msg = '') {
  $('#app').hidden = true;
  $('#login').hidden = false;
  const e = $('#loginError');
  e.textContent = msg;
  e.hidden = !msg;
  stopLive();
}

$('#loginForm').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  token = $('#tokenInput').value.trim();
  try {
    const r = await fetch('/api/changes?since=0', { headers: { Authorization: `Bearer ${token}` } });
    if (r.status === 401) return showLogin('Token falsch');
    if (!r.ok) return showLogin(`Server antwortet ${r.status}`);
    localStorage.setItem('notizen-token', token);
    $('#tokenInput').value = '';
    start();
  } catch {
    showLogin('Server nicht erreichbar');
  }
});

$('#logoutBtn').addEventListener('click', () => {
  localStorage.removeItem('notizen-token');
  token = '';
  showLogin();
});

// ---------- Dateiliste (Änderungsnummer, inkrementell) ----------

const files = new Map(); // Pfad -> { rev, size, mtime }
let seq = 0;

async function pull() {
  const r = await api('GET', `/api/changes?since=${seq}`);
  const j = await r.json();
  const changed = [];
  for (const f of j.files) {
    if (f.deleted) files.delete(f.path);
    else files.set(f.path, f);
    changed.push(f.path);
  }
  seq = j.seq;
  return changed;
}

const mdFiles = () => [...files.keys()].filter((p) => p.endsWith('.md')).sort((a, b) => a.localeCompare(b, 'de'));

// ---------- Baum ----------

const openFolders = new Set(JSON.parse(localStorage.getItem('notizen-open') || '[]'));
let current = null; // { path, rev, base }

const load = (key) => {
  try {
    const v = JSON.parse(localStorage.getItem(key) || '[]');
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
};
const store = (key, v) => localStorage.setItem(key, JSON.stringify(v));
let pins = load('notizen-pins'); // angepinnte Notizen (nur in diesem Browser)
let recents = load('notizen-recent'); // zuletzt geöffnete Notizen

/** Klappt alle Ordner über `path` auf, damit die Notiz im Baum sichtbar ist. */
function reveal(path) {
  for (let d = dirOf(path); d; d = dirOf(d)) openFolders.add(d);
  store('notizen-open', [...openFolders]);
}

function renderSide() {
  pins = prune(pins, (p) => files.has(p));
  recents = prune(recents, (p) => files.has(p));
  const sec = (title, list, ico) =>
    list.length
      ? `<div class="side-label">${title}</div>${list
          .map((p) => `<button type="button" class="row item${current?.path === p ? ' sel' : ''}" data-note="${esc(p)}"><span class="ico">${ico}</span><span class="name">${esc(titleOf(p))}</span><span class="meta">${esc(dirOf(p).split('/').pop())}</span></button>`)
          .join('')}`
      : '';
  $('#pins').innerHTML = sec('Angepinnt', pins, '★');
  $('#recents').innerHTML = sec('Zuletzt geöffnet', recents.filter((p) => !pins.includes(p)).slice(0, 4), '◷');
}

for (const id of ['#pins', '#recents']) {
  $(id).addEventListener('click', (e) => {
    const n = e.target.closest('[data-note]');
    if (n) openNote(n.dataset.note);
  });
}

function buildTree(paths) {
  const root = { folders: new Map(), notes: [] };
  for (const p of paths) {
    const parts = p.split('/');
    let node = root;
    for (const seg of parts.slice(0, -1)) {
      if (!node.folders.has(seg)) node.folders.set(seg, { folders: new Map(), notes: [] });
      node = node.folders.get(seg);
    }
    node.notes.push(p);
  }
  return root;
}

function renderTree() {
  const box = $('#tree');
  const paths = mdFiles();
  renderSide();
  if (!paths.length) {
    box.innerHTML = '<div class="none">Noch keine Notizen.</div>';
    return;
  }
  const counts = folderCounts(paths);
  const row = (cls, ico, name, meta, attrs) => `<button type="button" class="row ${cls}" ${attrs}><span class="ico">${ico}</span><span class="name">${esc(name)}</span>${meta ? `<span class="meta">${esc(meta)}</span>` : ''}</button>`;
  const walk = (node, prefix) => {
    let html = '<ul>';
    for (const [name, child] of [...node.folders].sort((a, b) => a[0].localeCompare(b[0], 'de'))) {
      const full = prefix ? `${prefix}/${name}` : name;
      const open = openFolders.has(full);
      const here = dashOn && area === full ? ' sel' : '';
      html += `<li>${row(`folder${here}`, open ? '▾' : '▸', name, String(counts.get(full) || 0), `data-folder="${esc(full)}" aria-expanded="${open}"`)}${open ? walk(child, full) : ''}</li>`;
    }
    for (const p of node.notes.sort((a, b) => a.localeCompare(b, 'de'))) {
      const name = p.split('/').pop().replace(/\.md$/, '');
      const date = new Date(files.get(p).mtime).toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
      html += `<li>${row(`item${current?.path === p ? ' sel' : ''}`, '·', name, date, `data-note="${esc(p)}"`)}</li>`;
    }
    return `${html}</ul>`;
  };
  box.innerHTML = walk(buildTree(paths), '');
}

$('#tree').addEventListener('click', (e) => {
  const f = e.target.closest('[data-folder]');
  if (f) {
    const p = f.dataset.folder;
    if (openFolders.has(p)) openFolders.delete(p);
    else openFolders.add(p);
    localStorage.setItem('notizen-open', JSON.stringify([...openFolders]));
    return renderTree();
  }
  const n = e.target.closest('[data-note]');
  if (n) openNote(n.dataset.note);
});


// ---------- Notiz öffnen, anzeigen, speichern ----------

const editor = $('#editor');
let dirty = false;
let saveTimer = null;
let saving = false;
let lastSaved = ''; // Text, wie er auf dem Server liegt

const dirOf = (p) => (p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '');
const setStatus = (t) => ($('#status').textContent = t);

async function openNote(path) {
  if (current && dirty) await save();
  const r = await api('GET', `/api/file?path=${encodeURIComponent(path)}`);
  if (!r.ok) return setStatus('Konnte nicht laden');
  const text = await r.text();
  current = { path, rev: Number(r.headers.get('x-rev')) };
  editor.value = text;
  lastSaved = text;
  dirty = false;
  dashOn = false;
  $('#dash').hidden = true;
  $('#empty').hidden = true;
  $('#note').hidden = false;
  $('#banner').hidden = true;
  $('#app').classList.add('reading');
  setStatus('');
  reveal(path);
  recents = pushRecent(recents, path);
  store('notizen-recent', recents);
  renderPreview();
  renderTree();
  renderNoteNav();
  updateNav();
  history.replaceState(null, '', `#${encodeURIComponent(path)}`);
  record({ t: 'n', p: path });
}

// ---------- Pfad, Blättern, Pins, Verlauf ----------

function renderNoteNav() {
  const p = current.path;
  const cs = crumbs(p);
  const parts = cs.map((c, i) =>
    i === cs.length - 1
      ? `<button type="button" class="crumb-btn" data-sib="${esc(c.path)}" aria-haspopup="true" aria-expanded="false">${esc(c.label)} ▾</button>`
      : `<button type="button" class="crumb-btn" data-go="${esc(c.path)}">${esc(c.label)}</button>`,
  );
  $('#crumb').innerHTML = `${parts.map((x) => `${x}<span class="crumb-sep">›</span>`).join('')}<strong class="crumb-title">${esc(titleOf(p))}</strong><div id="sibMenu" class="sib-menu" hidden></div>`;
  const pg = pager(mdFiles(), p);
  $('#pagerInfo').textContent = `${pg.index + 1} von ${pg.total} in ${pg.folder ? pg.folder.split('/').pop() : 'Notizen'}`;
  for (const [id, target] of [['#prevBtn', pg.prev], ['#nextBtn', pg.next]]) {
    const b = $(id);
    b.disabled = !target;
    b.dataset.path = target || '';
    b.title = target ? titleOf(target) : '';
  }
  const pinned = pins.includes(p);
  $('#pinBtn').classList.toggle('on', pinned);
  $('#pinBtn').setAttribute('aria-pressed', String(pinned));
  $('#pinBtn').title = pinned ? 'Nicht mehr anpinnen' : 'Anpinnen';
}

$('#crumb').addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go) return showDash(go.dataset.go);
  const sib = e.target.closest('[data-sib]');
  const menu = $('#sibMenu');
  if (sib && menu) {
    const open = menu.hidden;
    if (open) {
      const folder = sib.dataset.sib;
      menu.innerHTML = `<div class="side-label">Ordner in ${esc(dirOf(folder) ? dirOf(folder).split('/').pop() : 'Notizen')}</div>${folderPeers(mdFiles(), folder)
        .map((f) => `<button type="button" class="sib-item${f.current ? ' on' : ''}" data-go="${esc(f.path)}"><span class="chk">${f.current ? '✓' : ''}</span><span class="grow">${esc(f.name)}</span><span class="meta">${f.count}</span></button>`)
        .join('')}`;
    }
    menu.hidden = !open;
    sib.setAttribute('aria-expanded', String(open));
  }
});

document.addEventListener('click', (e) => {
  const menu = $('#sibMenu');
  if (menu && !menu.hidden && !e.target.closest('#crumb')) menu.hidden = true;
});

for (const id of ['#prevBtn', '#nextBtn']) {
  $(id).addEventListener('click', () => {
    const p = $(id).dataset.path;
    if (p) openNote(p);
  });
}

$('#pinBtn').addEventListener('click', () => {
  if (!current) return;
  const t = togglePin(pins, current.path);
  pins = t.list;
  store('notizen-pins', pins);
  renderNoteNav();
  renderSide();
});

// Zurück/Vor wie im Browser, aber innerhalb der App (Notizen und Bereiche)
const hist = [];
let hi = -1;
let navLock = false;

function updateHist() {
  $('#backBtn').disabled = hi <= 0;
  $('#fwdBtn').disabled = hi >= hist.length - 1;
}

function record(e) {
  if (navLock) return;
  const top = hist[hi];
  if (top && top.t === e.t && top.p === e.p) return;
  hist.splice(hi + 1);
  hist.push(e);
  if (hist.length > 50) hist.shift();
  hi = hist.length - 1;
  updateHist();
}

async function stepHist(d) {
  const i = hi + d;
  if (i < 0 || i >= hist.length) return;
  const e = hist[i];
  if (e.t === 'n' && !files.has(e.p)) {
    hist.splice(i, 1); // Notiz gibt es nicht mehr
    if (d < 0) hi--;
    updateHist();
    return stepHist(d);
  }
  navLock = true;
  hi = i;
  try {
    if (e.t === 'n') await openNote(e.p);
    else await showDash(e.p);
  } finally {
    navLock = false;
    updateHist();
  }
}

$('#backBtn').addEventListener('click', () => stepHist(-1));
$('#fwdBtn').addEventListener('click', () => stepHist(1));

let previewTimer = null;
function renderPreview() {
  if (!current) return;
  const html = renderMarkdown(editor.value, { dir: dirOf(current.path) });
  $('#preview').innerHTML = html;
  loadInk();
}

// Zeichnungen brauchen das Token, deshalb per fetch laden und als blob-Adresse anzeigen
const inkCache = new Map(); // Pfad -> { rev, url }
async function inkUrl(path) {
  const info = files.get(path);
  if (!info) return null;
  const hit = inkCache.get(path);
  if (hit && hit.rev === info.rev) return hit.url;
  const r = await api('GET', `/api/file?path=${encodeURIComponent(path)}`);
  if (!r.ok) return null;
  const url = URL.createObjectURL(new Blob([await r.arrayBuffer()], { type: 'image/svg+xml' }));
  if (hit) URL.revokeObjectURL(hit.url);
  inkCache.set(path, { rev: info.rev, url });
  return url;
}

async function loadInk() {
  for (const img of $$('#preview img.ink')) {
    inkUrl(img.dataset.ink).then((u) => u && (img.src = u));
  }
  // Zeichenebene der Notiz (Stift über dem ganzen Text), nur zum Ansehen
  const name = current.path.split('/').pop().replace(/\.md$/, '');
  const overlay = `${dirOf(current.path) ? `${dirOf(current.path)}/` : ''}.ink/_page_${name}.svg`;
  const panel = $('#inkPanel');
  if (files.has(overlay)) {
    const u = await inkUrl(overlay);
    panel.hidden = !u;
    if (u) $('#inkImg').src = u;
  } else {
    panel.hidden = true;
  }
}

editor.addEventListener('input', () => {
  dirty = true;
  setStatus('…');
  clearTimeout(previewTimer);
  previewTimer = setTimeout(renderPreview, 150);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(save, 1500);
});

async function save() {
  if (!current || !dirty || saving) return;
  clearTimeout(saveTimer);
  saving = true;
  const text = editor.value;
  try {
    const r = await api('PUT', `/api/file?path=${encodeURIComponent(current.path)}&base=${current.rev}`, text);
    if (r.ok) {
      const info = await r.json();
      current.rev = info.rev;
      files.set(info.path, info);
      lastSaved = text;
      dirty = editor.value !== text;
      setStatus(dirty ? '…' : 'Gespeichert');
      if (dirty) saveTimer = setTimeout(save, 400);
    } else if (r.status === 409) {
      conflict();
    } else {
      setStatus(`Speichern fehlgeschlagen (${r.status})`);
    }
  } catch (e) {
    if (e.message !== 'unauthorized') setStatus('Speichern fehlgeschlagen');
  } finally {
    saving = false;
  }
}

/** Die Notiz wurde inzwischen woanders geändert (Tablet, Claude): nichts überschreiben, zwei Auswege anbieten. */
function conflict() {
  setStatus('Konflikt');
  const b = $('#banner');
  b.hidden = false;
  b.innerHTML = '<span class="grow">Diese Notiz wurde inzwischen woanders geändert (z. B. am Tablet). Deine Fassung ist noch nicht gespeichert.</span><button type="button" class="btn small" id="keepMine">Meine Fassung als Kopie speichern</button><button type="button" class="btn small" id="takeTheirs">Neue Fassung laden</button>';
  $('#keepMine').onclick = async () => {
    const stamp = new Date().toLocaleString('sv-SE').slice(0, 16).replace('T', ' ').replace(':', '.');
    const copy = current.path.replace(/\.md$/, ` (Konflikt ${stamp}).md`);
    const r = await api('PUT', `/api/file?path=${encodeURIComponent(copy)}&base=0`, editor.value);
    if (r.ok) files.set(copy, await r.json());
    dirty = false;
    await openNote(current.path);
    setStatus('Kopie angelegt');
  };
  $('#takeTheirs').onclick = async () => {
    dirty = false;
    await openNote(current.path);
  };
}

window.addEventListener('beforeunload', (e) => {
  if (dirty) {
    e.preventDefault();
    e.returnValue = '';
  }
});
document.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    save();
  }
});

// ---------- Ansicht, Format-Leiste ----------

function setView(v) {
  $('#panes').dataset.view = v;
  $$('.seg button').forEach((b) => b.classList.toggle('active', b.dataset.view === v));
  localStorage.setItem('notizen-view', v);
}
$('.seg').addEventListener('click', (e) => {
  const b = e.target.closest('[data-view]');
  if (b) setView(b.dataset.view);
});

const PREFIX = { h1: '# ', h2: '## ', h3: '### ', ul: '- ', ol: '1. ', task: '- [ ] ' };
const ANY_PREFIX = /^(#{1,6} |- \[[ xX]\] |- |\* |\d+\. )/;

function edit(fn) {
  fn();
  editor.dispatchEvent(new Event('input'));
  editor.focus();
}

function linePrefix(prefix) {
  edit(() => {
    const t = editor.value;
    const pos = editor.selectionStart;
    const ls = t.lastIndexOf('\n', pos - 1) + 1;
    let le = t.indexOf('\n', pos);
    if (le === -1) le = t.length;
    const old = (ANY_PREFIX.exec(t.slice(ls, le)) || [''])[0];
    const same = old === prefix;
    editor.setRangeText(same ? '' : prefix, ls, ls + old.length, 'preserve');
    const caret = Math.max(ls, pos + (same ? 0 : prefix.length) - old.length);
    editor.setSelectionRange(caret, caret);
  });
}

function wrap(mark) {
  edit(() => {
    const a = editor.selectionStart;
    const b = editor.selectionEnd;
    const t = editor.value;
    const n = mark.length;
    if (a !== b && a >= n && t.slice(a - n, a) === mark && t.slice(b, b + n) === mark) {
      editor.setRangeText('', b, b + n);
      editor.setRangeText('', a - n, a);
      editor.setSelectionRange(a - n, b - n);
    } else {
      editor.setRangeText(mark, b, b);
      editor.setRangeText(mark, a, a);
      editor.setSelectionRange(a + n, b + n);
    }
  });
}

$('#toolbar').addEventListener('click', (e) => {
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (!act) return;
  if (PREFIX[act]) linePrefix(PREFIX[act]);
  else wrap({ bold: '**', italic: '*', code: '`' }[act]);
});

editor.addEventListener('keydown', (e) => {
  if (e.ctrlKey || e.metaKey) {
    const k = e.key.toLowerCase();
    if (k === 'b') { e.preventDefault(); wrap('**'); } else if (k === 'i') { e.preventDefault(); wrap('*'); } else if (k === 'e') { e.preventDefault(); wrap('`'); } else if (['1', '2', '3'].includes(k)) { e.preventDefault(); linePrefix(PREFIX[`h${k}`]); }
    return;
  }
  // Enter in einer Aufzählung setzt sie fort, Enter auf leerem Punkt beendet sie
  if (e.key === 'Enter' && !e.shiftKey && editor.selectionStart === editor.selectionEnd) {
    const t = editor.value;
    const pos = editor.selectionStart;
    const ls = t.lastIndexOf('\n', pos - 1) + 1;
    const line = t.slice(ls, pos);
    const m = /^(\s*)(- \[[ xX]\] |- |\* |\d+\. )(.*)$/.exec(line);
    if (!m) return;
    e.preventDefault();
    if (!m[3].trim()) {
      edit(() => editor.setRangeText('', ls, pos, 'end'));
      return;
    }
    const next = m[2].startsWith('- [') ? '- [ ] ' : /^\d/.test(m[2]) ? `${parseInt(m[2], 10) + 1}. ` : m[2];
    edit(() => editor.setRangeText(`\n${m[1]}${next}`, pos, pos, 'end'));
  }
});

// ---------- Neue Notiz, Umbenennen, Löschen ----------

const clean = (s) => s.trim().replace(/\\/g, '/').replace(/\/{2,}/g, '/').replace(/^\/|\/$/g, '').split('/').map((x) => x.replace(/[:*?"<>|]/g, '-').trim()).filter((x) => x && x !== '.' && x !== '..' && !x.startsWith('.')).join('/');

$('#newBtn').addEventListener('click', async () => {
  const folder = current ? dirOf(current.path) : '';
  const input = prompt('Neue Notiz (Ordner/Name):', folder ? `${folder}/` : '');
  if (!input) return;
  let p = clean(input).replace(/\.md$/, '');
  if (!p) return;
  p += '.md';
  if (files.has(p)) return alert('Diese Notiz gibt es schon.');
  await createNote(p);
});

async function createNote(p) {
  if (files.has(p)) return openNote(p);
  const title = p.split('/').pop().replace(/\.md$/, '');
  const r = await api('PUT', `/api/file?path=${encodeURIComponent(p)}&base=0`, `# ${title}\n\n`);
  if (!r.ok) return alert(`Konnte nicht angelegt werden (${r.status})`);
  files.set(p, await r.json());
  await openNote(p);
  editor.focus();
}

$('#deleteBtn').addEventListener('click', async () => {
  if (!current) return;
  if (!confirm(`„${current.path}“ löschen?\n\nAuf dem Server bleibt eine Sicherung der letzten Fassungen.`)) return;
  const r = await api('DELETE', `/api/file?path=${encodeURIComponent(current.path)}&base=${current.rev}`);
  if (r.status === 409) return conflict();
  if (!r.ok) return alert(`Löschen fehlgeschlagen (${r.status})`);
  files.delete(current.path);
  closeNote();
  renderTree();
});

$('#renameBtn').addEventListener('click', async () => {
  if (!current) return;
  if (dirty) await save();
  const input = prompt('Neuer Pfad (Ordner/Name):', current.path.replace(/\.md$/, ''));
  if (!input) return;
  const p = `${clean(input).replace(/\.md$/, '')}.md`;
  if (p === '.md' || p === current.path) return;
  if (files.has(p)) return alert('Diese Notiz gibt es schon.');
  const put = await api('PUT', `/api/file?path=${encodeURIComponent(p)}&base=0`, editor.value);
  if (!put.ok) return alert(`Umbenennen fehlgeschlagen (${put.status})`);
  files.set(p, await put.json());
  const del = await api('DELETE', `/api/file?path=${encodeURIComponent(current.path)}&base=${current.rev}`);
  if (del.ok) files.delete(current.path);
  await openNote(p);
});

function closeNote() {
  current = null;
  dirty = false;
  showDash(area);
}

// ---------- Übersicht und Bereiche ----------

let area = ''; // Ordnerpfad des gezeigten Dashboards, '' = Übersicht
let dashOn = false;
const taskCache = new Map(); // Pfad -> { rev, list } (offene Aufgaben je Notiz)
let taskRun = 0;

function updateNav() {
  const top = dashOn ? area.split('/')[0] : current?.path.includes('/') ? current.path.split('/')[0] : null;
  for (const b of $$('#nav [data-go]')) b.classList.toggle('active', top !== null && b.dataset.go === (top || ''));
}

function drawDash() {
  for (const p of [...taskCache.keys()]) if (!files.has(p)) taskCache.delete(p);
  $('#dash').innerHTML = dashHtml({ area, files, tasks: taskCache });
  const scroller = $('.act-scroll', $('#dash'));
  if (scroller) scroller.scrollLeft = scroller.scrollWidth; // wie bei GitHub: heute steht rechts
  updateNav();
}

// Aufgaben stehen im Text: Notizen einmal holen (je Revision), vier gleichzeitig, Übersicht nach jedem Schub neu zeichnen.
async function loadTasks() {
  const run = ++taskRun;
  const todo = mdFiles().filter((p) => taskCache.get(p)?.rev !== files.get(p).rev);
  for (let i = 0; i < todo.length && run === taskRun; i += 4) {
    await Promise.all(
      todo.slice(i, i + 4).map(async (p) => {
        const rev = files.get(p)?.rev;
        try {
          const r = await api('GET', `/api/file?path=${encodeURIComponent(p)}`);
          if (r.ok) {
            const text = await r.text();
            taskCache.set(p, { rev, list: openTasks(text), text }); // der Text dient auch der Suche im Schnellwechsel
          }
        } catch {
          /* bleibt ohne Aufgaben, bis zum nächsten Abgleich */
        }
      }),
    );
    if (run === taskRun && dashOn) drawDash();
  }
}

async function showDash(a = '') {
  if (current && dirty) await save();
  current = null;
  dirty = false;
  area = a;
  dashOn = true;
  $('#note').hidden = true;
  $('#empty').hidden = true;
  $('#dash').hidden = false;
  $('#app').classList.add('reading');
  history.replaceState(null, '', a ? `#@${encodeURIComponent(a)}` : '#');
  reveal(`${a}/x`);
  drawDash();
  renderTree();
  loadTasks();
  record({ t: 'a', p: a });
}

// ---------- Schnellwechsel (Strg+K) ----------

let qsOpen = false;
let qsScope = '';
let qsItems = [];
let qsSel = 0;
const SCOPES = ['', 'Schule', 'Projekte', 'Alltag'];

function openQs() {
  if ($('#app').hidden) return;
  qsOpen = true;
  qsScope = '';
  qsSel = 0;
  $('#qs').hidden = false;
  $('#qsInput').value = '';
  renderQs();
  $('#qsInput').focus();
  loadTasks(); // holt die Texte für die Suche im Inhalt
}

function closeQs() {
  qsOpen = false;
  $('#qs').hidden = true;
}

function renderQs() {
  const raw = $('#qsInput').value;
  const q = raw.trim();
  for (const b of $$('#qsChips [data-scope]')) b.classList.toggle('on', b.dataset.scope === qsScope);
  const hits = search({ paths: mdFiles(), mtime: (p) => files.get(p).mtime, text: (p) => taskCache.get(p)?.text, query: raw, scope: qsScope });
  qsItems = hits.map((h) => ({ ...h, run: () => (h.kind === 'folder' ? showDash(h.path) : openNote(h.path)) }));
  if (q && !q.startsWith('/')) {
    const base = qsScope || (current ? dirOf(current.path) : area);
    const rel = clean(q).replace(/\.md$/, '');
    const target = rel ? `${q.includes('/') || !base ? rel : `${base}/${rel}`}.md` : '';
    if (target) qsItems.push({ kind: 'create', title: q, path: target, run: () => createNote(target) });
  }
  qsSel = Math.max(0, Math.min(qsSel, qsItems.length - 1));
  const mark = (parts, fallback) => (parts ? `${esc(parts[0])}<mark>${esc(parts[1])}</mark>${esc(parts[2])}` : esc(fallback));
  let html = '';
  let last = '';
  qsItems.forEach((it, i) => {
    const group = it.kind === 'create' ? 'Aktionen' : it.kind === 'folder' ? 'Ordner' : q ? 'Notizen' : 'Zuletzt geändert';
    if (group !== last) html += `<div class="side-label">${group}</div>`;
    last = group;
    const sel = i === qsSel ? ' sel' : '';
    if (it.kind === 'create') {
      html += `<button type="button" class="qs-row${sel}" role="option" aria-selected="${i === qsSel}" data-i="${i}"><span class="qs-ico">+</span><span class="qs-main"><span class="qs-title">Neue Notiz „${esc(it.title)}“ anlegen</span><span class="qs-path">${esc(it.path)}</span></span><kbd>Strg ↵</kbd></button>`;
    } else {
      const meta = it.kind === 'folder' ? `${it.count} Notizen` : when(it.mtime);
      const via = it.via === 'text' ? '<span class="chip">im Text</span>' : '';
      html += `<button type="button" class="qs-row${sel}" role="option" aria-selected="${i === qsSel}" data-i="${i}"><span class="qs-ico">${it.kind === 'folder' ? '▸' : '·'}</span><span class="qs-main"><span class="qs-title">${mark(it.parts, it.title)}</span><span class="qs-path">${esc(it.kind === 'folder' ? it.path : it.path)}</span></span>${via}${it.area ? `<span class="chip">${esc(it.area)}</span>` : ''}<span class="when">${esc(meta)}</span></button>`;
    }
  });
  $('#qsList').innerHTML = html || '<div class="none">Nichts gefunden.</div>';
  $('#qsList .qs-row.sel')?.scrollIntoView({ block: 'nearest' });
}

function runQs(i) {
  const it = qsItems[i];
  if (!it) return;
  closeQs();
  it.run();
}

$('#qsBtn').addEventListener('click', openQs);
$('#qs').addEventListener('click', (e) => {
  if (e.target === $('#qs')) return closeQs();
  const row = e.target.closest('[data-i]');
  if (row) return runQs(Number(row.dataset.i));
  const chip = e.target.closest('[data-scope]');
  if (chip) {
    qsScope = chip.dataset.scope;
    qsSel = 0;
    renderQs();
    $('#qsInput').focus();
  }
});
$('#qsInput').addEventListener('input', () => {
  qsSel = 0;
  renderQs();
});
$('#qsInput').addEventListener('keydown', (e) => {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    qsSel = Math.max(0, Math.min(qsItems.length - 1, qsSel + (e.key === 'ArrowDown' ? 1 : -1)));
    renderQs();
  } else if (e.key === 'Enter') {
    e.preventDefault();
    const create = qsItems.findIndex((it) => it.kind === 'create');
    runQs(e.ctrlKey || e.metaKey ? create : qsSel);
  } else if (e.key === 'Tab') {
    e.preventDefault();
    qsScope = SCOPES[(SCOPES.indexOf(qsScope) + (e.shiftKey ? SCOPES.length - 1 : 1)) % SCOPES.length];
    qsSel = 0;
    renderQs();
  }
});
document.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    if (qsOpen) closeQs();
    else openQs();
  } else if (e.key === 'Escape' && qsOpen) {
    closeQs();
  }
});

$('#dash').addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go) return showDash(go.dataset.go);
  const n = e.target.closest('[data-note]');
  if (n) return openNote(n.dataset.note);
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (act === 'new') $('#newBtn').click();
  if (act === 'tree') $('#app').classList.remove('reading');
});

$('#nav').addEventListener('click', (e) => {
  const go = e.target.closest('[data-go]');
  if (go) showDash(go.dataset.go);
});

// ---------- Live-Sync: der Server meldet jede Änderung sofort ----------

let liveCtrl = null;
let liveTimer = null;
let pullTimer = null;

function stopLive() {
  liveCtrl?.abort();
  liveCtrl = null;
  clearTimeout(liveTimer);
}

function schedulePull() {
  clearTimeout(pullTimer);
  pullTimer = setTimeout(refresh, 150);
}

async function refresh() {
  let changed;
  try {
    changed = await pull();
  } catch {
    return;
  }
  renderTree();
  if (dashOn) {
    drawDash();
    loadTasks();
  }
  if (!current) return;
  const info = files.get(current.path);
  if (!info) {
    if (!dirty) {
      closeNote();
      setStatus('');
    }
    return;
  }
  if (changed.includes(current.path) && info.rev !== current.rev) {
    if (dirty) conflict(); // beim nächsten Speichern würde es sonst überschreiben
    else await openNote(current.path); // fremde Änderung einfach übernehmen
  } else if (changed.some((p) => p.startsWith(`${dirOf(current.path) ? `${dirOf(current.path)}/` : ''}.ink/`))) {
    loadInk();
  }
}

async function live() {
  stopLive();
  const ctrl = new AbortController();
  liveCtrl = ctrl;
  try {
    const r = await fetch('/api/events', { headers: { Authorization: `Bearer ${token}` }, signal: ctrl.signal });
    if (r.status === 401) return showLogin('Token ungültig');
    if (!r.ok || !r.body) throw new Error(`HTTP ${r.status}`);
    $('#syncState').textContent = 'live';
    const reader = r.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i + 2);
        if (/^event: change/m.test(block)) schedulePull();
      }
    }
  } catch (e) {
    if (ctrl.signal.aborted) return;
  }
  if (ctrl.signal.aborted) return;
  $('#syncState').textContent = 'getrennt';
  liveTimer = setTimeout(async () => {
    await refresh(); // Verpasstes nachholen, dann neu verbinden
    live();
  }, 3000);
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && token) refresh();
});

// ---------- Start ----------

async function start() {
  $('#login').hidden = true;
  $('#app').hidden = false;
  files.clear();
  seq = 0;
  await pull();
  setView(localStorage.getItem('notizen-view') || 'both');
  renderTree();
  const hash = decodeURIComponent(location.hash.slice(1));
  if (hash && files.has(hash)) await openNote(hash);
  else await showDash(hash.startsWith('@') ? hash.slice(1) : '');
  live();
}

if (token) {
  fetch('/api/changes?since=0', { headers: { Authorization: `Bearer ${token}` } })
    .then((r) => (r.ok ? start() : showLogin(r.status === 401 ? 'Token ungültig' : '')))
    .catch(() => showLogin('Server nicht erreichbar'));
} else {
  showLogin();
}
