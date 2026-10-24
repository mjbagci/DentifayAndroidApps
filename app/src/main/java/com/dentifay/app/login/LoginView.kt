package com.dentifay.app.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.BuildConfig
import com.dentifay.app.R
import com.dentifay.app.auth.AuthManager
import com.dentifay.app.auth.BiometricAuth
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.AppConfig
import com.dentifay.app.smile.SmileSimulationView
import com.dentifay.app.theme.BrandLockup
import com.dentifay.app.theme.ButtonLabel
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.FullScreenCover
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.PrimaryButton
import com.dentifay.app.theme.SecondaryButton
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.horizontalGradient
import com.dentifay.app.theme.mutedHex
import com.dentifay.app.theme.openInBrowser
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.relativeLinearGradient
import com.dentifay.app.theme.relativeRadialGradient
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.theme.verticalGradient
import com.dentifay.app.trial.GuestContactSheet
import com.dentifay.app.trial.GuestTrialFeature
import com.dentifay.app.trial.GuestTrialStore
import com.dentifay.app.trial.TrialQuotaChip
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.xray.XrayAnalysisView
import com.dentifay.app.xray.XrayAnalysisViewModel
import kotlinx.coroutines.launch

private enum class Field { email, password }

/**
 * Giriş ekranı — web giriş sayfasının (`frontend/src/pages/auth/LoginPage`) mobil karşılığı.
 *
 * Düzen (yukarıdan aşağı, ortalı, en fazla 440 dp genişlik):
 *   1. Marka: küçük logo + "Dentifay" yazısı yan yana
 *   2. Giriş kartı (e-posta/kullanıcı adı, şifre, oturumu açık tut, biyometrik)
 *   3. Ücretsiz deneme: Röntgen Analizi ve Gülüş Simülasyonu (Önce / Sonra) — giriş yapmadan,
 *      IP başına her biri için 5 hak (`/api/public/trial`); hak bitince iletişim sayfası.
 *
 * `screenReady`: splash kapanıp giriş ekranı görünür olduğunda RootView bunu true yapar.
 * Biyometrik otomatik istem ancak bu true olunca tetiklenir.
 */
@Composable
fun LoginView(screenReady: Boolean) {
    val authManager = LocalAuthManager.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberSession by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val canUseBiometrics = remember { BiometricAuth.isAvailable }
    val biometricName = remember { BiometricAuth.displayName }
    val biometricIcon = remember { BiometricAuth.iconName }
    var didAutoPrompt by remember { mutableStateOf(false) }
    var showXray by remember { mutableStateOf(false) }
    var showSmile by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var focusedField by remember { mutableStateOf<Field?>(null) }
    val passwordFocus = remember { FocusRequester() }
    val trial = GuestTrialStore.shared

    /** Kullanıcı daha önce biyometrik girişi etkinleştirdi mi? */
    val biometricEnabled = AuthManager.biometricEnabled
    val canSubmit = email.isNotBlank() && password.isNotEmpty() && !isLoading

    fun clearFocus() {
        focusManager.clearFocus()
        focusedField = null
    }

    /** Biyometrik giriş akışını çalıştırır. */
    suspend fun runBiometricLogin() {
        if (isLoading) return
        clearFocus()
        isLoading = true
        authManager.loginWithBiometrics()
        isLoading = false
    }

    /** Biyometrik otomatik istemi yalnızca giriş ekranı görünürken (screenReady) ve koşullar uygunsa bir kez tetikler. */
    fun maybeAutoPromptBiometrics() {
        if (!screenReady || !AuthManager.biometricEnabled || !canUseBiometrics || didAutoPrompt) return
        didAutoPrompt = true
        scope.launch { runBiometricLogin() }
    }

    fun submit() {
        if (!canSubmit) return
        Haptics.medium()
        clearFocus()
        isLoading = true
        scope.launch {
            authManager.login(email, password, remember = rememberSession)
            // Giriş başarılıysa ve biyometrik henüz açık değilse, giriş sonrası
            // "biyometrik açılsın mı?" teklifini hazırla (ContentView gösterir).
            if (authManager.isLoggedIn) {
                authManager.maybeOfferBiometricEnrollment(email, password)
            } else {
                Haptics.error()
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        // Otomatik istem burada değil; splash kapanıp giriş ekranı görünür olunca (screenReady) devreye girer.
        maybeAutoPromptBiometrics()
        trial.refresh()
    }
    LaunchedEffect(screenReady) { maybeAutoPromptBiometrics() }

    Box(Modifier.fillMaxSize()) {
        LoginBackdrop()

        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val minH = maxHeight
            // Tek ekrana sığacak şekilde ölçülü; daha küçük ekranda ya da klavye açıkken kaydırılabilir.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minH),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 440.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BrandLockup(Modifier.appear(0.05), markSize = 32.dp, titleSize = 22f)

                        LoginCard(
                            modifier = Modifier.appear(0.12),
                            email = email, onEmailChange = { email = it },
                            password = password, onPasswordChange = { password = it },
                            showPassword = showPassword,
                            onTogglePassword = { showPassword = !showPassword; Haptics.selection() },
                            rememberSession = rememberSession,
                            onToggleRemember = { rememberSession = !rememberSession; Haptics.selection() },
                            isLoading = isLoading,
                            canSubmit = canSubmit,
                            focusedField = focusedField,
                            onFocusChange = { field, focused ->
                                if (focused) focusedField = field else if (focusedField == field) focusedField = null
                            },
                            passwordFocus = passwordFocus,
                            showBiometric = biometricEnabled && canUseBiometrics,
                            biometricName = biometricName,
                            biometricIcon = biometricIcon,
                            loginError = authManager.loginError,
                            onBiometric = {
                                Haptics.light()
                                scope.launch { runBiometricLogin() }
                            },
                            onForgot = { openInBrowser(context, AppConfig.webBaseURL + "/login") },
                            onSubmit = { submit() },
                        )

                        TrialSection(
                            Modifier.appear(0.2),
                            limit = trial.limit,
                            onXray = { Haptics.light(); clearFocus(); showXray = true },
                            onSmile = { Haptics.light(); clearFocus(); showSmile = true },
                            onContact = { showContact = true },
                        )

                        Footer(Modifier.appear(0.26)) { showContact = true }
                    }
                }
            }
        }
    }

    FullScreenCover(isPresented = showXray, onDismiss = { showXray = false }) {
        XrayAnalysisView(onDismiss = { showXray = false }, mode = XrayAnalysisViewModel.Mode.guest)
    }
    FullScreenCover(isPresented = showSmile, onDismiss = { showSmile = false }) {
        SmileSimulationView(onDismiss = { showSmile = false }, guest = true)
    }
    DfySheet(isPresented = showContact, onDismiss = { showContact = false }) { dismiss ->
        GuestContactSheet(onDismiss = dismiss)
    }
}

// MARK: - Giriş kartı

@Composable
private fun LoginCard(
    modifier: Modifier,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    rememberSession: Boolean,
    onToggleRemember: () -> Unit,
    isLoading: Boolean,
    canSubmit: Boolean,
    focusedField: Field?,
    onFocusChange: (Field, Boolean) -> Unit,
    passwordFocus: FocusRequester,
    showBiometric: Boolean,
    biometricName: String,
    biometricIcon: String,
    loginError: String?,
    onBiometric: () -> Unit,
    onForgot: () -> Unit,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
            .clip(shape)
            .background(DS.Colors.Login.card)
            .border(
                1.dp,
                relativeLinearGradient(
                    listOf(0f to Color.White.copy(alpha = 0.12f), 0.5f to Color.White.copy(alpha = 0.04f), 1f to DS.Colors.secondaryContainer.copy(alpha = 0.26f)),
                    Offset(0f, 0f), Offset(1f, 1f),
                ),
                shape,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tekrar hoş geldiniz.", style = sys(24, FontWeight.Bold, tracking = -0.5), color = DS.Colors.Login.fg0)
            Text("Klinik paneline ulaşmak için bilgilerinizi girin.", style = sys(13), color = DS.Colors.Login.fg1)
        }

        if (showBiometric) {
            // Biyometrik giriş düğmesi + "veya" ayracı
            SecondaryButton(onClick = onBiometric, enabled = !isLoading, height = 46.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon(biometricIcon, size = 15.dp, tint = DS.Colors.onSurface)
                    ButtonLabel("$biometricName ile giriş yap")
                }
            }
            OrDivider()
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("E-posta veya kullanıcı adı")
            LoginField(icon = "envelope", focused = focusedField == Field.email) {
                LoginTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    placeholder = "hekim@klinik.com veya ad.soyad",
                    modifier = Modifier.weight(1f).onFocusChanged { onFocusChange(Field.email, it.isFocused) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("Şifre")
                Spacer(Modifier.weight(1f))
                val underline = DS.Colors.hairlineFocus
                Text(
                    "ŞİFREMİ UNUTTUM",
                    Modifier
                        .plainClickable(onClick = onForgot)
                        .drawBehind {
                            val y = size.height + 3.dp.toPx()
                            drawRect(underline, topLeft = Offset(0f, y - 0.5.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                        },
                    style = sys(10.5, FontWeight.Medium, tracking = 0.8).copy(fontFamily = FontFamily.Monospace),
                    color = DS.Colors.Login.fg1,
                )
            }
            LoginField(icon = "lock", focused = focusedField == Field.password) {
                LoginTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    placeholder = "Şifreniz",
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(passwordFocus)
                        .onFocusChanged { onFocusChange(Field.password, it.isFocused) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .plainClickable(onClick = onTogglePassword)
                        .semantics { contentDescription = if (showPassword) "Şifreyi gizle" else "Şifreyi göster" },
                    contentAlignment = Alignment.Center,
                ) {
                    DfyIcon(if (showPassword) "eye.slash" else "eye", size = 15.dp, tint = DS.Colors.Login.fg2)
                }
            }
        }

        // Hata kutusu: son hata kapanış animasyonunda da görünür kalsın.
        val lastError = remember { arrayOfNulls<String>(1) }
        if (loginError != null) lastError[0] = loginError
        AnimatedVisibility(
            visible = loginError != null,
            enter = fadeIn(Motion.easeInOut(200)) + expandVertically(Motion.easeInOut(200)),
            exit = fadeOut(Motion.easeInOut(200)) + shrinkVertically(Motion.easeInOut(200)),
        ) {
            val errColor = mutedHex("ff8a8a")
            val boxShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(boxShape)
                    .background(DS.Colors.danger.copy(alpha = 0.07f))
                    .border(1.dp, DS.Colors.danger.copy(alpha = 0.25f), boxShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                DfyIcon("exclamationmark.triangle", size = 13.dp, tint = errColor)
                Text(lastError[0] ?: "", style = sys(13), color = errColor)
            }
        }

        // Oturumu açık tut
        Row(
            Modifier
                .fillMaxWidth()
                .plainClickable(onClick = onToggleRemember)
                .semantics { contentDescription = "Oturumu açık tut" + if (rememberSession) ", seçili" else "" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val boxShape = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .size(18.dp)
                    .clip(boxShape)
                    .background(
                        if (rememberSession) horizontalGradient(DS.Colors.brandMagenta, DS.Colors.brandViolet)
                        else SolidColor(Color.White.copy(alpha = 0.02f))
                    )
                    .border(1.dp, if (rememberSession) Color.Transparent else DS.Colors.hairlineFocus, boxShape),
                contentAlignment = Alignment.Center,
            ) {
                if (rememberSession) DfyIcon("checkmark", size = 10.dp, tint = Color.White)
            }
            Text("Oturumu açık tut", style = sys(14), color = DS.Colors.Login.fg1)
        }

        PrimaryButton(onClick = onSubmit, enabled = canSubmit, height = 48.dp) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                ButtonLabel("Giriş yapılıyor…")
            } else {
                ButtonLabel("Giriş yap")
                Spacer(Modifier.width(8.dp))
                DfyIcon("arrow.right", size = 14.dp, tint = Color.White)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.upperTr(),
        style = sys(10.5, FontWeight.Medium, tracking = 1.2).copy(fontFamily = FontFamily.Monospace),
        color = DS.Colors.Login.fg2,
    )
}

@Composable
private fun OrDivider() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(DS.Colors.hairlineStrong))
        Text("veya", style = sys(12, FontWeight.Medium), color = DS.Colors.Login.fg2)
        Box(Modifier.weight(1f).height(1.dp).background(DS.Colors.hairlineStrong))
    }
}

/** Alan içi metin girişi: 16 boyut, açık metin, camgöbeği imleç, soluk yer tutucu. */
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = sys(16).copy(color = DS.Colors.Login.fg0),
        cursorBrush = SolidColor(DS.Colors.brandCyan),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = sys(16), color = DS.Colors.Login.fg3, maxLines = 1)
                inner()
            }
        },
    )
}

// MARK: - Giriş alanı

/** Web `.login-field`: koyu, gömük alan; odakta mor kenar + camgöbeği ikon (easeInOut 0.2 sn). */
@Composable
private fun LoginField(icon: String, focused: Boolean, content: @Composable RowScope.() -> Unit) {
    val iconColor by animateColorAsState(if (focused) DS.Colors.brandCyan else DS.Colors.Login.fg2, Motion.easeInOut(200), label = "icon")
    val bgAlpha by animateFloatAsState(if (focused) 0.04f else 0.02f, Motion.easeInOut(200), label = "bg")
    val border by animateColorAsState(
        if (focused) DS.Colors.brandViolet.copy(alpha = 0.6f) else DS.Colors.hairlineStrong, Motion.easeInOut(200), label = "border",
    )
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = bgAlpha))
            .border(1.dp, border, shape)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            DfyIcon(icon, size = 15.dp, tint = iconColor)
        }
        content()
    }
}

// MARK: - Ücretsiz deneme

@Composable
private fun TrialSection(modifier: Modifier, limit: Int, onXray: () -> Unit, onSmile: () -> Unit, onContact: () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ÜCRETSİZ DENEYİN", style = sys(11, FontWeight.Bold, tracking = 1.6), color = DS.Colors.Login.fg1, maxLines = 1)
            Text(
                "· giriş yapmadan, her biri $limit hak", style = sys(12), color = DS.Colors.Login.fg2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            TrialToolCard(
                modifier = Modifier.weight(1f),
                feature = GuestTrialFeature.xray,
                title = "Röntgen Analizi",
                detail = "Röntgeni çekin ya da yükleyin; diş diş bulgular.",
                action = "Röntgen yükle",
                icon = "camera.viewfinder",
                preview = { XrayPreviewThumb() },
                onTap = onXray,
                onContact = onContact,
            )
            TrialToolCard(
                modifier = Modifier.weight(1f),
                feature = GuestTrialFeature.smile,
                title = "Gülüş Simülasyonu",
                detail = "Önce / sonra: tedavi sonrası gülüşü gösterin.",
                action = "Fotoğraf çek",
                icon = "camera",
                preview = { SmilePreviewThumb() },
                onTap = onSmile,
                onContact = onContact,
            )
        }
    }
}

@Composable
private fun Footer(modifier: Modifier, onContact: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            buildAnnotatedString {
                append("Daha fazlası için ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bizimle iletişime geçin") }
            },
            Modifier.plainClickable(onClick = onContact),
            style = sys(13), color = DS.Colors.Login.fg1, textAlign = TextAlign.Center,
        )

        if (BuildConfig.DEBUG) {
            AppConfig.debugHostOverride?.let { host ->
                Text(
                    "Geliştirici · sunucu ${host.replace("http://", "")}",
                    style = sys(10, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
                    color = DS.Colors.warning.copy(alpha = 0.8f),
                )
            }
        }
    }
}

// MARK: - Deneme kartı

/** Giriş ekranındaki iki simetrik araç kartı (önizleme · başlık · açıklama · kalan hak · eylem). */
@Composable
private fun TrialToolCard(
    modifier: Modifier,
    feature: GuestTrialFeature,
    title: String,
    detail: String,
    action: String,
    icon: String,
    preview: @Composable () -> Unit,
    onTap: () -> Unit,
    onContact: () -> Unit = {},
) {
    val store = GuestTrialStore.shared
    val exhausted = store.isExhausted(feature)
    val cardShape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .pressable(onClick = if (exhausted) onContact else onTap)
            .semantics { contentDescription = "$title. $detail. Giriş yapmadan ücretsiz dener" }
            .clip(cardShape)
            .background(DS.Colors.Login.card)
            .border(1.dp, DS.Colors.hairlineStrong, cardShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val thumbShape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(thumbShape)
                .border(1.dp, DS.Colors.hairline, thumbShape)
        ) { preview() }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = sys(14.5, FontWeight.SemiBold), color = DS.Colors.Login.fg0, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail, Modifier.fillMaxWidth().heightIn(min = 30.dp),
                style = sys(11.5), color = DS.Colors.Login.fg1, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }

        TrialQuotaChip(feature = feature)

        val actionShape = RoundedCornerShape(10.dp)
        val fg = if (exhausted) DS.Colors.onSurface else Color.White
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(actionShape)
                .background(if (exhausted) SolidColor(Color.White.copy(alpha = 0.05f)) else DS.Gradients.primaryGradient)
                .border(1.dp, if (exhausted) DS.Colors.hairlineStrong else Color.Transparent, actionShape),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DfyIcon(if (exhausted) "bubble.left.and.text.bubble.right" else icon, size = 12.dp, tint = fg)
            Text(if (exhausted) "İletişime geçin" else action, style = sys(13, FontWeight.SemiBold), color = fg, maxLines = 1)
        }
    }
}

/** Röntgen kartı önizlemesi: örnek panoramik + iki bulgu kutusu. */
@Composable
private fun XrayPreviewThumb() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Image(painterResource(R.drawable.xray_sample), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        FindingBox(0.20f, 0.40f, 0.14f, 0.30f, mutedHex("ef4444"), "46", w, h)
        FindingBox(0.62f, 0.36f, 0.14f, 0.26f, mutedHex("f59e0b"), "38", w, h)
    }
}

@Composable
private fun FindingBox(x: Float, y: Float, bw: Float, bh: Float, c: Color, fdi: String, w: Dp, h: Dp) {
    Box(
        Modifier
            .offset(x = w * x, y = h * y)
            .size(width = w * bw, height = h * bh)
    ) {
        Box(Modifier.matchParentSize().border(1.2.dp, c, RoundedCornerShape(2.dp)))
        Text(
            fdi,
            Modifier
                .offset(x = (-2).dp, y = (-6).dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c)
                .padding(horizontal = 2.dp),
            style = sys(7, FontWeight.Bold), color = Color.White,
        )
    }
}

/** Gülüş kartı önizlemesi: önce | sonra yarım görseller. */
@Composable
private fun SmilePreviewThumb() {
    Row(
        Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.4f)),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        SmileHalf(R.drawable.smile_before_thumb, "ÖNCE", Modifier.weight(1f))
        SmileHalf(R.drawable.smile_after_thumb, "SONRA", Modifier.weight(1f))
    }
}

@Composable
private fun SmileHalf(res: Int, label: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Image(painterResource(res), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Text(
            label,
            Modifier
                .padding(5.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            style = sys(7.5, FontWeight.Bold, tracking = 0.8), color = Color.White,
        )
    }
}

// MARK: - Arka plan

/**
 * Web giriş sayfası zemini (statik): koyu mor-siyah, ince ızgara, köşelerde hafif mor ve
 * camgöbeği ışık, sol üstte birkaç ince çapraz ışık çizgisi. Dokunmaları almaz.
 */
@Composable
fun LoginBackdrop() {
    Box(Modifier.fillMaxSize().background(verticalGradient(DS.Colors.Login.bg1, DS.Colors.Login.bg0))) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 44.dp.toPx()
            val gridColor = Color.White.copy(alpha = 0.025f)
            val gridWidth = 0.5.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), gridWidth); x += step }
            var y = 0f
            while (y < size.height) { drawLine(gridColor, Offset(0f, y), Offset(size.width, y), gridWidth); y += step }

            // Çapraz ışık çizgileri (web'deki ışık izlerinin durağan hâli)
            val pt = 1.dp.toPx()
            listOf(-40f, 30f, 90f).forEachIndexed { i, off ->
                val o = off * pt
                val start = Offset(o, size.height * 0.30f + i * 26 * pt)
                val end = Offset(o + size.width * 0.55f, -20 * pt + i * 18 * pt)
                drawLine(
                    Brush.linearGradient(
                        listOf(Color.Transparent, DS.Colors.brandBlue.copy(alpha = 0.18f - i * 0.04f), Color.Transparent),
                        start = Offset(o, size.height * 0.3f), end = Offset(o + size.width * 0.55f, 0f),
                    ),
                    start, end, strokeWidth = 1.2f * pt,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(relativeRadialGradient(listOf(DS.Colors.brandViolet.copy(alpha = 0.22f), Color.Transparent), Offset(0f, 0f), 420.dp)))
        Box(Modifier.fillMaxSize().background(relativeRadialGradient(listOf(DS.Colors.brandCyan.copy(alpha = 0.10f), Color.Transparent), Offset(1f, 1f), 460.dp)))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun LoginViewPreview() {
    PreviewEnv(loggedIn = false) {
        LoginView(screenReady = false)
    }
}
