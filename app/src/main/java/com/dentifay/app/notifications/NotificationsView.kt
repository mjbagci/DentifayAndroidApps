package com.dentifay.app.notifications

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.APINotification
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.Unvan
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTileSize
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PreviewEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Bildirim kutusu — web'deki zil menüsüyle aynı veri (`/api/bildirimler`): "Bildirimler"
 * (INBOX) ve "Anlık uyarılar" (ALERT). Başlık ve gövde türden kurulur (web `notifications.types`).
 * Swift `NotificationsStore.shared` → hem `NotificationsStore.x` hem `NotificationsStore.shared.x` çalışır.
 */
object NotificationsStore {
    val shared: NotificationsStore get() = this

    enum class Channel(val rawValue: String) {
        inbox("INBOX"), alert("ALERT");

        val id: String get() = rawValue
        val title: String get() = if (this == inbox) "Bildirimler" else "Anlık uyarılar"
        val emptyText: String get() = if (this == inbox) "Bildirim yok." else "Anlık uyarı yok."
    }

    var items by mutableStateOf<Map<Channel, List<APINotification>>>(emptyMap())
        private set
    var unread by mutableStateOf<Map<Channel, Int>>(emptyMap())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Ekran kapanırken sayaç tazelemesi gibi ekrandan bağımsız işler. */
    internal val scope = MainScope()

    val unreadTotal: Int get() = unread.values.sum()

    suspend fun refreshCounts() {
        val c = try { ApiClient.fetchNotificationCount() } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        unread = unread + (Channel.inbox to c.inbox) + (Channel.alert to c.alert)
    }

    suspend fun load(channel: Channel) {
        isLoading = true
        try {
            val list = ApiClient.fetchNotifications(channel.rawValue)
            items = items + (channel to list.items)
            list.unread?.let { u -> unread = unread + (channel to u) }
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Bildirimler yüklenemedi."
        } finally {
            isLoading = false
        }
    }

    suspend fun markAllRead(channel: Channel) {
        try {
            ApiClient.markNotificationsRead(channel.rawValue)
            unread = unread + (channel to 0)
            load(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Okundu işareti kaydedilemedi."
        }
    }

    /** Yalnız önizleme için örnek veri. */
    internal fun seedPreview(list: List<APINotification>) {
        items = mapOf(Channel.inbox to list)
        unread = mapOf(Channel.inbox to list.count { it.isUnread }, Channel.alert to 1)
    }
}

/** Bildirimler sayfası. Sunan taraf `DfySheet` içinde gösterir. */
@Composable
fun NotificationsView(onDismiss: () -> Unit) {
    val store = NotificationsStore.shared
    var channel by remember { mutableStateOf(NotificationsStore.Channel.inbox) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(channel) { store.load(channel) }
    DisposableEffect(Unit) {
        onDispose { store.scope.launch { store.refreshCounts() } }
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(Modifier.fillMaxSize()) {
            // Gezinme çubuğu (inline başlık): solda "Tümünü okundu say", sağda "Kapat".
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(DS.Colors.background)
                    .padding(horizontal = 16.dp),
            ) {
                if ((store.unread[channel] ?: 0) > 0) {
                    Text(
                        "Tümünü okundu say",
                        Modifier
                            .align(Alignment.CenterStart)
                            .plainClickable {
                                Haptics.light()
                                scope.launch { store.markAllRead(channel) }
                            },
                        style = sys(14, FontWeight.Medium), color = DS.Colors.cyan400,
                    )
                }
                Text("Bildirimler", Modifier.align(Alignment.Center), style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
                Text(
                    "Kapat",
                    Modifier.align(Alignment.CenterEnd).plainClickable(onClick = onDismiss),
                    style = sys(15, FontWeight.SemiBold), color = DS.Colors.onSurface,
                )
            }

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        store.load(channel)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChannelPicker(channel, store.unread, Modifier.padding(bottom = 6.dp)) { c ->
                        Haptics.selection()
                        channel = c
                    }

                    val list = store.items[channel] ?: emptyList()
                    val error = store.errorMessage
                    when {
                        store.isLoading && list.isEmpty() ->
                            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
                            }
                        error != null && list.isEmpty() ->
                            StateView("exclamationmark.triangle", error, retry = true) { scope.launch { store.load(channel) } }
                        list.isEmpty() ->
                            StateView("bell.slash", channel.emptyText, retry = false) {}
                        else -> list.forEach { item -> NotificationRow(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelPicker(
    channel: NotificationsStore.Channel,
    unread: Map<NotificationsStore.Channel, Int>,
    modifier: Modifier = Modifier,
    onSelect: (NotificationsStore.Channel) -> Unit,
) {
    val outer = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(outer)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, DS.Colors.hairline, outer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NotificationsStore.Channel.entries.forEach { c ->
            val on = c == channel
            // Seçili arka plan easeOut 0.18 sn ile geçer.
            val bgAlpha by animateFloatAsState(if (on) 1f else 0f, Motion.easeOut(180), label = "seg")
            val shape = RoundedCornerShape(9.dp)
            Row(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.07f * bgAlpha))
                    .border(1.dp, DS.Colors.hairlineStrong.copy(alpha = DS.Colors.hairlineStrong.alpha * bgAlpha), shape)
                    .plainClickable { onSelect(c) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (on) Color.White else DS.Colors.onSurfaceVariant
                Text(c.title, style = sys(14, if (on) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1)
                val n = unread[c]
                if (n != null && n > 0) {
                    Text(
                        "$n",
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(DS.Colors.cyan400.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = sys(11, FontWeight.Bold).monoDigits(), color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateView(icon: String, text: String, retry: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DfyIcon(icon, size = 30.dp, tint = DS.Colors.metal)
        Text(text, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        if (retry) {
            Text("Tekrar dene", Modifier.plainClickable(onClick = onRetry), style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400)
        }
    }
}

// MARK: - Satır

@Composable
private fun NotificationRow(item: APINotification) {
    val style = NotificationStyle(item.type)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (item.isUnread) 1f else 0.85f)
            .glassCard(14.dp)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconTile(icon = style.icon, tone = IconTone.Metal, size = IconTileSize.Sm, iconTint = style.color)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    notificationTitle(item),
                    Modifier.weight(1f).alignByBaseline(),
                    style = sys(15, if (item.isUnread) FontWeight.SemiBold else FontWeight.Medium),
                    color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(relativeTime(item), Modifier.alignByBaseline(), style = sys(12), color = DS.Colors.outline, maxLines = 1)
            }
            notificationSubject(item)?.let { subject ->
                Text(
                    subject, style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            notificationBody(item)?.let { body ->
                Text(body, style = sys(13), color = DS.Colors.onSurfaceVariant)
            }
        }

        if (item.isUnread) {
            Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(DS.Colors.cyan400))
        }
    }
}

// web `notifications.types.*` metinleri
private fun notificationTitle(item: APINotification): String {
    val base = when (item.type) {
        "appointment.arrived" -> "Hasta geldi"
        "ai.permissionDenied" -> "Yetki dışı asistan sorusu"
        "task.assigned" -> "Size görev atandı"
        "task.unassigned" -> "Sorumlusuz yeni görev"
        "task.due" -> "Görevin vakti geldi"
        "task.completed" -> "Atadığınız görev tamamlandı"
        "survey.lowScore" -> "Düşük anket puanı"
        "credits.low" -> "Kredi bakiyesi azaldı"
        "credits.limitReached" -> "Kredi limiti doldu"
        else -> "Bildirim"
    }
    val n = item.count
    if (n != null && n > 1) return "$base ($n)"
    return base
}

/** Kimin / neyin hakkında (hasta, görev, kişi). */
private fun notificationSubject(item: APINotification): String? {
    if (item.type.startsWith("task.")) {
        val t = item.task?.title
        if (!t.isNullOrEmpty()) return t
    }
    if (item.type == "ai.permissionDenied") return Unvan.dt(item.actorName ?: "Silinmiş kullanıcı")
    if (item.patientMasked == true) return "Randevulu hasta"
    item.patient?.let { p ->
        if (p.archived == true) return "Arşivlenmiş hasta"
        return p.name
    }
    return null
}

private fun notificationBody(item: APINotification): String? {
    fun param(key: String): String? = item.param(key)?.display
    return when (item.type) {
        "appointment.arrived" -> param("saat")?.let { "$it randevusu" }
        "ai.permissionDenied" -> "Yetkisi olmayan bir veri sordu."
        "task.assigned" -> if (item.task == null) "Görev sorumlusu olarak siz atandınız." else null
        "task.unassigned" -> "Bu görevin sorumlusu henüz atanmadı."
        "task.due" -> "Görevin vadesi doldu."
        "task.completed" -> "Görev sorumlusu tarafından tamamlandı."
        "survey.lowScore" -> {
            val q = item.survey?.question
            val score = param("puan")
            if (q != null && score != null) "“$q” sorusuna $score puan" else score?.let { "$it puan verildi" }
        }
        "credits.low" -> param("kalan")?.let { "Kliniğin kullanılabilir kredisi $it krediye düştü." }
        "credits.limitReached" -> {
            val u = param("kullanilan")
            val l = param("limit")
            if (u != null && l != null) "Aylık kredi limitine ulaşıldı ($u / $l kredi); işlem yapılmadı."
            else "Aylık kredi limitine ulaşıldı; işlem yapılmadı."
        }
        else -> null
    }
}

private fun relativeTime(item: APINotification): String {
    val raw = item.lastEventAt ?: item.createdAt ?: return ""
    val date = parseIso(raw) ?: return ""
    val s = maxOf(0L, Duration.between(date, Instant.now()).seconds)
    if (s < 60) return "az önce"
    if (s < 3600) return "${s / 60} dk önce"
    if (s < 86_400) return "${s / 3600} sa önce"
    return "${s / 86_400} gün önce"
}

private fun parseIso(s: String): Instant? =
    runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

private class NotificationStyle(type: String) {
    val icon: String
    val color: Color

    init {
        val (i, c) = when (type) {
            "appointment.arrived" -> "person.crop.circle.badge.checkmark" to DS.Colors.success
            "ai.permissionDenied" -> "exclamationmark.shield" to DS.Colors.warning
            "task.assigned" -> "checklist" to DS.Colors.cyan400
            "task.unassigned" -> "list.clipboard" to DS.Colors.cyan400
            "task.due" -> "alarm" to DS.Colors.warning
            "task.completed" -> "checkmark.circle" to DS.Colors.success
            "survey.lowScore" -> "hand.thumbsdown" to DS.Colors.danger
            "credits.low" -> "creditcard" to DS.Colors.warning
            "credits.limitReached" -> "gauge.with.dots.needle.67percent" to DS.Colors.danger
            else -> "bell" to DS.Colors.metal
        }
        icon = i
        color = c
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun NotificationsViewPreview() {
    val now = Instant.now()
    val sample = listOf(
        APINotification(
            id = "n1", type = "appointment.arrived", params = mapOf("saat" to JsonPrimitive("14:30")),
            patient = APINotification.Patient(name = "Mehmet Kaya"), createdAt = now.minusSeconds(240).toString(),
        ),
        APINotification(
            id = "n2", type = "task.assigned", task = APINotification.TaskRef(title = "Laboratuvar ölçüsünü gönder"),
            createdAt = now.minusSeconds(7200).toString(),
        ),
        APINotification(
            id = "n3", type = "credits.limitReached", params = mapOf("kullanilan" to JsonPrimitive(480), "limit" to JsonPrimitive(500)),
            readAt = now.toString(), createdAt = now.minusSeconds(200_000).toString(),
        ),
    )
    remember { NotificationsStore.seedPreview(sample); true }
    PreviewEnv {
        NotificationsView(onDismiss = {})
    }
}
