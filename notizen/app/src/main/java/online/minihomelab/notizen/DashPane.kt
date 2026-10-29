package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Was rechts neben der Seitenleiste gezeigt wird, wenn keine Notiz offen ist. */
sealed class Screen {
    object Overview : Screen()
    data class Area(val path: String) : Screen()
    object Ideas : Screen()
    object Tasks : Screen()
    object Review : Screen()
}

/** Übersicht, Bereiche, Inbox-Durchsicht, Aufgaben und Wochenrückblick (wie am PC), alles aus den Dateien im Notizen-Ordner. */
class DashPane(ctx: Context, private val vault: Vault, private val host: Host) : ScrollView(ctx) {
    interface Host {
        fun openNote(path: String)
        fun go(s: Screen)
        fun changed()
        fun dictate(onText: (String) -> Unit)
        fun toast(msg: String)
    }

    var screen: Screen = Screen.Overview
        private set

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(48))
    }

    // Zustand der Durchsicht
    private var ideaIdx = 0
    private var ideaArea = "Schule"
    private var ideaMinutes = 45
    private var ideaDue = ""
    private var taskFilter = ""

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(box, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun refresh() = show(screen, keepScroll = true)

    fun show(s: Screen, keepScroll: Boolean = false) {
        val y = scrollY
        screen = s
        box.removeAllViews()
        val notes = vault.notes()
        when (s) {
            Screen.Overview -> overview(notes, "")
            is Screen.Area -> overview(notes, s.path)
            Screen.Ideas -> ideas()
            Screen.Tasks -> tasksScreen(notes)
            Screen.Review -> review(notes)
        }
        if (keepScroll) post { scrollTo(0, y) } else scrollTo(0, 0)
    }

    // ---- kleine Bausteine ----

    private fun Int.dp() = context.dp(this)

    private fun tv(text: CharSequence, sp: Float, color: Int = Ui.TEXT, face: Typeface = Ui.sans): TextView = Ui.label(context, text, sp, color, face)

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f, top: Int = 0, left: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top.dp(); leftMargin = left.dp() }

    private fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun rowLayout(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun card(): LinearLayout = column().apply {
        background = Ui.round(context, Ui.SURFACE, 12, Ui.BORDER)
        setPadding(20.dp(), 18.dp(), 20.dp(), 18.dp())
    }

    private fun header(eyebrow: String, title: String) {
        box.addView(tv(eyebrow, 12f, Ui.MUTED, Ui.mono).apply { letterSpacing = 0.04f })
        box.addView(tv(title, 28f, Ui.TEXT, Ui.sansSemi), lp(top = 2))
    }

    private fun sectionTitle(text: String, top: Int = 22) = box.addView(tv(text, 15f, Ui.TEXT, Ui.sansSemi), lp(top = top))

    private fun chip(label: String, on: Boolean, click: () -> Unit): View = tv(label, 14f, if (on) Ui.ACCENT else Ui.TEXT2, if (on) Ui.sansMedium else Ui.sans).apply {
        gravity = Gravity.CENTER
        minHeight = 44.dp()
        setPadding(16.dp(), 0, 16.dp(), 0)
        background = Ui.ripple(context, Ui.round(context, if (on) Ui.ACCENT_SOFT else Ui.SURFACE, 22, if (on) Ui.ACCENT else Ui.BORDER_STRONG), 22)
        isClickable = true
        setOnClickListener { click() }
    }

    private fun chips(items: List<Pair<String, Boolean>>, click: (Int) -> Unit): View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(rowLayout().apply {
            items.forEachIndexed { i, (label, on) -> addView(chip(label, on) { click(i) }, lp(WRAP_CONTENT, WRAP_CONTENT, left = if (i == 0) 0 else 8)) }
        })
    }

    private fun divider() = View(context).apply { setBackgroundColor(Ui.BORDER) }.also { it.layoutParams = lp(h = 1) }

    /** Zeile mit Titel, Unterzeile und rechtem Text; tippen öffnet. */
    private fun noteRow(title: String, sub: String, right: String, click: () -> Unit): View = rowLayout().apply {
        minimumHeight = 52.dp()
        setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        background = Ui.ripple(context, null, 8)
        isClickable = true
        addView(column().apply {
            addView(tv(title, 14f, Ui.TEXT, Ui.sansMedium).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (sub.isNotEmpty()) addView(tv(sub, 12f, Ui.MUTED, Ui.mono).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, lp(0, WRAP_CONTENT, 1f))
        addView(tv(right, 12f, Ui.MUTED, Ui.mono), lp(WRAP_CONTENT, WRAP_CONTENT, left = 8))
        setOnClickListener { click() }
    }

    private fun taskRow(ref: Vault.TaskRef, showCheck: Boolean = true): View = rowLayout().apply {
        minimumHeight = 52.dp()
        setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        if (showCheck) addView(View(context).apply {
            background = Ui.round(context, Ui.SURFACE, 4, Ui.BORDER_STRONG)
            isClickable = true
            setOnClickListener {
                if (vault.checkTask(ref)) { host.toast("Erledigt: ${ref.task.title}"); host.changed(); refresh() }
                else host.toast("Aufgabe hat sich geändert, bitte Notiz öffnen")
            }
        }, lp(22.dp(), 22.dp()).apply { rightMargin = 12.dp() })
        addView(column().apply {
            addView(tv(ref.task.title, 14f, Ui.TEXT, Ui.sansMedium).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(tv(Nav.titleOf(ref.path), 12f, Ui.MUTED, Ui.mono))
        }, lp(0, WRAP_CONTENT, 1f))
        val meta = listOfNotNull(ref.task.minutes?.let { "~$it min" }, ref.task.due?.let { "bis $it" }).joinToString(" · ")
        if (meta.isNotEmpty()) addView(tv(meta, 12f, Ui.MUTED, Ui.mono), lp(WRAP_CONTENT, WRAP_CONTENT, left = 8))
        background = Ui.ripple(context, null, 8)
        setOnClickListener { host.openNote(ref.path) }
    }

    // ---- Übersicht und Bereiche ----

    private fun overview(notes: List<Vault.Note>, area: String) {
        val scoped = notes.filter { area.isEmpty() || it.path.startsWith("$area/") }
        val tasks = vault.tasks(scoped)
        header(
            if (area.isEmpty()) LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)).uppercase(Locale.GERMAN) else "$area/",
            if (area.isEmpty()) "Übersicht" else area.substringAfterLast('/'),
        )
        captureCard()

        val ideas = vault.ideas()
        if (area.isEmpty() && ideas.isNotEmpty()) {
            val c = card()
            c.addView(rowLayout().apply {
                addView(tv("Inbox · ${ideas.size} ${if (ideas.size == 1) "neue Idee" else "neue Ideen"}", 15f, Ui.TEXT, Ui.sansSemi), lp(0, WRAP_CONTENT, 1f))
                addView(tv("Durchsicht am Sonntag", 12f, Ui.MUTED))
            })
            ideas.takeLast(4).reversed().forEach { i ->
                c.addView(divider(), lp(h = 1, top = 8))
                c.addView(rowLayout().apply {
                    minimumHeight = 44.dp()
                    addView(tv(i.text, 14f, Ui.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(0, WRAP_CONTENT, 1f))
                    addView(tv(i.stamp.takeLast(5), 12f, Ui.MUTED, Ui.mono), lp(WRAP_CONTENT, WRAP_CONTENT, left = 8))
                })
            }
            c.addView(Ui.primaryButton(context, "Jetzt durchsehen", null) { host.go(Screen.Ideas) }, lp(WRAP_CONTENT, 44.dp(), top = 12).apply { width = 200.dp() })
            box.addView(c, lp(top = 16))
        }

        folderCards(scoped, tasks, area)
        activityCard(scoped)

        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val recent = card()
        recent.addView(tv("Zuletzt bearbeitet", 15f, Ui.TEXT, Ui.sansSemi))
        val latest = scoped.sortedByDescending { it.mtime }.filter { it.path != Inbox.FILE }.take(8)
        if (latest.isEmpty()) recent.addView(tv("Noch keine Notizen.", 14f, Ui.MUTED), lp(top = 10))
        latest.forEach { n -> recent.addView(noteRow(Nav.titleOf(n.path), n.path, Nav.whenText(n.mtime)) { host.openNote(n.path) }, lp(top = 4)) }
        val open = card()
        open.addView(tv("Offene Aufgaben", 15f, Ui.TEXT, Ui.sansSemi))
        if (tasks.isEmpty()) open.addView(tv("Keine offenen Aufgaben.", 14f, Ui.MUTED), lp(top = 10))
        tasks.take(8).forEach { open.addView(taskRow(it), lp(top = 4)) }
        if (tasks.size > 8) open.addView(noteRow("und ${tasks.size - 8} weitere", "", "alle ansehen") { host.go(Screen.Tasks) }, lp(top = 4))
        cols.addView(recent, lp(0, WRAP_CONTENT, 3f))
        cols.addView(open, lp(0, WRAP_CONTENT, 2f, left = 16))
        box.addView(cols, lp(top = 16))
    }

    private fun captureCard() {
        val input = EditText(context).apply {
            hint = "Idee festhalten …"
            setHintTextColor(Ui.FAINT)
            setTextColor(Ui.TEXT)
            typeface = Ui.sans
            textSize = 17f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = Ui.round(context, Ui.BG, 10, Ui.BORDER_STRONG)
            setPadding(16.dp(), 0, 16.dp(), 0)
        }
        fun submit() {
            if (vault.addIdea(input.text.toString())) {
                input.setText("")
                host.toast("Festgehalten")
                host.changed()
                refresh()
            }
        }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        val c = card().apply { setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp()) }
        c.addView(rowLayout().apply {
            addView(input, lp(0, 52.dp(), 1f))
            addView(Ui.iconButton(context, R.drawable.ic_mic, "Diktieren", true) {
                host.dictate { t -> input.setText((input.text.toString() + " " + t).trim()); input.setSelection(input.text.length) }
            }, lp(52.dp(), 52.dp(), left = 10))
            addView(Ui.primaryButton(context, "Festhalten", null) { submit() }, lp(WRAP_CONTENT, 52.dp(), left = 10).apply { width = 140.dp() })
        })
        c.addView(tv("Landet in der Inbox, sortiert wird bei der Durchsicht. Strg+K sucht und springt.", 13f, Ui.MUTED), lp(top = 8))
        box.addView(c, lp(top = 18))
    }

    private fun folderCards(scoped: List<Vault.Note>, tasks: List<Vault.TaskRef>, area: String) {
        val groups = LinkedHashMap<String, MutableList<Vault.Note>>()
        if (area.isEmpty()) Nav.AREAS.forEach { groups[it] = ArrayList() }
        for (n in scoped) {
            val rest = if (area.isEmpty()) n.path else n.path.removePrefix("$area/")
            if ('/' !in rest) continue
            groups.getOrPut(rest.substringBefore('/')) { ArrayList() }.add(n)
        }
        if (groups.isEmpty()) return
        val names = groups.keys.sortedWith(compareBy({ if (area.isEmpty() && it in Nav.AREAS) Nav.AREAS.indexOf(it) else 99 }, { it.lowercase() }))
        sectionTitle(if (area.isEmpty()) "Bereiche" else "Ordner")
        names.chunked(3).forEach { rowNames ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowNames.forEachIndexed { i, name ->
                val full = if (area.isEmpty()) name else "$area/$name"
                val ns = groups[name]!!
                val last = ns.filter { it.path != Inbox.FILE }.maxByOrNull { it.mtime }
                val c = card().apply {
                    background = Ui.ripple(context, Ui.round(context, Ui.SURFACE, 12, Ui.BORDER), 12)
                    isClickable = true
                    setOnClickListener { host.go(Screen.Area(full)) }
                }
                c.addView(rowLayout().apply {
                    addView(tv(name, 17f, Ui.TEXT, Ui.sansSemi), lp(0, WRAP_CONTENT, 1f))
                    addView(tv("öffnen →", 12f, Ui.ACCENT, Ui.mono))
                })
                c.addView(tv(if (last != null) "Zuletzt: ${Nav.titleOf(last.path)}" else "Noch leer", 13f, Ui.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(top = 2))
                c.addView(divider(), lp(h = 1, top = 12))
                val openN = tasks.count { it.path.startsWith("$full/") }
                c.addView(rowLayout().apply {
                    addView(tv("${ns.size} ${if (ns.size == 1) "Notiz" else "Notizen"}", 12f, Ui.MUTED, Ui.mono), lp(0, WRAP_CONTENT, 1f))
                    if (ns.isNotEmpty()) addView(tv("$openN offen", 12f, Ui.MUTED, Ui.mono))
                }, lp(top = 8))
                row.addView(c, lp(0, WRAP_CONTENT, 1f, left = if (i == 0) 0 else 14))
            }
            repeat(3 - rowNames.size) { row.addView(View(context), lp(0, 1, 1f, left = 14)) }
            box.addView(row, lp(top = 10))
        }
    }

    private fun activityCard(scoped: List<Vault.Note>) {
        val act = Nav.activity(scoped.filter { it.path != Inbox.FILE }.map { it.mtime })
        val c = card()
        c.addView(rowLayout().apply {
            addView(tv("Aktivität", 15f, Ui.TEXT, Ui.sansSemi), lp(0, WRAP_CONTENT, 1f))
            addView(tv("${act.total} ${if (act.total == 1) "Notiz" else "Notizen"} im letzten Jahr nach Tag der letzten Änderung", 12f, Ui.MUTED))
        })
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        scroll.addView(ActivityView(context, act))
        c.addView(scroll, lp(top = 8))
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) } // wie bei GitHub: heute steht rechts
        box.addView(c, lp(top = 16))
    }

    // ---- Wochendurchsicht der Inbox ----

    private fun ideas() {
        val list = vault.ideas()
        header("WOCHENDURCHSICHT · INBOX", if (list.isEmpty()) "Inbox ist leer" else "Idee ${ideaIdx.coerceIn(0, list.size - 1) + 1} von ${list.size}")
        if (list.isEmpty()) {
            box.addView(tv("Neue Ideen landen hier. Oben in der Übersicht oder mit dem Mikrofon festhalten.", 15f, Ui.MUTED), lp(top = 16))
            box.addView(Ui.outlineButton(context, "Zur Übersicht") { host.go(Screen.Overview) }, lp(WRAP_CONTENT, 44.dp(), top = 16))
            return
        }
        ideaIdx = ideaIdx.coerceIn(0, list.size - 1)
        val idea = list[ideaIdx]

        box.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(context, Ui.BORDER, 4)
            addView(View(context).apply { background = Ui.round(context, Ui.ACCENT_BG, 4) }, lp(0, 8.dp(), (ideaIdx + 1).toFloat()))
            if (ideaIdx + 1 < list.size) addView(View(context), lp(0, 8.dp(), (list.size - ideaIdx - 1).toFloat()))
        }, lp(h = 8.dp(), top = 14))

        val c = card()
        c.addView(tv(idea.stamp, 12f, Ui.MUTED, Ui.mono))
        c.addView(tv(idea.text, 22f, Ui.TEXT).apply { setLineSpacing(0f, 1.25f) }, lp(top = 8))
        box.addView(c, lp(top = 14))

        val a = card()
        a.addView(tv("Zur Aufgabe machen", 15f, Ui.TEXT, Ui.sansSemi))
        a.addView(tv("Bereich", 13f, Ui.MUTED), lp(top = 12))
        a.addView(chips(Nav.AREAS.map { it to (it == ideaArea) }) { ideaArea = Nav.AREAS[it]; refresh() }, lp(top = 6))
        a.addView(tv("Geschätzte Dauer", 13f, Ui.MUTED), lp(top = 12))
        val mins = listOf(15, 30, 45, 60, 90)
        a.addView(chips(mins.map { "$it min" to (it == ideaMinutes) }) { ideaMinutes = mins[it]; refresh() }, lp(top = 6))
        a.addView(tv("Frist (optional, z. B. Freitag oder 3.11.)", 13f, Ui.MUTED), lp(top = 12))
        val due = EditText(context).apply {
            setText(ideaDue)
            setSingleLine()
            setTextColor(Ui.TEXT)
            typeface = Ui.sans
            textSize = 15f
            background = Ui.round(context, Ui.BG, 8, Ui.BORDER_STRONG)
            setPadding(12.dp(), 0, 12.dp(), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) { ideaDue = e.toString() }
            })
        }
        a.addView(due, lp(200.dp(), 44.dp(), top = 6))
        val buttons = rowLayout()
        buttons.addView(Ui.primaryButton(context, "Aufgabe anlegen", null) {
            val parsed = Inbox.parseDue(ideaDue)
            if (ideaDue.isNotBlank() && parsed == null) { host.toast("Frist nicht verstanden: Freitag, morgen oder 3.11."); return@primaryButton }
            val path = vault.addTask(ideaArea, idea.text, ideaMinutes, parsed)
            vault.removeIdea(idea.raw)
            ideaDue = ""
            host.toast("Aufgabe in $path")
            host.changed(); refresh()
        }, lp(WRAP_CONTENT, 48.dp()).apply { width = 170.dp() })
        buttons.addView(Ui.outlineButton(context, "Als Notiz") {
            val path = vault.noteFromIdea(ideaArea, idea.text)
            vault.removeIdea(idea.raw)
            host.toast("Notiz: $path")
            host.changed(); refresh()
        }, lp(WRAP_CONTENT, 48.dp(), left = 8))
        buttons.addView(View(context), lp(0, 1, 1f))
        buttons.addView(Ui.outlineButton(context, "Später") { ideaIdx = (ideaIdx + 1) % list.size; refresh() }, lp(WRAP_CONTENT, 48.dp()))
        buttons.addView(Ui.outlineButton(context, "Löschen") {
            AlertDialog.Builder(context).setTitle("Idee löschen?").setMessage(idea.text)
                .setPositiveButton("Löschen") { _, _ -> vault.removeIdea(idea.raw); host.changed(); refresh() }
                .setNegativeButton("Abbrechen", null).show()
        }, lp(WRAP_CONTENT, 48.dp(), left = 8))
        a.addView(buttons, lp(top = 16))
        box.addView(a, lp(top = 14))

        box.addView(rowLayout().apply {
            addView(Ui.outlineButton(context, "‹ Vorige") { ideaIdx = (ideaIdx - 1 + list.size) % list.size; refresh() }, lp(WRAP_CONTENT, 44.dp()))
            addView(Ui.outlineButton(context, "Nächste ›") { ideaIdx = (ideaIdx + 1) % list.size; refresh() }, lp(WRAP_CONTENT, 44.dp(), left = 8))
        }, lp(top = 14))
    }

    // ---- Aufgaben-Übersicht ----

    private fun tasksScreen(notes: List<Vault.Note>) {
        val all = vault.tasks(notes)
        header("ALLE NOTIZEN", "Aufgaben")
        val filters = listOf("" to "Alle ${all.size}") + Nav.AREAS.map { a -> a to "$a ${all.count { it.path.startsWith("$a/") }}" }
        box.addView(chips(filters.map { it.second to (it.first == taskFilter) }) { taskFilter = filters[it].first; refresh() }, lp(top = 14))
        val shown = all.filter { taskFilter.isEmpty() || it.path.startsWith("$taskFilter/") }
        if (shown.isEmpty()) {
            box.addView(tv("Keine offenen Aufgaben.", 15f, Ui.MUTED), lp(top = 16))
            return
        }
        val groups = shown.groupBy { it.path.substringBefore('/', "Ohne Bereich") }
        val order = groups.keys.sortedWith(compareBy({ if (it in Nav.AREAS) Nav.AREAS.indexOf(it) else 99 }, { it }))
        for (g in order) {
            val c = card()
            c.addView(rowLayout().apply {
                addView(tv(g, 15f, Ui.TEXT, Ui.sansSemi), lp(0, WRAP_CONTENT, 1f))
                addView(tv("${groups[g]!!.size}", 12f, Ui.MUTED, Ui.mono))
            })
            groups[g]!!.forEach { c.addView(taskRow(it), lp(top = 2)) }
            box.addView(c, lp(top = 14))
        }
        box.addView(tv("Aufgaben sind alle „- [ ]“ aus deinen Notizen; Abhaken schreibt „- [x]“ in die Notiz.", 12f, Ui.MUTED), lp(top = 14))
    }

    // ---- Wochenrückblick ----

    private fun review(notes: List<Vault.Note>) {
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val sunday = monday.plusDays(6)
        val fmt = DateTimeFormatter.ofPattern("dd.MM.")
        val week = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        header("${monday.format(fmt)} BIS ${sunday.format(fmt)}", "Wochenrückblick · Woche $week")

        val zone = java.time.ZoneId.systemDefault()
        val inWeek = notes.filter { it.path != Inbox.FILE && !java.time.Instant.ofEpochMilli(it.mtime).atZone(zone).toLocalDate().let { d -> d.isBefore(monday) || d.isAfter(sunday) } }
        val tasks = vault.tasks(notes)
        val ideas = vault.ideas()
        val stats = listOf("${inWeek.size}" to "Notizen bearbeitet", "${tasks.size}" to "Aufgaben offen", "${ideas.size}" to "Ideen in der Inbox")
        box.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            stats.forEachIndexed { i, (v, l) ->
                addView(card().apply {
                    addView(tv(v, 28f, Ui.TEXT, Ui.sansSemi))
                    addView(tv(l, 13f, Ui.MUTED))
                }, lp(0, WRAP_CONTENT, 1f, left = if (i == 0) 0 else 14))
            }
        }, lp(top = 16))

        val perDay = IntArray(7)
        inWeek.forEach { n -> perDay[java.time.Instant.ofEpochMilli(n.mtime).atZone(zone).toLocalDate().dayOfWeek.value - 1]++ }
        val bars = card()
        bars.addView(tv("Aktivität pro Tag", 15f, Ui.TEXT, Ui.sansSemi))
        bars.addView(BarsView(context, perDay.toList(), listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"), today.dayOfWeek.value - 1), lp(h = 130.dp(), top = 10))
        box.addView(bars, lp(top = 14))

        val due = card()
        due.addView(tv("Offen mit Frist", 15f, Ui.TEXT, Ui.sansSemi))
        val withDue = tasks.filter { it.task.due != null }
        if (withDue.isEmpty()) due.addView(tv("Keine Aufgaben mit Frist.", 14f, Ui.MUTED), lp(top = 8))
        withDue.take(8).forEach { due.addView(taskRow(it), lp(top = 2)) }
        box.addView(due, lp(top = 14))

        val claude = card()
        claude.addView(tv("Zusammenfassung von Claude", 15f, Ui.TEXT, Ui.sansSemi))
        claude.addView(tv("Auf Zuruf: Bitte Claude über den Notizen-Connector, die Woche zusammenzufassen oder Lernkarten zu einem Fach zu erstellen. Claude liest nur, was in deinen Notizen steht.", 14f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, lp(top = 8))
        box.addView(claude, lp(top = 14))
    }
}

/** Aktivität wie bei GitHub: 53 Wochen als Spalten, Montag oben, Tage nach Änderungen der Notizen eingefärbt. */
class ActivityView(ctx: Context, private val act: Nav.Activity) : View(ctx) {
    private val cell = ctx.dp(13).toFloat()
    private val gap = ctx.dp(3).toFloat()
    private val left = ctx.dp(28).toFloat()
    private val top = ctx.dp(20).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dp(10).toFloat()
        color = Ui.MUTED
        typeface = Ui.mono
    }
    private val rect = RectF()

    private fun levelColor(l: Int) = when (l) {
        0 -> Ui.BORDER
        1 -> Ui.mix(Ui.ACCENT_BG, Ui.SURFACE, 0.30f)
        2 -> Ui.mix(Ui.ACCENT_BG, Ui.SURFACE, 0.55f)
        3 -> Ui.mix(Ui.ACCENT_BG, Ui.SURFACE, 0.80f)
        else -> Ui.ACCENT_BG
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((left + Nav.WEEKS * (cell + gap)).toInt(), (top + 7 * (cell + gap)).toInt())

    override fun onDraw(c: Canvas) {
        listOf(0 to "Mo", 2 to "Mi", 4 to "Fr").forEach { (row, name) -> c.drawText(name, 0f, top + row * (cell + gap) + cell * 0.85f, text) }
        act.months.forEach { (name, col) -> c.drawText(name, left + col * (cell + gap), top - gap * 2, text) }
        act.cells.forEachIndexed { i, cl ->
            if (cl == null) return@forEachIndexed
            val x = left + (i / 7) * (cell + gap)
            val y = top + (i % 7) * (cell + gap)
            rect.set(x, y, x + cell, y + cell)
            paint.color = levelColor(cl.level)
            c.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, paint)
        }
    }
}

/** Sieben Balken (Mo bis So) mit Beschriftung; der heutige Tag ist kräftig. */
class BarsView(ctx: Context, private val values: List<Int>, private val labels: List<String>, private val today: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dp(11).toFloat()
        color = Ui.MUTED
        typeface = Ui.mono
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val slot = width / 7f
        val barW = minOf(slot * 0.55f, context.dp(40).toFloat())
        val labelH = context.dp(18).toFloat()
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val h = (height - labelH - 6) * v / max
            rect.set(cx - barW / 2, height - labelH - h, cx + barW / 2, height - labelH)
            paint.color = if (i == today) Ui.ACCENT_BG else Ui.mix(Ui.ACCENT_BG, Ui.SURFACE, 0.30f)
            c.drawRoundRect(rect, 4f, 4f, paint)
            c.drawText(labels[i], cx, height - 3f, text)
        }
    }
}
