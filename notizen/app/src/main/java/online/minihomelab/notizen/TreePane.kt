package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

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

    /** Karte mit der laufenden/nächsten Unterrichtsstunde: Tippen öffnet den Fach-Ordner. */
    private val card = TextView(ctx).apply {
        textSize = 14f
        setTextColor(Ui.ACCENT)
        setBackgroundColor(Ui.SELECT)
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        visibility = GONE
        setOnClickListener { onLesson(false) }
        setOnLongClickListener { onLesson(true); true }
    }

    private val search = EditText(ctx).apply {
        hint = "Titel suchen"
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        textSize = 16f
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        background = null
    }

    private val path = TextView(ctx).apply {
        textSize = 13f
        setTextColor(Ui.MUTED)
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        setOnClickListener { goUp() }
    }

    private val empty = TextView(ctx).apply {
        text = "Leer. Unten eine Notiz oder einen Ordner anlegen."
        setTextColor(Ui.MUTED)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(24), ctx.dp(32), ctx.dp(24), ctx.dp(32))
    }

    private val list = ListView(ctx).apply {
        divider = null
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = items[position]
            val tv = (convertView as? TextView) ?: TextView(parent.context).apply {
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = parent.context.dp(52)
                setPadding(parent.context.dp(16), 0, parent.context.dp(16), 0)
            }
            val searching = search.text.isNotBlank()
            tv.text = when {
                f.isDirectory -> "${f.name}  ›"
                searching -> store.rel(f)
                else -> f.nameWithoutExtension
            }
            tv.setTypeface(null, if (f.isDirectory) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(Ui.TEXT)
            tv.setBackgroundColor(if (f == selected) Ui.SELECT else 0)
            return tv
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFFFFFFF.toInt())

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hairline(ctx, false))
        addView(path, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(hairline(ctx, false))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(hairline(ctx, false))

        val buttons = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(flatButton(ctx, "Neue Notiz") { newNote() }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(flatButton(ctx, "Neuer Ordner") { newFolder() }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(flatButton(ctx, "Mehr") { onMore() }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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

    fun setLesson(text: String?) {
        card.text = text?.let { "$it  ›" }
        card.visibility = if (text == null) GONE else VISIBLE
    }

    /** Springt in einen Ordner (leert die Suche). */
    fun openDir(f: File) {
        dir = f
        search.setText("")
        refresh()
    }

    fun refresh() {
        val q = search.text.toString().trim()
        items = if (q.isEmpty()) store.list(dir) else store.search(q)
        path.text = when {
            q.isNotEmpty() -> "Suchergebnisse"
            dir == store.root -> "Notizen"
            else -> "‹  " + store.rel(File(dir, "x.md")).removeSuffix(" › x")
        }
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
