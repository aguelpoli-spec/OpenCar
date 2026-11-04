package org.opencar.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Letras desde internet (LRCLIB, servicio abierto y gratuito). Se guardan para verlas luego sin conexión. */
object LyricsNet {
    private const val UA = "OpenCarMusic/1.0 (https://github.com/opencar)"
    private val tried = HashSet<String>()

    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    private fun get(u: String): String? = try {
        val cn = URL(u).openConnection() as HttpURLConnection
        cn.setRequestProperty("User-Agent", UA); cn.connectTimeout = 8000; cn.readTimeout = 10000
        if (cn.responseCode == 200) cn.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Busca y guarda la letra. true si quedó guardada. */
    fun fetch(c: Context, artist: String, title: String, album: String, durMs: Long): Boolean {
        if (title.isBlank()) return false
        val key = Lyrics.key(artist, title)
        synchronized(tried) { if (!tried.add(key)) return Db.getLyrics(c, key) != null }
        if (Db.getLyrics(c, key) != null) return true
        if (!online(c)) { synchronized(tried) { tried.remove(key) }; return false }
        var o: JSONObject? = null
        val a = if (artist == "Artista desconocido") "" else artist
        val al = if (album == "Álbum desconocido") "" else album
        val dur = if (durMs > 0) "&duration=${durMs / 1000}" else ""
        get("https://lrclib.net/api/get?artist_name=${q(a)}&track_name=${q(title)}" + (if (al.isNotEmpty()) "&album_name=${q(al)}" else "") + dur)?.let { o = try { JSONObject(it) } catch (e: Exception) { null } }
        if (o == null || (o!!.optString("syncedLyrics").isBlank() && o!!.optString("plainLyrics").isBlank())) {
            get("https://lrclib.net/api/search?q=${q("$a $title".trim())}")?.let { s ->
                try { val arr = JSONArray(s); for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); if (x.optString("syncedLyrics").isNotBlank() || x.optString("plainLyrics").isNotBlank()) { o = x; break } } } catch (e: Exception) { }
            }
        }
        val x = o ?: return false
        val text = x.optString("syncedLyrics").ifBlank { x.optString("plainLyrics") }
        if (text.isBlank()) return false
        Db.putLyrics(c, key, text, "lrclib", title, artist)
        return true
    }
}
