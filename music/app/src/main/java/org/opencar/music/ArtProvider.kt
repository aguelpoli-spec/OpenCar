package org.opencar.music

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Entrega al launcher la caratula de una cancion:
 * content://org.opencar.music.art/cover?artist=..&album=..&title=..
 * Si aun no esta, la pide en segundo plano y responde "no encontrada" (el launcher reintenta).
 */
class ArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: throw FileNotFoundException()
        val artist = uri.getQueryParameter("artist") ?: ""
        val album = uri.getQueryParameter("album") ?: ""
        val title = uri.getQueryParameter("title") ?: ""
        val f = Covers.lookup(c, artist, album, title)
        if (f == null) {
            Covers.requestAsync(c, artist, album, title)
            throw FileNotFoundException("sin caratula todavia")
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** content://org.opencar.music.art/lyrics?artist=..&title=..&file=..  ->  columnas (text, synced) */
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        if (u.lastPathSegment != "lyrics") return null
        val c = context ?: return null
        val f = Lyrics.lookup(c, u.getQueryParameter("artist") ?: "", u.getQueryParameter("title") ?: "", u.getQueryParameter("file") ?: "")
        val cur = MatrixCursor(arrayOf("text", "synced"))
        if (f != null) cur.addRow(arrayOf<Any>(f.text, if (f.synced) 1 else 0))
        return cur
    }
    override fun getType(uri: Uri): String? = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
