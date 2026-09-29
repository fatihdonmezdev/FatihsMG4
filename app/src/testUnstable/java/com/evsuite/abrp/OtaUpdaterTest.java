package com.evsuite.abrp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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

    @Test
    public void newestAssetWinsEvenWhenItIsLaterInTheSameRelease() throws Exception {
        JSONArray releases = new JSONArray().put(release(true,
                asset("2.2.3"), asset("2.2.6"), asset("2.2.4")))
                .put(release(true, asset("2.2.5")));
        OtaUpdater.Update update = OtaUpdater.selectUpdate(releases, "2.2.2.0-unstable");
        assertEquals("2.2.6", update.versionName);
        assertTrue(update.apkUrl.endsWith("FatihsMG4-unstable-2.2.6.apk"));
    }

    @Test
    public void stableDraftForeignAndUnversionedAssetsCannotWin() throws Exception {
        JSONObject foreign = asset("9.0.0").put("browser_download_url", "https://example.com/app.apk");
        JSONObject unversioned = asset("9.0.0").put("name", "FatihsMG4-unstable.apk");
        JSONObject stableName = asset("9.0.0").put("name", "FatihsMG4-stable-9.0.0.apk");
        JSONArray releases = new JSONArray()
                .put(release(false, asset("9.0.0")))
                .put(release(true, asset("9.0.0")).put("draft", true))
                .put(release(true, foreign, unversioned, stableName, asset("2.2.5")));
        assertEquals("2.2.5", OtaUpdater.selectUpdate(releases, "2.2.4.0-unstable").versionName);
    }

    @Test
    public void successfulCheckFindsUpdateAndDisconnects() throws Exception {
        Response response = new Response(200,
                new JSONArray().put(release(true, asset("2.2.5"))).toString());
        assertEquals("2.2.5", OtaUpdater.check("2.2.4.0-unstable", response).versionName);
        assertTrue(response.disconnected);
    }

    @Test
    public void successfulCheckWithOnlyOlderOrEqualVersionsIsCurrent() throws Exception {
        Response response = new Response(200,
                new JSONArray().put(release(true, asset("2.2.3"), asset("2.2.5"))).toString());
        assertNull(OtaUpdater.check("2.2.5.0-unstable", response));
        assertTrue(response.disconnected);
    }

    @Test(expected = IOException.class)
    public void rateLimitMustNotBeReportedAsCurrent() throws Exception {
        Response response = new Response(403, "{}");
        try {
            OtaUpdater.check("2.2.4.0-unstable", response);
        } finally {
            assertTrue(response.disconnected);
        }
    }

    @Test(expected = IOException.class)
    public void malformedResponseMustNotBeReportedAsCurrent() throws Exception {
        Response response = new Response(200, "<html>unavailable</html>");
        try {
            OtaUpdater.check("2.2.4.0-unstable", response);
        } finally {
            assertTrue(response.disconnected);
        }
    }

    @Test(expected = SocketTimeoutException.class)
    public void timeoutMustNotBeReportedAsCurrent() throws Exception {
        Response response = new Response(200, "[]") {
            @Override public InputStream getInputStream() throws IOException {
                throw new SocketTimeoutException("test timeout");
            }
        };
        try {
            OtaUpdater.check("2.2.4.0-unstable", response);
        } finally {
            assertTrue(response.disconnected);
        }
    }

    private static JSONObject asset(String version) throws Exception {
        String name = "FatihsMG4-unstable-" + version + ".apk";
        return new JSONObject().put("name", name).put("browser_download_url",
                "https://github.com/fatihdonmezdev/MG4ABRP/releases/download/unstable/" + name);
    }

    private static JSONObject release(boolean prerelease, JSONObject... assets) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject asset : assets) list.put(asset);
        return new JSONObject().put("prerelease", prerelease).put("assets", list);
    }

    private static class Response extends HttpURLConnection {
        private final int status;
        private final String body;
        boolean disconnected;

        Response(int status, String body) throws Exception {
            super(new URL("https://api.github.com/repos/fatihdonmezdev/MG4ABRP/releases"));
            this.status = status;
            this.body = body;
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    // The `pm install -r` exit-code test that stood here is gone with the shell-out it
    // covered. Installation is now a PackageInstaller session, whose every step needs a real
    // PackageManager — there is no pure part of it left to exercise on the JVM. What used to
    // be asserted here is now reported by OtaInstallResultReceiver, on the car.
}
