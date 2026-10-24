package com.dentifay.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * `HTTPCookieStorage.shared` karşılığı: bellekte tutulan çerez kavanozu.
 * "Beni hatırla" açıkken `save()` ile kalıcı hale gelir, açılışta `restore()` geri yükler.
 */
object CookieStore : CookieJar {
    private const val KEY = "savedSessionCookies"
    private val cookies = mutableListOf<Cookie>()

    @Serializable
    private data class Saved(val name: String, val value: String, val domain: String, val path: String, val expiresAt: Long, val secure: Boolean, val httpOnly: Boolean, val hostOnly: Boolean)

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) this.cookies.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun all(): List<Cookie> = cookies.toList()

    @Synchronized
    fun clear() = cookies.clear()

    /** `AuthManager.saveCookies()` — oturum çerezleri 30 gün geçerli sayılır. */
    @Synchronized
    fun save() {
        if (cookies.isEmpty()) return
        val farFuture = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        val list = cookies.map {
            Saved(it.name, it.value, it.domain, it.path, if (it.persistent) it.expiresAt else farFuture, it.secure, it.httpOnly, it.hostOnly)
        }
        Prefs.set(KEY, DfyJson.encodeToString(ListSerializer(Saved.serializer()), list))
    }

    @Synchronized
    fun restore() {
        val raw = Prefs.string(KEY) ?: return
        val list = runCatching { DfyJson.decodeFromString(ListSerializer(Saved.serializer()), raw) }.getOrNull() ?: return
        for (s in list) {
            val b = Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt)
            if (s.hostOnly) b.hostOnlyDomain(s.domain) else b.domain(s.domain)
            if (s.secure) b.secure()
            if (s.httpOnly) b.httpOnly()
            val c = b.build()
            cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            cookies.add(c)
        }
    }

    fun clearSaved() = Prefs.remove(KEY)
}
