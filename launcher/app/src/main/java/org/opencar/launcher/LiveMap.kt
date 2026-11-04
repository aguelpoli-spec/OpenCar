package org.opencar.launcher

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import kotlin.math.cos

/**
 * Mapa pequeño del inicio con las calles reales del mapa descargado (sin internet): sigue al auto,
 * muestra la flecha con el rumbo y el recorrido reciente. Usa su propia copia del archivo de mapa
 * para no cerrar el de la búsqueda al destruirse.
 */
class LiveMap(ctx: Context, private val sc: Float, private val acc: Int, private val night: Boolean, private val corner: Float) : FrameLayout(ctx) {
    private var mv: MapView? = null
    private var posLayer: PosLayer? = null
    private var trailLine: Polyline? = null
    private var lastLat = Double.NaN; private var lastLon = Double.NaN; private var lastB = -999f
    private var zoomSet = false
    var ok = false; private set

    init {
        clipChildren = true; clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, corner) } }
        try {
            ensureGfx(ctx)
            val v = MapView(ctx)
            v.isClickable = false; v.isFocusable = false
            v.mapScaleBar.isVisible = false
            v.setBuiltInZoomControls(false)
            val mf = MapFile(MapData.mapFile(ctx))
            val tc = AndroidUtil.createTileCache(ctx, "opencar_mini", v.model.displayModel.tileSize, 1f, v.model.frameBufferModel.overdrawFactor)
            val tl = AndroidUtil.createTileRendererLayer(tc, v.model.mapViewPosition, mf, theme(ctx), false, true, false)
            tl.textScale = 1.1f
            v.layerManager.layers.add(tl)
            val gf = AndroidGraphicFactory.INSTANCE
            val pt = gf.createPaint().apply { setColor(if (night) 0xFF00B8D4.toInt() else 0xFF1A73E8.toInt()); strokeWidth = 6f * sc; setStyle(Style.STROKE); setStrokeCap(Cap.ROUND) }
            trailLine = Polyline(pt, gf).also { v.layerManager.layers.add(it) }
            posLayer = PosLayer().also { v.layerManager.layers.add(it) }
            addView(v, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            v.model.mapViewPosition.setZoomLevelMin(10); v.model.mapViewPosition.setZoomLevelMax(18)
            val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val cen = MapData.center(ctx)
            val la = p.getString("map_lat", null)?.toDoubleOrNull() ?: cen.first
            val lo = p.getString("map_lon", null)?.toDoubleOrNull() ?: cen.second
            v.model.mapViewPosition.mapPosition = org.mapsforge.core.model.MapPosition(LatLong(la, lo), 15)
            applyNight(v, night)
            mv = v; ok = true
        } catch (e: Throwable) { ok = false }
    }

    /** Mueve el mapa al auto (solo si se movió) y dibuja el recorrido. */
    fun update(lat: Double, lon: Double, bearing: Float, trail: List<DoubleArray>) {
        val v = mv ?: return
        try {
            if (!lat.isNaN() && !lon.isNaN()) {
                val moved = lastLat.isNaN() || Math.abs(lat - lastLat) > 0.00004 || Math.abs(lon - lastLon) > 0.00004 || Math.abs(bearing - lastB) > 8f
                if (moved) {
                    lastLat = lat; lastLon = lon; lastB = bearing
                    posLayer?.set(lat, lon, bearing)
                    v.model.mapViewPosition.setCenter(LatLong(lat, lon))
                    if (!zoomSet) { v.model.mapViewPosition.setZoomLevel(16.toByte()); zoomSet = true }
                }
            }
            trailLine?.setPoints(trail.map { LatLong(it[0], it[1]) })
            v.layerManager.redrawLayers()
        } catch (e: Throwable) { }
    }

    fun destroy() { try { mv?.destroyAll() } catch (e: Throwable) { }; mv = null }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow() }

    private inner class PosLayer : Layer() {
        private var lat = Double.NaN; private var lon = Double.NaN; private var bearing = 0f
        fun set(a: Double, o: Double, b: Float) { lat = a; lon = o; bearing = if (b >= 0f) b else bearing; requestRedraw() }
        override fun draw(bb: BoundingBox, z: Byte, canvas: org.mapsforge.core.graphics.Canvas, tl: Point) {
            if (lat.isNaN()) return
            val ms = MercatorProjection.getMapSize(z, displayModel.tileSize)
            val x = (MercatorProjection.longitudeToPixelX(lon, ms) - tl.x).toFloat()
            val y = (MercatorProjection.latitudeToPixelY(lat, ms) - tl.y).toFloat()
            val c = AndroidGraphicFactory.getCanvas(canvas)
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            pt.color = (acc and 0x00FFFFFF) or 0x30000000; c.drawCircle(x, y, 26f * sc, pt)
            c.save(); c.rotate(bearing, x, y)
            val s = 13f * sc
            val path = android.graphics.Path(); path.moveTo(x, y - s * 1.3f); path.lineTo(x + s, y + s); path.lineTo(x, y + s * 0.45f); path.lineTo(x - s, y + s); path.close()
            pt.color = Color.WHITE; pt.style = Paint.Style.FILL_AND_STROKE; pt.strokeWidth = 4f * sc; c.drawPath(path, pt)
            pt.color = acc; pt.style = Paint.Style.FILL; c.drawPath(path, pt)
            c.restore()
        }
    }

    companion object {
        /** Estilo del mapa: calles, avenidas con nombre, edificios, agua y verde. Sin símbolos (más liviano). Si falla, el de fábrica. */
        fun iconsOn(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("map_icons", true)

        /**
         * Mapsforge trae un INSTANCE de fábrica SIN contexto (por eso «== null» nunca era cierto y los iconos
         * assets: fallaban con el mapa en blanco). Hay que crearlo siempre una vez con el contexto de la app.
         */
        @Volatile private var gfxCtx: Context? = null
        fun ensureGfx(ctx: Context) {
            val app = ctx.applicationContext
            synchronized(LiveMap::class.java) {
                if (gfxCtx !== app) { AndroidGraphicFactory.createInstance(app as Application); gfxCtx = app }
            }
        }

        @Volatile private var iconsOk: Boolean? = null
        /** Prueba una vez por arranque que el estilo con iconos se lee bien; si no, se usa el básico (nunca mapa en blanco). */
        fun iconThemeWorks(ctx: Context): Boolean {
            iconsOk?.let { return it }
            val ok = try {
                ensureGfx(ctx)
                val f = org.mapsforge.map.rendertheme.rule.RenderThemeFuture(AndroidGraphicFactory.INSTANCE,
                    org.mapsforge.map.rendertheme.StreamRenderTheme("", ctx.assets.open("map/calles.xml")), org.mapsforge.map.model.DisplayModel())
                Thread(f).start()
                f.get(12, java.util.concurrent.TimeUnit.SECONDS) != null
            } catch (e: Throwable) { false }
            iconsOk = ok
            return ok
        }

        fun theme(ctx: Context): org.mapsforge.map.rendertheme.XmlRenderTheme =
            try {
                ensureGfx(ctx)
                if (iconsOn(ctx) && iconThemeWorks(ctx)) org.mapsforge.map.rendertheme.StreamRenderTheme("", ctx.assets.open("map/calles.xml"))
                else org.mapsforge.map.rendertheme.StreamRenderTheme("/", ctx.assets.open("map/calles_basico.xml"))
            } catch (e: Throwable) { try { org.mapsforge.map.rendertheme.StreamRenderTheme("/", ctx.assets.open("map/calles_basico.xml")) } catch (e2: Throwable) { InternalRenderTheme.DEFAULT } }

        /** Mismo modo noche del mapa completo: colores invertidos y tono girado. */
        fun applyNight(mv: MapView, night: Boolean) {
            if (!night) { mv.setLayerType(View.LAYER_TYPE_NONE, null); return }
            val inv = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
            val a = Math.toRadians(190.0); val c = cos(a).toFloat(); val s = Math.sin(a).toFloat()
            val hue = ColorMatrix(floatArrayOf(
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            inv.postConcat(hue)
            inv.postConcat(ColorMatrix().apply { setScale(0.92f, 0.92f, 0.95f, 1f) })
            mv.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(inv) })
        }
    }
}
