# Dentifay Android — Google Play Yayın Rehberi (şirket hesabı)

Sırayla ilerleyin. `▶ KOPYALA` kutularındaki metinler forma doğrudan yapıştırılabilir.
`⚠ SİZDEN` = yalnız sizin bilebileceğiniz / yapabileceğiniz şey.

---

## 0) Şirket künyesi (formlarda hep bunlar)

| Alan | Değer |
|---|---|
| Resmî unvan | **Dentifay Technologia Bilişim Sistemleri A.Ş.** |
| Geliştirici adı (mağazada görünen) | **Dentifay** |
| Adres | Yeşilce Mah. Dalgıç Sk. Dome No: 3/5 İç Kapı No: 1 |
| İlçe / İl / Posta kodu | Kağıthane / İstanbul / 34418 |
| Ülke | Türkiye |
| MERSİS | 0292142906000001 |
| Vergi no / dairesi | 2921429060 / Maslak V.D. |
| Kuruluş tarihi | 7 Ağustos 2026 |
| Web sitesi | https://dentifay.com |
| İletişim e-postası | misa@dentifay.com |
| Telefon | +90 532 669 17 38 |
| Yetkili kişi | Muhammet Isa Bagci — Kurucu & CEO |
| Uygulama paket adı | `com.dentifay.app` (yüklendikten sonra DEĞİŞTİRİLEMEZ) |

> Kaynak: web projesindeki `backend/lib/landing-shell.js` (vergi levhası + ticaret sicilinden alınmış) ve
> `Desktop/Dentifay Technologia/Dentifay-MASTER-Application-File-v2.md`.
> Master dosyada "Ltd/AŞ" yazıyor; resmî kayıt **A.Ş.** — her yerde A.Ş. kullanın.

---

## 1) Yayından ÖNCE kapanması gereken 4 engel (bunlar olmadan Google reddeder)

1. **Gizlilik politikası sayfası canlı değil.** `https://dentifay.com/gizlilik` şu an 404 veriyor.
   Metin web projesinde hazır (`backend/lib/legal-docs/gizlilik.js`). ⚠ SİZDEN: sunucuyu yayınlayan
   arkadaşınız (Furkan) bu metni **giriş gerektirmeyen** bir adreste yayınlasın: `https://dentifay.com/gizlilik`.
2. **Hesap silme sayfası.** Google, giriş gerektiren her uygulama için web üzerinden hesap silme talebi
   adresi istiyor. ⚠ SİZDEN: `https://dentifay.com/hesap-silme` adresinde kısa bir sayfa açılsın:
   ▶ KOPYALA (sayfa metni):
   > **Dentifay hesabınızı silme**
   > Hesabınızın ve kişisel verilerinizin silinmesini istiyorsanız kayıtlı e-posta adresinizden
   > **kvkk@dentifay.com** adresine "Hesap silme talebi" konulu bir e-posta gönderin. Talebiniz en geç 30 gün
   > içinde sonuçlandırılır. Hesap silindiğinde kullanıcı bilgileriniz silinir; kliniğin yasal saklama
   > yükümlülüğü olan hasta ve fatura kayıtları ilgili mevzuatın öngördüğü süre boyunca saklanır.
   > Dentifay Technologia Bilişim Sistemleri A.Ş. · Kağıthane/İstanbul
3. **Canlı sunucu güncel olmalı.** Yayın sürümü yalnız `dentifay.com`'a bağlanır. Mobilin kullandığı uçlar
   (misafir deneme `/api/public/trial`, gülüş simülasyonu, röntgen servisi, asistan) canlıda çalışmalı.
   Şu an canlıda `/api/public/trial` 404 dönüyor. ⚠ SİZDEN: son backend'i canlıya alın.
4. **İnceleme için demo hesabı.** Uygulama giriş istediğinden Google inceleme ekibine kullanıcı adı/şifre
   verilmeli. ⚠ SİZDEN: canlıda örnek verili bir klinik + bir kullanıcı açın
   (ör. `play-review@dentifay.com`). Gerçek hasta verisi OLMASIN.

---

## 2) D-U-N-S numarası (şirket hesabı için zorunlu, ÜCRETSİZ)

Google şirket hesaplarında D-U-N-S ister. Alması 1–30 iş günü sürebilir — **ilk bu adımı başlatın.**

1. Türkiye'de D-U-N-S'i yalnız **CRIF Türkiye** (D&B'nin yetkili ortağı) verir:
   https://www.crif.com.tr/duns-numarasi/ → sayfanın altındaki form · Talep nedeni: **Google Developer**
   (ya da e-posta: **dunstalep@crif.com**). Bu bir ön başvurudur; CRIF e-postayla dönüp evrak ister.
2. İstenecek evraklar (hazır tutun): **vergi levhası**, **Ticaret Sicil Gazetesi** (kuruluş ilanı),
   gerekirse imza sirküleri. Evrak gidince CRIF teslim tarihini bildirir.
3. Gelen D-U-N-S kaydındaki unvan/adres yazımını not alın — Play Console'da **birebir aynısını** yazın.
4. D&B sizi arayabilir veya vergi levhası / ticaret sicil gazetesi isteyebilir — hazır tutun.

---

## 3) Google Play Console şirket hesabı (25 $, tek seferlik)

⚠ SİZDEN — bu adımı sizin yapmanız gerekiyor (hesap açma ve ödeme).

1. Şirket için kullanılacak Google hesabıyla girin (öneri: `misa@dentifay.com` bir Google hesabına
   bağlı değilse önce **accounts.google.com → Hesap oluştur → "İşletmem için"** ile açın). 2 adımlı doğrulama açık olsun.
2. https://play.google.com/console/signup → **"Bir kuruluş"** (Organization) seçin.
3. **Ödeme profili:** yeni ödeme profili → tür **"Kuruluş"** → §0'daki unvan, adres, vergi bilgisi.
4. **Kuruluş bilgileri:** D-U-N-S numarası, kuruluş adı, adres, telefon, web sitesi (§0).
5. **Geliştirici hesabı:** Geliştirici adı `Dentifay`, iletişim e-postası `misa@dentifay.com`,
   iletişim telefonu, web sitesi `https://dentifay.com`.
6. **Web sitesi doğrulama:** Play Console, dentifay.com'un sizin olduğunu Google Search Console ile
   doğrulamanızı isteyebilir → Search Console'da "Alan adı" ekleyin, verilen TXT kaydını alan adı
   DNS'ine ekleyin (DNS'i kim yönetiyorsa).
7. 25 $ kayıt ücreti (kartla).
8. Kimlik/kuruluş doğrulaması: birkaç gün sürer. E-posta ve telefon doğrulama kodları gelir.

> Şirket hesapları "12 test kullanıcısı × 14 gün kapalı test" şartından MUAFTIR — doğrudan üretime çıkabilirsiniz.

---

## 4) Uygulamayı oluşturma

Play Console → **Uygulama oluştur**:

| Alan | Değer |
|---|---|
| Uygulama adı | `Dentifay – Klinik Yönetimi` |
| Varsayılan dil | Türkçe – tr-TR |
| Uygulama mı oyun mu | Uygulama |
| Ücretsiz mi ücretli mi | Ücretsiz |
| Beyanlar | Geliştirici Program Politikaları ✓ · ABD ihracat yasaları ✓ |

---

## 5) Mağaza girişi (Ana mağaza girişi)

**Uygulama adı (≤30):** `Dentifay – Klinik Yönetimi`

**Kısa açıklama (≤80)** ▶ KOPYALA:
> Diş klinikleri için randevu, stok, röntgen analizi ve gülüş simülasyonu.

**Tam açıklama (≤4000)** ▶ KOPYALA:
> Dentifay, diş kliniklerinin günlük işini tek yerden yürüten klinik yönetim yazılımının mobil uygulamasıdır. Dentifay hesabı olan klinik ekipleri için tasarlanmıştır.
>
> RANDEVULAR
> • Günün programını hekim hekim takvim görünümünde görün
> • Randevu ayrıntısı: hasta, işlem, şikâyet, notlar
> • Tek dokunuşla "Hasta geldi", tedaviye alma, iptal ve gelmedi işaretleme
> • Hastayı arama ya da mesaj gönderme
>
> STOK YÖNETİMİ
> • Tüm malzemeler, düşük ve tükenen stok uyarıları
> • Kamerayla barkod okutarak ürün bulma
>
> ANALİZ MERKEZİ
> • Röntgen Analizi: panoramik röntgeni çekin ya da yükleyin; dişler FDI numarasıyla işaretlenir, her bulgu için güven skoru ve tedavi önerisi çıkar. Karar her zaman hekimindir.
> • Gülüş Simülasyonu: rehberli çekimle hastanın gülüşünü fotoğraflayın, tasarımı seçin; tedavi sonrası görünümün önce/sonra simülasyonunu hastayla paylaşın.
> • Sesli Tedavi Planı: planı sesle söyleyin, kalemlere ayrılsın ve onayınızla kaydedilsin.
> • Dentifay Asistan: kliniğinizin verisi hakkında soru sorun ("Bu ayın cirosu ne?"), yanıtı tablo, grafik ya da Excel olarak alın. Sesli konuşma modu da vardır.
>
> KREDİLER
> • Kalan kredi, kalite başına maliyet ve paketler
> • Kredi yönetimi: kim, ne zaman, ne kadar kullandı; kişi bazlı aylık limitler
>
> GÜVENLİK
> • Yüz tanıma / parmak iziyle hızlı giriş
> • Yetkiler web panelindeki rol ayarlarına göre sunucuda uygulanır
> • Veriler şifreli bağlantıyla iletilir
>
> GİRİŞ YAPMADAN DENEYİN
> Röntgen Analizi ve Gülüş Simülasyonu'nu giriş yapmadan sınırlı sayıda ücretsiz deneyebilirsiniz.
>
> Önemli: Röntgen analizi ve gülüş simülasyonu hekime yardımcı araçlardır; teşhis ve tedavi kararı her zaman hekime aittir. Simülasyon sonuçları gerçek tedavi sonucundan farklılık gösterebilir.
>
> Dentifay Technologia Bilişim Sistemleri A.Ş. · https://dentifay.com

**Grafikler** (`play-store/gorseller/`):
| Öğe | Dosya | Durum |
|---|---|---|
| Uygulama simgesi 512×512 | `simge-512.png` | ✅ hazır |
| Öne çıkan grafik 1024×500 | `kapak-1024x500.png` | ✅ hazır |
| Telefon ekran görüntüleri (2–8 adet, 9:16) | — | ⏳ hazırlanacak |

**Kategori ve iletişim** (Mağaza ayarları):
| Alan | Değer |
|---|---|
| Uygulama kategorisi | **Tıp** (Medical) — alternatif: İş (Business) |
| Etiketler | Klinik yönetimi, Sağlık, Verimlilik |
| E-posta | misa@dentifay.com |
| Telefon | +90 532 669 17 38 |
| Web sitesi | https://dentifay.com |

---

## 6) Uygulama içeriği (Politika → Uygulama içeriği) — her bölüm tek tek

**6.1 Gizlilik politikası:** `https://dentifay.com/gizlilik` (§1-1 yayınlandıktan sonra)

**6.2 Uygulamaya erişim:** "Tüm işlevler veya bazı işlevler kısıtlı" → talimat ekle:
- Ad: `İnceleme hesabı`
- Kullanıcı adı: ⚠ SİZDEN (§1-4) · Şifre: ⚠ SİZDEN
- Diğer talimatlar ▶ KOPYALA:
  > Uygulama, Dentifay klinik hesabı olan ekipler içindir. Verilen hesapla giriş yapın. Giriş ekranındaki "Ücretsiz deneyin" kartları (Röntgen Analizi, Gülüş Simülasyonu) giriş yapmadan da denenebilir. Kamera izni röntgen ve gülüş fotoğrafı, mikrofon izni sesli tedavi planı ve sesli asistan içindir.

**6.3 Reklamlar:** Hayır, uygulamada reklam yok.

**6.4 İçerik derecelendirmesi (IARC anketi):**
- E-posta: misa@dentifay.com · Kategori: **"Tüm diğer uygulama türleri"** (Referans, Haber veya Eğitim değil)
- Şiddet, cinsellik, küfür, uyuşturucu, kumar: hepsi **Hayır**
- Kullanıcılar birbiriyle iletişim kurabiliyor mu: **Hayır** (yalnız kliniğin kendi asistanı)
- Kullanıcı tarafından oluşturulan içerik paylaşılıyor mu: **Hayır**
- Konum paylaşımı: **Hayır** · Dijital ürün satın alma: **Hayır** (kredi faturaya yansır, uygulama içi ödeme yok)
- Beklenen sonuç: **3+ / Herkes**

**6.5 Hedef kitle:** Yaş grubu yalnız **18 ve üzeri**. Çocuklara hitap ediyor mu: Hayır.

**6.6 Haber uygulaması:** Hayır. **6.7 COVID-19 uygulaması:** Hayır. **6.8 Devlet uygulaması:** Hayır.
**6.9 Finansal özellikler:** "Uygulamam finansal özellik sunmuyor".

**6.10 Sağlık uygulamaları beyanı:** işaretleyin →
- ✅ "Sağlık hizmeti sağlayıcıları için klinik yönetimi / hasta kayıtları"
- ✅ "Tıbbi görüntü / teşhise yardımcı araç" (röntgen analizi)
- Tıbbi cihaz düzenlemesine tabi mi sorulursa ⚠ SİZDEN: hukuki/düzenleyici görüş (röntgen analizi
  "karar destek" olarak sunuluyor; mağaza metninde "karar hekimindir" ifadesi var).

**6.11 Veri güvenliği formu** (Data safety):
- Veri topluyor veya paylaşıyor mu: **Evet**
- Aktarım sırasında şifreleniyor mu: **Evet**
- Kullanıcılar veri silme talebinde bulunabilir mi: **Evet** → URL `https://dentifay.com/hesap-silme`
- Hesap oluşturma: "Kullanıcılar uygulama dışında oluşturulan hesapla giriş yapar" (hesaplar web panelde açılır)

Toplanan veri türleri (hepsi **Toplanıyor**, **Paylaşılmıyor**, **Zorunlu**, amaç: **Uygulama işlevselliği** + **Hesap yönetimi**):
| Kategori | Tür | Not |
|---|---|---|
| Kişisel bilgiler | Ad, E-posta, Telefon numarası, Kullanıcı kimlikleri | kullanıcı ve hasta kayıtları |
| Sağlık ve fitness | Sağlık bilgileri | randevu notları, tedavi planı, röntgen bulguları |
| Fotoğraflar ve videolar | Fotoğraflar | röntgen ve gülüş fotoğrafları (isteğe bağlı) |
| Ses dosyaları | Ses kayıtları / Diğer ses | **İşaretlemeyin** — konuşma cihazda metne çevrilir, ses gönderilmez |
| Uygulama etkinliği | Diğer kullanıcı tarafından oluşturulan içerik | asistana yazılan sorular |
| Finansal bilgiler | Satın alma geçmişi | kredi paketi alımları (faturaya yansır) |

> Gülüş görselinin işlendiği yapay zekâ sağlayıcısı Dentifay adına çalışan hizmet sağlayıcıdır →
> Google tanımında "paylaşım" sayılmaz. Röntgen modeli Dentifay'in kendi sunucusunda çalışır.

---

## 7) Sürümü yayınlama

1. **Üretim** (Production) → **Yeni sürüm oluştur**.
2. **Play Uygulama İmzalama**: "Devam et / Google tarafından oluşturulan anahtarı kullan" → kabul.
   (Bizim `dentifay-release.jks` anahtarımız "yükleme anahtarı" olur.)
3. App bundle yükleyin: `app/build/outputs/bundle/release/app-release.aab`
4. Sürüm adı: `1.0 (1)` · Sürüm notları ▶ KOPYALA:
   ```
   <tr-TR>
   Dentifay Android ilk sürüm: randevular, stok, röntgen analizi, gülüş simülasyonu, sesli tedavi planı, Dentifay Asistan ve kredi yönetimi.
   </tr-TR>
   ```
5. **Ülkeler/bölgeler:** Türkiye (sonra istediklerinizi ekleyin).
6. **İncelemeye gönder.** İlk inceleme genelde 1–7 gün.

---

## 8) Kontrol listesi

- [ ] D-U-N-S numarası alındı
- [ ] Play Console şirket hesabı açıldı, doğrulandı, 25 $ ödendi
- [ ] dentifay.com Search Console'da doğrulandı (istenirse)
- [ ] `dentifay.com/gizlilik` canlı
- [ ] `dentifay.com/hesap-silme` canlı
- [ ] Canlı sunucu son sürümde (misafir deneme, röntgen, gülüş, asistan çalışıyor)
- [ ] İnceleme demo hesabı hazır
- [ ] Ekran görüntüleri yüklendi
- [ ] Mağaza girişi + Uygulama içeriği bölümlerinin hepsi yeşil tik
- [ ] `.aab` yüklendi, incelemeye gönderildi
- [ ] `dentifay-release.jks` + `keystore.properties` yedeklendi (USB + şifre yöneticisi)

Sonraki güncellemelerde: `app/build.gradle.kts` → `versionCode` +1, `versionName` güncelle,
`./gradlew :app:bundleRelease`, yeni `.aab`'ı Üretim'e yükle.

---

## 9) Yayınlamadan bir arkadaşın telefonunda denemek

Hazır dosya: **Masaüstü → `Dentifay-Android-1.0.apk`** (yayın sürümü, imzalı, 65 MB).
Bu sürüm yalnız **canlı sunucuya (dentifay.com)** bağlanır → arkadaşınızın canlıda bir Dentifay
hesabı olmalı (ya da giriş yapmadan "Ücretsiz deneyin" kartlarını dener).

### Yol A — Dosyayı gönderip kurmak (en kolayı, kablo yok)
1. APK'yı arkadaşınıza gönderin: WhatsApp'ta **"Belge"** olarak (fotoğraf/medya olarak DEĞİL),
   ya da Google Drive / Telegram / e-posta.
2. Arkadaşınız telefonda dosyaya dokunur.
3. "Bilinmeyen uygulamaları yükleme" uyarısı çıkar → **Ayarlar**'a dokunur →
   dosyayı açtığı uygulama (WhatsApp / Dosyalar / Chrome) için **"Bu kaynağa izin ver"** açılır → geri döner.
4. **Yükle**'ye dokunur.
5. Play Protect "Bu uygulama taranmadı / tanınmayan geliştirici" derse → **"Ayrıntılar" → "Yine de yükle"**.
   (Mağazada olmayan her uygulama için çıkar, normaldir. "Taramaya gönder" denirse "Gönderme" seçilebilir.)
6. Uygulama ana ekranda **Dentifay** simgesiyle görünür. Kamera / mikrofon izinlerini ilk kullanımda ister.

Güncelleme: yeni APK'yı aynı şekilde gönderip kurmak yeterli (veriler silinmez) — çünkü hep aynı imza
anahtarıyla derleniyor. Kaldırmak: simgeye basılı tut → Kaldır.

### Yol B — Kabloyla Android Studio'dan (geliştirici gibi, en hızlı deneme döngüsü)
1. Arkadaşın telefonunda: **Ayarlar → Telefon hakkında → Yapı numarası**'na 7 kez dokunun
   ("Artık geliştiricisiniz").
2. **Ayarlar → Sistem → Geliştirici seçenekleri → USB hata ayıklama** açık.
3. Telefonu Mac'e USB ile bağlayın; telefonda "USB hata ayıklamaya izin verilsin mi?" → **Her zaman izin ver**.
4. Android Studio → üstteki cihaz listesinde telefon görünür → **▶ Run**.
   Bu "debug" sürümdür: daha yavaştır ama yerel test sunucusuna yönlendirilebilir.
5. Yerel test sunucusuna bağlamak isterseniz (aynı Wi-Fi'de): Mac'te web projesinden
   `LISTEN_HOST=0.0.0.0 node backend/server.js` çalıştırın, sonra:
   ```
   adb shell am start -n com.dentifay.app/.MainActivity --es DENTIFAY_API_HOST http://<Mac'in Wi-Fi IP'si>:3001
   ```
   (Mac IP'si: `ipconfig getifaddr en0`). Canlıya geri dönmek için aynı komutta değer `prod`.
   Giriş için web projesindeki `prisma/seed.js` test hesaplarını kullanın.

### Yol C — Play Console "Dahili test" (hesap açıldıktan sonra, en düzgünü)
1. Play Console → Uygulamanız → **Test → Dahili test** → **Testçiler** sekmesi → e-posta listesi oluşturun,
   arkadaşınızın Gmail adresini ekleyin (en fazla 100 kişi).
2. **Yeni sürüm oluştur** → aynı `.aab`'ı yükleyin → **İncelemeye gönder / Kullanıma sun**
   (dahili test incelemesi genelde birkaç dakika–saat).
3. "Testçiler" sekmesindeki **katılım bağlantısını** arkadaşınıza gönderin → bağlantıyı açıp
   "Test kullanıcısı ol" der → Play Store'dan normal uygulama gibi indirir, güncellemeleri otomatik alır.
   Uyarı ekranı ve "bilinmeyen kaynak" izni gerekmez.

### Arkadaşınızın denemesini istediğim şeyler (emülatörde test edilemeyenler)
- Gerçek bir panoramik röntgen fotoğrafıyla **Röntgen Analizi** (sonuç ekranı)
- **Gülüş Simülasyonu**: rehberli kamera çekimi + 1 adet "Orta" kalite üretim (4 kredi)
- **Sesli Tedavi Planı** ve **sesli asistan** (gerçek konuşma)
- Parmak izi / yüz tanıma ile giriş
- Uygulamanın akıcılığı (yayın sürümü emülatörden çok daha hızlıdır)
