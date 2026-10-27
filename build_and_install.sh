#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
if [ -d "/Users/tcfdonmez/Android/Sdk/build-tools/36.0.0" ]; then
    BUILD_TOOLS="/Users/tcfdonmez/Android/Sdk/build-tools/36.0.0"
elif [ -d "/Users/tcfdonmez/Android/Sdk/build-tools/35.0.0" ]; then
    BUILD_TOOLS="/Users/tcfdonmez/Android/Sdk/build-tools/35.0.0"
elif [ -d "/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0" ]; then
    BUILD_TOOLS="/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0"
elif [ -d "/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0" ]; then
    BUILD_TOOLS="/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0"
else
    BUILD_TOOLS="$(find /opt/homebrew /Users/tcfdonmez/Android/Sdk -name zipalign 2>/dev/null | head -n 1 | xargs dirname || echo "")"
fi

ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYS="$ROOT/tools"

# Hedef kullanıcı: fatih (varsayılan) veya sami
TARGET_USER="${1:-fatih}"

if [ "$TARGET_USER" = "sami" ] || [ "$TARGET_USER" = "Sami" ]; then
    FLAVOR_TASK="assembleSamiUnstableDebug"
    IN="$ROOT/app/build/outputs/apk/samiUnstable/debug/app-sami-unstable-debug.apk"
    ALIGNED="$ROOT/app/build/outputs/apk/samiUnstable/debug/app-sami-unstable-debug-aligned.apk"
    OUT="$ROOT/SamisMG4-unstable-platform.apk"
    PKG="com.evsuite.abrp.sami.unstable"
    DISPLAY_NAME="SamisMG4 (Sami)"
else
    FLAVOR_TASK="assembleFatihUnstableDebug"
    IN="$ROOT/app/build/outputs/apk/fatihUnstable/debug/app-fatih-unstable-debug.apk"
    ALIGNED="$ROOT/app/build/outputs/apk/fatihUnstable/debug/app-fatih-unstable-debug-aligned.apk"
    OUT="$ROOT/FatihsMG4-unstable-platform.apk"
    PKG="com.evsuite.abrp.unstable"
    DISPLAY_NAME="FatihsMG4 (Fatih)"
fi

echo "=========================================================="
echo "  $DISPLAY_NAME için Derleme ve İmzalama Başlatılıyor...  "
echo "=========================================================="

echo "==> 1. Gradle derlemesi yapılıyor (:app:$FLAVOR_TASK)"
"$ROOT/gradlew" ":app:$FLAVOR_TASK"

echo "==> 2. Zipalign yapılıyor"
"$ZIPALIGN" -f 4 "$IN" "$ALIGNED"

echo "==> 3. SAIC MG4 Platform Anahtarı ile İmzalanıyor"
"$APKSIGNER" sign \
    --key "$KEYS/platform.pk8" \
    --cert "$KEYS/platform.x509.pem" \
    --out "$OUT" \
    "$ALIGNED"

rm -f "$ALIGNED"

echo "==> 4. İmza doğrulanıyor"
"$APKSIGNER" verify --print-certs "$OUT"

echo "==> 5. SHA256 checksum üretiliyor"
shasum -a 256 "$OUT" > "${OUT}.sha256"

echo "==> Başarıyla derlendi ve imzalandı: $OUT"

if command -v adb >/dev/null 2>&1 && adb devices | grep -q 'device$'; then
    echo "==> 6. Cihaz bağlı, MG4'e yükleniyor: $PKG (pm install -r -f -d -t)"
    adb push "$OUT" /data/local/tmp/update.apk
    adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
    adb shell am start-foreground-service -n "$PKG/com.evsuite.abrp.ReverseOverlayService" || true
    echo "==> MG4'e başarıyla yüklendi ve servis başlatıldı!"
else
    echo "==> Bilgi: ADB cihazı bağlı değil, APK kurulmadı. Cihaz bağlandığında ./build_and_install.sh $TARGET_USER ile kurabilirsin."
fi
