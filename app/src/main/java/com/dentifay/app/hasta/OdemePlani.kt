package com.dentifay.app.hasta

import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.EsnekSayi
import com.dentifay.app.core.govde
import com.dentifay.app.home.RandevuSms
import com.dentifay.app.home.SmsKipleri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Ödeme planı — web `PaymentPlanModal` + `lib/taksit-takvimi.js` ikizi (DentAIWeb @ 33c26896; M-E8.12). Tutarlar kuruş TAM SAYI,
 * kalan SON taksite; vade takvim ayı (çapa günü korunur, ay sonunda kırpılır); gün sınırı kliniğin bugünü.
 */
object OdemePlani {
    data class Taksit(val no: Int, val tutar: Double, val vade: String) {
        val id: Int get() = no
    }

    private fun parcala(ymd: String): Triple<Int, Int, Int>? {
        val p = ymd.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3 || p[1] !in 1..12 || p[2] !in 1..31) return null
        return Triple(p[0], p[1], p[2])
    }

    private fun ymd(y: Int, a: Int, g: Int): String = String.format(Locale.ROOT, "%04d-%02d-%02d", y, a, g)

    /** `ayEkle`: hedef ayın son gününe kırpılır. */
    fun ayEkle(s: String, ay: Int, capa: Int): String? {
        val (y, a, _) = parcala(s) ?: return null
        val toplam = (a - 1) + ay
        val hy = y + floor(toplam.toDouble() / 12).toInt()
        val ha = ((toplam % 12) + 12) % 12 + 1
        val gunler = runCatching { YearMonth.of(hy, ha).lengthOfMonth() }.getOrNull() ?: return null
        return ymd(hy, ha, min(capa, gunler))
    }

    fun varsayilanIlkVade(bugun: String): String? {
        val (_, _, g) = parcala(bugun) ?: return null
        return ayEkle(bugun, 1, capa = g)
    }

    fun vadeGunleri(adet: Int, ilkVade: String): List<String>? {
        if (adet !in 1..240) return null
        val capa = parcala(ilkVade)?.third ?: return null
        return (0 until adet).mapNotNull { ayEkle(ilkVade, it, capa = capa) }
    }

    /** `tutariBol`: kuruş tam sayı bölme, kalan SON parçaya. */
    fun tutariBol(toplam: Double, n: Int): List<Double> {
        val adet = max(1, n)
        val kurus = yakinaYuvarla(toplam * 100).toLong()
        val taban = kurus / adet
        return (0 until adet).map { i -> (if (i == adet - 1) kurus - taban * (adet - 1) else taban).toDouble() / 100 }
    }

    fun taksitler(toplam: Double, adet: Int, ilkVade: String): List<Taksit> {
        val vadeler = vadeGunleri(adet, ilkVade) ?: return emptyList()
        return tutariBol(toplam, adet).zip(vadeler).mapIndexed { i, (tutar, vade) -> Taksit(no = i + 1, tutar = tutar, vade = vade) }
    }

    /** Kliniğin bugünü (`YYYY-MM-DD`, oturumun saat dilimi). */
    fun bugun(saatDilimi: String?, simdi: Instant = Instant.now()): String {
        val bolge = saatDilimi?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        return GUN.format(simdi.atZone(bolge))
    }

    private val GUN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val NOKTALI: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("tr-TR"))

    // MARK: SMS · durum · özet

    /** SMS türü: `PAYMENT_PLAN_CREATED` ya da `IBAN_INFO`'dan kipi olan ilki; yanıt okunamazsa hiçbir şey çizilmez (web `{kinds:[],modes:[]}`). */
    fun smsKipi(y: SmsKipleri?): RandevuSms.Kip {
        if (y == null) return RandevuSms.Kip.yok
        val modlar = y.modes ?: (y.kinds ?: emptyList()).map { SmsKipleri.Kip(kind = it, mode = "AUTO") }
        fun kip(t: String): String? = modlar.firstOrNull { it.kind == t }?.mode?.uppercase()
        val tur = listOf("PAYMENT_PLAN_CREATED", "IBAN_INFO").firstOrNull { kip(it) != null } ?: "PAYMENT_PLAN_CREATED"
        return when (kip(tur)) {
            "AUTO" -> RandevuSms.Kip.otomatik(tur = tur)
            "ASK" -> RandevuSms.Kip.sor(tur = tur)
            else -> RandevuSms.Kip.yok
        }
    }

    val durumAdi: Map<String, String> = mapOf("PENDING" to "Bekliyor", "PARTIAL" to "Kısmen Ödendi", "PAID" to "Ödendi", "CANCELLED" to "İptal")

    /** Swift `(odenen: Double, kalan: Double, elle: Int)`. */
    data class Ozet(val odenen: Double, val kalan: Double, val elle: Int)

    /** Özet (web `planOzeti`): iptal atlanır; ödenen = Σ dağıtılan; elle işaretli kapalı sayılır; kalan = açık taksitlerin kalanı. */
    fun ozet(p: KayitliOdemePlani): Ozet {
        val canli = p.installments.filter { it.status != "CANCELLED" }
        val odenen = canli.fold(0.0) { a, t -> a + (t.allocatedAmount?.deger ?: 0.0) }
        val acik = canli.filter { it.status != "PAID" && it.manualPaidAt == null }
        val kalan = acik.fold(0.0) { a, t ->
            a + (t.remainingAmount?.deger ?: max(0.0, t.amount.deger - (t.allocatedAmount?.deger ?: 0.0)))
        }
        return Ozet(odenen, kalan, p.installments.count { it.manualPaidAt != null })
    }

    fun silmeMetni(p: KayitliOdemePlani): String {
        var m = "Bu ödeme planı ve ${p.installments.size} taksiti silinecek. Taksitlere bağlı ödemeler yerinde kalır; hastanın borcu değişmez."
        if (p.teklifBagi == true) m += " Plan bir pazarlık teklifinden açılmıştı; teklif kalır, plana bağı kopar."
        return m
    }

    const val ISARET_METNI =
        "Taksit \"Ödendi\" olarak işaretlenecek. Para kaydı oluşmaz ve hastanın borcu değişmez. Ödeme aldıysanız \"Ödeme Al\" ile girip bu taksite bağlayın."

    // MARK: Baskı (web `paymentPlanPrint.js` — yalnız `clinicHeader`, `printDate`, `odemeBilgisi` uygulanır; belge dili TR)

    private class Hucre(val per: Double, val oran: Double)

    fun baskiHtml(
        planAdi: String?,
        hastaAdi: String,
        tutar: Double,
        taksitler: List<Taksit>,
        poslar: List<PosKomisyonu>,
        adet: Int,
        kunye: PlanCiktisi.Kunye?,
        ayar: PlanCiktisi.Ayar,
        saatDilimi: ZoneId,
        simdi: Instant = Instant.now(),
    ): String {
        val e = PlanCiktisi::esc
        val p = { v: Double -> PlanCiktisi.para(v, "tr") }
        val bugun = NOKTALI.format(simdi.atZone(saatDilimi))
        val satirlar = taksitler.joinToString("") { t ->
            val vade = runCatching { NOKTALI.format(LocalDate.parse(t.vade)) }.getOrNull() ?: "—"
            "<tr><td>${t.no}/$adet</td><td class=\"r\">${e(p(t.tutar))}</td><td>$vade</td></tr>"
        }
        val sutunlar = listOf(1, adet).distinct()
        var karsilastirma = ""
        if (poslar.isNotEmpty()) {
            val hucreler = poslar.map { pos -> sutunlar.map { c -> pos.oran(c)?.let { Hucre(per = tutar * (1 + it / 100) / c.toDouble(), oran = it) } } }
            // Swift `min(by:)`: eşitlikte ilk satır; hücre yoksa +∞.
            val enIyi = sutunlar.indices.map { ci ->
                var en = 0
                for (ri in hucreler.indices) {
                    if ((hucreler[ri][ci]?.per ?: Double.POSITIVE_INFINITY) < (hucreler[en][ci]?.per ?: Double.POSITIVE_INFINITY)) en = ri
                }
                en
            }
            val bas = sutunlar.joinToString("") { "<th class=\"r\">${e(if (it == 1) "Tek Çekim" else "$it Taksit")}</th>" }
            val govde = poslar.indices.joinToString("") { ri ->
                "<tr><td class=\"bank\">${e(poslar[ri].ad)}</td>" + sutunlar.indices.joinToString("") { ci ->
                    val h = hucreler[ri][ci]
                    if (h == null) {
                        "<td class=\"r\">—</td>"
                    } else {
                        val oran = cG(h.oran)
                        "<td class=\"r ${if (enIyi[ci] == ri) "best" else ""}\">${e(p(h.per))}<br><span class=\"sub\">%${e(oran)}</span></td>"
                    }
                } + "</tr>"
            }
            karsilastirma = "<div class=\"section\">Banka / POS Karşılaştırması — taksit başına tutar (yeşil = en avantajlı)</div>" +
                "<table><thead><tr><th>Banka / POS</th>$bas</tr></thead><tbody>$govde</tbody></table>"
        }
        val antet = if (ayar.antet) PlanCiktisi.antetHtml(kunye, "tr") else "<div style=\"height:46px\"></div>"
        val tarihSatiri = if (ayar.tarih) "<br>${e(bugun)}" else ""
        return listOf(
            "<!doctype html>",
            "<html lang=\"tr\"><head><meta charset=\"utf-8\"><title>Ödeme Planı</title>",
            "<style>",
            "  * { box-sizing: border-box; }",
            "  html, body { background: #ffffff !important; color: #0f172a; margin: 0; font-family: -apple-system, \"Segoe UI\", Roboto, Arial, sans-serif; }",
            "  .doc { max-width: 820px; margin: 0 auto; padding: 8px; }",
            "  .head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #0ea5e9; padding-bottom: 12px; margin-bottom: 16px; }",
            "  .meta { text-align: right; font-size: 12px; color: #475569; }",
            "  h1 { font-size: 16px; margin: 0 0 4px; color:#0f172a; }",
            "  .info { font-size: 12px; color:#475569; margin-bottom: 16px; } .info b { color:#0f172a; }",
            "  table { width: 100%; border-collapse: collapse; margin-bottom: 18px; font-size: 12px; }",
            "  th, td { border: 1px solid #e2e8f0; padding: 6px 8px; text-align: left; }",
            "  thead th { background: #0ea5e9; color: #ffffff; font-size: 11px; text-transform: uppercase; letter-spacing:.5px; }",
            "  td.r, th.r { text-align: right; } .sub { font-size: 9px; color: #94a3b8; }",
            "  td.best { background: #dcfce7; color: #15803d; font-weight: 700; } td.bank { font-weight: 700; }",
            "  .section { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing:.6px; color:#0369a1; margin: 6px 0; }",
            "  .total { font-size: 14px; font-weight: 800; color:#0f172a; }",
            "  .foot { margin-top: 24px; font-size: 10px; color:#94a3b8; text-align:center; border-top:1px solid #e2e8f0; padding-top:8px; }",
            "</style></head>",
            "<body><div class=\"doc\">",
            "  <div class=\"head\">$antet<div class=\"meta\">Ödeme Planı$tarihSatiri</div></div>",
            "  <h1>Ödeme Planı</h1>",
            "  <div class=\"info\">",
            "    <div>Hasta: <b>${e(hastaAdi.ifEmpty { "—" })}</b></div>",
            "    <div>Tedavi Planı: <b>${e(planAdi?.takeIf { it.isNotEmpty() } ?: "—")}</b></div>",
            "    <div>Toplam Tutar: <b class=\"total\">${e(p(tutar))}</b> · $adet taksit</div>",
            "  </div>",
            "  <div class=\"section\">Nakit Taksit Planı</div>",
            "  <table><thead><tr><th>Taksit</th><th class=\"r\">Tutar</th><th>Vade</th></tr></thead><tbody>$satirlar</tbody></table>",
            "  ${PlanCiktisi.odemeBilgisiHtml(ayar, kunye, "tr")}",
            "  $karsilastirma",
            "  <div class=\"foot\">Bu belge bilgilendirme amaçlıdır · ${e(bugun)}</div>",
            "</div></body></html>",
        ).joinToString("\n")
    }
}

/**
 * C `%g` (Swift `String(format: "%g", x)`): 6 anlamlı basamak, sondaki sıfırlar atılır; ondalık üs < −4 ya da ≥ 6 ise bilimsel
 * ("2.5", "10", "1.23457e+06"). Java `%g` sıfırları atmaz ve hep 6 basamak yazar — bu yüzden elle.
 */
internal fun cG(x: Double): String {
    if (x.isNaN()) return "nan"
    if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
    if (x == 0.0) return if (1.0 / x < 0) "-0" else "0"
    val bd = BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN))
    val us = bd.precision() - bd.scale() - 1
    if (us < -4 || us >= 6) {
        val mantis = bd.movePointLeft(us).stripTrailingZeros().toPlainString()
        return "${mantis}e${if (us < 0) "-" else "+"}${abs(us).toString().padStart(2, '0')}"
    }
    return bd.stripTrailingZeros().toPlainString()
}

/** POS komisyon oranı (`GET /api/settings-collections?type=POS_KOMISYON&active=true`) — oran tanımsızsa `null` (0 sayılmaz). */
@Serializable
data class PosKomisyonu(
    val id: String,
    val name: String? = null,
    val label: String? = null,
    val data: Veri? = null,
) {
    @Serializable
    data class Veri(val single: EsnekSayi? = null, val installments: Map<String, EsnekSayi?>? = null)

    val ad: String get() = name ?: label ?: ""

    fun oran(taksit: Int): Double? = if (taksit <= 1) data?.single?.deger else data?.installments?.get(taksit.toString())?.deger
}

// MARK: Kayıtlı ödeme planları (web hasta kartı "Kayıtlı Ödeme Planları" — DentAIWeb @ 160244a5, M-E9.2)

@Serializable
data class KayitliOdemePlani(
    val id: String,
    val totalAmount: EsnekSayi,
    val notes: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val teklifBagi: Boolean? = null,
    val installments: List<Taksit> = emptyList(),
) {
    @Serializable
    data class Taksit(
        val id: String,
        val amount: EsnekSayi,
        val dueDate: String? = null,
        val status: String,
        val manualPaidAt: String? = null,
        val allocatedAmount: EsnekSayi? = null,
        val remainingAmount: EsnekSayi? = null,
        val manualPaidByAd: String? = null,
    )
}

// MARK: Uçlar

@Serializable private data class KayitliYanit(val paymentPlans: List<KayitliOdemePlani> = emptyList())
@Serializable private data class DuzenlemeYaniti(val isaretiDusenler: List<String>? = null)
@Serializable private data class PosYaniti(val items: List<PosKomisyonu> = emptyList())

/** `odemePlaniDuzenle` satırı — Swift `(id: String?, tutar: Double, vade: String?, durum: String)`; yeni satırda `id` yok. */
data class DuzenTaksiti(val id: String?, val tutar: Double, val vade: String?, val durum: String)

/** `GET /api/billing/payment-plans/patient/:id` (`fin.view`). */
suspend fun ApiClient.odemePlanlari(hastaId: String): List<KayitliOdemePlani> =
    request<KayitliYanit>(path = "/billing/payment-plans/patient/$hastaId").paymentPlans

/** `PUT /api/billing/payment-plans/:id` (`fin.payment.add`) — yeni satırda `id` yok. Dönen: elle "Ödendi" işareti düşen taksitler. */
suspend fun ApiClient.odemePlaniDuzenle(id: String, taksitler: List<DuzenTaksiti>): List<String> {
    val g = govde("installments" to taksitler.map { t ->
        govde("id" to t.id, "amount" to t.tutar, "dueDate" to (t.vade ?: JsonNull), "status" to t.durum)
    })
    return request<DuzenlemeYaniti>(method = "PUT", path = "/billing/payment-plans/$id", body = g).isaretiDusenler ?: emptyList()
}

suspend fun ApiClient.odemePlaniSil(id: String) {
    request<JsonElement>(method = "DELETE", path = "/billing/payment-plans/$id")
}

suspend fun ApiClient.taksitIsaretle(id: String, geriAl: Boolean) {
    request<JsonElement>(
        method = "POST", path = "/billing/installments/$id/${if (geriAl) "unmark-paid" else "mark-paid"}",
        body = JsonObject(emptyMap()),
    )
}

/** `POST /api/billing/payment-plans` (`fin.payment.add`). Tedavi planına BAĞLANMAZ — bağ yalnız `notes` metninde. */
suspend fun ApiClient.odemePlaniOlustur(hastaId: String, toplam: Double, not: String, taksitler: List<OdemePlani.Taksit>, hastayaBildir: Boolean? = null) {
    val g = govde(
        "patientId" to hastaId, "totalAmount" to toplam, "notes" to not,
        "installments" to taksitler.map { govde("amount" to it.tutar, "dueDate" to it.vade) },
        "notifyPatient" to hastayaBildir,
    )
    request<JsonElement>(method = "POST", path = "/billing/payment-plans", body = g)
}

suspend fun ApiClient.posKomisyonlari(): List<PosKomisyonu> =
    request<PosYaniti>(path = "/settings-collections", query = listOf("type" to "POS_KOMISYON", "active" to "true")).items
