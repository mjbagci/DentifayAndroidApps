package com.dentifay.app.smile

import com.dentifay.app.BuildConfig
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.Prefs
import java.util.Properties

/**
 * Gülüş simülasyonu yapılandırması (MOBIL_IS_EMRI M-E1.7.1).
 *
 * - **Yayın (release / qa):** istekler DAİMA Dentifay sunucusundan geçer (`{API}/ai/smile/...`); anahtar okunmaz,
 *   kredi düşülür, yetki ve saatlik sınır sunucuda denetlenir.
 * - **Geliştirme (debug):** geliştiricinin yerel anahtarı (`assets/Secrets.properties` → `KieAPIKey=…`, dosya
 *   .gitignore'da; ya da `KIE_API_KEY` ortam değişkeni) varsa doğrudan sağlayıcıya gidilir — yalnız geliştirme içindir.
 */
object SmileSimulationConfig {
    val kieAPIKey: String
        get() {
            // TEK kapı: yayın derlemesinde anahtar hiç okunmaz → yol DAİMA `proxy`.
            if (!BuildConfig.DEBUG) return ""
            System.getenv("KIE_API_KEY")?.takeIf { it.isNotEmpty() }?.let { return it }
            val ctx = Prefs.appContext ?: return ""
            return runCatching {
                ctx.assets.open("Secrets.properties").use { stream ->
                    Properties().apply { load(stream) }.getProperty("KieAPIKey")?.trim().orEmpty()
                }
            }.getOrDefault("")
        }

    /**
     * `kieAPIKey`in `BuildConfig.DEBUG` kapısı tektir: yayında anahtar okunmadan `""` döner ve yol DAİMA `proxy` olur.
     * (Burada ikinci bir kapı YOK — korunan kod yayında ölü kalmasın.)
     */
    val endpoint: KieImageEditClient.Endpoint
        get() {
            val key = kieAPIKey
            if (key.isNotEmpty()) return KieImageEditClient.Endpoint.direct(apiKey = key)
            return KieImageEditClient.Endpoint.proxy(baseURL = AppConfig.apiBaseURL + "/ai/smile")
        }
}
