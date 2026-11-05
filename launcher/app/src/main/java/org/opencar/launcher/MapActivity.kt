package org.opencar.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import btools.router.OpenCarRouter
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.reader.MapFile
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mapa propio sin internet: mapa de la región descargada, búsqueda de calles y lugares, ruta y guía por voz. */
class MapActivity : Activity(), LocationListener {
    companion object {
        private var gfxReady = false
        const val ACC_DEFAULT = 0xFF4DD0E1.toInt()
    }

    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    /** Centro del mapa descargado (antes de tener GPS). */
    private val CENTER: LatLong get() = MapData.center(this).let { LatLong(it.first, it.second) }
    private val ui = Handler(Looper.getMainLooper())
    private var sc = 1f
    private fun p(v: Int) = (v * sc).toInt()
    private val acc: Int by lazy { try { Modes.get(prefs.getString("app_mode", "ios") ?: "ios").acc } catch (e: Throwable) { ACC_DEFAULT } }
    private val night: Boolean by lazy {
        when (prefs.getString("theme_mode", "auto")) { "dark" -> true; "light" -> false; else -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 18 || it < 6 } }
    }
    private val glass: Int get() = if (night) 0xF00A1222.toInt() else 0xF5FFFFFF.toInt()
    private val fg: Int get() = if (night) Color.WHITE else 0xFF1B2230.toInt()
    private val fg2: Int get() = if (night) 0xFF9FB0C8.toInt() else 0xFF5A6678.toInt()
    private val line: Int get() = if (night) 0x33FFFFFF else 0x22000000

    private lateinit var root: FrameLayout
    private lateinit var holder: FrameLayout
    private var mapView: MapView? = null
    private var renderer: TileRendererLayer? = null
    private var sentido: Sentido? = null
    private var sentidoChip: TextView? = null
    private var sentidoAt = 0L
    private var sentidoLast = Sentido.Estado.NADA

    /** Chip con el tipo de calle donde vas: un sentido, doble sentido o contramano. */
    private fun sentidoTick() {
        val sn = sentido ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - sentidoAt < 1200L || myLat.isNaN()) return
        sentidoAt = now
        val la = myLat; val lo = myLon; val b = myBear; val mv = mySpeed > 2.0f
        Thread {
            val r = try { sn.check(la, lo, b, mv) } catch (e: Throwable) { Sentido.Res(Sentido.Estado.NADA, null) }
            ui.post {
                val chip = sentidoChip ?: TextView(this).also { t ->
                    t.textSize = 15f; t.setTypeface(null, android.graphics.Typeface.BOLD); t.setPadding((14 * sc).toInt(), (8 * sc).toInt(), (14 * sc).toInt(), (8 * sc).toInt())
                    t.visibility = View.GONE; sentidoChip = t
                    (mapView?.parent as? FrameLayout)?.addView(t, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = (16 * sc).toInt(); bottomMargin = (16 * sc).toInt() })
                }
                val nm = r.calle?.let { " · $it" } ?: ""
                when (r.estado) {
                    Sentido.Estado.CONTRAMANO -> { chip.text = "⛔ CONTRAMANO$nm"; chip.setTextColor(Color.WHITE); chip.background = GradientDrawable().apply { cornerRadius = 22f * sc; setColor(0xFFD32F2F.toInt()) }; chip.visibility = View.VISIBLE
                        if (sentidoLast != Sentido.Estado.CONTRAMANO) { try { (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(400) } catch (e: Throwable) { } } }
                    Sentido.Estado.UN_SENTIDO -> { chip.text = "➜ Un solo sentido$nm"; chip.setTextColor(Color.WHITE); chip.background = GradientDrawable().apply { cornerRadius = 22f * sc; setColor(0xFF1F5FBF.toInt()) }; chip.visibility = View.VISIBLE }
                    Sentido.Estado.DOBLE -> { chip.text = "⇄ Doble sentido$nm"; chip.setTextColor(0xFF1B2A3A.toInt()); chip.background = GradientDrawable().apply { cornerRadius = 22f * sc; setColor(0xE6FFFFFF.toInt()) }; chip.visibility = View.VISIBLE }
                    else -> chip.visibility = View.GONE
                }
                sentidoLast = r.estado
            }
        }.start()
    }
    private val index by lazy { PlaceIndex(this) }

    // capas
    private var casing: Polyline? = null
    private var routeLine: Polyline? = null
    private var posLayer: PosLayer? = null
    private var rutaFlechas: RutaFlechas? = null
    private var destLayer: DestLayer? = null

    // interfaz
    private var topUi: LinearLayout? = null
    private var fabCol: LinearLayout? = null
    private var searchPanel: LinearLayout? = null
    private var resultsBox: LinearLayout? = null
    private var searchEdit: EditText? = null
    private var previewCard: LinearLayout? = null
    private var navBanner: LinearLayout? = null
    private var navBottom: LinearLayout? = null
    private var dlCard: LinearLayout? = null
    private var infoToast: TextView? = null
    private lateinit var arrow: ArrowView
    private var bigDist: TextView? = null
    private var bigText: TextView? = null
    private var thenText: TextView? = null
    private var etaT: TextView? = null
    private var remT: TextView? = null
    private var kmT: TextView? = null
    private var speedT: TextView? = null
    private var streetT: TextView? = null
    private var previewT: TextView? = null
    private var previewS: TextView? = null
    private var dlStatus: TextView? = null
    private var dlBar: View? = null
    private var dlBarFill: View? = null
    private var idxStatus: TextView? = null

    // estado
    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myBear = 0f
    private var bearSm = 0f
    private var gpsAt = 0L
    private var headUp = false
    private var mySpeed = 0f
    private var follow = true
    private var navigating = false
    private var route: OpenCarRouter.Result? = null
    private var destName = ""
    private var destLat = Double.NaN
    private var destLon = Double.NaN
    private var lastIdx = 0
    private var offCount = 0
    private val spoken = HashSet<String>()
    private var rerouting = false
    private var searching = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dm = resources.displayMetrics
        sc = min(dm.widthPixels / 1024f, dm.heightPixels / 600f)
        if (!gfxReady) { LiveMap.ensureGfx(this); gfxReady = true }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (night) 0xFF0E1116.toInt() else 0xFFE8EAED.toInt()))
        try { window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT } catch (e: Throwable) { }
        if (android.os.Build.VERSION.SDK_INT >= 28) try { window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS } } catch (e: Throwable) { }
        root = FrameLayout(this).apply { setBackgroundColor(if (night) 0xFF0E1116.toInt() else 0xFFE8EAED.toInt()) }
        holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        hideBars()
        buildUi()
        if (MapData.ready(this)) { MapData.ensureProfiles(this); startMap() } else showDownload()
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIntent(i) }

    private fun hideBars() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.hide(WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            // también las banderas clásicas: algunas pantallas de auto reservan el espacio de la barra aunque esté oculta
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        } catch (e: Throwable) { }
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideBars() }

    // ------------------------------------------------------------------ pequeñas ayudas de diseño
    private fun bg(c: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = p(r).toFloat(); setColor(c); if (stroke != 0) setStroke(max(1, p(1)), stroke)
    }
    private fun tx(s: String, sp: Float, c: Int = fg, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; setTextColor(c); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * sc)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun circleBtn(label: String, size: Int = 46, tint: Int = 0, onClick: () -> Unit): TextView = tx(label, 22f, if (tint != 0) tint else fg, true).apply {
        gravity = Gravity.CENTER; background = bg(glass, size / 2, line); isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(p(size), p(size)).apply { bottomMargin = p(10) }
    }
    private fun toast(s: String) = ui.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun buildUi() {
        // barra superior: volver + buscar + menú
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(circleBtn("‹", 46) { onBackPressed() }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = p(10) })
        val sb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(glass, 26, line)
            setPadding(p(18), 0, p(18), 0); isClickable = true
            setOnClickListener { openSearch() }
        }
        sb.addView(tx("Buscar calle, avenida o lugar", 15f, fg2).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sb.addView(tx("🔍", 18f))
        top.addView(sb, LinearLayout.LayoutParams(p(430), p(50)))
        top.addView(circleBtn("⋮", 46) { menu() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = p(10) })
        topUi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topUi!!.addView(top)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(label: String, go: () -> Unit) = tx(label, 13f).apply {
            setPadding(p(14), p(7), p(14), p(7)); background = bg(glass, 18, line); isClickable = true
            setOnClickListener { go() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8); topMargin = p(10) }
        }
        chips.addView(chip("🏠 Casa") { goSaved("place_casa", "Casa") })
        chips.addView(chip("💼 Trabajo") { goSaved("place_trabajo", "Trabajo") })
        chips.addView(chip("👤 Pareja") { goSaved("place_pareja", "Pareja") })
        chips.addView(chip("👪 Familia") { goSaved("place_familia", "Familia") })
        chips.addView(chip("🔧 Taller") { goSaved("place_taller", "Taller") })
        chips.addView(chip("⛽ Gasolineras") { showFuel(false, false) })
        topUi!!.addView(chips)
        root.addView(topUi, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(16); topMargin = p(14) })

        // botones laterales
        fabCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fabCol!!.addView(circleBtn("+") { mapView?.model?.mapViewPosition?.zoomIn() })
        fabCol!!.addView(circleBtn("−") { mapView?.model?.mapViewPosition?.zoomOut() })
        fabCol!!.addView(circleBtn("◎", 46, acc) { follow = true; centerOnMe(true); applyHeading() })
        root.addView(fabCol, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.TOP).apply { rightMargin = p(16); topMargin = p(70) })

        infoToast = tx("", 13f).apply { background = bg(glass, 18, line); setPadding(p(14), p(7), p(14), p(7)); visibility = View.GONE }
        root.addView(infoToast, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = p(16); bottomMargin = p(16) })

        buildSearch(); buildPreview(); buildNav()
    }

    private fun info(s: String?) = ui.post { infoToast?.let { if (s.isNullOrEmpty()) it.visibility = View.GONE else { it.text = s; it.visibility = View.VISIBLE } } }

    // ------------------------------------------------------------------ descarga
    private fun showDownload() {
        dlCard?.let { root.removeView(it) }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(glass, 26, line); setPadding(p(26), p(22), p(26), p(22)); isClickable = true }
        c.addView(tx("Mapa sin internet", 22f, fg, true))
        c.addView(tx("Elige tu región y se descarga una sola vez (el tamaño depende de la región: del orden de decenas a cientos de MB, más los datos para calcular rutas). Conecta la pantalla a internet, mejor con Wi-Fi. Después funciona sin internet.", 14f, fg2).apply { setPadding(0, p(8), 0, p(10)) })
        val regionBtn = tx(if (MapData.regionPath(this) == null) "🌍  Elegir región del mapa" else "🌍  Región: ${MapData.regionName(this)}   (cambiar)", 15f, fg, true).apply {
            gravity = Gravity.CENTER; setPadding(p(14), p(12), p(14), p(12)); background = bg(line, 18); isClickable = true
            setOnClickListener { RegionPicker(this@MapActivity) { path, name -> MapData.setRegion(this@MapActivity, path, name); showDownload() }.show() }
        }
        c.addView(regionBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p(12) })
        val bar = FrameLayout(this).apply { background = bg(line, 6) }
        val fill = View(this).apply { background = bg(acc, 6) }
        bar.addView(fill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        c.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, p(10)))
        dlBar = bar; dlBarFill = fill
        dlStatus = tx("Listo para descargar", 13f, fg2).apply { setPadding(0, p(8), 0, p(12)) }
        c.addView(dlStatus)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val go = tx("Descargar ahora", 16f, Color.parseColor("#021018"), true).apply {
            gravity = Gravity.CENTER; background = bg(acc, 18); isClickable = true
            setOnClickListener { if (MapData.regionPath(this@MapActivity) == null) regionBtn.performClick() else { startService(Intent(this@MapActivity, MapDownloadService::class.java)); pollDownload() } }
        }
        val cancel = tx("Cancelar", 15f).apply { gravity = Gravity.CENTER; background = bg(line, 18); isClickable = true; setOnClickListener { startService(Intent(this@MapActivity, MapDownloadService::class.java).setAction("cancel")) } }
        row.addView(go, LinearLayout.LayoutParams(0, p(52), 1.4f).apply { rightMargin = p(10) })
        row.addView(cancel, LinearLayout.LayoutParams(0, p(52), 1f))
        c.addView(row)
        dlCard = c
        root.addView(c, FrameLayout.LayoutParams(p(560), WRAP_CONTENT, Gravity.CENTER))
        topUi?.visibility = View.GONE; fabCol?.visibility = View.GONE
        if (MapData.running) pollDownload()
    }

    private fun pollDownload() {
        val card = dlCard ?: return
        val w = (dlBar?.width ?: 0)
        dlBarFill?.layoutParams = FrameLayout.LayoutParams(w * MapData.pct() / 100, MATCH_PARENT)
        dlStatus?.text = if (MapData.running || MapData.total > 0) "${MapData.msg} · ${MapData.pct()} % (${MapData.mb(MapData.done)} de ${MapData.mb(MapData.total)} MB)" else MapData.msg.ifEmpty { "Listo para descargar" }
        if (MapData.ready(this) && !MapData.running) {
            root.removeView(card); dlCard = null
            topUi?.visibility = View.VISIBLE; fabCol?.visibility = View.VISIBLE
            VozClips.play(this, "maps_ready")
            startMap(); return
        }
        if (MapData.running || MapData.total == 0L) ui.postDelayed({ pollDownload() }, 800)
        else dlStatus?.text = MapData.msg
    }

    // ------------------------------------------------------------------ mapa
    private fun startMap() {
        if (mapView != null) return
        MapData.ensureProfiles(this)
        val mf = try { MapFile(MapData.mapFile(this)) } catch (e: Throwable) { toast("No pude abrir el mapa descargado"); return }
        val mv = MapView(this)
        mapView = mv
        mv.isClickable = true
        mv.mapScaleBar.isVisible = false
        mv.setBuiltInZoomControls(false)
        mv.model.displayModel.setUserScaleFactor(1.3f)
        val sn0 = try { Sentido(MapData.mapFile(this)) } catch (e: Throwable) { null }
        sentido = sn0
        val tc = AndroidUtil.createTileCache(this, LiveMap.cacheId(this, "opencar_map", night), mv.model.displayModel.tileSize, 2f, mv.model.frameBufferModel.overdrawFactor)
        val tl = AndroidUtil.createTileRendererLayer(tc, mv.model.mapViewPosition, mf, LiveMap.theme(this, night, sn0 != null), false, true, false)
        tl.textScale = 1.25f
        renderer = tl
        mv.layerManager.layers.add(tl)
        sn0?.let { sn -> mv.layerManager.layers.add(sn.NombresLayer(sc, night) { mapView?.rotation ?: 0f }) }
        val gf = AndroidGraphicFactory.INSTANCE
        fun paint(color: Int, w: Float) = gf.createPaint().apply { setColor(color); strokeWidth = w * sc; setStyle(Style.STROKE); setStrokeCap(Cap.ROUND) }
        casing = Polyline(paint(if (night) 0xFF06202A.toInt() else Color.WHITE, 18f), gf)
        routeLine = Polyline(paint(if (night) 0xFF00B8D4.toInt() else 0xFF1A73E8.toInt(), 12f), gf)
        mv.layerManager.layers.add(casing)
        mv.layerManager.layers.add(routeLine)
        rutaFlechas = RutaFlechas().also { mv.layerManager.layers.add(it) }
        sn0?.let { sn -> mv.layerManager.layers.add(sn.FlechasLayer(sc)) }
        destLayer = DestLayer().also { mv.layerManager.layers.add(it) }
        posLayer = PosLayer().also { mv.layerManager.layers.add(it) }
        holder.addView(mv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        mv.model.mapViewPosition.setZoomLevelMin(7); mv.model.mapViewPosition.setZoomLevelMax(21)
        val la = prefs.getString("map_lat", null)?.toDoubleOrNull() ?: CENTER.latitude
        val lo = prefs.getString("map_lon", null)?.toDoubleOrNull() ?: CENTER.longitude
        mv.model.mapViewPosition.mapPosition = MapPosition(LatLong(la, lo), 15)
        mv.addInputListener(object : org.mapsforge.map.view.InputListener {
            override fun onMoveEvent() { if (!navigating) follow = false }
            override fun onZoomEvent() {}
        })
        mv.layerManager.layers.add(object : Layer() {
            override fun draw(bb: BoundingBox, z: Byte, c: org.mapsforge.core.graphics.Canvas, tlp: Point) {}
            override fun onLongPress(ll: LatLong, a: Point, b: Point): Boolean { longPressMenu(ll); return true }
        })
        applyNight(mv)
        startLocation()
        ensureIndex()
    }

    private fun applyNight(mv: MapView) { mv.setLayerType(View.LAYER_TYPE_NONE, null) }

    /** Si todavía no se indexó esta zona, se hace en segundo plano para poder buscar calles sin internet. */
    private fun ensureIndex() {
        val la = if (myLat.isNaN()) CENTER.latitude else myLat
        val lo = if (myLon.isNaN()) CENTER.longitude else myLon
        if (index.covers(la, lo) || PlaceIndex.building) return
        info("Preparando búsqueda de calles y lugares…")
        Thread {
            index.build(la, lo)
            info(null)
            ui.post { idxStatus?.text = "Calles y lugares listos (${index.count()})" }
        }.start()
    }

    // ------------------------------------------------------------------ GPS
    private fun startLocation() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 91); return
            }
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            try { if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, Looper.getMainLooper()) } catch (e: Exception) { }
            myBear = prefs.getFloat("map_bear", 0f); bearSm = myBear
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onLocationChanged(last)
            else {
                // sin señal todavía: se muestra el auto al instante en la última posición conocida
                if (!TripService.lat.isNaN()) { myLat = TripService.lat; myLon = TripService.lon }
                else { val a = prefs.getString("map_lat", null)?.toDoubleOrNull(); val o = prefs.getString("map_lon", null)?.toDoubleOrNull(); if (a != null && o != null) { myLat = a; myLon = o } }
                if (!myLat.isNaN()) { applyHeading(); centerOnMe(false) }
            }
        } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) { if (rc == 91) startLocation() }

    override fun onLocationChanged(l: Location) {
        val isGps = l.provider == LocationManager.GPS_PROVIDER
        val now = android.os.SystemClock.elapsedRealtime()
        if (isGps) gpsAt = now else if (now - gpsAt < 15000L && !myLat.isNaN()) return   // la red solo sirve mientras no hay GPS
        myLat = l.latitude; myLon = l.longitude
        if (isGps) mySpeed = l.speed
        if (isGps && l.hasBearing() && l.speed > 1.5f) { myBear = l.bearing; prefs.edit().putFloat("map_bear", myBear).apply() }
        else if (navigating && mySpeed < 1.5f) routeBearing()?.let { myBear = it }   // parado: mira hacia donde va la ruta
        sentidoTick()
        if (navigating) navTick() else { applyHeading(); if (follow) centerOnMe(false) }
        prefs.edit().putString("map_lat", myLat.toString()).putString("map_lon", myLon.toString()).apply()
    }
    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    /** Rumbo del tramo de ruta donde estás (para orientar el auto cuando aún no te mueves). */
    private fun routeBearing(): Float? {
        val r = route ?: return null
        val n = r.lats.size; if (n < 2) return null
        val i = lastIdx.coerceIn(0, n - 2); var j = i + 1
        val kx0 = cos(Math.toRadians(r.lats[i])) * 111320.0
        while (j < n - 1 && hypot((r.lons[j] - r.lons[i]) * kx0, (r.lats[j] - r.lats[i]) * 111320.0) < 8.0) j++   // el tramo de la calle donde estás (no el de después de la esquina)
        val dy = r.lats[j] - r.lats[i]; val dx = (r.lons[j] - r.lons[i]) * cos(Math.toRadians(r.lats[i]))
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return null
        return ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
    }

    /** El auto apunta a su rumbo y el mapa gira para que siempre veas hacia dónde vas (arriba = adelante). */
    private fun applyHeading() {
        if (myLat.isNaN()) return
        val d = ((myBear - bearSm + 540f) % 360f) - 180f
        bearSm = (bearSm + d * 0.5f + 360f) % 360f
        posLayer?.set(myLat, myLon, bearSm)
        val on = navigating || follow
        if (on != headUp) setNavView(on)
        mapView?.let { mv -> if (headUp) { mv.rotation = -bearSm; mv.layerManager.redrawLayers() } }
    }

    private fun centerOnMe(zoom: Boolean) {
        val mv = mapView ?: return
        if (myLat.isNaN()) { toast("Esperando señal de GPS…"); return }
        val z = if (zoom) max(mv.model.mapViewPosition.zoomLevel.toInt(), 16).toByte() else mv.model.mapViewPosition.zoomLevel
        mv.model.mapViewPosition.mapPosition = MapPosition(LatLong(myLat, myLon), z)
    }

    // ------------------------------------------------------------------ búsqueda
    private fun buildSearch() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(glass, 24, line); setPadding(p(14), p(14), p(14), p(10)); visibility = View.GONE; isClickable = true }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(this).apply {
            hint = "Calle, avenida o lugar"; setHintTextColor(fg2); setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17f * sc); setSingleLine(); background = bg(line, 22)
            setPadding(p(18), 0, p(18), 0); imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchEdit = et
        et.addTextChangedListener(object : TextWatcher {
            private var job: Runnable? = null
            override fun afterTextChanged(s: Editable?) { job?.let { ui.removeCallbacks(it) }; job = Runnable { runSearch(s?.toString() ?: "", false) }.also { ui.postDelayed(it, 220) } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        et.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { runSearch(et.text.toString(), true); true } else false }
        row.addView(et, LinearLayout.LayoutParams(0, p(48), 1f))
        row.addView(tx("✕", 18f, fg2, true).apply { gravity = Gravity.CENTER; setOnClickListener { closeSearch() } }, LinearLayout.LayoutParams(p(48), p(48)))
        panel.addView(row)
        val sv = ScrollView(this)
        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(resultsBox)
        panel.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        searchPanel = panel
        root.addView(panel, FrameLayout.LayoutParams(p(470), MATCH_PARENT).apply { leftMargin = p(14); topMargin = p(14); bottomMargin = p(14) })
    }

    private fun openSearch() {
        val mv = mapView ?: run { toast("Primero descarga el mapa"); return }
        searchPanel?.visibility = View.VISIBLE; topUi?.visibility = View.GONE
        searchEdit?.setText(""); searchEdit?.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(searchEdit, 0)
        showResults(emptyList(), "Escribe una calle, avenida o lugar (por ejemplo: una avenida, un centro comercial o una universidad)")
    }

    private fun closeSearch() {
        searchPanel?.visibility = View.GONE; topUi?.visibility = View.VISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(searchEdit?.windowToken, 0)
    }


    // ------------------------------------------------------------------ estaciones de servicio
    private var fuelPanel: LinearLayout? = null
    private var fuelList: List<Pair<Estacion, Double>> = emptyList()
    private var fuelGo = false
    private var fuelVoice: VoiceEngine? = null

    private fun closeFuel() {
        fuelPanel?.let { root.removeView(it) }; fuelPanel = null
        AsistenteService.choiceUntil = 0L; AsistenteService.choiceCb = null
        fuelVoice?.stop(); VozClips.stop()
        topUi?.visibility = View.VISIBLE
    }

    /** Las 5 estaciones más cercanas. byVoice = pide por voz que elijas o confirmes. */
    private fun showFuel(gas: Boolean, byVoice: Boolean) {
        val (la, lo) = refPoint()
        val noFix = myLat.isNaN()
        val list = Estaciones.nearest(this, la, lo, 5, gas)
        if (list.isEmpty()) { toast("No hay estaciones de servicio guardadas"); VozClips.play(this, "fuel_none"); return }
        fuelList = list
        Estaciones.choices = list.map { it.first }
        ui.post {
            fuelPanel?.let { root.removeView(it) }
            searchPanel?.visibility = View.GONE
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(glass, 24, line); setPadding(p(14), p(12), p(14), p(10)); isClickable = true }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tx(if (gas) "Estaciones cercanas · gas" else "Estaciones de servicio cercanas", 17f, fg, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(tx("✕", 18f, fg2, true).apply { gravity = Gravity.CENTER; setOnClickListener { closeFuel() } }, LinearLayout.LayoutParams(p(48), p(40)))
            panel.addView(head)
            val sub = if (noFix) "Sin GPS todavía: medido desde el centro del mapa" else if (gas && !list.any { it.first.gas }) "El mapa no indica cuáles tienen gas; se muestran las más cercanas" else "Di el número, «sí» para la más cercana, o toca una"
            panel.addView(tx(sub, 12f, fg2).apply { setPadding(0, 0, 0, p(6)) })
            list.forEachIndexed { i, (e, d) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(if (i == 0) (acc and 0x00FFFFFF) or 0x33000000 else line, 16); setPadding(p(10), p(8), p(12), p(8)); isClickable = true; setOnClickListener { pickFuel(i + 1) } }
                row.addView(tx("${i + 1}", 22f, Color.parseColor("#021018"), true).apply { gravity = Gravity.CENTER; background = bg(acc, 20) }, LinearLayout.LayoutParams(p(40), p(40)))
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), 0, 0, 0) }
                col.addView(tx(e.shown, 15f, fg, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(tx((if (i == 0) "La más cercana" else "Estación") + (if (e.gas) " · gas" else ""), 11.5f, fg2))
                row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.addView(tx(Estaciones.km(d), 15f, acc, true))
                panel.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) })
            }
            fuelPanel = panel
            topUi?.visibility = View.GONE
            root.addView(panel, FrameLayout.LayoutParams(p(470), WRAP_CONTENT).apply { leftMargin = p(14); topMargin = p(14) })
            // mostrar todas en el mapa
            try {
                val mv = mapView
                if (mv != null) {
                    val pts = list.map { LatLong(it.first.lat, it.first.lon) } + LatLong(la, lo)
                    val bb = BoundingBox(pts.minOf { it.latitude }, pts.minOf { it.longitude }, pts.maxOf { it.latitude }, pts.maxOf { it.longitude })
                    val dim = Dimension(max(100, mv.width), max(100, mv.height))
                    val z = min(16, LatLongUtils.zoomForBounds(dim, bb.extendMeters(400), mv.model.displayModel.tileSize).toInt()).toByte()
                    follow = false
                    mv.model.mapViewPosition.mapPosition = MapPosition(bb.centerPoint, z)
                    destLayer?.set(list[0].first.lat, list[0].first.lon); mv.layerManager.redrawLayers()
                }
            } catch (e: Exception) { }
        }
        if (byVoice) {
            if (noFix) VozClips.play(this, "fuel_gps")
            else VozClips.play(this, "fuel_ask") { ui.postDelayed({ listenFuel() }, 500) }
        }
    }

    private fun listenFuel() {
        if (fuelPanel == null) return
        val guard = Regex("encontre|dime|prefieras|numero de")
        if (AsistenteService.running) {
            AsistenteService.choiceCb = { t -> if (guard.containsMatchIn(PlaceIndex.norm(t))) false else { val c = Estaciones.parseChoice(t, fuelList.size); if (c == null) false else { ui.post { onFuelChoice(c) }; true } } }
            AsistenteService.choiceUntil = System.currentTimeMillis() + 25000
        } else {
            val v = fuelVoice ?: VoiceEngine(applicationContext).also { fuelVoice = it }
            if (!v.available()) return
            v.start(object : VoiceEngine.Cb {
                override fun onPartial(t: String) {}
                override fun onLevel(l: Float) {}
                override fun onFinal(t: String) { val c = Estaciones.parseChoice(t, fuelList.size); ui.post { if (c == null) { VozClips.play(this@MapActivity, "fuel_which") { ui.postDelayed({ listenFuel() }, 400) } } else onFuelChoice(c) } }
                override fun onError(msg: String) { }
            })
        }
    }

    private fun onFuelChoice(c: Int) {
        if (fuelPanel == null) return
        when {
            c == -2 -> { closeFuel(); VozClips.play(this, "fuel_cancel") }
            c == -1 -> { AsistenteService.choiceUntil = 0L; VozClips.play(this, "fuel_which") { ui.postDelayed({ listenFuel() }, 500) } }
            else -> pickFuel(if (c == 0) 1 else c)
        }
    }

    private fun pickFuel(n: Int) {
        val e = fuelList.getOrNull(n - 1)?.first ?: return
        closeFuel()
        fuelGo = true
        routeTo(e.shown, e.lat, e.lon, true)
    }

    private fun refPoint(): Pair<Double, Double> {
        if (!myLat.isNaN()) return Pair(myLat, myLon)
        val c = mapView?.model?.mapViewPosition?.center ?: CENTER
        return Pair(c.latitude, c.longitude)
    }

    private fun runSearch(q: String, online: Boolean) {
        if (q.trim().length < 2) { showResults(emptyList(), "Escribe una calle, avenida o lugar"); return }
        val (la, lo) = refPoint()
        Thread {
            val local = try { index.search(q, la, lo) } catch (e: Exception) { emptyList() }
            ui.post { showResults(local, if (local.isEmpty()) (if (isOnline()) "Sin resultados guardados. Toca Enter para buscar en internet." else "Sin resultados en la zona guardada.") else null, q) }
            if (online || local.isEmpty() && q.trim().length >= 4) {
                if (isOnline() && online) {
                    val on = nominatim(q)
                    ui.post { showResults(local + on.filter { o -> local.none { it.name == o.name } }, if (local.isEmpty() && on.isEmpty()) "No encontré «$q»" else null, q) }
                }
            }
        }.start()
    }

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    private fun nominatim(q: String): List<Place> = try {
        val u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&countrycodes=bo&q=" + URLEncoder.encode(q, "UTF-8")
        val cn = URL(u).openConnection() as HttpURLConnection
        cn.setRequestProperty("User-Agent", MapData.UA); cn.connectTimeout = 8000; cn.readTimeout = 8000
        val arr = org.json.JSONArray(cn.inputStream.bufferedReader().readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Place(it.optString("name").ifEmpty { it.optString("display_name").substringBefore(",") }, "Internet · " + it.optString("display_name").split(",").drop(1).take(2).joinToString(",").trim(), it.getDouble("lat"), it.getDouble("lon")) }
    } catch (e: Exception) { emptyList() }

    private fun showResults(list: List<Place>, msg: String?, q: String = "") {
        val box = resultsBox ?: return
        box.removeAllViews()
        if (msg != null) box.addView(tx(msg, 13f, fg2).apply { setPadding(p(8), p(12), p(8), p(8)) })
        for (pl in list) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(10), p(12), p(10)); isClickable = true; setOnClickListener { closeSearch(); routeTo(pl.name, pl.lat, pl.lon, false) } }
            r.addView(tx(pl.name, 16f, fg, true).apply { maxLines = 1 })
            r.addView(tx(pl.sub, 12f, fg2).apply { maxLines = 1 })
            box.addView(r)
            box.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
        if (q.isNotBlank() && isOnline()) box.addView(tx("Buscar «$q» en internet", 14f, acc, true).apply { setPadding(p(12), p(14), p(12), p(14)); isClickable = true; setOnClickListener { runSearch(q, true) } })
    }

    private fun goSaved(key: String, label: String) {
        val v = prefs.getString(key, null)
        val ll = v?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        if (ll != null && ll.size == 2) routeTo(label, ll[0], ll[1], false)
        else AlertDialog.Builder(this).setTitle("$label sin guardar").setMessage("Mantén pulsado el mapa en el lugar y elige «Guardar como $label».").setPositiveButton("Entendido", null).show()
    }

    private fun goNearest(kind: String) {
        val (la, lo) = refPoint()
        Thread {
            val r = try { index.nearestKind(kind, la, lo, 1) } catch (e: Exception) { emptyList() }
            ui.post { if (r.isEmpty()) toast("No hay ${kind.lowercase()}s guardadas en esta zona (menú ⋮ > Indexar)") else routeTo(r[0].name, r[0].lat, r[0].lon, false) }
        }.start()
    }

    private fun longPressMenu(ll: LatLong) {
        ui.post {
            AlertDialog.Builder(this).setTitle("Este punto")
                .setItems(arrayOf("Ir aquí", "Guardar como Casa", "Guardar como Trabajo", "Guardar como Pareja", "Guardar como Familia", "Guardar como Taller")) { _, w ->
                    val v = String.format(Locale.US, "%.6f,%.6f", ll.latitude, ll.longitude)
                    when (w) {
                        0 -> routeTo("Punto del mapa", ll.latitude, ll.longitude, false)
                        1 -> { prefs.edit().putString("place_casa", v).apply(); toast("Casa guardada") }
                        2 -> { prefs.edit().putString("place_trabajo", v).apply(); toast("Trabajo guardado") }
                        3 -> { prefs.edit().putString("place_pareja", v).apply(); toast("Pareja guardado") }
                        4 -> { prefs.edit().putString("place_familia", v).apply(); toast("Familia guardado") }
                        else -> { prefs.edit().putString("place_taller", v).apply(); toast("Taller guardado") }
                    }
                }.show()
        }
    }

    /** Prueba de punta a punta: archivo, lectura de datos y dibujo de una pieza del mapa en esta pantalla. */
    private fun mapDiagnostic() {
        val (la, lo) = refPoint()
        info("Revisando el mapa…")
        Thread {
            val txt = try { MapDiag.run(this, la, lo) } catch (e: Throwable) { "No se pudo hacer la prueba: ${e.message}" }
            info(null)
            ui.post {
                AlertDialog.Builder(this).setTitle("Diagnóstico del mapa")
                    .setView(ScrollView(this).apply { addView(TextView(this@MapActivity).apply { text = txt; textSize = 13f; setPadding(p(24), p(12), p(24), p(12)); setTextIsSelectable(true) }) })
                    .setPositiveButton("Cerrar", null)
                    .setNegativeButton("Descargar de nuevo") { _, _ -> startService(Intent(this, MapDownloadService::class.java).putExtra("force", true)); showDownload(); pollDownload() }
                    .show()
            }
        }.start()
    }

    private fun menu() {
        val upd = "Actualizar mapas (${MapData.regionName(this)} · último: ${MapData.stamp(this)})"
        AlertDialog.Builder(this).setTitle("Mapa")
            .setItems(arrayOf(upd, "Indexar calles y lugares aquí (${index.count()} guardados)", "Abrir Google Maps", "Cerrar mapa", "Diagnóstico del mapa", "Cambiar región del mapa", "Iconos de lugares y sentidos: " + (if (LiveMap.iconsOn(this)) "SÍ (tocar para quitar)" else "NO (tocar para poner)"))) { _, w ->
                when (w) {
                    0 -> { startService(Intent(this, MapDownloadService::class.java).putExtra("force", true)); showDownload(); pollDownload() }
                    1 -> { val c = mapView?.model?.mapViewPosition?.center ?: CENTER; Thread { info("Indexando…"); index.build(c.latitude, c.longitude); info(null); toast("Listo: ${index.count()} calles y lugares") }.start() }
                    2 -> try { startActivity(packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")) } catch (e: Exception) { toast("Google Maps no está instalado") }
                    3 -> finish()
                    4 -> mapDiagnostic()
                    5 -> showDownload().also { RegionPicker(this) { path, name -> MapData.setRegion(this, path, name); showDownload() }.show() }
                    else -> { prefs.edit().putBoolean("map_icons", !LiveMap.iconsOn(this)).apply(); toast("Cierra y abre el mapa para ver el cambio"); finish() }
                }
            }.show()
    }

    // ------------------------------------------------------------------ ruta
    private fun buildPreview() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(glass, 26, line); setPadding(p(18), p(14), p(18), p(14)); visibility = View.GONE; isClickable = true }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewT = tx("", 20f, fg, true).apply { maxLines = 1 }
        previewS = tx("", 14f, fg2)
        col.addView(previewT); col.addView(previewS)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tx("Cancelar", 15f, fg2, true).apply { gravity = Gravity.CENTER; setPadding(p(18), p(12), p(18), p(12)); background = bg(line, 18); setOnClickListener { clearRoute() } })
        row.addView(tx("INICIAR  ➜", 18f, Color.parseColor("#021018"), true).apply { gravity = Gravity.CENTER; background = bg(acc, 18); setPadding(p(26), p(14), p(26), p(14)); setOnClickListener { startNav() } }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(12) })
        c.addView(row)
        previewCard = c
        root.addView(c, FrameLayout.LayoutParams(p(760), WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = p(16) })
    }

    private fun routeTo(name: String, lat: Double, lon: Double, autoStart: Boolean) {
        if (!MapData.routeReady(this)) { toast("Faltan los datos de rutas: descárgalos desde el menú ⋮"); return }
        val (fa, fo) = refPoint()
        destName = name; destLat = lat; destLon = lon
        info("Calculando la ruta…")
        if (autoStart) VozClips.play(this, if (fuelGo) "fuel_go" else "nav_calc")
        fuelGo = false
        Thread {
            val r = OpenCarRouter.route(MapData.segDir(this), MapData.profile(this).absolutePath, fa, fo, lat, lon, 60000L)
            if (r.error != null || r.lats == null) {
                info(null)
                toast("No pude calcular la ruta" + (r.error?.let { ": " + it.take(80) } ?: ""))
                return@Thread
            }
            for (h in r.hints) if (h.cmd != 1 && h.cmd != 16) h.name = PlaceIndex.nameAt(this, h.lat, h.lon)
            info(null)
            ui.post { showRoute(r, autoStart) }
        }.start()
    }

    private fun showRoute(r: OpenCarRouter.Result, autoStart: Boolean) {
        route = r; lastIdx = 0; offCount = 0; spoken.clear()
        val pts = r.lats.indices.map { LatLong(r.lats[it], r.lons[it]) }
        casing?.setPoints(pts); routeLine?.setPoints(pts); rutaFlechas?.set(r.lats, r.lons)
        destLayer?.set(destLat, destLon)
        val mv = mapView ?: return
        mv.layerManager.redrawLayers()
        if (!navigating) {
            val bb = BoundingBox(r.lats.min(), r.lons.min(), r.lats.max(), r.lons.max())
            val dim = Dimension(max(100, mv.width), max(100, (mv.height * 0.7f).toInt()))
            val z = min(17, LatLongUtils.zoomForBounds(dim, bb.extendMeters(150), mv.model.displayModel.tileSize).toInt()).toByte()
            mv.model.mapViewPosition.mapPosition = MapPosition(bb.centerPoint, z)
            follow = false
            previewT?.text = destName
            previewS?.text = "${fmtKm(r.distance.toDouble())} · ${max(1, r.seconds / 60)} min · llegas ${etaClock(r.seconds)} · sin tráfico en vivo"
            previewCard?.visibility = View.VISIBLE; topUi?.visibility = View.GONE
        }
        if (autoStart) startNav()
    }

    private fun clearRoute() {
        route = null; navigating = false
        casing?.setPoints(emptyList()); routeLine?.setPoints(emptyList()); rutaFlechas?.set(null, null); destLayer?.set(Double.NaN, Double.NaN)
        previewCard?.visibility = View.GONE; navBanner?.visibility = View.GONE; navBottom?.visibility = View.GONE
        topUi?.visibility = View.VISIBLE; fabCol?.visibility = View.VISIBLE
        setNavView(false)
        mapView?.layerManager?.redrawLayers()
    }

    // ------------------------------------------------------------------ navegación
    private fun buildNav() {
        arrow = ArrowView(this)
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(acc, 22); setPadding(p(14), p(10), p(18), p(10)); visibility = View.GONE }
        b.addView(arrow, LinearLayout.LayoutParams(p(84), p(84)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), 0, 0, 0) }
        val dk = Color.parseColor("#021018")
        bigDist = tx("", 34f, dk, true)
        bigText = tx("", 16f, dk, true).apply { maxLines = 2 }
        col.addView(bigDist); col.addView(bigText)
        b.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        navBanner = b
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        thenText = tx("", 13f).apply { background = bg(glass, 16, line); setPadding(p(14), p(8), p(14), p(8)); visibility = View.GONE }
        wrap.addView(thenText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = p(8) })
        root.addView(wrap, FrameLayout.LayoutParams(p(450), WRAP_CONTENT).apply { leftMargin = p(16); topMargin = p(14) })
        navWrap = wrap
        wrap.visibility = View.GONE

        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(glass, 26, line); setPadding(p(22), p(10), p(22), p(10)); visibility = View.GONE }
        fun stat(label: String): Pair<LinearLayout, TextView> {
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, p(22), 0) }
            val v = tx("", 22f, fg, true); l.addView(v); l.addView(tx(label, 11f, fg2)); return Pair(l, v)
        }
        val (a, av) = stat("llegada"); etaT = av
        val (c1, cv) = stat("restante"); remT = cv
        val (d1, dv) = stat("distancia"); kmT = dv
        bot.addView(a); bot.addView(c1); bot.addView(d1)
        bot.addView(tx("✕ Terminar", 16f, 0xFFFF5A4F.toInt(), true).apply { setPadding(p(10), p(10), p(6), p(10)); setOnClickListener { stopNav(false) } })
        navBottom = bot
        root.addView(bot, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = p(16) })

        val sp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(glass, 20, line); setPadding(p(16), p(8), p(16), p(8)); visibility = View.GONE }
        speedT = tx("0", 32f, fg, true); streetT = tx("", 12f, fg2).apply { maxLines = 1 }
        sp.addView(speedT); sp.addView(streetT)
        speedBox = sp
        root.addView(sp, FrameLayout.LayoutParams(p(190), WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = p(16); bottomMargin = p(16) })
    }

    private var navWrap: LinearLayout? = null
    private var speedBox: LinearLayout? = null

    private fun startNav() {
        val r = route ?: return
        navigating = true; follow = true; spoken.clear(); lastIdx = 0; offCount = 0
        previewCard?.visibility = View.GONE; topUi?.visibility = View.GONE
        navWrap?.visibility = View.VISIBLE; navBanner?.visibility = View.VISIBLE; navBottom?.visibility = View.VISIBLE; speedBox?.visibility = View.VISIBLE
        setNavView(true)
        val mv = mapView
        mv?.model?.mapViewPosition?.zoomLevel = 17
        VozClips.play(this, "nav_start")
        navTick()
    }

    private fun stopNav(arrived: Boolean) {
        navigating = false
        navWrap?.visibility = View.GONE; navBottom?.visibility = View.GONE; speedBox?.visibility = View.GONE
        clearRoute()
        if (arrived) VozClips.play(this, "arrived")
    }

    /** Mapa girado con el rumbo del auto y el auto en la parte baja, como en un navegador. */
    private fun setNavView(nav: Boolean) {
        val mv = mapView ?: return
        headUp = nav
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (nav) {
            val diag = (sqrt((w * w + h * h).toDouble()) * 1.1).toInt()
            val lp = FrameLayout.LayoutParams(diag, diag)
            lp.leftMargin = w / 2 - diag / 2; lp.topMargin = (h * 0.66f).toInt() - diag / 2
            mv.layoutParams = lp
            mv.pivotX = diag / 2f; mv.pivotY = diag / 2f
        } else {
            mv.rotation = 0f
            mv.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        mv.requestLayout()
    }

    private fun clipFor(h: OpenCarRouter.Hint): String? = when (h.cmd) {
        2, 4, 10 -> "turn_left"; 5, 7, 11 -> "turn_right"
        3, 8 -> "slight_left"; 6, 9, 12 -> "slight_right"
        15 -> "uturn"
        13, 14 -> "rb" + h.exit.coerceIn(1, 4)
        else -> null
    }

    private fun textFor(h: OpenCarRouter.Hint): String {
        val via = if (h.name.isNotBlank()) " · " + h.name else ""
        return when (h.cmd) {
            2 -> "Gira a la izquierda$via"; 5 -> "Gira a la derecha$via"
            3 -> "Gira ligeramente a la izquierda$via"; 6 -> "Gira ligeramente a la derecha$via"
            4 -> "Gira cerrado a la izquierda$via"; 7 -> "Gira cerrado a la derecha$via"
            8 -> "Mantente a la izquierda$via"; 9 -> "Mantente a la derecha$via"
            10, 11, 15 -> "Cambio de sentido"
            13, 14 -> "En la rotonda, toma la salida ${h.exit}$via"
            12 -> "Toma la salida$via"
            else -> "Continúa$via"
        }
    }

    private fun navTick() {
        val r = route ?: return
        if (myLat.isNaN()) {
            // todavía sin GPS: muestra ya el primer giro y los totales de la ruta (no se queda en blanco)
            val first = r.hints.firstOrNull { it.cmd != 1 && it.cmd != 16 }
            if (first != null) { arrow.cmd = first.cmd; arrow.invalidate(); bigDist?.text = fmtDist(first.distFromStart); bigText?.text = textFor(first) }
            else { arrow.cmd = 1; arrow.invalidate(); bigDist?.text = fmtDist(r.distance.toDouble()); bigText?.text = "Sigue hasta tu destino: $destName" }
            etaT?.text = etaClock(r.seconds); remT?.text = "${max(1, r.seconds / 60)} min"; kmT?.text = fmtKm(r.distance.toDouble())
            speedT?.text = "0 km/h"
            return
        }
        val n = r.lats.size
        // punto más cercano de la ruta (ventana alrededor del último)
        val k = 111320.0; val kx = k * cos(Math.toRadians(myLat))
        var best = lastIdx; var bd = Double.MAX_VALUE; var along = r.cum[lastIdx]
        val from = max(0, lastIdx - 3); val to = min(n - 2, lastIdx + 80)
        for (i in from..to) {
            val ax = (r.lons[i] - myLon) * kx; val ay = (r.lats[i] - myLat) * k
            val bx = (r.lons[i + 1] - myLon) * kx; val by = (r.lats[i + 1] - myLat) * k
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < bd) { bd = d; best = i; along = r.cum[i] + t * (r.cum[i + 1] - r.cum[i]) }
        }
        if (bd < 60) { lastIdx = best; offCount = 0 } else offCount++
        if (offCount >= 3 && !rerouting) { rerouting = true; VozClips.play(this, "recalc"); offCount = 0
            val dn = destName; val dl = destLat; val dlo = destLon
            Thread {
                val rr = OpenCarRouter.route(MapData.segDir(this), MapData.profile(this).absolutePath, myLat, myLon, dl, dlo, 60000L)
                if (rr.error == null && rr.lats != null) { for (h in rr.hints) if (h.cmd != 1 && h.cmd != 16) h.name = PlaceIndex.nameAt(this, h.lat, h.lon); ui.post { showRoute(rr, false); destName = dn } }
                rerouting = false
            }.start()
            return
        }
        val total = r.cum[n - 1]
        val remain = max(0.0, total - along)
        // próximo giro
        var next: OpenCarRouter.Hint? = null; var after: OpenCarRouter.Hint? = null
        for (h in r.hints) {
            if (h.cmd == 1 || h.cmd == 16) continue
            if (h.distFromStart > along + 8) { if (next == null) next = h else { after = h; break } }
        }
        val dNext = if (next != null) next.distFromStart - along else remain
        if (next != null) {
            arrow.cmd = next.cmd; arrow.invalidate()
            bigDist?.text = fmtDist(dNext)
            bigText?.text = textFor(next)
            thenText?.visibility = if (after != null) View.VISIBLE else View.GONE
            if (after != null) thenText?.text = "Luego: " + textFor(after) + " · " + fmtDist(after.distFromStart - next.distFromStart)
            guide(next, dNext)
        } else {
            arrow.cmd = 1; arrow.invalidate()
            bigDist?.text = fmtDist(remain); bigText?.text = "Sigue hasta tu destino: $destName"; thenText?.visibility = View.GONE
        }
        val secLeft = (r.seconds * (remain / max(1.0, total))).toInt()
        etaT?.text = etaClock(secLeft); remT?.text = "${max(1, secLeft / 60)} min"; kmT?.text = fmtKm(remain)
        speedT?.text = (mySpeed * 3.6f).roundToInt().toString() + " km/h"
        streetT?.text = PlaceIndexName.cached(this, myLat, myLon)
        // girar el mapa con el rumbo
        mapView?.model?.mapViewPosition?.setCenter(LatLong(myLat, myLon))
        applyHeading()
        casing?.let { }
        if (remain < 25 && total > 60) stopNav(true)
    }

    private fun guide(h: OpenCarRouter.Hint, d: Double) {
        val clip = clipFor(h) ?: return
        val id = h.idx
        // el primer aviso con distancia solo si el giro queda lejos del anterior
        val levels = listOf(500 to "dist_500", 200 to "dist_200")
        for ((m, dc) in levels) if (d <= m && d > m - 120 && spoken.add("$id:$m")) { VozClips.play(this, dc, clip); return }
        if (d <= 45 && spoken.add("$id:now")) VozClips.play(this, clip)
    }

    // ------------------------------------------------------------------ utilidades
    private fun fmtKm(m: Double) = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',') else "${(m / 10).roundToInt() * 10} m"
    private fun fmtDist(m: Double) = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',') else "${max(10, (m / 10).roundToInt() * 10)} m"
    private fun etaClock(sec: Int): String {
        val c = Calendar.getInstance(); c.add(Calendar.SECOND, sec)
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra("fuel", false)) {
            val gas = i.getBooleanExtra("gnv", false)
            val voice = !i.getBooleanExtra("silent", false)
            i.removeExtra("fuel"); i.removeExtra("gnv"); i.removeExtra("silent")
            ui.postDelayed({ showFuel(gas, voice) }, 700)
            return
        }
        val q = i.getStringExtra("dest_q") ?: return
        val label = i.getStringExtra("dest_name") ?: q
        val go = i.getBooleanExtra("go", false)
        i.removeExtra("dest_q")
        val ll = Regex("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$").find(q)
        ui.postDelayed({
            val kn = PlaceIndex.norm(q + " " + label)
            val known: Triple<String, Double, Double>? = null
            if (ll != null) routeTo(label, ll.groupValues[1].toDouble(), ll.groupValues[2].toDouble(), go)
            else if (known != null) routeTo(known.first, known.second, known.third, go)
            else Thread {
                val (la, lo) = refPoint()
                val key = q.substringBefore(",").replace(Regex("(?i)universidad.*"), "").trim().ifEmpty { q }
                var res = try { index.search(key, la, lo, 3) } catch (e: Exception) { emptyList() }
                if (res.isEmpty() && isOnline()) res = nominatim(q)
                ui.post { if (res.isEmpty()) toast("No encontré «$label»") else routeTo(res[0].name, res[0].lat, res[0].lon, go) }
            }.start()
        }, 600)
    }

    override fun onBackPressed() {
        when {
            fuelPanel?.visibility == View.VISIBLE -> closeFuel()
            searchPanel?.visibility == View.VISIBLE -> closeSearch()
            navigating -> AlertDialog.Builder(this).setTitle("¿Terminar la ruta?").setPositiveButton("Terminar") { _, _ -> stopNav(false) }.setNegativeButton("Seguir", null).show()
            previewCard?.visibility == View.VISIBLE -> clearRoute()
            else -> finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!navigating) try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (e: Exception) { }
    }

    override fun onResume() { super.onResume(); if (mapView != null) startLocation() }

    override fun onDestroy() {
        AsistenteService.choiceUntil = 0L; AsistenteService.choiceCb = null; fuelVoice?.stop()
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (e: Exception) { }
        VozClips.stop()
        try { mapView?.destroyAll() } catch (e: Throwable) { }
        try { sentido?.close() } catch (e: Throwable) { }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ capas propias
    inner class PosLayer : Layer() {
        private var lat = Double.NaN; private var lon = Double.NaN; private var bearing = 0f
        fun set(a: Double, o: Double, b: Float) { lat = a; lon = o; bearing = b; requestRedraw() }
        override fun draw(bb: BoundingBox, z: Byte, canvas: org.mapsforge.core.graphics.Canvas, tl: Point) {
            if (lat.isNaN()) return
            val ms = MercatorProjection.getMapSize(z, displayModel.tileSize)
            val x = (MercatorProjection.longitudeToPixelX(lon, ms) - tl.x).toFloat()
            val y = (MercatorProjection.latitudeToPixelY(lat, ms) - tl.y).toFloat()
            val c = AndroidGraphicFactory.getCanvas(canvas)
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            pt.color = (acc and 0x00FFFFFF) or 0x30000000; c.drawCircle(x, y, 34f * sc, pt)
            CarIcon.draw(c, x, y, bearing, sc, acc, 0.72f)
        }
    }

    /** Flechas blancas sobre la ruta: muestran el sentido en que debes recorrerla. */
    inner class RutaFlechas : Layer() {
        private var la: DoubleArray? = null; private var lo: DoubleArray? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        fun set(a: DoubleArray?, o: DoubleArray?) { la = a; lo = o; requestRedraw() }
        override fun draw(bb: BoundingBox, z: Byte, canvas: org.mapsforge.core.graphics.Canvas, tl: Point) {
            val a = la ?: return; val o = lo ?: return
            if (a.size < 2 || z < 14) return
            val ms = MercatorProjection.getMapSize(z, displayModel.tileSize)
            val c = AndroidGraphicFactory.getCanvas(canvas)
            val step = 50f * sc; val s = 5.6f * sc
            var acc = step / 2
            var px = (MercatorProjection.longitudeToPixelX(o[0], ms) - tl.x).toFloat(); var py = (MercatorProjection.latitudeToPixelY(a[0], ms) - tl.y).toFloat()
            for (i in 1 until a.size) {
                val x = (MercatorProjection.longitudeToPixelX(o[i], ms) - tl.x).toFloat(); val y = (MercatorProjection.latitudeToPixelY(a[i], ms) - tl.y).toFloat()
                val len = hypot(x - px, y - py)
                if (len >= 1f) {
                    val ux = (x - px) / len; val uy = (y - py) / len
                    var d = acc
                    while (d < len) {
                        val cx = px + ux * d; val cy = py + uy * d
                        if (cx > -20 && cy > -20 && cx < c.width + 20 && cy < c.height + 20) {
                            val p = Path()
                            p.moveTo(cx + ux * s * 1.2f, cy + uy * s * 1.2f)
                            p.lineTo(cx - ux * s * 0.8f - uy * s, cy - uy * s * 0.8f + ux * s)
                            p.lineTo(cx - ux * s * 0.2f, cy - uy * s * 0.2f)
                            p.lineTo(cx - ux * s * 0.8f + uy * s, cy - uy * s * 0.8f - ux * s)
                            p.close(); c.drawPath(p, fill)
                        }
                        d += step
                    }
                    acc = d - len
                }
                px = x; py = y
            }
        }
    }

    inner class DestLayer : Layer() {
        private var lat = Double.NaN; private var lon = Double.NaN
        fun set(a: Double, o: Double) { lat = a; lon = o; requestRedraw() }
        override fun draw(bb: BoundingBox, z: Byte, canvas: org.mapsforge.core.graphics.Canvas, tl: Point) {
            if (lat.isNaN()) return
            val ms = MercatorProjection.getMapSize(z, displayModel.tileSize)
            val x = (MercatorProjection.longitudeToPixelX(lon, ms) - tl.x).toFloat()
            val y = (MercatorProjection.latitudeToPixelY(lat, ms) - tl.y).toFloat()
            val c = AndroidGraphicFactory.getCanvas(canvas)
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = 15f * sc
            pt.color = 0x55000000; c.drawCircle(x, y, 5f * sc, pt)
            val path = Path(); path.moveTo(x, y); path.lineTo(x - r * 0.8f, y - r * 1.6f); path.lineTo(x + r * 0.8f, y - r * 1.6f); path.close()
            pt.color = 0xFFFF5A4F.toInt(); c.drawPath(path, pt); c.drawCircle(x, y - r * 2f, r, pt)
            pt.color = Color.WHITE; c.drawCircle(x, y - r * 2f, r * 0.42f, pt)
        }
    }

    class ArrowView(c: Context) : View(c) {
        var cmd = 1
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#021018"); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            pt.strokeWidth = w * 0.11f
            val cx = w / 2; val bottom = h * 0.9f; val mid = h * 0.52f
            val ang = when (cmd) { 2 -> -90f; 3, 8 -> -40f; 4 -> -135f; 5 -> 90f; 6, 9, 12 -> 40f; 7 -> 135f; else -> 0f }
            if (cmd == 13 || cmd == 14) {
                cv.drawCircle(cx, h * 0.42f, w * 0.2f, pt)
                cv.drawLine(cx, bottom, cx, h * 0.62f, pt)
                cv.drawLine(cx + w * 0.14f, h * 0.27f, cx + w * 0.3f, h * 0.1f, pt)
                cv.drawLine(cx + w * 0.3f, h * 0.1f, cx + w * 0.3f, h * 0.27f, pt)
                cv.drawLine(cx + w * 0.3f, h * 0.1f, cx + w * 0.13f, h * 0.1f, pt)
                return
            }
            if (cmd == 15 || cmd == 10 || cmd == 11) {
                val path = Path(); path.moveTo(cx - w * 0.15f, bottom); path.lineTo(cx - w * 0.15f, h * 0.3f)
                path.quadTo(cx - w * 0.15f, h * 0.1f, cx + w * 0.02f, h * 0.1f); path.quadTo(cx + w * 0.2f, h * 0.1f, cx + w * 0.2f, h * 0.3f); path.lineTo(cx + w * 0.2f, h * 0.55f)
                cv.drawPath(path, pt)
                cv.drawLine(cx + w * 0.2f, h * 0.62f, cx + w * 0.05f, h * 0.45f, pt); cv.drawLine(cx + w * 0.2f, h * 0.62f, cx + w * 0.35f, h * 0.45f, pt)
                return
            }
            cv.drawLine(cx, bottom, cx, mid, pt)
            val rad = Math.toRadians(ang.toDouble()); val len = h * 0.42f
            val ex = cx + (Math.sin(rad) * len).toFloat(); val ey = mid - (Math.cos(rad) * len).toFloat()
            cv.drawLine(cx, mid, ex, ey, pt)
            val back = Math.toRadians(ang + 150.0); val back2 = Math.toRadians(ang - 150.0); val hl = w * 0.26f
            cv.drawLine(ex, ey, ex + (Math.sin(back) * hl).toFloat(), ey - (Math.cos(back) * hl).toFloat(), pt)
            cv.drawLine(ex, ey, ex + (Math.sin(back2) * hl).toFloat(), ey - (Math.cos(back2) * hl).toFloat(), pt)
        }
    }
}

/** Nombre de la calle donde vas, con un caché pequeño para no leer el mapa en cada fix. */
object PlaceIndexName {
    private var lastT = 0L
    private var last = ""
    fun cached(c: Context, lat: Double, lon: Double): String {
        val t = System.currentTimeMillis()
        if (t - lastT > 4000) { lastT = t; Thread { last = PlaceIndex.nameAt(c, lat, lon) }.start() }
        return last
    }
}
