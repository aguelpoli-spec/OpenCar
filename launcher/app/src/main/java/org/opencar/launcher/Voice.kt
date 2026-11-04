package org.opencar.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import java.util.Locale
import kotlin.math.sin

/** Reconocimiento de voz (micrófono de la radio) y respuestas habladas en español. */
class VoiceEngine(private val ctx: Context) {
    interface Cb {
        fun onPartial(t: String)
        fun onLevel(l: Float)
        fun onFinal(t: String)
        fun onError(msg: String)
    }

    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var pending: String? = null

    fun available(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (e: Exception) { false }

    fun start(cb: Cb) {
        stop()
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            sr = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) { cb.onLevel(((rms + 2f) / 12f).coerceIn(0f, 1f)) }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {
                    p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { cb.onPartial(it) }
                }
                override fun onResults(p: Bundle?) {
                    val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) cb.onError("No te entendí") else cb.onFinal(t)
                }
                override fun onError(e: Int) {
                    cb.onError(when (e) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No te entendí, inténtalo otra vez"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No escuché nada"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin internet para reconocer la voz"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado"
                        else -> "No pude escuchar (código $e)"
                    })
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-BO")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            r.startListening(i)
        } catch (e: Exception) { cb.onError("No se pudo iniciar el micrófono") }
    }

    fun stop() {
        try { sr?.cancel(); sr?.destroy() } catch (e: Exception) { }
        sr = null
    }

    fun speak(text: String) {
        try {
            if (tts == null) {
                tts = TextToSpeech(ctx) { st ->
                    ttsOk = st == TextToSpeech.SUCCESS
                    if (ttsOk) {
                        val t = tts ?: return@TextToSpeech
                        for (l in listOf(Locale("es", "BO"), Locale("es", "US"), Locale("es", "MX"), Locale("es", "ES"), Locale("es"))) {
                            if (t.isLanguageAvailable(l) >= TextToSpeech.LANG_AVAILABLE) { t.language = l; break }
                        }
                        pending?.let { t.speak(it, TextToSpeech.QUEUE_FLUSH, null, "opencar"); pending = null }
                    }
                }
            }
            if (ttsOk) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "opencar") else pending = text
        } catch (e: Exception) { }
    }

    fun shutdown() {
        stop()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null; ttsOk = false
    }
}

/** Esfera animada del asistente: respira al escuchar y reacciona al volumen de tu voz. */
class VoiceOrb(c: Context) : View(c) {
    var level = 0f
    var state = 0   // 0 reposo, 1 escuchando, 2 pensando, 3 listo, 4 error
    private var sm = 0f
    private val t0 = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        sm += (level - sm) * 0.2f
        val acc = TechBase.base
        val col = when (state) { 4 -> 0xFFFF5A4F.toInt(); 3 -> 0xFF69F0AE.toInt(); else -> acc }
        val base = minOf(w, h) * 0.26f
        val r = base * (1f + 0.12f * sin(t * 3f) + sm * 0.6f)
        p.style = Paint.Style.STROKE
        for (i in 0 until 3) {
            val f = ((t * 0.7f + i / 3f) % 1f)
            p.strokeWidth = 3f * (1f - f) + 1f
            p.color = (col and 0x00FFFFFF) or (((1f - f) * (if (state == 1) 150 else 70)).toInt() shl 24)
            cv.drawCircle(cx, cy, base + f * base * 1.2f, p)
        }
        p.style = Paint.Style.FILL
        p.color = (col and 0x00FFFFFF) or 0x2E000000; cv.drawCircle(cx, cy, r * 1.55f, p)
        p.color = (col and 0x00FFFFFF) or 0x55000000; cv.drawCircle(cx, cy, r * 1.25f, p)
        p.color = col; cv.drawCircle(cx, cy, r, p)
        p.color = 0xCCFFFFFF.toInt(); cv.drawCircle(cx - r * 0.25f, cy - r * 0.3f, r * 0.28f, p)
        if (state == 2) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.WHITE; p.strokeCap = Paint.Cap.ROUND
            cv.drawArc(RectF(cx - r * 1.6f, cy - r * 1.6f, cx + r * 1.6f, cy + r * 1.6f), (t * 300f) % 360f, 90f, false, p)
        }
        postInvalidateOnAnimation()
    }
}
