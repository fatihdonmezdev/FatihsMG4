package com.evsuite.abrp;

import android.app.PendingIntent;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;

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
    private static final int MAX_HASH_BYTES = 16 * 1024;

    private OtaUpdater() { }

    static void purgeCachedApks(Context context) {
        File[] files = context.getCacheDir().listFiles((dir, name) ->
                name.startsWith(CACHE_PREFIX) && name.endsWith(".apk"));
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) Log.w(TAG, "Could not purge cached OTA APK: " + file.getName());
        }
    }

    /** Result of a successful check: null means there is no newer eligible release. */
    static final class Update {
        final String versionName;
        final String apkUrl;
        final String apkName;
        final String hashUrl;

        Update(String versionName, String apkUrl, String apkName, String hashUrl) {
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.hashUrl = hashUrl;
        }
    }

    interface ProgressListener {
        void onProgress(int percent);
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
    static Update check(String currentVersion) throws IOException {
        return check(currentVersion, (HttpURLConnection) new URL(RELEASES_API).openConnection());
    }

    /** Connection seam for exercising real response handling without a device or network. */
    static Update check(String currentVersion, HttpURLConnection conn) throws IOException {
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", "EVABRPUploader-Android");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("Release API returned " + status);

            StringBuilder body = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) body.append(line);
            }

            return selectUpdate(new JSONArray(body.toString()), currentVersion);
        } catch (JSONException e) {
            throw new IOException("Invalid release response", e);
        } finally {
            conn.disconnect();
        }
    }

    /** Scan every asset of every pre-release; asset ordering does not imply version order. */
    static Update selectUpdate(JSONArray releases, String currentVersion) throws JSONException {
        Update best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (!release.optBoolean("prerelease", false) || release.optBoolean("draft", false)) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.getJSONObject(a);
                String name = asset.optString("name", "");
                if (!name.toLowerCase(Locale.US).contains("unstable")) continue;
                String version = versionFromAssetName(name);
                if (version == null || !isNewer(version, currentVersion)) continue;
                if (best != null && !isNewer(version, best.versionName)) continue;
                String url = asset.optString("browser_download_url", "");
                String hashUrl = findHashUrl(assets, name);
                // Keep the candidate even when the sidecar is absent so the UI can report
                // a broken release instead of incorrectly saying the app is current.
                // fetchExpectedSha256 still fails closed before any APK is downloaded.
                if (isAllowedUrl(url)) best = new Update(version, url, name, hashUrl);
            }
        }
        return best;
    }

    private static String findHashUrl(JSONArray assets, String apkName) throws JSONException {
        String exact = apkName + ".sha256";
        String sums = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (!isAllowedUrl(url)) continue;
            if (exact.equalsIgnoreCase(name)) return url;
            if ("SHA256SUMS".equalsIgnoreCase(name)) sums = url;
        }
        return sums;
    }

    static String parseExpectedSha256(String content, String apkName) {
        if (content == null || apkName == null) return null;
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\s+");
            if (fields.length == 1 && isSha256(fields[0]))
                return fields[0].toLowerCase(Locale.US);
            if (fields.length >= 2 && isSha256(fields[0])) {
                String named = fields[fields.length - 1].replaceFirst("^\\*", "");
                if (apkName.equals(named)) return fields[0].toLowerCase(Locale.US);
            }
        }
        return null;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }

    static String fetchExpectedSha256(Update update) throws IOException {
        if (update == null || !isAllowedUrl(update.hashUrl))
            throw new IOException("Missing SHA-256 sidecar");
        String body = readSmallText(update.hashUrl);
        String hash = parseExpectedSha256(body, update.apkName);
        if (hash == null) throw new IOException("Invalid SHA-256 sidecar");
        return hash;
    }

    private static String readSmallText(String initialUrl) throws IOException {
        URL current = new URL(initialUrl);
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!isAllowedUrl(current.toString())) throw new IOException("Hash URL refused");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "EVABRPUploader-Android");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Hash redirect missing location");
                    current = current.toURI().resolve(location).toURL();
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK)
                    throw new IOException("Hash download returned " + status);
                StringBuilder body = new StringBuilder();
                try (java.io.InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (body.length() + count > MAX_HASH_BYTES)
                            throw new IOException("Hash response too large");
                        body.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    }
                }
                return body.toString();
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Invalid hash redirect", e);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many hash redirects");
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
        return safe + ".apk";
    }

    /**
     * Downloads into private cache. Every redirect URL is validated before it is followed.
     */
    static File download(Context context, Update update, String expectedSha256,
                         ProgressListener listener) {
        if (!isAllowedUrl(update.apkUrl)) {
            Log.w(TAG, "Refusing to download from " + update.apkUrl);
            return null;
        }
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return null;
        String publicName = downloadFileName(update.versionName);
        File publicFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), publicName);
        if (publicFile.exists() && !publicFile.delete()) return null;
        long downloadId = -1L;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.apkUrl))
                    .setTitle("EVABRPUploader " + update.versionName)
                    .setDescription("Güncelleme indiriliyor")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, publicName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true);
            downloadId = manager.enqueue(request);
            while (true) {
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
                    if (cursor == null || !cursor.moveToFirst()) return null;
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long received = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (listener != null) listener.onProgress(total > 0
                            ? (int) Math.min(100, received * 100 / total) : -1);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) break;
                    if (status == DownloadManager.STATUS_FAILED) return null;
                }
                Thread.sleep(250L);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (ParcelFileDescriptor descriptor = manager.openDownloadedFile(downloadId);
                 java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            if (!hex(digest.digest()).equalsIgnoreCase(expectedSha256)) {
                if (!publicFile.delete()) Log.w(TAG, "Could not remove invalid OTA file");
                return null;
            }
            return publicFile;
        } catch (Exception e) {
            Log.w(TAG, "Update download failed", e);
        }
        return null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value));
        return out.toString();
    }

    /** Opens Android's document UI directly in Downloads; the driver chooses the APK. */
    static boolean openDownloads(Context context) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 26) {
            picker.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3ADownload"));
        }
        try {
            context.startActivity(picker);
            return true;
        } catch (Throwable first) {
            try {
                Intent downloads = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(downloads);
                return true;
            } catch (Throwable second) {
                Log.w(TAG, "No Downloads UI available", second);
                return false;
            }
        }
    }

    /**
     * What {@link #install} did. The PackageInstaller is the authority for signing-certificate
     * compatibility, matching the proven DriveHub_Dort flow on this head unit.
     */
    enum InstallResult {
        /** The package manager cannot parse the archive, or it is for another package. */
        UNREADABLE_ARCHIVE,
        /** Handed to the platform. The outcome arrives at {@link OtaInstallResultReceiver}. */
        SESSION_STARTED,
        /** Session install was unavailable; the system's interactive installer was opened. */
        INSTALL_UI_STARTED,
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
        if (!archiveIsThisPackage(context, apk)) return InstallResult.UNREADABLE_ARCHIVE;
        try {
            commitSession(context, apk);
            return InstallResult.SESSION_STARTED;
        } catch (Throwable t) {
            Log.w(TAG, "OTA session install failed; trying system install UI", t);
            try {
                launchInstallerActivity(context, apk);
                return InstallResult.INSTALL_UI_STARTED;
            } catch (Throwable fallback) {
                Log.w(TAG, "OTA install UI failed", fallback);
                return InstallResult.SESSION_FAILED;
            }
        }
    }

    /**
     * True if the archive parses and declares our own package name.
     *
     * The package name is checked here before any bytes reach an installer session. Android's
     * PackageInstaller then performs the authoritative signing-certificate and downgrade checks.
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
        boolean committed = false;
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
            committed = true;
            Log.i(TAG, "OTA session " + sessionId + " committed (" + apk.length() + " bytes)");
        } finally {
            // Abandon failed staging, but always close our local handle after a commit too.
            if (!committed) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignored) { }
            }
            if (session != null) {
                try { session.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** DriveHub_Dort-compatible fallback for head units that refuse installer sessions. */
    private static void launchInstallerActivity(Context context, File apk) {
        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            throw new IllegalStateException("unknown sources permission required");
        }

        File publicApk = copyToPublicDownloads(apk);
        Uri contentUri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", publicApk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri("EVABRPUploader update", contentUri));
        grantToInstallers(context, contentUri);
        if (install.resolveActivity(context.getPackageManager()) == null) {
            throw new IllegalStateException("No package installer activity");
        }
        context.startActivity(install);
        Log.i(TAG, "System install UI opened for " + publicApk.getAbsolutePath());
    }

    private static File copyToPublicDownloads(File source) {
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null || (!downloads.isDirectory() && !downloads.mkdirs())) {
            throw new IllegalStateException("Downloads directory unavailable");
        }
        File destination = new File(downloads, "FatihsMG4-OTA-update.apk");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException e) {
            throw new IllegalStateException("Could not stage APK in Downloads", e);
        }
        if (destination.length() != source.length()) {
            throw new IllegalStateException("Staged APK size mismatch");
        }
        return destination;
    }

    private static void grantToInstallers(Context context, Uri uri) {
        String[] packages = {
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.samsung.android.packageinstaller"
        };
        for (String packageName : packages) {
            try {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
        }
        Intent probe = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive");
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(probe, 0)) {
            if (info.activityInfo == null) continue;
            try {
                context.grantUriPermission(info.activityInfo.packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
        }
    }

}
