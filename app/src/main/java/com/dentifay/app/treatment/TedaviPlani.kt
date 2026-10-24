@file:Suppress("ConstPropertyName")

package com.dentifay.app.treatment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.agent.AgentChoiceSet
import com.dentifay.app.agent.AgentMessage
import com.dentifay.app.agent.AgentWritePreview
import com.dentifay.app.agent.AgentWriteResult
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.serialization.Serializable

// MARK: - Tedavi Planı modülünün saf kararları (MOBIL_IS_EMRI M-E1.2)
//
// Görünüm ve ağ yok: VM bu tipleri çağırır, testler bu tipleri sınar. İş kuralı (plan adı, kapalı plan, sistem planı,
// konum etiketi) SUNUCUDADIR; burada yalnız sunucunun alanları okunur.

/** `GET /api/treatment-plans?patientId=` yanıtındaki tek plan (sözleşme §6). */
@Immutable
@Serializable
data class PlanKarti(
    val id: String,
    val title: String? = null,
    val status: String? = null,
    val doctor: Hekim? = null,
    val kalemSayisi: Int? = null,
    val ilkKalemler: List<IlkKalem>? = null,
    val sistemPlani: Boolean? = null,
    val kapali: Boolean? = null,
    /** Röntgenden açılıp hekim onayı bekleyen plan (Ekleme 13 · 32C) — mobil OKUR, yazmaz. */
    val onayBekliyor: Boolean? = null,
) {
    @Immutable
    @Serializable
    data class Hekim(val id: String? = null, val name: String? = null)

    @Immutable
    @Serializable
    data class IlkKalem(val name: String? = null, val konumEtiketi: String? = null)

    /** Kapalı ve sistem planı dokunulamaz (karar 16; varsayım 2). Karar YALNIZ sunucunun alanlarından;
     *  hekim onayı bekleyen plana da yazılmaz (sözleşme §6 — sunucu 409 `E_PLAN_AWAITING_APPROVAL`). */
    val dokunulabilir: Boolean
        get() = !(kapali ?: false) && !(sistemPlani ?: false) && !(onayBekliyor ?: false)

    /** Ek MO.3 — durum rozeti. */
    val durumEtiketi: String
        get() = when (status) {
            "DRAFT" -> "Taslak"
            "PROPOSED" -> "Önerildi"
            "APPROVED" -> "Onaylandı"
            "REJECTED" -> "Reddedildi"
            "IN_PROGRESS" -> "Devam Ediyor"
            "COMPLETED" -> "Tamamlandı"
            "CANCELLED" -> "İptal"
            else -> "Plan"
        }

    /** Ek MO.2 — hekimsiz plan metni. */
    val hekimMetni: String
        get() {
            val ad = Unvan.hekimAdi(doctor?.name)
            return ad.ifEmpty { "Hekim atanmadı" }
        }

    /** İlk üç kalemin satırları: ad + (boş değilse) sunucunun konum etiketi. Mobil konum metni ÜRETMEZ. */
    val ilkKalemSatirlari: List<String>
        get() = (ilkKalemler ?: emptyList()).take(3).mapNotNull { k ->
            val ad = k.name?.trim().orEmpty()
            if (ad.isEmpty()) return@mapNotNull null
            val etiket = k.konumEtiketi?.trim().orEmpty()
            if (etiket.isEmpty()) ad else "$ad · $etiket"
        }

    val baslik: String
        get() = title?.trim().orEmpty().ifEmpty { "Plan" }
}

/** Plan listesi yanıtı + kartların ekran sırası. */
@Immutable
@Serializable
data class PlanListesi(
    val plans: List<PlanKarti> = emptyList(),
    val pagination: Sayfalama? = null,
    val sonrakiPlanAdi: String? = null,
) {
    @Immutable
    @Serializable
    data class Sayfalama(val total: Int? = null)

    val siraliPlanlar: List<PlanKarti> get() = sirala(plans)

    /** Ek MO.2 kırpma satırı — sessiz kırpma yok. */
    val kirpmaMetni: String?
        get() {
            val total = pagination?.total ?: return null
            if (total <= plans.size) return null
            return "İlk ${plans.size} plan gösteriliyor."
        }

    companion object {
        /** Açık planlar (sunucunun sırası) → kapalı / onay bekleyen → en altta sistem planı. */
        fun sirala(planlar: List<PlanKarti>): List<PlanKarti> {
            val sistem = planlar.filter { it.sistemPlani ?: false }
            val digerleri = planlar.filter { !(it.sistemPlani ?: false) }
            return digerleri.filter { it.dokunulabilir } + digerleri.filter { !it.dokunulabilir } + sistem
        }
    }
}

/** Hastanın plan kartları (sözleşme §6; kapı `chart.view`). Kart alanları ve `sonrakiPlanAdi` sunucudan. Swift `APIClient.fetchPatientPlans`. */
suspend fun ApiClient.fetchPatientPlans(patientId: String): PlanListesi =
    request(path = "/treatment-plans", query = listOf("patientId" to patientId, "limit" to "50"))

/** İlk tur mesajı — sözleşme §6 (`lib/ai-agent/mobil-plan-mesaji.js` ile BİREBİR): istemci talimat YAZMAZ. */
object TedaviPlaniMesaji {
    fun ilk(hasta: String, metin: String): String {
        val ad = hasta.replace("«", "").replace("»", "").trim()
        val m = metin.replace("\"", "'").trim()
        return "«$ad» için: \"$m\""
    }
}

/** Faz kararları. */
object TedaviPlaniFazi {

    /** Hedef plan hataları (akış AÇILMADAN dönen 400/409) — plan listesine dönülür ve liste yenilenir. */
    val hedefPlanKodlari: Set<String> = setOf("E_TARGET_PLAN_INVALID", "E_PLAN_CLOSED")

    /** Ek MO.5 — sunucu cümlesi yoksa yedek. */
    fun hedefPlanYedegi(kod: String): String =
        if (kod == "E_PLAN_CLOSED") "Bu plan kapanmış; yeni tedavi eklenemez." else "Seçilen plan kullanılamıyor; liste yenilendi."

    /** Ek MO.6c */
    const val bosYanit = "Asistandan yanıt gelmedi. Tekrar deneyin."
    /** Ek MO.6d */
    const val zamanAsimi = "Yanıt uzun süre gelmedi. Plan kaydedilmiş olabilir; planlar listesini kontrol edin."

    /**
     * Turun sonu — sıra: kredi → hedef plan hatası → yazma sonucu → önizleme → seçim kartı → gerçek hata → soru.
     * (Kredi kartı VM'de, bu fonksiyondan ÖNCE ele alınır.)
     */
    fun turSonu(
        mesaj: AgentMessage,
        onizleme: AgentWritePreview?,
        oncekiOnizleme: AgentWritePreview?,
        iptalBekleniyor: Boolean,
        turHatasi: String?,
    ): TreatmentPlanViewModel.Phase {
        // 0) Hedef plan reddi (akış açılmadan) → plan listesi + yenileme.
        val kod = mesaj.errorCode
        if (kod != null && kod in hedefPlanKodlari) {
            val cumle = mesaj.content.ifEmpty { hedefPlanYedegi(kod) }
            return TreatmentPlanViewModel.Phase.plans(cumle)
        }

        // 1) Yazma sonucu (onay ya da iptal turunun sonu)
        mesaj.writeResult?.let { wr ->
            return when {
                wr == AgentWriteResult.committed -> TreatmentPlanViewModel.Phase.done(mesaj.content)
                wr == AgentWriteResult.cancelled && iptalBekleniyor -> TreatmentPlanViewModel.Phase.recording
                else -> TreatmentPlanViewModel.Phase.failed(mesaj.content.ifEmpty { "İşlem tamamlanamadı." })
            }
        }
        // İptal beklenirken tur (sonuçsuz da olsa) bitti → kayda dön: bekleyen yazma sunucuda kalmadı ya da tükendi.
        if (iptalBekleniyor && onizleme == null && mesaj.choiceSet == null && turHatasi == null && !mesaj.isError) {
            return TreatmentPlanViewModel.Phase.recording
        }

        // 2) Yapısal önizleme hazır
        if (onizleme != null) return TreatmentPlanViewModel.Phase.review

        // 3) Seçim kartı (0 adaylı `elleSecim` kartı dahil) + sunucunun sorusu (yoksa Ek MO.10a)
        val set = mesaj.choiceSet
        if (set != null && set.cizilir) {
            val soru = mesaj.content.trim()
            return TreatmentPlanViewModel.Phase.choosing(set, soru.ifEmpty { AgentChoiceSet.soruYedegi })
        }

        // 4) GERÇEK hata — açık sinyal (soft_fail / error / ağ)
        if (turHatasi != null) return TreatmentPlanViewModel.Phase.failed(turHatasi)
        if (mesaj.isError) return TreatmentPlanViewModel.Phase.failed(mesaj.content.ifEmpty { "İşlem tamamlanamadı." })
        if (mesaj.softFailCode != null) {
            return TreatmentPlanViewModel.Phase.failed(mesaj.content.ifEmpty { "Bu isteği şu an tamamlayamadım." })
        }

        // 5) Soru — hata DEĞİL
        val soru = mesaj.content.trim()
        if (soru.isEmpty()) {
            return if (oncekiOnizleme != null) TreatmentPlanViewModel.Phase.review else TreatmentPlanViewModel.Phase.failed(bosYanit)
        }
        return TreatmentPlanViewModel.Phase.asking(soru)
    }

    /** Hareketsizlik bekçisi yalnız düşünme fazında kurulur; `submitting`te sunucu turu kendi bütçesiyle biter (M-E1.2.8). */
    fun bekciKurulur(phase: TreatmentPlanViewModel.Phase): Boolean =
        phase == TreatmentPlanViewModel.Phase.thinking || phase == TreatmentPlanViewModel.Phase.cancelling

    /** Onay turunda (`submitting`) modül kapatılamaz — kapatmak akışı keser ve sunucu turu düşürürdü. */
    fun kapatmaSerbest(phase: TreatmentPlanViewModel.Phase): Boolean = phase != TreatmentPlanViewModel.Phase.submitting
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 360)
@Composable
private fun TedaviPlaniPreview() {
    val liste = OrnekTedaviPlani.planListesi
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            liste.siraliPlanlar.forEach { p ->
                Text("${p.baslik} · ${p.durumEtiketi} · ${p.hekimMetni}", style = sys(14), color = if (p.dokunulabilir) DS.Colors.onSurface else DS.Colors.outline)
            }
            liste.kirpmaMetni?.let { Text(it, style = sys(12), color = DS.Colors.outline) }
            Text(TedaviPlaniMesaji.ilk("Deneme Hasta", "36'ya dolgu"), style = sys(13), color = DS.Colors.cyan400)
        }
    }
}
