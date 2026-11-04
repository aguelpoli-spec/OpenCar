package org.opencar.music

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Ecualizador y mezcla del reproductor propio: ecualizador de bandas, graves, espacial,
 * volumen extra y mezcla entre canciones (crossfade). Los ajustes se guardan solos.
 */
object AudioFx {
    private var prefs: SharedPreferences? = null

    // datos reales del equipo (se leen una vez)
    var bands = 0; private set
    var minMb = -1500; private set
    var maxMb = 1500; private set
    var freqs = IntArray(0); private set
    var supported = false; private set

    /** Curvas en dB sobre 10 frecuencias (31 Hz ... 16 kHz); se adaptan a las bandas del equipo. */
    val presets: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Plano" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Graves" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Rock" to floatArrayOf(4f, 3f, 2f, -1f, -2f, -1f, 2f, 3f, 4f, 4f),
        "Pop" to floatArrayOf(-1f, 0f, 2f, 3f, 3f, 2f, 0f, -1f, -1f, -2f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f),
        "Clásica" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Vocal" to floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
        "Electrónica" to floatArrayOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 3f, 5f, 5f),
        "Hip-Hop" to floatArrayOf(5f, 4f, 3f, 2f, -1f, -1f, 1f, -1f, 2f, 3f),
        "Carretera" to floatArrayOf(4f, 3f, 2f, 0f, 0f, 1f, 2f, 3f, 3f, 2f)
    )
    private val presetHz = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    class Chain(val mp: MediaPlayer) {
        var eq: Equalizer? = null
        var bass: BassBoost? = null
        var virt: Virtualizer? = null
        var loud: LoudnessEnhancer? = null
        fun release() {
            try { eq?.release() } catch (e: Exception) { }
            try { bass?.release() } catch (e: Exception) { }
            try { virt?.release() } catch (e: Exception) { }
            try { loud?.release() } catch (e: Exception) { }
            eq = null; bass = null; virt = null; loud = null
        }
    }

    private val chains = ArrayList<Chain>()
    private var globalEq: Equalizer? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)
        if (!supported) probe()
    }

    private fun probe() {
        try {
            val mp = MediaPlayer()
            val e = Equalizer(0, mp.audioSessionId)
            bands = e.numberOfBands.toInt()
            val r = e.bandLevelRange
            minMb = r[0].toInt(); maxMb = r[1].toInt()
            freqs = IntArray(bands) { e.getCenterFreq(it.toShort()) / 1000 }
            e.release(); mp.release()
            supported = bands > 0
        } catch (ex: Throwable) { supported = false }
    }

    // ---- ajustes guardados
    var eqOn: Boolean
        get() = prefs?.getBoolean("eq_on", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("eq_on", v)?.apply(); applyAll() }
    val preset: String get() = prefs?.getString("eq_preset", "Plano") ?: "Plano"
    private fun savePreset(v: String) { prefs?.edit()?.putString("eq_preset", v)?.apply() }
    var bassStrength: Int
        get() = prefs?.getInt("eq_bass", 0) ?: 0
        set(v) { prefs?.edit()?.putInt("eq_bass", v)?.apply(); applyAll() }
    var virtStrength: Int
        get() = prefs?.getInt("eq_virt", 0) ?: 0
        set(v) { prefs?.edit()?.putInt("eq_virt", v)?.apply(); applyAll() }
    var gainMb: Int
        get() = prefs?.getInt("eq_gain", 0) ?: 0
        set(v) { prefs?.edit()?.putInt("eq_gain", v)?.apply(); applyAll() }
    var xfadeSec: Int
        get() = prefs?.getInt("xf_s", 5) ?: 5
        set(v) { prefs?.edit()?.putInt("xf_s", v)?.apply() }
    val xfadeMs: Long get() = xfadeSec * 1000L
    var xfadeManual: Boolean
        get() = prefs?.getBoolean("xf_manual", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("xf_manual", v)?.apply() }
    var global: Boolean
        get() = prefs?.getBoolean("eq_global", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("eq_global", v)?.apply(); applyAll() }

    /** Niveles actuales (mB) por banda del equipo. */
    fun levels(): IntArray {
        val saved = prefs?.getString("eq_levels", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (saved != null && saved.size == bands) return saved.toIntArray()
        return fromPreset(preset)
    }

    private fun fromPreset(name: String): IntArray {
        val curve = presets[name] ?: presets["Plano"]!!
        return IntArray(bands) { b ->
            val f = max(20, freqs.getOrElse(b) { 1000 }).toDouble()
            // interpolación en escala logarítmica entre las 10 frecuencias de la curva
            var db = curve[curve.size - 1].toDouble()
            if (f <= presetHz[0]) db = curve[0].toDouble()
            else for (i in 0 until presetHz.size - 1) {
                if (f <= presetHz[i + 1]) {
                    val t = (ln(f) - ln(presetHz[i].toDouble())) / (ln(presetHz[i + 1].toDouble()) - ln(presetHz[i].toDouble()))
                    db = curve[i] + (curve[i + 1] - curve[i]) * t; break
                }
            }
            (db * 100).toInt().coerceIn(minMb, maxMb)
        }
    }

    fun setPreset(name: String) {
        savePreset(name)
        prefs?.edit()?.putString("eq_levels", fromPreset(name).joinToString(","))?.apply()
        applyAll()
    }

    fun setBand(i: Int, mb: Int) {
        val l = levels(); if (i !in l.indices) return
        l[i] = mb.coerceIn(minMb, maxMb)
        prefs?.edit()?.putString("eq_levels", l.joinToString(","))?.apply()
        savePreset("Personalizado")
        applyAll()
    }

    fun reset() {
        prefs?.edit()?.putInt("eq_bass", 0)?.putInt("eq_virt", 0)?.putInt("eq_gain", 0)?.apply()
        setPreset("Plano")
    }

    // ---- enganche a cada reproductor
    fun attach(mp: MediaPlayer): Chain {
        val c = Chain(mp)
        val sid = try { mp.audioSessionId } catch (e: Exception) { 0 }
        try { c.eq = Equalizer(0, sid) } catch (e: Throwable) { }
        try { c.bass = BassBoost(0, sid) } catch (e: Throwable) { }
        try { c.virt = Virtualizer(0, sid) } catch (e: Throwable) { }
        try { c.loud = LoudnessEnhancer(sid) } catch (e: Throwable) { }
        synchronized(chains) { chains.add(c) }
        applyChain(c)
        return c
    }

    fun detach(c: Chain?) {
        if (c == null) return
        synchronized(chains) { chains.remove(c) }
        c.release()
    }

    private fun applyChain(c: Chain) {
        val on = eqOn
        val l = levels()
        try {
            c.eq?.let { e ->
                e.enabled = on
                for (i in 0 until min(l.size, e.numberOfBands.toInt())) e.setBandLevel(i.toShort(), l[i].toShort())
            }
        } catch (e: Throwable) { }
        try { c.bass?.let { b -> if (b.strengthSupported) b.setStrength(bassStrength.toShort()); b.enabled = on && bassStrength > 0 } } catch (e: Throwable) { }
        try { c.virt?.let { v -> if (v.strengthSupported) v.setStrength(virtStrength.toShort()); v.enabled = on && virtStrength > 0 } } catch (e: Throwable) { }
        try { c.loud?.let { x -> x.setTargetGain(gainMb); x.enabled = on && gainMb > 0 } } catch (e: Throwable) { }
    }

    fun applyAll() {
        synchronized(chains) { chains.forEach { applyChain(it) } }
        // experimental: efecto sobre toda la salida de audio (depende del equipo)
        try {
            if (global && eqOn) {
                if (globalEq == null) globalEq = Equalizer(0, 0)
                val e = globalEq!!
                val l = levels()
                e.enabled = true
                for (i in 0 until min(l.size, e.numberOfBands.toInt())) e.setBandLevel(i.toShort(), l[i].toShort())
            } else { globalEq?.release(); globalEq = null }
        } catch (e: Throwable) { globalEq = null }
    }
}
