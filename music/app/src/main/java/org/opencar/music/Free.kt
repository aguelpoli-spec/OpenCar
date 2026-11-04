package org.opencar.music

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Musica libre: solo canciones con licencia Creative Commons o dominio publico.
 * Fuentes: Internet Archive (sin clave) y Jamendo (con tu clave gratuita de desarrollador).
 * Cada descarga queda registrada en "Creditos" con su licencia.
 */
object Free {
    private const val UA = "OpenCarMusic/1.0 (https://github.com/opencar)"

    class Hit(val src: String, val id: String, val title: String, val artist: String, val license: String, val dl: String?, val page: String)
    class Track(val name: String, val url: String)

    @Volatile var status = ""
    @Volatile var busy = false

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): ByteArray? = try {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = 12000; cn.readTimeout = 25000
        cn.setRequestProperty("User-Agent", UA)
        if (cn.responseCode != 200) { cn.disconnect(); null } else {
            val out = ByteArrayOutputStream()
            cn.inputStream.use { ins -> val b = ByteArray(16384); while (true) { val n = ins.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > 8_000_000) break } }
            cn.disconnect(); out.toByteArray()
        }
    } catch (e: Exception) { null }

    private fun str(o: JSONObject, k: String): String {
        val v = o.opt(k) ?: return ""
        return if (v is JSONArray) (if (v.length() > 0) v.optString(0) else "") else v.toString()
    }

    /** "http://creativecommons.org/licenses/by-nc-sa/3.0/" -> "CC BY-NC-SA 3.0" */
    fun licenseName(url: String?): String {
        if (url.isNullOrBlank()) return ""
        Regex("creativecommons.org/licenses/([a-z\\-]+)/([0-9.]+)").find(url)?.let { return "CC " + it.groupValues[1].uppercase() + " " + it.groupValues[2] }
        if (url.contains("publicdomain/zero")) return "CC0 (dominio público)"
        if (url.contains("publicdomain")) return "Dominio público"
        return url
    }

    private fun isFree(url: String?) = url != null && (url.contains("creativecommons.org") || url.contains("publicdomain"))

    // ------------------------------------------------------------ Internet Archive

    fun searchArchive(term: String): List<Hit> {
        val out = ArrayList<Hit>()
        fun run(query: String, rows: Int): Boolean {
            val url = "https://archive.org/advancedsearch.php?q=${q(query)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl&rows=$rows&page=1&output=json"
            val b = get(url) ?: return false
            val docs = try { JSONObject(String(b)).getJSONObject("response").getJSONArray("docs") } catch (e: Exception) { return false }
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val lic = str(d, "licenseurl")
                if (!isFree(lic)) continue
                val id = str(d, "identifier")
                out.add(Hit("archive", id, str(d, "title").ifBlank { id }, str(d, "creator"), licenseName(lic), null, "https://archive.org/details/$id"))
            }
            return true
        }
        run("($term) AND mediatype:audio AND licenseurl:(*creativecommons.org* OR *publicdomain*)", 30)
        if (out.isEmpty()) run("($term) AND mediatype:audio", 80)
        return out
    }

    /** Pistas de audio de un elemento: prefiere MP3, luego OGG, luego FLAC (sin duplicados por formato). */
    fun archiveTracks(id: String): List<Track> {
        val b = get("https://archive.org/metadata/${Uri.encode(id)}") ?: return emptyList()
        val files = try { JSONObject(String(b)).getJSONArray("files") } catch (e: Exception) { return emptyList() }
        val byExt = HashMap<String, MutableList<Track>>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name")
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("mp3", "ogg", "flac") || f.optString("source") == "metadata") continue
            if (f.optString("size", "0").toLongOrNull() ?: 0L < 50_000L) continue
            if (name.contains("sample", true)) continue
            byExt.getOrPut(ext) { ArrayList() }.add(Track(name, "https://archive.org/download/${Uri.encode(id)}/${Uri.encode(name, "/")}"))
        }
        val pick = byExt["mp3"] ?: byExt["ogg"] ?: byExt["flac"] ?: return emptyList()
        return pick.take(60)
    }

    // ------------------------------------------------------------ Jamendo

    fun searchJamendo(key: String, term: String): List<Hit> {
        val b = get("https://api.jamendo.com/v3.0/tracks/?client_id=${q(key)}&format=json&limit=30&search=${q(term)}&audiodlformat=mp32") ?: return emptyList()
        val out = ArrayList<Hit>()
        try {
            val arr = JSONObject(String(b)).getJSONArray("results")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dl = o.optString("audiodownload")
                if (!o.optBoolean("audiodownload_allowed", false) || dl.isBlank()) continue
                out.add(Hit("jamendo", o.optString("id"), o.optString("name"), o.optString("artist_name"), licenseName(o.optString("license_ccurl")), dl,
                    o.optString("shareurl").ifBlank { "https://www.jamendo.com/track/" + o.optString("id") }))
            }
        } catch (e: Exception) { }
        return out
    }

    // ------------------------------------------------------------ descarga

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Sin nombre" }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; else -> "audio/mpeg"
    }

    private fun save(c: Context, url: String, folder: String, name: String): Boolean {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 40000
        cn.setRequestProperty("User-Agent", UA)
        if (cn.responseCode != 200) { cn.disconnect(); return false }
        val res = c.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/OpenCarLibre/$folder/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = res.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: run { cn.disconnect(); return false }
        return try {
            res.openOutputStream(uri)?.use { os -> cn.inputStream.use { ins -> ins.copyTo(os) } } ?: throw java.io.IOException()
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0); res.update(uri, values, null, null)
            true
        } catch (e: Exception) { try { res.delete(uri, null, null) } catch (x: Exception) { }; false } finally { cn.disconnect() }
    }

    /** Descarga una cancion de Jamendo o las pistas de un elemento de Internet Archive. Devuelve cuantas guardo. */
    fun download(c: Context, hit: Hit, tracks: List<Track>?): Int {
        busy = true
        var ok = 0
        try {
            val artist = clean(hit.artist.ifBlank { "Varios artistas" })
            if (hit.src == "jamendo" && hit.dl != null) {
                status = "Descargando ${hit.title}…"
                if (save(c, hit.dl, artist, clean(hit.title) + ".mp3")) { ok++; Db.addCredit(c, hit.title, hit.artist, hit.license, hit.page, "jamendo") }
            } else if (tracks != null) {
                val folder = clean(hit.artist.ifBlank { hit.title })
                for ((i, t) in tracks.withIndex()) {
                    status = "Descargando ${i + 1} de ${tracks.size}: ${t.name.substringAfterLast('/')}"
                    val nm = clean(t.name.substringAfterLast('/'))
                    if (save(c, t.url, folder, nm)) { ok++; Db.addCredit(c, nm.substringBeforeLast('.'), hit.artist.ifBlank { hit.title }, hit.license, hit.page, "archive") }
                }
            }
            status = "Listo: $ok guardadas en Music/OpenCarLibre"
        } catch (e: Exception) { status = "Error: ${e.message}" }
        busy = false
        return ok
    }
}
