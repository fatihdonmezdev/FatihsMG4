# Installing a Platform-Signed APK on the MG4 Head Unit

This documents the reliable way to install a (platform-signed) APK onto the
MG4 SWI69 head unit (Android 9 / SAIC MT2712) over ADB.

The head unit rejects the standard `adb install` / `pm install` flow with:

```
java.io.IOException: Not allowed to install non-system apps on internal storage
	at com.android.internal.content.PackageHelper.resolveInstallVolume(...)
```

This is not a signature problem — the same `platform.pk8` / `platform.x509.pem`
that signs the already-installed `com.mg4.*` apps signs our APK too (verified by
matching SHA-256 cert digests). The PackageManager simply will not let the
shell UID (2000) stage a non-system package onto internal storage through its
default install path, and `--install-location`, `--force-uuid internal`, `-l`
(forward lock) and session-based `install-create`/`install-write`/`install-commit`
all hit the same wall or fail to open the file (SELinux denies `system_server`
read access to shell-owned files in some flag combinations).

## Prerequisites

- The MG4 head unit connected over ADB (`adb devices` lists it).
- A built APK. For a platform-signed build see
  [`MG4_PLATFORM_BUILD.md`](./MG4_PLATFORM_BUILD.md) (DiPlay) — the same
  `zipalign` + `apksigner sign --key tools/platform.pk8 --cert
  tools/platform.x509.pem` step applies here.
- The platform keys in `tools/` (`platform.pk8`, `platform.x509.pem`).

## The Install Command

The only flag combination that works on this unit is:

```bash
adb push <your-app>.apk /data/local/tmp/update.apk
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
```

Flags:

- `-r` — replace the existing package if one is installed.
- `-f` — force install on internal flash (`/data/app/`).
- `-d` — allow a version-code downgrade (debuggable packages only). The
  unstable channel bumps `versionCode` per CI build, so a locally built APK
  is almost always lower than whatever the OTA last pushed; without `-d` the
  install silently fails on a version mismatch.
- `-t` — allow test packages. Unstable/debug builds are marked debuggable,
  which the PackageManager treats as a test package.

The file **must** be pushed as (or renamed to) something under
`/data/local/tmp/`. Other locations (`/sdcard/`, a custom cache dir) fail with
`Can't open file` because `system_server` has no SELinux read access to those
contexts. A failed `pm install` attempt can delete the staged file, so if a
later install says `No such file or directory`, re-push.

## Verifying the Install

```bash
adb shell pm path com.evsuite.abrp.unstable
# -> package:/data/app/com.evsuite.abrp.unstable-.../base.apk
```

To confirm the platform signature matches the system:

```bash
BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
adb pull "$(adb shell pm path com.evsuite.abrp.unstable | sed 's/package://')" /tmp/installed.apk
"$BUILD_TOOLS/apksigner" verify --print-certs /tmp/installed.apk
# SHA-256 cert digest should match tools/platform.x509.pem
```

## Uninstalling

```bash
adb uninstall com.evsuite.abrp.unstable
```

Add `-k` to keep data/cache around (useful when re-installing to test without
wiping the local consumption store).

## End-to-End: Build → Sign → Install

From the repository root:

```bash
# 1. Build
./gradlew :app:assembleUnstableDebug

# 2. Align + platform-sign
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/36.0.0"
IN=app/build/outputs/apk/unstable/debug/app-unstable-debug.apk
ALIGNED=app/build/outputs/apk/unstable/debug/app-unstable-debug-aligned.apk
OUT=FatihsMG4-unstable-platform.apk
"$BT/zipalign" -f 4 "$IN" "$ALIGNED"
"$BT/apksigner" sign --key tools/platform.pk8 --cert tools/platform.x509.pem --out "$OUT" "$ALIGNED"
"$BT/apksigner" verify --verbose "$OUT"

# 3. Install on the head unit
adb push "$OUT" /data/local/tmp/update.apk
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
```
