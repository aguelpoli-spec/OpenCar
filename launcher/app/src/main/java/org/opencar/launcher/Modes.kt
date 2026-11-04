package org.opencar.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random

/** Modos de la pantalla: cada uno trae colores, forma de iconos, fondos propios y animaciones. */
object Modes {
    class M(
        val id: String, val name: String, val sub: String,
        val acc: Int, val acc2: Int, val bgA: Int, val bgB: Int, val tint: Int,
        val shape: Int,            // 0 cuadrado redondeado, 1 círculo, 2 suave, 3 corte deportivo
        val pressScale: Float, val pressMs: Long, val bouncy: Boolean,
        val clockFont: String, val iconTint: Int
    )

    val list = listOf(
        M("ios", "iOS", "Claro y limpio", 0xFF4DD0E1.toInt(), 0xFF0A84FF.toInt(), 0xFF1B3A66.toInt(), 0xFF050A14.toInt(), 0xFF0E1A32.toInt(), 0, 0.94f, 70, false, "sans-serif-light", 0),
        M("exec", "Ejecutivo", "Sobrio, azul y dorado", 0xFFD4AF37.toInt(), 0xFFB8860B.toInt(), 0xFF1B2340.toInt(), 0xFF0B1020.toInt(), 0xFF101830.toInt(), 2, 0.97f, 90, false, "serif", 0xFFD4AF37.toInt()),
        M("pink", "Pink Rouse", "Rosa y violeta suave", 0xFFFF4F9A.toInt(), 0xFFB388FF.toInt(), 0xFFB24BFF.toInt(), 0xFF3A0F5C.toInt(), 0xFF3A1250.toInt(), 1, 0.90f, 170, true, "sans-serif-medium", 0xFFFFFFFF.toInt()),
        M("trip", "Viaje", "Atardecer y carretera", 0xFFFF9F43.toInt(), 0xFF26C6DA.toInt(), 0xFFC2417A.toInt(), 0xFF2B1B4E.toInt(), 0xFF2A1838.toInt(), 4, 0.95f, 100, false, "sans-serif-light", 0xFFFFE0B2.toInt()),
        M("sport", "Sport / Carrera", "Carbono y rojo", 0xFFFF1744.toInt(), 0xFFFFFFFF.toInt(), 0xFF2A0A10.toInt(), 0xFF0A0A0C.toInt(), 0xFF140808.toInt(), 3, 0.90f, 50, false, "sans-serif-condensed", 0xFFFF1744.toInt())
    )

    fun get(id: String?): M = list.firstOrNull { it.id == id } ?: list[0]

    private fun lg(x0: Float, y0: Float, x1: Float, y1: Float, a: Int, b: Int) = LinearGradient(x0, y0, x1, y1, a, b, Shader.TileMode.CLAMP)

    /** Fondo dibujado por código (3 variantes por modo). El modo iOS usa degradados. */
    fun wallpaper(id: String, variant: Int, w: Int = 1024, h: Int = 600): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val v = ((variant % 3) + 3) % 3
        val rnd = Random(1000L * (v + 1) + id.hashCode())
        val wf = w.toFloat(); val hf = h.toFloat()
        when (id) {
            "exec" -> {
                val pal = arrayOf(intArrayOf(0xFF0B1020.toInt(), 0xFF1B2340.toInt(), 0xFFD4AF37.toInt()),
                    intArrayOf(0xFF0E0E10.toInt(), 0xFF2A2A2E.toInt(), 0xFFC0C0C0.toInt()),
                    intArrayOf(0xFF04201C.toInt(), 0xFF0E3B34.toInt(), 0xFFD4AF37.toInt()))[v]
                p.shader = lg(0f, 0f, wf, hf, pal[0], pal[1]); c.drawRect(0f, 0f, wf, hf, p); p.shader = null
                p.shader = RadialGradient(wf * 0.82f, hf * 0.12f, hf * 0.9f, (pal[2] and 0x00FFFFFF) or 0x44000000, 0x00000000, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, wf, hf, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = (pal[2] and 0x00FFFFFF) or 0x1A000000
                var x = -hf
                while (x < wf) { c.drawLine(x, hf, x + hf * 0.7f, 0f, p); x += 46f }
                p.strokeWidth = 2f; p.color = (pal[2] and 0x00FFFFFF) or 0x66000000
                c.drawRect(22f, 22f, wf - 22f, hf - 22f, p)
                p.strokeWidth = 1f; p.color = (pal[2] and 0x00FFFFFF) or 0x33000000
                c.drawRect(34f, 34f, wf - 34f, hf - 34f, p)
            }
            "pink" -> {
                val pal = arrayOf(intArrayOf(0xFFFF8FB8.toInt(), 0xFFB24BFF.toInt(), 0xFF3A0F5C.toInt()),
                    intArrayOf(0xFFFFB3C7.toInt(), 0xFFFF4F9A.toInt(), 0xFF6A1B9A.toInt()),
                    intArrayOf(0xFFF8BBD0.toInt(), 0xFFCE93D8.toInt(), 0xFF4A148C.toInt()))[v]
                p.shader = LinearGradient(0f, 0f, wf * 0.7f, hf, pal, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, wf, hf, p); p.shader = null
                for (i in 0 until 34) {
                    val r = 18f + rnd.nextFloat() * 90f
                    p.color = Color.argb(14 + rnd.nextInt(46), 255, 255 - rnd.nextInt(90), 255)
                    c.drawCircle(rnd.nextFloat() * wf, rnd.nextFloat() * hf, r, p)
                }
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x55FFFFFF
                for (i in 0 until 40) c.drawCircle(rnd.nextFloat() * wf, rnd.nextFloat() * hf, 3f + rnd.nextFloat() * 5f, p)
            }
            "trip" -> {
                val pal = arrayOf(intArrayOf(0xFFFF9A5A.toInt(), 0xFFC2417A.toInt(), 0xFF2B1B4E.toInt()),
                    intArrayOf(0xFFFFD27D.toInt(), 0xFFF36F56.toInt(), 0xFF3B2A6B.toInt()),
                    intArrayOf(0xFF7FD6E8.toInt(), 0xFF4C8DC8.toInt(), 0xFF1B2A5E.toInt()))[v]
                p.shader = LinearGradient(0f, 0f, 0f, hf * 0.8f, pal, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, wf, hf, p); p.shader = null
                val sx = wf * (0.3f + 0.2f * v); val sy = hf * 0.42f
                p.shader = RadialGradient(sx, sy, hf * 0.55f, 0x66FFF3C4, 0x00FFF3C4, Shader.TileMode.CLAMP); c.drawCircle(sx, sy, hf * 0.55f, p); p.shader = null
                p.color = 0xFFFFE9A8.toInt(); c.drawCircle(sx, sy, 46f, p)
                val layers = intArrayOf(0xFF5B2A5E.toInt(), 0xFF3A1D52.toInt(), 0xFF1E1038.toInt())
                for ((li, lc) in layers.withIndex()) {
                    val path = Path(); val base = hf * (0.62f + li * 0.09f)
                    path.moveTo(0f, hf)
                    var x = 0f
                    while (x <= wf + 80f) { path.lineTo(x, base - rnd.nextFloat() * (120f - li * 25f)); x += 70f + rnd.nextFloat() * 60f }
                    path.lineTo(wf, hf); path.close(); p.color = lc; c.drawPath(path, p)
                }
                // carretera en perspectiva
                val road = Path(); road.moveTo(wf * 0.40f, hf * 0.80f); road.lineTo(wf * 0.60f, hf * 0.80f); road.lineTo(wf * 0.98f, hf); road.lineTo(wf * 0.02f, hf); road.close()
                p.color = 0xFF14101F.toInt(); c.drawPath(road, p)
                p.color = 0xFFFFE9A8.toInt()
                for (i in 0 until 5) {
                    val t0 = i / 5f; val t1 = t0 + 0.09f
                    val y0 = hf * (0.80f + 0.20f * t0 * t0 * 1.6f).coerceAtMost(hf); val y1 = hf * (0.80f + 0.20f * t1 * t1 * 1.6f).coerceAtMost(hf)
                    val w0 = 2f + 14f * t0; val w1 = 2f + 14f * t1
                    val dash = Path(); dash.moveTo(wf / 2 - w0, y0); dash.lineTo(wf / 2 + w0, y0); dash.lineTo(wf / 2 + w1, y1); dash.lineTo(wf / 2 - w1, y1); dash.close(); c.drawPath(dash, p)
                }
            }
            "sport" -> {
                val acc = intArrayOf(0xFFFF1744.toInt(), 0xFFFF6D00.toInt(), 0xFF2979FF.toInt())[v]
                c.drawColor(0xFF0A0A0C.toInt())
                val tile = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); val tc = Canvas(tile); val tp = Paint()
                tp.color = 0xFF141417.toInt(); tc.drawRect(0f, 0f, 16f, 16f, tp)
                tp.color = 0xFF1C1C21.toInt(); tc.drawRect(0f, 0f, 8f, 8f, tp); tc.drawRect(8f, 8f, 16f, 16f, tp)
                tp.color = 0xFF0E0E10.toInt(); tc.drawRect(8f, 0f, 16f, 8f, tp); tc.drawRect(0f, 8f, 8f, 16f, tp)
                p.shader = android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val m = android.graphics.Matrix(); m.postRotate(-35f); p.shader.setLocalMatrix(m); c.drawRect(0f, 0f, wf, hf, p); p.shader = null
                p.shader = lg(0f, 0f, wf, 0f, 0x00000000, 0xCC000000.toInt()); c.drawRect(0f, 0f, wf, hf, p); p.shader = null
                // franjas rojas inclinadas
                for ((i, ww) in floatArrayOf(70f, 26f).withIndex()) {
                    val path = Path(); val x0 = -40f + i * 110f
                    path.moveTo(x0 + 120f, 0f); path.lineTo(x0 + 120f + ww, 0f); path.lineTo(x0 + ww - 60f, hf); path.lineTo(x0 - 60f, hf); path.close()
                    p.color = if (i == 0) acc else 0xFFFFFFFF.toInt(); c.drawPath(path, p)
                }
                p.strokeWidth = 2f
                for (i in 0 until 26) { p.color = Color.argb(20 + rnd.nextInt(40), 255, 255, 255); val y = rnd.nextFloat() * hf; val x = wf * (0.35f + rnd.nextFloat() * 0.6f); c.drawLine(x, y, x + 120f + rnd.nextFloat() * 240f, y, p) }
                p.style = Paint.Style.FILL
                val sq = 14f
                for (i in 0 until (wf / sq).toInt()) for (j in 0 until 2) { if ((i + j) % 2 == 0) { p.color = 0x33FFFFFF; c.drawRect(i * sq, hf - (j + 1) * sq, (i + 1) * sq, hf - j * sq, p) } }
            }
            else -> c.drawColor(0xFF050A14.toInt())
        }
        return bmp
    }
}
