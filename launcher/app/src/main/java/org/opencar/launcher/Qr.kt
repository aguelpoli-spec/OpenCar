package org.opencar.launcher

import android.graphics.Bitmap
import android.graphics.Color

/** Código QR mínimo (modo bytes, corrección L, versiones 1 a 10) para mostrar un enlace sin depender de librerías. */
object Qr {
    private val ECC_PER_BLOCK = intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
    private val NUM_BLOCKS = intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4)

    private fun rawModules(v: Int): Int {
        var r = (16 * v + 128) * v + 64
        if (v >= 2) { val a = v / 7 + 2; r -= (25 * a - 10) * a - 55; if (v >= 7) r -= 36 }
        return r
    }
    private fun dataWords(v: Int) = rawModules(v) / 8 - ECC_PER_BLOCK[v] * NUM_BLOCKS[v]

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
        return z
    }
    private fun divisor(d: Int): IntArray {
        val r = IntArray(d); r[d - 1] = 1; var root = 1
        for (i in 0 until d) {
            for (j in 0 until d) { r[j] = mul(r[j], root); if (j + 1 < d) r[j] = r[j] xor r[j + 1] }
            root = mul(root, 2)
        }
        return r
    }
    private fun remainder(data: IntArray, div: IntArray): IntArray {
        val r = IntArray(div.size)
        for (b in data) {
            val f = b xor r[0]
            System.arraycopy(r, 1, r, 0, r.size - 1); r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(div[i], f)
        }
        return r
    }

    /** Matriz de módulos (true = oscuro). */
    fun encode(text: String): Array<BooleanArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var ver = 1
        while (true) {
            if (ver > 10) throw IllegalArgumentException("texto demasiado largo")
            val cc = if (ver >= 10) 16 else 8
            if (4 + cc + bytes.size * 8 <= dataWords(ver) * 8) break
            ver++
        }
        val cap = dataWords(ver) * 8
        val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v ushr i) and 1) }
        put(4, 4); put(bytes.size, if (ver >= 10) 16 else 8)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        put(0, minOf(4, cap - bits.size))
        while (bits.size % 8 != 0) bits.add(0)
        var pad = 0xEC
        while (bits.size < cap) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
        val data = IntArray(bits.size / 8) { i -> var v = 0; for (k in 0 until 8) v = (v shl 1) or bits[i * 8 + k]; v }

        // bloques + corrección de errores
        val nb = NUM_BLOCKS[ver]; val ecc = ECC_PER_BLOCK[ver]
        val raw = rawModules(ver) / 8
        val nShort = nb - raw % nb; val shortLen = raw / nb
        val div = divisor(ecc)
        val blocks = ArrayList<IntArray>(); var k = 0
        for (i in 0 until nb) {
            val len = shortLen - ecc + (if (i < nShort) 0 else 1)
            val dat = data.copyOfRange(k, k + len); k += len
            val e = remainder(dat, div)
            val blk = IntArray(shortLen + 1)
            System.arraycopy(dat, 0, blk, 0, dat.size)
            System.arraycopy(e, 0, blk, blk.size - ecc, ecc)
            blocks.add(blk)
        }
        val inter = ArrayList<Int>()
        for (i in 0 until shortLen + 1) for (j in 0 until nb) if (i != shortLen - ecc || j >= nShort) inter.add(blocks[j][i])

        val size = ver * 4 + 17
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun setF(x: Int, y: Int, d: Boolean) { if (x in 0 until size && y in 0 until size) { m[y][x] = d; fn[y][x] = true } }
        for (i in 0 until size) { setF(6, i, i % 2 == 0); setF(i, 6, i % 2 == 0) }
        fun finder(cx: Int, cy: Int) { for (dy in -4..4) for (dx in -4..4) { val d = maxOf(Math.abs(dx), Math.abs(dy)); setF(cx + dx, cy + dy, d != 2 && d != 4) } }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        if (ver >= 2) {
            val na = ver / 7 + 2
            val step = (ver * 4 + na * 2 + 1) / (na * 2 - 2) * 2
            val pos = IntArray(na); pos[0] = 6; var pp = size - 7
            for (i in na - 1 downTo 1) { pos[i] = pp; pp -= step }
            for (i in 0 until na) for (j in 0 until na) {
                if ((i == 0 && j == 0) || (i == 0 && j == na - 1) || (i == na - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) setF(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
        }
        fun format(mask: Int) {
            val d = (1 shl 3) or mask; var r = d
            repeat(10) { r = (r shl 1) xor ((r ushr 9) * 0x537) }
            val b = ((d shl 10) or r) xor 0x5412
            fun bit(i: Int) = ((b ushr i) and 1) != 0
            for (i in 0..5) setF(8, i, bit(i))
            setF(8, 7, bit(6)); setF(8, 8, bit(7)); setF(7, 8, bit(8))
            for (i in 9..14) setF(14 - i, 8, bit(i))
            for (i in 0..7) setF(size - 1 - i, 8, bit(i))
            for (i in 8..14) setF(8, size - 15 + i, bit(i))
            setF(8, size - 8, true)
        }
        format(0)
        if (ver >= 7) {
            var r = ver; repeat(12) { r = (r shl 1) xor ((r ushr 11) * 0x1F25) }
            val b = (ver shl 12) or r
            for (i in 0 until 18) { val bit = ((b ushr i) and 1) != 0; val a = size - 11 + i % 3; val q = i / 3; setF(a, q, bit); setF(q, a, bit) }
        }
        // datos en zigzag
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j; val up = ((right + 1) and 2) == 0; val y = if (up) size - 1 - vert else vert
                if (!fn[y][x] && i < inter.size * 8) { m[y][x] = ((inter[i ushr 3] ushr (7 - (i and 7))) and 1) != 0; i++ }
            }
            right -= 2
        }
        // máscara con menor penalidad
        fun apply(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val inv = when (mask) { 0 -> (x + y) % 2 == 0; 1 -> y % 2 == 0; 2 -> x % 3 == 0; 3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0; 5 -> x * y % 2 + x * y % 3 == 0; 6 -> (x * y % 2 + x * y % 3) % 2 == 0; else -> ((x + y) % 2 + x * y % 3) % 2 == 0 }
                if (!fn[y][x] && inv) m[y][x] = !m[y][x]
            }
        }
        fun penalty(): Int {
            var s = 0
            for (t in 0..1) for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    val cur = if (t == 0) m[a][b] else m[b][a]; val prev = if (t == 0) m[a][b - 1] else m[b - 1][a]
                    if (cur == prev) { run++; if (run == 5) s += 3 else if (run > 5) s++ } else run = 1
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) s += 3
            val pat = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            for (t in 0..1) for (a in 0 until size) for (b in 0..size - 11) {
                var f1 = true; var f2 = true
                for (k2 in 0 until 11) { val v = if (t == 0) m[a][b + k2] else m[b + k2][a]; if (v != pat[k2]) f1 = false; if (v != pat[10 - k2]) f2 = false }
                if (f1) s += 40; if (f2) s += 40
            }
            var dark = 0; for (y in 0 until size) for (x in 0 until size) if (m[y][x]) dark++
            s += Math.abs(dark * 20 - size * size * 10) / (size * size) * 10
            return s
        }
        var best = 0; var bp = Int.MAX_VALUE
        for (mk in 0..7) { apply(mk); val pn = penalty(); if (pn < bp) { bp = pn; best = mk }; apply(mk) }
        apply(best); format(best)
        return m
    }

    /** Imagen cuadrada en blanco y negro, con el margen de 4 módulos que pide el estándar. */
    fun bitmap(text: String, px: Int): Bitmap {
        val m = encode(text); val n = m.size + 8
        val cell = maxOf(1, px / n); val side = cell * n
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in m.indices) for (x in m.indices) if (m[y][x]) {
            for (dy in 0 until cell) for (dx in 0 until cell) pixels[((y + 4) * cell + dy) * side + (x + 4) * cell + dx] = Color.BLACK
        }
        bmp.setPixels(pixels, 0, side, 0, 0, side, side)
        return bmp
    }
}
