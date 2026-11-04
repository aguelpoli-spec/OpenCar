package org.opencar.music

import android.content.Context
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Caratulas desde bases abiertas: MusicBrainz (busqueda) y Cover Art Archive (imagen).
 * Se guardan en la carpeta de la app y se entregan al launcher con ArtProvider.
 */
object Covers {
    private const val UA = "OpenCarMusic/1.0 (https://github.com/opencar)"
    private val lock = Any()
    private var lastMb = 0L
    private val pool = Executors.newSingleThreadExecutor()
    private val queued = HashSet<String>()

    @Volatile var running = false
    @Volatile var total = 0
    @Volatile var done = 0
    @Volatile var found = 0
    @Volatile var cancel = false

    private fun key(artist: String, album: String, title: String): String =
        if (album.isNotBlank() && !album.startsWith("<")) "a|${Db.norm(artist)}|${Db.norm(album)}" else "t|${Db.norm(artist)}|${Db.norm(title)}"

    private fun dir(c: Context): File = (c.getExternalFilesDir("covers") ?: File(c.filesDir, "covers")).also { it.mkdirs() }

    private fun fileFor(c: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(c), "$h.jpg")
    }

    private fun http(url: String, mb: Boolean): ByteArray? {
        if (mb) synchronized(lock) {
            val w = 1100L - (System.currentTimeMillis() - lastMb)
            if (w > 0) Thread.sleep(w)
            lastMb = System.currentTimeMillis()
        }
        return try {
            val cn = URL(url).openConnection() as HttpURLConnection
            cn.connectTimeout = 10000; cn.readTimeout = 20000
            cn.instanceFollowRedirects = true
            cn.setRequestProperty("User-Agent", UA)
            if (cn.responseCode != 200) { cn.disconnect(); return null }
            val out = ByteArrayOutputStream()
            cn.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > 6_000_000) return null }
            }
            cn.disconnect()
            out.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\"", " ").replace("\\", " ")

    private fun artOf(mbid: String): ByteArray? = http("https://coverartarchive.org/release/$mbid/front-500", false)

    private fun search(artist: String, album: String, title: String): ByteArray? {
        val ids = ArrayList<String>()
        try {
            if (album.isNotBlank() && !album.startsWith("<")) {
                val qs = "release:\"${esc(album)}\"" + (if (artist.isNotBlank()) " AND artist:\"${esc(artist)}\"" else "")
                http("https://musicbrainz.org/ws/2/release/?query=${q(qs)}&fmt=json&limit=4", true)?.let { b ->
                    val arr = JSONObject(String(b)).optJSONArray("releases")
                    if (arr != null) for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).getString("id"))
                }
            }
            if (ids.isEmpty() && title.isNotBlank()) {
                val qs = "recording:\"${esc(title)}\"" + (if (artist.isNotBlank()) " AND artist:\"${esc(artist)}\"" else "")
                http("https://musicbrainz.org/ws/2/recording/?query=${q(qs)}&fmt=json&limit=3", true)?.let { b ->
                    val arr = JSONObject(String(b)).optJSONArray("recordings")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val rels = arr.getJSONObject(i).optJSONArray("releases") ?: continue
                        for (j in 0 until minOf(rels.length(), 3)) ids.add(rels.getJSONObject(j).getString("id"))
                    }
                }
            }
        } catch (e: Exception) { return null }
        for (id in ids.take(5)) artOf(id)?.let { return it }
        return null
    }

    /** Busca y guarda la caratula si no la tenemos (reintenta fallidas despues de 3 dias). */
    fun fetchFor(c: Context, artist: String, album: String, title: String): Boolean {
        val k = key(artist, album, title)
        val info = Db.coverInfo(c, k)
        if (info != null) {
            if (info.second && info.first != null && File(info.first!!).exists()) return true
            if (!info.second && System.currentTimeMillis() - info.third < 3 * 86_400_000L) return false
        }
        val bytes = search(artist, album, title)
        if (bytes == null) { Db.setCover(c, k, null, false); return false }
        val f = fileFor(c, k)
        return try { f.writeBytes(bytes); Db.setCover(c, k, f.absolutePath, true); true } catch (e: Exception) { false }
    }

    /** Ruta de la caratula ya guardada (por album o por titulo). */
    fun lookup(c: Context, artist: String, album: String, title: String): File? {
        for (k in listOf(key(artist, album, title), key(artist, "", title))) {
            val i = Db.coverInfo(c, k) ?: continue
            if (i.second && i.first != null) { val f = File(i.first!!); if (f.exists()) return f }
        }
        return null
    }

    /** Pide una caratula en segundo plano (la usa el launcher cuando suena algo sin imagen). */
    fun requestAsync(c: Context, artist: String, album: String, title: String) {
        if (title.isBlank()) return
        val k = key(artist, album, title)
        synchronized(queued) { if (!queued.add(k)) return }
        val app = c.applicationContext
        pool.execute {
            try { fetchFor(app, artist, album, title) } finally { synchronized(queued) { queued.remove(k) } }
        }
    }

    /** Recorre la musica guardada y busca la caratula de cada album que aun no la tiene. */
    fun scanLibrary(c: Context) {
        if (running) return
        running = true; cancel = false; done = 0; found = 0; total = 0
        try {
            val items = LinkedHashMap<String, Triple<String, String, String>>()
            val base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            c.contentResolver.query(base, arrayOf(MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.TITLE),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { q ->
                while (q.moveToNext()) {
                    val a = (q.getString(0) ?: "").let { if (it == "<unknown>") "" else it }
                    val al = (q.getString(1) ?: "").let { if (it == "<unknown>") "" else it }
                    val t = q.getString(2) ?: ""
                    items.putIfAbsent(key(a, al, t), Triple(a, al, t))
                }
            }
            total = items.size
            for ((k, v) in items) {
                if (cancel) break
                val info = Db.coverInfo(c, k)
                val had = info != null && info.second && info.first != null && File(info.first!!).exists()
                if (had || fetchFor(c, v.first, v.second, v.third)) found++
                done++
            }
        } catch (e: Exception) { }
        running = false
    }
}
