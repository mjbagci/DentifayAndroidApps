package com.dentifay.app.smile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dentifay.app.credit.SmileCreditFormat
import com.dentifay.app.credit.SmileCreditsStore
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.Motion
import com.dentifay.app.theme.appear
import com.dentifay.app.theme.glassCard
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import kotlin.math.abs
import kotlin.math.roundToInt

// Gülüş simülasyonu · 2. adım (Tasarım): hazır tasarımlar, ince ayar, gelişmiş, alt eylem çubuğu.

@Composable
internal fun SmileStepHeader(n: Int, title: String, detail: String) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, DS.Colors.hairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = sys(13, FontWeight.Bold), color = Color.White) }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = sys(16, FontWeight.Bold), color = DS.Colors.onSurface)
            Text(detail, style = sys(11), color = DS.Colors.onSurfaceVariant)
        }
    }
}

// MARK: Fotoğraf + gönderilen alan

@Composable
internal fun SmilePhotoSection(vm: SmileSimulationViewModel, onRetakeFrontal: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().glassCard(22.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val image = vm.original
        val region = vm.region
        if (image != null && region != null) {
            SmileRegionPreview(image = image, region = region, mask = vm.maskPreview, modifier = Modifier.fillMaxWidth())
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmileInfoLine("rectangle.dashed", "Çerçeve: tasarımın uygulanacağı ağız bölgesi.")
            SmileInfoLine(
                "paintbrush.pointed",
                if (vm.isMaskEdited) "Turkuaz: değişecek dişler (elle düzenlendi)."
                else "Turkuaz: değişecek dişler, otomatik seçildi. Dudak ve yüz aynen kalır.",
            )
            val p = vm.photo
            if (p != null && p.isDeskewed) {
                val deg = (abs(p.angle.toDouble()) * 180 / Math.PI).roundToInt()
                SmileInfoLine("angle", "Baş $deg° eğikti, ağız hattı otomatik düzeltildi.")
            }
        }

        if (!vm.isFrontal) SideAngleWarning(onRetakeFrontal)
    }
}

/** Yandan çekilmiş fotoğrafta dişler yamuk çıkabiliyor → karşıdan çekime yönlendir. */
@Composable
private fun SideAngleWarning(onRetake: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DS.Colors.orange400.copy(alpha = 0.12f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon("person.crop.circle.badge.exclamationmark", size = 20.dp, tint = DS.Colors.orange400)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Fotoğraf yandan çekilmiş", style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text("Dişler yamuk görünebilir. En iyi sonuç için hastayı tam karşıdan çekin.", style = sys(11), color = DS.Colors.onSurfaceVariant)
        }
        Box(
            Modifier
                .pressable(onClick = onRetake)
                .height(32.dp)
                .clip(CircleShape)
                .background(DS.Colors.orange400)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Karşıdan çek", style = sys(12, FontWeight.Bold), color = Color.White) }
    }
}

@Composable
private fun SmileInfoLine(icon: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { DfyIcon(icon, size = 13.dp, tint = DS.Colors.cyan400) }
        Text(text, style = sys(12), color = DS.Colors.onSurfaceVariant)
    }
}

// MARK: Hazır tasarımlar (tek seçim)

@Composable
internal fun SmilePresetGrid(vm: SmileSimulationViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SmilePreset.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p -> PresetCard(vm, p, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetCard(vm: SmileSimulationViewModel, p: SmilePreset, modifier: Modifier) {
    val selected = vm.presetID == p.id
    val spring = Motion.spring<Float>(0.3, 0.8)
    val sel by animateFloatAsState(if (selected) 1f else 0f, spring, label = "preset")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .pressable { vm.selectPreset(p) }
            .semantics { this.selected = selected }
            .shadow((10 * sel).dp, shape, ambientColor = DS.Colors.cyan400.copy(alpha = 0.22f), spotColor = DS.Colors.cyan400.copy(alpha = 0.22f))
            .glassCard(18.dp)
            .border(1.5.dp, DS.Colors.cyan400.copy(alpha = 0.85f * sel), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) DS.Gradients.primaryDiagonal else SolidColor(DS.Colors.cyan400.copy(alpha = 0.1f))),
                contentAlignment = Alignment.Center,
            ) { DfyIcon(p.icon, size = 15.dp, tint = if (selected) Color.White else DS.Colors.cyan400) }
            Spacer(Modifier.weight(1f))
            DfyIcon(
                if (selected) "largecircle.fill.circle" else "circle", size = 18.dp,
                tint = if (selected) DS.Colors.cyan400 else DS.Colors.outline.copy(alpha = 0.6f),
            )
        }
        Text(p.title, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // `.lineLimit(2, reservesSpace: true)`
        Text(p.subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant, maxLines = 2, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(hex(p.design.shade.swatchHex))
                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
            Text(p.design.shade.title, style = sys(10, FontWeight.SemiBold), color = DS.Colors.outline)
            if (p.design.form != ToothForm.natural) {
                Text("· ${p.design.form.title}", style = sys(10, FontWeight.SemiBold), color = DS.Colors.outline)
            }
        }
    }
}

// MARK: İnce ayar

@Composable
internal fun SmileTuningSection(vm: SmileSimulationViewModel, modifier: Modifier = Modifier) {
    val custom = vm.presetID == null
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .glassCard(22.dp)
            .border(1.2.dp, if (custom) DS.Colors.cyan400.copy(alpha = 0.6f) else Color.Transparent, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DfyIcon(vm.preset?.icon ?: "slider.horizontal.3", tint = DS.Colors.cyan400)
            Text(vm.designTitle, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Spacer(Modifier.weight(1f))
            if (custom) {
                Text(
                    "ÖZEL",
                    Modifier.clip(CircleShape).background(DS.Colors.cyan400).padding(horizontal = 6.dp, vertical = 3.dp),
                    style = sys(9, FontWeight.Bold, tracking = 0.8), color = DS.Colors.onPrimary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningLabel("Diş rengi", "${vm.design.shade.title} · ${vm.design.shade.caption}")
            Row {
                ToothShade.entries.forEach { s -> ShadeSwatch(vm, s, Modifier.weight(1f)) }
            }
        }

        ScaleRow(
            title = "Diş boyu", value = vm.design.length, label = { it.lengthTitle }, ends = "Kısa" to "Uzun",
            onChange = { vm.design = vm.design.copy(length = it) },
        )
        ScaleRow(
            title = "Diş genişliği", value = vm.design.width, label = { it.widthTitle }, ends = "İnce" to "Geniş",
            onChange = { vm.design = vm.design.copy(width = it) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TuningLabel("Diş şekli", vm.design.form.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToothForm.entries.forEach { f -> FormChip(vm, f, Modifier.weight(1f)) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val procs = vm.design.procedures
            TuningLabel("Yapılacak işlemler", if (procs.isEmpty()) "Seçilmedi" else "${procs.size} seçili")
            SmileProcedure.entries.forEach { ProcedureRow(vm, it) }
        }
    }
}

@Composable
private fun TuningLabel(title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
        Spacer(Modifier.weight(1f))
        Crossfade(value, animationSpec = Motion.easeInOut(200), label = "tuningValue") {
            Text(it, style = sys(12, FontWeight.Medium), color = DS.Colors.cyan400)
        }
    }
}

@Composable
private fun ShadeSwatch(vm: SmileSimulationViewModel, s: ToothShade, modifier: Modifier) {
    val selected = vm.design.shade == s
    val spring = Motion.spring<Float>(0.3, 0.8)
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, spring, label = "swatchScale")
    val ring by animateDpAsState(if (selected) 3.dp else 1.dp, Motion.spring(0.3, 0.8), label = "swatchRing")
    val ringColor by animateColorAsState(if (selected) DS.Colors.cyan400 else Color.White.copy(alpha = 0.25f), Motion.spring(0.3, 0.8), label = "swatchColor")
    Column(
        modifier
            .plainClickable {
                Haptics.selection()
                vm.design = vm.design.copy(shade = s)
            }
            .semantics { contentDescription = "${s.title}, ${s.caption}"; this.selected = selected }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(hex(s.swatchHex))
                .border(ring, ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (s == ToothShade.keep) DfyIcon("equal", size = 12.dp, tint = Color.Black.copy(alpha = 0.45f))
        }
        Text(s.title, style = sys(11, FontWeight.Bold), color = if (selected) DS.Colors.onSurface else DS.Colors.onSurfaceVariant)
        Text(s.caption, style = sys(9), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScaleRow(title: String, value: ToothScale, label: (ToothScale) -> String, ends: Pair<String, String>, onChange: (ToothScale) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TuningLabel(title, label(value))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ends.first, style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
            SmileSlider(
                value = value.rawValue.toFloat(),
                onValueChange = { v ->
                    val n = ToothScale.of(v.roundToInt()) ?: ToothScale.zero
                    if (n != value) { onChange(n); Haptics.selection() }
                },
                range = -2f..2f, steps = 3,
                modifier = Modifier.weight(1f).semantics { contentDescription = title; stateDescription = label(value) },
            )
            Text(ends.second, style = sys(11, FontWeight.Medium), color = DS.Colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun FormChip(vm: SmileSimulationViewModel, f: ToothForm, modifier: Modifier) {
    val selected = vm.design.form == f
    val shape = RoundedCornerShape(12.dp)
    val fg = if (selected) DS.Colors.onSurface else DS.Colors.onSurfaceVariant
    Column(
        modifier
            .pressable {
                Haptics.selection()
                vm.design = vm.design.copy(form = f)
            }
            .semantics { this.selected = selected }
            .height(54.dp)
            .clip(shape)
            .background(if (selected) DS.Colors.cyan400.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) DS.Colors.cyan400.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.1f), shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        DfyIcon(f.icon, size = 15.dp, tint = fg)
        Text(f.title, style = sys(12, FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

@Composable
private fun ProcedureRow(vm: SmileSimulationViewModel, proc: SmileProcedure) {
    val on = vm.design.procedures.contains(proc)
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable {
                Haptics.selection()
                val d = vm.design
                vm.design = d.copy(procedures = if (on) d.procedures - proc else d.procedures + proc)
            }
            .semantics { selected = on }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (on) DS.Gradients.primaryDiagonal else SolidColor(DS.Colors.cyan400.copy(alpha = 0.1f))),
            contentAlignment = Alignment.Center,
        ) { DfyIcon(proc.icon, size = 13.dp, tint = if (on) Color.White else DS.Colors.cyan400) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(proc.title, style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurface)
            Text(proc.subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant)
        }
        DfyIcon(
            if (on) "checkmark.circle.fill" else "circle", size = 20.dp,
            tint = if (on) DS.Colors.cyan400 else DS.Colors.outline.copy(alpha = 0.6f),
        )
    }
}

// MARK: Gelişmiş

@Composable
internal fun SmileAdvancedSection(
    vm: SmileSimulationViewModel,
    showAdvanced: Boolean,
    onToggle: () -> Unit,
    onContact: () -> Unit,
    onEditMask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val credits = SmileCreditsStore.shared
    val chevron by animateFloatAsState(if (showAdvanced) 180f else 0f, Motion.easeInOut(250), label = "chevron")
    Column(modifier.fillMaxWidth().glassCard(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().plainClickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Text("GELİŞMİŞ", style = sys(11, FontWeight.Bold, tracking = 1.1), color = DS.Colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.isGuest) "${SmileAIModel.default.title} · ücretsiz deneme"
                else "${vm.model.title} · ${SmileCreditFormat.credits(vm.estimatedCredits)} kredi",
                style = sys(12), color = DS.Colors.outline,
            )
            Spacer(Modifier.width(6.dp))
            DfyIcon("chevron.down", size = 11.dp, tint = DS.Colors.outline, modifier = Modifier.rotate(chevron))
        }

        AnimatedVisibility(
            showAdvanced,
            enter = fadeIn(Motion.easeInOut(250)) + expandVertically(Motion.easeInOut(250)),
            exit = fadeOut(Motion.easeInOut(250)) + shrinkVertically(Motion.easeInOut(250)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdvancedLabel("Kalite")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmileAIModel.selectable.forEach { m ->
                        ModelRow(vm, m, locked = vm.isGuest && m != SmileAIModel.default, price = credits.price(m), onContact = onContact)
                    }
                }
                if (vm.isGuest) {
                    Text(
                        "Üst kalite kademeleri Dentifay'ı kliniğinizde kullanmaya başladığınızda açılır.",
                        style = sys(11), color = DS.Colors.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DfyIcon("arrow.triangle.2.circlepath", size = 11.dp, tint = DS.Colors.onSurfaceVariant)
                    Text("Model ağzı kaydırırsa sonuç otomatik düzeltilir (ücretsiz).", style = sys(11), color = DS.Colors.onSurfaceVariant)
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

                AdvancedLabel("Maske · değişecek diş alanı")
                SmileSegmented(
                    options = MaskScope.entries.toList(), selected = vm.maskScope,
                    onSelect = { vm.maskScope = it; Haptics.selection() },
                    modifier = Modifier.fillMaxWidth(), label = { it.title }, animationMs = 200,
                )

                AnimatedVisibility(!vm.isMaskEdited, enter = fadeIn(), exit = fadeOut()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dar", style = sys(11), color = DS.Colors.onSurfaceVariant)
                        SmileSlider(
                            value = vm.maskExpansion.toFloat(),
                            onValueChange = { v ->
                                val stepped = (v * 100).roundToInt() / 100.0
                                if (stepped != vm.maskExpansion) vm.maskExpansion = stepped
                            },
                            range = 0.02f..0.12f, steps = 9,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Maske sınırı" },
                        )
                        Text("Geniş", style = sys(11), color = DS.Colors.onSurfaceVariant)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier
                            .weight(1f)
                            .pressable(onClick = onEditMask)
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(DS.Colors.cyan400.copy(alpha = 0.12f))
                            .border(1.dp, DS.Colors.cyan400.copy(alpha = 0.5f), CircleShape),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DfyIcon("paintbrush.pointed", size = 13.dp, tint = DS.Colors.onSurface)
                        Text("Elle düzenle", style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
                    }
                    AnimatedVisibility(vm.isMaskEdited || vm.maskScope != MaskScope.all, enter = fadeIn(), exit = fadeOut()) {
                        Row(
                            Modifier
                                .pressable { vm.resetMask() }
                                .height(40.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                .padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DfyIcon("arrow.counterclockwise", size = 13.dp, tint = DS.Colors.onSurfaceVariant)
                            Text("Otomatik", style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedLabel(text: String) {
    Text(text.upperTr(), style = sys(10, FontWeight.Bold, tracking = 1), color = DS.Colors.onSurfaceVariant)
}

/**
 * `locked`: ücretsiz denemede yalnız varsayılan kademe seçilir; diğerleri görünür ama kilitli
 * (dokununca iletişim ekranı).
 */
@Composable
private fun ModelRow(vm: SmileSimulationViewModel, m: SmileAIModel, locked: Boolean, price: Double, onContact: () -> Unit) {
    val selected = if (vm.isGuest) m == SmileAIModel.default else vm.model == m
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable {
                if (locked) { Haptics.selection(); onContact(); return@plainClickable }
                vm.model = m; Haptics.selection()
            }
            .alpha(if (locked) 0.6f else 1f)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DfyIcon(
            if (locked) "lock" else if (selected) "largecircle.fill.circle" else "circle",
            tint = if (selected) DS.Colors.cyan400 else DS.Colors.outline,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, style = sys(13, FontWeight.Medium), color = DS.Colors.onSurface)
                if (m == SmileAIModel.default) {
                    Text(
                        "ÖNERİLEN",
                        Modifier.clip(CircleShape).background(DS.Colors.cyan400).padding(horizontal = 5.dp, vertical = 2.dp),
                        style = sys(8, FontWeight.Bold, tracking = 0.8), color = DS.Colors.onPrimary,
                    )
                }
            }
            Text(m.subtitle, style = sys(11), color = DS.Colors.onSurfaceVariant)
        }
        if (locked) {
            Text("Uygulamayla", style = sys(11, FontWeight.SemiBold), color = DS.Colors.outline)
        } else if (!vm.isGuest) {
            Text("${SmileCreditFormat.credits(price)} kredi", style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        }
    }
}
