package online.minihomelab.notizen

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Schnellwechsel (Strg+K oder Feld in der Seitenleiste): Notizen, Ordner und "Neue Notiz" in einem Feld.
 * Pfeile wählen, Enter öffnet, Strg+Enter legt neu an, Tab wechselt den Bereich, "/" sucht nur Ordner.
 */
class QuickSwitcher(
    private val act: Activity,
    private val vault: Vault,
    private val host: Host,
    private val folderHint: () -> String,
) {
    interface Host {
        fun openNote(path: String)
        fun go(s: Screen)
        fun create(path: String)
    }

    private val scopes = listOf("", "Schule", "Projekte", "Alltag")
    private var scope = ""
    private var items: List<Nav.Hit> = emptyList()
    private var sel = 0
    private var dialog: Dialog? = null

    val isShowing get() = dialog?.isShowing == true

    private fun Int.dp() = act.dp(this)

    fun show() {
        if (isShowing) return
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val input = EditText(act).apply {
            hint = "Notiz, Ordner oder neue Notiz"
            setHintTextColor(Ui.FAINT)
            setTextColor(Ui.TEXT)
            typeface = Ui.sans
            textSize = 18f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
        }
        val chipViews = ArrayList<TextView>()
        val chips = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp(), 8.dp(), 18.dp(), 8.dp())
        }
        val list = ListView(act).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(0)
            setPadding(8.dp(), 4.dp(), 8.dp(), 8.dp())
            clipToPadding = false
        }

        fun refresh() {
            val notes = vault.notes().filter { it.path != Inbox.FILE }
            val mt = notes.associate { it.path to it.mtime }
            val q = input.text.toString()
            val hits = ArrayList(Nav.search(mt.keys, { mt[it] ?: 0L }, vault::text, q, scope))
            val raw = q.trim()
            if (raw.isNotEmpty() && !raw.startsWith("/")) {
                val base = scope.ifEmpty { folderHint() }
                val rel = raw.replace(Regex("""[\\:*?"<>|]"""), "-").trim('/', ' ').removeSuffix(".md")
                if (rel.isNotEmpty()) hits.add(Nav.Hit(Nav.Kind.CREATE, (if ('/' in raw || base.isEmpty()) rel else "$base/$rel") + ".md", raw))
            }
            items = hits
            sel = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            chipViews.forEachIndexed { i, c -> styleChip(c, scopes[i] == scope) }
            (list.adapter as BaseAdapter).notifyDataSetChanged()
            if (items.isNotEmpty()) list.setSelection(sel)
        }

        fun run(i: Int) {
            val it = items.getOrNull(i) ?: return
            d.dismiss()
            when (it.kind) {
                Nav.Kind.NOTE -> host.openNote(it.path)
                Nav.Kind.FOLDER -> host.go(Screen.Area(it.path))
                Nav.Kind.CREATE -> host.create(it.path)
            }
        }

        scopes.forEach { s ->
            val c = Ui.label(act, s.ifEmpty { "Alle" }, 14f, Ui.TEXT2).apply {
                gravity = Gravity.CENTER
                setPadding(16.dp(), 0, 16.dp(), 0)
                minHeight = 40.dp()
                isClickable = true
                setOnClickListener { scope = s; sel = 0; refresh(); input.requestFocus() }
            }
            chipViews.add(c)
            chips.addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, 40.dp()).apply { rightMargin = 8.dp() })
        }
        chips.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        chips.addView(Ui.label(act, "Tab wechselt den Bereich", 12f, Ui.MUTED))

        list.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(items[position], position == sel)
        }
        list.setOnItemClickListener { _, _, position, _ -> run(position) }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { sel = 0; refresh() }
        })
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { run(sel); true } else false }
        input.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { sel = (sel + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)); refresh(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { sel = (sel - 1).coerceAtLeast(0); refresh(); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (ev.isCtrlPressed) items.indexOfFirst { it.kind == Nav.Kind.CREATE }.let { if (it >= 0) run(it) } else run(sel)
                    true
                }
                KeyEvent.KEYCODE_TAB -> {
                    val step = if (ev.isShiftPressed) scopes.size - 1 else 1
                    scope = scopes[(scopes.indexOf(scope) + step) % scopes.size]
                    sel = 0
                    refresh()
                    true
                }
                KeyEvent.KEYCODE_ESCAPE -> { d.dismiss(); true }
                else -> false
            }
        }

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(act, Ui.SURFACE, 14, Ui.BORDER_STRONG)
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp(), 0, 18.dp(), 0)
                addView(Ui.icon(act, R.drawable.ic_search, Ui.MUTED, 20))
                addView(input, LinearLayout.LayoutParams(0, 56.dp(), 1f).apply { leftMargin = 12.dp() })
                addView(Ui.label(act, "Esc", 11f, Ui.MUTED, Ui.mono).apply {
                    setPadding(6.dp(), 1.dp(), 6.dp(), 1.dp())
                    background = Ui.round(act, Ui.SURFACE, 4, Ui.BORDER_STRONG)
                })
            })
            addView(hairline(act, false))
            addView(chips)
            addView(hairline(act, false))
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(hairline(act, false))
            addView(Ui.label(act, "↑↓ wählen   ↵ öffnen   / nur Ordner   Strg ↵ neu anlegen", 12f, Ui.MUTED).apply { setPadding(18.dp(), 10.dp(), 18.dp(), 10.dp()) })
        }
        d.setContentView(root, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = minOf(act.resources.displayMetrics.widthPixels - 32.dp(), 680.dp())
            val h = minOf(act.resources.displayMetrics.heightPixels - 140.dp(), 560.dp())
            setLayout(w, h)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = 72.dp(); dimAmount = 0.45f }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        refresh()
        d.show()
        input.requestFocus()
    }

    private fun styleChip(c: TextView, on: Boolean) {
        c.setTextColor(if (on) Ui.ACCENT else Ui.TEXT2)
        c.typeface = if (on) Ui.sansMedium else Ui.sans
        c.background = Ui.ripple(act, Ui.round(act, if (on) Ui.ACCENT_SOFT else Ui.SURFACE, 20, if (on) Ui.ACCENT else Ui.BORDER_STRONG), 20)
    }

    private fun row(h: Nav.Hit, selected: Boolean): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 56.dp()
        setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp())
        background = if (selected) Ui.round(act, Ui.ACCENT_SOFT, 8) else null
        addView(Ui.label(act, when (h.kind) { Nav.Kind.CREATE -> "+"; Nav.Kind.FOLDER -> "▸"; else -> "·" }, 18f, Ui.ACCENT, Ui.sansSemi).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(24.dp(), WRAP_CONTENT))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val title: CharSequence = when {
                h.kind == Nav.Kind.CREATE -> "Neue Notiz „${h.title}“ anlegen"
                h.parts != null -> SpannableString(h.title).apply {
                    val (pre, hit, _) = h.parts
                    setSpan(ForegroundColorSpan(Ui.ACCENT), pre.length, pre.length + hit.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(FontSpan(Ui.sansSemi), pre.length, pre.length + hit.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> h.title
            }
            addView(Ui.label(act, title, 15f, Ui.TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(Ui.label(act, h.path, 12f, Ui.MUTED, Ui.mono).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (h.via == "text") addView(Ui.label(act, "im Text", 12f, Ui.MUTED), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp() })
        if (h.kind == Nav.Kind.CREATE) addView(Ui.label(act, "Strg ↵", 11f, Ui.MUTED, Ui.mono), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp() })
        else {
            if (h.area.isNotEmpty()) addView(Ui.label(act, h.area, 12f, Ui.MUTED), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp() })
            addView(Ui.label(act, if (h.kind == Nav.Kind.FOLDER) "${h.count} Notizen" else Nav.whenText(h.mtime), 12f, Ui.MUTED, Ui.mono), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8.dp() })
        }
    }
}
