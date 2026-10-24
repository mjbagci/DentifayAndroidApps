package com.dentifay.app.core

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/**
 * Hekim unvanı — web `backend/lib/hekim-unvani.js#hekimAdi` ile BİREBİR (M-E1.7A).
 *
 * Kişinin KENDİ unvanı korunur ("Prof. Dr. Ayşe Demir" aynen); yalnız çıplak "Dr." (ya da "Dt") "Dt." olur; unvansız ada
 * "Dt. " eklenir; ad yoksa boş. Desen ve eklenen unvan sunucu deposundaki `hekim-unvani.data.json`ın AYNEN kopyası olan
 * `assets/hekim-unvani.json`dan okunur — Kotlin'de ikinci desen YOK.
 * YALNIZ kişinin hekim olarak gösterildiği yerlerde çağrılır; hekim olmayan kişiler ve asistanın serbest metni AYNEN.
 */
object Unvan {
    /** Kural dosyasının çözülmüş hâli. Dosya yok ya da bozuksa null → `hekimAdi` adı AYNEN döndürür. */
    class Kural(val unvan: String, val desen: Regex) {
        companion object {
            /** `{ unvan, unvanDeseni, bayraklar }` — JS bayrakları: `i` duyarsız, `u` Unicode, `m`/`s`; tanınmayan bayrak kuralı geçersiz kılar. */
            fun coz(metin: String): Kural? {
                val o = runCatching { DfyJson.parseToJsonElement(metin) as? JsonObject }.getOrNull() ?: return null
                val unvan = o.str("unvan")?.takeIf { it.isNotEmpty() } ?: return null
                val desen = o.str("unvanDeseni") ?: return null
                val secenek = mutableSetOf<RegexOption>()
                for (b in o.str("bayraklar") ?: "") {
                    when (b) {
                        'i' -> secenek += RegexOption.IGNORE_CASE
                        'u' -> Unit   // Java'da IGNORE_CASE ile birlikte UNICODE_CASE aşağıda eklenir
                        'm' -> secenek += RegexOption.MULTILINE
                        's' -> secenek += RegexOption.DOT_MATCHES_ALL
                        else -> return null
                    }
                }
                val bayrak = secenek.fold(0) { acc, o2 -> acc or o2.value } or
                    (if (RegexOption.IGNORE_CASE in secenek) java.util.regex.Pattern.UNICODE_CASE else 0)
                val re = runCatching { java.util.regex.Pattern.compile(desen, bayrak).toRegex() }.getOrNull() ?: return null
                return Kural(unvan, re)
            }
        }
    }

    /** Paketteki kural (bir kez okunur). Birim testlerinde Android bağlamı yok → kaynak dosyadan okunur. */
    val kural: Kural? by lazy {
        val metin = Prefs.appContext?.let { ctx -> runCatching { ctx.assets.open("hekim-unvani.json").bufferedReader().readText() }.getOrNull() }
            ?: listOf("src/main/assets/hekim-unvani.json", "app/src/main/assets/hekim-unvani.json")
                .map(::File).firstOrNull { it.exists() }?.readText()
        metin?.let(Kural::coz)
    }

    private val tr = Locale.forLanguageTag("tr-TR")
    private val bosluklar = Regex("""\s+""")
    private val noktaBosluk = Regex("""[.\s]""")

    /** "Prof. Dr. Ayşe Demir" → ("Prof. Dr.", "Ayşe Demir"); unvansız ad → ("", ad). Web `unvanAyir`. */
    fun unvanAyir(ham: String?, kural: Kural? = Unvan.kural): Pair<String, String> {
        val s = (ham ?: "").trim()
        if (kural == null) return "" to s
        val m = kural.desen.find(s) ?: return "" to s
        val unvan = (m.groups[1]?.value ?: "").trim().replace(bosluklar, " ")
        val ad = s.substring(m.range.last + 1).trim()
        return unvan to ad
    }

    /** Baştaki unvan dizisi atılmış ad. Web `unvansizAd`. */
    fun unvansizAd(ham: String?, kural: Kural? = Unvan.kural): String = unvanAyir(ham, kural).second

    /** Ürünün yazdığı hekim adı. Web `hekimAdi`. Kural okunamadıysa ad AYNEN (çökme yok, unvan eklenmez). */
    fun hekimAdi(ham: String?, kural: Kural? = Unvan.kural): String {
        if (kural == null) return ham ?: ""
        val (unvan, ad) = unvanAyir(ham, kural)
        if (ad.isEmpty()) return ""
        if (unvan.isEmpty() || ciplakMi(unvan)) return "${kural.unvan} $ad"
        return "$unvan $ad"
    }

    /** Oturum kişisinin adı: rolü `DOCTOR` ise `hekimAdi`, değilse AYNEN (banko/yönetici "Dt." almaz). */
    fun oturumAdi(ad: String?, rol: String?, kural: Kural? = Unvan.kural): String =
        if (rol?.uppercase() == "DOCTOR") hekimAdi(ad, kural) else (ad ?: "")

    /** Unvan yalnız çıplak "Dr." ya da "Dt." mi? Web `ciplakMi`. */
    private fun ciplakMi(unvan: String): Boolean {
        val k = unvan.lowercase(tr).replace(noktaBosluk, "")
        return k == "dr" || k == "dt"
    }
}
