package com.dentifay.app.agent

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.core.ApiClient
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.ThinkingDots
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Seçim kartı bloğu — sunucunun sorusu · kartlar · 0 adayda Ek MO.10b · "Listede yok, kendim seçeceğim" (Ek O.26D-4).
 * Sohbet ve Tedavi Planı AYNI bileşeni kullanır (M-E1.4.1; iki kopya yazılmaz).
 */
@Composable
fun SecimKarti(
    set: AgentChoiceSet,
    /** Sunucunun sorusu (`msg.content`); null → soru satırı çizilmez (sohbette soru balonun metnidir). */
    soru: String? = null,
    pending: AgentChoice? = null,
    modifier: Modifier = Modifier,
    onSelect: (AgentChoice) -> Unit,
    onListedeYok: () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (soru != null) {
            Text(soru, style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface)
        }
        if (set.choices.isEmpty()) {
            Text(
                AgentChoiceSet.bosKartMetni,
                Modifier.fillMaxWidth().glassCard(14.dp).padding(14.dp),
                style = sys(13), color = DS.Colors.onSurfaceVariant,
            )
        } else {
            ChoiceCards("SEÇENEKLER", set.choices, pending = pending, onSelect = onSelect)
        }
        if (set.listedeYokVar) {
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (pending != null) 0.45f else 1f)
                    .semantics { role = Role.Button }
                    .pressable(enabled = pending == null, onClick = onListedeYok)
                    .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.3f), shape)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, tint = DS.Colors.cyan400, modifier = Modifier.size(18.dp))
                Text(
                    AgentChoiceSet.listedeYokMetni, style = sys(14, FontWeight.SemiBold), color = DS.Colors.cyan400,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * "Tedavi menüsünden seçin" (Ek O.26D-5) — `GET /api/price-lists/:id/secim?q=`; arama 300 ms gecikmeli ve SUNUCUDA.
 * Üç durum ayrı: yükleniyor · hata + "Tekrar dene" · boş. Sayfa içeriğidir (`DfySheet` içinde); seçimde ve "Kapat"ta
 * `onDismiss` çağrılır (Swift `dismiss()`). Tam boy açılır (Swift `.large`); tablette içerik 640 dp'de ortalanır.
 */
@Composable
fun MenuSecimSheet(
    set: AgentChoiceSet,
    onSelect: (MenuSecimYaniti.Oge) -> Unit,
    onDismiss: () -> Unit,
) {
    var arama by remember { mutableStateOf("") }
    var yanit by remember { mutableStateOf<MenuSecimYaniti?>(null) }
    var yukleniyor by remember { mutableStateOf(true) }
    var hata by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var aramaGorevi by remember { mutableStateOf<Job?>(null) }

    suspend fun yukle() {
        val id = set.priceListId
        if (id == null) {
            hata = true; yukleniyor = false; return
        }
        yukleniyor = true
        try {
            val r = ApiClient.fetchMenuSecim(priceListId = id, q = arama)
            yanit = r
            hata = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hata = true
        }
        yukleniyor = false
    }

    LaunchedEffect(set.id) { yukle() }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .imePadding()
            .icerikGenisligi(640.dp)
    ) {
        // Gezinme çubuğu: ortada başlık, sağda "Kapat".
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                MenuSecimYaniti.baslik, Modifier.padding(horizontal = 64.dp),
                style = sys(17, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Kapat",
                Modifier
                    .align(Alignment.CenterEnd)
                    .semantics { role = Role.Button }
                    .plainClickable(onClick = onDismiss),
                style = sys(17), color = DS.Colors.cyan400,
            )
        }

        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Arama
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DfyIcon("magnifyingglass", size = 16.dp, tint = DS.Colors.onSurfaceVariant)
                BasicTextField(
                    value = arama,
                    onValueChange = { yeni ->
                        arama = yeni
                        aramaGorevi?.cancel()
                        aramaGorevi = scope.launch {
                            delay(300)
                            yukle()
                        }
                    },
                    modifier = Modifier.weight(1f).semantics { contentDescription = MenuSecimYaniti.aramaIpucu },
                    textStyle = sys(16).copy(color = DS.Colors.onSurface),
                    cursorBrush = SolidColor(DS.Colors.cyan400),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (arama.isEmpty()) Text(MenuSecimYaniti.aramaIpucu, style = sys(16), color = DS.Colors.onSurfaceVariant.copy(alpha = 0.6f))
                            inner()
                        }
                    },
                )
            }

            val y = yanit
            when {
                yukleniyor && y == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    ThinkingDots(dotSize = 8.dp)
                }
                hata -> Column(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(MenuSecimYaniti.hataMetni, style = sys(15), color = DS.Colors.onSurface, textAlign = TextAlign.Center)
                    Text(
                        "Tekrar dene",
                        Modifier
                            .semantics { role = Role.Button }
                            .plainClickable { scope.launch { yukle() } }
                            .padding(8.dp),
                        style = sys(15, FontWeight.SemiBold), color = DS.Colors.cyan400,
                    )
                }
                y != null && y.items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(MenuSecimYaniti.bosMetni, style = sys(15), color = DS.Colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                y != null -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(y.items, key = { it.id }, contentType = { "oge" }) { oge ->
                        MenuSatiri(oge, ilk = oge === y.items.first(), son = oge === y.items.last()) {
                            onSelect(oge)
                            onDismiss()
                        }
                    }
                    if (y.truncated == true) {
                        item(key = "kirpma", contentType = "kirpma") {
                            Text(
                                MenuSecimYaniti.kirpmaMetni, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = sys(12), color = DS.Colors.outline,
                            )
                        }
                    }
                    item(key = "alt", contentType = "alt") { Spacer(Modifier.navigationBarsPadding()) }
                }
                else -> Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MenuSatiri(oge: MenuSecimYaniti.Oge, ilk: Boolean, son: Boolean, onClick: () -> Unit) {
    val r = 10.dp
    val shape = RoundedCornerShape(
        topStart = if (ilk) r else 0.dp, topEnd = if (ilk) r else 0.dp,
        bottomStart = if (son) r else 0.dp, bottomEnd = if (son) r else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(DS.Colors.surfaceContainer)) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { role = Role.Button }
                .plainClickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(oge.name, style = sys(15, FontWeight.Medium), color = DS.Colors.onSurface)
                if (!oge.grup.isNullOrEmpty()) Text(oge.grup, style = sys(12), color = DS.Colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                oge.fiyatMetni,
                Modifier.widthIn(max = 160.dp),
                style = sys(13, FontWeight.SemiBold),
                color = if (oge.priceReason == null) DS.Colors.onSurface else DS.Colors.outline,
                textAlign = TextAlign.End,
            )
        }
        if (!son) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
    }
}

private val ornekSecim = AgentChoiceSet(
    table = "service_catalog", term = "dolgu", priceListId = "menu-1", elleSecim = true,
    choices = listOf(
        AgentChoice("Kompozit Dolgu", "1.800,00 TL", "s1", "service_catalog", "dolgu"),
        AgentChoice("Amalgam Dolgu", "1.200,00 TL", "s4", "service_catalog", "dolgu"),
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 700)
@Composable
private fun SecimKartiPreview() {
    PreviewEnv {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            SecimKarti(ornekSecim, soru = "Hangi dolguyu kastettiniz?", onSelect = {}, onListedeYok = {})
            SecimKarti(
                AgentChoiceSet("service_catalog", "ozon terapi", "menu-1", elleSecim = true, choices = emptyList()),
                onSelect = {}, onListedeYok = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 1280, heightDp = 800)
@Composable
private fun MenuSecimSheetTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) {
        Box(Modifier.fillMaxSize().background(DS.Colors.background)) {
            MenuSecimSheet(ornekSecim, onSelect = {}, onDismiss = {})
        }
    }
}
