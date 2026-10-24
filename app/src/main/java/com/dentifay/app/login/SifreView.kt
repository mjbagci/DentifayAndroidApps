package com.dentifay.app.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dentifay.app.auth.LocalAuthManager
import com.dentifay.app.core.ApiClient
import com.dentifay.app.core.ApiException
import com.dentifay.app.core.SifreKurali
import com.dentifay.app.core.sifreDegistir
import com.dentifay.app.core.sifremiUnuttum
import com.dentifay.app.theme.DS
import com.dentifay.app.theme.DfyIcon
import com.dentifay.app.theme.Haptics
import com.dentifay.app.theme.hex
import com.dentifay.app.theme.plainClickable
import com.dentifay.app.theme.pressable
import com.dentifay.app.theme.sys
import com.dentifay.app.ui.LocalPencere
import com.dentifay.app.ui.PencereBilgisi
import com.dentifay.app.ui.PreviewEnv
import com.dentifay.app.ui.YetkiliEylem
import com.dentifay.app.ui.icerikGenisligi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Araç çubuğu "Kapat" ve imleç vurgusu: uygulamanın marka camgöbeği (iOS sistem mavisi yerine). */
private val SISTEM_MAVISI get() = DS.Colors.cyan400

/** Geçici şifreyle açılmış ZORUNLU oturumda "Mevcut şifre" istenmez (AUTH §5; sunucuya `currentPassword` gitmez). */
internal fun mevcutSifreGerekli(zorunlu: Boolean, geciciSifreyle: Boolean?): Boolean = !(zorunlu && geciciSifreyle == true)

/**
 * "Yeni şifre belirleyin" (zorunlu — kökte, M-E8.6) ve Ayarlar'daki "Şifre değiştir" (yerli). Geçici şifreyle açılmış
 * oturumda "Mevcut şifre" istenmez; kurallar sunucudan, cümlesi aynen.
 *
 * `zorunlu = true` → oturum şifre değişikliği bekliyor: kökte tam ekran, kapatılamaz; yalnız çıkış. `false` → Ayarlar'ın
 * sayfası (`DfySheet` içinde), solda "Kapat"; kayıttan 0,9 sn sonra kendiliğinden kapanır.
 */
@Composable
fun SifreBelirlemeView(zorunlu: Boolean, onDismiss: () -> Unit = {}) {
    val auth = LocalAuthManager.current
    val focus = LocalFocusManager.current
    var mevcut by remember { mutableStateOf("") }
    var yeni by remember { mutableStateOf("") }
    var tekrar by remember { mutableStateOf("") }
    var kaydediliyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    var basari by remember { mutableStateOf<String?>(null) }
    val kapat by rememberUpdatedState(onDismiss)

    val mevcutGerekli = mevcutSifreGerekli(zorunlu, auth.currentUser?.geciciSifreyle)
    val hazir = yeni.isNotEmpty() && tekrar.isNotEmpty() && (!mevcutGerekli || mevcut.isNotEmpty()) && !kaydediliyor

    fun kaydet() {
        if (!hazir) return
        focus.clearFocus()
        hata = null
        basari = null
        SifreKurali.denetle(yeni, tekrar)?.let { hata = it; return }
        kaydediliyor = true
        val yeniSifre = yeni
        val mevcutSifre = if (mevcutGerekli) mevcut else null
        // Ekrandan bağımsız (Swift `Task`): zorunlu ekran kayıttan sonra kökten kalkar; oturumun yeniden okunması yarıda kalmaz.
        girisKapsami.launch {
            try {
                val cumle = ApiClient.sifreDegistir(yeniSifre, mevcutSifre)
                Haptics.success()
                basari = cumle ?: "Şifreniz güncellendi."
                auth.sifreKaydedildi()
                if (!zorunlu) {
                    delay(900)
                    kapat()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                hata = e.message ?: "İşlem tamamlanamadı."
                // 5 hatalı deneme → sunucu oturumu kapatır (429 PASSWORD_CHANGE_LOCKED); oturum yeniden okunur.
                if ((e as? ApiException)?.kod == "PASSWORD_CHANGE_LOCKED") auth.checkSession()
            } finally {
                kaydediliyor = false
            }
        }
    }

    val kok = if (zorunlu) {
        Modifier.fillMaxSize().background(DS.Colors.surfaceContainer).safeDrawingPadding()
    } else {
        Modifier.fillMaxWidth().background(DS.Colors.surfaceContainer)
    }
    Column(kok) {
        UstCubuk(
            baslik = if (zorunlu) "" else "Şifre değiştir",
            sol = { if (!zorunlu) KapatDugmesi(onDismiss) },
        )
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .icerikGenisligi(720.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (zorunlu) {
                Text(
                    "Yeni şifre belirleyin", Modifier.padding(top = 24.dp),
                    style = sys(24, FontWeight.Bold), color = DS.Colors.onSurface,
                )
            }
            hata?.let { SonucBandi(basari = false, metin = it) }
            basari?.let { SonucBandi(basari = true, metin = it) }
            if (mevcutGerekli) {
                SifreAlani("Mevcut şifre", mevcut, { mevcut = it }, ImeAction.Next) { focus.moveFocus(FocusDirection.Down) }
            }
            SifreAlani("Yeni şifre", yeni, { yeni = it }, ImeAction.Next) { focus.moveFocus(FocusDirection.Down) }
            SifreAlani("Yeni şifre (tekrar)", tekrar, { tekrar = it }, ImeAction.Done) { kaydet() }

            GradyanDugme(
                metin = "Şifreyi kaydet", yukseklik = 52.dp, etkin = hazir, isliyor = kaydediliyor,
                modifier = Modifier.padding(top = 6.dp).alpha(if (hazir) 1f else YetkiliEylem.SONUK_OPAKLIK),
            ) { kaydet() }

            if (zorunlu) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Çıkış Yap",
                        Modifier
                            .plainClickable { girisKapsami.launch { auth.logout() } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = sys(14, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Giriş ekranındaki "Şifremi unuttum" — SMS ile geçici şifre (yalnız `forgot-password/durum` `sms:true` iken görünür).
 * Sunan taraf `DfySheet` içinde gösterir; yarım ekran yüksekliğinde (Swift `.presentationDetents([.medium])`), klavye
 * açılınca içerik kaydırılır.
 */
@Composable
fun SifremiUnuttumSheet(onDismiss: () -> Unit) {
    val focus = LocalFocusManager.current
    val pencere = LocalPencere.current
    var telefon by remember { mutableStateOf("") }
    var gonderiliyor by remember { mutableStateOf(false) }
    var hata by remember { mutableStateOf<String?>(null) }
    var basari by remember { mutableStateOf<String?>(null) }
    val etkin = telefon.trim().isNotEmpty() && !gonderiliyor && basari == null

    fun gonder() {
        if (!etkin) return
        focus.clearFocus()
        hata = null
        gonderiliyor = true
        val numara = telefon.trim()
        girisKapsami.launch {
            try {
                basari = ApiClient.sifremiUnuttum(numara)
                    ?: "İstek alındı. Numara kayıtlıysa geçici şifreniz SMS ile gönderildi."
                Haptics.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error()
                hata = e.message ?: "İşlem tamamlanamadı."
            } finally {
                gonderiliyor = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = pencere.yukseklik * 0.5f)
            .background(DS.Colors.surfaceContainer)
    ) {
        UstCubuk(baslik = "Şifremi unuttum", sol = { KapatDugmesi(onDismiss) })
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Telefon numaranızı yazın; size SMS ile geçici şifre gönderelim.",
                style = sys(14), color = DS.Colors.onSurfaceVariant,
            )
            hata?.let { SonucBandi(basari = false, metin = it) }
            basari?.let { SonucBandi(basari = true, metin = it) }
            GirisAlani(
                deger = telefon, onDeger = { telefon = it }, yerTutucu = "05XX XXX XX XX",
                klavye = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
                eylemler = KeyboardActions(onSend = { gonder() }),
            )
            GradyanDugme(
                metin = "Gönder", yukseklik = 50.dp, etkin = etkin, isliyor = gonderiliyor,
                // iOS bu düğmeyi pasifken soldurmuyor; Android'de pasif hâli görünür olsun diye hafif soluk.
                modifier = Modifier.alpha(if (etkin || gonderiliyor) 1f else YetkiliEylem.SONUK_OPAKLIK),
            ) { gonder() }
        }
    }
}

// MARK: - Parçalar

/** Web `SonucBandi`: başarı yeşil, hata kırmızı; ikon + cümle (sunucunun cümlesi AYNEN). */
@Composable
private fun SonucBandi(basari: Boolean, metin: String) {
    val renk = if (basari) hex("6EE7B7") else DS.Colors.danger
    val zemin = if (basari) hex("10B981") else DS.Colors.danger
    val sekil = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(sekil)
            .background(zemin.copy(alpha = 0.10f))
            .border(1.dp, zemin.copy(alpha = 0.4f), sekil)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DfyIcon(
            if (basari) "checkmark.circle.fill" else "exclamationmark.triangle.fill",
            Modifier.padding(top = 1.dp), size = 13.dp, tint = renk,
        )
        Text(metin, style = sys(13, FontWeight.Medium), color = renk)
    }
}

/** `SecureField` + başlık (12 yarı kalın) — 46 dp, %4 beyaz zemin, ince kenar. */
@Composable
private fun SifreAlani(baslik: String, deger: String, onDeger: (String) -> Unit, ime: ImeAction, onIme: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(baslik, style = sys(12, FontWeight.SemiBold), color = DS.Colors.onSurfaceVariant)
        GirisAlani(
            deger = deger, onDeger = onDeger, yerTutucu = null,
            klavye = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password, imeAction = ime,
            ),
            eylemler = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
            gizli = true,
        )
    }
}

@Composable
private fun GirisAlani(
    deger: String,
    onDeger: (String) -> Unit,
    yerTutucu: String?,
    klavye: KeyboardOptions,
    eylemler: KeyboardActions,
    gizli: Boolean = false,
) {
    val sekil = RoundedCornerShape(12.dp)
    BasicTextField(
        value = deger,
        onValueChange = onDeger,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = sys(17).copy(color = DS.Colors.onSurface),
        cursorBrush = SolidColor(SISTEM_MAVISI),
        keyboardOptions = klavye,
        keyboardActions = eylemler,
        visualTransformation = if (gizli) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { ic ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clip(sekil)
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, DS.Colors.hairlineStrong, sekil)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (deger.isEmpty() && yerTutucu != null) {
                    Text(yerTutucu, style = sys(17), color = DS.Colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ic()
            }
        },
    )
}

/** Marka gradyanlı tam genişlik düğme (köşe 14) — işlerken soluk beyaz döner gösterge. */
@Composable
private fun GradyanDugme(
    metin: String,
    yukseklik: Dp,
    etkin: Boolean,
    isliyor: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val sekil = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = yukseklik)
            .pressable(enabled = etkin, onClick = onClick)
            .clip(sekil)
            .background(DS.Gradients.primaryGradient)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isliyor) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        Text(metin, style = sys(16, FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Araç çubuğu "Kapat" (iOS `.cancellationAction` → solda, sistem mavisi). */
@Composable
private fun KapatDugmesi(onClick: () -> Unit) {
    Text(
        "Kapat",
        Modifier.plainClickable(onClick = onClick).padding(vertical = 8.dp),
        style = sys(17), color = SISTEM_MAVISI, maxLines = 1,
    )
}

/**
 * Inline gezinme çubuğu (44 dp): başlık ortada; düğme genişse başlık aradaki boşluğa kayar, sığmazsa kısalır (iOS
 * gezinme çubuğu davranışı) — dar telefonda ve büyük yazı boyutunda üst üste binme yok.
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
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
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

// MARK: - Önizleme

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 844)
@Composable
private fun SifreBelirlemeZorunluPreview() {
    PreviewEnv { SifreBelirlemeView(zorunlu = true) }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 1280, heightDp = 800)
@Composable
private fun SifreBelirlemeZorunluTabletPreview() {
    PreviewEnv(pencere = PencereBilgisi.TABLET_YATAY) { SifreBelirlemeView(zorunlu = true) }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 600)
@Composable
private fun SifreDegistirPreview() {
    PreviewEnv { SifreBelirlemeView(zorunlu = false) }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1F2B, widthDp = 390, heightDp = 500)
@Composable
private fun SifremiUnuttumPreview() {
    PreviewEnv(loggedIn = false) { SifremiUnuttumSheet(onDismiss = {}) }
}
