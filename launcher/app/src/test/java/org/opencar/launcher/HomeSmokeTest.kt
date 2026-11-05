package org.opencar.launcher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSmokeTest {
    private fun layer(a: MainActivity): WidgetLayer? {
        val f = MainActivity::class.java.getDeclaredField("widgetLayer"); f.isAccessible = true
        return f.get(a) as WidgetLayer?
    }
    private fun call(a: Any, m: String, vararg args: Any?) {
        val me = a.javaClass.declaredMethods.first { it.name == m && it.parameterTypes.size == args.size }
        me.isAccessible = true; me.invoke(a, *args)
    }
    private fun find(v: View, pred: (View) -> Boolean, out: MutableList<View> = ArrayList()): List<View> {
        if (pred(v)) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred, out)
        return out
    }

    @Test fun inicioConWidgetsYEdicion() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        val l = layer(a)
        assertNotNull("la capa de widgets debe existir", l)
        assertEquals(12, l!!.specList().size)
        // modo edición, agregar, quitar y restablecer
        l.enterEdit("mapa"); assertTrue(l.editing)
        l.add("clima"); assertTrue(l.has("clima"))
        l.exitEdit(); assertFalse(l.editing)
        assertEquals(13, WStore.load(a.getSharedPreferences("launcher", 0)).size)
        // se vuelve a dibujar todo sin fallar
        call(a, "render")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(13, layer(a)!!.specList().size)
        l.rebuildAll()
    }

    @Test fun nocheYLuces() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        a.getSharedPreferences("launcher", 0).edit().putString("theme_mode", "dark").apply()
        call(a, "render"); shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(layer(a))
        call(a, "nightSettings"); call(a, "lightsDiag")
        // sin señal configurada: no hay estado
        assertNull(Luces.state(a, a.getSharedPreferences("launcher", 0)))
        val prefs = a.getSharedPreferences("launcher", 0)
        Luces.saveSignal(prefs, Luces.Diff("system/screen_brightness", "200", "60"))
        assertTrue(Luces.configured(prefs))
        Luces.clearSignal(prefs); assertFalse(Luces.configured(prefs))
        // aviso de luces
        call(a, "showLightAlert", true)
        call(a, "showLightAlert", false)
    }

    @Test fun restablecerYLayoutGuardado() {
        val p = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("t", 0)
        val d = WStore.defaults(); assertEquals(11, d.size)
        d[0].x = 5000f; d[0].w = 20f
        WStore.save(p, d)
        val back = WStore.load(p)
        assertTrue(back[0].x <= WStore.W - back[0].w); assertTrue(back[0].w >= WStore.def(back[0].id)!!.minW)
        WStore.reset(p); assertEquals(11, WStore.load(p).size)
        // diseño viejo con «Consumo»: se quita y «Cambio de aceite» se ensancha
        p.edit().putString("widgets_v2", """[{"id":"aceite","x":704,"y":344,"w":146,"h":60},{"id":"cons","x":858,"y":344,"w":146,"h":60}]""").commit()
        val mig = WStore.load(p); assertEquals(1, mig.size); assertEquals(300f, mig[0].w, 0.5f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveMapTest {
    @Test fun mapaEnVivoConCallesReales() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val src = java.io.File(System.getenv("OPENCAR_TEST_MAP") ?: "/nonexistent.map")
        if (!src.exists()) return
        val dst = MapData.mapFile(ctx); dst.parentFile?.mkdirs()
        // enlace al mapa real (no se copia: pesa 170 MB)
        try { java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath()) } catch (e: Exception) { src.copyTo(dst, true) }
        val lm = LiveMap(ctx, 1f, 0xFF4DD0E1.toInt(), false, 20f)
        assertTrue("el mapa en vivo debe iniciar", lm.ok)
        val c = MapData.center(ctx)
        lm.update(c.first, c.second, 45f, listOf(doubleArrayOf(c.first + 0.0003, c.second + 0.0001), doubleArrayOf(c.first, c.second)))
        lm.destroy()
        // el archivo compartido de búsqueda sigue abierto tras destruir el mapa
        assertNotNull(PlaceIndex.mapFile(ctx))
        val lm2 = LiveMap(ctx, 1f, 0xFF4DD0E1.toInt(), true, 20f)
        assertTrue(lm2.ok); lm2.update(c.first, c.second, 0f, emptyList()); lm2.destroy()
    }
}

class MapaTemaTest {
    @Test fun elEstiloDelMapaEsXmlValido() {
        for (nombre in listOf("calles.xml", "calles_noche.xml", "calles_basico.xml", "calles_basico_noche.xml")) {
            val f = java.io.File("src/main/assets/map/$nombre")
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            assertEquals("rendertheme", doc.documentElement.nodeName)
            assertTrue(nombre, doc.getElementsByTagName("pathText").length >= 2)
            val iconos = java.io.File("src/main/assets/symbols")
            for (tag in listOf("symbol", "lineSymbol")) {
                val l = doc.getElementsByTagName(tag)
                for (i in 0 until l.length) assertTrue(java.io.File(iconos, (l.item(i) as org.w3c.dom.Element).getAttribute("src").removePrefix("assets:symbols/")).exists())
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuSuperpuestoTest {
    @Test fun alAbrirElMenuSeEscondenLosWidgets() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        val f = MainActivity::class.java.getDeclaredField("widgetLayer"); f.isAccessible = true
        val l = f.get(a) as WidgetLayer
        l.hideExcept(setOf("dock", "player"))
        val frames = (0 until l.childCount).map { l.getChildAt(it) }
        assertTrue(frames.any { it.visibility == View.INVISIBLE })
        assertTrue(frames.any { it.visibility == View.VISIBLE })
        l.showAll()
        assertTrue(frames.all { it.visibility == View.VISIBLE })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreguntaGasolineraTest {
    @Test fun elDialogoDeCargaSeAbreYCalcula() {
        val a = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().get()
        val p = a.getSharedPreferences("launcher", 0)
        p.edit().putString("fuel_log", """[{"t":1,"bs":260,"l":37.4,"odo":138126,"st":"x","full":true}]""").putString("odo_unit", "mi").commit()
        val m = MainActivity::class.java.getDeclaredMethod("fuelPrompt", String::class.java); m.isAccessible = true
        m.invoke(a, "ES Ejemplo")
        shadowOf(Looper.getMainLooper()).idle()
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(d); assertTrue(d.isShowing)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VigilanciaTest {
    @Test fun estacionaTrasMasDe30MinYPreguntaEnGasolinera() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val p = ctx.getSharedPreferences("launcher", 0)
        p.edit().clear().commit()
        val est = Estacion("Prueba", 10.0, 20.0, "", "")
        // venía en marcha
        BgWatch.onFix(ctx, p, est.lat + 0.01, est.lon, 40f)
        // se detiene lejos de una gasolinera: guarda el punto de parada
        BgWatch.onFix(ctx, p, -17.0, -63.0, 0f)
        assertTrue(p.getLong("stop_t", 0L) > 0L)
        // 31 minutos después sigue ahí: se guarda dónde estacionó
        p.edit().putLong("stop_t", System.currentTimeMillis() - 31 * 60_000L).commit()
        BgWatch.onFix(ctx, p, -17.0, -63.0, 0f)
        assertNotNull(p.getString("park_lat", null))
    }

    @Test fun overlayYNumerosDeLaPregunta() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        FuelAsk.show(ctx, "ES Prueba")   // sin permiso de superposición usa la notificación
        FuelAsk.dismiss(ctx)
    }
}
