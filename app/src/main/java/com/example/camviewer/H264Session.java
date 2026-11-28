package com.example.camviewer;

import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * فك ترميز H.264 بدون شاشة، وأخذ لقطة صغيرة (300x300 RGB) كل نصف ثانية تقريباً.
 */
public class H264Session {

    public interface FrameCallback {
        void onFrame(int channel, byte[] rgb);
    }

    public static final int SIZE = 300;
    private static final long TICK_MS = 450;

    private final int channel;
    private final FrameCallback cb;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private boolean waitKey = true;
    private boolean released = false;
    private long ptsUs = 0;
    private long lastTick = 0;

    public H264Session(int channel, FrameCallback cb) {
        this.channel = channel;
        this.cb = cb;
    }

    public synchronized boolean start(byte[] sps, byte[] pps) {
        try {
            int w = 640;
            int h = 360;
            int[] wh = parseSpsSize(sps);
            if (wh != null) {
                w = wh[0];
                h = wh[1];
            }
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(withStart(sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(withStart(pps)));
            codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(f, null, null, 0);
            codec.start();
            return true;
        } catch (Exception e) {
            release();
            return false;
        }
    }

    public synchronized void feed(byte[] au, boolean idr) {
        if (released || codec == null) return;
        if (waitKey) {
            if (!idr) return;
            waitKey = false;
        }
        try {
            int in = codec.dequeueInputBuffer(5000);
            if (in >= 0) {
                ByteBuffer b = codec.getInputBuffer(in);
                if (b == null || au.length > b.capacity()) {
                    codec.queueInputBuffer(in, 0, 0, ptsUs, 0);
                } else {
                    b.clear();
                    b.put(au);
                    codec.queueInputBuffer(in, 0, au.length, ptsUs, 0);
                }
                ptsUs += 40000;
            }
            drain();
        } catch (Exception ignored) {
        }
    }

    private void drain() {
        while (true) {
            int out = codec.dequeueOutputBuffer(info, 0);
            if (out >= 0) {
                long now = SystemClock.elapsedRealtime();
                if (cb != null && now - lastTick >= TICK_MS) {
                    lastTick = now;
                    Image img = null;
                    try {
                        img = codec.getOutputImage(out);
                        if (img != null) {
                            cb.onFrame(channel, toRgb(img));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (img != null) img.close();
                    }
                }
                codec.releaseOutputBuffer(out, false);
            } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
        }
    }

    public synchronized void release() {
        released = true;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }

    // ---------- تحويل الصورة ----------

    private static byte clamp(int v) {
        return (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
    }

    private static byte[] toRgb(Image img) {
        Rect crop = img.getCropRect();
        int cx = crop.left;
        int cy = crop.top;
        int cw = crop.width();
        int ch = crop.height();
        if (cw <= 0 || ch <= 0) {
            cx = 0;
            cy = 0;
            cw = img.getWidth();
            ch = img.getHeight();
        }
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yRow = p[0].getRowStride();
        int yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride();
        int uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride();
        int vPix = p[2].getPixelStride();

        byte[] out = new byte[SIZE * SIZE * 3];
        int o = 0;
        for (int oy = 0; oy < SIZE; oy++) {
            int sy = cy + oy * ch / SIZE;
            for (int ox = 0; ox < SIZE; ox++) {
                int sx = cx + ox * cw / SIZE;
                int y = yb.get(sy * yRow + sx * yPix) & 0xFF;
                int u = (ub.get((sy >> 1) * uRow + (sx >> 1) * uPix) & 0xFF) - 128;
                int v = (vb.get((sy >> 1) * vRow + (sx >> 1) * vPix) & 0xFF) - 128;
                out[o++] = clamp(y + ((359 * v) >> 8));
                out[o++] = clamp(y - ((88 * u + 183 * v) >> 8));
                out[o++] = clamp(y + ((454 * u) >> 8));
            }
        }
        return out;
    }

    // ---------- أدوات H.264 ----------

    private static byte[] withStart(byte[] nal) {
        byte[] r = new byte[nal.length + 4];
        r[3] = 1;
        System.arraycopy(nal, 0, r, 4, nal.length);
        return r;
    }

    private static class Bits {
        final byte[] d;
        int pos = 0;

        Bits(byte[] d) {
            this.d = d;
        }

        int u1() {
            if (pos >= d.length * 8) return 0;
            int v = (d[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return v;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | u1();
            return v;
        }

        int ue() {
            int z = 0;
            while (u1() == 0) {
                z++;
                if (z > 31 || pos >= d.length * 8) return 0;
            }
            if (z == 0) return 0;
            long v = (1L << z) - 1 + u(z);
            return (int) v;
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    private static void skipScaling(Bits r, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                int delta = r.se();
                next = (last + delta + 256) % 256;
            }
            last = (next == 0) ? last : next;
        }
    }

    static int[] parseSpsSize(byte[] nal) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int zeros = 0;
            for (int i = 1; i < nal.length; i++) {
                int b = nal[i] & 0xFF;
                if (zeros >= 2 && b == 3) {
                    zeros = 0;
                    continue;
                }
                bo.write(b);
                zeros = (b == 0) ? zeros + 1 : 0;
            }
            Bits r = new Bits(bo.toByteArray());
            int profile = r.u(8);
            r.u(8);
            r.u(8);
            r.ue();
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244
                    || profile == 44 || profile == 83 || profile == 86 || profile == 118
                    || profile == 128 || profile == 138 || profile == 139 || profile == 134
                    || profile == 135) {
                chroma = r.ue();
                if (chroma == 3) r.u1();
                r.ue();
                r.ue();
                r.u1();
                if (r.u1() == 1) {
                    int n = chroma != 3 ? 8 : 12;
                    for (int i = 0; i < n; i++) {
                        if (r.u1() == 1) skipScaling(r, i < 6 ? 16 : 64);
                    }
                }
            }
            r.ue();
            int poc = r.ue();
            if (poc == 0) {
                r.ue();
            } else if (poc == 1) {
                r.u1();
                r.se();
                r.se();
                int n = r.ue();
                for (int i = 0; i < n; i++) r.se();
            }
            r.ue();
            r.u1();
            int wMb = r.ue() + 1;
            int hMap = r.ue() + 1;
            int fmo = r.u1();
            if (fmo == 0) r.u1();
            r.u1();
            int cl = 0, cr = 0, ct = 0, cb = 0;
            if (r.u1() == 1) {
                cl = r.ue();
                cr = r.ue();
                ct = r.ue();
                cb = r.ue();
            }
            int cropX = (chroma == 1 || chroma == 2) ? 2 : 1;
            int cropY = ((chroma == 1) ? 2 : 1) * (2 - fmo);
            int w = wMb * 16 - (cl + cr) * cropX;
            int h = (2 - fmo) * hMap * 16 - (ct + cb) * cropY;
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
            return new int[]{w, h};
        } catch (Exception e) {
            return null;
        }
    }
}
