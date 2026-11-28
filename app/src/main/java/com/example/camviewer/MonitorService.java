package com.example.camviewer;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * خدمة تعمل بالخلفية: تتصل بالكاميرات بدون شاشة وتكشف الحركة وترسل التنبيهات.
 * عندما تكون واجهة التطبيق ظاهرة، تتوقف الخدمة مؤقتاً (الواجهة تتولى الكشف).
 */
public class MonitorService extends Service {

    public static volatile boolean uiVisible = false;
    public static MonitorService instance;

    private static final int NOTIF_ID = 1;
    private static final int IMG_W = 320;
    private static final int IMG_H = 180;
    private static final long CHECK_MS = 200;
    private static final long WARMUP_MS = 2500;

    private static class Worker {
        int channel;
        ImageReader reader;
        MediaPlayer player;
        MotionDetector det = new MotionDetector();
        long lastCheck = 0;
        long start = SystemClock.elapsedRealtime();
    }

    private final List<Worker> workers = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread bgThread;
    private Handler bgHandler;
    private LibVLC libVLC;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean running = false;
    private boolean restartPending = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("cam", MODE_PRIVATE);
        Notifier.createChannels(this);
        bgThread = new HandlerThread("motion-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, Notifier.serviceNotification(this));
        if (!uiVisible) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
        return START_STICKY;
    }

    public void pause() {
        mainHandler.post(this::stopMonitoring);
    }

    public void resume() {
        mainHandler.post(() -> {
            if (!uiVisible) startMonitoring();
        });
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camviewer:monitor");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "camviewer:wifi");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private String buildUrl(int channel) {
        String ip = prefs.getString("ip", "192.168.0.112");
        String user = prefs.getString("user", "admin");
        String pass = prefs.getString("pass", "");
        return "rtsp://" + ip + ":554/user=" + user + "&password=" + pass
                + "&channel=" + channel + "&stream=1.sdp";
    }

    private void startMonitoring() {
        stopMonitoring();
        running = true;
        acquireLocks();

        try {
            ArrayList<String> opts = new ArrayList<>();
            opts.add("--rtsp-tcp");
            opts.add("--network-caching=300");
            libVLC = new LibVLC(this, opts);

            for (int i = 1; i <= 8; i++) {
                if (prefs.getBoolean("ch" + i, true)) {
                    workers.add(createWorker(i));
                }
            }
        } catch (Exception e) {
            scheduleRestart();
        }
    }

    private Worker createWorker(int channel) {
        final Worker w = new Worker();
        w.channel = channel;

        w.reader = ImageReader.newInstance(IMG_W, IMG_H, PixelFormat.RGBA_8888, 2);
        w.reader.setOnImageAvailableListener(r -> onFrame(w, r), bgHandler);

        w.player = new MediaPlayer(libVLC);
        IVLCVout vout = w.player.getVLCVout();
        vout.setVideoSurface(w.reader.getSurface(), null);
        vout.setWindowSize(IMG_W, IMG_H);
        vout.attachViews();

        w.player.setEventListener(ev -> {
            if (ev.type == MediaPlayer.Event.EncounteredError
                    || ev.type == MediaPlayer.Event.EndReached) {
                scheduleRestart();
            }
        });

        Media media = new Media(libVLC, Uri.parse(buildUrl(channel)));
        media.setHWDecoderEnabled(false, false);
        media.addOption(":network-caching=300");
        media.addOption(":android-display-chroma=RV32");
        w.player.setMedia(media);
        media.release();
        w.player.play();
        return w;
    }

    private void onFrame(Worker w, ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;

            long now = SystemClock.elapsedRealtime();
            if (now - w.lastCheck < CHECK_MS) return;
            w.lastCheck = now;

            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int iw = img.getWidth();
            int ih = img.getHeight();

            int[] gray = new int[MotionDetector.W * MotionDetector.H];
            for (int y = 0; y < MotionDetector.H; y++) {
                int sy = y * ih / MotionDetector.H;
                for (int x = 0; x < MotionDetector.W; x++) {
                    int sx = x * iw / MotionDetector.W;
                    int off = sy * rowStride + sx * pixelStride;
                    int r = buf.get(off) & 0xFF;
                    int g = buf.get(off + 1) & 0xFF;
                    int b = buf.get(off + 2) & 0xFF;
                    gray[y * MotionDetector.W + x] = (r * 30 + g * 59 + b * 11) / 100;
                }
            }

            if (now - w.start < WARMUP_MS) {
                w.det.prime(gray);
                return;
            }

            if (w.det.update(gray, prefs.getInt("sens", 5), now)) {
                Notifier.alert(this, w.channel);
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private void scheduleRestart() {
        if (restartPending || !running) return;
        restartPending = true;
        mainHandler.postDelayed(() -> {
            restartPending = false;
            if (running && !uiVisible) startMonitoring();
        }, 3000);
    }

    private void stopMonitoring() {
        running = false;
        restartPending = false;
        mainHandler.removeCallbacksAndMessages(null);

        for (Worker w : workers) {
            try {
                w.player.stop();
                w.player.getVLCVout().detachViews();
                w.player.release();
            } catch (Exception ignored) {
            }
            try {
                w.reader.close();
            } catch (Exception ignored) {
            }
        }
        workers.clear();

        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Exception ignored) {
            }
            libVLC = null;
        }
        releaseLocks();
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        instance = null;
        if (bgThread != null) bgThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
