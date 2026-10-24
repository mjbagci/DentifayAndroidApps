package com.dentifay.app.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.dentifay.app.core.DfyJson
import com.dentifay.app.core.Prefs
import com.dentifay.app.theme.ActivityHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume

/** Face ID / Touch ID (LocalAuthentication) karşılığı: AndroidX BiometricPrompt. */
object BiometricAuth {
    private const val AUTHENTICATORS = BIOMETRIC_STRONG or BIOMETRIC_WEAK

    /** Cihazda biyometri kurulu ve kullanılabilir mi? */
    val isAvailable: Boolean
        get() {
            val ctx = Prefs.appContext ?: return false
            return BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        }

    /** Kullanıcıya gösterilecek ad (Android tür ayırt etmez: yüz / parmak izi ortak ad). */
    val displayName: String get() = "Biyometrik Giriş"

    /** İlgili ikon adı (SF Symbol karşılığı; DfyIcon yedek eşlemesi Material ikon verir). */
    val iconName: String get() = if (isAvailable) "touchid" else "lock.shield.fill"

    /** Biyometrik doğrulama ister; başarılıysa true. İptal/eşleşmeme → false. */
    suspend fun authenticate(reason: String): Boolean {
        val activity = ActivityHolder.current as? FragmentActivity ?: return false
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
                override fun onAuthenticationFailed() { /* Kullanıcı yeniden deneyebilir; sistem istemi açık kalır. */ }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Dentifay")
                .setSubtitle(reason)
                .setNegativeButtonText("Vazgeç")   // bizim yedeğimiz uygulama giriş formudur
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
    }
}

/** Giriş kimliği + parolayı yalnızca bu cihazda, şifreli depoda saklar (Keychain karşılığı). */
object BiometricCredentialStore {
    private const val FILE = "com.dentifay.biometric"
    private const val KEY = "login"

    @Serializable
    data class Credentials(val email: String, val password: String)

    private fun prefs() = Prefs.appContext?.let { ctx ->
        runCatching {
            EncryptedSharedPreferences.create(
                FILE, MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    fun save(email: String, password: String) {
        val raw = DfyJson.encodeToString(Credentials.serializer(), Credentials(email, password))
        prefs()?.edit()?.putString(KEY, raw)?.apply()
    }

    fun load(): Credentials? {
        val raw = prefs()?.getString(KEY, null) ?: return null
        return runCatching { DfyJson.decodeFromString(Credentials.serializer(), raw) }.getOrNull()
    }

    fun delete() {
        prefs()?.edit()?.remove(KEY)?.apply()
    }
}
