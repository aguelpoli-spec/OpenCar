package org.opencar.music

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Historial de escuchas: registra titulo, artista, app/fuente y hora de lo que suena
 * (Bluetooth, apps o la musica local del launcher). Una escucha cuenta si suena 20 s o la mitad.
 */
class HistoryService : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private val trackers = HashMap<String, Tracker>()
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    override fun onListenerConnected() {
        try {
            val m = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            val cn = ComponentName(this, HistoryService::class.java)
            m.addOnActiveSessionsChangedListener(listener, cn)
            attach(m.getActiveSessions(cn))
            Scheduler.setup(this)
        } catch (e: Exception) { }
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(listener) } catch (e: Exception) { }
        trackers.values.forEach { it.detach() }
        trackers.clear()
    }

    private fun idOf(c: MediaController) = c.packageName + "#" + c.sessionToken.hashCode()

    private fun attach(list: List<MediaController>) {
        val ids = list.map { idOf(it) }.toSet()
        trackers.keys.filter { it !in ids }.forEach { trackers.remove(it)?.detach() }
        for (c in list) if (!trackers.containsKey(idOf(c))) trackers[idOf(c)] = Tracker(c)
    }

    private fun kindOf(pkg: String): String {
        if (pkg == "org.opencar.launcher") return "local"
        val p = pkg.lowercase()
        if (p.contains("radio")) return "radio"
        if (p.contains("bluetooth") || p.contains("btmusic") || p.contains("a2dp")) return "bt"
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
        if (label.contains("radio", true)) return "radio"
        if (label.contains("bluetooth", true) || Regex("\\bbt\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)) return "bt"
        return "app"
    }

    private inner class Tracker(val c: MediaController) : MediaController.Callback() {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var dur = 0L
        var acc = 0L
        var since = 0L

        init {
            c.registerCallback(this)
            onMetadataChanged(c.metadata)
            onPlaybackStateChanged(c.playbackState)
        }

        private fun playing() = c.playbackState?.state == PlaybackState.STATE_PLAYING

        override fun onMetadataChanged(md: MediaMetadata?) {
            val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val a = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            if (t == title && a == artist) return
            finish()
            title = t; artist = a; album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            acc = 0L
            since = if (playing()) SystemClock.elapsedRealtime() else 0L
        }

        override fun onPlaybackStateChanged(st: PlaybackState?) {
            val now = SystemClock.elapsedRealtime()
            val pl = st?.state == PlaybackState.STATE_PLAYING
            if (pl && since == 0L) since = now
            else if (!pl && since != 0L) { acc += now - since; since = 0L }
        }

        override fun onSessionDestroyed() { finish(); title = null; artist = null }

        fun finish() {
            val now = SystemClock.elapsedRealtime()
            if (since != 0L) { acc += now - since; since = 0L }
            val t = title
            if (!t.isNullOrBlank() && (acc >= 20_000L || (dur > 0 && acc >= dur / 2))) {
                try { Db.addPlay(this@HistoryService, t, artist ?: "", album ?: "", dur, c.packageName, kindOf(c.packageName), acc) } catch (e: Exception) { }
            }
            acc = 0L
        }

        fun detach() {
            finish()
            try { c.unregisterCallback(this) } catch (e: Exception) { }
        }
    }
}
