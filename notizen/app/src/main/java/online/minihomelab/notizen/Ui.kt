package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout

object Ui {
    val TEXT = Color.rgb(0x1B, 0x1F, 0x24)
    val MUTED = Color.rgb(0x6B, 0x72, 0x80)
    val LINE = Color.rgb(0xE3, 0xE6, 0xEA)
    val ACCENT = Color.rgb(0x1F, 0x4E, 0xD8)
    val SELECT = Color.rgb(0xE8, 0xEF, 0xFD)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Flache Schaltfläche ohne Großbuchstaben und ohne Fokus-Klau (Tastatur bleibt offen). */
fun flatButton(ctx: Context, label: String, onClick: () -> Unit): Button =
    Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setTextColor(Ui.ACCENT)
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { onClick() }
    }

fun hairline(ctx: Context, vertical: Boolean): View = View(ctx).apply {
    setBackgroundColor(Ui.LINE)
    layoutParams = if (vertical) {
        android.widget.LinearLayout.LayoutParams(1, android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
    } else {
        android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }
}

fun askText(ctx: Context, title: String, initial: String, ok: (String) -> Unit) {
    val input = EditText(ctx).apply {
        setText(initial)
        setSingleLine()
        selectAll()
    }
    val box = FrameLayout(ctx).apply {
        setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
        addView(input)
    }
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> ok(input.text.toString()) }
        .setNegativeButton("Abbrechen", null)
        .create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}
