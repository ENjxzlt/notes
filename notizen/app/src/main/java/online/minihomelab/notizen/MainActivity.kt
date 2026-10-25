package online.minihomelab.notizen

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: NoteStore
    private lateinit var tree: TreePane
    private lateinit var editor: EditorPane
    private var wide = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(File(filesDir, "notizen"))
        // Breites Tablet: Baum links, Editor rechts. Schmal: eins nach dem anderen.
        wide = resources.configuration.screenWidthDp >= 720

        tree = TreePane(this, store, ::openNote, ::onRemoved, ::moreMenu)
        editor = EditorPane(this, store, showBack = !wide, onBack = ::showTree)

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (wide) {
            root.addView(tree, LinearLayout.LayoutParams(dp(340), MATCH_PARENT))
            root.addView(hairline(this, true))
            root.addView(editor, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        } else {
            root.addView(tree, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            editor.visibility = View.GONE
        }
        setContentView(root)
        Updater.check(this)

        if (wide) {
            val last = getPreferences(MODE_PRIVATE).getString("last", null)?.let { File(it) }
            if (last != null && last.isFile) {
                tree.dir = last.parentFile ?: store.root
                openNote(last)
            }
        }
    }

    private fun openNote(f: File) {
        editor.open(f)
        tree.selected = f
        tree.refresh()
        getPreferences(MODE_PRIVATE).edit().putString("last", f.path).apply()
        if (!wide) {
            tree.visibility = View.GONE
            editor.visibility = View.VISIBLE
        }
    }

    private fun moreMenu() {
        AlertDialog.Builder(this)
            .setTitle("Notizen · Build ${Updater.installedVersion(this)}")
            .setItems(arrayOf("Nach Updates suchen", "GitHub-Token für Updates")) { _, which ->
                if (which == 0) Updater.check(this, manual = true) else Updater.askToken(this)
            }
            .show()
    }

    private fun showTree() {
        editor.flush()
        if (!wide) {
            editor.visibility = View.GONE
            tree.visibility = View.VISIBLE
        }
    }

    private fun onRemoved(f: File) {
        editor.closeIfInside(f)
        if (tree.selected?.path?.startsWith(f.path) == true) tree.selected = null
        if (!wide && editor.file == null) showTree()
    }

    override fun onPause() {
        editor.flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!wide && editor.visibility == View.VISIBLE) {
            showTree()
        } else if (!tree.goUp()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
