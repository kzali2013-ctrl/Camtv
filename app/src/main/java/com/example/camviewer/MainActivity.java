package com.example.camviewer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText etIp, etUser, etPass;
    private LinearLayout videoContainer;
    private final CheckBox[] checks = new CheckBox[8];
    private final List<MediaPlayer> players = new ArrayList<>();
    private final List<VLCVideoLayout> layouts = new ArrayList<>();
    private LibVLC libVLC;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("cam", MODE_PRIVATE);

        etIp = findViewById(R.id.etIp);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
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

        ((Button) findViewById(R.id.btnTest)).setOnClickListener(v -> {
            saveSettings();
            List<Integer> one = new ArrayList<>();
            one.add(1);
            startStreams(one, 0);
        });

        ((Button) findViewById(R.id.btnBack)).setOnClickListener(v -> {
            stopStreams();
            finish();
        });

        ((Button) findViewById(R.id.btnStop)).setOnClickListener(v -> stopStreams());

        ((Button) findViewById(R.id.btnConnect)).setOnClickListener(v -> {
            saveSettings();
            List<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                if (checks[i].isChecked()) chosen.add(i + 1);
            }
            if (chosen.isEmpty()) {
                Toast.makeText(this, "اختر كاميرا واحدة على الأقل", Toast.LENGTH_SHORT).show();
                return;
            }
            startStreams(chosen, chosen.size() == 1 ? 0 : 1);
        });
    }

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
        mp.attachViews(vlcLayout, null, false, false);

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

        players.add(mp);
        layouts.add(vlcLayout);
        return cell;
    }

    private void stopStreams() {
        for (MediaPlayer p : players) {
            try {
                p.stop();
                p.detachViews();
                p.release();
            } catch (Exception ignored) {
            }
        }
        players.clear();
        layouts.clear();
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        videoContainer.removeAllViews();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopStreams();
    }
}