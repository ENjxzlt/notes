package online.minihomelab.notizen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
    private val onInk: (noteDir: File, id: String) -> Unit,
) : LinearLayout(ctx) {

    var file: File? = null
        private set

    /** Wird nach jedem erfolgreichen Speichern gemeldet (löst den Sync aus). */
    var onSaved: (() -> Unit)? = null

    /** Zustand der Datei auf dem Datenträger, wie wir sie zuletzt gelesen oder geschrieben haben. */
    private var diskStamp = 0L

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
        setLineSpacing(ctx.dp(6).toFloat(), 1.0f) // Zusatzabstand statt Faktor: Zeichnungen sollen nicht mitgestreckt werden
        setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(24))
    }
    private val toolbar: View

    // Zeichnen direkt in der Notiz
    private var inkModel: InkModel? = null
    private var inkId: String? = null
    private var inkDir: File? = null
    private var inkDirty = false
    // Zeichenebene über der ganzen Notiz
    private var overlayModel: InkModel? = null
    private var overlayDir: File? = null
    private var overlayId: String? = null
    private var overlayDirty = false
    private var drawMode = ctx.getSharedPreferences("notizen-ui", Context.MODE_PRIVATE).getBoolean("draw", true)
    private val overlaySave = Runnable { saveOverlay() }
    private var lastInkTool = "pen"
    private var lastInkColor = "text"
    private val inkSave = Runnable { saveInk() }
    private val inkTools: InkTools = InkTools(
        ctx,
        onTool = { t -> lastInkTool = t; inkModel?.tool = t; overlayModel?.tool = t; syncInkTools() },
        onColor = { c -> lastInkColor = c; inkModel?.color = c; overlayModel?.color = c; syncInkTools() },
        onUndo = { undoInk() },
        onExtend = { extendInk() },
        onFull = { openInkFullscreen() },
        onDone = { if (inkId != null) deactivateInk() else setDrawMode(false) },
        isInline = true,
    ).apply { visibility = GONE }

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
        addView(inkTools, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(64)))
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
        edit.onInkTap = { id -> activateInk(id) }
        edit.onInkTapOutside = { deactivateInk() }
        edit.inkProvider = { id -> activateInk(id) }
        edit.onInkEdited = { markInkDirty() }
        edit.onOverlayEdited = { markOverlayDirty() }

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
        for ((key, set) in toolStates) if (key != "ink") set(key in active)
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
        divider()
        iconButton("ink", R.drawable.ic_pen, "Mit dem Stift zeichnen an/aus") { setDrawMode(!drawMode) }

        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** Nach dem Bearbeiten im Vollbild: Vorschau neu laden (Höhe kann sich geändert haben). */
    fun refreshInk(id: String) {
        Ink.invalidate(id)
        relayoutInk()
    }

    private fun relayoutInk() {
        if (file == null) return
        busy = true
        try {
            applyStyle(edit.text)
        } finally {
            busy = false
        }
        edit.requestLayout()
        edit.invalidate()
    }

    /** Zeichnung aus der Notiz entfernen (Zeile und Dateien). */
    fun removeInk(dir: File, id: String) {
        if (inkId == id) {
            inkDirty = false
            handler.removeCallbacks(inkSave)
            clearActiveInk()
        }
        val e = edit.text
        val marker = "![Zeichnung](.ink/$id.svg)"
        val i = e.toString().indexOf(marker)
        if (i >= 0) {
            val end = minOf(e.length, i + marker.length + if (e.length > i + marker.length && e[i + marker.length] == '\n') 1 else 0)
            e.delete(i, end)
        }
        Ink.delete(dir, id)
    }

    // ---- Zeichnen direkt in der Notiz ----

    /** Macht einen Block zum aktiven Zeichenblock (lädt ihn, zeigt die Werkzeuge) und gibt sein Modell zurück. */
    private fun activateInk(id: String): InkModel? {
        val dir = file?.parentFile ?: return null
        if (inkId == id && inkModel != null) return inkModel
        deactivateInk()
        val m = InkModel(Ink.load(dir, id))
        m.tool = lastInkTool
        m.color = lastInkColor
        inkModel = m
        inkId = id
        inkDir = dir
        edit.inkModel = m
        edit.inkActiveId = id
        updateInkTools()
        edit.invalidate()
        return m
    }

    fun deactivateInk() {
        val id = inkId ?: return
        handler.removeCallbacks(inkSave)
        saveInk()
        clearActiveInk()
        Ink.invalidate(id)
        edit.invalidate()
    }

    private fun clearActiveInk() {
        inkModel = null
        inkId = null
        inkDir = null
        edit.inkModel = null
        edit.inkActiveId = null
        updateInkTools()
    }

    private fun syncInkTools() {
        inkTools.sync(lastInkTool, lastInkColor)
    }

    /** Leiste zeigen, solange der Stift zeichnen darf oder ein Block aktiv ist. */
    private fun updateInkTools() {
        inkTools.visibility = if (file != null && (drawMode || inkId != null)) VISIBLE else GONE
        inkTools.showExtend(inkId != null)
        syncInkTools()
        toolStates["ink"]?.invoke(drawMode)
        edit.drawMode = drawMode
    }

    private fun setDrawMode(on: Boolean) {
        drawMode = on
        context.getSharedPreferences("notizen-ui", Context.MODE_PRIVATE).edit().putBoolean("draw", on).apply()
        updateInkTools()
    }

    private fun undoInk() {
        val block = inkModel
        if (block != null) {
            if (block.undo()) markInkDirty()
        } else if (overlayModel?.undo() == true) {
            markOverlayDirty()
        }
        edit.invalidate()
    }

    // ---- Zeichenebene (Stift überall in der Notiz) ----

    private fun loadOverlay(f: File) {
        val dir = f.parentFile
        overlayDir = dir
        overlayId = Ink.overlayId(f.nameWithoutExtension)
        val m = InkModel(if (dir != null) Ink.load(dir, overlayId!!) else InkDoc())
        m.tool = lastInkTool
        m.color = lastInkColor
        overlayModel = m
        edit.overlayModel = m
    }

    private fun markOverlayDirty() {
        overlayDirty = true
        setStatus("…", false)
        handler.removeCallbacks(overlaySave)
        handler.postDelayed(overlaySave, 700)
    }

    private fun saveOverlay() {
        val m = overlayModel ?: return
        val d = overlayDir ?: return
        val id = overlayId ?: return
        if (!overlayDirty) return
        try {
            var maxY = 0f
            for (st in m.doc.strokes) {
                st.bounds()
                if (st.y1 > maxY) maxY = st.y1
            }
            m.doc.height = maxOf(200, maxY.toInt() + 100)
            if (m.doc.strokes.isEmpty()) Ink.delete(d, id) else Ink.save(d, id, m.doc)
            overlayDirty = false
            setStatus("Gespeichert", true)
            onSaved?.invoke()
        } catch (e: Exception) {
            setStatus("Zeichnung nicht gespeichert", false)
        }
    }


    private fun markInkDirty() {
        inkDirty = true
        setStatus("…", false)
        handler.removeCallbacks(inkSave)
        handler.postDelayed(inkSave, 700)
    }

    private fun saveInk() {
        val m = inkModel ?: return
        val d = inkDir ?: return
        val id = inkId ?: return
        if (!inkDirty) return
        try {
            Ink.save(d, id, m.doc)
            inkDirty = false
            setStatus("Gespeichert", true)
            onSaved?.invoke()
        } catch (e: Exception) {
            setStatus("Zeichnung nicht gespeichert", false)
        }
    }

    private fun extendInk() {
        val m = inkModel ?: return
        val d = inkDir ?: return
        val id = inkId ?: return
        m.doc.height = minOf(Ink.MAX_H, m.doc.height + 400)
        inkDirty = true
        saveInk()
        Ink.invalidate(id)
        relayoutInk()
    }

    private fun openInkFullscreen() {
        val id = inkId ?: return
        val dir = inkDir ?: return
        deactivateInk()
        onInk(dir, id)
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
        deactivateInk()
        file = f
        MarkdownStyler.noteDir = f.parentFile
        loadOverlay(f)
        loading = true
        edit.setText(store.read(f))
        diskStamp = stampOf(f)
        loading = false
        dirty = false
        edit.setSelection(edit.text.length)
        title.text = crumbs(f)
        setStatus("", false)
        edit.visibility = VISIBLE
        toolbar.visibility = VISIBLE
        hint.visibility = GONE
        updateInkTools()
        edit.requestFocus()
    }

    fun close() {
        handler.removeCallbacks(saveRunnable)
        flush()
        deactivateInk()
        file = null
        showEmpty()
    }

    /** Schließt den Editor, wenn die geöffnete Notiz zu [f] gehört (umbenannt/gelöscht). */
    fun closeIfInside(f: File) {
        val cur = file ?: return
        if (cur.path == f.path || cur.path.startsWith(f.path + File.separator)) close()
    }

    private fun showEmpty() {
        overlayModel = null
        edit.overlayModel = null
        updateInkTools()
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

    private fun stampOf(f: File): Long = f.lastModified() * 31 + f.length()

    fun flush() {
        saveInk()
        saveOverlay()
        val f = file ?: return
        handler.removeCallbacks(saveRunnable)
        if (!dirty) return
        try {
            // Hat inzwischen jemand anderes die Datei geändert (Sync, anderes Programm)? Dann nicht überschreiben:
            // die eigene Fassung wandert in eine Konfliktkopie, die fremde bleibt am Originalnamen.
            if (f.exists() && stampOf(f) != diskStamp && store.read(f) != edit.text.toString()) {
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.GERMANY).format(java.util.Date())
                var copy = File(f.parentFile, "${f.nameWithoutExtension} (Konflikt $ts).md")
                var n = 1
                while (copy.exists()) copy = File(f.parentFile, "${f.nameWithoutExtension} (Konflikt $ts $n++).md")
                store.write(copy, edit.text.toString())
                reloadFromDisk(f)
                setStatus("Konflikt: Kopie angelegt", false)
                onSaved?.invoke()
                return
            }
            store.write(f, edit.text.toString())
            diskStamp = stampOf(f)
            dirty = false
            setStatus("Gespeichert", true)
            onSaved?.invoke()
        } catch (e: Exception) {
            setStatus("Speichern fehlgeschlagen", false)
        }
    }

    private fun reloadFromDisk(f: File) {
        val sel = edit.selectionStart
        loading = true
        edit.setText(store.read(f))
        loading = false
        diskStamp = stampOf(f)
        dirty = false
        edit.setSelection(sel.coerceIn(0, edit.text.length))
    }

    /** Nach einem Sync: geänderte Notiz (und Zeichenebene) neu laden, sofern hier nichts Ungespeichertes liegt. */
    fun reloadIfChanged() {
        val f = file ?: return
        if (dirty) return
        if (!f.exists()) {
            close()
            return
        }
        if (stampOf(f) != diskStamp) reloadFromDisk(f)
        if (!overlayDirty && inkId == null) loadOverlay(f)
        relayoutInk()
    }
}
