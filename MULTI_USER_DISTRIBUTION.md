# MG4 Çoklu Kullanıcı & Dağıtım Mimarisi (Fatih & Sami)

Bu doküman, **FatihsMG4** projesinde iki farklı kullanıcı (**Fatih** ve **Sami**) için bağımsız build, APK isimlendirmesi, görsel varlıklar, `installationId` ayrımı ve GitHub OTA güncelleme kanallarının nasıl yapılandırıldığını açıklar.

---

## 1. Mimari Genel Bakış

| Özellik | Fatih Kanalı | Sami Kanalı |
| :--- | :--- | :--- |
| **Uygulama Adı (`app_name`)** | `FatihsMG4` | `SamisMG4` |
| **Paket Adı (`applicationId`)** | `com.evsuite.abrp.unstable` | `com.evsuite.abrp.sami.unstable` |
| **Kurulum Kimliği (`installationId`)** | `226b1614-4d18-48f0-8938-3609cfa3db93` | `Sami` |
| **Çıkış APK Dosyası** | `FatihsMG4-unstable-platform.apk` | `SamisMG4-unstable-platform.apk` |
| **Hakkında / Logo Görseli** | Orijinal MG4 Vektör Logosu | `screenshots/unnamed.jpg` (Özel Profil Fotoğrafı) |
| **Web Dashboard Erişimi** | Kontrol + Tüketim + Şarj (Tam Yetki / Admin) | Yalnızca Tüketim + Şarj (Viewer - Kontrol Gizli) |
| **GitHub OTA Release Kanalı** | `fatihdonmezdev/FatihsMG4` | `fatihdonmezdev/FatihsMG4` (Veya `fatih-unstable` / `sami-unstable` Tagleri) |

---

## 2. Gradle Flavor Yapılandırması (`app/build.gradle`)

Her iki kullanıcıyı aynı kod tabanında temiz bir şekilde yönetmek için Gradle `flavorDimensions` mekanizması kullanılır:

```groovy
flavorDimensions += ["user", "channel"]

productFlavors {
    fatih {
        dimension "user"
        buildConfigField "String", "CONSUMPTION_INSTALLATION_ID", "\"226b1614-4d18-48f0-8938-3609cfa3db93\""
        buildConfigField "String", "OTA_CHANNEL_TAG", "\"unstable\""
        resValue "string", "app_name", "FatihsMG4"
        resValue "string", "about_title", "Fatih'sMG4 Hakkında"
    }
    sami {
        dimension "user"
        applicationIdSuffix ".sami"
        buildConfigField "String", "CONSUMPTION_INSTALLATION_ID", "\"Sami\""
        buildConfigField "String", "OTA_CHANNEL_TAG", "\"sami-unstable\""
        resValue "string", "app_name", "SamisMG4"
        resValue "string", "about_title", "Sami'sMG4 Hakkında"
    }
    unstable {
        dimension "channel"
        applicationIdSuffix ".unstable"
        buildConfigField "boolean", "OTA_ENABLED", "true"
    }
}
```

---

## 3. Görsel Varlık Yönetimi (`screenshots/unnamed.jpg`)

Sami için derleme yapılırken Hakkında (`dialog_about.xml`) veya karşılama ekranındaki görsel `screenshots/unnamed.jpg` üzerinden yüklenir:

* **Fatih build'i:** Orijinal QR / logo vektörü kullanılır (`app/src/fatih/res/`).
* **Sami build'i:** `screenshots/unnamed.jpg` dosyası `app/src/sami/res/drawable/about_custom_avatar.jpg` konumuna kaynak olarak eklenir.

```xml
<!-- dialog_about.xml içinde -->
<ImageView
    android:id="@+id/about_avatar"
    android:layout_width="140dp"
    android:layout_height="140dp"
    android:layout_gravity="center_horizontal"
    android:src="@drawable/about_custom_avatar"
    android:scaleType="centerCrop" />
```

---

## 4. GitHub OTA Güncelleme Kanalları (`OtaUpdater.java`)

Araçların birbirinin APK'sını indirip üzerine kurmasını engellemek için OTA kontrolü **kullanıcı etiketine (`OTA_CHANNEL_TAG`)** göre ayrıştırılır:

1. **Fatih Aracı OTA Kontrolü:**
   * GitHub Releases API üzerinden `unstable` tag'ine bakar.
   * `FatihsMG4-unstable-platform.apk` dosyasını ve SHA256 özetini indirip kurar.

2. **Sami Aracı OTA Kontrolü:**
   * GitHub Releases API üzerinden `sami-unstable` tag'ine (veya asset adına) bakar:
   ```java
   String targetTag = BuildConfig.OTA_CHANNEL_TAG; // "sami-unstable"
   String releaseUrl = "https://api.github.com/repos/fatihdonmezdev/FatihsMG4/releases/tags/" + targetTag;
   ```
   * Sadece `SamisMG4-unstable-platform.apk` dosyasını doğrular ve günceller.

---

## 5. Derleme & İmzalama Komutları

### Fatih İçin Derleme & Kurulum:
```bash
./gradlew :app:assembleFatihUnstableDebug
# Çıktı: app-fatih-unstable-debug.apk -> FatihsMG4-unstable-platform.apk
./build_and_install.sh fatih
```

### Sami İçin Derleme & Kurulum:
```bash
./gradlew :app:assembleSamiUnstableDebug
# Çıktı: app-sami-unstable-debug.apk -> SamisMG4-unstable-platform.apk
./build_and_install.sh sami
```

Her iki APK da derleme sonrasında SAIC MG4 multimedya platform anahtarlarıyla (`platform.pk8` & `platform.x509.pem`) imzalanır.
