package org.opencar.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Widget del sol: curva del día entre el amanecer y el atardecer, con un punto que se mueve,
 * las dos horas y cuánto falta para que anochezca (o amanezca). Todo se calcula con la fecha y el GPS, sin internet.
 * [times] devuelve (amanecer, atardecer) en minutos del día, hora local.
 */
class SunView(ctx: Context, private val u: Float, private val fg: Int, private val soft: Int, private val times: () -> Pair<Int, Int>,
              private val minuteNow: () -> Int = { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }) : View(ctx) {
    private val gold = 0xFFFFC94A.toInt()
    private val night = 0xFF8FB4FF.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = object : Runnable { override fun run() { invalidate(); postDelayed(this, 30_000L) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    companion object {
        fun hm(min: Int): String { val m = ((min % 1440) + 1440) % 1440; return String.format(Locale.US, "%02d:%02d", m / 60, m % 60) }
        fun dur(min: Int): String { val m = min.coerceAtLeast(0); return if (m >= 60) "${m / 60} h" + (if (m % 60 >= 5) " ${m % 60} min" else "") else "$m min" }

        /** Texto de cuánto falta: «Atardece en 2 h 10 min» / «Amanece en 7 h». */
        fun nextText(now: Int, rise: Int, set: Int): String = when {
            now in rise until set -> "Atardece en " + dur(set - now)
            now < rise -> "Amanece en " + dur(rise - now)
            else -> "Amanece en " + dur(1440 - now + rise)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 10f || h < 10f) return
        val now = minuteNow()
        val (rise, set) = times()
        val isDay = now in rise until set
        val padX = 10f * u
        // títulos
        p.style = Paint.Style.FILL; p.shader = null
        p.color = soft; p.textSize = 10.5f * u; p.isFakeBoldText = false; p.textAlign = Paint.Align.LEFT
        c.drawText("Sol", padX, 14f * u, p)
        p.color = if (isDay) gold else night; p.textSize = 12.5f * u; p.isFakeBoldText = true; p.textAlign = Paint.Align.RIGHT
        c.drawText(nextText(now, rise, set), w - padX, 14f * u, p)
        // curva
        val x0 = padX + 6f * u; val x1 = w - padX - 6f * u
        val base = h - 22f * u; val amp = (base - 26f * u).coerceAtLeast(10f * u)
        fun cx(t: Float) = x0 + (x1 - x0) * t
        fun cy(t: Float) = base - amp * sin(PI * t).toFloat()
        val full = Path(); full.moveTo(cx(0f), cy(0f)); for (i in 1..48) full.lineTo(cx(i / 48f), cy(i / 48f))
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2f * u; p.strokeCap = Paint.Cap.ROUND
        p.color = soft and 0x00FFFFFF or 0x55000000; c.drawPath(full, p)
        val f = if (isDay) (now - rise).toFloat() / (set - rise).coerceAtLeast(1) else if (now >= set) 1f else 0f
        if (f > 0f) {
            val part = Path(); part.moveTo(cx(0f), cy(0f)); val n = (48 * f).toInt().coerceAtLeast(1)
            for (i in 1..n) part.lineTo(cx(f * i / n), cy(f * i / n))
            p.color = gold; p.strokeWidth = 2.6f * u; c.drawPath(part, p)
        }
        // horizonte
        p.color = soft and 0x00FFFFFF or 0x66000000; p.strokeWidth = 1f * u; c.drawLine(padX, base, w - padX, base, p)
        // el sol (o la luna, de noche, en el borde)
        val tx = if (isDay) f else if (now >= set) 1f else 0f
        val dx = cx(tx); val dy = if (isDay) cy(f) else base
        p.style = Paint.Style.FILL
        if (isDay) {
            p.shader = RadialGradient(dx, dy, 13f * u, 0x88FFC94A.toInt(), 0x00FFC94A, Shader.TileMode.CLAMP); c.drawCircle(dx, dy, 13f * u, p)
            p.shader = null; p.color = gold; c.drawCircle(dx, dy, 5.5f * u, p)
        } else { p.shader = null; p.color = night; c.drawCircle(dx, dy, 4.5f * u, p) }
        // horas
        p.shader = null; p.isFakeBoldText = true; p.textSize = 12f * u; p.color = fg
        p.textAlign = Paint.Align.LEFT; c.drawText("↑ " + hm(rise), padX, h - 5f * u, p)
        p.textAlign = Paint.Align.RIGHT; c.drawText("↓ " + hm(set), w - padX, h - 5f * u, p)
    }
}
