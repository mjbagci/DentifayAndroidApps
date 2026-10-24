package com.dentifay.app.settings

import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.theme.FullScreenCover
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.BiometricAuth
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.core.Unvan
import com.dentifay.app.credit.CreditCenterView
import com.dentifay.app.credit.KrediErisimi
import com.dentifay.app.credit.SmileCreditFormat
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.credit.SmileCreditsView
import com.dentifay.app.login.SifreBelirlemeView
import com.dentifay.app.notifications.ZilDugmesi
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
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Ayarlar — web "Ayarlar" sayfasının sade mobil karşılığı: profil kartı, gruplu listeler (nötr ikonlar, ince ayraçlar),
 * yerli "Şifre değiştir", web paneline giden bağlantılar (paket web erişimi veriyorsa) ve çıkış. Üst çubuktaki profil
 * simgesinden açılır (M-E8.1). Tablette içerik 640 dp, ortada.
 */
@Composable
fun SettingsView(onShowNotifications: () -> Unit) {
    val authManager = LocalAuthManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var biometricEnabled by remember { mutableStateOf(AuthManager.biometricEnabled) }
    var showCredits by remember { mutableStateOf(false) }
    var sifreAcik by remember { mutableStateOf(false) }
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .icerikGenisligi(640.dp)
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
                    ZilDugmesi(onShowNotifications)
                }

                ProfileCard(Modifier.appear(0.04))

                if (BiometricAuth.isAvailable && biometricEnabled) {
                    Group("Tercihler", Modifier.appear(0.08)) {
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

                // Kredi Yönetimi görünürse TEK satır; değilse `ai.smilesim` ile salt okur "Krediler"; ikisi de yoksa grup yok.
                // Mağaza derlemesinde (`inAppPurchase` kapalı) satın alma yok → alt yazıda "paketler" geçmez.
                val kalan = credits.wallet?.let { SmileCreditFormat.credits(it.availableCredits) }
                when (KrediErisimi.ayarlarSatiri(authManager.yetenekler)) {
                    KrediErisimi.AyarlarSatiri.yonetim -> Group("Krediler", Modifier.appear(0.12)) {
                        val ozet = if (AppConfig.inAppPurchase) "kullanım, limitler ve paketler" else "kullanım ve limitler"
                        LinkRow(
                            icon = "chart.bar.doc.horizontal", title = "Kredi Yönetimi",
                            subtitle = kalan?.let { "Kalan $it kredi · $ozet" } ?: ozet.replaceFirstChar { it.titlecase(TR) },
                        ) { showCreditCenter = true }
                    }
                    KrediErisimi.AyarlarSatiri.krediler -> Group("Krediler", Modifier.appear(0.12)) {
                        LinkRow(
                            icon = "creditcard", title = "Krediler",
                            subtitle = kalan?.let { "Kalan $it kredi" } ?: "Kredi bakiyesi",
                        ) { showCredits = true }
                    }
                    null -> Unit
                }

                Group("Hesap", Modifier.appear(0.16)) {
                    LinkRow(icon = "lock.rotation", title = "Şifre değiştir", subtitle = null) { sifreAcik = true }
                    RowDivider()
                    // Paket web erişimi vermiyorsa web'e giden bağlantı çizilmez (M-E8.6).
                    if (authManager.currentUser?.webErisimi ?: true) {
                        LinkRow(icon = "macwindow", title = "Web panelini aç", subtitle = "Tüm klinik ayarları ve raporlar") {
                            open("/")
                        }
                        RowDivider()
                    }
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
                    Text("Güvenli Çıkış", style = sys(15, FontWeight.SemiBold), color = danger, maxLines = 1)
                }

                val surum = remember(context) { versionText(context) }
                Text(
                    surum,
                    Modifier.fillMaxWidth(),
                    style = sys(11, FontWeight.Medium, tracking = 0.6).copy(fontFamily = FontFamily.Monospace),
                    color = DS.Colors.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    DfySheet(isPresented = showCredits, onDismiss = { showCredits = false }) { dismiss ->
        SmileCreditsView(onDismiss = dismiss)
    }
    // Yerli "Şifre değiştir" (M-E8.6) — geçici şifreyle açılmış oturumda "Mevcut şifre" istenmez.
    DfySheet(isPresented = sifreAcik, onDismiss = { sifreAcik = false }) { dismiss ->
        SifreBelirlemeView(zorunlu = false, onDismiss = dismiss)
    }
    // Kredi Yönetimi: telefonda sayfa (iOS gibi); geniş pencerede (tablet) tam ekran — pano 1040 dp'ye kadar açılır.
    if (LocalPencere.current.genis) {
        FullScreenCover(isPresented = showCreditCenter, onDismiss = { showCreditCenter = false }) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            ) {
                CreditCenterView(onDismiss = { showCreditCenter = false })
            }
        }
    } else {
        DfySheet(isPresented = showCreditCenter, onDismiss = { showCreditCenter = false }) { dismiss ->
            CreditCenterView(onDismiss = dismiss)
        }
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

private val TR: Locale = Locale.forLanguageTag("tr-TR")

// MARK: - Profil

@Composable
private fun ProfileCard(modifier: Modifier = Modifier) {
    val authManager = LocalAuthManager.current
    val user = authManager.currentUser
    val initials = remember(user?.name) { userInitials(user?.name) }
    // Oturum kişisi: rolü DOCTOR ise unvan kuralıyla, değilse AYNEN (banko/yönetici "Dt." almaz).
    val ad = remember(user?.name, user?.role) { user?.let { Unvan.oturumAdi(it.name, it.role) } ?: "Hekim" }
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
                .background(AVATAR_ZEMINI)
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials.ifEmpty { "D" }, style = sys(20, FontWeight.Bold), color = Color.White)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ad, style = sys(18, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TagChip(roleLabel(user?.role), tint = DS.Colors.cyan400)
                val email = user?.email
                if (!email.isNullOrEmpty()) {
                    OrtadanKisalanMetin(email, sys(12), DS.Colors.onSurfaceVariant, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

private val AVATAR_ZEMINI = relativeLinearGradient(listOf(0f to DS.Colors.purple500, 1f to DS.Colors.brandBlue), Offset(0f, 0f), Offset(1f, 1f))

/**
 * SwiftUI `.lineLimit(1).truncationMode(.middle)`: metin sığmıyorsa ORTADAN kısaltılır ("ayse.dem…@klinik.com") —
 * kısaltma gerçek genişliğe göre (tablette tam adres görünür). Ölçüm yalnız metin ya da genişlik değişince.
 */
@Composable
private fun OrtadanKisalanMetin(metin: String, stil: TextStyle, renk: Color, modifier: Modifier = Modifier) {
    val olcer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val enCok = constraints.maxWidth
        val sigan = remember(metin, enCok, stil) { ortadanKisalt(metin, enCok) { olcer.measure(it, stil, maxLines = 1, softWrap = false).size.width } }
        Text(sigan, style = stil, color = renk, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false)
    }
}

/** `enCok` piksele sığan en uzun "baş…son" biçimi (ikili arama; `olc` metnin genişliğini verir). */
internal fun ortadanKisalt(metin: String, enCok: Int, olc: (String) -> Int): String {
    if (enCok == Constraints.Infinity || olc(metin) <= enCok) return metin
    fun aday(k: Int): String = metin.take((k + 1) / 2) + "…" + metin.takeLast(k / 2)
    var dusuk = 0
    var yuksek = metin.length - 1
    while (dusuk < yuksek) {
        val orta = (dusuk + yuksek + 1) / 2
        if (olc(aday(orta)) <= enCok) dusuk = orta else yuksek = orta - 1
    }
    return aday(dusuk)
}

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
private fun LinkRow(icon: String, title: String, subtitle: String?, tint: Color? = null, action: () -> Unit) {
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
            if (subtitle != null) Text(subtitle, style = sys(12.5), color = DS.Colors.onSurfaceVariant)
        }
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

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun SettingsViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        SettingsView(onShowNotifications = {})
    }
}
