// Ideen-Dump und Aufgaben (gleiches Format wie Notizen-App und Linux-Client). Reine Funktionen ohne DOM.
//
// Inbox: eine Notiz `Inbox.md`, je Idee eine Zeile `- 2026-10-29 12:31 · Text`.
// Aufgaben: `- [ ] Titel (~45 min · bis 30.10.)`, Dauer und Frist sind optional.

export const INBOX_FILE = 'Inbox.md';

const pad = (n) => String(n).padStart(2, '0');
export const stamp = (d = new Date()) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;

export const formatIdea = (st, text) => `- ${st} · ${text.trim().replace(/\s*\n\s*/g, ' / ')}`;

const IDEA = /^- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) · (.+)$/;
export function parseIdeas(content) {
  const out = [];
  for (const line of String(content).split('\n')) {
    const m = IDEA.exec(line.trimEnd());
    if (m) out.push({ raw: line.trimEnd(), stamp: m[1], text: m[2] });
  }
  return out;
}

/** Hängt [line] an; steht im Text nur eine Überschrift, bleibt eine Leerzeile dazwischen. */
function attach(content, line) {
  const body = content.replace(/\n+$/, '');
  return `${body}${body.includes('\n') ? '\n' : '\n\n'}${line}\n`;
}

/** Hängt eine Idee an; bei leerem Text entsteht die Überschrift. Leere Ideen werden ignoriert. */
export function appendIdea(content, idea, st) {
  if (!idea.trim()) return content;
  return attach(content.trim() ? content : '# Inbox\n\n', formatIdea(st, idea));
}

export function removeIdea(content, raw) {
  const lines = content.split('\n');
  const i = lines.findIndex((l) => l.trimEnd() === raw);
  if (i >= 0) lines.splice(i, 1);
  return lines.join('\n');
}

// ---- Fristen ----

const WEEKDAYS = { montag: 1, mo: 1, dienstag: 2, di: 2, mittwoch: 3, mi: 3, donnerstag: 4, do: 4, freitag: 5, fr: 5, samstag: 6, sa: 6, sonntag: 7, so: 7 };
const dm = (d) => `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.`;
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);

/** Frist aus Eingabe wie „Freitag“, „morgen“ oder „3.11.“ als „dd.mm.“ (nächster passender Tag); null, wenn unklar. */
export function parseDue(input, today = new Date()) {
  const s = input.trim().toLowerCase().replace(/^bis /, '').trim();
  if (!s) return null;
  if (s === 'heute') return dm(today);
  if (s === 'morgen') return dm(addDays(today, 1));
  if (s === 'übermorgen') return dm(addDays(today, 2));
  if (WEEKDAYS[s]) {
    const dow = today.getDay() === 0 ? 7 : today.getDay();
    return dm(addDays(today, (WEEKDAYS[s] - dow + 7) % 7));
  }
  const m = /^(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?$/.exec(s);
  if (!m) return null;
  const d = new Date(today.getFullYear(), Number(m[2]) - 1, Number(m[1]));
  return d.getMonth() === Number(m[2]) - 1 && d.getDate() === Number(m[1]) ? dm(d) : null;
}

// ---- Aufgaben ----

const TASK = /^(\s*[-*] )\[ \] (.+)$/;
const META = /^(.*?)\s*\(~(\d+) min(?: · bis (\d{2}\.\d{2}\.))?\)\s*$/;

export function taskLine(title, minutes, due) {
  const meta = minutes != null && due ? ` (~${minutes} min · bis ${due})` : minutes != null ? ` (~${minutes} min)` : '';
  return `- [ ] ${title.trim()}${meta}`;
}

/** Offene Aufgaben einer Notiz mit Zeilennummer (Codeblöcke ausgenommen). */
export function parseTasks(text) {
  const out = [];
  let fence = false;
  String(text).split('\n').forEach((line, i) => {
    if (/^\s*```/.test(line)) {
      fence = !fence;
      return;
    }
    if (fence) return;
    const m = TASK.exec(line);
    if (!m) return;
    const body = m[2].trim();
    const meta = META.exec(body);
    out.push(meta ? { line: i, title: meta[1].trim(), minutes: Number(meta[2]), due: meta[3] || null } : { line: i, title: body, minutes: null, due: null });
  });
  return out;
}

/** Hakt die Aufgabe in Zeile [line] ab; null, wenn die Zeile keine offene Aufgabe (mehr) ist. */
export function checkTask(text, line, expectTitle) {
  const lines = text.split('\n');
  const m = TASK.exec(lines[line] ?? '');
  if (!m || !m[2].startsWith(expectTitle)) return null;
  lines[line] = lines[line].replace('[ ]', '[x]');
  return lines.join('\n');
}

/** Hängt eine Zeile ans Ende der Notiz (legt sie mit Überschrift an, wenn sie leer ist). */
export const appendLine = (content, title, line) => attach(content.trim() ? content : `# ${title}\n\n`, line);

/** Titel für eine Notiz aus einer Idee: die ersten Wörter, ohne verbotene Zeichen. */
export function titleFromIdea(text) {
  return text.slice(0, 48).replace(/[\\/:*?"<>|]/g, '-').trim().replace(/[.,\s]+$/, '').replace(/^\.+/, '') || 'Idee';
}
