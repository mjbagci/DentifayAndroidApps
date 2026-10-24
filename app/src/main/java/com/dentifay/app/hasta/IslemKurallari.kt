package com.dentifay.app.hasta

import com.dentifay.app.core.Yetki
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Hasta sayfası işlemlerinin SAF kuralları (MOBIL_IS_EMRI M-E8.4) — web `treatments/islem-pasifligi.js` · `features/treatments/yapilana-aktar.js` ·
 * `lib/treatment-pricing.js#{satirIndirimi, planIndirimleri}` ikizleri. Bölüm B (DentAIWeb @ 33c26896, 52D): Yapılan satırda geri alma, indirim, kur,
 * parçalama ve geri yükleme AÇIK (hakediş etkisi onayıyla — `HakedisOnayi`); yalnız silme kapalı (sunucu da 409 `E_PLAN_EDIT_DONE_DELETE`).
 */
object IslemKurallari {

    /** Bölüm A'da Yapılan (DONE) satıra dokunan işlemin metni (Ek MO.8) — Bölüm B'de kullanılmaz, tarihçe için durur. */
    const val SONRAKI_SURUM = "Bu işlem uygulamanın sonraki sürümünde açılacak."
    /** Web `treatments.itemActions.pasif.yapilan`. */
    const val YAPILAN_SILINMEZ = "Yapılan kalem silinemez; önce geri alın."
    const val YETKI_YOK = Yetki.EYLEM_YETKISIZ_METNI
    const val DUZENLEME_YETENEGI = "chart.plan.edit"

    sealed class Durum {
        data object acik : Durum()
        data class pasif(val metin: String) : Durum()

        val acikMi: Boolean get() = this is acik
        val neden: String? get() = (this as? pasif)?.metin
    }

    enum class KalemIslemi { yapilacaga, yapilana, planlamaya, indirim, doviz, parcala, geriYukle, sil }

    /** Çok dişli satırda aktarılacak kimlikler (`cocukKimlikleri`), değilse `[id]` (web `aktarmaKimlikleri`). */
    fun aktarmaKimlikleri(kalemler: List<TedaviKalemi>): List<String> = kalemler.flatMap { k ->
        val c = k.cocukKimlikleri
        if (!c.isNullOrEmpty()) c else listOf(k.id)
    }

    private fun yapilanVar(k: List<TedaviKalemi>): Boolean =
        k.any { it.stage == "DONE" || it.canliCocuklar.any { c -> c.stage == "DONE" } }

    fun durum(islem: KalemIslemi, kalemler: List<TedaviKalemi>, yetenekler: Set<String>): Durum {
        if (DUZENLEME_YETENEGI !in yetenekler) return Durum.pasif(YETKI_YOK)
        if (kalemler.isEmpty()) return Durum.pasif("Önce bir kalem seçin")
        val silinmis = kalemler.any { it.deletedAt != null }
        return when (islem) {
            KalemIslemi.geriYukle ->
                if (kalemler.size == 1 && silinmis) Durum.acik else Durum.pasif("Önce bir kalem seçin")
            KalemIslemi.yapilacaga, KalemIslemi.planlamaya -> {
                val hedef = if (islem == KalemIslemi.yapilacaga) "TODO" else "PLANNED"
                when {
                    silinmis -> Durum.pasif("Silinmiş kalem — önce geri yükleyin")
                    kalemler.all { it.stage == hedef } ->
                        Durum.pasif(if (hedef == "TODO") "Seçili kalemler zaten yapılacak tedavilerde" else "Seçili kalemler zaten planlamada")
                    else -> Durum.acik
                }
            }
            KalemIslemi.yapilana -> when {
                silinmis -> Durum.pasif("Silinmiş kalem — önce geri yükleyin")
                kalemler.all { it.stage == "DONE" } -> Durum.pasif("Seçili kalemler zaten yapılan tedavilerde")
                else -> Durum.acik   // normal tamamlama AÇIK (onay istemez)
            }
            KalemIslemi.indirim, KalemIslemi.doviz, KalemIslemi.parcala, KalemIslemi.sil -> {
                val k = kalemler.first()
                when {
                    silinmis -> Durum.pasif("Silinmiş kalem — önce geri yükleyin")
                    islem == KalemIslemi.sil && yapilanVar(kalemler) -> Durum.pasif(YAPILAN_SILINMEZ)
                    islem != KalemIslemi.sil && kalemler.size != 1 -> Durum.pasif("Önce bir kalem seçin")
                    kalemler.any { it.kismi == true } ->
                        Durum.pasif("Bu kalemin dişleri farklı aşamalarda; indirim, kur ve silme için \"Planı düzenle\"yi kullanın.")
                    islem == KalemIslemi.parcala &&
                        (k.grupMu || DisSemasi.kalemDisleri(k).size < 2 || k.cocukKimlikleri?.isEmpty() == false) ->
                        Durum.pasif("Önce bir kalem seçin")
                    else -> Durum.acik
                }
            }
        }
    }

    enum class PlanIslemi { duzenle, ozellestir, paylas, sifirla, geriYukle, sil, hekimDegistir }

    fun planDurumu(islem: PlanIslemi, plan: HastaPlani, yetenekler: Set<String>): Durum {
        if (islem == PlanIslemi.paylas) return Durum.acik
        if (DUZENLEME_YETENEGI !in yetenekler) return Durum.pasif(YETKI_YOK)
        val kalemler = (plan.items ?: emptyList()).filter { it.deletedAt == null }
        return when (islem) {
            PlanIslemi.paylas, PlanIslemi.ozellestir -> Durum.acik
            PlanIslemi.duzenle ->
                if ((plan.status ?: "") in listOf("COMPLETED", "CANCELLED", "REJECTED")) Durum.pasif("Plan kapalı") else Durum.acik
            PlanIslemi.sifirla -> {
                val hepsiPlanda = kalemler.all { k ->
                    if (k.grupMu) k.canliCocuklar.all { it.stage == "PLANNED" } else k.stage == "PLANNED"
                }
                if (hepsiPlanda) Durum.pasif("Aktarılmış kalem yok — bütün kalemler zaten planlamada") else Durum.acik
            }
            // Yapılmış tedavili plan silinmez — sunucunun 409 `PLAN_HAS_DONE_ITEMS` cümlesi aynen gösterilir (web de düğmeyi açık tutar).
            PlanIslemi.sil, PlanIslemi.geriYukle, PlanIslemi.hekimDegistir -> Durum.acik
        }
    }

    // MARK: Yapılan'a aktar — hekim (web `yapilana-aktar.js`)

    /** Kalemin varsayılan hekimi: birden çok hekimli grup satırında BOŞ (seçilmeli); değilse kalem hekimi → plan hekimi → boş. */
    fun kalemHekimi(k: TedaviKalemi, planHekimi: String?): String {
        if ((k.hekimler?.size ?: 0) > 1) return ""
        return k.assignedDoctorId ?: k.assignedDoctor?.id ?: planHekimi ?: ""
    }

    /** Swift `(tek: Bool, secimler: [String: String])`. */
    data class BaslangicSecimi(val tek: Boolean, val secimler: Map<String, String>)

    /** Ayrı değer ≤ 1 → TEK seçici (`ortak`); > 1 → kalem başına seçici. */
    fun baslangicSecimi(k: List<TedaviKalemi>, planHekimi: (TedaviKalemi) -> String?): BaslangicSecimi {
        val s = LinkedHashMap<String, String>()
        for (kalem in k) s[kalem.id] = kalemHekimi(kalem, planHekimi(kalem))
        return BaslangicSecimi(s.values.toSet().size <= 1, s)
    }

    /** Swift `(hekimId: String?, hekimler: [String: String]?)`. */
    data class AktarGovdesi(val hekimId: String?, val hekimler: Map<String, String>?)

    /** Gövde: tek → `hekimId`; kalem başına → `hekimler` (anahtar SATIR kimliği). Boş seçim varsa `null` (Kaydet kapalı). */
    fun aktarGovdesi(tek: Boolean, secimler: Map<String, String>): AktarGovdesi? {
        if (tek) {
            val ortak = secimler.values.firstOrNull()
            if (ortak.isNullOrEmpty()) return null
            return AktarGovdesi(ortak, null)
        }
        if (!secimler.values.all { it.isNotEmpty() }) return null
        return AktarGovdesi(null, secimler)
    }

    // MARK: İndirim ikizi (`satirIndirimi` / `planIndirimleri` — DentAIWeb 52B @ 33c26896, M-E8.11)

    data class Satir(
        val fiyat: Double,
        val adet: Int = 1,
        val kur: Double? = null,
        val indirim: Double = 0.0,
        val yuzdeMi: Boolean = false,
        /** Yapılan satır — toplu pay ALMAZ (yalnız kendi kalem indirimi). */
        val yapilan: Boolean = false,
        /** `false` → karar bekleyen alternatif: toplama girmez, etkin oranla kendi indirimini alır. */
        val dahil: Boolean = true,
    )

    data class SatirSonucu(val liste: Double, val indirim: Double, val hasta: Double, val son: Double, val toplamIndirim: Double)

    /** Toplu indirim kipleri: yüzde · TL (düşülecek tutar) · hedef toplam. */
    enum class TopluKip(val rawValue: String) { yuzde("YUZDE"), tl("TL"), hedef("HEDEF") }

    data class PlanIndirimi(
        val satirlar: List<SatirSonucu>,
        val liste: Double,
        val indirim: Double,
        val hasta: Double,
        /** Toplu indirim (TL). */
        val toplu: Double,
        val net: Double,
        val acikToplam: Double,
        val gecersiz: Boolean,
    )

    fun yuvarla(x: Double): Double = yakinaYuvarla(x * 100) / 100

    /** Kuruş (Swift `Int` 64 bit — burada `Long`). */
    private fun kurus(x: Double): Long = yakinaYuvarla(x * 100).toLong()

    /** `kalemBrutTL`: fiyat × adet × kur, ondalık (Decimal) çarpılıp kuruşa yuvarlanır (adet < 1 → 1; kur ≤ 0/yok → 1). */
    fun brut(s: Satir): Double {
        val adet = max(1, s.adet)
        val kurHam = s.kur ?: 1.0
        val kur = if (kurHam > 0) kurHam else 1.0
        val d = ondalik(s.fiyat).multiply(BigDecimal.valueOf(adet.toLong())).multiply(ondalik(kur))
        // NSDecimalRound `.plain` = yarıda sıfırdan uzağa = HALF_UP.
        return d.setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    /** Swift `Decimal(string: String(x))` — sayının kısa ondalık yazımından (ikili açılımından DEĞİL). */
    private fun ondalik(x: Double): BigDecimal = if (x.isFinite()) BigDecimal(x.toString()) else BigDecimal.ZERO

    /** Swift `(liste: Double, indirim: Double, hasta: Double)`. */
    data class SatirIndirimi(val liste: Double, val indirim: Double, val hasta: Double)

    fun satirIndirimi(s: Satir): SatirIndirimi {
        val liste = brut(s)
        val indirim = if (s.yuzdeMi) liste * s.indirim / 100 else s.indirim
        return SatirIndirimi(yuvarla(liste), yuvarla(indirim), yuvarla(max(0.0, liste - indirim)))
    }

    /** Kuruş dağıtımı — EN BÜYÜK KALAN (toplam kuruşu kuruşuna korunur; eşitlikte önce gelen satır). */
    fun kurusDagit(toplam: Long, agirliklar: List<Long>): List<Long> {
        val w = agirliklar.sum()
        if (w <= 0 || toplam <= 0) return agirliklar.map { 0L }
        val ham = agirliklar.map { toplam.toDouble() * it.toDouble() / w.toDouble() }
        val taban = ham.map { floor(it).toLong() }.toMutableList()
        var kalan = toplam - taban.sum()
        val sira = ham.mapIndexed { i, h -> i to (h - floor(h)) }
            .sortedWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else a.first.compareTo(b.first) }
        var j = 0
        while (kalan > 0 && j < sira.size) {
            taban[sira[j].first] += 1
            kalan -= 1
            j += 1
        }
        return taban
    }

    private class Hesap(val s: Satir, val c: SatirIndirimi, val acik: Boolean)

    /**
     * Kalem indirimleri + toplu indirim (üç kip). Açık satır = dahil VE Yapılan değil. İndirim: yüzde × açık toplam · TL değeri ·
     * (açık + Yapılan) − hedef; 0 ≤ indirim ≤ açık toplam değilse `gecersiz` (uygulanmaz). Açık satırlara kalem indirimi sonrası
     * tutarlarına ORANLA, kuruş en büyük kalanla; dahil olmayan satır etkin oranla; Yapılan satır pay almaz.
     */
    fun planIndirimleri(satirlar: List<Satir>, kip: TopluKip = TopluKip.yuzde, deger: Double): PlanIndirimi {
        val h = satirlar.map { s -> Hesap(s, satirIndirimi(s), s.dahil && !s.yapilan) }
        var liste = 0.0
        var indirim = 0.0
        var hasta = 0.0
        for (x in h) if (x.s.dahil) {
            liste += x.c.liste
            indirim += x.c.indirim
            hasta += x.c.hasta
        }
        val acikKurus = h.fold(0L) { a, x -> a + if (x.acik) kurus(x.c.hasta) else 0L }
        val yapilanKurus = h.fold(0L) { a, x -> a + if (x.s.yapilan && x.s.dahil) kurus(x.c.hasta) else 0L }
        val yuzde = min(100.0, max(0.0, deger))
        val istenen: Long = when (kip) {
            TopluKip.tl -> kurus(deger)
            TopluKip.hedef -> acikKurus + yapilanKurus - kurus(deger)
            TopluKip.yuzde -> yakinaYuvarla(yuzde / 100 * acikKurus.toDouble()).toLong()
        }
        val gecersiz = istenen < 0 || istenen > acikKurus || (kip != TopluKip.yuzde && deger < 0)
        val topluKurus = if (gecersiz) 0L else istenen
        val paylar = kurusDagit(topluKurus, h.map { if (it.acik) kurus(it.c.hasta) else 0L })
        val oran = if (kip == TopluKip.yuzde) yuzde / 100 else (if (acikKurus > 0) topluKurus.toDouble() / acikKurus.toDouble() else 0.0)
        val sonuc = h.mapIndexed { i, x ->
            var pay = 0L
            if (x.acik) {
                pay = paylar[i]
            } else if (!x.s.dahil && !x.s.yapilan) {
                pay = yakinaYuvarla(kurus(x.c.hasta).toDouble() * oran).toLong()
            }
            SatirSonucu(
                liste = x.c.liste, indirim = x.c.indirim, hasta = x.c.hasta,
                son = (kurus(x.c.hasta) - pay).toDouble() / 100,
                toplamIndirim = (kurus(x.c.indirim) + pay).toDouble() / 100,
            )
        }
        return PlanIndirimi(
            satirlar = sonuc, liste = yuvarla(liste), indirim = yuvarla(indirim), hasta = yuvarla(hasta),
            toplu = topluKurus.toDouble() / 100,
            net = max(0.0, (kurus(yuvarla(hasta)) - topluKurus).toDouble() / 100),
            acikToplam = acikKurus.toDouble() / 100,
            gecersiz = gecersiz,
        )
    }

    // MARK: Paylaşım metni (web `buildShareText`)

    fun paylasimMetni(plan: HastaPlani, hastaAdi: String): String {
        val satirlar = mutableListOf("*${plan.title?.takeIf { it.isNotEmpty() } ?: "Tedavi Planı"}*", hastaAdi, "")
        val kalemler = (plan.items ?: emptyList()).filter { it.parentItemId == null }
        if (kalemler.isNotEmpty()) {
            satirlar.add("*Tedaviler:*")
            for (k in kalemler.take(20)) {
                val disler = (k.teeth ?: emptyList()).map { it.toothCode }
                val dis = k.tooth ?: (if (disler.isEmpty()) "—" else disler.joinToString(","))
                satirlar.add("• $dis · ${k.name} — ${Para.tl(PlanTutari.satirTutari(k))}")
            }
            if (kalemler.size > 20) satirlar.add("(+${kalemler.size - 20} daha)")
        }
        satirlar.add("")
        satirlar.add("*Planlanan Toplam:* ${Para.tl(plan.plannedTotal?.deger)}")
        return satirlar.joinToString("\n")
    }
}
