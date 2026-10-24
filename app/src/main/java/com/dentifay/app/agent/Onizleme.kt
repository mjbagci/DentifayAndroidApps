@file:Suppress("ClassName")

package com.dentifay.app.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv

// MARK: - Önizleme: konum, hekim, düzeltme mesajı (MOBIL_IS_EMRI M-E1.3)
//
// Mobil konum metni, tutar ya da hekim kümesi ÜRETMEZ: etiketler ve sayılar sunucudan
// (`write_preview.steps[].fields.<kolon>.label`, `tutarTL`, `toplamTL`), hekim listeleri sunucunun uçlarından.
// FDI · TL · GrupTutari · TakvimHekimleri · AtanabilirHekimler · HekimSecici `core/Hekimler.kt`tedir.

/** Kalemin konumu (26C: diş(ler) · çene · yarım çene 1–4 · konumsuz). "Üst ve alt çene" seçeneği YOK — her çene ayrı kalemdir. */
@Immutable
sealed class KonumSecimi {
    data class dis(val disler: List<String>) : KonumSecimi()
    data object ustCene : KonumSecimi()
    data object altCene : KonumSecimi()
    data class bolge(val n: Int) : KonumSecimi()
    data object konumsuz : KonumSecimi()

    companion object {
        /** Ek MO.8 — yarım çene adları. */
        fun bolgeAdi(n: Int): String = when (n) {
            1 -> "Sağ üst"
            2 -> "Sol üst"
            3 -> "Sol alt"
            else -> "Sağ alt"
        }
    }
}

// MARK: - AgentWriteStep uzantıları (Swift `extension AgentWriteStep`)

/** Sunucunun konum etiketi: `fields`te dolu olan konum alanının metni (kalem tek konum türü taşır). */
val AgentWriteStep.konumMetni: String?
    get() {
        for (k in listOf("toothCodes", "tooth", "jawType", "quadrant")) {
            val t = fields[k]?.text?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        if (toothCodes.isNotEmpty()) return toothCodes.joinToString(", ")
        return null
    }

/** Kalemin hekimi (`assignedDoctorId`'nin sunucu etiketi); yoksa null (ekran Ek MO.7e yazar). */
val AgentWriteStep.hekimMetni: String?
    get() = Unvan.hekimAdi(fields["assignedDoctorId"]?.text).takeIf { it.isNotEmpty() }

/** Planın hekimi (plan adımı, `doctorUserId`). */
val AgentWriteStep.planHekimiMetni: String?
    get() = Unvan.hekimAdi(fields["doctorUserId"]?.text).takeIf { it.isNotEmpty() }

/** Fiyat etiketi — sunucunun `fmtKalemFiyati` biçimi ("1.800,00 TL", dövizde kendi birimiyle). Mobil hesaplamaz. */
val AgentWriteStep.fiyatEtiketi: String?
    get() = fields["price"]?.label?.trim()?.takeIf { it.isNotEmpty() }

/** Tedavi adı (katalog etiketi öncelikli). */
val AgentWriteStep.tedaviAdi: String
    get() = catalogLabel ?: name ?: title

/** Önizleme düzeltme mesajları — Ek MO.9 (modele giden Türkçe sabit kalıplar). Kalemi sunucunun `steps[].index`i anar. */
object DuzeltmeMesaji {
    /** Ek MO.9a — "Önizlemedeki {n}. adım («{ad}»{, konum})" */
    fun tanim(adim: AgentWriteStep): String {
        val konum = adim.konumMetni?.let { ", $it" } ?: ""
        return "Önizlemedeki ${adim.index + 1}. adım («${adim.tedaviAdi}»$konum)"
    }

    fun konum(adim: AgentWriteStep, secim: KonumSecimi): String {
        val t = tanim(adim)
        return when (secim) {
            is KonumSecimi.dis -> "$t: dişi ${secim.disler.joinToString(", ")} yap; diğer adımları aynen koru."
            KonumSecimi.ustCene -> "$t: konumu üst çene yap; diğer adımları aynen koru."
            KonumSecimi.altCene -> "$t: konumu alt çene yap; diğer adımları aynen koru."
            is KonumSecimi.bolge -> "$t: konumu ${secim.n}. bölge (${KonumSecimi.bolgeAdi(secim.n)}) yap; diğer adımları aynen koru."
            KonumSecimi.konumsuz -> "$t: konumu kaldır; diğer adımları aynen koru."
        }
    }

    /** Ek MO.9f */
    fun hekim(adim: AgentWriteStep, hekim: String): String = "${tanim(adim)}: hekimi $hekim yap; diğer adımları aynen koru."

    /** Ek MO.9g — kalemler hakkında talimat TAŞIMAZ (hangi kalemin değişeceğine sunucu karar verir). */
    fun planHekimi(hekim: String): String = "Planın hekimini $hekim yap."

    /** Ek MO.9h */
    fun tedavi(adim: AgentWriteStep, ad: String): String = "${tanim(adim)}: tedaviyi «$ad» yap; diğer adımları aynen koru."

    /** Ek MO.9i */
    fun sil(adim: AgentWriteStep): String = "${tanim(adim)}: listeden çıkar; diğer adımları aynen koru."

    /** Ek MO.9j */
    fun ekle(ad: String, konum: KonumSecimi?): String {
        val k = when (konum) {
            is KonumSecimi.dis -> ": konumu diş ${konum.disler.joinToString(", ")}"
            KonumSecimi.ustCene -> ": konumu üst çene"
            KonumSecimi.altCene -> ": konumu alt çene"
            is KonumSecimi.bolge -> ": konumu ${konum.n}. bölge (${KonumSecimi.bolgeAdi(konum.n)})"
            KonumSecimi.konumsuz, null -> ""
        }
        return "Plana «$ad» kalemini ekle$k; mevcut adımları aynen koru."
    }

    /** Hekim seçimi aynı istekte `pick{table:"users"}` taşır (26E.6). */
    fun hekimPick(userId: String, ad: String): AgentClient.PickPayload =
        AgentClient.PickPayload(table = "users", term = ad, id = userId, name = ad)
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 300)
@Composable
private fun OnizlemePreview() {
    val adim = AgentWriteStep(
        index = 1, entity = "treatment_items", operation = "insert", title = "Kompozit Dolgu", linkedToStep = 0, affectedCount = 1,
        fields = mapOf(
            "tooth" to AgentFieldValue(JSONValue.string("36"), "36"),
            "catalogServiceId" to AgentFieldValue(JSONValue.string("s1"), "Kompozit Dolgu"),
            "price" to AgentFieldValue(JSONValue.string("1800"), "1.800,00 TL"),
            "assignedDoctorId" to AgentFieldValue(JSONValue.string("d1"), "Deneme A"),
        ),
        tutarTL = 1800.0, toothCodes = listOf("36"),
    )
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${adim.tedaviAdi} · ${adim.konumMetni} · ${adim.fiyatEtiketi}", style = sys(15), color = DS.Colors.onSurface)
            Text(adim.hekimMetni ?: "Hekim atanmadı", style = sys(13), color = DS.Colors.cyan400)
            Text(DuzeltmeMesaji.konum(adim, KonumSecimi.bolge(2)), style = sys(13), color = DS.Colors.onSurfaceVariant)
        }
    }
}
