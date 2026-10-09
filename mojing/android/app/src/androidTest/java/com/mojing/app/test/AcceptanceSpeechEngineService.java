package com.mojing.app.test;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone engine process uses only platform/Java classes, with no APK Kotlin dependency. */
public final class AcceptanceSpeechEngineService extends TextToSpeechService {
    private final AtomicLong serial = new AtomicLong();
    private volatile Thread activeThread;

    @Override protected String[] onGetLanguage() { return new String[]{"zho", "CHN", ""}; }

    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return "zh".equalsIgnoreCase(language) || "zho".equalsIgnoreCase(language)
                ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override protected int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }

    @Override protected void onStop() {
        serial.incrementAndGet();
        Thread current = activeThread;
        if (current != null) current.interrupt();
    }

    @Override public void onDestroy() {
        onStop();
        super.onDestroy();
    }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        Thread.interrupted();
        long currentSerial = serial.incrementAndGet();
        activeThread = Thread.currentThread();
        byte[] silence = new byte[4096];
        int remainingSamples = 8000 * 8;
        Log.i("AcceptanceSpeechEngine", "local synthesis length=" + request.getCharSequenceText().length());
        try {
            if (callback.start(8000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
            while (remainingSamples > 0 && serial.get() == currentSerial && !Thread.currentThread().isInterrupted()) {
                int count = Math.min(remainingSamples, silence.length / 2);
                if (callback.audioAvailable(silence, 0, count * 2) != TextToSpeech.SUCCESS) return;
                remainingSamples -= count;
            }
            if (serial.get() == currentSerial && !Thread.currentThread().isInterrupted()) callback.done();
            else callback.error();
        } catch (RuntimeException failure) {
            callback.error();
        } finally {
            if (activeThread == Thread.currentThread()) activeThread = null;
            Thread.interrupted();
        }
    }
}
