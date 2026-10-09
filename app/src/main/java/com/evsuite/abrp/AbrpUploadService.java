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
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.evsuite.hardware.telemetry.EnergySnapshot;
import com.evsuite.hardware.telemetry.EnergyTelemetryReader;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that polls vehicle telemetry every 15 s, integrates consumption locally,
 * and syncs it to the cloud (MongoDB) in 30-minute delta chunks. Never sends anything to ABRP.
 *
 * Always-on by design: started at boot (BootReceiver) and on app open, never gated on
 * credentials or a toggle. The only thing it needs is a network for the cloud sync, and a
 * missing one is handled by caching chunks locally until the connection returns.
 */
public class AbrpUploadService extends Service {

    /**
     * Live in-process signal. The "service_running" preference cannot be trusted on its
     * own: a force-kill (adb install -r, low-memory kill) skips onDestroy and leaves it
     * stale-true forever. This field dies with the process, so it is only ever true while
     * the service really is up.
     */
    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    /**
     * Upload history, shared with the UI. Static because the activity reads it while the
     * service owns it; it lives and dies with the process, like {@link #running}.
     */
    private static final UploadLog uploadLog = new UploadLog();

    public static UploadLog log() { return uploadLog; }

    public static UploadLog.State state() { return uploadLog.state(running); }

    private static final String TAG             = "AbrpUploadService";
    private static final String CHANNEL_ID      = "abrp_uploader";
    private static final int    NOTIF_ID        = 1;
    /** Scheduler tick — vehicle telemetry is sampled this often. */
    private static final long   UPLOAD_INTERVAL_SEC = 15;
    /** Warm-up before the first sample. */
    private static final long   FIRST_UPLOAD_DELAY_SEC = 20;

    private EnergyTelemetryReader energyReader;
    private ConsumptionTracker consumptionTracker;
    private ConsumptionCloudClient consumptionCloudClient;
    private ChargeSessionTracker chargeSessionTracker;
    private WifiAutoConnect wifi;
    private ScheduledExecutorService scheduler;
    private SharedPreferences prefs;

    /** What the foreground notification currently says, so re-asserting it does not blank it. */
    private volatile String lastStatus = "Starting…";

    // ---------- Lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        uploadLog.clear();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "abrp-upload");
            t.setDaemon(false);
            return t;
        });

        createNotificationChannel();
        startInForeground();
        running = true;
        prefs.edit().putBoolean("service_running", true).apply();

        energyReader = new EnergyTelemetryReader(getApplicationContext());
        consumptionTracker = ConsumptionTracker.get(this);
        consumptionCloudClient = new ConsumptionCloudClient(getApplicationContext());
        chargeSessionTracker = ChargeSessionTracker.get(getApplicationContext());
        wifi = new WifiAutoConnect(getApplicationContext());

        scheduler.scheduleWithFixedDelay(
                this::safeUploadCycle,
                FIRST_UPLOAD_DELAY_SEC, UPLOAD_INTERVAL_SEC, TimeUnit.SECONDS);

        uploadLog.record(new UploadLog.Entry(System.currentTimeMillis(), 0, true,
                "Tüketim servisi başlatıldı"));
        Log.i(TAG, "Service started, consumption tracking armed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        return START_STICKY;
    }

    /**
     * Enters the foreground. No location type — this service no longer reads GPS. Foreground
     * service types do not exist before API 29, so on this API-28 head unit a plain
     * startForeground is the only correct call.
     */
    private void startInForeground() {
        Notification notification = buildNotification(lastStatus);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                    this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        prefs.edit().putBoolean("service_running", false).apply();
        if (scheduler != null) scheduler.shutdownNow();
        Log.i(TAG, "Service stopped");
    }

    // ---------- Sampling cycle (runs on scheduler thread) ----------

    private void safeUploadCycle() {
        try {
            doUpload();
        } catch (Throwable t) {
            Log.e(TAG, "upload cycle threw", t);
        }
    }

    private void doUpload() {
        long sampleMs = System.currentTimeMillis();
        EnergySnapshot vehicle = energyReader.read(sampleMs);
        consumptionTracker.sample(vehicle);
        chargeSessionTracker.sample(vehicle);
        consumptionCloudClient.syncIfDue(consumptionTracker, chargeSessionTracker, sampleMs);

        // Keep the head unit on the phone hotspot — the sync needs a network, and this is
        // exactly when being offline costs something. Rate-limited internally.
        if (wifi != null && prefs.getBoolean(MainActivity.WIFI_AUTO_KEY, true)) {
            wifi.ensureConnected(false);
        }

        updateNotification("Tüketim takibi aktif");
    }

    // ---------- Notification ----------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String status) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(status)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        if (status.equals(lastStatus)) return;
        lastStatus = status;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(status));
    }
}
