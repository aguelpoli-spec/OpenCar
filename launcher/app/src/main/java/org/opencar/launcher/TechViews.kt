package org.opencar.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Base de las pantallas "tecnológicas": fondo animado, color de acento y ondas al tocar. */
abstract class TechBase(c: Context, private val prefs: SharedPreferences) : View(c) {
    companion object { @JvmStatic var base = 0xFF4DD0E1.toInt() }
    private val accents = intArrayOf(0, 0xFF69F0AE.toInt(), 0xFFFF4081.toInt(), 0xFFFFC107.toInt(), 0xFFB388FF.toInt())
    private var accentIdx = prefs.getInt("tech_accent", 0).coerceIn(0, accents.size - 1)
    protected val acc get() = if (accentIdx == 0) base else accents[accentIdx]
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val t0 = SystemClock.uptimeMillis()
    private class Rip(val x: Float, val y: Float, val t: Long)
    private val rips = ArrayList<Rip>()
    var onLong: (() -> Unit)? = null
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private val longRun = Runnable { onLong?.invoke(); downT = 0L }

    /** Las subclases pueden consumir el toque (por ejemplo, un botón dibujado). */
    protected open fun hit(x: Float, y: Float): Boolean = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rips.add(Rip(e.x, e.y, now)); downT = now; downX = e.x; downY = e.y
                postDelayed(longRun, 800)
            }
            MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > 40f) removeCallbacks(longRun)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                if (downT != 0L && now - downT < 450 && !hit(e.x, e.y)) {
                    accentIdx = (accentIdx + 1) % accents.size
                    prefs.edit().putInt("tech_accent", accentIdx).apply()
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRun)
        }
        return true
    }

    protected fun bg(cv: Canvas, w: Float, h: Float) {
        val t = SystemClock.uptimeMillis() - t0
        cv.drawColor(0xFF02050C.toInt())
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        p.color = (acc and 0x00FFFFFF) or 0x16000000
        val step = max(w, h) / 22f
        var x = -((t / 40f) % step)
        while (x < w) { cv.drawLine(x, 0f, x, h, p); x += step }
        var y = ((t / 50f) % step)
        while (y < h) { cv.drawLine(0f, y, w, y, p); y += step }
        // haz que barre la pantalla
        val by = ((t / 9f) % (h + 160f)) - 80f
        p.style = Paint.Style.FILL
        for (i in 0 until 5) { p.color = (acc and 0x00FFFFFF) or ((14 - i * 3) shl 24); cv.drawRect(0f, by - i * 14f, w, by - i * 14f + 14f, p) }
        // viñeta
        p.color = 0x55000000; cv.drawRect(0f, 0f, w, h * 0.06f, p); cv.drawRect(0f, h * 0.94f, w, h, p)
    }

    protected fun ripples(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        val it = rips.iterator()
        p.style = Paint.Style.STROKE
        while (it.hasNext()) {
            val r = it.next()
            val age = now - r.t
            if (age > 1000) { it.remove(); continue }
            val f = age / 1000f
            p.strokeWidth = 5f * (1f - f) + 1f
            p.color = (acc and 0x00FFFFFF) or (((1f - f) * 200).toInt() shl 24)
            cv.drawCircle(r.x, r.y, 20f + f * 300f, p)
            p.color = (acc and 0x00FFFFFF) or (((1f - f) * 110).toInt() shl 24)
            cv.drawCircle(r.x, r.y, 10f + f * 170f, p)
        }
    }

    /** Texto con brillo (halo del color de acento). */
    protected fun glowText(cv: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, glow: Int = acc, light: Boolean = true) {
        p.style = Paint.Style.FILL_AND_STROKE
        p.textSize = size
        p.typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif-medium", Typeface.NORMAL)
        p.textAlign = Paint.Align.LEFT
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = size * 0.16f; p.color = (glow and 0x00FFFFFF) or 0x1E000000; cv.drawText(s, x, y, p)
        p.strokeWidth = size * 0.08f; p.color = (glow and 0x00FFFFFF) or 0x40000000; cv.drawText(s, x, y, p)
        p.style = Paint.Style.FILL; p.color = color; cv.drawText(s, x, y, p)
    }

    protected fun glowArc(cv: Canvas, rect: RectF, start: Float, sweep: Float, width: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = width * 2.6f; p.color = (color and 0x00FFFFFF) or 0x1C000000; cv.drawArc(rect, start, sweep, false, p)
        p.strokeWidth = width * 1.6f; p.color = (color and 0x00FFFFFF) or 0x38000000; cv.drawArc(rect, start, sweep, false, p)
        p.strokeWidth = width; p.color = color; cv.drawArc(rect, start, sweep, false, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    protected fun label(cv: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0.12f) {
        p.style = Paint.Style.FILL; p.textSize = size; p.color = color; p.textAlign = align
        p.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        p.letterSpacing = spacing
        cv.drawText(s, x, y, p)
        p.letterSpacing = 0f; p.textAlign = Paint.Align.LEFT
    }
}

/** Reloj grande: anillos de segundos/minutos/horas, hora con brillo, fecha en números y temperatura. */
class TechClock(c: Context, prefs: SharedPreferences) : TechBase(c, prefs) {
    var temp = ""
    var info = ""
    var onClose: (() -> Unit)? = null
    private val closeRect = RectF()

    override fun hit(x: Float, y: Float): Boolean {
        if (closeRect.contains(x, y)) { onClose?.invoke(); return true }
        return false
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg(cv, w, h)
        val now = Calendar.getInstance()
        val ms = now.get(Calendar.MILLISECOND)
        val sec = now.get(Calendar.SECOND) + ms / 1000f
        val min = now.get(Calendar.MINUTE) + sec / 60f
        val hr = (now.get(Calendar.HOUR_OF_DAY) % 12) + min / 60f
        val t = SystemClock.uptimeMillis() - t0
        val cx = w / 2f; val cy = h * 0.47f
        val r = min(w * 0.40f, h * 0.44f)
        // marcas de segundos
        p.style = Paint.Style.STROKE
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90.0)
            val big = i % 5 == 0
            val on = i <= sec.toInt()
            p.strokeWidth = if (big) 4f else 2f
            p.color = if (on) acc else 0x33FFFFFF
            val r2 = r - (if (big) 24f else 13f)
            cv.drawLine(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, p)
        }
        // anillo punteado que gira
        p.strokeWidth = 2f; p.color = (acc and 0x00FFFFFF) or 0x55000000
        p.pathEffect = DashPathEffect(floatArrayOf(6f, 14f), (t / 30f) % 20f)
        cv.drawCircle(cx, cy, r + 16f, p)
        p.pathEffect = null
        // arcos: segundos, minutos, horas
        val a1 = RectF(cx - (r - 38f), cy - (r - 38f), cx + (r - 38f), cy + (r - 38f))
        val a2 = RectF(cx - (r - 58f), cy - (r - 58f), cx + (r - 58f), cy + (r - 58f))
        val a3 = RectF(cx - (r - 78f), cy - (r - 78f), cx + (r - 78f), cy + (r - 78f))
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = 0x22FFFFFF
        cv.drawArc(a1, 0f, 360f, false, p); cv.drawArc(a2, 0f, 360f, false, p); cv.drawArc(a3, 0f, 360f, false, p)
        glowArc(cv, a1, -90f, sec / 60f * 360f, 6f, acc)
        glowArc(cv, a2, -90f, min / 60f * 360f, 5f, 0xFFFFFFFF.toInt())
        glowArc(cv, a3, -90f, hr / 12f * 360f, 5f, (acc and 0x00FFFFFF) or 0xAA000000.toInt())
        // hora
        val hh = String.format("%02d", now.get(Calendar.HOUR_OF_DAY))
        val mm = String.format("%02d", now.get(Calendar.MINUTE))
        val size = r * 0.50f
        p.textSize = size; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val wh = p.measureText(hh); val wc = p.measureText(":"); val wm = p.measureText(mm)
        val x0 = cx - (wh + wc + wm) / 2f; val base = cy + size * 0.30f
        glowText(cv, hh, x0, base, size, Color.WHITE)
        val pulse = (0.35f + 0.65f * (0.5f + 0.5f * sin(t / 300.0).toFloat()))
        glowText(cv, ":", x0 + wh, base, size, (acc and 0x00FFFFFF) or ((pulse * 255).toInt() shl 24))
        glowText(cv, mm, x0 + wh + wc, base, size, Color.WHITE)
        // fecha en números
        val date = String.format("%02d / %02d / %04d", now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.MONTH) + 1, now.get(Calendar.YEAR))
        label(cv, date, cx, base + r * 0.21f, r * 0.12f, 0xDDFFFFFF.toInt(), false, Paint.Align.CENTER, 0.16f)
        if (temp.isNotEmpty()) label(cv, temp, cx, base + r * 0.40f, r * 0.15f, acc, true, Paint.Align.CENTER, 0.1f)
        if (info.isNotEmpty()) label(cv, info, cx, h - 46f, 17f, 0x88FFFFFF.toInt(), false, Paint.Align.CENTER, 0.04f)
        // botón de cerrar
        closeRect.set(w - 170f, h - 62f, w - 20f, h - 14f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x66FFFFFF
        cv.drawRoundRect(closeRect, 24f, 24f, p)
        label(cv, "CERRAR  ✕", closeRect.centerX(), closeRect.centerY() + 6f, 16f, 0xCCFFFFFF.toInt(), false, Paint.Align.CENTER, 0.1f)
        label(cv, "TOCA PARA CAMBIAR EL COLOR · MANTÉN PULSADO PARA CERRAR", cx, h - 20f, 11f, 0x66FFFFFF.toInt(), false, Paint.Align.CENTER, 0.12f)
        ripples(cv)
        postInvalidateOnAnimation()
    }
}

/** Modo manejo: velocímetro de arco con brillo, odómetro, viaje, rumbo, altura y temperatura. */
class TechDrive(c: Context, prefs: SharedPreferences) : TechBase(c, prefs) {
    var speed = 0f
    var unit = "km/h"
    var over = false
    var maxScale = 180f
    var limitFrac = -1f
    var odo = ""
    var trip = ""
    var tripTime = ""
    var heading = "--"
    var alt = "--"
    var temp = "--"
    private var disp = 0f

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        bg(cv, w, h)
        val t = SystemClock.uptimeMillis() - t0
        disp += (speed - disp) * 0.12f
        val col = if (over) 0xFFFF5A4F.toInt() else acc
        val cx = w / 2f; val cy = h * 0.40f
        val r = min(w * 0.27f, h * 0.31f)
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        // anillo exterior punteado
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = (col and 0x00FFFFFF) or 0x55000000
        p.pathEffect = DashPathEffect(floatArrayOf(5f, 12f), (t / 25f) % 17f)
        cv.drawCircle(cx, cy, r + 26f, p)
        p.pathEffect = null
        // marcas
        for (i in 0..36) {
            val a = Math.toRadians(135.0 + i * 7.5)
            val big = i % 4 == 0
            val on = i / 36f <= (disp / maxScale)
            p.strokeWidth = if (big) 3f else 1.5f
            p.color = if (on) col else 0x33FFFFFF
            val r1 = r + 12f; val r2 = r + (if (big) 28f else 20f)
            cv.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, p)
        }
        // arco base y arco de velocidad
        p.style = Paint.Style.STROKE; p.strokeWidth = 16f; p.color = 0x24FFFFFF; p.strokeCap = Paint.Cap.ROUND
        cv.drawArc(rect, 135f, 270f, false, p); p.strokeCap = Paint.Cap.BUTT
        val f = (disp / maxScale).coerceIn(0f, 1f)
        val glow = if (over) (0.6f + 0.4f * sin(t / 120.0).toFloat()) else 1f
        if (f > 0.004f) glowArc(cv, rect, 135f, 270f * f, 16f, (col and 0x00FFFFFF) or ((glow * 255).toInt() shl 24))
        // límite de velocidad
        if (limitFrac in 0f..1f) {
            val a = Math.toRadians(135.0 + 270.0 * limitFrac)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = 0xFFFFC107.toInt()
            cv.drawLine(cx + cos(a).toFloat() * (r - 16f), cy + sin(a).toFloat() * (r - 16f), cx + cos(a).toFloat() * (r + 34f), cy + sin(a).toFloat() * (r + 34f), p)
        }
        // número
        val s = disp.toInt().coerceAtLeast(0).toString()
        val size = r * 0.95f
        p.textSize = size; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val sw = p.measureText(s)
        glowText(cv, s, cx - sw / 2f, cy + size * 0.28f, size, if (over) 0xFFFF8A80.toInt() else Color.WHITE, col)
        label(cv, unit.uppercase(), cx, cy + size * 0.28f + r * 0.30f, r * 0.15f, 0xAAFFFFFF.toInt(), false, Paint.Align.CENTER, 0.3f)
        // paneles laterales
        val lx = w * 0.045f; val rx = w * 0.955f
        panel(cv, lx, h * 0.12f, "ODÓMETRO", odo, false, col)
        panel(cv, lx, h * 0.27f, "VIAJE", trip, false, col)
        panel(cv, lx, h * 0.42f, "TIEMPO", tripTime, false, col)
        panel(cv, rx, h * 0.12f, "RUMBO", heading, true, col)
        panel(cv, rx, h * 0.27f, "ALTURA", alt, true, col)
        panel(cv, rx, h * 0.42f, "TEMPERATURA", temp, true, col)
        ripples(cv)
        postInvalidateOnAnimation()
    }

    private fun panel(cv: Canvas, x: Float, y: Float, title: String, value: String, right: Boolean, col: Int) {
        val al = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        label(cv, title, x, y, 12f, 0x99FFFFFF.toInt(), true, al, 0.2f)
        p.style = Paint.Style.FILL; p.textSize = 30f; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        p.textAlign = al; p.color = Color.WHITE
        cv.drawText(value, x, y + 34f, p)
        p.textAlign = Paint.Align.LEFT
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = (col and 0x00FFFFFF) or 0x99000000.toInt()
        val len = 70f
        cv.drawLine(if (right) x - len else x, y + 46f, if (right) x else x + len, y + 46f, p)
    }
}
