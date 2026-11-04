package org.opencar.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Catálogo de autos para personalizar el launcher.
 *  - Marcas y logos: vienen dentro de la app (assets/brands.json + assets/logos (PNG por marca)).
 *  - Modelos: se consultan a Wikidata cuando hay internet (cualquier marca, de los más antiguos a los más nuevos).
 *  - Fotos para el fondo: Wikimedia Commons (licencias libres). Se guarda el crédito del autor.
 * Los logos son marcas registradas de sus fabricantes; se usan solo para identificar el auto del usuario.
 */
object CarCatalog {
    class Brand(val name: String, val slug: String)
    class Model(val name: String, val years: String, val image: String?)
    class Photo(val title: String, val thumb: String, val full: String, val credit: String)

    const val UA = "OpenCarLauncher/1.0 (https://github.com/opencar; open source car launcher)"
    private var cache: List<Brand>? = null

    fun brands(ctx: Context): List<Brand> {
        cache?.let { return it }
        val out = ArrayList<Brand>()
        try {
            val arr = JSONArray(ctx.assets.open("brands.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); out.add(Brand(o.getString("n"), o.getString("s"))) }
        } catch (e: Exception) { }
        cache = out
        return out
    }

    fun brand(ctx: Context, p: SharedPreferences): Brand? {
        val s = p.getString("car_brand", null) ?: return null
        return brands(ctx).firstOrNull { it.slug == s }
    }

    fun logo(ctx: Context, slug: String?): Bitmap? {
        if (slug == null) return null
        return try { ctx.assets.open("logos/$slug.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }

    /** Texto grande del inicio: «TOYOTA COROLLA 2012», o «MI AUTO» si aún no se eligió. */
    fun title(p: SharedPreferences): String = (p.getString("car_name", null) ?: "").trim().ifEmpty { "Mi auto" }.uppercase()

    private fun plain(x: String): String = Normalizer.normalize(x.lowercase(), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

    /** Palabra para despertar al asistente: la primera palabra de la marca («Hey Toyota»); sin marca, «auto». */
    fun wakeWord(ctx: Context, p: SharedPreferences): String {
        val b = brand(ctx, p) ?: return "auto"
        return plain(b.name.split(' ', '-').first()).ifEmpty { "auto" }
    }

    // ------------------------------------------------------------------ red

    private fun get(url: String, accept: String? = null): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = 20000; cn.readTimeout = 70000
        cn.setRequestProperty("User-Agent", UA)
        if (accept != null) cn.setRequestProperty("Accept", accept)
        try {
            if (cn.responseCode != 200) throw IOException("HTTP ${cn.responseCode}")
            return cn.inputStream.bufferedReader().use { it.readText() }
        } finally { cn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Modelos de una marca según Wikidata (con año de inicio y foto cuando existen). Bloquea: usar en un hilo. */
    fun models(brandName: String): List<Model> {
        val b = esc(brandName)
        val q = "SELECT ?ml (MIN(?s) AS ?start) (MAX(?e) AS ?end) (SAMPLE(?img) AS ?image) WHERE { " +
            "?b rdfs:label \"$b\"@en . ?m wdt:P176 ?b . ?m wdt:P31/wdt:P279* wd:Q3231690 . " +
            "?m rdfs:label ?ml . FILTER(LANG(?ml)=\"en\" && STRSTARTS(?ml, \"$b \")) " +
            "OPTIONAL { ?m wdt:P571 ?s } OPTIONAL { ?m wdt:P576 ?e } OPTIONAL { ?m wdt:P18 ?img } } GROUP BY ?ml LIMIT 900"
        val json = JSONObject(get("https://query.wikidata.org/sparql?format=json&query=" + enc(q), "application/sparql-results+json"))
        val rows = json.getJSONObject("results").getJSONArray("bindings")
        val out = ArrayList<Pair<Int, Model>>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val name = r.getJSONObject("ml").getString("value")
            val y0 = r.optJSONObject("start")?.getString("value")?.take(4)?.toIntOrNull()
            val y1 = r.optJSONObject("end")?.getString("value")?.take(4)?.toIntOrNull()
            val years = if (y0 == null) "" else if (y1 != null) "$y0–$y1" else "$y0–"
            val img = r.optJSONObject("image")?.getString("value")
            out.add(Pair(y0 ?: 9999, Model(name, years, img)))
        }
        return out.sortedWith(compareBy({ it.first }, { it.second.name.lowercase() })).map { it.second }
    }

    /** Nombre de archivo de Commons a partir de la dirección que da Wikidata (…/Special:FilePath/Nombre.jpg). */
    private fun fileOf(imageUrl: String): String = java.net.URLDecoder.decode(imageUrl.substringAfter("FilePath/").substringBefore('?'), "UTF-8")

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private fun photosFrom(json: JSONObject): List<Photo> {
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<Photo>()
        for (k in pages.keys()) {
            val pg = pages.getJSONObject(k)
            val ii = pg.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (ii.optString("mime") != "image/jpeg" && ii.optString("mime") != "image/png") continue
            val full = ii.optString("thumburl").ifEmpty { ii.optString("url") }
            if (full.isEmpty()) continue
            val meta = ii.optJSONObject("extmetadata")
            val lic = meta?.optJSONObject("LicenseShortName")?.optString("value") ?: ""
            if (Regex("NC|ND", RegexOption.IGNORE_CASE).containsMatchIn(lic)) continue   // solo licencias que permiten uso libre
            val who = stripHtml(meta?.optJSONObject("Artist")?.optString("value") ?: "")
            val title = pg.optString("title").removePrefix("File:").substringBeforeLast('.')
            val thumb = full.replace(Regex("/\\d+px-"), "/320px-")
            out.add(Photo(title, thumb, full, listOf(who, lic, "Wikimedia Commons").filter { it.isNotBlank() }.joinToString(" · ")))
        }
        return out
    }

    private const val API = "https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url|mime|extmetadata&iiurlwidth=1280&iiextmetadatafilter=LicenseShortName|Artist"

    /** Busca fotos libres en Commons («Toyota Corolla 2012»). Bloquea: usar en un hilo. */
    fun searchPhotos(query: String, limit: Int = 24): List<Photo> =
        photosFrom(JSONObject(get("$API&generator=search&gsrnamespace=6&gsrlimit=$limit&gsrsearch=" + enc("$query filetype:bitmap"))))

    /** La foto principal que Wikidata asocia a un modelo. */
    fun photoOf(imageUrl: String): List<Photo> =
        photosFrom(JSONObject(get("$API&titles=" + enc("File:" + fileOf(imageUrl)))))

    /** Descarga la foto, la guarda como fondo propio del launcher y anota el crédito del autor. */
    fun saveBackground(ctx: Context, p: SharedPreferences, ph: Photo): Boolean = try {
        val cn = URL(ph.full).openConnection() as HttpURLConnection
        cn.connectTimeout = 20000; cn.readTimeout = 60000; cn.setRequestProperty("User-Agent", UA)
        val bmp = cn.inputStream.use { BitmapFactory.decodeStream(it) } ?: throw IOException("imagen inválida")
        FileOutputStream(File(ctx.filesDir, "bg.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        p.edit().putString("bg_choice", "custom").putString("bg_credit", "Foto: ${ph.title} · ${ph.credit}").apply()
        true
    } catch (e: Exception) { false }
}
