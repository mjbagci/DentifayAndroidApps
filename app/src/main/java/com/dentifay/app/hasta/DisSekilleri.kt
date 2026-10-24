package com.dentifay.app.hasta

import androidx.compose.ui.graphics.Path

/**
 * Süt dişi çizimleri — web `dental/ToothShape.jsx` UPPER/LOWER tabloları (yol verileri BİREBİR). Süt dişi konumu kalıcı şekle eşlenir
 * (PRIMARY_SHAPE_POS {1:1, 2:2, 3:3, 4:7, 5:6} — süt birinci azı ikinci büyük azı şeklini, süt ikinci azı birinci büyük azı şeklini kullanır).
 */
object DisSekilleri {
    data class Sekil(val kokler: List<String>, val kuron: String, val okluzal: List<String>)

    val sutKonumu: Map<Int, Int> = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 7, 5 to 6)

    fun sekil(dis: String): Sekil? {
        val k = sutKonumu[DisSemasi.konumNo(dis)] ?: return null
        return if (DisSemasi.ustMu(dis)) ust[k] else alt[k]
    }

    val ust: Map<Int, Sekil> = mapOf(
        1 to Sekil(
            kokler = listOf("M 20 60 C 17 44 14 22 19 9 C 24 1 36 1 41 9 C 46 22 43 44 40 60 C 35 64 25 64 20 60 Z"),
            kuron = "M 18 60 C 16 72 16 94 19 104 Q 22 113 30 114 Q 39 112 41 104 C 44 94 44 72 42 60 Q 30 56 18 60 Z",
            okluzal = listOf("M 21 112 Q 30 114 39 112"),
        ),
        2 to Sekil(
            kokler = listOf("M 21 60 C 19 44 17 20 22 9 C 26 2 35 2 39 9 C 44 20 42 44 39 60 C 35 64 25 64 21 60 Z"),
            kuron = "M 20 60 C 18 72 18 96 21 106 Q 24 113 30 114 Q 37 112 40 106 C 43 96 43 72 41 60 Q 30 56 20 60 Z",
            okluzal = listOf("M 22 112 Q 30 114 38 110"),
        ),
        3 to Sekil(
            kokler = listOf("M 19 60 C 14 42 8 14 17 3 C 24 -3 36 -3 43 3 C 52 14 47 42 42 60 C 36 64 24 64 19 60 Z"),
            kuron = "M 18 60 C 15 72 15 94 18 102 Q 22 112 30 118 Q 38 112 42 102 C 45 94 45 72 43 60 Q 30 56 18 60 Z",
            okluzal = listOf("M 21 102 L 30 116 L 39 102"),
        ),
        6 to Sekil(
            kokler = listOf(
                "M 8 60 C 5 46 1 22 8 9 C 14 3 21 6 23 14 C 26 30 24 50 20 60 C 16 64 12 64 8 60 Z",
                "M 26 60 C 24 48 24 22 28 11 C 32 4 37 4 40 11 C 44 22 43 48 40 60 C 35 64 30 64 26 60 Z",
                "M 44 60 C 41 50 40 30 44 14 C 47 6 53 4 58 10 C 64 22 62 46 58 60 C 54 64 48 64 44 60 Z",
            ),
            kuron = "M 6 60 C 4 72 4 94 8 104 Q 13 111 17 110 C 20 108 22 104 24 100 C 26 104 28 108 31 110 C 34 108 36 104 38 100 C 40 104 42 108 45 110 Q 53 111 56 104 C 60 94 60 72 58 60 Q 32 56 6 60 Z",
            okluzal = listOf("M 9 104 Q 17 111 24 100 Q 31 110 38 100 Q 45 111 55 104", "M 49 100 Q 51 104 53 100"),
        ),
        7 to Sekil(
            kokler = listOf(
                "M 10 60 C 7 46 4 22 11 10 C 16 4 22 6 24 14 C 26 30 24 50 20 60 C 16 64 13 64 10 60 Z",
                "M 26 60 C 24 48 24 22 28 11 C 32 4 36 4 39 11 C 42 22 42 48 39 60 C 35 64 30 64 26 60 Z",
                "M 42 60 C 40 50 39 30 42 14 C 45 6 51 4 55 10 C 60 22 58 46 55 60 C 51 64 46 64 42 60 Z",
            ),
            kuron = "M 8 60 C 6 72 6 94 10 104 Q 14 110 18 110 C 21 108 23 104 25 100 C 27 104 28 108 31 110 C 34 108 36 104 38 100 C 40 104 42 108 45 110 Q 51 110 55 104 C 58 94 58 72 56 60 Q 32 56 8 60 Z",
            okluzal = listOf("M 11 104 Q 18 110 25 100 Q 31 110 38 100 Q 45 110 53 104"),
        ),
    )

    val alt: Map<Int, Sekil> = mapOf(
        1 to Sekil(
            kokler = listOf("M 22 60 C 20 44 18 22 23 11 C 26 4 34 4 37 11 C 42 22 40 44 38 60 C 34 64 26 64 22 60 Z"),
            kuron = "M 22 60 C 20 74 20 98 23 106 Q 26 113 30 114 Q 35 113 37 106 C 40 98 40 74 38 60 Q 30 56 22 60 Z",
            okluzal = listOf("M 23 113 L 37 113"),
        ),
        2 to Sekil(
            kokler = listOf("M 21 60 C 19 44 17 20 22 9 C 26 2 35 2 39 9 C 44 20 42 44 39 60 C 35 64 25 64 21 60 Z"),
            kuron = "M 21 60 C 19 74 19 98 22 106 Q 25 113 30 114 Q 36 113 38 106 C 41 98 41 74 39 60 Q 30 56 21 60 Z",
            okluzal = listOf("M 22 113 Q 30 114 38 112"),
        ),
        3 to Sekil(
            kokler = listOf("M 19 60 C 14 42 9 14 18 4 C 24 -1 36 -1 42 4 C 51 14 46 42 42 60 C 36 64 24 64 19 60 Z"),
            kuron = "M 18 60 C 15 72 15 94 18 102 Q 22 112 30 117 Q 38 112 42 102 C 45 94 45 72 43 60 Q 30 56 18 60 Z",
            okluzal = listOf("M 21 102 L 30 115 L 39 102"),
        ),
        6 to Sekil(
            kokler = listOf(
                "M 10 60 C 6 44 4 20 12 9 C 20 2 26 6 28 16 C 30 30 28 50 24 60 C 19 64 14 64 10 60 Z",
                "M 36 60 C 34 50 32 30 36 14 C 39 6 47 4 53 12 C 60 22 58 46 54 60 C 49 64 41 64 36 60 Z",
            ),
            kuron = "M 6 60 C 4 72 4 94 8 104 Q 12 111 16 110 C 19 108 21 104 23 100 C 25 104 27 108 30 110 C 33 108 35 104 37 100 C 39 104 41 108 44 110 C 47 110 51 109 54 104 C 58 94 58 72 56 60 Q 32 56 6 60 Z",
            okluzal = listOf("M 9 104 Q 16 111 23 100 Q 30 110 37 100 Q 44 111 53 104", "M 46 104 Q 49 108 52 104"),
        ),
        7 to Sekil(
            kokler = listOf(
                "M 12 60 C 8 44 6 20 14 9 C 22 2 28 6 30 16 C 32 30 30 50 26 60 C 21 64 16 64 12 60 Z",
                "M 34 60 C 32 50 30 30 34 14 C 37 6 45 4 51 12 C 58 22 56 46 52 60 C 47 64 39 64 34 60 Z",
            ),
            kuron = "M 8 60 C 6 72 6 94 10 104 Q 14 110 18 110 C 21 108 23 104 25 100 C 27 104 28 108 31 110 C 34 108 36 104 38 100 C 40 104 42 108 45 110 Q 51 110 55 104 C 58 94 58 72 56 60 Q 32 56 8 60 Z",
            okluzal = listOf("M 11 104 Q 18 110 25 100 Q 31 110 38 100 Q 45 110 53 104", "M 25 100 L 38 100"),
        ),
    )
}

/**
 * SVG yol ayrıştırıcısı — yalnız web şekillerinde geçen mutlak komutlar (M · L · C · Q · Z). Komut listesi saf (test edilir);
 * Compose `Path` bir kez kurulur ve önbellekte tutulur (çizimde ayrıştırma YOK).
 */
object SVGYolu {
    sealed class Komut {
        data class M(val x: Float, val y: Float) : Komut()
        data class L(val x: Float, val y: Float) : Komut()
        data class C(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Komut()
        data class Q(val x1: Float, val y1: Float, val x: Float, val y: Float) : Komut()
        data object Z : Komut()
    }

    fun komutlar(d: String): List<Komut> {
        val parcalar = d.replace(",", " ").split(" ").filter { it.isNotEmpty() }
        val out = mutableListOf<Komut>()
        var i = 0
        var komut = ""
        fun sayi(): Float = (parcalar.getOrNull(i)?.toDoubleOrNull() ?: 0.0).toFloat().also { i += 1 }
        while (i < parcalar.size) {
            val s = parcalar[i]
            if (s.first().isLetter()) {
                komut = s
                i += 1
                if (komut == "Z") { out += Komut.Z; continue }
            }
            when (komut) {
                "M" -> { val x = sayi(); val y = sayi(); out += Komut.M(x, y); komut = "L" }
                "L" -> { val x = sayi(); val y = sayi(); out += Komut.L(x, y) }
                "C" -> {
                    val x1 = sayi(); val y1 = sayi(); val x2 = sayi(); val y2 = sayi(); val x = sayi(); val y = sayi()
                    out += Komut.C(x1, y1, x2, y2, x, y)
                }
                "Q" -> { val x1 = sayi(); val y1 = sayi(); val x = sayi(); val y = sayi(); out += Komut.Q(x1, y1, x, y) }
                else -> i += 1
            }
        }
        return out
    }

    private val onbellek = HashMap<String, Path>()

    /** Önbellekli Compose yolu (yol uzayında; çizen dönüşümle yerleştirir). */
    fun path(d: String): Path = synchronized(onbellek) {
        onbellek.getOrPut(d) {
            Path().apply {
                for (k in komutlar(d)) when (k) {
                    is Komut.M -> moveTo(k.x, k.y)
                    is Komut.L -> lineTo(k.x, k.y)
                    is Komut.C -> cubicTo(k.x1, k.y1, k.x2, k.y2, k.x, k.y)
                    is Komut.Q -> quadraticTo(k.x1, k.y1, k.x, k.y)
                    Komut.Z -> close()
                }
            }
        }
    }
}
