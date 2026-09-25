package com.evsuite.abrp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * OTA update policy — unstable channel only. The updater installs code on a vehicle, so the
 * origin checks are the part that must not regress.
 */
public class OtaUpdaterTest {

    // ---- URL allowlist ----

    @Test
    public void httpsFromAnAllowedHostIsAccepted() {
        assertTrue(OtaUpdater.isAllowedUrl(
                "https://github.com/malys/EVABRPUploader/releases/download/v1.2.0/app.apk"));
        assertTrue(OtaUpdater.isAllowedUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/x.apk"));
    }

    @Test
    public void httpIsRejectedEvenOnAnAllowedHost() {
        assertFalse(OtaUpdater.isAllowedUrl("http://github.com/malys/app.apk"));
    }

    @Test
    public void foreignHostsAreRejected() {
        assertFalse(OtaUpdater.isAllowedUrl("https://evil.example.com/app.apk"));
    }

    @Test
    public void lookalikeHostsAreRejected() {
        // Exact match, never a suffix test.
        assertFalse(OtaUpdater.isAllowedUrl("https://github.com.attacker.net/app.apk"));
        assertFalse(OtaUpdater.isAllowedUrl("https://evil-github.com/app.apk"));
        assertFalse(OtaUpdater.isAllowedUrl("https://notgithub.com/app.apk"));
    }

    @Test
    public void unparsableOrNonHttpUrlsAreRejected() {
        assertFalse(OtaUpdater.isAllowedUrl(""));
        assertFalse(OtaUpdater.isAllowedUrl("not a url"));
        assertFalse(OtaUpdater.isAllowedUrl("ftp://github.com/app.apk"));
        assertFalse(OtaUpdater.isAllowedUrl("file:///sdcard/Download/app.apk"));
    }

    @Test
    public void hostMatchingIgnoresCase() {
        assertTrue(OtaUpdater.isAllowedUrl("https://GitHub.com/malys/app.apk"));
        assertTrue(OtaUpdater.isAllowedUrl("HTTPS://github.com/malys/app.apk"));
    }

    // ---- Version comparison ----

    @Test
    public void higherVersionsAreNewer() {
        assertTrue(OtaUpdater.isNewer("1.2.1", "1.2.0"));
        assertTrue(OtaUpdater.isNewer("1.3.0", "1.2.9"));
        assertTrue(OtaUpdater.isNewer("2.0.0", "1.99.99"));
    }

    @Test
    public void equalOrLowerVersionsAreNotNewer() {
        assertFalse(OtaUpdater.isNewer("1.2.0", "1.2.0"));
        assertFalse(OtaUpdater.isNewer("1.1.9", "1.2.0"));
    }

    @Test
    public void theUnstableSuffixIsIgnored() {
        // The installed unstable reports "1.0.42-unstable"; that must not read as older
        // than the "v1.0.42" tag it was built from, or it would update to itself forever.
        assertFalse(OtaUpdater.isNewer("v1.0.42", "1.0.42-unstable"));
        assertTrue(OtaUpdater.isNewer("v1.0.43", "1.0.42-unstable"));
    }

    @Test
    public void unstableBuildNumbersCompareAsVersions() {
        // The CI names unstable assets "<app>-unstable-<base>.<run>.apk" precisely so this
        // works. A name that parses to 0 would never look newer than an installed build,
        // so the channel would silently never update.
        assertTrue(OtaUpdater.isNewer("v1.0.100", "1.0.99-unstable"));
        assertFalse(OtaUpdater.isNewer("v1.0.41", "1.0.42-unstable"));
        assertArrayEquals(new int[]{0}, OtaUpdater.segments("unstable-43"));
    }

    @Test
    public void versionIsReadFromTheAssetName() {
        // The release tag is the constant "unstable", so the asset name carries the build.
        assertEquals("1.0.42", OtaUpdater.versionFromAssetName("EVABRPUploader-unstable-1.0.42.apk"));
        assertEquals("1.0.100", OtaUpdater.versionFromAssetName("EVABRPUploader-unstable-1.0.100.APK"));
    }

    @Test
    public void assetNameWithoutAVersionIsIgnored() {
        assertNull(OtaUpdater.versionFromAssetName("EVABRPUploader-unstable.apk"));
        assertNull(OtaUpdater.versionFromAssetName("unstable"));
    }

    @Test
    public void versionCoreParsingKeepsSegmentPositions() {
        assertArrayEquals(new int[]{1, 2, 3}, OtaUpdater.segments("v1.2.3"));
        assertArrayEquals(new int[]{1, 2, 3}, OtaUpdater.segments("1.2.3-unstable"));
        assertArrayEquals(new int[]{1, 2, 3}, OtaUpdater.segments("1.2.3+build7"));
        // A non-numeric segment is 0, not dropped.
        assertArrayEquals(new int[]{1, 0, 5}, OtaUpdater.segments("1.x.5"));
    }

    // The `pm install -r` exit-code test that stood here is gone with the shell-out it
    // covered. Installation is now a PackageInstaller session, whose every step needs a real
    // PackageManager — there is no pure part of it left to exercise on the JVM. What used to
    // be asserted here is now reported by OtaInstallResultReceiver, on the car.
}
