package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ordnerbaum mit Titelsuche: Fach → Thema → Notiz, ohne Tags. */
class TreePane(
    ctx: Context,
    private val store: NoteStore,
    private val onOpen: (File) -> Unit,
    private val onRemoved: (File) -> Unit,
    private val onMore: () -> Unit,
    private val onLesson: (longPress: Boolean) -> Unit,
) : LinearLayout(ctx) {

    var dir: File = store.root
    var selected: File? = null

    private var items: List<File> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM.", Locale.GERMANY)

    // ---- Suche ----

    private val search = EditText(ctx).apply {
        hint = "Titel suchen"
        setHintTextColor(Ui.FAINT)
        setTextColor(Ui.TEXT)
        typeface = Ui.sans
        textSize = 16f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        background = null
        setPadding(0, 0, 0, 0)
    }

    private val searchBox = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.round(ctx, Ui.SUBTLE, 8)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        addView(Ui.icon(ctx, R.drawable.ic_search, Ui.FAINT))
        addView(search, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = ctx.dp(10) })
    }

    // ---- Karte mit der laufenden/nächsten Unterrichtsstunde ----

    private val cardHead = Ui.label(ctx, "", 11f, Ui.ACCENT, Ui.mono).apply {
        isAllCaps = true
        letterSpacing = 0.04f
    }
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
        val texts = LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(cardHead)
            addView(cardTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
            addView(cardSub, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(Ui.icon(ctx, R.drawable.ic_chevron_right, Ui.ACCENT))
        setOnClickListener { onLesson(false) }
        setOnLongClickListener { onLesson(true); true }
    }

    // ---- Pfad ----

    private val crumbBack = Ui.icon(ctx, R.drawable.ic_chevron_left, Ui.MUTED, 16)
    private val crumbText = Ui.label(ctx, "", 13f, Ui.MUTED)
    private val crumb = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        addView(crumbBack, LayoutParams(ctx.dp(16), ctx.dp(16)).apply { rightMargin = ctx.dp(6) })
        addView(crumbText)
        setOnClickListener { goUp() }
    }

    // ---- Liste ----

    private val empty = Ui.label(ctx, "", 14f, Ui.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(24), ctx.dp(32), ctx.dp(24), ctx.dp(32))
    }

    private val list = ListView(ctx).apply {
        divider = null
        dividerHeight = 0
        selector = ColorDrawable(0)
        clipToPadding = false
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
    }

    private inner class RowView(c: Context) : LinearLayout(c) {
        val icon = ImageView(c)
        val name = Ui.label(c, "", 15f, Ui.TEXT)
        val meta = Ui.label(c, "", 12f, Ui.FAINT, Ui.mono)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = c.dp(50)
            setPadding(c.dp(12), 0, c.dp(12), 0)
            addView(icon, LayoutParams(c.dp(20), c.dp(20)))
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = c.dp(12); rightMargin = c.dp(8) })
            addView(meta)
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = items[position]
            val c = parent.context
            val row = (convertView as? RowView) ?: RowView(c)
            val isSelected = f == selected
            val searching = search.text.isNotBlank()
            val fg = if (isSelected) Ui.ACCENT else Ui.TEXT
            val sub = if (isSelected) Ui.ACCENT else Ui.FAINT

            row.icon.setImageResource(if (f.isDirectory) R.drawable.ic_folder else R.drawable.ic_note)
            row.icon.setColorFilter(if (isSelected) Ui.ACCENT else Ui.FAINT)
            row.name.text = when {
                f.isDirectory -> f.name
                searching -> store.rel(f)
                else -> f.nameWithoutExtension
            }
            row.name.typeface = if (f.isDirectory || isSelected) Ui.sansMedium else Ui.sans
            row.name.setTextColor(fg)
            row.meta.text = if (f.isDirectory) store.list(f).size.toString() else dateFormat.format(Date(f.lastModified()))
            row.meta.setTextColor(sub)
            row.background = Ui.ripple(c, if (isSelected) Ui.round(c, Ui.ACCENT_SOFT, 8) else null, 8)
            return row
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)

        val top = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(12))
            addView(searchBox, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(44)))
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
        }
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hairline(ctx, false))
        addView(crumb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hairline(ctx, false))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hairline(ctx, false))

        val bottom = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            addView(Ui.primaryButton(ctx, "Neue Notiz", R.drawable.ic_plus) { newNote() }, LayoutParams(0, ctx.dp(44), 1f))
            addView(Ui.outlineButton(ctx, "Ordner") { newFolder() }, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(44)).apply { leftMargin = ctx.dp(8) })
            addView(Ui.iconButton(ctx, R.drawable.ic_more, "Mehr", true) { onMore() }, LayoutParams(ctx.dp(44), ctx.dp(44)).apply { leftMargin = ctx.dp(8) })
        }
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val f = items[position]
            if (f.isDirectory) {
                dir = f
                search.setText("")
                refresh()
            } else {
                onOpen(f)
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            itemMenu(items[position])
            true
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
    }

    fun setLesson(c: SchoolPlan.Card?) {
        if (c == null) {
            card.visibility = GONE
            return
        }
        cardHead.text = c.head
        cardTitle.text = c.title
        cardSub.text = c.sub
        cardSub.visibility = if (c.sub.isEmpty()) GONE else VISIBLE
        card.visibility = VISIBLE
    }

    /** Springt in einen Ordner (leert die Suche). */
    fun openDir(f: File) {
        dir = f
        search.setText("")
        refresh()
    }

    private fun crumbs(q: String): CharSequence {
        if (q.isNotEmpty()) return "Suchergebnisse"
        if (dir == store.root) return "Notizen"
        val parts = dir.relativeTo(store.root).path.split(File.separator)
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

    fun refresh() {
        val q = search.text.toString().trim()
        items = if (q.isEmpty()) store.list(dir) else store.search(q)
        crumbText.text = crumbs(q)
        crumbBack.visibility = if (q.isNotEmpty() || dir != store.root) VISIBLE else GONE
        crumb.isClickable = q.isNotEmpty() || dir != store.root
        adapter.notifyDataSetChanged()
        val none = items.isEmpty()
        list.visibility = if (none) GONE else VISIBLE
        empty.visibility = if (none) VISIBLE else GONE
        empty.text = if (q.isNotEmpty()) "Nichts gefunden." else "Leer. Unten eine Notiz oder einen Ordner anlegen."
    }

    /** Zurück: erst Suche leeren, dann einen Ordner höher. */
    fun goUp(): Boolean {
        if (search.text.isNotEmpty()) {
            search.setText("")
            return true
        }
        if (dir != store.root) {
            dir = dir.parentFile ?: store.root
            refresh()
            return true
        }
        return false
    }

    private fun newNote() {
        askText(context, "Neue Notiz", "") { name ->
            val f = store.createNote(dir, name)
            if (f == null) {
                toast("Name leer oder schon vergeben")
            } else {
                search.setText("")
                refresh()
                onOpen(f)
            }
        }
    }

    private fun newFolder() {
        askText(context, "Neuer Ordner", "") { name ->
            if (store.createFolder(dir, name) == null) toast("Name leer oder schon vergeben")
            search.setText("")
            refresh()
        }
    }

    private fun itemMenu(f: File) {
        AlertDialog.Builder(context)
            .setTitle(if (f.isDirectory) f.name else f.nameWithoutExtension)
            .setItems(arrayOf("Umbenennen", "Löschen")) { _, which ->
                if (which == 0) rename(f) else confirmDelete(f)
            }
            .show()
    }

    private fun rename(f: File) {
        askText(context, "Umbenennen", if (f.isDirectory) f.name else f.nameWithoutExtension) { name ->
            onRemoved(f)
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
                onRemoved(f)
                store.delete(f)
                refresh()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}
