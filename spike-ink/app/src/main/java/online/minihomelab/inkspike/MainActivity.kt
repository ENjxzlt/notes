package online.minihomelab.inkspike

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ink = InkView(this)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun add(label: String, action: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        add("Stift") { ink.tool = Tool.PEN }
        add("Textmarker") { ink.tool = Tool.MARKER }
        add("Radierer") { ink.tool = Tool.ERASER }
        add("Schwarz") { ink.color = Color.BLACK }
        add("Blau") { ink.color = Color.rgb(0x1F, 0x4E, 0xD8) }
        add("Rot") { ink.color = Color.rgb(0xD1, 0x2B, 0x2B) }
        add("Gelb") { ink.color = Color.rgb(0xFF, 0xD5, 0x00) }
        add("Rückgängig") { ink.undo() }
        add("Alles löschen") { ink.clear() }

        val scroll = HorizontalScrollView(this).apply { addView(bar) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ink, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }
}
