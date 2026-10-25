package online.minihomelab.notizen

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

// Eigene Span-Klassen, damit wir beim Neuformatieren genau diese und nichts anderes entfernen
interface MdSpan
class MdSize(f: Float) : RelativeSizeSpan(f), MdSpan
class MdBold : StyleSpan(Typeface.BOLD), MdSpan
class MdItalic : StyleSpan(Typeface.ITALIC), MdSpan
class MdColor(c: Int) : ForegroundColorSpan(c), MdSpan
class MdMono : TypefaceSpan("monospace"), MdSpan
class MdBg(c: Int) : BackgroundColorSpan(c), MdSpan
class MdStrike : StrikethroughSpan(), MdSpan

/**
 * Gespeichert wird reines Markdown. Im Editor werden die Zeichen nur optisch gestaltet:
 * Überschriften groß, Aufzählungszeichen farbig, Markierungszeichen ausgegraut.
 */
object MarkdownStyler {
    private val GRAY = Color.rgb(0x9A, 0xA0, 0xA6)
    private val CODE_BG = Color.rgb(0xF1, 0xF3, 0xF5)
    private val SIZES = floatArrayOf(1.6f, 1.35f, 1.15f)

    private val heading = Regex("^(#{1,3}) ")
    private val listItem = Regex("^(\\s*)(- \\[[ xX]\\] |- |\\* |\\d+\\. )")
    private val anyPrefix = Regex("^(#{1,6} |- \\[[ xX]\\] |- |\\* |\\d+\\. )")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val italic = Regex("(?<![*\\w])\\*(?![*\\s])(.+?)(?<![*\\s])\\*(?![*\\w])")
    private val code = Regex("`([^`]+)`")

    private fun Editable.mark(span: Any, s: Int, e: Int) {
        if (e > s) setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun apply(e: Editable) {
        for (s in e.getSpans(0, e.length, MdSpan::class.java)) e.removeSpan(s)
        val text = e.toString()
        var pos = 0
        var inFence = false
        while (pos <= text.length) {
            var end = text.indexOf('\n', pos)
            if (end == -1) end = text.length
            val line = text.substring(pos, end)

            if (line.startsWith("```")) {
                inFence = !inFence
                e.mark(MdColor(GRAY), pos, end)
                e.mark(MdMono(), pos, end)
            } else if (inFence) {
                e.mark(MdMono(), pos, end)
                e.mark(MdBg(CODE_BG), pos, end)
            } else {
                styleLine(e, line, pos, end)
            }
            pos = end + 1
        }
    }

    private fun styleLine(e: Editable, line: String, pos: Int, end: Int) {
        val h = heading.find(line)
        if (h != null) {
            val level = h.groupValues[1].length
            e.mark(MdSize(SIZES[level - 1]), pos, end)
            e.mark(MdBold(), pos, end)
            e.mark(MdColor(GRAY), pos, pos + h.value.length)
            return
        }
        var contentStart = pos
        val li = listItem.find(line)
        if (li != null) {
            val markerStart = pos + li.groupValues[1].length
            val markerEnd = pos + li.value.length
            e.mark(MdColor(Ui.ACCENT), markerStart, markerEnd)
            e.mark(MdBold(), markerStart, markerEnd)
            if (li.groupValues[2].startsWith("- [") && li.groupValues[2][3] != ' ') {
                e.mark(MdStrike(), markerEnd, end)
                e.mark(MdColor(GRAY), markerEnd, end)
            }
            contentStart = markerEnd
        }
        inline(e, line.substring(contentStart - pos), contentStart)
    }

    private fun inline(e: Editable, text: String, offset: Int) {
        for (m in bold.findAll(text)) {
            val s = offset + m.range.first
            val t = offset + m.range.last + 1
            e.mark(MdBold(), s, t)
            e.mark(MdColor(GRAY), s, s + 2)
            e.mark(MdColor(GRAY), t - 2, t)
        }
        for (m in italic.findAll(text)) {
            val s = offset + m.range.first
            val t = offset + m.range.last + 1
            e.mark(MdItalic(), s, t)
            e.mark(MdColor(GRAY), s, s + 1)
            e.mark(MdColor(GRAY), t - 1, t)
        }
        for (m in code.findAll(text)) {
            val s = offset + m.range.first
            val t = offset + m.range.last + 1
            e.mark(MdMono(), s, t)
            e.mark(MdBg(CODE_BG), s, t)
            e.mark(MdColor(GRAY), s, s + 1)
            e.mark(MdColor(GRAY), t - 1, t)
        }
    }

    /**
     * Nach einem Zeilenumbruch bei [newlineAt]: Aufzählung fortsetzen oder bei leerem Punkt beenden.
     * Gibt die neue Cursorposition zurück, -1 wenn nichts zu tun war.
     */
    fun continueList(e: Editable, newlineAt: Int): Int {
        val text = e.toString()
        val lineStart = text.lastIndexOf('\n', newlineAt - 1) + 1
        val prev = text.substring(lineStart, newlineAt)
        val m = listItem.find(prev) ?: return -1
        val indent = m.groupValues[1]
        val marker = m.groupValues[2]
        if (prev.substring(m.value.length).isBlank()) {
            e.delete(lineStart, newlineAt + 1)
            return lineStart
        }
        val next = when {
            marker.startsWith("- [") -> "- [ ] "
            marker[0].isDigit() -> "${marker.trim().removeSuffix(".").toInt() + 1}. "
            else -> marker
        }
        e.insert(newlineAt + 1, indent + next)
        return newlineAt + 1 + indent.length + next.length
    }

    /** Setzt das Zeilenpräfix der Cursorzeile (ersetzt ein vorhandenes, nochmal tippen = entfernen). */
    fun setLinePrefix(e: Editable, cursor: Int, prefix: String): Int {
        val text = e.toString()
        val ls = text.lastIndexOf('\n', cursor - 1) + 1
        var le = text.indexOf('\n', cursor)
        if (le == -1) le = text.length
        val old = anyPrefix.find(text.substring(ls, le))?.value ?: ""
        val same = old == prefix
        e.replace(ls, ls + old.length, if (same) "" else prefix)
        val added = if (same) 0 else prefix.length
        return (cursor + added - old.length).coerceAtLeast(ls)
    }

    /** Umschließt die Auswahl mit [mk] (oder entfernt es wieder). Gibt die neue Auswahl zurück. */
    fun wrap(e: Editable, a: Int, b: Int, mk: String): Pair<Int, Int> {
        val s = minOf(a, b)
        val t = maxOf(a, b)
        val n = mk.length
        val text = e.toString()
        if (s != t && s >= n && t + n <= text.length &&
            text.substring(s - n, s) == mk && text.substring(t, t + n) == mk
        ) {
            e.delete(t, t + n)
            e.delete(s - n, s)
            return Pair(s - n, t - n)
        }
        e.insert(t, mk)
        e.insert(s, mk)
        return Pair(s + n, t + n)
    }
}
