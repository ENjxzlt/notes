package online.minihomelab.notizen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** Ein Strich: Werkzeug ("pen"/"marker"), Farbe ("text" = Textfarbe oder "#rrggbb") und Punkte (x, y, Druck). */
class InkStroke(val tool: String, val color: String) {
    val pts = ArrayList<FloatArray>()
}

/** Eine Zeichnung auf einer Seite von 1000 Einheiten Breite und [height] Einheiten Höhe. */
class InkDoc(var height: Int = 600) {
    val strokes = ArrayList<InkStroke>()
}

/**
 * Zeichenlogik ohne Oberfläche: Striche aufnehmen, radieren (ganze Striche), rückgängig machen.
 * Wird vom Vollbild-Editor und vom Zeichnen direkt in der Notiz benutzt.
 */
class InkModel(val doc: InkDoc) {
    var tool = "pen" // pen, marker, eraser
    var color = "text"
    var current: InkStroke? = null
    /** Letzter abgeschlossener Vorgang war Radieren (dann muss die Seite neu gezeichnet werden). */
    var lastWasErase = false

    private class Op(val added: InkStroke?, val removed: List<Pair<Int, InkStroke>>)

    private val ops = ArrayList<Op>()
    private var erasing = false
    private var erasedNow = ArrayList<Pair<Int, InkStroke>>()

    /** Gibt true zurück, wenn sich bereits Gezeichnetes geändert hat (Radieren). */
    fun down(x: Float, y: Float, p: Float, forceErase: Boolean): Boolean {
        erasing = forceErase || tool == "eraser"
        if (erasing) {
            erasedNow = ArrayList()
            return eraseAt(x, y)
        }
        current = InkStroke(tool, color).also { it.pts.add(floatArrayOf(x, y, p)) }
        return false
    }

    fun move(x: Float, y: Float, p: Float): Boolean {
        if (erasing) return eraseAt(x, y)
        current?.pts?.add(floatArrayOf(x, y, p))
        return false
    }

    /** Schließt den Strich ab; true, wenn sich die Zeichnung geändert hat. */
    fun up(): Boolean {
        var changed = false
        lastWasErase = erasing
        if (erasing) {
            if (erasedNow.isNotEmpty()) {
                ops.add(Op(null, erasedNow))
                changed = true
            }
        } else {
            current?.let {
                doc.strokes.add(it)
                ops.add(Op(it, emptyList()))
                changed = true
            }
        }
        current = null
        erasing = false
        return changed
    }

    /** Berührung abgebrochen (Handballen): Strich verwerfen. */
    fun cancel() {
        if (erasing && erasedNow.isNotEmpty()) ops.add(Op(null, erasedNow))
        current = null
        erasing = false
    }

    fun undo(): Boolean {
        val op = ops.removeLastOrNull() ?: return false
        op.added?.let { doc.strokes.remove(it) }
        for ((i, s) in op.removed.sortedBy { it.first }) doc.strokes.add(minOf(i, doc.strokes.size), s)
        return true
    }

    private fun eraseAt(x: Float, y: Float): Boolean {
        val radius = 14f
        var changed = false
        var i = doc.strokes.size - 1
        while (i >= 0) {
            val s = doc.strokes[i]
            if (s.pts.any { kotlin.math.hypot(it[0] - x, it[1] - y) < radius }) {
                erasedNow.add(Pair(i, s))
                doc.strokes.removeAt(i)
                changed = true
            }
            i--
        }
        return changed
    }
}

/**
 * Zeichnungen liegen neben der Notiz im versteckten Ordner `.ink`: `<id>.json` (bearbeitbar, mit Druck) und
 * `<id>.svg` (Vorschau für andere Programme). In der Notiz steht nur `![Zeichnung](.ink/<id>.svg)`.
 */
object Ink {
    const val W = 1000
    const val MAX_H = 3000
    const val MARKER_W = 16f
    private val thumbs = LinkedHashMap<String, Bitmap>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun dir(noteDir: File) = File(noteDir, ".ink")
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
    fun widthFor(p: Float) = 1.2f + 4.5f * p.coerceIn(0f, 1f)

    /** "text" folgt dem Hell/Dunkel-Modus, damit schwarze Schrift im Dunkeln nicht verschwindet. */
    fun resolve(color: String): Int =
        if (color == "text") Ui.TEXT else try { Color.parseColor(color) } catch (e: Exception) { Ui.TEXT }

    // ---- Laden und Speichern ----

    fun load(noteDir: File, id: String): InkDoc {
        val f = File(dir(noteDir), "$id.json")
        val doc = InkDoc()
        if (!f.isFile) return doc
        try {
            val o = JSONObject(f.readText())
            doc.height = o.optInt("h", 600).coerceIn(200, MAX_H)
            val arr = o.getJSONArray("strokes")
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val s = InkStroke(so.optString("t", "pen"), so.optString("c", "text"))
                val p = so.getJSONArray("p")
                var j = 0
                while (j + 2 < p.length()) {
                    s.pts.add(floatArrayOf(p.getDouble(j).toFloat(), p.getDouble(j + 1).toFloat(), p.getDouble(j + 2).toFloat()))
                    j += 3
                }
                doc.strokes.add(s)
            }
        } catch (e: Exception) {
            // Beschädigte Datei nicht überschreiben, sondern zur Seite legen
            f.renameTo(File(f.path + ".defekt"))
            return InkDoc()
        }
        return doc
    }

    fun save(noteDir: File, id: String, doc: InkDoc) {
        val d = dir(noteDir)
        d.mkdirs()
        val j = JSONObject()
        j.put("v", 1)
        j.put("w", W)
        j.put("h", doc.height)
        val arr = JSONArray()
        for (s in doc.strokes) {
            val so = JSONObject()
            so.put("t", s.tool)
            so.put("c", s.color)
            val p = JSONArray()
            for (pt in s.pts) {
                p.put(Math.round(pt[0] * 10) / 10.0)
                p.put(Math.round(pt[1] * 10) / 10.0)
                p.put(Math.round(pt[2] * 100) / 100.0)
            }
            so.put("p", p)
            arr.put(so)
        }
        j.put("strokes", arr)
        writeAtomic(File(d, "$id.json"), j.toString())
        writeAtomic(File(d, "$id.svg"), toSvg(doc))
        invalidate(id)
    }

    fun delete(noteDir: File, id: String) {
        for (ext in listOf("json", "svg")) File(dir(noteDir), "$id.$ext").delete()
        invalidate(id)
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun toSvg(doc: InkDoc): String {
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $W ${doc.height}\" width=\"$W\" height=\"${doc.height}\">\n")
        for (s in doc.strokes) {
            if (s.pts.isEmpty()) continue
            val col = if (Regex("^#[0-9a-fA-F]{6}$").matches(s.color)) s.color else "#18181b"
            val marker = s.tool == "marker"
            val w = if (marker) MARKER_W else s.pts.map { widthFor(it[2]) }.average().toFloat()
            val d = StringBuilder("M")
            s.pts.forEachIndexed { i, pt ->
                if (i > 0) d.append(" L")
                d.append(String.format(Locale.US, "%.1f %.1f", pt[0], pt[1]))
            }
            if (s.pts.size == 1) d.append(String.format(Locale.US, " L%.1f %.1f", s.pts[0][0] + 0.1f, s.pts[0][1]))
            sb.append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(col)
                .append("\" stroke-width=\"").append(String.format(Locale.US, "%.2f", w))
                .append("\" stroke-linecap=\"").append(if (marker) "butt" else "round")
                .append("\" stroke-linejoin=\"round\"").append(if (marker) " stroke-opacity=\"0.35\"" else "").append("/>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    // ---- Zeichnen ----

    fun drawStroke(c: Canvas, s: InkStroke, scale: Float) {
        val pts = s.pts
        if (pts.isEmpty()) return
        paint.color = resolve(s.color)
        if (s.tool == "marker") {
            // Textmarker: ein Pfad mit fester Breite und Transparenz, damit sich Teilstücke nicht verdunkeln
            paint.alpha = 90
            paint.strokeWidth = MARKER_W * scale
            paint.strokeCap = Paint.Cap.BUTT
            val path = Path()
            path.moveTo(pts[0][0] * scale, pts[0][1] * scale)
            for (i in 1 until pts.size) path.lineTo(pts[i][0] * scale, pts[i][1] * scale)
            if (pts.size == 1) path.lineTo(pts[0][0] * scale + 0.1f, pts[0][1] * scale)
            c.drawPath(path, paint)
            paint.alpha = 255
            return
        }
        paint.strokeCap = Paint.Cap.ROUND
        if (pts.size == 1) {
            paint.strokeWidth = widthFor(pts[0][2]) * scale
            c.drawPoint(pts[0][0] * scale, pts[0][1] * scale, paint)
            return
        }
        for (i in 1 until pts.size) {
            paint.strokeWidth = widthFor((pts[i - 1][2] + pts[i][2]) / 2f) * scale
            c.drawLine(pts[i - 1][0] * scale, pts[i - 1][1] * scale, pts[i][0] * scale, pts[i][1] * scale, paint)
        }
    }

    /** Papier: Fläche mit Rand und feinen Linien alle 50 Einheiten. */
    fun drawPaper(c: Canvas, wPx: Float, hPx: Float, scale: Float, radiusPx: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = Ui.SURFACE
        val r = RectF(0f, 0f, wPx, hPx)
        c.drawRoundRect(r, radiusPx, radiusPx, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = Ui.mix(Ui.BORDER, Ui.SURFACE, 0.5f)
        var y = 50f * scale
        while (y < hPx - 1) {
            c.drawLine(radiusPx / 2, y, wPx - radiusPx / 2, y, p)
            y += 50f * scale
        }
        p.color = Ui.BORDER
        p.strokeWidth = maxOf(1f, scale)
        r.inset(p.strokeWidth / 2, p.strokeWidth / 2)
        c.drawRoundRect(r, radiusPx, radiusPx, p)
    }

    /** Vorschau für die Notiz (zwischengespeichert, nur wenige gleichzeitig wegen des Speichers). */
    fun thumb(noteDir: File, id: String, widthPx: Int): Bitmap {
        val jf = File(dir(noteDir), "$id.json")
        val key = "${jf.path}|${jf.lastModified()}|$widthPx|${Ui.dark}"
        thumbs[key]?.let { return it }
        val doc = load(noteDir, id)
        val scale = widthPx.toFloat() / W
        val h = maxOf(1, (doc.height * scale).toInt())
        val bm = Bitmap.createBitmap(maxOf(1, widthPx), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        drawPaper(c, widthPx.toFloat(), h.toFloat(), scale, 12f * scale)
        for (s in doc.strokes) drawStroke(c, s, scale)
        if (doc.strokes.isEmpty()) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG)
            t.color = Ui.FAINT
            t.textSize = 15f * scale * 1.6f
            t.typeface = Ui.sans
            t.textAlign = Paint.Align.CENTER
            c.drawText("Zeichnung · antippen zum Zeichnen", widthPx / 2f, h / 2f, t)
        }
        thumbs[key] = bm
        while (thumbs.size > 3) thumbs.remove(thumbs.keys.first())
        return bm
    }

    fun invalidate(id: String) {
        thumbs.keys.removeAll { it.contains("/$id.json|") }
    }
}
