package online.minihomelab.notizen

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Markdown-Editor: Formatierung per Schnellleiste oder Strg+1/2/3, Strg+B/I, Strg+E.
 * Man muss nie Markdown-Zeichen von Hand tippen, es steht aber trotzdem Markdown in der Datei.
 */
class EditorPane(
    ctx: Context,
    private val store: NoteStore,
    private val showBack: Boolean,
    private val onBack: () -> Unit,
) : LinearLayout(ctx) {

    var file: File? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { flush() }
    private var dirty = false
    private var loading = false
    private var busy = false
    private var enterAt = -1
    private var styledStart = -1
    private var styledEnd = -1

    private val title = Ui.label(ctx, "", 13f, Ui.MUTED).apply {
        maxLines = 1
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.START
    }
    private val statusIcon = Ui.icon(ctx, R.drawable.ic_check, Ui.FAINT, 14).apply { visibility = INVISIBLE }
    private val status = Ui.label(ctx, "", 12f, Ui.FAINT, Ui.mono)
    private val hint = Ui.label(ctx, "Notiz links wählen oder neu anlegen.", 15f, Ui.MUTED).apply {
        gravity = Gravity.CENTER
    }
    private val edit = NoteEdit(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP or Gravity.START
        background = null
        typeface = Ui.sans
        textSize = 17f
        setTextColor(Ui.TEXT)
        setLineSpacing(0f, 1.3f)
        setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(24))
    }
    private val toolbar: View

    /** Zustandsanzeige der Schnellleiste: Schlüssel wie bei MarkdownStyler.activeFormats. */
    private val toolStates = HashMap<String, (Boolean) -> Unit>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)

        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (showBack) ctx.dp(4) else ctx.dp(28), 0, ctx.dp(28), 0)
            if (showBack) {
                addView(LinearLayout(ctx).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(8), 0, ctx.dp(12), 0)
                    background = Ui.ripple(ctx, null, 8)
                    isClickable = true
                    addView(Ui.icon(ctx, R.drawable.ic_chevron_left, Ui.ACCENT))
                    addView(Ui.label(ctx, "Notizen", 15f, Ui.ACCENT, Ui.sansMedium))
                    setOnClickListener { onBack() }
                }, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(44)))
            }
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (showBack) leftMargin = ctx.dp(8) })
            addView(statusIcon, LayoutParams(ctx.dp(14), ctx.dp(14)).apply { rightMargin = ctx.dp(6) })
            addView(status)
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(52)))
        addView(hairline(ctx, false))
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hairline(ctx, false))
        toolbar = buildToolbar(ctx)
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(64)))

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!busy && !loading && before == 0 && count == 1 && s != null && s[start] == '\n') enterAt = start
            }

            override fun afterTextChanged(e: Editable) {
                if (busy) return
                busy = true
                try {
                    if (enterAt >= 0) {
                        val at = enterAt
                        enterAt = -1
                        val cursor = MarkdownStyler.continueList(e, at)
                        if (cursor >= 0) edit.setSelection(cursor)
                    }
                    applyStyle(e)
                } finally {
                    busy = false
                }
                if (!loading) scheduleSave()
            }
        })

        // Cursor bewegt: Markdown-Zeichen im aktuellen Wort/Zeile ein-, anderswo ausblenden (verzögert, nie mitten im Tippen)
        edit.onSelection = { edit.post { restyle() } }

        edit.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN || !ev.isCtrlPressed) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_1 -> { line("# "); true }
                KeyEvent.KEYCODE_2 -> { line("## "); true }
                KeyEvent.KEYCODE_3 -> { line("### "); true }
                KeyEvent.KEYCODE_B -> { wrapSel("**"); true }
                KeyEvent.KEYCODE_I -> { wrapSel("*"); true }
                KeyEvent.KEYCODE_E -> { wrapSel("`"); true }
                else -> false
            }
        }
        showEmpty()
    }

    private fun applyStyle(e: Editable) {
        styledStart = maxOf(0, edit.selectionStart)
        styledEnd = maxOf(0, edit.selectionEnd)
        MarkdownStyler.apply(e, styledStart, styledEnd)
        updateToolbar(e)
    }

    private fun restyle() {
        if (busy || loading || file == null) return
        if (maxOf(0, edit.selectionStart) == styledStart && maxOf(0, edit.selectionEnd) == styledEnd) return
        busy = true
        try {
            applyStyle(edit.text)
        } finally {
            busy = false
        }
    }

    private fun updateToolbar(e: Editable) {
        val active = MarkdownStyler.activeFormats(e.toString(), maxOf(0, edit.selectionStart))
        for ((key, set) in toolStates) set(key in active)
    }

    // ---- Schnellleiste ----

    private fun buildToolbar(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        }

        fun setActive(v: View, on: Boolean) {
            val bg: Drawable? = if (on) Ui.round(ctx, Ui.ACCENT_SOFT, 8) else null
            v.background = Ui.ripple(ctx, bg, 8)
        }

        fun textButton(key: String, label: String, desc: String, mono: Boolean, style: Int, action: () -> Unit) {
            val tv = Ui.label(ctx, label, if (mono) 14f else 17f, Ui.TEXT2, if (mono) Ui.monoMedium else Ui.sans).apply {
                gravity = Gravity.CENTER
                contentDescription = desc
                isClickable = true
                setOnClickListener { action() }
                if (style == 1) typeface = Ui.sansSemi
                if (style == 2) typeface = Ui.sansItalic
            }
            setActive(tv, false)
            toolStates[key] = { on ->
                setActive(tv, on)
                tv.setTextColor(if (on) Ui.ACCENT else Ui.TEXT2)
            }
            row.addView(tv, LayoutParams(ctx.dp(44), ctx.dp(44)))
        }

        fun iconButton(key: String, res: Int, desc: String, action: () -> Unit) {
            val iv = ImageView(ctx).apply {
                setImageResource(res)
                setColorFilter(Ui.TEXT2)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = desc
                isClickable = true
                setOnClickListener { action() }
            }
            setActive(iv, false)
            toolStates[key] = { on ->
                setActive(iv, on)
                iv.setColorFilter(if (on) Ui.ACCENT else Ui.TEXT2)
            }
            row.addView(iv, LayoutParams(ctx.dp(44), ctx.dp(44)))
        }

        fun divider() {
            row.addView(View(ctx).apply { setBackgroundColor(Ui.BORDER) }, LayoutParams(1, ctx.dp(24)).apply {
                leftMargin = ctx.dp(8)
                rightMargin = ctx.dp(8)
            })
        }

        textButton("h1", "H1", "Überschrift 1", true, 0) { line("# ") }
        textButton("h2", "H2", "Überschrift 2", true, 0) { line("## ") }
        textButton("h3", "H3", "Überschrift 3", true, 0) { line("### ") }
        divider()
        iconButton("list", R.drawable.ic_list, "Liste") { line("- ") }
        textButton("num", "1.", "Nummerierte Liste", true, 0) { line("1. ") }
        iconButton("task", R.drawable.ic_task, "Aufgabe") { line("- [ ] ") }
        divider()
        textButton("bold", "B", "Fett", false, 1) { wrapSel("**") }
        textButton("italic", "I", "Kursiv", false, 2) { wrapSel("*") }
        iconButton("code", R.drawable.ic_code, "Code") { wrapSel("`") }

        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun line(prefix: String) {
        if (file == null) return
        val cursor = MarkdownStyler.setLinePrefix(edit.text, edit.selectionStart, prefix)
        edit.setSelection(cursor)
    }

    private fun wrapSel(mk: String) {
        if (file == null) return
        val (s, t) = MarkdownStyler.wrap(edit.text, edit.selectionStart, edit.selectionEnd, mk)
        edit.setSelection(s, t)
    }

    // ---- Notiz öffnen/schließen/speichern ----

    private fun crumbs(f: File): CharSequence {
        val parts = store.rel(f).split(" › ")
        val sb = SpannableStringBuilder()
        parts.forEachIndexed { i, p ->
            if (i > 0) {
                val s = sb.length
                sb.append("  ›  ")
                sb.setSpan(ForegroundColorSpan(Ui.FAINT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val s = sb.length
            sb.append(p)
            if (i == parts.lastIndex) {
                sb.setSpan(ForegroundColorSpan(Ui.TEXT), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(FontSpan(Ui.sansMedium), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun setStatus(text: String, showCheck: Boolean) {
        status.text = text
        statusIcon.visibility = if (showCheck) VISIBLE else INVISIBLE
    }

    fun open(f: File) {
        flush()
        file = f
        loading = true
        edit.setText(store.read(f))
        loading = false
        dirty = false
        edit.setSelection(edit.text.length)
        title.text = crumbs(f)
        setStatus("", false)
        edit.visibility = VISIBLE
        toolbar.visibility = VISIBLE
        hint.visibility = GONE
        edit.requestFocus()
    }

    fun close() {
        handler.removeCallbacks(saveRunnable)
        flush()
        file = null
        showEmpty()
    }

    /** Schließt den Editor, wenn die geöffnete Notiz zu [f] gehört (umbenannt/gelöscht). */
    fun closeIfInside(f: File) {
        val cur = file ?: return
        if (cur.path == f.path || cur.path.startsWith(f.path + File.separator)) close()
    }

    private fun showEmpty() {
        title.text = ""
        setStatus("", false)
        edit.visibility = GONE
        toolbar.visibility = GONE
        hint.visibility = VISIBLE
    }

    private fun scheduleSave() {
        dirty = true
        setStatus("…", false)
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 700)
    }

    fun flush() {
        val f = file ?: return
        handler.removeCallbacks(saveRunnable)
        if (!dirty) return
        try {
            store.write(f, edit.text.toString())
            dirty = false
            setStatus("Gespeichert", true)
        } catch (e: Exception) {
            setStatus("Speichern fehlgeschlagen", false)
        }
    }
}

/** EditText, das Cursorbewegungen meldet, Antippen von Aufgaben-Kästchen umschaltet und den Text mittig hält. */
private class NoteEdit(ctx: Context) : EditText(ctx) {
    var onSelection: (() -> Unit)? = null
    private var pendingBox: MdCheckbox? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke()
    }

    /** Auf breiten Bildschirmen eine gut lesbare Spalte (max. 700 dp) in der Mitte. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = maxOf(context.dp(28), (w - context.dp(700)) / 2)
        if (paddingLeft != side) setPadding(side, paddingTop, side, paddingBottom)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingBox = boxAt(ev.x, ev.y)
                if (pendingBox != null) return true
            }
            MotionEvent.ACTION_UP -> {
                val box = pendingBox
                pendingBox = null
                if (box != null) {
                    if (boxAt(ev.x, ev.y) === box) toggle(box)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> pendingBox = null
            else -> if (pendingBox != null) return true
        }
        return super.onTouchEvent(ev)
    }

    private fun boxAt(x: Float, y: Float): MdCheckbox? {
        val l = layout ?: return null
        val ed = text ?: return null
        val line = l.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        val from = l.getLineStart(line)
        val to = l.getLineEnd(line)
        val slop = context.dp(12)
        for (sp in ed.getSpans(from, to, MdCheckbox::class.java)) {
            val x0 = l.getPrimaryHorizontal(ed.getSpanStart(sp)) + totalPaddingLeft - scrollX
            val x1 = l.getPrimaryHorizontal(ed.getSpanEnd(sp)) + totalPaddingLeft - scrollX
            if (x >= x0 - slop && x <= x1 + slop) return sp
        }
        return null
    }

    /** "- [ ] " ⇄ "- [x] ": nur das Zeichen in der Klammer tauschen. */
    private fun toggle(box: MdCheckbox) {
        val ed = text ?: return
        val s = ed.getSpanStart(box)
        if (s < 0) return
        ed.replace(s + 3, s + 4, if (box.checked) " " else "x")
    }
}
