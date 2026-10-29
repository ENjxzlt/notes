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

    /** Platz rechts im Kopf für Blättern und Anpinnen (füllt die Activity im breiten Layout). */
    val tools = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Tippen auf den Pfad im Kopf (zeigt die Geschwister-Ordner). */
    var onTitleTap: (() -> Unit)? = null
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
            addView(tools, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(12) })
            title.isClickable = true
            title.setOnClickListener { onTitleTap?.invoke() }
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
                while (copy.exists()) {
                    copy = File(f.parentFile, "${f.nameWithoutExtension} (Konflikt $ts $n).md")
                    n++
                }
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

/**
 * EditText, das Cursorbewegungen meldet, Aufgaben-Kästchen umschaltet, den Text mittig hält und den Stift direkt
 * auf Zeichenblöcken zeichnen lässt. Ein aktiver Block wird über dem Text live aus dem Modell gezeichnet.
 */
private class NoteEdit(ctx: Context) : EditText(ctx) {
    var onSelection: (() -> Unit)? = null
    var onInkTap: ((String) -> Unit)? = null
    var onInkTapOutside: (() -> Unit)? = null
    var inkProvider: ((String) -> InkModel?)? = null
    var onInkEdited: (() -> Unit)? = null
    var onOverlayEdited: (() -> Unit)? = null
    var inkModel: InkModel? = null
    var inkActiveId: String? = null
    var overlayModel: InkModel? = null
    var drawMode = true

    private var pendingBox: MdCheckbox? = null
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    // Stiftzeichnen: auf einem Block oder auf der Ebene über der ganzen Notiz
    private var penPointer = -1
    private var penOnOverlay = false

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke()
    }

    /** Auf breiten Bildschirmen eine gut lesbare Spalte (max. 700 dp) in der Mitte. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = maxOf(context.dp(28), (w - context.dp(700)) / 2)
        if (paddingLeft != side) setPadding(side, paddingTop, side, paddingBottom)
        MdInk.blockWidth = w - 2 * side
    }

    private fun blockScale() = MdInk.blockWidth.toFloat() / Ink.W

    /** Oberkante des aktiven Blocks im Inhalt (ohne Scroll), null wenn nicht vorhanden. */
    private fun activeBlockTop(): Float? {
        val id = inkActiveId ?: return null
        val l = layout ?: return null
        val ed = text ?: return null
        val sp = ed.getSpans(0, ed.length, MdInk::class.java).firstOrNull { it.id == id } ?: return null
        val start = ed.getSpanStart(sp)
        if (start < 0) return null
        return (l.getLineTop(l.getLineForOffset(start)) + totalPaddingTop).toFloat()
    }

    /** Berührungspunkt in Seiteneinheiten des aktiven Blocks (an den Rand geklemmt). */
    private fun toPage(x: Float, y: Float): FloatArray? {
        val top = activeBlockTop() ?: return null
        val m = inkModel ?: return null
        val sc = blockScale()
        val px = (x + scrollX - paddingLeft) / sc
        val py = (y + scrollY - top) / sc
        return floatArrayOf(px.coerceIn(0f, Ink.W.toFloat()), py.coerceIn(0f, m.doc.height.toFloat()))
    }

    /** Berührungspunkt in Seiteneinheiten der Ebene über der Notiz (Spalte = 1000 Einheiten, nach unten unbegrenzt). */
    private fun toOverlay(x: Float, y: Float): FloatArray {
        val sc = blockScale()
        return floatArrayOf((x + scrollX - paddingLeft) / sc, (y + scrollY - totalPaddingTop) / sc)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val type = ev.getToolType(ev.actionIndex)
        val isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

        // Stift: auf einem Zeichenblock oder überall in der Notiz direkt zeichnen, kein Cursor, kein Scrollen
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && isPen && penPointer == -1) {
            val button = (ev.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            val erase = type == MotionEvent.TOOL_TYPE_ERASER || button
            val id = inkAt(ev.x, ev.y)
            if (id != null) {
                val m = inkProvider?.invoke(id)
                val pt = if (m != null) toPage(ev.x, ev.y) else null
                if (m != null && pt != null) {
                    penPointer = ev.getPointerId(ev.actionIndex)
                    penOnOverlay = false
                    requestUnbufferedDispatch(ev)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    m.down(pt[0], pt[1], ev.pressure, erase)
                    invalidate()
                    return true
                }
            }
            val ov = overlayModel
            if (ov != null && drawMode) {
                val pt = toOverlay(ev.x, ev.y)
                penPointer = ev.getPointerId(ev.actionIndex)
                penOnOverlay = true
                requestUnbufferedDispatch(ev)
                parent?.requestDisallowInterceptTouchEvent(true)
                ov.down(pt[0], pt[1], ev.pressure, erase)
                invalidate()
                return true
            }
        }
        if (penPointer != -1) {
            val m = if (penOnOverlay) overlayModel else inkModel
            val i = ev.findPointerIndex(penPointer)
            fun point(x: Float, y: Float) = if (penOnOverlay) toOverlay(x, y) else toPage(x, y)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (m != null && i != -1) {
                    for (h in 0 until ev.historySize) {
                        point(ev.getHistoricalX(i, h), ev.getHistoricalY(i, h))?.let { m.move(it[0], it[1], ev.getHistoricalPressure(i, h)) }
                    }
                    point(ev.getX(i), ev.getY(i))?.let { m.move(it[0], it[1], ev.pressure) }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (ev.getPointerId(ev.actionIndex) == penPointer) {
                    if (m != null && m.up()) {
                        if (penOnOverlay) onOverlayEdited?.invoke() else onInkEdited?.invoke()
                    }
                    penPointer = -1
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Das System hat die Berührung als Handballen erkannt: Strich verwerfen
                    m?.cancel()
                    penPointer = -1
                    invalidate()
                }
            }
            return true
        }

        // Finger (und Stift außerhalb von Blöcken): Kästchen umschalten, Tippen auf Blöcke aktiviert sie
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                tapCandidate = true
                pendingBox = boxAt(ev.x, ev.y)
                if (pendingBox != null) return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > slop) tapCandidate = false
                if (pendingBox != null) return true
            }
            MotionEvent.ACTION_UP -> {
                val box = pendingBox
                pendingBox = null
                if (box != null) {
                    if (boxAt(ev.x, ev.y) === box) toggle(box)
                    return true
                }
                if (tapCandidate) {
                    val id = inkAt(ev.x, ev.y)
                    if (id != null) {
                        // Antippen eines Blocks: aktivieren, ohne Cursor und Tastatur
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        onInkTap?.invoke(id)
                        return true
                    } else if (inkActiveId != null) {
                        onInkTapOutside?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> pendingBox = null
        }
        return super.onTouchEvent(ev)
    }

    /** Zeichenblock in der Zeile unter dem Finger/Stift (die ganze Fläche des Blocks zählt). */
    private fun inkAt(x: Float, y: Float): String? {
        val l = layout ?: return null
        val ed = text ?: return null
        val line = l.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        return ed.getSpans(l.getLineStart(line), l.getLineEnd(line), MdInk::class.java).firstOrNull()?.id
    }

    private fun boxAt(x: Float, y: Float): MdCheckbox? {
        val l = layout ?: return null
        val ed = text ?: return null
        val line = l.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        val from = l.getLineStart(line)
        val to = l.getLineEnd(line)
        val slopPx = context.dp(12)
        for (sp in ed.getSpans(from, to, MdCheckbox::class.java)) {
            val x0 = l.getPrimaryHorizontal(ed.getSpanStart(sp)) + totalPaddingLeft - scrollX
            val x1 = l.getPrimaryHorizontal(ed.getSpanEnd(sp)) + totalPaddingLeft - scrollX
            if (x >= x0 - slopPx && x <= x1 + slopPx) return sp
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

    /** Den aktiven Zeichenblock live aus dem Modell über den Text zeichnen (deckt die Vorschau ab). */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawActiveBlock(canvas)
        drawOverlay(canvas)
    }

    /** Die Zeichenebene liegt über Text und Blöcken; nur sichtbare Striche werden gezeichnet. */
    private fun drawOverlay(canvas: Canvas) {
        val ov = overlayModel ?: return
        if (ov.doc.strokes.isEmpty() && ov.current == null) return
        val sc = blockScale()
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), totalPaddingTop.toFloat())
        val clip = canvas.clipBounds
        for (s in ov.doc.strokes) {
            s.bounds()
            if ((s.y1 + 16f) * sc < clip.top || (s.y0 - 16f) * sc > clip.bottom) continue
            Ink.drawStroke(canvas, s, sc)
        }
        ov.current?.let { Ink.drawStroke(canvas, it, sc) }
        canvas.restore()
    }

    private fun drawActiveBlock(canvas: Canvas) {
        val m = inkModel ?: return
        val top = activeBlockTop() ?: return
        val sc = blockScale()
        val w = MdInk.blockWidth.toFloat()
        val h = m.doc.height * sc
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), top)
        canvas.clipRect(0f, 0f, w, h)
        Ink.drawPaper(canvas, w, h, sc, 12f * sc)
        for (s in m.doc.strokes) Ink.drawStroke(canvas, s, sc)
        m.current?.let { Ink.drawStroke(canvas, it, sc) }
        // Rahmen zeigt: dieser Block ist aktiv
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = context.dp(2).toFloat()
        p.color = Ui.ACCENT
        canvas.drawRoundRect(RectF(1f, 1f, w - 1, h - 1), 12f * sc, 12f * sc, p)
        canvas.restore()
    }
}
