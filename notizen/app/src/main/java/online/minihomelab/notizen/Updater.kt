package online.minihomelab.notizen

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-App-Updates über GitHub Releases (Workflow notizen.yml veröffentlicht je Build ein Release "notizen-<Nr>").
 * Die Build-Nummer ist zugleich der versionCode. Installiert wird über den Paketinstaller von Android,
 * der Nutzer bestätigt im System-Dialog. Das Repo ist privat: ein GitHub-Token (nur Lesen, Contents) wird
 * einmal eingetragen und liegt nur im App-Speicher. Ist das Repo öffentlich, geht es ohne Token.
 */
object Updater {
    private const val OWNER = "ENjxzlt"
    private const val REPO = "notes"
    private const val TAG_PREFIX = "notizen-"
    private const val ASSET = "notizen.apk"
    private const val CHECK_INTERVAL = 6 * 3_600_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notizen-update", Context.MODE_PRIVATE)
    private fun token(ctx: Context) = prefs(ctx).getString("token", "") ?: ""

    @Suppress("DEPRECATION")
    fun installedVersion(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    /** Auf dem Main-Thread aufrufen. manual = vom Nutzer ausgelöst (dann auch Rückmeldung, wenn aktuell). */
    fun check(activity: Activity, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!manual && now - prefs(activity).getLong("checked", 0) < CHECK_INTERVAL) return
        val tok = token(activity)
        Thread {
            val release = try {
                latestRelease(tok)
            } catch (e: Exception) {
                if (manual) activity.runOnUiThread { toast(activity, "Update-Prüfung fehlgeschlagen: ${e.message}") }
                return@Thread
            }
            prefs(activity).edit().putLong("checked", now).apply()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (release != null && release.number > installedVersion(activity)) offer(activity, release)
                else if (manual) toast(activity, "Notizen ist aktuell (Build ${installedVersion(activity)})")
            }
        }.start()
    }

    private class Release(val number: Long, val name: String, val assetUrl: String, val size: Long)

    private fun latestRelease(token: String): Release? {
        val body = httpGet("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30", token, "application/vnd.github+json")
        val arr = JSONArray(body)
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft") || !tag.startsWith(TAG_PREFIX)) continue
            val n = tag.removePrefix(TAG_PREFIX).toLongOrNull() ?: continue
            if (best != null && n <= best.number) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a: JSONObject = assets.getJSONObject(j)
                if (a.optString("name") == ASSET) {
                    best = Release(n, r.optString("name", tag), a.getString("url"), a.optLong("size"))
                }
            }
        }
        return best
    }

    private fun offer(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Update verfügbar")
            .setMessage("${release.name} ist verfügbar (installiert: Build ${installedVersion(activity)}).")
            .setPositiveButton("Installieren") { _, _ -> download(activity, release) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun download(activity: Activity, release: Release) {
        // Einmalig: die App darf Apps installieren
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Installation erlauben")
                .setMessage("Damit sich die App selbst aktualisieren kann, erlaube im nächsten Schritt „Apps aus dieser Quelle installieren“. Danach erneut nach Updates suchen.")
                .setPositiveButton("Weiter") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                    )
                    prefs(activity).edit().putLong("checked", 0).apply()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
            return
        }
        val progress = AlertDialog.Builder(activity)
            .setTitle("Update wird geladen")
            .setMessage("0 %")
            .setCancelable(false)
            .show()
        val file = File(activity.cacheDir, "notizen-update.apk")
        val tok = token(activity)
        Thread {
            try {
                httpDownload(release.assetUrl, tok, file, release.size) { pct ->
                    activity.runOnUiThread { progress.setMessage("$pct %") }
                }
                install(activity, file)
                activity.runOnUiThread { progress.dismiss() }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    progress.dismiss()
                    toast(activity, "Update fehlgeschlagen: ${e.message}")
                }
            }
        }.start()
    }

    /** Token eintragen oder ändern (leer = entfernen). */
    fun askToken(activity: Activity) {
        val input = EditText(activity).apply {
            setText(token(activity))
            setSingleLine()
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = FrameLayout(activity).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("GitHub-Token für Updates")
            .setMessage("Das Repo ist privat. Erzeuge auf github.com unter Settings → Developer settings → Fine-grained tokens ein Token nur für das Repo „notes“ mit Berechtigung Contents: Read-only. Es bleibt auf diesem Gerät. Bei öffentlichem Repo leer lassen.")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                prefs(activity).edit().putString("token", input.text.toString().trim()).putLong("checked", 0).apply()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun connect(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "notizen-app")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun httpGet(url: String, token: String, accept: String): String {
        val c = connect(url, token, accept)
        try {
            val code = c.responseCode
            if (code == 404 || code == 401 || code == 403) {
                throw IOException("Kein Zugriff (HTTP $code). Token prüfen oder Repo öffentlich machen.")
            }
            if (code != 200) throw IOException("GitHub antwortet mit HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun httpDownload(url: String, token: String, out: File, size: Long, progress: (Int) -> Unit) {
        var c = connect(url, token, "application/octet-stream")
        try {
            var code = c.responseCode
            if (code in 300..399) {
                // Weiterleitung zum Datei-Speicher: dort darf kein GitHub-Token mitgeschickt werden
                val loc = c.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                c.disconnect()
                c = URL(loc).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                code = c.responseCode
            }
            if (code != 200) throw IOException("Download: HTTP $code")
            val total = if (size > 0) size else c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                progress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** APK an den Paketinstaller übergeben; Android zeigt danach den Bestätigungsdialog. */
    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("notizen.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

/** Rückmeldung des Paketinstallers: Bestätigung anzeigen bzw. Fehler melden. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unbekannter Fehler"
                Toast.makeText(ctx, "Update fehlgeschlagen: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
