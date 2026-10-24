package com.dentifay.app.hasta

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Plan satırı tutarları — web `lib/treatment-pricing.js#{kalemSatirToplamiTL, planSatiriTutariTL, planSatiriIndirimiTL}` ikizi
 * (DentAIWeb @ afc3170). Tutarlar sunucudan; istemci yalnız sunucunun alanlarını okur (grup satırı `grupToplami`, yoksa çocuklar).
 */
object PlanTutari {
    /** Kalemin TL satır toplamı: dolu `totalAmountTRY`; yoksa fiyat × adet × kur − indirim (sıfırın altına inmez, kuruşa yuvarlanır). */
    fun kalemSatirToplami(k: TedaviKalemi): Double {
        val t = k.totalAmountTRY?.deger
        if (t != null && t != 0.0) return t
        val brut = (k.price?.deger ?: 0.0) * (k.quantity ?: 1).toDouble() * (k.exchangeRate?.deger ?: 1.0)
        val net = max(0.0, brut - (k.discount?.deger ?: 0.0))
        return yakinaYuvarla(net * 100) / 100
    }

    /** Plan satırının tutarı: grup → `grupToplami`, yoksa canlı çocukların toplamı; çok dişli satırın tutarı hiç yoksa `null` ("—", ₺0 DEĞİL). */
    fun satirTutari(k: TedaviKalemi): Double? {
        k.grupToplami?.let { return it.deger }
        val cocuk = k.canliCocuklar
        if (cocuk.isNotEmpty()) return yakinaYuvarla(cocuk.fold(0.0) { a, c -> a + kalemSatirToplami(c) } * 100) / 100
        val grupGibi = k.parentItemId == null && (k.teeth?.size ?: 0) >= 2 && !((k.price?.deger ?: 0.0) > 0)
        return if (grupGibi) null else kalemSatirToplami(k)
    }

    fun satirIndirimi(k: TedaviKalemi): Double {
        k.grupIndirimi?.let { return it.deger }
        val cocuk = k.canliCocuklar
        if (cocuk.isNotEmpty()) return yakinaYuvarla(cocuk.fold(0.0) { a, c -> a + (c.discount?.deger ?: 0.0) } * 100) / 100
        return k.discount?.deger ?: 0.0
    }
}

/**
 * Swift `rounded()` (= `.toNearestOrAwayFromZero`): en yakın tama, yarıda SIFIRDAN UZAĞA. Kotlin `round` yarıda çifte yuvarlar,
 * `Math.round` yarıda yukarı (eksi sayıda sıfıra) — ikisi de kuruş eşliğini bozar.
 */
internal fun yakinaYuvarla(x: Double): Double {
    if (!x.isFinite()) return x
    val taban = floor(x)
    val fark = x - taban
    return when {
        fark > 0.5 -> taban + 1
        fark < 0.5 -> taban
        else -> if (x > 0) taban + 1 else taban
    }
}

/** Web `formatCurrency` (tr-TR, daima TRY): "₺1.234,50"; `null` → "—". */
object Para {
    /** `NumberFormat` iş parçacığı güvenli değil — her iş parçacığına bir tane (bir kez kurulur). */
    private val bicimci = object : ThreadLocal<NumberFormat>() {
        override fun initialValue(): NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
            currency = Currency.getInstance("TRY")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    }

    fun tl(v: Double?): String {
        if (v == null || !v.isFinite()) return "—"
        return bicimci.get()?.format(v) ?: "—"
    }
}

/** Web tarih biçimleri: plan kartı "24 Eki 2026" · aşama listesi "24 Eki 26" · medya "24 Ekim 2026". */
object Tarih {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val kisa: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", tr)
    private val cokKisa: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yy", tr)
    private val uzun: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", tr)

    /** ISO an ("…T…Z", kesirli ya da değil); olmazsa ilk 10 karakter cihazın saat diliminde gün başı. */
    fun tarih(s: String?): Instant? {
        if (s == null) return null
        isoAnCoz(s)?.let { return it }
        return runCatching { LocalDate.parse(s.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    fun plan(s: String?): String = bicimle(s, kisa)
    fun asama(s: String?): String = bicimle(s, cokKisa)
    fun medya(s: String?): String = bicimle(s, uzun)

    private fun bicimle(s: String?, f: DateTimeFormatter): String =
        tarih(s)?.let { f.format(it.atZone(ZoneId.systemDefault())) } ?: "—"
}

/** Swift `ISO8601DateFormatter` (`.withInternetDateTime`, kesirli saniyeli ya da değil) karşılığı — saat dilimi işareti şart. */
internal fun isoAnCoz(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
