package com.dentifay.app.hasta

import com.dentifay.app.core.ApiClient

/*
 * Hasta sayfası OKUMA uçları — sözleşme AGENT §14 @ 33c26896 (Bölüm B; Bölüm A'da DentAIWeb kodu @ afc3170'ten okunmuştu).
 * Büyük yanıtlarda zaman aşımı yalnız bu isteklerde 30 sn.
 */

/** Hastanın planları — `detay=1`: kalemler `GET /:id`'deki AYNI ağaçla (kart ayrıntı beklemeden çizilir; M-E8.12). */
suspend fun ApiClient.hastaPlanlari(hastaId: String): PlanListesiYaniti =
    request<PlanListesiYaniti>(
        path = "/treatment-plans",
        query = listOf("patientId" to hastaId, "detay" to "1", "limit" to "50"),
        timeoutSeconds = 30,
    )

/** Planın tam ağacı (diş, `catalog.visualKey`, çocuklar, onam alanları) — kart açılınca, tembel. */
suspend fun ApiClient.planAyrintisi(planId: String): HastaPlani =
    request<PlanYaniti>(path = "/treatment-plans/$planId", timeoutSeconds = 30).plan

/** Aşama listesi (Yapılacak `TODO` / Yapılan `DONE`), çok dişli kalem TEK satır (`gruplu=1`). */
suspend fun ApiClient.asamaKalemleri(hastaId: String, asama: String): List<TedaviKalemi> =
    request<KalemListesiYaniti>(
        path = "/treatment-items",
        query = listOf("patientId" to hastaId, "stage" to asama, "gruplu" to "1", "limit" to "500"),
        timeoutSeconds = 30,
    ).items

/** Hastanın görselleri — sayfalı (`limit` 50, `cursor` önceki yanıtın `pagination.nextCursor`u; `null` son sayfa). */
suspend fun ApiClient.hastaMedyasi(hastaId: String, kategori: String? = null, imlec: String? = null): MedyaYaniti {
    val q = mutableListOf<Pair<String, String?>>("limit" to "50")
    if (imlec != null) q += "cursor" to imlec
    if (kategori != null) q += "category" to kategori
    return request<MedyaYaniti>(path = "/patients/$hastaId/media", query = q, timeoutSeconds = 30)
}
