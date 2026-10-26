package online.minihomelab.notizen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Editable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.graphics.Typeface

// Eigene Span-Klassen, damit wir beim Neuformatieren genau diese und nichts anderes entfernen
interface MdSpan
class MdSize(f: Float) : RelativeSizeSpan(f), MdSpan
class MdFont(face: Typeface) : FontSpan(face), MdSpan
class MdColor(c: Int) : ForegroundColorSpan(c), MdSpan
class MdBg(c: Int) : BackgroundColorSpan(c), MdSpan
class MdStrike : StrikethroughSpan(), MdSpan

/** Zeichnet "- [ ] " bzw. "- [x] " als echtes Kästchen (Antippen wechselt, siehe NoteEdit). */
class MdCheckbox(val checked: Boolean) : ReplacementSpan(), MdSpan {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (paint.textSize * 1.3f).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val side = paint.textSize * 0.8f
        val left = x + paint.textSize * 0.05f
        val boxBottom = y - paint.textSize * 0.06f
        val boxTop = boxBottom - side
        val stroke = side * 0.11f
        val radius = side * 0.2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (checked) {
            p.style = Paint.Style.FILL
            p.color = Ui.ACCENT_BG
            canvas.drawRoundRect(RectF(left, boxTop, left + side, boxBottom), radius, radius, p)
            p.style = Paint.Style.STROKE
            p.color = Color.WHITE
            p.strokeWidth = stroke * 1.3f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            val tick = Path()
            tick.moveTo(left + side * 0.22f, boxTop + side * 0.52f)
            tick.lineTo(left + side * 0.43f, boxTop + side * 0.72f)
            tick.lineTo(left + side * 0.78f, boxTop + side * 0.28f)
            canvas.drawPath(tick, p)
        } else {
            p.style = Paint.Style.STROKE
            p.color = Ui.ACCENT
            p.strokeWidth = stroke
            val h = stroke / 2
            canvas.drawRoundRect(RectF(left + h, boxTop + h, left + side - h, boxBottom - h), radius, radius, p)
        }
    }
}

/** "- " bzw. "* " als kleiner Akzent-Punkt statt Strich. */
class MdBullet : ReplacementSpan(), MdSpan {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (paint.textSize * 1.1f).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Ui.ACCENT
        canvas.drawCircle(x + paint.textSize * 0.3f, y - paint.textSize * 0.32f, paint.textSize * 0.14f, p)
    }
}

/** Blendet Markdown-Zeichen aus: Breite 0, nichts gezeichnet. */
class MdHide : ReplacementSpan(), MdSpan {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = 0
    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
    }
}

/**
 * Gespeichert wird reines Markdown. Im Editor werden die Zeichen nur optisch gestaltet:
 * Überschriften groß, Aufzählungen als Punkt, Aufgaben als Kästchen. Markierungszeichen (#, **, *, `) sind
 * ausgeblendet und erscheinen grau, solange der Cursor in der Überschrift-Zeile bzw. im Wort steht.
 */
object MarkdownStyler {
    private val SIZES = floatArrayOf(1.9f, 1.35f, 1.15f)

    private val heading = Regex("^(#{1,3}) ")
    private val listItem = Regex("^(\\s*)(- \\[[ xX]\\] |- |\\* |\\d+\\. )")
    private val anyPrefix = Regex("^(#{1,6} |- \\[[ xX]\\] |- |\\* |\\d+\\. )")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val italic = Regex("(?<![*\\w])\\*(?![*\\s])(.+?)(?<![*\\s])\\*(?![*\\w])")
    private val code = Regex("`([^`]+)`")

    private fun Editable.mark(span: Any, s: Int, e: Int) {
        if (e > s) setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun apply(e: Editable, selStart: Int, selEnd: Int) {
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
                e.mark(MdColor(Ui.FAINT), pos, end)
                e.mark(MdFont(Ui.mono), pos, end)
            } else if (inFence) {
                e.mark(MdFont(Ui.mono), pos, end)
                e.mark(MdBg(Ui.SUBTLE), pos, end)
            } else {
                styleLine(e, line, pos, end, selStart, selEnd)
            }
            pos = end + 1
        }
    }

    private fun styleLine(e: Editable, line: String, pos: Int, end: Int, selStart: Int, selEnd: Int) {
        val h = heading.find(line)
        if (h != null) {
            val level = h.groupValues[1].length
            e.mark(MdSize(SIZES[level - 1]), pos, end)
            e.mark(MdFont(Ui.sansSemi), pos, end)
            val active = selStart <= end && selEnd >= pos
            e.mark(if (active) MdColor(Ui.FAINT) else MdHide(), pos, pos + h.value.length)
            return
        }
        var contentStart = pos
        val li = listItem.find(line)
        if (li != null) {
            val markerStart = pos + li.groupValues[1].length
            val markerEnd = pos + li.value.length
            val markerText = li.groupValues[2]
            when {
                markerText.startsWith("- [") -> e.mark(MdCheckbox(markerText[3] != ' '), markerStart, markerEnd)
                markerText[0].isDigit() -> {
                    e.mark(MdColor(Ui.ACCENT), markerStart, markerEnd)
                    e.mark(MdFont(Ui.monoMedium), markerStart, markerEnd)
                }
                else -> e.mark(MdBullet(), markerStart, markerEnd)
            }
            if (markerText.startsWith("- [") && markerText[3] != ' ') {
                e.mark(MdStrike(), markerEnd, end)
                e.mark(MdColor(Ui.FAINT), markerEnd, end)
            }
            contentStart = markerEnd
        }
        inline(e, line.substring(contentStart - pos), contentStart, selStart, selEnd)
    }

    private fun inline(e: Editable, text: String, offset: Int, selStart: Int, selEnd: Int) {
        fun marker(touching: Boolean): Any = if (touching) MdColor(Ui.FAINT) else MdHide()
        for (m in bold.findAll(text)) {
            val s = offset + m.range.first
            val t = offset + m.range.last + 1
            e.mark(MdFont(Ui.sansSemi), s, t)
            val touch = selStart <= t && selEnd >= s
            e.mark(marker(touch), s, s + 2)
            e.mark(marker(touch), t - 2, t)
        }
        for (m in italic.findAll(text)) {
            val s = offset + m.range.first
            val t = offset + m.range.last + 1
            e.mark(MdFont(Ui.sansItalic), s, t)
            val touch = selStart <= t && selEnd >= s
            e.mark(marker(touch), s, s + 1)
            e.mark(marker(touch), t - 1, t)
        }
        for (m in code.findAll(text)) {
            val s = offset + m.range.first
            val t = offset + m.range.last + 1
            e.mark(MdFont(Ui.mono), s, t)
            e.mark(MdBg(Ui.SUBTLE), s, t)
            val touch = selStart <= t && selEnd >= s
            e.mark(marker(touch), s, s + 1)
            e.mark(marker(touch), t - 1, t)
        }
    }

    /** Welche Formate an der Cursorposition gelten: h1..h3, list, num, task, bold, italic, code. */
    fun activeFormats(text: String, cursor: Int): Set<String> {
        val c = cursor.coerceIn(0, text.length)
        val ls = text.lastIndexOf('\n', c - 1) + 1
        var le = text.indexOf('\n', c)
        if (le == -1) le = text.length
        val line = text.substring(ls, le)
        val out = HashSet<String>()
        heading.find(line)?.let { out.add("h" + it.groupValues[1].length) }
        listItem.find(line)?.let { m ->
            val mk = m.groupValues[2]
            out.add(
                when {
                    mk.startsWith("- [") -> "task"
                    mk[0].isDigit() -> "num"
                    else -> "list"
                },
            )
        }
        val pos = c - ls
        for (m in bold.findAll(line)) if (pos in m.range.first..m.range.last + 1) out.add("bold")
        for (m in italic.findAll(line)) if (pos in m.range.first..m.range.last + 1) out.add("italic")
        for (m in code.findAll(line)) if (pos in m.range.first..m.range.last + 1) out.add("code")
        return out
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
