package org.opencar.launcher

import android.content.SharedPreferences
import java.text.Normalizer
import java.util.Locale

/** Lugares favoritos para órdenes de voz: «al trabajo», «a casa», «donde mi pareja», «a casa de mis padres». Los guarda cada usuario. */
object Favoritos {
    class Fav(val key: String, val label: String, val spoken: String)

    val ALL = listOf(
        Fav("place_casa", "Casa", "casa"),
        Fav("place_trabajo", "Trabajo", "el trabajo"),
        Fav("place_pareja", "Pareja", "donde tu pareja"),
        Fav("place_familia", "Familia", "la casa de tu familia")
    )

    fun norm(x: String): String = Normalizer.normalize(x.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ,.]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Coordenadas guardadas («lat,lon»), o null si el usuario aún no lo guardó. */
    fun coords(p: SharedPreferences, f: Fav): Pair<Double, Double>? {
        val v = p.getString(f.key, null)?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        return if (v != null && v.size == 2) Pair(v[0], v[1]) else null
    }

    /** Busca el favorito que corresponde a lo dicho. El orden importa: lo más específico primero. */
    fun match(raw: String): Fav? {
        val t = norm(raw)
        fun by(key: String) = ALL.first { it.key == key }
        return when {
            t.contains("padres") || t.contains("papas") || t.contains("hermanos") || t.contains("familia") || t.contains("mi mama") || t.contains("mi papa") -> by("place_familia")
            t.contains("mujer") || t.contains("esposa") || t.contains("esposo") || t.contains("marido") || t.contains("pareja") || t.contains("novia") || t.contains("novio") || t.contains("senora") -> by("place_pareja")
            t.contains("trabajo") || t.contains("oficina") -> by("place_trabajo")
            t == "casa" || t.startsWith("mi casa") || t.contains("departamento") || t.contains("mi hogar") || t.endsWith(" casa") -> by("place_casa")
            else -> null
        }
    }
}
