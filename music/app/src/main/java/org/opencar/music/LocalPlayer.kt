package org.opencar.music

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.max
import android.provider.MediaStore
import android.util.Size

/**
 * Reproductor de musica guardada (almacenamiento de la pantalla o USB).
 * Publica una sesion multimedia propia: el reproductor del launcher, la barra inferior y
 * cualquier otra app la ven igual que a Spotify o a la musica por Bluetooth.
 */
object LocalPlayer {

    class Track(val id: Long, val title: String, val artist: String, val album: String,
                val albumId: Long, val dur: Long, val uri: Uri, val name: String = "", val path: String = "", val added: Long = 0L) {
        val key: String get() = (artist + "|" + title).lowercase()
    }

    var songs: List<Track> = emptyList()
    @Volatile var loaded = false
    @Volatile var loading = false

    private var app: Context? = null
    private var session: MediaSession? = null
    private var mp: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var order: IntArray = IntArray(0)
    private var pos = -1
    var shuffle = false
        private set
    var repeat = 0          // 0 no, 1 todas, 2 una
        private set
    private var ready = false
    private var wantPlay = false
    private var resumeOnFocus = false
    private var art: Bitmap? = null
    private var artToken = 0
    private var errors = 0
    // --- mezcla entre canciones y efectos de audio
    private var chain: AudioFx.Chain? = null
    private var fading: MediaPlayer? = null
    private var fadingChain: AudioFx.Chain? = null
    private var fadeStart = 0L
    private var fadeMs = 0L
    private var duck = 1f
    private var autoTriggered = false
    private var notifKey: String? = null
    private val h = Handler(Looper.getMainLooper())

    /** Pista actual, posición y duración para la pantalla. */
    fun position(): Long = try { if (ready) (mp?.currentPosition ?: 0).toLong() else 0L } catch (e: Exception) { 0L }
    fun upcoming(): List<Track> = if (pos in order.indices) (pos + 1 until order.size).map { queue[order[it]] } else emptyList()
    fun queuePos(): Int = pos
    fun orderSize(): Int = order.size
    fun trackAt(orderPos: Int): Track? = order.getOrNull(orderPos)?.let { queue.getOrNull(it) }

    val current: Track? get() = if (pos in order.indices) queue.getOrNull(order[pos]) else null
    val hasQueue: Boolean get() = queue.isNotEmpty()
    val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (e: Exception) { false }

    private var ctrl: android.media.session.MediaController? = null

    /** Controlador de la sesion propia (sirve aunque no se haya dado acceso a notificaciones). */
    fun controller(): android.media.session.MediaController? = if (session?.isActive == true) ctrl else null

    fun init(ctx: Context) {
        if (session != null) return
        val c = ctx.applicationContext
        app = c
        AudioFx.init(c)
        val s = MediaSession(c, "OpenCarMusic")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { play() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next(false) }
            override fun onSkipToPrevious() { prev() }
            override fun onSeekTo(p: Long) { seek(p) }
            override fun onStop() { stop() }
            override fun onSkipToQueueItem(id: Long) { jumpTo(id.toInt()) }
            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) { "shuffle" -> toggleShuffle(); "repeat" -> cycleRepeat() }
            }
        }, h)
        session = s
        ctrl = android.media.session.MediaController(c, s.sessionToken)
    }

    // ---------------------------------------------------------------- biblioteca

    fun scan(ctx: Context) {
        loading = true
        val out = ArrayList<Track>()
        try {
            val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            ctx.contentResolver.query(
                base,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_ADDED),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 20000",
                null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    fun clean(s: String?, d: String) = if (s.isNullOrBlank() || s == "<unknown>") d else s
                    out.add(Track(id, clean(c.getString(1), "Sin título"), clean(c.getString(2), "Artista desconocido"),
                        clean(c.getString(3), "Álbum desconocido"), c.getLong(4), c.getLong(5),
                        ContentUris.withAppendedId(base, id), c.getString(6) ?: "", c.getString(7) ?: "", c.getLong(8)))
                }
            }
        } catch (e: Exception) { }
        songs = out
        loaded = true
        loading = false
    }

    // ---------------------------------------------------------------- reproduccion

    fun playQueue(list: List<Track>, start: Int, shuf: Boolean) {
        if (list.isEmpty()) return
        queue = list
        shuffle = shuf
        buildOrder(start.coerceIn(0, list.size - 1))
        errors = 0
        startCurrent()
    }

    private fun buildOrder(first: Int) {
        val idx = (0 until queue.size).toMutableList()
        if (shuffle) {
            idx.remove(first); idx.shuffle(); idx.add(0, first)
            order = idx.toIntArray(); pos = 0
        } else {
            order = idx.toIntArray(); pos = first
        }
    }

    private fun release() {
        try { mp?.release() } catch (e: Exception) { }
        AudioFx.detach(chain); chain = null
        mp = null
        ready = false
    }

    private fun dropFading() {
        h.removeCallbacks(fadeTick)
        try { fading?.release() } catch (e: Exception) { }
        AudioFx.detach(fadingChain)
        fading = null; fadingChain = null
        try { mp?.setVolume(duck, duck) } catch (e: Exception) { }
    }

    private val fadeTick = object : Runnable {
        override fun run() {
            val m = mp ?: return
            if (fading == null) return
            val t = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / max(1L, fadeMs)).coerceIn(0f, 1f)
            val vin = Math.sin(t * Math.PI / 2).toFloat() * duck
            val vout = Math.cos(t * Math.PI / 2).toFloat() * duck
            try { m.setVolume(vin, vin) } catch (e: Exception) { }
            try { fading?.setVolume(vout, vout) } catch (e: Exception) { }
            if (t >= 1f) dropFading() else h.postDelayed(this, 40)
        }
    }

    /** Vigila el final de la canción para empezar la mezcla con la siguiente (modo DJ automático). */
    private val watch = object : Runnable {
        override fun run() {
            try {
                val m = mp
                val x = AudioFx.xfadeMs
                if (m != null && ready && m.isPlaying && fading == null && x > 0 && repeat != 2 && !autoTriggered) {
                    val dur = m.duration
                    val remain = dur - m.currentPosition
                    val xx = minOf(x, (dur / 3).toLong())
                    if (dur > 0 && remain <= xx + 150 && (pos + 1 < order.size || repeat == 1)) { autoTriggered = true; next(true) }
                }
            } catch (e: Exception) { }
            if (wantPlay) h.postDelayed(this, 300)
        }
    }

    private fun startCurrent(xf: Boolean = false) {
        val c = app ?: return
        val t = current ?: return
        autoTriggered = false
        val old = mp
        val canFade = xf && AudioFx.xfadeMs > 0 && ready && (try { old?.isPlaying == true } catch (e: Exception) { false })
        dropFading()
        if (canFade && old != null) {
            fading = old; fadingChain = chain
            try { old.setOnCompletionListener(null); old.setOnErrorListener(null); old.setOnPreparedListener(null) } catch (e: Exception) { }
            mp = null; chain = null; ready = false
        } else release()
        session?.isActive = true
        art = null
        wantPlay = true
        requestFocus()
        try {
            val m = MediaPlayer()
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            try { m.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK) } catch (e: Exception) { }
            m.setDataSource(c, t.uri)
            chain = AudioFx.attach(m)
            m.setOnPreparedListener {
                ready = true; errors = 0
                if (wantPlay) {
                    if (fading != null) {
                        fadeStart = SystemClock.elapsedRealtime(); fadeMs = AudioFx.xfadeMs
                        try { it.setVolume(0f, 0f) } catch (e: Exception) { }
                        it.start(); h.removeCallbacks(fadeTick); h.post(fadeTick)
                    } else { try { it.setVolume(duck, duck) } catch (e: Exception) { }; it.start() }
                    h.removeCallbacks(watch); h.postDelayed(watch, 300)
                }
                publish()
            }
            m.setOnCompletionListener { onComplete() }
            m.setOnErrorListener { _, _, _ -> release(); h.post { failed() }; true }
            mp = m
            m.prepareAsync()
        } catch (e: Exception) {
            h.post { failed() }
        }
        loadArt(t)
        publish()
        notifyService()
    }

    private fun failed() {
        errors++
        if (errors > 5) { stop(); return }
        next(true)
    }

    private fun onComplete() {
        if (repeat == 2) { startCurrent(); return }
        next(true)
    }

    fun play() {
        if (!hasQueue) return
        if (mp == null) { startCurrent(); return }
        wantPlay = true
        requestFocus()
        if (ready) try { mp?.start() } catch (e: Exception) { }
        h.removeCallbacks(watch); h.postDelayed(watch, 300)
        publish()
    }

    fun pause() {
        wantPlay = false
        h.removeCallbacks(watch)
        dropFading()
        try { if (ready) mp?.pause() } catch (e: Exception) { }
        publish()
    }

    fun toggle() { if (isPlaying || (wantPlay && !ready)) pause() else play() }

    fun seek(p: Long) {
        try { if (ready) mp?.seekTo(p.toInt()) } catch (e: Exception) { }
        publish()
    }

    fun next(auto: Boolean) {
        if (queue.isEmpty()) return
        val xf = auto || AudioFx.xfadeManual
        if (pos + 1 < order.size) { pos++; startCurrent(xf); return }
        if (repeat == 1 || !auto) {
            if (shuffle) buildOrder((0 until queue.size).random()) else pos = 0
            startCurrent(xf)
        } else {
            // fin de la lista: se detiene en la ultima cancion
            wantPlay = false
            try { mp?.seekTo(0); mp?.pause() } catch (e: Exception) { }
            publish()
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        val p = try { if (ready) mp?.currentPosition ?: 0 else 0 } catch (e: Exception) { 0 }
        if (p > 3000 || pos == 0) seek(0) else { pos--; startCurrent(AudioFx.xfadeManual) }
    }

    fun jumpTo(orderPos: Int) {
        if (orderPos in order.indices) { pos = orderPos; startCurrent(AudioFx.xfadeManual) }
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        val cur = if (pos in order.indices) order[pos] else 0
        if (queue.isNotEmpty()) buildOrder(cur)
        publish()
    }

    fun cycleRepeat() { repeat = (repeat + 1) % 3; publish() }

    fun stop() {
        h.removeCallbacks(watch)
        dropFading()
        release()
        wantPlay = false
        queue = emptyList(); order = IntArray(0); pos = -1
        session?.isActive = false
        try { app?.stopService(Intent(app, MusicService::class.java)) } catch (e: Exception) { }
        notifKey = null
    }

    // ---------------------------------------------------------------- enfoque de audio

    private val focusL = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying) { resumeOnFocus = true; try { mp?.pause() } catch (e: Exception) { }; publish() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = 0.3f; try { if (fading == null) mp?.setVolume(0.3f, 0.3f) } catch (e: Exception) { } }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                try { if (fading == null) mp?.setVolume(1f, 1f) } catch (e: Exception) { }
                if (resumeOnFocus) { resumeOnFocus = false; try { mp?.start() } catch (e: Exception) { }; publish() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        try {
            (app?.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- caratula y sesion

    private fun loadArt(t: Track) {
        val token = ++artToken
        val c = app ?: return
        Thread {
            var b: Bitmap? = null
            try { if (Build.VERSION.SDK_INT >= 29) b = c.contentResolver.loadThumbnail(t.uri, Size(600, 600), null) } catch (e: Exception) { }
            if (b == null) try {
                val r = MediaMetadataRetriever()
                r.setDataSource(c, t.uri)
                r.embeddedPicture?.let { bytes ->
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
                r.release()
            } catch (e: Exception) { }
            if (b == null) try {
                val ar = if (t.artist == "Artista desconocido") "" else t.artist
                val al = if (t.album == "Álbum desconocido") "" else t.album
                val u = Uri.parse("content://org.opencar.music.art/cover?artist=${Uri.encode(ar)}&album=${Uri.encode(al)}&title=${Uri.encode(t.title)}")
                c.contentResolver.openInputStream(u)?.use { b = BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { }
            h.post { if (token == artToken) { art = b; publish() } }
        }.start()
    }

    private fun notifyService() {
        val c = app ?: return
        val k = current?.id?.toString() ?: return
        if (k == notifKey) return
        notifKey = k
        try {
            val i = Intent(c, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        } catch (e: Exception) { }
    }

    private fun publish() {
        val s = session ?: return
        val t = current
        val b = MediaMetadata.Builder()
        if (t != null) {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.dur)
            art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        }
        s.setMetadata(b.build())
        val playing = isPlaying
        val p = try { if (ready) (mp?.currentPosition ?: 0).toLong() else 0L } catch (e: Exception) { 0L }
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            wantPlay && !ready -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        val st = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackState.ACTION_STOP)
            .setState(state, p, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
            .setActiveQueueItemId(pos.toLong())
            .addCustomAction("shuffle", "Aleatorio: ${if (shuffle) "sí" else "no"}", R.drawable.ic_shuffle)
            .addCustomAction("repeat", "Repetir: ${listOf("no", "todas", "una")[repeat]}", R.drawable.ic_repeat)
            .build()
        s.setPlaybackState(st)
        // cola: ventana de 100 pistas para no saturar el sistema
        if (pos in order.indices) {
            val items = ArrayList<MediaSession.QueueItem>()
            for (i in pos until minOf(order.size, pos + 100)) {
                val tr = queue[order[i]]
                items.add(MediaSession.QueueItem(
                    MediaDescription.Builder().setMediaId(tr.id.toString()).setTitle(tr.title).setSubtitle(tr.artist).build(), i.toLong()))
            }
            s.setQueue(items)
        } else s.setQueue(null)
    }
}
