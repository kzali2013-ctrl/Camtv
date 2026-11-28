package com.example.camviewer;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * خدمة تعمل بالخلفية: تتصل بالكاميرات وتحلل بيانات البث وترسل تنبيه الحركة.
 * عندما تكون واجهة التطبيق ظاهرة تتوقف مؤقتاً (الواجهة تتولى الكشف).
 */
public class MonitorService extends Service {

    public static volatile boolean uiVisible = false;
    public static MonitorService instance;

    private static final int NOTIF_ID = 1;

    private final List<RtspMotionClient> clients = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean running = false;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                StringBuilder sb = new StringBuilder("الكاميرات: ");
                for (RtspMotionClient c : clients) {
                    sb.append(c.getChannel()).append(c.isConnected() ? "✓ " : "✗ ");
                }
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, Notifier.serviceNotification(
                        MonitorService.this, sb.toString().trim()));
            } catch (Exception ignored) {
            }
            mainHandler.postDelayed(this, 3000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("cam", MODE_PRIVATE);
        Notifier.createChannels(this);
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

    private void startMonitoring() {
        stopMonitoring();
        running = true;
        acquireLocks();

        String host = prefs.getString("ip", "192.168.0.112");
        String user = prefs.getString("user", "admin");
        String pass = prefs.getString("pass", "");

        RtspMotionClient.Listener listener = channel -> Notifier.alert(MonitorService.this, channel);

        for (int i = 1; i <= 8; i++) {
            if (!prefs.getBoolean("ch" + i, true)) continue;
            String path = "/user=" + user + "&password=" + pass
                    + "&channel=" + i + "&stream=1.sdp";
            RtspMotionClient c = new RtspMotionClient(i, host, 554, path, prefs, listener);
            Thread t = new Thread(c, "rtsp-" + i);
            clients.add(c);
            threads.add(t);
            t.start();
        }

        mainHandler.postDelayed(statusUpdater, 2000);
    }

    private void stopMonitoring() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);

        for (RtspMotionClient c : clients) {
            c.stop();
        }
        for (Thread t : threads) {
            t.interrupt();
        }
        clients.clear();
        threads.clear();
        releaseLocks();
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
