package com.evsuite.abrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/**
 * Monitors reverse gear on the MG4 head unit. When in reverse, places an overlay on the
 * right 65% of the screen (1250x720) to show the 70mai rear camera live stream via LibVLC,
 * leaving the OEM park radar / distance sensor visible on the left side.
 */
public class ReverseOverlayService extends Service {
    private static final String TAG = "ReverseOverlayService";
    private static final String CHANNEL_ID = "maistream_reverse_channel";
    private static final int NOTIF_ID = 202;
    public static final String PREF_REVERSE_CAM_ENABLED = "reverse_cam_enabled";

    private WindowManager windowManager;
    private View overlayView;
    private SurfaceView surfaceOverlay;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private boolean isOverlayShowing = false;

    private Handler monitorHandler;
    private Runnable monitorRunnable;
    private boolean lastWasReverse = false;
    private int reverseFalseCount = 0;
    private static final int DEBOUNCE_THRESHOLD = 5; // ~1.5s stability buffer to prevent focus flicker drops

    private static final String RTSP_URL = "rtsp://192.168.0.1:554/livestream/22";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MG4 Geri Vites Kamerası")
                .setContentText("Geri vites kamerası izleniyor")
                .setSmallIcon(R.drawable.ic_tab_camera)
                .build();
        startForeground(NOTIF_ID, notification);

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startGearMonitoring();
    }

    private void startGearMonitoring() {
        monitorHandler = new Handler(Looper.getMainLooper());
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
                boolean enabled = prefs.getBoolean(PREF_REVERSE_CAM_ENABLED, true);

                if (!enabled) {
                    if (isOverlayShowing) {
                        hideOverlay();
                        lastWasReverse = false;
                    }
                    monitorHandler.postDelayed(this, 1000);
                    return;
                }

                boolean inReverse = checkIsReverse();
                if (inReverse) {
                    reverseFalseCount = 0;
                    if (!lastWasReverse) {
                        lastWasReverse = true;
                        Log.i(TAG, "Geri vites algılandı -> Overlay ve LibVLC başlatılıyor");
                        showOverlay();
                    }
                } else {
                    if (lastWasReverse) {
                        reverseFalseCount++;
                        if (reverseFalseCount >= DEBOUNCE_THRESHOLD) {
                            lastWasReverse = false;
                            reverseFalseCount = 0;
                            Log.i(TAG, "Geri vitesten çıkıldı (onaylandı) -> Overlay kapatılıyor");
                            hideOverlay();
                        }
                    }
                }
                monitorHandler.postDelayed(this, 300);
            }
        };
        monitorHandler.post(monitorRunnable);
    }

    private boolean checkIsReverse() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String gear = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "arcsoft.avm.mCurCarGear", "0");
            if ("2".equals(gear)) {
                return true;
            }

            // Fallback: check window focus and active window records for SAIC EVS (radar screen)
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "dumpsys window | grep -iE 'mCurrentFocus|mFocusedApp' | grep -q 'com.saicmotor.evs'"});
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "checkIsReverse error: " + t.getMessage());
        }
        return false;
    }

    private void showOverlay() {
        if (isOverlayShowing) return;

        // TYPE_SYSTEM_ERROR is the only window type that layers on top of OEM SAIC EVS radar
        int layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;

        // MG4 screen: 1920x720. 65% of screen width = 1250px on the right side
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1250,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.x = 0;
        params.y = 0;

        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.overlay_camera, null);
        surfaceOverlay = overlayView.findViewById(R.id.surfaceOverlay);

        try {
            windowManager.addView(overlayView, params);
            isOverlayShowing = true;
            startVlcStream();
            Log.d(TAG, "Overlay sağ tarafa yerleştirildi ve LibVLC açıldı (1250x720)");
        } catch (Exception e) {
            Log.e(TAG, "Overlay ekleme hatası", e);
        }
    }

    private void startVlcStream() {
        try {
            stopVlcStream();
            ArrayList<String> options = new ArrayList<>();
            options.add("--rtsp-tcp");
            options.add("--network-caching=600");
            options.add("--live-caching=600");
            options.add("--no-audio");
            options.add("--clock-jitter=0");
            options.add("--clock-synchro=0");
            options.add("--drop-late-frames");
            options.add("--skip-frames");
            options.add("--avcodec-hurry-up");

            libVLC = new LibVLC(this, options);
            mediaPlayer = new MediaPlayer(libVLC);

            if (surfaceOverlay != null) {
                IVLCVout vout = mediaPlayer.getVLCVout();
                vout.setVideoView(surfaceOverlay);
                vout.setWindowSize(1250, 720);
                vout.attachViews();
            }

            // Force stretch to fill the entire 1250x720 area without shrinking
            mediaPlayer.setAspectRatio("125:72");
            mediaPlayer.setScale(0f);

            // Reconnect listener if camera stream drops
            mediaPlayer.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached) {
                    Log.w(TAG, "Stream koptu, otomatik yeniden bağlanılıyor...");
                    if (isOverlayShowing) {
                        monitorHandler.postDelayed(() -> {
                            if (isOverlayShowing) startVlcStream();
                        }, 500);
                    }
                }
            });

            Media media = new Media(libVLC, Uri.parse(RTSP_URL));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=600");
            media.addOption(":live-caching=600");
            media.addOption(":rtsp-tcp");
            media.addOption(":clock-jitter=0");
            media.addOption(":clock-synchro=0");
            media.addOption(":drop-late-frames");
            media.addOption(":skip-frames");

            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
        } catch (Exception e) {
            Log.e(TAG, "Overlay VLC error", e);
        }
    }

    private void stopVlcStream() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                if (mediaPlayer.getVLCVout() != null) {
                    mediaPlayer.getVLCVout().detachViews();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Exception ignored) {}
            libVLC = null;
        }
    }

    private void hideOverlay() {
        if (!isOverlayShowing || overlayView == null) return;
        try {
            stopVlcStream();
            windowManager.removeView(overlayView);
            isOverlayShowing = false;
            overlayView = null;
            surfaceOverlay = null;
        } catch (Exception e) {
            Log.e(TAG, "Overlay kaldırma hatası", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }
        hideOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MG4 Geri Vites Kamerası",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
