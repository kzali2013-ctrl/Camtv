package com.example.camviewer;

import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * عميل RTSP خفيف يعمل بدون شاشة.
 * 1) يقيس حجم بيانات الصورة المتحركة لكشف الحركة فوراً.
 * 2) يجمّع إطارات H.264 (آخر مجموعة إطارات) لتحليلها بالذكاء الاصطناعي عند الحاجة.
 */
public class RtspMotionClient implements Runnable {

    public interface Listener {
        void onMotion(int channel, boolean onset);
    }

    public interface Sink {
        /** هل نجمّع الإطارات الآن؟ */
        boolean collect();

        /** هل توجد جلسة تحليل نشطة لهذه الكاميرا؟ */
        boolean isActive(int channel);

        void onAccessUnit(int channel, byte[] au, boolean idr);
    }

    private static final double[] MULT = {0, 5.0, 4.0, 3.2, 2.6, 2.1, 1.8, 1.55, 1.4, 1.25, 1.12};
    private static final int[] FLOOR = {0, 1500, 1200, 900, 700, 500, 400, 300, 200, 150, 100};

    private static final long WINDOW_MS = 300;
    private static final long WARMUP_MS = 3000;
    private static final long REPEAT_MS = 1000;
    private static final long END_MS = 1500;
    private static final long KEEPALIVE_MS = 20000;
    private static final int GOP_MAX_BYTES = 1500000;

    private final int channel;
    private final String host;
    private final int port;
    private final String path;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Sink sink;

    private volatile boolean stopped = false;
    private volatile long lastData = 0;
    private volatile Socket socket;

    private int cseq = 1;
    private double baseline = -1;
    private boolean inMotion = false;
    private long lastMotion = 0;
    private long lastAlert = 0;
    private long connectTime = 0;

    // تجميع إطارات H.264
    private final ByteArrayOutputStream auBuf = new ByteArrayOutputStream(65536);
    private final ByteArrayOutputStream fuBuf = new ByteArrayOutputStream(65536);
    private boolean fuActive = false;
    private boolean auIdr = false;
    private long curTs = -1;
    private final List<byte[]> gop = new ArrayList<>();
    private int gopBytes = 0;
    private volatile byte[] sps;
    private volatile byte[] pps;

    public RtspMotionClient(int channel, String host, int port, String path,
                            SharedPreferences prefs, Listener listener, Sink sink) {
        this.channel = channel;
        this.host = host;
        this.port = port;
        this.path = path;
        this.prefs = prefs;
        this.listener = listener;
        this.sink = sink;
    }

    public int getChannel() {
        return channel;
    }

    public byte[] getSps() {
        return sps;
    }

    public byte[] getPps() {
        return pps;
    }

    public List<byte[]> snapshotGop() {
        synchronized (gop) {
            return new ArrayList<>(gop);
        }
    }

    public boolean isConnected() {
        long t = lastData;
        return t != 0 && SystemClock.elapsedRealtime() - t < 4000;
    }

    public void stop() {
        stopped = true;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                session();
            } catch (Exception ignored) {
            }
            lastData = 0;
            Socket s = socket;
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            if (stopped) break;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ---------- جلسة RTSP ----------

    private void session() throws IOException {
        Socket s = new Socket();
        socket = s;
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(15000);
        s.setTcpNoDelay(true);

        InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
        OutputStream out = s.getOutputStream();

        cseq = 1;
        baseline = -1;
        inMotion = false;
        auBuf.reset();
        fuBuf.reset();
        fuActive = false;
        auIdr = false;
        curTs = -1;
        synchronized (gop) {
            gop.clear();
            gopBytes = 0;
        }

        String url = "rtsp://" + host + ":" + port + path;

        send(out, "DESCRIBE " + url + " RTSP/1.0\r\nCSeq: " + (cseq++)
                + "\r\nAccept: application/sdp\r\nUser-Agent: CamViewer\r\n\r\n");
        Response d = readResponse(in);
        if (d.code != 200) throw new IOException("describe " + d.code);

        String base = d.headers.get("content-base");
        if (base == null || base.isEmpty()) base = url;

        boolean inVideo = false;
        boolean hevc = false;
        String control = null;
        for (String line : d.body.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("m=")) {
                inVideo = line.startsWith("m=video");
            } else if (inVideo && line.startsWith("a=control:")) {
                control = line.substring(10).trim();
            } else if (inVideo && line.startsWith("a=rtpmap:")) {
                String u = line.toUpperCase();
                if (u.contains("H265") || u.contains("HEVC")) hevc = true;
            }
        }
        if (control == null) control = "";

        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

        List<String> cands = new ArrayList<>();
        if (control.toLowerCase().startsWith("rtsp://")) {
            cands.add(control);
        } else if (control.isEmpty() || control.equals("*")) {
            cands.add(b);
        } else {
            cands.add(b + "/" + control);
            int idx = base.lastIndexOf('/');
            if (idx > 8) cands.add(base.substring(0, idx + 1) + control);
        }

        Response sr = null;
        for (String u : cands) {
            send(out, "SETUP " + u + " RTSP/1.0\r\nCSeq: " + (cseq++)
                    + "\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1"
                    + "\r\nUser-Agent: CamViewer\r\n\r\n");
            Response t = readResponse(in);
            if (t.code == 200) {
                sr = t;
                break;
            }
        }
        if (sr == null) throw new IOException("setup failed");

        String sh = sr.headers.get("session");
        String session = sh == null ? null : sh.split(";")[0].trim();

        int rtpChannel = 0;
        String tr = sr.headers.get("transport");
        if (tr != null) {
            int i = tr.indexOf("interleaved=");
            if (i >= 0) {
                int j = i + 12;
                int k = j;
                while (k < tr.length() && Character.isDigit(tr.charAt(k))) k++;
                if (k > j) rtpChannel = Integer.parseInt(tr.substring(j, k));
            }
        }

        send(out, "PLAY " + b + " RTSP/1.0\r\nCSeq: " + (cseq++)
                + (session != null ? "\r\nSession: " + session : "")
                + "\r\nRange: npt=0.000-\r\nUser-Agent: CamViewer\r\n\r\n");
        Response pr = readResponse(in);
        if (pr.code != 200) throw new IOException("play " + pr.code);

        connectTime = SystemClock.elapsedRealtime();
        long windowStart = connectTime;
        long lastKeep = connectTime;
        long windowBytes = 0;
        byte[] buf = new byte[65536];

        while (!stopped) {
            int c = in.read();
            if (c < 0) throw new EOFException();

            if (c == '$') {
                int ch = in.read();
                int l1 = in.read();
                int l2 = in.read();
                if (ch < 0 || l1 < 0 || l2 < 0) throw new EOFException();
                int len = (l1 << 8) | l2;
                readFully(in, buf, len);
                if (ch == rtpChannel) {
                    lastData = SystemClock.elapsedRealtime();
                    windowBytes += processRtp(buf, len, hevc);
                }
            } else if (c == 'R') {
                String head = "R" + readHeaderText(in);
                skipBody(in, head);
            }

            long now = SystemClock.elapsedRealtime();

            if (now - lastKeep >= KEEPALIVE_MS) {
                lastKeep = now;
                send(out, "OPTIONS " + b + " RTSP/1.0\r\nCSeq: " + (cseq++)
                        + (session != null ? "\r\nSession: " + session : "")
                        + "\r\n\r\n");
            }

            if (now - windowStart >= WINDOW_MS) {
                analyze(windowBytes, now);
                windowBytes = 0;
                windowStart = now;
            }
        }
    }

    // ---------- تحليل الحركة ----------

    private void analyze(long bytes, long now) {
        if (baseline < 0) {
            baseline = bytes;
            return;
        }
        if (now - connectTime < WARMUP_MS) {
            baseline = baseline * 0.8 + bytes * 0.2;
            return;
        }

        int level = prefs.getInt("sens", 5);
        if (level < 1) level = 1;
        if (level > 10) level = 10;

        double base = Math.max(baseline, 60);
        boolean motion = bytes > base * MULT[level] + FLOOR[level];

        if (motion) {
            baseline = baseline * 0.995 + bytes * 0.005;
            lastMotion = now;
            if (!inMotion) {
                inMotion = true;
                lastAlert = now;
                listener.onMotion(channel, true);
            } else if (now - lastAlert >= REPEAT_MS) {
                lastAlert = now;
                listener.onMotion(channel, false);
            }
        } else {
            baseline = baseline * 0.9 + bytes * 0.1;
            if (inMotion && now - lastMotion > END_MS) inMotion = false;
        }
    }

    // ---------- معالجة حزم RTP ----------

    /** يرجع حجم بيانات الصورة المتحركة (للكشف)، ويجمّع الإطارات لو مطلوب. */
    private int processRtp(byte[] buf, int len, boolean hevc) {
        if (len < 14) return 0;
        int cc = buf[0] & 0x0F;
        boolean ext = (buf[0] & 0x10) != 0;
        boolean marker = (buf[1] & 0x80) != 0;
        long ts = ((buf[4] & 0xFFL) << 24) | ((buf[5] & 0xFFL) << 16)
                | ((buf[6] & 0xFFL) << 8) | (buf[7] & 0xFFL);
        int off = 12 + cc * 4;
        if (ext) {
            if (len < off + 4) return 0;
            int extLen = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
            off += 4 + extLen * 4;
        }
        if (off + 2 >= len) return 0;
        int payloadLen = len - off;

        if (hevc) {
            int type = (buf[off] >> 1) & 0x3F;
            if (type == 49) type = buf[off + 2] & 0x3F;
            return (type <= 9) ? payloadLen : 0;
        }

        int type = buf[off] & 0x1F;
        int realType = type;
        if (type == 28 || type == 29) realType = buf[off + 1] & 0x1F;
        int counted = (realType >= 1 && realType <= 4) ? payloadLen : 0;

        if (sink != null && sink.collect()) {
            if (curTs != -1 && ts != curTs) flushAu();
            curTs = ts;
            depacketize(buf, off, len, type);
            if (marker) flushAu();
        }
        return counted;
    }

    private void depacketize(byte[] buf, int off, int len, int type) {
        if (type >= 1 && type <= 23) {
            addNal(buf, off, len - off);
        } else if (type == 24) {
            int p = off + 1;
            while (p + 2 < len) {
                int size = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF);
                p += 2;
                if (size <= 0 || p + size > len) break;
                addNal(buf, p, size);
                p += size;
            }
        } else if (type == 28) {
            if (off + 1 >= len) return;
            int fu = buf[off + 1] & 0xFF;
            boolean start = (fu & 0x80) != 0;
            boolean end = (fu & 0x40) != 0;
            if (start) {
                fuBuf.reset();
                fuBuf.write((buf[off] & 0xE0) | (fu & 0x1F));
                fuActive = true;
            }
            if (fuActive) {
                fuBuf.write(buf, off + 2, len - (off + 2));
                if (end) {
                    byte[] nal = fuBuf.toByteArray();
                    fuActive = false;
                    addNal(nal, 0, nal.length);
                }
            }
        }
    }

    private void addNal(byte[] src, int off, int len) {
        if (len <= 0) return;
        int t = src[off] & 0x1F;
        if (t == 7) {
            byte[] c = new byte[len];
            System.arraycopy(src, off, c, 0, len);
            sps = c;
        } else if (t == 8) {
            byte[] c = new byte[len];
            System.arraycopy(src, off, c, 0, len);
            pps = c;
        } else if (t == 5) {
            auIdr = true;
        } else if (t == 9) {
            return;
        }
        auBuf.write(0);
        auBuf.write(0);
        auBuf.write(0);
        auBuf.write(1);
        auBuf.write(src, off, len);
    }

    private void flushAu() {
        if (auBuf.size() == 0) {
            auIdr = false;
            return;
        }
        byte[] au = auBuf.toByteArray();
        boolean idr = auIdr;
        auBuf.reset();
        auIdr = false;

        synchronized (gop) {
            if (idr) {
                gop.clear();
                gopBytes = 0;
            }
            if (idr || !gop.isEmpty()) {
                gop.add(au);
                gopBytes += au.length;
            }
            if (gopBytes > GOP_MAX_BYTES) {
                gop.clear();
                gopBytes = 0;
            }
        }

        if (sink != null && sink.isActive(channel)) {
            sink.onAccessUnit(channel, au, idr);
        }
    }

    // ---------- أدوات RTSP ----------

    private static class Response {
        int code;
        Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    private void send(OutputStream out, String text) throws IOException {
        out.write(text.getBytes("ISO-8859-1"));
        out.flush();
    }

    private String readHeaderText(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            bo.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = (b == '\r') ? 1 : 0;
            }
            if (state == 4) break;
            if (bo.size() > 16384) throw new IOException("header too long");
        }
        return bo.toString("ISO-8859-1");
    }

    private int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("content-length")) {
                try {
                    return Integer.parseInt(line.substring(i + 1).trim());
                } catch (Exception e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private void skipBody(InputStream in, String head) throws IOException {
        int cl = contentLength(head);
        for (int i = 0; i < cl; i++) {
            if (in.read() < 0) throw new EOFException();
        }
    }

    private Response readResponse(InputStream in) throws IOException {
        String head = readHeaderText(in);
        String[] lines = head.split("\r\n");
        Response r = new Response();

        String[] p = lines[0].split(" ");
        if (p.length >= 2) {
            try {
                r.code = Integer.parseInt(p[1].trim());
            } catch (Exception e) {
                r.code = 0;
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int k = lines[i].indexOf(':');
            if (k > 0) {
                r.headers.put(lines[i].substring(0, k).trim().toLowerCase(),
                        lines[i].substring(k + 1).trim());
            }
        }

        int cl = contentLength(head);
        if (cl > 0) {
            byte[] body = new byte[cl];
            readFully(in, body, cl);
            r.body = new String(body, "ISO-8859-1");
        }
        return r;
    }

    private void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(buf, n, len - n);
            if (r < 0) throw new EOFException();
            n += r;
        }
    }
}
