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
class UpdateTest {
    @Test fun versionesNuevas() {
        assertTrue(Update.newer("v1.0.14", "1.0.13")); assertTrue(Update.newer("1.1.0", "1.0.99")); assertTrue(Update.newer("2.0", "1.9.9"))
        assertFalse(Update.newer("1.0.13", "1.0.13")); assertFalse(Update.newer("v1.0.12", "1.0.13")); assertFalse(Update.newer("1.0", "1.0.0"))
    }

    @Test fun notasLimpias() {
        val n = Update.cleanNotes("## Novedades\n- Uno **negrita**\n- Dos\n\nIncluye: Launcher y Music\n\nCertificado SHA-256: abc")
        assertEquals("- Uno negrita\n- Dos", n)
    }
}
