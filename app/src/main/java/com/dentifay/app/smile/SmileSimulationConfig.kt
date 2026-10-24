package com.dentifay.app.smile

import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.Prefs
import java.util.Properties

/**
 * Gülüş simülasyonu yapılandırması.
 *
 * İstekler iki yoldan birine gider:
 * - **Geliştirme:** `assets/Secrets.properties` → `KieAPIKey=…` (dosya .gitignore'da;
 *   iOS'taki Secrets.plist karşılığı) ya da `KIE_API_KEY` ortam değişkeni varsa doğrudan kie.ai.
 * - **Canlı:** anahtar yoksa Dentifay sunucusu (`{API}/ai/smile/...`). Anahtar
 *   yalnızca sunucuda durur (`KIE_API_KEY`), istek oturum çereziyle yetkilendirilir.
 */
object SmileSimulationConfig {
    val kieAPIKey: String
        get() {
            System.getenv("KIE_API_KEY")?.takeIf { it.isNotEmpty() }?.let { return it }
            val ctx = Prefs.appContext ?: return ""
            return runCatching {
                ctx.assets.open("Secrets.properties").use { stream ->
                    Properties().apply { load(stream) }.getProperty("KieAPIKey")?.trim().orEmpty()
                }
            }.getOrDefault("")
        }

    /** Cihazda anahtar yoksa istekler Dentifay sunucusundan geçer. */
    val endpoint: KieImageEditClient.Endpoint
        get() {
            val key = kieAPIKey
            if (key.isNotEmpty()) return KieImageEditClient.Endpoint.direct(apiKey = key)
            return KieImageEditClient.Endpoint.proxy(baseURL = AppConfig.apiBaseURL + "/ai/smile")
        }
}
