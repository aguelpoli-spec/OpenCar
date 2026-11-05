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
            val step = 100f * sc; val sz = 7f * sc
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

    // ------------------------------------------------------------------ nombres derechos
    private val lcache = object : LinkedHashMap<Long, List<NomItem>>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<NomItem>>?) = size > 120 }

    /** Nombres de una pieza del mapa (calles, lugares, edificios/parques y comercios). zl = zoom de la pieza. */
    private fun tileNames(tx: Int, ty: Int, zl: Byte): List<NomItem> {
        val key = (zl.toLong() * 10_000_000L + tx) * 10_000_000L + ty
        synchronized(lcache) { lcache[key]?.let { return it } }
        val out = ArrayList<NomItem>()
        try {
            val r = synchronized(mf) { mf.readMapData(Tile(tx, ty, zl, 256)) }
            if (r != null) {
                for (w in r.ways) {
                    var name: String? = null; var hw: String? = null; var kind = ""
                    for (t in w.tags) when (t.key) {
                        "name" -> name = t.value; "highway" -> hw = t.value
                        "leisure", "landuse", "natural", "amenity", "building", "tourism", "historic", "place", "aeroway" -> if (kind.isEmpty() || t.key == "leisure" || t.key == "natural") kind = t.key
                    }
                    if (name == null) continue
                    if (hw != null) {
                        val rk = when { hw.startsWith("motorway") || hw.startsWith("trunk") || hw.startsWith("primary") -> 0; hw.startsWith("secondary") -> 1; hw.startsWith("tertiary") -> 2; hw == "residential" || hw == "unclassified" || hw == "living_street" -> 3; else -> 9 }
                        if (rk > 3) continue
                        for (line in w.latLongs) {
                            if (line.size < 2) continue
                            val a = DoubleArray(line.size * 2); for (k in line.indices) { a[2 * k] = line[k].latitude; a[2 * k + 1] = line[k].longitude }
                            out.add(NomItem(name, 0, rk, a, 0.0, 0.0, ""))
                        }
                    } else if (kind.isNotEmpty()) {
                        val line = w.latLongs.firstOrNull() ?: continue
                        val lp = w.labelPosition
                        val la = lp?.latitude ?: line.map { it.latitude }.average(); val lo = lp?.longitude ?: line.map { it.longitude }.average()
                        out.add(NomItem(name, 1, 4, null, la, lo, kind))
                    }
                }
                for (p in r.pointOfInterests) {
                    var name: String? = null; var kind = ""; var place = ""
                    for (t in p.tags) when (t.key) {
                        "name" -> name = t.value; "place" -> place = t.value
                        "amenity", "shop", "tourism", "historic", "leisure", "office", "healthcare", "craft", "sport" -> if (kind.isEmpty()) kind = t.key
                    }
                    if (name == null) continue
                    if (place.isNotEmpty()) out.add(NomItem(name, 2, if (place == "city" || place == "town") -2 else -1, null, p.position.latitude, p.position.longitude, "place"))
                    else if (kind.isNotEmpty()) out.add(NomItem(name, 3, 5, null, p.position.latitude, p.position.longitude, kind))
                }
            }
        } catch (e: Throwable) { }
        synchronized(lcache) { lcache[key] = out }
        return out
    }

    /**
     * Nombres SIEMPRE derechos aunque el mapa esté girado (rumbo arriba). Se dibujan aquí y no en las piezas del mapa
     * (el estilo se usa sin rótulos): cada texto se orienta según el giro actual de la vista, así nunca sale de cabeza.
     */
    inner class NombresLayer(private val sc: Float, private val night: Boolean, private val rot: () -> Float) : Layer() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textAlign = Paint.Align.CENTER }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.2f * sc; strokeJoin = Paint.Join.ROUND; textAlign = Paint.Align.CENTER; color = if (night) 0xFF0E1A22.toInt() else 0xFFFFFFFF.toInt() }
        private fun color(kind: String, k: Int): Int = if (night) when {
            k == 0 -> 0xFFE6EEF2.toInt(); kind == "leisure" || kind == "natural" -> 0xFF9CCC9C.toInt(); else -> 0xFFCFD8DC.toInt()
        } else when {
            k == 0 -> 0xFF202020.toInt(); k == 2 -> 0xFF303030.toInt()
            kind == "leisure" -> 0xFF2E7D32.toInt(); kind == "natural" -> 0xFF2A6F9A.toInt(); kind == "shop" -> 0xFF7A2F5A.toInt()
            kind == "tourism" || kind == "historic" -> 0xFF6A4A1F.toInt(); kind == "building" || kind == "landuse" -> 0xFF5A4A3A.toInt(); else -> 0xFF33485A.toInt()
        }

        override fun draw(bb: BoundingBox, zoom: Byte, canvas: org.mapsforge.core.graphics.Canvas, topLeft: Point) {
            if (!enabled || zoom < 10) return
            val zl: Byte = if (zoom > 17) 17 else zoom
            val x0 = MercatorProjection.longitudeToTileX(bb.minLongitude, zl); val x1 = MercatorProjection.longitudeToTileX(bb.maxLongitude, zl)
            val y0 = MercatorProjection.latitudeToTileY(bb.maxLatitude, zl); val y1 = MercatorProjection.latitudeToTileY(bb.minLatitude, zl)
            if ((x1 - x0 + 1) * (y1 - y0 + 1) > 36) return
            val ms = MercatorProjection.getMapSize(zoom, displayModel.tileSize)
            val c = AndroidGraphicFactory.getCanvas(canvas)
            val rdeg = rot(); val rr = Math.toRadians(rdeg.toDouble()); val cr = cos(rr).toFloat(); val sr = sin(rr).toFloat()
            val ccx = c.width / 2f; val ccy = c.height / 2f
            val cand = ArrayList<NomCnd>(); val seen = HashSet<String>()
            for (tx in x0..x1) for (ty in y0..y1) for (it in tileNames(tx, ty, zl)) {
                if (it.type == 0) {
                    if (zoom < 14 || (zoom < 16 && it.rank > 1)) continue
                    val m = it.pts!!.size / 2
                    val xs = FloatArray(m); val ys = FloatArray(m)
                    for (i in 0 until m) { xs[i] = (MercatorProjection.longitudeToPixelX(it.pts[2 * i + 1], ms) - topLeft.x).toFloat(); ys[i] = (MercatorProjection.latitudeToPixelY(it.pts[2 * i], ms) - topLeft.y).toFloat() }
                    var best = 0f; var bs = 0; var be = 1; var st = 0; var run = 0f
                    for (i in 1 until m) {
                        run += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
                        val turn = if (i < m - 1) abs(((Math.toDegrees(atan2((ys[i + 1] - ys[i]).toDouble(), (xs[i + 1] - xs[i]).toDouble())) - Math.toDegrees(atan2((ys[i] - ys[i - 1]).toDouble(), (xs[i] - xs[i - 1]).toDouble())) + 540) % 360) - 180) else 999.0
                        if (turn > 25 || i == m - 1) { if (run > best) { best = run; bs = st; be = i }; st = i; run = 0f }
                    }
                    val ang = Math.toDegrees(atan2((ys[be] - ys[bs]).toDouble(), (xs[be] - xs[bs]).toDouble())).toFloat()
                    cand.add(NomCnd(it, (xs[bs] + xs[be]) / 2, (ys[bs] + ys[be]) / 2, ang, best, it.rank))
                } else {
                    if ((it.type == 1 && zoom < 15) || (it.type == 3 && zoom < 16) || (it.type == 2 && it.rank == -1 && zoom < 12)) continue
                    val key = it.name + "|" + it.type; if (!seen.add(key + (it.lat * 1e4).toInt() + "," + (it.lon * 1e4).toInt())) continue
                    val x = (MercatorProjection.longitudeToPixelX(it.lon, ms) - topLeft.x).toFloat(); val y = (MercatorProjection.latitudeToPixelY(it.lat, ms) - topLeft.y).toFloat()
                    cand.add(NomCnd(it, x, y, 0f, 0f, if (it.type == 2) it.rank else it.rank))
                }
            }
            cand.sortWith(compareBy<NomCnd> { it.prio }.thenByDescending { it.len })
            val placed = ArrayList<Pl>()
            for (cd in cand) {
                val it = cd.it
                val bold = it.type == 0 && it.rank <= 1 || it.type == 2
                val ts = when (it.type) { 0 -> if (it.rank <= 1) 15.5f else 14f; 2 -> if (it.rank <= -2) 17f else 14.5f; 1 -> 12f; else -> 12f } * sc
                fill.textSize = ts; fill.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                val w = fill.measureText(it.name)
                if (it.type == 0 && cd.len < w * 0.9f) continue
                if (cd.x < -80 || cd.y < -80 || cd.x > c.width + 80 || cd.y > c.height + 80) continue
                var mx = cd.x; var my = cd.y
                // posición en pantalla (la vista gira alrededor de su centro)
                var th = 0f
                if (it.type == 0) {
                    val s0 = (((cd.ang + rdeg) % 360f) + 540f) % 360f - 180f
                    th = if (abs(s0) > 90f) (((s0 + 180f) % 360f) + 540f) % 360f - 180f else s0
                } else if (it.type == 3) { val off = 15f * sc; mx += off * sr; my += off * cr }   // el nombre va debajo del símbolo, mirando a la pantalla
                val dx = mx - ccx; val dy = my - ccy
                val sx = ccx + dx * cr - dy * sr; val sy = ccy + dx * sr + dy * cr
                val thr = Math.toRadians(th.toDouble()); val ca = abs(cos(thr)).toFloat(); val sa = abs(sin(thr)).toFloat()
                val bw = w * ca + ts * sa + 4f * sc; val bh = w * sa + ts * ca + 2f * sc
                var ok = true
                for (p in placed) {
                    if (abs(p.sx - sx) < (p.bw + bw) / 2f && abs(p.sy - sy) < (p.bh + bh) / 2f) { ok = false; break }
                    if (p.text == it.name && hypot(p.sx - sx, p.sy - sy) < 320f * sc) { ok = false; break }
                }
                if (!ok) continue
                placed.add(Pl(it.name, sx, sy, mx, my, th, ts, color(it.kind, if (it.type == 0) 0 else if (it.type == 2) 2 else 1), bold, bw, bh))
                if (placed.size >= 90) break
            }
            for (p in placed) {
                fill.textSize = p.ts; halo.textSize = p.ts; fill.color = p.col
                val tf = if (p.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                fill.typeface = tf; halo.typeface = tf
                c.save(); c.translate(p.mx, p.my); c.rotate(p.th - rdeg)
                c.drawText(p.text, 0f, p.ts * 0.35f, halo); c.drawText(p.text, 0f, p.ts * 0.35f, fill)
                c.restore()
            }
        }
    }
}

private class NomItem(val name: String, val type: Int, val rank: Int, val pts: DoubleArray?, val lat: Double, val lon: Double, val kind: String)
private class NomCnd(val it: NomItem, val x: Float, val y: Float, val ang: Float, val len: Float, val prio: Int)
private class Pl(val text: String, val sx: Float, val sy: Float, val mx: Float, val my: Float, val th: Float, val ts: Float, val col: Int, val bold: Boolean, val bw: Float, val bh: Float)
