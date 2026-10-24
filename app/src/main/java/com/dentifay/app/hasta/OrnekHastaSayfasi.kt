package com.dentifay.app.hasta

import com.dentifay.app.core.EsnekSayi

/**
 * Kurmaca hasta sayfası örneği (gerçek veri YOK — M0-8): iki plan, çok dişli kalem, süt dişi, Yapılacak/Yapılan satırları, birkaç
 * görsel. Önizlemeler ve testler kullanır; uygulama akışı kullanmaz. `buyukPlan` / `medya200` iOS ölçüm tohumunun (100 kalemli plan,
 * 200 görsel) karşılığıdır — büyük veride kaydırma önizlemesi için.
 */
object OrnekHastaSayfasi {
    val hekimA = KisiOzeti(id = "demo", name = "Selin Demir")
    val hekimB = KisiOzeti(id = "d2", name = "Burak Kaya")

    val hasta = HastaBasligi(id = "p1", ad = "Deneme Hasta", dosyaNo = "1024", telefon = "0555 000 00 00")

    val kalemler1: List<TedaviKalemi>
        get() = listOf(
            TedaviKalemi(
                id = "k1", name = "Kompozit Dolgu", stage = "DONE", tooth = "36", visualKey = "filling", price = 1800.0,
                totalAmountTRY = 1800.0, doktor = hekimA, treatmentDate = "2026-10-10", planId = "p1",
            ),
            TedaviKalemi(
                id = "k2", name = "Kanal Tedavisi", stage = "TODO", tooth = "46", visualKey = "rootcanal", price = 6500.0,
                totalAmountTRY = 6500.0, doktor = hekimA, treatmentDate = "2026-10-12", planId = "p1",
            ),
            TedaviKalemi(
                id = "k3", name = "Zirkonyum Kuron", stage = "PLANNED", visualKey = "crown",
                children = listOf(
                    TedaviKalemi(id = "k3a", name = "Zirkonyum Kuron", stage = "PLANNED", tooth = "11", price = 6000.0, totalAmountTRY = 6000.0),
                    TedaviKalemi(id = "k3b", name = "Zirkonyum Kuron", stage = "PLANNED", tooth = "12", price = 6000.0, totalAmountTRY = 6000.0),
                    TedaviKalemi(
                        id = "k3c", name = "Zirkonyum Kuron", stage = "PLANNED", tooth = "21", price = 6000.0, discount = 600.0,
                        totalAmountTRY = 5400.0,
                    ),
                ),
                doktor = hekimB, treatmentDate = "2026-10-12", planId = "p1",
            ),
            TedaviKalemi(
                id = "k4", name = "İmplant", stage = "PLANNED", tooth = "26", visualKey = "implant", price = 18000.0,
                totalAmountTRY = 18000.0, doktor = hekimB, treatmentDate = "2026-10-12", planId = "p1",
            ),
            TedaviKalemi(
                id = "k5", name = "Diş Çekimi", stage = "TODO", tooth = "48", visualKey = "extraction", price = 1500.0,
                totalAmountTRY = 1500.0, doktor = hekimA, treatmentDate = "2026-10-12", planId = "p1",
            ),
        )

    val planlar: List<HastaPlani>
        get() = listOf(
            plan("p1", "Plan 1", "IN_PROGRESS", aktif = true, planlanan = 45200.0, tamam = 1800.0, kalemler = null, onam = null),
            plan("p2", "Plan 2", "DRAFT", aktif = false, planlanan = 1200.0, tamam = 0.0, kalemler = null, onam = null),
        )

    fun ayrinti(id: String): HastaPlani =
        if (id == "p1") {
            plan("p1", "Plan 1", "IN_PROGRESS", aktif = true, planlanan = 45200.0, tamam = 1800.0, kalemler = kalemler1, onam = "Bekliyor")
        } else {
            plan(
                "p2", "Plan 2", "DRAFT", aktif = false, planlanan = 1200.0, tamam = 0.0,
                kalemler = listOf(
                    TedaviKalemi(
                        id = "k9", name = "Fissür Örtücü", stage = "PLANNED", tooth = "55", visualKey = "sealant", price = 1200.0,
                        totalAmountTRY = 1200.0, doktor = hekimA, treatmentDate = "2026-10-20", planId = "p2",
                    ),
                ),
                onam = "Yok",
            )
        }

    /** Aşama listesi satırları (`gruplu=1` biçimi: plan özeti satırda). */
    fun asama(asama: String): List<TedaviKalemi> =
        kalemler1.filter { it.stage == asama }.map { it.copy(plan = TedaviKalemi.PlanOzeti(id = "p1", title = "Plan 1", status = "IN_PROGRESS")) }

    val medya: MedyaYaniti
        get() = MedyaYaniti(
            media = listOf(
                gorsel("m1", "XRAY_PANORAMIC", "panoramik.jpg", favori = true),
                gorsel("m2", "PHOTO_INTRAORAL", "agiz-ici-1.jpg"),
                gorsel("m3", "PHOTO_SMILE", "gulus.jpg"),
                gorsel("m4", "XRAY_PERIAPICAL", "periapikal-36.jpg"),
                HastaMedyasi(
                    id = "m5", category = "DOCUMENT_CONSENT", fileName = "onam-formu.pdf", fileUrl = "/uploads/ornek/onam-formu.pdf",
                    mimeType = "application/pdf", createdAt = "2026-10-12T09:00:00.000Z",
                ),
            ),
            counts = mapOf("XRAY_PANORAMIC" to 1, "XRAY_PERIAPICAL" to 1, "PHOTO_INTRAORAL" to 1, "PHOTO_SMILE" to 1, "DOCUMENT_CONSENT" to 1),
            can = MedyaYaniti.Yetki(trash = true, purge = false),
        )

    private fun gorsel(id: String, kategori: String, ad: String, favori: Boolean = false) = HastaMedyasi(
        id = id, category = kategori, fileName = ad, fileUrl = "/uploads/ornek/$ad", thumbnailUrl = null, mimeType = "image/jpeg",
        capturedAt = "2026-10-12T09:00:00.000Z", createdAt = "2026-10-12T09:00:00.000Z", isFavorite = favori,
    )

    private fun plan(
        id: String,
        ad: String,
        durum: String,
        aktif: Boolean,
        planlanan: Double,
        tamam: Double,
        kalemler: List<TedaviKalemi>?,
        onam: String?,
    ) = HastaPlani(
        id = id, title = ad, status = durum, isActive = aktif, totalPrice = EsnekSayi(planlanan), plannedTotal = EsnekSayi(planlanan),
        completedTotal = EsnekSayi(tamam), treatmentDate = "2026-10-12", createdAt = "2026-10-12", notes = null, doctorUserId = "demo",
        doctor = hekimA, items = kalemler, version = 1, onayBekliyor = false, kapali = false, sistemPlani = false,
        kalemSayisi = kalemler?.size, onamEtiketi = onam, deletedAt = null,
    )

    // MARK: Büyük veri (iOS `OlcumTohumu` karşılığı — 100 kalem, 200 görsel; ağ yok)

    private val tedaviler = listOf(
        Triple("Kompozit Dolgu", "filling", 1800.0), Triple("Kanal Tedavisi", "rootcanal", 6500.0),
        Triple("Zirkonyum Kuron", "crown", 6000.0), Triple("İmplant", "implant", 18000.0),
        Triple("Diş Çekimi", "extraction", 1500.0), Triple("Fissür Örtücü", "sealant", 900.0),
    )

    val buyukPlan: HastaPlani
        get() {
            val disler = DisSemasi.daimiUst + DisSemasi.daimiAlt
            val asamalar = listOf("PLANNED", "TODO", "DONE")
            val kalemler = (0 until 100).map { i ->
                val t = tedaviler[i % tedaviler.size]
                TedaviKalemi(
                    id = "o$i", name = t.first, stage = asamalar[i % 3], tooth = disler[i % disler.size], visualKey = t.second,
                    price = t.third, totalAmountTRY = t.third, doktor = hekimA, treatmentDate = "2026-10-12", planId = "p1",
                )
            }
            val toplam = kalemler.sumOf { it.totalAmountTRY?.deger ?: 0.0 }
            return plan("p1", "Plan 1", "IN_PROGRESS", aktif = true, planlanan = toplam, tamam = 0.0, kalemler = kalemler, onam = null)
        }

    val medya200: MedyaYaniti
        get() {
            val kategoriler = listOf("XRAY_PANORAMIC", "PHOTO_INTRAORAL", "PHOTO_FACE", "DOCUMENT_CONSENT")
            return MedyaYaniti(media = (0 until 200).map { i -> gorsel("m$i", kategoriler[i % kategoriler.size], "olcum-$i.jpg") })
        }

    /** Önizleme modeli: ağ yok, bölüm hazır; Planlama'da ilk kart açık. */
    fun model(bolum: HastaSayfasiModeli.Bolum = HastaSayfasiModeli.Bolum.planlama, buyuk: Boolean = false): HastaSayfasiModeli {
        val buyukP = if (buyuk) buyukPlan else null
        val m = HastaSayfasiModeli(hasta, OrnekKaynak, null)
        m.ornekVeriYukle(
            planlar = buyukP?.let { listOf(it) } ?: planlar,
            sonrakiPlanAdi = "Plan 3",
            ayrintilar = buyukP?.let { mapOf("p1" to it) } ?: mapOf("p1" to ayrinti("p1"), "p2" to ayrinti("p2")),
            asamalar = mapOf("TODO" to asama("TODO"), "DONE" to asama("DONE")),
            medya = if (buyuk) medya200 else medya,
            acik = setOf("p1"),
            bolum = bolum,
        )
        return m
    }

    /** Ağsız kaynak (önizlemede bölüm tazelenirse aynı örnek veri döner). */
    object OrnekKaynak : HastaSayfasiModeli.Kaynak {
        override suspend fun planlar(hastaId: String): PlanListesiYaniti = PlanListesiYaniti(OrnekHastaSayfasi.planlar, "Plan 3")
        override suspend fun ayrinti(planId: String): HastaPlani = OrnekHastaSayfasi.ayrinti(planId)
        override suspend fun asama(hastaId: String, asama: String): List<TedaviKalemi> = OrnekHastaSayfasi.asama(asama)
        override suspend fun medya(hastaId: String, imlec: String?): MedyaYaniti = OrnekHastaSayfasi.medya
    }
}
