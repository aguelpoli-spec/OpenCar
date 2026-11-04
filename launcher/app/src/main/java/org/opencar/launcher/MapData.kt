package org.opencar.launcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Datos del mapa sin internet: mapa vectorial de la región elegida (Mapsforge) + datos de rutas (BRouter). Se bajan con internet y se guardan. */
object MapData {
    const val BROUTER = "https://brouter.de/brouter"
    const val UA = "OpenCarLauncher/1.0 (https://github.com/opencar; open source car launcher)"

    @Volatile var running = false
    @Volatile var msg = ""
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var error: String? = null
    @Volatile var cancel = false

    fun root(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "maps").also { it.mkdirs() }
    fun mapFile(c: Context) = File(root(c), "region.map")
    fun segDir(c: Context) = File(root(c), "segments4").also { it.mkdirs() }
    fun profDir(c: Context) = File(root(c), "profiles2").also { it.mkdirs() }
    fun profile(c: Context) = File(profDir(c), "car-fast.brf")

    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Región elegida por el usuario (ruta en el servidor de Mapsforge, p. ej. south-america/bolivia.map). */
    fun regionPath(c: Context): String? = prefs(c).getString("map_path", null)
    fun regionName(c: Context): String = prefs(c).getString("map_name", null) ?: "mapa"
    fun mapUrl(c: Context): String? = regionPath(c)?.let { MapRegions.BASE + it }

    /** Cambia de región: si es otra, se descarta el mapa anterior (los segmentos de rutas se reaprovechan). */
    fun setRegion(c: Context, path: String, name: String) {
        if (regionPath(c) != path) {
            mapFile(c).delete(); File(root(c), "region.map.part").delete()
            prefs(c).edit().remove("route_segments").remove("map_lat").remove("map_lon").apply()
            centerCache = null
        }
        prefs(c).edit().putString("map_path", path).putString("map_name", name).apply()
    }

    fun mapReady(c: Context) = mapFile(c).let { it.exists() && it.length() > 200_000L }
    private fun segList(c: Context): List<String> = (prefs(c).getString("route_segments", "") ?: "").split(",").filter { it.isNotBlank() }
    fun routeReady(c: Context) = segList(c).let { l -> l.isNotEmpty() && l.all { File(segDir(c), "$it.rd5").exists() } } && profile(c).exists() && File(profDir(c), "lookups.dat").exists()
    fun ready(c: Context) = mapReady(c) && routeReady(c)

    @Volatile private var centerCache: Pair<Double, Double>? = null
    /** Centro del área descargada (para abrir el mapa antes de tener GPS). */
    fun center(c: Context): Pair<Double, Double> {
        centerCache?.let { return it }
        return try {
            val m = org.mapsforge.map.reader.MapFile(mapFile(c)); val b = m.boundingBox(); m.close()
            Pair((b.minLatitude + b.maxLatitude) / 2, (b.minLongitude + b.maxLongitude) / 2).also { centerCache = it }
        } catch (e: Throwable) { Pair(0.0, 0.0) }
    }

    /** Nombre de los segmentos de rutas de 5°×5° (BRouter) que cubren el área del mapa. */
    fun segmentsFor(c: Context): List<String> {
        val m = org.mapsforge.map.reader.MapFile(mapFile(c)); val b = m.boundingBox(); m.close()
        fun name(lon: Int, lat: Int) = (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat")
        val out = ArrayList<String>()
        var lo = Math.floor(b.minLongitude / 5).toInt() * 5
        while (lo <= b.maxLongitude) {
            var la = Math.floor(b.minLatitude / 5).toInt() * 5
            while (la <= b.maxLatitude) { out.add(name(lo, la)); la += 5 }
            lo += 5
        }
        return out
    }

    fun stamp(c: Context): String {
        val f = mapFile(c)
        return if (f.exists()) java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.util.Date(f.lastModified())) else "sin descargar"
    }

    /** Copia los perfiles de ruta que vienen en la app (por si no hay internet para actualizarlos). */
    fun ensureProfiles(c: Context) {
        for (n in listOf("lookups.dat", "car-fast.brf")) {
            val dst = File(profDir(c), n)
            if (dst.exists() && dst.length() > 0) continue
            try { c.assets.open("brouter/$n").use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } } } catch (e: Exception) { }
        }
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        var u = url
        for (i in 0 until 5) {
            val cn = URL(u).openConnection() as HttpURLConnection
            cn.connectTimeout = 20000; cn.readTimeout = 30000
            cn.setRequestProperty("User-Agent", UA)
            if (from > 0) cn.setRequestProperty("Range", "bytes=$from-")
            cn.instanceFollowRedirects = false
            val code = cn.responseCode
            if (code in 300..399) { u = cn.getHeaderField("Location") ?: u; cn.disconnect(); continue }
            return cn
        }
        throw java.io.IOException("demasiadas redirecciones")
    }

    fun remoteMapSize(c: Context): Long = mapUrl(c)?.let { size(it) } ?: -1L

    private fun size(url: String): Long = try {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.requestMethod = "HEAD"; cn.connectTimeout = 15000; cn.readTimeout = 15000
        cn.setRequestProperty("User-Agent", UA)
        val l = if (cn.responseCode == 200) cn.contentLengthLong else -1L
        cn.disconnect(); l
    } catch (e: Exception) { -1L }

    private fun fetch(url: String, dst: File, label: String): Boolean {
        val part = File(dst.parentFile, dst.name + ".part")
        for (attempt in 1..4) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val cn = open(url, have)
                val code = cn.responseCode
                if (code == 404) { cn.disconnect(); return false }
                if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
                val resume = code == 206
                if (!resume && part.exists()) part.delete()
                val raf = RandomAccessFile(part, "rw")
                if (resume) raf.seek(have)
                cn.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (ins.read(buf).also { n = it } > 0) {
                        if (cancel) { raf.close(); return false }
                        raf.write(buf, 0, n)
                        done += n
                        msg = label
                    }
                }
                raf.close()
                if (dst.exists()) dst.delete()
                if (!part.renameTo(dst)) throw java.io.IOException("no pude guardar ${dst.name}")
                return true
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                try { Thread.sleep(1500L * attempt) } catch (x: InterruptedException) { }
            }
        }
        return false
    }

    private fun runItems(c: Context, items: List<Triple<String, File, String>>, sizes: List<Long>, force: Boolean): Boolean {
        var okAll = true
        for ((i, it) in items.withIndex()) {
            if (cancel) break
            val isProfile = it.first.endsWith(".brf") || it.first.endsWith(".dat")
            if (sizes[i] < 0 && !isProfile && !it.first.endsWith(".map")) continue   // segmento que no existe en el servidor (mar o sin datos)
            val old = File(it.second.parentFile, it.second.name + ".old")
            val had = it.second.exists()
            if (force && had) it.second.renameTo(old)
            val ok = fetch(it.first, it.second, it.third)
            if (!ok) {
                if (had && old.exists()) old.renameTo(it.second)
                if (!isProfile) okAll = false
            } else old.delete()
        }
        return okAll
    }

    /** Descarga todo (reanuda si se cortó). Se llama desde el servicio, nunca desde la pantalla. */
    fun downloadAll(c: Context, force: Boolean) {
        if (running) return
        running = true; cancel = false; error = null; done = 0; total = 0
        try {
            val url = mapUrl(c)
            if (url == null) { msg = "Primero elige la región del mapa"; return }
            ensureProfiles(c)
            msg = "Buscando datos…"
            // 1) mapa de la región + perfiles de ruta
            val items = ArrayList<Triple<String, File, String>>()
            if (force || !mapReady(c)) items.add(Triple(url, mapFile(c), "Mapa: ${regionName(c)}"))
            for (n in listOf("lookups.dat", "car-fast.brf")) items.add(Triple("$BROUTER/profiles2/$n", File(profDir(c), n), "Perfil de rutas"))
            val sizes = items.map { size(it.first) }
            total = sizes.filter { it > 0 }.sum()
            var okAll = runItems(c, items, sizes, force)
            if (cancel) { msg = "Descarga cancelada"; return }
            if (!mapReady(c)) { msg = "Faltan datos: ${error ?: "no se pudo bajar el mapa, reintenta con Wi-Fi"}"; return }
            // 2) segmentos de rutas que cubren el área del mapa elegido
            msg = "Calculando las rutas necesarias…"
            val segs = segmentsFor(c)
            val items2 = segs.filter { force || !File(segDir(c), "$it.rd5").exists() }.map { Triple("$BROUTER/segments4/$it.rd5", File(segDir(c), "$it.rd5"), "Rutas $it (${segs.indexOf(it) + 1} de ${segs.size})") }
            val sizes2 = items2.map { size(it.first) }
            total += sizes2.filter { it > 0 }.sum()
            okAll = runItems(c, items2, sizes2, force) && okAll
            val have = segs.filter { File(segDir(c), "$it.rd5").exists() }
            prefs(c).edit().putString("route_segments", have.joinToString(",")).apply()
            msg = if (cancel) "Descarga cancelada" else if (okAll && ready(c)) "Listo" else "Faltan datos: ${error ?: "reintenta con Wi-Fi"}"
        } finally { running = false }
    }

    fun pct(): Int = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
    fun mb(x: Long) = (x / 1_048_576L).toString()
}
