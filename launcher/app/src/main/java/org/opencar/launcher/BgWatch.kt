package org.opencar.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.min

/**
 * Vigilancia en segundo plano (la llama el servicio de GPS, así funciona aunque estés en CarPlay u otra app):
 * guarda dónde dejaste el auto (más de 30 min detenido) y detecta cuando estás en una gasolinera.
 */
object BgWatch {
    const val PARK_AFTER_MS = 30 * 60_000L
    const val FUEL_STOP_MS = 3 * 60_000L
    const val FUEL_RADIUS_M = 90.0

    private var lastMoveAt = 0L
    private var fuelMoved = false
    private var fuelStopAt = 0L

    fun onFix(ctx: Context, p: SharedPreferences, lat: Double, lon: Double, kmh: Float) {
        val now = System.currentTimeMillis()
        try { Parking.noteFix(ctx, p, lat, lon, kmh) } catch (e: Throwable) { }
        // ---- dónde estacioné
        if (kmh > 12f) {
            lastMoveAt = now
            if (now - p.getLong("last_move_t", 0L) > 30_000L) p.edit().putLong("last_move_t", now).remove("stop_t").apply()
        } else if (kmh < 3f) {
            var st = p.getLong("stop_t", 0L)
            val lastMove = maxOf(lastMoveAt, p.getLong("last_move_t", 0L))
            if (st == 0L && lastMove > 0L) {
                st = now
                p.edit().putLong("stop_t", st).putString("stop_lat", String.format(Locale.US, "%.6f", lat)).putString("stop_lon", String.format(Locale.US, "%.6f", lon)).apply()
            }
            if (st != 0L && now - st > Parking.STOP_LOG_MS && p.getLong("stop_logged", 0L) != st) {
                val la0 = p.getString("stop_lat", null)?.toDoubleOrNull(); val lo0 = p.getString("stop_lon", null)?.toDoubleOrNull()
                if (la0 != null && lo0 != null) { Parking.add(ctx, p, st, la0, lo0, "parada", ((now - st) / 60_000L).toInt()); p.edit().putLong("stop_logged", st).apply() }
            }
            if (st != 0L && now - st > PARK_AFTER_MS && lastMove > p.getLong("park_t", 0L)) {
                val la = p.getString("stop_lat", null)?.toDoubleOrNull(); val lo = p.getString("stop_lon", null)?.toDoubleOrNull()
                if (la != null && lo != null) saveParking(ctx, p, la, lo)
                p.edit().remove("stop_t").apply()
            }
        }
        // ---- gasolinera
        if (kmh > 8f) { fuelMoved = true; fuelStopAt = 0L; return }
        if (kmh >= 3f || !fuelMoved) return
        if (fuelStopAt == 0L) fuelStopAt = now
        if (now - fuelStopAt < FUEL_STOP_MS) return
        if (now - p.getLong("fuel_ask_t", 0L) < 3 * 3_600_000L || now - Combustible.lastFillMs(p) < 3_600_000L) return
        val near = Estaciones.nearestIn(Estaciones.around(ctx, lat, lon), lat, lon, 1).firstOrNull() ?: return
        if (near.second > FUEL_RADIUS_M) return
        fuelMoved = false
        p.edit().putLong("fuel_ask_t", now).apply()
        Handler(Looper.getMainLooper()).post { FuelAsk.show(ctx.applicationContext, near.first.shown) }
    }

    fun saveParking(ctx: Context, p: SharedPreferences, la: Double, lo: Double) {
        Parking.add(ctx, p, p.getLong("stop_t", System.currentTimeMillis()), la, lo, "estacionado", (PARK_AFTER_MS / 60_000L).toInt())
    }
}

/** Pregunta «¿Cargaste gasolina?» sobre cualquier app: voz + tarjeta con Sí / No (o notificación si no hay permiso). */
object FuelAsk {
    private var view: View? = null
    private val ui = Handler(Looper.getMainLooper())

    fun show(ctx: Context, station: String) {
        if (view != null) return
        VozClips.play(ctx, "fuel_filled") { listenYesNo(ctx, station) }
        val ok = try { if (Settings.canDrawOverlays(ctx)) { overlay(ctx, station); true } else false } catch (e: Throwable) { false }
        if (!ok) notify(ctx, station)
    }

    private fun listenYesNo(ctx: Context, station: String) {
        if (!AsistenteService.running) return
        AsistenteService.choiceCb = { t ->
            val n = PlaceIndex.norm(t)
            val yes = Regex("\\b(si|sii|cargue|ya cargue|claro|correcto|afirmativo)\\b").containsMatchIn(n) && !Regex("\\bno\\b").containsMatchIn(n)
            val no = Regex("\\b(no|nada|cancela|todavia no|aun no)\\b").containsMatchIn(n)
            when { yes -> { ui.post { yes(ctx, station) }; true }; no -> { ui.post { dismiss(ctx) }; true }; else -> false }
        }
        AsistenteService.choiceUntil = System.currentTimeMillis() + 40_000L
    }

    private fun clearVoice() { AsistenteService.choiceUntil = 0L; AsistenteService.choiceCb = null }

    fun dismiss(ctx: Context) {
        clearVoice()
        view?.let { try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Throwable) { } }
        view = null
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(7711) } catch (e: Throwable) { }
    }

    /** Sí: trae el launcher al frente con el diálogo para anotar cuánto cargó. */
    fun yes(ctx: Context, station: String) {
        dismiss(ctx)
        try { ctx.startActivity(openIntent(ctx, station)) } catch (e: Throwable) { notify(ctx, station) }
    }

    private fun openIntent(ctx: Context, station: String) = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .putExtra("fuel_yes", station)

    private fun overlay(ctx: Context, station: String) {
        val dm = ctx.resources.displayMetrics
        val sc = min(dm.widthPixels / 1024f, dm.heightPixels / 600f).coerceIn(0.6f, 2.5f)
        fun p(v: Float) = (v * sc).toInt()
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(p(26f), p(18f), p(26f), p(20f))
            background = GradientDrawable().apply { cornerRadius = p(26f).toFloat(); setColor(0xF2070E1E.toInt()); setStroke(maxOf(2, p(1.5f)), 0xCC4DD0E1.toInt()) }
        }
        c.addView(TextView(ctx).apply { text = "⛽  ¿Cargaste gasolina?"; setTextColor(Color.WHITE); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26f * sc); setTypeface(null, android.graphics.Typeface.BOLD) })
        c.addView(TextView(ctx).apply { text = "Estás en $station"; setTextColor(0xFF9FB0C8.toInt()); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f * sc); setPadding(0, p(4f), 0, p(14f)) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, bg: Int, fg: Int, go: () -> Unit) = TextView(ctx).apply {
            text = label; setTextColor(fg); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 20f * sc); setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(p(30f), p(14f), p(30f), p(14f))
            background = GradientDrawable().apply { cornerRadius = p(20f).toFloat(); setColor(bg) }
            isClickable = true; setOnClickListener { go() }
        }
        row.addView(btn("Sí, cargué", 0xFF4DD0E1.toInt(), 0xFF021018.toInt()) { yes(ctx, station) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = p(10f) })
        row.addView(btn("No", 0x33FFFFFF, Color.WHITE) { dismiss(ctx) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = p(10f) })
        c.addView(row, LinearLayout.LayoutParams(p(420f), LinearLayout.LayoutParams.WRAP_CONTENT))
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = p(70f)
        }
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(c, lp)
        view = c
        ui.postDelayed({ if (view === c) dismiss(ctx) }, 90_000L)
    }

    private fun notify(ctx: Context, station: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("fuel_ask", "Gasolinera", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, 7711, openIntent(ctx, station), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "fuel_ask") else @Suppress("DEPRECATION") Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.ic_place).setContentTitle("¿Cargaste gasolina?").setContentText("Estás en $station. Toca para anotar cuánto cargaste.")
                .setContentIntent(pi).setAutoCancel(true).addAction(Notification.Action.Builder(null, "Sí, cargué", pi).build())
            nm.notify(7711, b.build())
        } catch (e: Throwable) { }
    }
}

/** Entiende cifras dichas en español: «doscientos sesenta» o «260». */
object NumEs {
    private val U = mapOf("cero" to 0, "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
        "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20,
        "veintiuno" to 21, "veintiun" to 21, "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29)
    private val T = mapOf("treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90)
    private val H = mapOf("cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300, "cuatrocientos" to 400, "quinientos" to 500, "seiscientos" to 600, "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900)

    fun parse(raw: String): Double? {
        val t = PlaceIndex.norm(raw)
        Regex("(\\d+(?:[.,]\\d+)?)").find(t)?.let { return it.value.replace(',', '.').toDoubleOrNull() }
        var total = 0; var found = false
        for (w in t.split(" ")) {
            val n = H[w] ?: T[w] ?: U[w]
            if (n != null) { total += n; found = true }
        }
        return if (found && total > 0) total.toDouble() else null
    }
}
