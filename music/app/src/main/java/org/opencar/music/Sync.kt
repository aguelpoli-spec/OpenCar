package org.opencar.music

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Copia a la pantalla tu PROPIA musica desde una carpeta tuya: un servidor WebDAV (Nextcloud, NAS...)
 * o el indice HTTP de una carpeta de tu PC. Los archivos quedan en Music/OpenCarSync y el launcher
 * los ve en "Mi musica" porque se registran en el almacenamiento del sistema.
 */
object Sync {
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "lrc")
    @Volatile var running = false
    @Volatile var cancel = false
    @Volatile var found = 0
    @Volatile var done = 0
    @Volatile var skipped = 0
    @Volatile var failed = 0
    @Volatile var bytes = 0L
    @Volatile var current = ""
    @Volatile var lastRun = 0L
    @Volatile var lastSummary = ""
    private val lines = ArrayList<String>()

    fun prefs(c: Context) = c.getSharedPreferences("sync", Context.MODE_PRIVATE)

    @Synchronized fun log(s: String) {
        lines.add(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + s)
        while (lines.size > 40) lines.removeAt(0)
    }
    @Synchronized fun logText(n: Int = 14): String = lines.takeLast(n).joinToString("\n")

    class Item(val rel: String, val url: String, val size: Long)

    private fun auth(c: Context): String? {
        val p = prefs(c)
        val u = p.getString("user", "") ?: ""
        if (u.isBlank()) return null
        return "Basic " + Base64.encodeToString("$u:${p.getString("pass", "")}".toByteArray(), Base64.NO_WRAP)
    }

    private fun baseUrl(c: Context): URL? {
        var u = (prefs(c).getString("url", "") ?: "").trim()
        if (u.isBlank()) return null
        if (!u.contains("://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return try { URL(u) } catch (e: Exception) { null }
    }

    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()

    // ------------------------------------------------------------ listado: indice HTTP

    private fun getText(u: URL, auth: String?): String? = try {
        val cn = u.openConnection() as HttpURLConnection
        cn.connectTimeout = 10000; cn.readTimeout = 20000
        auth?.let { cn.setRequestProperty("Authorization", it) }
        if (cn.responseCode != 200) null else cn.inputStream.use { String(it.readBytes()) }
    } catch (e: Exception) { null }

    private fun listIndex(base: URL, dir: URL, auth: String?, out: MutableList<Item>, depth: Int, max: Int) {
        if (depth > 6 || out.size >= max || cancel) return
        val html = getText(dir, auth) ?: return
        val re = Regex("href\\s*=\\s*[\"']([^\"'#?]+)[\"']", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            if (out.size >= max || cancel) return
            val href = m.groupValues[1]
            if (href.startsWith("..") || href == "./" || href == "/") continue
            val u = try { URL(dir, href) } catch (e: Exception) { continue }
            if (u.host != base.host || u.port != base.port || !u.path.startsWith(base.path) || u.path.length <= dir.path.length) continue
            if (u.path.endsWith("/")) listIndex(base, u, auth, out, depth + 1, max)
            else if (ext(u.path) in AUDIO) out.add(Item(Uri.decode(u.path.removePrefix(base.path)), u.toString(), -1L))
        }
    }

    // ------------------------------------------------------------ listado: WebDAV (PROPFIND por socket)

    private fun dechunk(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            var j = i
            while (j + 1 < b.size && !(b[j] == 13.toByte() && b[j + 1] == 10.toByte())) j++
            val n = String(b, i, j - i).substringBefore(";").trim().toIntOrNull(16) ?: break
            if (n == 0) break
            i = j + 2
            out.write(b, i, minOf(n, b.size - i))
            i += n + 2
        }
        return out.toByteArray()
    }

    private fun raw(method: String, url: URL, auth: String?, body: String?, depth: String?): Pair<Int, ByteArray>? {
        var u = url
        for (attempt in 0 until 4) {
            try {
                val https = u.protocol == "https"
                val port = if (u.port > 0) u.port else if (https) 443 else 80
                val plain = Socket()
                plain.connect(InetSocketAddress(u.host, port), 10000)
                val sock = if (https) (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, u.host, port, true) else plain
                sock.soTimeout = 20000
                val result: Pair<Int, ByteArray>? = sock.use { s ->
                    val payload = body?.toByteArray()
                    val sb = StringBuilder()
                    sb.append("$method ${if (u.file.isEmpty()) "/" else u.file} HTTP/1.1\r\nHost: ${u.host}${if (u.port > 0) ":" + u.port else ""}\r\n")
                    sb.append("User-Agent: OpenCarMusic/1.0\r\nAccept: */*\r\nConnection: close\r\n")
                    if (auth != null) sb.append("Authorization: $auth\r\n")
                    if (depth != null) sb.append("Depth: $depth\r\n")
                    if (payload != null) sb.append("Content-Type: application/xml; charset=utf-8\r\nContent-Length: ${payload.size}\r\n")
                    sb.append("\r\n")
                    val os = s.getOutputStream()
                    os.write(sb.toString().toByteArray())
                    if (payload != null) os.write(payload)
                    os.flush()
                    val data = s.getInputStream().readBytes()
                    var cut = -1
                    for (i in 0 until data.size - 3) {
                        if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() && data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()) { cut = i; break }
                    }
                    if (cut < 0) return null
                    val head = String(data, 0, cut)
                    val code = head.lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull() ?: return null
                    val loc = if (code in listOf(301, 302, 307, 308))
                        head.lineSequence().firstOrNull { it.startsWith("Location:", true) }?.substringAfter(":")?.trim() else null
                    if (loc != null) {
                        u = URL(u, loc)
                        null
                    } else {
                        var rest = data.copyOfRange(cut + 4, data.size)
                        if (head.contains("transfer-encoding: chunked", true)) rest = dechunk(rest)
                        Pair(code, rest)
                    }
                }
                if (result != null) return result
            } catch (e: Exception) { return null }
        }
        return null
    }

    private fun listDav(base: URL, dir: URL, auth: String?, out: MutableList<Item>, depth: Int, max: Int) {
        if (depth > 6 || out.size >= max || cancel) return
        val xml = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"
        val r = raw("PROPFIND", dir, auth, xml, "1") ?: return
        if (r.first != 207 && r.first != 200) return
        try {
            val f = DocumentBuilderFactory.newInstance(); f.isNamespaceAware = true
            val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(r.second))
            val resp = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until resp.length) {
                if (out.size >= max || cancel) return
                val el = resp.item(i) as org.w3c.dom.Element
                val href = el.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
                val u = try { URL(dir, href) } catch (e: Exception) { continue }
                val up = Uri.decode(u.path); val dp = Uri.decode(dir.path)
                if (up.trimEnd('/') == dp.trimEnd('/')) continue
                val isCol = el.getElementsByTagNameNS("DAV:", "collection").length > 0 || href.endsWith("/")
                if (isCol) listDav(base, if (u.path.endsWith("/")) u else URL(u.toString() + "/"), auth, out, depth + 1, max)
                else if (ext(up) in AUDIO) {
                    val len = el.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: -1L
                    out.add(Item(up.removePrefix(Uri.decode(base.path)), u.toString(), len))
                }
            }
        } catch (e: Exception) { }
    }

    private fun list(c: Context, max: Int): List<Item>? {
        val base = baseUrl(c) ?: return null
        val out = ArrayList<Item>()
        if ((prefs(c).getString("type", "http") ?: "http") == "dav") listDav(base, base, auth(c), out, 0, max)
        else listIndex(base, base, auth(c), out, 0, max)
        return out
    }

    /** Prueba la conexion y dice cuantas canciones encuentra. */
    fun test(c: Context): String {
        if (baseUrl(c) == null) return "Falta la dirección de la carpeta."
        val l = try { list(c, 200) } catch (e: Exception) { null } ?: return "No pude conectar. Revisa la dirección, el usuario y que el PC o la nube estén encendidos y en la misma red."
        return if (l.isEmpty()) "Conecté, pero no encontré canciones (mp3, m4a, flac, ogg, wav…)." else "Conectado: encontré ${if (l.size >= 200) "200 o más" else l.size.toString()} canciones."
    }

    // ------------------------------------------------------------ descarga

    private fun mime(e: String) = when (e) {
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/x-wav"; else -> "audio/mpeg"
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    /** Los .lrc (letras sincronizadas) no son musica: se guardan en la base de datos para el launcher. */
    private fun downloadLrc(c: Context, it: Item, auth: String?): Boolean {
        val cn = URL(it.url).openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 30000
        auth?.let { a -> cn.setRequestProperty("Authorization", a) }
        try {
            if (cn.responseCode != 200) return false
            val out = ByteArrayOutputStream()
            cn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (out.size() < 600_000) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            Lyrics.storeSidecar(c, it.rel.substringAfterLast('/'), out.toByteArray())
            Db.markSynced(c, it.rel, out.size().toLong(), "lrc")
            return true
        } catch (e: Exception) { return false } finally { cn.disconnect() }
    }

    private fun download(c: Context, it: Item, auth: String?): Boolean {
        val segs = it.rel.split("/").filter { s -> s.isNotBlank() }.map { s -> clean(s) }
        if (segs.isEmpty()) return false
        val name = segs.last()
        val rel = "Music/OpenCarSync/" + segs.dropLast(1).joinToString("/") { s -> s } .let { d -> if (d.isEmpty()) "" else "$d/" }
        val cn = URL(it.url).openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 30000
        auth?.let { a -> cn.setRequestProperty("Authorization", a) }
        if (cn.responseCode != 200) { cn.disconnect(); return false }
        val size = cn.contentLengthLong
        val res = c.contentResolver
        val coll = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime(ext(name)))
            put(MediaStore.Audio.Media.RELATIVE_PATH, rel)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = res.insert(coll, values) ?: run { cn.disconnect(); return false }
        try {
            res.openOutputStream(uri)?.use { os ->
                cn.inputStream.use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        if (cancel) throw java.io.IOException("cancelado")
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); bytes += n
                    }
                }
            } ?: throw java.io.IOException("sin salida")
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            res.update(uri, values, null, null)
            Db.markSynced(c, it.rel, if (it.size > 0) it.size else size, uri.toString())
            return true
        } catch (e: Exception) {
            try { res.delete(uri, null, null) } catch (x: Exception) { }
            return false
        } finally { cn.disconnect() }
    }

    /** Sincroniza. Si no es manual, respeta "solo Wi-Fi sin limite de datos". Devuelve un resumen. */
    fun run(c: Context, manual: Boolean): String {
        if (running) return "Ya hay una sincronización en curso."
        running = true; cancel = false; found = 0; done = 0; skipped = 0; failed = 0; bytes = 0L; current = ""
        var summary: String
        try {
            if (baseUrl(c) == null) { summary = "Falta configurar la carpeta."; return summary }
            val cm = c.getSystemService(ConnectivityManager::class.java)
            if (!manual && cm.isActiveNetworkMetered && !prefs(c).getBoolean("metered", false)) {
                summary = "Red con límite de datos: no sincronizo (puedes permitirlo en la pantalla Sincronizar)."
                log(summary); return summary
            }
            log("Buscando canciones en tu carpeta…")
            val items = list(c, 5000)
            if (items == null) { summary = "No pude leer la carpeta."; log(summary); return summary }
            found = items.size
            log("Encontré ${items.size} canciones.")
            val a = auth(c)
            for (it in items) {
                if (cancel) break
                if (Db.isSynced(c, it.rel)) { skipped++; continue }
                current = it.rel.substringAfterLast('/')
                val ok = if (ext(it.rel) == "lrc") downloadLrc(c, it, a) else download(c, it, a)
                if (ok) { done++; log("Copiada: $current") } else { failed++; log("Error: $current") }
            }
            summary = "$done nuevas, $skipped ya estaban" + (if (failed > 0) ", $failed con error" else "") + (if (cancel) " (cancelada)" else "")
            log(summary)
        } catch (e: Exception) {
            summary = "Error: ${e.message}"
            log(summary)
        } finally {
            running = false; current = ""; lastRun = System.currentTimeMillis()
        }
        lastSummary = summary
        return summary
    }
}
