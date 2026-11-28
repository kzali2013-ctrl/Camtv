package com.example.camviewer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final long CHECK_MS = 250;
    private static final long WARMUP_MS = 3000;

    private static class Cell {
        int channel;
        TextView status;
        VLCVideoLayout layout;
        TextureView tv;
        MotionDetector det = new MotionDetector();
        long startTime = SystemClock.elapsedRealtime();
    }

    private EditText etIp, etUser, etPass;
    private TextView tvSens;
    private SeekBar sbSens;
    private LinearLayout videoContainer;
    private final CheckBox[] checks = new CheckBox[8];
    private final List<MediaPlayer> players = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private LibVLC libVLC;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable motionRunnable = new Runnable() {
        @Override
        public void run() {
            for (Cell c : cells) {
                checkMotion(c);
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Notifier.createChannels(this);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }

        prefs = getSharedPreferences("cam", MODE_PRIVATE);

        etIp = findViewById(R.id.etIp);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        tvSens = findViewById(R.id.tvSens);
        sbSens = findViewById(R.id.sbSens);
        videoContainer = findViewById(R.id.videoContainer);

        int[] ids = {R.id.cb1, R.id.cb2, R.id.cb3, R.id.cb4,
                R.id.cb5, R.id.cb6, R.id.cb7, R.id.cb8};
        for (int i = 0; i < 8; i++) {
            checks[i] = findViewById(ids[i]);
            checks[i].setChecked(prefs.getBoolean("ch" + (i + 1), true));
        }

        etIp.setText(prefs.getString("ip", "192.168.0.112"));
        etUser.setText(prefs.getString("user", "admin"));
        etPass.setText(prefs.getString("pass", ""));

        int level = prefs.getInt("sens", 5);
        sbSens.setProgress(level - 1);
        tvSens.setText("الحساسية: " + level);
        sbSens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int lv = progress + 1;
                tvSens.setText("الحساسية: " + lv);
                prefs.edit().putInt("sens", lv).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        ((Button) findViewById(R.id.btnTest)).setOnClickListener(v -> {
            saveSettings();
            List<Integer> one = new ArrayList<>();
            one.add(1);
            startStreams(one, 0);
        });

        // رجوع: يخرج من الشاشة والمراقبة تستمر بالخلفية
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(v -> moveTaskToBack(true));

        // Stop: يوقف كل شيء بما فيه المراقبة بالخلفية
        ((Button) findViewById(R.id.btnStop)).setOnClickListener(v -> {
            prefs.edit().putBoolean("monitoring", false).apply();
            stopService(new Intent(this, MonitorService.class));
            stopStreams();
            Toast.makeText(this, "تم إيقاف المراقبة", Toast.LENGTH_SHORT).show();
        });

        ((Button) findViewById(R.id.btnConnect)).setOnClickListener(v -> {
            saveSettings();
            List<Integer> chosen = chosenChannels();
            if (chosen.isEmpty()) {
                Toast.makeText(this, "اختر كاميرا واحدة على الأقل", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putBoolean("monitoring", true).apply();
            startMonitorService();
            startStreams(chosen, chosen.size() == 1 ? 0 : 1);
            askBatteryExemption();
        });
    }

    private List<Integer> chosenChannels() {
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            if (checks[i].isChecked()) chosen.add(i + 1);
        }
        return chosen;
    }

    private void startMonitorService() {
        try {
            Intent i = new Intent(this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "تعذر تشغيل خدمة الخلفية", Toast.LENGTH_LONG).show();
        }
    }

    private void askBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (prefs.getBoolean("battery_asked", false)) return;
        try {
            prefs.edit().putBoolean("battery_asked", true).apply();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        MonitorService.uiVisible = true;
        MonitorService s = MonitorService.instance;
        if (s != null) s.pause();

        if (prefs.getBoolean("monitoring", false) && players.isEmpty()) {
            List<Integer> chosen = chosenChannels();
            if (!chosen.isEmpty()) {
                startStreams(chosen, chosen.size() == 1 ? 0 : 1);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        MonitorService.uiVisible = false;
        stopStreams();

        if (prefs.getBoolean("monitoring", false)) {
            MonitorService s = MonitorService.instance;
            if (s != null) {
                s.resume();
            } else {
                startMonitorService();
            }
        }
    }

    // ---------- كشف الحركة (والواجهة ظاهرة) ----------

    private TextureView findTextureView(View v) {
        if (v instanceof TextureView) return (TextureView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextureView t = findTextureView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private void checkMotion(Cell c) {
        long now = SystemClock.elapsedRealtime();

        if (c.tv == null) c.tv = findTextureView(c.layout);
        if (c.tv == null || !c.tv.isAvailable()) return;

        Bitmap bmp;
        try {
            bmp = c.tv.getBitmap(MotionDetector.W, MotionDetector.H);
        } catch (Exception e) {
            return;
        }
        if (bmp == null) return;

        int[] px = new int[MotionDetector.W * MotionDetector.H];
        bmp.getPixels(px, 0, MotionDetector.W, 0, 0, MotionDetector.W, MotionDetector.H);
        bmp.recycle();
        int[] gray = MotionDetector.toGray(px);

        if (now - c.startTime < WARMUP_MS) {
            c.det.prime(gray);
            return;
        }

        if (c.det.update(gray, prefs.getInt("sens", 5), now)) {
            onMotion(c);
        }
    }

    private void onMotion(final Cell c) {
        c.status.setText("CH" + c.channel + "  ⚠ حركة");
        c.status.setTextColor(0xFFFF4444);
        handler.postDelayed(() -> {
            c.status.setText("CH" + c.channel);
            c.status.setTextColor(0xFFFFFFFF);
        }, 2000);
        Notifier.alert(this, c.channel);
    }

    // ---------- الإعدادات والبث ----------

    private void saveSettings() {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("ip", etIp.getText().toString().trim());
        e.putString("user", etUser.getText().toString().trim());
        e.putString("pass", etPass.getText().toString());
        for (int i = 0; i < 8; i++) {
            e.putBoolean("ch" + (i + 1), checks[i].isChecked());
        }
        e.apply();
    }

    private String buildUrl(int channel, int stream) {
        String ip = etIp.getText().toString().trim();
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString();
        return "rtsp://" + ip + ":554/user=" + user + "&password=" + pass
                + "&channel=" + channel + "&stream=" + stream + ".sdp";
    }

    private void startStreams(List<Integer> channels, int stream) {
        stopStreams();

        ArrayList<String> options = new ArrayList<>();
        options.add("--rtsp-tcp");
        options.add("--network-caching=300");
        libVLC = new LibVLC(this, options);

        int n = channels.size();
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);

        int index = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            for (int c = 0; c < cols; c++) {
                if (index < n) {
                    int channel = channels.get(index);
                    row.addView(createCell(channel, stream));
                    index++;
                } else {
                    View filler = new View(this);
                    filler.setLayoutParams(new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                    row.addView(filler);
                }
            }
            videoContainer.addView(row);
        }

        handler.removeCallbacks(motionRunnable);
        handler.postDelayed(motionRunnable, CHECK_MS);
    }

    private View createCell(int channel, int stream) {
        final TextView status = new TextView(this);
        status.setText("CH" + channel + " ...");
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(11);
        status.setPadding(8, 4, 8, 4);

        VLCVideoLayout vlcLayout = new VLCVideoLayout(this);
        vlcLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout cell = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(2, 2, 2, 2);
        cell.setLayoutParams(lp);
        cell.addView(vlcLayout);
        cell.addView(status);

        MediaPlayer mp = new MediaPlayer(libVLC);
        // آخر قيمة true: استخدام TextureView ليمكن قراءة الصورة وكشف الحركة
        mp.attachViews(vlcLayout, null, false, true);

        mp.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    status.setText("CH" + channel);
                    mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
                    break;
                case MediaPlayer.Event.EncounteredError:
                    status.setText("CH" + channel + " خطأ في التشغيل");
                    break;
                case MediaPlayer.Event.EndReached:
                    status.setText("CH" + channel + " انتهى البث");
                    break;
                default:
                    break;
            }
        });

        Media media = new Media(libVLC, Uri.parse(buildUrl(channel, stream)));
        media.addOption(":network-caching=300");
        mp.setMedia(media);
        media.release();
        mp.play();

        Cell data = new Cell();
        data.channel = channel;
        data.status = status;
        data.layout = vlcLayout;
        cells.add(data);
        players.add(mp);
        return cell;
    }

    private void stopStreams() {
        handler.removeCallbacks(motionRunnable);
        for (MediaPlayer p : players) {
            try {
                p.stop();
                p.detachViews();
                p.release();
            } catch (Exception ignored) {
            }
        }
        players.clear();
        cells.clear();
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        videoContainer.removeAllViews();
    }
}
