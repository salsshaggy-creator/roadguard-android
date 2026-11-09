package gh.roadguard.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/** Plays RoadGuard's voice natively, so it keeps talking when the screen is off.
 *  A message is a list of parts: a voice clip from the app, or text for the phone's own voice. */
public class RgVoice {
    public interface Done { void run(); }
    public static class Part { String clip, text; Part(String c, String t) { clip = c; text = t; } }

    private final Context ctx;
    private final AudioManager am;
    private MediaPlayer mp;
    private TextToSpeech tts; private boolean ttsReady = false;
    private int gen = 0;
    private AudioFocusRequest focusReq;
    private static final AudioAttributes ATTR = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    RgVoice(Context c) {
        ctx = c.getApplicationContext(); am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(ctx, s -> { if (s == TextToSpeech.SUCCESS) { tts.setLanguage(Locale.UK); tts.setAudioAttributes(ATTR); ttsReady = true; } });
    }

    private void focus(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (on) { focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTR).build(); am.requestAudioFocus(focusReq); }
                else if (focusReq != null) am.abandonAudioFocusRequest(focusReq);
            } else {
                if (on) am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); else am.abandonAudioFocus(null);
            }
        } catch (Exception e) { }
    }

    synchronized void stop() {
        gen++;
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception e) { } mp = null;
        try { if (tts != null) tts.stop(); } catch (Exception e) { }
        focus(false);
    }

    synchronized void play(List<Part> parts, float rate, Done done) {
        stop(); final int my = gen; focus(true);
        next(parts, 0, rate, my, () -> { if (my == gen) focus(false); done.run(); });
    }

    private void next(List<Part> parts, int i, float rate, int my, Done done) {
        if (my != gen || i >= parts.size()) { done.run(); return; }
        Part p = parts.get(i); Done go = () -> next(parts, i + 1, rate, my, done);
        if (p.clip != null) playClip(p.clip, rate, my, go, () -> { if (p.text != null) speak(p.text, rate, my, go); else go.run(); });
        else if (p.text != null) speak(p.text, rate, my, go);
        else go.run();
    }

    private void playClip(String clip, float rate, int my, Done ok, Done fail) {
        try {
            MediaPlayer m = new MediaPlayer(); m.setAudioAttributes(ATTR);
            String asset = "public/" + clip;
            try { AssetFileDescriptor fd = ctx.getAssets().openFd(asset); m.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength()); fd.close(); }
            catch (Exception compressed) { m.setDataSource(cached(asset)); }
            if (Build.VERSION.SDK_INT >= 23 && Math.abs(rate - 1f) > 0.02f) { try { m.setPlaybackParams(m.getPlaybackParams().setSpeed(rate)); } catch (Exception e) { } }
            m.setOnCompletionListener(x -> { x.release(); synchronized (this) { if (mp == x) mp = null; } if (my == gen) ok.run(); });
            m.setOnErrorListener((x, a, b) -> { try { x.release(); } catch (Exception e) { } synchronized (this) { if (mp == x) mp = null; } if (my == gen) fail.run(); return true; });
            m.prepare(); synchronized (this) { if (my != gen) { m.release(); return; } mp = m; } m.start();
        } catch (Exception e) { if (my == gen) fail.run(); }
    }

    private String cached(String asset) throws Exception {
        File f = new File(ctx.getCacheDir(), "rgv_" + asset.replace('/', '_'));
        if (!f.exists()) { try (InputStream in = ctx.getAssets().open(asset); OutputStream out = new FileOutputStream(f)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); } }
        return f.getAbsolutePath();
    }

    private void speak(String text, float rate, int my, Done done) {
        if (!ttsReady) { done.run(); return; }
        String id = "rg" + System.nanoTime();
        tts.setSpeechRate(rate);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String u) { }
            @Override public void onDone(String u) { if (id.equals(u) && my == gen) done.run(); }
            @Override public void onError(String u) { if (id.equals(u) && my == gen) done.run(); }
        });
        Bundle b = new Bundle(); tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, id);
    }
}
