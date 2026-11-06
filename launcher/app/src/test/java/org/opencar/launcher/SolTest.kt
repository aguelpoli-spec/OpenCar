package org.opencar.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h600dp-mdpi")
class SolTest {
    @Test fun textosDeCuantoFalta() {
        assertEquals("Atardece en 2 h 10 min", SunView.nextText(16 * 60, 5 * 60 + 51, 18 * 60 + 10))
        assertEquals("Amanece en 8 h", SunView.nextText(21 * 60 + 51, 5 * 60 + 51, 18 * 60))
        assertEquals("Amanece en 45 min", SunView.nextText(5 * 60 + 6, 5 * 60 + 51, 18 * 60))
        assertEquals("05:51", SunView.hm(351))
    }

    @Test fun dibujaDiaYNoche() {
        val ctx = RuntimeEnvironment.getApplication()
        File("/tmp/sol").mkdirs()
        for ((name, now) in listOf("sol-manana" to 8 * 60, "sol-tarde" to 16 * 60 + 40, "sol-noche" to 21 * 60 + 15)) {
            val v = SunView(ctx, 2f, Color.WHITE, 0xFFC5D3E8.toInt(), { 351 to 1124 }, { now })
            v.measure(View.MeasureSpec.makeMeasureSpec(448, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 448, 200)
            val bmp = Bitmap.createBitmap(448, 200, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp); cv.drawColor(0xFF10203A.toInt()); v.draw(cv)
            File("/tmp/sol/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun versionesNuevas() {
        assertTrue(Update.newer("v1.0.14", "1.0.13")); assertTrue(Update.newer("1.1.0", "1.0.99")); assertTrue(Update.newer("2.0", "1.9.9"))
        assertFalse(Update.newer("1.0.13", "1.0.13")); assertFalse(Update.newer("v1.0.12", "1.0.13")); assertFalse(Update.newer("1.0", "1.0.0"))
    }

    @Test fun notasLimpias() {
        val n = Update.cleanNotes("## Novedades\n- Uno **negrita**\n- Dos\n\nIncluye: Launcher y Music\n\nCertificado SHA-256: abc")
        assertEquals("- Uno negrita\n- Dos", n)
    }
}
