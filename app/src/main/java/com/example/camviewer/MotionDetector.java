package com.example.camviewer;

/**
 * كاشف الحركة: يقارن كل صورة بالتي قبلها.
 * مستوى الحساسية من 1 إلى 10، وكل زيادة = حساسية أعلى.
 */
public class MotionDetector {

    public static final int W = 64;
    public static final int H = 36;

    // فرق السطوع المطلوب لاعتبار النقطة متغيرة (يقل مع زيادة الحساسية)
    private static final int[] THRESH = {0, 50, 44, 38, 32, 27, 22, 18, 14, 10, 7};
    // نسبة المساحة المتغيرة % المطلوبة لاعتبارها حركة (تقل مع زيادة الحساسية)
    private static final double[] AREA = {0, 8.0, 5.5, 4.0, 3.0, 2.0, 1.4, 1.0, 0.6, 0.3, 0.15};

    // أثناء استمرار الحركة، يتكرر التنبيه كل هذه المدة (مللي ثانية)
    private static final long REPEAT_MS = 1000;
    // الحركة تعتبر انتهت إذا لم تُرصد خلال هذه المدة
    private static final long END_MS = 1500;

    private int[] prev;
    private boolean inMotion = false;
    private long lastMotion = 0;
    private long lastAlert = 0;

    public static int[] toGray(int[] argb) {
        int[] g = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF;
            int gr = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            g[i] = (r * 30 + gr * 59 + b * 11) / 100;
        }
        return g;
    }

    /** يحفظ الصورة كمرجع بدون إطلاق أي تنبيه (فترة بدء البث). */
    public void prime(int[] gray) {
        prev = gray;
    }

    /** يرجع true إذا يجب إرسال تنبيه الآن. */
    public boolean update(int[] gray, int level, long now) {
        if (level < 1) level = 1;
        if (level > 10) level = 10;

        boolean motion = false;
        if (prev != null && prev.length == gray.length) {
            int changed = 0;
            int t = THRESH[level];
            for (int i = 0; i < gray.length; i++) {
                if (Math.abs(gray[i] - prev[i]) > t) changed++;
            }
            motion = (changed * 100.0 / gray.length) >= AREA[level];
        }
        prev = gray;

        if (motion) {
            lastMotion = now;
            if (!inMotion) {
                // بداية الحركة: تنبيه فوري
                inMotion = true;
                lastAlert = now;
                return true;
            }
            if (now - lastAlert >= REPEAT_MS) {
                lastAlert = now;
                return true;
            }
        } else if (inMotion && now - lastMotion > END_MS) {
            inMotion = false;
        }
        return false;
    }
}
