package com.example.camviewer;

import android.app.Activity;
import android.content.SharedPreferences;
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

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText etIp, etUser, etPass;
    private LinearLayout videoContainer;
    private final CheckBox[] checks = new CheckBox[8];
    private final List<ExoPlayer> players = new ArrayList<>();
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

    private String describeError(PlaybackException error) {
        StringBuilder sb = new StringBuilder();
        sb.append(error.getErrorCodeName());
        Throwable c = error.getCause();
        int depth = 0;
        while (c != null && depth < 4) {
            sb.append("\n").append(c.toString());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private void startStreams(List<Integer> channels, int stream) {
        stopStreams();

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

        PlayerView pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout cell = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(2, 2, 2, 2);
        cell.setLayoutParams(lp);
        cell.addView(pv);
        cell.addView(status);

        ExoPlayer player = new ExoPlayer.Builder(this).build();
        pv.setPlayer(player);

        MediaItem item = MediaItem.fromUri(buildUrl(channel, stream));
        RtspMediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(item);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.setText("CH" + channel);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("CH" + channel + " " + describeError(error));
            }
        });

        player.setMediaSource(source);
        player.prepare();
        player.play();
        players.add(player);
        return cell;
    }

    private void stopStreams() {
        for (ExoPlayer p : players) {
            p.release();
        }
        players.clear();
        videoContainer.removeAllViews();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopStreams();
    }
}