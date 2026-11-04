package org.opencar.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Frases fijas del asistente y de la navegación.
 * Si existe una grabación propia (res/raw/voz_<clave>.mp3) suena esa, sin internet; si no, habla la voz del sistema (TTS).
 * Así cualquiera puede grabar su propia voz sin tocar el código.
 */
object VozClips {
    private var mp: MediaPlayer? = null
    private var queue: ArrayDeque<String> = ArrayDeque()
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Texto de respaldo para cada clave. */
    private val TEXT = mapOf(
        "maps_ready" to "El mapa está listo", "fuel_none" to "No hay estaciones de servicio guardadas cerca",
        "fuel_gps" to "Aún no tengo señal de GPS", "fuel_ask" to "Estas son las más cercanas. ¿A cuál quieres ir?",
        "fuel_filled" to "¿Cargaste gasolina?", "fuel_which" to "No te entendí. ¿Cuál estación?",
        "fuel_cancel" to "Listo, cancelado", "fuel_go" to "Te guío a la estación", "fuel_amount" to "¿Cuánto pagaste?",
        "nav_calc" to "Calculando la ruta", "nav_start" to "Comenzamos", "arrived" to "Llegaste a tu destino",
        "recalc" to "Recalculando", "dist_500" to "En quinientos metros", "dist_200" to "En doscientos metros",
        "turn_left" to "gira a la izquierda", "turn_right" to "gira a la derecha",
        "slight_left" to "mantente a la izquierda", "slight_right" to "mantente a la derecha", "uturn" to "da la vuelta",
        "rb1" to "en la rotonda toma la primera salida", "rb2" to "en la rotonda toma la segunda salida",
        "rb3" to "en la rotonda toma la tercera salida", "rb4" to "en la rotonda toma la cuarta salida",
        "pause" to "Música en pausa", "next" to "Siguiente canción", "vol_up" to "Subí el volumen", "vol_down" to "Bajé el volumen",
        "drive_mode" to "Modo manejo", "big_clock" to "Reloj grande", "ok" to "Reproduciendo", "not_understood" to "No te entendí",
        "dest_casa" to "Navegando a casa", "dest_trabajo" to "Navegando al trabajo", "dest_taller" to "Te guío al taller",
        "hello_conductor" to "Listo, ya conozco tu voz", "hello_acompanante" to "Listo, ya conozco tu voz", "done" to "Voz lista"
    )

    /** Voz grabada incluida (Adriana). Se puede apagar en Asistente para que hable la voz del sistema. */
    fun clipsOn(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("voz_clips", true)
    fun has(ctx: Context, key: String) = clipsOn(ctx) && ctx.resources.getIdentifier("voz_$key", "raw", ctx.packageName) != 0

    /** Reproduce una o varias frases seguidas (p. ej. dist_500 + turn_left). false si alguna no se puede decir. */
    fun play(ctx: Context, vararg keys: String, onDone: (() -> Unit)? = null): Boolean {
        val a = ctx.applicationContext
        if (keys.isEmpty()) return false
        val allClips = keys.all { has(a, it) }
        val allText = keys.all { it in TEXT }
        if (!allClips && !allText) return false
        stop()
        app = a
        if (allClips) { queue = ArrayDeque(keys.toList()); next(onDone) }
        else speak(a, keys.joinToString(". ") { TEXT[it]!! }, onDone)
        return true
    }

    private fun speak(a: Context, text: String, onDone: (() -> Unit)?) {
        val go = {
            val id = "vc" + System.nanoTime()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) {}
                override fun onDone(u: String?) { if (u == id) onDone?.let { android.os.Handler(a.mainLooper).post(it) } }
                @Deprecated("x") override fun onError(u: String?) { if (u == id) onDone?.let { android.os.Handler(a.mainLooper).post(it) } }
            })
            val b = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC) }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, b, id)
        }
        if (ttsReady && tts != null) { go(); return }
        tts = TextToSpeech(a) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.getDefault().takeIf { it.language == "es" } ?: Locale("es"); ttsReady = true; go() }
            else onDone?.let { android.os.Handler(a.mainLooper).post(it) }
        }
    }

    private fun next(onDone: (() -> Unit)?) {
        val a = app ?: return
        val key = queue.removeFirstOrNull() ?: run { onDone?.invoke(); return }
        val id = a.resources.getIdentifier("voz_$key", "raw", a.packageName)
        val p = try {
            val m = MediaPlayer()
            a.resources.openRawResourceFd(id).use { m.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            m.prepare(); m
        } catch (e: Exception) { null }
        if (p == null) { next(onDone); return }
        p.setOnCompletionListener { it.release(); if (mp === it) mp = null; next(onDone) }
        mp = p
        p.start()
    }

    fun stop() {
        queue.clear()
        try { mp?.stop() } catch (e: Exception) { }
        try { mp?.release() } catch (e: Exception) { }
        mp = null
        try { tts?.stop() } catch (e: Exception) { }
    }
}
