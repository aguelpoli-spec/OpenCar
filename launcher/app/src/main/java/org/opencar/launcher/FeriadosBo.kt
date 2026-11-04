package org.opencar.launcher

import java.util.Calendar

/**
 * Calendario de feriados de Bolivia: viene de fábrica solo si el teléfono está configurado en Bolivia (BO).
 * En el resto de países se usan los feriados que sugiere la fuente en línea (Nager.Date) y los eventos del usuario.
 * Se calcula solo para cualquier año: fechas fijas, Semana Santa/Carnaval/Corpus por la Pascua,
 * traslado al lunes cuando cae domingo (DS 2750 art. 3, modificado por DS 5019 y DS 5483 de 29/10/2025)
 * y los decretos especiales conocidos por año (2026: DS 5521).
 */
object FeriadosBo {
    const val NAC = 0   // feriado nacional
    const val DEP = 1   // feriado departamental/regional (los agrega el usuario)
    const val IMP = 2   // fecha importante, no feriado
    const val USR = 3   // evento propio del usuario
    const val SUG = 4   // sugerido en línea (sin confirmar)

    data class Ev(val y: Int, val m: Int, val d: Int, val name: String, val kind: Int) {
        fun millis(): Long = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis
        fun isHoliday() = kind == NAC || kind == DEP
    }

    private fun cal(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }

    private fun shift(c: Calendar, days: Int): Calendar =
        (c.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, days) }

    private fun ev(c: Calendar, name: String, kind: Int) =
        Ev(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), name, kind)

    /** Domingo de Pascua (algoritmo gregoriano). */
    fun easter(y: Int): Calendar {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return cal(y, month, day)
    }

    fun forYear(y: Int): List<Ev> = if (java.util.Locale.getDefault().country == "BO") bolivia(y) else emptyList()

    private fun bolivia(y: Int): List<Ev> {
        val out = ArrayList<Ev>()
        fun add(e: Ev) { if (out.none { it.y == e.y && it.m == e.m && it.d == e.d && it.name == e.name }) out.add(e) }

        // fijos nacionales; el traslado al lunes aplica si caen domingo
        val fijos = listOf(
            Triple(1, 1, "Año Nuevo"),
            Triple(1, 22, "Día del Estado Plurinacional"),
            Triple(5, 1, "Día del Trabajo"),
            Triple(6, 21, "Año Nuevo Andino Amazónico y Chaqueño"),
            Triple(8, 6, "Día de la Independencia"),
            Triple(11, 2, "Día de Todos los Difuntos"),
            Triple(12, 25, "Navidad")
        )
        for ((m, d, n) in fijos) {
            // 2026: el Gobierno traslada el 22 de enero al viernes 23 (DS 5521)
            if (y == 2026 && m == 1 && d == 22) {
                add(Ev(2026, 1, 23, "Día del Estado Plurinacional (trasladado)", NAC)); continue
            }
            val c = cal(y, m, d)
            add(ev(c, n, NAC))
            val moves = !(m == 11 && d == 2 && y < 2025)   // el 2 de nov se traslada desde DS 5483 (oct. 2025)
            if (c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && moves) add(ev(shift(c, 1), "$n (lunes de traslado)", NAC))
        }
        // movibles por la Pascua
        val e = easter(y)
        add(ev(shift(e, -48), "Carnaval (lunes)", NAC))
        add(ev(shift(e, -47), "Carnaval (martes)", NAC))
        add(ev(shift(e, -2), "Viernes Santo", NAC))
        add(ev(shift(e, 60), "Corpus Christi", NAC))
        // decretos especiales conocidos
        if (y == 2026) {
            add(Ev(2026, 6, 5, "Corpus Christi (día adicional, DS 5521)", NAC))
            add(Ev(2026, 8, 7, "Día de la Independencia (día adicional, DS 5521)", NAC))
        }
        // fechas importantes (no son feriado)
        add(ev(shift(e, -46), "Miércoles de Ceniza", IMP))
        add(ev(shift(e, -7), "Domingo de Ramos", IMP))
        add(ev(shift(e, -3), "Jueves Santo (no es feriado nacional)", IMP))
        add(ev(e, "Domingo de Pascua", IMP))
        val imp = listOf(
            Triple(3, 19, "Día del Padre"), Triple(3, 23, "Día del Mar"), Triple(4, 12, "Día del Niño"),
            Triple(5, 27, "Día de la Madre"), Triple(6, 6, "Día del Maestro"), Triple(6, 24, "Noche de San Juan"),
            Triple(8, 1, "Día de la Pachamama"), Triple(8, 17, "Día de la Bandera"),
            Triple(9, 27, "Día de la Integración Boliviana"), Triple(10, 11, "Día de la Mujer Boliviana"),
            Triple(11, 1, "Todos Santos"), Triple(12, 24, "Nochebuena"), Triple(12, 31, "Noche Vieja")
        )
        for ((m, d, n) in imp) add(Ev(y, m, d, n, IMP))
        return out.sortedWith(compareBy({ it.m }, { it.d }, { it.kind }))
    }
}
