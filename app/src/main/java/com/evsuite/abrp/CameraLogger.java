package com.evsuite.abrp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Captures camera, RTSP, and network diagnostic logs locally on device cache.
 * When internet connectivity is available, asynchronously flushes logs to the central backend.
 */
public final class CameraLogger {
    private static final String TAG = "CameraLogger";
    private static final String CACHE_FILE = "camera_diag_logs.json";
    private static final String ENDPOINT = "https://mg-web-app-eta.vercel.app/api/logs";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat ISO_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);

    static {
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private CameraLogger() {}

    public static void i(Context context, String message) {
        log(context, "INFO", message, null);
    }

    public static void w(Context context, String message) {
        log(context, "WARN", message, null);
    }

    public static void e(Context context, String message, Throwable t) {
        String details = (t != null) ? Log.getStackTraceString(t) : null;
        log(context, "ERROR", message, details);
    }

    public static void log(Context context, String level, String message, String details) {
        Log.println(
                "ERROR".equals(level) ? Log.ERROR : ("WARN".equals(level) ? Log.WARN : Log.INFO),
                TAG,
                message + (details != null ? (" | " + details) : "")
        );

        if (context == null) return;
        final Context appContext = context.getApplicationContext();

        EXECUTOR.execute(() -> {
            try {
                JSONObject entry = new JSONObject();
                entry.put("tag", "Camera");
                entry.put("level", level);
                entry.put("message", message);

                // Collect deep network diagnostics (SSID, IP, Gateway, Ping/Socket status)
                JSONObject netDiag = collectNetworkDiagnostics(appContext);
                if (details != null && !details.isEmpty()) {
                    netDiag.put("errorDetails", details);
                }
                entry.put("details", netDiag);

                synchronized (ISO_FORMAT) {
                    entry.put("timestamp", ISO_FORMAT.format(new Date()));
                }

                // 1) Append to disk cache
                appendCache(appContext, entry);

                // 2) Try to flush if internet is available
                if (isNetworkConnected(appContext)) {
                    flushCache(appContext);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Log buffering failed: " + t.getMessage());
            }
        });
    }

    private static JSONObject collectNetworkDiagnostics(Context context) {
        JSONObject diag = new JSONObject();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    diag.put("wifiSsid", ssid);
                    diag.put("bssid", info.getBSSID());
                    diag.put("rssi", info.getRssi());

                    int ip = info.getIpAddress();
                    String ipStr = String.format(Locale.US, "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    diag.put("deviceIp", ipStr);
                }

                android.net.DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null) {
                    int gw = dhcp.gateway;
                    String gwStr = String.format(Locale.US, "%d.%d.%d.%d",
                            (gw & 0xff), (gw >> 8 & 0xff), (gw >> 16 & 0xff), (gw >> 24 & 0xff));
                    diag.put("gatewayIp", gwStr);
                }
            }

            // Quick socket test to 192.168.0.1:554 (70mai default)
            boolean rtspPortReachable = testSocket("192.168.0.1", 554, 400);
            diag.put("rtsp554Reachable", rtspPortReachable);

        } catch (Throwable ignored) {}
        return diag;
    }

    private static boolean testSocket(String host, int port, int timeoutMs) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void appendCache(Context context, JSONObject entry) {
        try {
            File file = new File(context.getCacheDir(), CACHE_FILE);
            JSONArray array;
            if (file.exists() && file.length() > 0) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] data = new byte[(int) file.length()];
                    fis.read(data);
                    array = new JSONArray(new String(data, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    array = new JSONArray();
                }
            } else {
                array = new JSONArray();
            }

            // Keep max 250 records in cache to prevent storage bloat
            if (array.length() >= 250) {
                JSONArray pruned = new JSONArray();
                for (int i = 50; i < array.length(); i++) {
                    pruned.put(array.get(i));
                }
                array = pruned;
            }

            array.put(entry);

            try (FileOutputStream fos = new FileOutputStream(file, false)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write cache: " + e.getMessage());
        }
    }

    public static synchronized void flushCache(Context context) {
        File file = new File(context.getCacheDir(), CACHE_FILE);
        if (!file.exists() || file.length() == 0) return;

        try {
            JSONArray array;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                array = new JSONArray(new String(data, StandardCharsets.UTF_8));
            }

            if (array.length() == 0) return;

            JSONObject payload = new JSONObject();
            payload.put("installationId", BuildConfig.CONSUMPTION_INSTALLATION_ID);
            payload.put("logs", array);

            URL url = new URL(ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "MG4CameraLogger/" + BuildConfig.VERSION_NAME);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            byte[] jsonBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                // Sent successfully, delete cached log file
                file.delete();
                Log.i(TAG, "Flushed " + array.length() + " diagnostic logs to backend.");
            } else {
                Log.w(TAG, "Backend returned HTTP " + code + " for logs flush.");
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Could not flush diagnostic logs: " + e.getMessage());
        }
    }

    private static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
