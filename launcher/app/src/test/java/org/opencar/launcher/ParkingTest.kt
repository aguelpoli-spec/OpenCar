package org.opencar.launcher

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParkingTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun prefs() = ctx.getSharedPreferences("pk", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun guardaVariosLugaresYNoSoloUno() {
        val p = prefs(); val t = System.currentTimeMillis()
        Parking.add(ctx, p, t - 5 * 3600_000L, -17.7800, -63.1800, "estacionado", 40)
        Parking.add(ctx, p, t - 2 * 3600_000L, -17.7700, -63.2000, "estacionado", 30)
        Parking.add(ctx, p, t - 3600_000L, -17.7600, -63.2100, "parada", 10)
        assertEquals(3, Parking.list(p).size)
        // el más reciente que no es parada corta es el «dónde estacioné»
        assertEquals(-17.7700, Parking.lastParked(p)!!.lat, 1e-6)
    }

    @Test fun mismoSitioNoSeRepite() {
        val p = prefs(); val t = System.currentTimeMillis()
        Parking.add(ctx, p, t, -17.78, -63.18, "parada", 8)
        Parking.add(ctx, p, t + 600_000L, -17.78001, -63.18001, "estacionado", 35)
        assertEquals(1, Parking.list(p).size)
        assertEquals("estacionado", Parking.list(p)[0].kind)
    }

    @Test fun favoritoCercanoPoneNombre() {
        val p = prefs()
        p.edit().putString("place_casa", "-17.7800,-63.1800").commit()
        val lb = Parking.favLabel(p, -17.78005, -63.18005)
        println("LABEL=[" + lb + "] favs=" + Favoritos.ALL.map { it.key })
        assertEquals("Casa", lb)
        assertEquals("", Parking.favLabel(p, -17.80, -63.20))
    }

    @Test fun autoApagadoSeGuardaAlVolverElGps() {
        val p = prefs(); val now = System.currentTimeMillis()
        p.edit().putLong("lf_t", now - 10 * 60_000L).putString("lf_lat", "-17.780000").putString("lf_lon", "-63.180000").putFloat("lf_kmh", 2f).commit()
        Parking.noteFix(ctx, p, -17.7801, -63.1801, 0f)
        val l = Parking.list(p)
        assertEquals(1, l.size); assertEquals("apagado", l[0].kind)
    }

    @Test fun sinDetencionNoHayFalsoApagado() {
        val p = prefs(); val now = System.currentTimeMillis()
        // iba a 60 km/h y se perdió la señal (túnel): no es estacionamiento
        p.edit().putLong("lf_t", now - 10 * 60_000L).putString("lf_lat", "-17.780000").putString("lf_lon", "-63.180000").putFloat("lf_kmh", 60f).commit()
        Parking.noteFix(ctx, p, -17.7801, -63.1801, 30f)
        assertEquals(0, Parking.list(p).size)
    }
}
