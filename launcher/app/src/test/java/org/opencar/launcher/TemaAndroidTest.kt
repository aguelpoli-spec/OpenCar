package org.opencar.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemaAndroidTest {
    private fun carga(ctx: Application, iconos: Boolean) {
        ctx.getSharedPreferences("launcher", 0).edit().putBoolean("map_icons", iconos).commit()
        val rtf = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, LiveMap.theme(ctx), DisplayModel())
        Thread(rtf).start()
        val t = rtf.get(60, TimeUnit.SECONDS)
        assertNotNull(t)
    }
    @Test fun temaConIconosCargaEnAndroid() {
        val c = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("el estilo con iconos debe leerse bien en Android", LiveMap.iconThemeWorks(c))
        carga(c, true)
    }
    @Test fun temaBasicoCargaEnAndroid() { carga(ApplicationProvider.getApplicationContext(), false) }
}
