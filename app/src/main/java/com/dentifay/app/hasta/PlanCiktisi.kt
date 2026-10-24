package com.dentifay.app.hasta

import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.Unvan
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Currency
import java.util.Locale
import kotlin.math.max

/**
 * Tedavi planı çıktısı — web `features/print/planPrint.js#buildPlanPrintHtml` (kip "plan") ikizi (DentAIWeb @ 160244a5; M-E8.12 + M-E9.1).
 * Sunucuda plan PDF'i YOK: HTML istemcide kurulur ve yazdırma penceresinde açılır (yazdır / PDF). Antet `organization-settings/letterhead`,
 * ayarlar `PRINT_CONFIG` (15 anahtar, web varsayılanları), diş şeması uygulamanın şema çiziminden (PNG gömülü), para daima TL.
 */
object PlanCiktisi {

    // MARK: Ayar (`backend/lib/yazdirma-ayarlari.data.json`)

    @Serializable
    data class Ayar(
        val clinicHeader: Boolean? = null,
        val doctorInfo: Boolean? = null,
        val patientContact: Boolean? = null,
        val printDate: Boolean? = null,
        val treatmentDates: Boolean? = null,
        val priceInfo: Boolean? = null,
        val unitPrice: Boolean? = null,
        val discountRate: Boolean? = null,
        val fxEquivalent: Boolean? = null,
        val toothChart: Boolean? = null,
        val paymentPlanTable: Boolean? = null,
        val planNote: Boolean? = null,
        val totalsMode: String? = null,
        val odemeBilgisi: Boolean? = null,
        val planLanguage: String? = null,
    ) {
        val antet: Boolean get() = clinicHeader ?: true
        val hekim: Boolean get() = doctorInfo ?: true
        val iletisim: Boolean get() = patientContact ?: false
        val tarih: Boolean get() = printDate ?: true
        val tedaviTarihi: Boolean get() = treatmentDates ?: true
        val fiyat: Boolean get() = priceInfo ?: true
        /** `priceInfo:false` → fiyata bağlı anahtarların hepsi kapanır (web `yazdirmaAyari`). */
        val birim: Boolean get() = fiyat && (unitPrice ?: false)
        val indirim: Boolean get() = fiyat && (discountRate ?: true)
        val doviz: Boolean get() = fiyat && (fxEquivalent ?: false)
        val sema: Boolean get() = toothChart ?: true
        val not: Boolean get() = planNote ?: false
        val toplamKipi: String
            get() = if (fiyat) (if ((totalsMode ?: "") in listOf("subtotalsAndGrand", "grandOnly", "none")) (totalsMode ?: "grandOnly") else "grandOnly") else "none"
        val iban: Boolean get() = odemeBilgisi ?: false
        val dil: String get() = if (planLanguage == "en") "en" else "tr"
    }

    // MARK: Künye (`GET /api/organization-settings/letterhead`)

    @Serializable
    data class Kunye(val letterhead: Antet? = null, val odemeBilgisi: OdemeBilgisi? = null) {
        @Serializable
        data class Antet(
            val name: String? = null,
            val tradeName: String? = null,
            val taxNo: String? = null,
            val taxOffice: String? = null,
            val taxAddress: String? = null,
            val address: String? = null,
            val phone: String? = null,
            val email: String? = null,
            val website: String? = null,
            val logoDataUrl: String? = null,
        )

        @Serializable
        data class OdemeBilgisi(val iban: String? = null, val aliciAdi: String? = null, val bankaAdi: String? = null)
    }

    // MARK: Baskı sözlüğü (`tr.json › print.*` — belge dili arayüzden bağımsız)

    private val SOZLUK_TR = mapOf(
        "planTitle" to "Tedavi Planı", "planned" to "Planlama", "todo" to "Yapılacak", "done" to "Yapılan", "colNo" to "No", "colTooth" to "Diş",
        "colTreatment" to "Tedavi", "colDate" to "Tarih", "colQty" to "Adet", "colUnitPrice" to "Birim fiyat", "colDiscount" to "İndirim",
        "colFx" to "Döviz karşılığı", "colAmount" to "Tutar", "labelPatient" to "Hasta", "labelPhone" to "Telefon", "labelDoctor" to "Hekim",
        "noteTitle" to "Açıklama", "subtotal" to "Ara toplam", "listTotal" to "Liste tutarı", "totalDiscount" to "Toplam indirim",
        "grandTotal" to "Genel toplam", "noItems" to "Planda kalem bulunmuyor.", "infoOnly" to "Bu belge bilgilendirme amaçlıdır.",
        "treatments" to "Tedavi İçeriği", "odeme" to "Ödeme bilgisi (Havale / EFT)", "holder" to "Alıcı", "bank" to "Banka", "iban" to "IBAN",
        "tel" to "Tel", "vd" to "Vergi Dairesi", "vkn" to "VKN/TCKN",
    )
    private val SOZLUK_EN = mapOf(
        "planTitle" to "Treatment Plan", "planned" to "Planned", "todo" to "To do", "done" to "Done", "colNo" to "No", "colTooth" to "Tooth",
        "colTreatment" to "Treatment", "colDate" to "Date", "colQty" to "Qty", "colUnitPrice" to "Unit price", "colDiscount" to "Discount",
        "colFx" to "FX equivalent", "colAmount" to "Amount", "labelPatient" to "Patient", "labelPhone" to "Phone", "labelDoctor" to "Doctor",
        "noteTitle" to "Note", "subtotal" to "Subtotal", "listTotal" to "List amount", "totalDiscount" to "Total discount",
        "grandTotal" to "Grand total", "noItems" to "The plan has no items.", "infoOnly" to "This document is for information purposes.",
        "treatments" to "Treatments", "odeme" to "Payment details (bank transfer)", "holder" to "Account holder", "bank" to "Bank", "iban" to "IBAN",
        "tel" to "Tel", "vd" to "Tax office", "vkn" to "Tax ID",
    )

    fun L(dil: String, k: String): String = (if (dil == "en") SOZLUK_EN else SOZLUK_TR)[k] ?: k

    // MARK: Biçim

    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private val TR = Locale.forLanguageTag("tr-TR")
    private val EN_US = Locale.forLanguageTag("en-US")
    private val EN_GB = Locale.forLanguageTag("en-GB")

    /** Biçimleyiciler iş parçacığı güvenli değil — her iş parçacığına bir takım (bir kez kurulur). */
    private class Bicimleyiciler {
        val paraTr: NumberFormat = paraBicimi(TR)
        val paraEn: NumberFormat = paraBicimi(EN_US)
        val sayiTr: NumberFormat = sayiBicimi(TR)
        val sayiEn: NumberFormat = sayiBicimi(EN_US)
        /** Swift `NumberFormatter` (stil yok, en_US_POSIX, ≤ 6 ondalık) — "1", "34.5". */
        val kur = DecimalFormat("0.######", DecimalFormatSymbols(Locale.ROOT))

        private fun paraBicimi(l: Locale) = NumberFormat.getCurrencyInstance(l).apply {
            currency = Currency.getInstance("TRY")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }

        private fun sayiBicimi(l: Locale) = NumberFormat.getNumberInstance(l).apply { maximumFractionDigits = 6 }
    }

    private val bicim = object : ThreadLocal<Bicimleyiciler>() {
        override fun initialValue() = Bicimleyiciler()
    }

    private fun b(): Bicimleyiciler = bicim.get() ?: Bicimleyiciler()

    fun para(v: Double, dil: String): String = (if (dil == "en") b().paraEn else b().paraTr).format(v)

    private val GUN_AY_YIL_TR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", TR)
    private val GUN_AY_YIL_EN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy", EN_GB)

    private fun gunAyYil(dil: String) = if (dil == "en") GUN_AY_YIL_EN else GUN_AY_YIL_TR

    /** `12 Ekim 2026` — salt tarih (`YYYY-MM-DD`) UTC olarak okunur ve biçimlenir (gün kaymaz); an ise kliniğin saat diliminde. */
    fun tarih(s: String?, dil: String, saatDilimi: ZoneId): String {
        if (s.isNullOrEmpty()) return "—"
        val f = gunAyYil(dil)
        if (s.length == 10) {
            val d = runCatching { LocalDate.parse(s) }.getOrNull() ?: return "—"
            return f.format(d)
        }
        val an = isoAnCoz(s) ?: return "—"
        return f.format(an.atZone(saatDilimi))
    }

    fun bugun(simdi: Instant, dil: String, saatDilimi: ZoneId): String = gunAyYil(dil).format(simdi.atZone(saatDilimi))

    /** `kalemSatirToplamiTL`: sunucunun TL toplamı (≠0) yoksa fiyat × adet × kur − indirim. */
    fun satirTL(k: TedaviKalemi): Double {
        val d = k.totalAmountTRY?.deger
        if (d != null && d.isFinite() && d != 0.0) return d
        val brut = IslemKurallari.brut(IslemKurallari.Satir(fiyat = k.price?.deger ?: 0.0, adet = k.quantity ?: 1, kur = k.exchangeRate?.deger))
        return IslemKurallari.yuvarla(max(0.0, brut - (k.discount?.deger ?: 0.0)))
    }

    /** Döviz karşılığı: `100 USD × 1` (dövizli kalemde), aksi "—". */
    fun dovizMetni(k: TedaviKalemi, dil: String): String {
        val c = k.currency?.uppercase()
        if (c.isNullOrEmpty() || c == "TRY") return "—"
        val fiyat = (if (dil == "en") b().sayiEn else b().sayiTr).format(k.price?.deger ?: 0.0)
        val kur = k.exchangeRate?.let { b().kur.format(it.deger) } ?: "1"
        return "$fiyat $c × $kur"
    }

    /** Yapraklar: grup kalemi kapsayıcıdır (çocukları basılır), silinmişler yok. */
    fun yapraklar(plan: HastaPlani): List<TedaviKalemi> =
        (plan.items ?: emptyList()).filter { it.parentItemId == null }
            .flatMap { k -> if (k.children?.isEmpty() == false) k.canliCocuklar else listOf(k) }
            .filter { it.deletedAt == null }

    // MARK: Antet ve IBAN (web `buildLetterheadHtml`, `odemeBilgisiHtml`)

    fun telefonGoster(t: String): String {
        val r = t.replace(" ", "")
        if (!r.startsWith("+90") || r.length != 13 || !r.drop(3).all { it.isDigit() }) return t
        val d = r.drop(3)
        return "+90 ${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6, 8)} ${d.substring(8, 10)}"
    }

    fun antetHtml(k: Kunye?, dil: String): String {
        val lh = k?.letterhead ?: return ""
        val ad = lh.name?.takeIf { it.isNotEmpty() } ?: return ""
        val eposta = lh.email?.let { if (it.lowercase().trim().endsWith(".invalid")) null else it }
        val iletisim = listOf(
            lh.taxAddress ?: lh.address,
            listOfNotNull(lh.phone?.let { "${L(dil, "tel")}: ${telefonGoster(it)}" }, eposta).joinToString(" · "),
            lh.website,
            listOfNotNull(lh.taxOffice?.let { "${L(dil, "vd")}: $it" }, lh.taxNo?.let { "${L(dil, "vkn")}: $it" }).joinToString(" · "),
        ).filterNotNull().filter { it.isNotEmpty() }.joinToString("") { "<div>${esc(it)}</div>" }
        val gorsel = lh.logoDataUrl?.let {
            "<img src=\"${esc(it)}\" alt=\"\" style=\"max-height:52px;max-width:150px;width:auto;display:block;flex:0 0 auto;\" />"
        } ?: ""
        val ticariAd = lh.tradeName?.let { "<div style=\"font-size:11px;color:#4b5563;line-height:1.4;\">${esc(it)}</div>" } ?: ""
        val iletisimKutusu = if (iletisim.isEmpty()) "" else
            "<div class=\"belge-kunye-iletisim\" style=\"text-align:right;font-size:10.5px;line-height:1.5;color:#374151;overflow-wrap:anywhere;\">$iletisim</div>"
        return listOf(
            "<div class=\"letterhead belge-kunye\" style=\"display:flex;align-items:flex-end;justify-content:space-between;gap:10px 24px;padding-bottom:10px;margin-bottom:18px;border-bottom:1.5px solid #1e3a5f;color:#111827;\">",
            "  <div style=\"display:flex;align-items:center;gap:12px;min-width:0;\">$gorsel<div style=\"min-width:0;\">",
            "    <div style=\"font-size:16px;font-weight:700;line-height:1.25;letter-spacing:-0.01em;\">${esc(ad)}</div>",
            "    $ticariAd",
            "  </div></div>",
            "  $iletisimKutusu",
            "</div>",
        ).joinToString("\n")
    }

    fun ibanGrupla(i: String): String = i.replace(" ", "").uppercase().chunked(4).joinToString(" ")

    fun odemeBilgisiHtml(ayar: Ayar, k: Kunye?, dil: String): String {
        if (!ayar.iban) return ""
        val bilgi = k?.odemeBilgisi ?: return ""
        val iban = bilgi.iban?.takeIf { it.isNotEmpty() } ?: return ""
        val satirlar = mutableListOf<Triple<String, String, Boolean>>()
        bilgi.aliciAdi?.takeIf { it.isNotEmpty() }?.let { satirlar += Triple(L(dil, "holder"), it, false) }
        bilgi.bankaAdi?.takeIf { it.isNotEmpty() }?.let { satirlar += Triple(L(dil, "bank"), it, false) }
        satirlar += Triple(L(dil, "iban"), ibanGrupla(iban), true)
        val hucre = "border-bottom:1px solid #d1d5db;padding:6px 8px;text-align:left;vertical-align:top"
        val govde = satirlar.joinToString("") { s ->
            val es = if (s.third) ";font-family:ui-monospace,Menlo,Consolas,monospace;letter-spacing:.02em" else ""
            "<tr><th style=\"$hucre;font-weight:600;color:#374151;width:34%\">${esc(s.first)}</th><td style=\"$hucre;color:#111827$es\">${esc(s.second)}</td></tr>"
        }
        return "<div class=\"odeme-bilgisi\" style=\"margin:16px 0;break-inside:avoid;page-break-inside:avoid\">" +
            "<div style=\"font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:#111827;margin:0 0 5px\">${esc(L(dil, "odeme"))}</div>" +
            "<table style=\"width:100%;border-collapse:collapse;font-size:12px;border-top:1px solid #9ca3af\"><tbody>$govde</tbody></table></div>"
    }

    // MARK: Belge

    fun sutunSayisi(a: Ayar): Int = 4 + listOf(a.tedaviTarihi, a.birim, a.indirim, a.doviz, a.toplamKipi != "none").count { it }

    /** Diş şeması basılacak mı (web `semaGirdisi` → dişe / yarım çeneye / çeneye bağlı kalem yoksa `null`). */
    fun semaVarMi(plan: HastaPlani): Boolean = yapraklar(plan).any { DisSemasi.kalemDisleri(it).isNotEmpty() }

    fun html(
        plan: HastaPlani,
        hasta: HastaBasligi,
        kunye: Kunye?,
        ayar: Ayar,
        saatDilimi: ZoneId,
        simdi: Instant = Instant.now(),
        semaPNG: ByteArray? = null,
    ): String {
        val dil = ayar.dil
        val n = sutunSayisi(ayar)
        val bugunMetni = bugun(simdi, dil, saatDilimi)
        val yaprak = yapraklar(plan)

        val bas = StringBuilder()
        bas.append("<th class=\"r\" style=\"width:36px\">${esc(L(dil, "colNo"))}</th><th style=\"width:92px\">${esc(L(dil, "colTooth"))}</th><th>${esc(L(dil, "colTreatment"))}</th>")
        if (ayar.tedaviTarihi) bas.append("<th style=\"width:110px\">${esc(L(dil, "colDate"))}</th>")
        bas.append("<th class=\"r\" style=\"width:54px\">${esc(L(dil, "colQty"))}</th>")
        if (ayar.birim) bas.append("<th class=\"r\" style=\"width:100px\">${esc(L(dil, "colUnitPrice"))}</th>")
        if (ayar.indirim) bas.append("<th class=\"r\" style=\"width:90px\">${esc(L(dil, "colDiscount"))}</th>")
        if (ayar.doviz) bas.append("<th class=\"r\" style=\"width:130px\">${esc(L(dil, "colFx"))}</th>")
        if (ayar.toplamKipi != "none") bas.append("<th class=\"r\" style=\"width:120px\">${esc(L(dil, "colAmount"))}</th>")

        val govde = StringBuilder()
        var no = 0
        var genel = 0.0
        var indirimToplami = 0.0
        for ((kod, anahtar) in listOf("PLANNED" to "planned", "TODO" to "todo", "DONE" to "done")) {
            val grup = yaprak.filter { k -> (if ((k.stage ?: "") in listOf("TODO", "DONE")) k.stage else "PLANNED") == kod }
            if (grup.isEmpty()) continue
            val satirlar = StringBuilder("<tr class=\"grp-head\"><td colspan=\"$n\">${esc(L(dil, anahtar))}</td></tr>")
            var ara = 0.0
            for (k in grup) {
                no += 1
                val tutar = satirTL(k)
                ara += tutar
                val ind = k.discount?.deger ?: 0.0
                indirimToplami += ind
                val disler = (k.teeth ?: emptyList()).map { it.toothCode }
                val dis = if (disler.isEmpty()) (k.tooth?.takeIf { it.isNotEmpty() } ?: "—") else disler.joinToString(", ")
                val h = StringBuilder("<td class=\"r\">$no</td><td>${esc(dis)}</td><td>${esc(k.name.ifEmpty { "—" })}</td>")
                if (ayar.tedaviTarihi) h.append("<td>${esc(tarih(k.treatmentDate ?: k.completedAt, dil, saatDilimi))}</td>")
                h.append("<td class=\"r\">${k.quantity ?: 1}</td>")
                if (ayar.birim) h.append("<td class=\"r\">${esc(para(k.price?.deger ?: 0.0, dil))}</td>")
                if (ayar.indirim) h.append("<td class=\"r\">${if (ind != 0.0) esc(para(ind, dil)) else "—"}</td>")
                if (ayar.doviz) h.append("<td class=\"r\">${esc(dovizMetni(k, dil))}</td>")
                if (ayar.toplamKipi != "none") h.append("<td class=\"r\">${esc(para(tutar, dil))}</td>")
                satirlar.append("<tr>$h</tr>")
            }
            genel += ara
            if (ayar.toplamKipi == "subtotalsAndGrand") {
                satirlar.append("<tr class=\"sub\"><td colspan=\"${n - 1}\" class=\"r\">${esc(L(dil, "subtotal"))}</td><td class=\"r\">${esc(para(ara, dil))}</td></tr>")
            }
            govde.append("<tbody class=\"grp\">$satirlar</tbody>")
        }
        if (govde.isEmpty()) govde.append("<tbody><tr><td colspan=\"$n\">${esc(L(dil, "noItems"))}</td></tr></tbody>")

        var dip = ""
        if (ayar.toplamKipi != "none") {
            if (ayar.indirim && indirimToplami > 0) {
                dip += "<tr class=\"ara\"><td colspan=\"${n - 1}\" class=\"r\">${esc(L(dil, "listTotal"))}</td><td class=\"r\">${esc(para(genel + indirimToplami, dil))}</td></tr>"
                dip += "<tr class=\"ara\"><td colspan=\"${n - 1}\" class=\"r\">${esc(L(dil, "totalDiscount"))}</td><td class=\"r\">− ${esc(para(indirimToplami, dil))}</td></tr>"
            }
            dip = "<tfoot>$dip<tr class=\"total\"><td colspan=\"${n - 1}\" class=\"r\">${esc(L(dil, "grandTotal"))}</td><td class=\"r\">${esc(para(genel, dil))}</td></tr></tfoot>"
        }

        val antet = if (ayar.antet) antetHtml(kunye, dil) else "<div style=\"height:46px\"></div>"
        val hekim = if (ayar.hekim) {
            // Kartta olduğu gibi "Dt." unvanıyla (Unvan.hekimAdi — çifte unvan yazmaz, "Dr."yi "Dt." yapar).
            plan.doctor?.name?.let { Unvan.hekimAdi(it) }?.takeIf { it.isNotEmpty() }
                ?.let { "<div class=\"meta\">${esc(L(dil, "labelDoctor"))}: <b>${esc(it)}</b></div>" } ?: ""
        } else ""
        var taraflar = "<div><div class=\"alan-etiket\">${esc(L(dil, "labelPatient"))}</div><div class=\"alan-deger\">${esc(hasta.ad.ifEmpty { "—" })}</div></div>"
        if (ayar.iletisim) {
            taraflar += "<div><div class=\"alan-etiket\">${esc(L(dil, "labelPhone"))}</div><div class=\"alan-deger\">${esc(hasta.telefon?.takeIf { it.isNotEmpty() } ?: "—")}</div></div>"
        }
        var semaBolumu = ""
        if (ayar.sema && semaVarMi(plan) && semaPNG != null) {
            semaBolumu = "<h3 class=\"sec\">${esc(L(dil, "treatments"))}</h3><div class=\"sema\"><img src=\"data:image/png;base64,${Base64.getEncoder().encodeToString(semaPNG)}\" alt=\"\" style=\"width:100%;height:auto;display:block\" /></div>"
        }
        val not = if (ayar.not) {
            plan.notes?.takeIf { it.isNotEmpty() }?.let { "<h3 class=\"sec\">${esc(L(dil, "noteTitle"))}</h3><p class=\"note-body\">${esc(it)}</p>" } ?: ""
        } else ""
        val baslik = L(dil, "planTitle")
        val alt = esc(L(dil, "infoOnly")) + (if (ayar.tarih) " · ${esc(bugunMetni)}" else "")
        val planAdi = plan.title?.takeIf { it.isNotEmpty() }?.let { "<div class=\"meta\">${esc(it)}</div>" } ?: ""
        val tarihKutusu = if (ayar.tarih) "<div class=\"head-sag\"><div class=\"meta\">${esc(bugunMetni)}</div></div>" else ""

        return listOf(
            "<!doctype html>",
            "<html lang=\"$dil\"><head><meta charset=\"utf-8\" /><title>${esc(baslik)}</title>",
            "<style>",
            "  * { box-sizing:border-box; }",
            "  html, body { background:#fff !important; color:#111827; margin:0; font-family:\"Plus Jakarta Sans\",\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,sans-serif; line-height:1.5; }",
            "  body { padding:24px 16px; }",
            "  .doc { max-width:760px; margin:0 auto; font-size:12px; }",
            "  .head { display:flex; flex-wrap:wrap; align-items:flex-end; justify-content:space-between; gap:4px 18px; margin-bottom:14px; }",
            "  .head .letterhead, .head > div:first-child[style] { flex:1 0 100%; }",
            "  .title { font-size:20px; font-weight:700; letter-spacing:-0.01em; line-height:1.25; }",
            "  .head-sag { text-align:right; }",
            "  .meta { font-size:11px; color:#374151; } .meta b { color:#111827; font-weight:600; }",
            "  .taraflar { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:6px 18px; border:1px solid #d1d5db; border-radius:4px; padding:9px 12px; margin-bottom:16px; }",
            "  .alan-etiket { font-size:9px; text-transform:uppercase; letter-spacing:.07em; color:#6b7280; }",
            "  .alan-deger { font-size:12px; font-weight:600; overflow-wrap:anywhere; }",
            "  h3.sec { font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:.05em; color:#111827; margin:18px 0 6px; break-after:avoid; page-break-after:avoid; }",
            "  .table { width:100%; border-collapse:collapse; font-size:11.5px; margin-bottom:10px; }",
            "  .table thead { display:table-header-group; }",
            "  .table th, .table td { padding:6px 7px; text-align:left; vertical-align:top; }",
            "  .table thead th { font-size:9.5px; font-weight:600; text-transform:uppercase; letter-spacing:.05em; color:#374151; border-bottom:1px solid #9ca3af; }",
            "  .table tbody td { border-bottom:1px solid #d1d5db; }",
            "  .table tr { break-inside:avoid; page-break-inside:avoid; }",
            "  .table .r, .table th.r, .table td.r { text-align:right; white-space:nowrap; font-variant-numeric:tabular-nums; }",
            "  .table .grp-head td { font-weight:700; font-size:10px; text-transform:uppercase; letter-spacing:.06em; color:#111827; padding-top:12px; border-bottom:1px solid #9ca3af; }",
            "  .table .sub td { font-weight:600; border-bottom:1px solid #9ca3af; }",
            "  .table tfoot { display:table-row-group; }",
            "  .table tfoot td { border:0; padding:4px 7px; }",
            "  .table tfoot .ara td { color:#374151; }",
            "  .table tfoot .total td { font-size:13px; font-weight:700; color:#111827; border-top:1.5px solid #111827; padding-top:7px; }",
            "  .note-body { font-size:12px; line-height:1.6; white-space:pre-wrap; margin:0; }",
            "  .foot { font-size:10px; color:#6b7280; margin-top:22px; border-top:1px solid #d1d5db; padding-top:6px; break-inside:avoid; }",
            "  .sema { break-inside:avoid; page-break-inside:avoid; margin-top:4px; }",
            "</style></head>",
            "<body><div class=\"doc\">",
            "  <div class=\"head\">",
            "    $antet",
            "    <div><div class=\"title\">${esc(baslik)}</div>$planAdi$hekim</div>",
            "    $tarihKutusu",
            "  </div>",
            "  <div class=\"taraflar\">$taraflar</div>",
            "  <table class=\"table\"><thead><tr>$bas</tr></thead>$govde$dip</table>",
            "  $semaBolumu",
            "  ${odemeBilgisiHtml(ayar, kunye, dil)}",
            "  $not",
            "  <div class=\"foot\">$alt</div>",
            "</div></body></html>",
        ).joinToString("\n")
    }
}

// MARK: - Uçlar

@Serializable
private data class AyarYaniti(val data: PlanCiktisi.Ayar? = null)

/** Swift `try?` — hata `null` olur; iptal (CancellationException) YUTULMAZ. */
internal inline fun <T> sessizce(blok: () -> T): T? = try {
    blok()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Okunamazsa web gibi varsayılanlarla basılır. */
suspend fun ApiClient.yazdirmaAyari(): PlanCiktisi.Ayar =
    sessizce { request<AyarYaniti>(path = "/settings-forms/PRINT_CONFIG") }?.data ?: PlanCiktisi.Ayar()

/** Kurum künyesi (antet + IBAN); okunamazsa `null` (çıktı engellenmez). */
suspend fun ApiClient.kurumKunyesi(): PlanCiktisi.Kunye? =
    sessizce { request<PlanCiktisi.Kunye>(path = "/organization-settings/letterhead") }
