package com.dentifay.app.theme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/** Ön plandaki Activity (BiometricPrompt, izin isteği, paylaşım için). MainActivity ayarlar. */
object ActivityHolder {
    private var ref: WeakReference<Activity>? = null
    var current: Activity?
        get() = ref?.get()
        set(value) { ref = value?.let { WeakReference(it) } }
}

/**
 * SwiftUI `.sheet(isPresented:)` karşılığı. `isPresented` false olunca hiç çizilmez;
 * kapatma (aşağı kaydırma / dışarı dokunma) `onDismiss` çağırır.
 * `.presentationDetents([.fraction(x)])` → `heightFraction`; `.large` → null (tam boy).
 */
@Composable
fun DfySheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    containerColor: Color = DS.Colors.surfaceContainer,
    showDragHandle: Boolean = true,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    if (!isPresented) return
    val state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        // iOS sayfası gibi durum çubuğunun altından başlar.
        modifier = Modifier.statusBarsPadding(),
        containerColor = containerColor,
        contentColor = DS.Colors.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = if (showDragHandle) ({ androidx.compose.material3.BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.25f)) }) else null,
    ) {
        val mod = if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier
        androidx.compose.foundation.layout.Column(mod) { content(dismiss) }
    }
}

/** Tam ekran kapakların çizildiği katman (Activity penceresi içinde; klavye/geri/insets doğal çalışır). */
class CoverEntry(
    val state: MutableTransitionState<Boolean>,
    val background: Color,
    val onDismiss: State<() -> Unit>,
    val content: State<@Composable BoxScope.() -> Unit>,
)

val LocalCoverHost = staticCompositionLocalOf<SnapshotStateList<CoverEntry>?> { null }

/** Kök görünümde bir kez: içerik + üstünde açık kapaklar (iç içe açılan üstte). */
@Composable
fun CoverHost(content: @Composable () -> Unit) {
    val entries = remember { mutableStateListOf<CoverEntry>() }
    CompositionLocalProvider(LocalCoverHost provides entries) {
        Box(Modifier.fillMaxSize()) {
            content()
            entries.forEach { entry ->
                key(entry) {
                    AnimatedVisibility(
                        visibleState = entry.state,
                        enter = slideInVertically(Motion.easeOut(380)) { it },
                        exit = slideOutVertically(Motion.easeIn(300)) { it },
                    ) {
                        Box(Modifier.fillMaxSize().background(entry.background).plainClickable { }) {
                            BackHandler { entry.onDismiss.value() }
                            entry.content.value(this)
                        }
                    }
                }
            }
        }
    }
}

/**
 * SwiftUI `.fullScreenCover(isPresented:)` karşılığı: tam ekran, alttan kayarak açılır,
 * kapanırken aşağı kayar. Geri tuşu `onDismiss` çağırır.
 */
@Composable
fun FullScreenCover(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    background: Color = DS.Colors.background,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = LocalCoverHost.current
    val state = remember { MutableTransitionState(false) }
    state.targetState = isPresented
    val dismissState = rememberUpdatedState(onDismiss)
    val contentState = rememberUpdatedState(content)
    if (host == null) {
        // Önizleme vb.: katman yoksa yerinde çiz.
        if (isPresented) Box(Modifier.fillMaxSize().background(background), content = content)
        return
    }
    DisposableEffect(host) {
        val entry = CoverEntry(state, background, dismissState, contentState)
        host.add(entry)
        onDispose { host.remove(entry) }
    }
}

/** SwiftUI `.alert(title, isPresented:) { Button… } message: { … }` karşılığı. */
@Composable
fun DfyAlert(
    isPresented: Boolean,
    title: String,
    message: String? = null,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    if (!isPresented) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DS.Colors.surfaceContainerHigh,
        title = { Text(title, style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface) },
        text = message?.let { { Text(it, style = sys(14), color = DS.Colors.onSurfaceVariant) } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, style = sys(15, FontWeight.SemiBold), color = if (destructive) DS.Colors.danger else DS.Colors.cyan400)
            }
        },
        dismissButton = dismissText?.let { { TextButton(onClick = onDismiss) { Text(it, style = sys(15), color = DS.Colors.onSurfaceVariant) } } },
    )
}

/** `SafariView(url:)` / `UIApplication.open` karşılığı — Custom Tabs (koyu tema). */
fun openInBrowser(context: Context, url: String) {
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(DS.Colors.background.toArgb()).build()
    runCatching {
        CustomTabsIntent.Builder()
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(colors)
            .build()
            .launchUrl(context, Uri.parse(url))
    }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** `tel:` / `mailto:` / `sms:` gibi sistem bağlantıları. */
fun openSystemUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** `UIActivityViewController` / `ShareLink` karşılığı: metin ve/veya dosyalar paylaşılır. */
fun shareContent(context: Context, text: String? = null, files: List<File> = emptyList(), mime: String = "*/*", subject: String? = null) {
    val authority = context.packageName + ".fileprovider"
    val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
    val intent = when {
        uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        uris.size == 1 -> Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        else -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    }.apply {
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/** Paylaşım için önbellekte geçici dosya (FileProvider `cache/shared/`). */
fun sharedCacheFile(context: Context, name: String): File {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    return File(dir, name)
}
