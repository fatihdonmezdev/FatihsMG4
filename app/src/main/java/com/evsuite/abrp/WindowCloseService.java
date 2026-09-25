package com.evsuite.abrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.evsuite.hardware.EVHardware;
import com.evsuite.hardware.FirmwareInfo;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;

/** Independent of ABRP credentials, GPS and upload cadence. No alarms or wake locks. */
public final class WindowCloseService extends Service {
    static final String KEY_ENABLED = "window_close_enabled";
    private static final String ACTION_STOP = "com.evsuite.abrp.WINDOW_CLOSE_STOP";
    private static final String CHANNEL = "window_close";
    private static final int NOTIFICATION = 2;
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static volatile boolean running;
    private static volatile int status = R.string.window_close_stopped;

    private final WindowClosePolicy policy = new WindowClosePolicy();
    private volatile boolean active;
    private SharedPreferences prefs;
    private ExecutorService worker;
    private WinCloseHardware monitor;

    static void startIfEnabled(Context context) {
        if (!context.getSharedPreferences("abrp_prefs", MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)) return;
        try {
            context.startForegroundService(new Intent(context, WindowCloseService.class));
        } catch (RuntimeException e) {
            status = R.string.window_close_start_failed;
            log("Service start failed: " + e.getClass().getSimpleName());
        }
    }

    static int statusResource() { return status; }
    static boolean isRunning() { return running; }

    /**
     * True when this process can actually reach the vehicle setting service.
     *
     * Only the unstable flavor declares android.uid.system, so a stable build can toggle the
     * feature on and then fail every single command. Checking the uid rather than the flavor
     * keeps the answer true for any build that is genuinely platform-signed.
     */
    static boolean canWriteToVehicle() { return Process.myUid() == Process.SYSTEM_UID; }

    static synchronized String eventLog() { return String.join("\n", events); }

    private static synchronized void log(String message) {
        Log.i("WindowClose", message);
        String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
        events.addFirst(time + "  " + message);
        while (events.size() > 30) events.removeLast();
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "window-close"));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.window_close_title), NotificationManager.IMPORTANCE_LOW));
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification);
        }
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        }
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            active = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        // UNKNOWN is left running: at boot the generation is often not resolved yet, and the
        // monitor re-checks before it connects. A different known generation never will be
        // SWI69, so idling a foreground notification and a 5 s poll on it buys nothing.
        FirmwareInfo.Gen generation = FirmwareInfo.INSTANCE.getGeneration();
        if (generation != FirmwareInfo.Gen.SWI69 && generation != FirmwareInfo.Gen.UNKNOWN) {
            status = R.string.window_close_unsupported;
            log("Stopping: window closing is SWI69 only, this is " + generation);
            active = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canWriteToVehicle()) {
            status = R.string.window_close_no_system_uid;
            log("Stopping: process uid " + Process.myUid() + " cannot write vehicle settings");
            active = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (monitor == null) {
            active = true;
            status = R.string.window_close_waiting;
            log("Enabled: SWI69, P + 0 km/h, all four windows, 5 seconds");
            monitor = new WinCloseHardware(getApplicationContext(), () -> {
                onDoorOpening();
                return Unit.INSTANCE;
            }, message -> {
                log(message);
                return Unit.INSTANCE;
            });
            monitor.start();
        }
        return START_STICKY;
    }

    /** Runs on the monitor's callback thread, so it must not block on the car. */
    private void onDoorOpening() {
        if (!active || !prefs.getBoolean(KEY_ENABLED, false)) return;
        if (FirmwareInfo.INSTANCE.getGeneration() != FirmwareInfo.Gen.SWI69) return;
        if (!monitor.isReady()) {
            status = R.string.window_close_unavailable;
            log("Close skipped: window interface unavailable");
            return;
        }
        try {
            worker.execute(this::closeNow);
        } catch (RejectedExecutionException e) {
            log("Close skipped: worker is shutting down");
        }
    }

    /**
     * The gear and speed reads are binder round trips, so they happen here rather than on the
     * callback thread — a slow CPM read there would delay the next door event behind it.
     */
    private void closeNow() {
        Boolean parked = monitor.isParked();
        Float speed = EVHardware.INSTANCE.getVehicleSpeedKmh();
        if (!policy.begin(parked, speed)) {
            log("WinClose door: skipped (P=" + parked + ", speed=" + speed
                    + "; or a close ran in the last minute)");
            return;
        }
        // Held across the pulse because this fires as the driver leaves, which is exactly
        // when the head unit suspends. Timed out well past the pulse so a stuck close can
        // never pin the unit awake, and released in the finally regardless.
        PowerManager power = getSystemService(PowerManager.class);
        PowerManager.WakeLock wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EVABRP:windowClose");
        wakeLock.acquire(WindowClosePulse.DURATION_MS + 10_000L);
        status = R.string.window_close_closing;
        log("WinClose: closing all four windows");
        try {
            boolean ok = monitor.closeAllWindowsPulsed(() ->
                    active && prefs.getBoolean(KEY_ENABLED, false));
            status = ok ? R.string.window_close_sent : R.string.window_close_interrupted;
            log(ok ? "Close pulse finished; STOP sent. Position is unknown."
                    : "Close interrupted/refused or command failed; check P and speed.");
        } catch (RuntimeException e) {
            status = R.string.window_close_interrupted;
            log("Close failed: " + e.getClass().getSimpleName());
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
            policy.finish();
        }
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 20,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 21,
                new Intent(this, WindowCloseService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.window_close_title))
                .setContentText(getString(R.string.window_close_notification))
                .setContentIntent(open)
                .addAction(0, getString(R.string.window_close_disable), stop)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        active = false;
        if (monitor != null) monitor.close();
        // The pulse runner releases all windows in finally, including an interrupted sleep.
        // Waited on so those STOP commands actually go out before the process moves on.
        if (worker != null) {
            worker.shutdownNow();
            try {
                if (!worker.awaitTermination(2, TimeUnit.SECONDS)) log("Worker did not stop in 2 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
        status = R.string.window_close_stopped;
        log("Service stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
