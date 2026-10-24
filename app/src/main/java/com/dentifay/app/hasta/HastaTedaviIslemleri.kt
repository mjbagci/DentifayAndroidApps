package com.dentifay.app.hasta

import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.EsnekSayi
import com.dentifay.app.core.HakedisEtkisi
import com.dentifay.app.core.SessionEvents
import com.dentifay.app.core.govde
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Katalog kalemi (tedavi seçici) — `GET /api/service-catalog?active=true` ya da menü seçimi (`/price-lists/:id/secim`). */
@Serializable
data class KatalogKalemi(
    val id: String,
    val name: String,
    val group: String? = null,
    val tarifeKodu: String? = null,
    val price: EsnekSayi? = null,
    val currency: String? = null,
    val visualKey: String? = null,
)

@Serializable
data class SikKullanilan(
    val catalogServiceId: String? = null,
    val name: String,
    val group: String? = null,
    val price: EsnekSayi? = null,
    val usageCount: Int? = null,
)

/** `GET /api/fx/resolve` — "kur yok" HTTP hatası değildir (`ok:false`). */
@Serializable
data class KurYaniti(
    val ok: Boolean = false,
    val currency: String? = null,
    val rate: String? = null,
    val rateDate: String? = null,
    val source: String? = null,
    val sourceLabel: String? = null,
    val message: String? = null,
)

// MARK: - Yanıt kabukları (Swift `extension APIClient` içindeki iç türler)

@Serializable internal data class PlanSonucu(val plan: HastaPlani)
@Serializable internal data class SurumYaniti(val surum: String)
@Serializable internal data class KatalogYaniti(val items: List<KatalogKalemi> = emptyList())
@Serializable internal data class SikYaniti(val items: List<SikKullanilan> = emptyList())
@Serializable internal data class MenuCozumu(val menu: Menu? = null) {
    @Serializable data class Menu(val id: String, val name: String? = null)
}

/**
 * `GET /api/price-lists/:id/secim` satırı — katalog yetkisi (`set.catalog.view`) yokken menünün seçim listesi (≤ 200). Swift
 * `MenuSecimYaniti.Oge`ten yalnız seçicinin kullandığı alanlar; menü seçim modeli başka paketin — burada pakete özel kabuk.
 */
@Serializable internal data class MenuSecimi(val items: List<Oge> = emptyList()) {
    @Serializable data class Oge(
        val id: String,
        val name: String,
        val grup: String? = null,
        val price: EsnekSayi? = null,
        val currency: String? = null,
    )
}

/*
 * Hasta sayfası İŞLEM uçları (Bölüm A: kaynak DentAIWeb kodu @ afc3170 — `treatment-plans.js` · `treatment-items.js` · `patient-media.js` ·
 * `fx.js` · `service-catalog.js`; M-E8.4). Kendi yolu olmayan kodlu 4xx sunucunun cümlesiyle `ApiException.Kodlu` olur (M-E8.0).
 * Hakediş etkisi taşıyan yazımlar gövdeyi `HakedisEtkisi.govdeye` ile kurar (onaylanan özet `HakedisOnayi.korumali` bağlamından).
 */

// MARK: Plan

suspend fun ApiClient.planOlustur(hastaId: String, hekimId: String?, tarih: String, ad: String?): HastaPlani {
    val g = govde(
        "patientId" to hastaId, "doctorUserId" to hekimId, "treatmentDate" to tarih,
        "title" to ad?.takeIf { it.isNotEmpty() }, "status" to "DRAFT",
    )
    return request<PlanSonucu>(method = "POST", path = "/treatment-plans", body = g).plan
}

/** Ad / not / hekim (PUT — PATCH yok). `notes` boşsa "" gönderilir (sunucu `null` almaz). */
suspend fun ApiClient.planGuncelle(planId: String, ad: String? = null, not: String? = null, hekimId: String? = null) {
    val g = govde("title" to ad, "notes" to not, "doctorUserId" to hekimId)
    request<JsonElement>(method = "PUT", path = "/treatment-plans/$planId", body = HakedisEtkisi.govdeye(g))
}

suspend fun ApiClient.planSil(planId: String) {
    request<JsonElement>(method = "DELETE", path = "/treatment-plans/$planId")
}

suspend fun ApiClient.planGeriYukle(planId: String) {
    request<JsonElement>(method = "POST", path = "/treatment-plans/$planId/restore")
}

suspend fun ApiClient.planSifirla(planId: String) {
    request<JsonElement>(
        method = "POST", path = "/treatment-plans/$planId/reset",
        body = HakedisEtkisi.govdeye(govde("reason" to "Plan reset from mobile")),
    )
}

/** Plana tek kalem (`POST /treatment-plans/items`, sıkı şema). */
suspend fun ApiClient.planaKalemEkle(govde: JsonObject) {
    request<JsonElement>(method = "POST", path = "/treatment-plans/items", body = govde)
}

suspend fun ApiClient.planSurumu(planId: String): String =
    request<SurumYaniti>(path = "/treatment-plans/$planId/surum").surum

/** "Planı düzenle" tek kayıt — `If-Match: "<surum>"`; sürüm çakışması 409 `E_PLAN_VERSION_CONFLICT` (çağıran yeniden yükler). */
suspend fun ApiClient.planKalemleriniKaydet(planId: String, surum: String, eklenen: List<JsonObject>, degisen: List<JsonObject>, silinen: List<String>) {
    val g = govde("eklenen" to eklenen, "degisen" to degisen, "silinen" to silinen)
    request<JsonElement>(
        method = "PUT", path = "/treatment-plans/$planId/kalemler", body = HakedisEtkisi.govdeye(g),
        headers = mapOf("If-Match" to "\"$surum\""),
    )
}

// MARK: Kalem

/** Aşama aktarma (≤200 kimlik; çok dişli satırda `cocukKimlikleri`). `hekimId`/`hekimler` yalnız Yapılan'a. */
suspend fun ApiClient.kalemleriAktar(
    kimlikler: List<String>,
    asama: String,
    neden: String? = null,
    hekimId: String? = null,
    hekimler: Map<String, String>? = null,
) {
    for (parca in kimlikler.chunked(200)) {
        val g = govde(
            "itemIds" to parca, "toStage" to asama, "reason" to (neden ?: JsonNull),
            "hekimId" to hekimId, "hekimler" to hekimler,
        )
        request<JsonElement>(method = "POST", path = "/treatment-items/bulk-transition", body = HakedisEtkisi.govdeye(g))
    }
}

suspend fun ApiClient.kalemIndirimi(id: String, tutar: Double? = null, yuzde: Double? = null) {
    val g = govde("discountAmount" to tutar, "discountPercent" to yuzde)
    request<JsonElement>(method = "POST", path = "/treatment-items/$id/discount", body = HakedisEtkisi.govdeye(g))
}

/** Döviz: TL'de yalnız `currency`; dövizde ekranda GÖSTERİLEN kur geri gönderilir (farklıysa 409 `FX_RATE_CHANGED`). */
suspend fun ApiClient.kalemDovizi(id: String, para: String, kur: String?) {
    val g = govde("currency" to para, "exchangeRate" to (if (para == "TRY") null else kur))
    request<JsonElement>(method = "POST", path = "/treatment-items/$id/currency", body = HakedisEtkisi.govdeye(g))
}

suspend fun ApiClient.disleriParcala(id: String) {
    request<JsonElement>(method = "POST", path = "/treatment-items/$id/split-teeth", body = HakedisEtkisi.govdeye(null))
}

suspend fun ApiClient.kalemSil(id: String, neden: String?) {
    val g: JsonObject? = neden?.takeIf { it.isNotEmpty() }?.let { govde("reason" to it) }
    request<JsonElement>(method = "DELETE", path = "/treatment-items/$id", body = HakedisEtkisi.govdeye(g))
}

suspend fun ApiClient.kalemGeriYukle(id: String) {
    request<JsonElement>(method = "POST", path = "/treatment-items/$id/restore", body = HakedisEtkisi.govdeye(null))
}

/** `POST /api/hakedis/etki-onizleme` — gerçek ucun gövdesiyle; işlem sunucuda HER DURUMDA geri alınır (yalnız "Planı düzenle"). */
suspend fun ApiClient.hakedisOnizle(islem: String, hedef: String, govde: JsonObject, ifMatch: String? = null): HakedisEtkisi =
    request(
        method = "POST", path = "/hakedis/etki-onizleme",
        body = com.dentifay.app.core.govde("islem" to islem, "hedef" to hedef, "govde" to govde, "ifMatch" to ifMatch),
    )

/** Doğrudan kalem (`POST /treatment-items`) — plansız ise sistem planı "Doğrudan Eklenen İşlemler". */
suspend fun ApiClient.dogrudanKalemEkle(govde: JsonObject) {
    request<JsonElement>(method = "POST", path = "/treatment-items", body = govde)
}

// MARK: Seçici verisi

suspend fun ApiClient.tedaviKatalogu(): List<KatalogKalemi> =
    request<KatalogYaniti>(path = "/service-catalog", query = listOf("active" to "true"), timeoutSeconds = 30).items

suspend fun ApiClient.varsayilanMenu(hastaId: String): String? =
    request<MenuCozumu>(path = "/price-lists/resolve-menu", query = listOf("patientId" to hastaId)).menu?.id

/** Menünün düz seçim listesi (Swift `fetchMenuSecim(priceListId:q:)` — boş sorgu: `q` gönderilmez). */
internal suspend fun ApiClient.menuSecimListesi(menuId: String): MenuSecimi =
    request(path = "/price-lists/$menuId/secim")

suspend fun ApiClient.sikKullanilanlar(hekimId: String?): List<SikKullanilan> {
    val q = mutableListOf<Pair<String, String?>>("months" to "6", "limit" to "7")
    if (hekimId != null) q += "doctorId" to hekimId
    return request<SikYaniti>(path = "/treatment-items/frequent", query = q).items
}

suspend fun ApiClient.kurCoz(para: String, kalemId: String): KurYaniti =
    request(path = "/fx/resolve", query = listOf("currency" to para, "itemId" to kalemId))

// MARK: Medya

suspend fun ApiClient.medyaSil(hastaId: String, id: String) {
    request<JsonElement>(method = "DELETE", path = "/patients/$hastaId/media/$id")
}

suspend fun ApiClient.medyaGeriYukle(hastaId: String, id: String) {
    request<JsonElement>(method = "POST", path = "/patients/$hastaId/media/$id/restore")
}

suspend fun ApiClient.medyaFavori(hastaId: String, id: String, favori: Boolean) {
    request<JsonElement>(method = "PATCH", path = "/patients/$hastaId/media/$id", body = govde("isFavorite" to favori))
}

/** Görsel yükleme (multipart `files` + `category` + `note`) — `chart.docs`. Zaman aşımı 120 sn. */
suspend fun ApiClient.medyaYukle(hastaId: String, veri: ByteArray, dosyaAdi: String, mime: String, kategori: String, not: String?) {
    withContext(Dispatchers.IO) {
        val sinir = "dentifay-${UUID.randomUUID().toString().uppercase()}"
        val govde = ByteArrayOutputStream()
        fun yaz(s: String) = govde.write(s.toByteArray(Charsets.UTF_8))
        fun alan(ad: String, deger: String) = yaz("--$sinir\r\nContent-Disposition: form-data; name=\"$ad\"\r\n\r\n$deger\r\n")
        alan("category", kategori)
        if (!not.isNullOrEmpty()) alan("note", not)
        yaz("--$sinir\r\nContent-Disposition: form-data; name=\"files\"; filename=\"$dosyaAdi\"\r\nContent-Type: $mime\r\n\r\n")
        govde.write(veri)
        yaz("\r\n--$sinir--\r\n")
        val yol = "/patients/$hastaId/media"
        val istek = Request.Builder()
            .url(buildUrl(yol))
            .post(govde.toByteArray().toRequestBody("multipart/form-data; boundary=$sinir".toMediaType()))
            .build()
        val istemci = http.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .build()
        val yanit = execute(istek, istemci)
        if (yanit.status == 401) {
            SessionEvents.report401(yanit.body, yol)
            throw ApiException.Unauthorized()
        }
        if (yanit.status !in 200..299) throw hata(yanit.status, yanit.body)
    }
}
