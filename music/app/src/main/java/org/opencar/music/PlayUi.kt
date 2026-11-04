package org.opencar.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.media.MediaMetadataRetriever
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Carátulas: la del archivo, la descargada por OpenCar Music o un degradado con las iniciales. */
object Art {
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val none = HashSet<String>()
    private val retried = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())

    private val PAL = arrayOf(
        intArrayOf(0xFFFF6A3D.toInt(), 0xFF7A1F6D.toInt()), intArrayOf(0xFF34D399.toInt(), 0xFF0B3B5E.toInt()), intArrayOf(0xFFF9A8D4.toInt(), 0xFF6D28D9.toInt()),
        intArrayOf(0xFFFDE047.toInt(), 0xFFB45309.toInt()), intArrayOf(0xFF60A5FA.toInt(), 0xFF1E1B4B.toInt()), intArrayOf(0xFFFB7185.toInt(), 0xFF4C0519.toInt()),
        intArrayOf(0xFFA3E635.toInt(), 0xFF14532D.toInt()), intArrayOf(0xFFC4B5FD.toInt(), 0xFF312E81.toInt()))

    fun palette(key: String): IntArray = PAL[abs(key.hashCode()) % PAL.size]
    fun initials(t: String): String = t.split(" ", "-", "·").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "♪" }

    fun peek(key: String): Bitmap? = cache.get(key)

    /** Pide la carátula de una pista; avisa cuando está lista (si la hay). */
    fun load(c: Context, t: LocalPlayer.Track, size: Int, done: () -> Unit) {
        val k = t.id.toString() + "@" + size
        if (cache.get(k) != null || none.contains(k)) return
        Thread {
            var b: Bitmap? = null
            try { if (Build.VERSION.SDK_INT >= 29) b = c.contentResolver.loadThumbnail(t.uri, Size(size, size), null) } catch (e: Exception) { }
            if (b == null) try {
                val r = MediaMetadataRetriever(); r.setDataSource(c, t.uri)
                r.embeddedPicture?.let { bytes -> b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                r.release()
            } catch (e: Exception) { }
            if (b == null) try {
                val f: File? = Covers.lookup(c, if (t.artist == "Artista desconocido") "" else t.artist, if (t.album == "Álbum desconocido") "" else t.album, t.title)
                if (f != null && f.exists()) b = BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: Exception) { }
            val bb = b
            if (bb == null && LyricsNet.online(c)) {
                // sin carátula: se pide a internet una vez y se vuelve a mirar en unos segundos
                Covers.requestAsync(c, if (t.artist == "Artista desconocido") "" else t.artist, if (t.album == "Álbum desconocido") "" else t.album, t.title)
                val again = synchronized(retried) { retried.add(k) }
                if (again) ui.postDelayed({ none.remove(k); load(c, t, size, done) }, 9000)
            }
            ui.post { if (bb != null) { cache.put(k, bb); done() } else none.add(k) }
        }.start()
    }

    fun get(t: LocalPlayer.Track?, size: Int): Bitmap? = t?.let { cache.get(it.id.toString() + "@" + size) }
}

/** Cuadro de carátula con esquinas redondeadas. */
class CoverView(c: Context) : View(c) {
    var track: LocalPlayer.Track? = null
    var bmp: Bitmap? = null
    var label: String = ""
    var radiusDp = 14f
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun setTrack(t: LocalPlayer.Track?, text: String = "", size: Int = 400) {
        track = t; label = text.ifEmpty { t?.title ?: "" }; bmp = null
        if (t != null) {
            bmp = Art.get(t, size)
            if (bmp == null) Art.load(context, t, size) { if (track === t) { bmp = Art.get(t, size); invalidate() } }
        }
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = radiusDp * resources.displayMetrics.density
        rect.set(0f, 0f, w, h); path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        cv.save(); cv.clipPath(path)
        val b = bmp
        if (b != null) {
            val s = max(w / b.width, h / b.height)
            val dw = b.width * s; val dh = b.height * s
            cv.drawBitmap(b, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), pt)
        } else {
            val p = Art.palette(label + (track?.album ?: ""))
            pt.shader = LinearGradient(0f, 0f, w, h, p[0], p[1], Shader.TileMode.CLAMP); cv.drawRect(rect, pt)
            pt.shader = RadialGradient(w * 0.3f, h * 0.2f, w * 0.7f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP); cv.drawRect(rect, pt)
            pt.shader = null; pt.color = 0xCCFFFFFF.toInt(); pt.typeface = Typeface.DEFAULT_BOLD; pt.textSize = w * 0.28f
            cv.drawText(Art.initials(label), w * 0.1f, h * 0.9f, pt)
        }
        cv.restore()
    }
}

/** Iconos dibujados con trazos (sin imágenes). */
class IconView(c: Context, var kind: String, var tint: Int = Color.WHITE) : View(c) {
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) { draw(cv, kind, width.toFloat(), height.toFloat(), tint, pt) }

    companion object {
        fun draw(cv: Canvas, k: String, w: Float, h: Float, tint: Int, pt: Paint) {
            val s = min(w, h); val ox = (w - s) / 2; val oy = (h - s) / 2
            fun X(v: Float) = ox + v / 24f * s
            fun Y(v: Float) = oy + v / 24f * s
            pt.color = tint; pt.strokeWidth = 1.9f / 24f * s; pt.strokeCap = Paint.Cap.ROUND; pt.strokeJoin = Paint.Join.ROUND
            fun line(a: Float, b: Float, c: Float, d: Float) { pt.style = Paint.Style.STROKE; cv.drawLine(X(a), Y(b), X(c), Y(d), pt) }
            fun fillPath(vararg p: Float) { val pa = Path(); pa.moveTo(X(p[0]), Y(p[1])); var i = 2; while (i < p.size) { pa.lineTo(X(p[i]), Y(p[i + 1])); i += 2 }; pa.close(); pt.style = Paint.Style.FILL; cv.drawPath(pa, pt) }
            fun rr(a: Float, b: Float, c: Float, d: Float) { pt.style = Paint.Style.FILL; cv.drawRoundRect(RectF(X(a), Y(b), X(c), Y(d)), s / 24f * 1.5f, s / 24f * 1.5f, pt) }
            fun circ(x: Float, y: Float, r: Float, fill: Boolean) { pt.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE; cv.drawCircle(X(x), Y(y), r / 24f * s, pt) }
            when (k) {
                "play" -> fillPath(7f, 4f, 21f, 12f, 7f, 20f)
                "pause" -> { rr(6f, 4f, 10.5f, 20f); rr(13.5f, 4f, 18f, 20f) }
                "next" -> { fillPath(5f, 4f, 15f, 12f, 5f, 20f); rr(17f, 4f, 20f, 20f) }
                "prev" -> { fillPath(19f, 4f, 9f, 12f, 19f, 20f); rr(4f, 4f, 7f, 20f) }
                "shuffle" -> { line(3f, 7f, 7f, 7f); line(7f, 7f, 17f, 17f); line(17f, 17f, 21f, 17f); line(3f, 17f, 7f, 17f); line(7f, 17f, 10f, 14f); line(14f, 10f, 17f, 7f); line(17f, 7f, 21f, 7f); line(18f, 4f, 21f, 7f); line(21f, 7f, 18f, 10f); line(18f, 14f, 21f, 17f); line(21f, 17f, 18f, 20f) }
                "repeat" -> { line(4f, 11f, 4f, 9f); line(4f, 9f, 7f, 6f); line(7f, 6f, 19f, 6f); line(16f, 3f, 19f, 6f); line(16f, 9f, 19f, 6f); line(20f, 13f, 20f, 15f); line(20f, 15f, 17f, 18f); line(17f, 18f, 5f, 18f); line(8f, 15f, 5f, 18f); line(8f, 21f, 5f, 18f) }
                "lyrics" -> { line(4f, 6f, 20f, 6f); line(4f, 11f, 20f, 11f); line(4f, 16f, 14f, 16f) }
                "eq" -> { line(6f, 4f, 6f, 20f); line(12f, 4f, 12f, 20f); line(18f, 4f, 18f, 20f); circ(6f, 14f, 2.2f, true); circ(12f, 8f, 2.2f, true); circ(18f, 15f, 2.2f, true) }
                "queue" -> { line(4f, 6f, 16f, 6f); line(4f, 12f, 16f, 12f); line(4f, 18f, 11f, 18f); line(19f, 15f, 19f, 21f); line(16f, 18f, 22f, 18f) }
                "dj" -> { circ(8f, 12f, 5f, false); circ(16f, 12f, 5f, false) }
                "volume" -> { fillPath(4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f); line(16f, 9f, 17.5f, 12f); line(17.5f, 12f, 16f, 15f); line(18.8f, 6.5f, 20.5f, 12f); line(20.5f, 12f, 18.8f, 17.5f) }
                "home" -> { line(4f, 11f, 12f, 4f); line(12f, 4f, 20f, 11f); line(6f, 10f, 6f, 20f); line(18f, 10f, 18f, 20f); line(6f, 20f, 18f, 20f) }
                "library" -> { line(5f, 4f, 5f, 20f); line(10f, 4f, 10f, 20f); line(15f, 6f, 20f, 19f) }
                "search" -> { circ(10.5f, 10.5f, 6.5f, false); line(15.5f, 15.5f, 21f, 21f) }
                "heart" -> { val pa = Path(); pa.moveTo(X(12f), Y(20f)); pa.cubicTo(X(2f), Y(13f), X(4f), Y(4f), X(12f), Y(8.5f)); pa.cubicTo(X(20f), Y(4f), X(22f), Y(13f), X(12f), Y(20f)); pt.style = Paint.Style.STROKE; cv.drawPath(pa, pt) }
                "heartfill" -> { val pa = Path(); pa.moveTo(X(12f), Y(20f)); pa.cubicTo(X(2f), Y(13f), X(4f), Y(4f), X(12f), Y(8.5f)); pa.cubicTo(X(20f), Y(4f), X(22f), Y(13f), X(12f), Y(20f)); pt.style = Paint.Style.FILL; cv.drawPath(pa, pt) }
                "more" -> { circ(5f, 12f, 1.7f, true); circ(12f, 12f, 1.7f, true); circ(19f, 12f, 1.7f, true) }
                "settings" -> { circ(12f, 12f, 3.2f, false); for (a in 0 until 8) { val r = Math.toRadians(a * 45.0); line(12f + (Math.cos(r) * 6.3).toFloat(), 12f + (Math.sin(r) * 6.3).toFloat(), 12f + (Math.cos(r) * 8.5).toFloat(), 12f + (Math.sin(r) * 8.5).toFloat()) } }
                "back" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
                "down" -> { line(5f, 9f, 12f, 16f); line(12f, 16f, 19f, 9f) }
                "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                "note" -> { line(9f, 18f, 9f, 6f); line(9f, 6f, 19f, 4f); line(19f, 4f, 19f, 16f); circ(6.5f, 18f, 2.5f, false); circ(16.5f, 16f, 2.5f, false) }
            }
        }
    }
}

/** Barra de progreso o de volumen que se toca o se arrastra. */
class BarView(c: Context, var accent: Int = Color.WHITE) : View(c) {
    var frac = 0f
    var knob = true
    var onSeek: ((Float, Boolean) -> Unit)? = null     // (fracción, soltó)
    private var drag = false
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val t = h * 0.28f; val cy = h / 2
        pt.style = Paint.Style.FILL
        pt.color = 0x44FFFFFF; cv.drawRoundRect(RectF(0f, cy - t / 2, w, cy + t / 2), t, t, pt)
        pt.color = accent; cv.drawRoundRect(RectF(0f, cy - t / 2, w * frac.coerceIn(0f, 1f), cy + t / 2), t, t, pt)
        if (knob) cv.drawCircle(w * frac.coerceIn(0f, 1f), cy, t * 1.5f, pt)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { drag = true; parent?.requestDisallowInterceptTouchEvent(true); frac = f; invalidate(); onSeek?.invoke(f, false) }
            MotionEvent.ACTION_MOVE -> if (drag) { frac = f; invalidate(); onSeek?.invoke(f, false) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag) { drag = false; frac = f; invalidate(); onSeek?.invoke(f, true) }
        }
        return true
    }
    val dragging get() = drag
}
