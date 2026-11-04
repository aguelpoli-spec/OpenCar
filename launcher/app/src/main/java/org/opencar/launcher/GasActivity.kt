package org.opencar.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Registro de gasolina: cuánto echas, cuánto rinde (km/L), cuánto gastas por mes y por km. */
class GasActivity : Activity() {
    companion object {
        /** Rendimiento medio (km/L) de tanque lleno a tanque lleno, 0 si no hay datos suficientes. */
        fun kmPerL(prefs: android.content.SharedPreferences): Double {
            return try {
                val a = JSONArray(prefs.getString("fuel_log", "[]"))
                val unit = prefs.getString("odo_unit", null) ?: "mi"
                val l = (0 until a.length()).map { a.getJSONObject(it) }
                val fulls = l.withIndex().filter { it.value.optBoolean("full", false) }.map { it.index }
                var km = 0.0; var lit = 0.0
                for (k in 1 until fulls.size) {
                    val i = fulls[k - 1]; val j = fulls[k]
                    val seg = l.subList(i + 1, j + 1)
                    if (seg.any { it.optDouble("l", 0.0) <= 0.0 }) continue
                    val d = (l[j].optInt("odo", 0) - l[i].optInt("odo", 0)) * (if (unit == "mi") 1.609344 else 1.0)
                    if (d <= 0) continue
                    km += d; lit += seg.sumOf { it.optDouble("l", 0.0) }
                }
                if (lit > 0) km / lit else 0.0
            } catch (e: Exception) { 0.0 }
        }
    }

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private var sc = 1f
    private fun p(v: Int) = (v * sc).toInt()
    private val acc: Int by lazy { try { Modes.get(prefs.getString("app_mode", "ios") ?: "ios").acc } catch (e: Throwable) { 0xFF4DD0E1.toInt() } }
    private val card = 0xFF141C2C.toInt()
    private val fg = Color.WHITE
    private val fg2 = 0xFF9FB0C8.toInt()
    private lateinit var content: LinearLayout

    data class Fill(val t: Long, val bs: Double, val l: Double, val odo: Int, val st: String, val full: Boolean)

    private fun load(): MutableList<Fill> {
        val a = try { JSONArray(prefs.getString("fuel_log", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Fill(it.getLong("t"), it.optDouble("bs", 0.0), it.optDouble("l", 0.0), it.optInt("odo", 0), it.optString("st"), it.optBoolean("full", true)) }.sortedBy { it.t }.toMutableList()
    }
    private fun save(l: List<Fill>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("t", it.t).put("bs", it.bs).put("l", it.l).put("odo", it.odo).put("st", it.st).put("full", it.full)) }
        prefs.edit().putString("fuel_log", a.toString()).apply()
    }
    private fun odoNowKm(): Int? {
        val base = prefs.getString("odo_base", null)?.toDoubleOrNull() ?: return null
        val gps = prefs.getLong("gps_total_m", 0L); val at = prefs.getLong("gps_at_set_m", 0L)
        val mpu = if (unit() == "mi") 1609.344 else 1000.0
        return (base + (gps - at) / mpu).toInt()
    }
    private fun cur() = Combustible.cur(prefs)
    private fun unit() = prefs.getString("odo_unit", null) ?: "mi"
    private fun toKm(d: Int) = if (unit() == "mi") d * 1.609344 else d.toDouble()

    private fun bg(c: Int, r: Int) = GradientDrawable().apply { cornerRadius = p(r).toFloat(); setColor(c) }
    private fun tv(s: String, sp: Float, c: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(c); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * sc); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dm = resources.displayMetrics
        sc = min(dm.widthPixels / 1024f, dm.heightPixels / 600f)
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF0A1020.toInt()) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(18), p(24), p(24)) }
        sv.addView(content)
        setContentView(sv)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) { window.setDecorFitsSystemWindows(false); window.insetsController?.hide(android.view.WindowInsets.Type.systemBars()) }
        } catch (e: Throwable) { }
        render()
    }

    private fun stats(l: List<Fill>): Triple<Double, Double, Double> {   // km/L, Bs/km, km totales
        var km = 0.0; var lit = 0.0; var bs = 0.0
        val fulls = l.withIndex().filter { it.value.full }.map { it.index }
        for (k in 1 until fulls.size) {
            val i = fulls[k - 1]; val j = fulls[k]
            val seg = l.subList(i + 1, j + 1)
            if (seg.any { it.l <= 0.0 }) continue
            val d = toKm(l[j].odo - l[i].odo)
            if (d <= 0) continue
            km += d; lit += seg.sumOf { it.l }; bs += seg.sumOf { it.bs }
        }
        return Triple(if (lit > 0) km / lit else 0.0, if (km > 0) bs / km else 0.0, km)
    }

    private fun render() {
        content.removeAllViews()
        Combustible.repair(prefs, odoNowKm())
        val l = load()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("‹", 30f, fg, true).apply { setPadding(p(6), 0, p(18), 0); setOnClickListener { finish() } })
        head.addView(tv("Gasolina", 28f, fg, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(tv("+ Registrar carga", 17f, Color.parseColor("#021018"), true).apply { background = bg(acc, 18); setPadding(p(22), p(12), p(22), p(12)); setOnClickListener { addDialog(l) } })
        content.addView(head)

        // tarjeta de nivel y última carga
        val est = Combustible.estimate(prefs, odoNowKm())
        val lastF = l.lastOrNull()
        val lc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(card, 18); setPadding(p(20), p(14), p(20), p(14)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(tv("TANQUE AHORA (estimado)", 11f, fg2, true))
        if (est != null) {
            val colr = if (est.pct < 10) 0xFFFF5252.toInt() else if (est.pct < 22) 0xFFFFB300.toInt() else 0xFF4CD964.toInt()
            left.addView(tv(String.format(Locale.US, "%.0f L · %d %%", est.liters, est.pct), 30f, colr, true))
            left.addView(tv("≈ ${est.rangeKm} km de autonomía", 13f, fg2))
        } else left.addView(tv("Registra un llenado completo", 18f, fg, true))
        lc.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (lastF != null) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            r.addView(tv("ÚLTIMA CARGA · " + SimpleDateFormat("dd/MM", Locale.US).format(Date(lastF.t)), 11f, fg2, true))
            r.addView(tv(cur() + " " + lastF.bs.toInt() + (if (lastF.l > 0) String.format(Locale.US, " · %.1f L", lastF.l).replace('.', ',') else ""), 20f, fg, true))
            r.addView(tv((if (lastF.full) "tanque lleno" else "carga parcial") + (if (lastF.odo > 0) " · ${lastF.odo} ${unit()}" else ""), 12f, fg2))
            lc.addView(r)
        }
        content.addView(lc, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(14) })

        val now = Calendar.getInstance()
        val month = l.filter { val c = Calendar.getInstance().apply { timeInMillis = it.t }; c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH) }
        val (kmL, bsKm, kmTot) = stats(l)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun box(t: String, v: String, s: String) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(card, 18); setPadding(p(16), p(12), p(16), p(12)) }
            c.addView(tv(t, 11f, fg2, true)); c.addView(tv(v, 26f, fg, true)); c.addView(tv(s, 12f, fg2))
            row.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = p(10) })
        }
        box("RENDIMIENTO", if (kmL > 0) String.format(Locale.US, "%.1f km/L", kmL).replace('.', ',') else "—", "de tanque lleno a tanque lleno")
        box("COSTO POR KM", if (bsKm > 0) String.format(Locale.US, "${cur()} %.2f", bsKm).replace('.', ',') else "—", if (kmTot > 0) "en ${kmTot.toInt()} km medidos" else "falta litros y odómetro")
        box("GASTO DEL MES", cur() + " " + month.sumOf { it.bs }.toInt(), "${month.size} cargas · ${String.format(Locale.US, "%.0f", month.sumOf { it.l })} L")
        box("CARGAS", l.size.toString(), if (l.isNotEmpty()) "última: " + SimpleDateFormat("dd/MM", Locale.US).format(Date(l.last().t)) else "aún ninguna")
        content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(16) })

        // gráfica de los últimos 6 meses
        val months = (5 downTo 0).map { k -> Calendar.getInstance().apply { add(Calendar.MONTH, -k) } }
        val vals = months.map { m -> l.filter { val c = Calendar.getInstance().apply { timeInMillis = it.t }; c.get(Calendar.YEAR) == m.get(Calendar.YEAR) && c.get(Calendar.MONTH) == m.get(Calendar.MONTH) }.sumOf { it.bs } }
        val names = months.map { SimpleDateFormat("MMM", Locale("es")).format(it.time).replace(".", "") }
        content.addView(tv("GASTO POR MES (${cur()})", 12f, fg2, true).apply { setPadding(0, p(18), 0, p(6)) })
        content.addView(object : View(this) {
            override fun onDraw(cv: Canvas) {
                val pt = Paint(Paint.ANTI_ALIAS_FLAG)
                val w = width.toFloat(); val h = height.toFloat(); val mx = max(1.0, vals.max())
                val bw = w / vals.size
                for (i in vals.indices) {
                    val bh = ((h - 30 * sc) * (vals[i] / mx)).toFloat()
                    pt.color = if (i == vals.size - 1) acc else 0xFF3A4A66.toInt()
                    cv.drawRoundRect(i * bw + bw * 0.2f, h - 22 * sc - bh, i * bw + bw * 0.8f, h - 22 * sc, 8f * sc, 8f * sc, pt)
                    pt.color = fg2; pt.textSize = 12f * sc; pt.textAlign = Paint.Align.CENTER
                    cv.drawText(names[i], i * bw + bw / 2, h - 5 * sc, pt)
                    if (vals[i] > 0) { pt.color = fg; cv.drawText(vals[i].toInt().toString(), i * bw + bw / 2, h - 26 * sc - bh, pt) }
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, p(150)).apply { bottomMargin = p(6) })

        content.addView(tv("HISTORIAL (mantén pulsado para corregir o borrar)", 12f, fg2, true).apply { setPadding(0, p(12), 0, p(6)) })
        if (l.isEmpty()) content.addView(tv("Registra tu primera carga: total en ${cur()}, litros, lectura del tablero y gasolinera.", 15f, fg2))
        for (f in l.reversed()) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(card, 14); setPadding(p(16), p(10), p(16), p(10)); isClickable = true; setOnLongClickListener { editDialog(l, f); true } }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(SimpleDateFormat("EEE dd MMM yyyy · HH:mm", Locale("es")).format(Date(f.t)), 15f, fg, true))
            col.addView(tv((if (f.st.isNotBlank()) f.st + " · " else "") + "${f.odo} ${unit()}" + (if (f.full) " · tanque lleno" else " · carga parcial"), 12f, fg2))
            r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val rc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            rc.addView(tv(cur() + " " + String.format(Locale.US, "%.0f", f.bs), 18f, fg, true))
            rc.addView(tv(if (f.l > 0) String.format(Locale.US, "%.1f L · ${cur()} %.2f/L", f.l, f.bs / f.l).replace('.', ',') else "sin litros", 12f, fg2))
            r.addView(rc)
            content.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        }
    }

    private fun field(label: String, hint: String, num: Boolean, init: String = ""): Pair<LinearLayout, EditText> {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, p(6), 0, p(2)) }
        l.addView(TextView(this).apply { text = label; textSize = 13f })
        val e = EditText(this).apply { this.hint = hint; setSingleLine(); setText(init); inputType = if (num) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT }
        l.addView(e)
        return Pair(l, e)
    }

    private fun num(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun addDialog(l: MutableList<Fill>, old: Fill? = null) {
        val last = l.lastOrNull()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(10), p(24), 0) }
        val (a, bs) = field("Total pagado (${cur()})", "ej. 200", true, old?.bs?.let { String.format(Locale.US, "%.2f", it) } ?: "")
        val (b, lit) = field("Litros (si la bomba los dice)", "ej. 45", true, old?.l?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f", it) } ?: "")
        val (pp, price) = field("Precio por litro (${cur()}): si no sabes los litros, se calculan", "ej. 1.50", true, prefs.getString("fuel_price", "1") ?: "1")
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val (dd, dateF) = field("Fecha de la carga (dd/mm/aaaa)", "", false, fmt.format(Date(old?.t ?: System.currentTimeMillis())))
        val (c, odo) = field("Lectura del tablero (${unit()})", last?.let { "última: ${it.odo}" } ?: "", true, old?.odo?.toString() ?: "")
        val (d, st) = field("Gasolinera", "ej. Ecofuel", false, old?.st ?: "")
        val cb = android.widget.CheckBox(this).apply { text = "Llené el tanque"; isChecked = old?.full ?: true }
        listOf(a, b, pp, c, dd, d).forEach { box.addView(it) }; box.addView(cb)
        AlertDialog.Builder(this).setTitle(if (old == null) "Registrar carga" else "Corregir carga").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val pr = num(price)
                if (pr > 0) prefs.edit().putString("fuel_price", String.format(Locale.US, "%.2f", pr)).apply()
                val liters = if (num(lit) > 0) num(lit) else if (pr > 0) num(bs) / pr else 0.0
                val when_ = try { fmt.parse(dateF.text.toString().trim())?.time?.plus(12 * 3_600_000L) } catch (e: Exception) { null } ?: (old?.t ?: System.currentTimeMillis())
                val f = Fill(when_, num(bs), liters, Combustible.parseOdo(odo.text.toString()), st.text.toString().trim(), cb.isChecked)
                if (f.bs <= 0) { android.widget.Toast.makeText(this, "Falta el total pagado", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (old != null) l.remove(old)
                l.add(f); l.sortBy { it.t }; save(l); render()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun editDialog(l: MutableList<Fill>, f: Fill) {
        AlertDialog.Builder(this).setTitle("Carga del " + SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(f.t)))
            .setItems(arrayOf("Corregir", "Borrar")) { _, w -> if (w == 0) addDialog(l, f) else { l.remove(f); save(l); render() } }.show()
    }
}
