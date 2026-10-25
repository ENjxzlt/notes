package online.minihomelab.inkspike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

enum class Tool { PEN, MARKER, ERASER }

/**
 * Zeichenfläche für den Stift-Test. Nur Stift zeichnet (Finger wird ignoriert = einfache Palm Rejection),
 * die Strichstärke folgt dem Druck, der Stifttasten-Druck (S Pen) radiert vorübergehend.
 */
class InkView(context: Context) : View(context) {

    private class Pt(val x: Float, val y: Float, val p: Float)

    private class Stroke(val tool: Tool, val color: Int) {
        val pts = ArrayList<Pt>()
    }

    var tool = Tool.PEN
    var color = Color.BLACK

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    private var activePointer = -1
    private var erasing = false

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 30f
    }

    private var hud = "Bereit – mit dem Stift zeichnen"
    private var eventCount = 0
    private var eventWindowStart = System.nanoTime()
    private var eventsPerSecond = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmapCanvas = Canvas(bitmap!!)
        rebuild()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            rebuild()
            invalidate()
        }
    }

    fun clear() {
        strokes.clear()
        rebuild()
        invalidate()
    }

    private fun rebuild() {
        val c = bitmapCanvas ?: return
        c.drawColor(Color.WHITE)
        for (s in strokes) drawStroke(c, s)
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        val pts = s.pts
        if (pts.isEmpty()) return
        paint.color = s.color
        if (s.tool == Tool.MARKER) {
            // Textmarker: ein Pfad mit fester Breite und Transparenz, damit sich Teilstücke nicht verdunkeln
            paint.alpha = 90
            paint.strokeWidth = 28f
            paint.strokeCap = Paint.Cap.BUTT
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            if (pts.size == 1) path.lineTo(pts[0].x + 0.1f, pts[0].y)
            c.drawPath(path, paint)
            paint.alpha = 255
            return
        }
        paint.strokeCap = Paint.Cap.ROUND
        if (pts.size == 1) {
            paint.strokeWidth = widthFor(pts[0].p)
            c.drawPoint(pts[0].x, pts[0].y, paint)
            return
        }
        for (i in 1 until pts.size) {
            // Breite aus dem mittleren Druck des Segments
            paint.strokeWidth = widthFor((pts[i - 1].p + pts[i].p) / 2f)
            c.drawLine(pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y, paint)
        }
    }

    private fun widthFor(pressure: Float): Float = 2f + 10f * pressure.coerceIn(0f, 1f)

    private fun eraseAt(x: Float, y: Float) {
        val radius = 24f
        val iter = strokes.iterator()
        var removed = false
        while (iter.hasNext()) {
            val s = iter.next()
            if (s.pts.any { hypot(it.x - x, it.y - y) < radius }) {
                iter.remove()
                removed = true
            }
        }
        if (removed) {
            rebuild()
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val type = event.getToolType(i)
                // Palm Rejection light: nur Stift und Radierer-Ende zeichnen, Finger/Hand werden ignoriert
                if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return true
                if (activePointer != -1) return true
                activePointer = event.getPointerId(i)
                requestUnbufferedDispatch(event) // keine Bündelung der Ereignisse = geringere Latenz
                parent?.requestDisallowInterceptTouchEvent(true)
                val buttonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                erasing = type == MotionEvent.TOOL_TYPE_ERASER || buttonPressed || tool == Tool.ERASER
                if (erasing) {
                    eraseAt(event.getX(i), event.getY(i))
                } else {
                    current = Stroke(tool, color).also {
                        it.pts.add(Pt(event.getX(i), event.getY(i), event.getPressure(i)))
                    }
                }
                updateHud(event, i)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointer)
                if (i == -1) return true
                if (erasing) {
                    for (h in 0 until event.historySize) {
                        eraseAt(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                    }
                    eraseAt(event.getX(i), event.getY(i))
                } else {
                    val s = current ?: return true
                    var minX = event.getX(i)
                    var maxX = minX
                    var minY = event.getY(i)
                    var maxY = minY
                    // Alle gebündelten Zwischenpunkte übernehmen, sonst wird die Linie eckig
                    for (h in 0 until event.historySize) {
                        val hx = event.getHistoricalX(i, h)
                        val hy = event.getHistoricalY(i, h)
                        s.pts.add(Pt(hx, hy, event.getHistoricalPressure(i, h)))
                        minX = minOf(minX, hx); maxX = maxOf(maxX, hx)
                        minY = minOf(minY, hy); maxY = maxOf(maxY, hy)
                    }
                    s.pts.add(Pt(event.getX(i), event.getY(i), event.getPressure(i)))
                    val pad = 40
                    invalidate((minX - pad).toInt(), (minY - pad).toInt(), (maxX + pad).toInt(), (maxY + pad).toInt())
                }
                updateHud(event, i)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                if (event.getPointerId(i) == activePointer) {
                    finishStroke()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Das System hat die Berührung als Handballen erkannt: Strich verwerfen
                current = null
                activePointer = -1
                erasing = false
                invalidate()
            }
        }
        return true
    }

    private fun finishStroke() {
        current?.let {
            strokes.add(it)
            bitmapCanvas?.let { c -> drawStroke(c, it) }
        }
        current = null
        activePointer = -1
        erasing = false
        invalidate()
    }

    private fun updateHud(event: MotionEvent, i: Int) {
        eventCount += 1 + event.historySize
        val now = System.nanoTime()
        if (now - eventWindowStart >= 1_000_000_000L) {
            eventsPerSecond = eventCount
            eventCount = 0
            eventWindowStart = now
        }
        val type = when (event.getToolType(i)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "Stift"
            MotionEvent.TOOL_TYPE_ERASER -> "Radierer"
            MotionEvent.TOOL_TYPE_FINGER -> "Finger"
            else -> "?"
        }
        val button = if ((event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) " | Taste" else ""
        hud = "$type$button | Druck %.2f | Neigung %.2f | %d Punkte/s".format(
            event.getPressure(i), event.getAxisValue(MotionEvent.AXIS_TILT, i), eventsPerSecond
        )
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        current?.let { drawStroke(canvas, it) }
        canvas.drawText(hud, 20f, height - 24f, hudPaint)
    }
}
