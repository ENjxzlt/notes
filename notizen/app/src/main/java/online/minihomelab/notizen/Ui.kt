package online.minihomelab.notizen

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Farben und Schriften wie in Slotwise: sachlich, ein Blau-Akzent, IBM Plex, feine Linien statt Schatten. */
object Ui {
    var dark = false
    var BG = 0
    var SURFACE = 0
    var SUBTLE = 0
    var BORDER = 0
    var BORDER_STRONG = 0
    var TEXT = 0
    var TEXT2 = 0
    var MUTED = 0
    var FAINT = 0
    var ACCENT = 0 // Text und Linien in Akzentfarbe
    var ACCENT_BG = 0 // gefüllte Flächen (Schaltfläche, Häkchen)
    var ACCENT_SOFT = 0
    var RIPPLE = 0
    val ON_ACCENT = Color.WHITE

    lateinit var sans: Typeface
    lateinit var sansMedium: Typeface
    lateinit var sansSemi: Typeface
    lateinit var sansItalic: Typeface
    lateinit var mono: Typeface
    lateinit var monoMedium: Typeface
    private var fontsLoaded = false

    /** Beim Start jeder Activity aufrufen: Hell/Dunkel folgt dem System. */
    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (dark) {
            BG = rgb(0x111113); SURFACE = rgb(0x18181b); SUBTLE = rgb(0x222226)
            BORDER = rgb(0x2a2a2f); BORDER_STRONG = rgb(0x3a3a40)
            TEXT = rgb(0xf4f4f5); TEXT2 = rgb(0xd4d4d8); MUTED = rgb(0xa1a1aa); FAINT = rgb(0x8b8b93)
            ACCENT = rgb(0x93b4ff); ACCENT_BG = rgb(0x2f5fe8); ACCENT_SOFT = rgb(0x1c2540)
            RIPPLE = 0x22FFFFFF
        } else {
            BG = rgb(0xfafaf9); SURFACE = rgb(0xffffff); SUBTLE = rgb(0xf4f4f5)
            BORDER = rgb(0xe4e4e7); BORDER_STRONG = rgb(0xd4d4d8)
            TEXT = rgb(0x18181b); TEXT2 = rgb(0x3f3f46); MUTED = rgb(0x52525b); FAINT = rgb(0x71717a)
            ACCENT = rgb(0x1d4ed8); ACCENT_BG = rgb(0x1d4ed8); ACCENT_SOFT = rgb(0xeef2fd)
            RIPPLE = 0x14000000
        }
        if (!fontsLoaded) {
            fun font(name: String) = Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf")
            sans = font("IBMPlexSans-Regular")
            sansMedium = font("IBMPlexSans-Medium")
            sansSemi = font("IBMPlexSans-SemiBold")
            sansItalic = font("IBMPlexSans-Italic")
            mono = font("IBMPlexMono-Regular")
            monoMedium = font("IBMPlexMono-Medium")
            fontsLoaded = true
        }
    }

    private fun rgb(v: Int) = Color.rgb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)

    /** Mischt [a] mit Anteil [t] in [b]. */
    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * t + Color.red(b) * (1 - t)).toInt(),
        (Color.green(a) * t + Color.green(b) * (1 - t)).toInt(),
        (Color.blue(a) * t + Color.blue(b) * (1 - t)).toInt(),
    )

    fun round(ctx: Context, fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = ctx.dp(radiusDp).toFloat()
            if (stroke != 0) setStroke(maxOf(1, ctx.dp(1)), stroke)
        }

    /** Berührungseffekt in der Rundung von [radiusDp]; [content] darf null sein (nur Effekt). */
    fun ripple(ctx: Context, content: Drawable?, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(RIPPLE), content, round(ctx, Color.WHITE, radiusDp))

    fun icon(ctx: Context, res: Int, tint: Int, sizeDp: Int = 20): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        }

    fun label(ctx: Context, text: CharSequence, sp: Float, color: Int, face: Typeface = sans): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            typeface = face
        }

    /** Gefüllte Hauptschaltfläche mit optionalem Symbol. */
    fun primaryButton(ctx: Context, text: String, iconRes: Int?, onClick: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple(ctx, round(ctx, ACCENT_BG, 8), 8)
            isClickable = true
            if (iconRes != null) addView(icon(ctx, iconRes, ON_ACCENT, 18))
            addView(label(ctx, text, 14f, ON_ACCENT, sansMedium).apply { setPadding(if (iconRes != null) ctx.dp(8) else 0, 0, 0, 0) })
            setOnClickListener { onClick() }
        }

    fun outlineButton(ctx: Context, text: String, onClick: () -> Unit): View =
        label(ctx, text, 14f, TEXT2).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            background = ripple(ctx, round(ctx, SURFACE, 8, BORDER_STRONG), 8)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Quadratische 44-dp-Schaltfläche mit Symbol (Touch-Ziel groß genug für Finger und Stift). */
    fun iconButton(ctx: Context, res: Int, desc: String, outlined: Boolean, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(TEXT2)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            background = ripple(ctx, if (outlined) round(ctx, SURFACE, 8, BORDER_STRONG) else null, 8)
            setOnClickListener { onClick() }
        }
}

/** Schriftart für einen Textabschnitt (die Plex-Schnitte liegen als eigene Dateien vor). */
open class FontSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = face
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = face
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun hairline(ctx: Context, vertical: Boolean): View = View(ctx).apply {
    setBackgroundColor(Ui.BORDER)
    layoutParams = if (vertical) {
        LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
    } else {
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }
}

fun askText(ctx: Context, title: String, initial: String, ok: (String) -> Unit) {
    val input = EditText(ctx).apply {
        setText(initial)
        setSingleLine()
        selectAll()
    }
    val box = FrameLayout(ctx).apply {
        setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
        addView(input)
    }
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> ok(input.text.toString()) }
        .setNegativeButton("Abbrechen", null)
        .create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}
