# Dentifay — Android

`DentAISoft` (iOS, SwiftUI) uygulamasının Jetpack Compose karşılığı. Paket: `com.dentifay.app`, minSdk 26, targetSdk 36.

## Android Studio
Repo kökünü açın (File → Open). Studio `local.properties` dosyasını kendi SDK yolunuzla günceller.
Her ekran dosyasının sonunda `@Preview` vardır.

## Yapı türleri
| tür | paket | sunucu | not |
|---|---|---|---|
| `debug` | `com.dentifay.app` | dentifay.com (değiştirilebilir) | geliştirme |
| `qa` | `com.dentifay.app.qa` | dentifay.com (değiştirilebilir) | yayınla aynı (R8 + imza), mağazaya yüklenmez |
| `release` | `com.dentifay.app` | yalnız dentifay.com | Play Store |

Test sunucusuna yönlendirme (yalnız debug/qa; değer saklanır, `prod` siler; emülatörde `10.0.2.2` = Mac'in kendisi):

```bash
adb shell am start -n com.dentifay.app/.MainActivity --es DENTIFAY_API_HOST http://10.0.2.2:3001
```

qa yapısında paket adı farklıdır:

```bash
adb shell am start -n com.dentifay.app.qa/com.dentifay.app.MainActivity --es DENTIFAY_API_HOST http://10.0.2.2:3001
```

Giriş kimliği **telefon numarasıdır** (web ile aynı). Yerel test hesapları web projesinin `prisma/seed.js` dosyasındadır.

## Ekran boyutları
- Telefon: alt gezinme çubuğu, dikey yön.
- Genişlik ≥ 600 dp (tablet, katlanabilir açık): yan gezinme rayı, serbest yön; ≥ 840 dp'de Tedavi Planı liste–ayrıntı
  (iki bölme) açılır. Sayfalar ve alt sayfalar geniş ekranda okunur genişlikte ortalanır; kısa yükseklikte kaydırılır.

## Testler
```bash
./gradlew :app:testDebugUnitTest
```
Saf mantık birim testleri (iOS `DentAISoftTests` karşılıkları). `IkonCozumuTest`, kaynakta karşılığı olmayan ikon adı
kalmasın diye denetler (karşılıksız ad ekranda gri bir nokta olarak çizilir).

## İkonlar
Web paneliyle aynı set: kaynak SVG'ler `tools/ikon-kaynak/{dfy,dfycift}` → `python3 tools/svg2vector.py` →
`res/drawable/dfy_*.xml`, `dfycift_*.xml` ve `theme/DfyIconRes.kt`. SF Symbol adı → web adı eşlemesi `theme/DfyIconMap.kt`
(iOS'tan üretilir); iOS'ta sistem ikonuyla çizilen birkaç ad `theme/DfyIcon.kt` içindeki ek eşlemededir.

## Yayın
```bash
./gradlew :app:bundleRelease
```
Çıktı: `app/build/outputs/bundle/release/app-release.aab` → Play Console'a yüklenir. Adım adım rehber:
`play-store/YAYIN-REHBERI.md`; mağaza görselleri `play-store/gorseller/`.

İmza anahtarı: `dentifay-release.jks` + `keystore.properties` (git'e girmez). **Yedekleyin** — kaybolursa
uygulama güncellenemez. Play App Signing açılırsa bu anahtar "yükleme anahtarı" olur.

Her yeni sürümde `app/build.gradle.kts` içindeki `versionCode`'u artırın (şu an `2`, sürüm adı `1.1`).
