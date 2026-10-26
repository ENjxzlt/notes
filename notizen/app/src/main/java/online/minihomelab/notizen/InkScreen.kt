package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.hypot

/**
 * Zeichenfläche: nur der Stift zeichnet (Finger scrollt = einfache Palm Rejection), die Strichstärke folgt dem Druck,
 * die Stifttaste (S Pen) radiert, solange sie gedrückt ist. Koordinaten sind Seiteneinheiten (Breite = Ink.W).
 */
class DrawView(ctx: Context, val doc: InkDoc) : View(ctx) {
    var tool = "pen" // pen, marker, eraser
    var color = "text"
    var fingerDraws = false

    private class Op(val added: InkStroke?, val removed: List<Pair<Int, InkStroke>>)

    private val ops = ArrayList<Op>()
    private var current: InkStroke? = null
    private var activePointer = -1
    private var erasing = false
    private var erasedNow = ArrayList<Pair<Int, InkStroke>>()
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private val scale get() = width.toFloat() / Ink.W

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, (doc.height * w.toFloat() / Ink.W).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmapCanvas = Canvas(bitmap!!)
        rebuild()
    }

    fun undo() {
        val op = ops.removeLastOrNull() ?: return
        op.added?.let { doc.strokes.remove(it) }
        for ((i, s) in op.removed.sortedBy { it.first }) doc.strokes.add(minOf(i, doc.strokes.size), s)
        rebuild()
        invalidate()
    }

    fun extend() {
        doc.height = minOf(Ink.MAX_H, doc.height + 400)
        requestLayout()
    }

    private fun rebuild() {
        val c = bitmapCanvas ?: return
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        Ink.drawPaper(c, width.toFloat(), height.toFloat(), scale, context.dp(8).toFloat())
        for (s in doc.strokes) Ink.drawStroke(c, s, scale)
    }

    private fun eraseAt(x: Float, y: Float) {
        val radius = 14f
        var changed = false
        var i = doc.strokes.size - 1
        while (i >= 0) {
            val s = doc.strokes[i]
            if (s.pts.any { hypot(it[0] - x, it[1] - y) < radius }) {
                erasedNow.add(Pair(i, s))
                doc.strokes.removeAt(i)
                changed = true
            }
            i--
        }
        if (changed) {
            rebuild()
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sc = scale
        if (sc <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val type = event.getToolType(i)
                val isPen = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
                if (!isPen && !(fingerDraws && type == MotionEvent.TOOL_TYPE_FINGER)) return false // Finger scrollt
                if (activePointer != -1) return true
                activePointer = event.getPointerId(i)
                requestUnbufferedDispatch(event) // keine Bündelung der Ereignisse = geringere Latenz
                parent?.requestDisallowInterceptTouchEvent(true)
                val button = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                erasing = type == MotionEvent.TOOL_TYPE_ERASER || button || tool == "eraser"
                val x = event.getX(i) / sc
                val y = event.getY(i) / sc
                if (erasing) {
                    erasedNow = ArrayList()
                    eraseAt(x, y)
                } else {
                    val s = InkStroke(tool, color)
                    s.pts.add(floatArrayOf(x, y, pressure(event, i, type)))
                    current = s
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointer)
                if (i == -1) return true
                val type = event.getToolType(i)
                if (erasing) {
                    for (h in 0 until event.historySize) eraseAt(event.getHistoricalX(i, h) / sc, event.getHistoricalY(i, h) / sc)
                    eraseAt(event.getX(i) / sc, event.getY(i) / sc)
                } else {
                    val s = current ?: return true
                    val r = Rect()
                    val pad = 40
                    fun grow(px: Float, py: Float) {
                        val ix = px.toInt()
                        val iy = py.toInt()
                        if (r.isEmpty) r.set(ix - pad, iy - pad, ix + pad, iy + pad) else r.union(ix - pad, iy - pad, ix + pad, iy + pad)
                    }
                    // Alle gebündelten Zwischenpunkte übernehmen, sonst wird die Linie eckig
                    for (h in 0 until event.historySize) {
                        val hx = event.getHistoricalX(i, h)
                        val hy = event.getHistoricalY(i, h)
                        s.pts.add(floatArrayOf(hx / sc, hy / sc, if (type == MotionEvent.TOOL_TYPE_FINGER) 0.5f else event.getHistoricalPressure(i, h)))
                        grow(hx, hy)
                    }
                    s.pts.add(floatArrayOf(event.getX(i) / sc, event.getY(i) / sc, pressure(event, i, type)))
                    grow(event.getX(i), event.getY(i))
                    invalidate(r)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                if (event.getPointerId(i) == activePointer) finish()
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

    private fun pressure(e: MotionEvent, i: Int, type: Int) = if (type == MotionEvent.TOOL_TYPE_FINGER) 0.5f else e.getPressure(i)

    private fun finish() {
        if (erasing) {
            if (erasedNow.isNotEmpty()) ops.add(Op(null, erasedNow))
        } else {
            current?.let {
                doc.strokes.add(it)
                ops.add(Op(it, emptyList()))
                bitmapCanvas?.let { c -> Ink.drawStroke(c, it, scale) }
            }
        }
        current = null
        activePointer = -1
        erasing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        current?.let { Ink.drawStroke(canvas, it, scale) }
    }
}

/** Vollbild-Zeichnen: oben Fertig/Löschen, in der Mitte die Seite, unten Werkzeuge und Farben. */
class InkScreen(
    ctx: Context,
    val doc: InkDoc,
    private val onDone: () -> Unit,
    private val onDelete: () -> Unit,
) : LinearLayout(ctx) {

    private val draw = DrawView(ctx, doc)
    private val toolSetters = HashMap<String, (Boolean) -> Unit>()
    private val colorDots = ArrayList<ColorDot>()

    /** Farbpunkt mit Ring, wenn ausgewählt. */
    private inner class ColorDot(c: Context, val key: String) : View(c) {
        var selectedDot = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            p.style = Paint.Style.FILL
            p.color = Ink.resolve(key)
            canvas.drawCircle(cx, cy, context.dp(10).toFloat(), p)
            if (selectedDot) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = context.dp(2).toFloat()
                p.color = Ui.ACCENT
                canvas.drawCircle(cx, cy, context.dp(15).toFloat(), p)
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        isClickable = true // verschluckt Berührungen, die sonst die Notiz darunter erreichen würden

        val top = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(10), 0, ctx.dp(14), 0)
                background = Ui.ripple(ctx, null, 8)
                isClickable = true
                addView(Ui.icon(ctx, R.drawable.ic_chevron_left, Ui.ACCENT))
                addView(Ui.label(ctx, "Fertig", 15f, Ui.ACCENT, Ui.sansMedium))
                setOnClickListener { onDone() }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(44)))
            addView(Ui.label(ctx, "Zeichnung", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.iconButton(ctx, R.drawable.ic_trash, "Zeichnung löschen", false) { confirmDelete() }, LayoutParams(ctx.dp(44), ctx.dp(44)))
        }
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(52)))
        addView(hairline(ctx, false))

        val page = FrameLayout(ctx).apply {
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            addView(draw, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(ScrollView(ctx).apply { addView(page) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(hairline(ctx, false))
        addView(buildBar(ctx), LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(64)))
        selectTool("pen")
        selectColor("text")
    }

    private fun buildBar(ctx: Context): View {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            setBackgroundColor(Ui.SURFACE)
        }

        fun toolButton(key: String, res: Int, desc: String) {
            val iv = ImageView(ctx).apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = desc
                isClickable = true
                setOnClickListener { selectTool(key) }
            }
            toolSetters[key] = { on ->
                iv.setColorFilter(if (on) Ui.ACCENT else Ui.TEXT2)
                iv.background = Ui.ripple(ctx, if (on) Ui.round(ctx, Ui.ACCENT_SOFT, 8) else null, 8)
            }
            row.addView(iv, LayoutParams(ctx.dp(44), ctx.dp(44)))
        }

        fun divider() {
            row.addView(View(ctx).apply { setBackgroundColor(Ui.BORDER) }, LayoutParams(1, ctx.dp(24)).apply {
                leftMargin = ctx.dp(8)
                rightMargin = ctx.dp(8)
            })
        }

        toolButton("pen", R.drawable.ic_pen, "Stift")
        toolButton("marker", R.drawable.ic_marker, "Textmarker")
        toolButton("eraser", R.drawable.ic_eraser, "Radierer")
        divider()
        for (key in listOf("text", "#1d4ed8", "#d12b2b", "#16a34a", "#e8b400")) {
            val dot = ColorDot(ctx, key).apply {
                isClickable = true
                contentDescription = "Farbe"
                setOnClickListener { selectColor(key) }
            }
            colorDots.add(dot)
            row.addView(dot, LayoutParams(ctx.dp(40), ctx.dp(44)))
        }
        divider()
        row.addView(Ui.iconButton(ctx, R.drawable.ic_undo, "Rückgängig", false) { draw.undo() }, LayoutParams(ctx.dp(44), ctx.dp(44)))
        row.addView(Ui.iconButton(ctx, R.drawable.ic_plus, "Seite verlängern", false) { draw.extend() }, LayoutParams(ctx.dp(44), ctx.dp(44)))

        // Für Geräte ohne Stift: mit dem Finger zeichnen (dann scrollt der Finger nicht mehr)
        val finger = Ui.label(ctx, "Finger", 14f, Ui.TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            isClickable = true
            background = Ui.ripple(ctx, null, 8)
        }
        finger.setOnClickListener {
            draw.fingerDraws = !draw.fingerDraws
            finger.setTextColor(if (draw.fingerDraws) Ui.ACCENT else Ui.TEXT2)
            finger.background = Ui.ripple(ctx, if (draw.fingerDraws) Ui.round(ctx, Ui.ACCENT_SOFT, 8) else null, 8)
        }
        row.addView(finger, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(44)))

        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun selectTool(key: String) {
        draw.tool = key
        for ((k, set) in toolSetters) set(k == key)
    }

    private fun selectColor(key: String) {
        draw.color = key
        for (d in colorDots) {
            d.selectedDot = d.key == key
            d.invalidate()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(context)
            .setTitle("Zeichnung löschen?")
            .setMessage("Die Zeichnung wird aus der Notiz entfernt und die Dateien werden gelöscht.")
            .setPositiveButton("Löschen") { _, _ -> onDelete() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
