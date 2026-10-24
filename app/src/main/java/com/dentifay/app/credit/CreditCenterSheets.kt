package com.dentifay.app.credit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.sys
import com.dentifay.app.theme.upperTr
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

// MARK: - Form görünümü (SwiftUI `Form` · inset grouped)

private val FormCell = hex("1c1c1e")
private val FormSecondary = hex("8e8e93")

@Composable
private fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(header.upperTr(), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp), style = sys(13), color = FormSecondary)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FormCell), content = content)
        if (footer != null) {
            Text(footer, Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp), style = sys(13), color = FormSecondary)
        }
    }
}

@Composable
private fun FormRow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
        content()
    }
}

@Composable
private fun FormSeparator() {
    Box(Modifier.padding(start = 16.dp)) { CreditDivider(0.15f) }
}

/** `TextField(…).keyboardType(.decimalPad)` */
@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    invalid: Boolean,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = sys(17).copy(color = if (invalid) DS.Colors.error else DS.Colors.onSurface, textAlign = align),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        cursorBrush = SolidColor(CreditVurgu),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, Modifier.fillMaxWidth(), style = sys(17).copy(textAlign = align), color = Color.White.copy(alpha = 0.3f), maxLines = 1)
                }
                inner()
            }
        },
    )
}

/** Düzenleme alanına konan sayı: gruplamasız, Türkçe ondalık ("1250,5"). */
private object CreditInputText {
    fun make(v: Double): String {
        val f = NumberFormat.getNumberInstance(Locale.forLanguageTag("tr-TR"))
        f.isGroupingUsed = false
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        return f.format(v)
    }
}

/** Pencere içeriğinin kaydırılan gövdesi (form, tablette okunur genişlikte ortalı). */
@Composable
private fun ColumnScope.FormGovdesi(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp)
            .navigationBarsPadding(),
    ) {
        Column(Modifier.icerikGenisligi(640.dp), verticalArrangement = Arrangement.spacedBy(32.dp), content = content)
    }
}

// MARK: - Limit düzenleyici

/**
 * Kişinin aylık kredi limitleri (Ek MO.21s). Özellik listesi SUNUCUDAN; boş = sınırsız, 0 = o işlemi kredili
 * kullanmak kapalı. Gövde TAM settir; `If-Match` kişinin `limitVersion`ı. 409'da liste yeniden okunur, pencere
 * açık kalır, sunucunun cümlesi.
 */
@Composable
internal fun CcLimitEditorSheet(
    person: CreditPeople.Person,
    features: List<String>,
    maxCredits: Double,
    onSave: suspend (Double?, Map<String, Double?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var total by remember(person.id) { mutableStateOf(person.limits.total?.let { CreditInputText.make(it) } ?: "") }
    val values = remember(person.id, features) {
        mutableStateMapOf<String, String>().apply {
            features.forEach { f -> put(f, person.limits.features[f]?.let { CreditInputText.make(it) } ?: "") }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun isInvalid(s: String) = CreditInput.parse(s, maxCredits) == CreditInput.invalid

    suspend fun save() {
        val gecersiz = "Limit 0 ile 1.000.000 kredi arasında, en çok iki ondalık olmalı."
        val totalValue: Double? = when (val p = CreditInput.parse(total, maxCredits)) {
            CreditInput.empty -> null
            is CreditInput.value -> p.v
            CreditInput.invalid -> { error = gecersiz; return }
        }
        val featureValues = mutableMapOf<String, Double?>()
        for (f in features) {
            when (val p = CreditInput.parse(values[f] ?: "", maxCredits)) {
                CreditInput.empty -> featureValues[f] = null
                is CreditInput.value -> featureValues[f] = p.v
                CreditInput.invalid -> { error = gecersiz; return }
            }
        }
        saving = true
        try {
            onSave(totalValue, featureValues)
            onDismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            error = creditErrorText(e)
        } finally {
            saving = false
        }
    }

    // `.interactiveDismissDisabled(saving)`
    BackHandler(enabled = saving) {}

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar(
            "Aylık kredi limiti",
            subtitle = person.user.displayName,
            leading = { CreditBarButton("Vazgeç") { onDismiss() } },
            trailing = {
                if (saving) CreditSpinner() else CreditBarButton("Kaydet", bold = true) { scope.launch { save() } }
            },
        )
        FormGovdesi {
            FormSection(header = "Aylık toplam limit (kredi)") {
                FormRow {
                    DecimalField(total, { total = it }, "Sınırsız", isInvalid(total), Modifier.fillMaxWidth())
                }
            }
            FormSection(header = "Özellik limitleri (kredi)") {
                features.forEachIndexed { i, f ->
                    if (i > 0) FormSeparator()
                    FormRow {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                CreditLabels.feature(f), Modifier.weight(1f),
                                style = sys(17), color = DS.Colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            val v = values[f] ?: ""
                            DecimalField(v, { values[f] = it }, "Sınırsız", isInvalid(v), Modifier.width(110.dp), alignEnd = true)
                        }
                    }
                }
            }
            FormSection {
                val err = error
                if (err != null) {
                    FormRow { Text(err, style = sys(13), color = DS.Colors.error) }
                    FormSeparator()
                }
                FormRow {
                    Text("Boş bırakılan alan sınırsızdır; 0 o işlemi kredili kullanmayı kapatır.", style = sys(12), color = DS.Colors.onSurfaceVariant)
                }
            }
        }
    }
}

// MARK: - Düşük bakiye eşiği

@Composable
internal fun CcThresholdSheet(
    current: Double,
    onSave: suspend (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(CreditInputText.make(current)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun save() {
        val p = CreditInput.parse(value)
        if (p !is CreditInput.value) {
            error = "Eşik 0 ile 1.000.000 kredi arasında olmalı."
            return
        }
        saving = true
        try {
            onSave(p.v)
            onDismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.error()
            error = creditErrorText(e)
        } finally {
            saving = false
        }
    }

    BackHandler(enabled = saving) {}

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar(
            "Düşük bakiye uyarısı",
            leading = { CreditBarButton("Vazgeç") { onDismiss() } },
            trailing = {
                if (saving) CreditSpinner() else CreditBarButton("Kaydet", bold = true) { scope.launch { save() } }
            },
        )
        FormGovdesi {
            FormSection(
                header = "Uyarı eşiği (kredi)",
                footer = "Kullanılabilir kredi bu eşiğin altına düştüğünde kredi yükleme yetkisi olanlara bildirim gider (0 = kapalı).",
            ) {
                FormRow {
                    DecimalField(value, { value = it }, "0", CreditInput.parse(value) == CreditInput.invalid, Modifier.fillMaxWidth())
                }
            }
            val err = error
            if (err != null) {
                FormSection { FormRow { Text(err, style = sys(13), color = DS.Colors.error) } }
            }
        }
    }
}

// MARK: - Kabul edilen koşullar (Ek MO2.5 / MO2.6)

@Composable
internal fun CcAcceptedTermsSheet(
    row: CreditTransactionsPage.Row,
    load: suspend () -> AcceptedTerms,
    onDismiss: () -> Unit,
) {
    var terms by remember(row.id) { mutableStateOf<AcceptedTerms?>(null) }
    var error by remember(row.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(row.id) {
        try {
            terms = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CreditCenterClient.ClientError) {
            error = if (e.code == "E_CREDIT_TERMS_NONE" || e.status == 404) "Bu satın alma, koşul onayı ürüne eklenmeden önce yapıldı."
            else e.message ?: "Kredi bilgileri yüklenemedi."
        } catch (e: Exception) {
            error = "Kredi bilgileri yüklenemedi."
        }
    }

    Column(Modifier.fillMaxSize().background(DS.Colors.background)) {
        CreditNavBar("Kabul edilen koşullar", trailing = { CreditBarButton("Kapat") { onDismiss() } })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier.icerikGenisligi(720.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val t = terms
                val err = error
                when {
                    t != null -> {
                        Text(
                            "${t.kabulEden?.ad ?: "Dentifay"} · ${t.at?.let { CreditDates.short(it) } ?: ""} · Sürüm ${t.surum}",
                            style = sys(12), color = DS.Colors.onSurfaceVariant,
                        )
                        t.metin?.bolumler?.forEach { b ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(b.baslik, style = sys(13, FontWeight.SemiBold), color = DS.Colors.onSurface)
                                b.maddeler.forEach { madde ->
                                    Text("• $madde", style = sys(12.5), color = DS.Colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    err != null -> Text(err, style = sys(13), color = DS.Colors.onSurfaceVariant)
                    else -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) { CreditSpinner() }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CcLimitEditorSheetPreview() {
    PreviewEnv {
        CcLimitEditorSheet(
            CreditPreviewData.people.people[0], CreditPreviewData.people.features, 1_000_000.0,
            onSave = { _, _ -> }, onDismiss = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 422)
@Composable
private fun CcThresholdSheetPreview() {
    PreviewEnv {
        CcThresholdSheet(60.0, onSave = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F131E, widthDp = 390, heightDp = 844)
@Composable
private fun CcAcceptedTermsSheetPreview() {
    PreviewEnv {
        CcAcceptedTermsSheet(CreditPreviewData.rows[3], load = { CreditPreviewData.kabul }, onDismiss = {})
    }
}
