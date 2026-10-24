package com.dentifay.app.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.APINotification
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.Yetki
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTileSize
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.PingDot
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Bildirim kutusu — web'deki zil menüsüyle aynı veri (`/api/bildirimler`, sözleşme §5): TEK liste (`kanal` ayrımı kalktı),
 * `{unread}` sayacı, gövdesiz toplu okundu, satıra dokununca tek okundu. Başlık/gövde `BildirimMetni` ile web'in kuralından
 * kurulur; tanınmayan tür ÇİZİLMEZ.
 * Swift `NotificationsStore.shared` → hem `NotificationsStore.x` hem `NotificationsStore.shared.x` çalışır.
 */
object NotificationsStore {
    val shared: NotificationsStore get() = this

    var items by mutableStateOf<List<APINotification>>(emptyList())
        private set
    /** Bütün zillerin TEK sayacı (sunucunun `unread`i). */
    var unread by mutableIntStateOf(0)
        private set
    /** `unread` ile aynı (ana ekran rozeti ve eski çağıranlar için). */
    val unreadTotal: Int get() = unread
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var sayfa by mutableIntStateOf(1)
        private set
    var sayfaSayisi by mutableIntStateOf(1)
        private set
    val dahaVar: Boolean get() = sayfa < sayfaSayisi

    /** Çizilecek satırlar — kayıt defterinde olmayan tür gösterilmez (sayaç yine sunucunundur). */
    val gorunenler: List<APINotification> get() = items.filter { BildirimMetni.gorunur(it) }

    /**
     * Ekrandan bağımsız işler (Swift `Task { }`): okundu işaretleri, "Daha fazla", kapanışta sayaç — sayfa kapansa da
     * yarıda kesilmez.
     */
    internal val scope = MainScope()

    /** Yalnız önizleme örneği yüklüyken ağ isteği yapılmaz. */
    private var ornekVeri = false

    suspend fun refreshCounts() {
        if (ornekVeri) return
        val c = try {
            ApiClient.fetchNotificationCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        unread = c.unread
    }

    suspend fun load() {
        if (ornekVeri) return
        isLoading = true
        try {
            val list = ApiClient.fetchNotifications(page = 1)
            items = list.items
            sayfa = list.pagination?.page ?: 1
            sayfaSayisi = list.pagination?.pages ?: 1
            list.unread?.let { unread = it }
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kodsuz 403: liste boş kalır, kırmızı kart yok (M-E7.5).
            errorMessage = if (Yetki.reddiMi(e)) null else "Bildirimler yüklenemedi."
        } finally {
            isLoading = false
        }
    }

    /** "Daha fazla" — sonraki sayfayı EKLER. */
    suspend fun loadMore() {
        if (!dahaVar || isLoading || ornekVeri) return
        isLoading = true
        try {
            val list = ApiClient.fetchNotifications(page = sayfa + 1)
            val mevcut = items.mapTo(HashSet()) { it.id }
            items = items + list.items.filter { it.id !in mevcut }
            sayfa = list.pagination?.page ?: (sayfa + 1)
            sayfaSayisi = list.pagination?.pages ?: sayfaSayisi
            list.unread?.let { unread = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kodsuz 403: liste boş kalır, kırmızı kart yok (M-E7.5).
            errorMessage = if (Yetki.reddiMi(e)) null else "Bildirimler yüklenemedi."
        } finally {
            isLoading = false
        }
    }

    suspend fun markAllRead() {
        if (ornekVeri) {
            unread = 0
            return
        }
        try {
            ApiClient.markAllNotificationsRead()
            unread = 0
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = if (Yetki.reddiMi(e)) Yetki.EYLEM_YETKISIZ_METNI else "Okundu işareti kaydedilemedi."
        }
    }

    /**
     * Satıra dokunuş: okunmamışsa tek okundu ucu; sayaç yerelde 1 düşer, sonra sunucudan tazelenir.
     * Hedef ekrana gitme (§8) bu eklemede YOK (iOS ile aynı).
     */
    suspend fun markRead(n: APINotification) {
        val i = items.indexOfFirst { it.id == n.id }
        if (i < 0 || !items[i].isUnread) return
        val simdi = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        items = items.toMutableList().also { it[i] = it[i].copy(readAt = simdi) }
        unread = maxOf(0, unread - 1)
        if (ornekVeri) return
        try {
            ApiClient.markNotificationRead(n.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiException)?.status == 404) {
                items = items.filterNot { it.id == n.id }          // 404 sessiz: satır düşer
            } else {
                errorMessage = if (Yetki.reddiMi(e)) Yetki.EYLEM_YETKISIZ_METNI else "Okundu işareti kaydedilemedi."
            }
        }
        refreshCounts()
    }

    /** Yalnız @Preview: örnek liste (ağ isteği yapılmaz). */
    internal fun onizlemeYukle(liste: List<APINotification>) {
        ornekVeri = true
        items = liste
        unread = liste.count { it.isUnread }
        sayfa = 1
        sayfaSayisi = 1
        errorMessage = null
    }
}

/**
 * Bildirimler sayfası. Sunan taraf `DfySheet` içinde gösterir (tablette 640 dp; liste en çok 720 dp — okunur satır boyu).
 * Uzun liste tembel (`LazyColumn`, kararlı anahtarlar).
 */
@Composable
fun NotificationsView(onDismiss: () -> Unit) {
    val store = NotificationsStore.shared
    val scope = rememberCoroutineScope()
    var yenileniyor by remember { mutableStateOf(false) }
    val liste by remember { derivedStateOf { store.gorunenler } }

    LaunchedEffect(Unit) { store.load() }
    DisposableEffect(Unit) {
        onDispose { store.scope.launch { store.refreshCounts() } }
    }

    Box(Modifier.fillMaxSize()) {
        WebBackdrop()
        Column(Modifier.fillMaxSize()) {
            // Gezinme çubuğu (inline başlık): solda "Tümünü okundu say", sağda "Kapat".
            UstCubuk(
                baslik = "Bildirimler",
                modifier = Modifier.background(DS.Colors.background),
                sol = {
                    if (store.unread > 0) {
                        MetinDugmesi("Tümünü okundu say", sys(14, FontWeight.Medium), DS.Colors.cyan400) {
                            Haptics.light()
                            store.scope.launch { store.markAllRead() }
                        }
                    }
                },
                sag = { MetinDugmesi("Kapat", sys(15, FontWeight.SemiBold), DS.Colors.onSurface, onClick = onDismiss) },
            )

            PullToRefreshBox(
                isRefreshing = yenileniyor,
                onRefresh = {
                    scope.launch {
                        yenileniyor = true
                        store.load()
                        yenileniyor = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                val hata = store.errorMessage
                LazyColumn(
                    Modifier.fillMaxHeight().icerikGenisligi(720.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        store.isLoading && liste.isEmpty() -> item(key = "yukleniyor", contentType = "durum") {
                            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = DS.Colors.cyan400, strokeWidth = 2.dp)
                            }
                        }
                        hata != null && liste.isEmpty() -> item(key = "hata", contentType = "durum") {
                            DurumGorunumu("exclamationmark.triangle", hata, tekrar = true) {
                                store.scope.launch { store.load() }
                            }
                        }
                        liste.isEmpty() -> item(key = "bos", contentType = "durum") {
                            DurumGorunumu("bell.slash", "Bildirim yok.", tekrar = false) {}
                        }
                        else -> {
                            items(liste, key = { it.id }, contentType = { "bildirim" }) { bildirim ->
                                BildirimSatiri(bildirim) { store.scope.launch { store.markRead(bildirim) } }
                            }
                            if (store.dahaVar) {
                                item(key = "daha", contentType = "daha") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "Daha fazla",
                                            Modifier
                                                .plainClickable { store.scope.launch { store.loadMore() } }
                                                .padding(vertical = 8.dp),
                                            style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Zil düğmesi + okunmamış rozeti — ana ekran, Analiz Merkezi, Stok, Tedavi Planı ve Ayarlar AYNI bileşeni ve AYNI sayacı
 * (`NotificationsStore.shared.unread`) kullanır (M-E1.6.4; ikinci rozet yazılmaz). Swift `ZilDugmesi(action:)`.
 */
@Composable
fun ZilDugmesi(action: () -> Unit) = ZilDugmesi(Modifier, action)

@Composable
fun ZilDugmesi(modifier: Modifier, action: () -> Unit) {
    val okunmamis = NotificationsStore.shared.unread
    Box(
        modifier
            .size(40.dp)
            .background(Color.White.copy(alpha = 0.04f), CircleShape)
            .border(1.dp, DS.Colors.hairline, CircleShape)
            .plainClickable { Haptics.light(); action() }
            .semantics {
                contentDescription = if (okunmamis > 0) "Bildirimler, $okunmamis okunmamış" else "Bildirimler"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        DfyIcon("bell", size = 17.dp, tint = DS.Colors.metal)
        if (okunmamis > 0) {
            PingDot(DS.Colors.purple500, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp), size = 8.dp)
        }
    }
}

// MARK: - Gezinme çubuğu

/**
 * Inline gezinme çubuğu: başlık ortada; sol/sağ düğmeler genişse başlık aradaki boşluğa kayar, sığmazsa kısalır
 * (iOS gezinme çubuğu davranışı) — dar telefonda ve büyük yazı boyutunda üst üste binme yok.
 */
@Composable
private fun UstCubuk(
    baslik: String,
    modifier: Modifier = Modifier,
    sol: @Composable () -> Unit = {},
    sag: @Composable () -> Unit = {},
) {
    Layout(
        content = {
            Box(contentAlignment = Alignment.CenterStart) { sol() }
            Box(contentAlignment = Alignment.CenterEnd) { sag() }
            Text(
                baslik, style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp),
    ) { olculer, kisit ->
        val w = kisit.maxWidth
        val bosluk = 8.dp.roundToPx()
        val serbest = kisit.copy(minWidth = 0, minHeight = 0)
        val solP = olculer[0].measure(serbest.copy(maxWidth = (w * 0.45f).toInt()))
        val sagP = olculer[1].measure(serbest.copy(maxWidth = (w * 0.35f).toInt()))
        val aralik = (w - solP.width - sagP.width - 2 * bosluk).coerceAtLeast(0)
        val baslikP = olculer[2].measure(serbest.copy(maxWidth = aralik))
        val h = maxOf(kisit.minHeight, solP.height, sagP.height, baslikP.height)
        layout(w, h) {
            solP.placeRelative(0, (h - solP.height) / 2)
            sagP.placeRelative(w - sagP.width, (h - sagP.height) / 2)
            val enAz = solP.width + bosluk
            val enCok = maxOf(enAz, w - sagP.width - bosluk - baslikP.width)
            baslikP.placeRelative(((w - baslikP.width) / 2).coerceIn(enAz, enCok), (h - baslikP.height) / 2)
        }
    }
}

@Composable
private fun MetinDugmesi(metin: String, stil: TextStyle, renk: Color, onClick: () -> Unit) {
    Text(
        metin,
        Modifier.plainClickable(onClick = onClick).padding(vertical = 8.dp),
        style = stil, color = renk, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DurumGorunumu(ikon: String, metin: String, tekrar: Boolean, onTekrar: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DfyIcon(ikon, size = 30.dp, tint = DS.Colors.metal)
        Text(metin, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
        if (tekrar) {
            Text(
                "Tekrar dene", Modifier.plainClickable(onClick = onTekrar).padding(vertical = 4.dp),
                style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400,
            )
        }
    }
}

// MARK: - Satır

@Composable
private fun BildirimSatiri(item: APINotification, onClick: () -> Unit) {
    val stil = bildirimStili(item.type)
    // Metinler satır değişmedikçe yeniden kurulmaz (kaydırmada iş yok).
    val baslik = remember(item) { BildirimMetni.baslik(item) }
    val konu = remember(item) { BildirimMetni.konu(item) }
    val altMetin = remember(item) { BildirimMetni.altMetin(item) }
    val zaman = remember(item.lastEventAt, item.createdAt) { goreliZaman(item) }
    val okunmadi = item.isUnread
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (okunmadi) 1f else 0.85f)
            .glassCard(14.dp)
            .plainClickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StilIkonu(stil)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    baslik,
                    Modifier.weight(1f).alignByBaseline(),
                    style = sys(15, if (okunmadi) FontWeight.SemiBold else FontWeight.Medium),
                    color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(zaman, Modifier.alignByBaseline(), style = sys(12), color = DS.Colors.outline, maxLines = 1)
            }
            konu?.let {
                Text(
                    it, style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            altMetin?.let { Text(it, style = sys(13), color = DS.Colors.onSurfaceVariant) }
        }

        if (okunmadi) {
            Box(Modifier.padding(top = 6.dp).size(7.dp).background(DS.Colors.cyan400, CircleShape))
        }
    }
}

/** Tür ikonu: web setinde karşılığı olan ad `IconTile` ile; olmayan (iOS'ta SF Symbol kalanlar) Material çizimle. */
@Composable
private fun StilIkonu(stil: BildirimStili) {
    val vektor = stil.vektor
    if (vektor == null) {
        IconTile(icon = stil.ikon, tone = IconTone.Metal, size = IconTileSize.Sm, iconTint = stil.renk)
    } else {
        Box(Modifier.size(IconTileSize.Sm.box), contentAlignment = Alignment.Center) {
            Icon(vektor, contentDescription = null, modifier = Modifier.size(IconTileSize.Sm.icon), tint = stil.renk)
        }
    }
}

private fun goreliZaman(item: APINotification): String {
    val ham = item.lastEventAt ?: item.createdAt ?: return ""
    val tarih = isoCoz(ham) ?: return ""
    val s = maxOf(0L, Duration.between(tarih, Instant.now()).seconds)
    return when {
        s < 60 -> "az önce"
        s < 3600 -> "${s / 60} dk önce"
        s < 86_400 -> "${s / 3600} sa önce"
        else -> "${s / 86_400} gün önce"
    }
}

private fun isoCoz(s: String): Instant? =
    runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

/** Tür ikonları (DESIGN.md metal ton + durum rengi; emoji yok) — web `turler.data.json` ikonlarının karşılığı. */
private class BildirimStili(val ikon: String, val renk: Color, val vektor: ImageVector? = null)

private val STILLER: Map<String, BildirimStili> by lazy {
    mapOf(
        "appointment.created" to BildirimStili("calendar.badge.plus", DS.Colors.cyan400),
        "appointment.arrived" to BildirimStili("person.crop.circle.badge.checkmark", DS.Colors.success),
        // iOS "door.left.hand.open" — web setinde yok.
        "appointment.inTreatment" to BildirimStili("door.left.hand.open", DS.Colors.cyan400, Icons.Outlined.MeetingRoom),
        "appointment.completed" to BildirimStili("rectangle.portrait.and.arrow.right", DS.Colors.metal),
        // iOS "calendar.badge.minus" — web setinde yok.
        "appointment.cancelled" to BildirimStili("calendar.badge.minus", DS.Colors.warning, Icons.Outlined.EventBusy),
        // iOS "wallet.bifold" → web "Wallet".
        "payment.received" to BildirimStili("Wallet", DS.Colors.success),
        "ai.permissionDenied" to BildirimStili("exclamationmark.shield", DS.Colors.warning),
        "task.assigned" to BildirimStili("checklist", DS.Colors.cyan400),
        "task.unassigned" to BildirimStili("list.clipboard", DS.Colors.cyan400),
        "task.due" to BildirimStili("alarm", DS.Colors.warning),
        "task.completed" to BildirimStili("checkmark.circle", DS.Colors.success),
        "survey.lowScore" to BildirimStili("hand.thumbsdown", DS.Colors.danger),
        // iOS "percent" — web setinde yok.
        "pricing.approvalPending" to BildirimStili("percent", DS.Colors.warning, Icons.Outlined.Percent),
        "pricing.approvalResolved" to BildirimStili("checkmark.seal", DS.Colors.cyan400),
        "xray.planRejected" to BildirimStili("xmark.circle", DS.Colors.danger),
        // iOS "checkmark.shield" → web "ShieldCheck".
        "xray.planApprovalPending" to BildirimStili("ShieldCheck", DS.Colors.warning),
        "xray.planApproved" to BildirimStili("checkmark.circle", DS.Colors.success),
        "credits.low" to BildirimStili("creditcard", DS.Colors.warning),
        "credits.limitReached" to BildirimStili("gauge.with.dots.needle.67percent", DS.Colors.danger),
    )
}

private val VARSAYILAN_STIL by lazy { BildirimStili("bell", DS.Colors.metal) }

private fun bildirimStili(type: String): BildirimStili = STILLER[type] ?: VARSAYILAN_STIL

// MARK: - Önizleme

private fun ornekBildirimler(): List<APINotification> {
    val simdi = Instant.now()
    return listOf(
        APINotification(
            id = "n1", type = "appointment.arrived", params = mapOf("saat" to JsonPrimitive("14:30"), "oda" to JsonPrimitive("Oda 2")),
            patient = APINotification.Patient(name = "Mehmet Kaya"), createdAt = simdi.minusSeconds(240).toString(),
        ),
        APINotification(
            id = "n2", type = "task.assigned", task = APINotification.TaskRef(title = "Laboratuvar ölçüsünü gönder"),
            createdAt = simdi.minusSeconds(7200).toString(),
        ),
        APINotification(
            id = "n3", type = "payment.received", params = mapOf("tutar" to JsonPrimitive(1800), "yontem" to JsonPrimitive("CARD")),
            patient = APINotification.Patient(name = "Ayşe Yılmaz"), count = 2, createdAt = simdi.minusSeconds(9000).toString(),
        ),
        APINotification(
            id = "n4", type = "xray.planApprovalPending", params = mapOf("kalem" to JsonPrimitive(3)), patientMasked = true,
            createdAt = simdi.minusSeconds(20_000).toString(),
        ),
        APINotification(
            id = "n5", type = "credits.limitReached", params = mapOf("kullanilan" to JsonPrimitive(480), "limit" to JsonPrimitive(500)),
            readAt = simdi.toString(), createdAt = simdi.minusSeconds(200_000).toString(),
        ),
        // Tanınmayan tür — çizilmez.
        APINotification(id = "n6", type = "voice.call", createdAt = simdi.toString()),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun NotificationsViewPreview() {
    remember { NotificationsStore.onizlemeYukle(ornekBildirimler()); true }
    PreviewEnv { NotificationsView(onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun NotificationsViewTabletPreview() {
    remember { NotificationsStore.onizlemeYukle(ornekBildirimler()); true }
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { NotificationsView(onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 120, heightDp = 80)
@Composable
private fun ZilDugmesiPreview() {
    remember { NotificationsStore.onizlemeYukle(ornekBildirimler()); true }
    PreviewEnv { Box(Modifier.padding(20.dp)) { ZilDugmesi {} } }
}
