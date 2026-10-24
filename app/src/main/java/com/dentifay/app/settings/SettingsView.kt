package com.dentifay.app.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.BiometricAuth
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.Prefs
import com.dentifay.app.core.Unvan
import com.dentifay.app.credit.CreditCenterView
import com.dentifay.app.credit.SmileCreditFormat
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.credit.SmileCreditsView
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTileSize
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.PageHeader
import com.dentifay.app.theme.SectionLabel
import com.dentifay.app.theme.TagChip
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.openInBrowser
import com.dentifay.app.theme.openSystemUrl
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.launch
import java.util.Locale

private const val AUTO_TTS_KEY = "autoTTSEnabled"

/**
 * Ayarlar — web "Ayarlar" sayfasının sade mobil karşılığı: profil kartı, gruplu listeler
 * (nötr ikonlar, ince ayraçlar), web paneline giden bağlantılar ve çıkış.
 */
@Composable
fun SettingsView(onShowNotifications: () -> Unit) {
    val authManager = LocalAuthManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // @AppStorage("autoTTSEnabled") = true
    var autoTTSEnabled by remember { mutableStateOf(if (Prefs.contains(AUTO_TTS_KEY)) Prefs.bool(AUTO_TTS_KEY) else true) }
    var biometricEnabled by remember { mutableStateOf(AuthManager.biometricEnabled) }
    var showCredits by remember { mutableStateOf(false) }
    var showCreditCenter by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val credits = SmileCreditsStore.shared

    /** Web panelinin kökü (API adresinden türetilir: https://dentifay.com). */
    fun open(path: String) = openInBrowser(context, AppConfig.webBaseURL + path)

    LaunchedEffect(Unit) {
        if (!authManager.isLoggedIn) return@LaunchedEffect
        // Yetkiler tazelenir: sahip bir yetkiyi açtıysa ekran yeniden giriş istemeden görünür.
        authManager.refreshGrants()
        credits.refresh()
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                PageHeader(
                    title = "Ayarlar",
                    icon = "gearshape",
                    subtitle = "Klinik, kullanıcı, tedavi, finans ve sistem ayarları.",
                    modifier = Modifier.padding(top = 8.dp).appear(0.0),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, DS.Colors.hairline, CircleShape)
                            .plainClickable(onClick = onShowNotifications)
                            .semantics { contentDescription = "Bildirimler" },
                        contentAlignment = Alignment.Center,
                    ) {
                        DfyIcon("bell", size = 17.dp, tint = DS.Colors.metal)
                    }
                }

                ProfileCard(Modifier.appear(0.04))

                Group("Tercihler", Modifier.appear(0.08)) {
                    ToggleRow(
                        icon = "speaker.wave.2", title = "Otomatik seslendirme",
                        subtitle = "Asistan yanıtını sesli okur", isOn = autoTTSEnabled,
                    ) {
                        autoTTSEnabled = it
                        Prefs.set(AUTO_TTS_KEY, it)
                    }
                    if (BiometricAuth.isAvailable && biometricEnabled) {
                        RowDivider()
                        ToggleRow(
                            icon = BiometricAuth.iconName, title = "${BiometricAuth.displayName} ile giriş",
                            subtitle = "Girişte şifre yerine ${BiometricAuth.displayName}", isOn = biometricEnabled,
                        ) {
                            biometricEnabled = it
                            // Ayarlardan yalnızca kapatma yapılır (açmak parola gerektirir, girişte yapılır).
                            if (!it) authManager.disableBiometricLogin()
                        }
                    }
                }

                Group("Krediler", Modifier.appear(0.12)) {
                    LinkRow(
                        icon = "creditcard", title = "Krediler",
                        subtitle = if (AppConfig.inAppPurchase) {
                            credits.wallet?.let { "Kalan ${SmileCreditFormat.credits(it.availableCredits)} kredi · paket al" }
                                ?: "Kredi bakiyesi ve paketler"
                        } else {
                            // Mağaza derlemesi: satın alma yok, yalnız bakiye.
                            credits.wallet?.let { "Kalan ${SmileCreditFormat.credits(it.availableCredits)} kredi" }
                                ?: "Kredi bakiyesi"
                        },
                    ) { showCredits = true }
                    // Kredi Yönetimi: kim · nerede · ne kadar; kişi limitleri; kredi yükleme (yalnız `inAppPurchase`).
                    // Görünürlük sunucunun yetkisinden (`fin.credits.view`); asıl kapı sunucudadır.
                    if (authManager.currentUser?.can("fin.credits.view") == true) {
                        RowDivider()
                        LinkRow(
                            icon = "chart.bar.doc.horizontal", title = "Kredi Yönetimi",
                            subtitle = if (AppConfig.inAppPurchase) "Kullanım, kişi limitleri, kredi yükleme" else "Kullanım ve kişi limitleri",
                        ) { showCreditCenter = true }
                    }
                }

                Group("Hesap", Modifier.appear(0.16)) {
                    LinkRow(icon = "lock.rotation", title = "Şifre değiştir", subtitle = "Web panelinde açılır") {
                        open("/settings/sifre")
                    }
                    RowDivider()
                    LinkRow(icon = "macwindow", title = "Web panelini aç", subtitle = "Tüm klinik ayarları ve raporlar") {
                        open("/")
                    }
                    RowDivider()
                    LinkRow(
                        icon = "person.crop.circle.badge.xmark", title = "Hesap silme talebi",
                        subtitle = "KVKK başvurusu · kvkk@dentifay.com", tint = DS.Colors.error,
                    ) {
                        openSystemUrl(context, "mailto:kvkk@dentifay.com?subject=Hesap%20silme%20talebi")
                    }
                }

                val danger = mutedHex("ff8a8a")
                val logoutShape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .appear(0.2)
                        .pressable { confirmLogout = true }
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(logoutShape)
                        .background(DS.Colors.danger.copy(alpha = 0.07f))
                        .border(1.dp, DS.Colors.danger.copy(alpha = 0.25f), logoutShape),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DfyIcon("rectangle.portrait.and.arrow.right", size = 15.dp, tint = danger)
                    Text("Güvenli Çıkış", style = sys(15, FontWeight.SemiBold), color = danger)
                }

                Text(
                    versionText(context),
                    Modifier.fillMaxWidth(),
                    style = sys(11, FontWeight.Medium, tracking = 0.6).copy(fontFamily = FontFamily.Monospace),
                    color = DS.Colors.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    DfySheet(isPresented = showCredits, onDismiss = { showCredits = false }) { dismiss ->
        SmileCreditsView(onDismiss = dismiss)
    }
    DfySheet(isPresented = showCreditCenter, onDismiss = { showCreditCenter = false }) { dismiss ->
        CreditCenterView(onDismiss = dismiss)
    }
    DfyAlert(
        isPresented = confirmLogout,
        title = "Çıkış yapılsın mı?",
        confirmText = "Güvenli Çıkış",
        onConfirm = {
            confirmLogout = false
            Haptics.warning()
            scope.launch { authManager.logout() }
        },
        dismissText = "Vazgeç",
        onDismiss = { confirmLogout = false },
        destructive = true,
    )
}

// MARK: - Profil

@Composable
private fun ProfileCard(modifier: Modifier = Modifier) {
    val authManager = LocalAuthManager.current
    val user = authManager.currentUser
    val initials = userInitials(user?.name)
    Row(
        modifier
            .fillMaxWidth()
            .glassCard(18.dp)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    relativeLinearGradient(listOf(0f to DS.Colors.purple500, 1f to DS.Colors.brandBlue), Offset(0f, 0f), Offset(1f, 1f))
                )
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials.ifEmpty { "D" }, style = sys(20, FontWeight.Bold), color = Color.White)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                Unvan.dt(user?.name ?: "Hekim"), style = sys(18, FontWeight.Bold), color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TagChip(roleLabel(user?.role), tint = DS.Colors.cyan400)
                val email = user?.email
                if (!email.isNullOrEmpty()) {
                    Text(
                        middleEllipsis(email), style = sys(12), color = DS.Colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** SwiftUI `.truncationMode(.middle)` yaklaşık karşılığı: çok uzun adres ortadan kısaltılır. */
private fun middleEllipsis(s: String, max: Int = 30): String =
    if (s.length <= max) s else s.take(max / 2 - 1) + "…" + s.takeLast(max / 2 - 1)

private fun roleLabel(role: String?): String {
    val r = role ?: ""
    return when (r.uppercase(Locale.ROOT)) {
        "OWNER" -> "Sahip"
        "ADMIN" -> "Yönetici"
        "MANAGER" -> "Müdür"
        "DOCTOR" -> "Hekim"
        "ASSISTANT" -> "Asistan"
        "RECEPTION", "SECRETARY" -> "Banko"
        "ACCOUNTANT" -> "Muhasebe"
        "READONLY" -> "Salt okunur"
        "" -> "Hekim"
        // Swift `.capitalized`: her sözcüğün ilk harfi büyük, kalanı küçük.
        else -> r.split(" ").joinToString(" ") { w ->
            w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }
}

private fun userInitials(name: String?): String {
    val parts = (name ?: "").split(" ").filter { it.isNotEmpty() }
    return parts.take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").upperTr()
}

private fun versionText(context: Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val v = info?.versionName ?: "1.0"
    val b = info?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "1"
    return "DENTIFAY · SÜRÜM $v ($b)"
}

// MARK: - Liste yapı taşları

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxWidth().glassCard(16.dp), content = content)
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(DS.Colors.hairline))
}

@Composable
private fun ToggleRow(icon: String, title: String, subtitle: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon = icon, tone = IconTone.Metal, size = IconTileSize.Sm)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface)
            Text(subtitle, style = sys(12.5), color = DS.Colors.onSurfaceVariant)
        }
        Switch(
            checked = isOn,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = DS.Colors.cyan400,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun LinkRow(icon: String, title: String, subtitle: String, tint: Color? = null, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable { Haptics.light(); action() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon = icon, tone = IconTone.Metal, size = IconTileSize.Sm, iconTint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = sys(15, FontWeight.Medium), color = tint ?: DS.Colors.onSurface)
            Text(subtitle, style = sys(12.5), color = DS.Colors.onSurfaceVariant)
        }
        Spacer(Modifier.size(0.dp))
        DfyIcon("chevron.right", size = 12.dp, tint = DS.Colors.outline)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SettingsViewPreview() {
    PreviewEnv {
        SettingsView(onShowNotifications = {})
    }
}
