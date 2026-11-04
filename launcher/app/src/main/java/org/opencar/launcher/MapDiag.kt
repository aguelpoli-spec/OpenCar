package org.opencar.launcher

import android.content.Context
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.labels.TileBasedLabelStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.util.concurrent.TimeUnit

/** Revisa el mapa descargado paso a paso y dice dónde falla (archivo, datos o dibujo). */
object MapDiag {
    fun run(ctx: Context, lat: Double, lon: Double): String {
        val sb = StringBuilder()
        val f = MapData.mapFile(ctx)
        sb.appendLine("1) Archivo del mapa")
        sb.appendLine("   ${if (f.exists()) "existe" else "NO existe"} · ${f.length() / 1_048_576} MB")
        val remote = MapData.remoteMapSize(ctx)
        if (remote > 0) sb.appendLine("   en el servidor: ${remote / 1_048_576} MB · " + (if (remote == f.length()) "completo ✓" else "DIFERENTE: descarga incompleta o mapa actualizado")) else sb.appendLine("   sin internet: no se pudo comparar con el servidor")
        if (!f.exists()) return sb.toString()
        val cen = MapData.center(ctx); val la = if (lat.isNaN()) cen.first else lat; val lo = if (lon.isNaN()) cen.second else lon
        val mf = try { MapFile(f) } catch (e: Throwable) { sb.appendLine("\n2) Lectura: ERROR al abrir (${e.message})"); return sb.toString() }
        try {
            sb.appendLine("\n2) Lectura de datos")
            val bb = mf.boundingBox()
            sb.appendLine("   área: %.2f a %.2f lat, %.2f a %.2f lon".format(bb.minLatitude, bb.maxLatitude, bb.minLongitude, bb.maxLongitude))
            sb.appendLine("   tu posición: %.4f, %.4f %s".format(la, lo, if (bb.contains(org.mapsforge.core.model.LatLong(la, lo))) "(dentro ✓)" else "(FUERA del mapa)"))
            val z: Byte = 16
            val tx = MercatorProjection.longitudeToTileX(lo, z); val ty = MercatorProjection.latitudeToTileY(la, z)
            val r = mf.readMapData(Tile(tx, ty, z, 256))
            if (r == null) sb.appendLine("   pieza z16: sin datos") else {
                val roads = r.ways.count { w -> w.tags.any { it.key == "highway" } }
                val named = r.ways.count { w -> w.tags.any { it.key == "highway" } && w.tags.any { it.key == "name" } }
                sb.appendLine("   pieza z16: ${r.ways.size} trazos · $roads calles ($named con nombre) · ${r.pointOfInterests.size} lugares")
            }
        } catch (e: Throwable) { sb.appendLine("   ERROR leyendo datos: ${e.javaClass.simpleName} ${e.message}") }
        sb.appendLine("\n3) Dibujo de una pieza (en esta pantalla)")
        sb.appendLine("   estilo propio: " + draw(ctx, mf, LiveMap.theme(ctx), la, lo))
        sb.appendLine("   estilo de fábrica: " + draw(ctx, mf, InternalRenderTheme.DEFAULT, la, lo))
        try { mf.close() } catch (e: Throwable) { }
        sb.appendLine("\nSi en 2) hay calles pero en 3) dice «en blanco», el problema es el dibujo; si 1) dice DIFERENTE, hay que descargar de nuevo.")
        return sb.toString()
    }

    private fun draw(ctx: Context, mf: MapFile, theme: XmlRenderTheme, lat: Double, lon: Double): String = try {
        val gf = AndroidGraphicFactory.INSTANCE
        val dm = DisplayModel()
        val rtf = RenderThemeFuture(gf, theme, dm)
        Thread(rtf).start()
        rtf.get(30, TimeUnit.SECONDS)
        val dr = DatabaseRenderer(mf, gf, InMemoryTileCache(4), TileBasedLabelStore(100), true, false, null)
        val z: Byte = 16
        val t = Tile(MercatorProjection.longitudeToTileX(lon, z), MercatorProjection.latitudeToTileY(lat, z), z, dm.tileSize)
        val bmp = AndroidGraphicFactory.getBitmap(dr.executeJob(RendererJob(t, mf, rtf, dm, 1f, false, false)))
        val seen = HashSet<Int>()
        var x = 0
        while (x < bmp.width) { var y = 0; while (y < bmp.height) { seen.add(bmp.getPixel(x, y)); y += 5 }; x += 5 }
        if (seen.size > 12) "dibuja ✓ (${seen.size} colores)" else "en BLANCO (${seen.size} colores)"
    } catch (e: Throwable) { "ERROR: ${e.javaClass.simpleName} ${(e.cause?.message ?: e.message)?.take(120)}" }
}
