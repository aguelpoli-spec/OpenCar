package org.opencar.launcher

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Regiones de mapa disponibles para descargar. La lista sale en vivo del servidor de Mapsforge
 * (https://download.mapsforge.org/maps/v5/), basado en OpenStreetMap: continentes > países > (estados o regiones).
 */
object MapRegions {
    const val BASE = "https://download.mapsforge.org/maps/v5/"
    class Entry(val label: String, val path: String, val dir: Boolean, val size: String)

    private fun pretty(raw: String): String = raw.trimEnd('/').removeSuffix(".map").split('-', '_')
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Contenido de una carpeta del servidor. Bloquea: usar en un hilo. */
    fun list(path: String): List<Entry> {
        val cn = URL(BASE + path).openConnection() as HttpURLConnection
        cn.connectTimeout = 20000; cn.readTimeout = 30000; cn.setRequestProperty("User-Agent", MapData.UA)
        val html = try { if (cn.responseCode != 200) throw java.io.IOException("HTTP ${cn.responseCode}"); cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
        val re = Regex("<a href=\"([^\"/?][^\"]*)\">[^<]*</a>\\s*</td><td align=\"right\">[^<]*</td><td align=\"right\">\\s*([^<]*?)\\s*</td>")
        val out = ArrayList<Entry>()
        for (m in re.findAll(html)) {
            val href = m.groupValues[1]
            val sz = m.groupValues[2].let { s -> if (s == "-" || s.isEmpty()) "" else s.replace(Regex("([KMG])$"), " $1B") }
            if (href.endsWith("/")) out.add(Entry(pretty(href), path + href, true, ""))
            else if (href.endsWith(".map")) out.add(Entry(pretty(href), path + href, false, sz))
        }
        return out.sortedWith(compareBy({ !it.dir }, { it.label }))
    }
}

/** Ventana para elegir el mapa: continente > país > región; al tocar un archivo .map queda elegido. */
class RegionPicker(private val a: Activity, private val onPicked: (path: String, name: String) -> Unit) {
    fun show(path: String = "") {
        val wait = AlertDialog.Builder(a).setMessage("Consultando los mapas disponibles…").show()
        Thread {
            val list = try { MapRegions.list(path) } catch (e: Throwable) { null }
            a.runOnUiThread {
                try { wait.dismiss() } catch (e: Exception) { }
                if (a.isFinishing) return@runOnUiThread
                if (list == null || list.isEmpty()) { Toast.makeText(a, "No pude consultar la lista (¿sin internet?)", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val labels = list.map { if (it.dir) "📁  ${it.label}" else "🗺  ${it.label}   ·   ${it.size}" }
                val lv = ListView(a); lv.adapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, labels)
                val title = if (path.isEmpty()) "¿Dónde vas a manejar? Elige continente" else "Elige: " + path.trimEnd('/').split('/').joinToString(" › ") { it.replace('-', ' ') }
                val b = AlertDialog.Builder(a).setTitle(title).setView(lv)
                if (path.isNotEmpty()) b.setNegativeButton("‹ Atrás") { _, _ -> show(path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) }
                else b.setNegativeButton("Cancelar", null)
                val d = b.create()
                lv.setOnItemClickListener { _, _, i, _ ->
                    d.dismiss()
                    val e = list[i]
                    if (e.dir) show(e.path) else onPicked(e.path, e.label)
                }
                d.show()
            }
        }.start()
    }
}
