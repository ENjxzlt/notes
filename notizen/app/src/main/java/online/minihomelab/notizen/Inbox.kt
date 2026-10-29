package online.minihomelab.notizen

/**
 * Ideen-Dump und Aufgaben ohne Android-Abhängigkeit (gleiches Format in App, Linux-Client und Web-Ansicht).
 *
 * Inbox: eine Notiz `Inbox.md` im Notizen-Ordner, je Idee eine Zeile `- 2026-10-29 12:31 · Text`.
 * Aufgaben: `- [ ] Titel (~45 min · bis 30.10.)`, Dauer und Frist sind optional.
 */
object Inbox {
    const val FILE = "Inbox.md"
    private const val HEAD = "# Inbox\n\n"

    data class Idea(val raw: String, val stamp: String, val text: String)

    private val ideaRe = Regex("""^- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) · (.+)$""")

    fun format(stamp: String, text: String): String = "- $stamp · ${text.trim().replace(Regex("\\s*\\n\\s*"), " / ")}"

    fun parse(text: String): List<Idea> =
        text.split('\n').mapNotNull { line -> ideaRe.find(line.trimEnd())?.let { Idea(line.trimEnd(), it.groupValues[1], it.groupValues[2]) } }

    /** Hängt eine Idee an; legt bei leerem Text die Überschrift an. Leere Ideen werden ignoriert. */
    fun append(content: String, idea: String, stamp: String): String {
        if (idea.isBlank()) return content
        return attach(content.ifBlank { HEAD }, format(stamp, idea))
    }

    /** Hängt [line] an; steht im Text nur eine Überschrift, bleibt eine Leerzeile dazwischen. */
    private fun attach(content: String, line: String): String {
        val body = content.trimEnd('\n')
        return body + (if ('\n' in body) "\n" else "\n\n") + line + "\n"
    }

    fun remove(content: String, raw: String): String {
        val lines = content.split('\n').toMutableList()
        val i = lines.indexOfFirst { it.trimEnd() == raw }
        if (i >= 0) lines.removeAt(i)
        return lines.joinToString("\n")
    }

    // ---- Fristen ----

    private val weekdays = mapOf(
        "montag" to 1, "mo" to 1, "dienstag" to 2, "di" to 2, "mittwoch" to 3, "mi" to 3, "donnerstag" to 4, "do" to 4,
        "freitag" to 5, "fr" to 5, "samstag" to 6, "sa" to 6, "sonntag" to 7, "so" to 7,
    )
    private val dateRe = Regex("""^(\d{1,2})\.(\d{1,2})\.?(\d{2,4})?$""")

    /** Frist aus Eingabe wie "Freitag", "morgen" oder "3.11." als "dd.MM." (nächster passender Tag); null, wenn unklar. */
    fun parseDue(input: String, today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        val s = input.trim().lowercase().removePrefix("bis ").trim()
        if (s.isEmpty()) return null
        fun fmt(d: java.time.LocalDate) = "%02d.%02d.".format(d.dayOfMonth, d.monthValue)
        when (s) {
            "heute" -> return fmt(today)
            "morgen" -> return fmt(today.plusDays(1))
            "übermorgen" -> return fmt(today.plusDays(2))
        }
        weekdays[s]?.let { target -> return fmt(today.plusDays(((target - today.dayOfWeek.value + 7) % 7).toLong())) }
        val m = dateRe.find(s) ?: return null
        return try {
            fmt(java.time.LocalDate.of(today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()))
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    // ---- Aufgaben ----

    data class Task(val line: Int, val title: String, val minutes: Int?, val due: String?)

    private val taskRe = Regex("""^(\s*[-*] )\[ \] (.+)$""")
    private val metaRe = Regex("""^(.*?)\s*\(~(\d+) min(?: · bis (\d{2}\.\d{2}\.))?\)\s*$""")

    fun taskLine(title: String, minutes: Int?, due: String?): String {
        val meta = when {
            minutes != null && due != null -> " (~$minutes min · bis $due)"
            minutes != null -> " (~$minutes min)"
            else -> ""
        }
        return "- [ ] ${title.trim()}$meta"
    }

    /** Offene Aufgaben einer Notiz mit Zeilennummer (Codeblöcke ausgenommen). */
    fun tasks(text: String): List<Task> {
        val out = ArrayList<Task>()
        var fence = false
        text.split('\n').forEachIndexed { i, line ->
            if (line.trimStart().startsWith("```")) { fence = !fence; return@forEachIndexed }
            if (fence) return@forEachIndexed
            val m = taskRe.find(line) ?: return@forEachIndexed
            val body = m.groupValues[2].trim()
            val meta = metaRe.find(body)
            if (meta != null) out.add(Task(i, meta.groupValues[1].trim(), meta.groupValues[2].toInt(), meta.groupValues[3].ifEmpty { null }))
            else out.add(Task(i, body, null, null))
        }
        return out
    }

    /** Hakt die Aufgabe in Zeile [line] ab; null, wenn die Zeile keine offene Aufgabe (mehr) ist. */
    fun check(text: String, line: Int, expectTitle: String): String? {
        val lines = text.split('\n').toMutableList()
        if (line !in lines.indices) return null
        val m = taskRe.find(lines[line]) ?: return null
        if (!m.groupValues[2].startsWith(expectTitle)) return null
        lines[line] = lines[line].replaceFirst("[ ]", "[x]")
        return lines.joinToString("\n")
    }

    /** Hängt eine Zeile ans Ende der Notiz (legt sie mit Überschrift an, wenn sie leer ist). */
    fun appendLine(content: String, title: String, line: String): String {
        return attach(content.ifBlank { "# $title\n\n" }, line)
    }
}
