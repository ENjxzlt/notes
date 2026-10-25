package online.minihomelab.notizen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
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

    private val title = TextView(ctx).apply {
        textSize = 14f
        setTextColor(Ui.MUTED)
        maxLines = 1
    }
    private val status = TextView(ctx).apply {
        textSize = 12f
        setTextColor(Ui.MUTED)
    }
    private val hint = TextView(ctx).apply {
        text = "Notiz links wählen oder neu anlegen."
        setTextColor(Ui.MUTED)
        textSize = 15f
        gravity = Gravity.CENTER
    }
    private val edit = NoteEdit(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP or Gravity.START
        background = null
        textSize = 17f
        setTextColor(Ui.TEXT)
        setLineSpacing(0f, 1.25f)
        setPadding(ctx.dp(28), ctx.dp(20), ctx.dp(28), ctx.dp(20))
    }
    private val toolbar: View

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFFFFFFF.toInt())

        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), 0, ctx.dp(16), 0)
            if (showBack) addView(flatButton(ctx, "‹ Notizen") { onBack() })
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (!showBack) leftMargin = ctx.dp(8)
            })
            addView(status)
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(48)))
        addView(hairline(ctx, false))
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hairline(ctx, false))
        toolbar = buildToolbar(ctx)
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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

    private fun buildToolbar(ctx: Context): View {
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        fun b(label: String, action: () -> Unit) = row.addView(flatButton(ctx, label, action))
        b("H1") { line("# ") }
        b("H2") { line("## ") }
        b("H3") { line("### ") }
        b("Liste") { line("- ") }
        b("Nummer") { line("1. ") }
        b("Aufgabe") { line("- [ ] ") }
        b("Fett") { wrapSel("**") }
        b("Kursiv") { wrapSel("*") }
        b("Code") { wrapSel("`") }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
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

    fun open(f: File) {
        flush()
        file = f
        loading = true
        edit.setText(store.read(f))
        loading = false
        dirty = false
        edit.setSelection(edit.text.length)
        title.text = store.rel(f)
        status.text = ""
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
        status.text = ""
        edit.visibility = GONE
        toolbar.visibility = GONE
        hint.visibility = VISIBLE
    }

    private fun scheduleSave() {
        dirty = true
        status.text = "…"
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
            status.text = "Gespeichert"
        } catch (e: Exception) {
            status.text = "Speichern fehlgeschlagen"
        }
    }
}

/** EditText, das Cursorbewegungen meldet und Antippen von Aufgaben-Kästchen umschaltet. */
private class NoteEdit(ctx: Context) : EditText(ctx) {
    var onSelection: (() -> Unit)? = null
    private var pendingBox: MdCheckbox? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke()
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
