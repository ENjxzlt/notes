package online.minihomelab.notizen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Abgleich mit dem Notizen-Server (siehe server/README.md). Jede Datei hat eine Revision. Wir merken uns je Datei
 * die zuletzt abgeglichene Revision und deren Inhalts-Hash (`.sync-state.json` im Notizen-Ordner):
 *  - nur der Server hat sich geändert → herunterladen
 *  - nur lokal geändert → mit der bekannten Revision hochladen
 *  - beide geändert → die lokale Fassung wird zur Kopie "Name (Konflikt …)", der Server gewinnt am Originalnamen
 * Nichts wird stillschweigend überschrieben; Löschen wird über Grabsteine verteilt.
 */
object Sync {
    class Result(val pulled: Int, val pushed: Int, val conflicts: Int, val error: String?)

    private class Entry(val rev: Long, val hash: String, val mtime: Long, val size: Long)
    private class State(var seq: Long, val files: HashMap<String, Entry>)
    private class Resp(val code: Int, val body: ByteArray, val rev: Long)

    @Volatile private var running = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notizen-sync", Context.MODE_PRIVATE)

    fun isConnected(ctx: Context) = !prefs(ctx).getString("token", "").isNullOrEmpty() && !prefs(ctx).getString("url", "").isNullOrEmpty()

    fun lastStatus(ctx: Context): String = prefs(ctx).getString("status", "noch nicht synchronisiert") ?: ""

    fun configure(activity: Activity, onDone: () -> Unit) {
        val p = prefs(activity)
        val url = EditText(activity).apply {
            setText(p.getString("url", ""))
            hint = "https://…"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(activity).apply {
            setText(p.getString("token", ""))
            hint = "Token"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(url)
            addView(token)
        }
        AlertDialog.Builder(activity)
            .setTitle("Notizen-Server")
            .setMessage("Adresse des Servers (nur HTTPS) und das Token aus seiner .env (NOTES_TOKEN). Beide leer lassen trennt die Verbindung.")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val u = url.text.toString().trim().trimEnd('/')
                val t = token.text.toString().trim()
                if (u.isNotEmpty() && !u.startsWith("https://") && !u.startsWith("http://192.168.") && !u.startsWith("http://10.")) {
                    toast(activity, "Bitte eine https://-Adresse angeben")
                } else {
                    p.edit().putString("url", u).putString("token", t).apply()
                    onDone()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Im Hintergrund abgleichen; [onDone] kommt im Hauptthread. Läuft höchstens einmal gleichzeitig. */
    fun run(activity: Activity, store: NoteStore, onDone: (Result) -> Unit) {
        if (running || !isConnected(activity)) return
        running = true
        val p = prefs(activity)
        val base = p.getString("url", "")!!
        val token = p.getString("token", "")!!
        Thread {
            val res = try {
                syncOnce(store, base, token)
            } catch (e: Exception) {
                Result(0, 0, 0, e.message ?: e.javaClass.simpleName)
            }
            val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
            p.edit().putString(
                "status",
                if (res.error != null) "Fehler um $time: ${res.error}"
                else "zuletzt $time · ${res.pulled} geladen · ${res.pushed} gesendet" + if (res.conflicts > 0) " · ${res.conflicts} Konflikt(e)" else "",
            ).apply()
            running = false
            activity.runOnUiThread { onDone(res) }
        }.start()
    }

    // ---------- Ablauf ----------

    private fun syncOnce(store: NoteStore, base: String, token: String): Result {
        val root = store.root
        val st = loadState(root)
        var pulled = 0
        var pushed = 0
        var conflicts = 0
        try {
            for (pass in 0 until 3) {
                var again = false

                // 1. Änderungen vom Server holen
                val ch = call(base, token, "GET", "/api/changes?since=${st.seq}")
                if (ch.code != 200) throw IOException(errorOf(ch))
                val json = JSONObject(String(ch.body))
                val files = json.getJSONArray("files")
                val local = scan(root)
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val path = f.getString("path")
                    val rev = f.getLong("rev")
                    val lf = local[path]
                    val s = st.files[path]

                    if (f.getBoolean("deleted")) {
                        if (lf != null && s != null && hashOf(lf, s) == s.hash) {
                            lf.delete()
                            pulled++
                        } // lokal danach geändert: behalten, wird unten neu hochgeladen
                        st.files.remove(path)
                        continue
                    }
                    val hash = f.getString("hash")
                    if (s != null && s.rev == rev) continue
                    if (lf == null) {
                        download(base, token, root, path, rev, st)
                        pulled++
                        continue
                    }
                    val h = hashOf(lf, s)
                    when {
                        h == hash -> st.files[path] = Entry(rev, hash, lf.lastModified(), lf.length())
                        s != null && h == s.hash -> {
                            download(base, token, root, path, rev, st)
                            pulled++
                        }
                        else -> {
                            // Beide Seiten geändert: lokale Fassung als Kopie behalten, Server-Fassung übernehmen
                            lf.renameTo(conflictCopy(lf))
                            conflicts++
                            download(base, token, root, path, rev, st)
                            pulled++
                        }
                    }
                }
                st.seq = json.getLong("seq")

                // 2. Lokale Änderungen senden
                val now = scan(root)
                for ((path, lf) in now) {
                    val s = st.files[path]
                    val h = hashOf(lf, s)
                    if (s != null && h == s.hash) {
                        if (lf.lastModified() != s.mtime || lf.length() != s.size) st.files[path] = Entry(s.rev, s.hash, lf.lastModified(), lf.length())
                        continue
                    }
                    val data = lf.readBytes()
                    val r = call(base, token, "PUT", "/api/file?path=${enc(path)}&base=${s?.rev ?: 0}", data)
                    when (r.code) {
                        200 -> {
                            val j = JSONObject(String(r.body))
                            st.files[path] = Entry(j.getLong("rev"), j.getString("hash"), lf.lastModified(), lf.length())
                            pushed++
                        }
                        409 -> again = true // inzwischen auf dem Server geändert: der nächste Durchgang löst es auf
                        else -> throw IOException(errorOf(r))
                    }
                }
                for (path in st.files.keys.toList()) {
                    if (path in now) continue
                    val r = call(base, token, "DELETE", "/api/file?path=${enc(path)}&base=${st.files[path]!!.rev}")
                    when (r.code) {
                        200, 404 -> st.files.remove(path)
                        409 -> again = true
                        else -> throw IOException(errorOf(r))
                    }
                }
                if (!again) break
            }
        } finally {
            saveState(root, st)
        }
        return Result(pulled, pushed, conflicts, null)
    }

    private fun download(base: String, token: String, root: File, path: String, rev: Long, st: State) {
        val r = call(base, token, "GET", "/api/file?path=${enc(path)}")
        if (r.code != 200) throw IOException(errorOf(r))
        val f = File(root, path)
        writeLocal(f, r.body)
        st.files[path] = Entry(if (r.rev >= 0) r.rev else rev, sha256(r.body), f.lastModified(), f.length())
    }

    // ---------- Hilfsfunktionen ----------

    private fun syncable(rel: String): Boolean {
        val parts = rel.split('/')
        if (parts.any { it.startsWith(".") && it != ".ink" }) return false
        val name = parts.last()
        if (name.endsWith(".tmp") || name.endsWith(".defekt")) return false
        return when (name.substringAfterLast('.', "").lowercase()) {
            "md" -> true
            "json", "svg" -> parts.dropLast(1).contains(".ink")
            else -> false
        }
    }

    private fun scan(root: File): Map<String, File> {
        val out = HashMap<String, File>()
        root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") || it.name == ".ink" }.forEach { f ->
            if (f.isFile) {
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                if (syncable(rel)) out[rel] = f
            }
        }
        return out
    }

    private fun conflictCopy(f: File): File {
        val ts = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.GERMANY).format(Date())
        val ext = f.extension
        val stem = f.nameWithoutExtension
        var n = 0
        while (true) {
            val suffix = if (n == 0) "" else " $n"
            val name = if (ext == "md") "$stem (Konflikt $ts$suffix).md" else "$stem.konflikt-${ts.replace(' ', '_')}$suffix.$ext"
            val c = File(f.parentFile, name)
            if (!c.exists()) return c
            n++
        }
    }

    private fun writeLocal(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) {
            f.writeBytes(data)
            tmp.delete()
        }
    }

    /** Hash der lokalen Datei; unverändert seit dem letzten Abgleich (Größe und Zeit gleich) wird der gemerkte genommen. */
    private fun hashOf(f: File, s: Entry?): String =
        if (s != null && f.lastModified() == s.mtime && f.length() == s.size) s.hash else sha256(f.readBytes())

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun enc(path: String) = URLEncoder.encode(path, "UTF-8")

    private fun errorOf(r: Resp): String {
        val msg = try { JSONObject(String(r.body)).optString("error") } catch (e: Exception) { "" }
        return when (r.code) {
            401 -> "Token falsch"
            else -> "Server antwortet ${r.code}${if (msg.isNotEmpty()) ": $msg" else ""}"
        }
    }

    private fun call(base: String, token: String, method: String, pathAndQuery: String, body: ByteArray? = null): Resp {
        val c = URL(base + pathAndQuery).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, bytes, c.getHeaderField("X-Rev")?.toLongOrNull() ?: -1)
        } finally {
            c.disconnect()
        }
    }

    // ---------- Zustand (liegt im Notizen-Ordner und überlebt eine Neuinstallation) ----------

    private fun stateFile(root: File) = File(root, ".sync-state.json")

    private fun loadState(root: File): State {
        val f = stateFile(root)
        val st = State(0, HashMap())
        if (!f.isFile) return st
        try {
            val o = JSONObject(f.readText())
            st.seq = o.optLong("seq", 0)
            val files = o.optJSONObject("files") ?: return st
            for (k in files.keys()) {
                val e = files.getJSONObject(k)
                st.files[k] = Entry(e.getLong("rev"), e.getString("hash"), e.getLong("mtime"), e.getLong("size"))
            }
        } catch (e: Exception) {
            return State(0, HashMap()) // beschädigt: von vorn abgleichen (gleiche Inhalte erzeugen keine Konflikte)
        }
        return st
    }

    private fun saveState(root: File, st: State) {
        val files = JSONObject()
        for ((k, e) in st.files) {
            files.put(k, JSONObject().put("rev", e.rev).put("hash", e.hash).put("mtime", e.mtime).put("size", e.size))
        }
        val f = stateFile(root)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONObject().put("seq", st.seq).put("files", files).toString())
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
