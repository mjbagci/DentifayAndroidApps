package com.dentifay.app.credit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.dentifay.app.core.AppConfig
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * Kredi ipucu ve "Kredi ile çalışan işlemler" listesinin TEK kaynağı — `GET /api/ai-credits/tarife`
 * (MOBIL_IS_EMRI M-E1.7.8; web `components/kredi/KrediIpucu#ipucuMetni` ve `features/krediler/kredi-metni#tarifeMetni`).
 * Ön yüzde kredi sayısı SABİTİ YOK; uç düşerse ipucu çizilmez (işlem engellenmez).
 */
@Serializable
data class KrediTarifesi(
    val tarife: List<Satir>,
    val durum: Map<String, Durum>,
) {
    @Serializable
    data class Satir(
        val feature: String,
        val action: String,
        val enAz: Double,
        val enCok: Double,
        val kullanimaBagli: Boolean,
    ) {
        val id: String get() = "$feature:$action"
    }

    @Serializable
    data class Durum(
        /** `UCRETLI` · `BASLAYACAK` · `UCRETSIZ` */
        val durum: String,
        val tarih: String? = null,
    )

    fun satir(anahtar: String): Satir? = tarife.firstOrNull { it.id == anahtar }

    /** İpucu metni (Ek MO.27b–d; M-E4.2: her açıklama "ortalama" der); `UCRETSIZ`de ya da bilinmeyen işlemde null (ipucu çizilmez). */
    fun ipucu(anahtar: String): String? {
        val s = satir(anahtar) ?: return null
        return when (durum[s.feature]?.durum ?: "UCRETLI") {
            "UCRETSIZ" -> null
            "BASLAYACAK" -> "${tarih(durum[s.feature]?.tarih)} tarihinden itibaren kredi kullanacak; şu an ücretsiz."
            else -> if (s.kullanimaBagli) {
                "Bu işlem ortalama ${creditText(s.enAz)}–${creditText(s.enCok)} kredi kullanır. Kullanılan kredi işlemin kapsamına göre hesaplanır; uzun ya da kapsamlı isteklerde daha fazla olabilir."
            } else {
                "Bu işlem ortalama ${creditText(s.enAz)} kredi kullanır."
            }
        }
    }

    /** Gülüş kademesi (M-E3.1): sayı `tiers`tan (kartla AYNI); tarife yalnız durumu (ücretsiz / başlayacak) söyler. */
    fun ipucu(anahtar: String, kredi: Double?): String? {
        val feature = anahtar.split(":").firstOrNull { it.isNotEmpty() } ?: anahtar
        return when (durum[feature]?.durum ?: "UCRETLI") {
            "UCRETSIZ" -> null
            "BASLAYACAK" -> "${tarih(durum[feature]?.tarih)} tarihinden itibaren kredi kullanacak; şu an ücretsiz."
            else -> GulusBedeli.ipucu(kredi)
        }
    }

    /** Liste satırının kredi sütunu (Ek MO.27e). */
    fun listeMetni(s: Satir): String = when (durum[s.feature]?.durum ?: "UCRETLI") {
        "UCRETSIZ" -> "Şu an ücretsiz"
        "BASLAYACAK" -> "${tarih(durum[s.feature]?.tarih)} tarihinden itibaren kredi kullanacak; şu an ücretsiz."
        // Sözleşme §10 (Ekleme 20 · 39B) metni AYNEN.
        else -> if (s.kullanimaBagli) "Ortalama ${creditText(s.enAz)}–${creditText(s.enCok)} kredi; kullanıma göre değişir"
        else "Ortalama ${creditText(s.enAz)} kredi"
    }

    companion object {
        /** `YYYY-MM-DD` → "21.10.2026". */
        fun tarih(ham: String?): String {
            if (ham == null) return ""
            val p = ham.take(10).split("-").filter { it.isNotEmpty() }
            return if (p.size == 3) "${p[2]}.${p[1]}.${p[0]}" else ham
        }
    }
}

/** Oturum başına BİR kez okunur; Kredi Yönetimi açılınca yenilenir. */
class KrediTarifesiStore private constructor() {
    companion object {
        val shared = KrediTarifesiStore()
    }

    var tarife by mutableStateOf<KrediTarifesi?>(null)
        private set
    var hata by mutableStateOf(false)
        private set
    private var yuklendi = false
    private var yukleniyor = false
    private var ornekVeri = false

    suspend fun yukle(zorla: Boolean = false) {
        if (ornekVeri) return
        if (yuklendi && !zorla) return
        // Aynı anda birden çok ipucu ekranda olabilir: uçuştaki okuma bitince hepsi aynı veriyi görür.
        if (yukleniyor) return
        yukleniyor = true
        try {
            tarife = CreditCenterClient(AppConfig.apiBaseURL).tarife()
            hata = false
            yuklendi = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hata = true
        } finally {
            yukleniyor = false
        }
    }

    fun ipucu(anahtar: String): String? = tarife?.ipucu(anahtar)

    /** Sayısı ekranda zaten gösterilen işlem (gülüş kademesi): tarife okunamadıysa da sayı söylenir. */
    fun ipucu(anahtar: String, kredi: Double?): String? {
        val t = tarife
        return if (t != null) t.ipucu(anahtar, kredi) else GulusBedeli.ipucu(kredi)
    }

    /** Yalnız @Preview: örnek tarifeyle doldurur (ağ çağrısı yapılmaz). */
    internal fun ornekYukle(t: KrediTarifesi) {
        ornekVeri = true
        tarife = t
        hata = false
        yuklendi = true
    }
}

/**
 * Kredi düşen düğmenin yanındaki küçük bilgi işareti (metal ton; erişilebilir ad Ek MO.27a).
 * Dokununca işlemin kaç kredi kullandığını söyler; tarifede yoksa / ücretsizse hiç çizilmez.
 *
 * `kredi`: ekrandaki sayı (gülüş kademesi — `tiers`); verilirse ipucu AYNI sayıyı söyler (M-E3.1). Swift'teki `Double??`
 * yerine null = "verilmedi" (çağıranlar yalnız bilinen sayıyı verir).
 */
@Composable
fun KrediIpucu(islem: String, kredi: Double? = null, modifier: Modifier = Modifier) {
    val store = KrediTarifesiStore.shared
    // Oturum başına bir kez (M-E1.7.8): ipucu, Kredi Yönetimi hiç açılmamış olsa da tarifeyi bulur; okunmuşsa ağ çağrısı yok.
    LaunchedEffect(Unit) { store.yukle() }
    val metin = if (kredi != null) store.ipucu(islem, kredi) else store.ipucu(islem)
    if (metin == null) return

    var acik by remember { mutableStateOf(false) }
    val boslukPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val kenarPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    Box(
        modifier
            .size(28.dp)
            .semantics {
                contentDescription = "Kredi bilgisi"
                stateDescription = metin
                role = Role.Button
            }
            .plainClickable { acik = true },
        contentAlignment = Alignment.Center,
    ) {
        DfyIcon("info.circle", size = 13.dp, tint = DS.Colors.metal)
        if (acik) {
            // iOS `.popover` — işaretin üstünde (yer yoksa altında), pencere kenarına taşmadan.
            Popup(
                popupPositionProvider = remember(boslukPx, kenarPx) { IpucuKonumu(boslukPx, kenarPx) },
                onDismissRequest = { acik = false },
                properties = PopupProperties(focusable = true),
            ) {
                val sekil = RoundedCornerShape(12.dp)
                Text(
                    metin,
                    Modifier
                        .widthIn(max = 280.dp)
                        .shadow(10.dp, sekil)
                        .clip(sekil)
                        .background(DS.Colors.surfaceContainerHighest)
                        .border(1.dp, DS.Colors.hairlineStrong, sekil)
                        .padding(14.dp),
                    style = sys(13), color = DS.Colors.onSurface,
                )
            }
        }
    }
}

/** Ipucu balonunun konumu: işaretin ortasına hizalı, üstte; yer yoksa altta; yatayda pencereye sığdırılır. */
private class IpucuKonumu(private val bosluk: Int, private val kenar: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val enFazlaX = max(kenar, windowSize.width - popupContentSize.width - kenar)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(kenar, enFazlaX)
        val ust = anchorBounds.top - popupContentSize.height - bosluk
        val y = if (ust >= kenar) ust else anchorBounds.bottom + bosluk
        return IntOffset(x, y)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun KrediIpucuPreview() {
    PreviewEnv {
        remember { KrediTarifesiStore.shared.ornekYukle(CreditPreviewData.tarife); 0 }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Röntgen analizi", style = sys(14), color = DS.Colors.onSurface)
                KrediIpucu(islem = "xray:analyze")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("İyi kalite · ${GulusBedeli.kart(12.0)}", style = sys(14), color = DS.Colors.onSurface)
                KrediIpucu(islem = "before_after:iyi", kredi = 12.0)
            }
        }
    }
}
