package com.evsuite.abrp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Over-the-air updater — UNSTABLE BUILDS ONLY.
 *
 * The stable channel deliberately has no self-update path: this class does not exist in a
 * stable build. Unstable testers get updates without manual work, and accept that channel's
 * risk.
 *
 * Security posture is the one EVProfile settled on in its own OTA work:
 *  - the APK URL comes from a remote JSON document and is never trusted: https only,
 *    exact-match host allowlist, checked again before it reaches the system downloader;
 *  - the downloaded APK must be signed by the same certificate as the running app, or it
 *    is deleted rather than offered for install;
 *  - both checks fail closed.
 */
final class OtaUpdater {

    private static final String TAG = "OtaUpdater";
    private static final String CACHE_PREFIX = "EVABRPUploader-ota-";

    /**
     * Pre-releases live here; the unstable channel tracks them.
     *
     * This fork's own repository, deliberately: an update pulled from upstream would be
     * signed with a different key, and Android refuses to install over a platform-signed
     * build with anything but the same signature. Pointing here means the check finds
     * nothing until this repository publishes a release, which is the intended quiet.
     */
    private static final String RELEASES_API =
            "https://api.github.com/repos/fatihdonmezdev/MG4ABRP/releases";

    /**
     * Hosts an update may come from. The githubusercontent entries are the CDNs GitHub
     * redirects release-asset downloads to; without them the download fails.
     */
    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com");

    private static final java.util.regex.Pattern ASSET_VERSION =
            java.util.regex.Pattern.compile("-(\\d[0-9.]*?)\\.apk$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final int TIMEOUT_MS = 10_000;

    private OtaUpdater() { }

    static void purgeCachedApks(Context context) {
        File[] files = context.getCacheDir().listFiles((dir, name) ->
                name.startsWith(CACHE_PREFIX) && name.endsWith(".apk"));
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) Log.w(TAG, "Could not purge cached OTA APK: " + file.getName());
        }
    }

    /** Result of a check: null when there is nothing newer or the check failed. */
    static final class Update {
        final String versionName;
        final String apkUrl;

        Update(String versionName, String apkUrl) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
        }
    }

    /**
     * True if [url] is https and points at an allowed host.
     *
     * Rejects http (including an https -> http downgrade), unknown hosts, unparsable
     * URLs, and lookalikes such as "github.com.attacker.net" — the host match is exact,
     * never a suffix test.
     */
    static boolean isAllowedUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) return false;
        String host = uri.getHost();
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase(java.util.Locale.US));
    }

    /**
     * Numeric core of a version: "v1.2.3-unstable" -> [1, 2, 3].
     *
     * A segment with no digits becomes 0 rather than being dropped, so later segments do
     * not shift left and turn a patch into a minor.
     */
    static int[] segments(String version) {
        String core = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1) : version;
        int cut = core.indexOf('+');
        if (cut >= 0) core = core.substring(0, cut);
        cut = core.indexOf('-');
        if (cut >= 0) core = core.substring(0, cut);

        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int digits = 0;
            while (digits < parts[i].length() && Character.isDigit(parts[i].charAt(digits))) digits++;
            try {
                out[i] = digits == 0 ? 0 : Integer.parseInt(parts[i].substring(0, digits));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * Version carried by an unstable asset name:
     * "EVABRPUploader-unstable-1.0.42.apk" -> "1.0.42".
     *
     * The release tag is the fixed string "unstable" (one rolling pre-release), so the asset
     * name is what identifies a build. Returns null when the name carries no version.
     */
    static String versionFromAssetName(String assetName) {
        java.util.regex.Matcher m = ASSET_VERSION.matcher(assetName);
        return m.find() ? m.group(1) : null;
    }

    /** True if [remote] is a strictly higher version than [current]. */
    static boolean isNewer(String remote, String current) {
        int[] r = segments(remote);
        int[] c = segments(current);
        for (int i = 0; i < Math.max(r.length, c.length); i++) {
            int rv = i < r.length ? r[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (rv > cv) return true;
            if (rv < cv) return false;
        }
        return false;
    }

    /**
     * Asks GitHub for the newest pre-release and returns it if it beats [currentVersion].
     * Runs on the caller's thread — never call from the main thread.
     */
    static Update check(String currentVersion) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "EVABRPUploader-Android");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "Release API returned " + conn.getResponseCode());
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }

            JSONArray releases = new JSONArray(body.toString());

            // Scan every entry and keep the HIGHEST version, not simply the first one
            // that beats the installed build: the API's ordering is by creation date, and
            // a re-published or back-dated release would otherwise win over a newer one.
            //
            // Stable releases are skipped on purpose. This channel tracks pre-releases
            // only — and a stable APK could not update a unstable install anyway, since
            // the unstable applicationId carries a .unstable suffix.
            //
            // The version comes from the asset name, not the tag: the unstable channel is
            // a single rolling pre-release tagged "unstable", overwritten on every build.
            Update best = null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (!release.optBoolean("prerelease", false)) continue;

                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int a = 0; a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    String name = asset.optString("name", "");
                    if (!name.toLowerCase(java.util.Locale.US).endsWith(".apk")) continue;
                    if (!name.contains("unstable")) continue;

                    String version = versionFromAssetName(name);
                    if (version == null) continue;
                    if (!isNewer(version, currentVersion)) continue;
                    if (best != null && !isNewer(version, best.versionName)) continue;

                    String url = asset.optString("browser_download_url", "");
                    if (!isAllowedUrl(url)) {
                        Log.w(TAG, "Rejected update URL from an unexpected host: " + url);
                        continue;
                    }
                    best = new Update(version, url);
                    break;
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "Update check failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Safe diagnostic name for the downloaded APK. The version comes from a remote
     * asset name, so it is reduced to a safe character set before it reaches a path. Callers that
     * look for an already-downloaded update must use this same name.
     */
    static String downloadFileName(String versionName) {
        String safe = (versionName == null || versionName.isEmpty())
                ? "unknown"
                : versionName.toLowerCase(Locale.US).replaceAll("[^a-z0-9._-]", "_");
        return "EVABRPUploader-unstable-" + safe + ".apk";
    }

    /**
     * Downloads into private cache. Every redirect URL is validated before it is followed.
     */
    static File download(Context context, Update update) {
        if (!isAllowedUrl(update.apkUrl)) {
            Log.w(TAG, "Refusing to download from " + update.apkUrl);
            return null;
        }
        File target = new File(context.getCacheDir(), CACHE_PREFIX
                + java.util.UUID.randomUUID() + ".apk");
        File temporary = null;
        try {
            temporary = File.createTempFile(CACHE_PREFIX, ".apk", context.getCacheDir());
            URL current = new URL(update.apkUrl);
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (!isAllowedUrl(current.toString())) return null;
                HttpURLConnection connection = (HttpURLConnection) current.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status <= 399) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) return null;
                        current = current.toURI().resolve(location).toURL();
                        if (!isAllowedUrl(current.toString())) return null;
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_OK) return null;
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        try (java.io.InputStream input = connection.getInputStream()) {
                            byte[] buffer = new byte[32 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                        output.getFD().sync();
                    }
                    if (!temporary.renameTo(target)) return null;
                    return target;
                } finally {
                    connection.disconnect();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Update download failed", e);
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
        return null;
    }

    /**
     * What {@link #install} did. Every value except {@link #SESSION_STARTED} is a refusal,
     * and they are distinct because the single "imza uyuşmuyor?" message they used to share
     * sent a perfectly well-signed build to the wrong diagnosis.
     */
    enum InstallResult {
        /** The archive is signed by a different certificate than the running app. */
        SIGNATURE_MISMATCH,
        /** The package manager cannot parse the archive, or it is for another package. */
        UNREADABLE_ARCHIVE,
        /** Handed to the platform. The outcome arrives at {@link OtaInstallResultReceiver}. */
        SESSION_STARTED,
        /** The session could not be created, written or committed. */
        SESSION_FAILED
    }

    /**
     * Hands [apk] to the platform's {@link PackageInstaller}.
     *
     * This used to shell out to {@code pm install -r}. That fails on this head unit: the APK
     * lives in the app's private cache, and the package manager service opens the path from
     * its own process and SELinux context, where that directory is not readable. The failure
     * surfaced as a signature error, which it never was.
     *
     * A session takes no path. We open the archive ourselves, as the app, and stream the bytes
     * into the session — so there is no second process that has to be able to read our cache.
     * The bytes are fully written before commit, which is why the caller may delete the staged
     * file as soon as this returns.
     */
    static InstallResult install(Context context, File apk) {
        if (!signatureMatchesRunningApp(context, apk)) return InstallResult.SIGNATURE_MISMATCH;
        if (!archiveIsThisPackage(context, apk)) return InstallResult.UNREADABLE_ARCHIVE;
        try {
            commitSession(context, apk);
            return InstallResult.SESSION_STARTED;
        } catch (Throwable t) {
            Log.w(TAG, "OTA session install failed", t);
            return InstallResult.SESSION_FAILED;
        }
    }

    /**
     * True if the archive parses and declares our own package name.
     *
     * A signature match alone does not say the APK is this app: the same platform key signs
     * every app on the unit, so a correctly signed archive for some other package would pass
     * the certificate check and then fail deep inside the installer.
     */
    private static boolean archiveIsThisPackage(Context context, File apk) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info == null || info.packageName == null) {
                Log.w(TAG, "OTA archive cannot be parsed by the package manager");
                return false;
            }
            if (!context.getPackageName().equals(info.packageName)) {
                Log.w(TAG, "OTA archive is for " + info.packageName + ", not "
                        + context.getPackageName());
                return false;
            }
            Log.i(TAG, "OTA archive ok: " + info.packageName + " " + info.versionName);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "OTA archive check threw: " + e.getMessage());
            return false;
        }
    }

    private static void commitSession(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Only honoured for an installer the platform already trusts; harmless otherwise,
            // and this unit is API 28 where the field does not exist at all.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = installer.openSession(sessionId);
            try (java.io.InputStream in = new java.io.FileInputStream(apk);
                 java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                session.fsync(out);
            }
            Intent callback = new Intent(context, OtaInstallResultReceiver.class)
                    .setAction(OtaInstallResultReceiver.ACTION_INSTALL_RESULT)
                    .setPackage(context.getPackageName());
            // MUTABLE: the platform fills in EXTRA_STATUS and, when it wants the driver to
            // confirm, EXTRA_INTENT. An immutable PendingIntent would arrive empty.
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            Log.i(TAG, "OTA session " + sessionId + " committed (" + apk.length() + " bytes)");
            session = null;
        } finally {
            // Only reached when commit did not happen; an abandoned session frees its staging.
            if (session != null) {
                try { session.abandon(); } catch (Throwable ignored) { }
                try { session.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * True if [apk] is signed by the same certificate as the running app.
     *
     * Fail closed: an unreadable archive, a missing signature or a failed API call all
     * return false. The caller must delete the file rather than offer it for install.
     */
    static boolean signatureMatchesRunningApp(Context context, File apk) {
        java.util.Set<String> archive = ApkSignature.of(context, apk.getAbsolutePath());
        java.util.Set<String> installed = ApkSignature.ofPackage(context);
        boolean ok = !archive.isEmpty() && !installed.isEmpty() && archive.equals(installed);
        if (!ok) {
            Log.w(TAG, "Signature mismatch — refusing update ("
                    + archive.size() + " vs " + installed.size() + " cert(s))");
        }
        return ok;
    }
}
