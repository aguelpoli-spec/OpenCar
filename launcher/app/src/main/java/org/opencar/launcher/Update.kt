package org.opencar.launcher

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Busca en GitHub si hay una versión más nueva del launcher (la última release publicada). */
object Update {
    const val API = "https://api.github.com/repos/gpc-07/OpenCar/releases/latest"
    const val PAGE = "https://github.com/gpc-07/OpenCar/releases/latest"

    class Info(val version: String, val notes: String)

    /** true si [remote] (por ejemplo «v1.0.14») es mayor que [local] («1.0.13»). */
    fun newer(remote: String, local: String): Boolean {
        fun parts(s: String) = s.trim().trimStart('v', 'V').split('.', '-').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Descarga los datos de la última release. Devuelve null si no hay internet o falló. */
    fun fetch(): Info? = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "OpenCar-launcher")
        if (c.responseCode != 200) null else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            Info(j.optString("tag_name").trimStart('v', 'V'), cleanNotes(j.optString("body")))
        }
    } catch (e: Throwable) { null }

    /** Quita el formato Markdown y las líneas técnicas para mostrar las novedades en un cuadro. */
    fun cleanNotes(md: String): String = md.lines()
        .map { it.trim().removePrefix("## ").removePrefix("# ").replace("**", "") }
        .filter { it.isNotBlank() && !it.startsWith("Certificado SHA-256", true) && !it.startsWith("Incluye:", true) && !it.equals("Novedades", true) }
        .joinToString("\n").take(700)

    /** Hay que revisar de nuevo (máx. una vez al día) salvo que se pida a mano. */
    fun due(p: SharedPreferences, force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        return force || now - p.getLong("upd_at", 0L) > 24 * 3_600_000L
    }
}
