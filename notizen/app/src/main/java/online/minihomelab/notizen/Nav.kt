package online.minihomelab.notizen

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Navigation und Übersichten ohne Android-Abhängigkeit (gleiche Regeln wie Web-Ansicht und Linux-Client):
 * Suche für den Schnellwechsel, Geschwisterordner, Blättern, Pins/Verlauf, Aktivität, offene Aufgaben.
 * Pfade sind relativ zum Notizen-Ordner und mit "/" getrennt.
 */
object Nav {
    val AREAS = listOf("Schule", "Projekte", "Alltag")
    private val months = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")

    fun titleOf(p: String) = p.substringAfterLast('/').removeSuffix(".md")
    fun dirOf(p: String) = if ('/' in p) p.substringBeforeLast('/') else ""
    private fun key(s: String) = s.lowercase()

    /** Anzahl der Notizen je Ordner, einschließlich aller Unterordner. */
    fun folderCounts(paths: Collection<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (p in paths) {
            val parts = p.split('/').dropLast(1)
            for (i in 1..parts.size) {
                val f = parts.take(i).joinToString("/")
                out[f] = (out[f] ?: 0) + 1
            }
        }
        return out
    }

    data class Peer(val path: String, val name: String, val count: Int, val current: Boolean)

    /** Ordner auf derselben Ebene wie [folder] (er selbst eingeschlossen); oben stehen die festen Bereiche zuerst. */
    fun folderPeers(paths: Collection<String>, folder: String): List<Peer> {
        val parent = dirOf(folder)
        val counts = folderCounts(paths)
        val names = counts.keys.filter { dirOf(it) == parent }.toMutableSet()
        if (parent.isEmpty()) names.addAll(AREAS)
        fun rank(f: String) = if (parent.isEmpty() && f in AREAS) AREAS.indexOf(f) else 99
        return names.sortedWith(compareBy({ rank(it) }, { key(it) }))
            .map { Peer(it, it.substringAfterLast('/'), counts[it] ?: 0, it == folder) }
    }

    data class Pager(val folder: String, val index: Int, val total: Int, val prev: String?, val next: String?)

    /** Position der Notiz in ihrem Ordner (nach Titel sortiert) und die Nachbarn zum Blättern. */
    fun pager(paths: Collection<String>, path: String): Pager {
        val d = dirOf(path)
        val same = paths.filter { dirOf(it) == d }.sortedBy { key(titleOf(it)) }
        val i = same.indexOf(path)
        return Pager(d, i, same.size, if (i > 0) same[i - 1] else null, if (i in 0 until same.size - 1) same[i + 1] else null)
    }

    /** Ordnerkette einer Notiz als (Name, Pfad). */
    fun crumbs(path: String): List<Pair<String, String>> {
        val parts = path.split('/').dropLast(1)
        return parts.mapIndexed { i, label -> label to parts.take(i + 1).joinToString("/") }
    }

    fun pushRecent(list: List<String>, path: String, max: Int = 6): List<String> =
        (listOf(path) + list.filter { it != path }).take(max)

    fun togglePin(list: List<String>, path: String): Pair<Boolean, List<String>> =
        if (path in list) false to list.filter { it != path } else true to list + path

    // ---- Schnellwechsel ----

    enum class Kind { NOTE, FOLDER, CREATE }

    data class Hit(
        val kind: Kind,
        val path: String,
        val title: String,
        val area: String = "",
        val mtime: Long = 0,
        val via: String = "title",
        val parts: Triple<String, String, String>? = null,
        val score: Int = 0,
        val count: Int = 0,
    )

    private fun hit(title: String, q: String): Triple<String, String, String>? {
        val i = title.lowercase().indexOf(q)
        return if (i < 0 || q.isEmpty()) null else Triple(title.take(i), title.substring(i, i + q.length), title.substring(i + q.length))
    }

    /** Suche nach Titel, Pfad und Text. Beginnt die Eingabe mit "/", werden nur Ordner gesucht. */
    fun search(
        paths: Collection<String>,
        mtime: (String) -> Long,
        text: (String) -> String?,
        query: String,
        scope: String = "",
        limit: Int = 12,
    ): List<Hit> {
        fun inScope(p: String) = scope.isEmpty() || p.startsWith("$scope/")
        val raw = query.trim()
        fun area(p: String) = if ('/' in p) p.substringBefore('/') else ""

        if (raw.startsWith("/")) {
            val q = raw.drop(1).trim().lowercase()
            val counts = folderCounts(paths.filter(::inScope))
            return counts.keys.filter { q.isEmpty() || q in it.lowercase() }.sortedBy(::key).take(limit).map {
                Hit(Kind.FOLDER, it, it.substringAfterLast('/'), area(it + "/x"), count = counts[it] ?: 0, parts = hit(it.substringAfterLast('/'), q))
            }
        }

        val q = raw.lowercase()
        val out = ArrayList<Hit>()
        for (p in paths.filter(::inScope)) {
            val title = titleOf(p)
            val lt = title.lowercase()
            var score = -1
            var via = "title"
            when {
                q.isEmpty() -> score = 0
                lt.startsWith(q) -> score = 0
                q in lt -> score = 1
                q in p.lowercase() -> { score = 2; via = "path" }
                q in (text(p) ?: "").lowercase() -> { score = 3; via = "text" }
            }
            if (score < 0) continue
            out.add(Hit(Kind.NOTE, p, title, area(p), mtime(p), via, if (q.isEmpty()) null else hit(title, q), score))
        }
        return out.sortedWith(compareBy({ it.score }, { -it.mtime })).take(limit)
    }

    // ---- Übersicht ----

    private val taskRe = Regex("""^\s*[-*] \[ \] (.+)$""")

    /** Offene Aufgaben (`- [ ] …`) einer Notiz; Codeblöcke werden übersprungen. */
    fun openTasks(text: String): List<String> {
        val out = ArrayList<String>()
        var fence = false
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence) continue
            taskRe.find(line)?.let { out.add(it.groupValues[1].trim()) }
        }
        return out
    }

    data class Cell(val date: LocalDate, val n: Int, val level: Int)
    data class Activity(val cells: List<Cell?>, val months: List<Pair<String, Int>>, val total: Int)

    const val WEEKS = 53

    /** 53 Wochen, Montag zuerst; null = Tag in der Zukunft. Gezählt wird je Notiz der Tag der letzten Änderung. */
    fun activity(mtimes: Collection<Long>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Activity {
        val counts = HashMap<LocalDate, Int>()
        for (t in mtimes) {
            val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            counts[d] = (counts[d] ?: 0) + 1
        }
        val start = today.minusDays((today.dayOfWeek.value - 1).toLong() + (WEEKS - 1) * 7L)
        val cells = ArrayList<Cell?>()
        val monthLabels = ArrayList<Pair<String, Int>>()
        var total = 0
        for (i in 0 until WEEKS * 7) {
            val d = start.plusDays(i.toLong())
            if (d.isAfter(today)) { cells.add(null); continue }
            val n = counts[d] ?: 0
            total += n
            cells.add(Cell(d, n, when { n == 0 -> 0; n == 1 -> 1; n <= 3 -> 2; n <= 6 -> 3; else -> 4 }))
            if (i % 7 == 0 && d.dayOfMonth <= 7) monthLabels.add(months[d.monthValue - 1] to i / 7)
        }
        return Activity(cells, monthLabels, total)
    }

    /** Uhrzeit für heute, sonst "gestern" oder Tag.Monat. */
    fun whenText(t: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(t).atZone(zone)
        val diff = java.time.temporal.ChronoUnit.DAYS.between(dt.toLocalDate(), today)
        return when (diff) {
            0L -> "%02d:%02d".format(dt.hour, dt.minute)
            1L -> "gestern"
            else -> "%02d.%02d.".format(dt.dayOfMonth, dt.monthValue)
        }
    }
}
