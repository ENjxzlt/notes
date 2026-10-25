package online.minihomelab.notizen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Stundenplan aus Slotwise (das ihn von vpmobil24 holt). Verbunden wird mit dem Abo-Link aus
 * Slotwise → Einstellungen → Kalender ("…/ical/<Token>.ics"); der Token reicht auch für /school/<Token>.json.
 * Die Stunden werden zwischengespeichert, damit die Karte auch offline (WLAN in der Schule) funktioniert.
 */
object SchoolPlan {
    class Lesson(
        val start: Long,
        val end: Long,
        val period: Int,
        val subject: String,
        val teacher: String,
        val room: String,
        val changed: Boolean,
        val cancelled: Boolean,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notizen-school", Context.MODE_PRIVATE)

    fun isConnected(ctx: Context) = !prefs(ctx).getString("token", "").isNullOrEmpty()

    /** Abo-Link einlesen: Basisadresse und Token. */
    fun parseLink(link: String): Pair<String, String>? {
        val m = Regex("^(https?://[^/]+)(?:/.*)?/ical/([A-Za-z0-9_-]+)(?:\\.ics)?/?$").find(link.trim()) ?: return null
        return Pair(m.groupValues[1], m.groupValues[2])
    }

    fun connect(activity: Activity, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            setText(prefs(activity).let { p -> p.getString("base", "")?.let { b -> if (b.isEmpty()) "" else "$b/ical/${p.getString("token", "")}.ics" } })
            hint = "https://…/ical/….ics"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val box = FrameLayout(activity).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("Stundenplan von Slotwise")
            .setMessage("Kopiere in Slotwise unter Einstellungen → Kalender den Abo-Link („Slotwise abonnieren“) und füge ihn hier ein. In Slotwise muss unter „Schulplan“ dein vpmobil24-Zugang eingetragen sein.")
            .setView(box)
            .setPositiveButton("Verbinden") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    prefs(activity).edit().remove("token").remove("base").remove("cache").apply()
                    onDone()
                    return@setPositiveButton
                }
                val parsed = parseLink(text.replace(Regex("^webcals?://"), "https://"))
                if (parsed == null) {
                    toast(activity, "Das ist kein Slotwise-Abo-Link")
                } else {
                    prefs(activity).edit().putString("base", parsed.first).putString("token", parsed.second).apply()
                    refresh(activity, manual = true, onDone)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Stunden der nächsten 7 Tage laden und zwischenspeichern. */
    fun refresh(activity: Activity, manual: Boolean = false, onDone: () -> Unit) {
        val p = prefs(activity)
        val base = p.getString("base", "") ?: ""
        val token = p.getString("token", "") ?: ""
        if (base.isEmpty() || token.isEmpty()) return
        val today = LocalDate.now()
        val url = "$base/school/$token.json?from=$today&to=${today.plusDays(7)}"
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "notizen-app")
                try {
                    if (c.responseCode != 200) throw RuntimeException("Slotwise antwortet mit HTTP ${c.responseCode}")
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    p.edit().putString("cache", body).apply()
                    val warnings = json.optJSONArray("warnings")
                    activity.runOnUiThread {
                        onDone()
                        if (manual) {
                            val n = json.optJSONArray("lessons")?.length() ?: 0
                            val warn = if (warnings != null && warnings.length() > 0) "\n${warnings.getString(0)}" else ""
                            toast(activity, "$n Stunden geladen$warn")
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                if (manual) activity.runOnUiThread { toast(activity, "Stundenplan laden fehlgeschlagen: ${e.message}") }
            }
        }.start()
    }

    fun lessons(ctx: Context): List<Lesson> {
        val cached = prefs(ctx).getString("cache", null) ?: return emptyList()
        return try {
            val arr = JSONObject(cached).getJSONArray("lessons")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Lesson(
                    start = Instant.parse(o.getString("start")).toEpochMilli(),
                    end = Instant.parse(o.getString("end")).toEpochMilli(),
                    period = o.optInt("period", 0),
                    subject = o.optString("subject"),
                    teacher = o.optString("teacher"),
                    room = o.optString("room"),
                    changed = o.optBoolean("changed"),
                    cancelled = o.optBoolean("cancelled"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Laufende oder nächste Stunde von heute (Entfall übersprungen); null, wenn heute nichts mehr kommt. */
    fun upcoming(ctx: Context, now: Long = System.currentTimeMillis()): Pair<Lesson, Boolean>? {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val next = lessons(ctx)
            .filter { !it.cancelled && it.end > now && Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == today }
            .minByOrNull { it.start } ?: return null
        return Pair(next, next.start <= now)
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun label(l: Lesson, running: Boolean): String {
        val head = if (running) "Jetzt" else "Als Nächstes ${timeFmt.format(Instant.ofEpochMilli(l.start))}"
        val period = if (l.period > 0) " · ${l.period}. Std" else ""
        val room = if (l.room.isNotEmpty()) " · ${l.room}" else ""
        val changed = if (l.changed) " (Vertretung)" else ""
        return "$head$period · ${l.subject}$room$changed"
    }

    // ---- Zuordnung Fach-Kürzel → Ordner ----

    private fun mapping(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString("folders", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun folderFor(ctx: Context, store: NoteStore, subject: String): File? {
        val rel = mapping(ctx).optString(subject, "")
        if (rel.isEmpty()) return null
        val f = File(store.root, rel)
        return if (f.isDirectory) f else null
    }

    fun setFolder(ctx: Context, store: NoteStore, subject: String, folder: File) {
        val m = mapping(ctx)
        m.put(subject, folder.relativeTo(store.root).path)
        prefs(ctx).edit().putString("folders", m.toString()).apply()
    }

    /** Fragt nach dem Ordner für ein Fach (einmalig) und ruft danach [onChosen] auf. */
    fun chooseFolder(activity: Activity, store: NoteStore, subject: String, onChosen: (File) -> Unit) {
        val folders = store.root.walkTopDown().maxDepth(3).filter { it.isDirectory && it != store.root }
            .sortedBy { it.path.lowercase() }.toList()
        val names = folders.map { store.rel(File(it, "x.md")).removeSuffix(" › x") } + "Neuer Ordner für „$subject“ …"
        AlertDialog.Builder(activity)
            .setTitle("Ordner für $subject")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < folders.size) {
                    setFolder(activity, store, subject, folders[which])
                    onChosen(folders[which])
                } else {
                    askText(activity, "Neuer Ordner für $subject", subject) { name ->
                        val parent = File(store.root, "Schule").takeIf { it.isDirectory } ?: store.root
                        val created = store.createFolder(parent, name)
                            ?: File(parent, store.cleanName(name)).takeIf { it.isDirectory }
                        if (created == null) {
                            toast(activity, "Name leer oder ungültig")
                        } else {
                            setFolder(activity, store, subject, created)
                            onChosen(created)
                        }
                    }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
