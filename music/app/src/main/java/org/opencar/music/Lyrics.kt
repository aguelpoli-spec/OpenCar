package org.opencar.music

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Letras que YA traen tus archivos: etiquetas incrustadas (MP3 con ID3, M4A, FLAC) y archivos .lrc
 * sincronizados junto a la musica. No descarga letras de internet.
 */
object Lyrics {
    class Found(val text: String, val synced: Boolean)

    @Volatile var running = false
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var found = 0

    private val lrcRe = Regex("\\[\\d{1,3}:\\d{2}([.:]\\d{1,3})?\\]")
    fun isLrc(t: String) = lrcRe.containsMatchIn(t)

    fun key(artist: String, title: String) = "a|${Db.norm(artist)}|${Db.norm(title)}"
    fun fkey(file: String) = "f|${Db.norm(file.substringBeforeLast('.'))}"

    fun lookup(c: Context, artist: String, title: String, file: String): Found? {
        val keys = ArrayList<String>()
        if (file.isNotBlank()) keys.add(fkey(file))
        if (title.isNotBlank()) { keys.add(key(artist, title)); keys.add(key("", title)) }
        for (k in keys) Db.getLyrics(c, k)?.let { return Found(it.first, it.second) }
        return null
    }

    /** Guarda el texto de un .lrc / .txt con las claves posibles. */
    fun storeSidecar(c: Context, fileName: String, bytes: ByteArray) {
        val text = decodeText(bytes)
        if (text.isBlank()) return
        Db.putLyrics(c, fkey(fileName), text, "lrc")
        val ar = Regex("\\[ar:([^\\]]*)\\]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val ti = Regex("\\[ti:([^\\]]*)\\]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val base = fileName.substringBeforeLast('.')
        when {
            !ti.isNullOrBlank() -> Db.putLyrics(c, key(ar ?: "", ti), text, "lrc", ti, ar ?: "")
            base.contains(" - ") -> Db.putLyrics(c, key(base.substringBefore(" - "), base.substringAfter(" - ")), text, "lrc", base.substringAfter(" - "), base.substringBefore(" - "))
        }
    }

    private fun decodeText(b: ByteArray): String {
        var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
        val s = String(b, off, b.size - off, Charsets.UTF_8)
        return if (s.contains('�')) String(b, off, b.size - off, Charsets.ISO_8859_1) else s
    }

    // ------------------------------------------------------------ lectura de etiquetas

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun syncsafe(b: ByteArray, o: Int) = ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be24(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        return when (enc) {
            1 -> String(b, from, to - from, Charsets.UTF_16)
            2 -> String(b, from, to - from, Charsets.UTF_16BE)
            3 -> String(b, from, to - from, Charsets.UTF_8)
            else -> String(b, from, to - from, Charsets.ISO_8859_1)
        }
    }

    /** Posicion del terminador de texto (1 o 2 bytes segun la codificacion). */
    private fun term(b: ByteArray, from: Int, to: Int, enc: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) { while (i + 1 < to) { if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i; i += 2 }; return to }
        while (i < to) { if (b[i] == 0.toByte()) return i; i++ }
        return to
    }

    private fun parseUslt(t: ByteArray, s: Int, e: Int): String? {
        if (e - s < 5) return null
        val enc = t[s].toInt()
        val wide = enc == 1 || enc == 2
        var p = s + 4
        val d = term(t, p, e, enc)
        p = d + (if (wide) 2 else 1)
        return decode(t, p.coerceAtMost(e), e, enc).trim().takeIf { it.isNotBlank() }
    }

    private fun parseSylt(t: ByteArray, s: Int, e: Int): String? {
        if (e - s < 7) return null
        val enc = t[s].toInt()
        val wide = enc == 1 || enc == 2
        if (t[s + 4].toInt() != 2) return null          // solo marcas en milisegundos
        var p = s + 6
        p = term(t, p, e, enc) + (if (wide) 2 else 1)
        val sb = StringBuilder()
        while (p < e) {
            val q = term(t, p, e, enc)
            val text = decode(t, p, q, enc).replace("\n", " ").trim()
            p = q + (if (wide) 2 else 1)
            if (p + 4 > e) break
            val ms = be32(t, p); p += 4
            val sec = ms / 1000
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]%s\n", sec / 60, sec % 60, (ms % 1000) / 10, text))
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun id3(ins: InputStream): Found? {
        val h = ByteArray(10)
        if (readFully(ins, h) < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return null
        val ver = h[3].toInt()
        if (ver < 2 || ver > 4) return null
        val size = syncsafe(h, 6)
        if (size <= 0 || size > 4_000_000) return null
        val tag = ByteArray(size)
        readFully(ins, tag)
        var i = 0
        if (ver >= 3 && (h[5].toInt() and 0x40) != 0 && size > 6) i = (if (ver == 4) syncsafe(tag, 0) else be32(tag, 0) + 4).coerceIn(0, size)
        val hdr = if (ver == 2) 6 else 10
        var uslt: String? = null
        var sylt: String? = null
        while (i + hdr <= size) {
            val id = String(tag, i, if (ver == 2) 3 else 4, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val fsz = if (ver == 2) be24(tag, i + 3) else if (ver == 4) syncsafe(tag, i + 4) else be32(tag, i + 4)
            val s = i + hdr
            val e = s + fsz
            if (fsz <= 0 || e > size) break
            if (id == "USLT" || id == "ULT") uslt = parseUslt(tag, s, e)
            if (id == "SYLT" || id == "SLT") sylt = parseSylt(tag, s, e)
            i = e
        }
        return sylt?.let { Found(it, true) } ?: uslt?.let { Found(it, isLrc(it)) }
    }

    private fun atom(b: ByteArray, from: Int, to: Int, name: String): IntArray? {
        var p = from
        while (p + 8 <= to) {
            val sz = be32(b, p)
            if (sz < 8) return null
            val nm = String(b, p + 4, 4, Charsets.ISO_8859_1)
            if (nm == name) return intArrayOf(p + 8, minOf(to, p + sz))
            p += sz
        }
        return null
    }

    private fun mp4(c: Context, uri: Uri): Found? {
        c.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val len = ch.size()
            var pos = 0L
            val hb = ByteBuffer.allocate(16)
            while (pos + 8 <= len) {
                hb.clear(); ch.read(hb, pos)
                val a = hb.array()
                var sz = (be32(a, 0).toLong() and 0xFFFFFFFFL)
                val type = String(a, 4, 4, Charsets.ISO_8859_1)
                var head = 8
                if (sz == 1L) { sz = ByteBuffer.wrap(a, 8, 8).long; head = 16 } else if (sz == 0L) sz = len - pos
                if (sz < head) return null
                if (type == "moov") {
                    val n = (sz - head).toInt()
                    if (n <= 0 || n > 16_000_000) return null
                    val buf = ByteBuffer.allocate(n)
                    var rp = pos + head
                    while (buf.hasRemaining()) { val r = ch.read(buf, rp); if (r <= 0) break; rp += r }
                    val b = buf.array()
                    val udta = atom(b, 0, n, "udta") ?: return null
                    val meta = atom(b, udta[0], udta[1], "meta") ?: return null
                    val ilst = atom(b, meta[0] + 4, meta[1], "ilst") ?: return null
                    val lyr = atom(b, ilst[0], ilst[1], "\u00A9lyr") ?: return null
                    val data = atom(b, lyr[0], lyr[1], "data") ?: return null
                    val text = String(b, data[0] + 8, (data[1] - data[0] - 8).coerceAtLeast(0), Charsets.UTF_8).trim()
                    return if (text.isBlank()) null else Found(text, isLrc(text))
                }
                pos += sz
            }
        }
        return null
    }

    private fun flac(ins: InputStream): Found? {
        val m = ByteArray(4)
        if (readFully(ins, m) < 4 || String(m, Charsets.ISO_8859_1) != "fLaC") return null
        while (true) {
            val h = ByteArray(4)
            if (readFully(ins, h) < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = be24(h, 1)
            if (type == 4 && len in 8..4_000_000) {
                val b = ByteArray(len); readFully(ins, b)
                var p = 4 + le32(b, 0)
                if (p + 4 > len) return null
                val n = le32(b, p); p += 4
                for (k in 0 until n) {
                    if (p + 4 > len) break
                    val l = le32(b, p); p += 4
                    if (l < 0 || p + l > len) break
                    val kv = String(b, p, l, Charsets.UTF_8); p += l
                    val up = kv.substringBefore('=').uppercase().replace(" ", "")
                    if (up == "LYRICS" || up == "UNSYNCEDLYRICS" || up == "SYNCEDLYRICS") {
                        val text = kv.substringAfter('=').trim()
                        if (text.isNotBlank()) return Found(text, isLrc(text))
                    }
                }
                return null
            } else {
                var skip = len.toLong()
                while (skip > 0) { val r = ins.skip(skip); if (r <= 0) return null; skip -= r }
            }
            if (last) return null
        }
    }

    fun fromAudio(c: Context, uri: Uri, name: String): Found? = try {
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> c.contentResolver.openInputStream(uri)?.use { id3(it) }
            "m4a", "mp4", "aac" -> mp4(c, uri)
            "flac" -> c.contentResolver.openInputStream(uri)?.use { flac(it) }
            else -> null
        }
    } catch (e: Exception) { null }

    /** Recorre la musica guardada y guarda las letras que traen los archivos (una sola vez por archivo). */
    fun indexLibrary(c: Context) {
        if (running) return
        running = true; done = 0; found = 0; total = 0
        try {
            val base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val rows = ArrayList<Array<String>>()
            c.contentResolver.query(base, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { q ->
                while (q.moveToNext()) rows.add(arrayOf(q.getLong(0).toString(), q.getString(1) ?: "", q.getString(2) ?: "", q.getString(3) ?: ""))
            }
            total = rows.size
            for (r in rows) {
                val id = r[0].toLong()
                if (!Db.lyricSeen(c, id)) {
                    val artist = if (r[1] == "<unknown>") "" else r[1]
                    val f = fromAudio(c, android.content.ContentUris.withAppendedId(base, id), r[3])
                    if (f != null) {
                        Db.putLyrics(c, fkey(r[3]), f.text, "tag")
                        Db.putLyrics(c, key(artist, r[2]), f.text, "tag", r[2], artist)
                        found++
                    }
                    Db.markLyricSeen(c, id)
                }
                done++
            }
        } catch (e: Exception) { }
        running = false
    }
}
