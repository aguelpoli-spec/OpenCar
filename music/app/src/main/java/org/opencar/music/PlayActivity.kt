package org.opencar.music

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** OpenCar Music: reproductor completo (música del equipo, USB, sincronizada y libre) con letra, ecualizador y mezcla DJ. */
class PlayActivity : Activity() {
    private val sp by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private var sc = 1f
    private fun p(v: Int) = (v * sc).toInt()
    private val acc = 0xFF4DD0E1.toInt()
    private val fg = Color.WHITE
    private val fg2 = 0xFF9FB0C8.toInt()
    private val line = 0x22FFFFFF
    private val glass = 0x14FFFFFF

    private enum class Tab { HOME, LIB, SEARCH, LYRICS, SET }
    private var tab = Tab.HOME
    private var seg = 0                  // pestaña de la biblioteca
    private var source = 0               // 0 todo, 1 equipo, 2 USB, 3 sincronizada, 4 libre
    private var detail: Pair<String, String>? = null
    private var ovl: String? = null   // now, queue, eq, lyrics
    private var query = ""

    private lateinit var root: FrameLayout
    private var lastKey = ""
    private var nowBar: BarView? = null
    private var nowTime: TextView? = null
    private var nowLeft: TextView? = null
    private var nowPlay: IconView? = null
    private var miniBar: BarView? = null
    private var miniPlay: IconView? = null
    private var lyricBox: LinearLayout? = null
    private var lyricScroll: ScrollView? = null
    private var lyricLines: List<Pair<Long, String>> = emptyList()
    private var lyricIdx = -2

    // ------------------------------------------------------------------ datos
    private fun songs(): List<LocalPlayer.Track> = LocalPlayer.songs.let { all ->
        when (source) {
            1 -> all.filter { kindOf(it) == 1 }
            2 -> all.filter { kindOf(it) == 2 }
            3 -> all.filter { kindOf(it) == 3 }
            4 -> all.filter { kindOf(it) == 4 }
            else -> all
        }
    }
    private fun kindOf(t: LocalPlayer.Track): Int {
        val pa = t.path
        return when {
            pa.contains("/OpenCarSync", true) -> 3
            pa.contains("/OpenCarLibre", true) -> 4
            pa.startsWith("/storage/emulated") || pa.startsWith("/sdcard") -> 1
            pa.startsWith("/storage/") -> 2
            else -> 1
        }
    }
    private fun favs(): MutableSet<String> = sp.getStringSet("fav", emptySet())!!.toMutableSet()
    private fun isFav(t: LocalPlayer.Track) = favs().contains(t.key)
    private fun toggleFav(t: LocalPlayer.Track) { val f = favs(); if (!f.add(t.key)) f.remove(t.key); sp.edit().putStringSet("fav", f).apply() }
    private fun byKey(keys: Collection<String>): List<LocalPlayer.Track> { val m = LocalPlayer.songs.associateBy { it.key }; return keys.mapNotNull { m[it] } }
    private fun recents(): List<LocalPlayer.Track> = byKey((sp.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() })
    private fun pushRecent(t: LocalPlayer.Track) {
        val l = (sp.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() && it != t.key }.toMutableList()
        l.add(0, t.key); sp.edit().putString("recent", l.take(30).joinToString("\n")).apply()
    }
    private fun playlists(): MutableMap<String, MutableList<String>> {
        val o = try { JSONObject(sp.getString("lists", "{}")!!) } catch (e: Exception) { JSONObject() }
        val m = LinkedHashMap<String, MutableList<String>>()
        for (k in o.keys()) { val a = o.getJSONArray(k); m[k] = (0 until a.length()).map { a.getString(it) }.toMutableList() }
        return m
    }
    private fun savePlaylists(m: Map<String, List<String>>) { val o = JSONObject(); m.forEach { (k, v) -> o.put(k, JSONArray(v)) }; sp.edit().putString("lists", o.toString()).apply() }

    // ------------------------------------------------------------------ lo que suena (propio o de otra app)
    class Np(val title: String, val artist: String, val album: String, val pos: Long, val dur: Long, val playing: Boolean,
             val track: LocalPlayer.Track?, val art: Bitmap?, val ctrl: MediaController?, val source: String)

    private fun external(): MediaController? = try {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(this, HistoryService::class.java)).firstOrNull { it.packageName != packageName && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null && it.playbackState?.state == PlaybackState.STATE_PLAYING }
    } catch (e: Exception) { null }

    private fun np(): Np? {
        val t = LocalPlayer.current
        if (!LocalPlayer.isPlaying) {
            val c = external()
            if (c != null) {
                val m = c.metadata!!
                val st = c.playbackState
                val pk = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (e: Exception) { c.packageName }
                return Np(m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "", m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "", m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                    st?.position ?: 0L, m.getLong(MediaMetadata.METADATA_KEY_DURATION), true, null, m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART), c, pk)
            }
        }
        if (t == null) return null
        return Np(t.title, t.artist, t.album, LocalPlayer.position(), t.dur, LocalPlayer.isPlaying, t, null, null, "Música del equipo")
    }

    private fun toggle(n: Np?) { if (n?.ctrl != null) { if (n.playing) n.ctrl.transportControls.pause() else n.ctrl.transportControls.play() } else LocalPlayer.toggle() }
    private fun next(n: Np?) { if (n?.ctrl != null) n.ctrl.transportControls.skipToNext() else LocalPlayer.next(false) }
    private fun prev(n: Np?) { if (n?.ctrl != null) n.ctrl.transportControls.skipToPrevious() else LocalPlayer.prev() }

    // ------------------------------------------------------------------ ciclo de vida
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sc = min(resources.displayMetrics.widthPixels / 1024f, resources.displayMetrics.heightPixels / 600f)
        LocalPlayer.init(this)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF070B14.toInt()) }
        setContentView(root)
        try {
            if (Build.VERSION.SDK_INT >= 30) { window.setDecorFitsSystemWindows(false); window.insetsController?.hide(WindowInsets.Type.systemBars()); window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } catch (e: Throwable) { }
        ensureLibrary()
        render()
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); setIntent(i); handleIntent(i) }

    private fun ensureLibrary() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val need = ArrayList<String>()
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) need.add(perm)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty() && !sp.getBoolean("asked_perm", false)) { sp.edit().putBoolean("asked_perm", true).apply(); requestPermissions(need.toTypedArray(), 12); return }
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return
        if (!LocalPlayer.loaded && !LocalPlayer.loading) Thread { LocalPlayer.scan(this); ui.post { lastKey = ""; render(); runPending() } }.start()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) { if (rc == 12) { sp.edit().putBoolean("asked_perm", true).apply(); ensureLibrary() } }

    private fun listenerOn(): Boolean = try { (android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName) } catch (e: Exception) { true }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ovl != null -> { ovl = null; render() }
            detail != null -> { detail = null; render() }
            tab != Tab.HOME -> { tab = Tab.HOME; render() }
            else -> moveTaskToBack(true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { refresh() } catch (e: Exception) { }
            ui.postDelayed(this, 500)
        }
    }

    private fun refresh() {
        val n = np()
        val key = (n?.title ?: "") + "|" + (n?.artist ?: "") + "|" + (n?.playing == true) + "|" + (ovl ?: "") + "|" + tab + "|" + LocalPlayer.songs.size
        if (n?.track != null && key != lastKey) pushRecent(n.track)
        if (n != null && key != lastKey) prefetchLyrics(n)
        if (key != lastKey) { lastKey = key; render(); return }
        if (n != null) {
            val f = if (n.dur > 0) n.pos.toFloat() / n.dur else 0f
            if (nowBar?.dragging != true) nowBar?.frac = f
            nowBar?.invalidate(); miniBar?.frac = f; miniBar?.invalidate()
            nowTime?.text = fmt(n.pos); nowLeft?.text = "-" + fmt(max(0, n.dur - n.pos))
            if (lyricLines.isNotEmpty()) syncLyrics(n.pos)
        }
    }

    private val lyricTried = HashSet<String>()
    private fun prefetchLyrics(n: Np) {
        val k = n.artist + "|" + n.title
        if (!lyricTried.add(k)) return
        if (Lyrics.lookup(this, n.artist, n.title, n.track?.name ?: "") != null) return
        Thread {
            val ok = LyricsNet.fetch(this, n.artist, n.title, n.album, n.dur)
            if (ok && ovl == "lyrics") ui.post { lastKey = ""; render() }
        }.start()
    }

    // ------------------------------------------------------------------ ayudas de diseño
    private fun bg(c: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply { cornerRadius = p(r).toFloat(); setColor(c); if (stroke != 0) setStroke(max(1, p(1)), stroke) }
    private fun tv(s: String, sp: Float, c: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(c); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * sc); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun icon(k: String, size: Int, tint: Int = fg, onClick: (() -> Unit)? = null) = IconView(this, k, tint).also { v ->
        v.layoutParams = LinearLayout.LayoutParams(p(size), p(size))
        if (onClick != null) { v.isClickable = true; v.setOnClickListener { onClick() } }
    }
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun fmt(ms: Long): String { val s = (ms / 1000).toInt(); return String.format(Locale.US, "%d:%02d", s / 60, s % 60) }

    private fun cover(t: LocalPlayer.Track?, size: Int, r: Float = 14f, label: String = ""): CoverView = CoverView(this).apply {
        radiusDp = r; layoutParams = LinearLayout.LayoutParams(p(size), p(size)); setTrack(t, label, max(200, p(size)))
    }

    private fun pill(label: String, on: Boolean, go: () -> Unit) = tv(label, 13f, if (on) Color.parseColor("#021018") else fg, true).apply {
        setPadding(p(18), p(8), p(18), p(8)); background = bg(if (on) acc else glass, 18, if (on) 0 else line); isClickable = true; setOnClickListener { go() }
    }

    // ------------------------------------------------------------------ pantalla
    private fun render() {
        root.removeAllViews(); nowBar = null; nowTime = null; nowLeft = null; nowPlay = null; miniBar = null; miniPlay = null; lyricBox = null; lyricScroll = null
        val base = FrameLayout(this)
        base.setBackgroundColor(0xFF070B14.toInt())
        val content = FrameLayout(this)
        when (tab) {
            Tab.HOME -> content.addView(home())
            Tab.LIB -> content.addView(library())
            Tab.SEARCH -> content.addView(searchTab())
            Tab.LYRICS -> content.addView(lyricsTab())
            Tab.SET -> content.addView(settings())
        }
        base.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { bottomMargin = p(62) })
        if (np() != null) base.addView(miniPlayer(), FrameLayout.LayoutParams(MATCH_PARENT, p(68), Gravity.BOTTOM).apply { bottomMargin = p(72); leftMargin = p(16); rightMargin = p(16) })
        base.addView(tabBar(), FrameLayout.LayoutParams(MATCH_PARENT, p(62), Gravity.BOTTOM))
        root.addView(base, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        when (ovl) {
            "now" -> root.addView(nowView())
            "queue" -> root.addView(queueView())
            "eq" -> root.addView(eqView())
            "lyrics" -> root.addView(lyricsView())
        }
    }

    private fun tabBar(): View {
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(0xEB070B14.toInt()) }
        val items = listOf(Tab.HOME to ("home" to "Inicio"), Tab.LIB to ("library" to "Biblioteca"), Tab.SEARCH to ("search" to "Buscar"), Tab.LYRICS to ("lyrics" to "Letras"), Tab.SET to ("settings" to "Ajustes"))
        for ((t, ik) in items) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; setOnClickListener { tab = t; detail = null; ovl = null; render() } }
            val col = if (tab == t) acc else fg2
            c.addView(icon(ik.first, 26, col)); c.addView(tv(ik.second, 11f, col))
            b.addView(c, lp(p(120), MATCH_PARENT))
        }
        return b
    }

    private fun miniPlayer(): View {
        val n = np()!!
        val m = FrameLayout(this).apply { background = bg(0xF01A2438.toInt(), 20, line); isClickable = true; setOnClickListener { ovl = "now"; render() } }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p(12), 0, p(14), 0) }
        r.addView(if (n.track != null) cover(n.track, 48, 10f) else ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(p(48), p(48)); setImageBitmap(n.art); scaleType = ImageView.ScaleType.CENTER_CROP })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), 0, 0, 0) }
        col.addView(tv(n.title, 15f, fg, true).apply { maxLines = 1 }); col.addView(tv(n.artist + (if (n.track == null) " · ${n.source}" else ""), 12f, fg2).apply { maxLines = 1 })
        r.addView(col, lp(0, WRAP_CONTENT, 1f))
        r.addView(icon("prev", 28) { prev(np()) }.apply { setPadding(p(2), p(2), p(2), p(2)) })
        val pl = icon(if (n.playing) "pause" else "play", 32, acc) { toggle(np()); lastKey = "" }; miniPlay = pl
        r.addView(pl, lp(p(44), p(44)).apply { leftMargin = p(10); rightMargin = p(10) })
        r.addView(icon("next", 28) { next(np()) })
        m.addView(r, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val bar = BarView(this, acc).apply { knob = false; frac = if (n.dur > 0) n.pos.toFloat() / n.dur else 0f }; miniBar = bar
        m.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, p(8), Gravity.BOTTOM).apply { leftMargin = p(16); rightMargin = p(16) })
        return m
    }

    // ------------------------------------------------------------------ inicio
    private fun home(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(22), p(24), p(90)) }
        sv.addView(col)
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        g.addView(tv(if (hr < 12) "Buenos días" else if (hr < 19) "Buenas tardes" else "Buenas noches", 13f, fg2)); g.addView(tv("Tu música", 30f, fg, true))
        top.addView(g, lp(0, WRAP_CONTENT, 1f))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Todo", "Del equipo", "USB", "Sincronizada", "Libre").forEachIndexed { i, t -> chips.addView(pill(t, source == i) { source = i; render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = p(8) }) }
        top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        col.addView(top)
        if (!listenerOn()) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg((acc and 0x00FFFFFF) or 0x22000000, 16, (acc and 0x00FFFFFF) or 0x66000000); setPadding(p(16), p(10), p(16), p(10)); isClickable = true
                setOnClickListener { try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (e: Exception) { toast("No pude abrir ese ajuste") } } }
            c.addView(tv("Activa el acceso a notificaciones", 14f, acc, true)); c.addView(tv("Para ver aquí lo que suena en Spotify, YouTube Music o Bluetooth y guardar tu historial. Toca para activarlo.", 12f, fg2))
            col.addView(c, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(12) })
        }
        val ext = external()
        if (ext != null && !LocalPlayer.isPlaying) {
            val m = ext.metadata!!
            col.addView(tv("♪  Suena en otra app: ${m.getString(MediaMetadata.METADATA_KEY_TITLE)} · ${m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""}", 13f, acc, true).apply { setPadding(0, p(10), 0, 0) })
        }
        val all = songs()
        if (LocalPlayer.loading || (!LocalPlayer.loaded && all.isEmpty())) col.addView(tv("Buscando tu música…", 16f, fg2).apply { setPadding(0, p(40), 0, 0) })
        else if (all.isEmpty()) col.addView(tv("No encuentro música en este origen. Copia canciones al equipo o a un USB, o usa Sincronizar en Ajustes.", 15f, fg2).apply { setPadding(0, p(40), 0, 0) })
        val rec = recents().filter { t -> all.any { it.id == t.id } }
        if (rec.isNotEmpty()) { col.addView(tv("Continuar escuchando", 18f, fg, true).apply { setPadding(0, p(22), 0, p(10)) }); col.addView(trackRow(rec.take(10))) }
        val added = all.sortedByDescending { it.added }.take(10)
        if (added.isNotEmpty()) { col.addView(tv("Recién añadidas", 18f, fg, true).apply { setPadding(0, p(18), 0, p(10)) }); col.addView(trackRow(added)) }
        col.addView(tv("Tus listas", 18f, fg, true).apply { setPadding(0, p(18), 0, p(10)) })
        val lists = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun listCard(name: String, tracks: List<LocalPlayer.Track>, key: String) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(glass, 16, line); setPadding(p(12), p(12), p(18), p(12)); isClickable = true; setOnClickListener { tab = Tab.LIB; seg = 3; detail = Pair("list", key); render() } }
            c.addView(cover(tracks.firstOrNull(), 44, 8f, name)); c.addView(tv(name, 15f, fg, true).apply { setPadding(p(12), 0, 0, 0) })
            lists.addView(c, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(12) })
        }
        listCard("Favoritas", byKey(favs()), "fav"); listCard("Recientes", recents(), "recent")
        for ((n, k) in playlists()) listCard(n, byKey(k), "pl:$n")
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(lists) })
        return sv
    }

    private fun trackRow(list: List<LocalPlayer.Track>): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        list.forEachIndexed { i, t ->
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setOnClickListener { LocalPlayer.playQueue(list, i, false); ovl = "now"; render() } }
            c.addView(cover(t, 150, 16f))
            c.addView(tv(t.title, 14f, fg, true).apply { maxLines = 1; maxWidth = p(150); setPadding(0, p(8), 0, 0) }); c.addView(tv(t.artist, 12f, fg2).apply { maxLines = 1; maxWidth = p(150) })
            r.addView(c, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(16) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    // ------------------------------------------------------------------ biblioteca
    private fun library(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(20), p(24), 0) }
        val d = detail
        if (d != null) return detailView(d)
        col.addView(tv("Biblioteca", 30f, fg, true))
        val segs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p(12), 0, p(10)) }
        listOf("Canciones", "Álbumes", "Artistas", "Listas").forEachIndexed { i, t -> segs.addView(pill(t, seg == i) { seg = i; render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) }) }
        col.addView(segs)
        val body = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, p(90)) }
        body.addView(box)
        val all = songs()
        when (seg) {
            0 -> all.forEachIndexed { i, t -> box.addView(songRow(t) { LocalPlayer.playQueue(all, i, false); ovl = "now"; render() }) }
            1 -> grid(box, all.groupBy { it.albumId to it.album }.map { (k, v) -> Triple(k.second, v.first().artist, v) }) { Pair("album", it.first + "||" + it.second) }
            2 -> grid(box, all.groupBy { it.artist }.map { (k, v) -> Triple(k, "${v.size} canciones", v) }) { Pair("artist", it.first) }
            else -> {
                val l = ArrayList<Triple<String, String, List<LocalPlayer.Track>>>()
                l.add(Triple("Favoritas", "${favs().size} canciones", byKey(favs()))); l.add(Triple("Recientes", "últimas escuchadas", recents()))
                playlists().forEach { (n, k) -> l.add(Triple(n, "${k.size} canciones", byKey(k))) }
                grid(box, l) { Pair("list", if (it.first == "Favoritas") "fav" else if (it.first == "Recientes") "recent" else "pl:" + it.first) }
                box.addView(tv("+ Nueva lista", 15f, acc, true).apply { setPadding(0, p(14), 0, p(8)); setOnClickListener { newList() } })
            }
        }
        col.addView(body, lp(MATCH_PARENT, 0, 1f))
        return col
    }

    private fun grid(box: LinearLayout, items: List<Triple<String, String, List<LocalPlayer.Track>>>, key: (Triple<String, String, List<LocalPlayer.Track>>) -> Pair<String, String>) {
        var row: LinearLayout? = null
        items.sortedBy { it.first.lowercase() }.forEachIndexed { i, item ->
            if (i % 6 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; box.addView(row, lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p(14) }) }
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setOnClickListener { detail = key(item); render() } }
            c.addView(cover(item.third.firstOrNull(), 138, 14f, item.first))
            c.addView(tv(item.first, 13.5f, fg, true).apply { maxLines = 1; maxWidth = p(138); setPadding(0, p(6), 0, 0) }); c.addView(tv(item.second, 11.5f, fg2).apply { maxLines = 1; maxWidth = p(138) })
            row!!.addView(c, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(16) })
        }
    }

    private fun songRow(t: LocalPlayer.Track, onClick: () -> Unit): View {
        val cur = LocalPlayer.current?.id == t.id
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p(8), p(6), p(8), p(6)); isClickable = true; setOnClickListener { onClick() }; setOnLongClickListener { songMenu(t); true }
            if (cur) background = bg((acc and 0x00FFFFFF) or 0x22000000, 12, (acc and 0x00FFFFFF) or 0x66000000) }
        r.addView(cover(t, 44, 8f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(14), 0, 0, 0) }
        col.addView(tv(t.title, 15f, if (cur) acc else fg, true).apply { maxLines = 1 }); col.addView(tv(t.artist + " · " + t.album, 12f, fg2).apply { maxLines = 1 })
        r.addView(col, lp(0, WRAP_CONTENT, 1f))
        if (isFav(t)) r.addView(icon("heartfill", 18, acc)); r.addView(tv(fmt(t.dur), 12f, fg2).apply { setPadding(p(12), 0, p(8), 0) })
        r.addView(icon("more", 22, fg2) { songMenu(t) })
        return r
    }

    private fun songMenu(t: LocalPlayer.Track) {
        val pls = playlists()
        val items = arrayListOf(if (isFav(t)) "Quitar de favoritas" else "Añadir a favoritas", "Reproducir a continuación", "Añadir a una lista…")
        AlertDialog.Builder(this).setTitle(t.title).setItems(items.toTypedArray()) { _, w ->
            when (w) {
                0 -> { toggleFav(t); render() }
                1 -> { toast("Añadida a la cola") ; LocalPlayer.playQueue(listOf(t), 0, false) }
                else -> {
                    val names = pls.keys.toList() + "Nueva lista…"
                    AlertDialog.Builder(this).setTitle("Añadir a").setItems(names.toTypedArray()) { _, i ->
                        if (i == names.size - 1) newList(t) else { pls[names[i]]!!.add(t.key); savePlaylists(pls); toast("Añadida a ${names[i]}") }
                    }.show()
                }
            }
        }.show()
    }

    private fun newList(t: LocalPlayer.Track? = null) {
        val et = EditText(this).apply { hint = "Nombre de la lista"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Nueva lista").setView(et).setPositiveButton("Crear") { _, _ ->
            val n = et.text.toString().trim(); if (n.isEmpty()) return@setPositiveButton
            val pls = playlists(); pls.getOrPut(n) { ArrayList() }; if (t != null) pls[n]!!.add(t.key); savePlaylists(pls); render()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun detailView(d: Pair<String, String>): View {
        val all = songs()
        val (title, list) = when (d.first) {
            "album" -> { val (a, ar) = d.second.split("||").let { it[0] to it.getOrElse(1) { "" } }; "$a · $ar" to all.filter { it.album == a } }
            "artist" -> d.second to all.filter { it.artist == d.second }
            else -> when (d.second) { "fav" -> "Favoritas" to byKey(favs()); "recent" -> "Recientes" to recents(); else -> d.second.removePrefix("pl:") to byKey(playlists()[d.second.removePrefix("pl:")] ?: emptyList()) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(20), p(24), 0) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon("back", 28) { detail = null; render() }, lp(p(40), p(40))); head.addView(tv(title, 26f, fg, true).apply { maxLines = 1 }, lp(0, WRAP_CONTENT, 1f))
        head.addView(pill("▶  Reproducir", true) { if (list.isNotEmpty()) { LocalPlayer.playQueue(list, 0, false); ovl = "now"; render() } }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) })
        head.addView(pill("Aleatorio", false) { if (list.isNotEmpty()) { LocalPlayer.playQueue(list, 0, true); ovl = "now"; render() } })
        col.addView(head)
        val sv = ScrollView(this); val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, p(10), 0, p(90)) }; sv.addView(box)
        list.forEachIndexed { i, t -> box.addView(songRow(t) { LocalPlayer.playQueue(list, i, false); ovl = "now"; render() }) }
        if (list.isEmpty()) box.addView(tv("Aquí no hay canciones todavía. Mantén pulsada una canción para añadirla.", 14f, fg2))
        col.addView(sv, lp(MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------------ buscar
    private fun searchTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(20), p(24), 0) }
        col.addView(tv("Buscar", 30f, fg, true))
        val et = EditText(this).apply { hint = "Canciones, artistas o álbumes"; setHintTextColor(fg2); setTextColor(fg); setSingleLine(); setText(query); background = bg(glass, 22, line); setPadding(p(20), 0, p(20), 0); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17f * sc) }
        col.addView(et, lp(MATCH_PARENT, p(48)).apply { topMargin = p(10); bottomMargin = p(8) })
        val sv = ScrollView(this); val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, p(90)) }; sv.addView(box)
        fun fill() {
            box.removeAllViews()
            val q = Db.norm(query)
            if (q.isBlank()) { box.addView(tv("Escribe para buscar en tu música.", 14f, fg2)); return }
            val toks = q.split(" ").filter { it.isNotEmpty() }
            val hit = LocalPlayer.songs.filter { t -> val n = Db.norm(t.title + " " + t.artist + " " + t.album); toks.all { n.contains(it) } }.take(60)
            if (hit.isEmpty()) box.addView(tv("No encontré «$query».", 14f, fg2))
            hit.forEachIndexed { i, t -> box.addView(songRow(t) { LocalPlayer.playQueue(hit, i, false); ovl = "now"; render() }) }
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; fill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fill()
        col.addView(sv, lp(MATCH_PARENT, 0, 1f))
        return col
    }

    // ------------------------------------------------------------------ ajustes
    private fun settings(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(20), p(24), p(90)) }
        sv.addView(col)
        col.addView(tv("Ajustes", 30f, fg, true))
        fun item(t: String, sub: String, go: () -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(glass, 16, line); setPadding(p(18), p(12), p(18), p(12)); isClickable = true; setOnClickListener { go() } }
            r.addView(tv(t, 16f, fg, true)); r.addView(tv(sub, 12.5f, fg2))
            col.addView(r, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(10) })
        }
        fun page(pg: String) = { startActivity(Intent(this, MainActivity::class.java).putExtra("page", pg)) }
        item("Ecualizador y mezcla DJ", "Graves, agudos, espacial y enlace entre canciones", { ovl = "eq"; render() })
        item("Escanear la música de nuevo", "${LocalPlayer.songs.size} canciones encontradas", { LocalPlayer.loaded = false; Thread { LocalPlayer.scan(this); ui.post { lastKey = ""; render() } }.start(); toast("Buscando…") })
        item("Sincronizar música y carátulas", "Copia música del servidor y busca carátulas y letras", { startActivity(Intent(this, MainActivity::class.java).putExtra("page", "SYNC").putExtra("do", "sync")) })
        item("Historial de lo que escuchas", "Incluye Spotify, YouTube Music y Bluetooth", page("HISTORY"))
        item("Carátulas pendientes", "Canciones sin portada", page("COVERS"))
        item("Letras", "Letras guardadas para usarlas sin internet", page("LYRICS"))
        item("Música libre", "Descarga música con licencia libre", page("FREE"))
        item("Créditos y licencias", "", page("CREDITS"))
        return sv
    }

    // ------------------------------------------------------------------ letras
    private fun lyricsTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(20), p(24), 0) }
        col.addView(tv("Letras", 30f, fg, true))
        val n = np()
        col.addView(tv(if (n != null) "Ahora suena: ${n.title} · ${n.artist}" else "Pon una canción para ver su letra.", 14f, fg2).apply { setPadding(0, p(8), 0, p(14)) })
        if (n != null) col.addView(pill("Ver letra a pantalla completa", true) { ovl = "lyrics"; render() })
        col.addView(tv("OpenCar Music guarda las letras que encuentra (en la canción, en archivos .lrc o al sincronizar con internet) para que se vean sin conexión.", 13f, fg2).apply { setPadding(0, p(16), 0, 0) })
        return col
    }

    private fun lrcOf(n: Np): List<Pair<Long, String>> {
        val f = Lyrics.lookup(this, n.artist, n.title, n.track?.name ?: "") ?: return emptyList()
        if (!f.synced) return f.text.split("\n").filter { it.isNotBlank() }.map { -1L to it.trim() }
        val re = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)")
        val out = ArrayList<Pair<Long, String>>()
        for (l in f.text.split("\n")) {
            val ms = re.findAll(l).toList(); if (ms.isEmpty()) continue
            val txt = ms.last().groupValues[4].trim()
            for (m in ms) { val sec = m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong(); val fr = m.groupValues[3]; val ms2 = if (fr.isEmpty()) 0L else (fr.padEnd(3, '0').take(3)).toLong(); out.add((sec * 1000 + ms2) to txt) }
        }
        return out.sortedBy { it.first }.filter { it.second.isNotEmpty() }
    }

    private fun lyricsView(): View {
        val n = np()
        val f = FrameLayout(this)
        f.addView(backdrop(n))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(40), p(26), p(40), p(20)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon("down", 30) { ovl = "now"; render() }, lp(p(40), p(40)))
        if (n != null) { head.addView(if (n.track != null) cover(n.track, 62, 12f) else View(this), lp(p(62), p(62)).apply { leftMargin = p(10) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(14), 0, 0, 0) }; t.addView(tv(n.title, 20f, fg, true).apply { maxLines = 1 }); t.addView(tv(n.artist, 14f, 0xCCFFFFFF.toInt()).apply { maxLines = 1 }); head.addView(t, lp(0, WRAP_CONTENT, 1f)) }
        col.addView(head)
        lyricLines = if (n != null) lrcOf(n) else emptyList()
        lyricIdx = -2
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, p(40), 0, p(200)) }
        sv.addView(box); lyricBox = box; lyricScroll = sv
        if (lyricLines.isEmpty()) box.addView(tv(if (LyricsNet.online(this)) "Buscando la letra en internet…\nSi no aparece en unos segundos, esta canción no tiene letra publicada." else "Sin internet y sin letra guardada para esta canción.\nCuando tengas internet se busca sola y queda guardada.", 20f, 0xAAFFFFFF.toInt()))
        if (lyricLines.isEmpty() && n != null && LyricsNet.online(this)) Thread { val ok = LyricsNet.fetch(this, n.artist, n.title, n.album, n.dur); if (ok) ui.post { lastKey = ""; render() } }.start()
        lyricLines.forEachIndexed { i, l -> box.addView(tv(l.second, 26f, 0x66FFFFFF, true).apply { setPadding(0, p(10), 0, p(10)); isClickable = true; setOnClickListener { if (l.first >= 0) LocalPlayer.seek(l.first) } }) }
        col.addView(sv, lp(MATCH_PARENT, 0, 1f))
        f.addView(col)
        return f
    }

    private fun syncLyrics(pos: Long) {
        val box = lyricBox ?: return
        if (lyricLines.firstOrNull()?.first ?: -1L < 0L) return
        var idx = -1
        for (i in lyricLines.indices) if (lyricLines[i].first <= pos + 200) idx = i
        if (idx == lyricIdx) return
        lyricIdx = idx
        for (i in 0 until box.childCount) (box.getChildAt(i) as? TextView)?.let { it.setTextColor(if (i == idx) Color.WHITE else 0x66FFFFFF); it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (if (i == idx) 34f else 26f) * sc) }
        if (idx >= 0) box.getChildAt(idx)?.let { v -> lyricScroll?.smoothScrollTo(0, max(0, v.top - p(120))) }
    }

    // ------------------------------------------------------------------ reproduciendo
    private fun backdrop(n: Np?): View {
        val f = FrameLayout(this)
        val b: Bitmap? = n?.art ?: Art.get(n?.track, max(200, p(440)))
        val pal = Art.palette((n?.title ?: "") + (n?.track?.album ?: ""))
        if (b != null) {
            val iv = ImageView(this).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP; scaleX = 1.4f; scaleY = 1.4f; alpha = 0.9f }
            if (Build.VERSION.SDK_INT >= 31) iv.setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
            f.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else f.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, pal) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        f.addView(View(this).apply { setBackgroundColor(0xAA000000.toInt()) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return f
    }

    private fun nowView(): View {
        val n = np()
        if (n == null) { ovl = null; return View(this) }
        val f = FrameLayout(this)
        f.addView(backdrop(n))
        f.addView(icon("down", 30) { ovl = null; render() }, FrameLayout.LayoutParams(p(40), p(40)).apply { leftMargin = p(14); topMargin = p(14) })
        val art: View = if (n.track != null) cover(n.track, 440, 28f) else ImageView(this).apply { setImageBitmap(n.art); scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(p(440), p(440)); clipToOutline = true; background = bg(0xFF222222.toInt(), 28) }
        f.addView(art, FrameLayout.LayoutParams(p(440), p(440), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = p(56); topMargin = p(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("REPRODUCIENDO · ${n.source.uppercase()}", 12f, 0xAAFFFFFF.toInt(), true))
        col.addView(tv(n.title, 36f, fg, true).apply { maxLines = 2; setPadding(0, p(8), 0, 0) })
        col.addView(tv(n.artist, 19f, 0xCCFFFFFF.toInt()).apply { maxLines = 1 })
        val bar = BarView(this, Color.WHITE).apply { frac = if (n.dur > 0) n.pos.toFloat() / n.dur else 0f
            onSeek = { fr, up -> if (up) { if (n.ctrl != null) n.ctrl.transportControls.seekTo((fr * n.dur).toLong()) else LocalPlayer.seek((fr * n.dur).toLong()) } } }
        nowBar = bar
        col.addView(bar, lp(MATCH_PARENT, p(26)).apply { topMargin = p(24) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nowTime = tv(fmt(n.pos), 12f, 0xAAFFFFFF.toInt()); nowLeft = tv("-" + fmt(max(0, n.dur - n.pos)), 12f, 0xAAFFFFFF.toInt())
        times.addView(nowTime, lp(0, WRAP_CONTENT, 1f)); times.addView(nowLeft)
        col.addView(times)
        val ctr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val shuf = icon("shuffle", 28, if (LocalPlayer.shuffle) acc else 0xCCFFFFFF.toInt()) { if (n.ctrl == null) { LocalPlayer.toggleShuffle(); render() } }
        val rep = icon("repeat", 28, if (LocalPlayer.repeat > 0) acc else 0xCCFFFFFF.toInt()) { if (n.ctrl == null) { LocalPlayer.cycleRepeat(); render() } }
        ctr.addView(shuf, lp(p(28), p(28)).apply { weight = 0f }); ctr.addView(View(this), lp(0, 1, 1f))
        ctr.addView(icon("prev", 40) { prev(np()) }); ctr.addView(View(this), lp(0, 1, 1f))
        val pl = FrameLayout(this).apply { background = bg(Color.WHITE, 42); isClickable = true; setOnClickListener { toggle(np()); lastKey = "" } }
        pl.addView(icon(if (n.playing) "pause" else "play", 40, 0xFF0A0F1C.toInt()), FrameLayout.LayoutParams(p(40), p(40), Gravity.CENTER)); nowPlay = null
        ctr.addView(pl, lp(p(84), p(84))); ctr.addView(View(this), lp(0, 1, 1f))
        ctr.addView(icon("next", 40) { next(np()) }); ctr.addView(View(this), lp(0, 1, 1f)); ctr.addView(rep, lp(p(28), p(28)))
        col.addView(ctr, lp(MATCH_PARENT, p(90)).apply { topMargin = p(14) })
        val vol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        vol.addView(icon("volume", 22, 0xCCFFFFFF.toInt()), lp(p(26), p(26)))
        vol.addView(BarView(this, Color.WHITE).apply { knob = false; frac = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            onSeek = { fr, _ -> am.setStreamVolume(AudioManager.STREAM_MUSIC, (fr * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0) } }, lp(0, p(24), 1f).apply { leftMargin = p(10) })
        col.addView(vol, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) })
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun act(k: String, t: String, on: Boolean = false, go: () -> Unit) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(0x22FFFFFF, 16, 0x33FFFFFF); isClickable = true; setOnClickListener { go() } }
            c.addView(icon(k, 24, if (on) acc else fg)); c.addView(tv(t, 12f, fg))
            acts.addView(c, lp(0, p(66), 1f).apply { rightMargin = p(10) })
        }
        act("lyrics", "Letra") { ovl = "lyrics"; render() }
        act("eq", "Ecualizador") { ovl = "eq"; render() }
        act("dj", "Mezcla DJ", AudioFx.xfadeSec > 0) { ovl = "eq"; render() }
        act("queue", "Cola") { ovl = "queue"; render() }
        val fav = n.track != null && isFav(n.track)
        act(if (fav) "heartfill" else "heart", "Favorita", fav) { n.track?.let { toggleFav(it); render() } }
        col.addView(acts, lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(16) })
        f.addView(col, FrameLayout.LayoutParams(p(420), WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = p(40) })
        return f
    }

    // ------------------------------------------------------------------ cola
    private fun queueView(): View {
        val f = FrameLayout(this).apply { setBackgroundColor(0xFF0B1020.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(18), p(24), p(20)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon("down", 30) { ovl = "now"; render() }, lp(p(40), p(40))); head.addView(tv("Cola", 28f, fg, true), lp(0, WRAP_CONTENT, 1f))
        head.addView(pill("Mezcla DJ y ecualizador", false) { ovl = "eq"; render() })
        col.addView(head)
        val cur = LocalPlayer.current
        val sv = ScrollView(this); val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; sv.addView(box)
        if (cur != null) { box.addView(tv("SUENA AHORA", 12f, fg2, true).apply { setPadding(0, p(12), 0, p(6)) }); box.addView(songRow(cur) { }) }
        box.addView(tv("A CONTINUACIÓN", 12f, fg2, true).apply { setPadding(0, p(14), 0, p(6)) })
        val base = LocalPlayer.queuePos()
        LocalPlayer.upcoming().take(80).forEachIndexed { i, t -> box.addView(songRow(t) { LocalPlayer.jumpTo(base + 1 + i); lastKey = "" }) }
        col.addView(sv, lp(MATCH_PARENT, 0, 1f))
        f.addView(col)
        return f
    }

    // ------------------------------------------------------------------ ecualizador y mezcla DJ
    private fun eqView(): View {
        val f = FrameLayout(this).apply { setBackgroundColor(0xFF0B1020.toInt()) }
        val sv = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p(24), p(18), p(24), p(30)) }
        sv.addView(col)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon("down", 30) { ovl = if (np() != null) "now" else null; render() }, lp(p(40), p(40))); head.addView(tv("Ecualizador y mezcla DJ", 26f, fg, true), lp(0, WRAP_CONTENT, 1f))
        col.addView(head)
        col.addView(tv("MEZCLA DJ · ENLAZA UNA CANCIÓN CON LA SIGUIENTE", 12f, fg2, true).apply { setPadding(0, p(14), 0, p(8)) })
        val xr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(glass, 16, line); setPadding(p(18), p(12), p(18), p(12)) }
        val xl = tv(if (AudioFx.xfadeSec == 0) "Apagada" else "${AudioFx.xfadeSec} s", 18f, acc, true)
        xr.addView(tv("Duración del enlace", 15f, fg), lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(16) })
        xr.addView(BarView(this, acc).apply { frac = AudioFx.xfadeSec / 12f; onSeek = { fr, up -> AudioFx.xfadeSec = (fr * 12).toInt(); xl.text = if (AudioFx.xfadeSec == 0) "Apagada" else "${AudioFx.xfadeSec} s" } }, lp(0, p(30), 1f))
        xr.addView(xl, lp(p(90), WRAP_CONTENT).apply { leftMargin = p(14) })
        col.addView(xr)
        col.addView(tv(if (AudioFx.xfadeManual) "También al tocar siguiente o anterior." else "Solo cuando la canción termina sola.", 12.5f, fg2).apply { setPadding(0, p(6), 0, 0); setOnClickListener { AudioFx.xfadeManual = !AudioFx.xfadeManual; render() } })
        col.addView(tv("ECUALIZADOR", 12f, fg2, true).apply { setPadding(0, p(18), 0, p(8)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        AudioFx.presets.keys.forEach { n -> chips.addView(pill(n, AudioFx.preset == n) { AudioFx.setPreset(n); render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = p(8) }) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        if (AudioFx.supported) {
            val bands = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p(16), 0, 0) }
            val lv = AudioFx.levels()
            for (i in 0 until AudioFx.bands) {
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val hz = AudioFx.freqs.getOrElse(i) { 0 }
                c.addView(tv(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", 12f, fg2))
                val vb = VerticalBand(this, acc, AudioFx.minMb, AudioFx.maxMb, lv.getOrElse(i) { 0 }) { v -> AudioFx.setBand(i, v) }
                c.addView(vb, lp(p(60), p(190)))
                bands.addView(c, lp(0, WRAP_CONTENT, 1f))
            }
            col.addView(bands)
        } else col.addView(tv("Este equipo no permite ecualizador.", 14f, fg2).apply { setPadding(0, p(14), 0, 0) })
        fun slider(label: String, get: () -> Int, set: (Int) -> Unit, max: Int, fmt: (Int) -> String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p(12), 0, 0) }
            val vl = tv(fmt(get()), 14f, acc, true)
            r.addView(tv(label, 15f, fg), lp(p(180), WRAP_CONTENT)); r.addView(BarView(this, acc).apply { frac = get().toFloat() / max; onSeek = { fr, _ -> set((fr * max).toInt()); vl.text = fmt(get()) } }, lp(0, p(28), 1f)); r.addView(vl, lp(p(80), WRAP_CONTENT).apply { leftMargin = p(12) })
            col.addView(r)
        }
        slider("Graves extra", { AudioFx.bassStrength }, { AudioFx.bassStrength = it }, 1000) { "${it / 10} %" }
        slider("Sonido espacial", { AudioFx.virtStrength }, { AudioFx.virtStrength = it }, 1000) { "${it / 10} %" }
        slider("Volumen extra", { AudioFx.gainMb }, { AudioFx.gainMb = it }, 1500) { "+${it / 100} dB" }
        col.addView(pill("Restablecer", false) { AudioFx.reset(); render() }, lp(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = p(16) })
        f.addView(sv)
        return f
    }

    class VerticalBand(c: Context, val accent: Int, val mn: Int, val mx: Int, var level: Int, val onChange: (Int) -> Unit) : View(c) {
        private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val x = w / 2; val pad = h * 0.06f
            pt.color = 0x33FFFFFF; pt.strokeWidth = w * 0.09f; pt.strokeCap = android.graphics.Paint.Cap.ROUND; cv.drawLine(x, pad, x, h - pad, pt)
            val fr = (level - mn).toFloat() / (mx - mn); val y = h - pad - fr * (h - 2 * pad)
            pt.color = accent; cv.drawLine(x, h / 2, x, y, pt); pt.style = android.graphics.Paint.Style.FILL; cv.drawCircle(x, y, w * 0.2f, pt)
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            val h = height.toFloat(); val pad = h * 0.06f
            val fr = 1f - ((e.y - pad) / (h - 2 * pad)).coerceIn(0f, 1f)
            level = (mn + fr * (mx - mn)).toInt(); onChange(level); invalidate()
            parent?.requestDisallowInterceptTouchEvent(true); return true
        }
    }

    // ------------------------------------------------------------------ órdenes de voz desde el launcher
    private var pendingPlay: String? = null
    private var pendingSilent = false

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("open")?.let { o ->
            i.removeExtra("open")
            when (o) { "now", "eq", "lyrics", "queue" -> ovl = o; "lib" -> { tab = Tab.LIB; ovl = null }; else -> { tab = Tab.HOME; ovl = null } }
            lastKey = ""; render()
        }
        val q = i.getStringExtra("play_q") ?: return
        pendingPlay = q; pendingSilent = i.getBooleanExtra("silent", true); i.removeExtra("play_q")
        runPending()
    }

    private fun runPending() {
        val q = pendingPlay ?: return
        if (!LocalPlayer.loaded) return
        pendingPlay = null
        val all = LocalPlayer.songs
        var list: List<LocalPlayer.Track> = emptyList()
        val nq = Db.norm(q)
        when {
            nq.isBlank() || nq == "musica" || nq == "algo" -> list = all.shuffled()
            nq == "favoritas" || nq == "mis favoritas" -> list = byKey(favs()).shuffled()
            else -> {
                val toks = nq.split(" ").filter { it.isNotEmpty() && it !in setOf("de", "la", "el", "los", "las", "del", "musica", "cancion", "canciones", "algo", "mi", "mis") }
                if (toks.isNotEmpty()) {
                    val byTitle = all.filter { t -> val n = Db.norm(t.title); toks.all { n.contains(it) } }
                    val byArtist = all.filter { t -> val n = Db.norm(t.artist); toks.all { n.contains(it) } }
                    val byAlbum = all.filter { t -> val n = Db.norm(t.album); toks.all { n.contains(it) } }
                    val any = all.filter { t -> val n = Db.norm(t.title + " " + t.artist + " " + t.album); toks.all { n.contains(it) } }
                    list = when { byTitle.isNotEmpty() -> byTitle.take(1) + (byTitle.drop(1)); byArtist.isNotEmpty() -> byArtist.shuffled(); byAlbum.isNotEmpty() -> byAlbum; else -> any }
                }
            }
        }
        if (list.isEmpty()) { toast("No encontré «$q» en tu música"); VozReply.say(this, "not_found"); return }
        LocalPlayer.playQueue(list, 0, false)
        if (pendingSilent) ui.postDelayed({ moveTaskToBack(true) }, 400) else { ovl = "now"; render() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Respuestas habladas opcionales (usa las frases del launcher si existen; si no, nada). */
object VozReply { fun say(c: Context, key: String) {} }
