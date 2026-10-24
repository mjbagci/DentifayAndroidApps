# Dentifay — Android

`DentAISoft` (iOS, SwiftUI) uygulamasının Jetpack Compose karşılığı. Paket: `com.dentifay.app`, minSdk 26, targetSdk 36.

## Android Studio
Repo kökünü açın (File → Open). Studio `local.properties` dosyasını kendi SDK yolunuzla günceller.
Her ekran dosyasının sonunda `@Preview` vardır.

## Yapı türleri
| tür | sunucu | not |
|---|---|---|
| `debug` | dentifay.com (değiştirilebilir) | geliştirme |
| `qa` | dentifay.com (değiştirilebilir) | yayınla aynı (R8 + imza), mağazaya yüklenmez |
| `release` | yalnız dentifay.com | Play Store |

Test sunucusuna yönlendirme (yalnız debug/qa; değer saklanır, `prod` siler):

```bash
adb shell am start -n com.dentifay.app/.MainActivity --es DENTIFAY_API_HOST http://10.0.2.2:3001
```

## Yayın
```bash
./gradlew :app:bundleRelease
```
Çıktı: `app/build/outputs/bundle/release/app-release.aab` → Play Console'a yüklenir.

İmza anahtarı: `dentifay-release.jks` + `keystore.properties` (git'e girmez). **Yedekleyin** — kaybolursa
uygulama güncellenemez. Play App Signing açılırsa bu anahtar "yükleme anahtarı" olur.

Her yeni sürümde `app/build.gradle.kts` içindeki `versionCode`'u artırın.
