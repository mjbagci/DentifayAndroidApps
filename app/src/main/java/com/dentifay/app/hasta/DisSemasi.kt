package com.dentifay.app.hasta

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Diş şeması kuralları — web `lib/fdi.js` · `lib/tedavi-konumu.js#kalemDisleri` · `treatments/toothAnnotations.js` ikizi (DentAIWeb @ afc3170;
 * MOBIL_IS_EMRI M-E8.3). Görsel tahmini YOK: simge/katman yalnız `catalog.visualKey`ten (kapalı küme `sema-gorselleri.data.json`).
 */
object DisSemasi {

    // MARK: FDI sırası (hekim hastaya bakar — hastanın SAĞI ekranda SOLDA)
    val daimiUst = listOf("18", "17", "16", "15", "14", "13", "12", "11", "21", "22", "23", "24", "25", "26", "27", "28")
    val daimiAlt = listOf("48", "47", "46", "45", "44", "43", "42", "41", "31", "32", "33", "34", "35", "36", "37", "38")
    val sutUst = listOf("55", "54", "53", "52", "51", "61", "62", "63", "64", "65")
    val sutAlt = listOf("85", "84", "83", "82", "81", "71", "72", "73", "74", "75")

    private fun rakam(c: Char?): Int? = if (c != null && c in '0'..'9') c - '0' else null

    fun kadran(dis: String): Int? = rakam(dis.firstOrNull())
    fun sutMu(dis: String): Boolean = (kadran(dis) ?: 0) in 5..8
    fun ustMu(dis: String): Boolean = (kadran(dis) ?: 0) in setOf(1, 2, 5, 6)
    fun solMu(dis: String): Boolean = (kadran(dis) ?: 0) in setOf(2, 3, 6, 7)
    fun konumNo(dis: String): Int = if (dis.length == 2) rakam(dis.last()) ?: 1 else 1

    /** Yarım çenenin 8 daimi dişi (ör. 2 → 21…28). */
    fun kadranDisleri(k: Int): List<String> = (1..8).map { "$k$it" }

    fun ceneDisleri(cene: String): List<String> =
        if (cene == "UPPER") kadranDisleri(1) + kadranDisleri(2) else kadranDisleri(3) + kadranDisleri(4)

    // MARK: Kalemin dişleri (`kalemDisleri`)

    /**
     * Grup satırında CANLI çocukların dişleri (sırayla, tekrarsız); tek kalemde `teeth[]` → `toothCodes` → `tooth` → yarım çene →
     * çene; konumsuz → `[]` (şemada boyanmaz).
     */
    fun kalemDisleri(k: TedaviKalemi, yalnizAsama: String? = null): List<String> {
        val cocuklar = k.children
        if (!cocuklar.isNullOrEmpty()) {
            val out = LinkedHashSet<String>()
            for (c in cocuklar) {
                if (c.deletedAt != null || (yalnizAsama != null && c.stage != yalnizAsama)) continue
                out.addAll(kalemDisleri(c))
            }
            return out.toList()
        }
        val kodlar = (k.teeth ?: emptyList()).map { it.toothCode }.filter { it.trim(' ', '\t').isNotEmpty() }
        val liste = kodlar.ifEmpty { (k.toothCodes ?: emptyList()).map { it.trim(' ', '\t') }.filter { it.isNotEmpty() } }
        if (liste.isNotEmpty()) return liste
        k.tooth?.trim(' ', '\t')?.takeIf { it.isNotEmpty() }?.let { return listOf(it) }
        k.quadrant?.takeIf { it in 1..4 }?.let { return kadranDisleri(it) }
        k.jawType?.trim(' ', '\t')?.uppercase()?.takeIf { it == "UPPER" || it == "LOWER" }?.let { return ceneDisleri(it) }
        return emptyList()
    }

    /** Tablo hücresi konum etiketi (`konumEtiketi`, öneksiz): "11, 12, 13" · "Üst çene" · "Sağ üst yarım çene"; konumsuz → "". */
    fun konumEtiketi(k: TedaviKalemi): String {
        val cocuk = if (k.children.isNullOrEmpty()) emptyList()
        else kalemDisleri(TedaviKalemi(id = "", name = "", stage = null, children = k.children))
        val ilisk = (k.teeth ?: emptyList()).map { it.toothCode }.filter { it.isNotEmpty() }
        val kodlar = ilisk.ifEmpty { cocuk.ifEmpty { k.toothCodes ?: emptyList() } }
        val disler = kodlar.ifEmpty { listOfNotNull(k.tooth) }
        if (disler.isNotEmpty()) return disler.joinToString(", ")
        k.quadrant?.takeIf { it in 1..4 }?.let { return kadranAdlari[it - 1] }
        return when (k.jawType?.uppercase()) {
            "UPPER" -> "Üst çene"
            "LOWER" -> "Alt çene"
            else -> ""
        }
    }

    val kadranAdlari = listOf("Sağ üst yarım çene", "Sol üst yarım çene", "Sol alt yarım çene", "Sağ alt yarım çene")

    // MARK: Görsel kodlar ve tonlar

    enum class Gorsel(
        /** `treatments.toothSymbols.*` (TR). */
        val rozet: String,
        /** Rozet zemini (Tailwind -700 tonları), 0xRRGGBB. */
        val rozetRengi: Int,
    ) {
        crown("KURON", 0x0E7490),
        veneer("LAM", 0xBE185D),
        rootcanal("KANAL", 0xBE123C),
        filling("DOLGU", 0x1D4ED8),
        implant("IMPLANT", 0x047857),
        sealant("Fissür örtücü", 0x0F766E),
        ortho("ORTO", 0xC2410C),
        extraction("ÇEK", 0x475569);

        val renk: Color get() = Color(0xFF000000L or rozetRengi.toLong())

        companion object {
            /** Swift `Gorsel(rawValue:)` — tanınmayan kod → `null`. */
            fun coz(ham: String?): Gorsel? = if (ham == null) null else entries.firstOrNull { it.name == ham }
        }
    }

    /** Aşama tonu: Yapılan > Yapılacak > Planlandı. */
    enum class Ton { yapildi, yapilacak, planlandi }

    @Immutable
    data class Aciklama(
        val rozetler: List<Rozet> = emptyList(),
        val katmanlar: List<Gorsel> = emptyList(),
        val kalemler: List<Kalem> = emptyList(),
        val ton: Ton? = null,
    ) {
        data class Rozet(val gorsel: Gorsel, val baslik: String)

        /** Swift `(ad: String, asama: String?)`. */
        data class Kalem(val ad: String, val asama: String?)
    }

    private class Insa {
        val rozetler = mutableListOf<Aciklama.Rozet>()
        val katmanlar = mutableListOf<Gorsel>()
        val kalemler = mutableListOf<Aciklama.Kalem>()
    }

    /**
     * `buildToothAnnotations`: silinmiş kalem ve çocukları AYNI listede olan grup satırı atlanır; her diş için aşama kümesi,
     * kalemler (sırayla), rozet (aynı etiket bir kez — ilk kalemin adı) ve katman (tekrarsız, ekleme sırası); ton Yapılan > Yapılacak > Planlandı.
     */
    fun aciklamalar(kalemler: List<TedaviKalemi>): Map<String, Aciklama> {
        val ebeveynler = kalemler.mapNotNullTo(HashSet()) { it.parentItemId }
        val insa = LinkedHashMap<String, Insa>()
        val asamalar = HashMap<String, MutableSet<String>>()
        for (k in kalemler) {
            if (k.deletedAt != null || k.id in ebeveynler) continue
            val gorsel = Gorsel.coz(k.catalog?.visualKey)
            for (d in kalemDisleri(k)) {
                val a = insa.getOrPut(d) { Insa() }
                k.stage?.let { asamalar.getOrPut(d) { HashSet() }.add(it) }
                a.kalemler += Aciklama.Kalem(k.name, k.stage)
                if (gorsel != null) {
                    if (a.rozetler.none { it.gorsel.rozet == gorsel.rozet }) a.rozetler += Aciklama.Rozet(gorsel, k.name)
                    if (gorsel !in a.katmanlar) a.katmanlar += gorsel
                }
            }
        }
        val sonuc = LinkedHashMap<String, Aciklama>(insa.size)
        for ((d, a) in insa) {
            val s = asamalar[d].orEmpty()
            val ton = when {
                "DONE" in s -> Ton.yapildi
                "TODO" in s -> Ton.yapilacak
                "PLANNED" in s -> Ton.planlandi
                else -> null
            }
            sonuc[d] = Aciklama(a.rozetler.toList(), a.katmanlar.toList(), a.kalemler.toList(), ton)
        }
        return sonuc
    }

    /** Planların bütün kalemleri (silinmiş plan hariç; aşama yoksa "PLANNED"). */
    fun planAciklamalari(planlar: List<HastaPlani>): Map<String, Aciklama> =
        aciklamalar(planlar.filter { it.deletedAt == null }.flatMap { it.items ?: emptyList() })

    /** Otomatik görünüm: herhangi bir açıklamalı diş süt dişiyse "karma", değilse "daimi". */
    fun karmaMi(a: Map<String, Aciklama>): Boolean = a.keys.any { sutMu(it) }

    // MARK: Etiketler (web `treatments.*`, TR — BİREBİR)

    fun asamaAdi(s: String?): String = when (s) {
        "DONE" -> "Yapıldı"
        "TODO" -> "Yapılacak"
        "PLANNED" -> "Planlandı"
        else -> ""
    }

    fun planDurumu(s: String?): String = when (s) {
        "APPROVED" -> "Onaylandı"
        "CANCELLED" -> "İptal"
        "COMPLETED" -> "Tamamlandı"
        "DRAFT" -> "Taslak"
        "IN_PROGRESS" -> "Aktif"
        "PROPOSED" -> "Önerildi"
        "REJECTED" -> "Reddedildi"
        else -> s ?: ""
    }
}
