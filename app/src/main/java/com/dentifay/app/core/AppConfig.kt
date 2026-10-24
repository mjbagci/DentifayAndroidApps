package com.dentifay.app.core

import android.content.Context
import android.content.SharedPreferences
import com.dentifay.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** `UserDefaults.standard` karşılığı. `DentifayApplication` başlatır. */
object Prefs {
    private var sp: SharedPreferences? = null
    /** Önizleme (@Preview) ve testlerde `init` çağrılmaz → bellek içi yedek kullanılır. */
    private val memory = mutableMapOf<String, Any?>()
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        sp = context.getSharedPreferences("dentifay", Context.MODE_PRIVATE)
    }

    fun contains(key: String): Boolean = sp?.contains(key) ?: memory.containsKey(key)
    fun string(key: String): String? = sp?.getString(key, null) ?: memory[key] as? String
    fun bool(key: String): Boolean = sp?.getBoolean(key, false) ?: (memory[key] as? Boolean ?: false)
    fun int(key: String, def: Int = 0): Int = sp?.getInt(key, def) ?: (memory[key] as? Int ?: def)
    fun long(key: String, def: Long = 0): Long = sp?.getLong(key, def) ?: (memory[key] as? Long ?: def)
    fun double(key: String): Double? = if (contains(key)) java.lang.Double.longBitsToDouble(long(key)) else null
    fun set(key: String, value: String?) { if (value == null) remove(key) else sp?.edit()?.putString(key, value)?.apply() ?: memory.put(key, value) }
    fun set(key: String, value: Boolean) { sp?.edit()?.putBoolean(key, value)?.apply() ?: memory.put(key, value) }
    fun set(key: String, value: Int) { sp?.edit()?.putInt(key, value)?.apply() ?: memory.put(key, value) }
    fun set(key: String, value: Long) { sp?.edit()?.putLong(key, value)?.apply() ?: memory.put(key, value) }
    fun set(key: String, value: Double) = set(key, java.lang.Double.doubleToRawLongBits(value))
    fun remove(key: String) { sp?.edit()?.remove(key)?.apply() ?: memory.remove(key) }
}

/**
 * Backend kök adresinin TEK kaynağı. ApiClient ve AgentClient bunu kullanır.
 * Açılışta `resolveBaseURL()` adaylardan ulaşılabilir olanı seçer ve hatırlar.
 */
object AppConfig {
    /** Emülatörde bilgisayarın localhost'u 10.0.2.2'dir (iOS simülatöründeki localhost yedeğinin karşılığı). */
    val candidateHosts: List<String>
        get() {
            debugHostOverride?.let { return listOf(it) }
            return if (isEmulator) listOf("https://dentifay.com", "http://10.0.2.2:3001") else listOf("https://dentifay.com")
        }

    private const val API_SUFFIX = "/api"
    private const val CACHE_KEY = "AppConfig.resolvedHost.v2"

    @Volatile private var resolvedHost: String? = null
    private val cachedHost: String? get() = Prefs.string(CACHE_KEY)

    val apiBaseURL: String
        get() {
            debugHostOverride?.let { return it + API_SUFFIX }
            val host = resolvedHost ?: cachedHost ?: candidateHosts.firstOrNull() ?: "https://dentifay.com"
            return host + API_SUFFIX
        }

    /** Web panelinin kökü (`/api` soneki atılır) — tarayıcıda açılan sayfalar için. */
    val webBaseURL: String
        get() = apiBaseURL.let { if (it.endsWith(API_SUFFIX)) it.dropLast(API_SUFFIX.length) else it }

    /**
     * Uygulama içi kredi satın alma. Mağaza derlemesinde (release, qa) KAPALI: krediler web panelinden
     * alınır, uygulama yalnız harcar ve bakiyeyi gösterir. Kapalıyken paketler, satın alma penceresi ve
     * "Kredi Al / kredi yükle" çağrıları çizilmez; web'e satın alma bağlantısı da verilmez (Play Ödemeler §4).
     */
    val inAppPurchase: Boolean get() = BuildConfig.IN_APP_PURCHASE

    /**
     * Yalnız geliştirme: `adb shell am start -n com.dentifay.app/.MainActivity --es DENTIFAY_API_HOST http://10.0.2.2:3001`
     * verilirse tüm istekler o sunucuya gider; değer saklanır. `prod` saklanan değeri siler.
     */
    private const val OVERRIDE_KEY = "AppConfig.debugHostOverride"
    val debugHostOverride: String?
        get() = if (BuildConfig.ALLOW_HOST_OVERRIDE) Prefs.string(OVERRIDE_KEY) else null

    fun applyDebugOverride(value: String?) {
        if (!BuildConfig.ALLOW_HOST_OVERRIDE) return
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return
        if (v == "prod") Prefs.remove(OVERRIDE_KEY) else Prefs.set(OVERRIDE_KEY, v)
    }

    private val isEmulator: Boolean
        get() = android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone") ||
            android.os.Build.PRODUCT.contains("sdk") || android.os.Build.HARDWARE.contains("ranchu")

    private val probe = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    /** Tüm adayları PARALEL dener; ulaşılabilenlerden en yüksek öncelikliyi seçip cache'ler. */
    suspend fun resolveBaseURL() {
        debugHostOverride?.let { resolvedHost = it; return }
        val hosts = candidateHosts.toMutableList()
        cachedHost?.let { c -> hosts.remove(c); hosts.add(0, c) }
        val results = coroutineScope { hosts.map { h -> async(Dispatchers.IO) { isReachable(h) } }.awaitAll() }
        val chosen = hosts.indices.firstOrNull { results[it] }?.let { hosts[it] }
        if (chosen != null) {
            resolvedHost = chosen
            Prefs.set(CACHE_KEY, chosen)
            if (BuildConfig.DEBUG) android.util.Log.d("AppConfig", "backend seçildi: $chosen")
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d("AppConfig", "hiçbir aday backend'e ulaşılamadı; varsayılan kullanılacak")
        }
    }

    /** Herhangi bir HTTP cevabı = host ayakta; yalnız bağlantı hatası → false. */
    private suspend fun isReachable(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { probe.newCall(Request.Builder().url(host + API_SUFFIX).get().build()).execute().use { true } }.getOrDefault(false)
    }
}
