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
     * Called periodically by AbrpUploadService (e.g. every 15s).
     */
    public void pollAndExecute() {
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
                    Log.i(TAG, "New vehicle command received: " + cmdId + " action=" + action);

                    executeCommand(cmdId, action);
                }
            } catch (Exception e) {
                Log.d(TAG, "Command poll skipped or error: " + e.getMessage());
            }
        });
    }

    private void executeCommand(String commandId, String action) {
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

            switch (action) {
                case "windows_close":
                    success = runWindowsMove(true, FULL_COURSE_MS);
                    resultMsg = "All windows closed";
                    break;
                case "windows_open":
                    success = runWindowsMove(false, FULL_COURSE_MS);
                    resultMsg = "All windows opened";
                    break;
                case "windows_vent":
                    success = runWindowsMove(false, VENT_COURSE_MS);
                    resultMsg = "Windows ventilated";
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
        int autoCmd   = isUp ? CMD_AUTO_UP   : CMD_AUTO_DOWN;

        // 1. Driver window has native auto: trigger auto once
        writeWindowProp(cpm, setFloatMethod, WINDOW_PROPS[0], autoCmd);

        // 2. Other windows (and fallback for driver): pulse manual command for durationMs
        long startMs = SystemClock.elapsedRealtime();
        long deadline = startMs + durationMs;

        while (SystemClock.elapsedRealtime() < deadline) {
            // Re-check standstill gate during the pulse
            if (!VehicleWriteGate.INSTANCE.allow("Direct window pulse")) {
                Log.w(TAG, "Motion detected during pulse, stopping windows!");
                break;
            }

            for (int i = 1; i < WINDOW_PROPS.length; i++) {
                writeWindowProp(cpm, setFloatMethod, WINDOW_PROPS[i], manualCmd);
            }

            try {
                Thread.sleep(HOLD_REPEAT_MS);
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
