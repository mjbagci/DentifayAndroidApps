package com.dentifay.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Paket erişim sınırı (MOBIL_IS_EMRI M-E3.6): sekme, kart, kısayol ve derin bağlantı YALNIZ oturumun `capabilities`
 * listesinden süzülür. Koda yazılı rol ya da PAKET listesi YOK — tavanı sunucu uygular, oturum yanıtı onu taşır; burada
 * yalnız her yüzeyin sunucu kataloğundaki yetenek adı var. Alan yoksa küme BOŞ sayılır (fail-closed).
 */
object Yetki {
    enum class Yuzey(val yetenek: String) {
        randevular("appt.view"),
        /** Tedavi Planı SEKMESİ — hastanın Planlama · Yapılacak · Yapılan · Medya sayfası (M-E8.1). */
        hastaTedavi("chart.view"),
        stok("inv.view"),
        rontgen("ai.xray"),
        gulus("ai.smilesim"),
        /** "Sesli Tedavi Planı" kartı. */
        tedaviPlani("chart.plan.edit"),
        asistan("ai.assistant"),
        yeniRandevu("appt.create"),
    }

    fun gorur(caps: Set<String>, yuzey: Yuzey): Boolean = yuzey.yetenek in caps

    /** Randevu durum eyleminin yetenekleri — sunucunun kapısıyla AYNI (ARRIVED `appt.checkin`, NO_SHOW `appt.noshow`). */
    fun randevuEylemi(hedefDurum: String): List<String> = when (hedefDurum) {
        "ARRIVED" -> listOf("appt.edit", "appt.checkin")
        "NO_SHOW" -> listOf("appt.edit", "appt.noshow")
        else -> listOf("appt.edit")
    }

    /** Analiz Merkezi'nin kartları — sekme yalnız en az biri görünürken çizilir. */
    val analizKartlari: List<Yuzey> = listOf(Yuzey.rontgen, Yuzey.gulus, Yuzey.tedaviPlani, Yuzey.asistan)

    /** Ek MO3.7 — YALNIZ bildirimden ya da derin bağlantıdan yetkisiz bir ekrana gidilmek istendiğinde (ekran açılmaz; M-E7.5). */
    const val KAPSAM_DISI_METNI = "Bu bölüm paketinizin ya da yetkilerinizin kapsamında değil."

    /** Yetkisiz EYLEM uyarısı — sönük düğmeye dokununca ve kodsuz 403 alan bir eylemde (M-E7.4 · M-E7.5). */
    const val EYLEM_YETKISIZ_METNI = "Bu işlem için yetkiniz yok."

    /**
     * 403'ün türü — sözleşme AUTH §16 sırası: önce kurulum kapısı (yetki reddi DEĞİL), sonra iş kodu (`PASSWORD_CHANGE_REQUIRED`
     * · `E_CLINIC_CHOICE_REQUIRED` · `DEMO_CREDITS_EXHAUSTED` dahil — kendi yolları), en son kodsuz yetki reddi.
     */
    sealed class Ret {
        data class Kurulum(val yapabilir: Boolean) : Ret()
        data class IsKodu(val cumle: String?) : Ret()
        data object Yetki : Ret()
    }

    fun ret(data: ByteArray?): Ret {
        val o = data?.let { runCatching { DfyJson.parseToJsonElement(it.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() }
        val kod = o?.str("code")
        if (kod == com.dentifay.app.core.Kurulum.KOD) return Ret.Kurulum(o?.bool("kurulumYapabilir") ?: false)
        if (kod.isNullOrEmpty()) return Ret.Yetki
        val cumle = o?.str("message") ?: o?.str("error")
        return Ret.IsKodu(cumle?.takeIf { it.isNotEmpty() })
    }

    /**
     * 403 gövdesinin cümlesi. Kurulum kapısı bekleme ekranını açar (olay); iş kodlu 403 sunucunun cümlesi (M0-7); kodsuz yetki
     * reddi "Bu işlem için yetkiniz yok." — bir PARÇANIN verisinde ise o parça hiç çizilmez (`reddiMi`).
     */
    fun yasakMetni(data: ByteArray?): String = when (val r = ret(data)) {
        is Ret.Kurulum -> {
            com.dentifay.app.core.Kurulum.denetle(403, data)
            com.dentifay.app.core.Kurulum.metin(r.yapabilir)
        }
        is Ret.IsKodu -> r.cumle ?: EYLEM_YETKISIZ_METNI
        Ret.Yetki -> EYLEM_YETKISIZ_METNI
    }

    /**
     * Hata kodsuz bir yetki reddi (403) mi? Öyleyse veri parçası ÇİZİLMEZ, kırmızı "hata · tekrar dene" kartı yok (M-E7.5).
     * Kurulum kapısı ve iş kodlu 403 yetki reddi SAYILMAZ. Özellik paketlerinin kendi hata türleri `YetkiReddi`ni uygular
     * (Swift'te `reddiMi` her istemcinin hatasını tek tek sayıyordu).
     */
    fun reddiMi(hata: Throwable): Boolean = when (hata) {
        is ApiException.ServerError -> hata.code == 403 && hata.serverMessage == EYLEM_YETKISIZ_METNI
        is YetkiReddi -> hata.yetkiReddiMi
        else -> false
    }
}

/** Kodsuz 403 yetki reddini taşıyabilen hata türleri (AgentError, CreditCenterClient.ClientError, KieError, ClinicError …). */
interface YetkiReddi {
    val yetkiReddiMi: Boolean
}

/** Oturum yanıtının `kurulum` alanı (M-E5.4). */
@Serializable
data class KurulumDurumu(
    val tamamlandi: Boolean = true,
    /** Kişi kurulumu yapabilir mi (Sahip · Yönetici · Klinik Müdürü) — yalnız bekleme metnini seçer. */
    val yapabilir: Boolean? = null,
    val adimSayisi: Int? = null,
    val onayliAdim: Int? = null,
)

/**
 * Kurulumu bitmemiş klinik: kurulum bitene dek HİÇBİR kullanıcı ürünü kullanamaz; kurulum web'dedir. Kural sunucunun
 * yanıtından okunur (`kurulum.tamamlandi` ya da herhangi bir uçta 403 `E_CLINIC_SETUP_INCOMPLETE`).
 */
object Kurulum {
    const val KOD = "E_CLINIC_SETUP_INCOMPLETE"

    /** Herhangi bir uç 403 `E_CLINIC_SETUP_INCOMPLETE` dönünce yayınlanır (değer: `yapabilir`); `AuthManager` dinler. */
    private val _tamamlanmadi = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val tamamlanmadi: SharedFlow<Boolean> = _tamamlanmadi.asSharedFlow()

    /** Bekleme ekranı gerekiyor mu? Gerekiyorsa `yapabilir` (metni seçer), gerekmiyorsa ya da alan yoksa null. */
    fun bekleme(durum: KurulumDurumu?): Boolean? {
        if (durum == null || durum.tamamlandi) return null
        return durum.yapabilir ?: false
    }

    /** DentAIWeb Ek W.5 — "Mobil" (kurulumu yapabilen) ve "Bekleme sayfası" (yapamayan) metinleri. */
    fun metin(yapabilir: Boolean): String =
        if (yapabilir) "Kliniğinizin kurulumu tamamlanmadı. Kurulumu bilgisayarınızdan Dentifay'a girerek tamamlayın."
        else "Bu kliniğin kurulumu tamamlanmadığı için devam edemezsiniz. Kurulum, kliniğin Sahibi, Yöneticisi ya da Klinik Müdürü tarafından tamamlandığında uygulamayı kullanabilirsiniz."

    /** 403 gövdesi kurulum kapısı mı? Öyleyse olayı yayınlar ve true döner (ekran bekleme ekranına geçer; ham kod görünmez). */
    fun denetle(status: Int, json: JsonObject?): Boolean {
        if (status != 403 || (json?.str("code") ?: json?.str("error")) != KOD) return false
        _tamamlanmadi.tryEmit(json?.bool("kurulumYapabilir") ?: false)
        return true
    }

    fun denetle(status: Int, data: ByteArray?): Boolean {
        if (status != 403 || data == null) return false
        return denetle(status, runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull())
    }
}

/**
 * Şifre değişikliği zorunlu (AUTH §4): sunucu 403 `PASSWORD_CHANGE_REQUIRED` verince yayınlanır; `AuthManager` dinler ve kökte
 * "Yeni şifre belirleyin" ekranı açılır (M-E8.6).
 */
object SifreOlayi {
    const val KOD = "PASSWORD_CHANGE_REQUIRED"
    private val _gerekli = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val gerekli: SharedFlow<Unit> = _gerekli.asSharedFlow()

    fun bildir() {
        _gerekli.tryEmit(Unit)
    }
}

/** İstemci tarafı tek kural: iki alan aynı olmalı (uzunluk/ortak şifre kuralları sunucudan, cümlesi aynen gösterilir). */
object SifreKurali {
    const val UYUSMAZLIK = "Şifreler aynı değil."
    fun denetle(yeni: String, tekrar: String): String? = if (yeni == tekrar) null else UYUSMAZLIK
}

// MARK: - Şifre uçları (sözleşme AUTH §5/§10)

@Serializable
private data class SifreMesaji(val message: String? = null)

@Serializable
private data class SmsDurumu(val sms: Boolean? = null)

/** `PUT /api/auth/password` — geçici şifreyle açılmış oturumda `currentPassword` GÖNDERİLMEZ. Dönüş: sunucunun cümlesi. */
suspend fun ApiClient.sifreDegistir(yeni: String, mevcut: String?): String? =
    request<SifreMesaji>(method = "PUT", path = "/auth/password", body = govde("newPassword" to yeni, "currentPassword" to mevcut)).message

/** `GET /api/auth/forgot-password/durum` — SMS ile sıfırlama açık mı. */
suspend fun ApiClient.sifreSifirlamaAcik(): Boolean =
    runCatching { request<SmsDurumu>(path = "/auth/forgot-password/durum").sms == true }.getOrDefault(false)

/** `POST /api/auth/forgot-password` `{telefon}` — yanıt her zaman aynı cümle. */
suspend fun ApiClient.sifremiUnuttum(telefon: String): String? =
    request<SifreMesaji>(method = "POST", path = "/auth/forgot-password", body = govde("telefon" to telefon)).message
