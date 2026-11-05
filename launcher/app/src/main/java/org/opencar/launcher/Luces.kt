package org.opencar.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import android.view.View

/**
 * Señal de luces del auto. La radio casi nunca avisa si las luces están encendidas, así que el
 * diagnóstico compara los ajustes del sistema con las luces apagadas y encendidas: si algún
 * valor cambia (p. ej. el brillo que baja la radio al recibir la señal de iluminación),
 * se guarda como «señal de luces» y el launcher la usa.
 */
object Luces {
    private val TABLES = listOf("system", "global", "secure")
    private val NOISE = Regex("time|date|last|count|uptime|elapsed|stamp|clock|boot|usage|bytes|rx|tx|seq|version|counter|random|id\$|uuid|token|cache|sync|lock|screen_off|battery|temperature|volume", RegexOption.IGNORE_CASE)

    fun snapshot(ctx: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (t in TABLES) {
            try {
                ctx.contentResolver.query(Uri.parse("content://settings/$t"), arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) out["$t/${c.getString(0)}"] = c.getString(1) ?: ""
                }
            } catch (e: Throwable) { }
        }
        return out
    }

    data class Diff(val key: String, val off: String, val on: String)

    fun diff(off: Map<String, String>, on: Map<String, String>): List<Diff> =
        on.keys.union(off.keys).mapNotNull { k ->
            val a = off[k] ?: ""; val b = on[k] ?: ""
            if (a != b && !NOISE.containsMatchIn(k.substringAfter('/'))) Diff(k, a, b) else null
        }.sortedBy { it.key }

    fun saveSignal(p: SharedPreferences, d: Diff) {
        p.edit().putString("lights_key", d.key).putString("lights_on", d.on).putString("lights_off", d.off).putBoolean("lights_invert", false).apply()
    }

    fun clearSignal(p: SharedPreferences) { p.edit().remove("lights_key").remove("lights_on").remove("lights_off").apply() }

    fun configured(p: SharedPreferences) = !p.getString("lights_key", null).isNullOrBlank()

    /** El usuario dice cómo están las luces ahora mismo: si la señal dice lo contrario, se invierte sola. Devuelve true si corrigió. */
    fun learn(ctx: Context, p: SharedPreferences, realOn: Boolean): Boolean {
        val st = state(ctx, p) ?: return false
        if (st == realOn) return false
        p.edit().putBoolean("lights_invert", !p.getBoolean("lights_invert", false)).apply()
        return true
    }

    /** true = encendidas, false = apagadas, null = no hay señal configurada o no se puede leer. */
    fun state(ctx: Context, p: SharedPreferences): Boolean? {
        val k = p.getString("lights_key", null) ?: return null
        val on = p.getString("lights_on", null) ?: return null
        val off = p.getString("lights_off", null) ?: ""
        val table = k.substringBefore('/'); val name = k.substringAfter('/')
        val v = try {
            when (table) {
                "system" -> Settings.System.getString(ctx.contentResolver, name)
                "global" -> Settings.Global.getString(ctx.contentResolver, name)
                else -> Settings.Secure.getString(ctx.contentResolver, name)
            }
        } catch (e: Throwable) { null } ?: return null
        val inv = p.getBoolean("lights_invert", false)
        val r: Boolean? = when (v) {
            on -> true
            off -> false
            else -> {
                // valores numéricos (p. ej. brillo): se toma el más cercano a «encendidas» o a «apagadas»
                val a = v.toDoubleOrNull(); val b = on.toDoubleOrNull(); val c = off.toDoubleOrNull()
                if (a != null && b != null && c != null && b != c) Math.abs(a - b) < Math.abs(a - c) else null
            }
        }
        return if (inv && r != null) !r else r
    }

    /** Estado final de las luces: la señal de la radio si existe; si no, lo que marcaste a mano (vale 12 h). Nunca se adivina por la hora. */
    fun effective(ctx: Context, p: SharedPreferences): Boolean? {
        state(ctx, p)?.let { return it }
        val t = p.getLong("lights_manual_at", 0L)
        if (t == 0L || System.currentTimeMillis() - t > 12 * 3_600_000L) return null
        return p.getBoolean("lights_manual", false)
    }
    fun hasSignal(ctx: Context, p: SharedPreferences) = state(ctx, p) != null
    fun setManual(p: SharedPreferences, on: Boolean) { p.edit().putBoolean("lights_manual", on).putLong("lights_manual_at", System.currentTimeMillis()).apply() }

    fun hasLightSensor(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_LIGHT) != null
    } catch (e: Throwable) { false }

    // ------------------------------------------------------------------ brillo de los faros sobre la foto
    /** Posiciones (en píxeles de la foto original de 1024 de ancho) de faros h, luces de niebla f y luces traseras t. */
    class Spot(val x: Float, val y: Float, val r: Float, val t: Char)

    private val GLOWS = mapOf(
        "bg1" to listOf(Spot(441f, 375f, 46f, 'h'), Spot(103f, 366f, 30f, 'h'), Spot(441f, 487f, 22f, 'f')),
        "bg2" to listOf(Spot(445f, 361f, 44f, 'h'), Spot(668f, 362f, 36f, 'h'), Spot(440f, 426f, 24f, 'f'), Spot(684f, 424f, 22f, 'f')),
        "bg3" to listOf(Spot(562f, 382f, 46f, 'h'), Spot(792f, 388f, 30f, 'h'), Spot(576f, 473f, 24f, 'f')),
        "bg4" to listOf(Spot(672f, 355f, 38f, 't'), Spot(858f, 347f, 28f, 't')),
        "bg5" to listOf(Spot(443f, 361f, 44f, 'h'), Spot(270f, 366f, 26f, 'h'), Spot(428f, 432f, 22f, 'f'))
    )

    fun hasGlow(key: String) = GLOWS.containsKey(key)

    /** Vista transparente que dibuja el resplandor encima de la foto, siguiendo su misma escala. */
    class GlowView(ctx: Context, private val matrix: () -> Matrix?, private val bgKey: () -> String) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
        var level = 0f
        private var anim: ValueAnimator? = null

        init { setLayerType(LAYER_TYPE_HARDWARE, null); isClickable = false }

        fun setOn(on: Boolean, animate: Boolean = true) {
            val to = if (on) 1f else 0f
            anim?.cancel()
            if (!animate) { level = to; invalidate(); return }
            anim = ValueAnimator.ofFloat(level, to).apply {
                duration = 1800
                addUpdateListener { level = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val spots = GLOWS[bgKey()] ?: return
            val m = matrix() ?: return
            if (level <= 0.01f) return
            c.save()
            c.concat(m)
            for (s in spots) {
                val warm = if (s.t == 't') intArrayOf(0xFFFF3B30.toInt(), 0x66FF2A20, 0x00FF2A20) else intArrayOf(0xFFFFF6D8.toInt(), 0x77FFE6A8, 0x00FFDC96)
                val rr = s.r * (if (s.t == 'f') 2.6f else 3.4f)
                paint.shader = RadialGradient(s.x, s.y, rr, warm, floatArrayOf(0f, 0.28f, 1f), Shader.TileMode.CLAMP)
                paint.alpha = (255 * level).toInt()
                c.drawCircle(s.x, s.y, rr, paint)
                // brillo sobre el piso delante del faro
                if (s.t != 't') {
                    paint.shader = RadialGradient(s.x, s.y + s.r * 2.6f, s.r * 5f, intArrayOf(0x55FFE9B0, 0x00FFE9B0), null, Shader.TileMode.CLAMP)
                    paint.alpha = (200 * level).toInt()
                    c.drawCircle(s.x, s.y + s.r * 2.6f, s.r * 5f, paint)
                }
            }
            paint.shader = null
            c.restore()
        }
    }
}
