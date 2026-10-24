package com.dentifay.app.smile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dentifay.app.R
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.APIPatientWithAppointments
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.AppConfig
import com.dentifay.app.credit.SmileCreditsClient
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.lowerTr
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sharedCacheFile
import com.dentifay.app.theme.shareContent
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.verticalGradient
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.time.Instant
import kotlin.math.max

/**
 * Sonucu hastaya gönderir. Görsel tam kare önce/sonra yan yana hazırlanır
 * (`SmileShareComposer`); hasta adı (giriş yapılmışsa Dentifay hasta
 * aramasından, yoksa elle) başlığa yazılır, telefon ve e-posta alıcı olarak
 * önceden dolar. Tek dokunuş: Mesajlar, E-posta, WhatsApp ve diğerleri,
 * Galeriye kaydet. Hasta saklanmasına onay verirse simülasyon Geçmiş'e
 * (ve seçilirse hastanın Dentifay dosyasına) kaydedilir.
 *
 * `historyItem`: Geçmişten açıldıysa mevcut kayıt (yeniden kaydedilmez).
 */
@Composable
fun SmileShareSheet(content: SmileShareContent, historyItem: SmileHistoryItem? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val auth = LocalAuthManager.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var layout by remember { mutableStateOf(SmileShareLayout.full) }
    /** Gönderilecek tam görsel (2400 px). */
    var image by remember { mutableStateOf<Bitmap?>(null) }
    /** Ekrandaki önizleme — tam görselin küçültülmüş kopyası (GPU'ya 2400 px yüklenmesin). */
    var previewImage by remember { mutableStateOf<Bitmap?>(null) }
    var suggestions by remember { mutableStateOf<List<APIPatientWithAppointments>>(emptyList()) }
    /** Öneriden seçilen ad — aynı adla tekrar arama yapılmasın. */
    var pickedName by remember { mutableStateOf<String?>(null) }
    /** Öneriden seçilen hastanın Dentifay kimliği (hasta dosyasına kayıt için). */
    var patientId by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastGen by remember { mutableIntStateOf(0) }
    var saveConsent by remember { mutableStateOf(false) }
    var addToPatientFile by remember { mutableStateOf(true) }
    var savedItem by remember { mutableStateOf<SmileHistoryItem?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var didPrefill by remember { mutableStateOf(false) }

    val isLoggedIn = auth.isLoggedIn
    val firstName = name.split(" ").firstOrNull { it.isNotEmpty() } ?: ""
    val canUploadToPatient = isLoggedIn && patientId != null
    val messageText = run {
        val hello = if (firstName.isEmpty()) "Merhaba" else "Merhaba $firstName"
        "$hello, kliniğimizde hazırladığımız gülüş simülasyonunuz ektedir (${content.designTitle}). Görsel yapay zeka ile hazırlanmış bir simülasyondur; gerçek tedavi sonucu farklılık gösterebilir."
    }
    val fileName = shareFileName(name)

    fun showToast(text: String) {
        toast = text
        val gen = ++toastGen
        scope.launch {
            delay(2400)
            if (toastGen == gen && toast == text) toast = null
        }
    }

    // Önceden doldur (Geçmişten açıldıysa)
    LaunchedEffect(Unit) {
        if (didPrefill) return@LaunchedEffect
        didPrefill = true
        historyItem?.let { item ->
            savedItem = item
            item.patientName?.takeIf { it.isNotEmpty() }?.let { n -> name = n; pickedName = n }
            patientId = item.patientId
        }
    }

    // Görsel: yazarken her harfte yeniden çizmesin
    LaunchedEffect(layout, name) {
        if (image != null) delay(350)
        val c = content; val l = layout; val n = name
        val (rendered, onizleme) = withContext(Dispatchers.Default) {
            val r = SmileShareComposer.render(c, l, n)
            r to r?.let { runCatching { smileDisplayCopy(it, maxPx = 1600) }.getOrNull() }
        }
        image = rendered
        previewImage = onizleme ?: rendered
    }

    // Hasta araması
    LaunchedEffect(name) {
        val q = name.trim()
        if (!isLoggedIn || q.length < 2 || q == pickedName) { suggestions = emptyList(); return@LaunchedEffect }
        delay(300)
        val found = try { ApiClient.searchPatients(q, limit = 5) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        suggestions = found
    }

    fun pick(p: APIPatientWithAppointments) {
        val full = "${p.firstName} ${p.lastName}"
        pickedName = full
        name = full
        patientId = p.id
        phone = p.phone ?: ""
        email = p.email ?: ""
        suggestions = emptyList()
        focusManager.clearFocus()
        Haptics.selection()
    }

    fun onNameChange(new: String) {
        name = new
        // Seçilen hastanın adı değiştirildiyse bağ kopar (yanlış dosyaya kayıt olmasın).
        val picked = pickedName
        if (picked != null && new != picked) { patientId = null; pickedName = null }
    }

    // Galeriye kaydet (Android 9 ve öncesinde yazma izni gerekir).
    fun doSaveToPhotos() {
        val img = image ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveBitmapToGallery(context, img, fileName) }
            if (ok) { Haptics.success(); showToast("Fotoğraflar'a kaydedildi") } else { Haptics.error(); showToast("Kaydedilemedi") }
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSaveToPhotos() else { Haptics.error(); showToast("Fotoğraf izni yok · Ayarlar'dan izin verin") }
    }
    fun saveToPhotos() {
        if (image == null) return
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else doSaveToPhotos()
    }

    fun openMessage() {
        val img = image ?: return
        scope.launch {
            val file = withContext(Dispatchers.IO) { writeShareJpeg(context, img, fileName) }
            if (!launchSms(context, phone, messageText, file)) showToast("Bu cihaz mesaj gönderemiyor")
        }
    }

    fun openMail() {
        val img = image ?: return
        scope.launch {
            val file = withContext(Dispatchers.IO) { writeShareJpeg(context, img, fileName) }
            if (!launchMail(context, email, "Gülüş simülasyonunuz", messageText, file)) {
                showToast("Mail uygulamasında hesap yok · “WhatsApp ve diğer uygulamalar” ile gönderin")
            }
        }
    }

    fun openShare() {
        val img = image ?: return
        scope.launch {
            val file = withContext(Dispatchers.IO) { writeShareJpeg(context, img, fileName) }
            if (file != null) shareContent(context, text = messageText, files = listOf(file), mime = "image/jpeg", subject = "Gülüş simülasyonu")
        }
    }

    /**
     * Ekranda seçili düzendeki görseli (Tam fotoğraf ya da Tam + gülüş detayı) hastanın dosyasına
     * yükler — hastaya gönderilen görselle dosyadaki görsel AYNI olsun. Web'deki Gülüş Tasarımı
     * kartı (Medya › Gülüş kartı) bu görselle aynı şablondadır.
     */
    suspend fun uploadToPatient(item: SmileHistoryItem) {
        val pid = patientId ?: return
        isSaving = true
        try {
            val c = content; val n = name; val l = layout
            val jpeg = withContext(Dispatchers.Default) {
                SmileShareComposer.render(c, l, n)?.let { bmp ->
                    ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }
                }
            }
            if (jpeg == null) { saveError = "Görsel hazırlanamadı."; return }
            try {
                SmileCreditsClient(AppConfig.apiBaseURL).uploadToPatientFile(
                    patientId = pid, jpeg = jpeg, fileName = fileName,
                    note = "Gülüş simülasyonu · ${c.designTitle} (${c.tierTitle}) — yapay zeka simülasyonu",
                )
                SmileHistoryStore.shared.markSavedToPatientFile(item.id, pid, n)
                savedItem = SmileHistoryStore.shared.items.firstOrNull { it.id == item.id } ?: item
                saveError = null
                Haptics.success()
                showToast("Hastanın dosyasına eklendi")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                saveError = "Hasta dosyasına eklenemedi: ${e.message ?: "Bilinmeyen hata"}"
            }
        } finally {
            isSaving = false
        }
    }

    suspend fun save() {
        if (!saveConsent || isSaving) return
        isSaving = true
        saveError = null
        var item: SmileHistoryItem? = null
        try {
            val trimmed = name.trim()
            item = SmileHistoryStore.shared.save(
                before = content.before, after = content.after, designTitle = content.designTitle,
                designSummary = content.designSummary, tierTitle = content.tierTitle,
                patientName = trimmed.ifEmpty { null }, patientId = patientId, zoomRect = content.zoomRect,
            )
            savedItem = item
            Haptics.success()
            showToast("Geçmiş'e kaydedildi")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            saveError = e.message ?: "Simülasyon kaydedilemedi. Cihazda yer olduğundan emin olun."
        } finally {
            isSaving = false
        }
        val saved = item
        if (saved != null && canUploadToPatient && addToPatientFile) uploadToPatient(saved)
    }

    // Önizleme pencereye sığar (Swift `maxHeight: 420`; kısa pencerede pencerenin %45'i).
    val onizlemeMax = min(420.dp, LocalPencere.current.yukseklik * 0.45f)
    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SmileInlineNavBar("Hastaya Gönder", onTrailing = onDismiss)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Önizleme
                Box(
                    Modifier.fillMaxWidth().heightIn(max = onizlemeMax)
                        .semantics { contentDescription = "Gönderilecek önce ve sonra görseli" },
                    contentAlignment = Alignment.Center,
                ) {
                    val img = previewImage
                    if (img != null) {
                        val shape = RoundedCornerShape(16.dp)
                        Image(
                            remember(img) { img.asImageBitmap() }, null,
                            Modifier
                                .aspectRatio(img.width.toFloat() / max(img.height, 1).toFloat())
                                .clip(shape)
                                .border(1.dp, Color.White.copy(alpha = 0.1f), shape),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center,
                        ) { SmileSpinner() }
                    }
                }

                SmileSegmented(
                    options = SmileShareLayout.entries.toList(), selected = layout, onSelect = { layout = it },
                    modifier = Modifier.fillMaxWidth(), label = { it.title },
                )

                // Hasta
                Column(Modifier.animateContentSize(Motion.easeInOut(200)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("HASTA", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (patientId != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                DfyIcon("checkmark.seal.fill", size = 10.dp, tint = DS.Colors.tertiaryContainer)
                                Text("Dentifay hastası", style = sys(10, FontWeight.SemiBold), color = DS.Colors.tertiaryContainer)
                            }
                        }
                    }

                    ShareField(
                        "person.fill", "Ad Soyad (görselin başlığına yazılır)", name, ::onNameChange,
                        KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false),
                    )

                    if (suggestions.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DS.Colors.surfaceContainerLow)) {
                            suggestions.forEachIndexed { i, p ->
                                Row(
                                    Modifier.fillMaxWidth().plainClickable { pick(p) }.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    DfyIcon("person.crop.circle.fill", tint = DS.Colors.cyan400)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            "${p.firstName} ${p.lastName}", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            p.phone ?: p.email ?: "İletişim bilgisi yok", style = sys(11), color = DS.Colors.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    DfyIcon("arrow.up.left", size = 11.dp, tint = DS.Colors.outline)
                                }
                                if (i != suggestions.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
                            }
                        }
                    } else if (isLoggedIn && pickedName == null) {
                        Text("Yazmaya başlayın; Dentifay'daki hastalarınız önerilir.", style = sys(11), color = DS.Colors.outline)
                    }

                    ShareField(
                        "phone.fill", "Telefon (mesaj için)", phone, { phone = it },
                        KeyboardOptions(keyboardType = KeyboardType.Phone, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    ShareField(
                        "envelope.fill", "E-posta", email, { email = it },
                        KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                }

                // Gönderim
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TEK DOKUNUŞLA GÖNDER", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
                    val enabled = image != null
                    Column(Modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ShareAction("Mesaj", phone.ifEmpty { "SMS / MMS" }, "message.fill", hex("30D158"), enabled, Modifier.weight(1f)) { openMessage() }
                            ShareAction("E-posta", email.ifEmpty { "Mail uygulaması" }, "envelope.fill", hex("0A84FF"), enabled, Modifier.weight(1f)) { openMail() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (image != null) {
                                ShareAction("WhatsApp", "ve diğer uygulamalar", "square.and.arrow.up", hex("40CBE0"), enabled, Modifier.weight(1f)) { openShare() }
                            }
                            ShareAction("Fotoğraflara", "Galeriye kaydet", "square.and.arrow.down.fill", hex("FF9F0A"), enabled, Modifier.weight(1f)) { saveToPhotos() }
                            if (image == null) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // Kayıt (Geçmiş + hasta dosyası)
                Column(Modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("KAYIT", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
                    val saved = savedItem
                    if (saved != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            DfyIcon("checkmark.circle.fill", tint = DS.Colors.tertiaryContainer)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Geçmiş'te kayıtlı", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
                                Text(
                                    if (saved.savedToPatientFile) "Hastanın Dentifay dosyasına da eklendi" else "Yalnız bu cihazda",
                                    style = sys(11), color = DS.Colors.onSurfaceVariant,
                                )
                            }
                        }
                        if (!saved.savedToPatientFile && canUploadToPatient) {
                            SaveButton("Hasta dosyasına ekle", "folder.badge.plus", isSaving, enabled = !isSaving) {
                                scope.launch { uploadToPatient(saved) }
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().plainClickable { saveConsent = !saveConsent; Haptics.selection() },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            DfyIcon(
                                if (saveConsent) "checkmark.square.fill" else "square", size = 20.dp,
                                tint = if (saveConsent) DS.Colors.cyan400 else DS.Colors.onSurfaceVariant,
                            )
                            Text(
                                "Hasta, simülasyonun (önce ve sonra fotoğrafları) saklanmasına onay verdi",
                                Modifier.weight(1f),
                                style = sys(12, FontWeight.Medium),
                                color = if (saveConsent) DS.Colors.onSurface else DS.Colors.onSurfaceVariant,
                            )
                        }

                        if (canUploadToPatient) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Hastanın Dentifay dosyasına da ekle", style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface)
                                    Text("Hasta kartında “Önce / Sonra” görsellerinde görünür", style = sys(11), color = DS.Colors.onSurfaceVariant)
                                }
                                Switch(
                                    checked = addToPatientFile, onCheckedChange = { addToPatientFile = it },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = DS.Colors.cyan400, checkedThumbColor = Color.White,
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.16f), uncheckedThumbColor = Color.White,
                                        uncheckedBorderColor = Color.Transparent,
                                    ),
                                )
                            }
                        }

                        SaveButton(
                            if (canUploadToPatient && addToPatientFile) "Geçmişe ve hasta dosyasına kaydet" else "Geçmişe kaydet",
                            "tray.and.arrow.down.fill", isSaving,
                            enabled = saveConsent && !isSaving,
                            modifier = Modifier.alpha(if (saveConsent) 1f else 0.5f),
                        ) { scope.launch { save() } }
                    }

                    saveError?.let { err ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DfyIcon("exclamationmark.triangle.fill", size = 11.dp, tint = DS.Colors.orange400)
                            Text(err, style = sys(11), color = DS.Colors.orange400)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DfyIcon("lock.fill", size = 11.dp, tint = DS.Colors.onSurfaceVariant)
                    Text(
                        "Görsel yalnızca seçtiğiniz uygulamayla hastaya gider. Kaydederseniz cihazdaki Geçmiş'e (ve seçerseniz hastanın Dentifay dosyasına) eklenir.",
                        style = sys(11), color = DS.Colors.onSurfaceVariant,
                    )
                }
            }
        }

        // Bildirim
        var lastToast by remember { mutableStateOf("") }
        toast?.let { lastToast = it }
        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.spring(0.35, 0.85)) { it } + fadeIn(Motion.spring(0.35, 0.85)),
            exit = slideOutVertically(Motion.easeOut(250)) { it } + fadeOut(Motion.easeOut(250)),
        ) {
            Text(
                lastToast,
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                style = sys(13, FontWeight.SemiBold), color = Color.White, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ShareField(icon: String, placeholder: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardOptions) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DS.Colors.surfaceContainerLow)
            .border(1.dp, if (focused) DS.Colors.cyan400.copy(alpha = 0.5f) else DS.Colors.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) { DfyIcon(icon, size = 15.dp, tint = DS.Colors.outline) }
        BasicTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
            singleLine = true,
            keyboardOptions = keyboard,
            textStyle = sys(17).copy(color = DS.Colors.onSurface),
            cursorBrush = SolidColor(DS.Colors.cyan400),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = sys(17), color = DS.Colors.outline, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ShareAction(title: String, subtitle: String, icon: String, tint: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .pressable(enabled = enabled, onClick = onClick)
            .glassCard(16.dp)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(verticalGradient(tint.copy(red = minOf(1f, tint.red + 0.08f), green = minOf(1f, tint.green + 0.08f), blue = minOf(1f, tint.blue + 0.08f)), tint)),
            contentAlignment = Alignment.Center,
        ) { DfyIcon(icon, size = 16.dp, tint = Color.White) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SaveButton(title: String, icon: String, isSaving: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .height(46.dp)
            .clip(CircleShape)
            .background(DS.Gradients.primaryGradient),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSaving) SmileSpinner(color = Color.White, size = 16.dp) else DfyIcon(icon, size = 14.dp, tint = Color.White)
        Text(
            title, Modifier.weight(1f, fill = false),
            style = sys(14, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Yardımcılar

/** "gulus-simulasyonu-ayse-demir" (Türkçe karakterler sadeleşir). */
private fun shareFileName(name: String): String {
    val lowered = name.lowerTr().replace("ı", "i")
    val folded = Normalizer.normalize(lowered, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    val slug = folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("-")
    return if (slug.isEmpty()) "gulus-simulasyonu" else "gulus-simulasyonu-$slug"
}

/** Paylaşım için JPEG (FileProvider `cache/shared/`). */
private fun writeShareJpeg(context: Context, image: Bitmap, fileName: String): File? = runCatching {
    val file = sharedCacheFile(context, "$fileName.jpg")
    file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    file
}.getOrNull()

private fun shareUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

/** Mesajlar (SMS/MMS) — alıcı, metin ve görsel önceden hazır; hekim yalnızca "Gönder"e basar. */
private fun launchSms(context: Context, phone: String, body: String, file: File?): Boolean {
    val smsPackage = Telephony.Sms.getDefaultSmsPackage(context)
    val intent = if (file != null) {
        val uri = shareUri(context, file)
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            putExtra("sms_body", body)
            putExtra(Intent.EXTRA_TEXT, body)
            if (phone.isNotEmpty()) putExtra("address", phone)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            smsPackage?.let { setPackage(it) }
        }
    } else {
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", body)
    }
    if (smsPackage == null && file != null) return false
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Mail — alıcı, konu, metin ve görsel ek önceden hazır. */
private fun launchMail(context: Context, email: String, subject: String, body: String, file: File?): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (file != null) "image/jpeg" else "text/plain"
        if (email.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
        if (file != null) {
            val uri = shareUri(context, file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Yalnız e-posta uygulamaları
        selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Galeriye (MediaStore → Pictures/Dentifay) JPEG olarak kaydeder. */
private fun saveBitmapToGallery(context: Context, image: Bitmap, fileName: String): Boolean = runCatching {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName-${Instant.now().epochSecond}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Dentifay")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
    val ok = resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: false
    if (Build.VERSION.SDK_INT >= 29) {
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
    if (!ok) resolver.delete(uri, null, null)
    ok
}.getOrDefault(false)

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun SmileShareSheetPreview() {
    PreviewEnv {
        val before = rememberSampleBitmap(R.drawable.smile_before)
        val after = rememberSampleBitmap(R.drawable.smile_after)
        val content = remember(before, after) {
            SmileShareContent(
                before = before, after = after, zoomRect = null,
                designTitle = "Doğal Beyaz", designSummary = "Beyazlatma · A1", tierTitle = "Orta",
                createdAt = Instant.now(),
            )
        }
        SmileShareSheet(content = content, onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 640, heightDp = 800)
@Composable
private fun SmileShareSheetTabletPreview() {
    // Sayfa tablette 640 dp genişlikte ortalanır (DfySheet).
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        val before = rememberSampleBitmap(R.drawable.smile_before)
        val after = rememberSampleBitmap(R.drawable.smile_after)
        val content = remember(before, after) {
            SmileShareContent(
                before = before, after = after, zoomRect = null,
                designTitle = "Doğal Beyaz", designSummary = "Beyazlatma · A1", tierTitle = "Orta",
                createdAt = Instant.now(),
            )
        }
        SmileShareSheet(content = content, onDismiss = {})
    }
}
