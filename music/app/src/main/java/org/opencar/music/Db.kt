package org.opencar.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.MediaStore
import java.text.Normalizer

/** Base de datos local: historial de escuchas, caratulas, archivos sincronizados y pendientes ocultos. */
object Db {
    private class H(c: Context) : SQLiteOpenHelper(c, "music.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE plays(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, artist TEXT, album TEXT, dur INTEGER, pkg TEXT, kind TEXT, ts INTEGER, listened INTEGER)")
            db.execSQL("CREATE INDEX plays_ts ON plays(ts)")
            db.execSQL("CREATE TABLE covers(key TEXT PRIMARY KEY, path TEXT, ts INTEGER, ok INTEGER)")
            db.execSQL("CREATE TABLE synced(remote TEXT PRIMARY KEY, size INTEGER, ts INTEGER, uri TEXT)")
            db.execSQL("CREATE TABLE dismissed(key TEXT PRIMARY KEY, state TEXT)")
            v2(db)
        }
        private fun v2(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS lyrics(key TEXT PRIMARY KEY, text TEXT, synced INTEGER, src TEXT, title TEXT, artist TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS lyr_seen(id INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE IF NOT EXISTS free_dl(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, artist TEXT, license TEXT, url TEXT, source TEXT, ts INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) v2(db) }
    }

    private var h: H? = null
    @Synchronized fun db(c: Context): SQLiteDatabase {
        if (h == null) h = H(c.applicationContext)
        return h!!.writableDatabase
    }

    private val marks = Regex("\\p{M}+")
    private val junk = Regex("[^a-z0-9]+")
    fun norm(s: String?): String =
        Normalizer.normalize(s ?: "", Normalizer.Form.NFD).replace(marks, "").lowercase().replace(junk, " ").trim()

    class Play(val id: Long, val title: String, val artist: String, val album: String, val pkg: String, val kind: String,
               val ts: Long, val listened: Long)

    class Pending(val title: String, val artist: String, val count: Int, val lastTs: Long, val key: String)

    fun addPlay(c: Context, title: String, artist: String, album: String, dur: Long, pkg: String, kind: String, listened: Long) {
        val v = ContentValues().apply {
            put("title", title); put("artist", artist); put("album", album); put("dur", dur)
            put("pkg", pkg); put("kind", kind); put("ts", System.currentTimeMillis()); put("listened", listened)
        }
        db(c).insert("plays", null, v)
    }

    fun plays(c: Context, kind: String?, limit: Int): List<Play> {
        val out = ArrayList<Play>()
        val sel = if (kind == null) null else "kind=?"
        val args = if (kind == null) null else arrayOf(kind)
        db(c).query("plays", null, sel, args, null, null, "ts DESC", limit.toString()).use { q ->
            while (q.moveToNext()) out.add(Play(q.getLong(0), q.getString(1) ?: "", q.getString(2) ?: "", q.getString(3) ?: "",
                q.getString(5) ?: "", q.getString(6) ?: "", q.getLong(7), q.getLong(8)))
        }
        return out
    }

    fun countPlays(c: Context, sinceTs: Long = 0L): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM plays WHERE ts>=?", arrayOf(sinceTs.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearPlays(c: Context) { db(c).delete("plays", null, null) }

    /** Claves "titulo|artista" de la musica guardada en la pantalla. */
    fun localKeys(c: Context): Set<String> {
        val out = HashSet<String>()
        try {
            val base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            c.contentResolver.query(base, arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { q ->
                while (q.moveToNext()) {
                    val a = q.getString(1)
                    out.add(norm(q.getString(0)) + "|" + (if (a == null || a == "<unknown>") "" else norm(a)))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Canciones escuchadas por Bluetooth o apps que no estan guardadas en la pantalla (ni ocultadas por el usuario). */
    fun pending(c: Context): List<Pending> {
        val local = localKeys(c)
        val localTitles = local.map { it.substringBefore("|") + "|" }.toSet()
        val hidden = HashSet<String>()
        db(c).query("dismissed", arrayOf("key"), null, null, null, null, null).use { while (it.moveToNext()) hidden.add(it.getString(0)) }
        val map = LinkedHashMap<String, Pending>()
        db(c).query("plays", null, "kind<>?", arrayOf("local"), null, null, "ts DESC").use { q ->
            while (q.moveToNext()) {
                val t = q.getString(1) ?: ""; val a = q.getString(2) ?: ""
                val key = norm(t) + "|" + norm(a)
                if (key.startsWith("|") || hidden.contains(key) || local.contains(key)) continue
                // si en la pantalla el artista es desconocido, basta con que coincida el titulo
                if (localTitles.contains(norm(t) + "|") && local.contains(norm(t) + "|")) continue
                val old = map[key]
                map[key] = if (old == null) Pending(t, a, 1, q.getLong(7), key) else Pending(old.title, old.artist, old.count + 1, old.lastTs, key)
            }
        }
        return map.values.toList()
    }

    fun setDismissed(c: Context, key: String, state: String) {
        db(c).insertWithOnConflict("dismissed", null, ContentValues().apply { put("key", key); put("state", state) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- caratulas
    fun coverInfo(c: Context, key: String): Triple<String?, Boolean, Long>? =
        db(c).query("covers", arrayOf("path", "ok", "ts"), "key=?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) Triple(it.getString(0), it.getInt(1) == 1, it.getLong(2)) else null
        }

    fun setCover(c: Context, key: String, path: String?, ok: Boolean) {
        db(c).insertWithOnConflict("covers", null, ContentValues().apply {
            put("key", key); put("path", path); put("ts", System.currentTimeMillis()); put("ok", if (ok) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun countCovers(c: Context): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM covers WHERE ok=1", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---- sincronizacion
    fun isSynced(c: Context, remote: String): Boolean =
        db(c).query("synced", arrayOf("size"), "remote=?", arrayOf(remote), null, null, null).use { it.moveToFirst() }

    fun markSynced(c: Context, remote: String, size: Long, uri: String) {
        db(c).insertWithOnConflict("synced", null, ContentValues().apply {
            put("remote", remote); put("size", size); put("ts", System.currentTimeMillis()); put("uri", uri)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun countSynced(c: Context): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM synced", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---- letras
    fun putLyrics(c: Context, key: String, text: String, src: String, title: String = "", artist: String = "") {
        if (text.isBlank()) return
        db(c).insertWithOnConflict("lyrics", null, ContentValues().apply {
            put("key", key); put("text", text.take(60_000)); put("synced", if (Lyrics.isLrc(text)) 1 else 0); put("src", src); put("title", title); put("artist", artist)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getLyrics(c: Context, key: String): Pair<String, Boolean>? =
        db(c).query("lyrics", arrayOf("text", "synced"), "key=?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) Pair(it.getString(0) ?: "", it.getInt(1) == 1) else null
        }

    fun countLyrics(c: Context): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM lyrics WHERE key LIKE 'a|%'", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    class LyricItem(val title: String, val artist: String, val key: String)

    fun lyricList(c: Context, limit: Int): List<LyricItem> {
        val out = ArrayList<LyricItem>()
        db(c).query("lyrics", arrayOf("title", "artist", "key"), "key LIKE 'a|%' AND title<>''", null, null, null, "title COLLATE NOCASE ASC", limit.toString()).use {
            while (it.moveToNext()) out.add(LyricItem(it.getString(0) ?: "", it.getString(1) ?: "", it.getString(2)))
        }
        return out
    }

    fun lyricSeen(c: Context, id: Long): Boolean =
        db(c).query("lyr_seen", arrayOf("id"), "id=?", arrayOf(id.toString()), null, null, null).use { it.moveToFirst() }

    fun markLyricSeen(c: Context, id: Long) {
        db(c).insertWithOnConflict("lyr_seen", null, ContentValues().apply { put("id", id) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ---- musica libre (creditos)
    class Credit(val title: String, val artist: String, val license: String, val url: String, val ts: Long)

    fun addCredit(c: Context, title: String, artist: String, license: String, url: String, source: String) {
        db(c).insert("free_dl", null, ContentValues().apply {
            put("title", title); put("artist", artist); put("license", license); put("url", url); put("source", source); put("ts", System.currentTimeMillis())
        })
    }

    fun credits(c: Context): List<Credit> {
        val out = ArrayList<Credit>()
        db(c).query("free_dl", null, null, null, null, null, "ts DESC", "500").use {
            while (it.moveToNext()) out.add(Credit(it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "", it.getString(4) ?: "", it.getLong(6)))
        }
        return out
    }
}
