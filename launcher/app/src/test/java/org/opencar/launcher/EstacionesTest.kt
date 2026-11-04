package org.opencar.launcher

import org.junit.Assert.*
import org.junit.Test

class EstacionesTest {
    private val all = listOf(
        Estacion("Norte", 10.020, 20.000, "", ""), Estacion("Centro", 10.001, 20.001, "c", ""),
        Estacion("Sur", 9.950, 20.000, "", ""), Estacion("Este", 10.000, 20.050, "l", ""),
        Estacion("Oeste", 10.000, 19.950, "", ""), Estacion("Lejos", 11.000, 21.000, "", "")
    )

    @Test fun lasMasCercanasEstanOrdenadas() {
        val r = Estaciones.nearestIn(all, 10.0, 20.0, 5)
        assertEquals(5, r.size)
        assertTrue(r.zipWithNext().all { it.first.second <= it.second.second })
        assertEquals("Centro", r[0].first.name)
        assertTrue("la más cercana debe estar a menos de 1 km: ${r[0].second}", r[0].second < 1000)
    }

    @Test fun pidePorVoz() {
        for (f in listOf("quiero cargar gasolina", "quiero cargar gasolina por favor", "donde cargo gasolina", "necesito gasolina", "gasolinera mas cercana", "estacion de servicio", "voy a echar combustible", "llevame a una gasolinera", "quiero cargar diesel"))
            assertNotNull(f, Estaciones.wantsFuel(f))
        assertEquals(true, Estaciones.wantsFuel("quiero cargar gas"))
        assertEquals(true, Estaciones.wantsFuel("quiero cargar GNV"))
        assertEquals(false, Estaciones.wantsFuel("quiero cargar gasolina"))
        for (f in listOf("pon musica", "llevame a casa", "sube el volumen", "que hora es", "abre el mapa", "quiero ir a la casa de gas"))
            assertNull(f, Estaciones.wantsFuel(f))
    }

    @Test fun entiendeLaRespuesta() {
        assertEquals(1, Estaciones.parseChoice("la uno", 5)); assertEquals(1, Estaciones.parseChoice("la primera", 5))
        assertEquals(2, Estaciones.parseChoice("dos", 5)); assertEquals(3, Estaciones.parseChoice("la tres", 5))
        assertEquals(4, Estaciones.parseChoice("la cuarta", 5)); assertEquals(5, Estaciones.parseChoice("cinco", 5))
        assertEquals(0, Estaciones.parseChoice("sí", 5)); assertEquals(0, Estaciones.parseChoice("dale", 5))
        assertEquals(0, Estaciones.parseChoice("la más cercana", 5)); assertEquals(0, Estaciones.parseChoice("confirmo", 5))
        assertEquals(-1, Estaciones.parseChoice("no", 5)); assertEquals(-1, Estaciones.parseChoice("otra", 5))
        assertEquals(-2, Estaciones.parseChoice("cancela", 5)); assertEquals(-2, Estaciones.parseChoice("no gracias olvídalo", 5))
        assertNull(Estaciones.parseChoice("quiero un helado", 5)); assertNull(Estaciones.parseChoice("", 5))
        assertNull("solo hay 3", Estaciones.parseChoice("la cinco", 3))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class CombustibleTest {
    @org.junit.Test fun odometroConPuntoDeMiles() {
        org.junit.Assert.assertEquals(138126, Combustible.parseOdo("138.126"))
        org.junit.Assert.assertEquals(138126, Combustible.parseOdo("138,126"))
        org.junit.Assert.assertEquals(138126, Combustible.parseOdo("138126"))
        org.junit.Assert.assertEquals(138126, Combustible.parseOdo("138126.4"))
        org.junit.Assert.assertEquals(0, Combustible.parseOdo(""))
    }
    @org.junit.Test fun cargaGuardadaTruncadaSeRepara() {
        val p = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("t_fix", 0)
        p.edit().clear().putString("fuel_log", "[{\"t\":${System.currentTimeMillis()},\"bs\":260,\"l\":37.4,\"odo\":138,\"st\":\"\",\"full\":true}]").apply()
        val sinReparar = Combustible.estimate(p, 138200)
        org.junit.Assert.assertTrue(sinReparar != null && sinReparar.liters > 0)
        org.junit.Assert.assertTrue(Combustible.repair(p, 138200))
        val e = Combustible.estimate(p, 138200)!!
        org.junit.Assert.assertTrue(e.liters > 40 && e.pct > 80)
    }
    @org.junit.Test fun favoritosDeVoz() {
        org.junit.Assert.assertEquals("place_trabajo", Favoritos.match("al trabajo")!!.key)
        org.junit.Assert.assertEquals("place_casa", Favoritos.match("a casa")!!.key)
        org.junit.Assert.assertEquals("place_familia", Favoritos.match("donde mis padres")!!.key)
        org.junit.Assert.assertEquals("place_pareja", Favoritos.match("a mi mujer")!!.key)
        org.junit.Assert.assertNull(Favoritos.match("la universidad"))
    }
    private fun prefs(vararg fills: String): android.content.SharedPreferences {
        val p = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("c" + System.nanoTime(), 0)
        p.edit().putString("fuel_log", "[" + fills.joinToString(",") + "]").putString("odo_unit", "mi").commit()
        return p
    }

    @org.junit.Test fun sinLlenadoNoEstima() { org.junit.Assert.assertNull(Combustible.estimate(prefs(), 100)) }

    @org.junit.Test fun llenadoYRecorrido() {
        // lleno a 138126 mi; después recorre 31 mi (~50 km) con 7,2 km/L = unos 7 L menos
        val p = prefs("""{"t":1,"bs":260,"l":37.4,"odo":138126,"st":"x","full":true}""")
        val e = Combustible.estimate(p, 138157)!!
        org.junit.Assert.assertTrue("quedan ~43 L: ${e.liters}", e.liters in 42.0..44.5)
        org.junit.Assert.assertTrue("${e.rangeKm}", e.rangeKm in 290..330)
    }

    @org.junit.Test fun cargaParcialDespues() {
        val p = prefs("""{"t":1,"odo":1000,"l":40,"bs":280,"full":true}""", """{"t":2,"odo":1100,"l":20,"bs":139,"full":false}""")
        val e = Combustible.estimate(p, 1100)!!
        org.junit.Assert.assertTrue("${e.liters}", e.liters in 46.0..49.5)
    }

    @org.junit.Test fun guardaUnaCargaYLaLee() {
        val p = prefs()
        Combustible.addFill(p, 260.0, 37.4, 138126, "Estación", true)
        org.junit.Assert.assertNotNull(Combustible.estimate(p, 138126))
        org.junit.Assert.assertEquals(50, Combustible.estimate(p, 138126)!!.liters.toInt())
    }
}

class NumEsTest {
    @org.junit.Test fun entiendeCifrasHabladas() {
        org.junit.Assert.assertEquals(260.0, NumEs.parse("doscientos sesenta")!!, 0.01)
        org.junit.Assert.assertEquals(260.0, NumEs.parse("260 pesos")!!, 0.01)
        org.junit.Assert.assertEquals(350.0, NumEs.parse("trescientos cincuenta")!!, 0.01)
        org.junit.Assert.assertEquals(100.0, NumEs.parse("cien")!!, 0.01)
        org.junit.Assert.assertEquals(45.0, NumEs.parse("cuarenta y cinco")!!, 0.01)
        org.junit.Assert.assertEquals(121.0, NumEs.parse("ciento veintiuno")!!, 0.01)
        org.junit.Assert.assertNull(NumEs.parse("hola buenas"))
    }
}
