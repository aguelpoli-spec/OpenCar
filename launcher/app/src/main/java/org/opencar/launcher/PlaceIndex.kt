package org.opencar.launcher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot

data class Place(val name: String, val sub: String, val lat: Double, val lon: Double)

/** Lee el mapa descargado y arma una lista de calles, avenidas y lugares para buscar sin internet. */
class PlaceIndex(private val ctx: Context) : SQLiteOpenHelper(ctx, "places.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE p(norm TEXT, name TEXT, sub TEXT, lat REAL, lon REAL)")
        db.execSQL("CREATE INDEX i_norm ON p(norm)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    companion object {
        private val lock = Any()
        private var mf: MapFile? = null
        @Volatile var building = false
        @Volatile var progress = 0
        fun norm(x: String): String = Normalizer.normalize(x.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        fun mapFile(c: Context): MapFile? = synchronized(lock) {
            if (mf == null) { val f = MapData.mapFile(c); if (f.exists()) mf = try { MapFile(f) } catch (e: Exception) { null } }
            mf
        }

        private val KIND = mapOf(
            "restaurant" to "Restaurante", "fast_food" to "Comida rápida", "cafe" to "Café", "bar" to "Bar", "pub" to "Bar",
            "fuel" to "Gasolinera", "hospital" to "Hospital", "clinic" to "Clínica", "pharmacy" to "Farmacia", "bank" to "Banco", "atm" to "Cajero",
            "school" to "Colegio", "university" to "Universidad", "college" to "Instituto", "kindergarten" to "Jardín", "parking" to "Estacionamiento",
            "supermarket" to "Supermercado", "mall" to "Centro comercial", "hotel" to "Hotel", "place_of_worship" to "Iglesia", "police" to "Policía",
            "car_repair" to "Taller", "car_wash" to "Lavado de autos", "bus_station" to "Terminal", "marketplace" to "Mercado", "cinema" to "Cine",
            "stadium" to "Estadio", "park" to "Parque", "townhall" to "Alcaldía", "post_office" to "Correo", "library" to "Biblioteca",
            "suburb" to "Barrio", "neighbourhood" to "Barrio", "city" to "Ciudad", "town" to "Pueblo", "village" to "Comunidad", "aerodrome" to "Aeropuerto"
        )

        fun nameAt(c: Context, lat: Double, lon: Double): String {
            val m = mapFile(c) ?: return ""
            try {
                val z: Byte = 17
                val tx = MercatorProjection.longitudeToTileX(lon, z); val ty = MercatorProjection.latitudeToTileY(lat, z)
                val r = synchronized(lock) { m.readMapData(Tile(tx, ty, z, 256)) } ?: return ""
                var best = ""; var bd = 70.0
                val k = 111320.0; val kx = k * cos(Math.toRadians(lat))
                for (w in r.ways) {
                    val hw = w.tags.any { it.key == "highway" }
                    if (!hw) continue
                    val nm = w.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    for (seg in w.latLongs) for (p in seg) {
                        val d = hypot((p.latitude - lat) * k, (p.longitude - lon) * kx)
                        if (d < bd) { bd = d; best = nm }
                    }
                }
                return best
            } catch (e: Exception) { return "" }
        }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM p", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun regions(): String = readableDatabase.rawQuery("SELECT v FROM meta WHERE k='regions'", null).use { if (it.moveToFirst()) it.getString(0) else "" }

    fun clear() { writableDatabase.execSQL("DELETE FROM p"); writableDatabase.execSQL("DELETE FROM meta") }

    private fun kindOf(tags: List<Tag>): String? {
        for (t in tags) when (t.key) {
            "amenity", "shop", "tourism", "leisure", "place", "aeroway", "highway" -> KIND[t.value]?.let { return it }
        }
        for (t in tags) when (t.key) {
            "shop" -> return "Tienda"; "tourism" -> return "Turismo"; "leisure" -> return "Ocio"; "amenity" -> return "Lugar"
        }
        return null
    }

    /** Indexa un cuadro de ±r grados alrededor de un punto (≈ 0,25° = 28 km). */
    fun build(lat: Double, lon: Double, r: Double = 0.25) {
        if (building) return
        building = true; progress = 0
        try {
            val m = mapFile(ctx) ?: return
            val z: Byte = 14
            val x0 = MercatorProjection.longitudeToTileX(lon - r, z); val x1 = MercatorProjection.longitudeToTileX(lon + r, z)
            val y0 = MercatorProjection.latitudeToTileY(lat + r, z); val y1 = MercatorProjection.latitudeToTileY(lat - r, z)
            val total = (x1 - x0 + 1) * (y1 - y0 + 1)
            var n = 0
            val db = writableDatabase
            for (x in x0..x1) for (y in y0..y1) {
                n++
                progress = n * 100 / total
                try {
                    val res = synchronized(lock) { m.readMapData(Tile(x * 8, y * 8, 17, 256), Tile(x * 8 + 7, y * 8 + 7, 17, 256)) } ?: continue
                    db.beginTransaction()
                    try {
                        val seen = HashSet<String>()
                        fun add(name: String, sub: String, la: Double, lo: Double) {
                            val key = name + "|" + sub + (if (sub == "Calle" || sub == "Avenida") "" else "${(la * 2000).toInt()},${(lo * 2000).toInt()}")
                            if (!seen.add(key)) return
                            val cv = ContentValues()
                            cv.put("norm", norm(name)); cv.put("name", name); cv.put("sub", sub); cv.put("lat", la); cv.put("lon", lo)
                            db.insert("p", null, cv)
                        }
                        for (p in res.pointOfInterests) {
                            val nm = p.tags.firstOrNull { it.key == "name" }?.value ?: continue
                            val k = kindOf(p.tags) ?: continue
                            add(nm, k, p.position.latitude, p.position.longitude)
                        }
                        for (w in res.ways) {
                            val nm = w.tags.firstOrNull { it.key == "name" }?.value ?: continue
                            val pos = w.labelPosition ?: w.latLongs.firstOrNull()?.firstOrNull() ?: continue
                            if (w.tags.any { it.key == "highway" }) {
                                val hv = w.tags.first { it.key == "highway" }.value
                                if (hv in listOf("footway", "path", "steps", "cycleway", "service", "track", "pedestrian")) continue
                                val n2 = norm(nm)
                                add(nm, if (n2.startsWith("avenida") || n2.startsWith("av ") || hv in listOf("primary", "trunk", "motorway")) "Avenida" else "Calle", pos.latitude, pos.longitude)
                            } else {
                                val k = kindOf(w.tags) ?: if (w.tags.any { it.key == "building" }) "Edificio" else continue
                                add(nm, k, pos.latitude, pos.longitude)
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                } catch (e: Exception) { }
            }
            val cur = regions()
            val tag = String.format(java.util.Locale.US, "%.2f,%.2f,%.2f", lat, lon, r)
            val cv = ContentValues(); cv.put("k", "regions"); cv.put("v", if (cur.isEmpty()) tag else "$cur;$tag")
            db.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } finally { building = false; progress = 100 }
    }

    fun covers(lat: Double, lon: Double): Boolean = regions().split(";").filter { it.isNotBlank() }.any {
        val p = it.split(",").map { s -> s.toDoubleOrNull() ?: 0.0 }
        p.size == 3 && Math.abs(lat - p[0]) < p[2] && Math.abs(lon - p[1]) < p[2]
    }

    fun nearestKind(kind: String, lat: Double, lon: Double, limit: Int = 5): List<Place> {
        val k = 111320.0; val kx = k * cos(Math.toRadians(lat))
        val all = ArrayList<Place>()
        readableDatabase.rawQuery("SELECT name, sub, lat, lon FROM p WHERE sub = ? LIMIT 1500", arrayOf(kind)).use { c ->
            while (c.moveToNext()) all.add(Place(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3)))
        }
        return all.sortedBy { hypot((it.lat - lat) * k, (it.lon - lon) * kx) }.take(limit)
    }

    fun search(q: String, lat: Double, lon: Double, limit: Int = 15): List<Place> {
        val toks = norm(q).split(" ").filter { it.isNotEmpty() }
        if (toks.isEmpty()) return emptyList()
        val where = toks.joinToString(" AND ") { "norm LIKE ?" }
        val args = toks.map { "%$it%" }.toTypedArray()
        val out = ArrayList<Place>()
        val seen = HashSet<String>()
        val k = 111320.0; val kx = k * cos(Math.toRadians(lat))
        val all = ArrayList<Pair<Double, Place>>()
        readableDatabase.rawQuery("SELECT name, sub, lat, lon, norm FROM p WHERE $where LIMIT 4000", args).use { c ->
            while (c.moveToNext()) {
                val pl = Place(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3))
                val nm = c.getString(4)
                var score = hypot((pl.lat - lat) * k, (pl.lon - lon) * kx)
                // nombre exacto primero (el más cercano entre los exactos); todo lo demás, del más cerca al más lejos
                if (nm == toks.joinToString(" ")) score -= 1_000_000.0
                all.add(Pair(score, pl))
            }
        }
        for ((_, pl) in all.sortedBy { it.first }) {
            if (!seen.add(pl.name + "|" + pl.sub)) continue
            out.add(pl)
            if (out.size >= limit) break
        }
        return out
    }
}
