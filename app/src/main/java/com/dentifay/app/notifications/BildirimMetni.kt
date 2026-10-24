package com.dentifay.app.notifications

import com.dentifay.app.core.APIFlexibleValue
import com.dentifay.app.core.APINotification
import com.dentifay.app.core.TL
import com.dentifay.app.core.Unvan

/**
 * Bildirim satırının metni — web `features/bildirimler/bildirim-metni.js#{baslikMetni, govdeMetni, konuMetni}` ve
 * `BildirimSatiri.jsx`in yedeği (`govdeMetni || t(body, params)`) ile BİREBİR (MOBIL_IS_EMRI M-E1.6.3).
 * Dizeler web `i18n/locales/tr.json › notifications.types` ile aynı (Ek MO.20). Sunucu satırı METİN taşımaz.
 */
object BildirimMetni {

    /**
     * Sunucunun kayıt defteri (`backend/lib/bildirimler/turler.data.json`) — 19 tür, aynı sıra.
     * Listede olmayan tür ÇİZİLMEZ (sözleşme §1); yeni tür mobil güncellenene dek görünmez.
     */
    val turler: List<String> = listOf(
        "appointment.created", "appointment.arrived", "appointment.inTreatment", "appointment.completed",
        "appointment.cancelled", "payment.received", "ai.permissionDenied", "task.assigned", "task.unassigned",
        "task.due", "survey.lowScore", "task.completed", "pricing.approvalPending", "pricing.approvalResolved",
        "xray.planRejected", "xray.planApprovalPending", "xray.planApproved", "credits.low", "credits.limitReached",
    )

    private val turKumesi: Set<String> = turler.toHashSet()

    fun gorunur(n: APINotification): Boolean = n.type in turKumesi

    // MARK: Sözlük (web `notifications.types.<tür>`)

    private val basliklar: Map<String, String> = mapOf(
        "appointment.created" to "Yeni randevu",
        "appointment.arrived" to "Hasta geldi",
        "appointment.inTreatment" to "Hasta odaya alındı",
        "appointment.completed" to "Hasta klinikten çıktı",
        "appointment.cancelled" to "Randevu iptal edildi",
        "payment.received" to "Ödeme alındı",
        "ai.permissionDenied" to "Yetki dışı asistan sorusu",
        "task.assigned" to "Size görev atandı",
        "task.unassigned" to "Sorumlusuz yeni görev",
        "task.due" to "Görevin vakti geldi",
        "task.completed" to "Atadığınız görev tamamlandı",
        "survey.lowScore" to "Düşük anket puanı",
        "pricing.approvalPending" to "Fiyat onayı bekliyor",
        "pricing.approvalResolved" to "Fiyat talebiniz sonuçlandı",
        "xray.planRejected" to "Röntgen tedavi öneriniz reddedildi",
        "xray.planApprovalPending" to "Onay bekleyen röntgen planı",
        "xray.planApproved" to "Röntgen planı onaylandı",
        "credits.low" to "Kredi bakiyesi azaldı",
        "credits.limitReached" to "Kredi limiti doldu",
    )

    private val iptalBasliklari: Map<String, String> = mapOf(
        "CANCELLED" to "Randevu iptal edildi", "RESCHEDULED" to "Randevu ertelendi",
        "NO_SHOW" to "Hasta gelmedi", "DELETED" to "Randevu silindi",
    )
    private val fiyatSonucBasliklari: Map<String, String> = mapOf(
        "APPROVED" to "Fiyat talebiniz onaylandı", "REJECTED" to "Fiyat talebiniz reddedildi",
    )

    /** Genel gövde şablonları (`{{x}}` = parametre). */
    private val govdeSablonlari: Map<String, String> = mapOf(
        "appointment.created" to "{{tarih}} {{saat}}",
        "appointment.arrived" to "{{saat}} randevusu",
        "appointment.inTreatment" to "Tedavide",
        "appointment.completed" to "Tamamlandı",
        "appointment.cancelled" to "{{tarih}} {{saat}}",
        "payment.received" to "{{tutar}}",
        "ai.permissionDenied" to "yetkisi olmayan bir veri sordu",
        "task.assigned" to "Görev sorumlusu olarak siz atandınız.",
        "task.unassigned" to "Bu görevin sorumlusu henüz atanmadı.",
        "task.due" to "Görevin vadesi doldu.",
        "task.completed" to "Görev sorumlusu tarafından tamamlandı.",
        "survey.lowScore" to "{{puan}} puan verildi",
        "pricing.approvalPending" to "%{{indirim}} indirim talebi",
        "pricing.approvalResolved" to "Sonucu pazarlık ekranında görebilirsiniz.",
        "xray.planRejected" to "Gerekçeyi plan geçmişinde görebilirsiniz.",
        "xray.planApprovalPending" to "{{hasta}} için röntgenden hazırlanan plan ({{count}} kalem) onayınızı bekliyor.",
        "xray.planApproved" to "{{hasta}} için gönderdiğiniz plan onaylandı.",
        "credits.low" to "Kalan kredi: {{kalan}}. Kredi Yönetimi'nden kredi yükleyebilirsiniz.",
        "credits.limitReached" to "Aylık kredi limiti doldu ({{kullanilan}} / {{limit}} kredi).",
    )

    /** Ek MO.20f — ödeme yöntemi (web `patients.finance.paymentMethods`). */
    val odemeYontemleri: Map<String, String> = mapOf(
        "CASH" to "Nakit", "CARD" to "Kredi Kartı", "BANK_TRANSFER" to "Banka Transferi", "ONLINE" to "Online", "OTHER" to "Diğer",
    )

    /** Ek MO.20d */
    const val MASKELI_HASTA = "Randevulu hasta"
    /** Ek MO.20e */
    const val ARSIV_HASTA = "Arşivlenmiş hasta"
    /** Ek MO.20g */
    const val ONAYLAMA_YETKISI_YOK = "Bu talebi onaylama yetkiniz yok."

    // MARK: Kurallar

    /** Swift `params: [String: APIFlexibleValue]` görünümü. */
    private fun parametreler(n: APINotification): Map<String, APIFlexibleValue> =
        n.params?.mapValues { APIFlexibleValue.from(it.value) } ?: emptyMap()

    /** `baslikMetni` — iptalde DURUMA, ödemede İADEYE, fiyat sonucunda SONUCA göre. */
    fun baslik(n: APINotification): String {
        val p = parametreler(n)
        if (n.type == "appointment.cancelled") {
            p["durum"]?.display?.let { d -> iptalBasliklari[d]?.let { return it } }
        }
        if (n.type == "payment.received" && p["iade"]?.dogruMu == true) return "İade yapıldı"
        if (n.type == "pricing.approvalResolved") {
            p["sonuc"]?.display?.let { s -> fiyatSonucBasliklari[s]?.let { return it } }
        }
        return basliklar[n.type] ?: ""
    }

    /** `konuMetni` — maskeli hasta · hasta adı (arşivliyse ek) · görev başlığı · aktör. */
    fun konu(n: APINotification): String? {
        if (n.patientMasked == true) return MASKELI_HASTA
        val ad = n.patient?.name
        if (!ad.isNullOrEmpty()) return if (n.patient?.archived == true) "$ad ($ARSIV_HASTA)" else ad
        val gorev = n.task?.title
        if (!gorev.isNullOrEmpty()) return gorev
        val aktor = n.actorName
        if (!aktor.isNullOrEmpty()) return aktor
        return null
    }

    /** Satırın gövdesi: `govdeMetni`, yoksa türün genel gövdesi (`params` ile doldurulur). */
    fun govde(n: APINotification): String? {
        ozelGovde(n)?.let { return it }
        val sablon = govdeSablonlari[n.type] ?: return null
        val dolu = doldur(sablon, parametreler(n)).trim { it.yatayBosluk() }
        return dolu.ifEmpty { null }
    }

    /** web `govdeMetni` — türe özel gövde; `null` → genel gövde. */
    private fun ozelGovde(n: APINotification): String? {
        val p = parametreler(n)
        fun birlestir(vararg x: String?): String? {
            val v = x.filterNotNull().filter { it.isNotEmpty() }
            return if (v.isEmpty()) null else v.joinToString(" · ")
        }
        fun tarihSaat(): String? =
            birlestir(p["tarih"]?.display?.let { tarihBicimi(it) }, p["saat"]?.display)?.replace(" · ", " ")
        /** Hekim adı unvan kuralıyla (M-E1.7A); boşsa `null`. */
        fun hekim(ad: String?): String? = Unvan.hekimAdi(ad).ifEmpty { null }

        return when (n.type) {
            "appointment.created" -> birlestir(tarihSaat(), hekim(n.doctorName))
            "appointment.arrived" -> birlestir(p["saat"]?.display?.let { "$it randevusu" }, p["oda"]?.display)
            "appointment.inTreatment", "appointment.completed" -> hekim(n.doctorName)
            "appointment.cancelled" -> tarihSaat()
            "payment.received" -> {
                val tutar = p["tutar"]?.sayi?.let { TL.bicim(it) } ?: p["tutar"]?.display
                birlestir(tutar, p["yontem"]?.display?.let { odemeYontemleri[it] ?: it })
            }
            "xray.planApprovalPending", "xray.planApproved" -> {
                val hasta = if (n.patientMasked == true) MASKELI_HASTA else (n.patient?.name ?: "")
                val kalem = p["kalem"]?.display ?: "0"
                doldur(govdeSablonlari[n.type] ?: "", mapOf("hasta" to APIFlexibleValue.Text(hasta), "count" to APIFlexibleValue.Text(kalem)))
            }
            "pricing.approvalPending" ->
                birlestir(doldur(govdeSablonlari[n.type] ?: "", p), if (n.pricing?.canApprove == false) ONAYLAMA_YETKISI_YOK else null)
            "survey.lowScore" -> {
                val puan = p["puan"]?.display ?: ""
                val parcalar = mutableListOf<String>()
                val soru = n.survey?.question
                if (!soru.isNullOrEmpty()) parcalar += "“$soru” sorusuna $puan puan" else parcalar += "$puan puan verildi"
                val fazla = maxOf(0, (p["soruSayisi"]?.sayi?.toInt() ?: 0) - 1)
                if (fazla > 0) parcalar += "+$fazla soru"
                hekim(n.survey?.doctorName)?.let { parcalar += it }
                parcalar.joinToString(" · ")
            }
            else -> null
        }
    }

    /**
     * `{{anahtar}}` yerine parametre (yoksa boş). Arama, yerleştirilen değerden SONRA sürer — değerin içindeki `{{…}}`
     * yeniden açılmaz (sonsuz döngü olmaz).
     */
    fun doldur(sablon: String, p: Map<String, APIFlexibleValue>): String {
        val s = StringBuilder(sablon)
        var bas = 0
        while (true) {
            val a = s.indexOf("{{", bas)
            if (a < 0) break
            val b = s.indexOf("}}", a + 2)
            if (b < 0) break
            val deger = p[s.substring(a + 2, b)]?.display ?: ""
            s.replace(a, b + 2, deger)
            bas = a + deger.length
        }
        return s.toString()
    }

    /** `YYYY-MM-DD` → "17.10.2026" (`tr_TR`). */
    fun tarihBicimi(ham: String): String {
        val parcalar = ham.take(10).split("-").filter { it.isNotEmpty() }
        if (parcalar.size != 3) return ham
        return "${parcalar[2]}.${parcalar[1]}.${parcalar[0]}"
    }

    /** web `notifications.count` — "3 kez". */
    fun sayiMetni(n: APINotification): String? {
        val c = n.count ?: return null
        return if (c > 1) "$c kez" else null
    }

    /** Satırın alt metni: gövde · sayı (ikisi de yoksa `null`). */
    fun altMetin(n: APINotification): String? =
        listOfNotNull(govde(n), sayiMetni(n)).joinToString(" · ").ifEmpty { null }

    /** Swift `CharacterSet.whitespaces`: sekme + Unicode boşluk ayırıcıları (satır sonu DEĞİL). */
    private fun Char.yatayBosluk(): Boolean = this == '\t' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()
}
