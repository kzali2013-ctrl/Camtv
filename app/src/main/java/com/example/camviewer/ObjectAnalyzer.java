package com.example.camviewer;

import android.content.Context;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * التعرف على الأشياء (إنسان، سيارة، دراجة...) وتتبعها ووصف ما يحدث.
 * النموذج: COCO SSD MobileNet (يُحمَّل مرة واحدة من موقع TensorFlow).
 */
public class ObjectAnalyzer {

    public interface Listener {
        void onEvent(int channel, String text);

        void onActivity(int channel);
    }

    private static final String MODEL_URL =
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/"
                    + "coco_ssd_mobilenet_v1_1.0_quant_2018_06_29.zip";

    private static final int SIZE = H264Session.SIZE;
    private static final float MIN_SCORE = 0.5f;

    // إعدادات الحركة (نسبة من عرض الصورة)
    private static final float MATCH_DIST = 0.25f;
    private static final float MOVE_TH = 0.03f;
    private static final float STILL_TH = 0.015f;
    private static final long WINDOW_MS = 1500;
    private static final long STOP_MS = 2500;
    private static final long LOST_MS = 3000;
    private static final long START_GRACE_MS = 1500;

    // أسماء الأصناف حسب رقمها في نموذج COCO
    private static final String[] NAMES = new String[100];
    private static final boolean[] FEM = new boolean[100];

    static {
        set(0, "إنسان", false);
        set(1, "دراجة", true);
        set(2, "سيارة", true);
        set(3, "دراجة نارية", true);
        set(5, "حافلة", true);
        set(7, "شاحنة", true);
        set(16, "قطة", true);
        set(17, "كلب", false);
    }

    private static void set(int cls, String name, boolean fem) {
        NAMES[cls] = name;
        FEM[cls] = fem;
    }

    private final Context ctx;
    private final Listener listener;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Tracker[] trackers = new Tracker[9];

    private volatile Interpreter interpreter;
    private volatile boolean ready = false;
    private volatile boolean loading = false;
    private volatile String status = "غير محمّل";
    private boolean floatInput = false;
    private ByteBuffer inputBuf;

    public ObjectAnalyzer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() {
        return ready;
    }

    public String getStatus() {
        return status;
    }

    // ---------- تحميل النموذج ----------

    public synchronized void ensureModel() {
        if (ready || loading) return;
        loading = true;
        status = "جاري التجهيز...";
        new Thread(() -> {
            File f = new File(ctx.getFilesDir(), "detect.tflite");
            try {
                if (!f.exists() || f.length() < 100000) {
                    status = "جاري تحميل النموذج...";
                    download(f);
                }
                load(f);
                ready = true;
                status = "جاهز";
            } catch (Throwable e) {
                status = "فشل (" + e.getClass().getSimpleName() + ")";
                try {
                    f.delete();
                } catch (Exception ignored) {
                }
            } finally {
                loading = false;
            }
        }, "model-loader").start();
    }

    private void download(File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        File tmp = new File(dest.getPath() + ".tmp");
        boolean found = false;
        try {
            InputStream raw = c.getInputStream();
            ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw));
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().endsWith("detect.tflite")) {
                    FileOutputStream fo = new FileOutputStream(tmp);
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = zin.read(b)) > 0) fo.write(b, 0, n);
                    fo.close();
                    found = true;
                    break;
                }
            }
            zin.close();
        } finally {
            c.disconnect();
        }
        if (!found || tmp.length() < 100000) {
            tmp.delete();
            throw new IOException("bad model");
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("rename");
    }

    private void load(File f) {
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(2);
        Interpreter it = new Interpreter(f, o);
        int[] shape = it.getInputTensor(0).shape();
        if (shape.length != 4 || shape[1] != SIZE || shape[2] != SIZE) {
            it.close();
            throw new IllegalStateException("shape");
        }
        floatInput = it.getInputTensor(0).dataType() == DataType.FLOAT32;
        inputBuf = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * (floatInput ? 4 : 1))
                .order(ByteOrder.nativeOrder());
        interpreter = it;
    }

    // ---------- التحليل ----------

    public void submit(final int channel, final byte[] rgb) {
        if (!ready) return;
        if (pending.get() >= 3) return;
        pending.incrementAndGet();
        exec.execute(() -> {
            try {
                process(channel, rgb);
            } catch (Throwable ignored) {
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    public void reset(final int channel) {
        exec.execute(() -> trackers[channel] = null);
    }

    public void shutdown() {
        try {
            exec.shutdownNow();
        } catch (Exception ignored) {
        }
        try {
            if (interpreter != null) interpreter.close();
        } catch (Exception ignored) {
        }
        ready = false;
    }

    private static class Det {
        int cls;
        float x, y;
    }

    private void process(int channel, byte[] rgb) {
        Interpreter it = interpreter;
        if (it == null) return;

        inputBuf.rewind();
        if (floatInput) {
            for (byte b : rgb) inputBuf.putFloat(((b & 0xFF) - 127.5f) / 127.5f);
        } else {
            inputBuf.put(rgb);
        }
        inputBuf.rewind();

        float[][][] boxes = new float[1][10][4];
        float[][] classes = new float[1][10];
        float[][] scores = new float[1][10];
        float[] count = new float[1];
        Map<Integer, Object> outs = new HashMap<>();
        outs.put(0, boxes);
        outs.put(1, classes);
        outs.put(2, scores);
        outs.put(3, count);
        it.runForMultipleInputsOutputs(new Object[]{inputBuf}, outs);

        List<Det> dets = new ArrayList<>();
        int n = Math.min(10, (int) count[0]);
        for (int i = 0; i < n; i++) {
            if (scores[0][i] < MIN_SCORE) continue;
            int cls = (int) classes[0][i];
            if (cls < 0 || cls >= NAMES.length || NAMES[cls] == null) continue;
            Det d = new Det();
            d.cls = cls;
            d.x = (boxes[0][i][1] + boxes[0][i][3]) / 2f;
            d.y = (boxes[0][i][0] + boxes[0][i][2]) / 2f;
            dets.add(d);
        }

        long now = System.currentTimeMillis();
        Tracker t = trackers[channel];
        if (t == null) {
            t = new Tracker(now);
            trackers[channel] = t;
        }
        List<String> events = t.update(dets, now);
        if (t.anyMoving) listener.onActivity(channel);
        for (String e : events) listener.onEvent(channel, e);
    }

    // ---------- تتبع الأشياء ----------

    private static class Track {
        int cls;
        float x, y;
        long first, last;
        int hits;
        final List<double[]> hist = new ArrayList<>();
        boolean announced, wasMoving, stoppedAnnounced, background;
        long stillSince = 0;
    }

    private static class Tracker {
        final long sessionStart;
        final List<Track> tracks = new ArrayList<>();
        boolean anyMoving = false;

        Tracker(long start) {
            sessionStart = start;
        }

        List<String> update(List<Det> dets, long now) {
            List<String> ev = new ArrayList<>();
            boolean[] used = new boolean[tracks.size()];
            List<Track> fresh = new ArrayList<>();

            for (Det d : dets) {
                int best = -1;
                float bd = MATCH_DIST;
                for (int i = 0; i < tracks.size(); i++) {
                    if (used[i]) continue;
                    Track t = tracks.get(i);
                    if (t.cls != d.cls) continue;
                    float dist = (float) Math.hypot(t.x - d.x, t.y - d.y);
                    if (dist < bd) {
                        bd = dist;
                        best = i;
                    }
                }
                if (best >= 0) {
                    used[best] = true;
                    Track t = tracks.get(best);
                    t.x = d.x;
                    t.y = d.y;
                    t.last = now;
                    t.hits++;
                    addHist(t, now);
                } else {
                    Track t = new Track();
                    t.cls = d.cls;
                    t.x = d.x;
                    t.y = d.y;
                    t.first = now;
                    t.last = now;
                    t.hits = 1;
                    addHist(t, now);
                    fresh.add(t);
                }
            }
            tracks.addAll(fresh);

            anyMoving = false;
            for (Track t : tracks) {
                evaluate(t, now, ev);
            }

            Iterator<Track> it = tracks.iterator();
            while (it.hasNext()) {
                if (now - it.next().last > LOST_MS) it.remove();
            }
            return ev;
        }

        private void addHist(Track t, long now) {
            t.hist.add(new double[]{now, t.x, t.y});
            if (t.hist.size() > 8) t.hist.remove(0);
        }

        private float displacement(Track t, long now) {
            for (double[] h : t.hist) {
                if (now - (long) h[0] <= WINDOW_MS) {
                    return (float) Math.hypot(t.x - h[1], t.y - h[2]);
                }
            }
            return 0f;
        }

        private void evaluate(Track t, long now, List<String> ev) {
            if (t.hits < 2) return;

            float disp = displacement(t, now);
            boolean moving = disp >= MOVE_TH;
            if (moving) {
                t.stillSince = 0;
                anyMoving = true;
            } else if (disp < STILL_TH && t.stillSince == 0) {
                t.stillSince = now;
            }

            String name = NAMES[t.cls];
            boolean fem = FEM[t.cls];

            if (!t.announced) {
                if (moving) {
                    ev.add(name + (fem ? " تمر" : " يمر"));
                    t.announced = true;
                    t.wasMoving = true;
                } else if (t.first - sessionStart < START_GRACE_MS) {
                    // شيء ثابت من البداية (مثل سيارة مركونة): نتجاهله
                    t.announced = true;
                    t.background = true;
                } else if (now - t.first >= 2500) {
                    ev.add((fem ? "ظهرت " : "ظهر ") + name);
                    t.announced = true;
                }
            } else {
                if (moving) {
                    if (t.background) {
                        ev.add((fem ? "بدأت " + name + " تتحرك" : "بدأ " + name + " يتحرك"));
                        t.background = false;
                    } else if (t.stoppedAnnounced) {
                        ev.add((fem ? "تحركت " : "تحرك ") + name);
                    }
                    t.stoppedAnnounced = false;
                    t.wasMoving = true;
                } else if (t.wasMoving && !t.stoppedAnnounced
                        && t.stillSince != 0 && now - t.stillSince >= STOP_MS) {
                    ev.add((fem ? "توقفت " : "توقف ") + name);
                    t.stoppedAnnounced = true;
                }
            }
        }
    }
}
