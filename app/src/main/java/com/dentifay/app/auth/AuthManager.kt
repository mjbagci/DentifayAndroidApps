package com.dentifay.app.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.dentifay.app.core.APIUser
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.CookieStore
import com.dentifay.app.core.Kurulum
import com.dentifay.app.core.SifreOlayi
import com.dentifay.app.core.Prefs
import com.dentifay.app.core.SessionEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** SwiftUI `@Environment(AuthManager.self)` karşılığı. */
val LocalAuthManager = staticCompositionLocalOf { AuthManager() }

/** `@Observable final class AuthManager` — alanlar Compose snapshot state'tir. */
class AuthManager {
    var currentUser by mutableStateOf<APIUser?>(null)
    var isLoggedIn by mutableStateOf(false)
    var isCheckingSession by mutableStateOf(true)
    var loginError by mutableStateOf<String?>(null)
    /**
     * Aktif kliniğin GERÇEK adı (`GET /auth/organizations`). Yüklenemezse null KALIR ve arayüz o satırı ÇİZMEZ —
     * marka adı UYDURULMAZ.
     */
    var organizationName by mutableStateOf<String?>(null)
    /** Bir uç 403 `E_CLINIC_SETUP_INCOMPLETE` döndü (yarış / eski sürüm) — oturum yeniden okununca temizlenir. */
    private var kurulumOlayi by mutableStateOf<Boolean?>(null)
    /** Şifre değişikliği zorunlu (403 `PASSWORD_CHANGE_REQUIRED` geldi) — şifre kaydedilip oturum yeniden okununca temizlenir. */
    private var sifreOlayi by mutableStateOf(false)

    private val scope = MainScope()

    init {
        // Oturum herhangi bir istekte düşerse giriş ekranına NEDENİYLE dönülür.
        scope.launch { SessionEvents.ended.collect { code -> sessionEnded(code) } }
        scope.launch { SifreOlayi.gerekli.collect { if (isLoggedIn) sifreOlayi = true } }
        scope.launch { Kurulum.tamamlanmadi.collect { yapabilir -> if (isLoggedIn) kurulumOlayi = yapabilir } }
    }

    /** Olay dinleyicilerini bırakır (uygulamada kök ömür boyunca yaşar; birim testleri ve önizlemeler için). */
    fun dispose() {
        scope.cancel()
    }

    /** "Yeni şifre belirleyin" ekranı gerekiyor mu (M-E8.6): oturumun `mustChangePassword` alanı ya da 403 olayı. */
    val sifreBelirle: Boolean get() = sifreOlayi || currentUser?.mustChangePassword == true

    /** Kurulumu bitmemiş klinik: bekleme ekranı gerekiyorsa `yapabilir`, gerekmiyorsa null (M-E5.4). */
    val kurulumBekleme: Boolean? get() = kurulumOlayi ?: Kurulum.bekleme(currentUser?.kurulum)

    /** Oturumun yetenek kümesi (sekme/kart/eylem görünürlüğü — `Yetki`). */
    val yetenekler: Set<String> get() = currentUser?.yetenekler ?: emptySet()

    /** Şifre kaydedildi — oturumu yeniden oku (AUTH §5: başarıdan sonra `GET /session`). */
    suspend fun sifreKaydedildi() {
        sifreOlayi = false
        currentUser?.let { currentUser = it.copy(mustChangePassword = false, geciciSifreyle = false) }
        refreshGrants()
    }

    /** Aktif kliniğin adını çözer. Başarısızsa null bırakır (sessiz, ama SAHTE bir ad da göstermez). */
    private suspend fun loadOrganizationName() {
        val orgId = currentUser?.organizationId ?: run { organizationName = null; return }
        val list = runCatching { ApiClient.fetchOrganizations() }.getOrDefault(emptyList())
        organizationName = list.firstOrNull { it.organizationId == orgId }?.organizationName
            ?: list.firstOrNull { it.current == true }?.organizationName
    }

    private fun sessionEnded(code: String?) {
        // Açılıştaki oturum denetimi sırasında `isLoggedIn` henüz false'tur; o yolun nedenini checkSession gösterir.
        if (!isLoggedIn) return
        SessionEvents.takeLastCode()
        isLoggedIn = false
        currentUser = null
        rememberSession = false
        CookieStore.clearSaved()
        CookieStore.clear()
        loginError = SessionEvents.message(code) ?: "Oturumunuz sona erdi. Lütfen yeniden giriş yapın."
    }

    companion object {
        var rememberSession: Boolean
            get() = Prefs.bool("rememberSession")
            set(value) = Prefs.set("rememberSession", value)

        /** Kullanıcı biyometrik girişi etkinleştirdi mi? */
        var biometricEnabled: Boolean
            get() = Prefs.bool("biometricEnabled")
            set(value) = Prefs.set("biometricEnabled", value)

        fun saveCookies() = CookieStore.save()
        fun restoreCookies() = CookieStore.restore()

        /** Giriş yanıtının `geciciSifreyle` / `mustChangePassword` alanları oturum yanıtında olmayabilir — giriş yanıtındaki korunur. */
        fun birlestir(giris: APIUser, oturum: APIUser): APIUser = oturum.copy(
            mustChangePassword = oturum.mustChangePassword ?: giris.mustChangePassword,
            geciciSifreyle = oturum.geciciSifreyle ?: giris.geciciSifreyle,
        )
    }

    suspend fun checkSession() {
        isCheckingSession = true
        try {
            val resp = try {
                ApiClient.checkSession()
            } catch (e: ApiException.ServerError) {
                if (e.code != 503) throw e
                // AUTH_UNAVAILABLE: kimlik altyapısı geçici olarak yok — ÇIKIŞ YAPILMAZ, bir kez yinelenir.
                delay(2000)
                ApiClient.checkSession()
            }
            currentUser = resp.user
            kurulumOlayi = null
            isLoggedIn = true
            loadOrganizationName()
        } catch (e: ApiException.Unauthorized) {
            isLoggedIn = false
            currentUser = null
            rememberSession = false
            CookieStore.clearSaved()
            // Uygulama kapalıyken oturum düştüyse (başka cihazdan giriş …) nedeni göster.
            loginError = SessionEvents.message(SessionEvents.takeLastCode())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isLoggedIn = false
            currentUser = null
        }
        isCheckingSession = false
    }

    /** Yetkileri sessizce tazeler (Ayarlar açılınca). Hata oturumu KAPATMAZ. */
    suspend fun refreshGrants() {
        if (!isLoggedIn) return
        val resp = runCatching { ApiClient.checkSession() }.getOrNull() ?: return
        currentUser = resp.user
    }

    suspend fun login(email: String, password: String, remember: Boolean) {
        loginError = null
        try {
            val resp = ApiClient.login(email, password)
            currentUser = resp.user
            // M-E8.0: giriş yanıtında `timezone` Bölüm B'den itibaren — oturum ayrıca okunur (alan yoksa giriş yanıtı kalır).
            runCatching { ApiClient.checkSession() }.getOrNull()?.let { currentUser = birlestir(resp.user, it.user) }
            kurulumOlayi = null
            sifreOlayi = false
            isLoggedIn = true
            loadOrganizationName()
            rememberSession = remember
            if (remember) saveCookies()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loginError = e.message
        }
    }

    suspend fun logout() {
        runCatching { ApiClient.logout() }
        CookieStore.clear()
        currentUser = null
        organizationName = null
        isLoggedIn = false
        rememberSession = false
        CookieStore.clearSaved()
        disableBiometricLogin()
    }

    // MARK: - Biyometrik Giriş

    /** Başarılı parola girişinin ardından biyometrik girişi etkinleştirir (şifreli depoya yazar). */
    fun enableBiometricLogin(email: String, password: String) {
        BiometricCredentialStore.save(email, password)
        biometricEnabled = true
    }

    fun disableBiometricLogin() {
        BiometricCredentialStore.delete()
        biometricEnabled = false
    }

    /** Biyometrik doğrulayıp saklanan kimlik bilgileriyle giriş yapar; başarısızsa false. */
    suspend fun loginWithBiometrics(): Boolean {
        if (!biometricEnabled) return false
        val creds = BiometricCredentialStore.load() ?: return false
        val ok = BiometricAuth.authenticate("Dentifay'e giriş yapmak için kimliğinizi doğrulayın")
        if (!ok) return false
        login(creds.email, creds.password, remember = true)
        return isLoggedIn
    }

    // MARK: - Giriş sonrası biyometrik onay teklifi

    var pendingBiometricOffer by mutableStateOf(false)
    private var offerEmail: String? = null
    private var offerPassword: String? = null

    fun maybeOfferBiometricEnrollment(email: String, password: String) {
        if (!BiometricAuth.isAvailable || biometricEnabled) return
        offerEmail = email
        offerPassword = password
        pendingBiometricOffer = true
    }

    fun acceptBiometricOffer() {
        val e = offerEmail; val p = offerPassword
        if (e != null && p != null) enableBiometricLogin(e, p)
        dismissBiometricOffer()
    }

    fun dismissBiometricOffer() {
        pendingBiometricOffer = false
        offerEmail = null
        offerPassword = null
    }
}
