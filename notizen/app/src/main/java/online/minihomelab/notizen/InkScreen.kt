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

/** Farbpunkt mit Ring, wenn ausgewählt. */
class InkColorDot(c: Context, val key: String) : View(c) {
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

val INK_COLORS = listOf("text", "#1d4ed8", "#d12b2b", "#16a34a", "#e8b400")

/**
 * Werkzeugleiste fürs Zeichnen direkt in der Notiz: Stift, Textmarker, Radierer, Farben, Rückgängig,
 * Seite verlängern, Vollbild und Fertig.
 */
class InkTools(
    ctx: Context,
    private val onTool: (String) -> Unit,
    private val onColor: (String) -> Unit,
    private val onUndo: () -> Unit,
    private val onExtend: () -> Unit,
    private val onFull: () -> Unit,
    private val onDone: () -> Unit,
    private val isInline: Boolean,
) : HorizontalScrollView(ctx) {

    private val toolSetters = HashMap<String, (Boolean) -> Unit>()
    private val dots = ArrayList<InkColorDot>()

    /** Nur im Vollbild: ob mit dem Finger gezeichnet wird (für die Anzeige des Schalters). */
    var fingerOn: () -> Boolean = { false }

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Ui.SURFACE)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
        }

        fun toolButton(key: String, res: Int, desc: String) {
            val iv = ImageView(ctx).apply {
                setImageResource(res)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = desc
                isClickable = true
                setOnClickListener { onTool(key) }
            }
            toolSetters[key] = { on ->
                iv.setColorFilter(if (on) Ui.ACCENT else Ui.TEXT2)
                iv.background = Ui.ripple(ctx, if (on) Ui.round(ctx, Ui.ACCENT_SOFT, 8) else null, 8)
            }
            row.addView(iv, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        }

        fun divider() {
            row.addView(View(ctx).apply { setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(1, ctx.dp(24)).apply {
                leftMargin = ctx.dp(8)
                rightMargin = ctx.dp(8)
            })
        }

        toolButton("pen", R.drawable.ic_pen, "Stift")
        toolButton("marker", R.drawable.ic_marker, "Textmarker")
        toolButton("eraser", R.drawable.ic_eraser, "Radierer")
        divider()
        for (key in INK_COLORS) {
            val dot = InkColorDot(ctx, key).apply {
                isClickable = true
                contentDescription = "Farbe"
                setOnClickListener { onColor(key) }
            }
            dots.add(dot)
            row.addView(dot, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(44)))
        }
        divider()
        row.addView(Ui.iconButton(ctx, R.drawable.ic_undo, "Rückgängig", false) { onUndo() }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        row.addView(Ui.iconButton(ctx, R.drawable.ic_plus, "Seite verlängern", false) { onExtend() }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        if (isInline) {
            row.addView(Ui.iconButton(ctx, R.drawable.ic_expand, "Vollbild", false) { onFull() }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            row.addView(Ui.label(ctx, "Fertig", 14f, Ui.ACCENT, Ui.sansMedium).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
                isClickable = true
                background = Ui.ripple(ctx, null, 8)
                setOnClickListener { onDone() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ctx.dp(44)))
        } else {
            // Vollbild: für Geräte ohne Stift mit dem Finger zeichnen (dann scrollt der Finger nicht mehr)
            val finger = Ui.label(ctx, "Finger", 14f, Ui.TEXT2).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                isClickable = true
                background = Ui.ripple(ctx, null, 8)
            }
            finger.setOnClickListener {
                onFull()
                val on = fingerOn()
                finger.setTextColor(if (on) Ui.ACCENT else Ui.TEXT2)
                finger.background = Ui.ripple(ctx, if (on) Ui.round(ctx, Ui.ACCENT_SOFT, 8) else null, 8)
            }
            row.addView(finger, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ctx.dp(44)))
        }

        addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    fun sync(tool: String, color: String) {
        for ((k, set) in toolSetters) set(k == tool)
        for (d in dots) {
            d.selectedDot = d.key == color
            d.invalidate()
        }
    }
}

/**
 * Vollbild-Zeichenfläche: nur der Stift zeichnet (Finger scrollt = einfache Palm Rejection), die Strichstärke folgt
 * dem Druck, die Stifttaste (S Pen) radiert, solange sie gedrückt ist. Koordinaten sind Seiteneinheiten (Breite = Ink.W).
 */
class DrawView(ctx: Context, val doc: InkDoc) : View(ctx) {
    val model = InkModel(doc)
    var fingerDraws = false

    private var activePointer = -1
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
        if (model.undo()) {
            rebuild()
            invalidate()
        }
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

    private fun pressure(e: MotionEvent, i: Int, type: Int) = if (type == MotionEvent.TOOL_TYPE_FINGER) 0.5f else e.getPressure(i)

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
                val changed = model.down(event.getX(i) / sc, event.getY(i) / sc, pressure(event, i, type), type == MotionEvent.TOOL_TYPE_ERASER || button)
                if (changed) rebuild()
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointer)
                if (i == -1) return true
                val type = event.getToolType(i)
                val r = Rect()
                val pad = 40
                var erased = false
                fun grow(px: Float, py: Float) {
                    val ix = px.toInt()
                    val iy = py.toInt()
                    if (r.isEmpty) r.set(ix - pad, iy - pad, ix + pad, iy + pad) else r.union(ix - pad, iy - pad, ix + pad, iy + pad)
                }
                // Alle gebündelten Zwischenpunkte übernehmen, sonst wird die Linie eckig
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i, h)
                    val hy = event.getHistoricalY(i, h)
                    val p = if (type == MotionEvent.TOOL_TYPE_FINGER) 0.5f else event.getHistoricalPressure(i, h)
                    if (model.move(hx / sc, hy / sc, p)) erased = true
                    grow(hx, hy)
                }
                if (model.move(event.getX(i) / sc, event.getY(i) / sc, pressure(event, i, type))) erased = true
                grow(event.getX(i), event.getY(i))
                if (erased) {
                    rebuild()
                    invalidate()
                } else {
                    invalidate(r)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                if (event.getPointerId(i) == activePointer) {
                    val stroke = model.current
                    if (model.up()) {
                        if (model.lastWasErase) rebuild() else stroke?.let { s -> bitmapCanvas?.let { c -> Ink.drawStroke(c, s, scale) } }
                    }
                    activePointer = -1
                    invalidate()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // Das System hat die Berührung als Handballen erkannt: Strich verwerfen
                model.cancel()
                activePointer = -1
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        model.current?.let { Ink.drawStroke(canvas, it, scale) }
    }
}

/** Vollbild-Zeichnen: oben Fertig/Löschen, in der Mitte die Seite, unten die Werkzeuge. */
class InkScreen(
    ctx: Context,
    val doc: InkDoc,
    private val onDone: () -> Unit,
    private val onDelete: () -> Unit,
) : LinearLayout(ctx) {

    private val draw = DrawView(ctx, doc)
    private lateinit var tools: InkTools

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
        tools = InkTools(
            ctx,
            onTool = { draw.model.tool = it; tools.sync(draw.model.tool, draw.model.color) },
            onColor = { draw.model.color = it; tools.sync(draw.model.tool, draw.model.color) },
            onUndo = { draw.undo() },
            onExtend = { draw.extend() },
            onFull = { draw.fingerDraws = !draw.fingerDraws },
            onDone = onDone,
            isInline = false,
        )
        tools.fingerOn = { draw.fingerDraws }
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(64)))
        tools.sync(draw.model.tool, draw.model.color)
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
