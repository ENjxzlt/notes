package online.minihomelab.notizen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var store: NoteStore
    private lateinit var tree: TreePane
    private lateinit var editor: EditorPane
    private var wide = false
    private var started = false
    private var currentLesson: SchoolPlan.Lesson? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateCard()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasAccess()) startApp() else showAccessScreen()
    }

    override fun onResume() {
        super.onResume()
        // Zurück aus den Systemeinstellungen, in denen der Zugriff erlaubt wurde
        if (!started && hasAccess()) startApp()
        if (started) startTicker()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (SchoolPlan.isConnected(this)) SchoolPlan.refresh(this, onDone = ::updateCard)
    }

    private fun updateCard() {
        if (!started) return
        val next = if (SchoolPlan.isConnected(this)) SchoolPlan.upcoming(this) else null
        currentLesson = next?.first
        tree.setLesson(next?.let { SchoolPlan.label(it.first, it.second) })
    }

    /** Karte angetippt: Fach-Ordner öffnen; beim ersten Mal (oder langem Drücken) Ordner zuordnen. */
    private fun onLessonCard(longPress: Boolean) {
        val lesson = currentLesson ?: return
        val folder = SchoolPlan.folderFor(this, store, lesson.subject)
        if (longPress || folder == null) {
            SchoolPlan.chooseFolder(this, store, lesson.subject) { openFolder(it) }
        } else {
            openFolder(folder)
        }
    }

    private fun openFolder(f: File) {
        tree.openDir(f)
        if (!wide) showTree()
    }

    // ---- Speicherzugriff: Notizen liegen als normale Dateien im Ordner "Notizen" des Gerätespeichers ----

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!started && hasAccess()) startApp()
    }

    private fun showAccessScreen() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        box.addView(TextView(this).apply {
            text = "Zugriff auf den Gerätespeicher"
            textSize = 20f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "Deine Notizen liegen als normale .md-Dateien im Ordner „Notizen“ im Gerätespeicher. " +
                "Sie bleiben beim Deinstallieren erhalten und lassen sich mit anderen Apps nutzen " +
                "(Dateien, Syncthing, Termux). Dafür braucht die App Zugriff auf alle Dateien."
            textSize = 15f
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        }, LinearLayout.LayoutParams(dp(480), WRAP_CONTENT))
        box.addView(Button(this).apply {
            text = "Zugriff erlauben"
            isAllCaps = false
            setOnClickListener { requestAccess() }
        })
        setContentView(box)
    }

    // ---- App ----

    private fun startApp() {
        started = true
        store = NoteStore(File(Environment.getExternalStorageDirectory(), "Notizen"))
        migrateOldNotes()
        // Breites Tablet: Baum links, Editor rechts. Schmal: eins nach dem anderen.
        wide = resources.configuration.screenWidthDp >= 720

        tree = TreePane(this, store, ::openNote, ::onRemoved, ::moreMenu, ::onLessonCard)
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

    /** Frühere Versionen speicherten im App-Speicher (filesDir/notizen): einmalig in den Ordner kopieren. */
    private fun migrateOldNotes() {
        val old = File(filesDir, "notizen")
        if (!old.isDirectory) return
        val hadNotes = old.walkTopDown().any { it.isFile && it.extension == "md" }
        old.copyRecursively(store.root, overwrite = false) { _, _ -> kotlin.io.OnErrorAction.SKIP }
        // Als Sicherheitskopie im App-Speicher stehen lassen, aber nicht nochmal kopieren
        old.renameTo(File(filesDir, "notizen-migriert"))
        if (hadNotes) Toast.makeText(this, "Notizen in den Ordner „Notizen“ verschoben", Toast.LENGTH_LONG).show()
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
        // Achtung: setMessage und setItems schließen sich in AlertDialog aus (die Einträge würden nicht angezeigt)
        AlertDialog.Builder(this)
            .setTitle("Notizen · Build ${Updater.installedVersion(this)}")
            .setItems(
                arrayOf(
                    "Nach Updates suchen",
                    "Stundenplan von Slotwise verbinden",
                    "Ordner: ${store.root.path}",
                ),
            ) { _, which ->
                when (which) {
                    0 -> Updater.check(this, manual = true)
                    1 -> SchoolPlan.connect(this) { updateCard() }
                }
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
        handler.removeCallbacks(ticker)
        if (started) editor.flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!started) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else if (!wide && editor.visibility == View.VISIBLE) {
            showTree()
        } else if (!tree.goUp()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
