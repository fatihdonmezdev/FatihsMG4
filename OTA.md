# Publishing an OTA release

How to ship an update the car will install by itself. Written for whoever does this
next — probably me, having forgotten the details.

## First upgrade from 2.2.3 or older

Install the platform-signed 2.2.7 APK manually once if the car still runs 2.2.3 or older.
Those builds use the old `pm install` path; publishing a newer APK does not repair the
installer already running on the car. Builds from 2.2.4 use `PackageInstaller` sessions.
Keep the unstable package ID, system shared UID and signing certificate when upgrading.

The 2.2.5 build removes window automation. It also distinguishes failed release checks
from a successful check with no newer release. On 2026-09-29, GitHub still published only
2.2.3 at the last live check. The local 2.2.7 build includes these fixes and the redesigned
UI; it becomes available to OTA only after publication as a pre-release asset named
`FatihsMG4-unstable-2.2.7.apk`, together with
`FatihsMG4-unstable-2.2.7.apk.sha256`.

The vehicle artifact uses `com.evsuite.abrp.unstable`, `android.uid.system`, and the
DriveHub_Kamera platform key. Its certificate SHA-256 must be
`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.
The stable build used for a Samsung UI preview is a separate package without OTA or the
vehicle's shared UID; do not use that preview APK for the MG4 upgrade.

## What the updater actually looks for

`OtaUpdater.check()` reads `https://api.github.com/repos/fatihdonmezdev/MG4ABRP/releases`
and walks **every** entry, keeping the highest version rather than the first match. A
release is a candidate only if all of these hold:

| Requirement | Where it is enforced | What happens otherwise |
|---|---|---|
| Marked **pre-release** | `release.optBoolean("prerelease")` | Skipped entirely |
| Asset name contains `unstable` | `name.contains("unstable")` | Asset ignored |
| Asset name ends `-<version>.apk` | `ASSET_VERSION` regex | No version, asset ignored |
| Version strictly higher than installed | `isNewer()` | Nothing to do, reports "Güncel" |
| Download host on the allowlist | `isAllowedUrl()` | Refused and logged |
| APK signed with the **same certificate** as the running app | `signatureMatchesRunningApp()` | Deleted, never installed |
| Matching `.sha256` asset or `SHA256SUMS` entry | `fetchExpectedSha256()` | Update ignored/refused |

The last one is the one that bites. The car runs a platform-signed build; an APK signed
with a debug key — or anyone else's key — is downloaded, rejected and deleted. Sign every
release with the same platform key or the update silently never lands.

The version comes from the **asset filename**, not the git tag. The tag can stay
`unstable` forever and be moved; the filename is what identifies a build.

## Release checklist

1. **Raise the version.** `app/build.gradle`, `defaultConfig`:

   ```gradle
   versionCode 24          // must increase — Android orders updates by this, not by name
   versionName "2.2.7"
   ```

   The unstable flavour appends its own suffix, so `2.2.7` becomes `2.2.7.0-unstable`.
   Check what the installed build reports before picking a number: the app's About
   dialog shows it, and `isNewer` compares numerically segment by segment.

2. **Build.**

   ```bash
   ./gradlew :app:assembleUnstableDebug
   ```

3. **Sign with the platform key.** Options come *before* the input APK — apksigner
   rejects the other order.

   ```bash
   BT=~/Android/Sdk/build-tools/35.0.0
   KEYS=~/Desktop/Coding/MG/DriveHub_Kamera/tools

   "$BT/zipalign" -f 4 \
     app/build/outputs/apk/unstable/debug/app-unstable-debug.apk aligned.apk

   "$BT/apksigner" sign \
     --key  "$KEYS/platform.pk8" \
     --cert "$KEYS/platform.x509.pem" \
     --out  FatihsMG4-unstable-2.2.7.apk \
     aligned.apk

   rm aligned.apk
   ```

   The output filename **is** the version the updater reads. Name it
   `FatihsMG4-unstable-<version>.apk`, then create its required integrity sidecar:

   ```bash
   shasum -a 256 FatihsMG4-unstable-2.2.7.apk \
     > FatihsMG4-unstable-2.2.7.apk.sha256
   ```

4. **Verify the signer** before uploading. It must say `CN=Android`:

   ```bash
   "$BT/apksigner" verify --print-certs FatihsMG4-unstable-2.2.7.apk
   ```

5. **Publish.** On github.com/fatihdonmezdev/MG4ABRP → Releases → Draft a new release:
   - tag: `unstable` (reuse it; the rolling tag is the design)
   - **tick "Set as a pre-release"** ← the whole thing is inert without this
   - attach the signed APK and its `.sha256` sidecar

6. **Install it.** Open the app on the head unit and press the refresh button in the top
   bar. The dialog shows download progress, verifies SHA-256, then reports that the
   PackageInstaller session has started.

## When it says something else

| Toast | Meaning |
|---|---|
| `Güncel (2.2.2.0-unstable)` | No release beat the installed version. Check the pre-release tick and the asset filename. |
| `Güncelleme reddedildi: SHA-256 doğrulaması yok` | APK has no matching sidecar, or the sidecar is invalid/unreachable. |
| `İndirme veya SHA-256 doğrulaması başarısız` | Download failed or its bytes do not match the published hash. |
| `Kuruluyor: 2.2.3 — ekrandaki onayı bekleyin` | The session was committed. Not done yet: watch for the platform's own toast, or a confirmation dialog to accept. |
| `Kurulum reddedildi: imza uyuşmuyor` | Genuinely the wrong key — rebuild and re-sign with `platform.pk8`. Unlike the old catch-all, this one means what it says. |
| `Kurulum reddedildi: APK okunamadı` | The archive does not parse, or it is built for another package. |
| `Kurulum başlatılamadı … Log sayfasına bakın` | The session could not be created or committed. `adb logcat -s EVABRP.Update`. |
| `Kurulum başarısız: INSTALL_FAILED_…` | From `OtaInstallResultReceiver` — the package manager's own words for why it refused. |
| `Güncelleme kontrolü başarısız` | The check threw. `adb logcat -s EVABRP.Update OtaUpdater` if a cable is available; otherwise the in-app Log page. |

## How the install works, and why it is not `pm install`

`OtaUpdater.install()` used to shell out to `/system/bin/pm install -r`. On this head unit
that fails every time, and the reason is worth writing down because the symptom pointed
somewhere else entirely.

The downloaded APK lives in `context.getCacheDir()`. `pm` does not install the file itself
— it passes the path to the package manager service, which opens it from *its* process and
SELinux context, where an app's private cache is not readable. The install failed, and
because `install()` returned a bare boolean, the caller reported the only failure it knew
how to name: `Kurulum reddedildi (imza uyuşmuyor?)`. A correctly signed build, refused with
a signature error it had not earned. The `pm` output that would have said so was captured
and thrown away.

It now uses the platform `PackageInstaller`: we open the archive ourselves and stream it
into a session, so no second process ever has to read our cache. The approach is taken from
[merthankaraman/DriveHub_Dort](https://github.com/merthankaraman/DriveHub_Dort), which hit
the same wall on the same hardware.

Two consequences for anyone reading a failure:

- **Committing a session is not installing.** `commit()` is asynchronous. The refresh button
  can only report that an install *started*; the verdict arrives at
  `OtaInstallResultReceiver` and appears as its own toast.
- **`STATUS_PENDING_USER_ACTION` is not an error.** The platform is asking the driver to
  confirm. The receiver launches the confirmation activity the platform hands it.

Still unverified on the car: whether this build installs silently or raises that
confirmation. Either way it installs, which the old path did not.

## Why installation is manual

`UpdateHook.checkInBackground(this)` performs an availability-only check at app start and
shows a notification toast when a complete APK + hash pair exists. It never downloads or
installs. The refresh button is the only trigger for download, verification and installation.

The upload service does **not** check for updates on its tick. Installing a new APK
restarts the process, and doing that mid-drive would interrupt the telemetry the service
exists to send.
