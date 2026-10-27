import { renderMarkdown, esc } from './md.js';

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
let query = '';

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
  const q = query.trim().toLowerCase();
  let paths = mdFiles();
  if (q) paths = paths.filter((p) => p.toLowerCase().includes(q));
  if (!paths.length) {
    box.innerHTML = `<div class="none">${q ? 'Nichts gefunden.' : 'Noch keine Notizen.'}</div>`;
    return;
  }
  const row = (cls, ico, name, meta, attrs) => `<button type="button" class="row ${cls}" ${attrs}><span class="ico">${ico}</span><span class="name">${esc(name)}</span>${meta ? `<span class="meta">${esc(meta)}</span>` : ''}</button>`;
  const walk = (node, prefix) => {
    let html = '<ul>';
    for (const [name, child] of [...node.folders].sort((a, b) => a[0].localeCompare(b[0], 'de'))) {
      const full = prefix ? `${prefix}/${name}` : name;
      const open = q || openFolders.has(full);
      html += `<li>${row('folder', open ? '▾' : '▸', name, '', `data-folder="${esc(full)}"`)}${open ? walk(child, full) : ''}</li>`;
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

$('#search').addEventListener('input', (e) => {
  query = e.target.value;
  renderTree();
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
  $('#empty').hidden = true;
  $('#note').hidden = false;
  $('#banner').hidden = true;
  $('#app').classList.add('reading');
  const parts = path.replace(/\.md$/, '').split('/');
  $('#crumb').innerHTML = parts.map((p, i) => (i === parts.length - 1 ? `<strong>${esc(p)}</strong>` : esc(p))).join(' › ');
  setStatus('');
  renderPreview();
  renderTree();
  history.replaceState(null, '', `#${encodeURIComponent(path)}`);
}

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
  const title = p.split('/').pop().replace(/\.md$/, '');
  const r = await api('PUT', `/api/file?path=${encodeURIComponent(p)}&base=0`, `# ${title}\n\n`);
  if (!r.ok) return alert(`Konnte nicht angelegt werden (${r.status})`);
  files.set(p, await r.json());
  for (let d = dirOf(p); d; d = dirOf(d)) openFolders.add(d);
  await openNote(p);
  editor.focus();
});

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
  $('#note').hidden = true;
  $('#empty').hidden = false;
  $('#app').classList.remove('reading');
  history.replaceState(null, '', '#');
}

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
  live();
}

if (token) {
  fetch('/api/changes?since=0', { headers: { Authorization: `Bearer ${token}` } })
    .then((r) => (r.ok ? start() : showLogin(r.status === 401 ? 'Token ungültig' : '')))
    .catch(() => showLogin('Server nicht erreichbar'));
} else {
  showLogin();
}
