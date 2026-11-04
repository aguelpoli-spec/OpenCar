package org.opencar.music

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** OpenCar Music: historial de escuchas, pendientes, caratulas y sincronizacion de tu propia musica. */
class MainActivity : Activity() {

    private enum class Page { HOME, HISTORY, PENDING, COVERS, SYNC, LYRICS, FREE, CREDITS }

    private var page = Page.HOME
    private var scale = 1f
    private val handler = Handler(Looper.getMainLooper())
    private var histFilter: String? = null
    private var liveA: TextView? = null
    private var liveB: TextView? = null
    private var freeSrc = "archive"
    private var freeQuery = ""
    private var freeHits: List<Free.Hit> = emptyList()
    private var freeMsg = ""

    private val bg = Color.parseColor("#0A1428")
    private val cardBg = Color.parseColor("#B3101E38")
    private val strokeC = Color.parseColor("#3377A9FF")
    private val soft = Color.parseColor("#C5D3E8")
    private val muted = Color.parseColor("#8EA0BA")
    private val green = Color.parseColor("#34D27B")
    private val amber = Color.parseColor("#FFCC3D")
    private val red = Color.parseColor("#FF5A4F")
    private val accent = Color.parseColor("#9B6BFF")

    private val tick = object : Runnable {
        override fun run() { updateLive(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(bg))
        Scheduler.setup(this)
        voiceIntent(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        voiceIntent(i)
        render()
    }

    /** Órdenes que manda el launcher con «Hey <marca de tu auto>»: abrir una pantalla o sincronizar. */
    private fun voiceIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("page")?.let { pg -> try { page = Page.valueOf(pg) } catch (e: Exception) { } ; i.removeExtra("page") }
        if (i.getStringExtra("do") == "sync") {
            i.removeExtra("do")
            try { startForegroundService(Intent(this, SyncService::class.java)); toast("Sincronizando…") } catch (e: Exception) { toast("No pude iniciar") }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.HOME) { page = Page.HOME; render() } else super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------ utilidades

    private fun p(v: Float) = (v * scale).toInt()
    private fun p(v: Int) = p(v.toFloat())

    private fun txt(t: String, sz: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sz * scale)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rounded(color: Int, r: Float, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = p(r).toFloat()
        if (stroke) setStroke(max(1, p(1)), strokeC)
    }

    private fun glass(c: Int, pressed: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, 18f, true))
        addState(intArrayOf(), rounded(c, 18f, true))
    }

    private fun tile(title: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(p(8), p(4), p(8), p(4))
        background = glass(cardBg, Color.parseColor("#D9223A63"))
        isClickable = true
        setOnClickListener { onClick() }
        addView(txt(title, 15f).apply { gravity = Gravity.CENTER; maxLines = 2 })
    }

    private fun pill(label: String, sel: Boolean, onClick: () -> Unit) = txt(label, 14f, if (sel) Color.parseColor("#0B1B33") else Color.WHITE, sel).apply {
        background = rounded(if (sel) Color.WHITE else Color.parseColor("#26FFFFFF"), 18f)
        setPadding(p(16), p(9), p(16), p(9))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, h: Int, wt: Float = 0f, m: Float = 0f) = LinearLayout.LayoutParams(w, h, wt).apply { setMargins(p(m), p(m), p(m), p(m)) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun hasListener(): Boolean =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun audioPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudio() = checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED
    private fun launcherInstalled() = try { packageManager.getPackageInfo("org.opencar.launcher", 0); true } catch (e: Exception) { false }

    private fun fmtDate(ts: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ts))
    private fun fmtMin(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60) }
    private fun kindLabel(k: String) = when (k) { "bt" -> "Bluetooth"; "local" -> "Mi música"; "radio" -> "Radio"; else -> "App" }

    private fun render() {
        val dm = resources.displayMetrics
        scale = min(dm.widthPixels / 1024f, dm.heightPixels / 600f).coerceIn(0.5f, 2.5f)
        liveA = null; liveB = null
        setContentView(when (page) {
            Page.HOME -> buildHome(); Page.HISTORY -> buildHistory(); Page.PENDING -> buildPending()
            Page.COVERS -> buildCovers(); Page.SYNC -> buildSync(); Page.LYRICS -> buildLyrics(); Page.FREE -> buildFree(); Page.CREDITS -> buildCredits()
        })
        updateLive()
    }

    private fun header(title: String, right: View? = null): LinearLayout {
        val h = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(p(20), p(8), p(20), p(4)) }
        if (page != Page.HOME) h.addView(tile("‹  Inicio") { page = Page.HOME; render() }, lp(p(170), p(64)))
        h.addView(txt(title, 22f, Color.WHITE, true).apply { setPadding(p(if (page == Page.HOME) 8 else 24), 0, 0, 0) }, lp(0, WRAP_CONTENT, 1f))
        if (right != null) h.addView(right)
        return h
    }

    private fun glassCol() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(p(18), p(12), p(18), p(14))
        background = rounded(cardBg, 22f, true)
    }

    private fun statusRow(box: LinearLayout, level: Int, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, p(7), 0, p(7)) }
        val col = when (level) { 0 -> green; 1 -> amber; else -> red }
        r.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) } }, lp(p(12), p(12)).apply { rightMargin = p(12) })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(txt(title, 16f, Color.WHITE, true)); t.addView(txt(detail, 13.5f, Color.parseColor("#B3FFFFFF")))
        r.addView(t, lp(0, WRAP_CONTENT, 1f))
        if (action != null) r.addView(pill(action, false, onAction))
        box.addView(r)
    }

    private fun section(t: String) = txt(t.uppercase(), 11f, Color.parseColor("#8CFFFFFF")).apply { letterSpacing = 0.16f; setPadding(0, p(6), 0, p(4)) }

    // ------------------------------------------------------------ inicio

    private fun card(title: String, big: String, sub: String, to: Page): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(p(20), p(14), p(20), p(14))
        background = glass(cardBg, Color.parseColor("#D9223A63"))
        isClickable = true
        setOnClickListener { page = to; render() }
        addView(txt(title, 15f, muted))
        addView(txt(big, 30f, Color.WHITE, true).apply { setPadding(0, p(4), 0, p(2)) })
        addView(txt(sub, 14f, soft).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    }

    private fun buildHome(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        sv.addView(col)
        col.addView(header("OpenCar Music"))
        col.addView(txt("Historial, carátulas y tu música guardada: todo se acopla al reproductor del launcher.", 14f, soft).apply { setPadding(p(28), 0, p(28), p(8)) })
        val today = Db.countPlays(this, System.currentTimeMillis() - 86_400_000L)
        val pend = try { if (hasAudio()) Db.pending(this).size else Db.pending(this).size } catch (e: Exception) { 0 }
        val g1 = LinearLayout(this).apply { setPadding(p(16), 0, p(16), 0) }
        g1.addView(card("HISTORIAL", "${Db.countPlays(this)}", "escuchas · $today en las últimas 24 h", Page.HISTORY), lp(0, WRAP_CONTENT, 1f, 6f))
        g1.addView(card("PENDIENTES", "$pend", "canciones para conseguir de forma legal", Page.PENDING), lp(0, WRAP_CONTENT, 1f, 6f))
        col.addView(g1)
        val g2 = LinearLayout(this).apply { setPadding(p(16), 0, p(16), 0) }
        g2.addView(card("CARÁTULAS", "${Db.countCovers(this)}", "guardadas · se entregan al launcher", Page.COVERS), lp(0, WRAP_CONTENT, 1f, 6f))
        val last = if (Sync.lastRun > 0) "Última: ${fmtDate(Sync.lastRun)}" else "Aún no sincroniza"
        g2.addView(card("SINCRONIZACIÓN", "${Db.countSynced(this)}", "canciones copiadas · $last", Page.SYNC), lp(0, WRAP_CONTENT, 1f, 6f))
        col.addView(g2)
        val g3 = LinearLayout(this).apply { setPadding(p(16), 0, p(16), 0) }
        g3.addView(card("LETRAS", "${Db.countLyrics(this)}", "canciones con letra en tus archivos", Page.LYRICS), lp(0, WRAP_CONTENT, 1f, 6f))
        g3.addView(card("MÚSICA LIBRE", "${Db.credits(this).size}", "descargadas · Creative Commons y dominio público", Page.FREE), lp(0, WRAP_CONTENT, 1f, 6f))
        col.addView(g3)

        val st = glassCol()
        st.addView(section("Estado"))
        if (hasListener()) statusRow(st, 0, "Historial activo", "Registro lo que suena por Bluetooth y apps")
        else statusRow(st, 2, "Falta activar el historial", "Dale acceso a las notificaciones a «OpenCar Music»", "Activar") {
            try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) { toast("No pude abrir ese ajuste") }
        }
        if (hasAudio()) statusRow(st, 0, "Permiso de música", "Puedo leer tu música guardada")
        else statusRow(st, 2, "Falta el permiso de música", "Sirve para carátulas y para saber qué ya tienes", "Dar permiso") {
            requestPermissions(arrayOf(audioPerm()) + (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()), 1)
        }
        if (launcherInstalled()) statusRow(st, 0, "OpenCar Launcher detectado", "Sus pantallas de música usan estas carátulas automáticamente")
        else statusRow(st, 1, "No veo OpenCar Launcher", "Instálalo para ver las carátulas en su reproductor")
        col.addView(st, lp(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p(22), p(8), p(22), p(18)) })
        return sv
    }

    // ------------------------------------------------------------ listas

    private class Row(val title: String, val sub: String, val right: String, val onClick: () -> Unit)

    private fun rowView(r: Row): View {
        val v = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(28), 0, p(28), 0)
            layoutParams = AbsListView.LayoutParams(MATCH_PARENT, p(64))
        }
        val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(txt(r.title, 17f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        tx.addView(txt(r.sub, 13f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, p(2), 0, 0) })
        v.addView(tx, lp(0, WRAP_CONTENT, 1f))
        v.addView(txt(r.right, 13f, muted).apply { setPadding(p(12), 0, 0, 0) })
        return v
    }

    private fun listOf(rows: List<Row>): ListView = ListView(this).apply {
        divider = ColorDrawable(Color.parseColor("#1AFFFFFF")); dividerHeight = 1
        overScrollMode = View.OVER_SCROLL_NEVER
        selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#26FFFFFF")))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int) = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?) = rowView(rows[i])
        }
        setOnItemClickListener { _, _, i, _ -> rows[i].onClick() }
    }

    private fun emptyMsg(col: LinearLayout, title: String, sub: String) {
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(p(60), 0, p(60), p(40)) }
        b.addView(txt(title, 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        b.addView(txt(sub, 15f, soft).apply { gravity = Gravity.CENTER; setPadding(0, p(10), 0, 0) })
        col.addView(b, lp(MATCH_PARENT, 0, 1f))
    }

    // ------------------------------------------------------------ historial

    private fun buildHistory(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        col.addView(header("Historial de escuchas"))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(p(24), p(4), p(24), p(6)) }
        listOf("Todo" to null, "Bluetooth" to "bt", "Apps" to "app", "Mi música" to "local", "Radio" to "radio").forEach { (l, k) ->
            bar.addView(pill(l, histFilter == k) { histFilter = k; render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        }
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Borrar historial", false) {
            AlertDialog.Builder(this).setTitle("¿Borrar todo el historial?")
                .setPositiveButton("Borrar") { _, _ -> Db.clearPlays(this); render() }.setNegativeButton("Cancelar", null).show()
        })
        col.addView(bar)
        val l = Db.plays(this, histFilter, 500)
        if (l.isEmpty()) {
            emptyMsg(col, "Aún no hay escuchas", if (hasListener()) "Reproduce algo (una canción cuenta a los 20 segundos)." else "Activa el acceso a notificaciones en el inicio de esta app.")
            return col
        }
        col.addView(listOf(l.map { Row(it.title, "${it.artist.ifBlank { "Artista desconocido" }} · ${kindLabel(it.kind)} · ${fmtDate(it.ts)}", fmtMin(it.listened)) {} }), lp(MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------ pendientes

    private fun searchUrl(kind: Int, t: String, a: String): String {
        val q = Uri.encode("$t $a".trim())
        return when (kind) {
            0 -> "https://music.apple.com/search?term=$q"
            1 -> "https://bandcamp.com/search?q=$q&item_type=t"
            else -> "https://www.google.com/search?q=" + Uri.encode("comprar $t $a".trim())
        }
    }

    private fun buildPending(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        col.addView(header("Pendientes por conseguir"))
        val list = Db.pending(this)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(p(24), p(4), p(24), p(6)) }
        bar.addView(txt("Lo que escuchaste y no está guardado en la pantalla. Cómpralo o bájalo de forma legal.", 13.5f, soft), lp(0, WRAP_CONTENT, 1f))
        if (list.isNotEmpty()) {
            val text = list.joinToString("\n") { "${it.title} — ${it.artist}" }
            bar.addView(pill("Copiar lista", false) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("pendientes", text)); toast("Lista copiada")
            }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(8) })
            bar.addView(pill("Compartir", false) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir lista"))
            }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(8) })
        }
        col.addView(bar)
        if (list.isEmpty()) { emptyMsg(col, "Nada pendiente", "Cuando escuches por Bluetooth algo que no tengas guardado, aparecerá aquí."); return col }
        col.addView(listOf(list.map { pd ->
            Row(pd.title, "${pd.artist.ifBlank { "Artista desconocido" }} · ${pd.count} ${if (pd.count == 1) "vez" else "veces"} · ${fmtDate(pd.lastTs)}", "›") {
                AlertDialog.Builder(this).setTitle(pd.title)
                    .setItems(arrayOf("Buscar en Apple Music", "Buscar en Bandcamp", "Buscar para comprar (Google)", "Ya la tengo (ocultar)", "No me interesa (ocultar)")) { _, w ->
                        if (w <= 2) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl(w, pd.title, pd.artist)))) } catch (e: Exception) { toast("La pantalla no tiene navegador") }
                        else { Db.setDismissed(this, pd.key, if (w == 3) "have" else "no"); render() }
                    }.show()
            }
        }), lp(MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------ caratulas

    private fun buildCovers(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        sv.addView(col)
        col.addView(header("Carátulas automáticas"))
        val g = glassCol()
        g.addView(txt("Busco la imagen de cada álbum de tu música guardada en bases abiertas (MusicBrainz y Cover Art Archive) cuando hay internet. Se guardan en la pantalla y el launcher las muestra solas en su reproductor, también para lo que suena por Bluetooth.", 14.5f, soft))
        liveA = txt("", 20f, Color.WHITE, true).apply { setPadding(0, p(12), 0, p(4)) }
        g.addView(liveA)
        val acts = LinearLayout(this).apply { setPadding(0, p(8), 0, 0) }
        acts.addView(tile("Buscar carátulas ahora") {
            if (!hasAudio()) { toast("Primero da el permiso de música en el inicio"); return@tile }
            if (Covers.running) { toast("Ya está buscando"); return@tile }
            Thread { Covers.scanLibrary(applicationContext) }.start(); toast("Buscando carátulas…")
        }, lp(0, p(70), 1f, 4f))
        acts.addView(tile("Cancelar") { Covers.cancel = true }, lp(0, p(70), 1f, 4f))
        g.addView(acts)
        col.addView(g, lp(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p(22), p(6), p(22), p(8)) })

        val files = (getExternalFilesDir("covers") ?: File(filesDir, "covers")).listFiles { f -> f.extension == "jpg" }?.sortedByDescending { it.lastModified() }?.take(12) ?: emptyList()
        if (files.isNotEmpty()) {
            val strip = glassCol()
            strip.addView(section("Últimas carátulas guardadas"))
            val row = LinearLayout(this)
            files.take(8).forEach { f ->
                val bmp = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (e: Exception) { null }
                if (bmp != null) row.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, lp(p(92), p(92)).apply { rightMargin = p(10) })
            }
            strip.addView(row)
            col.addView(strip, lp(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p(22), 0, p(22), p(18)) })
        }
        return sv
    }

    // ------------------------------------------------------------ sincronizacion

    private fun field(hint: String, key: String, pass: Boolean = false): EditText {
        val sp = Sync.prefs(this)
        return EditText(this).apply {
            setText(sp.getString(key, "") ?: "")
            this.hint = hint
            setHintTextColor(Color.parseColor("#66FFFFFF")); setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
            setSingleLine(true)
            background = rounded(Color.parseColor("#33101E38"), 12f, true)
            setPadding(p(14), p(10), p(14), p(10))
            inputType = if (pass) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            tag = key
        }
    }

    private fun buildSync(): View {
        val sv = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        sv.addView(root)
        root.addView(header("Sincronizar mi música"))
        val body = LinearLayout(this).apply { setPadding(p(14), p(4), p(14), p(14)) }
        root.addView(body)
        val left = glassCol(); val right = glassCol()
        body.addView(left, lp(0, WRAP_CONTENT, 1f, 5f)); body.addView(right, lp(0, WRAP_CONTENT, 1f, 5f))
        val sp = Sync.prefs(this)

        left.addView(section("Tu carpeta de música"))
        val type = sp.getString("type", "http") ?: "http"
        val tr = LinearLayout(this).apply { setPadding(0, 0, 0, p(8)) }
        tr.addView(pill("Carpeta de mi PC (índice HTTP)", type == "http") { sp.edit().putString("type", "http").apply(); render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        tr.addView(pill("Nube / NAS (WebDAV)", type == "dav") { sp.edit().putString("type", "dav").apply(); render() })
        left.addView(tr)
        val fUrl = field("Dirección, ej. http://192.168.0.10:8000/", "url")
        val fUser = field("Usuario (si lo pide)", "user")
        val fPass = field("Contraseña (si la pide)", "pass", true)
        left.addView(fUrl, lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p(8) })
        left.addView(fUser, lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p(8) })
        left.addView(fPass, lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p(8) })
        fun save() {
            sp.edit().putString("url", fUrl.text.toString().trim()).putString("user", fUser.text.toString().trim()).putString("pass", fPass.text.toString()).apply()
        }
        val metered = sp.getBoolean("metered", false)
        left.addView(pill("Permitir datos del celular o hotspot: ${if (metered) "sí" else "no"}", metered) {
            save(); sp.edit().putBoolean("metered", !metered).apply(); Scheduler.setup(this); render()
        })
        val acts = LinearLayout(this).apply { setPadding(0, p(10), 0, 0) }
        acts.addView(tile("Guardar") { save(); toast("Guardado"); Scheduler.setup(this) }, lp(0, p(66), 1f, 4f))
        acts.addView(tile("Probar conexión") {
            save(); toast("Probando…")
            Thread { val r = Sync.test(applicationContext); handler.post { AlertDialog.Builder(this).setMessage(r).setPositiveButton("OK", null).show() } }.start()
        }, lp(0, p(66), 1f, 4f))
        left.addView(acts)
        val acts2 = LinearLayout(this)
        acts2.addView(tile("Sincronizar ahora") {
            save()
            try { startForegroundService(Intent(this, SyncService::class.java)); toast("Sincronizando…") } catch (e: Exception) { toast("No pude iniciar") }
        }, lp(0, p(66), 1f, 4f))
        acts2.addView(tile("Cancelar") { Sync.cancel = true }, lp(0, p(66), 1f, 4f))
        left.addView(acts2)

        right.addView(section("Estado"))
        liveB = txt("", 14f, soft)
        right.addView(liveB)
        right.addView(section("Cómo prepararlo"))
        right.addView(txt("PC: abre una terminal en tu carpeta de música y escribe:  python -m http.server 8000\nEn la pantalla usa  http://IP-DE-TU-PC:8000/  (el PC y la pantalla en la misma red o hotspot).\n\nNube/NAS: usa la dirección WebDAV de tu carpeta (Nextcloud, Synology…), con tu usuario y contraseña.\n\nCopio solo música tuya: se guarda en Music/OpenCarSync y aparece sola en «Mi música» del launcher. Se repite cada vez que haya red.", 13.5f, soft))
        return sv
    }


    // ------------------------------------------------------------ letras

    private fun buildLyrics(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        col.addView(header("Letras de tus archivos"))
        val g = glassCol()
        g.addView(txt("Leo las letras que ya traen tus archivos: etiquetas incrustadas (MP3, M4A, FLAC) y archivos .lrc que sincronices junto a la música. El launcher las muestra con el botón «Letra» del reproductor, y las sincronizadas (.lrc) se resaltan línea por línea. No descargo letras de internet.", 14.5f, soft))
        liveA = txt("", 18f, Color.WHITE, true).apply { setPadding(0, p(10), 0, p(4)) }
        g.addView(liveA)
        val acts = LinearLayout(this).apply { setPadding(0, p(6), 0, 0) }
        acts.addView(tile("Buscar letras en mis archivos") {
            if (!hasAudio()) { toast("Primero da el permiso de música en el inicio"); return@tile }
            if (Lyrics.running) { toast("Ya está leyendo"); return@tile }
            Thread { Lyrics.indexLibrary(applicationContext); handler.post { if (page == Page.LYRICS) render() } }.start(); toast("Leyendo etiquetas…")
        }, lp(0, p(64), 1f, 4f))
        g.addView(acts)
        col.addView(g, lp(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p(22), p(6), p(22), p(8)) })
        val list = Db.lyricList(this, 400)
        if (list.isEmpty()) { emptyMsg(col, "Aún no hay letras", "Pulsa «Buscar letras en mis archivos», o sincroniza tus .lrc desde Sincronizar."); return col }
        col.addView(listOf(list.map { it0 ->
            Row(it0.title, it0.artist.ifBlank { "Artista desconocido" }, "›") {
                val ly = Db.getLyrics(this, it0.key)
                AlertDialog.Builder(this).setTitle(it0.title).setMessage((ly?.first ?: "").replace(Regex("\\[[0-9:.]+\\]"), "")).setPositiveButton("Cerrar", null).show()
            }
        }), lp(MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------ musica libre

    private fun buildFree(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        col.addView(header("Música libre (Creative Commons)"))
        val fp = getSharedPreferences("free", Context.MODE_PRIVATE)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(p(24), p(4), p(24), p(6)) }
        bar.addView(pill("Internet Archive", freeSrc == "archive") { freeSrc = "archive"; freeHits = emptyList(); render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        bar.addView(pill("Jamendo", freeSrc == "jamendo") { freeSrc = "jamendo"; freeHits = emptyList(); render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(pill("Créditos y licencias", false) { page = Page.CREDITS; render() })
        col.addView(bar)
        var keyField: EditText? = null
        if (freeSrc == "jamendo") {
            keyField = EditText(this).apply {
                setText(fp.getString("jamendo_key", "") ?: ""); hint = "Tu clave gratuita de Jamendo (devportal.jamendo.com)"
                setHintTextColor(Color.parseColor("#66FFFFFF")); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * scale); setSingleLine(true)
                background = rounded(Color.parseColor("#33101E38"), 12f, true); setPadding(p(14), p(8), p(14), p(8))
            }
            col.addView(keyField, lp(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p(24), 0, p(24), p(6)) })
        }
        val sr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(p(24), 0, p(24), p(6)) }
        val box = EditText(this).apply {
            setText(freeQuery); hint = "Busca por artista, canción o género"
            setHintTextColor(Color.parseColor("#66FFFFFF")); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale); setSingleLine(true)
            background = rounded(Color.parseColor("#33101E38"), 12f, true); setPadding(p(14), p(10), p(14), p(10))
        }
        sr.addView(box, lp(0, WRAP_CONTENT, 1f))
        sr.addView(tile("Buscar") {
            freeQuery = box.text.toString().trim()
            if (freeQuery.isBlank()) { toast("Escribe qué buscar"); return@tile }
            val key = keyField?.text?.toString()?.trim() ?: (fp.getString("jamendo_key", "") ?: "")
            if (freeSrc == "jamendo") { if (key.isBlank()) { toast("Pon tu clave de Jamendo"); return@tile }; fp.edit().putString("jamendo_key", key).apply() }
            freeMsg = "Buscando…"; Free.status = ""; render()
            val src = freeSrc; val qy = freeQuery
            Thread {
                val r = try { if (src == "jamendo") Free.searchJamendo(key, qy) else Free.searchArchive(qy) } catch (e: Exception) { emptyList() }
                handler.post { freeHits = r; freeMsg = if (r.isEmpty()) "Sin resultados con licencia libre. Prueba otra palabra." else "${r.size} resultados con licencia libre"; if (page == Page.FREE) render() }
            }.start()
        }, lp(p(140), p(54)).apply { leftMargin = p(10) })
        col.addView(sr)
        liveB = txt("", 13.5f, amber).apply { setPadding(p(28), 0, p(28), p(4)) }
        col.addView(liveB)
        col.addView(txt(freeMsg, 13.5f, soft).apply { setPadding(p(28), 0, p(28), p(4)) })
        if (freeHits.isNotEmpty()) {
            col.addView(listOf(freeHits.map { h ->
                Row(h.title, "${h.artist.ifBlank { "Varios" }} · ${h.license}", "›") { askDownload(h) }
            }), lp(MATCH_PARENT, 0, 1f))
        } else {
            col.addView(txt("Solo aparecen canciones con licencia Creative Commons o dominio público. Se guardan en Music/OpenCarLibre y salen en «Mi música» del launcher, con su licencia en «Créditos».", 14f, muted).apply { setPadding(p(28), p(10), p(28), 0) })
        }
        return col
    }

    private fun askDownload(h: Free.Hit) {
        if (Free.busy) { toast("Ya hay una descarga en curso"); return }
        toast("Consultando…")
        Thread {
            val tracks = if (h.src == "archive") Free.archiveTracks(h.id) else null
            handler.post {
                if (h.src == "archive" && (tracks == null || tracks.isEmpty())) { toast("No encontré archivos de audio descargables"); return@post }
                val n = tracks?.size ?: 1
                AlertDialog.Builder(this).setTitle(h.title)
                    .setMessage("${if (n == 1) "1 pista" else "$n pistas"} · Licencia: ${h.license.ifBlank { "libre" }}\nSe guardan en Music/OpenCarLibre. Al usarla, respeta la licencia (créditos en esta app).")
                    .setPositiveButton("Descargar") { _, _ ->
                        Thread { val ok = Free.download(applicationContext, h, tracks); handler.post { toast("$ok guardadas"); if (page == Page.FREE) render() } }.start()
                        toast("Descargando…")
                    }.setNegativeButton("Cancelar", null).show()
            }
        }.start()
    }

    private fun buildCredits(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        col.addView(header("Créditos y licencias"))
        val l = Db.credits(this)
        if (l.isEmpty()) { emptyMsg(col, "Aún no descargaste música libre", "Cada canción descargada queda aquí con su licencia y su enlace de origen."); return col }
        col.addView(listOf(l.map { c0 ->
            Row(c0.title, "${c0.artist.ifBlank { "Varios" }} · ${c0.license}", fmtDate(c0.ts)) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c0.url))) } catch (e: Exception) { toast(c0.url) }
            }
        }), lp(MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------ refresco en vivo

    private fun updateLive() {
        liveA?.text = if (page == Page.LYRICS) (if (Lyrics.running) "Leyendo etiquetas… ${Lyrics.done} de ${Lyrics.total} · ${Lyrics.found} con letra" else "Canciones con letra: ${Db.countLyrics(this)}") else if (Covers.running) "Buscando… ${Covers.done} de ${Covers.total} · ${Covers.found} con carátula" else "Carátulas guardadas: ${Db.countCovers(this)}"
        if (page == Page.FREE) liveB?.text = Free.status
        else liveB?.text = (if (Sync.running) "Sincronizando: ${Sync.done} copiadas de ${Sync.found}" + (if (Sync.current.isNotEmpty()) "\n${Sync.current}" else "")
            else (if (Sync.lastRun > 0) "Última vez: ${fmtDate(Sync.lastRun)} · ${Sync.lastSummary}" else "Todavía no sincronizó.")) + "\n\n" + Sync.logText()
    }
}
