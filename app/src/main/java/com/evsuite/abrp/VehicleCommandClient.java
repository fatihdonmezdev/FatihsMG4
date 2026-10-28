package com.evsuite.abrp;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.evsuite.hardware.EVHardware;
import com.evsuite.hardware.VehicleWriteGate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct vehicle command client & window executor.
 *
 * Polls the cloud endpoint (https://mg-web-app-eta.vercel.app/api/vehicle/commands)
 * when the head unit is connected to the internet (phone hotspot / Wi-Fi).
 * Executes window open, close, and vent commands directly on the car's hardware
 * (CarPropertyManager / SAIC VSM) without going through iSmart servers.
 */
public class VehicleCommandClient {

    private static final String TAG = "VehicleCommandClient";
    private static final String BASE_URL = "https://mg-web-app-eta.vercel.app/api/vehicle/commands";
    private static final String PING_URL = "https://mg-web-app-eta.vercel.app/api/vehicle/ping";

    private long lastPingTimeMs = 0L;
    private static final long PING_INTERVAL_MS = 60_000L; // En az dakikada bir ping at

    // Power window property IDs on MG4 (AOSP / SAIC CarPropertyManager)
    // 0x11603801 = Front Left (Driver)
    // 0x11603802 = Front Right (Passenger)
    // 0x11603803 = Rear Left
    // 0x11603804 = Rear Right
    private static final int[] WINDOW_PROPS = {
            0x11603801,
            0x11603802,
            0x11603803,
            0x11603804
    };

    private static final int AREA_GLOBAL = 0x1000000;

    // Window command values
    private static final int CMD_STOP        = 0;
    private static final int CMD_MANUAL_UP   = 1;
    private static final int CMD_MANUAL_DOWN = 2;
    private static final int CMD_AUTO_UP     = 3;
    private static final int CMD_AUTO_DOWN   = 4;

    private static final long HOLD_REPEAT_MS = 120L;
    private static final long FULL_COURSE_MS = 5000L;
    private static final long VENT_COURSE_MS = 1200L; // ~15-20% vent gap

    private final Context context;
    private final String installationId;
    private final CarPropertyAdapter carPropertyAdapter;
    private final Handler workerHandler;
    private final AtomicBoolean isExecuting = new AtomicBoolean(false);

    public VehicleCommandClient(Context context, String installationId, CarPropertyAdapter adapter) {
        this.context = context.getApplicationContext();
        this.installationId = installationId;
        this.carPropertyAdapter = adapter;

        HandlerThread thread = new HandlerThread("VehicleCommandWorker");
        thread.start();
        this.workerHandler = new Handler(thread.getLooper());
    }

    /**
     * Sends a heartbeat ping to cloud indicating the head unit is awake & online.
     */
    public void sendHeartbeat() {
        workerHandler.post(() -> {
            long now = System.currentTimeMillis();
            if (now - lastPingTimeMs < PING_INTERVAL_MS) {
                return;
            }
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(PING_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);

                JSONObject body = new JSONObject();
                body.put("installationId", installationId);
                body.put("source", "fatihsmg4-headunit");

                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    lastPingTimeMs = now;
                    Log.d(TAG, "Heartbeat ping sent successfully");
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.d(TAG, "Heartbeat ping skipped/failed: " + e.getMessage());
            }
        });
    }

    /**
     * Called periodically by AbrpUploadService (e.g. every 15s).
     */
    public void pollAndExecute() {
        sendHeartbeat();
        if (isExecuting.get()) return;

        workerHandler.post(() -> {
            try {
                String queryUrl = BASE_URL + "?installationId=" + installationId + "&status=pending";
                HttpURLConnection conn = (HttpURLConnection) new URL(queryUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject res = new JSONObject(sb.toString());
                JSONArray commands = res.optJSONArray("commands");
                if (commands == null || commands.length() == 0) return;

                for (int i = 0; i < commands.length(); i++) {
                    JSONObject cmd = commands.getJSONObject(i);
                    String cmdId = cmd.getString("commandId");
                    String action = cmd.getString("action");
                    JSONObject params = cmd.optJSONObject("params");
                    String createdAtStr = cmd.optString("createdAt", "");

                    // Çift güvenlik: Cihaz tarafında da 5 dakikadan eski komutları yoksay
                    if (!createdAtStr.isEmpty()) {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                            java.util.Date createdDate = sdf.parse(createdAtStr);
                            if (createdDate != null && (System.currentTimeMillis() - createdDate.getTime() > 5 * 60 * 1000L)) {
                                Log.w(TAG, "Command " + cmdId + " expired (>5m old), skipping execution.");
                                updateCommandStatus(cmdId, "failed", "Expired on client (>5m)");
                                continue;
                            }
                        } catch (Exception ignored) {}
                    }

                    Log.i(TAG, "New vehicle command received: " + cmdId + " action=" + action);

                    executeCommand(cmdId, action, params);
                }
            } catch (Exception e) {
                Log.d(TAG, "Command poll skipped or error: " + e.getMessage());
            }
        });
    }

    private void executeCommand(String commandId, String action, JSONObject params) {
        if (!isExecuting.compareAndSet(false, true)) {
            Log.w(TAG, "Another command is already executing, skipping: " + commandId);
            return;
        }

        try {
            // Safety gate: Check standstill before executing window movement
            if (!VehicleWriteGate.INSTANCE.allow("Direct window control")) {
                Log.w(TAG, "Command " + commandId + " REFUSED: Vehicle is not at standstill");
                updateCommandStatus(commandId, "failed", "Vehicle is moving or speed unreadable");
                return;
            }

            boolean success = false;
            String resultMsg = "Executed";

            long customDuration = params != null ? params.optLong("durationMs", 0) : 0;

            switch (action) {
                case "windows_close":
                    long closeDuration = customDuration > 0 ? customDuration : 4500L;
                    success = runWindowsMove(true, closeDuration);
                    resultMsg = "All windows closed (" + closeDuration + "ms)";
                    break;
                case "windows_open":
                    long openDuration = customDuration > 0 ? customDuration : 4500L;
                    success = runWindowsMove(false, openDuration);
                    resultMsg = "All windows opened (" + openDuration + "ms)";
                    break;
                case "windows_vent":
                    long ventMs = customDuration > 0 ? customDuration : 350L;
                    success = runWindowsMove(false, ventMs);
                    resultMsg = "Windows ventilated (" + ventMs + "ms)";
                    break;
                case "windows_pulse_up":
                    long upMs = customDuration > 0 ? customDuration : 350L;
                    success = runWindowsMove(true, upMs);
                    resultMsg = "Windows pulse up (" + upMs + "ms)";
                    break;
                case "windows_pulse_down":
                    long downMs = customDuration > 0 ? customDuration : 350L;
                    success = runWindowsMove(false, downMs);
                    resultMsg = "Windows pulse down (" + downMs + "ms)";
                    break;
                case "windows_stop":
                    stopWindowsImmediate();
                    success = true;
                    resultMsg = "Windows stopped";
                    break;
                default:
                    resultMsg = "Unknown action: " + action;
                    break;
            }

            updateCommandStatus(commandId, success ? "executed" : "failed", resultMsg);
        } catch (Exception e) {
            Log.e(TAG, "Execution error for " + commandId, e);
            updateCommandStatus(commandId, "failed", e.getMessage());
        } finally {
            isExecuting.set(false);
        }
    }

    private void stopWindowsImmediate() {
        Object cpm = carPropertyAdapter != null ? carPropertyAdapter.cpm : null;
        if (cpm == null) return;
        try {
            Method setFloatMethod = cpm.getClass().getMethod("setFloatProperty", int.class, int.class, float.class);
            for (int prop : WINDOW_PROPS) {
                writeWindowProp(cpm, setFloatMethod, prop, CMD_STOP);
            }
        } catch (Exception e) {
            Log.e(TAG, "stopWindowsImmediate error", e);
        }
    }

    private boolean runWindowsMove(boolean isUp, long durationMs) {
        Object cpm = carPropertyAdapter != null ? carPropertyAdapter.cpm : null;
        if (cpm == null) {
            Log.w(TAG, "CarPropertyManager not available, aborting window move");
            return false;
        }

        Method setFloatMethod = null;
        try {
            setFloatMethod = cpm.getClass().getMethod("setFloatProperty", int.class, int.class, float.class);
        } catch (Exception e) {
            Log.e(TAG, "setFloatProperty method not found on CarPropertyManager", e);
            return false;
        }

        int manualCmd = isUp ? CMD_MANUAL_UP : CMD_MANUAL_DOWN;

        long startMs = SystemClock.elapsedRealtime();
        long deadline = startMs + durationMs;

        while (SystemClock.elapsedRealtime() < deadline) {
            // Re-check standstill gate during the pulse
            if (!VehicleWriteGate.INSTANCE.allow("Direct window pulse")) {
                Log.w(TAG, "Motion detected during pulse, stopping windows!");
                break;
            }

            // Sürücü dahil tüm 4 camı kontrollü manuel darbeyle hareket ettir
            for (int prop : WINDOW_PROPS) {
                writeWindowProp(cpm, setFloatMethod, prop, manualCmd);
            }

            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                break;
            }
        }

        // 3. Stop command to release motors
        for (int prop : WINDOW_PROPS) {
            writeWindowProp(cpm, setFloatMethod, prop, CMD_STOP);
        }

        return true;
    }

    private boolean writeWindowProp(Object cpm, Method setter, int propId, int cmdValue) {
        try {
            // Try global zone 0x1000000 first, fallback to zone 0
            try {
                setter.invoke(cpm, propId, AREA_GLOBAL, (float) cmdValue);
                return true;
            } catch (Exception e) {
                setter.invoke(cpm, propId, 0, (float) cmdValue);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "writeWindowProp 0x" + Integer.toHexString(propId) + " val=" + cmdValue + " failed: " + e.getMessage());
            return false;
        }
    }

    private void updateCommandStatus(String commandId, String status, String result) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                JSONObject body = new JSONObject();
                body.put("commandId", commandId);
                body.put("status", status);
                body.put("result", result);

                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }

                int code = conn.getResponseCode();
                Log.i(TAG, "Command " + commandId + " status updated to " + status + " (HTTP " + code + ")");
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to update command status for " + commandId, e);
            }
        }).start();
    }
}
