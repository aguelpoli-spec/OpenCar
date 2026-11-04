package org.opencar.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.net.URL

/** Pantallas para elegir marca (con logo), modelo y foto de fondo. Se llaman desde Ajustes y en el primer arranque. */
class CarPicker(private val a: Activity, private val p: SharedPreferences, private val onChanged: () -> Unit) {
    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun ui(r: () -> Unit) = a.runOnUiThread { if (!a.isFinishing) r() }
    private val logos = HashMap<String, Bitmap?>()

    private fun filterBox(hint: String, onText: (String) -> Unit): EditText = EditText(a).apply {
        this.hint = hint; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    // ------------------------------------------------------------------ marca
    fun pickBrand(onCancel: (() -> Unit)? = null) {
        val all = CarCatalog.brands(a)
        var shown: List<CarCatalog.Brand> = all
        val lv = ListView(a)
        val ad = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
                val b = shown[i]
                val row = (v as? LinearLayout) ?: LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
                    addView(ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(56), dp(40)))
                    addView(TextView(a).apply { textSize = 17f; setPadding(dp(14), 0, 0, 0) })
                }
                val bmp = logos.getOrPut(b.slug) { CarCatalog.logo(a, b.slug) }
                (row.getChildAt(0) as ImageView).setImageBitmap(bmp)
                (row.getChildAt(1) as TextView).text = b.name
                return row
            }
        }
        lv.adapter = ad
        val et = filterBox("Buscar marca…") { q ->
            val n = norm(q).trim()
            shown = if (n.isEmpty()) all else all.filter { norm(it.name).contains(n) }
            ad.notifyDataSetChanged()
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(et); box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        val d = AlertDialog.Builder(a).setTitle("Elige la marca de tu auto").setView(box)
            .setNegativeButton("Cancelar") { _, _ -> onCancel?.invoke() }.create()
        lv.setOnItemClickListener { _, _, i, _ -> d.dismiss(); pickModel(shown[i]) }
        d.show()
    }

    // ------------------------------------------------------------------ modelo
    private fun pickModel(b: CarCatalog.Brand) {
        val wait = AlertDialog.Builder(a).setTitle(b.name).setMessage("Buscando los modelos en internet…").setCancelable(true).show()
        Thread {
            val list = try { CarCatalog.models(b.name) } catch (e: Throwable) { emptyList() }
            ui {
                try { wait.dismiss() } catch (e: Exception) { }
                if (list.isEmpty()) { askManual(b, "No encontré modelos en línea (¿sin internet?). Escribe el modelo a mano."); return@ui }
                showModels(b, list)
            }
        }.start()
    }

    private fun showModels(b: CarCatalog.Brand, all: List<CarCatalog.Model>) {
        var shown: List<CarCatalog.Model> = all
        fun label(m: CarCatalog.Model) = m.name.removePrefix(b.name).trim() + if (m.years.isNotEmpty()) "   ·   ${m.years}" else ""
        val lv = ListView(a)
        val ad = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup?): View =
                ((v as? TextView) ?: TextView(a).apply { textSize = 17f; setPadding(dp(8), dp(11), dp(8), dp(11)) }).also { it.text = label(shown[i]) }
        }
        lv.adapter = ad
        val et = filterBox("Buscar modelo…") { q ->
            val n = norm(q).trim()
            shown = if (n.isEmpty()) all else all.filter { norm(it.name).contains(n) }
            ad.notifyDataSetChanged()
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(et); box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        val d = AlertDialog.Builder(a).setTitle("${b.name}: elige el modelo").setView(box)
            .setPositiveButton("Otro modelo (escribir)") { _, _ -> askManual(b, null) }
            .setNegativeButton("Atrás") { _, _ -> pickBrand() }.create()
        lv.setOnItemClickListener { _, _, i, _ -> d.dismiss(); askYear(b, shown[i].name.removePrefix(b.name).trim(), shown[i].image) }
        d.show()
    }

    private fun askManual(b: CarCatalog.Brand, msg: String?) {
        val et = EditText(a).apply { hint = "Modelo (ej.: Corolla)"; setSingleLine() }
        AlertDialog.Builder(a).setTitle(b.name).apply { if (msg != null) setMessage(msg) }.setView(et)
            .setPositiveButton("Continuar") { _, _ -> askYear(b, et.text.toString().trim(), null) }
            .setNegativeButton("Atrás") { _, _ -> pickBrand() }.show()
    }

    private fun askYear(b: CarCatalog.Brand, model: String, image: String?) {
        val et = EditText(a).apply { hint = "Año (opcional)"; setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(a).setTitle("$model: ¿de qué año es tu auto?").setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val y = et.text.toString().trim().takeIf { it.length == 4 } ?: ""
                p.edit().putString("car_brand", b.slug).putString("car_model", model).putString("car_year", y).remove("wake_custom")
                    .putString("car_name", listOf(b.name, model, y).filter { it.isNotBlank() }.joinToString(" ")).apply()
                onChanged()
                AlertDialog.Builder(a).setTitle("¿Quieres una foto de tu modelo de fondo?")
                    .setMessage("Se buscan fotos con licencia libre en Wikimedia Commons (necesita internet). También puedes elegir una foto tuya desde Ajustes > Fondo.")
                    .setPositiveButton("Buscar fotos") { _, _ -> photos("${b.name} $model $y".trim(), image) }
                    .setNegativeButton("Ahora no", null).show()
            }
            .setNegativeButton("Atrás") { _, _ -> pickBrand() }.show()
    }

    // ------------------------------------------------------------------ fotos
    /** Lista de fotos libres del modelo; al tocar una se descarga y queda como fondo. */
    fun photos(query: String, wikidataImage: String? = null) {
        val wait = AlertDialog.Builder(a).setTitle("Fotos de $query").setMessage("Buscando en Wikimedia Commons…").show()
        Thread {
            val list = ArrayList<CarCatalog.Photo>()
            try { if (wikidataImage != null) list.addAll(CarCatalog.photoOf(wikidataImage)) } catch (e: Throwable) { }
            try { for (x in CarCatalog.searchPhotos(query)) if (list.none { it.full == x.full }) list.add(x) } catch (e: Throwable) { }
            ui {
                try { wait.dismiss() } catch (e: Exception) { }
                if (list.isEmpty()) { Toast.makeText(a, "No encontré fotos libres (¿sin internet?). Puedes elegir una de tu almacenamiento.", Toast.LENGTH_LONG).show(); return@ui }
                showPhotos(list)
            }
        }.start()
    }

    private fun showPhotos(list: List<CarCatalog.Photo>) {
        val thumbs = HashMap<String, Bitmap?>()
        val lv = ListView(a)
        val ad = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
                val ph = list[i]
                val row = (v as? LinearLayout) ?: LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(dp(150), dp(90)))
                    addView(TextView(a).apply { textSize = 13f; setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val iv = row.getChildAt(0) as ImageView
                (row.getChildAt(1) as TextView).text = ph.title + "\n" + ph.credit
                iv.tag = ph.thumb
                if (thumbs.containsKey(ph.thumb)) iv.setImageBitmap(thumbs[ph.thumb]) else {
                    iv.setImageBitmap(null)
                    Thread {
                        val bmp = try { URL(ph.thumb).openConnection().apply { setRequestProperty("User-Agent", CarCatalog.UA) }.getInputStream().use { BitmapFactory.decodeStream(it) } } catch (e: Throwable) { null }
                        thumbs[ph.thumb] = bmp
                        ui { if (iv.tag == ph.thumb) iv.setImageBitmap(bmp) }
                    }.start()
                }
                return row
            }
        }
        lv.adapter = ad
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        val d = AlertDialog.Builder(a).setTitle("Toca la foto que quieras de fondo").setView(box).setNegativeButton("Cerrar", null).create()
        lv.setOnItemClickListener { _, _, i, _ ->
            d.dismiss()
            val wait = AlertDialog.Builder(a).setMessage("Descargando la foto…").setCancelable(false).show()
            Thread {
                val ok = CarCatalog.saveBackground(a, p, list[i])
                ui {
                    try { wait.dismiss() } catch (e: Exception) { }
                    Toast.makeText(a, if (ok) "Fondo cambiado" else "No pude descargar la foto", Toast.LENGTH_LONG).show()
                    if (ok) onChanged()
                }
            }.start()
        }
        d.show()
    }
}
