// Markdown → HTML für die Vorschau. Absichtlich klein und sicher: aller Text wird maskiert, es gibt nur bekannte Tags,
// Links nur mit http(s)/mailto, keine fremden Bilder (Datenschutz), Zeichnungen aus `.ink/` werden später mit Token nachgeladen.

export const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');

const safeUrl = (u) => (/^(https?:\/\/|mailto:)/i.test(u) ? u : null);
const join = (dir, p) => (dir ? `${dir}/${p}` : p);

function image(alt, url, ctx) {
  if (/^\.ink\/[A-Za-z0-9_-]+\.svg$/.test(url)) {
    return `<img class="ink" alt="${esc(alt || 'Zeichnung')}" data-ink="${esc(join(ctx.dir, url))}">`;
  }
  const u = safeUrl(url);
  return u ? `<a href="${esc(u)}" target="_blank" rel="noopener noreferrer">${esc(alt || u)}</a>` : esc(alt || '');
}

function fmt(text, ctx) {
  const stash = [];
  const keep = (html) => {
    stash.push(html);
    return `\u0000${stash.length - 1}\u0000`;
  };
  let t = text.replace(/\u0000/g, '');
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, url) => keep(image(alt, url, ctx)));
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, url) => {
    const u = safeUrl(url);
    return keep(u ? `<a href="${esc(u)}" target="_blank" rel="noopener noreferrer">${esc(label)}</a>` : esc(label));
  });
  t = esc(t)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/(?<![*\w])\*(?![*\s])(.+?)(?<![*\s])\*(?![*\w])/g, '<em>$1</em>');
  return t.replace(/\u0000(\d+)\u0000/g, (_, i) => stash[Number(i)]);
}

export function inline(text, ctx) {
  let out = '';
  let last = 0;
  const re = /`([^`]+)`/g;
  let m;
  while ((m = re.exec(text))) {
    out += `${fmt(text.slice(last, m.index), ctx)}<code>${esc(m[1])}</code>`;
    last = re.lastIndex;
  }
  return out + fmt(text.slice(last), ctx);
}

/** ctx.dir: Ordner der Notiz (für relative Pfade zu Zeichnungen). */
export function renderMarkdown(src, ctx = { dir: '' }) {
  const lines = String(src).replace(/\r\n?/g, '\n').split('\n');
  const out = [];
  let para = [];
  let list = null;
  const flush = () => {
    if (para.length) out.push(`<p>${inline(para.join(' '), ctx)}</p>`);
    para = [];
  };
  const closeList = () => {
    if (list) out.push(`<${list.type}>${list.items.join('')}</${list.type}>`);
    list = null;
  };
  const addItem = (type, html) => {
    flush();
    if (!list || list.type !== type) {
      closeList();
      list = { type, items: [] };
    }
    list.items.push(html);
  };

  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    let m;
    if (/^```/.test(line)) {
      flush();
      closeList();
      const buf = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i])) buf.push(lines[i++]);
      i++;
      out.push(`<pre><code>${esc(buf.join('\n'))}</code></pre>`);
      continue;
    }
    if ((m = /^(#{1,6}) +(.*)$/.exec(line))) {
      flush();
      closeList();
      out.push(`<h${m[1].length}>${inline(m[2], ctx)}</h${m[1].length}>`);
    } else if (/^ {0,3}(-{3,}|\*{3,})\s*$/.test(line)) {
      flush();
      closeList();
      out.push('<hr>');
    } else if ((m = /^\s*[-*] \[([ xX])\] (.*)$/.exec(line))) {
      const done = m[1] !== ' ';
      addItem('ul', `<li class="task${done ? ' done' : ''}"><input type="checkbox" disabled${done ? ' checked' : ''}> ${inline(m[2], ctx)}</li>`);
    } else if ((m = /^\s*[-*] (.*)$/.exec(line))) {
      addItem('ul', `<li>${inline(m[1], ctx)}</li>`);
    } else if ((m = /^\s*\d+\. (.*)$/.exec(line))) {
      addItem('ol', `<li>${inline(m[1], ctx)}</li>`);
    } else if (/^> ?/.test(line)) {
      flush();
      closeList();
      const buf = [];
      while (i < lines.length && /^> ?/.test(lines[i])) buf.push(lines[i++].replace(/^> ?/, ''));
      out.push(`<blockquote><p>${inline(buf.join(' '), ctx)}</p></blockquote>`);
      continue;
    } else if (line.trim() === '') {
      flush();
      closeList();
    } else {
      closeList();
      para.push(line.trim());
    }
    i++;
  }
  flush();
  closeList();
  return out.join('\n');
}
