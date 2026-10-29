package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Seitenleiste für Tablets (wie am PC): Zurück/Vor, Schnellwechsel, Stundenkarte, Bereiche mit Zählern,
 * angepinnte und zuletzt geöffnete Notizen und der Ordnerbaum mit Notizzahl.
 */
class SidePane(
    ctx: Context,
    private val vault: Vault,
    private val host: Host,
) : LinearLayout(ctx) {
    interface Host {
        fun go(s: Screen)
        fun openNote(path: String)
        fun quick()
        fun back()
        fun forward()
        fun newNote()
        fun newFolder()
        fun newNoteIn(dir: File)
        fun newFolderIn(dir: File)
        fun more()
        fun lesson(longPress: Boolean)
        fun removed(f: File)
    }

    var pins: List<String> = emptyList()
    var recents: List<String> = emptyList()
    var current: String? = null
    var screen: Screen = Screen.Overview
    val openFolders = HashSet<String>()

    private val store get() = vault.store
    private val prefs = ctx.getSharedPreferences("notizen-ui", Context.MODE_PRIVATE)

    private val backBtn = Ui.iconButton(ctx, R.drawable.ic_chevron_left, "Zurück", false) { host.back() }
    private val fwdBtn = Ui.iconButton(ctx, R.drawable.ic_chevron_right, "Vor", false) { host.forward() }
    private val navBox = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val listBox = LinearLayout(ctx).apply { orientation = VERTICAL }

    // Karte mit der laufenden/nächsten Unterrichtsstunde (wie bisher)
    private val cardHead = Ui.label(ctx, "", 11f, Ui.ACCENT, Ui.mono).apply { isAllCaps = true; letterSpacing = 0.04f }
    private val cardTitle = Ui.label(ctx, "", 16f, Ui.TEXT, Ui.sansSemi)
    private val cardSub = Ui.label(ctx, "", 13f, Ui.MUTED)
    private val card = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        background = Ui.ripple(ctx, Ui.round(ctx, Ui.ACCENT_SOFT, 8, Ui.mix(Ui.ACCENT, Ui.SURFACE, 0.22f)), 8)
        visibility = GONE
        isClickable = true
        isLongClickable = true
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(cardHead)
            addView(cardTitle, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
            addView(cardSub, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
        }, LayoutParams(0, WRAP_CONTENT, 1f))
        addView(Ui.icon(ctx, R.drawable.ic_chevron_right, Ui.ACCENT))
        setOnClickListener { host.lesson(false) }
        setOnLongClickListener { host.lesson(true); true }
    }

    private fun Int.dp() = context.dp(this)

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)
        openFolders.addAll(prefs.getString("open-folders", "")!!.split('\n').filter { it.isNotEmpty() })

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 0, 8.dp(), 0)
            addView(backBtn, LayoutParams(40.dp(), 40.dp()))
            addView(fwdBtn, LayoutParams(40.dp(), 40.dp()))
            addView(Ui.label(ctx, "Notizen", 16f, Ui.TEXT, Ui.sansSemi), LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 8.dp() })
            addView(Ui.iconButton(ctx, R.drawable.ic_plus, "Neue Notiz", false) { host.newNote() }, LayoutParams(40.dp(), 40.dp()))
        }
        addView(head, LayoutParams(MATCH_PARENT, 52.dp()))

        // Schnellwechsel-Feld (Strg+K)
        val quick = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 0, 14.dp(), 0)
            background = Ui.ripple(ctx, Ui.round(ctx, Ui.BG, 8, Ui.BORDER), 8)
            isClickable = true
            addView(Ui.icon(ctx, R.drawable.ic_search, Ui.FAINT, 18))
            addView(Ui.label(ctx, "Suchen oder springen", 15f, Ui.MUTED), LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 10.dp() })
            addView(Ui.label(ctx, "Strg K", 11f, Ui.FAINT, Ui.mono).apply {
                setPadding(6.dp(), 1.dp(), 6.dp(), 1.dp())
                background = Ui.round(ctx, Ui.SURFACE, 4, Ui.BORDER_STRONG)
            })
            setOnClickListener { host.quick() }
        }
        addView(quick, LayoutParams(MATCH_PARENT, 44.dp()).apply { leftMargin = 12.dp(); rightMargin = 12.dp(); bottomMargin = 8.dp() })
        addView(card, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = 12.dp(); rightMargin = 12.dp(); bottomMargin = 8.dp() })

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(navBox)
                addView(listBox)
            })
        }
        scroll.setOnContextClickListener { rootMenu(); true }
        addView(scroll, LayoutParams(MATCH_PARENT, 0, 1f))
        addView(hairline(ctx, false))

        val bottom = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            addView(Ui.primaryButton(ctx, "Neue Notiz", R.drawable.ic_plus) { host.newNote() }, LayoutParams(0, 44.dp(), 1f))
            addView(Ui.outlineButton(ctx, "Ordner") { host.newFolder() }, LayoutParams(WRAP_CONTENT, 44.dp()).apply { leftMargin = 8.dp() })
            addView(Ui.iconButton(ctx, R.drawable.ic_more, "Mehr", true) { host.more() }, LayoutParams(44.dp(), 44.dp()).apply { leftMargin = 8.dp() })
        }
        addView(bottom, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun setLesson(c: SchoolPlan.Card?) {
        if (c == null) { card.visibility = GONE; return }
        cardHead.text = c.head
        cardTitle.text = c.title
        cardSub.text = c.sub
        cardSub.visibility = if (c.sub.isEmpty()) GONE else VISIBLE
        card.visibility = VISIBLE
    }

    fun setHistory(canBack: Boolean, canForward: Boolean) {
        backBtn.alpha = if (canBack) 1f else 0.3f
        backBtn.isEnabled = canBack
        fwdBtn.alpha = if (canForward) 1f else 0.3f
        fwdBtn.isEnabled = canForward
    }

    private fun saveOpen() = prefs.edit().putString("open-folders", openFolders.joinToString("\n")).apply()

    /** Klappt alle Ordner über [path] auf. */
    fun reveal(path: String) {
        var d = Nav.dirOf(path)
        while (d.isNotEmpty()) { openFolders.add(d); d = Nav.dirOf(d) }
        saveOpen()
    }

    // ---- Aufbau ----

    private fun row(icon: Int?, iconTint: Int, name: CharSequence, meta: String, depth: Int, selected: Boolean, bold: Boolean, click: () -> Unit, long: (() -> Unit)? = null): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 40.dp()
            setPadding(8.dp() + depth * 16.dp(), 0, 10.dp(), 0)
            background = Ui.ripple(context, if (selected) Ui.round(context, Ui.ACCENT_SOFT, 8) else null, 8)
            isClickable = true
            if (icon != null) addView(ImageView(context).apply { setImageResource(icon); setColorFilter(iconTint) }, LayoutParams(18.dp(), 18.dp()).apply { rightMargin = 10.dp() })
            addView(Ui.label(context, name, 14f, if (selected) Ui.ACCENT else Ui.TEXT, if (bold || selected) Ui.sansMedium else Ui.sans).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(0, WRAP_CONTENT, 1f))
            if (meta.isNotEmpty()) addView(Ui.label(context, meta, 11f, if (selected) Ui.ACCENT else Ui.FAINT, Ui.mono), LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp() })
            setOnClickListener { click() }
            if (long != null) {
                setOnLongClickListener { long(); true }
                setOnContextClickListener { long(); true } // Rechtsklick mit der Maus
            }
        }

    private fun label(text: String): View = Ui.label(context, text.uppercase(), 11f, Ui.MUTED, Ui.mono).apply {
        letterSpacing = 0.06f
        setPadding(18.dp(), 14.dp(), 12.dp(), 4.dp())
    }

    fun refresh() {
        val notes = vault.notes()
        val counts = Nav.folderCounts(notes.map { it.path })
        val ideaCount = vault.ideas().size
        val taskCount = vault.tasks(notes).size
        navBox.removeAllViews()
        listBox.removeAllViews()
        val pad = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = 8.dp(); rightMargin = 8.dp() }

        fun nav(icon: Int, name: String, meta: String, s: Screen) {
            val on = current == null && screen == s
            navBox.addView(row(icon, if (on) Ui.ACCENT else Ui.TEXT2, name, meta, 0, on, false, { host.go(s) }), pad)
        }
        nav(R.drawable.ic_grid, "Übersicht", "", Screen.Overview)
        nav(R.drawable.ic_inbox, "Inbox", if (ideaCount > 0) "$ideaCount" else "", Screen.Ideas)
        nav(R.drawable.ic_task, "Aufgaben", if (taskCount > 0) "$taskCount" else "", Screen.Tasks)
        nav(R.drawable.ic_week, "Rückblick", "", Screen.Review)

        val hasNote = { p: String -> notes.any { it.path == p } }
        val pinned = pins.filter(hasNote)
        val recent = recents.filter { hasNote(it) && it !in pinned }.take(4)
        fun noteRow(p: String, icon: Int) = row(icon, Ui.ACCENT, Nav.titleOf(p), Nav.dirOf(p).substringAfterLast('/'), 0, current == p, false, { host.openNote(p) })
        if (pinned.isNotEmpty()) {
            listBox.addView(label("Angepinnt"))
            pinned.forEach { listBox.addView(noteRow(it, R.drawable.ic_star_fill), pad) }
        }
        if (recent.isNotEmpty()) {
            listBox.addView(label("Zuletzt geöffnet"))
            recent.forEach { listBox.addView(noteRow(it, R.drawable.ic_clock), pad) }
        }

        listBox.addView(label("Ordner"))
        addDir(store.root, 0, counts, pad)
    }

    private fun addDir(dir: File, depth: Int, counts: Map<String, Int>, pad: LayoutParams) {
        for (f in store.list(dir)) {
            val rel = f.relativeTo(store.root).path.replace(File.separatorChar, '/')
            if (f.isDirectory) {
                val open = rel in openFolders
                val here = current == null && screen == Screen.Area(rel)
                listBox.addView(
                    row(
                        R.drawable.ic_folder, Ui.FAINT, f.name, "${counts[rel] ?: 0}", depth, here, depth == 0,
                        {
                            if (open && here) openFolders.remove(rel) else openFolders.add(rel)
                            saveOpen()
                            host.go(Screen.Area(rel))
                        },
                        { itemMenu(f) },
                    ),
                    pad,
                )
                if (open) addDir(f, depth + 1, counts, pad)
            } else if (rel != Inbox.FILE) {
                val date = Nav.whenText(f.lastModified())
                listBox.addView(row(R.drawable.ic_note, Ui.FAINT, f.nameWithoutExtension, date, depth, current == rel, false, { host.openNote(rel) }, { itemMenu(f) }), pad)
            }
        }
    }

    // ---- Umbenennen und Löschen (langes Drücken) ----

    /** Langes Drücken oder Rechtsklick: Neue Notiz/Ordner im Ordner (bei einer Notiz in ihrem), Umbenennen, Löschen. */
    private fun itemMenu(f: File) {
        val dir = if (f.isDirectory) f else (f.parentFile ?: store.root)
        val where = if (dir == store.root) "Notizen" else dir.name
        AlertDialog.Builder(context)
            .setTitle(if (f.isDirectory) f.name else f.nameWithoutExtension)
            .setItems(arrayOf("Neue Notiz in „$where“", "Neuer Ordner in „$where“", "Umbenennen", "Löschen")) { _, which ->
                when (which) {
                    0 -> host.newNoteIn(dir)
                    1 -> host.newFolderIn(dir)
                    2 -> rename(f)
                    else -> confirmDelete(f)
                }
            }
            .show()
    }

    /** Rechtsklick auf leeren Platz in der Liste: im Notizen-Ordner anlegen. */
    private fun rootMenu() {
        AlertDialog.Builder(context)
            .setTitle("Notizen")
            .setItems(arrayOf("Neue Notiz", "Neuer Ordner")) { _, which -> if (which == 0) host.newNoteIn(store.root) else host.newFolderIn(store.root) }
            .show()
    }

    private fun rename(f: File) {
        askText(context, "Umbenennen", if (f.isDirectory) f.name else f.nameWithoutExtension) { name ->
            host.removed(f)
            if (store.rename(f, name) == null) toast("Name leer oder schon vergeben")
            refresh()
        }
    }

    private fun confirmDelete(f: File) {
        val what = if (f.isDirectory) "Ordner \"${f.name}\" mit allem Inhalt" else "Notiz \"${f.nameWithoutExtension}\""
        AlertDialog.Builder(context)
            .setTitle("Löschen?")
            .setMessage("$what wird endgültig gelöscht. Es gibt noch keinen Papierkorb.")
            .setPositiveButton("Löschen") { _, _ ->
                host.removed(f)
                store.delete(f)
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
