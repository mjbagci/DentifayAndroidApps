package com.dentifay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dentifay.app.agent.AgentView
import com.dentifay.app.aihub.AIHubView
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.BiometricAuth
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.data.Appointment
import com.dentifay.app.data.DataManager
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.data.MockData
import com.dentifay.app.hasta.HastaTedaviListeView
import com.dentifay.app.home.AppointmentDetailSheet
import com.dentifay.app.home.HomeView
import com.dentifay.app.home.YeniRandevuSheet
import com.dentifay.app.notifications.NotificationsStore
import com.dentifay.app.notifications.NotificationsView
import com.dentifay.app.quickadd.QuickAction
import com.dentifay.app.quickadd.QuickAddSheet
import com.dentifay.app.settings.SettingsView
import com.dentifay.app.smile.SmileSimulationView
import com.dentifay.app.stock.StockView
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyAlert
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.treatment.TreatmentPlanView
import com.dentifay.app.xray.XrayAnalysisView
import com.dentifay.app.xray.XrayAnalysisViewModel
import kotlinx.coroutines.launch

/**
 * Ana sekme kabuğu: içerik + gezinme (telefonda alt çubuk, geniş ekranda yan ray) + sayfalar ve tam ekran akışlar.
 * Sekmeler oturumun yeteneklerinden süzülür (M-E3.6); "Ayarlar" her üst çubuktaki profil simgesindedir (M-E8.1).
 */
@Composable
fun ContentView() {
    val authManager = LocalAuthManager.current
    val dataManager = LocalDataManager.current
    val pencere = LocalPencere.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(TabItem.home) }
    var showQuickAdd by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var selectedAppointment by remember { mutableStateOf<Appointment?>(null) }
    var isDetailPresented by remember { mutableStateOf(false) }
    // Hızlı işlem: sayfa kapanırken seçilen işlem bekletilir, kapanınca açılır.
    var pendingQuickAction by remember { mutableStateOf<QuickAction?>(null) }
    var quickAction by remember { mutableStateOf<QuickAction?>(null) }
    /** "Ekle › Yeni randevu" yerli ekran (M-E8.13). */
    var yeniRandevu by remember { mutableStateOf(false) }

    val gorunurSekmeler = TabItem.gorunur(authManager.yetenekler)
    // Seçili sekme oturumun kapsamından çıktıysa (yetki alındı / paket değişti) görünen ilk sekmeye geç.
    LaunchedEffect(gorunurSekmeler) {
        if (selectedTab !in gorunurSekmeler) {
            selectedTab = TabItem.entries.firstOrNull { it in gorunurSekmeler && it != TabItem.quickAdd } ?: TabItem.settings
        }
    }

    // Bildirim sayacı ön plana gelişte yenilenir (zamanlayıcıyla yoklama YOK — M-E1.6.4).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val gozlemci = LifecycleEventObserver { _, olay ->
            if (olay == Lifecycle.Event.ON_RESUME && authManager.isLoggedIn) scope.launch { NotificationsStore.shared.refreshCounts() }
        }
        lifecycleOwner.lifecycle.addObserver(gozlemci)
        onDispose { lifecycleOwner.lifecycle.removeObserver(gozlemci) }
    }

    LaunchedEffect(selectedAppointment) { isDetailPresented = selectedAppointment != null }
    val blur by animateDpAsState(if (isDetailPresented) 2.dp else 0.dp, Motion.easeOut(250), label = "detailBlur")

    val ray = pencere.genis
    val sekmeyeGit: (TabItem) -> Unit = { selectedTab = it }

    val sekmeDurumlari = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalAyarlariAc provides { selectedTab = TabItem.settings }, LocalSekmeDurumlari provides sekmeDurumlari) {
        Box(
            Modifier
                .fillMaxSize()
                .background(DS.Colors.background)
                // Yatayda çentik / yan gezinme çubuğu içeriği kesmesin.
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
        ) {
            Box(Modifier.fillMaxSize().blur(blur)) {
                if (ray) {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(selectedTab, sekmeyeGit, gorunur = gorunurSekmeler, onQuickAddTap = { showQuickAdd = true })
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            SekmeIcerigi(selectedTab, { showNotifications = true }, selectedAppointment, { selectedAppointment = it })
                            StatusBarScrim()
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = ALT_CUBUK_YUKSEKLIGI)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    ) {
                        SekmeIcerigi(selectedTab, { showNotifications = true }, selectedAppointment, { selectedAppointment = it })
                    }
                    BottomNavBar(
                        selectedTab, sekmeyeGit, Modifier.align(Alignment.BottomCenter),
                        gorunur = gorunurSekmeler, onQuickAddTap = { showQuickAdd = true },
                    )
                    // Kaydırılan içerik durum çubuğunun altına girmesin.
                    StatusBarScrim()
                }
            }

            AnimatedVisibility(isDetailPresented, enter = fadeIn(Motion.easeOut(250)), exit = fadeOut(Motion.easeOut(250))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).plainClickable { selectedAppointment = null })
            }
        }

        DfyAlert(
            isPresented = authManager.pendingBiometricOffer,
            title = "${BiometricAuth.displayName} ile Giriş",
            message = "Bir dahaki sefere parola girmeden ${BiometricAuth.displayName} ile giriş yapmak ister misiniz?",
            confirmText = "Etkinleştir", onConfirm = { authManager.acceptBiometricOffer() },
            dismissText = "Şimdi Değil", onDismiss = { authManager.dismissBiometricOffer() },
        )

        // Randevu ayrıntısı: %78 yükseklik, tutamaçsız (tablette ortada 640 dp genişlikte).
        DfySheet(
            isPresented = selectedAppointment != null,
            onDismiss = { selectedAppointment = null },
            showDragHandle = false,
            heightFraction = 0.78f,
        ) { dismiss ->
            selectedAppointment?.let { appt -> AppointmentDetailSheet(appointment = appt, onDismiss = dismiss) }
        }

        DfySheet(
            isPresented = showQuickAdd,
            containerColor = DS.Colors.surfaceContainerLow,
            showDragHandle = false,
            onDismiss = {
                showQuickAdd = false
                pendingQuickAction?.let { action ->
                    pendingQuickAction = null
                    if (action == QuickAction.appointment) yeniRandevu = true else quickAction = action
                }
            },
        ) { dismiss ->
            QuickAddSheet(onSelect = { pendingQuickAction = it }, onDismiss = dismiss)
        }

        // "Ekle › Yeni randevu" yerli pencere (M-E8.13) — kaydedilince randevular tazelenir.
        DfySheet(isPresented = yeniRandevu, onDismiss = { yeniRandevu = false }, containerColor = DS.Colors.background) { dismiss ->
            YeniRandevuSheet(kapat = { kaydedildi ->
                dismiss()
                if (kaydedildi) scope.launch { dataManager.refreshAppointments() }
            })
        }

        DfySheet(isPresented = showNotifications, onDismiss = { showNotifications = false }) { dismiss ->
            NotificationsView(onDismiss = dismiss)
        }

        FullScreenCover(isPresented = quickAction != null, onDismiss = { quickAction = null }) {
            quickAction?.let { QuickActionScreen(it) { quickAction = null } }
        }
    }
}

private val LocalSekmeDurumlari = staticCompositionLocalOf<SaveableStateHolder> { error("ContentView dışında") }

@Composable
private fun SekmeIcerigi(
    tab: TabItem,
    onShowNotifications: () -> Unit,
    selectedAppointment: Appointment?,
    onSelectAppointment: (Appointment?) -> Unit,
) {
    // iOS TabView gibi: sekmeden çıkıp dönünce sekmenin kaydedilebilir durumu (seçili hasta, arama, kaydırma) korunur.
    // Tutucu ContentView'de: telefon ↔ geniş düzen geçişinde (döndürme / bölünmüş ekran) da durum kaybolmaz.
    LocalSekmeDurumlari.current.SaveableStateProvider(tab.name) {
        SekmeGovdesi(tab, onShowNotifications, selectedAppointment, onSelectAppointment)
    }
}

@Composable
private fun SekmeGovdesi(
    tab: TabItem,
    onShowNotifications: () -> Unit,
    selectedAppointment: Appointment?,
    onSelectAppointment: (Appointment?) -> Unit,
) {
    when (tab) {
        TabItem.home, TabItem.quickAdd -> HomeView(
            onShowNotifications = onShowNotifications,
            selectedAppointment = selectedAppointment,
            onSelectAppointment = onSelectAppointment,
        )
        TabItem.hastaTedavi -> HastaTedaviListeView(onShowNotifications = onShowNotifications)
        TabItem.stock -> StockView(onShowNotifications = onShowNotifications)
        TabItem.ai -> AIHubView(onShowNotifications = onShowNotifications)
        TabItem.settings -> SettingsView(onShowNotifications = onShowNotifications)
    }
}

/** Hızlı işlem menüsünden açılan tam ekran akışlar (Analiz Merkezi modüllerinin aynısı). */
@Composable
private fun QuickActionScreen(action: QuickAction, close: () -> Unit) {
    when (action) {
        QuickAction.xray -> XrayAnalysisView(onDismiss = close, mode = XrayAnalysisViewModel.Mode.clinic)
        QuickAction.smile -> SmileSimulationView(onDismiss = close)
        QuickAction.treatmentPlan -> TreatmentPlanView(onDismiss = close)
        QuickAction.assistant -> AgentView(onDismiss = close)
        QuickAction.appointment -> Unit
    }
}

/** Durum çubuğu perdesi — durum çubuğunun tamamı + altında 14 dp yumuşak geçiş. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier
            .fillMaxWidth()
            .height(top + 14.dp)
            .background(
                relativeLinearGradient(
                    listOf(0f to DS.Colors.background, 0.78f to DS.Colors.background.copy(alpha = 0.94f), 1f to DS.Colors.background.copy(alpha = 0f)),
                    Offset(0.5f, 0f), Offset(0.5f, 1f),
                )
            )
    )
}

/** Önizlemelerdeki örnek oturumun yetenekleri — bütün sekme ve eylemler görünür. */
val ORNEK_YETENEKLER = listOf(
    "appt.view", "appt.create", "appt.edit", "appt.checkin", "appt.noshow", "chart.view", "chart.plan.edit", "chart.edit",
    "inv.view", "ai.xray", "ai.smilesim", "ai.assistant", "patient.view", "patient.create", "fin.payment.add",
    "fin.credits.view", "fin.credits", "fin.credits.limits",
)

/**
 * @Preview ortamı: yöneticiler MockData ile dolu, koyu tema, telefon penceresi. Tüm ekran önizlemeleri bunu kullanır.
 * `loggedIn = true` → AuthManager örnek kullanıcıyla. `pencere` → tablet önizlemeleri için `PencereBilgisi.TABLET_YATAY`.
 */
@Composable
fun PreviewEnv(loggedIn: Boolean = true, pencere: PencereBilgisi = PencereBilgisi.TELEFON, content: @Composable () -> Unit) {
    val auth = remember {
        AuthManager().apply {
            if (loggedIn) {
                currentUser = com.dentifay.app.core.APIUser(
                    id = "u1", email = "ayse@klinik.com", name = "Dt. Ayşe Demir", organizationId = "o1", role = "OWNER",
                    capabilities = ORNEK_YETENEKLER,
                )
                organizationName = "Örnek Diş Kliniği"
                isLoggedIn = true
            }
        }
    }
    val data = remember {
        DataManager().apply {
            appointments = MockData.appointments
            doctors = MockData.doctors
            stockItems = MockData.stockItems
            hasLoadedStock = true
        }
    }
    DentifayTheme {
        CompositionLocalProvider(
            LocalAuthManager provides auth,
            LocalDataManager provides data,
            LocalPencere provides pencere,
            LocalAyarlariAc provides {},
        ) {
            Box(Modifier.fillMaxSize().background(DS.Colors.background)) { content() }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun ContentViewPreview() {
    PreviewEnv { ContentView() }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun ContentViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { ContentView() }
}
