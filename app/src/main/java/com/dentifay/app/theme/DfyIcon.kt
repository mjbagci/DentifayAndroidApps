package com.dentifay.app.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Web paneliyle AYNI ikon seti: Hugeicons "Stroke Rounded" + çift tonlu (duotone) çizimler.
 * `name` bir SF Symbol adıdır ("calendar") ya da doğrudan web adı ("Calendar").
 * Kaynaklar `res/drawable/dfy_*.xml` · `dfycift_*.xml` (VectorDrawable — `tools/svg2vector.py` üretir):
 * eşzamanlı çizilir, kaydırmada takılmaz; renk `tint` ile (SrcIn — çift tonun yarı saydam katmanı korunur).
 *
 * SwiftUI'de boyut yazı tipinden (`.font(.system(size:))`) gelir — burada `size` = o yazı boyutu (dp).
 * Çizim, SF Symbol ile aynı alanı kaplasın diye ×1.18 büyütülür (Swift `scaleEffect(1.18)`).
 */
object DfyIconAssets {
    /**
     * iOS'ta eşlemesi olmayan ad SF Symbol olarak çizilir; Android'de karşılıksız ad gri bir nokta olurdu (kredi ipucu,
     * onay kutusu, silgi, gülüş şekli…). Bu adlar web setindeki eşine ya da `ikon-kaynak`a eklenen aynı çizgide ikona bağlanır.
     */
    private val ekSf: Map<String, String> = mapOf(
        "info.circle" to "Info",
        "bell.badge" to "BellRing",
        "square" to "Square",
        "triangle" to "Triangle",
        "equal" to "Equal",
        "eraser.fill" to "Eraser",
        "arrow.left.arrow.right" to "ArrowLeftRight",
        "banknote" to "Wallet",
        "wallet.bifold" to "Wallet",
        "pencil" to "PencilLine",
        "checkmark.shield" to "ShieldCheck",
    )

    /** Web ikon adı; eşleme yoksa null. */
    fun web(name: String): String? = if (name in DfyIconMap.web) name else DfyIconMap.sf[name] ?: ekSf[name]

    /** VectorDrawable kaynağı ("Calendar" ya da çift tonlu karşılığı); eşleme yoksa null. */
    fun res(name: String, duotone: Boolean = false): Int? {
        val web = web(name) ?: return null
        if (duotone && web in DfyIconMap.duotone) DfyIconRes.cift[web]?.let { return it }
        return DfyIconRes.tek[web]
    }

    /** Web setinde karşılığı olmayan SF Symbol'ler (Face ID gibi sistem kavramları) için Material ikon. */
    fun fallback(name: String): ImageVector = when (name) {
        "faceid" -> Icons.Filled.Face
        "touchid", "opticid" -> Icons.Filled.Fingerprint
        "eye.fill" -> Icons.Filled.Visibility
        "square.on.square", "doc.on.doc" -> Icons.Outlined.ContentCopy
        "ellipsis", "ellipsis.circle" -> Icons.Outlined.MoreHoriz
        "pause.fill" -> Icons.Outlined.Pause
        "globe" -> Icons.Outlined.Language
        "crop" -> Icons.Outlined.Crop
        "flip.horizontal" -> Icons.Outlined.Flip
        "waveform.circle", "waveform.path" -> Icons.Outlined.GraphicEq
        "bolt" -> Icons.Outlined.Bolt
        "slider.vertical.3" -> Icons.Outlined.Tune
        "printer", "printer.fill" -> Icons.Outlined.Print
        "square.and.arrow.up.fill", "arrowshape.turn.up.right" -> Icons.Outlined.Share
        "xmark.octagon", "multiply" -> Icons.Outlined.Close
        "percent" -> Icons.Outlined.Percent
        "person.badge.plus" -> Icons.Outlined.PersonAdd
        "calendar.badge.minus" -> Icons.Outlined.EventBusy
        "door.left.hand.open" -> Icons.Outlined.MeetingRoom
        else -> Icons.Filled.Circle
    }
}

/**
 * Tam `modifier` boyutunda çizilen ikon görseli (Swift `Image(decorative: asset).resizable()`).
 * IconTile / CiftIkon / özel boyutlu yerler bunu kullanır.
 */
@Composable
fun DfyIconImage(name: String, modifier: Modifier = Modifier, duotone: Boolean = false, tint: Color = LocalContentColor.current) {
    val res = DfyIconAssets.res(name, duotone)
    if (res != null) {
        Image(
            painter = painterResource(res), contentDescription = null, modifier = modifier,
            contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint),
        )
    } else {
        Icon(DfyIconAssets.fallback(name), null, modifier, tint = tint)
    }
}

/**
 * SwiftUI `DfyIcon(name).font(.system(size: N)).foregroundStyle(c)` karşılığı:
 * `DfyIcon(name, size = N.dp, tint = c)`.
 */
@Composable
fun DfyIcon(name: String, modifier: Modifier = Modifier, size: Dp = 17.dp, tint: Color = LocalContentColor.current, duotone: Boolean = false) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        DfyIconImage(
            name, Modifier.size(size).graphicsLayer { scaleX = 1.18f; scaleY = 1.18f },
            duotone = duotone, tint = tint,
        )
    }
}
