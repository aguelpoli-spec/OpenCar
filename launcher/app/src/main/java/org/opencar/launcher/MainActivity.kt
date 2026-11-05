package org.opencar.launcher

import android.Manifest
import android.content.ContentValues
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.MediaStore
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.app.AlertDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.ExifInterface
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCar Launcher - tablero para pantalla Android de auto (1024x600, horizontal).
 * Todas las medidas estan pensadas en "pixeles de diseno 1024x600" y se escalan
 * a la resolucion real, asi se ve igual sin importar la densidad de la pantalla.
 */
class MainActivity : Activity() {

    data class AppInfo(val label: String, val pkg: String, val icon: Drawable)

    private data class F(val key: String, val label: String, val kind: Int, val def: String = "")

    private enum class Page { HOME, ALL, DIAG, CAR, CARPLAY, MAPS, MUSIC, LIBRARY, CAL, TRIP, EQ }

    companion object {
        private const val REQ_PHOTO = 41
        private const val REQ_PERM = 42
        private const val REQ_MIC = 46
        private const val REQ_BG = 43
        private const val REQ_RESTORE = 44
        private const val REQ_AUDIO = 44
        private const val KIND_TEXT = 0
        private const val KIND_NUM = 1
        private const val KIND_DATE = 2
    }

    private lateinit var prefs: SharedPreferences
    private var apps: List<AppInfo> = emptyList()
    private var page = Page.HOME
    private var pickTitle: String? = null
    private var onPick: ((AppInfo) -> Unit)? = null
    private var scale = 1f

    private val handler = Handler(Looper.getMainLooper())
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var mediaTitle: TextView? = null
    private var mediaSub: TextView? = null
    private var playIcon: ImageView? = null
    private var mediaBar: ThinBar? = null
    private var mediaTime: TextView? = null
    private var mediaRow: View? = null
    private var speedView: TextView? = null
    private var speedUnitView: TextView? = null
    private var speedBar: ThinBar? = null
    private var speedCard: View? = null
    private var volCard: View? = null
    private var volBar2: TouchBar? = null
    private var volPct2: TextView? = null
    private val volAutoClose = Runnable { setVolumeCard(false) }
    private var musicTitleV: TextView? = null
    private var musicArtistV: TextView? = null
    private var musicAlbumV: TextView? = null
    private var musicSrcV: TextView? = null
    private var musicSrcIcon: ImageView? = null
    private var musicArtView: ImageView? = null
    private var musicNoArt: View? = null
    private var musicBgV: ImageView? = null
    private var musicSeek: TouchBar? = null
    private var musicPosV: TextView? = null
    private var musicDurV: TextView? = null
    private var musicPlayV: ImageView? = null
    private var musicVol: TouchBar? = null
    private var musicVolPct: TextView? = null
    private var musicChips: LinearLayout? = null
    private var musicInfoV: TextView? = null
    private var chipsSig = ""
    private var lyrOverlay: FrameLayout? = null
    private var lyrScroll: ScrollView? = null
    private var lyrLines: List<Pair<Long, TextView>> = emptyList()
    private var lyrOpen = false
    private var lyrIdx = -1
    private class Lyr(val text: String, val synced: Boolean)
    private val lyrCache = HashMap<String, Pair<Long, Lyr?>>()
    private val lyrBusy = HashSet<String>()
    private var shownKey: String? = null
    private var lastArt: Bitmap? = null
    private var lastPlaying = false
    private var npT = 0L
    private var npV: NP? = null
    private var artKey: String? = null
    private var artBmp: Bitmap? = null
    // respaldo: avisos clasicos de apps de musica (com.android.music.*)
    private var lgTitle: String? = null
    private var lgArtist: String? = null
    private var lgAlbum: String? = null
    private var lgPlaying = false
    private var lgTs = 0L
    private val legacyRx = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            if (i == null) return
            (i.getStringExtra("track") ?: i.getStringExtra("title"))?.let { lgTitle = it }
            i.getStringExtra("artist")?.let { lgArtist = it }
            i.getStringExtra("album")?.let { lgAlbum = it }
            if (i.hasExtra("playing")) lgPlaying = i.getBooleanExtra("playing", false)
            lgTs = System.currentTimeMillis()
        }
    }
    private var renderedDay = false
    private var mediaDenied = false
    private var permFromWizard = false

    // --- v2.20: fluidez, modo manejo, brillo
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private var clockKey = -1L
    private var dayChkAt = 0L
    private var appsAt = 0L
    private var lastRenderAt = 0L
    private var renderedPage: Page? = null
    private var mT: String? = null
    private var mS: String? = null
    private var mP = -1
    private var driveActive = false
    private var driveFast = 0
    private var driveSlow = 0
    private var driveOffUntil = 0L
    private var driveLayer: FrameLayout? = null
    private var driveSpeedV: TextView? = null
    private var driveUnitV: TextView? = null
    private var driveTitleV: TextView? = null
    private var driveSubV: TextView? = null
    private var drivePlayV: ImageView? = null
    private var lux = -1f
    private var lastBright = -2f
    private var brightAt = 0L
    private var sensorMgr: SensorManager? = null
    private val luxL = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { lux = if (lux < 0f) e.values[0] else lux * 0.7f + e.values[0] * 0.3f }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private var bgCacheKey: String? = null
    private var bgCacheBmp: Bitmap? = null

    private fun View.pressFx(): View {
        setOnTouchListener { v, e ->
            val m = mode()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(m.pressScale).scaleY(m.pressScale).setDuration(m.pressMs).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(m.pressMs + 60)
                    .setInterpolator(if (m.bouncy) android.view.animation.OvershootInterpolator(3f) else android.view.animation.DecelerateInterpolator()).start()
            }
            false
        }
        return this
    }

    private fun mode(): Modes.M = Modes.get(prefs.getString("app_mode", "ios"))
    private var modeFx = false
    private fun modeTint(): Int = if (mode().iconTint != 0 && !th.day) mode().iconTint else th.fg

    private fun startLight() {
        if (!prefs.getBoolean("auto_bright", true)) return
        try {
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sensorMgr = sm
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(luxL, it, 1_000_000) }
        } catch (e: Exception) { }
    }

    private fun stopLight() {
        try { sensorMgr?.unregisterListener(luxL) } catch (e: Exception) { }
        lux = -1f
        setBright(-1f)
    }

    private fun setBright(v: Float) {
        if (abs(v - lastBright) < 0.05f && v == lastBright) return
        lastBright = v
        try { window.attributes = window.attributes.apply { screenBrightness = v } } catch (e: Exception) { }
    }

    private fun applyBrightness() {
        if (!prefs.getBoolean("auto_bright", true)) { setBright(prefs.getFloat("bright_level", -1f)); return }
        val t = if (lux >= 0f) when { lux < 3f -> 0.25f; lux < 30f -> 0.4f; lux < 200f -> 0.65f; else -> 1f }
                else if (!isDay()) 0.45f else -1f
        if (t == -1f || abs(t - lastBright) >= 0.1f || lastBright < 0f) setBright(t)
    }

    private fun speedKmh(): Float {
        val fresh = TripService.running && TripService.lastFixMs != 0L &&
            System.currentTimeMillis() - TripService.lastFixMs < 8000L
        return if (fresh) TripService.speedMps * 3.6f else 0f
    }

    private var chipStyleBig: ((Boolean) -> Unit)? = null
    private var chipStyleDrive: ((Boolean) -> Unit)? = null

    private fun setDrive(on: Boolean) {
        if (on && bigActive) setBig(false)
        driveActive = on
        chipStyleDrive?.invoke(on)
        driveFast = 0; driveSlow = 0
        driveLayer?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { closeOverlays(); setVolumeCard(false) }
    }

    private var techDrive: TechDrive? = null
    private var techClock: TechClock? = null

    private fun tripElapsedMs(): Long {
        val st = TripService.tripStartMs
        return if (st == 0L) 0L else (if (TripService.tripOn) System.currentTimeMillis() else TripService.tripEndMs) - st
    }

    private fun updateDrive() {
        if (!driveActive) return
        val td = techDrive ?: return
        val kmh = speedKmh()
        val mph = speedUnit() == "mph"
        td.speed = if (kmh < 3f) 0f else kmhToUnit(kmh)
        td.unit = if (mph) "mph" else "km/h"
        td.maxScale = if (mph) 120f else 180f
        td.over = speedOver(kmh)
        td.limitFrac = if (prefs.getBoolean("spd_alert", false)) kmhToUnit(prefs.getInt("spd_limit", 100).toFloat()) / td.maxScale else -1f
        td.odo = odoKm()?.let { "${fmtKm(it)} ${u()}" } ?: "--"
        td.trip = String.format(Locale.US, "%.1f %s", metersToUnit(TripService.tripM), distUnit())
        td.tripTime = fmtHms(tripElapsedMs())
        td.heading = headingText()
        td.alt = if (TripService.altitude.isNaN()) "--" else "${TripService.altitude.roundToInt()} m"
        td.temp = tempText() ?: "--"
        val np = nowPlaying()
        driveTitleV?.text = np?.title ?: "Sin reproducción"
        driveSubV?.text = np?.artist ?: ""
        drivePlayV?.setImageResource(if (np?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun buildDriveLayer(): FrameLayout {
        val white = Color.WHITE
        val lay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FF02050C"))
            isClickable = true
            visibility = if (driveActive) View.VISIBLE else View.GONE
        }
        val td = TechDrive(this, prefs)
        techDrive = td
        lay.addView(td, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        driveTitleV = txt("", 24f, white, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(p(40), 0, p(40), 0)
            setShadowLayer(10f, 0f, 2f, Color.parseColor("#CC000000"))
        }
        driveSubV = txt("", 17f, Color.parseColor("#B3FFFFFF")).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(driveTitleV, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(driveSubV, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun big(res: Int, label: String, size: Int, tint: Int, onClick: () -> Unit): LinearLayout {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; setOnClickListener { onClick() } }
            val c = FrameLayout(this).apply { background = oval(tint, Color.parseColor("#664DD0E1")) }
            val iv = ImageView(this).apply { setImageResource(res); setColorFilter(white) }
            c.addView(iv, FrameLayout.LayoutParams(p(size * 0.42f), p(size * 0.42f), Gravity.CENTER))
            if (res == R.drawable.ic_play) drivePlayV = iv
            b.addView(c, LinearLayout.LayoutParams(p(size), p(size)))
            b.addView(txt(label, 13f, Color.parseColor("#CCFFFFFF")).apply { gravity = Gravity.CENTER; setPadding(0, p(3), 0, 0) })
            b.pressFx()
            return b
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lp = { LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(p(12), 0, p(12), 0) } }
        row.addView(big(R.drawable.ic_prev, "", 70, Color.parseColor("#1AFFFFFF")) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, lp())
        row.addView(big(R.drawable.ic_play, "", 88, Color.parseColor("#660A84FF")) { dockPlay() }, lp())
        row.addView(big(R.drawable.ic_next, "", 70, Color.parseColor("#1AFFFFFF")) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, lp())
        row.addView(big(R.drawable.ic_place, "Mapas", 70, Color.parseColor("#1AFFFFFF")) { mapsMenu() }, lp())
        row.addView(big(R.drawable.ic_phone, "CarPlay", 70, Color.parseColor("#1AFFFFFF")) {
            val a = findApp("carplay_pkg", carPlayHints)
            if (a != null) launch(a.pkg) else chooseApp("carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)")
        }, lp())
        col.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = p(8) })
        lay.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = p(12) })
        lay.addView(txt("SALIR  ✕", 14f, Color.parseColor("#CCFFFFFF")).apply {
            letterSpacing = 0.1f
            setPadding(p(16), p(8), p(16), p(8)); isClickable = true
            background = GradientDrawable().apply { cornerRadius = p(20).toFloat(); setColor(Color.parseColor("#33FFFFFF")); setStroke(max(1, p(1)), Color.parseColor("#66FFFFFF")) }
            setOnClickListener { setDrive(false) }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, p(10), p(14), 0) })
        driveLayer = lay
        return lay
    }

    // ---- respaldo de ajustes
    private fun exportBackup() {
        try {
            val o = JSONObject()
            for ((k, v) in prefs.all) {
                val e = JSONObject()
                when (v) {
                    is String -> { e.put("t", "s"); e.put("v", v) }
                    is Boolean -> { e.put("t", "b"); e.put("v", v) }
                    is Int -> { e.put("t", "i"); e.put("v", v) }
                    is Long -> { e.put("t", "l"); e.put("v", v) }
                    is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                    else -> continue
                }
                o.put(k, e)
            }
            val name = "OpenCarLauncher-respaldo-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("sin destino")
            contentResolver.openOutputStream(uri)?.use { it.write(o.toString(1).toByteArray()) }
            toast("Respaldo guardado en Descargas: $name")
        } catch (e: Exception) { toast("No se pudo guardar el respaldo") }
    }

    private fun restoreBackup(uri: Uri) {
        try {
            val txt = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: throw Exception("vacío")
            val o = JSONObject(txt)
            val ed = prefs.edit()
            var n = 0
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "s" -> ed.putString(k, e.getString("v"))
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    else -> continue
                }
                n++
            }
            ed.apply()
            bgCacheKey = null
            toast("Respaldo restaurado ($n ajustes)")
            render()
        } catch (e: Exception) { toast("Ese archivo no es un respaldo válido") }
    }

    private fun menuLabels() = linkedMapOf("estado" to "Estado", "mapas" to "Mapas", "taller" to "Taller",
        "musica" to "Música", "viaje" to "Viaje", "calendario" to "Calendario", "carplay" to "CarPlay", "radio" to "Radio", "apps" to "Apps",
        "reloj" to "Reloj grande", "manejo" to "Modo manejo", "gasolina" to "Gasolina", "mapa" to "Mapa propio",
        "noche" to "Modo noche", "pantalla" to "Editar pantalla")

    private fun menuKeys(): List<String> {
        var saved = prefs.getString("menu2", null)
        if (saved != null && prefs.getInt("menu_ver", 0) < 2) {
            val l = saved.split(",").toMutableList()
            for (k in listOf("noche", "pantalla")) if (k !in l) l.add(k)
            saved = l.joinToString(","); prefs.edit().putString("menu2", saved).putInt("menu_ver", 2).apply()
        }
        return (saved ?: menuLabels().keys.joinToString(",")).split(",").filter { it in menuLabels() }.ifEmpty { listOf("estado") }
    }

    private fun menuConfig() {
        val labels = menuLabels()
        val keys = labels.keys.toList()
        val cur = menuKeys().toMutableSet()
        AlertDialog.Builder(this).setTitle("Botones del Menú rápido")
            .setMultiChoiceItems(labels.values.toTypedArray(), BooleanArray(keys.size) { keys[it] in cur }) { _, i, on ->
                if (on) cur.add(keys[i]) else cur.remove(keys[i])
            }
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("menu2", keys.filter { it in cur }.joinToString(",")).apply()
                render()
            }.setNegativeButton("Cancelar", null).show()
    }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updateMedia()
            updateSpeed()
            checkAutoMusic()
            updateVolumeCard()
            updateDrive()
            updateBig()
            updateBar()
            trackParking()
            checkLights()
            if (page == Page.HOME) { miniMap?.invalidate(); liveMapTick() }
            if (page == Page.TRIP) updateTrip()
            if (page == Page.MUSIC) updateMusicPage()
            val t = SystemClock.elapsedRealtime()
            if (t - dayChkAt > 30_000L) { dayChkAt = t; if (page == Page.HOME && isDay() != renderedDay) render() }
            if (t - brightAt > 5_000L) { brightAt = t; applyBrightness() }
            if (t - wxCheckAt > 60_000L) {
                wxCheckAt = t
                val w = System.currentTimeMillis()
                if (w - prefs.getLong("wx_at", 0L) > 30 * 60_000L && w - prefs.getLong("wx_try", 0L) > 5 * 60_000L) syncWeather()
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Paleta del inicio segun el tema (dia = claro, noche = oscuro). */
    private class Th(val day: Boolean, val m: Modes.M) {
        private fun al(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
        private fun dk(c: Int) = Color.rgb(Color.red(c) / 2, Color.green(c) / 2, Color.blue(c) / 2)
        private val ios = m.id == "ios"
        val fg = if (day) Color.parseColor("#0B1B33") else Color.WHITE
        val fgSoft = if (day) Color.parseColor("#990B1B33") else Color.parseColor("#A6FFFFFF")
        val glass = if (day) Color.parseColor("#E8F3F7FC") else al(m.tint, 0x94)
        val glassStroke = if (day) Color.parseColor("#33000000") else Color.parseColor("#3DFFFFFF")
        val circle = if (day) Color.parseColor("#14000000") else Color.parseColor("#1FFFFFFF")
        val circleStroke = if (day) Color.parseColor("#26000000") else if (ios) Color.parseColor("#38FFFFFF") else al(m.acc, 0x66)
        val divider = if (day) Color.parseColor("#26000000") else Color.parseColor("#29FFFFFF")
        val press = if (day) Color.parseColor("#1A000000") else Color.parseColor("#26FFFFFF")
        val track = if (day) Color.parseColor("#26000000") else Color.parseColor("#33FFFFFF")
        val fill = if (day) m.acc2 else m.acc
        val dockTop = if (day) Color.parseColor("#F2EEF4FB") else al(m.tint, 0xF7)
        val dockBot = if (day) Color.parseColor("#F7E2EAF4") else al(dk(m.tint), 0xFC)
        val dockLine = if (day) Color.parseColor("#33000000") else if (ios) Color.parseColor("#388CBEFF") else al(m.acc, 0x55)
    }

    private var th = Th(false, Modes.list[0])

    /** Barra fina de progreso (musica y velocidad). */
    private class ThinBar(c: Context) : View(c) {
        var frac = 0f
        var trackC = Color.parseColor("#33FFFFFF")
        var fillC = Color.parseColor("#4DD0E1")
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val r = h / 2f
            pt.color = trackC
            cv.drawRoundRect(0f, 0f, w, h, r, r, pt)
            val f = frac.coerceIn(0f, 1f)
            if (f > 0f) {
                pt.color = fillC
                cv.drawRoundRect(0f, 0f, max(h, w * f), h, r, r, pt)
            }
        }
    }

    private fun themeMode(): String = prefs.getString("theme_mode", "auto") ?: "auto"

    /** Amanecer y atardecer (minutos del dia, hora local) en tu ubicación (GPS, o el centro del mapa descargado). */
    private fun sunTimes(cal: Calendar): Pair<Int, Int> {
        val here = if (!TripService.lat.isNaN()) Pair(TripService.lat, TripService.lon) else MapData.center(this)
        val lat = Math.toRadians(here.first.coerceIn(-66.0, 66.0))
        val lon = here.second
        val g = 2 * Math.PI / 365.0 * (cal.get(Calendar.DAY_OF_YEAR) - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val cosH = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosH < -1.0 || cosH > 1.0) return 360 to 1080
        val ha = Math.toDegrees(acos(cosH))
        val off = TimeZone.getDefault().getOffset(cal.timeInMillis) / 60000.0
        val rise = 720 - 4 * (lon + ha) - eq + off
        val set = 720 - 4 * (lon - ha) - eq + off
        return rise.toInt() to set.toInt()
    }

    private fun isDay(): Boolean = when (themeMode()) {
        "light" -> true
        "dark" -> false
        else -> {
            val c = Calendar.getInstance()
            val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val (rise, set) = sunTimes(c)
            m in rise until set
        }
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // Colores
    private val bg = Color.parseColor("#0A1428")
    private val cardBg = Color.parseColor("#B3101E38")
    private val cardPressed = Color.parseColor("#D9223A63")
    private val strokeC = Color.parseColor("#3377A9FF")
    private val accent = Color.parseColor("#0A84FF")
    private val accentPressed = Color.parseColor("#4DA3FF")
    private val soft = Color.parseColor("#C5D3E8")
    private val muted = Color.parseColor("#8EA0BA")
    private val green = Color.parseColor("#34D27B")
    private val amber = Color.parseColor("#FFCC3D")
    private val red = Color.parseColor("#FF5A4F")
    private val teal = Color.parseColor("#CC139C8E")
    private val redCard = Color.parseColor("#CCB3261E")

    private val defaultFavs = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.whatsapp",
        "com.spotify.music",
        "com.waze",
        "com.google.android.apps.maps",
        "com.android.chrome"
    )
    // Taller de confianza: lo escribe cada usuario (no viene nada de fábrica)
    private fun tallerNombre() = ps("taller_nombre") ?: ""
    private fun tallerRubro() = ps("taller_rubro") ?: ""
    private fun tallerContacto() = ps("taller_contacto") ?: ""
    private fun tallerTelefono() = ps("taller_telefono") ?: ""
    private fun tallerMapa() = ps("taller_mapa") ?: ""
    private fun editTaller() = form("Tu taller de confianza", listOf(
        F("taller_nombre", "Nombre del taller", KIND_TEXT),
        F("taller_rubro", "Descripción (opcional)", KIND_TEXT),
        F("taller_contacto", "Persona de contacto", KIND_TEXT),
        F("taller_telefono", "Teléfono", KIND_TEXT),
        F("taller_mapa", "Enlace del mapa (opcional)", KIND_TEXT)
    ))

    private val carPlayHints = listOf("zlink", "carplay", "carlink", "zjinnova")
    private val radioHints = listOf("radio")

    // Intervalos por defecto (editables por el usuario; NO son datos oficiales de ningún fabricante)
    // Intervalos en las unidades del tablero (millas). Referencias genéricas del mercado;
    // llantas 5.000-7.500, frenos cada 10.000. Todo editable.
    private val maint = listOf(
        Triple("Rotación llantas", "rot", 7500),
        Triple("Balanceo", "bal", 7500),
        Triple("Alineación", "ali", 12500),
        Triple("Frenos (revisión)", "fre", 10000),
        Triple("Aceite de caja (CVT)", "cvt", 30000)
    )

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        TripService.loadTrip(prefs)
        TechBase.base = Modes.get(prefs.getString("app_mode", "ios")).acc
        LocalPlayer.init(this)
        window.setBackgroundDrawable(ColorDrawable(bg))
        if (!prefs.getBoolean("seed_v27", false)) {
            seedDefaults()
            prefs.edit().putBoolean("seed_v27", true).apply()
        }
    }

    override fun onResume() {
        try {
            if (prefs.getBoolean("hey_on", false) && VoskModels.ready(this) && hasMicPerm() && !AsistenteService.running) AsistenteService.start(this)
            intent?.getStringExtra("voice_cmd")?.let { pendingVoice = it; intent.removeExtra("voice_cmd") }
            intent?.getStringExtra("fuel_yes")?.let { st -> intent.removeExtra("fuel_yes"); pendingFuelAsk = st; handler.postDelayed({ askFuelIfPossible() }, 1200) }
            if (pendingVoice != null) handler.postDelayed({ consumeVoice() }, 500)
        } catch (e: Throwable) { }
        super.onResume()
        watchLights(Luces.configured(prefs))
        val nowR = SystemClock.elapsedRealtime()
        if (apps.isEmpty() || nowR - appsAt > 60_000L) { apps = loadApps(); appsAt = nowR }
        try {
            val f = IntentFilter().apply {
                addAction("com.android.music.metachanged"); addAction("com.android.music.playstatechanged")
                addAction("com.android.music.playbackcomplete"); addAction("com.android.music.queuechanged")
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(legacyRx, f, Context.RECEIVER_EXPORTED) else registerReceiver(legacyRx, f)
        } catch (e: Exception) { }
        npT = 0L
        lastPlaying = nowPlaying()?.playing == true   // solo abre el reproductor si empieza a sonar con el launcher abierto
        val fresh = page == Page.HOME && renderedPage == Page.HOME && wallView != null && nowR - lastRenderAt < 15_000L && isDay() == renderedDay
        if (!fresh) render()
        handler.post(tick)
        startLight()
        hideSystemBars()
        handler.postDelayed({ startTrip() }, 1500)
        handler.postDelayed({
            val t = System.currentTimeMillis()
            if (t - prefs.getLong("cal_net_at", 0L) > 7 * 86_400_000L && t - prefs.getLong("cal_net_try", 0L) > 6 * 3_600_000L) syncHolidaysOnline(false)
        }, 6000)
        if (!prefs.getBoolean("setup_done", false)) {
            handler.post { startSetup() }
        } else if (!prefs.getBoolean("perm_asked", false) && (!hasLocationPerm() || !hasListenerAccess())) {
            prefs.edit().putBoolean("perm_asked", true).apply()
            handler.post { askPermissions() }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        stopLight()
        watchLights(false)
        try { unregisterReceiver(legacyRx) } catch (e: Exception) { }
    }

    private var pendingVoice: String? = null

    override fun onDestroy() {
        try { liveMap?.destroy() } catch (e: Throwable) { }
        liveMap = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("fuel_yes")?.let { st -> intent.removeExtra("fuel_yes"); pendingFuelAsk = st; handler.postDelayed({ askFuelIfPossible() }, 900) }
        val vc = intent?.getStringExtra("voice_cmd")
        if (vc != null) { pendingVoice = vc; intent.removeExtra("voice_cmd"); handler.postDelayed({ consumeVoice() }, 250); return }
        page = Page.HOME
        onPick = null
        pickTitle = null
    }

    /** Orden dicha con «Hey <marca>» (la manda el servicio de voz). */
    private fun consumeVoice() {
        val cmd = pendingVoice ?: return
        if (voiceLayer == null) { handler.postDelayed({ consumeVoice() }, 300); return }
        pendingVoice = null
        if (voice == null) voice = VoiceEngine(applicationContext)
        showVoice(true)
        runVoice(cmd)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (lyrOpen) { closeLyrics(); return }
        if (page == Page.HOME && (panelOpen || stripOpen)) {
            closeOverlays()
            return
        }
        if (page == Page.LIBRARY) {
            if (libFilter != null) libFilter = null else page = Page.MUSIC
            render()
            return
        }
        if (page != Page.HOME) {
            page = Page.HOME
            onPick = null
            pickTitle = null
            render()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK) {
            data?.data?.let { savePhoto(it) }
        }
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK) {
            data?.data?.let { restoreBackup(it) }
        }
        if (requestCode == REQ_BG && resultCode == RESULT_OK) {
            data?.data?.let { saveBackground(it) }
        }
    }

    // ------------------------------------------------------------ datos

    private fun ps(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    private fun pi(key: String): Int? =
        ps(key)?.replace(".", "")?.replace(",", "")?.trim()?.toIntOrNull()

    /** Odometro = valor puesto por el usuario + metros recorridos por GPS desde entonces. */
    /** Unidad del tablero del auto: "mi" (millas) o "km". Por defecto según el país del teléfono. */
    private fun u(): String = prefs.getString("odo_unit", null) ?: "mi"
    private fun metersPerUnit(): Double = if (u() == "mi") 1609.344 else 1000.0

    private fun odoKm(): Int? {
        val base = prefs.getString("odo_base", null)?.toDoubleOrNull() ?: return null
        val gps = prefs.getLong("gps_total_m", 0L)
        val at = prefs.getLong("gps_at_set_m", 0L)
        return (base + (gps - at) / metersPerUnit()).toInt()
    }

    private val svcPrefixes = listOf("oil", "rot", "bal", "ali", "fre", "cvt")

    /** Cambia mi <-> km y convierte todo lo guardado. */
    private fun switchUnit() {
        val toKm = u() == "mi"
        val f = if (toKm) 1.609344 else 1 / 1.609344
        val ed = prefs.edit()
        prefs.getString("odo_base", null)?.toDoubleOrNull()?.let { ed.putString("odo_base", Math.round(it * f).toString()) }
        for (k in svcPrefixes) for (suffix in listOf("_last_km", "_int_km")) {
            pi(k + suffix)?.let { ed.putString(k + suffix, Math.round(it * f).toString()) }
        }
        ed.putString("odo_unit", if (toKm) "km" else "mi").apply()
        render()
    }

    /** Valores iniciales neutros: solo la unidad (millas en EE. UU., Reino Unido, Liberia y Myanmar; km en el resto). */
    private fun seedDefaults() {
        val ed = prefs.edit()
        if (ps("odo_unit") == null) {
            val c = Locale.getDefault().country
            ed.putString("odo_unit", if (c in setOf("US", "GB", "LR", "MM")) "mi" else "km")
        }
        ed.apply()
        fillMissingServices()
    }

    private fun setOdo(km: Int) {
        prefs.edit().putString("odo_base", km.toString())
            .putLong("gps_at_set_m", prefs.getLong("gps_total_m", 0L)).apply()
    }

    private fun applyOdoInput() {
        pi("odo_input")?.let { setOdo(it) }
        prefs.edit().remove("odo_input").apply()
    }

    private fun hasLocationPerm() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasListenerAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun startTrip() {
        if (!hasLocationPerm()) return
        try {
            val i = Intent(this, TripService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
        }
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            val f = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            f.isLenient = false
            f.parse(s.trim().replace('-', '/').replace('.', '/'))?.time
        } catch (e: Exception) {
            null
        }
    }

    /** Documentos anuales: si la fecha ya pasó, la mueve al mismo dia del año siguiente (hasta que sea futura). */
    private fun rollYearly(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        while (daysUntil(c.timeInMillis) < 0) c.add(Calendar.YEAR, 1)
        return c.timeInMillis
    }

    private fun daysUntil(ms: Long): Int {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return Math.floorDiv(ms - c.timeInMillis, 86_400_000L).toInt()
    }

    private fun fmtKm(n: Int): String =
        NumberFormat.getIntegerInstance(Locale.forLanguageTag("es")).format(n)

    private fun fmtDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(ms))

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun favorites(): List<AppInfo> {
        val saved = prefs.getString("favs", null)
        val pkgs = if (saved == null) defaultFavs else saved.split(",").filter { it.isNotEmpty() }
        return pkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    }

    private fun saveFavs(pkgs: List<String>) {
        prefs.edit().putString("favs", pkgs.joinToString(",")).apply()
    }

    private fun findApp(prefKey: String, hints: List<String>): AppInfo? {
        ps(prefKey)?.let { saved -> apps.firstOrNull { it.pkg == saved }?.let { return it } }
        return apps.firstOrNull { a ->
            hints.any { a.pkg.lowercase().contains(it) || a.label.lowercase().contains(it) }
        }
    }

    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            toast("No se pudo abrir la app")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Esta pantalla no tiene esa opción de ajustes")
        }
    }

    // ------------------------------------------------------------ musica

    private fun activeSessions(): List<MediaController> {
        val own = LocalPlayer.controller()
        return try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
            mediaDenied = false
            if (own != null && list.none { it.packageName == packageName }) list + own else list
        } catch (e: SecurityException) {
            mediaDenied = true
            listOfNotNull(own)
        } catch (e: Exception) {
            listOfNotNull(own)
        }
    }

    private fun srcMode(): String = prefs.getString("src_mode", "auto") ?: "auto"

    private val btWord = Regex("\\bbt\\b", RegexOption.IGNORE_CASE)

    /** Que tipo de fuente es una app de musica: local (este launcher), radio, bluetooth u otra app. */
    private fun kindOf(pkg: String?): String {
        if (pkg == null) return "app"
        if (pkg == packageName) return "local"
        if (pkg == ps("radio_pkg") || pkg.contains("radio", true)) return "radio"
        val l = appLabel(pkg)
        if (l.contains("radio", true)) return "radio"
        if (pkg.contains("bluetooth", true) || pkg.contains("btmusic", true) || pkg.contains("a2dp", true) ||
            l.contains("bluetooth", true) || btWord.containsMatchIn(l) || btWord.containsMatchIn(pkg.replace('.', ' '))) return "bt"
        return "app"
    }

    private fun activeController(mode: String = "auto"): MediaController? {
        val list = activeSessions()
        val pool = if (mode == "auto") list else list.filter { kindOf(it.packageName) == mode }
        return pool.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: pool.firstOrNull()
    }

    /** Lo que suena ahora, de la sesion multimedia o, si no trae datos, de avisos y notificaciones. */
    private class NP(
        val c: MediaController?, val title: String?, val artist: String?, val album: String?,
        val art: Bitmap?, val pos: Long, val dur: Long, val playing: Boolean, val pkg: String?, val have: String
    )

    private fun artFor(md: android.media.MediaMetadata, key: String): Bitmap? {
        if (key == artKey && artBmp != null) return artBmp
        var b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (b == null) {
            val u = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI)
                ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (u != null) b = try {
                contentResolver.openInputStream(Uri.parse(u))?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            } catch (e: Exception) { null }
        }
        artKey = key
        artBmp = b
        return b
    }

    // Caratulas de la app OpenCar Music (si esta instalada): se piden por ContentProvider y se reintenta cada 2 min
    private val cArt = HashMap<String, Pair<Long, Bitmap?>>()
    private val cBusy = HashSet<String>()

    private fun companionArt(artist: String?, album: String?, title: String?): Bitmap? {
        if (title.isNullOrBlank()) return null
        val key = "$artist|$album|$title"
        val now = SystemClock.elapsedRealtime()
        val e = cArt[key]
        if (e != null && (e.second != null || now - e.first < 120_000L)) return e.second
        if (cBusy.add(key)) {
            Thread {
                val uri = Uri.parse("content://org.opencar.music.art/cover?artist=${Uri.encode(artist ?: "")}&album=${Uri.encode(album ?: "")}&title=${Uri.encode(title)}")
                val b = try { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (x: Exception) { null }
                handler.post { cArt[key] = Pair(SystemClock.elapsedRealtime(), b); cBusy.remove(key); npT = 0L }
            }.start()
        }
        return e?.second
    }

    private fun nowPlaying(): NP? {
        val t = SystemClock.elapsedRealtime()
        if (t - npT < 400L) return npV
        npV = computeNowPlaying()
        npT = t
        return npV
    }

    private fun computeNowPlaying(mode: String = "auto"): NP? {
        val c = activeController(mode)
        val md = c?.metadata
        var title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        var artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        var album = md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
        val st = c?.playbackState
        var playing = st?.state == PlaybackState.STATE_PLAYING
        var origin = if (c != null) "sesión de música" else ""
        val now = System.currentTimeMillis()
        if (title == null && lgTitle != null && now - lgTs < 21_600_000L && (mode == "auto" || mode == "bt")) {
            title = lgTitle
            if (artist == null) artist = lgArtist
            if (album == null) album = lgAlbum
            if (c == null) playing = lgPlaying
            origin = "aviso de la app de música"
        }
        val nTitle = MediaListener.title
        if (title == null && nTitle != null && now - MediaListener.ts < 21_600_000L &&
            (mode == "auto" || kindOf(MediaListener.pkg) == mode)) {
            title = nTitle
            if (artist == null) artist = MediaListener.text
            origin = "notificación de ${MediaListener.pkg}"
        }
        if (c == null && title == null) return null
        val art = (if (md != null) artFor(md, "${c?.packageName}|$title|$artist") else null) ?: companionArt(artist, album, title)
        val dur = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st?.position ?: -1L
        if (st != null && playing && pos >= 0L) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        fun ok(b: Boolean) = if (b) "✓" else "✗"
        val have = "título ${ok(title != null)} · artista ${ok(artist != null)} · álbum ${ok(album != null)} · " +
            "carátula ${ok(art != null)} · duración ${ok(dur > 0)} · origen: ${origin.ifEmpty { "ninguno" }}"
        return NP(c, title, artist, album, art, pos, dur, playing, c?.packageName ?: MediaListener.pkg, have)
    }

    private fun updateMedia() {
        if (mediaTitle == null) return
        val np = nowPlaying()
        mediaArt?.let { iv ->
            val b = np?.art
            if (b !== mediaArtBmp) { mediaArtBmp = b; if (b != null) iv.setImageBitmap(b) else iv.setImageDrawable(null) }
        }
        if (np == null) {
            val t0 = if (mediaDenied) "Activar info de música" else "Sin reproducción"
            val s0 = if (mediaDenied) "Toca aquí y permite el acceso" else "Toca para abrir el reproductor"
            if (mT != t0) { mediaTitle?.text = t0; mT = t0 }
            if (mS != s0) { mediaSub?.text = s0; mS = s0 }
            if (mP != 0) { playIcon?.setImageResource(R.drawable.ic_play); mP = 0 }
            mediaRow?.visibility = View.GONE
            return
        }
        val t1 = np.title ?: "Reproduciendo"
        val s1 = np.artist ?: ""
        if (mT != t1) { mediaTitle?.text = t1; mT = t1 }
        if (mS != s1) { mediaSub?.text = s1; mS = s1 }
        val p1 = if (np.playing) 2 else 1
        if (mP != p1) { playIcon?.setImageResource(if (np.playing) R.drawable.ic_pause else R.drawable.ic_play); mP = p1 }
        if (np.dur > 0L && np.pos >= 0L) {
            val shown = np.pos.coerceIn(0L, np.dur)
            mediaBar?.frac = shown.toFloat() / np.dur
            mediaBar?.invalidate()
            mediaTime?.text = "${fmtTime(shown)} / ${fmtTime(np.dur)}"
            mediaRow?.visibility = View.VISIBLE
        } else {
            mediaRow?.visibility = View.GONE
        }
    }

    /** Abre el reproductor solo cuando la musica EMPIEZA a sonar estando el launcher a la vista. */
    private fun checkAutoMusic() {
        val playing = nowPlaying()?.playing == true
        if (playing && !lastPlaying && page == Page.HOME && !panelOpen && !stripOpen && onPick == null &&
            prefs.getBoolean("music_auto", true)) {
            page = Page.MUSIC
            render()
        }
        lastPlaying = playing
    }

    private fun speedUnit(): String = prefs.getString("speed_unit", "kmh") ?: "kmh"

    private fun updateSpeed() {
        val tv = speedView ?: return
        val mph = speedUnit() == "mph"
        speedUnitView?.text = if (mph) "mph" else "km/h"
        val fresh = TripService.running && TripService.lastFixMs != 0L &&
            System.currentTimeMillis() - TripService.lastFixMs < 8000L
        if (!fresh) {
            tv.text = "--"
            tv.setTextColor(th.fg)
            speedBar?.frac = 0f
        } else {
            val kmh = TripService.speedMps * 3.6f
            val shown = if (kmh < 3f) 0 else (if (mph) kmh * 0.621371f else kmh).roundToInt()
            tv.text = shown.toString()
            tv.setTextColor(if (speedOver(kmh)) red else th.fg)
            speedBar?.frac = shown / (if (mph) 100f else 160f)
        }
        speedBar?.invalidate()
    }

    private fun mediaKey(code: Int, mode: String = "auto") {
        // 1) Controlar directamente la sesion activa (ZLink, Spotify, etc.)
        val c = activeController(mode)
        if (c != null) {
            val t = c.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
        } else {
            // 2) Sin permiso o sin sesion: tecla multimedia global (como los controles del volante)
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        handler.postDelayed({ updateMedia() }, 600)
    }

    // ------------------------------------------------------------ acciones / dialogos

    private fun chooseApp(prefKey: String, title: String) {
        pickTitle = title
        onPick = { app -> prefs.edit().putString(prefKey, app.pkg).apply() }
        page = Page.ALL
        render()
    }

    private fun addFavorite() {
        pickTitle = "Elige la app para agregar a favoritos"
        onPick = { app ->
            val cur = favorites().map { it.pkg }.toMutableList()
            if (!cur.contains(app.pkg)) cur.add(app.pkg)
            saveFavs(cur)
        }
        page = Page.ALL
        render()
    }

    private fun appOptions(app: AppInfo) {
        val isFav = favorites().any { it.pkg == app.pkg }
        val items = arrayOf(
            if (isFav) "Quitar de favoritos" else "Agregar a favoritos",
            "Usar como app de CarPlay",
            "Usar como app de radio",
            "Usar como app de mapas"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val cur = favorites().map { it.pkg }.toMutableList()
                        if (isFav) cur.remove(app.pkg) else cur.add(app.pkg)
                        saveFavs(cur)
                    }
                    1 -> prefs.edit().putString("carplay_pkg", app.pkg).apply()
                    2 -> prefs.edit().putString("radio_pkg", app.pkg).apply()
                    3 -> prefs.edit().putString("nav_pkg", app.pkg).apply()
                }
                render()
            }
            .show()
    }

    private fun form(
        title: String,
        fields: List<F>,
        onSaved: (() -> Unit)? = null,
        cancelLabel: String = "Cancelar",
        onCancel: (() -> Unit)? = null,
        extra: ((AlertDialog.Builder) -> Unit)? = null
    ) {
        val sv = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(24), p(8), p(24), p(8))
        }
        sv.addView(box)
        val edits = fields.map { f ->
            box.addView(TextView(this).apply {
                text = f.label
                size(16f)
                setPadding(0, p(10), 0, 0)
            })
            val e = EditText(this).apply {
                setText(prefs.getString(f.key, f.def))
                inputType = when (f.kind) {
                    KIND_NUM -> InputType.TYPE_CLASS_NUMBER
                    KIND_DATE -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                    else -> InputType.TYPE_CLASS_TEXT
                }
                if (f.kind == KIND_DATE) hint = "dd/mm/aaaa"
                setSingleLine()
                size(18f)
            }
            box.addView(e)
            e
        }
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Guardar") { _, _ ->
                val ed = prefs.edit()
                fields.forEachIndexed { i, f -> ed.putString(f.key, edits[i].text.toString().trim()) }
                ed.apply()
                onSaved?.invoke()
                render()
            }
            .setNegativeButton(cancelLabel) { _, _ -> onCancel?.invoke() }
        extra?.invoke(b)
        b.show()
    }

    private fun editVehicle() = form(
        "Tu vehículo (el odómetro suma solo con el GPS)",
        listOf(
            F("car_name", "Nombre (ej. Toyota Corolla 2012)", KIND_TEXT),
            F("car_plate", "Placa (opcional)", KIND_TEXT),
            F("car_motor", "Motor (opcional)", KIND_TEXT),
            F("car_tires", "Llantas (opcional)", KIND_TEXT),
            F("car_psi", "Presión de llantas (opcional)", KIND_TEXT),
            F("odo_input", "Odómetro actual (km). Corrígelo aquí si difiere del tablero", KIND_NUM, odoKm()?.toString() ?: "")
        ),
        onSaved = { applyOdoInput() }
    ) { it.setNeutralButton("Marca y modelo") { _, _ -> carPicker().pickBrand(null) } }

    private fun editOil() = form(
        "Cambio de aceite",
        listOf(
            F("oil_last_km", "Lectura del tablero en el último cambio (${u()})", KIND_NUM),
            F("oil_int_km", "Cada cuántos ${u()}", KIND_NUM, "5000"),
            F("oil_last_date", "Fecha del último cambio", KIND_DATE),
            F("oil_int_months", "Cada cuántos meses", KIND_NUM, "6")
        )
    )

    private fun editMaint() = form(
        "Llantas y mantenimiento",
        maint.flatMap { (label, k, def) ->
            listOf(
                F("${k}_last_km", "$label · lectura del último servicio (${u()})", KIND_NUM),
                F("${k}_int_km", "$label · cada cuántos ${u()}", KIND_NUM, def.toString())
            )
        }
    )

    private fun editDocs() = form(
        "Documentos (al poner una fecha futura se renuevan solos cada año)",
        listOf(
            F("soat_date", "SOAT vence", KIND_DATE),
            F("insp_date", "Inspección técnica vence", KIND_DATE),
            F("imp_date", "Impuesto vence", KIND_DATE)
        )
        ,
        onSaved = {
            for (k in listOf("soat", "insp", "imp")) {
                val d = parseDate(ps("${k}_date"))
                if (d != null && daysUntil(d) >= 0) prefs.edit().putString("${k}_roll", "1").apply()
            }
        }
    )

    // ---- asistente inicial

    /** Asistente con formulario (se abre desde Diagnostico). */
    private fun setupForm() {
        prefs.edit().putBoolean("setup_done", true).apply()
        form(
            "Configuración inicial (1 de 3)",
            listOf(
                F("odo_input", "Odómetro actual del auto (km)", KIND_NUM, odoKm()?.toString() ?: ""),
                F("oil_last_km", "Lectura del tablero en el último cambio de aceite (${u()})", KIND_NUM),
                F("oil_last_date", "Fecha del último cambio de aceite", KIND_DATE),
                F("soat_date", "SOAT vence (opcional)", KIND_DATE),
                F("insp_date", "Inspección técnica vence (opcional)", KIND_DATE)
            ),
            onSaved = { applyOdoInput(); fillMissingServices(); askPermissions() },
            cancelLabel = "Omitir",
            onCancel = { askPermissions() }
        )
    }

    /** Primer arranque: los datos ya vienen cargados, solo se piden los permisos. */
    private fun startSetup() {
        prefs.edit().putBoolean("setup_done", true).apply()
        seedDefaults()
        render()
        askPermissions()
    }

    /** Lo que no sepas se empieza a contar desde hoy. */
    private fun fillMissingServices() {
        val odo = odoKm() ?: return
        val ed = prefs.edit()
        if (ps("oil_last_km") == null) ed.putString("oil_last_km", odo.toString())
        if (ps("oil_last_date") == null) ed.putString("oil_last_date", fmtDate(System.currentTimeMillis()))
        for ((_, k, _) in maint) if (ps("${k}_last_km") == null) ed.putString("${k}_last_km", odo.toString())
        ed.apply()
    }

    private fun askPermissions() {
        if (hasLocationPerm()) {
            startTrip()
            askListener()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Permisos (2 de 3)")
            .setMessage("Para contar los kilómetros solo con el GPS de la pantalla, permite el acceso a la ubicación en la ventana que sigue.")
            .setPositiveButton("Continuar") { _, _ -> requestPerms(true) }
            .setNegativeButton("Después") { _, _ -> askListener() }
            .show()
    }

    private fun requestPerms(fromWizard: Boolean) {
        permFromWizard = fromWizard
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), REQ_PERM)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            LocalPlayer.loaded = false
            if (page == Page.LIBRARY) render()
        }
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openVoice() else toast("Sin permiso del micrófono no puedo escucharte")
        }
        if (requestCode == REQ_PERM) {
            startTrip()
            if (permFromWizard) {
                permFromWizard = false
                askListener()
            }
        }
    }

    private fun askListener() {
        if (hasListenerAccess()) return
        AlertDialog.Builder(this)
            .setTitle("Música (3 de 3)")
            .setMessage("Para mostrar el título y artista de lo que suena: en la lista que se abre, activa «OpenCar Launcher».")
            .setPositiveButton("Abrir") { _, _ -> openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }
            .setNegativeButton("Después", null)
            .show()
    }

    // ---- marcar servicios como hechos hoy

    private fun markDone(prefix: String) {
        val odo = odoKm()
        if (odo == null) {
            toast("Primero pon el odómetro (toca el auto)")
            return
        }
        val ed = prefs.edit().putString("${prefix}_last_km", odo.toString())
        if (prefix == "oil") ed.putString("oil_last_date", fmtDate(System.currentTimeMillis()))
        ed.apply()
        render()
    }

    private fun confirmOilDone() {
        AlertDialog.Builder(this)
            .setTitle("Cambio de aceite")
            .setMessage("¿Marcarlo como hecho hoy, con el kilometraje actual?")
            .setPositiveButton("Sí, hecho hoy") { _, _ -> markDone("oil") }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickMaintDone() {
        AlertDialog.Builder(this)
            .setTitle("¿Qué servicio hiciste hoy?")
            .setItems(maint.map { it.first }.toTypedArray()) { _, i -> markDone(maint[i].second) }
            .show()
    }

    private val dayNames = arrayOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

    private fun editRestriction() {
        val mask = prefs.getInt("restr_mask", 0)
        val checked = BooleanArray(7) { mask and (1 shl it) != 0 }
        AlertDialog.Builder(this)
            .setTitle("¿Qué días NO puedes circular?")
            .setMultiChoiceItems(dayNames, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Guardar") { _, _ ->
                var m = 0
                checked.forEachIndexed { i, c -> if (c) m = m or (1 shl i) }
                prefs.edit().putInt("restr_mask", m).putBoolean("restr_set", true).apply()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun photoOptions() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Elegir foto del auto (aparece en el centro)", "Quitar foto del auto")) { _, which ->
                if (which == 0) {
                    try {
                        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        startActivityForResult(i, REQ_PHOTO)
                    } catch (e: ActivityNotFoundException) {
                        toast("La pantalla no tiene selector de fotos")
                    }
                } else {
                    File(filesDir, "car.png").delete()
                    render()
                }
            }
            .show()
    }

    /** Decodifica una imagen, reduce su tamano y corrige la rotacion (EXIF). */
    private fun loadBitmap(uri: Uri, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxW) sample *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        }
        return bmp
    }

    private fun savePhoto(uri: Uri) {
        try {
            val bmp = loadBitmap(uri, 1400) ?: return
            FileOutputStream(File(filesDir, "car.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            render()
        } catch (e: Exception) {
            toast("No se pudo cargar la foto")
        }
    }

    private val bgNames = listOf("Azul noche", "Grafito", "Atardecer", "Verde bosque", "Violeta")
    private val bgColors = listOf(
        intArrayOf(0xFF0B1B3A.toInt(), 0xFF1E5AA8.toInt()), intArrayOf(0xFF101214.toInt(), 0xFF3A4048.toInt()),
        intArrayOf(0xFF2B1138.toInt(), 0xFFE0704A.toInt()), intArrayOf(0xFF07241A.toInt(), 0xFF2E8B57.toInt()),
        intArrayOf(0xFF1A0F3D.toInt(), 0xFF7A4FD6.toInt()))
    private val sessionBg = (1..5).random()
    private fun wakeName() = CarCatalog.wakeWord(this, prefs).replaceFirstChar { it.uppercase() }
    private fun carPicker() = CarPicker(this, prefs) { bgCacheKey = null; render() }

    /** Recorta la imagen al formato de la pantalla para que llene sin deformarse. */
    private fun fitToScreen(src: Bitmap): Bitmap {
        val dm = resources.displayMetrics
        val target = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()
        val cur = src.width.toFloat() / src.height.toFloat()
        val cropW: Int
        val cropH: Int
        if (cur > target) { cropH = src.height; cropW = (src.height * target).toInt() }
        else { cropW = src.width; cropH = (src.width / target).toInt() }
        val cropped = Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        val outW = min(cropped.width, 1280)
        return Bitmap.createScaledBitmap(cropped, outW, (outW / target).toInt(), true)
    }

    private fun decodeSampled(open: () -> java.io.InputStream?): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) }
        val tw = max(resources.displayMetrics.widthPixels, 1024)
        var sm = 1
        while (b.outWidth / (sm * 2) >= tw) sm *= 2
        return open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sm }) }
    }

    /** Degradados incluidos (no llevan fotos de nadie). */
    private fun assetBg(n: Int): Bitmap? {
        val c = bgColors[(n - 1).coerceIn(0, bgColors.size - 1)]
        val bmp = Bitmap.createBitmap(512, 300, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 512f, 300f, c[0], c[1], Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, 512f, 300f, pt)
        return bmp
    }

    /** Fondo actual segun la eleccion: bg1..bg5, random, custom o none. */
    private fun currentBackground(): Bitmap? {
        val choice = prefs.getString("bg_choice", null) ?: "random"
        val ck = currentKey() + (if (choice == "custom") File(filesDir, "bg.jpg").lastModified() else 0L)
        if (ck == bgCacheKey && bgCacheBmp != null) return bgCacheBmp
        val r = decodeBackground(choice)
        bgCacheKey = ck; bgCacheBmp = r
        return r
    }

    private fun decodeBackground(choice: String): Bitmap? {
        return try {
            when {
                choice == "none" -> null
                choice == "custom" -> File(filesDir, "bg.jpg").takeIf { it.exists() }
                    ?.let { fl -> decodeSampled { fl.inputStream() } }
                choice == "random" -> assetBg(sessionBg)
                choice.startsWith("bg") -> assetBg(choice.removePrefix("bg").toInt())
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBackground(uri: Uri) {
        try {
            val src = loadBitmap(uri, 1600) ?: return
            FileOutputStream(File(filesDir, "bg.jpg")).use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            prefs.edit().putString("bg_choice", "custom").apply()
            render()
        } catch (e: Exception) {
            toast("No se pudo cargar la imagen de fondo")
        }
    }

    private fun bgOptions() {
        AlertDialog.Builder(this)
            .setTitle("Fondo de pantalla")
            .setItems(arrayOf(
                "Degradados incluidos",
                "Aleatorio (cambia cada vez que enciende)",
                "Elegir imagen de mi almacenamiento",
                "Buscar foto de mi modelo en internet",
                "Elegir marca y modelo de mi auto",
                "Foto del auto (aparece en el centro)",
                "Sin fondo (degradado azul)"
            )) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("Elige un fondo")
                        .setItems(bgNames.toTypedArray()) { _, i ->
                            prefs.edit().putString("bg_choice", "bg${i + 1}").apply()
                            render()
                        }.show()
                    1 -> { prefs.edit().putString("bg_choice", "random").apply(); render() }
                    2 -> try {
                        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        startActivityForResult(i, REQ_BG)
                    } catch (e: ActivityNotFoundException) {
                        toast("La pantalla no tiene selector de imágenes")
                    }
                    3 -> carPicker().photos(CarCatalog.title(prefs).lowercase(), null)
                    4 -> carPicker().pickBrand(null)
                    5 -> photoOptions()
                    else -> { prefs.edit().putString("bg_choice", "none").apply(); render() }
                }
            }
            .show()
        prefs.getString("bg_credit", null)?.takeIf { it.isNotBlank() }?.let { toast("Foto: $it") }
    }

    // ------------------------------------------------------------ construccion de UI

    private fun p(v: Float) = (v * scale).toInt()
    private fun p(v: Int) = p(v.toFloat())
    private fun TextView.size(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, v * scale)

    private fun render() {
        clockView = null; dateView = null; mediaTitle = null; mediaSub = null; playIcon = null
        mediaBar = null; mediaTime = null; mediaRow = null
        musicTitleV = null; musicArtistV = null; musicAlbumV = null; musicSrcV = null; musicSrcIcon = null
        musicArtView = null; musicNoArt = null; musicBgV = null; musicSeek = null; musicPosV = null; musicDurV = null
        musicPlayV = null; musicVol = null; musicVolPct = null; musicChips = null; musicInfoV = null
        chipsSig = ""; shownKey = null; lastArt = null
        clockKey = -1L; mT = null; mS = null; mP = -1; lastRenderAt = SystemClock.elapsedRealtime(); renderedPage = page
        driveLayer = null; driveSpeedV = null; driveUnitV = null; driveTitleV = null; driveSubV = null; drivePlayV = null
        lyrOverlay = null; lyrScroll = null; lyrLines = emptyList(); lyrOpen = false; lyrIdx = -1
        speedView = null; speedUnitView = null; speedBar = null; speedCard = null
        try { liveMap?.destroy() } catch (e: Throwable) { }; liveMap = null; widgetLayer = null; glowView = null; mediaArt = null; mediaArtBmp = null; miniMap = null; menuToggleFn = null; menuLongFn = null; alertView = null; lightsShown = null; homeArea = null
        volCard = null; volBar2 = null; volPct2 = null
        tripSpeedV = null; tripHeadV = null; tripAltV = null; tripAccV = null; tripTimeV = null; tripKmV = null; tripAvgV = null
        tripMaxV = null; chartSpeed = null; chartAlt = null; tripVolBar = null; tripLimitV = null; tripOnTile = null; tripOffTile = null
        bigLayer = null; bigTimeV = null; bigDateV = null; bigInfoV = null
        chipStyleBig = null; chipStyleDrive = null
        barTime = null; barTemp = null; barNote = null; barGps = null; barWifi = null; barBt = null; barVol = null; barChip = null; barSig = ""
        qpLayer = null; qpPanel = null; qpVol = null; qpBright = null; qpOpen = false
        tapFx = null
        if (page != Page.HOME) bigActive = false
        th = Th(isDay(), mode())
        renderedDay = th.day
        val dm = resources.displayMetrics
        scale = min(dm.widthPixels / 1024f, dm.heightPixels / 600f).coerceIn(0.5f, 2.5f)
        val content = (when (page) { Page.HOME -> buildHome(); Page.ALL -> buildAll(); Page.DIAG -> buildDiag(); Page.CAR -> buildCar(); Page.CARPLAY -> buildCarPlay(); Page.MAPS -> buildMaps(); Page.MUSIC -> buildMusic(); Page.LIBRARY -> buildLibrary(); Page.CAL -> buildCal(); Page.TRIP -> buildTrip(); Page.EQ -> buildEq() })
        setContentView(wrapBar(content))
        if (modeFx) {
            modeFx = false
            val m = mode()
            val v = findViewById<View>(android.R.id.content)
            v.alpha = 0f; v.scaleX = if (m.bouncy) 0.9f else 0.97f; v.scaleY = v.scaleX
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(m.pressMs * 3 + 120).setInterpolator(if (m.bouncy) android.view.animation.OvershootInterpolator(1.6f) else android.view.animation.DecelerateInterpolator()).start()
        }
        updateClock()
        updateMedia()
        updateSpeed()
        updateMusicPage()
    }

    private fun updateClock() {
        val ms = System.currentTimeMillis()
        val k = ms / 60_000L
        if (k == clockKey) return
        clockKey = k
        val now = Date(ms)
        val tz = TimeZone.getDefault()
        timeFmt.timeZone = tz; dateFmt.timeZone = tz
        clockView?.text = timeFmt.format(now)
        val tmp = tempText()
        dateView?.text = dateFmt.format(now) + (if (tmp != null) "  ·  $tmp" else "")
    }

    private fun rounded(color: Int, radius: Float = 18f, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = p(radius).toFloat()
        if (stroke) setStroke(max(1, p(1)), strokeC)
    }

    private fun glass(color: Int, pressed: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, 18f, true))
        addState(intArrayOf(android.R.attr.state_focused), rounded(pressed, 18f, true))
        addState(intArrayOf(), rounded(color, 18f, true))
    }

    private fun weight(w: Float, h: Int = 0, m: Float = 5f) =
        LinearLayout.LayoutParams(0, if (h == 0) MATCH_PARENT else h, w).apply {
            setMargins(p(m), p(m), p(m), p(m))
        }

    private fun vweight(w: Float, m: Float = 5f) =
        LinearLayout.LayoutParams(MATCH_PARENT, 0, w).apply { setMargins(p(m), p(m), p(m), p(m)) }

    private fun LinearLayout.line(text: String, color: Int = soft, sz: Float = 15f) {
        addView(TextView(this@MainActivity).apply {
            this.text = text
            size(sz)
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, p(2), 0, 0)
        })
    }

    private fun card(
        title: String,
        color: Int = cardBg,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null,
        build: LinearLayout.() -> Unit
    ): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(p(16), p(6), p(16), p(6))
        c.background = glass(color, cardPressed)
        c.isClickable = true
        c.isFocusable = true
        c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener { onLong(); true }
        c.addView(TextView(this).apply {
            text = title
            size(17f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        c.build()
        return c
    }

    // ---- tarjetas de mantenimiento

    private fun docsCard(): LinearLayout {
        val docs = listOf("SOAT" to "soat_date", "Inspección técnica" to "insp_date", "Impuesto" to "imp_date")
        return card("Documentos", onClick = { editDocs() }) {
            var shown = 0
            for ((label, key) in docs) {
                val base = parseDate(ps(key)) ?: continue
                val roll = ps(key.replace("_date", "_roll")) != "0"
                val ms = if (roll) rollYearly(base) else base // anual: pasa solo al año siguiente
                val d = daysUntil(ms)
                val col = if (d <= 7) red else if (d < 30) amber else green
                line(
                    when {
                        d < 0 -> "$label: ¡VENCIDO hace ${-d} días! (${fmtDate(ms)})"
                        d == 0 -> "$label: ¡vence HOY!"
                        else -> "$label: $d días (${fmtDate(ms)})"
                    }, col, 14f
                )
                shown++
            }
            if (shown == 0) line("Toca para configurar", muted)
        }
    }

    // ---- datos de estado (burbuja de avisos y panel Estado)

    private data class Item(val label: String, val value: String, val sub: String?, val frac: Float, val level: Int)

    private fun lvlColor(l: Int) = when (l) { 2 -> red; 1 -> amber; else -> green }

    private fun oilItem(): Item? {
        val odo = odoKm()
        val lastKm = pi("oil_last_km")
        val intKm = pi("oil_int_km") ?: 4000
        val lastDate = parseDate(ps("oil_last_date"))
        val months = pi("oil_int_months") ?: 6
        val kmLeft = if (odo != null && lastKm != null) lastKm + intKm - odo else null
        val due = lastDate?.let {
            Calendar.getInstance().apply { timeInMillis = it; add(Calendar.MONTH, months) }.timeInMillis
        }
        val days = due?.let { daysUntil(it) }
        if (kmLeft == null && days == null) return null
        var level = 0
        kmLeft?.let { level = max(level, if (it <= 0) 2 else if (it < 1000) 1 else 0) }
        days?.let { level = max(level, if (it <= 0) 2 else if (it < 30) 1 else 0) }
        val value = when {
            kmLeft != null && kmLeft <= 0 -> "¡pasado ${fmtKm(-kmLeft)} ${u()}!"
            kmLeft != null -> "${fmtKm(kmLeft)} ${u()}"
            else -> "$days días"
        }
        val sub = if (days != null && due != null) {
            (if (days <= 0) "vencido hace ${-days} días" else "$days días") + " · límite ${fmtDate(due)}"
        } else null
        val frac = when {
            kmLeft != null -> (kmLeft.toFloat() / intKm).coerceIn(0f, 1f)
            days != null -> (days / (months * 30f)).coerceIn(0f, 1f)
            else -> 0f
        }
        return Item("Aceite del motor", value, sub, frac, level)
    }

    private fun maintItems(): List<Item> {
        val odo = odoKm() ?: return emptyList()
        return maint.mapNotNull { (label, k, def) ->
            val last = pi("${k}_last_km") ?: return@mapNotNull null
            val interval = pi("${k}_int_km") ?: def
            val left = last + interval - odo
            Item(
                label,
                if (left <= 0) "¡pasado ${fmtKm(-left)}!" else fmtKm(left),
                null,
                (left.toFloat() / interval).coerceIn(0f, 1f),
                if (left <= 0) 2 else if (left < 1000) 1 else 0
            )
        }
    }

    private fun docItems(): List<Item> {
        val docs = listOf("SOAT" to "soat_date", "Inspección técnica" to "insp_date", "Impuesto" to "imp_date")
        return docs.mapNotNull { (label, key) ->
            val base = parseDate(ps(key)) ?: return@mapNotNull null
            val roll = ps(key.replace("_date", "_roll")) != "0"
            val ms = if (roll) rollYearly(base) else base
            val d = daysUntil(ms)
            val level = if (d <= 7) 2 else if (d < 30) 1 else 0
            val text = when {
                d < 0 -> "VENCIDA hace ${-d} días"
                d == 0 -> "vence HOY"
                else -> "$d días · ${fmtDate(ms)}"
            }
            Item(label, text, null, 0f, level)
        }
    }

    /** Cuantas cosas piden atencion y cual es la mas grave (0 ok, 1 pronto, 2 urgente). */
    private fun attention(): Pair<Int, Int> {
        val all = listOfNotNull(oilItem()) + maintItems() + docItems()
        val bad = all.filter { it.level > 0 }
        return Pair(bad.size, bad.maxOfOrNull { it.level } ?: 0)
    }

    // ---- pantalla principal (diseño v3)

    private var wallView: ImageView? = null
    private var dotsView: LinearLayout? = null
    private var panelView: LinearLayout? = null
    private var dimView: View? = null
    private var stripView: LinearLayout? = null
    private var hideOnPanel: List<View> = emptyList()
    private var arrowRing: View? = null
    private var panelOpen = false
    private var stripOpen = false
    private val cyan: Int get() = mode().acc
    private val panelAutoClose = Runnable { setPanel(false) }
    private val stripAutoClose = Runnable { setStrip(false) }

    private fun currentKey(): String {
        val c = prefs.getString("bg_choice", null) ?: "random"
        return if (c == "random") "bg$sessionBg" else c
    }

    private fun bgOrder(): List<String> =
        (1..5).map { "bg$it" } + (if (File(filesDir, "bg.jpg").exists()) listOf("custom") else emptyList())

    /** Altura (0..1) del centro del auto en cada foto, para recortar sin cortar techo ni ruedas. */
    private fun focalFor(key: String) = when (key) {
        "bg1" -> 0.595f; "bg2" -> 0.58f; "bg3" -> 0.60f; "bg4" -> 0.62f; "bg5" -> 0.585f
        else -> 0.55f
    }

    private fun fitWallpaper() {
        val v = wallView ?: return
        val d = v.drawable ?: return
        if (v.width == 0 || v.height == 0) return
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        val s = max(v.width / bw, v.height / bh)
        val sh = bh * s
        val top = (focalFor(currentKey()) * sh - v.height / 2f).coerceIn(0f, max(0f, sh - v.height))
        val m = Matrix()
        m.postScale(s, s)
        m.postTranslate((v.width - bw * s) / 2f, -top)
        v.imageMatrix = m
    }

    private fun applyWallpaper(animate: Boolean) {
        val v = wallView ?: return
        val bmp = currentBackground()
        val change = { v.setImageBitmap(bmp); fitWallpaper() }
        if (animate && v.drawable != null) {
            v.animate().alpha(0f).setDuration(110).withEndAction {
                change()
                v.animate().alpha(1f).setDuration(200).start()
            }.start()
        } else {
            change()
        }
        updateDots()
    }

    private fun setBg(key: String) {
        prefs.edit().putString("bg_choice", key).apply()
        applyWallpaper(true)
        if (stripOpen) stripView?.let { fillStrip(it) }
    }

    private fun swipeBg(dir: Int) {
        val order = bgOrder()
        val cur = order.indexOf(currentKey()).let { if (it < 0) 0 else it }
        setBg(order[(cur + dir + order.size) % order.size])
    }

    private fun updateDots() {
        val d = dotsView ?: return
        d.removeAllViews()
        val cur = currentKey()
        for (k in bgOrder()) {
            val on = k == cur
            d.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(if (on) cyan else Color.parseColor("#73FFFFFF"))
                    cornerRadius = p(4).toFloat()
                }
            }, LinearLayout.LayoutParams(if (on) p(24) else p(7), p(7)).apply { setMargins(p(4), 0, p(4), 0) })
        }
    }

    private fun pickBgImage() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, REQ_BG)
        } catch (e: ActivityNotFoundException) {
            toast("La pantalla no tiene selector de imágenes")
        }
    }

    private var menuCollapseFn: (() -> Unit)? = null

    private fun closeOverlays() {
        if (qpOpen) qpToggle(false)
        menuCollapseFn?.invoke()
        setPanel(false)
        setStrip(false)
    }

    private fun setPanel(open: Boolean) {
        if (open) { setStrip(false); setVolumeCard(false) }
        panelOpen = open
        handler.removeCallbacks(panelAutoClose)
        val panel = panelView ?: return
        if (open) {
            fillPanel(panel)
            handler.postDelayed(panelAutoClose, 25000)
        }
        panel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) { panel.alpha = 0f; panel.animate().alpha(1f).setDuration(170).start() }
        dimView?.visibility = if (open) View.VISIBLE else View.GONE
        for (v in hideOnPanel) v.visibility = if (open) View.INVISIBLE else View.VISIBLE
    }

    private fun setStrip(open: Boolean) {
        if (open) { setPanel(false); setVolumeCard(false) }
        stripOpen = open
        handler.removeCallbacks(stripAutoClose)
        val strip = stripView ?: return
        if (open) {
            fillStrip(strip)
            handler.postDelayed(stripAutoClose, 15000)
        }
        strip.visibility = if (open) View.VISIBLE else View.GONE
        dotsView?.visibility = if (open) View.INVISIBLE else View.VISIBLE
        speedCard?.visibility = if (open || volCard?.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        arrowRing?.background = oval(if (open) Color.parseColor("#404DD0E1") else th.circle,
            if (open) th.fill else th.circleStroke)
    }

    private fun oval(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(max(1, p(1)), stroke)
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, p(r).toFloat())
        }
    }

    private fun thumb(key: String): Bitmap? = key.removePrefix("bg").toIntOrNull()?.let { assetBg(it) }

    private fun txt(text: String, sz: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        size(sz)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun fillStrip(strip: LinearLayout) {
        strip.removeAllViews()
        strip.addView(txt("FONDO DE PANTALLA · EL ELEGIDO SE MANTIENE AL REINICIAR", 11f, Color.parseColor("#A6FFFFFF")).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, p(9))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = prefs.getString("bg_choice", null) ?: "random"
        fun frame(sel: Boolean) = GradientDrawable().apply {
            cornerRadius = p(14).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(max(2, p(2)), if (sel) cyan else Color.TRANSPARENT)
        }
        for (i in 1..5) {
            val key = "bg$i"
            val box = FrameLayout(this).apply {
                setPadding(p(2), p(2), p(2), p(2))
                background = frame(cur == key)
                isClickable = true
                setOnClickListener { setBg(key) }
            }
            box.addView(ImageView(this).apply {
                setImageBitmap(thumb(key))
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = roundOutline(12f)
                clipToOutline = true
            }, FrameLayout.LayoutParams(p(98), p(58)))
            row.addView(box, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(p(4), 0, p(4), 0) })
        }
        fun modeTile(icon: Int, label: String, sel: Boolean, onClick: () -> Unit) {
            val box = FrameLayout(this).apply {
                setPadding(p(2), p(2), p(2), p(2))
                background = frame(sel)
                isClickable = true
                setOnClickListener { onClick() }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(Color.parseColor("#1FFFFFFF"), 12f)
            }
            inner.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
                LinearLayout.LayoutParams(p(22), p(22)))
            inner.addView(txt(label, 11f).apply { gravity = Gravity.CENTER })
            box.addView(inner, FrameLayout.LayoutParams(p(98), p(58)))
            row.addView(box, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(p(4), 0, p(4), 0) })
        }
        modeTile(R.drawable.ic_shuffle, "Aleatorio", cur == "random") {
            prefs.edit().putString("bg_choice", "random").apply()
            applyWallpaper(true)
            fillStrip(strip)
        }
        modeTile(R.drawable.ic_add, "Mi imagen", cur == "custom") { setStrip(false); pickBgImage() }
        strip.addView(row)
    }

    // ---- panel Estado

    private fun barOf(frac: Float, level: Int, h: Float): View {
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#21FFFFFF"), h)
        }
        val f = frac.coerceIn(0.02f, 1f)
        track.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(if (level == 0) Color.parseColor("#1FC58A") else lvlColor(level), if (level == 0) cyan else lvlColor(level))
            ).apply { cornerRadius = p(h).toFloat() }
        }, LinearLayout.LayoutParams(0, MATCH_PARENT, f))
        if (f < 1f) track.addView(View(this), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f - f))
        return track
    }

    private fun secLabel(t: String) = txt(t.uppercase(), 10.5f, Color.parseColor("#8CFFFFFF")).apply {
        letterSpacing = 0.18f
        setPadding(0, p(8), 0, p(4))
    }

    private fun fillPanel(panel: LinearLayout) {
        panel.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(txt("Estado del auto", 19f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(txt("×", 24f).apply {
            gravity = Gravity.CENTER
            background = oval(Color.parseColor("#1FFFFFFF"), Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { setPanel(false) }
        }, LinearLayout.LayoutParams(p(34), p(34)))
        panel.addView(head)

        // Aceite
        val oil = oilItem()
        panel.addView(secLabel("Aceite del motor"))
        if (oil == null) {
            panel.addView(txt("Toca para configurar", 14f, Color.parseColor("#99FFFFFF")).apply {
                setOnClickListener { setPanel(false); editOil() }
            })
        } else {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(txt(oil.value, 14f, lvlColor(oil.level), true))
            r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            if (oil.sub != null) r.addView(txt(oil.sub, 13f, Color.parseColor("#99FFFFFF")))
            panel.addView(r)
            panel.addView(barOf(oil.frac, oil.level, 3f), LinearLayout.LayoutParams(MATCH_PARENT, p(6)).apply { topMargin = p(4) })
            panel.setOnLongClickListener { false }
        }

        // Mantenimiento
        val items = maintItems()
        panel.addView(secLabel("Mantenimiento · ${u()} restantes"))
        if (items.isEmpty()) {
            panel.addView(txt("Toca para configurar", 14f, Color.parseColor("#99FFFFFF")).apply {
                setOnClickListener { setPanel(false); editMaint() }
            })
        } else {
            var i = 0
            while (i < items.size) {
                val twoCols = i + 1 < items.size || items.size % 2 == 0
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val cnt = if (twoCols) 2 else 1
                for (j in 0 until cnt) {
                    val it2 = items[i + j]
                    val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    cr.addView(txt(it2.label, 12.5f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    cr.addView(txt(it2.value, 12.5f, lvlColor(it2.level), true))
                    cell.addView(cr)
                    cell.addView(barOf(it2.frac, it2.level, 2f), LinearLayout.LayoutParams(MATCH_PARENT, p(4)).apply { topMargin = p(3) })
                    row.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        if (cnt == 2) { if (j == 0) rightMargin = p(8) else leftMargin = p(8) }
                    })
                }
                panel.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(5) })
                i += cnt
            }
        }

        // Documentos
        panel.addView(secLabel("Documentos"))
        val docs = docItems()
        if (docs.isEmpty()) {
            panel.addView(txt("Toca para configurar", 14f, Color.parseColor("#99FFFFFF")).apply {
                setOnClickListener { setPanel(false); editDocs() }
            })
        }
        for (d in docs) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(View(this).apply { background = oval(lvlColor(d.level), Color.TRANSPARENT) },
                LinearLayout.LayoutParams(p(9), p(9)).apply { rightMargin = p(9) })
            if (d.level == 2) {
                r.addView(txt("${d.label} · ${d.value}", 13.5f, red))
            } else {
                r.addView(txt(d.label, 13.5f))
                r.addView(txt("  · ${d.value}", 13.5f, Color.parseColor("#99FFFFFF")))
            }
            panel.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(4) })
        }

        // Restriccion + acceso a Mi auto
        val set = prefs.getBoolean("restr_set", false)
        val mask = prefs.getInt("restr_mask", 0)
        val idx = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        val blockedToday = set && (mask and (1 shl idx)) != 0
        val last = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(txt("RESTRICCIÓN VEHICULAR", 10.5f, Color.parseColor("#8CFFFFFF")).apply { letterSpacing = 0.12f })
        left.addView(txt(
            if (!set) "Configurar días" else if (blockedToday) "Hoy: NO circulas" else "Hoy puedes circular",
            12.5f, if (blockedToday) red else Color.WHITE
        ).apply {
            background = rounded(Color.parseColor("#1FFFFFFF"), 12f)
            setPadding(p(10), p(3), p(10), p(3))
            isClickable = true
            setOnClickListener { setPanel(false); editRestriction() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = p(5) })
        last.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        last.addView(txt("VER MI AUTO Y TALLER ›", 12.5f, cyan, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = p(18).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(max(1, p(1)), cyan)
            }
            setPadding(p(16), 0, p(16), 0)
            isClickable = true
            setOnClickListener { setPanel(false); page = Page.CAR; render() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, p(36)))
        panel.addView(last, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(10) })
    }

    // ---- barra inferior

    private fun overrideApp(key: String): AppInfo? = ps(key)?.let { pkg -> apps.firstOrNull { it.pkg == pkg } }

    private val navHints = listOf(
        "com.google.android.apps.maps", "net.osmand.plus", "net.osmand",
        "app.organicmaps", "com.here.app.maps", "com.waze"
    )

    private fun navApp(): AppInfo? {
        overrideApp("nav_pkg")?.let { return it }
        for (h in navHints) apps.firstOrNull { it.pkg == h }?.let { return it }
        return null
    }

    /** Menu de toque largo de un boton del dock: acciones propias + cambiar app + restaurar. */
    private fun dockMenu(title: String, key: String, chooseTitle: String, extra: List<Pair<String, () -> Unit>> = emptyList()) {
        val items = extra + listOf<Pair<String, () -> Unit>>(
            "Cambiar app" to { chooseApp(key, chooseTitle) },
            "Restaurar original" to { prefs.edit().remove(key).apply(); toast("Restaurado"); render() }
        )
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .show()
    }

    private fun isOnlineNow(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    private fun mapsMenu() {
        val nav = navApp()
        val items = arrayOf(
            if (nav != null) "Abrir ${nav.label}" else "Abrir mapa (elegir app)",
            "Mapas sin internet (guía)",
            "Elegir app de mapas",
            "Mapa propio (sin internet)"
        )
        AlertDialog.Builder(this)
            .setTitle("Mapas")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> if (nav != null) launch(nav.pkg) else chooseApp("nav_pkg", "Elige la app de mapas")
                    1 -> { page = Page.MAPS; render() }
                    3 -> startActivity(Intent(this, MapActivity::class.java))
                    else -> chooseApp("nav_pkg", "Elige la app de mapas")
                }
            }.show()
    }

    private fun dockIcon(
        res: Int, label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null, iconApp: AppInfo? = null
    ): LinearLayout {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(th.press, 16f))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        if (iconApp != null) {
            b.addView(ImageView(this).apply { setImageBitmap(iosIcon(iconApp, p(34))) }, LinearLayout.LayoutParams(p(34), p(34)))
        } else {
            b.addView(ImageView(this).apply { setImageResource(res); setColorFilter(modeTint()) }, LinearLayout.LayoutParams(p(30), p(30)))
        }
        b.addView(txt(iconApp?.label ?: label, 11.5f, th.fg).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(p(2), p(6), p(2), 0)
        })
        b.pressFx()
        return b
    }

    private fun ctrlBtn(res: Int, size: Float, big: Boolean, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = if (big) oval(if (mode().id == "ios") th.circle else (cyan and 0x00FFFFFF) or 0x44000000, if (mode().id == "ios") th.circleStroke else (cyan and 0x00FFFFFF) or 0xAA000000.toInt()) else
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), oval(th.press, Color.TRANSPARENT))
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply { setImageResource(res); setColorFilter(th.fg) }
        f.addView(iv, FrameLayout.LayoutParams(p(26), p(26), Gravity.CENTER))
        if (big) playIcon = iv
        f.pressFx()
        return f
    }

    private fun buildDock(): View {
        val dock = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(th.dockTop, th.dockBot))
        }
        dock.addView(View(this).apply { setBackgroundColor(th.dockLine) },
            FrameLayout.LayoutParams(MATCH_PARENT, max(1, p(1))))
        dock.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, th.fill, Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(p(300), max(2, p(2))).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(30), 0, p(22), 0)
        }
        dock.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // musica (toque: abrir la app elegida; toque largo: elegir la app de musica)
        val np = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { openMusic() }
            setOnLongClickListener { dockMenu("App de música", "music_pkg", "Elige la app de música"); true }
        }
        np.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_music); setColorFilter(th.fill)
            setPadding(p(8), p(8), p(8), p(8))
            isClickable = true
            setOnClickListener { setVolumeCard(volCard?.visibility != View.VISIBLE) }
        }, LinearLayout.LayoutParams(p(46), p(46)).apply { rightMargin = p(4) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mediaTitle = txt("", 15f, th.fg, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        mediaSub = txt("", 12f, th.fgSoft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(mediaTitle); texts.addView(mediaSub)
        val prog = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        mediaBar = ThinBar(this).apply { trackC = th.track; fillC = th.fill }
        mediaTime = txt("", 10.5f, th.fgSoft).apply { maxLines = 1 }
        prog.addView(mediaBar, LinearLayout.LayoutParams(0, p(4), 1f))
        prog.addView(mediaTime, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(8) })
        mediaRow = prog
        texts.addView(prog, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(5) })
        np.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(np, LinearLayout.LayoutParams(p(328), WRAP_CONTENT))

        // controles
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ctrl.addView(ctrlBtn(R.drawable.ic_prev, 26f, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(p(48), p(48)))
        ctrl.addView(ctrlBtn(R.drawable.ic_play, 26f, true) { dockPlay() },
            LinearLayout.LayoutParams(p(56), p(56)).apply { setMargins(p(6), 0, p(6), 0) })
        ctrl.addView(ctrlBtn(R.drawable.ic_next, 26f, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(p(48), p(48)))
        bar.addView(ctrl, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(6) })

        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(View(this).apply { setBackgroundColor(th.divider) },
            LinearLayout.LayoutParams(max(1, p(1)), p(46)).apply { setMargins(0, 0, p(14), 0) })

        val w = LinearLayout.LayoutParams(p(84), MATCH_PARENT)
        

        bar.addView(dockIcon(R.drawable.ic_apps, "Apps", {
            val a = overrideApp("apps_pkg")
            if (a != null) launch(a.pkg) else { page = Page.ALL; render() }
        }, {
            dockMenu("Botón Apps", "apps_pkg", "Elige la app para este botón",
                listOf("Abrir el menú de Apps" to { page = Page.ALL; render() }))
        }, overrideApp("apps_pkg")), LinearLayout.LayoutParams(p(84), MATCH_PARENT))

        // CarPlay destacado
        val cp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                val a = findApp("carplay_pkg", carPlayHints)
                if (a != null) launch(a.pkg) else chooseApp("carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)")
            }
            setOnLongClickListener {
                dockMenu("Botón CarPlay", "carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)",
                    listOf("Asistente CarPlay" to { page = Page.CARPLAY; render() }))
                true
            }
        }
        val ring = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#27C7E8"), Color.parseColor("#1A6DFF"))).apply { shape = GradientDrawable.OVAL }
        }
        ring.addView(ImageView(this).apply { setImageResource(R.drawable.ic_phone); setColorFilter(Color.WHITE) },
            FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        cp.addView(ring, LinearLayout.LayoutParams(p(62), p(62)).apply { topMargin = p(-4) })
        cp.addView(txt("CarPlay", 11.5f, th.fg).apply { gravity = Gravity.CENTER; setPadding(0, p(3), 0, 0) })
        bar.addView(cp, LinearLayout.LayoutParams(p(88), MATCH_PARENT))

        bar.addView(dockIcon(R.drawable.ic_settings, "Ajustes", {
            val a = overrideApp("settings_pkg")
            if (a != null) launch(a.pkg) else openSettings(Settings.ACTION_SETTINGS)
        }, {
            dockMenu("Botón Ajustes", "settings_pkg", "Elige la app para este botón",
                listOf("Diagnóstico y ajustes del launcher" to { page = Page.DIAG; render() }))
        }, overrideApp("settings_pkg")), LinearLayout.LayoutParams(p(84), MATCH_PARENT))

        // flecha de fondos
        val arrow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { setStrip(!stripOpen) }
        }
        val aring = FrameLayout(this).apply { background = oval(th.circle, th.circleStroke) }
        aring.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron_up); setColorFilter(th.fg) },
            FrameLayout.LayoutParams(p(28), p(28), Gravity.CENTER))
        arrowRing = aring
        arrow.addView(aring, LinearLayout.LayoutParams(p(46), p(46)).apply { topMargin = p(-4) })
        arrow.addView(txt("Fondos", 11.5f, th.fg).apply { gravity = Gravity.CENTER; setPadding(0, p(5), 0, 0) })
        bar.addView(arrow, LinearLayout.LayoutParams(p(68), MATCH_PARENT).apply { leftMargin = p(8) })
        return dock
    }

    // ---- inicio

    private fun buildHome(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050A14"))
        }
        val area = FrameLayout(this)
        root.addView(area, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        homeArea = area

        // foto de fondo (a pantalla completa, sin tapar el auto)
        val wall = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWallpaper() }
        }
        wallView = wall
        area.addView(wall, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        applyWallpaper(false)
        // de noche el fondo se atenua para no encandilar
        val nightFx = !th.day && prefs.getBoolean("night_photo", true)
        wall.colorFilter = if (th.day) null else if (nightFx) ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.42f, 0.5f, 0.72f, 1f) })
            else ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.8f, 0.8f, 0.86f, 1f) })
        val glow = Luces.GlowView(this, { wall.imageMatrix }, { currentKey() })
        glowView = glow
        area.addView(glow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        glow.setOn(Luces.effective(this, prefs) ?: false, false)
        area.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#A0030814"), Color.parseColor("#00030814")))
        }, FrameLayout.LayoutParams(MATCH_PARENT, p(200), Gravity.TOP))

        // puntos indicadores del fondo
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        dotsView = dots
        area.addView(dots, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = p(96)
        })
        updateDots()

        val vc = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
            setPadding(p(12), p(10), p(18), p(10))
            background = GradientDrawable().apply {
                cornerRadius = p(28).toFloat()
                setColor(th.glass)
                setStroke(max(1, p(1)), th.glassStroke)
            }
        }
        fun vbtn(label: String, delta: Int) = txt(label, 28f, th.fg, true).apply {
            gravity = Gravity.CENTER
            background = oval(th.circle, th.circleStroke)
            isClickable = true
            setOnClickListener { stepVolume(delta) }
        }
        vc.addView(vbtn("−", -1), LinearLayout.LayoutParams(p(54), p(54)))
        val vb2 = TouchBar(this).apply {
            live = true; trackC = th.track; fillC = th.fill
            onSeek = { f -> setVolumeFrac(f) }
        }
        vc.addView(vb2, LinearLayout.LayoutParams(p(260), p(42)).apply { setMargins(p(12), 0, p(12), 0) })
        vc.addView(vbtn("+", 1), LinearLayout.LayoutParams(p(54), p(54)))
        val vp = txt("", 14f, th.fgSoft).apply { setPadding(p(12), 0, 0, 0); minWidth = p(56) }
        vc.addView(vp)
        area.addView(vc, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(p(26), 0, 0, p(104))
        })
        volCard = vc; volBar2 = vb2; volPct2 = vp

        // Menú lateral compacto: cuadrícula 2 x 4, páginas
        val (count, worst) = attention()
        val capsule = FrameLayout(this).apply { clipChildren = false }
        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(12), p(12), p(12), p(6))
            background = GradientDrawable().apply { cornerRadius = p(26).toFloat(); setColor((th.glass and 0x00FFFFFF) or 0xF8000000.toInt()); setStroke(max(1, p(1)), th.glassStroke) }
            visibility = View.GONE; isClickable = true
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        menuPanel.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        menuPanel.addView(dotsRow, LinearLayout.LayoutParams(MATCH_PARENT, p(26)))
        val toggleV: View? = null
        val collapse = Runnable { }
        fun showMenu(show: Boolean) {
            menuPanel.animate().cancel()
            if (show) {
                widgetLayer?.hideExcept(setOf("dock", "player"))
                toggleV?.visibility = View.GONE
                menuPanel.alpha = 0f; menuPanel.translationY = p(14).toFloat(); menuPanel.visibility = View.VISIBLE
                menuPanel.animate().alpha(1f).translationY(0f).setDuration(160).start()
            } else if (menuPanel.visibility == View.VISIBLE) {
                widgetLayer?.showAll()
                menuPanel.animate().alpha(0f).setDuration(120).withEndAction {
                    menuPanel.visibility = View.GONE
                    toggleV?.let { it.alpha = 0f; it.visibility = View.VISIBLE; it.animate().alpha(1f).setDuration(140).start() }
                }.start()
            }
        }
        menuCollapseFn = { showMenu(false) }
        fun wrapClick(a: () -> Unit): () -> Unit = { handler.removeCallbacks(collapse); showMenu(false); a() }
        val keys = menuKeys()
        val perPage = 8
        val pageCount = ((keys.size + perPage - 1) / perPage).coerceAtLeast(1)
        var menuPage = 0
        fun mTile(key: String): View {
            val ic = when (key) {
                "estado" -> R.drawable.ic_gauge; "mapas" -> R.drawable.ic_place; "taller" -> R.drawable.ic_phone
                "musica" -> R.drawable.ic_music; "viaje" -> R.drawable.ic_car; "calendario" -> R.drawable.ic_calendar
                "carplay" -> R.drawable.ic_car; "radio" -> R.drawable.ic_radio; "reloj" -> R.drawable.ic_clock
                "manejo" -> R.drawable.ic_gauge; "gasolina" -> R.drawable.ic_gauge; "mapa" -> R.drawable.ic_place
                "noche" -> R.drawable.ic_clock; "pantalla" -> R.drawable.ic_palette; else -> R.drawable.ic_apps
            }
            val act: () -> Unit = when (key) {
                "estado" -> { { setPanel(!panelOpen) } }
                "mapas" -> { { startActivity(Intent(this, MapActivity::class.java)) } }
                "taller" -> { { page = Page.CAR; render() } }
                "musica" -> { { openMusic() } }
                "viaje" -> { { page = Page.TRIP; render() } }
                "calendario" -> { { openCalendar() } }
                "carplay" -> { { val a = findApp("carplay_pkg", carPlayHints); if (a != null) launch(a.pkg) else chooseApp("carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)") } }
                "radio" -> { { val r = findApp("radio_pkg", radioHints); if (r != null) launch(r.pkg) else chooseApp("radio_pkg", "Elige la app de la radio") } }
                "reloj" -> { { setBig(!bigActive) } }
                "manejo" -> { { setDrive(!driveActive) } }
                "gasolina" -> { { startActivity(Intent(this, GasActivity::class.java)) } }
                "mapa" -> { { startActivity(Intent(this, MapActivity::class.java)) } }
                "noche" -> { { nightSettings() } }
                "pantalla" -> { { widgetLayer?.enterEdit() } }
                else -> { { page = Page.ALL; render() } }
            }
            val active = (key == "reloj" && bigActive) || (key == "manejo" && driveActive)
            val t = FrameLayout(this).apply {
                isClickable = true; isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = p(18).toFloat(); setColor(if (active) (cyan and 0x00FFFFFF) or 0x33000000 else th.circle)
                    setStroke(max(1, p(1)), if (active) cyan else th.circleStroke)
                }
                setOnClickListener { wrapClick(act)() }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(ImageView(this).apply { setImageResource(ic); setColorFilter(if (active) cyan else th.fg) }, LinearLayout.LayoutParams(p(26), p(26)))
            col.addView(txt(menuLabels()[key] ?: key, 12f, th.fg).apply { gravity = Gravity.CENTER; setSingleLine(true); setPadding(0, p(4), 0, 0) })
            t.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            if (key == "estado" && count > 0) t.addView(txt(count.toString(), 11f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = oval(if (worst == 2) red else amber, Color.TRANSPARENT)
            }, FrameLayout.LayoutParams(p(20), p(20), Gravity.TOP or Gravity.END).apply { setMargins(0, p(5), p(7), 0) })
            t.pressFx()
            return t
        }
        fun fillMenu() {
            grid.removeAllViews(); dotsRow.removeAllViews()
            val slice = keys.drop(menuPage * perPage).take(perPage)
            slice.chunked(2).forEachIndexed { i, pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { j, k ->
                    row.addView(mTile(k), LinearLayout.LayoutParams(0, p(76), 1f).apply { if (j == 0) rightMargin = p(4) else leftMargin = p(4) })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, p(76), 1f).apply { leftMargin = p(4) })
                grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = p(8) })
            }
            if (pageCount > 1) for (i in 0 until pageCount) {
                dotsRow.addView(View(this).apply {
                    background = oval(if (i == menuPage) cyan else th.divider, Color.TRANSPARENT)
                    setOnClickListener { menuPage = i; fillMenu() }
                }, LinearLayout.LayoutParams(p(10), p(10)).apply { setMargins(p(6), p(8), p(6), p(8)) })
            }
        }
        fillMenu()
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(e2.x - e1.x) < p(60) || pageCount < 2) return false
                menuPage = if (e2.x < e1.x) (menuPage + 1).coerceAtMost(pageCount - 1) else (menuPage - 1).coerceAtLeast(0)
                fillMenu(); return true
            }
        })
        menuPanel.setOnTouchListener { v, ev -> swipe.onTouchEvent(ev); false }
        capsule.addView(menuPanel, FrameLayout.LayoutParams(p(340), WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        menuToggleFn = { menuPage = 0; fillMenu(); showMenu(true) }
        menuLongFn = { menuConfig() }
        area.clipChildren = false
        area.addView(capsule, FrameLayout.LayoutParams(p(340), WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, p(18), p(102))
        })
        chipStyleBig = { fillMenu() }; chipStyleDrive = { fillMenu() }

        // widgets del inicio (mover, agrandar, agregar: toque largo)
        val layer = WidgetLayer(this, scale, prefs, cyan, { sp -> buildWidget(sp) }, { missing, add -> pickWidgetDialog(missing, add) }) { on ->
            if (on) { closeOverlays(); setPanel(false) }
        }
        if (!prefs.getBoolean("comb_added", false)) { prefs.edit().putBoolean("comb_added", true).apply(); if (!layer.has("comb")) layer.add("comb") }
        layer.alpha = if (nightFx && prefs.getBoolean("night_dim", true)) 0.88f else 1f
        widgetLayer = layer
        area.addView(layer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // capa oscura + panel Estado
        val dim = View(this).apply {
            setBackgroundColor(Color.parseColor("#66020610"))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeOverlays() }
        }
        dimView = dim
        area.addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(20), p(14), p(20), p(16))
            background = GradientDrawable().apply {
                cornerRadius = p(26).toFloat()
                setColor(Color.parseColor("#C7070E1E"))
                setStroke(max(1, p(1)), Color.parseColor("#38A5CBFF"))
            }
            visibility = View.GONE
            isClickable = true
        }
        panelView = panel
        area.addView(panel, FrameLayout.LayoutParams(p(404), WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, p(18), p(102))
        })

        // selector de fondos
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(p(16), p(12), p(16), p(12))
            background = GradientDrawable().apply {
                cornerRadius = p(24).toFloat()
                setColor(Color.parseColor("#CC070E1E"))
                setStroke(max(1, p(1)), Color.parseColor("#40A5CBFF"))
            }
            visibility = View.GONE
            isClickable = true
        }
        stripView = strip
        area.addView(strip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = p(102)
        })

        tapFx = TapFx(this).also { area.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        hideOnPanel = listOf(layer, capsule, dots)
        panelOpen = false
        stripOpen = false

        // deslizar para cambiar de fondo / tocar fuera para cerrar
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > p(90) && abs(dx) > abs(dy) * 1.5f && abs(vx) > 400f) {
                    swipeBg(if (dx < 0) 1 else -1)
                    return true
                }
                if (dy < -p(90) && abs(dy) > abs(dx) * 1.5f && abs(vy) > 400f) {
                    when (mode().id) {
                        "exec" -> setPanel(true)
                        "pink" -> { openMusic() }
                        "trip" -> openCalendar()
                        "sport" -> { page = Page.TRIP; render() }
                        else -> { page = Page.ALL; render() }
                    }
                    return true
                }
                return false
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                closeOverlays()
                tapFx?.burst(e.x, e.y, mode())
                return true
            }
            override fun onLongPress(e: MotionEvent) { widgetLayer?.enterEdit() }
        })
        area.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
        val outer = FrameLayout(this)
        outer.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        outer.addView(buildDriveLayer(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        outer.addView(buildBigClock(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return outer
    }

    // ============================================================== widgets del inicio
    private var homeArea: FrameLayout? = null
    private var widgetLayer: WidgetLayer? = null
    private var glowView: Luces.GlowView? = null
    private var mediaArt: ImageView? = null
    private var mediaArtBmp: Bitmap? = null
    private var miniMap: MiniMapView? = null
    private var liveMap: LiveMap? = null
    private var liveAt = 0L
    private var menuToggleFn: (() -> Unit)? = null
    private var menuLongFn: (() -> Unit)? = null
    private val lightsObs = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { lightsAt = 0L; try { checkLights() } catch (e: Throwable) { } }
    }
    private var lightsObsOn = false
    private fun watchLights(on: Boolean) {
        try {
            if (on && !lightsObsOn) {
                for (u in listOf(Settings.System.CONTENT_URI, Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI)) contentResolver.registerContentObserver(u, true, lightsObs)
                lightsObsOn = true
            } else if (!on && lightsObsOn) { contentResolver.unregisterContentObserver(lightsObs); lightsObsOn = false }
        } catch (e: Throwable) { }
    }
    private var lightsShown: Boolean? = null
    private var lightsAt = 0L
    private var alertView: View? = null
    private var lastMoveAt = 0L
    private var stopAt = 0L
    private var trailAt = 0L
    private val PARK_AFTER_MS = 30 * 60_000L

    private fun wGlass(radius: Float = 20f) = GradientDrawable().apply {
        cornerRadius = p(radius).toFloat(); setColor(th.glass); setStroke(max(1, p(1)), th.glassStroke)
    }

    private fun wTitle(t: String, color: Int = th.fgSoft) = txt(t, 10.5f, color).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    private fun lightsOn(): Boolean = Luces.state(this, prefs) ?: true

    private fun buildWidget(s: WSpec): View = when (s.id) {
        "nombre" -> wName(s); "reloj" -> wClock(s); "vel" -> wSpeed(s); "modos" -> wStyle(s); "luces" -> wLights(s)
        "park" -> wPark(s); "mapa" -> wMap(s); "acc" -> wQuick(s); "aceite" -> wOil(s); "cons" -> wCons(s)
        "player" -> wPlayer(s); "dock" -> wDock(s); "comb" -> wFuel(s)
        "clima" -> wInfo("Clima", tempText() ?: "—", th.fg) { openSettings(Settings.ACTION_SETTINGS) }
        "viaje" -> wInfo("Viaje", String.format(Locale.US, "%.1f %s", TripService.tripM / metersPerUnit(), u()).replace('.', ','), th.fg) { page = Page.TRIP; render() }
        "gas" -> { val k = GasActivity.kmPerL(prefs); wInfo("Gasolina", if (k > 0) String.format(Locale.US, "%.1f km/L", k).replace('.', ',') else "Registrar", green) { startActivity(Intent(this, GasActivity::class.java)) } }
        "cal" -> { val n = nextHolidayText(); wInfo("Próximo feriado", n ?: "—", th.fg) { openCalendar() } }
        "asist" -> wInfo("Asistente", "Hey ${wakeName()}", cyan) { assistantDialog() }
        "estado" -> { val (c, w) = attention(); wInfo("Estado del auto", if (c == 0) "Todo bien" else "$c avisos", if (c == 0) green else if (w == 2) red else amber) { setPanel(true) } }
        else -> View(this)
    }

    private fun nextHolidayText(): String? = try {
        val now = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val y = Calendar.getInstance().get(Calendar.YEAR)
        val e = (FeriadosBo.forYear(y) + FeriadosBo.forYear(y + 1)).filter { it.isHoliday() && it.millis() >= now }.minByOrNull { it.millis() }
        e?.let { "${it.name} · ${it.d}/${it.m}" }
    } catch (x: Exception) { null }

    private fun wInfo(title: String, value: String, color: Int, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(14), p(6), p(12), p(6)); background = wGlass(16f); isClickable = true; setOnClickListener { onClick() }
        }
        c.addView(wTitle(title))
        c.addView(txt(value, 18f, color, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        c.pressFx()
        return c
    }

    // ---- nombre del auto + logo debajo
    private fun wName(s: WSpec): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true; setOnClickListener { page = Page.CAR; render() }
        }
        val shadow = Color.parseColor("#99000000")
        val titleSp = (s.w / 16f).coerceIn(11f, 22f)
        c.addView(txt(CarCatalog.title(prefs), titleSp, Color.WHITE, true).apply {
            letterSpacing = 0.4f; setSingleLine(true); ellipsize = null; gravity = Gravity.CENTER; setShadowLayer(10f, 0f, 2f, shadow)
            // el espaciado entre letras también se añade tras la última: se compensa a la izquierda para que quede centrado de verdad
            setPadding(Math.round(0.4f * textSize), 0, 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        c.addView(View(this).apply { background = GradientDrawable().apply { setColor(cyan); cornerRadius = p(2).toFloat() } },
            LinearLayout.LayoutParams(p(44), max(2, p(2))).apply { setMargins(0, p(6), 0, p(5)) })
        val odo = odoKm()
        c.addView(txt(if (odo != null) "${fmtKm(odo)} ${u()}" + (if (TripService.running) " · GPS" else "") else "toca: poner ${u()}", 12.5f, Color.parseColor("#E6FFFFFF")).apply {
            letterSpacing = 0.06f; setSingleLine(true); ellipsize = null; gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 1f, shadow)
            setPadding(Math.round(0.06f * textSize), 0, 0, 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val room = (s.h - 64f).coerceAtMost(72f)
        val logoBmp = CarCatalog.logo(this, prefs.getString("car_brand", null))
        if (room >= 30f && logoBmp != null) c.addView(ImageView(this).apply {
            setImageBitmap(logoBmp); scaleType = ImageView.ScaleType.FIT_CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }, LinearLayout.LayoutParams(p(room), p(room)).apply { topMargin = p(4) })
        return c
    }

    // ---- reloj
    private fun wClock(s: WSpec): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(p(16), p(6), p(16), p(8)); background = GradientDrawable().apply { cornerRadius = p(22).toFloat(); setColor(Color.parseColor("#B3030814")) }
            isClickable = true; setOnClickListener { openCalendar() }
        }
        val showDate = s.h >= 70f
        clockView = TextView(this).apply {
            size(min((s.w / 3.3f).coerceIn(26f, 64f), s.h * if (showDate) 0.6f else 0.8f)); setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); letterSpacing = -0.03f; includeFontPadding = false
            setShadowLayer(10f, 0f, 2f, Color.parseColor("#99000000")); maxLines = 1
        }
        dateView = TextView(this).apply {
            size(if (s.w >= 170f) 14f else 11f); setTextColor(Color.WHITE); setShadowLayer(8f, 0f, 2f, Color.parseColor("#99000000"))
            setPadding(0, p(4), 0, 0); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = if (showDate) View.VISIBLE else View.GONE
        }
        clockView!!.gravity = Gravity.CENTER; dateView!!.gravity = Gravity.CENTER
        c.addView(clockView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); c.addView(dateView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        clockKey = -1L
        handler.post { updateClock() }
        return c
    }

    // ---- velocimetro
    private fun wSpeed(s: WSpec): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(p(16), p(6), p(16), p(8)); background = wGlass(20f); isClickable = true
            setOnClickListener { page = Page.TRIP; render() }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
        speedView = TextView(this).apply {
            size((s.h * 0.5f).coerceIn(24f, 90f)); setTextColor(th.fg); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false; text = "--"
        }
        speedUnitView = txt("km/h", 13f, th.fgSoft).apply { setPadding(p(6), 0, 0, p(3)) }
        top.addView(speedView); top.addView(speedUnitView)
        top.addView(txt("GPS", 10.5f, th.fgSoft).apply { letterSpacing = 0.12f; setPadding(p(14), 0, 0, p(3)) })
        speedBar = ThinBar(this).apply { trackC = th.track; fillC = th.fill }
        c.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        c.addView(speedBar, LinearLayout.LayoutParams(MATCH_PARENT, p(5)).apply { topMargin = p(6) })
        speedCard = c
        handler.post { updateSpeed() }
        return c
    }

    // ---- estilo ECO / NORMAL / SPORT (solo visual)
    private fun wStyle(s: WSpec): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cur = prefs.getString("drive_style", "NORMAL") ?: "NORMAL"
        for ((ix, k) in listOf("ECO", "NORMAL", "SPORT").withIndex()) {
            val on = k == cur
            row.addView(txt(k, 11.5f, if (on) Color.parseColor("#021018") else Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(p(10), p(6), p(10), p(6))
                background = GradientDrawable().apply { cornerRadius = p(15).toFloat(); setColor(if (on) cyan else Color.parseColor("#CC0A1224")); if (!on) setStroke(max(1, p(1)), 0x22FFFFFF) }
                isClickable = true
                setOnClickListener { prefs.edit().putString("drive_style", k).apply(); widgetLayer?.rebuild("modos") }
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { rightMargin = if (ix < 2) p(5) else 0 })
        }
        return row
    }

    // ---- luces / modo noche
    private fun wLights(s: WSpec): View {
        val st = Luces.effective(this, prefs)
        lightsShown = st
        val night = !th.day
        val (icon, label, col) = when {
            st == true -> Triple("💡", "Luces encendidas", Color.parseColor("#FFE9A8"))
            st == false -> Triple("⚠", "Luces apagadas", Color.parseColor("#FFB347"))
            night -> Triple("🌙", "Modo noche", Color.parseColor("#CFE0F5"))
            else -> Triple("☀", "Modo día", th.fg)
        }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(p(12), p(4), p(12), p(4)); background = wGlass(18f); isClickable = true
            // toque: dices cómo están las luces AHORA; si la señal de la radio dice lo contrario, se corrige sola; mantener pulsado = ajustes
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("¿Cómo están las luces ahora?")
                    .setItems(arrayOf("💡 Encendidas", "Apagadas", "Ajustes y detección de luces…", "Cómo conectar las luces de mi auto")) { _, w ->
                        if (w == 2) { nightSettings(); return@setItems }
                        if (w == 3) { lightsHelp(); return@setItems }
                        val on = w == 0
                        val fixed = Luces.learn(this@MainActivity, prefs, on)
                        if (!Luces.hasSignal(this@MainActivity, prefs)) Luces.setManual(prefs, on)
                        lightsShown = null; widgetLayer?.rebuild("luces"); glowView?.setOn(Luces.effective(this@MainActivity, prefs) ?: on)
                        toast(if (fixed) "Listo: ajusté la señal de luces" else if (on) "Luces encendidas" else "Luces apagadas")
                    }.setNegativeButton("Cancelar", null).show()
            }
            setOnLongClickListener { nightSettings(); true }
        }
        c.addView(txt(icon, 14f, col)); c.addView(txt(label, 12.5f, col, true).apply { setPadding(p(7), 0, 0, 0); maxLines = 1 })
        c.pressFx()
        return c
    }

    // ---- donde estacione
    private fun parkAgo(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60000L).coerceAtLeast(0)
        return when { m < 2 -> "ahora"; m < 60 -> "hace $m min"; m < 1440 -> "hace ${m / 60} h"; else -> "hace ${m / 1440} d" }
    }

    private fun wPark(s: WSpec): View {
        val lat = prefs.getString("park_lat", null)?.toDoubleOrNull(); val lon = prefs.getString("park_lon", null)?.toDoubleOrNull()
        val t = prefs.getLong("park_t", 0L)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(12), p(4), p(10), p(4)); background = wGlass(20f); isClickable = true
            setOnClickListener { parkDialog() }
        }
        c.addView(ImageView(this).apply { setImageResource(R.drawable.ic_place); setColorFilter(Color.parseColor("#FFB347")) }, LinearLayout.LayoutParams(p(24), p(24)).apply { rightMargin = p(8) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(wTitle("Dónde estacioné"))
        col.addView(txt(if (lat != null && lon != null) (prefs.getString("park_name", null)?.takeIf { it.isNotBlank() } ?: "Mi auto") + " · " + parkAgo(t) else "Aún sin ubicación guardada", 13.5f, th.fg, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        c.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (lat != null && lon != null) c.addView(txt("Ir ▸", 13f, Color.parseColor("#021018"), true).apply {
            gravity = Gravity.CENTER; setPadding(p(14), p(6), p(14), p(6)); background = GradientDrawable().apply { cornerRadius = p(14).toFloat(); setColor(cyan) }
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java).putExtra("dest_q", String.format(Locale.US, "%.6f,%.6f", lat, lon)).putExtra("dest_name", "Mi auto")) }
        })
        return c
    }

    private fun parkDialog() {
        val has = prefs.getString("park_lat", null) != null
        val items = arrayListOf("Guardar mi ubicación actual")
        if (has) items.add("Borrar la ubicación guardada")
        AlertDialog.Builder(this).setTitle("Dónde estacioné")
            .setItems(items.toTypedArray()) { _, w ->
                if (w == 0) saveParking(true) else { prefs.edit().remove("park_lat").remove("park_lon").remove("park_t").remove("park_name").apply(); widgetLayer?.rebuild("park") }
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun saveParking(manual: Boolean) {
        val la = TripService.lat; val lo = TripService.lon
        if (la.isNaN() || lo.isNaN()) { if (manual) toast("Todavía no hay señal de GPS"); return }
        saveParkingAt(la, lo, manual)
    }

    private fun saveParkingAt(la: Double, lo: Double, manual: Boolean) {
        prefs.edit().putString("park_lat", String.format(Locale.US, "%.6f", la)).putString("park_lon", String.format(Locale.US, "%.6f", lo))
            .putLong("park_t", System.currentTimeMillis()).remove("park_name").apply()
        widgetLayer?.rebuild("park")
        Thread {
            val nm = try { PlaceIndex.nameAt(this, la, lo) } catch (e: Throwable) { "" }
            if (nm.isNotBlank()) handler.post { prefs.edit().putString("park_name", nm).apply(); widgetLayer?.rebuild("park") }
        }.start()
        if (manual) toast("Ubicación guardada")
    }

    /** El «dónde estacioné» automático lo hace el servicio de GPS (BgWatch) aunque estés en otra app; aquí solo se arma el recorrido del mini mapa. */
    private fun trackParking() {
        val fresh = TripService.running && TripService.lastFixMs != 0L && System.currentTimeMillis() - TripService.lastFixMs < 8000L
        if (!fresh) return
        val now = System.currentTimeMillis()
        if (now - trailAt > 3000L && !TripService.lat.isNaN()) {
            trailAt = now
            synchronized(HomeTrail.pts) { HomeTrail.pts.add(doubleArrayOf(TripService.lat, TripService.lon)); if (HomeTrail.pts.size > 160) HomeTrail.pts.removeAt(0) }
            miniMap?.let { it.invalidate() }
        }
    }

    // ---- mapa en vivo (recorrido + datos del viaje)
    private fun wMap(s: WSpec): View {
        val data = {
            val tm = TripService.tripMoveMs / 3_600_000.0
            val km = TripService.tripM / 1000.0
            MiniMapView.Data(synchronized(HomeTrail.pts) { HomeTrail.pts.toList() }, TripService.bearing, km, TripService.tripMoveMs, if (tm > 0.001) km / tm else 0.0, TripService.tripMaxKmh.toDouble(), MapData.ready(this))
        }
        val corner = p(20).toFloat()
        val ov = MiniMapView(this, p(1).toFloat().coerceAtLeast(1f), cyan, th.fg).apply { provider = data }
        miniMap = ov
        // con el mapa descargado: calles reales; si no, el esquema con el recorrido
        if (MapData.ready(this)) {
            val lm = LiveMap(this, scale, cyan, !th.day, corner)
            if (lm.ok) {
                liveMap = lm
                ov.overlayOnly = true
                val box = FrameLayout(this).apply {
                    background = GradientDrawable().apply { cornerRadius = corner; setColor(Color.parseColor("#F00A1426")); setStroke(max(1, p(1)), th.glassStroke) }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, corner) } }
                    isClickable = true; setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java)) }
                }
                box.addView(lm, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                box.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                handler.post { lm.update(TripService.lat, TripService.lon, TripService.bearing, synchronized(HomeTrail.pts) { HomeTrail.pts.toList() }) }
                return box
            }
            lm.destroy()
        }
        ov.background = GradientDrawable().apply { cornerRadius = corner; setColor(Color.parseColor("#F00A1426")); setStroke(max(1, p(1)), th.glassStroke) }
        ov.clipToOutline = true
        ov.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, corner) } }
        ov.isClickable = true
        ov.setOnClickListener { startActivity(Intent(this, MapActivity::class.java)) }
        return ov
    }

    // ---- accesos rapidos
    private fun wQuick(s: WSpec): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(8), p(6), p(8), p(6)); background = wGlass(20f) }
        fun tl(icon: String, label: String, col: Int, go: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            background = GradientDrawable().apply { cornerRadius = p(14).toFloat(); setColor(th.circle); setStroke(max(1, p(1)), th.circleStroke) }
            setOnClickListener { go() }
            addView(txt(icon, 20f, col)); addView(txt(label, 11.5f, th.fg).apply { maxLines = 1; setPadding(0, p(2), 0, 0) })
            pressFx()
        }
        row.addView(tl("⛽", "Gasolinera", Color.parseColor("#FFB347")) { startActivity(Intent(this, MapActivity::class.java).putExtra("fuel", true).putExtra("silent", true)) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { rightMargin = p(6) })
        row.addView(tl("♫", "Música", cyan) { openMusic() }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { rightMargin = p(6) })
        row.addView(tl("➤", "Mapas", Color.parseColor("#7DD3A8")) { startActivity(Intent(this, MapActivity::class.java)) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        return row
    }

    // ---- aceite y consumo
    private fun wOil(s: WSpec): View {
        val it = oilItem()
        return wInfo("Cambio de aceite", it?.value ?: "Configurar", if (it != null) lvlColor(it.level) else th.fg) { setPanel(true) }
    }

    private fun wCons(s: WSpec): View {
        val k = GasActivity.kmPerL(prefs)
        return wInfo("Consumo", if (k > 0) String.format(Locale.US, "%.1f km/L", k).replace('.', ',') else "Registrar", green) { startActivity(Intent(this, GasActivity::class.java)) }
    }

    // ---- reproductor
    private fun roundArt(sz: Int, r: Float) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, p(r).toFloat()) } }
        setBackgroundColor(Color.parseColor("#33FFFFFF"))
    }

    private fun wPlayer(s: WSpec): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(12), p(8), p(14), p(8)); background = wGlass(26f)
        }
        val cv = (s.h - 18f).coerceIn(34f, 120f)
        val art = roundArt(p(cv), 14f)
        art.setImageDrawable(null); mediaArt = art; mediaArtBmp = null
        c.addView(art, LinearLayout.LayoutParams(p(cv), p(cv)).apply { rightMargin = p(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true
            setOnClickListener { openMusic() }
            setOnLongClickListener { dockMenu("App de música", "music_pkg", "Elige la app de música"); true } }
        texts.addView(txt("OPENCAR MUSIC", 10.5f, cyan, true).apply { letterSpacing = 0.12f; maxLines = 1 })
        mediaTitle = txt("", 17f, th.fg, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        mediaSub = txt("", 12f, th.fgSoft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(mediaTitle); texts.addView(mediaSub)
        c.addView(texts, LinearLayout.LayoutParams(p(if (s.w >= 560f) 200f else (s.w * 0.34f).coerceAtLeast(90f)), WRAP_CONTENT))
        val wide = s.w >= 560f
        val prog = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        mediaBar = ThinBar(this).apply { trackC = th.track; fillC = th.fill }
        mediaTime = txt("", 10.5f, th.fgSoft).apply { maxLines = 1 }
        prog.addView(mediaBar, LinearLayout.LayoutParams(0, p(4), 1f))
        prog.addView(mediaTime, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(8) })
        mediaRow = prog
        if (wide) c.addView(prog, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = p(8); rightMargin = p(10) })
        else c.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ctrl.addView(ctrlBtn(R.drawable.ic_prev, 26f, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(p(46), p(46)))
        ctrl.addView(ctrlBtn(R.drawable.ic_play, 26f, true) { dockPlay() }, LinearLayout.LayoutParams(p(54), p(54)).apply { setMargins(p(4), 0, p(4), 0) })
        ctrl.addView(ctrlBtn(R.drawable.ic_next, 26f, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(p(46), p(46)))
        c.addView(ctrl)
        if (wide) {
            fun mini(res: Int, go: () -> Unit) = ImageView(this).apply { setImageResource(res); setColorFilter(th.fg); setPadding(p(8), p(8), p(8), p(8)); isClickable = true; setOnClickListener { go() } }
            c.addView(mini(R.drawable.ic_volume) { setVolumeCard(volCard?.visibility != View.VISIBLE) }, LinearLayout.LayoutParams(p(38), p(38)).apply { leftMargin = p(8) })
            c.addView(mini(R.drawable.ic_settings) { if (!openRm("eq")) { page = Page.EQ; render() } }, LinearLayout.LayoutParams(p(38), p(38)))
            c.addView(mini(R.drawable.ic_book) { if (!openRm("lyrics")) showLyrics() }, LinearLayout.LayoutParams(p(38), p(38)))
        }
        mT = null; mS = null; mP = -1
        handler.post { updateMedia() }
        return c
    }

    // ---- menu y apps
    private fun wDock(s: WSpec): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p(6), p(4), p(6), p(4)); background = wGlass(26f) }
        val ring = (s.h - 36f).coerceIn(30f, 50f)
        fun item(res: Int, label: String, fill: Int?, stroke: Int, badge: Int = 0, go: () -> Unit, long: (() -> Unit)? = null): View {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; setOnClickListener { go() }; if (long != null) setOnLongClickListener { long(); true } }
            val f = FrameLayout(this).apply {
                background = if (fill != null) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(fill, Color.parseColor("#1A6DFF"))).apply { shape = GradientDrawable.OVAL } else oval(th.circle, stroke)
                clipChildren = false
            }
            f.addView(ImageView(this).apply { setImageResource(res); setColorFilter(if (fill != null) Color.WHITE else th.fg) }, FrameLayout.LayoutParams(p(ring * 0.5f), p(ring * 0.5f), Gravity.CENTER))
            if (badge > 0) f.addView(txt(badge.toString(), 10.5f, Color.parseColor("#111111"), true).apply { gravity = Gravity.CENTER; background = oval(amber, Color.TRANSPARENT) }, FrameLayout.LayoutParams(p(18), p(18), Gravity.TOP or Gravity.END).apply { setMargins(0, p(-4), p(-4), 0) })
            b.addView(f, LinearLayout.LayoutParams(p(ring), p(ring)))
            if (s.h >= 64f) b.addView(txt(label, 10f, th.fg).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, p(2), 0, 0) })
            b.pressFx()
            return b
        }
        val (count, _) = attention()
        val lp = { LinearLayout.LayoutParams(0, MATCH_PARENT, 1f) }
        c.addView(item(R.drawable.ic_menu, "Menú", null, cyan, count, { menuToggleFn?.invoke() }, { menuLongFn?.invoke() }), lp())
        c.addView(item(R.drawable.ic_apps, "Apps", null, th.circleStroke, 0, {
            val a = overrideApp("apps_pkg"); if (a != null) launch(a.pkg) else { page = Page.ALL; render() }
        }, { dockMenu("Botón Apps", "apps_pkg", "Elige la app para este botón", listOf("Abrir el menú de Apps" to { page = Page.ALL; render() })) }), lp())
        c.addView(item(R.drawable.ic_phone, "CarPlay", Color.parseColor("#27C7E8"), 0, 0, {
            val a = findApp("carplay_pkg", carPlayHints); if (a != null) launch(a.pkg) else chooseApp("carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)")
        }, { dockMenu("Botón CarPlay", "carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)", listOf("Asistente CarPlay" to { page = Page.CARPLAY; render() })) }), lp())
        c.addView(item(R.drawable.ic_settings, "Ajustes", null, th.circleStroke, 0, {
            val a = overrideApp("settings_pkg"); if (a != null) launch(a.pkg) else openSettings(Settings.ACTION_SETTINGS)
        }, { dockMenu("Botón Ajustes", "settings_pkg", "Elige la app para este botón", listOf("Diagnóstico y ajustes del launcher" to { page = Page.DIAG; render() })) }), lp())
        c.addView(item(R.drawable.ic_chevron_up, "Fondos", null, th.circleStroke, 0, { setStrip(!stripOpen) }), lp())
        return c
    }

    private fun pickWidgetDialog(missing: List<WStore.Def>, add: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle("Agregar widget")
            .setItems(missing.map { it.name + "  —  " + it.sub }.toTypedArray()) { _, i -> add(missing[i].id) }
            .setNegativeButton("Cerrar", null).show()
    }


    // ---- combustible: nivel estimado y detección de gasolinera
    private var fuelStopAt = 0L
    private var fuelMoved = false
    private var pendingFuelAsk: String? = null
    private var fuelDialogOpen = false
    private var fuelBsField: EditText? = null

    private fun wFuel(s: WSpec): View {
        Combustible.repair(prefs, odoKm())
        val est = Combustible.estimate(prefs, odoKm())
        val lf = Combustible.last(prefs)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(p(12), p(6), p(10), p(6)); background = wGlass(16f); isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, GasActivity::class.java)) }
        }
        c.addView(wTitle("⛽ Combustible"))
        if (est == null) {
            c.addView(txt(if (lf == null) "Registra un llenado" else "Falta un llenado completo", 13f, th.fg, true).apply { maxLines = 2 })
        } else {
            val col = if (est.pct < 10) red else if (est.pct < 22) amber else green
            c.addView(txt(String.format(Locale.US, "%.0f L · %d%%", est.liters, est.pct), 18f, col, true).apply { maxLines = 1; isSingleLine = true })
            c.addView(ThinBar(this).apply { trackC = th.track; fillC = col; frac = est.pct / 100f }, LinearLayout.LayoutParams(MATCH_PARENT, p(6)).apply { topMargin = p(3) })
            c.addView(txt("≈ ${est.rangeKm} km", 12f, th.fgSoft).apply { maxLines = 1; setPadding(0, p(3), 0, 0) })
            if (lf != null && s.h >= 80f) c.addView(txt("Cargó " + java.text.SimpleDateFormat("dd/MM", Locale.US).format(java.util.Date(lf.optLong("t"))) + " · " + Combustible.cur(prefs) + " " + lf.optDouble("bs", 0.0).toInt(), 11f, th.fgSoft).apply { maxLines = 1 })
        }
        return c
    }

    private fun refreshAfterBackground() { widgetLayer?.rebuild("park") }

    private fun askFuelIfPossible() {
        val st = pendingFuelAsk ?: return
        if (fuelDialogOpen || !hasWindowFocus()) return
        pendingFuelAsk = null
        fuelPrompt(st)
    }

    private fun fuelPrompt(station: String) {
        fuelDialogOpen = true
        VozClips.play(this, "fuel_amount") {
            if (AsistenteService.running) {
                AsistenteService.choiceCb = { t -> NumEs.parse(t)?.let { v -> handler.post { fuelBsField?.setText(if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()) }; true } ?: false }
                AsistenteService.choiceUntil = System.currentTimeMillis() + 30_000L
            }
        }
        val odo = odoKm()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(8), p(24), 0) }
        box.addView(TextView(this).apply { text = "Estás en $station."; textSize = 14f })
        fun field(label: String, init: String): EditText {
            box.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, p(10), 0, 0) })
            return EditText(this).apply { setSingleLine(); setText(init); inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }.also { box.addView(it) }
        }
        val bs = field("Total pagado (Bs) · o dilo en voz alta", "")
        fuelBsField = bs
        val pr = field("Precio por litro (Bs)", String.format(Locale.US, "%.2f", Combustible.price(prefs)))
        val res = TextView(this).apply { textSize = 14f; setPadding(0, p(12), 0, p(4)); setTypeface(null, Typeface.BOLD) }
        box.addView(res)
        val cb = android.widget.CheckBox(this).apply { text = "Llené el tanque"; isChecked = true }
        box.addView(cb)
        fun n(e: EditText) = e.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        fun refresh() {
            val b = n(bs); val pp = n(pr)
            if (b <= 0 || pp <= 0) { res.text = "Escribe cuánto pagaste"; return }
            val l = b / pp
            val before = Combustible.estimate(prefs, odo)
            val tank = Combustible.tankL(prefs)
            if (before == null) { res.text = String.format(Locale.US, "≈ %.1f L · aún no sé cuánto había: dime si llenaste el tanque", l); return }
            val after = min(tank, before.liters + l)
            val full = before.liters + l >= tank * 0.95
            res.text = String.format(Locale.US, "≈ %.1f L · antes ~%.0f L → ahora ~%.0f L (%d %%)\n%s", l, before.liters, after, (after / tank * 100).toInt(),
                if (full) "✔ TANQUE LLENO" else "✘ No quedó lleno")
            cb.isChecked = full
        }
        val tw = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        bs.addTextChangedListener(tw); pr.addTextChangedListener(tw)
        refresh()
        val d = AlertDialog.Builder(this).setTitle("⛽ ¿Cuánto cargaste?").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val b = n(bs); val pp = n(pr)
                if (b <= 0 || pp <= 0) { toast("Falta cuánto pagaste"); return@setPositiveButton }
                prefs.edit().putString("fuel_price", String.format(Locale.US, "%.2f", pp)).apply()
                val l = b / pp
                Combustible.addFill(prefs, b, l, odo ?: 0, station, cb.isChecked)
                val est = Combustible.estimate(prefs, odo)
                toast(String.format(Locale.US, "Guardado: %.1f L (%s %.0f). ", l, Combustible.cur(prefs), b) + (if (cb.isChecked) "Tanque lleno" else "No lleno") + (est?.let { " · ~%.0f L, autonomía ≈ %d km".format(Locale.US, it.liters, it.rangeKm) } ?: ""))
                widgetLayer?.rebuild("comb"); widgetLayer?.rebuild("cons")
            }
            .setNegativeButton("No cargué", null)
            .setOnDismissListener { fuelDialogOpen = false; fuelBsField = null; AsistenteService.choiceUntil = 0L; AsistenteService.choiceCb = null; VozClips.stop() }
            .create()
        d.show()
    }

    // ---- aviso de luces (de noche, en marcha, luces apagadas o sin dato)
    private fun liveMapTick() {
        val t = SystemClock.elapsedRealtime()
        if (t - liveAt < 2500L) return
        liveAt = t
        liveMap?.update(TripService.lat, TripService.lon, TripService.bearing, synchronized(HomeTrail.pts) { HomeTrail.pts.toList() })
    }

    private fun checkLights() {
        val t = SystemClock.elapsedRealtime()
        if (t - lightsAt < 4000L || page != Page.HOME) return
        lightsAt = t
        val st = Luces.effective(this, prefs)
        if (st != lightsShown) { lightsShown = st; widgetLayer?.rebuild("luces") }
        val night = !th.day && prefs.getBoolean("night_photo", true)
        glowView?.setOn(Luces.effective(this, prefs) ?: false)   // sigue las luces reales (o lo que marcaste), nunca la hora
        if (th.day || !prefs.getBoolean("light_alert", true) || alertView != null || widgetLayer?.editing == true) return
        val moving = TripService.running && TripService.lastFixMs != 0L && System.currentTimeMillis() - TripService.lastFixMs < 8000L && TripService.speedMps * 3.6f > 6f
        if (st == true || !moving) return
        val dayKey = ((System.currentTimeMillis() - 12 * 3_600_000L) / 86_400_000L).toString()
        if (prefs.getString("alert_day", "") == dayKey) return
        prefs.edit().putString("alert_day", dayKey).apply()
        showLightAlert(st == false)
    }

    private fun showLightAlert(known: Boolean) {
        val area = homeArea ?: return
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(18), p(12), p(18), p(12))
            background = GradientDrawable().apply { cornerRadius = p(24).toFloat(); setColor(Color.parseColor("#F02A1A05")); setStroke(max(1, p(1)), Color.parseColor("#AAFFB347")) }
            isClickable = true
        }
        c.addView(txt("💡", 30f, Color.WHITE).apply { setPadding(0, 0, p(12), 0) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(if (known) "Anocheció y las luces están apagadas" else "Anocheció, enciende las luces", 16f, Color.parseColor("#FFD58A"), true))
        col.addView(txt("Enciéndelas para ver y que te vean", 12.5f, Color.parseColor("#F0DCC0")))
        c.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(14) })
        val close = { alertView?.let { area.removeView(it) }; alertView = null }
        c.addView(txt("Entendido", 13f, Color.parseColor("#2A1A05"), true).apply {
            gravity = Gravity.CENTER; setPadding(p(16), p(8), p(16), p(8)); background = GradientDrawable().apply { cornerRadius = p(16).toFloat(); setColor(Color.parseColor("#FFB347")) }
            isClickable = true; setOnClickListener { close() }
        })
        area.addView(c, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = p(170) })
        alertView = c
        handler.postDelayed({ close() }, 20_000L)
    }

    // ---- ajustes del modo noche + diagnóstico de luces
    /** Instrucciones para cualquier auto: depende de que la radio exponga el estado de las luces a Android. */
    private fun lightsHelp() {
        val tx = "Para que el resplandor de los faros siga tus luces reales, el launcher necesita que tu radio avise a Android cuando las luces se encienden. Cada radio lo hace distinto, por eso se detecta así:\n\n" +
            "1. Abre «Ajustes y detección de luces».\n" +
            "2. Con las luces APAGADAS pulsa «1 · Tomar lectura».\n" +
            "3. ENCIENDE las luces del auto y pulsa «2 · Tomar lectura».\n" +
            "4. Pulsa «3 · Comparar» y elige el ajuste que cambió (los primeros de la lista suelen ser de luces: beam, lamp, light, illum, backlight).\n" +
            "5. Toca el widget de luces y di cómo están: si sale al revés, se corrige sola.\n\n" +
            "Si no aparece ningún cambio:\n" +
            "• En los ajustes de tu radio busca opciones como «Luces / Faros», «Brillo automático», «Modo noche con las luces» o «Iluminación de la pantalla» y actívalas; vuelve a tomar las lecturas.\n" +
            "• Prueba «Vigilar cambios EN VIVO» y enciende/apaga las luces varias veces.\n" +
            "• Muchas radios no entregan este dato (la señal va por el bus del auto y no llega a Android). Es normal: entonces usa el toque en el widget para marcar tú si las luces están encendidas o apagadas.\n\n" +
            "El launcher nunca adivina por la hora: sin señal ni marca tuya, el resplandor queda apagado."
        AlertDialog.Builder(this).setTitle("Conectar las luces de tu auto").setMessage(tx).setPositiveButton("Entendido", null)
            .setNeutralButton("Abrir detección") { _, _ -> nightSettings() }.show()
    }

    private fun nightSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(20), p(8), p(20), p(4)) }
        fun sw(title: String, sub: String, key: String, def: Boolean) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p(8), 0, p(8)) }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(this).apply { text = title; textSize = 16f; setTypeface(null, Typeface.BOLD) })
            t.addView(TextView(this).apply { text = sub; textSize = 12f; alpha = 0.7f })
            r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(android.widget.Switch(this).apply { isChecked = prefs.getBoolean(key, def); setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() } })
            box.addView(r)
        }
        sw("Foto de noche con faros", "De noche la foto se oscurece y los faros se encienden", "night_photo", true)
        sw("Interfaz más tenue de noche", "Los widgets bajan de intensidad", "night_dim", true)
        sw("Aviso de luces", "«Anocheció, enciende las luces» solo si el auto se mueve", "light_alert", true)
        val theme = prefs.getString("theme_mode", "auto") ?: "auto"
        fun action(label: String, go: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; setPadding(0, p(12), 0, p(12)); setTextColor(cyan); isClickable = true; setOnClickListener { go() }
        }
        box.addView(action("Tema: " + when (theme) { "light" -> "Claro"; "dark" -> "Oscuro"; else -> "Automático (día y noche por GPS)" } + "  ›") {
            val nx = when (theme) { "auto" -> "light"; "light" -> "dark"; else -> "auto" }
            prefs.edit().putString("theme_mode", nx).apply(); render(); nightSettings()
        })
        box.addView(action("Unidad de velocidad: " + (if (speedUnit() == "mph") "mph" else "km/h") + "  ›") {
            prefs.edit().putString("speed_unit", if (speedUnit() == "mph") "kmh" else "mph").apply(); updateSpeed(); nightSettings()
        })
        box.addView(action("Diagnóstico de luces del auto…") { lightsDiag() })
        val overlayOk = try { Settings.canDrawOverlays(this) } catch (e: Throwable) { false }
        box.addView(action(if (overlayOk) "Avisos sobre otras apps: activados ✓" else "Activar avisos sobre CarPlay y otras apps (permiso)  ›") {
            if (!overlayOk) try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Throwable) { toast("Ábrelo en Ajustes > Apps > OpenCar Launcher > Mostrar sobre otras apps") }
        })
        AlertDialog.Builder(this).setTitle("Modo noche y luces").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Listo", null).show()
    }

    /** Muestra en vivo cada ajuste del sistema que cambia; sirve para ver qué mueve la radio al encender o apagar las luces. */
    private fun liveLights() {
        var prev = Luces.snapshot(this)
        val changes = LinkedHashMap<String, Luces.Diff>()
        val log = TextView(this).apply { textSize = 12.5f; setPadding(p(20), p(8), p(20), p(4)); text = "Enciende y apaga las luces del auto. Aquí aparece cada ajuste que cambie…" }
        val obs = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val now = Luces.snapshot(this@MainActivity)
                for (d in Luces.diff(prev, now)) changes[d.key] = d
                prev = now
                if (changes.isNotEmpty()) log.text = changes.values.toList().takeLast(14).joinToString("\n") { "${it.key}: ${it.off.take(18)} → ${it.on.take(18)}" }
            }
        }
        try { for (u in listOf(Settings.System.CONTENT_URI, Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI)) contentResolver.registerContentObserver(u, true, obs) } catch (e: Throwable) { }
        fun pick(onWhenNew: Boolean) {
            val l = changes.values.toList()
            if (l.isEmpty()) { toast("Aún no cambió nada"); return }
            AlertDialog.Builder(this).setTitle(if (onWhenNew) "¿Cuál cambió al ENCENDER?" else "¿Cuál cambió al APAGAR?")
                .setItems(l.map { "${it.key}\n  ${it.off.take(24)} → ${it.on.take(24)}" }.toTypedArray()) { _, i ->
                    val d = l[i]; Luces.saveSignal(prefs, if (onWhenNew) d else Luces.Diff(d.key, d.on, d.off))
                    prefs.edit().putBoolean("lights_invert", false).apply(); watchLights(true); lightsShown = null; widgetLayer?.rebuild("luces"); checkLights(); toast("Señal guardada")
                }.setNegativeButton("Cancelar", null).show()
        }
        AlertDialog.Builder(this).setTitle("Vigilando luces en vivo").setView(ScrollView(this).apply { addView(log) })
            .setPositiveButton("Fue al ENCENDER") { _, _ -> pick(true) }
            .setNeutralButton("Fue al APAGAR") { _, _ -> pick(false) }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { try { contentResolver.unregisterContentObserver(obs) } catch (e: Throwable) { } }
            .show()
    }

    private fun lightsDiag() {
        var off: Map<String, String>? = null
        var on: Map<String, String>? = null
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(20), p(8), p(20), p(4)) }
        val info = TextView(this).apply { textSize = 13f }
        fun refresh() {
            val st = Luces.state(this, prefs)
            info.text = "Sensor de luz de la radio: " + (if (Luces.hasLightSensor(this)) "sí" else "no") + "\n" +
                "Señal de luces guardada: " + (if (Luces.configured(prefs)) (prefs.getString("lights_key", "") + " → ahora " + (when (st) { true -> "ENCENDIDAS"; false -> "APAGADAS"; null -> "sin lectura" })) else "ninguna") +
                "\n\n1) Con las luces APAGADAS toca «Tomar lectura».\n2) ENCIENDE las luces del auto y toca «Tomar lectura».\n3) Toca «Comparar».\n" +
                (if (off != null) "\nLectura con luces apagadas: ✓ (${off!!.size} ajustes)" else "") + (if (on != null) "\nLectura con luces encendidas: ✓ (${on!!.size} ajustes)" else "")
        }
        fun btn(label: String, go: () -> Unit) = TextView(this).apply { text = label; textSize = 15f; setPadding(0, p(11), 0, p(11)); setTextColor(cyan); isClickable = true; setOnClickListener { go() } }
        box.addView(info)
        box.addView(btn("1 · Luces APAGADAS → tomar lectura") { off = Luces.snapshot(this); refresh(); toast("Lectura guardada (apagadas)") })
        box.addView(btn("2 · Luces ENCENDIDAS → tomar lectura") { on = Luces.snapshot(this); refresh(); toast("Lectura guardada (encendidas)") })
        box.addView(btn("3 · Comparar") {
            val a = off; val b = on
            if (a == null || b == null) { toast("Falta tomar una de las dos lecturas"); return@btn }
            val d = Luces.diff(a, b)
            if (d.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Sin cambios").setMessage("Tu radio no cambió ningún ajuste al encender las luces. Revisa que en los ajustes de la radio estén activadas las opciones de luces/faros/brillo automático y repite las lecturas. Si sigue igual, tu radio no entrega ese dato: toca el widget de luces para marcarlas tú (el launcher no adivina por la hora).").setPositiveButton("Entendido", null).show()
            } else {
                val likely = Regex("beam|lamp|light|illum|headl|night|backlight|ill_")
                val top = d.sortedBy { if (likely.containsMatchIn(it.key.lowercase())) 0 else 1 }.take(15)
                AlertDialog.Builder(this).setTitle("Cambió esto: elige la señal de luces")
                    .setItems(top.map { "${it.key}\n  ${it.off.take(24)} → ${it.on.take(24)}" }.toTypedArray()) { _, i -> Luces.saveSignal(prefs, top[i]); widgetLayer?.rebuild("luces"); refresh(); toast("Señal guardada") }
                    .setNegativeButton("Cancelar", null).show()
            }
        })
        box.addView(btn("Invertir (si sale al revés): " + (if (prefs.getBoolean("lights_invert", false)) "INVERTIDO" else "normal")) {
            prefs.edit().putBoolean("lights_invert", !prefs.getBoolean("lights_invert", false)).apply(); lightsShown = null; widgetLayer?.rebuild("luces"); glowView?.setOn(Luces.state(this, prefs) ?: false); refresh(); toast("Invertido")
        })
        box.addView(btn("4 · Vigilar cambios EN VIVO (enciende y apaga las luces)") { liveLights() })
        box.addView(btn("Borrar la señal guardada") { Luces.clearSignal(prefs); widgetLayer?.rebuild("luces"); refresh() })
        refresh()
        AlertDialog.Builder(this).setTitle("Diagnóstico de luces").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Cerrar", null).show()
    }

    private fun tile(
        title: String, icon: Drawable?, iconPx: Float, titleSize: Float,
        onClick: () -> Unit, onLong: (() -> Unit)? = null
    ): LinearLayout {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        t.setPadding(p(4), p(4), p(4), p(4))
        t.background = glass(cardBg, cardPressed)
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        if (icon != null && iconPx > 0f) {
            t.addView(ImageView(this).apply {
                setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(p(iconPx), p(iconPx)).apply { bottomMargin = p(3) })
        }
        t.addView(TextView(this).apply {
            text = title; size(titleSize); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        t.pressFx()
        return t
    }

    // ---- Mi auto: datos del vehiculo, documentos y taller

    private fun cardLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        setMargins(p(5), p(5), p(5), p(5))
    }

    private fun openInGoogleMaps(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage("com.google.android.apps.maps")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { openUrl(url) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("No se pudo abrir el enlace")
        }
    }

    private fun buildCar(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(20), p(8), p(20), p(4))
        }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }),
            LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(TextView(this).apply {
            text = "Mi auto"
            size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(p(24), 0, 0, 0)
        })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val sv = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(p(14), p(4), p(14), p(14))
        }
        sv.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        body.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Datos del vehiculo
        left.addView(card(CarCatalog.title(prefs).lowercase().replaceFirstChar { it.uppercase() }, onClick = { editVehicle() }) {
            listOf(ps("car_plate")?.let { "Placa $it" }, ps("car_motor"), ps("car_tires"), ps("car_psi")?.let { "Presión: $it" })
                .filterNotNull().filter { it.isNotBlank() }.forEachIndexed { i, t -> line(t, if (i == 0) Color.WHITE else soft, 15f) }
            line(odoKm()?.let { "Odómetro: ${fmtKm(it)} ${u()} (toca para calibrar)" } ?: "Odómetro: toca para ponerlo", green, 15f)
        }, cardLp())

        // Documentos
        left.addView(docsCard(), cardLp())

        // Historial de servicios
        left.addView(card("Historial de servicios", onClick = { editOil() }) {
            val oilKm = pi("oil_last_km")
            val oilDate = ps("oil_last_date")
            if (oilKm != null) {
                line("Aceite motor: ${oilDate ?: "-"} · ${fmtKm(oilKm)} ${u()}", soft, 15f)
                line("Próximo cambio: ${fmtKm(oilKm + (pi("oil_int_km") ?: 5000))} ${u()}", green, 15f)
            }
            val cvtKm = pi("cvt_last_km")
            if (cvtKm != null) {
                line("Aceite de caja (CVT): ${ps("cvt_last_date") ?: "-"} · ${fmtKm(cvtKm)} ${u()}", soft, 15f)
                line("Próximo cambio: ${fmtKm(cvtKm + (pi("cvt_int_km") ?: 30000))} ${u()}", green, 15f)
            }
        }, cardLp())

        // Taller de confianza
        val taller = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(14), p(12), p(14), p(12))
            background = rounded(cardBg, 18f, true)
        }
        taller.addView(TextView(this).apply {
            text = "Taller de confianza"
            size(15f); setTextColor(muted)
        })
        if (tallerNombre().isBlank()) {
            taller.addView(TextView(this).apply {
                text = "Guarda aquí el nombre y teléfono de tu taller (toca)"
                size(16f); setTextColor(soft); setPadding(0, p(4), 0, p(6))
            })
            taller.isClickable = true; taller.setOnClickListener { editTaller() }
        } else {
            taller.addView(TextView(this).apply {
                text = tallerNombre()
                size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            if (tallerRubro().isNotBlank()) taller.addView(TextView(this).apply {
                text = tallerRubro()
                size(14f); setTextColor(soft)
                setPadding(0, p(2), 0, p(6))
            })
            taller.addView(TextView(this).apply {
                text = listOf(tallerContacto(), tallerTelefono()).filter { it.isNotBlank() }.joinToString("  ·  ").let { if (it.isBlank()) "" else "Contacto: $it" }
                size(18f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            taller.setOnLongClickListener { editTaller(); true }
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(tile("Llamar", getDrawable(R.drawable.ic_phone), 30f, 16f, {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${tallerTelefono()}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                toast("Esta pantalla no tiene app de teléfono")
            }
        }), weight(1f, p(84), 5f))
        btns.addView(tile("Cómo llegar", null, 0f, 17f, { if (tallerMapa().isBlank()) editTaller() else openInGoogleMaps(tallerMapa()) }), weight(1f, p(84), 5f))
        taller.addView(btns, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        right.addView(taller, cardLp())

        col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ---- Asistente CarPlay (revisa el estado y guia paso a paso)

    private fun statusRow(box: LinearLayout, level: Int, title: String, detail: String) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, p(7), 0, p(7))
        }
        r.addView(View(this).apply { background = oval(lvlColor(level), Color.TRANSPARENT) },
            LinearLayout.LayoutParams(p(12), p(12)).apply { rightMargin = p(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(txt(title, 16f, Color.WHITE, true))
        t.addView(txt(detail, 13.5f, Color.parseColor("#B3FFFFFF")))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(r)
    }

    private fun glassCol(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(p(18), p(12), p(18), p(14))
        background = GradientDrawable().apply {
            cornerRadius = p(22).toFloat()
            setColor(Color.parseColor("#B3101E38"))
            setStroke(max(1, p(1)), Color.parseColor("#3377A9FF"))
        }
    }

    private fun buildCarPlay(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(20), p(8), p(20), p(4))
        }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }), LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(txt("Asistente CarPlay", 22f, Color.WHITE, true).apply { setPadding(p(24), 0, 0, 0) })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val sv = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(p(14), p(4), p(14), p(14))
        }
        sv.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val left = glassCol()
        val right = glassCol()
        body.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(p(5), p(5), p(5), p(5)) })
        body.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(p(5), p(5), p(5), p(5)) })

        // ---- estado
        left.addView(txt("ESTADO DE LA PANTALLA", 11f, Color.parseColor("#8CFFFFFF")).apply { letterSpacing = 0.16f })
        val zl = findApp("carplay_pkg", carPlayHints)
        if (zl != null) statusRow(left, 0, "App de CarPlay instalada", "${zl.label} (${zl.pkg})")
        else statusRow(left, 2, "No encuentro la app de CarPlay", "Mantén presionado el botón CarPlay y elige ZLINK5")

        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val connected = wm.isWifiEnabled && info != null && info.supplicantState == SupplicantState.COMPLETED
            val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && !it.startsWith("<unknown") }
            when {
                !wm.isWifiEnabled -> statusRow(left, 0, "Wi-Fi de la pantalla apagado",
                    "Es lo esperado antes de conectar CarPlay inalámbrico")
                connected && ssid != null -> statusRow(left, 1, "Wi-Fi conectado a «$ssid»",
                    "Para CarPlay inalámbrico conviene desconectarla: la pantalla debe usar su propio Wi-Fi (esa red es la del espejo)")
                connected -> statusRow(left, 1, "Wi-Fi conectado a una red",
                    "Para CarPlay inalámbrico conviene desconectarla (el nombre se ve con permiso de ubicación)")
                else -> statusRow(left, 0, "Wi-Fi sin conexión a otra red", "Bien: queda libre para CarPlay")
            }
        } catch (e: Exception) {
            statusRow(left, 1, "No pude leer el Wi-Fi", "Revisa a mano en Ajustes de Wi-Fi")
        }
        statusRow(left, 0, "Bluetooth de la pantalla: SD-7822 PRO",
            "Empareja el iPhone con ESTE nombre (se ve en ZLINK5), no desde Ajustes de Android")
        statusRow(left, 0, "Modo en ZLINK5", "Elige inalámbrica > iPhone > CarPlay (no «Espejo»)")

        // ---- acciones
        left.addView(txt("ACCIONES", 11f, Color.parseColor("#8CFFFFFF")).apply {
            letterSpacing = 0.16f
            setPadding(0, p(10), 0, p(4))
        })
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        acts.addView(tile("Ajustes de Wi-Fi", null, 0f, 15f, { openSettings(Settings.ACTION_WIFI_SETTINGS) }), weight(1f, p(70), 4f))
        acts.addView(tile("Abrir ZLINK5", null, 0f, 15f, {
            if (zl != null) launch(zl.pkg) else chooseApp("carplay_pkg", "Elige la app de CarPlay (ZLink / CarPlay)")
        }), weight(1f, p(70), 4f))
        acts.addView(tile("Actualizar", null, 0f, 15f, { render() }), weight(1f, p(70), 4f))
        left.addView(acts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- pasos
        right.addView(txt("PASOS (EN ESTE ORDEN)", 11f, Color.parseColor("#8CFFFFFF")).apply { letterSpacing = 0.16f })
        val steps = listOf(
            "En el iPhone apaga el Punto de acceso personal y la VPN.",
            "En la pantalla desconecta el Wi-Fi de la red del iPhone (Ajustes de Wi-Fi > olvidar red).",
            "En el iPhone: Ajustes > General > CarPlay > tu auto > Olvidar este auto. Y en Bluetooth, olvida SD-7822 PRO.",
            "En el iPhone revisa: CarPlay permitido en Tiempo en pantalla, Siri activo, Ahorro de batería apagado.",
            "Abre ZLINK5 > inalámbrica > iPhone. En el iPhone ve a Bluetooth y toca SD-7822 PRO. Acepta el emparejamiento.",
            "Cuando el iPhone pregunte, acepta usar CarPlay. NO te unas a mano al Wi-Fi de la pantalla.",
            "Si queda en «Conectando»: apaga y enciende el Bluetooth del iPhone. Si sigue igual, prueba por cable USB al puerto de CarPlay."
        )
        steps.forEachIndexed { i, st ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, p(6), 0, p(2))
            }
            r.addView(txt("${i + 1}", 14f, Color.parseColor("#04101C"), true).apply {
                gravity = Gravity.CENTER
                background = oval(cyan, Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(p(26), p(26)).apply { rightMargin = p(12) })
            r.addView(txt(st, 14.5f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            right.addView(r)
        }
        col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ---- diagnostico y ajustes del launcher


    // ------------------------------------------------------------ calendario

    private var calYear = 0
    private var calMonth = 0
    private val calCache = HashMap<Int, List<FeriadosBo.Ev>>()
    private val mesesEs = arrayOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")
    private val diasEs = arrayOf("Dom", "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb")

    private fun userEvents(): List<FeriadosBo.Ev> = (prefs.getString("cal_user", "") ?: "").lines().mapNotNull { ln ->
        val i = ln.indexOf('|')
        if (i < 8) return@mapNotNull null
        val d = ln.substring(0, i).split('-')
        try { FeriadosBo.Ev(d[0].toInt(), d[1].toInt(), d[2].toInt(), ln.substring(i + 1), FeriadosBo.USR) } catch (e: Exception) { null }
    }

    private fun eventsOfYear(y: Int): List<FeriadosBo.Ev> {
        val base = calCache.getOrPut(y) { FeriadosBo.forYear(y) }
        val mine = userEvents().filter { it.y == y }
        return (base + mine + netSuggestions(y, base, mine)).sortedWith(compareBy({ it.m }, { it.d }, { it.kind }))
    }

    /** Sugerencias de la fuente en línea: solo fechas que mi cálculo no marca como feriado, sin confirmar ni descartar. */
    private fun netSuggestions(y: Int, base: List<FeriadosBo.Ev>, mine: List<FeriadosBo.Ev>): List<FeriadosBo.Ev> {
        val raw = prefs.getString("cal_net_$y", "") ?: ""
        if (raw.isBlank()) return emptyList()
        val ign = (prefs.getString("cal_net_ign", "") ?: "").split(",").toSet()
        return raw.lines().mapNotNull { ln ->
            val i = ln.indexOf('|')
            if (i < 10) return@mapNotNull null
            val ds = ln.substring(0, i)
            val d = ds.split('-')
            try {
                val e = FeriadosBo.Ev(d[0].toInt(), d[1].toInt(), d[2].toInt(), ln.substring(i + 1), FeriadosBo.SUG)
                if (ds in ign) null
                else if (base.any { it.isHoliday() && it.m == e.m && it.d == e.d }) null
                else if (mine.any { it.m == e.m && it.d == e.d }) null
                else e
            } catch (x: Exception) { null }
        }
    }

    private fun hasInternet(): Boolean = try {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { false }

    /** Consulta Nager.Date (año actual y siguiente). Solo guarda; lo nuevo aparece como "sugerido". */
    private fun syncHolidaysOnline(manual: Boolean) {
        if (!hasInternet()) { if (manual) toast("Sin internet por ahora"); return }
        prefs.edit().putLong("cal_net_try", System.currentTimeMillis()).apply()
        Thread {
            var ok = false
            val ed = prefs.edit()
            val y0 = Calendar.getInstance().get(Calendar.YEAR)
            for (y in y0..y0 + 1) {
                try {
                    val con = URL("https://date.nager.at/api/v3/PublicHolidays/$y/${Locale.getDefault().country.ifEmpty { "US" }}").openConnection() as HttpURLConnection
                    con.connectTimeout = 8000; con.readTimeout = 8000
                    con.setRequestProperty("User-Agent", "OpenCarLauncher/1.0")
                    if (con.responseCode != 200) continue
                    val arr = JSONArray(con.inputStream.bufferedReader().use { it.readText() })
                    val sb = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val types = o.optJSONArray("types")
                        var pub = types == null
                        if (types != null) for (j in 0 until types.length()) if (types.getString(j) == "Public") pub = true
                        if (!pub || !o.optBoolean("global", true)) continue
                        sb.append(o.getString("date")).append('|').append(o.optString("localName", o.optString("name"))).append('\n')
                    }
                    ed.putString("cal_net_$y", sb.toString()); ok = true
                } catch (e: Exception) { }
            }
            if (ok) ed.putLong("cal_net_at", System.currentTimeMillis())
            ed.apply()
            handler.post {
                var n = 0
                for (y in y0..y0 + 1) n += eventsOfYear(y).count { it.kind == FeriadosBo.SUG }
                if (manual) toast(if (!ok) "La fuente en línea no respondió" else if (n == 0) "Consultado: sin diferencias con el calendario" else "Hay $n sugerencia(s) en línea para revisar")
                if (page == Page.CAL) render()
            }
        }.start()
    }

    private fun sugDialog(e: FeriadosBo.Ev) {
        val key = String.format(Locale.US, "%04d-%02d-%02d", e.y, e.m, e.d)
        AlertDialog.Builder(this).setTitle("${e.name} · ${e.d}/${e.m}/${e.y}")
            .setMessage("Lo sugiere la fuente en línea (Nager.Date). Verifica el decreto antes de confirmar.")
            .setPositiveButton("Confirmar como feriado") { _, _ ->
                val cur = prefs.getString("cal_user", "") ?: ""
                val line = "$key|${e.name.replace("|", " ")}"
                prefs.edit().putString("cal_user", if (cur.isBlank()) line else "$cur\n$line").apply()
                render()
            }
            .setNegativeButton("Descartar") { _, _ ->
                val cur = prefs.getString("cal_net_ign", "") ?: ""
                prefs.edit().putString("cal_net_ign", if (cur.isBlank()) key else "$cur,$key").apply()
                render()
            }
            .setNeutralButton("Cerrar", null).show()
    }

    private fun todayHoliday(): String? {
        val c = Calendar.getInstance()
        return eventsOfYear(c.get(Calendar.YEAR)).firstOrNull {
            it.isHoliday() && it.m == c.get(Calendar.MONTH) + 1 && it.d == c.get(Calendar.DAY_OF_MONTH)
        }?.name
    }

    private fun nextHoliday(): Pair<FeriadosBo.Ev, Int>? {
        val y = Calendar.getInstance().get(Calendar.YEAR)
        for (yy in y..y + 1) for (e in eventsOfYear(yy)) {
            if (!e.isHoliday()) continue
            val d = daysUntil(e.millis())
            if (d >= 0) return Pair(e, d)
        }
        return null
    }

    private fun openCalendar() {
        val c = Calendar.getInstance()
        calYear = c.get(Calendar.YEAR); calMonth = c.get(Calendar.MONTH) + 1
        page = Page.CAL
        render()
    }

    private fun calMove(delta: Int) {
        calMonth += delta
        if (calMonth < 1) { calMonth = 12; calYear-- } else if (calMonth > 12) { calMonth = 1; calYear++ }
        render()
    }

    private fun addCalEvent() {
        prefs.edit().putString("cal_new_date", "").putString("cal_new_name", "").apply()
        form("Agregar evento propio", listOf(
            F("cal_new_date", "Fecha", KIND_DATE),
            F("cal_new_name", "Nombre del evento", KIND_TEXT)
        ), onSaved = {
            val ms = parseDate(ps("cal_new_date"))
            val name = (ps("cal_new_name") ?: "").replace("|", " ").replace("\n", " ")
            if (ms != null && name.isNotBlank()) {
                val c = Calendar.getInstance().apply { timeInMillis = ms }
                val line = String.format(Locale.US, "%04d-%02d-%02d|%s", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), name)
                val cur = prefs.getString("cal_user", "") ?: ""
                prefs.edit().putString("cal_user", if (cur.isBlank()) line else "$cur\n$line").apply()
                calYear = c.get(Calendar.YEAR); calMonth = c.get(Calendar.MONTH) + 1
            } else toast("Escribe la fecha como dd/mm/aaaa y un nombre")
            prefs.edit().remove("cal_new_date").remove("cal_new_name").apply()
        })
    }

    private fun deleteCalEvent(e: FeriadosBo.Ev) {
        AlertDialog.Builder(this).setTitle("Borrar \"${e.name}\"").setPositiveButton("Borrar") { _, _ ->
            val key = String.format(Locale.US, "%04d-%02d-%02d|%s", e.y, e.m, e.d, e.name)
            val rest = (prefs.getString("cal_user", "") ?: "").lines().filter { it.isNotBlank() && it != key }
            prefs.edit().putString("cal_user", rest.joinToString("\n")).apply()
            render()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun kindColor(k: Int) = when (k) {
        FeriadosBo.NAC -> red; FeriadosBo.DEP -> Color.parseColor("#FF9F43")
        FeriadosBo.USR -> Color.parseColor("#4DD0E1"); FeriadosBo.SUG -> Color.parseColor("#B388FF"); else -> amber
    }

    private fun kindLabel(k: Int) = when (k) {
        FeriadosBo.NAC -> "Feriado nacional"; FeriadosBo.DEP -> "Feriado regional"
        FeriadosBo.USR -> "Mi evento"; FeriadosBo.SUG -> "Sugerido en línea (verificar)"; else -> "Fecha importante"
    }

    private fun buildCal(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(20), p(8), p(20), p(4))
        }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }), LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(TextView(this).apply {
            text = "Calendario"; size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(p(24), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(tile("En línea", null, 0f, 17f, { syncHolidaysOnline(true) }), LinearLayout.LayoutParams(p(120), p(64)))
        header.addView(tile("Hoy", null, 0f, 18f, { openCalendar() }), LinearLayout.LayoutParams(p(96), p(64)).apply { leftMargin = p(8) })
        header.addView(tile("+ Evento", null, 0f, 18f, { addCalEvent() }), LinearLayout.LayoutParams(p(150), p(64)).apply { leftMargin = p(8) })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(20), p(4), p(20), p(10)) }
        if (calMonth < 1 || calYear < 1) { val c0 = Calendar.getInstance(); calYear = c0.get(Calendar.YEAR); calMonth = c0.get(Calendar.MONTH) + 1 }
        val evs = eventsOfYear(calYear)
        val monthEvs = evs.filter { it.m == calMonth }
        val today = Calendar.getInstance()
        val isThisMonth = today.get(Calendar.YEAR) == calYear && today.get(Calendar.MONTH) + 1 == calMonth

        // izquierda: mes
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 18f, true)
            setPadding(p(12), p(10), p(12), p(10))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(tile("‹", null, 0f, 24f, { calMove(-1) }), LinearLayout.LayoutParams(p(64), p(48)))
        nav.addView(TextView(this).apply {
            text = "${mesesEs[calMonth - 1]} $calYear"; size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        nav.addView(tile("›", null, 0f, 24f, { calMove(1) }), LinearLayout.LayoutParams(p(64), p(48)))
        left.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val wd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in listOf("L", "M", "M", "J", "V", "S", "D")) {
            wd.addView(TextView(this).apply { text = n; size(14f); setTextColor(muted); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { topMargin = p(6); bottomMargin = p(2) })
        }
        left.addView(wd, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val first = Calendar.getInstance().apply { clear(); set(calYear, calMonth - 1, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val dim = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        var day = 1 - offset
        for (r in 0 until 6) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (cI in 0 until 7) {
                val dd = day
                val cell = FrameLayout(this)
                if (dd in 1..dim) {
                    val es = monthEvs.filter { it.d == dd }
                    val k = listOf(FeriadosBo.NAC, FeriadosBo.DEP, FeriadosBo.USR, FeriadosBo.SUG, FeriadosBo.IMP).firstOrNull { kk -> es.any { it.kind == kk } }
                    val isToday = isThisMonth && today.get(Calendar.DAY_OF_MONTH) == dd
                    cell.background = GradientDrawable().apply {
                        cornerRadius = p(12).toFloat()
                        if (k == FeriadosBo.NAC || k == FeriadosBo.DEP) setColor((kindColor(k) and 0x00FFFFFF) or 0x66000000)
                        else setColor(Color.TRANSPARENT)
                        if (isToday) setStroke(max(2, p(2)), cyan)
                    }
                    cell.addView(TextView(this).apply {
                        text = dd.toString(); size(20f)
                        setTextColor(if (cI == 6 && k == null) Color.parseColor("#FF8A80") else Color.WHITE)
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    if (k == FeriadosBo.IMP || k == FeriadosBo.USR || k == FeriadosBo.SUG) {
                        cell.addView(View(this).apply { background = oval(kindColor(k), Color.TRANSPARENT) },
                            FrameLayout.LayoutParams(p(8), p(8), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = p(5) })
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(p(2), p(2), p(2), p(2)) })
                day++
            }
            left.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        body.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.1f).apply { rightMargin = p(10) })

        // derecha: proximo feriado + lista del mes
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 18f, true)
            setPadding(p(16), p(12), p(16), p(10))
        }
        val nh = nextHoliday()
        right.addView(TextView(this).apply {
            text = if (nh == null) "" else {
                val e = nh.first; val d = nh.second
                val when_ = when (d) { 0 -> "HOY"; 1 -> "mañana"; else -> "en $d días" }
                "Próximo feriado: ${e.name}\n" + diasEs[Calendar.getInstance().apply { clear(); set(e.y, e.m - 1, e.d) }.get(Calendar.DAY_OF_WEEK) - 1] +
                    " ${e.d} ${mesesEs[e.m - 1].lowercase()} · $when_"
            }
            size(16f); setTextColor(Color.parseColor("#FFE082")); setPadding(0, 0, 0, p(6))
        })
        val sv = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(list)
        if (monthEvs.isEmpty()) list.addView(txt("Sin fechas este mes", 16f, muted))
        for (e in monthEvs) {
            val dowIdx = Calendar.getInstance().apply { clear(); set(e.y, e.m - 1, e.d) }.get(Calendar.DAY_OF_WEEK) - 1
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, p(6), 0, p(6))
                if (e.kind == FeriadosBo.USR) setOnLongClickListener { deleteCalEvent(e); true }
                if (e.kind == FeriadosBo.SUG) { isClickable = true; setOnClickListener { sugDialog(e) } }
            }
            r.addView(View(this).apply { background = oval(kindColor(e.kind), Color.TRANSPARENT) }, LinearLayout.LayoutParams(p(12), p(12)).apply { rightMargin = p(10) })
            r.addView(txt("${diasEs[dowIdx]} ${e.d}", 17f, Color.WHITE, true), LinearLayout.LayoutParams(p(74), WRAP_CONTENT))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(txt(e.name, 16f, Color.WHITE))
            t.addView(txt(kindLabel(e.kind) + if (e.kind == FeriadosBo.USR) " · mantén pulsado para borrar" else if (e.kind == FeriadosBo.SUG) " · toca para confirmar o descartar" else "", 12f, muted))
            r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            list.addView(r)
        }
        right.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val netAt = prefs.getLong("cal_net_at", 0L)
        val netTxt = if (netAt == 0L) "En línea: aún sin consultar." else "En línea: consultado el ${fmtDate(netAt)}."
        right.addView(txt((if (Locale.getDefault().country == "BO") "Se calcula solo cada año (DS 2750 y DS 5483; 2026 con DS 5521). " else "Feriados de tu país vía internet (botón En línea). ") + "$netTxt Los puentes nuevos aparecen como sugeridos en violeta; confírmalos o agrégalos con + Evento.", 11f, muted).apply {
            setPadding(0, p(6), 0, 0)
        })
        body.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }


    // ------------------------------------------------------------ v2.24: viaje, telemetria, clima, reloj grande

    private var tripSpeedV: TextView? = null
    private var tripHeadV: TextView? = null
    private var tripAltV: TextView? = null
    private var tripAccV: TextView? = null
    private var tripTimeV: TextView? = null
    private var tripKmV: TextView? = null
    private var tripAvgV: TextView? = null
    private var tripMaxV: TextView? = null
    private var chartSpeed: MiniChart? = null
    private var chartAlt: MiniChart? = null
    private var tripVolBar: TouchBar? = null
    private var tripLimitV: TextView? = null
    private var tripOnTile: LinearLayout? = null
    private var tripOffTile: LinearLayout? = null
    private var bigLayer: FrameLayout? = null
    private var bigTimeV: TextView? = null
    private var bigDateV: TextView? = null
    private var bigInfoV: TextView? = null
    private var bigActive = false
    private var lastTouchAt = SystemClock.elapsedRealtime()
    private var wxCheckAt = 0L
    private var overNow = false
    private var overBeepAt = 0L

    private class MiniChart(c: Context, val bars: Boolean, val col: Int) : View(c) {
        var data = FloatArray(0)
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            pt.color = Color.parseColor("#22FFFFFF"); pt.style = Paint.Style.FILL
            cv.drawRect(0f, h - 2f, w, h, pt)
            if (data.isEmpty()) return
            var lo = if (bars) 0f else data.min()
            var hi = if (bars) max(60f, data.max()) else data.max()
            if (!bars && hi - lo < 10f) { val m = (hi + lo) / 2f; lo = m - 5f; hi = m + 5f }
            val n = 40
            val step = w / n
            if (bars) {
                pt.color = col
                for (i in data.indices) {
                    val x = (n - data.size + i) * step
                    val bh = max(2f, (data[i] - lo) / (hi - lo) * (h - 4f))
                    cv.drawRect(x + 1f, h - 2f - bh, x + step - 1f, h - 2f, pt)
                }
            } else {
                pt.color = col; pt.style = Paint.Style.STROKE; pt.strokeWidth = max(2f, h * 0.04f)
                val path = android.graphics.Path()
                for (i in data.indices) {
                    val x = (n - data.size + i) * step + step / 2f
                    val y = h - 4f - (data[i] - lo) / (hi - lo) * (h - 8f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                cv.drawPath(path, pt)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    private fun speedOver(kmh: Float): Boolean {
        val over = prefs.getBoolean("spd_alert", false) && kmh > (prefs.getInt("spd_limit", 100)).toFloat()
        if (over && !overNow) {
            val t = SystemClock.elapsedRealtime()
            if (t - overBeepAt > 20_000L) {
                overBeepAt = t
                try { android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 250) } catch (e: Exception) { }
            }
        }
        overNow = over
        return over
    }

    private fun distUnit() = if (speedUnit() == "mph") "mi" else "km"
    private fun metersToUnit(m: Double) = m / (if (speedUnit() == "mph") 1609.344 else 1000.0)
    private fun kmhToUnit(k: Float) = if (speedUnit() == "mph") k * 0.621371f else k

    private fun fmtHms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun headingText(): String {
        val b = TripService.bearing
        if (b < 0f) return "--"
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return dirs[((b + 22.5f) / 45f).toInt() % 8] + " " + b.roundToInt() + "°"
    }

    // ---- clima (Open-Meteo, sin clave; ubicacion aproximada a 2 decimales)
    private fun wxDesc(c: Int) = when (c) {
        0 -> "Despejado"; 1, 2 -> "Poco nublado"; 3 -> "Nublado"; 45, 48 -> "Niebla"
        in 51..57 -> "Llovizna"; in 61..67 -> "Lluvia"; in 71..77 -> "Nieve"; in 80..82 -> "Chubascos"
        in 95..99 -> "Tormenta"; else -> ""
    }

    private fun weatherText(): String? {
        val at = prefs.getLong("wx_at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 3 * 3_600_000L) return null
        val d = wxDesc(prefs.getInt("wx_code", -1))
        return prefs.getFloat("wx_t", 0f).roundToInt().toString() + " °C" + (if (d.isNotEmpty()) " · $d" else "")
    }

    private fun syncWeather() {
        if (!hasInternet()) return
        prefs.edit().putLong("wx_try", System.currentTimeMillis()).apply()
        val la = if (!TripService.lat.isNaN()) TripService.lat else java.lang.Double.longBitsToDouble(prefs.getLong("wx_lat", java.lang.Double.doubleToLongBits(MapData.center(this).first)))
        val lo = if (!TripService.lon.isNaN()) TripService.lon else java.lang.Double.longBitsToDouble(prefs.getLong("wx_lon", java.lang.Double.doubleToLongBits(MapData.center(this).second)))
        Thread {
            try {
                val u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code&timezone=auto", la, lo)
                val con = URL(u).openConnection() as HttpURLConnection
                con.connectTimeout = 8000; con.readTimeout = 8000
                if (con.responseCode == 200) {
                    val cur = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                    prefs.edit().putFloat("wx_t", cur.getDouble("temperature_2m").toFloat()).putInt("wx_code", cur.getInt("weather_code"))
                        .putLong("wx_at", System.currentTimeMillis())
                        .putLong("wx_lat", java.lang.Double.doubleToLongBits(la)).putLong("wx_lon", java.lang.Double.doubleToLongBits(lo)).apply()
                    handler.post { clockKey = -1L; updateClock(); if (page == Page.TRIP) render() }
                }
            } catch (e: Exception) { }
        }.start()
    }

    // ---- reloj grande (parado y sin tocar 1 minuto)
    private fun setBig(on: Boolean) {
        if (on && driveActive) setDrive(false)
        bigActive = on
        chipStyleBig?.invoke(on)
        bigLayer?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { closeOverlays(); setVolumeCard(false); updateBig(true) }
    }

    private fun tempText(): String? {
        val at = prefs.getLong("wx_at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 3 * 3_600_000L) return null
        return prefs.getFloat("wx_t", 0f).roundToInt().toString() + " °C"
    }

    private fun updateBig(force: Boolean = false) {
        if (!bigActive) return
        val tc = techClock ?: return
        tc.temp = tempText() ?: ""
        val np = nowPlaying()
        tc.info = np?.title?.let { t -> "♪ $t" + (np.artist?.let { a -> " · $a" } ?: "") } ?: ""
    }

    private fun buildBigClock(): FrameLayout {
        val lay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); isClickable = true
            visibility = if (bigActive) View.VISIBLE else View.GONE
        }
        val tc = TechClock(this, prefs)
        tc.onClose = { setBig(false) }
        tc.onLong = { setBig(false) }
        techClock = tc
        lay.addView(tc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bigLayer = lay
        return lay
    }

    // ---- pagina Viaje y telemetria
    private fun tripCard(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 18f, true)
            setPadding(p(16), p(10), p(16), p(10))
        }
        c.addView(txt(title, 14f, muted, true).apply { letterSpacing = 0.12f })
        return c
    }

    private fun updateTrip() {
        val kmh = speedKmh()
        val over = speedOver(kmh)
        tripSpeedV?.text = (if (kmh < 3f) 0 else kmhToUnit(kmh).roundToInt()).toString()
        tripSpeedV?.setTextColor(if (over) red else Color.WHITE)
        tripHeadV?.text = "Rumbo  " + headingText()
        tripAltV?.text = "Altura  " + (if (TripService.altitude.isNaN()) "--" else "${TripService.altitude.roundToInt()} m")
        tripAccV?.text = "Señal  " + (if (TripService.accM < 0f) "--" else "±${TripService.accM.roundToInt()} m")
        val h = TripService.hist()
        chartSpeed?.data = h.first.map { kmhToUnit(it) }.toFloatArray(); chartSpeed?.invalidate()
        chartAlt?.data = h.second; chartAlt?.invalidate()
        val on = TripService.tripOn
        val start = TripService.tripStartMs
        val el = if (start == 0L) 0L else (if (on) System.currentTimeMillis() else TripService.tripEndMs) - start
        tripTimeV?.text = fmtHms(el)
        tripKmV?.text = String.format(Locale.US, "%.1f %s", metersToUnit(TripService.tripM), distUnit())
        val mv = TripService.tripMoveMs
        val avg = if (mv > 0L) (TripService.tripM / (mv / 1000.0) * 3.6).toFloat() else 0f
        tripAvgV?.text = "PROM ${kmhToUnit(avg).roundToInt()}   MÁX ${kmhToUnit(TripService.tripMaxKmh).roundToInt()} ${if (speedUnit() == "mph") "mph" else "km/h"}"
        tripOnTile?.background = rounded(if (on) Color.parseColor("#B3E53935") else cardBg, 14f, true)
        tripOffTile?.background = rounded(if (!on) Color.parseColor("#B3555B66") else cardBg, 14f, true)
        if (tripVolBar?.dragging != true) try {
            val am = audioMgr(); tripVolBar?.frac = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            tripVolBar?.invalidate()
        } catch (e: Exception) { }
    }

    private fun setTripOn(on: Boolean) {
        if (on && !hasLocationPerm()) { toast("Falta el permiso de ubicación (Ajustes > Dar permiso)"); return }
        TripService.setTrip(prefs, on)
        if (on) startTrip()
        updateTrip()
    }

    private fun buildTrip(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p(20), p(8), p(20), p(4)) }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }), LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(TextView(this).apply {
            text = "Viaje y telemetría"; size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(p(24), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(tile("Unidad: ${if (speedUnit() == "mph") "mph" else "km/h"}", null, 0f, 17f, {
            prefs.edit().putString("speed_unit", if (speedUnit() == "mph") "kmh" else "mph").apply(); render()
        }), LinearLayout.LayoutParams(p(190), p(64)))
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(20), p(4), p(20), p(10)) }
        // izquierda: telemetria
        val tel = tripCard("TELEMETRÍA  ·  GPS")
        val spRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tripSpeedV = TextView(this).apply {
            size(96f); setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); includeFontPadding = false; text = "0"
        }
        spRow.addView(tripSpeedV)
        spRow.addView(txt(if (speedUnit() == "mph") "mph" else "km/h", 20f, muted).apply { setPadding(p(10), 0, 0, p(10)) })
        tel.addView(spRow)
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tripHeadV = txt("", 17f, Color.WHITE); tripAltV = txt("", 17f, Color.WHITE); tripAccV = txt("", 17f, Color.WHITE)
        info.addView(tripHeadV, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        info.addView(tripAltV, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        info.addView(tripAccV, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tel.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) })
        tel.addView(txt("Velocidad (últimos 2 min)", 12f, muted), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(12) })
        chartSpeed = MiniChart(this, true, red)
        tel.addView(chartSpeed, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = p(4) })
        tel.addView(txt("Altura (últimos 2 min)", 12f, muted), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        chartAlt = MiniChart(this, false, cyan)
        tel.addView(chartAlt, LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.7f).apply { topMargin = p(4) })
        val wx = weatherText()
        tel.addView(txt("Clima: " + (wx ?: "sin datos (necesita internet)"), 15f, Color.parseColor("#FFE082")), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        body.addView(tel, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.05f).apply { rightMargin = p(10) })

        // derecha: viaje + puesta a punto
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val trip = tripCard("VIAJE")
        tripTimeV = TextView(this).apply {
            size(44f); setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; includeFontPadding = false; text = "00:00:00"
        }
        trip.addView(tripTimeV, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(4) })
        val onoff = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tripOnTile = tile("ON  ·  Nuevo viaje", null, 0f, 16f, { setTripOn(true) })
        tripOffTile = tile("OFF  ·  Terminar", null, 0f, 16f, { setTripOn(false) })
        onoff.addView(tripOnTile, LinearLayout.LayoutParams(0, p(52), 1f).apply { rightMargin = p(6) })
        onoff.addView(tripOffTile, LinearLayout.LayoutParams(0, p(52), 1f))
        trip.addView(onoff, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        tripKmV = txt("", 30f, Color.WHITE, true)
        trip.addView(tripKmV, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        tripAvgV = txt("", 15f, muted)
        trip.addView(tripAvgV)
        right.addView(trip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tune = tripCard("PUESTA A PUNTO")
        fun sliderRow(label: String, bar: TouchBar): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(txt(label, 15f, Color.WHITE), LinearLayout.LayoutParams(p(84), WRAP_CONTENT))
            r.addView(bar, LinearLayout.LayoutParams(0, p(40), 1f))
            return r
        }
        tripVolBar = TouchBar(this).apply { trackC = th.track; fillC = th.fill; live = true; onSeek = { f -> setVolumeFrac(f) } }
        tune.addView(sliderRow("Volumen", tripVolBar!!), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(4) })
        val brBar = TouchBar(this).apply {
            trackC = th.track; fillC = th.fill; live = true
            frac = ((prefs.getFloat("bright_level", 1f) - 0.15f) / 0.85f).coerceIn(0f, 1f)
            onSeek = { f ->
                val lv = 0.15f + 0.85f * f
                prefs.edit().putBoolean("auto_bright", false).putFloat("bright_level", lv).apply(); setBright(lv)
            }
        }
        val brRow = sliderRow("Brillo", brBar)
        brRow.addView(tile(if (prefs.getBoolean("auto_bright", true)) "Auto ✓" else "Auto", null, 0f, 14f, {
            prefs.edit().putBoolean("auto_bright", true).apply(); lastBright = -2f; brightAt = 0L; render()
        }), LinearLayout.LayoutParams(p(86), p(40)).apply { leftMargin = p(8) })
        tune.addView(brRow)
        val al = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val alOn = prefs.getBoolean("spd_alert", false)
        al.addView(tile("Aviso: ${if (alOn) "sí" else "no"}", null, 0f, 15f, {
            prefs.edit().putBoolean("spd_alert", !prefs.getBoolean("spd_alert", false)).apply(); render()
        }), LinearLayout.LayoutParams(p(120), p(46)))
        tripLimitV = txt("${kmhToUnit(prefs.getInt("spd_limit", 100).toFloat()).roundToInt()} ${if (speedUnit() == "mph") "mph" else "km/h"}", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        al.addView(tile("−", null, 0f, 22f, {
            prefs.edit().putInt("spd_limit", (prefs.getInt("spd_limit", 100) - 5).coerceAtLeast(30)).apply(); render()
        }), LinearLayout.LayoutParams(p(56), p(46)).apply { leftMargin = p(10) })
        al.addView(tripLimitV, LinearLayout.LayoutParams(p(120), WRAP_CONTENT))
        al.addView(tile("+", null, 0f, 22f, {
            prefs.edit().putInt("spd_limit", (prefs.getInt("spd_limit", 100) + 5).coerceAtMost(200)).apply(); render()
        }), LinearLayout.LayoutParams(p(56), p(46)))
        tune.addView(al, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        tune.addView(txt("Al pasar el límite, la velocidad se pone roja y suena un aviso corto. El brillo manual vale mientras el launcher está a la vista.", 11f, muted).apply { setPadding(0, p(6), 0, 0) })
        right.addView(tune, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = p(10) })
        body.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        updateTrip()
        return col
    }


    // ------------------------------------------------------------ v2.28: modos, barra superior propia, ecualizador

    private var tapFx: TapFx? = null
    private var barTime: TextView? = null
    private var barTemp: TextView? = null
    private var barNote: ImageView? = null
    private var barGps: ImageView? = null
    private var barWifi: ImageView? = null
    private var barBt: ImageView? = null
    private var barVol: TextView? = null
    private var barChip: TextView? = null
    private var islandMusic = false
    private var barSig = ""
    private var qpLayer: FrameLayout? = null
    private var qpPanel: LinearLayout? = null
    private var qpVol: TouchBar? = null
    private var qpBright: TouchBar? = null
    private var qpOpen = false
    private val qpAutoClose = Runnable { qpToggle(false) }

    /** Efecto al tocar el fondo del inicio; cambia según el modo. */
    private class TapFx(c: Context) : View(c) {
        private class P(var x: Float, var y: Float, val vx: Float, val vy: Float, val t: Long, val life: Long, val kind: Int, val sz: Float)
        private val ps = ArrayList<P>()
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        private var col = Color.CYAN
        private val rnd = java.util.Random()
        private val heart = Path()

        fun burst(x: Float, y: Float, m: Modes.M) {
            val now = SystemClock.uptimeMillis(); col = m.acc
            when (m.id) {
                "pink" -> repeat(10) { ps.add(P(x, y, (rnd.nextFloat() - 0.5f) * 220f, -(90f + rnd.nextFloat() * 160f), now, 1100, 1, 12f + rnd.nextFloat() * 14f)) }
                "sport" -> { ps.add(P(x, y, 0f, 0f, now, 420, 0, 1f)); repeat(9) { val a = rnd.nextFloat() * 6.283f; ps.add(P(x, y, Math.cos(a.toDouble()).toFloat() * 900f, Math.sin(a.toDouble()).toFloat() * 900f, now, 380, 2, 1f)) } }
                "exec" -> { ps.add(P(x, y, 0f, 0f, now, 900, 0, 1f)); repeat(6) { val a = it * 1.047f; ps.add(P(x, y, Math.cos(a.toDouble()).toFloat() * 120f, Math.sin(a.toDouble()).toFloat() * 120f, now, 900, 3, 9f)) } }
                "trip" -> { ps.add(P(x, y, 0f, 0f, now, 900, 0, 1f)); repeat(7) { val a = it * 0.8976f; ps.add(P(x, y, Math.cos(a.toDouble()).toFloat() * 100f, Math.sin(a.toDouble()).toFloat() * 100f, now, 900, 4, 6f)) } }
                else -> ps.add(P(x, y, 0f, 0f, now, 700, 0, 1f))
            }
            invalidate()
        }

        override fun onDraw(cv: Canvas) {
            val now = SystemClock.uptimeMillis()
            val it = ps.iterator()
            while (it.hasNext()) {
                val q = it.next(); val age = now - q.t
                if (age > q.life) { it.remove(); continue }
                val f = age / q.life.toFloat(); val a = ((1f - f) * 230).toInt().coerceIn(0, 255)
                val sec = age / 1000f
                val cx = q.x + q.vx * sec; val cy = q.y + q.vy * sec
                pt.color = (col and 0x00FFFFFF) or (a shl 24)
                when (q.kind) {
                    0 -> { pt.style = Paint.Style.STROKE; pt.strokeWidth = 4f * (1f - f) + 1f; cv.drawCircle(q.x, q.y, 14f + f * 150f, pt) }
                    1 -> { pt.style = Paint.Style.FILL; val sz = q.sz; heart.reset(); heart.moveTo(0f, sz * 0.35f); heart.cubicTo(-sz, -sz * 0.2f, -sz * 0.5f, -sz * 0.9f, 0f, -sz * 0.4f); heart.cubicTo(sz * 0.5f, -sz * 0.9f, sz, -sz * 0.2f, 0f, sz * 0.35f)
                        cv.save(); cv.translate(cx, cy); cv.drawPath(heart, pt); cv.restore() }
                    2 -> { pt.style = Paint.Style.STROKE; pt.strokeWidth = 4f; cv.drawLine(cx, cy, cx - q.vx * 0.05f, cy - q.vy * 0.05f, pt) }
                    3 -> { pt.style = Paint.Style.FILL; cv.save(); cv.translate(cx, cy); cv.rotate(45f); cv.drawRect(-q.sz / 2, -q.sz / 2, q.sz / 2, q.sz / 2, pt); cv.restore() }
                    else -> { pt.style = Paint.Style.FILL; cv.drawCircle(cx, cy, q.sz * (1f - f * 0.5f), pt) }
                }
            }
            if (ps.isNotEmpty()) postInvalidateOnAnimation()
        }
    }

    // ---- barra superior propia (reemplaza a la del sistema)
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        try {
            if (!prefs.getBoolean("own_bar", true)) {
                if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
                else window.decorView.systemUiVisibility = 0
                return
            }
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
        } catch (e: Exception) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemBars(); handler.postDelayed({ askFuelIfPossible() }, 700) }
    }

    private fun barH() = if (prefs.getBoolean("own_bar", true)) p(36) else 0

    private fun wrapBar(content: View): View {
        val own = prefs.getBoolean("own_bar", true)
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val holder = FrameLayout(this).apply { if (own && page != Page.HOME) setPadding(0, p(36), 0, 0) }
        holder.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (own) {
            root.addView(buildBar(), FrameLayout.LayoutParams(MATCH_PARENT, p(36), Gravity.TOP))
            root.addView(buildQuickPanel(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(buildVoiceLayer(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    private fun barIcon(res: Int, onClick: (() -> Unit)?): ImageView = ImageView(this).apply {
        setImageResource(res); setColorFilter(Color.parseColor("#55FFFFFF"))
        setPadding(p(2), p(2), p(2), p(2))
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    private fun buildBar(): View {
        val m = mode()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(16), 0, p(16), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xC7060A14.toInt(), 0x00060A14))
            isClickable = true
            setOnClickListener { qpToggle() }
        }
        barTime = txt("", 16f, Color.WHITE, true).apply { isClickable = true; setOnClickListener { openCalendar() }; if (page == Page.HOME) visibility = View.GONE }
        barTemp = txt("", 14f, Color.parseColor("#B3FFFFFF")).apply { setPadding(p(10), 0, 0, 0); if (page == Page.HOME) visibility = View.GONE }
        bar.addView(barTime); bar.addView(barTemp)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        barChip = txt("● ${m.name}", 13f, Color.WHITE, true).apply {
            setPadding(p(16), p(4), p(16), p(4)); isClickable = true
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = p(330); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = p(18).toFloat(); setColor(Color.BLACK); setStroke(max(1, p(1)), 0x33FFFFFF) }
            setOnClickListener { if (islandMusic) { openMusic() } else modePicker() }
            setOnLongClickListener { assistantDialog(); true }
        }
        bar.addView(barChip)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val lp = { LinearLayout.LayoutParams(p(24), p(24)).apply { leftMargin = p(10) } }
        bar.addView(barIcon(R.drawable.ic_mic, { openVoice() }).also { it.setColorFilter(m.acc) }, lp())
        barGps = barIcon(R.drawable.ic_place, { page = Page.TRIP; render() }); bar.addView(barGps, lp())
        barWifi = barIcon(R.drawable.ic_wifi, { openSettings(Settings.ACTION_WIFI_SETTINGS) }); bar.addView(barWifi, lp())
        barBt = barIcon(R.drawable.ic_bluetooth, { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }); bar.addView(barBt, lp())
        bar.addView(barIcon(R.drawable.ic_volume, { qpToggle() }), lp())
        barVol = txt("", 13f, Color.WHITE).apply { setPadding(p(4), 0, 0, 0) }
        bar.addView(barVol)
        updateBar(true)
        return bar
    }

    private fun updateBar(force: Boolean = false) {
        val tv = barTime ?: return
        val c = Calendar.getInstance()
        val ts = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val gps = TripService.running && TripService.lastFixMs != 0L && System.currentTimeMillis() - TripService.lastFixMs < 8000L
        val wifi = try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) { false }
        val bt = try { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (e: Throwable) { false }
        val playing = try { nowPlaying()?.playing == true } catch (e: Exception) { false }
        val vol = try { val am = audioMgr(); (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)).roundToInt() } catch (e: Exception) { 0 }
        val tmp = tempText() ?: ""
        val np = if (playing) nowPlaying() else null
        val voiceOn = voiceLayer?.visibility == View.VISIBLE
        val sig = "$ts|$gps|$wifi|$bt|$playing|$vol|$tmp|${mode().id}|${np?.title}|$voiceOn|$page"
        if (!force && sig == barSig) return
        barSig = sig
        val on = mode().acc; val off = Color.parseColor("#55FFFFFF")
        tv.text = ts
        barTemp?.text = if (tmp.isNotEmpty()) tmp else ""
        barGps?.setColorFilter(if (gps) on else off)
        barWifi?.setColorFilter(if (wifi) on else off)
        barBt?.setColorFilter(if (bt) on else off)
        islandMusic = np != null && !np.title.isNullOrBlank() && !voiceOn
        val isl = barChip
        if (isl != null) {
            isl.visibility = if (page == Page.HOME && !voiceOn) View.GONE else View.VISIBLE
            val label = when {
                voiceOn -> "◉  Te escucho"
                islandMusic -> "♪  " + np!!.title + (if (!np.artist.isNullOrBlank()) " · " + np.artist else "")
                else -> "●  " + mode().name
            }
            val sp = android.text.SpannableString(label)
            sp.setSpan(android.text.style.ForegroundColorSpan(on), 0, 1, 0)
            isl.text = sp
        }
        barVol?.text = "$vol%"
    }

    private fun buildQuickPanel(): FrameLayout {
        val m = mode()
        val lay = FrameLayout(this).apply { visibility = View.GONE }
        lay.addView(View(this).apply { setBackgroundColor(0x80000000.toInt()); setOnClickListener { qpToggle(false) } }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(24), p(36) + p(10), p(24), p(16))
            val r = p(26).toFloat()
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                setColor((m.tint and 0x00FFFFFF) or 0xF5000000.toInt()); setStroke(max(1, p(1)), (m.acc and 0x00FFFFFF) or 0x66000000)
            }
            isClickable = true
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(res: Int, label: String, go: () -> Unit) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; setOnClickListener { qpToggle(false); go() } }
            val c = FrameLayout(this).apply { background = oval((m.acc and 0x00FFFFFF) or 0x33000000, (m.acc and 0x00FFFFFF) or 0x99000000.toInt()) }
            c.addView(ImageView(this).apply { setImageResource(res); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(p(28), p(28), Gravity.CENTER))
            b.addView(c, LinearLayout.LayoutParams(p(58), p(58)))
            b.addView(txt(label, 12f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(0, p(4), 0, 0) })
            b.pressFx()
            actions.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        act(R.drawable.ic_wifi, "Wi-Fi") { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        act(R.drawable.ic_bluetooth, "Bluetooth") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
        act(R.drawable.ic_settings, "Ajustes") { openSettings(Settings.ACTION_SETTINGS) }
        act(R.drawable.ic_palette, "Modo") { modePicker() }
        act(R.drawable.ic_calendar, "Calendario") { openCalendar() }
        act(R.drawable.ic_car, "Viaje") { page = Page.TRIP; render() }
        act(R.drawable.ic_mic, "Voz") { openVoice() }
        act(R.drawable.ic_music, "Música") { openMusic() }
        panel.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fun slider(label: String, bar: TouchBar) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(txt(label, 15f, Color.WHITE), LinearLayout.LayoutParams(p(90), WRAP_CONTENT))
            r.addView(bar, LinearLayout.LayoutParams(0, p(40), 1f))
            panel.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(10) })
        }
        qpVol = TouchBar(this).apply { trackC = th.track; fillC = m.acc; live = true; onSeek = { f -> setVolumeFrac(f) } }
        qpBright = TouchBar(this).apply {
            trackC = th.track; fillC = m.acc; live = true
            onSeek = { f -> val lv = 0.15f + 0.85f * f; prefs.edit().putBoolean("auto_bright", false).putFloat("bright_level", lv).apply(); setBright(lv) }
        }
        slider("Volumen", qpVol!!); slider("Brillo", qpBright!!)
        lay.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        qpLayer = lay; qpPanel = panel
        return lay
    }

    private fun qpToggle(open: Boolean = !qpOpen) {
        val lay = qpLayer ?: return
        val panel = qpPanel ?: return
        if (open == qpOpen) return
        qpOpen = open
        handler.removeCallbacks(qpAutoClose)
        val m = mode()
        val ip = if (m.bouncy) android.view.animation.OvershootInterpolator(1.3f) else android.view.animation.DecelerateInterpolator()
        panel.animate().cancel()
        if (open) {
            try {
                val am = audioMgr(); qpVol?.frac = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                qpBright?.frac = ((prefs.getFloat("bright_level", 1f) - 0.15f) / 0.85f).coerceIn(0f, 1f)
            } catch (e: Exception) { }
            lay.visibility = View.VISIBLE
            panel.translationY = -p(320).toFloat(); panel.alpha = 0f
            panel.animate().translationY(0f).alpha(1f).setDuration(m.pressMs * 2 + 100).setInterpolator(ip).start()
            handler.postDelayed(qpAutoClose, 12000)
        } else {
            panel.animate().translationY(-p(320).toFloat()).alpha(0f).setDuration(160).withEndAction { lay.visibility = View.GONE }.start()
        }
    }

    // ---- selector de modo
    private fun setMode(id: String) {
        prefs.edit().putString("app_mode", id).putInt("mode_var", 0).apply()
        TechBase.base = Modes.get(id).acc
        bgCacheKey = null
        iconCache.clear()
        modeFx = true
        render()
    }

    private fun modePicker() {
        val cur = mode().id
        val sv = HorizontalScrollView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(14), p(10), p(14), p(10)) }
        sv.addView(row)
        var dlg: AlertDialog? = null
        for (m in Modes.list) {
            val card = FrameLayout(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(m.bgA, m.bgB)).apply {
                    cornerRadius = p(20).toFloat()
                    if (m.id == cur) setStroke(p(3), m.acc) else setStroke(max(1, p(1)), 0x55FFFFFF)
                }
                isClickable = true
                setOnClickListener { dlg?.dismiss(); setMode(m.id) }
            }
            val shapes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 3) shapes.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = if (m.shape == 1) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                    cornerRadius = p(when (m.shape) { 2 -> 4; 3 -> 2; 4 -> 12; else -> 8 }).toFloat()
                    colors = intArrayOf(m.acc, m.acc2); orientation = GradientDrawable.Orientation.TL_BR
                }
            }, LinearLayout.LayoutParams(p(34), p(34)).apply { rightMargin = p(8) })
            card.addView(shapes, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(p(16), p(16), 0, 0) })
            val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tcol.addView(txt(m.name, 19f, Color.WHITE, true))
            tcol.addView(txt(m.sub, 12.5f, Color.parseColor("#CCFFFFFF")))
            card.addView(tcol, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(p(16), 0, p(16), p(14)) })
            row.addView(card, LinearLayout.LayoutParams(p(190), p(150)).apply { rightMargin = p(12) })
            card.pressFx()
        }
        dlg = AlertDialog.Builder(this).setTitle("Modo de pantalla").setView(sv).setNegativeButton("Cerrar", null).create()
        dlg.show()
    }

    // ---- ecualizador y mezcla DJ
    private class VBar(c: Context) : View(c) {
        var frac = 0.5f
        var col = Color.CYAN
        var onSeek: ((Float) -> Unit)? = null
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val tw = max(8f, w * 0.14f); val cx = w / 2f
            val top = h * 0.04f; val bot = h * 0.96f
            pt.style = Paint.Style.FILL; pt.color = 0x33FFFFFF
            cv.drawRoundRect(cx - tw / 2, top, cx + tw / 2, bot, tw / 2, tw / 2, pt)
            val mid = (top + bot) / 2f
            pt.color = 0x66FFFFFF; cv.drawRect(cx - tw * 1.2f, mid - 1f, cx + tw * 1.2f, mid + 1f, pt)
            val ky = bot - (bot - top) * frac.coerceIn(0f, 1f)
            val a = min(ky, mid); val b = max(ky, mid)
            pt.color = (col and 0x00FFFFFF) or 0x33000000; cv.drawRoundRect(cx - tw, a, cx + tw, b, tw, tw, pt)
            pt.color = col; cv.drawRoundRect(cx - tw / 2, a, cx + tw / 2, b, tw / 2, tw / 2, pt)
            pt.color = Color.WHITE; cv.drawCircle(cx, ky, tw * 1.25f, pt)
            pt.color = col; cv.drawCircle(cx, ky, tw * 0.55f, pt)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    frac = (1f - (e.y - height * 0.04f) / (height * 0.92f)).coerceIn(0f, 1f); invalidate(); onSeek?.invoke(frac)
                }
            }
            return true
        }
    }

    private fun dbText(mb: Int) = String.format(Locale.US, "%+.0f", mb / 100f)
    private fun hzText(hz: Int) = if (hz >= 1000) String.format(Locale.US, "%.1f", hz / 1000f).removeSuffix(".0") + "k" else "$hz"

    private fun buildEq(): View {
        val m = mode()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p(20), p(8), p(20), p(4)) }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }), LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(TextView(this).apply { text = "Ecualizador y mezcla DJ"; size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(p(24), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(tile("Reproductor", null, 0f, 16f, { openMusic() }), LinearLayout.LayoutParams(p(160), p(64)))
        header.addView(tile("EQ: ${if (AudioFx.eqOn) "ACTIVADO" else "APAGADO"}", null, 0f, 16f, { AudioFx.eqOn = !AudioFx.eqOn; render() }), LinearLayout.LayoutParams(p(190), p(64)).apply { leftMargin = p(8) })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(20), p(4), p(20), p(10)) }

        // izquierda: presets + bandas
        val left = tripCard("ECUALIZADOR")
        if (!AudioFx.supported) {
            left.addView(txt("Este equipo no permite ecualizar el audio del launcher. La mezcla DJ sí funciona.", 16f, Color.parseColor("#FFE082")), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(12) })
        } else {
            val ps = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val names = AudioFx.presets.keys.toList() + (if (AudioFx.preset == "Personalizado") listOf("Personalizado") else emptyList())
            for (n in names) {
                val sel = n == AudioFx.preset
                chips.addView(txt(n, 14f, Color.WHITE, sel).apply {
                    setPadding(p(14), p(8), p(14), p(8)); isClickable = true
                    background = GradientDrawable().apply { cornerRadius = p(18).toFloat(); setColor(if (sel) (m.acc and 0x00FFFFFF) or 0x88000000.toInt() else 0x26FFFFFF); if (sel) setStroke(max(1, p(1)), m.acc) }
                    setOnClickListener { if (n != "Personalizado") { AudioFx.setPreset(n); render() } }
                    pressFx()
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
            }
            ps.addView(chips)
            left.addView(ps, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) })
            val bands = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val lv = AudioFx.levels()
            for (i in 0 until AudioFx.bands) {
                val colm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val dbv = txt(dbText(lv.getOrElse(i) { 0 }) + " dB", 14f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                val bar = VBar(this).apply {
                    this.col = m.acc
                    frac = (lv.getOrElse(i) { 0 } - AudioFx.minMb).toFloat() / (AudioFx.maxMb - AudioFx.minMb)
                    onSeek = { f ->
                        val mb = (Math.round((AudioFx.minMb + f * (AudioFx.maxMb - AudioFx.minMb)) / 100f) * 100)
                        AudioFx.setBand(i, mb); dbv.text = dbText(mb) + " dB"
                    }
                }
                colm.addView(dbv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                colm.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                colm.addView(txt(hzText(AudioFx.freqs.getOrElse(i) { 0 }) + " Hz", 12.5f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                bands.addView(colm, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
            left.addView(bands, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = p(8) })
        }
        body.addView(left, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.3f).apply { rightMargin = p(10) })

        // derecha: efectos + mezcla DJ
        val right = tripCard("EFECTOS Y MEZCLA DJ")
        fun fx(label: String, init: Float, valueText: (Float) -> String, onChange: (Float) -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val vt = txt(valueText(init), 14f, Color.WHITE, true).apply { gravity = Gravity.END }
            r.addView(txt(label, 15f, Color.WHITE), LinearLayout.LayoutParams(p(104), WRAP_CONTENT))
            r.addView(TouchBar(this).apply { trackC = th.track; fillC = m.acc; live = true; frac = init; onSeek = { f -> onChange(f); vt.text = valueText(f) } }, LinearLayout.LayoutParams(0, p(38), 1f))
            r.addView(vt, LinearLayout.LayoutParams(p(64), WRAP_CONTENT))
            right.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) })
        }
        fx("Graves", AudioFx.bassStrength / 1000f, { "${(it * 100).roundToInt()}%" }) { AudioFx.bassStrength = (it * 1000).roundToInt() }
        fx("Espacial", AudioFx.virtStrength / 1000f, { "${(it * 100).roundToInt()}%" }) { AudioFx.virtStrength = (it * 1000).roundToInt() }
        fx("Volumen extra", AudioFx.gainMb / 1500f, { "+${(it * 15).roundToInt()} dB" }) { AudioFx.gainMb = (it * 1500).roundToInt() }
        right.addView(txt("MEZCLA ENTRE CANCIONES", 12f, muted, true).apply { letterSpacing = 0.12f; setPadding(0, p(10), 0, 0) })
        val dj = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val xv = txt(if (AudioFx.xfadeSec == 0) "Apagada" else "${AudioFx.xfadeSec} s", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        dj.addView(tile("−", null, 0f, 22f, { AudioFx.xfadeSec = (AudioFx.xfadeSec - 1).coerceAtLeast(0); xv.text = if (AudioFx.xfadeSec == 0) "Apagada" else "${AudioFx.xfadeSec} s" }), LinearLayout.LayoutParams(p(56), p(44)))
        dj.addView(xv, LinearLayout.LayoutParams(p(110), WRAP_CONTENT))
        dj.addView(tile("+", null, 0f, 22f, { AudioFx.xfadeSec = (AudioFx.xfadeSec + 1).coerceAtMost(12); xv.text = "${AudioFx.xfadeSec} s" }), LinearLayout.LayoutParams(p(56), p(44)))
        right.addView(dj, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(4) })
        val tg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tg.addView(tile("Al cambiar a mano: ${if (AudioFx.xfadeManual) "mezcla" else "corte"}", null, 0f, 14f, { AudioFx.xfadeManual = !AudioFx.xfadeManual; render() }), LinearLayout.LayoutParams(0, p(46), 1f).apply { rightMargin = p(6) })
        tg.addView(tile("Todo el audio (prueba): ${if (AudioFx.global) "sí" else "no"}", null, 0f, 14f, { AudioFx.global = !AudioFx.global; render() }), LinearLayout.LayoutParams(0, p(46), 1f))
        right.addView(tg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })
        right.addView(tile("Restablecer todo", null, 0f, 14f, { AudioFx.reset(); render() }), LinearLayout.LayoutParams(MATCH_PARENT, p(42)).apply { topMargin = p(6) })
        right.addView(txt("La mezcla y el ecualizador actúan sobre la música guardada del launcher. La música por Bluetooth o la radio dependen de tu equipo: prueba «Todo el audio».", 11f, muted).apply { setPadding(0, p(6), 0, 0) })
        body.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }


    // ------------------------------------------------------------ v2.29: asistente de voz (v2.30: menú compacto e isla)

    private var voice: VoiceEngine? = null
    private var voiceLayer: FrameLayout? = null
    private var voicePanel: LinearLayout? = null
    private var voiceOrb: VoiceOrb? = null
    private var voiceTitle: TextView? = null
    private var voiceText: TextView? = null
    private var voiceOpen = false
    private val voiceClose = Runnable { closeVoice() }

    private fun hasMicPerm() = checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED

    private fun buildVoiceLayer(): FrameLayout {
        val m = mode()
        val lay = FrameLayout(this).apply { visibility = View.GONE }
        lay.addView(View(this).apply { setBackgroundColor(0x80000000.toInt()); setOnClickListener { closeVoice() } }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p(24), p(12), p(24), p(16)); isClickable = true
            val r = p(28).toFloat()
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor((m.tint and 0x00FFFFFF) or 0xF7000000.toInt()); setStroke(max(1, p(1)), (m.acc and 0x00FFFFFF) or 0x77000000)
            }
        }
        val orb = VoiceOrb(this)
        orb.setOnClickListener { openVoice() }
        panel.addView(orb, LinearLayout.LayoutParams(p(150), p(150)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceTitle = txt("TE ESCUCHO…", 13f, m.acc, true).apply { letterSpacing = 0.2f }
        voiceText = txt("", 26f, Color.WHITE).apply { maxLines = 3; setPadding(0, p(4), 0, p(6)) }
        col.addView(voiceTitle)
        col.addView(voiceText)
        col.addView(txt("Prueba: «pon música» · «cambia de canción» · «llévame a casa» · «quiero ir a casa» · «abre YouTube» · «qué hora es»", 12.5f, Color.parseColor("#99FFFFFF")))
        panel.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panel.addView(txt("✕", 22f, Color.WHITE).apply { setPadding(p(14), p(8), p(6), p(8)); isClickable = true; setOnClickListener { closeVoice() } })
        lay.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        voiceLayer = lay; voicePanel = panel; voiceOrb = orb
        return lay
    }

    private fun showVoice(on: Boolean) {
        voiceOpen = on
        handler.removeCallbacks(voiceClose)
        val lay = voiceLayer ?: return
        val panel = voicePanel ?: return
        panel.animate().cancel()
        if (on) {
            lay.visibility = View.VISIBLE
            panel.translationY = p(260).toFloat()
            panel.animate().translationY(0f).setDuration(mode().pressMs * 2 + 100).setInterpolator(if (mode().bouncy) android.view.animation.OvershootInterpolator(1.2f) else android.view.animation.DecelerateInterpolator()).start()
        } else {
            panel.animate().translationY(p(260).toFloat()).setDuration(160).withEndAction { lay.visibility = View.GONE }.start()
        }
    }

    private fun closeVoice() {
        try { voice?.stop() } catch (e: Exception) { }
        showVoice(false)
    }

    private fun voiceState(st: Int, title: String, text: String) {
        voiceOrb?.state = st
        voiceTitle?.text = title
        voiceTitle?.setTextColor(when (st) { 4 -> Color.parseColor("#FF5A4F"); 3 -> Color.parseColor("#69F0AE"); else -> mode().acc })
        voiceText?.text = text
    }

    private fun openVoice() {
        if (!hasMicPerm()) { requestPermissions(arrayOf("android.permission.RECORD_AUDIO"), REQ_MIC); return }
        val v = voice ?: VoiceEngine(applicationContext).also { voice = it }
        if (voiceLayer == null) return
        if (!v.available()) { textCommandDialog(); return }
        showVoice(true)
        voiceState(1, "TE ESCUCHO…", "")
        v.start(object : VoiceEngine.Cb {
            override fun onPartial(t: String) { voiceText?.text = t }
            override fun onLevel(l: Float) { voiceOrb?.level = l }
            override fun onFinal(t: String) { runVoice(t) }
            override fun onError(msg: String) {
                voiceState(4, "NO PUDE ESCUCHAR", msg)
                handler.postDelayed(voiceClose, 2600)
            }
        })
    }

    /** Si la respuesta tiene frase grabada grabada por el usuario (res/raw), la reproduce; si no, habla la voz del sistema. */
    private fun speakClip(reply: String): Boolean {
        val r = norm(reply)
        if (r == "buscando estaciones cercanas") return true // el mapa habla por su cuenta
        val key = when {
            r == "musica en pausa" -> "pause"
            r == "siguiente cancion" -> "next"
            r == "subi el volumen" -> "vol_up"
            r == "baje el volumen" -> "vol_down"
            r == "modo manejo" -> "drive_mode"
            r == "reloj grande" -> "big_clock"
            r == "reproduciendo" -> "ok"
            r.startsWith("te guio al taller") -> "dest_taller"
            r.startsWith("navegando a ") -> when {
                r.contains("casa") -> "dest_casa"
                r.contains("trabajo") -> "dest_trabajo"
                r.contains("taller") -> "dest_taller"
                else -> "nav_start"
            }
            r.startsWith("no te entendi") || r.startsWith("no entendi") -> "not_understood"
            else -> return false
        }
        return VozClips.play(this, key)
    }

    // ------------------------------------------------------------ «Hey <marca>»
    private fun assistantDialog() {
        val ready = VoskModels.ready(this)
        val on = prefs.getBoolean("hey_on", false)
        val items = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        fun add(t: String, a: () -> Unit) { items.add(t); acts.add(a) }
        if (!ready) add("Descargar la voz (unos 55 MB, con internet)") { downloadVoice() }
        else {
            add(if (on) "Apagar «Hey ${wakeName()}»" else "Encender «Hey ${wakeName()}»") {
                if (!hasMicPerm()) { requestPermissions(arrayOf("android.permission.RECORD_AUDIO"), REQ_MIC); toast("Da el permiso del micrófono y vuelve a encender") }
                else if (on) { prefs.edit().putBoolean("hey_on", false).apply(); AsistenteService.stop(this); toast("«Hey ${wakeName()}» apagado") }
                else { prefs.edit().putBoolean("hey_on", true).apply(); AsistenteService.start(this); toast("«Hey ${wakeName()}» encendido: cargando la voz…") }
            }
            add("Entrenar la voz del conductor  ${if (SpeakerDb.has(this, "conductor")) "✓" else ""}") { train("conductor") }
            add("Entrenar la voz del acompañante  ${if (SpeakerDb.has(this, "acompanante")) "✓" else ""}") { train("acompanante") }
            add("Borrar las voces guardadas") { SpeakerDb.clear(this); toast("Voces borradas: te preguntará «¿Con quién hablo?»") }
        }
        add("Nombre para llamarlo: «Hey ${wakeName()}» (cambiar)") { renameWake() }
        add("Voz de los avisos: ${if (VozClips.clipsOn(this)) "grabada incluida" else "voz del sistema"} (cambiar)") {
            val nv = !VozClips.clipsOn(this); prefs.edit().putBoolean("voz_clips", nv).apply()
            toast(if (nv) "Usará las grabaciones incluidas" else "Usará la voz del sistema"); VozClips.play(this, "ok")
        }
        add("Permitir abrir el launcher desde otras apps") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { toast("No pude abrir ese ajuste") }
        }
        AlertDialog.Builder(this).setTitle("Hey ${wakeName()} · ${if (on) AsistenteService.status else "apagado"}")
            .setItems(items.toTypedArray()) { _, w -> acts[w]() }.setNegativeButton("Cerrar", null).show()
    }

    /** Cómo quieres llamar al asistente: por defecto la marca de tu auto; puedes poner otro nombre (escríbelo como suena en español, p. ej. «Nisán»). */
    private fun renameWake() {
        val et = EditText(this).apply { setSingleLine(); setText(prefs.getString("wake_custom", "") ?: ""); hint = wakeName() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(10), p(24), 0); addView(et) }
        fun apply() {
            AsistenteService.applyWake(CarCatalog.wakeWord(this, prefs))
            if (AsistenteService.running) { AsistenteService.stop(this); handler.postDelayed({ AsistenteService.start(this) }, 600) }
        }
        AlertDialog.Builder(this).setTitle("¿Cómo quieres llamarlo?")
            .setMessage("Se activa diciendo «Hey» y este nombre. Por defecto usa la marca de tu auto. Escríbelo como suena en español (mínimo 3 letras).")
            .setView(col)
            .setPositiveButton("Guardar") { _, _ ->
                val t = et.text.toString().trim()
                if (t.length in 1..2) toast("Muy corto: usa al menos 3 letras") else { prefs.edit().putString("wake_custom", t).apply(); apply(); toast("Ahora di «Hey ${wakeName()}»") }
            }
            .setNeutralButton("Usar la marca") { _, _ -> prefs.edit().remove("wake_custom").apply(); apply(); toast("Ahora di «Hey ${wakeName()}»") }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun downloadVoice() {
        val tv = TextView(this).apply { text = "Preparando…"; setPadding(p(30), p(20), p(30), p(10)); textSize = 16f }
        val d = AlertDialog.Builder(this).setTitle("Descargando la voz").setView(tv).setCancelable(false).show()
        VoskModels.download(this) { err ->
            d.dismiss()
            if (err == null) { toast("Voz lista. Enciéndelo y entrena las voces"); VozClips.play(this, "done") } else toast("No pude descargar: $err")
        }
        val tick = object : Runnable { override fun run() { if (d.isShowing) { tv.text = VoskModels.msg; handler.postDelayed(this, 500) } } }
        handler.post(tick)
    }

    private fun train(who: String) {
        if (!hasMicPerm()) { requestPermissions(arrayOf("android.permission.RECORD_AUDIO"), REQ_MIC); return }
        prefs.edit().putBoolean("hey_on", true).apply()
        if (!AsistenteService.running) AsistenteService.start(this)
        AsistenteService.enroll = who; AsistenteService.enrollLeft = 3
        AlertDialog.Builder(this).setTitle("Entrenar la voz de ${who.replaceFirstChar { it.uppercase() }}")
            .setMessage("Di «Hey ${wakeName()}» 3 veces, con tu voz normal y una pausa entre cada una. Cuando termine, te saludará por tu nombre. (Si recién lo encendiste, espera unos segundos a que cargue la voz.)")
            .setPositiveButton("Entendido", null).show()
    }

    private fun runVoice(text: String) {
        voiceState(2, "PROCESANDO", text)
        val reply = try { handleVoice(text) } catch (e: Exception) { "No pude hacerlo" }
        voiceState(3, "LISTO", "“$text”\n$reply")
        if (!speakClip(reply)) voice?.speak(reply)
        handler.removeCallbacks(voiceClose)
        handler.postDelayed(voiceClose, 3200)
    }

    private fun textCommandDialog() {
        val et = android.widget.EditText(this).apply { hint = "Ej.: llévame a casa"; setSingleLine(); setPadding(p(24), p(16), p(24), p(16)) }
        AlertDialog.Builder(this).setTitle("Tu radio no tiene reconocimiento de voz")
            .setMessage("Escribe el comando. (En Ajustes de Android activa «Entrada de voz» de Google para hablarle a la pantalla.)")
            .setView(et)
            .setPositiveButton("Ejecutar") { _, _ -> val t = et.text.toString(); if (t.isNotBlank()) toast(handleVoice(t)) }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---- lugares guardados
    private fun placesForm() {
        form("Lugares (dirección o lugar; vacío = sin guardar)", listOf(
            F("place_casa", "Casa", KIND_TEXT),
            F("place_trabajo", "Trabajo", KIND_TEXT),
            F("place_pareja", "Pareja", KIND_TEXT),
            F("place_familia", "Familia", KIND_TEXT),
            F("place_taller", "Taller (latitud,longitud)", KIND_TEXT)
        ), extra = { b ->
            b.setNeutralButton("Casa = estoy aquí") { _, _ ->
                if (!TripService.lat.isNaN()) { prefs.edit().putString("place_casa", String.format(Locale.US, "%.6f,%.6f", TripService.lat, TripService.lon)).apply(); toast("Casa guardada con tu ubicación actual") }
                else toast("Aún no hay señal de GPS")
            }
        })
    }

    // ---- comprension de comandos
    private fun norm(x: String): String = java.text.Normalizer.normalize(x.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ,.]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun navigateTo(query: String, label: String) {
        if (MapData.ready(this)) {
            try { startActivity(Intent(this, MapActivity::class.java).putExtra("dest_q", query).putExtra("dest_name", label).putExtra("go", true)); return } catch (e: Exception) { }
        }
        try {
            val gm = "com.google.android.apps.maps"
            val nav = navApp()
            val useG = nav?.pkg == gm || (nav == null && apps.any { it.pkg == gm })
            val i = if (useG) Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query))).setPackage(gm)
            else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).also { if (nav != null) it.setPackage(nav.pkg) }
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { toast("No pude abrir el mapa para $label") }
    }

    private fun destFor(raw: String): Pair<String, String>? {
        val t = norm(raw).removePrefix("a ").removePrefix("al ").removePrefix("la ").removePrefix("el ").trim()
        fun place(key: String, nm: String): Pair<String, String>? {
            val v = ps(key)
            if (v == null) { toast("Guarda tu $nm en Ajustes > Lugares"); placesForm(); return null }
            return Pair(nm, v)
        }
        Favoritos.match(raw)?.let { f ->
            val c = Favoritos.coords(prefs, f)
            if (c == null) { toast("Guarda tu lugar «${f.label}» en Ajustes > Lugares"); placesForm(); return null }
            return Pair(f.spoken, String.format(Locale.US, "%.7f,%.7f", c.first, c.second))
        }
        return when {
            t == "casa" || t.startsWith("mi casa") -> place("place_casa", "casa")
            t.contains("trabajo") || t.contains("oficina") -> place("place_trabajo", "trabajo")
            t.contains("taller") || t.contains("mecanico") -> if (ps("place_taller") != null) place("place_taller", "taller") else Pair("el taller", "TALLER")
            t.isBlank() -> null
            else -> Pair(raw, raw)
        }
    }

    private fun streamSearch(q: String, preferYt: Boolean = false): String {
        val order = if (preferYt) listOf("com.google.android.apps.youtube.music", "com.spotify.music") else listOf("com.spotify.music", "com.google.android.apps.youtube.music")
        val pkg = (listOfNotNull(overrideApp("music_pkg")?.pkg) + order).firstOrNull { p0 -> apps.any { it.pkg == p0 } }
        if (pkg == null) return "No tengo música guardada de $q ni una app de música instalada"
        try {
            startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                .putExtra(android.app.SearchManager.QUERY, q).putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { launch(pkg) }
        return "Buscando $q en ${appLabel(pkg)}"
    }

    private fun playLocalMatching(query: String): String? {
        val stop = setOf("la", "el", "los", "las", "de", "del", "un", "una", "algo", "mi", "mis", "me")
        val tokens = norm(query).split(" ").filter { it.isNotBlank() && it !in stop }
        if (!LocalPlayer.loaded) { try { LocalPlayer.scan(applicationContext) } catch (e: Exception) { } }
        val all = LocalPlayer.songs
        if (all.isEmpty()) return null
        val list = if (tokens.isEmpty()) all else all.filter { tr ->
            val hay = norm(tr.artist + " " + tr.title + " " + tr.album)
            tokens.all { hay.contains(it) }
        }
        if (list.isEmpty()) return null
        if (openRm(null, query, true)) {
            handler.postDelayed({ npT = 0L; updateMedia() }, 1500)
            return if (tokens.isEmpty()) "Reproduciendo tu música" else "Reproduciendo $query"
        }
        LocalPlayer.playQueue(list, list.indices.random(), true)
        handler.postDelayed({ npT = 0L; updateMedia() }, 700)
        return if (tokens.isEmpty()) "Reproduciendo tu música" else "Reproduciendo $query"
    }

    private fun handleVoice(raw: String): String {
        val t = norm(raw)
        if (t.isEmpty()) return "No te entendí"
        fun has(vararg w: String) = w.any { Regex("\\b$it\\b").containsMatchIn(t) }
        fun playing() = nowPlaying()?.playing == true

        // gasolineras: las 5 más cercanas, por voz
        Estaciones.wantsFuel(raw)?.let { gas ->
            startActivity(Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fuel", true).putExtra("gnv", gas))
            return "Buscando estaciones cercanas"
        }

        // navegación
        Regex("^(?:quiero ir (?:a|al|a la|donde)|me quiero ir a|(?:voy a |vamos a |quiero |tengo que |necesito )?(?:recoger|buscar|pasar por|ir por|ir a recoger|ir a buscar) (?:a )?|llevame (?:a|al|a la|donde)|llevame|ir (?:a|al|a la|donde)|vamos donde|navega(?:r)? (?:a|al|hasta)|vamos (?:a|al)|como llego (?:a|al)|como llegar (?:a|al)|ruta (?:a|al)|guiame (?:a|al|hasta)|ve (?:a|al)|dirigete (?:a|al))\\s+(.+)$").find(t)?.let { mt ->
            val d = destFor(mt.groupValues[1]) ?: return "Guarda ese lugar y vuelve a pedírmelo"
            if (d.second == "TALLER") { val tl = ps("place_taller"); if (tl != null && MapData.ready(this)) { navigateTo(tl, "el taller"); return "Te guío al taller" }; if (tallerMapa().isNotBlank()) { openInGoogleMaps(tallerMapa()); return "Te guío al taller" }; editTaller(); return "Guarda primero tu taller" }
            navigateTo(d.second, d.first)
            return "Navegando a ${d.first}"
        }
        // OpenCar Music: historial, sincronizar, música libre, carátulas
        fun rm(pg: String?, sync: Boolean = false): Boolean = try {
            startActivity(Intent().setClassName("org.opencar.music", "org.opencar.music.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .also { if (pg != null) it.putExtra("page", pg); if (sync) it.putExtra("do", "sync") }); true
        } catch (e: Exception) { false }
        val noRm = "OpenCar Music no está instalada"
        if (Regex("historial").containsMatchIn(t) && has("musica", "canciones", "escuchado", "escuchas")) return if (rm("HISTORY")) "Abriendo el historial de música" else noRm
        if (Regex("que (he |hemos )?escuchado").containsMatchIn(t)) return if (rm("HISTORY")) "Abriendo el historial de música" else noRm
        if (Regex("sincroniz").containsMatchIn(t) && (has("musica", "canciones", "biblioteca", "opencar") || t.length < 14)) return if (rm(null, true)) "Sincronizando la música" else noRm
        if (has("pendientes") && has("musica", "canciones", "caratulas", "letras")) return if (rm("PENDING")) "Abriendo lo pendiente de la música" else noRm
        if (has("caratulas", "portadas")) return if (rm("COVERS")) "Abriendo las carátulas" else noRm
        if (Regex("musica (gratis|libre|legal)|descarga(r)? musica").containsMatchIn(t)) return if (rm("FREE")) "Abriendo música libre" else noRm
        if (Regex("(que|cual) (cancion|musica|tema) (es|suena|esta sonando)|que suena|como se llama (esta|la) (cancion|musica)").containsMatchIn(t)) {
            val np = nowPlaying()
            return if (np?.title.isNullOrBlank()) "No hay música sonando" else "Suena ${np!!.title}" + (if (!np.artist.isNullOrBlank()) " de ${np.artist}" else "")
        }
        if (Regex("(muestra|mostrar|ver|pon|abre|abrir)( la)? letra|letra de (la|esta) cancion").containsMatchIn(t)) { showLyrics(); return "Mostrando la letra" }
        // video y streaming
        Regex("^(?:quiero ver|ver|pon|muestrame|busca)\\s+(.+?)\\s+en youtube$").find(t)?.let { mt ->
            val q = mt.groupValues[1]
            try { startActivity(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { launch("com.google.android.youtube") }
            return "Buscando $q en YouTube"
        }
        if (Regex("(musica|canciones|escuchar).*(youtube music|ytmusic)|youtube music").containsMatchIn(t)) {
            val q = Regex("(?:de|del)\\s+(.+?)(?:\\s+en youtube music)?$").find(t)?.groupValues?.get(1)
            return if (q != null) streamSearch(q, true) else { launch("com.google.android.apps.youtube.music"); "Abriendo YouTube Music" }
        }
        if (Regex("(quiero ver|ver|abre|abrir|pon).*(video|videos|youtube|peliculas?)").containsMatchIn(t)) { launch("com.google.android.youtube"); return "Abriendo YouTube" }
        if (has("netflix")) { launch("com.netflix.mediaclient"); return "Abriendo Netflix" }
        // control de musica
        if (Regex("^(?:dale )?(?:pausa|pausar|detente|deten|para|parar|alto)(?: la musica| la cancion)?$").containsMatchIn(t)) { if (playing()) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return "Música en pausa" }
        if (Regex("^(?:dale )?(?:play|continua|continuar|sigue|seguir|reanuda|reanudar|reproducir|reproduce|dale)(?: la musica| la cancion)?$").containsMatchIn(t)) { if (!playing()) dockPlay(); return "Reproduciendo" }
        if (Regex("(cambia|cambiar|salta|pasa|otra|siguiente|proxima)").containsMatchIn(t) && Regex("(musica|cancion|tema|siguiente|otra|proxima|cambia|salta|pasa)").containsMatchIn(t) && (!has("pon", "coloca", "reproduce") || has("otra"))) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return "Siguiente canción" }
        if (Regex("(anterior|atras|regresa|vuelve|retrocede)").containsMatchIn(t) && Regex("(cancion|musica|tema|anterior)").containsMatchIn(t)) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return "Canción anterior" }
        Regex("volumen\\s+(?:al|a)?\\s*(\\d{1,3})").find(t)?.let { mt -> setVolumeFrac(mt.groupValues[1].toInt().coerceIn(0, 100) / 100f); return "Volumen al ${mt.groupValues[1]} por ciento" }
        if (Regex("(sube|subir|aumenta|mas fuerte|mas alto|mas volumen)").containsMatchIn(t) && has("volumen", "fuerte", "alto")) { repeat(3) { stepVolume(1) }; return "Subí el volumen" }
        if (Regex("(baja|bajar|disminuye|mas bajo|mas suave|menos volumen)").containsMatchIn(t) && has("volumen", "bajo", "suave")) { repeat(3) { stepVolume(-1) }; return "Bajé el volumen" }
        if (has("silencio", "mute", "silencia")) { setVolumeFrac(0f); return "Silenciado" }
        // pantallas y modos
        Regex("modo (ios|ejecutivo|pink|rouse|viaje|sport|deportivo|carrera)").find(t)?.let { mt ->
            val id = when (mt.groupValues[1]) { "ios" -> "ios"; "ejecutivo" -> "exec"; "pink", "rouse" -> "pink"; "viaje" -> "trip"; else -> "sport" }
            setMode(id); return "Modo ${Modes.get(id).name}"
        }
        if (Regex("modo manejo").containsMatchIn(t)) { page = Page.HOME; render(); handler.postDelayed({ setDrive(true) }, 300); return "Modo manejo" }
        if (Regex("reloj grande|pantalla de reloj").containsMatchIn(t)) { page = Page.HOME; render(); handler.postDelayed({ setBig(true) }, 300); return "Reloj grande" }
        Regex("ecualizador (plano|graves|rock|pop|jazz|clasica|vocal|electronica|hip hop|carretera)").find(t)?.let { mt ->
            val name = AudioFx.presets.keys.firstOrNull { norm(it) == mt.groupValues[1] }
            if (name != null) { AudioFx.setPreset(name); return "Ecualizador $name" }
        }
        if (has("ecualizador")) { openRm("eq"); return "Ecualizador" }
        if (has("calendario", "feriados")) { openCalendar(); return "Calendario" }
        if (has("viaje", "telemetria")) { page = Page.TRIP; render(); return "Viaje y telemetría" }
        if (has("reproductor")) { openMusic(); return "Reproductor" }
        if (has("ajustes", "configuracion")) { page = Page.DIAG; render(); return "Ajustes" }
        // informacion
        if (Regex("que hora es|la hora").containsMatchIn(t)) { val c = Calendar.getInstance(); return "Son las %02d con %02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }
        if (Regex("que (?:dia|fecha) es|fecha de hoy").containsMatchIn(t)) return "Hoy es " + SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es")).format(Date())
        if (Regex("proximo feriado|cuando es el feriado|cuando hay feriado|que feriado").containsMatchIn(t)) {
            val nh = nextHoliday() ?: return "No encuentro el próximo feriado"
            return "El próximo feriado es ${nh.first.name}, " + (when (nh.second) { 0 -> "hoy"; 1 -> "mañana"; else -> "en ${nh.second} días" })
        }
        if (Regex("temperatura|clima|cuantos grados|hace (?:frio|calor)").containsMatchIn(t)) return tempText()?.let { "Hay ${it.replace("°C", "grados")}" } ?: "Aún no tengo el clima; necesito internet"
        if (has("velocidad")) return "Vas a ${speedKmh().roundToInt()} kilómetros por hora"
        if (Regex("odometro|kilometraje|cuantos (?:km|kilometros|millas)").containsMatchIn(t)) return odoKm()?.let { "Tu odómetro marca ${fmtKm(it)} ${if (u() == "mi") "millas" else "kilómetros"}" } ?: "Aún no tienes el odómetro configurado"
        // abrir apps
        Regex("^(?:abre|abrir|abreme|lanza|inicia|ejecuta)\\s+(.+)$").find(t)?.let { mt ->
            val q = mt.groupValues[1].removePrefix("la ").removePrefix("el ").removePrefix("las ").removePrefix("los ")
            val a = apps.firstOrNull { norm(it.label) == q } ?: apps.firstOrNull { norm(it.label).contains(q) || q.contains(norm(it.label)) && norm(it.label).length > 3 }
            if (a != null) { launch(a.pkg); return "Abriendo ${a.label}" }
            return "No encontré la app $q"
        }
        // musica: poner algo
        Regex("^(?:pon|pone|ponme|coloca|colocame|reproduce|reproducir|quiero escuchar|escuchar|escucha|toca|quiero oir|quiero musica)\\s*(.*)$").find(t)?.let { mt ->
            var q = mt.groupValues[1]
            q = q.replace(Regex("^(?:la |el |un |una |unas |unos |algo de )?(?:musica|cancion|canciones|tema|temas)(?: de la | de los | de las | del | de )?"), { r -> if (r.value.contains(" de la ")) "la " else if (r.value.contains(" de los ")) "los " else if (r.value.contains(" de las ")) "las " else "" }).trim()
            if (q.isBlank() || q == "musica") return playLocalMatching("") ?: "No hay música guardada. Dime qué artista quieres y la busco en streaming"
            return playLocalMatching(q) ?: streamSearch(q)
        }
        return "No entendí «$raw»"
    }

    private fun buildDiag(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(20), p(8), p(20), p(4))
        }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }),
            LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(TextView(this).apply {
            text = "Diagnóstico y ajustes  ·  v2.34"
            size(22f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(p(24), 0, 0, 0)
        })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val sv = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(28), p(8), p(28), p(16))
        }
        sv.addView(box)

        fun row(label: String, value: String, ok: Boolean?) {
            box.addView(TextView(this).apply {
                text = "$label:  $value"
                size(18f)
                setTextColor(when (ok) { true -> green; false -> red; null -> soft })
                setPadding(0, p(6), 0, p(6))
            })
        }
        val gpsKm = prefs.getLong("gps_total_m", 0L) / metersPerUnit()
        val fixAge = if (TripService.lastFixMs == 0L) null else (System.currentTimeMillis() - TripService.lastFixMs) / 1000
        row("Permiso de ubicación", if (hasLocationPerm()) "permitido" else "FALTA (botón de abajo)", hasLocationPerm())
        row("Servicio GPS", if (TripService.running) "activo" + (if (fixAge != null) ", última posición hace $fixAge s" else ", esperando señal") else "detenido", TripService.running)
        row("Distancia medida por GPS", String.format(Locale.US, "%.1f ${u()}", gpsKm), null)
        row("Odómetro", odoKm()?.let { "${fmtKm(it)} ${u()}" } ?: "sin configurar", odoKm() != null)
        row("Acceso a notificaciones (música)", if (hasListenerAccess()) "activado" else "FALTA activarlo (botón de abajo)", hasListenerAccess())
        val sessions = activeSessions()
        row("Sesiones multimedia activas",
            if (sessions.isEmpty()) "ninguna" else sessions.joinToString(", ") { it.packageName }, null)
        row("App de CarPlay", findApp("carplay_pkg", carPlayHints)?.let { "${it.label} (${it.pkg})" } ?: "no detectada", findApp("carplay_pkg", carPlayHints) != null)
        row("App de radio", findApp("radio_pkg", radioHints)?.let { "${it.label} (${it.pkg})" } ?: "no detectada", findApp("radio_pkg", radioHints) != null)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(tile("Dar permiso de ubicación", null, 0f, 16f, { requestPerms(false) }), weight(1f, p(80), 6f))
        buttons.addView(tile("Activar acceso a notificaciones", null, 0f, 16f,
            { openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }), weight(1f, p(80), 6f))
        buttons.addView(tile("Calibrar odómetro", null, 0f, 16f, { editVehicle() }), weight(1f, p(80), 6f))
        buttons.addView(tile("Editar datos", null, 0f, 16f, { setupForm() }), weight(1f, p(80), 6f))
        buttons.addView(tile("Unidad: ${u()} (cambiar)", null, 0f, 16f, { switchUnit() }), weight(1f, p(80), 6f))
        buttons.addView(tile("Fondo de pantalla", null, 0f, 16f, { bgOptions() }), weight(1f, p(80), 6f))
        box.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(14) })
        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons2.addView(tile("Asistente CarPlay", null, 0f, 16f, { page = Page.CARPLAY; render() }), weight(1f, p(80), 6f))
        buttons2.addView(tile("Mapas sin internet", null, 0f, 16f, { page = Page.MAPS; render() }), weight(1f, p(80), 6f))
        buttons2.addView(tile("Tema: ${when (themeMode()) { "light" -> "Claro"; "dark" -> "Oscuro"; else -> "Automático" }}", null, 0f, 16f, {
            val next = when (themeMode()) { "auto" -> "light"; "light" -> "dark"; else -> "auto" }
            prefs.edit().putString("theme_mode", next).apply()
            render()
        }), weight(1f, p(80), 6f))
        buttons2.addView(tile("Velocidad: ${if (speedUnit() == "mph") "mph" else "km/h"} (cambiar)", null, 0f, 16f, {
            prefs.edit().putString("speed_unit", if (speedUnit() == "mph") "kmh" else "mph").apply()
            render()
        }), weight(1f, p(80), 6f))
        box.addView(buttons2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val buttons3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons3.addView(tile("Reproductor", null, 0f, 16f, { openMusic() }), weight(1f, p(80), 6f))
        buttons3.addView(tile("Abrir reproductor solo: ${if (prefs.getBoolean("music_auto", true)) "sí" else "no"}", null, 0f, 16f, {
            prefs.edit().putBoolean("music_auto", !prefs.getBoolean("music_auto", true)).apply()
            render()
        }), weight(1f, p(80), 6f))
        buttons3.addView(tile("Mi música (biblioteca)", null, 0f, 16f, { openMusic() }), weight(1f, p(80), 6f))
        buttons3.addView(tile("OpenCar Music", null, 0f, 16f, {
            val i = packageManager.getLaunchIntentForPackage("org.opencar.music")
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else toast("Instala la app «OpenCar Music» para historial, carátulas y sincronización")
        }), weight(1f, p(80), 6f))
        box.addView(buttons3, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val buttons4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons4.addView(tile("Switch Modo manejo: ${if (prefs.getBoolean("drive_mode", true)) "visible" else "oculto"}", null, 0f, 15f, {
            prefs.edit().putBoolean("drive_mode", !prefs.getBoolean("drive_mode", true)).apply(); render()
        }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Brillo automático: ${if (prefs.getBoolean("auto_bright", true)) "sí" else "no"}", null, 0f, 15f, {
            prefs.edit().putBoolean("auto_bright", !prefs.getBoolean("auto_bright", true)).apply()
            lastBright = -2f; brightAt = 0L; render()
        }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Botones del Menú", null, 0f, 15f, { menuConfig() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Calendario de feriados", null, 0f, 15f, { openCalendar() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Asistente de voz", null, 0f, 15f, { openVoice() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Lugares: Casa, Trabajo", null, 0f, 15f, { placesForm() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Modo: ${mode().name}", null, 0f, 15f, { modePicker() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Ecualizador y mezcla DJ", null, 0f, 15f, { openRm("eq") }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Barra superior propia: ${if (prefs.getBoolean("own_bar", true)) "sí" else "no"}", null, 0f, 15f, {
            prefs.edit().putBoolean("own_bar", !prefs.getBoolean("own_bar", true)).apply(); hideSystemBars(); render()
        }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Viaje y telemetría", null, 0f, 15f, { page = Page.TRIP; render() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Switch Reloj grande: ${if (prefs.getBoolean("big_clock", true)) "visible" else "oculto"}", null, 0f, 15f, {
            prefs.edit().putBoolean("big_clock", !prefs.getBoolean("big_clock", true)).apply(); render()
        }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Guardar respaldo", null, 0f, 15f, { exportBackup() }), weight(1f, p(80), 6f))
        buttons4.addView(tile("Restaurar respaldo", null, 0f, 15f, {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, REQ_RESTORE)
            } catch (e: Exception) { toast("No hay selector de archivos") }
        }), weight(1f, p(80), 6f))
        val buttons5 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        while (buttons4.childCount > 6) { val v = buttons4.getChildAt(6); buttons4.removeViewAt(6); buttons5.addView(v) }
        box.addView(buttons4, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(buttons5, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ---- volumen y play del inicio

    private fun audioMgr() = getSystemService(AUDIO_SERVICE) as AudioManager

    private fun setVolumeCard(open: Boolean) {
        handler.removeCallbacks(volAutoClose)
        val v = volCard ?: return
        if (open) { if (panelOpen) setPanel(false); if (stripOpen) setStrip(false) }
        v.visibility = if (open) View.VISIBLE else View.GONE
        speedCard?.visibility = if (open || stripOpen) View.INVISIBLE else View.VISIBLE
        if (open) { updateVolumeCard(); handler.postDelayed(volAutoClose, 5000) }
    }

    private fun touchVolumeTimer() {
        handler.removeCallbacks(volAutoClose)
        handler.postDelayed(volAutoClose, 5000)
    }

    private fun setVolumeFrac(f: Float) {
        try {
            val am = audioMgr()
            val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (f * mx).roundToInt().coerceIn(0, mx), 0)
        } catch (e: Exception) { }
        touchVolumeTimer()
        updateVolumeCard()
    }

    private fun stepVolume(delta: Int) {
        try {
            audioMgr().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        } catch (e: Exception) { }
        touchVolumeTimer()
        updateVolumeCard()
    }

    private fun updateVolumeCard() {
        if (volCard?.visibility != View.VISIBLE) return
        try {
            val am = audioMgr()
            val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (volBar2?.dragging != true) { volBar2?.frac = v.toFloat() / mx; volBar2?.invalidate() }
            volPct2?.text = "${(v * 100f / mx).roundToInt()}%"
        } catch (e: Exception) { }
    }

    /** Play de la barra inferior: sirve para Bluetooth, Mi musica y radio. Si nada suena, reanuda mi musica. */
    // ------------------------------------------------------------ OpenCar Music (reproductor)
    private val RM = "org.opencar.music"
    private fun rmInstalled(): Boolean = try { packageManager.getPackageInfo(RM, 0); true } catch (e: Exception) { false }

    /** Abre el reproductor OpenCar Music (en la pantalla que se pida); si no está instalado, usa el reproductor del launcher. */
    private fun openRm(open: String? = null, playQ: String? = null, silent: Boolean = false): Boolean {
        if (!rmInstalled()) return false
        return try {
            startActivity(Intent().setClassName(RM, "$RM.PlayActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .also { if (open != null) it.putExtra("open", open); if (playQ != null) { it.putExtra("play_q", playQ); it.putExtra("silent", silent) } }); true
        } catch (e: Exception) { false }
    }

    private fun openMusic() { if (!openRm("now")) { page = Page.MUSIC; render() } }

    private fun dockPlay() {
        if (nowPlaying() != null) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return }
        if (openRm(null, "", true)) { handler.postDelayed({ npT = 0L; updateMedia() }, 1200); return }
        if (LocalPlayer.hasQueue) {
            LocalPlayer.play()
            handler.postDelayed({ npT = 0L; updateMedia() }, 500)
            return
        }
        if (hasAudioPerm()) {
            val go = {
                val l = LocalPlayer.songs
                if (l.isNotEmpty()) {
                    LocalPlayer.playQueue(l, l.indices.random(), true)
                    handler.postDelayed({ npT = 0L; updateMedia() }, 600)
                } else toast("No hay música guardada. Conecta el celular por Bluetooth o copia canciones.")
            }
            if (LocalPlayer.loaded) go()
            else if (!LocalPlayer.loading) Thread { LocalPlayer.scan(applicationContext); handler.post { go() } }.start()
            return
        }
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        toast("Para tu música guardada abre el reproductor y da el permiso")
    }

    // ---- letras (las entrega OpenCar Music desde las etiquetas y archivos .lrc del usuario)

    private fun lyricsFor(artist: String?, title: String?, file: String?): Lyr? {
        if (title.isNullOrBlank()) return null
        val key = "$artist|$title|$file"
        val now = SystemClock.elapsedRealtime()
        val e = lyrCache[key]
        if (e != null && (e.second != null || now - e.first < 60_000L)) return e.second
        if (lyrBusy.add(key)) {
            Thread {
                val uri = Uri.parse("content://org.opencar.music.art/lyrics?artist=${Uri.encode(artist ?: "")}&title=${Uri.encode(title)}&file=${Uri.encode(file ?: "")}")
                val r = try {
                    contentResolver.query(uri, null, null, null, null)?.use { if (it.moveToFirst()) Lyr(it.getString(0) ?: "", it.getInt(1) == 1) else null }
                } catch (x: Exception) { null }
                handler.post {
                    lyrCache[key] = Pair(SystemClock.elapsedRealtime(), r?.takeIf { it.text.isNotBlank() })
                    lyrBusy.remove(key)
                    chipsSig = ""
                }
            }.start()
        }
        return e?.second
    }

    private fun closeLyrics() {
        lyrOverlay?.visibility = View.GONE
        lyrOpen = false
    }

    private fun showLyrics() {
        val root = lyrOverlay ?: return
        val np = computeNowPlaying(srcMode()) ?: return
        val ly = lyricsFor(np.artist, np.title, if (np.pkg == packageName) LocalPlayer.current?.name else null) ?: return
        root.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(40), p(14), p(40), p(14)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(txt("Letra · ${np.title ?: ""}", 20f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(txt("Cerrar", 14f).apply {
            background = rounded(Color.parseColor("#26FFFFFF"), 18f)
            setPadding(p(18), p(9), p(18), p(9))
            isClickable = true
            setOnClickListener { closeLyrics() }
        })
        col.addView(head)
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, p(14), 0, p(140)) }
        val lines = ArrayList<Pair<Long, TextView>>()
        if (ly.synced) {
            val re = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
            val parsed = ArrayList<Pair<Long, String>>()
            for (raw in ly.text.lines()) {
                val tags = re.findAll(raw).toList()
                if (tags.isEmpty()) continue
                val text = raw.replace(re, "").trim()
                for (m in tags) {
                    val ms = m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + (m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
                    parsed.add(ms to text)
                }
            }
            parsed.sortBy { it.first }
            for ((ms, t) in parsed) {
                val tv = txt(t.ifBlank { "♪" }, 24f, Color.parseColor("#66FFFFFF"), true).apply { setPadding(0, p(8), 0, p(8)) }
                box.addView(tv)
                lines.add(ms to tv)
            }
        } else {
            box.addView(txt(ly.text.trim(), 20f, Color.WHITE).apply { setLineSpacing(p(4).toFloat(), 1f) })
        }
        sv.addView(box)
        col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        lyrScroll = sv; lyrLines = lines; lyrIdx = -2; lyrOpen = true
        root.visibility = View.VISIBLE
        updateLyricsHighlight(np.pos)
    }

    private fun updateLyricsHighlight(pos: Long) {
        if (!lyrOpen || lyrLines.isEmpty()) return
        var idx = -1
        for (i in lyrLines.indices) { if (lyrLines[i].first <= pos) idx = i else break }
        if (idx == lyrIdx) return
        lyrIdx = idx
        lyrLines.forEachIndexed { i, (_, tv) -> tv.setTextColor(if (i == idx) Color.WHITE else Color.parseColor("#66FFFFFF")) }
        if (idx >= 0) {
            val tv = lyrLines[idx].second
            lyrScroll?.let { sv -> sv.post { sv.smoothScrollTo(0, (tv.top - sv.height / 3).coerceAtLeast(0)) } }
        }
    }

    // ---- reproductor propio

    /** Barra tocable (progreso de la cancion y volumen). */
    private class TouchBar(c: Context) : View(c) {
        var frac = 0f
        var trackC = Color.parseColor("#33FFFFFF")
        var fillC = Color.parseColor("#4DD0E1")
        var live = false
        var onSeek: ((Float) -> Unit)? = null
        var dragging = false
            private set
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val bh = max(4f, h * 0.26f)
            val top = (h - bh) / 2f
            val r = bh / 2f
            pt.color = trackC
            cv.drawRoundRect(0f, top, w, top + bh, r, r, pt)
            val f = frac.coerceIn(0f, 1f)
            pt.color = fillC
            cv.drawRoundRect(0f, top, max(bh, w * f), top + bh, r, r, pt)
            pt.color = Color.WHITE
            cv.drawCircle((w * f).coerceIn(h * 0.22f, w - h * 0.22f), h / 2f, h * 0.22f, pt)
        }
        private fun upd(x: Float) { frac = (x / max(1, width)).coerceIn(0f, 1f); invalidate() }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); upd(e.x); if (live) onSeek?.invoke(frac) }
                MotionEvent.ACTION_MOVE -> if (dragging) { upd(e.x); if (live) onSeek?.invoke(frac) }
                MotionEvent.ACTION_UP -> { if (dragging) { upd(e.x); onSeek?.invoke(frac) }; dragging = false }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    private val musicHints = listOf("btmusic", "bt music", "musica bt", "música bt", "bluetooth music", "a2dp")

    private fun appLabel(pkg: String): String =
        apps.firstOrNull { it.pkg == pkg }?.label ?: try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }

    private fun musicBtn(res: Int, box: Int, icon: Int, big: Boolean, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = if (big) oval(Color.WHITE, Color.TRANSPARENT) else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), oval(Color.parseColor("#33FFFFFF"), Color.TRANSPARENT))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(if (big) Color.parseColor("#0B1B33") else Color.WHITE)
        }
        f.addView(iv, FrameLayout.LayoutParams(p(icon), p(icon), Gravity.CENTER))
        if (big) musicPlayV = iv
        return f
    }

    private fun buildMusic(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#050A14")) }
        val bgv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        musicBgV = bgv
        root.addView(bgv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#B8050A14")) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // encabezado
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(24), p(10), p(24), p(0))
        }
        val back = FrameLayout(this).apply {
            background = oval(Color.parseColor("#1FFFFFFF"), Color.parseColor("#38FFFFFF"))
            isClickable = true
            isFocusable = true
            setOnClickListener { page = Page.HOME; render() }
        }
        back.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_up); setColorFilter(Color.WHITE); rotation = -90f
        }, FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        header.addView(back, LinearLayout.LayoutParams(p(46), p(46)))
        val mode = srcMode()
        val playingKinds = activeSessions().filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            .map { kindOf(it.packageName) }.toSet()
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(p(16), 0, 0, 0) }
        listOf("auto" to "Auto", "bt" to "Bluetooth", "local" to "Mi música", "radio" to "Radio").forEach { (k, label) ->
            val sel = mode == k
            val dot = k != "auto" && playingKinds.contains(k)
            tabs.addView(txt(label + if (dot) "  ●" else "", 14f, if (sel) Color.parseColor("#0B1B33") else Color.WHITE, sel).apply {
                background = rounded(if (sel) Color.WHITE else Color.parseColor("#26FFFFFF"), 18f)
                setPadding(p(16), p(9), p(16), p(9))
                isClickable = true
                setOnClickListener { prefs.edit().putString("src_mode", k).apply(); render() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        }
        header.addView(tabs, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val autoOn = prefs.getBoolean("music_auto", true)
        header.addView(txt(if (autoOn) "Abrir solo: sí" else "Abrir solo: no", 13f).apply {
            background = rounded(Color.parseColor("#26FFFFFF"), 16f)
            setPadding(p(14), p(8), p(14), p(8))
            isClickable = true
            setOnClickListener { prefs.edit().putBoolean("music_auto", !autoOn).apply(); render() }
        })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(40), p(6), p(40), p(10))
        }
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // caratula
        val artBox = FrameLayout(this).apply {
            outlineProvider = roundOutline(26f)
            clipToOutline = true
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2B3A66"), Color.parseColor("#0F1A36")))
        }
        musicNoArt = ImageView(this).apply { setImageResource(R.drawable.ic_music); setColorFilter(Color.parseColor("#66FFFFFF")) }
        artBox.addView(musicNoArt, FrameLayout.LayoutParams(p(120), p(120), Gravity.CENTER))
        val artIv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        musicArtView = artIv
        artBox.addView(artIv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        body.addView(artBox, LinearLayout.LayoutParams(p(330), p(330)))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        body.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = p(40) })

        // fuente
        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        musicSrcIcon = ImageView(this)
        srcRow.addView(musicSrcIcon, LinearLayout.LayoutParams(p(26), p(26)).apply { rightMargin = p(10) })
        musicSrcV = txt("", 13.5f, Color.parseColor("#B3FFFFFF")).apply { letterSpacing = 0.08f }
        srcRow.addView(musicSrcV)
        right.addView(srcRow)

        musicTitleV = txt("Sin reproducción", 31f, Color.WHITE, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, p(8), 0, 0)
        }
        musicArtistV = txt("", 21f, Color.parseColor("#E6FFFFFF")).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, p(3), 0, 0)
        }
        musicAlbumV = txt("", 15f, Color.parseColor("#99FFFFFF")).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, p(2), 0, 0)
        }
        right.addView(musicTitleV); right.addView(musicArtistV); right.addView(musicAlbumV)

        // progreso (tocar o arrastrar para adelantar)
        val seek = TouchBar(this).apply {
            onSeek = { f ->
                val np = computeNowPlaying(srcMode())
                if (np != null && np.dur > 0L) np.c?.transportControls?.seekTo((f * np.dur).toLong())
                handler.postDelayed({ npT = 0L; updateMusicPage() }, 500)
            }
        }
        musicSeek = seek
        right.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, p(30)).apply { topMargin = p(12) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        musicPosV = txt("0:00", 12f, Color.parseColor("#99FFFFFF"))
        musicDurV = txt("0:00", 12f, Color.parseColor("#99FFFFFF"))
        times.addView(musicPosV, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        times.addView(musicDurV)
        right.addView(times)

        // controles
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        ctl.addView(musicBtn(R.drawable.ic_prev, 72, 34, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, srcMode()) }, LinearLayout.LayoutParams(p(72), p(72)))
        ctl.addView(musicBtn(R.drawable.ic_play, 92, 44, true) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, srcMode()) },
            LinearLayout.LayoutParams(p(92), p(92)).apply { setMargins(p(26), 0, p(26), 0) })
        ctl.addView(musicBtn(R.drawable.ic_next, 72, 34, false) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, srcMode()) }, LinearLayout.LayoutParams(p(72), p(72)))
        right.addView(ctl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) })

        // volumen
        val vol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        vol.addView(ImageView(this).apply { setImageResource(R.drawable.ic_volume); setColorFilter(Color.parseColor("#B3FFFFFF")) },
            LinearLayout.LayoutParams(p(26), p(26)))
        val vb = TouchBar(this).apply {
            live = true
            onSeek = { f ->
                try {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (f * mx).roundToInt().coerceIn(0, mx), 0)
                } catch (e: Exception) { }
            }
        }
        musicVol = vb
        vol.addView(vb, LinearLayout.LayoutParams(0, p(28), 1f).apply { leftMargin = p(12) })
        musicVolPct = txt("", 12f, Color.parseColor("#B3FFFFFF")).apply { setPadding(p(12), 0, 0, 0) }
        vol.addView(musicVolPct)
        right.addView(vol, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) })

        // acciones de la app de musica
        musicChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        right.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(musicChips)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(12) })
        musicInfoV = txt("", 10.5f, Color.parseColor("#66FFFFFF")).apply { maxLines = 2; setPadding(0, p(8), 0, 0) }
        right.addView(musicInfoV)
        val ov = FrameLayout(this).apply { visibility = View.GONE; isClickable = true; setBackgroundColor(Color.parseColor("#F2050A14")) }
        lyrOverlay = ov
        root.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    private fun refreshChips(np: NP?) {
        val box = musicChips ?: return
        val mode = srcMode()
        val custom = np?.c?.playbackState?.customActions ?: emptyList()
        val queue = try { np?.c?.queue } catch (e: Exception) { null } ?: emptyList()
        val lyr = np?.let { lyricsFor(it.artist, it.title, if (it.pkg == packageName) LocalPlayer.current?.name else null) }
        val sig = "$mode|${np?.pkg}|${custom.joinToString(",") { it.name.toString() }}|${queue.size}|$mediaDenied|${lyr != null}"
        if (sig == chipsSig && box.childCount > 0) return
        chipsSig = sig
        box.removeAllViews()
        fun chip(label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            box.addView(txt(label, 13.5f).apply {
                background = rounded(Color.parseColor("#26FFFFFF"), 18f)
                setPadding(p(16), p(9), p(16), p(9))
                maxLines = 1
                isClickable = true
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        }
        if (np == null && mediaDenied && mode != "local") {
            chip("Activar acceso a la música", { openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") })
            return
        }
        chip("Ecualizador", { openRm("eq") })
        val isLocal = mode == "local" || np?.pkg == packageName
        if (isLocal) {
            chip("Biblioteca", { openMusic() })
        } else {
            val key = if (mode == "radio") "radio_pkg" else "music_pkg"
            val appPkg = when (mode) {
                "radio" -> np?.pkg ?: ps("radio_pkg") ?: findApp("radio_pkg", radioHints)?.pkg
                "bt" -> np?.pkg ?: findApp("music_pkg", musicHints)?.pkg
                else -> np?.pkg ?: overrideApp("music_pkg")?.pkg ?: findApp("music_pkg", musicHints)?.pkg
            }
            val pick = { chooseApp(key, if (mode == "radio") "Elige la app de la radio" else "Elige la app de música") }
            if (appPkg != null) chip("Abrir ${appLabel(appPkg)}", { launch(appPkg) }, pick) else chip("Elegir app", pick)
            if (mode == "auto") chip("Biblioteca", { openMusic() })
        }
        custom.take(2).forEach { ca ->
            chip(ca.name.toString(), { np?.c?.transportControls?.sendCustomAction(ca.action, ca.extras) })
        }
        if (lyr != null) chip("Letra", { showLyrics() })
        if (queue.isNotEmpty()) chip("Cola (${queue.size})", {
            AlertDialog.Builder(this)
                .setTitle("Cola de reproducción")
                .setItems(queue.map { it.description.title?.toString() ?: "Pista ${it.queueId}" }.toTypedArray()) { _, w ->
                    np?.c?.transportControls?.skipToQueueItem(queue[w].queueId)
                }.show()
        })
    }

    private fun updateMusicPage() {
        val titleV = musicTitleV ?: return
        val mode = srcMode()
        val np = computeNowPlaying(mode)
        val dark = Color.parseColor("#0B1B33")
        // volumen
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (musicVol?.dragging != true) { musicVol?.frac = v.toFloat() / mx; musicVol?.invalidate() }
            musicVolPct?.text = "${(v * 100f / mx).roundToInt()}%"
        } catch (e: Exception) { }
        refreshChips(np)
        if (np == null) {
            when (mode) {
                "local" -> {
                    titleV.text = "Mi música"
                    musicArtistV?.text = if (hasAudioPerm()) "Elige canciones guardadas en la pantalla o en un USB" else "Falta el permiso para leer tu música"
                    musicAlbumV?.text = "Toca «Biblioteca» para empezar"
                    musicSrcV?.text = "MÚSICA GUARDADA"
                }
                "bt" -> {
                    titleV.text = "Bluetooth"
                    musicArtistV?.text = "Conecta tu celular y reproduce una canción"
                    musicAlbumV?.text = if (mediaDenied) "Activa también el acceso a notificaciones" else "Aquí verás lo que la app de Bluetooth de la pantalla publique"
                    musicSrcV?.text = "BLUETOOTH"
                }
                "radio" -> {
                    titleV.text = "Radio"
                    musicArtistV?.text = "Abre la radio; si su app publica la estación, se verá aquí"
                    musicAlbumV?.text = "Anterior y siguiente cambian de estación si la radio lo permite"
                    musicSrcV?.text = "RADIO"
                }
                else -> {
                    titleV.text = if (mediaDenied) "Activa el acceso a notificaciones" else "Sin reproducción"
                    musicArtistV?.text = if (mediaDenied) "Así puedo leer la canción que suena" else "Elige una fuente: Bluetooth, Mi música o Radio"
                    musicAlbumV?.text = ""
                    musicSrcV?.text = "REPRODUCTOR"
                }
            }
            musicSrcIcon?.setImageDrawable(null)
            musicPlayV?.setImageResource(R.drawable.ic_play)
            musicSeek?.frac = 0f; musicSeek?.invalidate()
            musicPosV?.text = "0:00"; musicDurV?.text = "0:00"
            musicInfoV?.text = "Sin sesión de música activa."
            if (shownKey != "none") {
                shownKey = "none"; lastArt = null
                musicArtView?.setImageDrawable(null); musicNoArt?.visibility = View.VISIBLE; musicBgV?.setImageDrawable(null)
            }
            return
        }
        titleV.text = np.title ?: "Reproduciendo"
        musicArtistV?.text = np.artist ?: ""
        musicAlbumV?.text = np.album ?: ""
        musicPlayV?.setImageResource(if (np.playing) R.drawable.ic_pause else R.drawable.ic_play)
        musicPlayV?.setColorFilter(dark)
        val key = "${np.pkg}|${np.title}|${np.artist}"
        if (key != shownKey && lyrOpen) closeLyrics()
        if (lyrOpen) updateLyricsHighlight(np.pos)
        if (key != shownKey) {
            shownKey = key
            val pkg = np.pkg
            val app = apps.firstOrNull { it.pkg == pkg }
            val label = pkg?.let { appLabel(it) } ?: "Música"
            val kind = kindOf(pkg)
            musicSrcV?.text = (when (kind) { "bt" -> "$label · BLUETOOTH"; "local" -> "MI MÚSICA · ALMACENAMIENTO"; "radio" -> "$label · RADIO"; else -> label }).uppercase()
            musicSrcIcon?.setImageBitmap(app?.let { iosIcon(it, p(26)) })
        }
        if (np.art !== lastArt) {
            lastArt = np.art
            musicArtView?.setImageBitmap(np.art)
            musicNoArt?.visibility = if (np.art == null) View.VISIBLE else View.GONE
            musicBgV?.setImageBitmap(np.art?.let { Bitmap.createScaledBitmap(it, max(1, it.width / 24), max(1, it.height / 24), true) })
        }
        if (musicSeek?.dragging != true) {
            if (np.dur > 0L && np.pos >= 0L) {
                val shown = np.pos.coerceIn(0L, np.dur)
                musicSeek?.frac = shown.toFloat() / np.dur
                musicPosV?.text = fmtTime(shown)
                musicDurV?.text = fmtTime(np.dur)
            } else {
                musicSeek?.frac = 0f
                musicPosV?.text = if (np.pos >= 0L) fmtTime(np.pos) else "--:--"
                musicDurV?.text = "--:--"
            }
            musicSeek?.invalidate()
        }
        musicInfoV?.text = "Fuente: ${np.pkg ?: "?"} · ${np.have}"
    }

    // ---- biblioteca de musica guardada

    private var libTab = 0
    private var libFilter: Pair<Int, String>? = null

    private class Row(val title: String, val sub: String, val right: String, val playing: Boolean, val onClick: () -> Unit)

    private fun hasAudioPerm(): Boolean = checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    private fun askAudioPerm() {
        requestPermissions(arrayOf(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ), REQ_AUDIO)
    }

    private fun loadLibrary(force: Boolean = false) {
        if (LocalPlayer.loading || (LocalPlayer.loaded && !force)) return
        LocalPlayer.loading = true
        Thread {
            LocalPlayer.scan(applicationContext)
            handler.post { if (page == Page.LIBRARY) render() }
        }.start()
    }

    private fun playList(list: List<LocalPlayer.Track>, index: Int, shuffle: Boolean) {
        LocalPlayer.playQueue(list, index, shuffle)
        prefs.edit().putString("src_mode", "local").apply()
        page = Page.MUSIC
        render()
    }

    private fun libRow(r: Row): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(28), 0, p(28), 0)
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, p(66))
        }
        val box = FrameLayout(this).apply {
            background = rounded(if (r.playing) Color.parseColor("#334DD0E1") else Color.parseColor("#1FFFFFFF"), 10f)
        }
        box.addView(ImageView(this).apply {
            setImageResource(if (r.playing) R.drawable.ic_play else R.drawable.ic_music)
            setColorFilter(if (r.playing) cyan else Color.parseColor("#B3FFFFFF"))
        }, FrameLayout.LayoutParams(p(24), p(24), Gravity.CENTER))
        v.addView(box, LinearLayout.LayoutParams(p(46), p(46)).apply { rightMargin = p(16) })
        val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(txt(r.title, 17f, if (r.playing) cyan else Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        tx.addView(txt(r.sub, 13f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, p(2), 0, 0) })
        v.addView(tx, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        v.addView(txt(r.right, 13f, muted).apply { setPadding(p(12), 0, 0, 0) })
        return v
    }

    private fun buildLibrary(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val filter = libFilter
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(24), p(10), p(24), p(4))
        }
        val back = FrameLayout(this).apply {
            background = oval(Color.parseColor("#1FFFFFFF"), Color.parseColor("#38FFFFFF"))
            isClickable = true
            isFocusable = true
            setOnClickListener { if (libFilter != null) { libFilter = null; render() } else { openMusic() } }
        }
        back.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_up); setColorFilter(Color.WHITE); rotation = -90f
        }, FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        header.addView(back, LinearLayout.LayoutParams(p(46), p(46)))
        header.addView(txt(filter?.second ?: "Mi música", 22f, Color.WHITE, true).apply {
            setPadding(p(16), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        fun message(title: String, sub: String, btn: String?, onBtn: () -> Unit = {}) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(p(60), 0, p(60), p(40)) }
            box.addView(txt(title, 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            box.addView(txt(sub, 15f, soft).apply { gravity = Gravity.CENTER; setPadding(0, p(10), 0, p(18)) })
            if (btn != null) box.addView(tile(btn, null, 0f, 17f, { onBtn() }), LinearLayout.LayoutParams(p(300), p(64)))
            col.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        if (!hasAudioPerm()) {
            message("Falta el permiso de música", "Permite que el launcher lea las canciones guardadas en la pantalla o en un USB.", "Dar permiso de música") { askAudioPerm() }
            return col
        }
        loadLibrary()
        if (LocalPlayer.loading || !LocalPlayer.loaded) { message("Buscando música…", "Esto tarda unos segundos la primera vez.", null); return col }
        val all = LocalPlayer.songs
        if (all.isEmpty()) {
            message("No encontré canciones", "Copia música a la pantalla (carpeta Music) o conecta un USB y busca otra vez. Formatos: MP3, AAC, FLAC, OGG, WAV.", "Buscar otra vez") {
                loadLibrary(true); render()
            }
            return col
        }
        val showSongs = filter != null || libTab == 0
        val songs = if (filter == null) all else all.filter { if (filter.first == 1) it.artist == filter.second else it.album == filter.second }

        // pestañas y acciones
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p(24), p(4), p(24), p(6)) }
        fun pill(label: String, sel: Boolean, onClick: () -> Unit) = txt(label, 14f, if (sel) Color.parseColor("#0B1B33") else Color.WHITE, sel).apply {
            background = rounded(if (sel) Color.WHITE else Color.parseColor("#26FFFFFF"), 18f)
            setPadding(p(16), p(9), p(16), p(9))
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (filter == null) {
            listOf("Canciones", "Artistas", "Álbumes").forEachIndexed { i, label ->
                bar.addView(pill(label, libTab == i) { libTab = i; render() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
            }
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (showSongs) {
            bar.addView(pill("▶  Reproducir todo", false) { playList(songs, 0, false) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
            bar.addView(pill("Aleatorio", false) { playList(songs, songs.indices.random(), true) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        }
        bar.addView(pill("Actualizar", false) { loadLibrary(true); render() })
        col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val cur = LocalPlayer.current
        val rows: List<Row> = when {
            showSongs -> songs.mapIndexed { i, t -> Row(t.title, "${t.artist} · ${t.album}", fmtTime(t.dur), cur?.id == t.id) { playList(songs, i, false) } }
            libTab == 1 -> all.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (k, v) -> Row(k, "${v.size} canciones", "›", false) { libFilter = 1 to k; render() } }
            else -> all.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (k, v) -> Row(k, "${v.first().artist} · ${v.size} canciones", "›", false) { libFilter = 2 to k; render() } }
        }
        val lv = ListView(this).apply {
            divider = null
            isFastScrollEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#26FFFFFF")))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(i: Int) = rows[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, cv: View?, parent: android.view.ViewGroup?) = libRow(rows[i])
            }
            setOnItemClickListener { _, _, i, _ -> rows[i].onClick() }
        }
        col.addView(lv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ---- mapas sin internet

    private fun buildMaps(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(20), p(8), p(20), p(4))
        }
        header.addView(tile("‹  Inicio", null, 0f, 20f, { page = Page.HOME; render() }), LinearLayout.LayoutParams(p(170), p(64)))
        header.addView(txt("Mapas sin internet", 22f, Color.WHITE, true).apply { setPadding(p(24), 0, 0, 0) })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val sv = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(p(14), p(4), p(14), p(14))
        }
        sv.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val left = glassCol()
        val right = glassCol()
        body.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(p(5), p(5), p(5), p(5)) })
        body.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(p(5), p(5), p(5), p(5)) })

        // ---- estado
        left.addView(txt("APPS DE MAPAS EN ESTA PANTALLA", 11f, Color.parseColor("#8CFFFFFF")).apply { letterSpacing = 0.16f })
        val known = listOf(
            "com.google.android.apps.maps" to "Google Maps (descarga áreas sin conexión)",
            "net.osmand.plus" to "OsmAnd+ (100% sin internet)",
            "net.osmand" to "OsmAnd (100% sin internet)",
            "app.organicmaps" to "Organic Maps (100% sin internet)",
            "com.here.app.maps" to "HERE WeGo (mapas descargables)",
            "com.waze" to "Waze (necesita internet)"
        )
        val found = known.filter { k -> apps.any { it.pkg == k.first } }
        if (found.isEmpty()) {
            statusRow(left, 2, "No encuentro apps de mapas", "Instala Google Maps, OsmAnd u Organic Maps")
        } else {
            found.forEach { statusRow(left, if (it.first == "com.waze") 1 else 0, apps.first { a -> a.pkg == it.first }.label, it.second) }
        }
        val offline = found.any { it.first != "com.waze" }
        val nav = navApp()
        statusRow(left, if (nav != null) 0 else 1, "App de mapas del botón Mapas",
            nav?.label ?: "Sin elegir: toca «Elegir app de mapas»")
        statusRow(left, 1, "Las descargas necesitan Wi-Fi una sola vez",
            "Usa el hotspot del celular; después olvida esa red para que no estorbe a CarPlay")

        left.addView(txt("ACCIONES", 11f, Color.parseColor("#8CFFFFFF")).apply {
            letterSpacing = 0.16f
            setPadding(0, p(10), 0, p(4))
        })
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        acts.addView(tile("Abrir mapa", null, 0f, 15f, {
            if (nav != null) launch(nav.pkg) else chooseApp("nav_pkg", "Elige la app de mapas")
        }), weight(1f, p(70), 4f))
        acts.addView(tile("Elegir app de mapas", null, 0f, 15f, { chooseApp("nav_pkg", "Elige la app de mapas") }), weight(1f, p(70), 4f))
        left.addView(acts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val acts2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        acts2.addView(tile("Wi-Fi: olvidar redes", null, 0f, 15f, { openSettings(Settings.ACTION_WIFI_SETTINGS) }), weight(1f, p(70), 4f))
        acts2.addView(tile(if (offline) "Más mapas (Play Store)" else "Instalar OsmAnd", null, 0f, 15f, {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=net.osmand.plus")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                toast("Esta pantalla no tiene Play Store; instala el APK a mano")
            }
        }), weight(1f, p(70), 4f))
        left.addView(acts2, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- pasos
        fun steps(title: String, list: List<String>) {
            right.addView(txt(title, 11f, Color.parseColor("#8CFFFFFF")).apply {
                letterSpacing = 0.16f
                setPadding(0, if (right.childCount == 0) 0 else p(12), 0, 0)
            })
            list.forEachIndexed { i, st ->
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, p(5), 0, p(1))
                }
                r.addView(txt("${i + 1}", 13f, Color.parseColor("#04101C"), true).apply {
                    gravity = Gravity.CENTER
                    background = oval(cyan, Color.TRANSPARENT)
                }, LinearLayout.LayoutParams(p(24), p(24)).apply { rightMargin = p(12) })
                r.addView(txt(st, 14f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                right.addView(r)
            }
        }
        steps("GOOGLE MAPS EN LA PANTALLA", listOf(
            "Conecta la pantalla a Wi-Fi (hotspot del celular) e inicia sesión en Google Maps.",
            "Toca tu foto > Mapas sin conexión > Seleccionar tu propio mapa.",
            "Encuadra tu ciudad y alrededores y toca Descargar.",
            "Prueba: apaga el Wi-Fi y pide una ruta. Funciona dentro del área descargada, sin tráfico en vivo.",
            "Si dice que no está disponible en esta región, usa OsmAnd u Organic Maps."
        ))
        steps("APPLE MAPS (IPHONE, POR CARPLAY)", listOf(
            "Apple Maps corre en el iPhone, no en esta pantalla: descarga ahí el mapa.",
            "Mapas > tu inicial > Mapas sin conexión > Descargar mapa nuevo (iOS 17 o más)."
        ))
        steps("OSMAND U ORGANIC MAPS", listOf(
            "Instálalos, abre la app y descarga el mapa de tu región (elige país y zona).",
            "Funcionan sin internet siempre; elígelos con «Elegir app de mapas»."
        ))
        col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    // ---- menu de Apps (estilo iOS)

    private val iconCache = HashMap<String, Bitmap>()

    private data class Glyph(val res: Int, val c1: String, val c2: String)

    /** Iconos modernos para las apps de fabrica de la pantalla (por su nombre). */
    private fun glyphFor(app: AppInfo): Glyph? {
        val l = app.label.lowercase().trim()
        return when {
            "ajustes auto" in l || "mi auto" in l || "mi carr" in l || "datos del autom" in l ->
                Glyph(R.drawable.ic_car, "#4FA3FF", "#1F5FD6")
            l == "ajustes" || l == "settings" -> Glyph(R.drawable.ic_gear, "#9AA6B8", "#5C6779")
            "radio" in l -> Glyph(R.drawable.ic_radio, "#FF8A5B", "#E5483B")
            "música bt" in l || "musica bt" in l -> Glyph(R.drawable.ic_music, "#A98BFF", "#6C4BEA")
            l == "música" || l == "musica" || l == "music" -> Glyph(R.drawable.ic_music, "#FF7AA8", "#E8365F")
            "teléfono" in l || "telefono" in l || l == "phone" -> Glyph(R.drawable.ic_phone, "#5BE08A", "#1FA855")
            "archivos" in l || "files" in l -> Glyph(R.drawable.ic_folder, "#5CC8FF", "#1E78D6")
            "vídeo" in l || l == "video" -> Glyph(R.drawable.ic_play, "#B07CFF", "#5B3FD1")
            "sonido" in l || l == "dsp" || "ecualiz" in l -> Glyph(R.drawable.ic_settings, "#5EE0F0", "#1E8FC9")
            l == "fan" -> Glyph(R.drawable.ic_air, "#7FE3E8", "#2A9FB8")
            "salvapantallas" in l -> Glyph(R.drawable.ic_clock, "#FFD66B", "#F29A1F")
            "reiniciar" in l -> Glyph(R.drawable.ic_refresh, "#FFB36B", "#E8742A")
            "apagado" in l -> Glyph(R.drawable.ic_power, "#FF7A70", "#D8352B")
            "ui style" in l -> Glyph(R.drawable.ic_palette, "#FF9AD5", "#B04BE8")
            l == "fondo" -> Glyph(R.drawable.ic_image, "#7DE3B5", "#1FA37A")
            "instrucciones" in l -> Glyph(R.drawable.ic_book, "#FFC46B", "#E8862A")
            "herramientas" in l -> Glyph(R.drawable.ic_build, "#B5BDC9", "#6F7A8C")
            l == "swc" -> Glyph(R.drawable.ic_gauge, "#7AA8FF", "#3D5FD6")
            l == "aux" -> Glyph(R.drawable.ic_music, "#C28BFF", "#7A4BE0")
            "usb2tvout" in l -> Glyph(R.drawable.ic_play, "#6BC8FF", "#2A74D6")
            "autokit" in l || "zlink" in l || "carplay" in l -> Glyph(R.drawable.ic_phone, "#2FC7EA", "#1A6DFF")
            else -> null
        }
    }

    /** Dibuja el icono real de la app llenando la baldosa (o sobre una baldosa oscura si tiene transparencias). */
    private fun drawAppIcon(c: Canvas, d: Drawable, s: Int) {
        val sf = s.toFloat()
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val ext = (sf * 0.25f).toInt()
            d.background?.let { it.setBounds(-ext, -ext, s + ext, s + ext); it.draw(c) }
            d.foreground?.let { it.setBounds(-ext, -ext, s + ext, s + ext); it.draw(c) }
            return
        }
        val tmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(tmp))
        val px = IntArray(s * s)
        tmp.getPixels(px, 0, s, 0, 0, s, s)
        var minX = s; var minY = s; var maxX = -1; var maxY = -1
        var lumSum = 0.0
        var cnt = 0
        for (y in 0 until s) for (x in 0 until s) {
            val col = px[y * s + x]
            if (Color.alpha(col) > 40) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                lumSum += (0.299 * Color.red(col) + 0.587 * Color.green(col) + 0.114 * Color.blue(col)) / 255.0
                cnt++
            }
        }
        val k = max(1, s / 14)
        // 1) icono que ya llena todo el cuadrado: se usa tal cual
        if (Color.alpha(tmp.getPixel(k, k)) > 235 && Color.alpha(tmp.getPixel(s - k, s - k)) > 235) {
            c.drawBitmap(tmp, 0f, 0f, null)
            return
        }
        if (cnt == 0) {
            c.drawColor(Color.parseColor("#2A3344"))
            return
        }
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val src = Rect(minX, minY, maxX + 1, maxY + 1)
        val fp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // 2) icono tipo "cuadrado redondeado" (ej. YouTube clasico): se amplia para llenar la forma iOS
        if (bw > s * 0.78f && bh > s * 0.78f) {
            val edge = tmp.getPixel(min(s - 1, minX + 3), (minY + maxY) / 2)
            c.drawColor(edge or 0xFF000000.toInt())
            val ext = sf * 0.05f
            c.drawBitmap(tmp, src, RectF(-ext, -ext, sf + ext, sf + ext), fp)
            return
        }
        // 3) logo suelto (ej. Chrome, Maps): baldosa clara u oscura segun el icono, centrado
        val lum = lumSum / cnt
        val dark = lum > 0.78
        c.drawRect(0f, 0f, sf, sf, Paint().apply {
            shader = if (dark)
                LinearGradient(0f, 0f, 0f, sf, Color.parseColor("#3A4660"), Color.parseColor("#1C2436"), Shader.TileMode.CLAMP)
            else
                LinearGradient(0f, 0f, 0f, sf, Color.parseColor("#FFFFFF"), Color.parseColor("#E3E8F1"), Shader.TileMode.CLAMP)
        })
        val scale = (sf * 0.70f) / max(bw, bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (sf - dw) / 2f
        val top = (sf - dh) / 2f
        c.drawBitmap(tmp, src, RectF(left, top, left + dw, top + dh), fp)
    }

    private fun iosIcon(app: AppInfo, s: Int): Bitmap {
        val md = mode()
        val ck = "${app.pkg}#$s#${md.id}"
        iconCache[ck]?.let { return it }
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sf = s.toFloat()
        val path = Path()
        when (md.shape) {
            3 -> { val k = sf * 0.24f; path.moveTo(k, 0f); path.lineTo(sf, 0f); path.lineTo(sf, sf - k); path.lineTo(sf - k, sf); path.lineTo(0f, sf); path.lineTo(0f, k); path.close() }
            else -> { val r = sf * when (md.shape) { 1 -> 0.5f; 2 -> 0.12f; 4 -> 0.34f; else -> 0.225f }; path.addRoundRect(RectF(0f, 0f, sf, sf), r, r, Path.Direction.CW) }
        }
        c.save()
        c.clipPath(path)
        val g = glyphFor(app)
        if (g != null) {
            c.drawRect(0f, 0f, sf, sf, Paint().apply {
                shader = if (md.id == "ios") LinearGradient(0f, 0f, sf * 0.35f, sf, Color.parseColor(g.c1), Color.parseColor(g.c2), Shader.TileMode.CLAMP)
                else LinearGradient(0f, 0f, sf * 0.35f, sf, md.acc, (md.acc2 and 0x00FFFFFF) or 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            })
            getDrawable(g.res)?.mutate()?.let { d ->
                val m = (sf * 0.22f).toInt()
                d.setBounds(m, m, s - m, s - m)
                d.draw(c)
            }
        } else {
            try { drawAppIcon(c, app.icon, s) } catch (e: Exception) { c.drawColor(Color.parseColor("#2A3344")) }
        }
        c.drawRect(0f, 0f, sf, sf * 0.5f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, sf * 0.5f, Color.parseColor("#2DFFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        c.restore()
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (md.id == "ios") max(1f, sf * 0.012f) else max(2f, sf * 0.03f)
            color = if (md.id == "ios") Color.parseColor("#33FFFFFF") else (md.acc and 0x00FFFFFF) or 0xAA000000.toInt()
        })
        iconCache[ck] = bmp
        return bmp
    }

    /** Favoritas primero, luego el resto en orden alfabetico. */
    private fun orderedApps(): List<AppInfo> {
        val fav = favorites()
        return fav + apps.filter { a -> fav.none { it.pkg == a.pkg } }
    }

    private class SwipeFrame(c: Context, val onSwipe: (Int) -> Unit) : FrameLayout(c) {
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(c).scaledTouchSlop * 2

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) return true
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    if (abs(dx) > slop * 3) onSwipe(if (dx < 0) 1 else -1)
                }
            }
            return true
        }
    }

    private fun appCell(app: AppInfo): LinearLayout {
        val size = p(84)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        cell.addView(ImageView(this).apply { setImageBitmap(iosIcon(app, size)) }, LinearLayout.LayoutParams(size, size))
        cell.addView(txt(app.label, 13f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding(p(2), p(7), p(2), 0)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cell.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        cell.setOnClickListener {
            val cb = onPick
            if (cb != null) {
                onPick = null
                pickTitle = null
                page = Page.HOME
                cb(app)
                render()
            } else {
                launch(app.pkg)
            }
        }
        cell.setOnLongClickListener { if (onPick == null) appOptions(app); true }
        return cell
    }

    private fun buildAll(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#050A14")) }
        // fondo: la misma foto del inicio, muy desenfocada y oscura
        currentBackground()?.let { bmp ->
            val small = Bitmap.createScaledBitmap(bmp, max(1, bmp.width / 24), max(1, bmp.height / 24), true)
            root.addView(ImageView(this).apply {
                setImageBitmap(small)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#B8050A14")) },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // encabezado
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(24), p(12), p(24), p(2))
        }
        val back = FrameLayout(this).apply {
            background = oval(Color.parseColor("#1FFFFFFF"), Color.parseColor("#38FFFFFF"))
            isClickable = true
            isFocusable = true
            setOnClickListener { page = Page.HOME; onPick = null; pickTitle = null; render() }
        }
        back.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_up)
            setColorFilter(Color.WHITE)
            rotation = -90f
        }, FrameLayout.LayoutParams(p(30), p(30), Gravity.CENTER))
        header.addView(back, LinearLayout.LayoutParams(p(46), p(46)))
        header.addView(txt(pickTitle ?: "Apps", 24f, Color.WHITE, true).apply { setPadding(p(16), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (pickTitle == null) header.addView(txt("Modo: ${mode().name}  ▾", 15f, Color.WHITE, true).apply {
            setPadding(p(18), p(10), p(18), p(10)); isClickable = true
            background = GradientDrawable().apply { cornerRadius = p(22).toFloat(); setColor((cyan and 0x00FFFFFF) or 0x44000000); setStroke(max(1, p(1)), cyan) }
            setOnClickListener { modePicker() }
            pressFx()
        })
        col.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // paginas de 7 x 3 iconos
        val perRow = 7
        val rows = 3
        val chunks = orderedApps().chunked(perRow * rows)
        val pages = chunks.map { chunk ->
            val pg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            chunk.chunked(perRow).forEach { rowApps ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowApps.forEach { row.addView(appCell(it), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
                repeat(perRow - rowApps.size) { row.addView(View(this), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
                pg.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            repeat(rows - chunk.chunked(perRow).size) { pg.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)) }
            pg
        }

        var cur = 0
        val dotsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun drawDots() {
            dotsRow.removeAllViews()
            if (pages.size < 2) return
            for (i in pages.indices) {
                dotsRow.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(if (i == cur) cyan else Color.parseColor("#73FFFFFF"))
                        cornerRadius = p(4).toFloat()
                    }
                }, LinearLayout.LayoutParams(if (i == cur) p(24) else p(7), p(7)).apply { setMargins(p(4), 0, p(4), 0) })
            }
        }
        lateinit var host: SwipeFrame
        fun show(i: Int, dir: Int) {
            val old = host.getChildAt(0)
            val next = pages[i]
            val shift = p(120).toFloat()
            if (old == null || dir == 0) {
                host.removeAllViews()
                next.translationX = 0f; next.alpha = 1f
                host.addView(next, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            } else {
                old.animate().translationX(-dir * shift).alpha(0f).setDuration(130).withEndAction {
                    host.removeAllViews()
                    next.translationX = dir * shift
                    next.alpha = 0f
                    host.addView(next, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    next.animate().translationX(0f).alpha(1f).setDuration(170).start()
                }.start()
            }
            drawDots()
        }
        host = SwipeFrame(this) { dir ->
            val n = cur + dir
            if (n in pages.indices) {
                cur = n
                show(n, dir)
            }
        }
        host.setPadding(p(14), p(4), p(14), 0)
        col.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (pages.isNotEmpty()) show(0, 0)
        col.addView(dotsRow, LinearLayout.LayoutParams(MATCH_PARENT, p(30)))
        return root
    }
}
