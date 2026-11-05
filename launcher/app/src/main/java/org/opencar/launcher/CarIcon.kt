package org.opencar.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Auto visto desde arriba, apuntando hacia donde avanza (bearing en grados, 0 = norte del mapa). */
object CarIcon {
    fun draw(c: Canvas, x: Float, y: Float, bearing: Float, sc: Float, color: Int, k: Float = 1f) {
        val l = 25f * sc * k; val w = 13.5f * sc * k
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save(); c.rotate(bearing, x, y)
        // sombra
        p.style = Paint.Style.FILL; p.color = 0x55000000
        c.drawRoundRect(RectF(x - w - 2f * sc, y - l + 3f * sc, x + w + 2f * sc, y + l + 4f * sc), 10f * sc, 10f * sc, p)
        // carrocería: borde blanco + color
        val body = RectF(x - w, y - l, x + w, y + l)
        p.color = Color.WHITE; c.drawRoundRect(RectF(body.left - 2.5f * sc, body.top - 2.5f * sc, body.right + 2.5f * sc, body.bottom + 2.5f * sc), 11f * sc, 11f * sc, p)
        p.color = color; c.drawRoundRect(body, 9f * sc, 9f * sc, p)
        // parabrisas, techo y luneta
        p.color = 0xCC0B1A24.toInt()
        c.drawRoundRect(RectF(x - w * 0.74f, y - l * 0.46f, x + w * 0.74f, y - l * 0.08f), 4f * sc, 4f * sc, p)
        c.drawRoundRect(RectF(x - w * 0.68f, y + l * 0.40f, x + w * 0.68f, y + l * 0.66f), 4f * sc, 4f * sc, p)
        p.color = 0x33FFFFFF; c.drawRoundRect(RectF(x - w * 0.62f, y - l * 0.04f, x + w * 0.62f, y + l * 0.36f), 4f * sc, 4f * sc, p)
        // faros delanteros y luces traseras
        p.color = 0xFFFFF3B0.toInt()
        c.drawOval(RectF(x - w * 0.86f, y - l * 0.98f, x - w * 0.34f, y - l * 0.80f), p)
        c.drawOval(RectF(x + w * 0.34f, y - l * 0.98f, x + w * 0.86f, y - l * 0.80f), p)
        p.color = 0xFFFF3B30.toInt()
        c.drawOval(RectF(x - w * 0.86f, y + l * 0.82f, x - w * 0.36f, y + l * 0.97f), p)
        c.drawOval(RectF(x + w * 0.36f, y + l * 0.82f, x + w * 0.86f, y + l * 0.97f), p)
        c.restore()
    }
}
