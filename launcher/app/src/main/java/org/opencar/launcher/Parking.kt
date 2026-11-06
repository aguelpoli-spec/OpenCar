package org.opencar.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Historial de lugares donde el auto quedó estacionado o detenido: guarda cada sitio con su nombre
 * (Casa, Trabajo… si coincide con un favorito) y la calle. Si el equipo se apaga con el auto (se pierde el GPS
 * estando detenido), el último punto conocido se guarda como estacionamiento al volver a encender.
 */
object Parking {
    private const val KEY = "park_hist"
    private const val MAX = 80
    private const val HOME_LABEL = "Casa"
    /** Sin señal de GPS más de 2,5 min estando detenido = se apagó el auto (o entró a un garaje). */
    const val OFF_GAP_MS = 150_000L
    /** Detenido 8 min o más = llegó a un sitio (se anota en el historial). */
    const val STOP_LOG_MS = 8 * 60_000L
    private const val NEAR_FAV_M = 120.0
    private const val SAME_SPOT_M = 70.0

    class Entry(val t: Long, val lat: Double, val lon: Double, var label: String, var street: String, var kind: String, var durMin: Int)

    @Volatile var listener: (() -> Unit)? = null
    private var lastWrite = 0L
    private val ui = Handler(Looper.getMainLooper())
    private fun fire() { ui.post { try { listener?.invoke() } catch (e: Throwable) { } } }

    fun dist(a: Double, b: Double, c: Double, d: Double): Double { val r = FloatArray(1); android.location.Location.distanceBetween(a, b, c, d, r); return r[0].toDouble() }

    /** Lugares con nombre: los favoritos guardados + el taller. */
    private fun favs(p: SharedPreferences): List<Triple<String, Double, Double>> {
        val out = ArrayList<Triple<String, Double, Double>>()
        for (f in Favoritos.ALL) {
            val c: Pair<Double, Double>? = Favoritos.coords(p, f)
            if (c != null) out.add(Triple(if (f.key == "place_casa") HOME_LABEL else f.label, c.first, c.second))
        }
        p.getString("place_taller", null)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { out.add(Triple("Taller", it[0], it[1])) }
        return out
    }

    fun favLabel(p: SharedPreferences, lat: Double, lon: Double): String =
        favs(p).filter { dist(it.second, it.third, lat, lon) < NEAR_FAV_M }.minByOrNull { dist(it.second, it.third, lat, lon) }?.first ?: ""

    @Synchronized fun list(p: SharedPreferences): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val raw = p.getString(KEY, null)
            if (raw != null) {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out.add(Entry(o.getLong("t"), o.getDouble("la"), o.getDouble("lo"), o.optString("lb"), o.optString("st"), o.optString("k", "estacionado"), o.optInt("d")))
                }
            }
        } catch (e: Exception) { }
        if (out.isEmpty() && p.getString(KEY, null) == null) {
            // versión anterior: solo guardaba un lugar
            val la = p.getString("park_lat", null)?.toDoubleOrNull(); val lo = p.getString("park_lon", null)?.toDoubleOrNull()
            if (la != null && lo != null) out.add(Entry(p.getLong("park_t", System.currentTimeMillis()), la, lo, favLabel(p, la, lo), p.getString("park_name", "") ?: "", "estacionado", 0))
        }
        return out
    }

    private fun save(p: SharedPreferences, l: List<Entry>) {
        val a = JSONArray()
        for (e in l.take(MAX)) a.put(JSONObject().put("t", e.t).put("la", e.lat).put("lo", e.lon).put("lb", e.label).put("st", e.street).put("k", e.kind).put("d", e.durMin))
        val ed = p.edit().putString(KEY, a.toString())
        val last = l.firstOrNull { it.kind != "parada" }
        if (last != null) ed.putString("park_lat", String.format(Locale.US, "%.6f", last.lat)).putString("park_lon", String.format(Locale.US, "%.6f", last.lon)).putLong("park_t", last.t).putString("park_name", display(last))
        else ed.remove("park_lat").remove("park_lon").remove("park_t").remove("park_name")
        ed.apply()
    }

    fun display(e: Entry): String = when {
        e.label.isNotBlank() && e.street.isNotBlank() -> e.label + " · " + e.street
        e.label.isNotBlank() -> e.label
        e.street.isNotBlank() -> e.street
        else -> "Mi auto"
    }

    fun kindText(e: Entry): String = when (e.kind) { "apagado" -> "Auto apagado"; "parada" -> "Parada"; "manual" -> "Guardado a mano"; else -> "Estacionado" }

    /** El último sitio donde quedó estacionado (las paradas cortas no cuentan). */
    fun lastParked(p: SharedPreferences): Entry? = list(p).firstOrNull { it.kind != "parada" }

    /** Anota un lugar. Si ya hay uno en el mismo sitio y hora cercana, lo actualiza en vez de repetirlo. */
    @Synchronized fun add(ctx: Context, p: SharedPreferences, t: Long, lat: Double, lon: Double, kind: String, durMin: Int) {
        val l = ArrayList(list(p))
        val same = l.firstOrNull { Math.abs(it.t - t) < 45 * 60_000L && dist(it.lat, it.lon, lat, lon) < SAME_SPOT_M }
        if (same != null) {
            if (kind != "parada") same.kind = kind
            if (durMin > same.durMin) same.durMin = durMin
            save(p, l); fire(); return
        }
        l.add(Entry(t, lat, lon, favLabel(p, lat, lon), "", kind, durMin))
        l.sortByDescending { it.t }
        save(p, l); fire()
        Thread {
            val nm = try { PlaceIndex.nameAt(ctx.applicationContext, lat, lon) } catch (e: Throwable) { "" }
            if (nm.isNotBlank()) {
                synchronized(Parking) {
                    val l2 = ArrayList(list(p))
                    val m = l2.firstOrNull { it.t == t && it.lat == lat && it.lon == lon }
                    if (m != null) { m.street = nm; save(p, l2) }
                }
                fire()
            }
        }.start()
    }

    @Synchronized fun rename(p: SharedPreferences, t: Long, lat: Double, label: String) {
        val l = ArrayList(list(p)); l.firstOrNull { it.t == t && it.lat == lat }?.label = label; save(p, l); fire()
    }

    @Synchronized fun remove(p: SharedPreferences, t: Long, lat: Double) {
        val l = ArrayList(list(p)); l.removeAll { it.t == t && it.lat == lat }; save(p, l); fire()
    }

    @Synchronized fun clear(p: SharedPreferences) { save(p, emptyList()); fire() }

    /**
     * Se llama con cada posición del GPS. Guarda siempre el último punto; si pasó un buen rato sin posiciones
     * (el equipo se apagó con el auto, o se perdió el GPS al entrar a un garaje) y el último punto era detenido o lento,
     * lo registra como «Auto apagado»: ahí quedó estacionado.
     */
    fun noteFix(ctx: Context, p: SharedPreferences, lat: Double, lon: Double, kmh: Float) {
        val now = System.currentTimeMillis()
        val lt = p.getLong("lf_t", 0L)
        if (lt > 0L && now > lt && now - lt > OFF_GAP_MS) {
            val la = p.getString("lf_lat", null)?.toDoubleOrNull(); val lo = p.getString("lf_lon", null)?.toDoubleOrNull()
            if (la != null && lo != null && p.getFloat("lf_kmh", 99f) < 8f) add(ctx, p, lt, la, lo, "apagado", ((now - lt) / 60_000L).toInt())
            lastWrite = 0L
        }
        if (now - lastWrite >= 3000L || lastWrite == 0L) {
            lastWrite = now
            p.edit().putLong("lf_t", now).putString("lf_lat", String.format(Locale.US, "%.6f", lat)).putString("lf_lon", String.format(Locale.US, "%.6f", lon)).putFloat("lf_kmh", kmh).apply()
        }
    }
}
