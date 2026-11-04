package org.opencar.launcher

import android.content.Context
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Estacion(val name: String, val lat: Double, val lon: Double, val flags: String, val brand: String) {
    /** El mapa casi nunca trae el tipo de combustible: «gas» se deduce del nombre o de las etiquetas si existen. */
    val gas: Boolean get() = 'c' in flags || 'l' in flags
    val shown: String get() = name.ifBlank { brand.ifBlank { "Estación de servicio" } }
}

/** Estaciones de servicio cercanas, leídas del mapa descargado (OpenStreetMap, sin internet). */
object Estaciones {
    // elección por voz en curso
    @Volatile var choices: List<Estacion> = emptyList()

    private var cacheKey = ""
    private var cache: List<Estacion> = emptyList()

    /** Estaciones en unos 12 km a la redonda. El resultado se reutiliza mientras no te muevas más de ~2 km. */
    @Synchronized
    fun around(ctx: Context, la: Double, lo: Double): List<Estacion> {
        val key = "${Math.round(la * 50)},${Math.round(lo * 50)}"
        if (key == cacheKey) return cache
        val out = ArrayList<Estacion>()
        try {
            val m = PlaceIndex.mapFile(ctx) ?: return emptyList()
            val z: Byte = 14
            val x0 = org.mapsforge.core.util.MercatorProjection.longitudeToTileX(lo - 0.11, z); val x1 = org.mapsforge.core.util.MercatorProjection.longitudeToTileX(lo + 0.11, z)
            val y0 = org.mapsforge.core.util.MercatorProjection.latitudeToTileY(la + 0.11, z); val y1 = org.mapsforge.core.util.MercatorProjection.latitudeToTileY(la - 0.11, z)
            val seen = HashSet<String>()
            for (x in x0..x1) for (y in y0..y1) {
                val res = m.readPoiData(org.mapsforge.core.model.Tile(x * 8, y * 8, 17, 256), org.mapsforge.core.model.Tile(x * 8 + 7, y * 8 + 7, 17, 256)) ?: continue
                for (p in res.pointOfInterests) {
                    if (p.tags.none { it.key == "amenity" && it.value == "fuel" }) continue
                    val name = p.tags.firstOrNull { it.key == "name" }?.value ?: ""
                    val brand = p.tags.firstOrNull { it.key == "brand" }?.value ?: ""
                    val flags = StringBuilder()
                    if (p.tags.any { (it.key == "fuel:cng" || it.key == "fuel:lng") && it.value == "yes" }) flags.append('c')
                    if (p.tags.any { it.key == "fuel:lpg" && it.value == "yes" }) flags.append('l')
                    if (seen.add("${(p.position.latitude * 10000).toInt()},${(p.position.longitude * 10000).toInt()}")) out.add(Estacion(name, p.position.latitude, p.position.longitude, flags.toString(), brand))
                }
            }
        } catch (e: Throwable) { }
        cacheKey = key; cache = out
        return out
    }

    fun dist(la: Double, lo: Double, e: Estacion): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(la); val p2 = Math.toRadians(e.lat)
        val dp = p2 - p1; val dl = Math.toRadians(e.lon - lo)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** Las n más cercanas (en línea recta). Con gas=true primero las que se sabe que despachan gas. */
    fun nearest(ctx: Context, la: Double, lo: Double, n: Int = 5, gas: Boolean = false): List<Pair<Estacion, Double>> = nearestIn(around(ctx, la, lo), la, lo, n, gas)

    fun nearestIn(base: List<Estacion>, la: Double, lo: Double, n: Int = 5, gas: Boolean = false): List<Pair<Estacion, Double>> {
        val pool = if (gas && base.any { it.gas }) base.filter { it.gas } else base
        return pool.map { Pair(it, dist(la, lo, it)) }.sortedBy { it.second }.take(n)
    }

    fun km(m: Double): String = if (m < 950) "${(Math.round(m / 10.0) * 10).toInt()} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000.0)

    /** Entiende la respuesta hablada: 1..5 = número, 0 = «sí / la más cercana», -1 = «no / otra», -2 = cancelar, null = no entendí. */
    fun parseChoice(raw: String, n: Int): Int? {
        val t = PlaceIndex.norm(raw)
        if (t.isEmpty()) return null
        if (Regex("\\b(cancela|cancelar|cancelalo|olvidalo|olvida|ninguna|ninguno|deja|salir|nada|dejalo)\\b").containsMatchIn(t)) return -2
        val nums = listOf(
            Regex("\\b(1|uno|una|primera|primero|la 1)\\b"), Regex("\\b(2|dos|segunda|segundo)\\b"), Regex("\\b(3|tres|tercera|tercero)\\b"),
            Regex("\\b(4|cuatro|cuarta|cuarto)\\b"), Regex("\\b(5|cinco|quinta|quinto)\\b")
        )
        if (Regex("\\b(mas cercana|mas cerca|la cercana|mas proxima)\\b").containsMatchIn(t)) return 0
        for (i in 0 until minOf(n, 5)) if (nums[i].containsMatchIn(t)) return i + 1
        if (Regex("\\b(si|sii|dale|confirmo|confirmado|claro|ok|okay|vamos|esa|esa misma|adelante|correcto|exacto|va|vale)\\b").containsMatchIn(t)) return 0
        if (Regex("\\b(no|otra|otras|otra opcion|siguiente|diferente)\\b").containsMatchIn(t)) return -1
        return null
    }

    /** ¿La frase pide cargar combustible? Devuelve null si no; true/false = quiere gas (GNV) o no. */
    fun wantsFuel(t: String): Boolean? {
        val n = PlaceIndex.norm(t)
        val gas = Regex("\\b(gnv|gas natural|gas vehicular|glp)\\b").containsMatchIn(n) || Regex("\\bcargar (el )?gas\\b|\\bcargo (el )?gas\\b|\\bcarga de gas\\b|\\bgas\\b.*\\b(estacion|cerca)").containsMatchIn(n)
        val fuel = Regex("\\b(cargar|cargo|echar|echo|poner|llenar|llenado|tanquear|tanqueo|repostar)\\b.*\\b(gasolina|combustible|nafta|diesel|gas|gnv|tanque)\\b").containsMatchIn(n) ||
            Regex("\\b(gasolinera|gasolineras|estacion de servicio|estaciones de servicio|surtidor|grifo|bomba de gasolina|donde (cargo|echo) (gasolina|combustible)|necesito (gasolina|combustible)|me falta (gasolina|combustible)|sin gasolina)\\b").containsMatchIn(n) ||
            Regex("\\bquiero (cargar|echar)\\b").containsMatchIn(n)
        return if (gas || fuel) gas else null
    }
}
