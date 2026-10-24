package com.dentifay.app.hasta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.Yetki
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Hastanın sayfasının durumu (MOBIL_IS_EMRI M-E8.3 · M-E8.7): bölümler yalnız AÇILINCA yüklenir; plan ayrıntısı kart açılınca, tembel ve
 * önbellekli; sayfadan çıkınca uçuştaki istekler iptal edilir (`iptalEt`). Kodsuz 403 parçayı boş bırakır (M-E7.5).
 *
 * Swift `@Environment(HastaSayfasiModeli.self)` → `LocalHastaSayfasiModeli.current` (sayfa sağlar; sayfanın açtığı pencereler okur).
 * Alanlar Compose durumudur; yöntemler ana iş parçacığından çağrılır.
 */
@Stable
class HastaSayfasiModeli internal constructor(
    val hasta: HastaBasligi,
    private val kaynak: Kaynak,
    disKapsam: CoroutineScope?,
) {
    constructor(hasta: HastaBasligi) : this(hasta, Kaynak.Ag, null)

    enum class Bolum {
        planlama, yapilacak, yapilan, medya;

        val id: String get() = name
        val baslik: String
            get() = when (this) {
                planlama -> "Planlama"
                yapilacak -> "Yapılacak"
                yapilan -> "Yapılan"
                medya -> "Medya"
            }
        val asama: String? get() = when (this) { yapilacak -> "TODO"; yapilan -> "DONE"; else -> null }

        /** Bölümün görünme yeteneği (Planlama/Yapılacak/Yapılan `chart.view`; Medya `chart.docs.view`). */
        val yetenek: String get() = if (this == medya) "chart.docs.view" else "chart.view"
    }

    sealed class Durum<out T> {
        data object bos : Durum<Nothing>()
        data object yukleniyor : Durum<Nothing>()
        data class hazir<T>(val deger: T) : Durum<T>()
        data class hata(val mesaj: String) : Durum<Nothing>()

        val hazirMi: Boolean get() = this is hazir
    }

    /** Veri kaynağı — uygulamada `ApiClient`; önizleme ve testte örnek / sahte kaynak. */
    interface Kaynak {
        suspend fun planlar(hastaId: String): PlanListesiYaniti
        suspend fun ayrinti(planId: String): HastaPlani
        suspend fun asama(hastaId: String, asama: String): List<TedaviKalemi>
        suspend fun medya(hastaId: String, imlec: String?): MedyaYaniti

        object Ag : Kaynak {
            override suspend fun planlar(hastaId: String) = ApiClient.hastaPlanlari(hastaId)
            override suspend fun ayrinti(planId: String) = ApiClient.planAyrintisi(planId)
            override suspend fun asama(hastaId: String, asama: String) = ApiClient.asamaKalemleri(hastaId, asama)
            override suspend fun medya(hastaId: String, imlec: String?) = ApiClient.hastaMedyasi(hastaId, imlec = imlec)
        }
    }

    private val kapsam: CoroutineScope by lazy { disKapsam ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    var bolum by mutableStateOf(Bolum.planlama)

    var planlar by mutableStateOf<Durum<List<HastaPlani>>>(Durum.bos)
        private set
    var sonrakiPlanAdi by mutableStateOf<String?>(null)
        private set
    private val ayrintiDurumu = mutableStateMapOf<String, HastaPlani>()

    /** Planların en son okunan hâli (liste `detay=1` ya da `GET /:id`). */
    val ayrintilar: Map<String, HastaPlani> get() = ayrintiDurumu
    var ayrintiYukleniyor by mutableStateOf<Set<String>>(emptySet())
        private set

    /** `GET /:id` ile okunmuş planlar (onam alanları yalnız orada) — liste `detay=1` ile kalemleri zaten getirir. */
    private val tamAyrinti = HashSet<String>()
    var medyaDahaYukleniyor by mutableStateOf(false)
        private set
    var acikPlanlar by mutableStateOf<Set<String>>(emptySet())
    var asamalar by mutableStateOf<Map<String, Durum<List<TedaviKalemi>>>>(emptyMap())
        private set
    var medya by mutableStateOf<Durum<MedyaYaniti>>(Durum.bos)
        private set

    private val gorevler = HashMap<String, Job>()

    /** Bölüm açılınca (ya da yenile) yükle. */
    fun bolumuYukle(b: Bolum, zorla: Boolean = false) {
        when (b) {
            Bolum.planlama -> {
                if (planlar is Durum.hazir && !zorla) return
                baslat("planlar") { planlariYukle() }
            }
            Bolum.yapilacak, Bolum.yapilan -> {
                val asama = b.asama ?: "TODO"
                if (asamalar[asama] is Durum.hazir && !zorla) return
                baslat("asama-$asama") { asamaYukle(asama) }
            }
            Bolum.medya -> {
                if (medya is Durum.hazir && !zorla) return
                baslat("medya") { medyaYukle() }
            }
        }
    }

    /** Bir işlemden sonra: açık bölümler ve açık kartların ayrıntısı tazelenir (öteki bölümler bir sonraki açılışta). */
    suspend fun tazele() {
        ayrintiDurumu.clear()
        tamAyrinti.clear()
        planlar = Durum.bos
        asamalar = emptyMap()
        bolumuYukle(bolum, zorla = true)
        for (id in acikPlanlar) ayrintiyiYukle(id)
    }

    /** Aşağı çekip yenileme: `tazele` + görünen bölümün yüklenmesi bitene dek bekler (gösterge o sürece döner). */
    suspend fun tazeleVeBekle() {
        tazele()
        gorevler[gorevAnahtari(bolum)]?.join()
    }

    fun iptalEt() {
        gorevler.values.forEach { it.cancel() }
        gorevler.clear()
    }

    private fun gorevAnahtari(b: Bolum): String = when (b) {
        Bolum.planlama -> "planlar"
        Bolum.yapilacak, Bolum.yapilan -> "asama-${b.asama}"
        Bolum.medya -> "medya"
    }

    private fun baslat(anahtar: String, isi: suspend () -> Unit) {
        gorevler[anahtar]?.cancel()
        gorevler[anahtar] = kapsam.launch { isi() }
    }

    // MARK: Planlama

    private suspend fun planlariYukle() {
        if (planlar !is Durum.hazir) planlar = Durum.yukleniyor
        try {
            val y = kaynak.planlar(hasta.id)
            currentCoroutineContext().ensureActive()
            val canli = y.plans.filter { it.deletedAt == null }.distinctBy { it.id }
            for (p in canli) if (ayrintiDurumu[p.id] == null) ayrintiDurumu[p.id] = p
            planlar = Durum.hazir(canli)
            sonrakiPlanAdi = y.sonrakiPlanAdi
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            planlar = if (Yetki.reddiMi(e)) Durum.hazir(emptyList()) else Durum.hata(hataMetni(e))
        }
    }

    fun kartiDegistir(planId: String) {
        if (planId in acikPlanlar) {
            acikPlanlar = acikPlanlar - planId
        } else {
            acikPlanlar = acikPlanlar + planId
            ayrintiyiYukle(planId)
        }
    }

    fun ayrintiyiYukle(planId: String) {
        if (planId in tamAyrinti || planId in ayrintiYukleniyor) return
        ayrintiYukleniyor = ayrintiYukleniyor + planId
        baslat("plan-$planId") {
            try {
                val p = try {
                    kaynak.ayrinti(planId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (p != null) {
                    ayrintiDurumu[planId] = p
                    tamAyrinti += planId
                }
            } finally {
                ayrintiYukleniyor = ayrintiYukleniyor - planId
            }
        }
    }

    // MARK: Yapılacak / Yapılan

    private suspend fun asamaYukle(asama: String) {
        if (asamalar[asama] == null) asamalar = asamalar + (asama to Durum.yukleniyor)
        try {
            val k = kaynak.asama(hasta.id, asama)
            currentCoroutineContext().ensureActive()
            asamalar = asamalar + (asama to Durum.hazir(k))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            asamalar = asamalar + (asama to if (Yetki.reddiMi(e)) Durum.hazir(emptyList()) else Durum.hata(hataMetni(e)))
        }
    }

    fun asamaDurumu(asama: String): Durum<List<TedaviKalemi>> = asamalar[asama] ?: Durum.bos

    // MARK: Medya

    private suspend fun medyaYukle() {
        if (medya !is Durum.hazir) medya = Durum.yukleniyor
        try {
            val y = kaynak.medya(hasta.id, null)
            currentCoroutineContext().ensureActive()
            medya = Durum.hazir(y.copy(media = y.media.distinctBy { it.id }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            medya = if (Yetki.reddiMi(e)) Durum.hazir(MedyaYaniti(media = emptyList())) else Durum.hata(hataMetni(e))
        }
    }

    /** Sonsuz kaydırma: son görsel görününce sonraki sayfa (`nextCursor` yoksa son sayfa). */
    fun medyaDevam() {
        val m = medya
        val y = if (m is Durum.hazir) m.deger else return
        val imlec = y.pagination?.nextCursor ?: return
        if (medyaDahaYukleniyor) return
        medyaDahaYukleniyor = true
        baslat("medya-devam") {
            try {
                val yeni = try {
                    kaynak.medya(hasta.id, imlec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                val simdiki = medya
                if (yeni != null && simdiki is Durum.hazir) medya = Durum.hazir(medyaBirlestir(simdiki.deger, yeni))
            } finally {
                medyaDahaYukleniyor = false
            }
        }
    }

    /** Önizleme ve testler için: bölümleri ağ olmadan doldurur. */
    internal fun ornekVeriYukle(
        planlar: List<HastaPlani>? = null,
        sonrakiPlanAdi: String? = null,
        ayrintilar: Map<String, HastaPlani> = emptyMap(),
        asamalar: Map<String, List<TedaviKalemi>> = emptyMap(),
        medya: MedyaYaniti? = null,
        acik: Set<String> = emptySet(),
        bolum: Bolum = Bolum.planlama,
    ) {
        planlar?.let { this.planlar = Durum.hazir(it) }
        this.sonrakiPlanAdi = sonrakiPlanAdi
        ayrintiDurumu.putAll(ayrintilar)
        tamAyrinti += ayrintilar.keys
        this.asamalar = asamalar.mapValues { Durum.hazir(it.value) }
        medya?.let { this.medya = Durum.hazir(it) }
        acikPlanlar = acik
        this.bolum = bolum
    }

    companion object {
        /** Sonraki sayfayı ekler (görülen kimlikler atlanır); imleç yeni sayfanınki, sayılar ve yetki ilk sayfanınki. */
        fun medyaBirlestir(eski: MedyaYaniti, yeni: MedyaYaniti): MedyaYaniti {
            val gorulen = eski.media.mapTo(HashSet()) { it.id }
            return eski.copy(media = eski.media + yeni.media.filter { gorulen.add(it.id) }, pagination = yeni.pagination)
        }

        internal fun hataMetni(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: "İşlem tamamlanamadı."
    }
}

/** Swift `@Environment(HastaSayfasiModeli.self)` — hastanın sayfası ve açtığı pencereler (plan penceresi, işlemler, yükleme…) okur. */
val LocalHastaSayfasiModeli = staticCompositionLocalOf<HastaSayfasiModeli> {
    error("HastaSayfasiModeli sağlanmadı — HastaSayfasiView ya da HastaSayfasiOrtami içinde kullanın")
}

/** Modeli alt ağaca sağlar (sayfanın dışında — ör. önizlemede — pencere çizmek için). */
@Composable
fun HastaSayfasiOrtami(model: HastaSayfasiModeli, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHastaSayfasiModeli provides model, content = content)
}
