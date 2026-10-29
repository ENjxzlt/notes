package online.minihomelab.notizen

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.util.Locale

class MainActivity : Activity(), SidePane.Host, DashPane.Host, QuickSwitcher.Host {
    private lateinit var store: NoteStore
    private lateinit var vault: Vault
    private var tree: TreePane? = null // schmal: Ordnerliste wie bisher
    private var side: SidePane? = null // breit (Tablet): Seitenleiste wie am PC
    private var dash: DashPane? = null
    private lateinit var editor: EditorPane
    private var quick: QuickSwitcher? = null
    private var wide = false
    private var started = false
    private lateinit var frame: FrameLayout
    private var inkScreen: InkScreen? = null
    private var inkDir: File? = null
    private var inkId: String? = null
    private var currentLesson: SchoolPlan.Lesson? = null
    private var lessonCard: SchoolPlan.Card? = null
    private var tick = 0
    private val syncRunnable = Runnable { syncNow(false) }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateCard()
            if (++tick % 5 == 0) syncNow(false) // alle 5 Minuten
            handler.postDelayed(this, 60_000)
        }
    }

    // Breites Layout: was gerade gezeigt wird, Pins, Verlauf, Zurück/Vor
    private var screen: Screen = Screen.Overview
    private var noteOpen: String? = null
    private var pins: List<String> = emptyList()
    private var recents: List<String> = emptyList()
    private val hist = ArrayList<Pair<Char, String>>() // ('n', Notiz) oder ('a', Bereich/Screen-Name)
    private var hi = -1
    private var navLock = false
    private var dictateCallback: ((String) -> Unit)? = null
    private lateinit var pinBtn: ImageView
    private lateinit var prevBtn: ImageView
    private lateinit var nextBtn: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        if (hasAccess()) startApp() else showAccessScreen()
    }

    override fun onResume() {
        super.onResume()
        // Zurück aus den Systemeinstellungen, in denen der Zugriff erlaubt wurde
        if (!started && hasAccess()) startApp()
        if (started) startTicker()
    }

    /** Fenster vergrößert/verkleinert (Tablet, Fenstermodus): Layout neu aufbauen, wenn sich breit/schmal ändert. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (started && inkScreen == null && (newConfig.screenWidthDp >= WIDE_DP) != wide) buildLayout()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (SchoolPlan.isConnected(this)) SchoolPlan.refresh(this, onDone = ::updateCard)
        syncNow(false)
        startLive()
    }

    /** Live-Sync nur im Vordergrund: der Server meldet Änderungen sofort. */
    private fun startLive() {
        Live.stop()
        if (Sync.isConnected(this)) Live.start(this) { scheduleSync(300) }
    }

    /** Notizen mit dem Server abgleichen; nach dem Speichern verzögert, damit nicht jeder Tastendruck zählt. */
    private fun syncNow(manual: Boolean) {
        if (!started || !Sync.isConnected(this)) return
        editor.flush()
        Sync.run(this, store) { res ->
            refreshLists()
            editor.reloadIfChanged()
            if (manual) {
                Toast.makeText(this, if (res.error != null) "Sync fehlgeschlagen: ${res.error}" else Sync.lastStatus(this), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun scheduleSync(delayMs: Long = 2_000) {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, delayMs)
    }

    /** Listen und Zähler neu zeichnen (nach Sync, Anlegen, Löschen). */
    private fun refreshLists() {
        tree?.refresh()
        side?.refresh()
        if (noteOpen == null) dash?.refresh()
    }

    private fun updateCard() {
        if (!started) return
        val next = if (SchoolPlan.isConnected(this)) SchoolPlan.upcoming(this) else null
        currentLesson = next?.first
        lessonCard = next?.let { SchoolPlan.card(it.first, it.second) }
        tree?.setLesson(lessonCard)
        side?.setLesson(lessonCard)
    }

    /** Karte angetippt: Fach-Ordner öffnen; beim ersten Mal (oder langem Drücken) Ordner zuordnen. */
    override fun lesson(longPress: Boolean) = onLessonCard(longPress)

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
        if (wide) {
            go(Screen.Area(f.relativeTo(store.root).path.replace(File.separatorChar, '/')))
        } else {
            tree?.openDir(f)
            showTree()
        }
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
            setBackgroundColor(Ui.SURFACE)
        }
        box.addView(Ui.label(this, "Zugriff auf den Gerätespeicher", 22f, Ui.TEXT, Ui.sansSemi).apply {
            gravity = Gravity.CENTER
        })
        box.addView(
            Ui.label(
                this,
                "Deine Notizen liegen als normale .md-Dateien im Ordner „Notizen“ im Gerätespeicher. " +
                    "Sie bleiben beim Deinstallieren erhalten und lassen sich mit anderen Apps nutzen " +
                    "(Dateien, Syncthing, Termux). Dafür braucht die App Zugriff auf alle Dateien.",
                15f, Ui.MUTED,
            ).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(16), 0, dp(24))
            },
            LinearLayout.LayoutParams(dp(480), WRAP_CONTENT),
        )
        box.addView(Ui.primaryButton(this, "Zugriff erlauben", null) { requestAccess() }, LinearLayout.LayoutParams(dp(240), dp(48)))
        setContentView(box)
    }

    // ---- App ----

    private fun startApp() {
        started = true
        store = NoteStore(File(Environment.getExternalStorageDirectory(), "Notizen"))
        vault = Vault(store)
        migrateOldNotes()
        pins = loadList("pins")
        recents = loadList("recents")
        buildLayout()
        Updater.check(this)
    }

    private fun loadList(key: String): List<String> =
        getPreferences(MODE_PRIVATE).getString(key, "")!!.split('\n').filter { it.isNotEmpty() }

    private fun saveList(key: String, list: List<String>) =
        getPreferences(MODE_PRIVATE).edit().putString(key, list.joinToString("\n")).apply()

    /** (Neu) aufbauen: breit (Tablet) = Seitenleiste + Inhalt wie am PC, schmal = Ordnerliste und Editor nacheinander. */
    private fun buildLayout() {
        if (this::editor.isInitialized) editor.flush()
        val reopen = if (this::editor.isInitialized) editor.file else null
        wide = resources.configuration.screenWidthDp >= WIDE_DP

        editor = EditorPane(this, store, showBack = !wide, onBack = ::showTree, onInk = ::openInk)
        editor.onSaved = { scheduleSync() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        if (wide) {
            tree = null
            side = SidePane(this, vault, this).also {
                it.pins = pins
                it.recents = recents
            }
            dash = DashPane(this, vault, this)
            val content = FrameLayout(this).apply {
                addView(dash, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(editor, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            buildNoteTools()
            editor.onTitleTap = { noteOpen?.let(::showPeers) }
            root.addView(side, LinearLayout.LayoutParams(dp(320), MATCH_PARENT))
            root.addView(hairline(this, true))
            root.addView(content, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        } else {
            side = null
            dash = null
            tree = TreePane(this, store, ::openNote, ::onRemoved, ::moreMenu, ::onLessonCard)
            root.addView(tree, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            editor.visibility = View.GONE
        }
        frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
        quick = QuickSwitcher(this, vault, this) { currentFolder() }
        updateCard()

        if (wide) {
            // Beim Start die Übersicht wie am PC; nur beim Umbau durch eine neue Fenstergröße die offene Notiz wieder zeigen
            if (reopen != null && reopen.isFile) openNote(reopen) else showScreen(screen)
        } else if (reopen != null && reopen.isFile) {
            openNote(reopen)
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

    // ---- Zeichnen ----

    private fun openInk(noteDir: File, id: String) {
        if (inkScreen != null) return
        editor.flush()
        val doc = Ink.load(noteDir, id)
        val screen = InkScreen(
            this, doc,
            onDone = { closeInk(save = true) },
            onDelete = {
                closeInk(save = false)
                editor.removeInk(noteDir, id)
            },
        )
        inkScreen = screen
        inkDir = noteDir
        inkId = id
        frame.addView(screen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun closeInk(save: Boolean) {
        val screen = inkScreen ?: return
        val dir = inkDir ?: return
        val id = inkId ?: return
        if (save) Ink.save(dir, id, screen.doc)
        frame.removeView(screen)
        inkScreen = null
        editor.refreshInk(id)
    }

    // ---- Notizen und Bildschirme öffnen ----

    private fun rel(f: File) = f.relativeTo(store.root).path.replace(File.separatorChar, '/')

    private fun openNote(f: File) {
        editor.open(f)
        getPreferences(MODE_PRIVATE).edit().putString("last", f.path).apply()
        if (wide) {
            val path = rel(f)
            noteOpen = path
            recents = Nav.pushRecent(recents, path)
            saveList("recents", recents)
            dash?.visibility = View.GONE
            editor.visibility = View.VISIBLE
            side?.apply {
                current = path
                this.recents = this@MainActivity.recents
                reveal(path)
                refresh()
            }
            updateNoteTools(path)
            record('n', path)
        } else {
            tree?.selected = f
            tree?.refresh()
            tree?.visibility = View.GONE
            editor.visibility = View.VISIBLE
        }
    }

    override fun openNote(path: String) = openNote(File(store.root, path))

    /** Übersicht, Bereich, Inbox, Aufgaben oder Rückblick im Inhaltsbereich zeigen. */
    private fun showScreen(s: Screen) {
        if (!wide) return
        editor.flush()
        screen = s
        noteOpen = null
        dash?.visibility = View.VISIBLE
        editor.visibility = View.GONE
        dash?.show(s)
        side?.apply {
            current = null
            screen = s
            if (s is Screen.Area) reveal(s.path + "/x")
            refresh()
        }
        record('a', encode(s))
    }

    override fun go(s: Screen) = showScreen(s)

    private fun encode(s: Screen): String = when (s) {
        Screen.Overview -> "ueberblick"
        is Screen.Area -> "area:${s.path}"
        Screen.Ideas -> "ideen"
        Screen.Tasks -> "aufgaben"
        Screen.Review -> "rueckblick"
    }

    private fun decode(code: String): Screen = when {
        code.startsWith("area:") -> Screen.Area(code.removePrefix("area:"))
        code == "ideen" -> Screen.Ideas
        code == "aufgaben" -> Screen.Tasks
        code == "rueckblick" -> Screen.Review
        else -> Screen.Overview
    }

    // ---- Zurück/Vor ----

    private fun record(kind: Char, p: String) {
        if (navLock) return
        if (hi in hist.indices && hist[hi] == (kind to p)) return
        while (hist.size > hi + 1) hist.removeAt(hist.size - 1)
        hist.add(kind to p)
        if (hist.size > 50) hist.removeAt(0)
        hi = hist.size - 1
        side?.setHistory(hi > 0, hi < hist.size - 1)
    }

    private fun step(d: Int) {
        val i = hi + d
        if (i !in hist.indices) return
        val (kind, p) = hist[i]
        if (kind == 'n' && !File(store.root, p).isFile) {
            hist.removeAt(i) // Notiz gibt es nicht mehr
            if (d < 0) hi--
            side?.setHistory(hi > 0, hi < hist.size - 1)
            return step(d)
        }
        navLock = true
        hi = i
        try {
            if (kind == 'n') openNote(File(store.root, p)) else showScreen(decode(p))
        } finally {
            navLock = false
            side?.setHistory(hi > 0, hi < hist.size - 1)
        }
    }

    override fun back() = step(-1)
    override fun forward() = step(1)

    // ---- Kopf der Notiz: Blättern, Anpinnen, Geschwister-Ordner ----

    private fun buildNoteTools() {
        prevBtn = Ui.iconButton(this, R.drawable.ic_chevron_left, "Vorherige Notiz im Ordner", true) { pagerTarget(false)?.let(::openNote) }
        nextBtn = Ui.iconButton(this, R.drawable.ic_chevron_right, "Nächste Notiz im Ordner", true) { pagerTarget(true)?.let(::openNote) }
        pinBtn = Ui.iconButton(this, R.drawable.ic_star, "Anpinnen", true) { togglePin() }
        editor.tools.removeAllViews()
        for (b in listOf(prevBtn, nextBtn, pinBtn)) {
            editor.tools.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        }
    }

    private fun pagerTarget(next: Boolean): String? {
        val p = noteOpen ?: return null
        val pg = Nav.pager(vault.notes().map { it.path }, p)
        return if (next) pg.next else pg.prev
    }

    private fun updateNoteTools(path: String) {
        val pg = Nav.pager(vault.notes().map { it.path }, path)
        prevBtn.alpha = if (pg.prev != null) 1f else 0.3f
        nextBtn.alpha = if (pg.next != null) 1f else 0.3f
        val pinned = path in pins
        pinBtn.setImageResource(if (pinned) R.drawable.ic_star_fill else R.drawable.ic_star)
        pinBtn.setColorFilter(if (pinned) Ui.ACCENT else Ui.TEXT2)
        pinBtn.contentDescription = if (pinned) "Nicht mehr anpinnen" else "Anpinnen"
    }

    private fun togglePin() {
        val p = noteOpen ?: return
        pins = Nav.togglePin(pins, p).second
        saveList("pins", pins)
        side?.pins = pins
        side?.refresh()
        updateNoteTools(p)
    }

    /** Tippen auf den Pfad: Ordner auf derselben Ebene zum direkten Wechsel (wie das Dropdown am PC). */
    private fun showPeers(path: String) {
        val folder = Nav.dirOf(path)
        if (folder.isEmpty()) return
        val peers = Nav.folderPeers(vault.notes().map { it.path }, folder)
        val items = peers.map { (if (it.current) "✓  " else "     ") + it.name + "   (${it.count})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ordner in ${Nav.dirOf(folder).substringAfterLast('/').ifEmpty { "Notizen" }}")
            .setItems(items) { _, which -> showScreen(Screen.Area(peers[which].path)) }
            .show()
    }

    // ---- Schnellwechsel, Neu anlegen, Diktieren ----

    override fun quick() {
        editor.flush()
        quick?.show()
    }

    private fun currentFolder(): String = noteOpen?.let(Nav::dirOf) ?: (screen as? Screen.Area)?.path ?: ""

    override fun create(path: String) {
        val f = File(store.root, path)
        if (!f.exists()) {
            store.write(f, "# ${f.nameWithoutExtension}\n\n")
            scheduleSync(500)
        }
        refreshLists()
        openNote(f)
    }

    private fun currentDir(): File = File(store.root, currentFolder())

    override fun newNote() = newNoteIn(currentDir())

    override fun newFolder() = newFolderIn(currentDir())

    private fun where(dir: File) = if (dir == store.root) "Notizen" else dir.name

    override fun newNoteIn(dir: File) {
        askText(this, "Neue Notiz in ${where(dir)}", "") { name ->
            val f = store.createNote(dir, name)
            if (f == null) toast("Name leer oder schon vergeben") else { refreshLists(); openNote(f); scheduleSync(500) }
        }
    }

    /** Auch verschachtelt möglich ("Schule/Physik/Optik"); der neue Ordner wird geöffnet. */
    override fun newFolderIn(dir: File) {
        askText(this, "Neuer Ordner in ${where(dir)}", "") { name ->
            val parts = name.split('/').map(store::cleanName).filter { it.isNotEmpty() }
            val target = parts.fold(dir) { acc, seg -> File(acc, seg) }
            when {
                parts.isEmpty() -> toast("Name leer")
                target.exists() -> toast("Den Ordner gibt es schon")
                !target.mkdirs() -> toast("Ordner konnte nicht angelegt werden")
                else -> {
                    toast("Ordner angelegt. Zum Server geht er, sobald eine Notiz darin liegt.")
                    refreshLists()
                    if (wide) showScreen(Screen.Area(rel(target)))
                }
            }
        }
    }

    /** Strg+Alt+I: Idee festhalten, wo man gerade ist (landet in der Inbox). */
    private fun captureIdea() {
        askText(this, "Idee festhalten", "") { text ->
            if (vault.addIdea(text)) { toast("Festgehalten"); changed() }
        }
    }

    override fun changed() {
        scheduleSync(800)
        side?.recents = recents
        side?.refresh()
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Diktieren über die Spracherkennung des Geräts (kein Mikrofonzugriff der App selbst nötig). */
    override fun dictate(onText: (String) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMANY.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Idee sprechen")
        dictateCallback = onText
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_DICTATE)
        } catch (e: ActivityNotFoundException) {
            dictateCallback = null
            toast("Keine Spracherkennung auf diesem Gerät gefunden")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DICTATE) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (resultCode == RESULT_OK && !text.isNullOrBlank()) dictateCallback?.invoke(text)
            dictateCallback = null
        }
    }

    /** Tastaturkürzel am Tablet mit Tastatur: Strg+K Schnellwechsel, Strg+N neue Notiz, Alt+←/→ Zurück/Vor. */
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (started && ev.action == KeyEvent.ACTION_DOWN && inkScreen == null && ev.isCtrlPressed && ev.isAltPressed && ev.keyCode == KeyEvent.KEYCODE_I) {
            captureIdea()
            return true
        }
        if (started && wide && ev.action == KeyEvent.ACTION_DOWN && inkScreen == null) {
            when {
                ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_K -> { if (quick?.isShowing != true) quick(); return true }
                ev.isCtrlPressed && ev.keyCode == KeyEvent.KEYCODE_N && quick?.isShowing != true -> { newNote(); return true }
                ev.isAltPressed && ev.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { back(); return true }
                ev.isAltPressed && ev.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { forward(); return true }
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun more() = moreMenu()

    private fun moreMenu() {
        val items = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            items.add(label)
            actions.add(action)
        }
        add("Nach Updates suchen") { Updater.check(this, manual = true) }
        add("Stundenplan von Slotwise verbinden") { SchoolPlan.connect(this) { updateCard() } }
        add("Notizen-Server verbinden") { Sync.configure(this) { syncNow(true); startLive() } }
        if (Sync.isConnected(this)) add("Jetzt synchronisieren (${Sync.lastStatus(this)})") { syncNow(true) }
        add("Ordner: ${store.root.path}") {}
        // Achtung: setMessage und setItems schließen sich in AlertDialog aus (die Einträge würden nicht angezeigt)
        AlertDialog.Builder(this)
            .setTitle("Notizen · Build ${Updater.installedVersion(this)}")
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showTree() {
        editor.flush()
        if (!wide) {
            editor.visibility = View.GONE
            tree?.visibility = View.VISIBLE
        }
    }

    private fun onRemoved(f: File) {
        editor.closeIfInside(f)
        tree?.let { t -> if (t.selected?.path?.startsWith(f.path) == true) t.selected = null }
        if (!wide && editor.file == null) showTree()
        if (wide && editor.file == null && noteOpen != null) showScreen(screen)
    }

    override fun removed(f: File) = onRemoved(f)

    override fun onPause() {
        handler.removeCallbacks(ticker)
        Live.stop()
        // Offene Zeichnung nicht verlieren, wenn die App in den Hintergrund geht
        inkScreen?.let { s -> inkDir?.let { d -> inkId?.let { i -> Ink.save(d, i, s.doc) } } }
        if (started) editor.flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inkScreen != null) {
            closeInk(save = true)
        } else if (!started) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else if (wide) {
            when {
                noteOpen != null -> showScreen(screen)
                hi > 0 -> back()
                else -> @Suppress("DEPRECATION") super.onBackPressed()
            }
        } else if (editor.visibility == View.VISIBLE) {
            showTree()
        } else if (tree?.goUp() != true) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        /** Ab dieser Fensterbreite (dp) gilt das Tablet-Layout mit Seitenleiste. */
        const val WIDE_DP = 600
        private const val REQ_DICTATE = 41
    }
}
