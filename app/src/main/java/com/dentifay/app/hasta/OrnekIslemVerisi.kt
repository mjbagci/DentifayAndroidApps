package com.dentifay.app.hasta

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dentifay.app.core.DfyJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * İşlem pencerelerinin önizleme verisi (kurmaca — gerçek kişi/tutar YOK, M0-8). Hasta ve planlar görüntüleme tarafının
 * `OrnekHastaSayfasi`ndan; ödeme planı ve künye Swift `OrnekHastaSayfasi` uzantılarının karşılığı.
 */
internal object OrnekIslemVerisi {
    val plan: HastaPlani get() = OrnekHastaSayfasi.ayrinti("p1")
    val kalemler: List<TedaviKalemi> get() = OrnekHastaSayfasi.kalemler1

    /** Swift `OrnekHastaSayfasi.odemePlanlari`. */
    val odemePlanlari: List<KayitliOdemePlani> by lazy {
        val json = """[{"id":"op1","totalAmount":"12000.00","notes":"Tedavi Planı: Plan 1 · 3 taksit","status":"ACTIVE","createdAt":"2026-10-12T09:00:00.000Z","teklifBagi":false,"installments":[{"id":"t1","amount":"4000.00","dueDate":"2026-11-12T00:00:00.000Z","status":"PAID","manualPaidAt":null,"allocatedAmount":"4000.00","remainingAmount":"0"},{"id":"t2","amount":"4000.00","dueDate":"2026-12-12T00:00:00.000Z","status":"PENDING","manualPaidAt":null,"allocatedAmount":"0","remainingAmount":"4000.00"},{"id":"t3","amount":"4000.00","dueDate":"2027-01-12T00:00:00.000Z","status":"PENDING","manualPaidAt":null,"allocatedAmount":"0","remainingAmount":"4000.00"}]}]"""
        runCatching { DfyJson.decodeFromString(ListSerializer(KayitliOdemePlani.serializer()), json) }.getOrDefault(emptyList())
    }

    /** Swift `OrnekHastaSayfasi.kunye` (kurmaca kurum künyesi — baskı önizlemesi). */
    val kunye = PlanCiktisi.Kunye(
        letterhead = PlanCiktisi.Kunye.Antet(
            name = "DENEME KLİNİK", address = "Örnek Mah. Deneme Sok. No:1, Kadıköy / İstanbul", phone = "+905550000000",
            email = "bilgi@ornek.test",
        ),
        odemeBilgisi = PlanCiktisi.Kunye.OdemeBilgisi(iban = "TR609999900000000000001234", aliciAdi = "Deneme Klinik Ltd.", bankaAdi = "Örnek Banka"),
    )
}

/** Önizlemede pencerelere örnek hasta sayfası modelini sağlar (ağ yok). */
@Composable
internal fun OrnekModelSaglayici(content: @Composable () -> Unit) {
    val model = remember { OrnekHastaSayfasi.model() }
    HastaSayfasiOrtami(model, content)
}
