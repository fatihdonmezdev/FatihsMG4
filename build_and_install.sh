#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
if [ -d "/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0" ]; then
    BUILD_TOOLS="/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0"
elif [ -d "/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0" ]; then
    BUILD_TOOLS="/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0"
else
    BUILD_TOOLS="$(find /opt/homebrew -name zipalign 2>/dev/null | head -n 1 | xargs dirname || echo "")"
fi
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYS="$ROOT/tools"

IN="$ROOT/app/build/outputs/apk/unstable/debug/app-unstable-debug.apk"
ALIGNED="$ROOT/app/build/outputs/apk/unstable/debug/app-unstable-debug-aligned.apk"
OUT="$ROOT/FatihsMG4-unstable-platform.apk"

echo "==> 1. Gradle derlemesi yapılıyor (assembleUnstableDebug)"
"$ROOT/gradlew" :app:assembleUnstableDebug

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
    echo "==> 6. Cihaz bağlı, MG4'e yükleniyor (pm install -r -f -d -t)"
    adb push "$OUT" /data/local/tmp/update.apk
    adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
    adb shell am start-foreground-service -n com.evsuite.abrp.unstable/com.evsuite.abrp.ReverseOverlayService || true
    echo "==> MG4'e başarıyla yüklendi ve servis başlatıldı!"
else
    echo "==> Bilgi: ADB cihazı bağlı değil, APK kurulmadı. Cihaz bağlandığında ./install_on_mg4.sh çalıştırabilirsin."
fi
