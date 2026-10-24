package com.dentifay.app.credit

/**
 * @Preview için kurmaca örnek kredi verileri (ağ çağrısı yok). Kişi adları kurmacadır ("Dt. A", "Dt. B",
 * "Deneme Kullanıcı"); gerçek veri YOK.
 */
internal object CreditPreviewData {
    val packs = listOf(
        SmileCreditPack("pack_100", 100.0, priceTL = 100.0, listPriceTL = 100.0, discountPct = 0.0, unitPriceTL = 1.0, savePct = 0, popular = false, bestValue = false),
        SmileCreditPack("pack_500", 500.0, priceTL = 405.0, listPriceTL = 450.0, discountPct = 10.0, unitPriceTL = 0.81, savePct = 10, popular = true, bestValue = false),
        SmileCreditPack("pack_1000", 1000.0, priceTL = null, listPriceTL = 800.0, discountPct = 0.0, unitPriceTL = null, savePct = 20, popular = false, bestValue = true),
    )

    val wallet = SmileWallet(
        availableCredits = 52.0, freeGrantCredits = 30.0, freeUsedCredits = 18.0, freeRemainingCredits = 12.0,
        balanceCredits = 40.0,
        tiers = listOf(
            SmileCreditTier("orta", "Orta", 4.0), SmileCreditTier("orta_iyi", "Orta İyi", 8.0),
            SmileCreditTier("iyi", "İyi", 12.0), SmileCreditTier("cok_iyi", "Çok İyi", null),
        ),
        fixCredits = 0.0, packs = packs, canPurchase = true,
        thisMonth = SmileWallet.Month(period = "2026-10", purchasedTL = 405.0, usedCredits = 46.0, billed = false),
        isDemo = false, lowBalanceWarnCredits = 60.0, isLow = true,
    )

    val usage = CreditUsage(
        wallet = wallet,
        months = listOf(
            CreditUsage.Month("2026-10", 46.0, purchasedTL = 405.0, overageTL = 0.0, billed = false),
            CreditUsage.Month("2026-09", 61.0, purchasedTL = 100.0, overageTL = 0.0, billed = true),
            CreditUsage.Month("2026-08", 12.0, purchasedTL = 0.0, overageTL = 0.0, billed = true),
        ),
        isDemo = false,
    )

    private val dtA = CreditPerson("u1", "Dt. A", "HEKIM_GENEL", active = true, former = false)
    private val deneme = CreditPerson("u2", "Deneme Kullanıcı", "BANKO", active = true, former = false)
    private val dtC = CreditPerson("u4", "Dt. C", "HEKIM_ENDODONTI", active = false, former = false)

    val analysis: CreditAnalysis = run {
        val yogun = mapOf((0 to 10) to 6, (0 to 11) to 4, (1 to 14) to 8, (2 to 9) to 3, (3 to 16) to 5, (4 to 11) to 7, (4 to 12) to 2, (5 to 10) to 1)
        val heatmap = (0 until 7).map { d ->
            (0 until 24).map { h ->
                val k = yogun[d to h] ?: 0
                CreditAnalysis.Cell(k.toDouble(), if (k == 0) 0 else maxOf(1, k / 2))
            }
        }
        CreditAnalysis(
            tz = "Europe/Istanbul", from = "2026-10-01", to = "2026-10-22", truncated = false,
            runwayDays = 34, last30DaysCredits = 46.0, unit = "day",
            summary = CreditAnalysis.Summary(usedCredits = 46.0, refundedCredits = 4.0, operations = 31, purchasedCredits = 500.0, purchases = 1, paidTL = 405.0),
            series = listOf(
                CreditAnalysis.Bucket("2026-10-06", 12.0, mapOf("ai_agent" to 6.0, "before_after" to 4.0, "xray" to 2.0)),
                CreditAnalysis.Bucket("2026-10-13", 20.0, mapOf("ai_agent" to 10.0, "before_after" to 8.0, "xray" to 2.0)),
                CreditAnalysis.Bucket("2026-10-20", 14.0, mapOf("ai_agent" to 8.0, "ai_center" to 2.0, "xray" to 4.0)),
            ),
            features = listOf(
                CreditAnalysis.FeatureRow("ai_agent", 24.0, 18, refunds = 1, share = 52),
                CreditAnalysis.FeatureRow("before_after", 12.0, 3, refunds = 0, share = 26),
                CreditAnalysis.FeatureRow("xray", 8.0, 2, refunds = 1, share = 17),
                CreditAnalysis.FeatureRow("ai_center", 2.0, 2, refunds = 0, share = 5),
            ),
            actions = listOf(
                CreditAnalysis.ActionRow("ai_agent", "chat", 24.0, 18),
                CreditAnalysis.ActionRow("before_after", "iyi", 12.0, 1),
                CreditAnalysis.ActionRow("xray", "analyze", 8.0, 2),
            ),
            heatmap = heatmap,
            users = listOf(
                CreditAnalysis.UserRow("u1", 30.0, 20, "2026-10-22T08:10:00Z", dtA),
                CreditAnalysis.UserRow("u2", 16.0, 11, "2026-10-21T15:00:00Z", deneme),
            ),
        )
    }

    val settings = CreditSettings(lowBalanceWarnCredits = 60.0, version = "e6000", canEdit = true)

    val eBelge = EDocumentUsage(
        hesap = EDocumentUsage.Hesap("h1", "Deneme e-Belge", "TEST"),
        buAy = EDocumentUsage.BuAy(14, mapOf("EFATURA" to 3, "EARSIV" to 10, "ESMM" to 1)),
        kontor = EDocumentUsage.Kontor(18.0, "2026-10-22T07:00:00Z"),
        uyari = "KONTOR_AZ",
    )

    val rows = listOf(
        CreditTransactionsPage.Row("t1", "2026-10-22T08:10:00Z", "kullanim", "ai_agent", "chat", -2.0, "ucretsiz", CreditTransactionsPage.Kisi("u1", "Dt. A")),
        CreditTransactionsPage.Row("t2", "2026-10-21T15:00:00Z", "kullanim", "before_after", "iyi", -12.0, "satin_alinmis", CreditTransactionsPage.Kisi("u2", "Deneme Kullanıcı")),
        CreditTransactionsPage.Row("t3", "2026-10-21T15:05:00Z", "iade", "xray", "analyze", 4.0, null, CreditTransactionsPage.Kisi("u1", "Dt. A")),
        CreditTransactionsPage.Row(
            "t4", "2026-10-15T10:00:00Z", "satin_alma", "purchase", "credit_pack", 500.0, null,
            CreditTransactionsPage.Kisi("u3", "Dt. B"), kosulVar = true, tutarTL = 405.0,
        ),
        CreditTransactionsPage.Row("t5", "2026-10-01T00:00:00Z", "tanim", "platform_grant", "opening", 30.0, null, null),
        CreditTransactionsPage.Row("t6", "2026-10-03T09:30:00Z", "kullanim", "ai_agent", "chat", -1.0, "ucretsiz", CreditTransactionsPage.Kisi("u1", "Dt. A")),
    )

    val people = CreditPeople(
        month = "2026-10",
        features = listOf("ai_agent", "ai_center", "xray", "messaging", "before_after"),
        people = listOf(
            CreditPeople.Person(
                dtA, "DOCTOR", 30.0, 20,
                mapOf("ai_agent" to CreditPeople.Usage(18.0, 15), "xray" to CreditPeople.Usage(8.0, 2), "before_after" to CreditPeople.Usage(4.0, 1)),
                CreditPeople.Limits(40.0, mapOf("before_after" to 12.0)), limitVersion = "v1", remainingCredits = 10.0,
            ),
            CreditPeople.Person(
                deneme, "RECEPTION", 16.0, 11,
                mapOf("ai_agent" to CreditPeople.Usage(6.0, 3), "before_after" to CreditPeople.Usage(10.0, 2)),
                CreditPeople.Limits(null, emptyMap()), limitVersion = "v0", remainingCredits = null,
            ),
            CreditPeople.Person(
                dtC, "DOCTOR", 0.0, 0, emptyMap(),
                CreditPeople.Limits(5.0, emptyMap()), limitVersion = "v2", remainingCredits = 5.0,
            ),
        ),
        nextCursor = null,
        unattributed = CreditPeople.Unattributed(3.0, 2),
        canManageLimits = true,
        maxLimitCredits = 1_000_000.0,
    )

    val tarife = KrediTarifesi(
        tarife = listOf(
            KrediTarifesi.Satir("ai_agent", "chat", 1.0, 2.0, true),
            KrediTarifesi.Satir("xray", "analyze", 4.0, 4.0, false),
            KrediTarifesi.Satir("before_after", "orta", 4.0, 4.0, false),
            KrediTarifesi.Satir("before_after", "iyi", 12.0, 12.0, false),
            KrediTarifesi.Satir("ai_center", "briefing", 2.0, 2.0, false),
            KrediTarifesi.Satir("messaging", "suggest_message", 1.0, 1.0, false),
        ),
        durum = mapOf(
            "ai_agent" to KrediTarifesi.Durum("UCRETLI"), "xray" to KrediTarifesi.Durum("UCRETLI"),
            "before_after" to KrediTarifesi.Durum("UCRETLI"), "ai_center" to KrediTarifesi.Durum("UCRETLI"),
            "messaging" to KrediTarifesi.Durum("BASLAYACAK", "2026-11-25"),
        ),
    )

    /** Koşul metni — indirimli (500'lük) ya da indirimsiz (100'lük) paket. */
    fun kosullar(pack: SmileCreditPack): SmilePurchaseTerms = SmilePurchaseTerms(
        surum = "2026-10-21.1", ozet = "4b1e9c07d2a8f613", baslik = "Kredi Satın Alma Koşulları", packId = pack.id,
        listeKurus = ((pack.listPriceTL ?: pack.priceTL ?: 0.0) * 100).toInt(),
        odenecekKurus = ((pack.priceTL ?: 0.0) * 100).toInt(),
        bolumler = listOf(
            SmilePurchaseTerms.Bolum("1. Taraflar", listOf("Bu koşullar Dentifay ile Deneme Klinik arasındadır.")),
            SmilePurchaseTerms.Bolum("2. Paket ve bedel", listOf("Paket kliniğinize hemen eklenir; bedel ay sonu faturanıza yansır.")),
            SmilePurchaseTerms.Bolum("3. Kullanım", listOf("Kredi, kredi ile çalışan işlemlerde kullanılır.")),
        ),
    )

    /** Satın almada kabul edilen koşullar (Hareketler › "Koşullar"). */
    val kabul = AcceptedTerms(
        surum = "2026-10-21.1", ozet = "4b1e9c07d2a8f613", kabulEden = AcceptedTerms.Kabul("Dt. B"), at = "2026-10-15T10:00:00Z",
        metin = kosullar(packs[1]).let { AcceptedTerms.Metin(it.baslik, it.bolumler) },
    )
}
