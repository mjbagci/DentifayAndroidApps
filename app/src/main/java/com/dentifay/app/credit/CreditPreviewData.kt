package com.dentifay.app.credit

/** @Preview için örnek kredi verileri (ağ çağrısı yok). */
internal object CreditPreviewData {
    val packs = listOf(
        SmileCreditPack("p50", 50.0, 600.0, unitPriceTL = 12.0, badge = "Başlangıç"),
        SmileCreditPack("p150", 150.0, 1500.0, unitPriceTL = 10.0, savePct = 17, badge = "Popüler", popular = true),
        SmileCreditPack("p500", 500.0, 4000.0, unitPriceTL = 8.0, savePct = 33, badge = "En iyi", bestValue = true),
    )

    val month = SmileWallet.Month(period = "2026-10", purchasedTL = 1500.0, usedCredits = 42.5, billed = false)

    val wallet = SmileWallet(
        availableCredits = 118.5, freeGrantCredits = 30.0, freeUsedCredits = 18.0, freeRemainingCredits = 12.0,
        balanceCredits = 106.5, openingGrantCredits = 30.0,
        tiers = listOf(
            SmileCreditTier("orta", "Orta", 4.0), SmileCreditTier("orta_iyi", "Orta İyi", 8.0),
            SmileCreditTier("iyi", "İyi", 12.0), SmileCreditTier("cok_iyi", "Çok İyi", 18.0),
        ),
        fixCredits = 0.0, packs = packs, canPurchase = true, thisMonth = month, isDemo = false,
        myLimit = SmileWallet.MyLimit(
            period = "2026-10",
            limits = listOf(
                SmileWallet.MyLimit.Line("*", 100.0, 42.5, 57.5),
                SmileWallet.MyLimit.Line("before_after", 40.0, 36.0, 4.0),
            ),
            remainingCredits = 4.0,
        ),
    )

    val terms = SmilePurchaseTerms(
        surum = "2026-10-01", ozet = "abc123", baslik = "Kredi Satın Alma Koşulları", taslak = true, packId = "p150",
        listeKurus = 150000, odenecekKurus = 135000,
        bolumler = listOf(
            SmilePurchaseTerms.Bolum("1. Konu", listOf("Bu koşullar kredi paketi satın alımını düzenler.", "Krediler kliniğe tanımlanır.")),
            SmilePurchaseTerms.Bolum("2. Bedel", listOf("Paket bedeli ay sonu faturasına yansır.")),
        ),
    )

    private val ayse = CreditPerson("u1", "Dt. Ayşe Demir", "HEKIM_GENEL", active = true, former = false)
    private val mehmet = CreditPerson("u2", "Mehmet Kaya", "BANKO", active = true, former = false)

    val usage: CreditUsage = run {
        val series = (1..14).map { d ->
            val key = "2026-10-%02d".format(d)
            val m = mapOf("before_after" to (d % 4) * 4.0, "ai_agent" to (d % 3).toDouble(), "xray" to if (d % 5 == 0) 2.0 else 0.0)
            CreditUsage.Bucket(key, m.values.sum(), m)
        }
        CreditUsage(
            tz = "Europe/Istanbul", from = "2026-10-01", to = "2026-10-14", truncated = false,
            wallet = CreditUsage.Wallet(118.5, 12.0, 30.0, 106.5, 20.0, isLow = false, isDemo = false, thisMonth = month),
            runwayDays = 24, last30DaysCredits = 148.0, unit = "day",
            summary = CreditUsage.Summary(
                usedCredits = 142.0, chargedCredits = 150.0, refundedCredits = 8.0, freeUsedCredits = 18.0, operations = 64,
                refunds = 2, freeFixes = 3, activeUsers = 2, purchasedCredits = 150.0, purchases = 1, paidTL = 1500.0,
                grantedCredits = 0.0, adjustedCredits = 0.0, avgDailyCredits = 10.1,
            ),
            series = series,
            features = listOf(
                CreditUsage.FeatureRow("before_after", 96.0, 18, 2),
                CreditUsage.FeatureRow("ai_agent", 40.0, 40, 0),
                CreditUsage.FeatureRow("xray", 6.0, 6, 0),
            ),
            actions = listOf(
                CreditUsage.ActionRow("before_after", "iyi", 60.0, 5),
                CreditUsage.ActionRow("ai_agent", "chat", 40.0, 40),
                CreditUsage.ActionRow("xray", "analyze", 6.0, 6),
            ),
            heatmap = (0 until 7).map { d -> (0 until 24).map { h -> CreditUsage.Cell(if (h in 9..18) ((d + h) % 5).toDouble() else 0.0, (d + h) % 3) } },
            users = listOf(
                CreditUsage.UserRow("u1", 110.0, 50, 2, "2026-10-14T11:30:00.000Z", ayse),
                CreditUsage.UserRow("u2", 32.0, 14, 0, "2026-10-13T08:10:00.000Z", mehmet),
                CreditUsage.UserRow(null, 0.0, 0, 0, null, null),
            ),
            packs = packs,
            priceCatalog = listOf(
                CreditUsage.PriceRow("before_after", "orta", 4.0, "simulation"),
                CreditUsage.PriceRow("ai_agent", "chat", 1.0, "turn", minCredits = 1.0, maxCredits = 2.0),
                CreditUsage.PriceRow("ai_agent", "voice", 1.0, "turn", extra = true),
                CreditUsage.PriceRow("before_after", "duzeltme", 0.0, "operation"),
            ),
            canPurchase = true, purchaseBlockedReason = null, canManageLimits = true,
        )
    }

    val people = CreditPeople(
        month = "2026-10",
        people = listOf(
            CreditPeople.Person(
                ayse, "HEKIM_GENEL", 110.0, 50,
                mapOf("before_after" to CreditPeople.Usage(80.0, 10), "ai_agent" to CreditPeople.Usage(30.0, 40)),
                CreditPeople.Limits(120.0, mapOf("before_after" to 90.0)), 10.0,
            ),
            CreditPeople.Person(
                mehmet, "BANKO", 32.0, 14,
                mapOf("ai_agent" to CreditPeople.Usage(32.0, 14)),
                CreditPeople.Limits(null, emptyMap()), null,
            ),
        ),
        unattributed = CreditPeople.Unattributed(4.0, 2),
        canManageLimits = true,
    )

    val rows = listOf(
        CreditTransactionsPage.Row("t1", "2026-10-14T11:30:00.000Z", "usage", "before_after", "iyi", -12.0, "balance", 1, null, false, ayse),
        CreditTransactionsPage.Row("t2", "2026-10-14T10:05:00.000Z", "refund", "before_after", "orta", 4.0, "free", 1, null, false, ayse),
        CreditTransactionsPage.Row("t3", "2026-10-12T09:00:00.000Z", "purchase", "purchase", "credit_pack", 150.0, null, 1, 1500.0, false, ayse),
        CreditTransactionsPage.Row("t4", "2026-10-11T15:45:00.000Z", "usage", "ai_agent", "chat", -1.5, "mixed", 2, null, false, mehmet),
        CreditTransactionsPage.Row("t5", "2026-10-01T00:00:00.000Z", "grant", "platform_grant", "opening", 30.0, null, 1, null, true, null),
    )
}
