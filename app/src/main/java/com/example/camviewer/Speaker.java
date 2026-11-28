package com.example.camviewer;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** نطق الأحداث بصوت عربي. */
public class Speaker implements TextToSpeech.OnInitListener {

    private final TextToSpeech tts;
    private volatile boolean ready = false;
    private final AtomicInteger queued = new AtomicInteger();
    private int counter = 0;

    public Speaker(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(new Locale("ar"));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return;
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
        } catch (Exception ignored) {
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                queued.decrementAndGet();
            }

            @Override
            public void onError(String utteranceId) {
                queued.decrementAndGet();
            }
        });
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    public void speak(String text) {
        if (!ready) return;
        if (queued.get() >= 4) return; // لا نكدّس كلام قديم
        queued.incrementAndGet();
        int r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "ev" + (counter++));
        if (r != TextToSpeech.SUCCESS) queued.decrementAndGet();
    }

    public void shutdown() {
        ready = false;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
    }
}
