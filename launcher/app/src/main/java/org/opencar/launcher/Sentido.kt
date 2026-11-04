package org.opencar.launcher

import android.graphics.Paint
import android.graphics.Path
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.reader.MapFile
import java.io.File
import kotlin.math.*

/**
 * Sentido de las calles con el mapa sin conexión: dibuja flechas en las calles de un solo sentido
 * (leyendo «oneway» del mismo archivo del mapa) y dice si la calle donde estás es de un sentido,
 * de doble sentido o si vas en contra.
 */
class Sentido(file: File) {
    class Seg(val pts: DoubleArray, val dir: Int, val name: String?, val hw: String)   // dir: 1 adelante, -1 al revés, 0 doble sentido
    enum class Estado { UN_SENTIDO, CONTRAMANO, DOBLE, NADA }
    class Res(val estado: Estado, val calle: String?)

    private val mf = MapFile(file)
    private val cache = object : LinkedHashMap<Long, List<Seg>>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<Seg>>?) = size > 80 }
    private val Z: Byte = 17
    @Volatile var enabled = true

    fun close() { try { mf.close() } catch (e: Throwable) { } }

    private fun tileSegs(tx: Int, ty: Int): List<Seg> {
        val key = tx.toLong() * 1_000_000L + ty
        synchronized(cache) { cache[key]?.let { return it } }
        val out = ArrayList<Seg>()
        try {
            val r = synchronized(mf) { mf.readMapData(Tile(tx, ty, Z, 256)) }
            if (r != null) for (w in r.ways) {
                var hw: String? = null; var ow: String? = null; var name: String? = null; var round = false
                for (t in w.tags) when (t.key) {
                    "highway" -> hw = t.value; "oneway" -> ow = t.value; "name" -> name = t.value
                    "junction" -> if (t.value == "roundabout") round = true
                }
                if (hw == null || hw!!.matches(Regex("footway|path|steps|cycleway|pedestrian|track|bridleway|corridor"))) continue
                val dir = when { ow == "-1" -> -1; ow == "yes" || ow == "1" || ow == "true" || round -> 1; else -> 0 }
                for (line in w.latLongs) {
                    if (line.size < 2) continue
                    val a = DoubleArray(line.size * 2); for (i in line.indices) { a[2 * i] = line[i].latitude; a[2 * i + 1] = line[i].longitude }
                    out.add(Seg(a, dir, name, hw!!))
                }
            }
        } catch (e: Throwable) { }
        synchronized(cache) { cache[key] = out }
        return out
    }

    private fun near(lat: Double, lon: Double): List<Seg> {
        val tx = MercatorProjection.longitudeToTileX(lon, Z); val ty = MercatorProjection.latitudeToTileY(lat, Z)
        val l = ArrayList<Seg>()
        for (dx in -1..1) for (dy in -1..1) l.addAll(tileSegs(tx + dx, ty + dy))
        return l
    }

    /** Dice en qué tipo de calle vas. heading en grados (0 = norte). */
    fun check(lat: Double, lon: Double, heading: Float, moving: Boolean): Res {
        if (!enabled) return Res(Estado.NADA, null)
        val cosL = cos(Math.toRadians(lat)); val m = 111_320.0
        var best = Double.MAX_VALUE; var bs: Seg? = null; var bb = 0.0
        for (s in near(lat, lon)) {
            for (i in 0 until s.pts.size / 2 - 1) {
                val ax = (s.pts[2 * i + 1] - lon) * cosL * m; val ay = (s.pts[2 * i] - lat) * m
                val bx = (s.pts[2 * i + 3] - lon) * cosL * m; val by = (s.pts[2 * i + 2] - lat) * m
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(ax + t * dx, ay + t * dy)
                if (d < best) { best = d; bs = s; bb = Math.toDegrees(atan2(dx, dy)) }
            }
        }
        val s = bs ?: return Res(Estado.NADA, null)
        if (best > 18) return Res(Estado.NADA, null)
        if (s.dir == 0) return Res(Estado.DOBLE, s.name)
        val seg = if (s.dir == 1) bb else bb + 180
        var diff = abs(((heading - seg + 540) % 360) - 180)
        return if (moving && diff > 100) Res(Estado.CONTRAMANO, s.name) else Res(Estado.UN_SENTIDO, s.name)
    }

    /** Capa con flechas sobre las calles de un solo sentido. */
    inner class FlechasLayer(private val sc: Float) : Layer() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F5FBF.toInt(); style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * sc }
        override fun draw(bb: BoundingBox, zoom: Byte, canvas: org.mapsforge.core.graphics.Canvas, topLeft: Point) {
            if (!enabled || zoom < 16) return
            val x0 = MercatorProjection.longitudeToTileX(bb.minLongitude, Z); val x1 = MercatorProjection.longitudeToTileX(bb.maxLongitude, Z)
            val y0 = MercatorProjection.latitudeToTileY(bb.maxLatitude, Z); val y1 = MercatorProjection.latitudeToTileY(bb.minLatitude, Z)
            if ((x1 - x0 + 1) * (y1 - y0 + 1) > 36) return
            val ms = MercatorProjection.getMapSize(zoom, displayModel.tileSize)
            val c = AndroidGraphicFactory.getCanvas(canvas)
            val step = 120f * sc; val sz = 5.5f * sc
            for (tx in x0..x1) for (ty in y0..y1) for (s in tileSegs(tx, ty)) {
                if (s.dir == 0) continue
                var acc = step / 2
                for (i in 0 until s.pts.size / 2 - 1) {
                    val ax = (MercatorProjection.longitudeToPixelX(s.pts[2 * i + 1], ms) - topLeft.x).toFloat()
                    val ay = (MercatorProjection.latitudeToPixelY(s.pts[2 * i], ms) - topLeft.y).toFloat()
                    val bx = (MercatorProjection.longitudeToPixelX(s.pts[2 * i + 3], ms) - topLeft.x).toFloat()
                    val by = (MercatorProjection.latitudeToPixelY(s.pts[2 * i + 2], ms) - topLeft.y).toFloat()
                    val len = hypot(bx - ax, by - ay); if (len < 1f) continue
                    var d = acc
                    while (d < len) {
                        val px = ax + (bx - ax) * d / len; val py = ay + (by - ay) * d / len
                        if (px > -20 && py > -20 && px < c.width + 20 && py < c.height + 20) arrow(c, px, py, (if (s.dir == 1) 1f else -1f) * (bx - ax) / len, (if (s.dir == 1) 1f else -1f) * (by - ay) / len, sz)
                        d += step
                    }
                    acc = d - len
                }
            }
        }
        private fun arrow(c: android.graphics.Canvas, x: Float, y: Float, ux: Float, uy: Float, s: Float) {
            val p = Path()
            p.moveTo(x + ux * s * 1.2f, y + uy * s * 1.2f)
            p.lineTo(x - ux * s * 0.8f - uy * s, y - uy * s * 0.8f + ux * s)
            p.lineTo(x - ux * s * 0.2f, y - uy * s * 0.2f)
            p.lineTo(x - ux * s * 0.8f + uy * s, y - uy * s * 0.8f - ux * s)
            p.close()
            c.drawPath(p, edge); c.drawPath(p, fill)
        }
    }
}
