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
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * خدمة تعمل بالخلفية: تكشف الحركة من البث مباشرة، وعند الحركة تحلل الصورة
 * بالذكاء الاصطناعي وتنطق ما يحدث (إنسان، سيارة، دراجة...).
 * عندما تكون واجهة التطبيق ظاهرة تتوقف مؤقتاً (الواجهة تتولى العرض).
 */
public class MonitorService extends Service implements RtspMotionClient.Sink {

    public static volatile boolean uiVisible = false;
    public static MonitorService instance;

    private static final int NOTIF_ID = 1;
    private static final long SESSION_IDLE_MS = 8000;

    private final List<RtspMotionClient> clients = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final RtspMotionClient[] clientByCh = new RtspMotionClient[9];
    private final AtomicReferenceArray<H264Session> sessions = new AtomicReferenceArray<>(9);
    private final long[] lastActive = new long[9];
    private final long[] lastSpokenTime = new long[9];
    private final String[] lastSpokenText = new String[9];
    private final Object sessionLock = new Object();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean running = false;

    private Speaker speaker;
    private ObjectAnalyzer analyzer;

    private final H264Session.FrameCallback frameCallback = (ch, rgb) -> {
        ObjectAnalyzer a = analyzer;
        if (a != null) a.submit(ch, rgb);
    };

    private final ObjectAnalyzer.Listener analyzerListener = new ObjectAnalyzer.Listener() {
        @Override
        public void onEvent(int channel, String text) {
            handleEvent(channel, text);
        }

        @Override
        public void onActivity(int channel) {
            lastActive[channel] = SystemClock.elapsedRealtime();
        }
    };

    private final RtspMotionClient.Listener motionListener = (channel, onset) -> {
        boolean ai = analyzer != null && analyzer.isReady();
        // الصوت الفوري عند بداية الحركة دائماً، والتكرار فقط لو الذكاء غير جاهز
        if (!ai || onset) Notifier.alert(MonitorService.this, channel);
        if (ai) {
            lastActive[channel] = SystemClock.elapsedRealtime();
            startSession(channel);
        }
    };

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            cleanupSessions(false);
            try {
                StringBuilder sb = new StringBuilder("الكاميرات: ");
                for (RtspMotionClient c : clients) {
                    sb.append(c.getChannel()).append(c.isConnected() ? "✓ " : "✗ ");
                }
                String ai = analyzer == null ? "-" : analyzer.getStatus();
                String voice = (speaker != null && speaker.isReady()) ? "✓" : "✗ (الصوت العربي غير متوفر)";
                sb.append("\nالتعرف: ").append(ai).append("\nالنطق: ").append(voice);

                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, Notifier.serviceNotification(
                        MonitorService.this, sb.toString()));
            } catch (Exception ignored) {
            }
            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("cam", MODE_PRIVATE);
        Notifier.createChannels(this);
        speaker = new Speaker(this);
        analyzer = new ObjectAnalyzer(this, analyzerListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, Notifier.serviceNotification(this));
        if (analyzer != null) analyzer.ensureModel();
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

    // ---------- Sink ----------

    @Override
    public boolean collect() {
        ObjectAnalyzer a = analyzer;
        return a != null && a.isReady();
    }

    @Override
    public boolean isActive(int channel) {
        return sessions.get(channel) != null;
    }

    @Override
    public void onAccessUnit(int channel, byte[] au, boolean idr) {
        H264Session s = sessions.get(channel);
        if (s != null) s.feed(au, idr);
    }

    // ---------- جلسات التحليل ----------

    private void startSession(int channel) {
        synchronized (sessionLock) {
            if (sessions.get(channel) != null) return;
            RtspMotionClient c = clientByCh[channel];
            if (c == null) return;
            byte[] sps = c.getSps();
            byte[] pps = c.getPps();
            if (sps == null || pps == null) return;

            H264Session s = new H264Session(channel, frameCallback);
            if (!s.start(sps, pps)) return;
            analyzer.reset(channel);
            sessions.set(channel, s);

            boolean first = true;
            for (byte[] au : c.snapshotGop()) {
                s.feed(au, first);
                first = false;
            }
        }
    }

    private void cleanupSessions(boolean all) {
        long now = SystemClock.elapsedRealtime();
        synchronized (sessionLock) {
            for (int ch = 1; ch <= 8; ch++) {
                H264Session s = sessions.get(ch);
                if (s != null && (all || now - lastActive[ch] > SESSION_IDLE_MS)) {
                    sessions.set(ch, null);
                    s.release();
                }
            }
        }
    }

    private void handleEvent(int channel, String text) {
        long now = SystemClock.elapsedRealtime();
        String full = text + "، الكاميرا " + channel;
        if (full.equals(lastSpokenText[channel]) && now - lastSpokenTime[channel] < 4000) return;
        lastSpokenText[channel] = full;
        lastSpokenTime[channel] = now;
        Notifier.alertText(this, channel, full);
        Speaker sp = speaker;
        if (sp != null) sp.speak(full);
    }

    // ---------- المراقبة ----------

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
        if (analyzer != null) analyzer.ensureModel();

        String host = prefs.getString("ip", "192.168.0.112");
        String user = prefs.getString("user", "admin");
        String pass = prefs.getString("pass", "");

        for (int i = 1; i <= 8; i++) {
            if (!prefs.getBoolean("ch" + i, true)) continue;
            String path = "/user=" + user + "&password=" + pass
                    + "&channel=" + i + "&stream=1.sdp";
            RtspMotionClient c = new RtspMotionClient(i, host, 554, path, prefs,
                    motionListener, this);
            Thread t = new Thread(c, "rtsp-" + i);
            clients.add(c);
            clientByCh[i] = c;
            threads.add(t);
            t.start();
        }

        mainHandler.postDelayed(statusUpdater, 2000);
    }

    private void stopMonitoring() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        cleanupSessions(true);

        for (RtspMotionClient c : clients) {
            c.stop();
        }
        for (Thread t : threads) {
            t.interrupt();
        }
        clients.clear();
        threads.clear();
        for (int i = 0; i < clientByCh.length; i++) clientByCh[i] = null;
        releaseLocks();
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        instance = null;
        if (analyzer != null) analyzer.shutdown();
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
