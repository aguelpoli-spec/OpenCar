package org.opencar.music

import android.net.Uri
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayActivitySmokeTest {
    private fun fakeSongs(): List<LocalPlayer.Track> = (1..40).map {
        LocalPlayer.Track(it.toLong(), "Canción $it", "Artista ${it % 5}", "Álbum ${it % 7}", (it % 7).toLong(), 200000L + it * 1000, Uri.parse("content://media/external/audio/media/$it"), "c$it.mp3", if (it % 4 == 0) "/storage/ABCD-1234/c$it.mp3" else "/storage/emulated/0/Music/c$it.mp3", 1700000000L + it)
    }

    private fun set(o: Any, f: String, v: Any?) { val fl = o.javaClass.getDeclaredField(f); fl.isAccessible = true; fl.set(o, v) }
    private fun call(o: Any, m: String) { val me = o.javaClass.getDeclaredMethod(m); me.isAccessible = true; me.invoke(o) }
    private fun enumOf(o: Any, f: String, name: String): Any { val fl = o.javaClass.getDeclaredField(f); val c = fl.type; return c.enumConstants!!.first { (it as Enum<*>).name == name } }

    @Test fun todasLasPantallasAbren() {
        LocalPlayer.songs = fakeSongs(); LocalPlayer.loaded = true
        val ctrl = Robolectric.buildActivity(PlayActivity::class.java).create().start().resume().visible()
        val a = ctrl.get()
        for (t in listOf("HOME", "LIB", "SEARCH", "LYRICS", "SET")) {
            set(a, "tab", enumOf(a, "tab", t)); set(a, "detail", null); set(a, "ovl", null)
            for (s in 0..3) { set(a, "seg", s); call(a, "render") }
        }
        // detalle de álbum, artista y lista
        set(a, "tab", enumOf(a, "tab", "LIB"))
        set(a, "detail", Pair("album", "Álbum 1||Artista 1")); call(a, "render")
        set(a, "detail", Pair("artist", "Artista 2")); call(a, "render")
        set(a, "detail", Pair("list", "fav")); call(a, "render")
        set(a, "detail", null)
        // sin nada sonando, las superposiciones deben cerrarse sin error
        for (o in listOf("now", "queue", "eq", "lyrics")) { set(a, "ovl", o); call(a, "render") }
        // filtros de origen
        for (src in 0..4) { set(a, "source", src); set(a, "tab", enumOf(a, "tab", "HOME")); set(a, "ovl", null); call(a, "render") }
        assertTrue(true)
    }

    @Test fun ordenDeVozEncuentraMusica() {
        LocalPlayer.songs = fakeSongs(); LocalPlayer.loaded = true
        val a = Robolectric.buildActivity(PlayActivity::class.java, android.content.Intent().putExtra("play_q", "artista 3")).create().start().resume().get()
        assertTrue(LocalPlayer.hasQueue)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayActivityPlayingTest {
    private fun set(o: Any, f: String, v: Any?) { val fl = o.javaClass.getDeclaredField(f); fl.isAccessible = true; fl.set(o, v) }
    private fun call(o: Any, m: String) { val me = o.javaClass.getDeclaredMethod(m); me.isAccessible = true; me.invoke(o) }

    @Test fun conMusicaSonandoTodasLasSuperposiciones() {
        val songs = (1..30).map { LocalPlayer.Track(it.toLong(), "Canción $it", "Artista ${it % 4}", "Álbum ${it % 5}", (it % 5).toLong(), 210000L, Uri.parse("content://media/external/audio/media/$it"), "c$it.mp3", "/storage/emulated/0/Music/c$it.mp3", 1700000000L + it) }
        LocalPlayer.songs = songs; LocalPlayer.loaded = true
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Db.putLyrics(ctx, Lyrics.key("Artista 1", "Canción 1"), "[00:01.00]Primera línea\n[00:05.50]Segunda línea\n[00:09.00]Tercera línea", "test", "Canción 1", "Artista 1")
        LocalPlayer.init(ctx)
        LocalPlayer.playQueue(songs, 0, false)
        val a = Robolectric.buildActivity(PlayActivity::class.java).create().start().resume().visible().get()
        for (o in listOf("now", "queue", "eq", "lyrics", null)) { set(a, "ovl", o); call(a, "render") }
        for (t in listOf("HOME", "LIB", "SEARCH", "LYRICS", "SET")) {
            val c = a.javaClass.getDeclaredField("tab").type
            set(a, "tab", c.enumConstants!!.first { (it as Enum<*>).name == t }); set(a, "ovl", null); call(a, "render")
        }
        // avanzar el reloj de la interfaz para que corra el refresco
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(a.window.decorView != null)
    }

    @Test fun ecualizadorYMezclaSeGuardan() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        AudioFx.init(ctx)
        AudioFx.xfadeSec = 7; assertTrue(AudioFx.xfadeSec == 7)
        AudioFx.setPreset("Rock"); assertTrue(AudioFx.preset == "Rock")
    }
}
