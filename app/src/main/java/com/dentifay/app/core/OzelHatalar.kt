package com.dentifay.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Hakediş etkisi onayı (DentAIWeb 52D; sözleşme AGENT §14; M-E8.10). Yapılan kalemi değiştiren yazım hekim hakedişini
 * değiştiriyorsa sunucu 409 `E_HAKEDIS_ETKI_ONAYI_GEREKLI` / `E_HAKEDIS_ETKI_DEGISTI` döner; pencere sunucunun cümleleriyle sorar,
 * onayda AYNI istek gövdeye `hakedisEtkisi: <ozet>` eklenerek yeniden gönderilir. Satır ve uyarı cümlelerini mobil KURMAZ.
 */
@Serializable
data class HakedisEtkisi(
    val etkiVar: Boolean = false,
    val satirlar: List<Satir>? = null,
    val ozet: String? = null,
    val uyari: String? = null,
    val tutarGorunur: Boolean? = null,
) {
    @Serializable
    data class Satir(
        val hekimId: String? = null,
        val ay: String? = null,
        val fark: EsnekSayi? = null,
        val metin: String = "",
        val ekMetin: String? = null,
    ) {
        val id: String get() = "${hekimId ?: ""}·${ay ?: ""}·$metin"
    }

    companion object {
        const val ONAY_KODU = "E_HAKEDIS_ETKI_ONAYI_GEREKLI"
        const val DEGISTI_KODU = "E_HAKEDIS_ETKI_DEGISTI"
        const val DEGISTI_METNI = "Hakediş etkisi siz onaylarken değişti; lütfen yeniden kontrol edin."

        /** 409 gövdesinden (yalnız iki kod ve `etkiVar`). */
        fun ayristir(status: Int, data: ByteArray): HakedisOnayiGerekli? {
            if (status != 409) return null
            val o = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
            val kod = o.str("code")
            if (kod != ONAY_KODU && kod != DEGISTI_KODU) return null
            val e = runCatching { DfyJson.decodeFromJsonElement(serializer(), o) }.getOrNull() ?: return null
            if (!e.etkiVar) return null
            return HakedisOnayiGerekli(e, degisti = kod == DEGISTI_KODU, cumle = o.str("error"))
        }

        /** Korumalı gövdeye onay özetini ekler (özet yoksa gövde aynen). Swift `@TaskLocal onayOzeti` → eşyordam bağlamı. */
        suspend fun govdeye(g: JsonObject?): JsonObject? {
            val ozet = coroutineContext[OnayOzeti]?.ozet ?: return g
            return JsonObject((g ?: JsonObject(emptyMap())) + ("hakedisEtkisi" to JsonPrimitive(ozet)))
        }
    }

    /** Onaylanan özet — korumalı istek yöntemleri gövdeye `hakedisEtkisi` olarak ekler (`HakedisOnayi.korumali` atar). */
    class OnayOzeti(val ozet: String?) : AbstractCoroutineContextElement(OnayOzeti) {
        companion object Key : CoroutineContext.Key<OnayOzeti>
    }
}

class HakedisOnayiGerekli(val etki: HakedisEtkisi, val degisti: Boolean, val cumle: String?) :
    Exception(cumle ?: "Bu değişiklik hakedişi etkiliyor; önce onaylayın.")

/** Kullanıcı "İptal" dedi — hiçbir şey yazılmadı; çağıran HATA GÖSTERMEZ (web `HakedisIptal`). */
class HakedisIptal : Exception("İptal edildi")

/** Bekleyen onay — korumalı işlemi başlatan ekran `HakedisEtkisiPenceresi` ile gösterir (Swift `@Observable HakedisOnayi`). */
class HakedisOnayi private constructor() {
    data class Bekleyen(
        val etki: HakedisEtkisi,
        val degisti: Boolean,
        /** "Planı düzenle"nin Yapılan/Yapılacak değişen kalem listesi (aynı pencerede; iki onay sorulmaz). */
        val ek: List<String>? = null,
    )

    var bekleyen by mutableStateOf<Bekleyen?>(null)
        private set
    private var devam: CompletableDeferred<Boolean>? = null

    suspend fun sor(b: Bekleyen): Boolean {
        devam?.complete(false)
        val d = CompletableDeferred<Boolean>()
        devam = d
        bekleyen = b
        return d.await()
    }

    fun yanitla(onay: Boolean) {
        bekleyen = null
        devam?.complete(onay)
        devam = null
    }

    companion object {
        val shared = HakedisOnayi()

        /**
         * Gönder → 409 → pencere → özetle yeniden gönder (en çok 4 deneme, web `hakedisKorumali`). `onizleme` verilirse önce o
         * sorulur ("Planı düzenle" — web tek bu yerde önizleme ucunu çağırır).
         */
        suspend fun <T> korumali(ek: List<String>? = null, onizleme: HakedisEtkisi? = null, yap: suspend () -> T): T {
            var ozet: String? = null
            if (onizleme != null && onizleme.etkiVar) {
                if (!shared.sor(Bekleyen(onizleme, false, ek))) throw HakedisIptal()
                ozet = onizleme.ozet
            }
            var deneme = 0
            while (true) {
                try {
                    return withContext(HakedisEtkisi.OnayOzeti(ozet)) { yap() }
                } catch (g: HakedisOnayiGerekli) {
                    deneme += 1
                    if (deneme >= 4) throw g
                    if (!shared.sor(Bekleyen(g.etki, g.degisti, ek))) throw HakedisIptal()
                    ozet = g.etki.ozet
                }
            }
        }
    }
}

/** Kara listedeki hasta (409/403 `PATIENT_BLOCKED`) — yeni randevu gerekçe ister (`gecilebilir`). */
class KaraListeEngeli(val cumle: String, val gecilebilir: Boolean) : Exception(cumle) {
    companion object {
        fun ayristir(status: Int, data: ByteArray): KaraListeEngeli? {
            if (status != 409 && status != 403) return null
            val o = runCatching { DfyJson.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull() ?: return null
            if (o.str("code") != "PATIENT_BLOCKED") return null
            return KaraListeEngeli(o.str("error") ?: "", o.bool("canOverride") == true)
        }

        fun istemMetni(cumle: String): String =
            "$cumle\n\nYine de randevu açmak için GEREKÇE girin.\nBu işlem, adınızla birlikte denetim kaydına yazılacaktır."
    }
}
