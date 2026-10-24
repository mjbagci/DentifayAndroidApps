package com.dentifay.app.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.CreditRequired
import com.dentifay.app.credit.CreditRequiredCard
import com.dentifay.app.credit.CreditSheetItem
import com.dentifay.app.credit.KrediEkrani
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.DfySheet
import com.dentifay.app.theme.IconTile
import com.dentifay.app.theme.IconTone
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.WebBackdrop
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.monoDigits
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.shareContent
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Dentifay Asistan — sohbet ekranı (tam ekran kapak).
 *
 * Telefon: Swift ile aynı düzen. Tablet / katlanabilir / yatay: mesajlar, öneri rayı ve kompozit en çok
 * `AgentViewDefaults.sohbetSutunu` genişlikte ortalanmış bir sütunda; liste tüm pencere genişliğinde kaydırılır;
 * kompozit klavyenin üstünde kalır (`imePadding`), liste kalan alanda kayar.
 */
@Composable
fun AgentView(onDismiss: () -> Unit) {
    val vm = remember { AgentSessionManager() }
    AgentEkrani(vm, onDismiss)
}

/** Swift `AgentView`ın statik yardımcıları + sütun ölçüsü. */
internal object AgentViewDefaults {
    /** Geniş pencerede sohbet sütununun en büyük genişliği (balonlar ve kompozit hapı bu genişlikte hizalanır). */
    val sohbetSutunu: Dp = 760.dp

    /** Boş sohbetin önerileri — finans çipleri yalnız `fin.view` iken (yalnız GÖRÜNÜM; kapı sunucuda — M-E1.5.9). */
    fun oneriler(finansGorur: Boolean): List<SuggestionRail.Item> {
        val items = mutableListOf<SuggestionRail.Item>()
        if (finansGorur) items += SuggestionRail.Item("chart.line.uptrend.xyaxis", DS.Colors.cyan400, "Bu ayın cirosu")
        items += SuggestionRail.Item("calendar", DS.Colors.tertiaryContainer, "Bugünkü randevular")
        if (finansGorur) {
            items += SuggestionRail.Item("creditcard.trianglebadge.exclamationmark", DS.Colors.orange400, "Vadesi geçmiş ödemeler")
        }
        items += SuggestionRail.Item("shippingbox", DS.Colors.purple500, "Kritik stok")
        return items
    }
}

/** Ekranın gövdesi — `AgentView` ve önizlemeler (örnek oturumla) kullanır. */
@Composable
internal fun AgentEkrani(vm: AgentSessionManager, onDismiss: () -> Unit) {
    val auth = LocalAuthManager.current
    val pencere = LocalPencere.current
    var showThreads by remember { mutableStateOf(false) }
    /** Kredi gerekli (402) → kredi ekranı. */
    var creditSheet by remember { mutableStateOf<CreditSheetItem?>(null) }
    /** "Listede yok" → "Tedavi menüsünden seçin" penceresi. */
    var menuKarti by remember { mutableStateOf<AgentChoiceSet?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm) { vm.onAppear() }
    // Temizlik sunumun kapanmasına bağlı (kapak kalkınca): akan tur durur, ses kapanır.
    DisposableEffect(vm) { onDispose { vm.dispose() } }

    val finansGorur = auth.currentUser?.can("fin.view") == true
    val oneriler = remember(finansGorur) { AgentViewDefaults.oneriler(finansGorur) }

    Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
        WebBackdrop()

        Column(
            Modifier
                .fillMaxSize()
                // Yatayda çentik / yan gezinme çubuğu içeriği kesmesin.
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal))
                .statusBarsPadding()
                .imePadding()
        ) {
            ChatTopBar(
                online = vm.agentOnline,
                kisa = pencere.kisa,
                onHistory = { scope.launch { vm.loadConversations() }; showThreads = true },
                onNewChat = { vm.newChat() },
                onClose = onDismiss,
            )
            if (!vm.agentOnline) OfflineBanner()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessagesList(
                    vm = vm,
                    kisa = pencere.kisa,
                    onShare = { file ->
                        shareContent(context, files = listOf(file), mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    },
                    onListedeYok = { menuKarti = it },
                    onBuyCredits = { creditSheet = CreditSheetItem(reason = it.message) },
                    onTapBackground = { focusManager.clearFocus() },
                )
            }

            // Kompozit liste ile aynı sütunda, altta — içerik kompozitin altına kaymaz, klavye açılınca üstünde kalır.
            KompozerAlani(vm, oneriler)
        }
    }

    DfySheet(isPresented = showThreads, onDismiss = { showThreads = false }, containerColor = DS.Colors.background) { dismiss ->
        SohbetGecmisi(vm, close = dismiss)
    }

    DfySheet(isPresented = creditSheet != null, onDismiss = { creditSheet = null }) { dismiss ->
        KrediEkrani(reason = creditSheet?.reason, onDismiss = dismiss)
    }

    DfySheet(isPresented = menuKarti != null, onDismiss = { menuKarti = null }, containerColor = DS.Colors.background) { dismiss ->
        menuKarti?.let { kart ->
            MenuSecimSheet(
                set = kart,
                onSelect = { oge -> vm.send(oge.name, pick = MenuSecimYaniti.pick(oge, kart)) },
                onDismiss = dismiss,
            )
        }
    }
}

// MARK: - Kompozit alanı (öneri rayı + kompozit)

/** Taslak, saniye sayacı ve akış bayrakları YALNIZ burada okunur — her tuşta/saniyede ekranın geri kalanı yeniden oluşmaz. */
@Composable
private fun KompozerAlani(vm: AgentSessionManager, oneriler: List<SuggestionRail.Item>) {
    val focusManager = LocalFocusManager.current
    val bos by remember(vm) { derivedStateOf { vm.messages.isEmpty() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(
            bos,
            enter = fadeIn(Motion.easeOut(250)) + slideInVertically(Motion.easeOut(250)) { it },
            exit = fadeOut(Motion.easeOut(250)) + slideOutVertically(Motion.easeOut(250)) { it },
        ) {
            SuggestionRail(oneriler, Modifier.icerikGenisligi(AgentViewDefaults.sohbetSutunu + 32.dp)) { vm.sendSuggested(it.text) }
        }
        ChatComposer(
            draft = vm.draft,
            onDraftChange = { vm.draft = it },
            isStreaming = vm.isStreaming,
            isDictating = vm.isListening,
            errorText = vm.composerError,
            elapsedSeconds = vm.elapsedSeconds,
            onSend = { vm.send() },
            onStop = { vm.stop() },
            onToggleDictation = { focusManager.clearFocus(); vm.toggleDictation() },
            onRetry = { vm.retryLast() },
            krediIslemi = "ai_agent:chat",
        )
    }
}

// MARK: - Üst çubuk

@Composable
private fun ChatTopBar(online: Boolean, kisa: Boolean, onHistory: () -> Unit, onNewChat: () -> Unit, onClose: () -> Unit) {
    // Başlık EKRANIN gerçek merkezinde (asimetrik ikonlar başlığı kaydırmasın); sığmazsa ikonlara çarpmadan kayar.
    OrtaBaslikCubugu(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (kisa) 4.dp else 12.dp, bottom = if (kisa) 4.dp else 8.dp),
        sol = { IconButton44("clock.arrow.circlepath", "Sohbet geçmişi", onHistory) },
        orta = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Dentifay Asistan", Modifier.semantics { heading() },
                    style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (online) DS.Colors.tertiaryContainer else DS.Colors.orange400))
                    Text(
                        if (online) "Çevrimiçi" else "Bağlantı yok",
                        style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        },
        sag = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton44("square.and.pencil", "Yeni sohbet", onNewChat)
                IconButton44("xmark", "Kapat", onClose)
            }
        },
    )
}

/**
 * Üç bölmeli üst çubuk: `orta` pencerenin tam ortasına yerleşir; dar pencerede (ya da büyük yazı ölçeğinde) yanlardaki
 * düğmelere çarpacaksa en az kaydırmayla aralarına sığdırılır.
 */
@Composable
private fun OrtaBaslikCubugu(
    modifier: Modifier,
    sol: @Composable () -> Unit,
    orta: @Composable () -> Unit,
    sag: @Composable () -> Unit,
) {
    Layout(content = { sol(); orta(); sag() }, modifier = modifier) { olculer, kisit ->
        val serbest = kisit.copy(minWidth = 0, minHeight = 0)
        val solY = olculer[0].measure(serbest)
        val sagY = olculer[2].measure(serbest)
        val bosluk = 8.dp.roundToPx()
        val genislik = kisit.maxWidth
        val ortaMax = (genislik - solY.width - sagY.width - 2 * bosluk).coerceAtLeast(0)
        val ortaY = olculer[1].measure(Constraints(maxWidth = ortaMax))
        val yukseklik = maxOf(solY.height, sagY.height, ortaY.height).coerceIn(kisit.minHeight, kisit.maxHeight)
        layout(genislik, yukseklik) {
            solY.place(0, (yukseklik - solY.height) / 2)
            sagY.place(genislik - sagY.width, (yukseklik - sagY.height) / 2)
            val enAz = solY.width + bosluk
            val enCok = (genislik - sagY.width - bosluk - ortaY.width).coerceAtLeast(enAz)
            val x = ((genislik - ortaY.width) / 2).coerceIn(enAz, enCok)
            ortaY.place(x, (yukseklik - ortaY.height) / 2)
        }
    }
}

@Composable
private fun IconButton44(name: String, label: String, action: () -> Unit) {
    // 44 dp dokunma alanı, 40 dp daire zemin.
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .plainClickable(onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
        DfyIcon(name, size = 15.dp, tint = DS.Colors.onSurface)
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().background(DS.Colors.orange400.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("exclamationmark.triangle.fill", size = 12.dp, tint = DS.Colors.orange400)
        Text(
            "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edip yeniden deneyin.",
            Modifier.weight(1f, fill = false),
            style = sys(12, FontWeight.Medium), color = DS.Colors.orange400,
        )
    }
}

// MARK: - Mesaj listesi

@Composable
private fun MessagesList(
    vm: AgentSessionManager,
    kisa: Boolean,
    onShare: (File) -> Unit,
    onListedeYok: (AgentChoiceSet) -> Unit,
    onBuyCredits: (CreditRequired) -> Unit,
    onTapBackground: () -> Unit,
) {
    val messages = vm.messages
    if (messages.isEmpty()) {
        EmptyState(kisa)
        return
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    /** Sona yapışık mı — kullanıcı yukarı kaydırınca akış onu aşağı çekmez; sona dönünce yeniden yapışır. */
    var takip by remember { mutableStateOf(true) }

    // Kaydırınca klavye kapanır (`.scrollDismissesKeyboard(.interactively)`); sürükleme bitince sondaysa takip döner.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> {
                    takip = false
                    focusManager.clearFocus()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> takip = !listState.canScrollForward
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { ileri -> if (!ileri) takip = true }
    }
    // Yeni mesaj → sona animasyonlu kay; akan içerik / pencere boyu değişimi → takipteyse sona yapış.
    LaunchedEffect(listState) {
        var oncekiSayi = -1
        snapshotFlow { Triple(vm.messages.size, vm.messages.lastOrNull(), listState.layoutInfo.viewportSize.height) }
            .collect { (sayi, _, _) ->
                if (sayi == 0) return@collect
                if (sayi != oncekiSayi) {
                    val ilk = oncekiSayi < 0
                    oncekiSayi = sayi
                    takip = true
                    guvenliKaydir { if (ilk) listState.scrollToItem(sayi) else listState.animateScrollToItem(sayi) }
                } else if (takip) {
                    guvenliKaydir { listState.scrollToItem(sayi) }
                }
            }
    }

    val sonId = messages.last().id
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Liste tüm genişlikte kayar; içerik sütunu ortada (telefonda 16 dp kenar boşluğu).
        val yatay = max(16.dp, (maxWidth - AgentViewDefaults.sohbetSutunu) / 2)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTapBackground() } },
            contentPadding = PaddingValues(horizontal = yatay, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(messages, key = { it.id }, contentType = { it.role }) { msg ->
                MessageBubble(
                    message = msg,
                    vm = vm,
                    isLast = msg.id == sonId,
                    onShare = onShare,
                    onListedeYok = onListedeYok,
                    onBuyCredits = onBuyCredits,
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.spring(0.4, 0.82),
                        placementSpec = Motion.spring(0.4, 0.82),
                        fadeOutSpec = Motion.easeOut(200),
                    ),
                )
            }
            item(key = "BOTTOM", contentType = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
    }
}

/** Kaydırma kullanıcı dokunuşuyla kesilirse (öncelik) eşyordam sürer; yalnız kendisi iptal edildiyse durur. */
private suspend fun guvenliKaydir(blok: suspend () -> Unit) {
    try {
        blok()
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
    }
}

@Composable
private fun EmptyState(kisa: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Kısa pencerede (yatay / bölünmüş ekran) sığmazsa kayar; sığarsa ortada durur.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp).appear(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (kisa) 10.dp else 16.dp),
            ) {
                // Web IconTile tone="brand".
                IconTile("bubble.left.and.text.bubble.right", tone = IconTone.Brand, customSize = if (kisa) 40.dp else 52.dp)
                Text(
                    "Bir soruyla başlayın", Modifier.semantics { heading() },
                    style = sys(if (kisa) 20 else 24, FontWeight.Bold, tracking = -0.4), color = Color.White, textAlign = TextAlign.Center,
                )
                Text(
                    "Klinik verileriniz hakkında soru sorun; yanıtı tablo, grafik ya da Excel dosyası olarak alın.",
                    style = sys(14), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// MARK: - Sohbetler sayfası

@Composable
private fun SohbetGecmisi(vm: AgentSessionManager, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val conversations = vm.conversations
    Column(Modifier.fillMaxWidth().icerikGenisligi(640.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        // Gezinme çubuğu: ortada başlık, sağda "Kapat".
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                "Sohbetler", Modifier.padding(horizontal = 64.dp).semantics { heading() },
                style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1,
            )
            Text(
                "Kapat",
                Modifier
                    .align(Alignment.CenterEnd)
                    .semantics { role = Role.Button }
                    .plainClickable(onClick = close),
                style = sys(17), color = DS.Colors.cyan400,
            )
        }
        LazyColumn(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DS.Colors.surfaceContainer)
        ) {
            item(key = "yeni", contentType = "eylem") {
                ThreadRow(onClick = { vm.newChat(); close() }) {
                    DfyLabel("Yeni Sohbet", "square.and.pencil", sys(17), DS.Colors.cyan400)
                }
            }
            if (vm.sohbetListeHatasi && conversations.isEmpty()) {
                // Ek MO.18c — hata, boş listeyle aynı görünmez.
                item(key = "hata", contentType = "hata") {
                    Column {
                        Ayrac()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Sohbetler yüklenemedi.", style = sys(15), color = DS.Colors.onSurface)
                            Text(
                                "Tekrar dene",
                                Modifier.semantics { role = Role.Button }.plainClickable { scope.launch { vm.loadConversations() } },
                                style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400,
                            )
                        }
                    }
                }
            }
            items(conversations, key = { it.id }, contentType = { "sohbet" }) { conv ->
                SohbetSatiri(conv, Modifier.animateItem(), onOpen = { vm.openConversation(conv); close() }, onDelete = { vm.deleteConversation(conv) })
            }
            if (vm.sohbetDahaVar) {
                item(key = "daha", contentType = "eylem") {
                    Column {
                        Ayrac()
                        ThreadRow(onClick = { scope.launch { vm.dahaFazlaSohbet() } }) {
                            Text("Daha fazla sohbet", style = sys(15, FontWeight.Medium), color = DS.Colors.cyan400)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SohbetSatiri(conv: AgentConversation, modifier: Modifier, onOpen: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) {
            onDelete(); true
        } else {
            false
        }
    })
    val tarih = remember(conv.lastMessageAt, conv.createdAt) { (conv.lastMessageAt ?: conv.createdAt)?.let { SohbetTarihi.metin(it) } }
    Column(modifier) {
        Ayrac()
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(Modifier.fillMaxSize().background(DS.Colors.danger).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("Sil", style = sys(15, FontWeight.SemiBold), color = Color.White)
                }
            },
        ) {
            ThreadRow(onClick = onOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        conv.title, Modifier.weight(1f),
                        style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (tarih != null) Text(tarih, style = sys(12), color = DS.Colors.outline, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Ayrac() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
}

@Composable
private fun ThreadRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(DS.Colors.surfaceContainer)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .plainClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** SwiftUI `Label(text, dfy: icon)` — ikon + metin. */
@Composable
internal fun DfyLabel(text: String, icon: String, style: TextStyle, color: Color, iconSize: Dp? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        DfyIcon(icon, size = iconSize ?: style.fontSize.value.dp, tint = color)
        Text(text, style = style, color = color)
    }
}

// MARK: - Mesaj balonu

@Composable
private fun MessageBubble(
    message: AgentMessage,
    vm: AgentSessionManager,
    isLast: Boolean,
    onShare: (File) -> Unit,
    onListedeYok: (AgentChoiceSet) -> Unit,
    onBuyCredits: (CreditRequired) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.role == AgentRole.user) {
        Box(modifier.fillMaxWidth().padding(start = 40.dp), contentAlignment = Alignment.CenterEnd) {
            val shape = RoundedCornerShape(20.dp)
            Text(
                message.content,
                Modifier
                    .clip(shape)
                    .background(DS.Colors.cyan400.copy(alpha = 0.15f))
                    .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.2f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = sys(16), color = DS.Colors.onSurface,
            )
        }
    } else {
        AssistantBubble(message, vm, isLast, onShare, onListedeYok, onBuyCredits, modifier)
    }
}

@Composable
private fun AssistantBubble(
    message: AgentMessage,
    vm: AgentSessionManager,
    isLast: Boolean,
    onShare: (File) -> Unit,
    onListedeYok: (AgentChoiceSet) -> Unit,
    onBuyCredits: (CreditRequired) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.streaming && message.content.isEmpty()) DusunuyorSatiri(message.thinkingText, vm)

        if (message.toolSteps.isNotEmpty()) ToolTrace(message.toolSteps)

        val preview = message.writePreview
        val credit = message.creditRequired
        when {
            preview != null -> {
                // Önizleme: sunucunun tek cümlelik özeti (`write_preview.ozet`) başta, ayrıntılı döküm açılır bölümde.
                val ozet = preview.ozet
                if (!ozet.isNullOrEmpty()) Text(ozet, style = sys(16, FontWeight.Medium), color = DS.Colors.onSurface)
                if (message.content.isNotEmpty()) {
                    Disclosure(label = { Text("İşlem detayı", style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400) }) {
                        MarkdownMessageView(message.content, Modifier.padding(top = 6.dp), textColor = DS.Colors.onSurfaceVariant)
                    }
                }
            }
            credit != null -> {
                // 402: ham hata yerine kredi kartı. "Tekrar dene" yalnız son balonda (eski balondan yanlış metin gitmesin).
                CreditRequiredCard(
                    credit = credit,
                    onBuy = { onBuyCredits(credit) },
                    onRetry = if (isLast && !vm.isStreaming) ({ vm.retryLast() }) else null,
                )
            }
            message.content.isNotEmpty() -> {
                MarkdownMessageView(message.content, textColor = if (message.isError) DS.Colors.error else DS.Colors.onSurface)
                if (message.yenidenGonderilecek != null) {
                    val etkin = !vm.isStreaming
                    Text(
                        "Yeniden gönder",
                        Modifier
                            .alpha(if (etkin) 1f else 0.5f)
                            .semantics { role = Role.Button }
                            .plainClickable(enabled = etkin) { vm.yenidenGonder(message) }
                            .padding(vertical = 4.dp),
                        style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400,
                    )
                }
            }
        }

        message.charts.forEach { spec -> key(spec.id) { AgentChartView(spec) } }

        message.excelFiles.forEach { file -> key(file.id) { ExcelPill(file, vm, onShare) } }

        if (preview != null) WritePreviewCard(preview, vm)

        message.writeResult?.let { WriteResultBadge(it) }

        // 🔴 BELİRSİZLİK SEÇİM KARTLARI — yalnız SON asistan balonunda ve onay beklemiyorken.
        // Soru balonun metnidir; altında kartlar, 0 adayda Ek MO.10b, "Listede yok". Yazarak cevap kompozitten.
        val set = message.choiceSet
        if (isLast && set != null && set.cizilir && preview == null) {
            SecimKarti(
                set = set,
                pending = vm.queuedChoice,
                onSelect = { vm.sendChoice(it) },
                onListedeYok = { onListedeYok(set) },
            )
        }
    }
}

/** "Düşünüyorum…" satırı — saniye sayacı YALNIZ burada okunur. */
@Composable
private fun DusunuyorSatiri(thinkingText: String?, vm: AgentSessionManager) {
    val gecen = vm.elapsedSeconds
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ThinkingDots()
            Text(thinkingText ?: "Düşünüyorum…", Modifier.weight(1f, fill = false), style = sys(13), color = DS.Colors.onSurfaceVariant)
            if (gecen > 0) Text("· ${gecen}s", style = sys(13, FontWeight.Medium).monoDigits(), color = DS.Colors.outline, maxLines = 1)
        }
        if (gecen >= 8) {
            Text("Analitik sorular birkaç dakika sürebilir…", style = sys(11), color = DS.Colors.outline)
        }
    }
}

/** SwiftUI `DisclosureGroup`: etiket + sağda dönen ok, içerik açılıp kapanır. */
@Composable
private fun Disclosure(modifier: Modifier = Modifier, label: @Composable () -> Unit, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val angle = animateFloatAsState(if (expanded) 90f else 0f, Motion.easeInOut(250), label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { role = Role.Button }
                .plainClickable { expanded = !expanded }
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { label() }
            DfyIcon("chevron.right", Modifier.graphicsLayer { rotationZ = angle.value }, size = 13.dp, tint = DS.Colors.cyan400)
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(Motion.easeInOut(250)) + fadeIn(),
            exit = shrinkVertically(Motion.easeInOut(250)) + fadeOut(),
        ) {
            content()
        }
    }
}

@Composable
private fun ToolTrace(steps: List<AgentToolStep>) {
    Disclosure(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).padding(12.dp),
        label = { Text("${steps.size} işlem yapıldı", style = sys(12, FontWeight.SemiBold), color = DS.Colors.cyan400) },
    ) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { step ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DfyIcon(
                        if (step.ok) "checkmark.circle.fill" else "xmark.circle.fill", size = 11.dp,
                        tint = if (step.ok) DS.Colors.tertiaryContainer else DS.Colors.error,
                    )
                    Text(step.summary, style = sys(12), color = DS.Colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ExcelPill(file: AgentExcelArtifact, vm: AgentSessionManager, onShare: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    var uretiliyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    var calisiyor by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { role = Role.Button }
            .plainClickable {
                if (calisiyor) return@plainClickable
                calisiyor = true
                scope.launch {
                    try {
                        val f = vm.excelIndir(file) { uretiliyor = it }
                        hata = null
                        onShare(f)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        hata = AgentSessionManager.errorText(e)
                    } finally {
                        calisiyor = false
                    }
                }
            }
            .glassCard(14.dp)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("tablecells.fill", size = 16.dp, tint = DS.Colors.tertiaryContainer)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.filename, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val h = hata
            when {
                uretiliyor -> Text("Excel yeniden hazırlanıyor…", style = sys(11), color = DS.Colors.cyan400)
                h != null -> Text(h, style = sys(11), color = DS.Colors.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                file.rowCount != null -> Text("${file.rowCount} satır", style = sys(11), color = DS.Colors.onSurfaceVariant)
            }
        }
        if (calisiyor && !uretiliyor) ThinkingDots(dotSize = 5.dp) else DfyIcon("square.and.arrow.down", size = 15.dp, tint = DS.Colors.cyan400)
    }
}

/** Önizlemenin geçerlilik sonu gelince düğmeler kendiliğinden "süresi doldu"ya döner (cihaz saati; karar sunucunun). */
@Composable
private fun rememberSuresiDoldu(preview: AgentWritePreview): Boolean {
    val son = preview.gecerlilikSonu ?: return false
    var doldu by remember(son) { mutableStateOf(OnayIstegi.suresiDoldu(preview, Instant.now())) }
    LaunchedEffect(son) {
        val kalan = Duration.between(Instant.now(), son).toMillis()
        if (kalan > 0) kotlinx.coroutines.delay(kalan)
        doldu = true
    }
    return doldu
}

/** Türkçe onay kartı (M-E1.4.4) — `OnayBilgisi.kur` (Tedavi Planı ile ortak); ham `operation`/`entity` gösterilmez. */
@Composable
private fun WritePreviewCard(preview: AgentWritePreview, vm: AgentSessionManager) {
    val bilgi = remember(preview) { OnayBilgisi.kur(preview) }
    val suresiDoldu = rememberSuresiDoldu(preview)
    val cift = preview.requiresDoubleConfirm
    val accent = if (cift) DS.Colors.error else DS.Colors.orange400
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, accent.copy(alpha = 0.3f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon("exclamationmark.shield.fill", tint = accent)
            Text(if (cift) "Kalıcı İşlem Onayı" else "İşlem Onayı", style = sys(14, FontWeight.Bold), color = DS.Colors.onSurface)
        }
        Text(bilgi.baslik, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        if (bilgi.satirlar.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                bilgi.satirlar.forEach { satir -> Text(satir, style = sys(13), color = DS.Colors.onSurface) }
                bilgi.kalanSatiri?.let { Text(it, style = sys(12), color = DS.Colors.outline) }
            }
        }
        bilgi.klinik?.let { Text(it, style = sys(12), color = DS.Colors.outline) }

        val akiyor = vm.isStreaming
        val onayEtkin = !akiyor && !suresiDoldu
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val onayZemin: Brush = if (cift) SolidColor(DS.Colors.error) else DS.Gradients.primaryGradient
            val kapsul = RoundedCornerShape(percent = 50)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .alpha(if (suresiDoldu) 0.6f else if (!onayEtkin) 0.5f else 1f)
                    .clip(kapsul)
                    .background(onayZemin)
                    .semantics { role = Role.Button }
                    .plainClickable(enabled = onayEtkin) { vm.confirmWrite(cift) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        suresiDoldu -> OnayIstegi.suresiDolduDugmesi
                        cift -> "Evet, kalıcı olarak uygula"
                        else -> "Onayla"
                    },
                    style = sys(14, FontWeight.SemiBold),
                    color = if (cift) DS.Colors.onError else Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .alpha(if (akiyor) 0.5f else 1f)
                    .clip(kapsul)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), kapsul)
                    .semantics { role = Role.Button }
                    .plainClickable(enabled = !akiyor) { vm.cancelWrite() }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Vazgeç", style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
            }
        }
        // Düzeltme ipucu: kullanıcı tüm isteği yeniden söylemek yerine yalnız düzeltmeyi yazabilir.
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            DfyIcon("pencil.line", size = 11.dp, tint = DS.Colors.onSurfaceVariant)
            Text(
                "Yanlış bir şey mi var? Aşağıya yalnız düzeltmeyi yazın (ör. «diş 37 olsun»).",
                style = sys(11), color = DS.Colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WriteResultBadge(result: AgentWriteResult) {
    val color = if (result == AgentWriteResult.committed) DS.Colors.tertiaryContainer else DS.Colors.onSurfaceVariant
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(if (result == AgentWriteResult.committed) "checkmark.seal.fill" else "info.circle.fill", size = 13.dp, tint = color)
        Text(result.label, style = sys(13, FontWeight.SemiBold), color = color)
    }
}

// MARK: - Önizleme

/** Önizleme oturumu: ağ çağrısı yok; grafik, Excel, Türkçe onay ve seçim kartı içerir. */
private fun ornekOturum(): AgentSessionManager = AgentSessionManager().apply {
    ornekVeri = true
    conversationId = "ornek-sohbet"
    val grafik = AgentChartSpec.from(
        mapOf(
            "type" to JSONValue.string("bar"), "title" to JSONValue.string("Randevular"), "xKey" to JSONValue.string("ay"),
            "series" to JSONValue.array(listOf(JSONValue.`object`(mapOf("key" to JSONValue.string("adet"), "label" to JSONValue.string("Randevu"))))),
            "rows" to JSONValue.array(
                listOf(
                    JSONValue.`object`(mapOf("ay" to JSONValue.string("Ağu"), "adet" to JSONValue.number(120.0))),
                    JSONValue.`object`(mapOf("ay" to JSONValue.string("Eyl"), "adet" to JSONValue.number(134.0))),
                    JSONValue.`object`(mapOf("ay" to JSONValue.string("Eki"), "adet" to JSONValue.number(98.0))),
                ),
            ),
        ),
    )
    messages = listOf(
        AgentMessage(role = AgentRole.user, content = "Son üç ayın randevularını göster ve kritik stoğu Excel'e aktar"),
        AgentMessage(
            role = AgentRole.assistant,
            content = "Son üç ayın randevu sayıları:\n\n| Ay | Randevu |\n|---|---|\n| Ağu | 120 |\n| Eyl | 134 |\n| Eki | 98 |",
            toolSteps = listOf(AgentToolStep("sql_query", "3 ay sorgulandı", true)),
            charts = listOfNotNull(grafik),
            excelFiles = listOf(AgentExcelArtifact("kritik-stok.xlsx", "/api/ai-assistant/download/f1", "f1", 12, "m-ornek", 0)),
        ),
        AgentMessage(role = AgentRole.user, content = "36'daki kalemi yapılan tedavilere aktar"),
        AgentMessage(
            role = AgentRole.assistant,
            content = "**Deneme Hasta** · Kompozit Dolgu (36) yapılanlara aktarılacak.",
            writePreview = AgentWritePreview(
                operation = "update", entity = "treatment_items", risk = "low", affectedCount = 1, requiresDoubleConfirm = false,
                ozet = "Deneme Hasta için 36 numaralı dişteki Kompozit Dolgu yapılanlara aktarılacak. Onaylıyor musunuz?",
                klinikAdi = "Deneme Klinik", entityLabel = "Tedavi kalemi", operationLabel = "Yapılana aktarma",
                changes = listOf(
                    Degisiklik("Kompozit Dolgu (36)", "Aşama", "Planlandı", "Yapıldı"),
                    Degisiklik("Kompozit Dolgu (36)", "Hekim", null, "Dt. A"),
                ),
                onizlemeId = "ornek-onizleme", gecerlilikSonu = Instant.now().plusSeconds(600),
            ),
        ),
        AgentMessage(role = AgentRole.user, content = "Dolgu ekle"),
        AgentMessage(
            role = AgentRole.assistant,
            content = "Hangi dolguyu kastettiniz?",
            choiceSet = AgentChoiceSet(
                table = "service_catalog", term = "dolgu", priceListId = "menu-1", elleSecim = true,
                choices = listOf(
                    AgentChoice("Kompozit Dolgu", "1.800,00 TL", "s1", "service_catalog", "dolgu"),
                    AgentChoice("Amalgam Dolgu", "1.200,00 TL", "s4", "service_catalog", "dolgu"),
                ),
            ),
        ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentViewPreview() {
    PreviewEnv { AgentEkrani(remember { AgentSessionManager().apply { ornekVeri = true } }, onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun AgentViewSohbetPreview() {
    PreviewEnv { AgentEkrani(remember { ornekOturum() }, onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun AgentViewTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { AgentEkrani(remember { ornekOturum() }, onDismiss = {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 800, heightDp = 1280)
@Composable
private fun AgentViewTabletDikeyPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_DIKEY) { AgentEkrani(remember { AgentSessionManager().apply { ornekVeri = true } }, onDismiss = {}) }
}
