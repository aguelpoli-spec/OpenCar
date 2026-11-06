package org.opencar.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Posición y tamaño de un widget, en unidades de diseño (la pantalla mide 1024 x 600). */
data class WSpec(val id: String, var x: Float, var y: Float, var w: Float, var h: Float)

object WStore {
    class Def(val id: String, val name: String, val sub: String, val x: Float, val y: Float, val w: Float, val h: Float,
              val minW: Float, val minH: Float, val inDefault: Boolean = true)

    const val W = 1024f
    const val H = 600f
    private const val KEY = "widgets_v2"

    val defs = listOf(
        Def("nombre", "Nombre del auto y logo", "Auto, millas y logo", 362f, 38f, 300f, 118f, 180f, 60f),
        Def("reloj", "Reloj", "Hora, fecha y temperatura", 20f, 44f, 224f, 96f, 110f, 56f),
        Def("vel", "Velocímetro", "Velocidad por GPS", 20f, 148f, 224f, 70f, 120f, 50f),
        Def("modos", "Estilo ECO · NORMAL · SPORT", "Solo visual", 20f, 226f, 224f, 34f, 170f, 30f),
        Def("luces", "Luces", "Estado de las luces / modo noche", 20f, 268f, 224f, 34f, 120f, 30f),
        Def("park", "Dónde estacioné", "Guarda dónde dejaste el auto", 704f, 44f, 300f, 52f, 190f, 46f),
        Def("mapa", "Mapa en vivo", "Rumbo, recorrido y datos del viaje", 704f, 102f, 300f, 160f, 190f, 100f),
        Def("acc", "Accesos rápidos", "Gasolinera · Música · Mapas", 704f, 270f, 300f, 66f, 190f, 56f),
        Def("aceite", "Cambio de aceite", "Cuánto falta", 704f, 344f, 300f, 60f, 100f, 50f),
        Def("player", "Reproductor", "OpenCar Music", 14f, 506f, 730f, 84f, 300f, 60f),
        Def("dock", "Menú y apps", "Menú · Apps · CarPlay · Ajustes · Fondos", 752f, 506f, 258f, 84f, 200f, 60f),
        // extras para agregar desde la galería
        Def("comb", "Combustible", "Nivel estimado y autonomía", 20f, 310f, 224f, 90f, 130f, 70f, false),
        Def("clima", "Clima", "Temperatura actual", 20f, 316f, 150f, 60f, 100f, 50f, false),
        Def("viaje", "Viaje de hoy", "Km y tiempo en marcha", 20f, 384f, 190f, 60f, 130f, 50f, false),
        Def("gas", "Gasolina", "Último rendimiento y registro", 20f, 316f, 190f, 60f, 130f, 50f, false),
        Def("cal", "Calendario", "Próximo feriado", 20f, 316f, 190f, 60f, 130f, 50f, false),
        Def("asist", "Asistente", "Hey auto", 20f, 316f, 150f, 60f, 100f, 50f, false),
        Def("estado", "Estado del auto", "Avisos de mantenimiento", 20f, 316f, 190f, 60f, 130f, 50f, false),
        Def("sol", "Amanecer y atardecer", "Curva del sol y cuánto falta para que anochezca", 20f, 316f, 224f, 100f, 160f, 76f, false)
    )

    fun def(id: String): Def? = defs.firstOrNull { it.id == id }

    fun defaults(): MutableList<WSpec> = defs.filter { it.inDefault }.map { WSpec(it.id, it.x, it.y, it.w, it.h) }.toMutableList()

    fun load(p: SharedPreferences): MutableList<WSpec> {
        val s = p.getString(KEY, null) ?: return defaults()
        return try {
            val a = JSONArray(s)
            val out = ArrayList<WSpec>(); var hadCons = false
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                if (id == "cons") { hadCons = true; continue }   // Consumo ya va dentro de Combustible
                val d = def(id) ?: continue
                out.add(WSpec(id, o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.getDouble("w").toFloat().coerceIn(d.minW, W), o.getDouble("h").toFloat().coerceIn(d.minH, H)))
            }
            if (hadCons) out.firstOrNull { it.id == "aceite" }?.let { if (it.w < 200f) it.w = (W - it.x).coerceAtMost(300f) }
            out.forEach { it.x = it.x.coerceIn(0f, W - it.w); it.y = it.y.coerceIn(0f, H - it.h) }
            out
        } catch (e: Exception) { defaults() }
    }

    fun save(p: SharedPreferences, l: List<WSpec>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("w", it.w.toDouble()).put("h", it.h.toDouble())) }
        p.edit().putString(KEY, a.toString()).apply()
    }

    fun reset(p: SharedPreferences) { p.edit().remove(KEY).apply() }
}

/**
 * Capa con todos los widgets del inicio. Toque largo = modo edición: se arrastran, se agrandan
 * desde la esquina, se quitan con la ✕ y se agregan desde la galería. Todo se guarda solo.
 */
class WidgetLayer(
    ctx: Context,
    private val sc: Float,
    private val prefs: SharedPreferences,
    private val acc: Int,
    private val build: (WSpec) -> View,
    private val pickWidget: (missing: List<WStore.Def>, add: (String) -> Unit) -> Unit,
    private val onEditChange: (Boolean) -> Unit = {}
) : FrameLayout(ctx) {

    private val specs: MutableList<WSpec> = WStore.load(prefs)
    private val frames = LinkedHashMap<String, Frame>()
    var editing = false; private set
    private var selected: String? = null
    private var toolbar: View? = null
    private var chips: View? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private fun p(v: Float) = (v * sc).toInt()

    init {
        clipChildren = false
        clipToPadding = false
        rebuildAll()
    }

    fun specList(): List<WSpec> = specs

    /** Esconde todos los widgets menos los indicados (p. ej. mientras está abierto el menú). */
    fun hideExcept(keep: Set<String>) { frames.forEach { (id, f) -> f.visibility = if (id in keep) View.VISIBLE else View.INVISIBLE } }

    fun showAll() { frames.values.forEach { it.visibility = View.VISIBLE } }

    fun has(id: String) = specs.any { it.id == id }

    fun rebuildAll() {
        removeAllViews(); frames.clear(); toolbar = null; chips = null
        specs.forEach { addFrame(it) }
        if (editing) { buildToolbar(); refreshDecor() }
    }

    /** Vuelve a dibujar un widget (p. ej. si cambió su contenido). */
    fun rebuild(id: String) {
        val f = frames[id] ?: return
        f.setContent()
    }

    private fun addFrame(s: WSpec) {
        val f = Frame(context, s)
        frames[s.id] = f
        addView(f, LayoutParams(p(s.w), p(s.h), Gravity.TOP or Gravity.START).apply { leftMargin = p(s.x); topMargin = p(s.y) })
        f.setContent()
    }

    fun enterEdit(sel: String? = null) {
        if (editing) { if (sel != null) select(sel); return }
        editing = true; selected = sel
        buildToolbar(); refreshDecor(); onEditChange(true)
    }

    fun exitEdit() {
        if (!editing) return
        editing = false; selected = null
        toolbar?.let { removeView(it) }; toolbar = null
        chips?.let { removeView(it) }; chips = null
        refreshDecor(); onEditChange(false)
        WStore.save(prefs, specs)
    }

    private fun select(id: String?) { selected = id; refreshDecor() }

    private fun refreshDecor() {
        frames.values.forEach { it.decor(editing, it.spec.id == selected) }
        chips?.let { removeView(it) }; chips = null
        val s = specs.firstOrNull { it.id == selected }
        if (editing && s != null) buildChips(s)
        toolbar?.bringToFront(); chips?.bringToFront()
    }

    private fun remove(id: String) {
        val f = frames.remove(id) ?: return
        removeView(f)
        specs.removeAll { it.id == id }
        if (selected == id) selected = null
        WStore.save(prefs, specs)
        refreshDecor()
    }

    fun add(id: String) {
        if (has(id)) return
        val d = WStore.def(id) ?: return
        val s = WSpec(d.id, d.x, d.y, d.w, d.h)
        // evita encimar exactamente otro widget
        var tries = 0
        while (specs.any { abs(it.x - s.x) < 6f && abs(it.y - s.y) < 6f } && tries < 12) { s.x = (s.x + 24f).coerceAtMost(WStore.W - s.w); s.y = (s.y + 24f).coerceAtMost(WStore.H - s.h); tries++ }
        specs.add(s)
        addFrame(s)
        WStore.save(prefs, specs)
        selected = id
        refreshDecor()
    }

    private fun resetAll() {
        WStore.reset(prefs)
        specs.clear(); specs.addAll(WStore.defaults())
        selected = null
        rebuildAll()
        if (editing) refreshDecor()
    }

    private fun txt(s: String, sp: Float, c: Int, bold: Boolean = false) = TextView(context).apply {
        text = s; setTextColor(c); setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * sc); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(label: String, fg: Int, bgc: Int, onClick: () -> Unit) = txt(label, 13f, fg, true).apply {
        setPadding(p(16f), p(9f), p(16f), p(9f)); gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = p(18f).toFloat(); setColor(bgc) }
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun buildToolbar() {
        toolbar?.let { removeView(it) }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(14f), p(10f), p(14f), p(10f))
            background = GradientDrawable().apply { cornerRadius = p(26f).toFloat(); setColor(0xF20A1326.toInt()); setStroke(max(1, p(1f)), (acc and 0x00FFFFFF) or 0x88000000.toInt()) }
            isClickable = true
        }
        val label = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        label.addView(txt("Editando pantalla", 12.5f, acc, true))
        label.addView(txt("Arrastra para mover · esquina ⤡ para agrandar", 11f, 0xFF9FB0C8.toInt()))
        bar.addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(12f) })
        bar.addView(pill("＋ Agregar widget", Color.WHITE, 0x1FFFFFFF) {
            val missing = WStore.defs.filter { d -> !has(d.id) }
            if (missing.isEmpty()) return@pill
            pickWidget(missing) { id -> add(id) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8f) })
        bar.addView(pill("↺ Restablecer", Color.WHITE, 0x1FFFFFFF) { resetAll() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8f) })
        bar.addView(pill("✓ Listo", 0xFF021018.toInt(), acc) { exitEdit() })
        addView(bar, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = p(108f) })
        toolbar = bar
    }

    private fun buildChips(s: WSpec) {
        val d = WStore.def(s.id) ?: return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(8f), p(5f), p(8f), p(5f))
            background = GradientDrawable().apply { cornerRadius = p(15f).toFloat(); setColor(0xF20A1326.toInt()); setStroke(max(1, p(1f)), 0x33FFFFFF) }
        }
        row.addView(txt("Tamaño", 11.5f, 0xFF9FB0C8.toInt(), true).apply { setPadding(0, 0, p(8f), 0) })
        fun chip(label: String, k: Float) = txt(label, 11.5f, Color.WHITE, true).apply {
            setPadding(p(10f), p(4f), p(10f), p(4f))
            background = GradientDrawable().apply { cornerRadius = p(10f).toFloat(); setColor(0x1FFFFFFF) }
            isClickable = true
            setOnClickListener {
                s.w = (d.w * k).coerceIn(d.minW, WStore.W); s.h = (d.h * k).coerceIn(d.minH, WStore.H)
                s.x = s.x.coerceIn(0f, WStore.W - s.w); s.y = s.y.coerceIn(0f, WStore.H - s.h)
                frames[s.id]?.applyGeometry(); frames[s.id]?.setContent(); WStore.save(prefs, specs); refreshDecor()
            }
        }
        row.addView(chip("Chico", 0.75f), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(5f) })
        row.addView(chip("Mediano", 1f), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(5f) })
        row.addView(chip("Grande", 1.45f))
        // dentro de la esquina inferior del widget, o debajo si cabe
        val belowY = s.y + s.h + 8f
        val y = if (belowY + 38f < WStore.H - 100f) belowY else (s.y + s.h - 44f).coerceAtLeast(2f)
        addView(row, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = p(s.x.coerceIn(0f, WStore.W - 230f)); topMargin = p(y) })
        chips = row
    }

    // ------------------------------------------------------------------ marco de un widget
    private inner class Frame(ctx: Context, val spec: WSpec) : FrameLayout(ctx) {
        private var content: View? = null
        private var border: View? = null
        private var tagV: TextView? = null
        private var delV: TextView? = null
        private var handleV: TextView? = null
        private var downX = 0f; private var downY = 0f
        private var startX = 0f; private var startY = 0f; private var startW = 0f; private var startH = 0f
        private var mode = 0 // 1 mover, 2 agrandar, 3 quitar
        private var moved = false
        private val longPress = Runnable { if (hasWindowFocus() && !editing) enterEdit(spec.id) }

        init { clipChildren = false; clipToPadding = false }

        fun setContent() {
            content?.let { removeView(it) }
            val v = try { build(spec) } catch (e: Throwable) { View(context) }
            content = v
            addView(v, 0, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        fun applyGeometry() {
            val lp = layoutParams as LayoutParams
            lp.width = p(spec.w); lp.height = p(spec.h); lp.leftMargin = p(spec.x); lp.topMargin = p(spec.y)
            layoutParams = lp
        }

        fun decor(on: Boolean, sel: Boolean) {
            border?.let { removeView(it) }; tagV?.let { removeView(it) }; delV?.let { removeView(it) }; handleV?.let { removeView(it) }
            border = null; tagV = null; delV = null; handleV = null
            if (!on) return
            val col = if (sel) 0xFFFFC94D.toInt() else acc
            val d = WStore.def(spec.id)
            border = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = p(22f).toFloat(); setColor(Color.TRANSPARENT)
                    setStroke(max(2, p(2f)), col, if (sel) 0f else p(7f).toFloat(), if (sel) 0f else p(5f).toFloat())
                }
            }
            addView(border, LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(-p(3f), -p(3f), -p(3f), -p(3f)) })
            tagV = txt("⠿ " + (d?.name ?: spec.id), 10f, 0xFF021018.toInt(), true).apply {
                setPadding(p(8f), p(2f), p(8f), p(2f)); setSingleLine(true)
                background = GradientDrawable().apply { cornerRadius = p(9f).toFloat(); setColor(col) }
            }
            addView(tagV, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = p(6f); topMargin = -p(11f) })
            delV = txt("✕", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF5D5D.toInt()) } }
            addView(delV, LayoutParams(p(24f), p(24f), Gravity.TOP or Gravity.END).apply { topMargin = -p(11f); rightMargin = -p(11f) })
            handleV = txt("⤡", 14f, 0xFF021018.toInt(), true).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) } }
            addView(handleV, LayoutParams(p(28f), p(28f), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = -p(12f); rightMargin = -p(12f) })
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (editing) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; postDelayed(longPress, 700L) }
                MotionEvent.ACTION_MOVE -> if (hypot(e.rawX - downX, e.rawY - downY) > slop) removeCallbacks(longPress)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return false
        }

        private fun hit(v: View?, e: MotionEvent): Boolean {
            if (v == null) return false
            val pad = p(8f)
            return e.x >= v.left - pad && e.x <= v.right + pad && e.y >= v.top - pad && e.y <= v.bottom + pad
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!editing) return super.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.rawX; downY = e.rawY; startX = spec.x; startY = spec.y; startW = spec.w; startH = spec.h
                    moved = false
                    mode = when { hit(delV, e) -> 3; hit(handleV, e) -> 2; else -> 1 }
                    if (selected != spec.id) select(spec.id)
                    bringToFront(); toolbar?.bringToFront()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX) / sc; val dy = (e.rawY - downY) / sc
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > slop) moved = true
                    if (!moved) return true
                    val d = WStore.def(spec.id)
                    if (mode == 1) {
                        spec.x = (startX + dx).coerceIn(0f, WStore.W - spec.w); spec.y = (startY + dy).coerceIn(0f, WStore.H - spec.h)
                        applyGeometry(); chips?.let { removeView(it) }; chips = null
                    } else if (mode == 2) {
                        spec.w = (startW + dx).coerceIn(d?.minW ?: 60f, WStore.W - spec.x); spec.h = (startH + dy).coerceIn(d?.minH ?: 40f, WStore.H - spec.y)
                        applyGeometry(); chips?.let { removeView(it) }; chips = null
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (mode == 3 && !moved) { remove(spec.id); return true }
                    if (moved) {
                        // se acomoda a una cuadrícula de 8
                        fun snap(v: Float) = (v / 8f).roundToInt() * 8f
                        if (mode == 1) { spec.x = snap(spec.x).coerceIn(0f, WStore.W - spec.w); spec.y = snap(spec.y).coerceIn(0f, WStore.H - spec.h) }
                        if (mode == 2) { spec.w = snap(spec.w).coerceIn(WStore.def(spec.id)?.minW ?: 60f, WStore.W - spec.x); spec.h = snap(spec.h).coerceIn(WStore.def(spec.id)?.minH ?: 40f, WStore.H - spec.y) }
                        applyGeometry()
                        if (mode == 2) setContent()
                        WStore.save(prefs, specs)
                    }
                    refreshDecor()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { applyGeometry(); refreshDecor(); return true }
            }
            return true
        }
    }

    /** Toque en un hueco: quita la selección. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN && selected != null) select(null)
        return true
    }
}

/** Mini mapa del inicio: recorrido reciente, flecha del auto con su rumbo y datos del viaje. Toque = mapa completo. */
class MiniMapView(ctx: Context, private val dp: Float, private val acc: Int, private val fg: Int) : View(ctx) {
    class Data(val trail: List<DoubleArray>, val bearing: Float, val km: Double, val moveMs: Long, val avg: Double, val max: Double, val mapReady: Boolean)

    var provider: (() -> Data)? = null
    /** true = solo dibuja el rótulo y la franja de datos encima de un mapa real. */
    var overlayOnly = false
    private val pLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val pGrid = android.graphics.Paint().apply { color = 0x14FFFFFF; style = android.graphics.Paint.Style.STROKE }
    private val pTxt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val pFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun compass(b: Float): String {
        if (b < 0f) return "--"
        val n = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return n[((b + 22.5f) / 45f).toInt() % 8]
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 10f || h < 10f) return
        val d = provider?.invoke() ?: return
        val u = (w / 300f).coerceIn(0.6f, 2.2f)
        val statsH = (30f * u).coerceAtMost(h * 0.28f)
        val ph = h - statsH
        // fondo esquemático (solo si no hay mapa real debajo)
        if (!overlayOnly) {
            pGrid.strokeWidth = 3f * u
            for (i in 1..4) { c.drawLine(w * i / 5f, 0f, w * (i + 0.4f) / 5f, ph, pGrid) }
            for (i in 1..2) { c.drawLine(0f, ph * i / 3f, w, ph * (i + 0.5f) / 3f, pGrid) }
        }
        // recorrido
        val pts = if (overlayOnly) emptyList() else d.trail
        val pad = 28f * u
        if (pts.size >= 2) {
            var minLa = Double.MAX_VALUE; var maxLa = -Double.MAX_VALUE; var minLo = Double.MAX_VALUE; var maxLo = -Double.MAX_VALUE
            for (p in pts) { minLa = min(minLa, p[0]); maxLa = max(maxLa, p[0]); minLo = min(minLo, p[1]); maxLo = max(maxLo, p[1]) }
            val k = 111320.0
            val cosLa = Math.cos(Math.toRadians((minLa + maxLa) / 2))
            val spanY = max((maxLa - minLa) * k, 60.0); val spanX = max((maxLo - minLo) * k * cosLa, 60.0)
            val s = min((w - 2 * pad) / spanX, (ph - 2 * pad) / spanY)
            val cx = w / 2f; val cy = ph / 2f
            val mx = (minLo + maxLo) / 2; val my = (minLa + maxLa) / 2
            val path = android.graphics.Path()
            pts.forEachIndexed { i, p ->
                val x = cx + ((p[1] - mx) * k * cosLa * s).toFloat(); val y = cy - ((p[0] - my) * k * s).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            pLine.color = acc; pLine.strokeWidth = 5f * u
            c.drawPath(path, pLine)
            val last = pts.last()
            drawArrow(c, cx + ((last[1] - mx) * k * cosLa * s).toFloat(), cy - ((last[0] - my) * k * s).toFloat(), d.bearing, u)
        } else if (!overlayOnly && (pts.size == 1 || d.bearing >= 0f)) {
            drawArrow(c, w / 2f, ph / 2f, d.bearing, u)
        }
        // texto superior
        pTxt.color = fg; pTxt.textSize = 13f * u; pTxt.typeface = Typeface.DEFAULT
        val gpsOk = if (overlayOnly) !(TripService.lat.isNaN()) else pts.isNotEmpty()
        val head = (if (!gpsOk) "Sin GPS todavía" else "Rumbo ${compass(d.bearing)}") + (if (d.mapReady) " · mapa sin internet" else "")
        if (overlayOnly) { pFill.color = 0xB3000000.toInt(); c.drawRoundRect(8f * u, 6f * u, 8f * u + pTxt.measureText(head) + 14f * u, 30f * u, 10f * u, 10f * u, pFill) }
        pTxt.color = fg; if (overlayOnly) pTxt.color = Color.WHITE
        c.drawText(head, if (overlayOnly) 15f * u else 12f * u, 22f * u, pTxt)
        // franja de datos
        pFill.color = 0xAA000000.toInt(); c.drawRect(0f, ph, w, h, pFill)
        val cols = listOf("Viaje" to String.format(java.util.Locale.US, "%.1f km", d.km),
            "Tiempo" to String.format(java.util.Locale.US, "%d:%02d h", d.moveMs / 3_600_000L, (d.moveMs / 60_000L) % 60),
            "Prom." to d.avg.roundToInt().toString(), "Máx." to d.max.roundToInt().toString())
        pTxt.textSize = 10.5f * u
        val cw = w / cols.size
        cols.forEachIndexed { i, (a, b) ->
            pTxt.color = 0xFF9FB0C8.toInt(); val ta = "$a "
            val full = ta + b
            val wa = pTxt.measureText(full)
            val x0 = cw * i + (cw - wa) / 2f
            c.drawText(ta, x0, ph + statsH * 0.64f, pTxt)
            pTxt.color = Color.WHITE
            c.drawText(b, x0 + pTxt.measureText(ta), ph + statsH * 0.64f, pTxt)
        }
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, bearing: Float, u: Float) {
        pFill.color = (acc and 0x00FFFFFF) or 0x33000000
        c.drawCircle(x, y, 17f * u, pFill)
        c.save(); c.translate(x, y); c.rotate(if (bearing >= 0f) bearing else 0f)
        pFill.color = acc
        val a = android.graphics.Path().apply { moveTo(0f, -12f * u); lineTo(8f * u, 9f * u); lineTo(0f, 4f * u); lineTo(-8f * u, 9f * u); close() }
        c.drawPath(a, pFill)
        c.restore()
    }
}

/** Últimos puntos GPS (lat, lon) del recorrido, para el mini mapa. */
object HomeTrail { val pts = ArrayList<DoubleArray>() }
