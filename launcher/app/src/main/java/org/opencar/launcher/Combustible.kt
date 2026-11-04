package org.opencar.launcher

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Estima cuánta gasolina queda: desde el último llenado completo resta lo recorrido según el rendimiento
 * (km/L del registro, o 7,2 km/L del tablero si aún no hay) y suma lo cargado después.
 */
object Combustible {
    class Est(val liters: Double, val pct: Int, val rangeKm: Int)

    fun tankL(p: SharedPreferences) = p.getString("tank_l", "50")?.toDoubleOrNull()?.takeIf { it > 10 } ?: 50.0
    fun price(p: SharedPreferences) = p.getString("fuel_price", "1")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
    fun kmpl(p: SharedPreferences): Double {
        val k = GasActivity.kmPerL(p)
        return if (k > 2.0) k else p.getString("kmpl_def", "7.2")?.toDoubleOrNull()?.takeIf { it > 1 } ?: 7.2
    }
    /** Símbolo de moneda: el que eligió el usuario (pref fuel_cur) o el del país del teléfono. */
    fun cur(p: SharedPreferences): String = p.getString("fuel_cur", null)?.takeIf { it.isNotBlank() }
        ?: try { java.util.Currency.getInstance(java.util.Locale.getDefault()).symbol } catch (e: Exception) { "$" }

    private fun kmPerUnit(p: SharedPreferences) = if ((p.getString("odo_unit", null) ?: "mi") == "mi") 1.609344 else 1.0

    private fun log(p: SharedPreferences): List<JSONObject> = try {
        val a = JSONArray(p.getString("fuel_log", "[]")); (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optLong("t") }
    } catch (e: Exception) { emptyList() }

    fun lastFillMs(p: SharedPreferences): Long = log(p).lastOrNull()?.optLong("t") ?: 0L

    /** Litros estimados en el tanque ahora; null si todavía no hay un llenado completo registrado. */
    /** Convierte "138.126", "138,126" o "138126.4" en un número; los puntos/comas de miles se ignoran. */
    fun parseOdo(raw: String): Int {
        val t = raw.trim().replace(" ", "")
        if (t.isEmpty()) return 0
        val miles = Regex("^\\d{1,3}([.,]\\d{3})+$").matches(t)
        val clean = if (miles) t.replace(".", "").replace(",", "") else t.replace(',', '.')
        return clean.toDoubleOrNull()?.toInt() ?: 0
    }

    /** Corrige cargas guardadas con la lectura truncada (138 en vez de 138.126). Devuelve true si cambió algo. */
    fun repair(p: SharedPreferences, odoNow: Int?): Boolean {
        if (odoNow == null || odoNow < 10_000) return false
        val a = try { JSONArray(p.getString("fuel_log", "[]")) } catch (e: Exception) { return false }
        var ch = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val v = o.optInt("odo", 0)
            if (v in 1..999 && v == odoNow / 1000) { o.put("odo", odoNow); ch = true }
        }
        if (ch) p.edit().putString("fuel_log", a.toString()).apply()
        return ch
    }

    fun estimate(p: SharedPreferences, odoNow: Int?): Est? {
        val l = log(p)
        val i = l.indexOfLast { it.optBoolean("full", false) }
        if (i < 0) return null
        val tank = tankL(p); val k = kmpl(p); val u = kmPerUnit(p)
        var lvl = tank
        var prev = l[i].optInt("odo", 0)
        for (j in i + 1 until l.size) {
            val o = l[j].optInt("odo", 0)
            if (o > 0 && prev > 0 && o > prev && (o - prev) < 3000) lvl -= (o - prev) * u / k
            lvl = max(0.0, lvl) + max(0.0, l[j].optDouble("l", 0.0))
            lvl = min(lvl, tank)
            if (o > 0) prev = o
        }
        if (odoNow != null && prev > 0 && odoNow > prev && (odoNow - prev) < 3000) lvl -= (odoNow - prev) * u / k
        lvl = lvl.coerceIn(0.0, tank)
        return Est(lvl, (lvl / tank * 100).toInt(), (lvl * k).toInt())
    }

    /** Última carga registrada, para mostrarla en el widget y la página. */
    fun last(p: SharedPreferences): JSONObject? = log(p).lastOrNull()

    fun addFill(p: SharedPreferences, bs: Double, liters: Double, odo: Int, station: String, full: Boolean) {
        val a = try { JSONArray(p.getString("fuel_log", "[]")) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("bs", bs).put("l", liters).put("odo", odo).put("st", station).put("full", full))
        p.edit().putString("fuel_log", a.toString()).putString("fuel_price", String.format(java.util.Locale.US, "%.2f", price(p))).apply()
    }
}
