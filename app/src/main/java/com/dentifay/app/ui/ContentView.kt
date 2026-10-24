package com.dentifay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.agent.AgentView
import com.dentifay.app.aihub.AIHubView
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.BiometricAuth
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.data.Appointment
import com.dentifay.app.data.DataManager
import com.dentifay.app.data.LocalDataManager
import com.dentifay.app.data.MockData
import com.dentifay.app.home.AppointmentDetailSheet
import com.dentifay.app.home.HomeView
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
import com.dentifay.app.theme.openInBrowser
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.treatment.TreatmentPlanView
import com.dentifay.app.xray.XrayAnalysisViewModel
import com.dentifay.app.xray.XrayAnalysisView

/** Ana sekme kabuğu: içerik + alt gezinme + sayfalar (sheet) ve tam ekran akışlar. */
@Composable
fun ContentView() {
    val authManager = LocalAuthManager.current
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(TabItem.home) }
    var showQuickAdd by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var selectedAppointment by remember { mutableStateOf<Appointment?>(null) }
    var isDetailPresented by remember { mutableStateOf(false) }
    // Hızlı işlem: sayfa kapanırken seçilen işlem bekletilir, kapanınca açılır.
    var pendingQuickAction by remember { mutableStateOf<QuickAction?>(null) }
    var quickAction by remember { mutableStateOf<QuickAction?>(null) }

    LaunchedEffect(selectedAppointment) { isDetailPresented = selectedAppointment != null }
    val blur by animateDpAsState(if (isDetailPresented) 2.dp else 0.dp, Motion.easeOut(250), label = "detailBlur")

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(DS.Colors.background).blur(blur)) {
            Box(Modifier.fillMaxSize().padding(bottom = 64.dp)) {
                when (selectedTab) {
                    TabItem.home, TabItem.quickAdd -> HomeView(
                        onShowNotifications = { showNotifications = true },
                        selectedAppointment = selectedAppointment,
                        onSelectAppointment = { selectedAppointment = it },
                    )
                    TabItem.stock -> StockView(onShowNotifications = { showNotifications = true })
                    TabItem.ai -> AIHubView(onShowNotifications = { showNotifications = true })
                    TabItem.settings -> SettingsView(onShowNotifications = { showNotifications = true })
                }
            }
            BottomNavBar(selectedTab, { selectedTab = it }, Modifier.align(Alignment.BottomCenter), onQuickAddTap = { showQuickAdd = true })
            // Kaydırılan içerik durum çubuğunun altına girmesin.
            StatusBarScrim()
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

    // Randevu ayrıntısı: %78 yükseklik, tutamaçsız.
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
                if (action == QuickAction.appointment) openInBrowser(context, AppConfig.webBaseURL + "/appointments")
                else quickAction = action
            }
        },
    ) { dismiss ->
        QuickAddSheet(onSelect = { pendingQuickAction = it }, onDismiss = dismiss)
    }

    DfySheet(isPresented = showNotifications, onDismiss = { showNotifications = false }) { dismiss ->
        NotificationsView(onDismiss = dismiss)
    }

    FullScreenCover(isPresented = quickAction != null, onDismiss = { quickAction = null }) {
        quickAction?.let { QuickActionScreen(it) { quickAction = null } }
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

/**
 * @Preview ortamı: yöneticiler MockData ile dolu, koyu tema. Tüm ekran önizlemeleri bunu kullanır.
 * `loggedIn = true` → AuthManager örnek kullanıcıyla.
 */
@Composable
fun PreviewEnv(loggedIn: Boolean = true, content: @Composable () -> Unit) {
    val auth = remember {
        AuthManager().apply {
            if (loggedIn) {
                currentUser = com.dentifay.app.core.APIUser(
                    id = "u1", email = "ayse@klinik.com", name = "Dt. Ayşe Demir", organizationId = "o1", role = "OWNER",
                    capabilities = listOf("fin.credits.view", "fin.credits", "fin.credits.limits"),
                )
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
        CompositionLocalProvider(LocalAuthManager provides auth, LocalDataManager provides data) {
            Box(Modifier.fillMaxSize().background(DS.Colors.background)) { content() }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun ContentViewPreview() {
    PreviewEnv { ContentView() }
}
