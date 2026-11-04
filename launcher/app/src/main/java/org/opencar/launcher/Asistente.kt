package org.opencar.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/** Modelos de voz sin internet (Vosk): reconocer lo que dices y saber quién habla. Se descargan una vez. */
object VoskModels {
    const val ES_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    @Volatile var msg = ""
    @Volatile var running = false
    @Volatile var pct = 0

    fun root(c: Context) = File(c.filesDir, "vosk").also { it.mkdirs() }
    fun esDir(c: Context) = File(root(c), "es")
    fun spkDir(c: Context) = File(root(c), "spk")
    fun ready(c: Context) = File(esDir(c), ".ok").exists() && File(spkDir(c), ".ok").exists()

    private fun fetchZip(c: Context, url: String, dst: File, label: String, base: Int, span: Int) {
        if (File(dst, ".ok").exists()) return
        dst.deleteRecursively(); dst.mkdirs()
        var u = url
        var cn: HttpURLConnection
        var tries = 0
        while (true) {
            cn = URL(u).openConnection() as HttpURLConnection
            cn.connectTimeout = 20000; cn.readTimeout = 40000; cn.instanceFollowRedirects = true
            cn.setRequestProperty("User-Agent", MapData.UA)
            if (cn.responseCode == 200) break
            if (++tries > 3) throw java.io.IOException("HTTP ${cn.responseCode}")
            Thread.sleep(1500)
        }
        val total = cn.contentLengthLong.coerceAtLeast(1)
        var read = 0L
        val counting = object : java.io.FilterInputStream(cn.inputStream) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) { read += n; pct = base + (read * span / total).toInt(); msg = "$label · $pct %" }; return n }
        }
        ZipInputStream(counting).use { z ->
            var e = z.nextEntry
            while (e != null) {
                // se quita la carpeta raíz del zip para dejar los archivos directo en dst
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val f = File(dst, rel)
                    if (!f.canonicalPath.startsWith(dst.canonicalPath)) throw java.io.IOException("zip inválido")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> z.copyTo(o) } }
                }
                e = z.nextEntry
            }
        }
        File(dst, ".ok").writeText("ok")
    }

    fun download(c: Context, done: (String?) -> Unit) {
        if (running) return
        running = true; pct = 0
        Thread {
            var err: String? = null
            try {
                fetchZip(c, ES_URL, esDir(c), "Voz en español", 0, 75)
                fetchZip(c, SPK_URL, spkDir(c), "Reconocer quién habla", 75, 25)
                msg = "Listo"
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName; msg = "Error: $err" }
            running = false
            Handler(Looper.getMainLooper()).post { done(err) }
        }.start()
    }
}

/** Huellas de voz del conductor y del acompañante (promedio de lo que se ha oído). */
object SpeakerDb {
    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    val NAMES = listOf("conductor", "acompanante")

    private fun load(c: Context, who: String): Pair<FloatArray, Int>? {
        val s = prefs(c).getString("spk_$who", null) ?: return null
        return try { val o = JSONObject(s); val a = o.getJSONArray("v"); Pair(FloatArray(a.length()) { a.getDouble(it).toFloat() }, o.getInt("n")) } catch (e: Exception) { null }
    }
    fun has(c: Context, who: String) = load(c, who) != null
    fun any(c: Context) = NAMES.any { has(c, it) }
    fun clear(c: Context) { val e = prefs(c).edit(); NAMES.forEach { e.remove("spk_$it") }; e.apply() }

    fun learn(c: Context, who: String, v: FloatArray) {
        val old = load(c, who)
        val nv = if (old == null) v.copyOf() else FloatArray(v.size) { (old.first[it] * old.second + v[it]) / (old.second + 1) }
        val n = (old?.second ?: 0) + 1
        val a = JSONArray(); nv.forEach { a.put(it.toDouble()) }
        prefs(c).edit().putString("spk_$who", JSONObject().put("v", a).put("n", minOf(n, 12)).toString()).apply()
    }

    private fun cos(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return 1.0 - d / (sqrt(na) * sqrt(nb) + 1e-9)
    }

    /** Devuelve (nombre, distancia) del más parecido, o null si todavía no hay huellas. */
    fun identify(c: Context, v: FloatArray): Pair<String, Double>? {
        var best: Pair<String, Double>? = null
        for (n in NAMES) { val p = load(c, n) ?: continue; if (p.first.size != v.size) continue; val d = cos(p.first, v); if (best == null || d < best.second) best = Pair(n, d) }
        return best
    }
}

/** Escucha «Hey <marca de tu auto>» sin internet, sabe quién habla y manda la orden al launcher. */
class AsistenteService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var status = "apagado"
        @Volatile var enroll: String? = null
        /** Mientras dura, lo que se diga (sin la palabra de activación) se entrega a choiceCb (p. ej. elegir una estación). */
        @Volatile var choiceUntil = 0L
        @Volatile var choiceCb: ((String) -> Boolean)? = null
        @Volatile var enrollLeft = 0
        private val PRE = setOf("hey", "ey", "ei", "hay", "oye", "oie", "hola", "ok", "okay", "he", "ea", "e", "eh")
        /** Palabra de activación en minúsculas y sin acentos (la marca del auto; «auto» si no se eligió). */
        @Volatile var wake = "auto"
        fun applyWake(w: String) { val n = norm(w).replace(" ", ""); if (n.length >= 3) wake = n }

        fun start(c: Context) { try { c.startForegroundService(Intent(c, AsistenteService::class.java)) } catch (e: Exception) { } }
        fun stop(c: Context) { try { c.stopService(Intent(c, AsistenteService::class.java)) } catch (e: Exception) { } }

        fun lev(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) { var prev = dp[0]; dp[0] = i; for (j in 1..b.length) { val t = dp[j]; dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t } }
            return dp[b.length]
        }
        fun wakeish(w: String) = w == wake || (wake.length >= 4 && lev(w, wake) <= 1) || (wake.length >= 7 && lev(w, wake) <= 2)
        fun norm(x: String): String = Normalizer.normalize(x.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Si en la frase está «hey <marca>», devuelve lo que se dijo después (puede ser vacío). */
        fun parseWake(t: String): String? {
            val toks = t.split(" ").filter { it.isNotEmpty() }
            for (i in toks.indices) {
                val one = wakeish(toks[i])
                val two = !one && i + 1 < toks.size && wakeish(toks[i] + toks[i + 1])
                if (!one && !two) continue
                if (i != 0 && toks[i - 1] !in PRE) continue
                return toks.drop(i + if (two) 2 else 1).joinToString(" ")
            }
            return null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var spk: SpeakerModel? = null
    private var rec: Recognizer? = null
    private var svc: SpeechService? = null
    private var awaitingUntil = 0L
    private var askingUntil = 0L
    private var awaitWho: String? = null
    private var enrollVecs = ArrayList<FloatArray>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyWake(CarCatalog.wakeWord(this, getSharedPreferences("launcher", MODE_PRIVATE)))
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("asistente", "Asistente de voz", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "asistente").setSmallIcon(R.drawable.ic_mic).setContentTitle("Asistente activo").setContentText("Di «Hey ${wake.replaceFirstChar { it.uppercase() }}» y tu orden").setOngoing(true).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(78, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(78, n)
        } catch (e: Exception) { status = "sin permiso de micrófono"; stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true; status = "cargando voz…"
        Thread {
            try {
                if (!VoskModels.ready(this)) { status = "falta descargar la voz"; running = false; stopSelf(); return@Thread }
                model = Model(VoskModels.esDir(this).absolutePath)
                spk = SpeakerModel(VoskModels.spkDir(this).absolutePath)
                rec = Recognizer(model, 16000f, spk)
                svc = SpeechService(rec, 16000f)
                svc!!.startListening(listener)
                status = "escuchando"
            } catch (e: Throwable) { status = "error: ${e.message}"; running = false; stopSelf() }
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; status = "apagado"
        try { svc?.stop(); svc?.shutdown() } catch (e: Throwable) { }
        try { rec?.close(); spk?.close(); model?.close() } catch (e: Throwable) { }
        super.onDestroy()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(h: String?) {}
        override fun onResult(h: String?) { if (h != null) main.post { try { onUtterance(h) } catch (e: Throwable) { } } }
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { status = "error: ${e?.message}" }
        override fun onTimeout() {}
    }

    private fun say(vararg keys: String, then: (() -> Unit)? = null) {
        svc?.setPause(true)
        val ok = VozClips.play(this, *keys) { svc?.setPause(false); then?.invoke() }
        if (!ok) { svc?.setPause(false); then?.invoke() }
    }

    private fun vecOf(o: JSONObject): FloatArray? {
        val a = o.optJSONArray("spk") ?: return null
        if (o.optInt("spk_frames", 0) < 60) return null
        return FloatArray(a.length()) { a.getDouble(it).toFloat() }
    }

    private fun onUtterance(json: String) {
        val o = JSONObject(json)
        val text = norm(o.optString("text"))
        if (text.isBlank()) return
        val v = vecOf(o)
        val now = System.currentTimeMillis()

        // entrenar la voz de una persona: 3 veces «hey <marca>»
        val en = enroll
        if (en != null) {
            if (v != null && parseWake(text) != null) {
                enrollVecs.add(v); enrollLeft--
                if (enrollLeft <= 0) {
                    enrollVecs.forEach { SpeakerDb.learn(this, en, it) }
                    enrollVecs.clear(); enroll = null
                    say("hello_$en")
                } else say("ok")
            }
            return
        }

        // respondió «¿con quién hablo?»
        if (now < askingUntil) {
            val who = SpeakerDb.NAMES.firstOrNull { text.contains(it) }
            askingUntil = 0
            if (who != null) { if (v != null) SpeakerDb.learn(this, who, v); say("hello_$who") } else say("not_understood")
            return
        }

        // elección por voz abierta por otra pantalla (p. ej. el mapa)
        if (now < choiceUntil) {
            val cb = choiceCb
            if (cb != null && try { cb(text) } catch (e: Throwable) { false }) return
        }

        // esperaba la orden después de «hey <marca>»
        if (now < awaitingUntil) {
            awaitingUntil = 0
            dispatch(text, awaitWho)
            return
        }

        val rest = parseWake(text) ?: return
        val id = if (v != null) SpeakerDb.identify(this, v) else null
        val who = if (id != null && id.second < 0.55) id.first else null
        if (rest.isNotBlank()) { dispatch(rest, who); return }
        if (who != null) { awaitWho = who; say("wake_generic") { awaitingUntil = System.currentTimeMillis() + 9000 } }
        else if (SpeakerDb.any(this) && v != null && id != null) { awaitWho = null; say("wake_generic") { awaitingUntil = System.currentTimeMillis() + 9000 } }
        else { say("who") { askingUntil = System.currentTimeMillis() + 9000 } }
    }

    private fun dispatch(cmd: String, who: String?) {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("voice_cmd", cmd).putExtra("voice_who", who ?: ""))
        } catch (e: Exception) { }
    }
}
