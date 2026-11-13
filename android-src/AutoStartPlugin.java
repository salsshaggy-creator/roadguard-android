package gh.roadguard.app;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import org.json.JSONObject;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@CapacitorPlugin(name = "AutoStart", permissions = {
    @Permission(alias = "activity", strings = { "android.permission.ACTIVITY_RECOGNITION" }),
    @Permission(alias = "bluetooth", strings = { "android.permission.BLUETOOTH_CONNECT" }),
    @Permission(alias = "mic", strings = { "android.permission.RECORD_AUDIO" })
})
public class AutoStartPlugin extends Plugin {
    static AutoStartPlugin instance;
    private RgVoice voice;
    private String pendingReason = null;
    private boolean visible = true;

    @Override
    public void load() {
        instance = this;
        voice = new RgVoice(getContext());
        Intent i = getActivity() != null ? getActivity().getIntent() : null;
        if (i != null && i.hasExtra("rg_autostart")) { pendingReason = i.getStringExtra("rg_autostart"); pendingBg = i.getBooleanExtra("rg_bg", false); i.removeExtra("rg_autostart"); if (pendingBg) showOverLock(true); }
        askFromIntent(i);
    }

    /** While RoadGuard starts itself, show it over the lock screen and wake the screen: Android only gives GPS to an app the person can see. */
    void showOverLock(boolean on) {
        final android.app.Activity a = getActivity(); if (a == null) return;
        a.runOnUiThread(() -> { try {
            if (Build.VERSION.SDK_INT >= 27) { a.setShowWhenLocked(on); a.setTurnScreenOn(on); }
            else { int f = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
                   if (on) a.getWindow().addFlags(f); else a.getWindow().clearFlags(f); }
        } catch (Exception e) { } });
    }
    private boolean pendingBg = false;

    @Override
    protected void handleOnNewIntent(Intent i) {
        super.handleOnNewIntent(i);
        if (i != null && i.hasExtra("rg_autostart")) { String r = i.getStringExtra("rg_autostart"); boolean bg = i.getBooleanExtra("rg_bg", false); i.removeExtra("rg_autostart"); if (bg) showOverLock(true); fire(r, bg); }
        askFromIntent(i);
    }

    @Override protected void handleOnResume() { super.handleOnResume(); visible = true; }
    static long lastPausedAt = 0;
    @Override protected void handleOnPause() { super.handleOnPause(); visible = false; lastPausedAt = System.currentTimeMillis(); }
    private void askFromIntent(Intent i) {
        if (i == null || !i.getBooleanExtra("rg_ask", false)) return;
        boolean wasVisible = i.getBooleanExtra("rg_wasvisible", false); i.removeExtra("rg_ask");
        if (!wasVisible) showOverLock(true);
        JSObject o = new JSObject(); o.put("action", "ask"); o.put("hidden", !wasVisible); o.put("key", "voice");
        notifyListeners("carButton", o, true);
    }
    boolean appVisible() { return visible; }

    void fire(String reason) { fire(reason, false); }
    void fire(String reason, boolean bg) {
        JSObject o = new JSObject(); o.put("reason", reason); o.put("bg", bg);
        notifyListeners("autostart", o, true);
    }

    private SharedPreferences prefs() { return AutoNotify.prefs(getContext()); }

    /** Was the app opened from the "driving detected" notification? */
    @PluginMethod
    public void consumeLaunch(PluginCall call) {
        JSObject o = new JSObject(); o.put("reason", pendingReason); o.put("bg", pendingBg); pendingReason = null; pendingBg = false; call.resolve(o);
    }

    /** JS tells us when a journey is running, so we don't nag while recording. */
    @PluginMethod
    public void setJourneyActive(PluginCall call) {
        boolean on = call.getBoolean("active", false);
        prefs().edit().putLong("journeyActiveAt", on ? System.currentTimeMillis() : 0).putBoolean("journeyActive", on).apply();
        if (!on) showOverLock(false);
        call.resolve();
    }

    @PluginMethod
    public void getSettings(PluginCall call) {
        SharedPreferences p = prefs(); JSObject o = new JSObject();
        o.put("detect", p.getBoolean("detect", false)); o.put("carAddress", p.getString("carAddress", null)); o.put("carName", p.getString("carName", null));
        o.put("lastSignal", p.getString("lastSignal", null)); o.put("lastSignalAt", p.getLong("lastSignalAt", 0)); o.put("lastNote", p.getLong("lastNote", 0));
        o.put("notifications", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        o.put("activityPerm", Build.VERSION.SDK_INT < 29 || getPermissionState("activity") == PermissionState.GRANTED);
        o.put("btPerm", Build.VERSION.SDK_INT < 31 || getPermissionState("bluetooth") == PermissionState.GRANTED);
        boolean batt = true;
        try { PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE); if (Build.VERSION.SDK_INT >= 23 && pm != null) batt = pm.isIgnoringBatteryOptimizations(getContext().getPackageName()); } catch (Exception e) {}
        o.put("batteryFree", batt);
        o.put("fullAuto", p.getBoolean("fullAuto", false));
        o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(o);
    }

    @PluginMethod
    public void setDriveDetect(PluginCall call) {
        boolean on = call.getBoolean("enabled", false);
        if (on && Build.VERSION.SDK_INT >= 29 && getPermissionState("activity") != PermissionState.GRANTED) {
            requestPermissionForAlias("activity", call, "activityPermsDone"); return;
        }
        applyDetect(call, on);
    }

    @PermissionCallback
    private void activityPermsDone(PluginCall call) {
        if (getPermissionState("activity") != PermissionState.GRANTED) { call.reject("Permission to detect driving was not given."); return; }
        applyDetect(call, true);
    }

    private PendingIntent transitionIntent() { return AutoNotify.detectIntent(getContext()); }

    /** Speak a message natively (keeps working when the screen is off). items: [{clip:"voice/x.mp3", text:"fallback"} | {text:"..."}] */
    @PluginMethod
    public void speak(PluginCall call) {
        JSArray arr = call.getArray("items"); float rate = call.getFloat("rate", 1f);
        List<RgVoice.Part> parts = new ArrayList<>();
        try { for (int k = 0; k < arr.length(); k++) { JSONObject o = arr.getJSONObject(k);
            parts.add(new RgVoice.Part(o.has("clip") ? o.getString("clip") : null, o.has("text") ? o.getString("text") : null)); } }
        catch (Exception e) { call.reject("bad message"); return; }
        voice.play(parts, rate, call::resolve);
    }

    @PluginMethod
    public void stopSpeech(PluginCall call) { voice.stop(); call.resolve(); }

    /** Start with no tap at all: RoadGuard opens itself when you start driving (needs "Display over other apps"). */
    @PluginMethod
    public void setFullAuto(PluginCall call) {
        boolean on = call.getBoolean("enabled", false);
        prefs().edit().putBoolean("fullAuto", on).apply();
        JSObject o = new JSObject(); o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext())); call.resolve(o);
    }

    @PluginMethod
    public void askOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); call.resolve();
        } catch (Exception e) { call.reject("Could not open the setting"); }
    }

    /** After starting by itself, step aside so the app you were using (maps, music) comes back. Recording carries on. */
    @PluginMethod
    public void moveToBack(PluginCall call) {
        showOverLock(false);
        try { if (getActivity() != null) getActivity().runOnUiThread(() -> getActivity().moveTaskToBack(true)); } catch (Exception e) { }
        call.resolve();
    }

    /** Show the driving alert in 10 seconds, so the user can close RoadGuard and see it arrive. */
    @PluginMethod
    public void testAlert(PluginCall call) {
        final Context c = getContext().getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> AutoNotify.show(c, "test", true), 10000);
        call.resolve();
    }

    /** Open RoadGuard's page in the phone's settings (battery, notifications, permissions). */
    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); call.resolve();
        } catch (Exception e) { call.reject("Could not open settings"); }
    }

    private void applyDetect(PluginCall call, boolean on) {
        prefs().edit().putBoolean("detect", on).apply();
        try {
            if (on) {
                List<ActivityTransition> t = new ArrayList<>();
                t.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
                ActivityRecognition.getClient(getContext()).requestActivityTransitionUpdates(new ActivityTransitionRequest(t), transitionIntent())
                    .addOnSuccessListener(v -> call.resolve())
                    .addOnFailureListener(e -> call.reject("Could not turn on driving detection: " + e.getMessage()));
            } else {
                ActivityRecognition.getClient(getContext()).removeActivityTransitionUpdates(transitionIntent());
                call.resolve();
            }
        } catch (SecurityException e) { call.reject("Permission to detect driving was not given."); }
    }

    @PluginMethod
    public void listBluetooth(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 31 && getPermissionState("bluetooth") != PermissionState.GRANTED) {
            requestPermissionForAlias("bluetooth", call, "btPermsDone"); return;
        }
        doList(call);
    }

    @PermissionCallback
    private void btPermsDone(PluginCall call) {
        if (getPermissionState("bluetooth") != PermissionState.GRANTED) { call.reject("Permission to see Bluetooth devices was not given."); return; }
        doList(call);
    }

    private void doList(PluginCall call) {
        JSArray arr = new JSArray();
        try {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a != null) {
                Set<BluetoothDevice> ds = a.getBondedDevices();
                if (ds != null) for (BluetoothDevice d : ds) { JSObject o = new JSObject(); o.put("name", d.getName()); o.put("address", d.getAddress()); arr.put(o); }
            }
        } catch (SecurityException e) { call.reject("Permission to see Bluetooth devices was not given."); return; }
        JSObject r = new JSObject(); r.put("devices", arr); call.resolve(r);
    }

    @PluginMethod
    public void setCar(PluginCall call) {
        String addr = call.getString("address", null), name = call.getString("name", null);
        SharedPreferences.Editor e = prefs().edit();
        if (addr == null) { e.remove("carAddress"); e.remove("carName"); } else { e.putString("carAddress", addr); e.putString("carName", name); }
        e.apply(); call.resolve();
    }

    /* ---------- Ask RoadGuard: listen once and return what the driver said ---------- */
    private SpeechRecognizer sr; private final Handler main = new Handler(Looper.getMainLooper());

    @PluginMethod
    public void listenAvailable(PluginCall call) {
        JSObject o = new JSObject(); o.put("available", SpeechRecognizer.isRecognitionAvailable(getContext()));
        o.put("mic", getPermissionState("mic") == PermissionState.GRANTED); call.resolve(o);
    }

    @PluginMethod
    public void listen(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) { requestPermissionForAlias("mic", call, "micPermsDone"); return; }
        startListening(call);
    }

    @PermissionCallback
    private void micPermsDone(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) { call.reject("Permission to use the microphone was not given."); return; }
        startListening(call);
    }

    @PluginMethod
    public void stopListening(PluginCall call) { main.post(() -> { if (sr != null) { sr.cancel(); sr.destroy(); sr = null; } }); call.resolve(); }

    private void startListening(PluginCall call) {
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) { call.reject("Speech recognition is not available on this phone."); return; }
        voice.stop();
        String lang = call.getString("lang", "en-GH");
        main.post(() -> {
            if (sr != null) { sr.cancel(); sr.destroy(); }
            sr = SpeechRecognizer.createSpeechRecognizer(getContext());
            sr.setRecognitionListener(new RecognitionListener() {
                boolean done = false;
                private void finish(JSObject o, String err) { if (done) return; done = true;
                    main.post(() -> { if (sr != null) { sr.destroy(); sr = null; } });
                    if (err != null) call.reject(err); else call.resolve(o); }
                public void onReadyForSpeech(Bundle b) { notifyListeners("listenState", new JSObject().put("state", "ready")); }
                public void onBeginningOfSpeech() { notifyListeners("listenState", new JSObject().put("state", "speaking")); }
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { notifyListeners("listenState", new JSObject().put("state", "thinking")); }
                public void onPartialResults(Bundle b) {}
                public void onEvent(int t, Bundle b) {}
                public void onError(int e) {
                    String m = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "nomatch"
                        : e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "network"
                        : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "permission" : "error" + e;
                    finish(null, m); }
                public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    JSArray a = new JSArray(); if (r != null) for (String x : r) a.put(x);
                    JSObject o = new JSObject(); o.put("matches", a); finish(o, null); }
            });
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            it.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
            try { sr.startListening(it); } catch (Exception ex) { call.reject("Could not start listening."); }
        });
    }

    /* ---------- Ask with the car's play button: press twice quickly to ask; one press goes to the music as usual ---------- */
    private MediaSession ms; private long lastPress = 0; private Runnable single;

    @PluginMethod
    public void carButton(PluginCall call) { boolean on = call.getBoolean("on", false);
        if (call.hasOption("setting")) prefs().edit().putBoolean("carAsk", call.getBoolean("setting", false)).apply();
        main.post(() -> { if (on) btnOn(); else btnOff(); }); call.resolve(); }

    private void btnOn() {
        if (ms != null) { claim(); return; }
        ms = new MediaSession(getContext(), "RoadGuard");
        ms.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent i) {
                KeyEvent e = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT); if (e == null) return false;
                int c = e.getKeyCode();
                if (c != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && c != KeyEvent.KEYCODE_HEADSETHOOK && c != KeyEvent.KEYCODE_MEDIA_PLAY && c != KeyEvent.KEYCODE_MEDIA_PAUSE
                    && c != KeyEvent.KEYCODE_MEDIA_NEXT && c != KeyEvent.KEYCODE_MEDIA_PREVIOUS) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) press(c);
                return true;
            }
            @Override public void onPlay() { press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
            @Override public void onPause() { press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
            @Override public void onSkipToNext() { press(KeyEvent.KEYCODE_MEDIA_NEXT); }
            @Override public void onSkipToPrevious() { press(KeyEvent.KEYCODE_MEDIA_PREVIOUS); }
        });
        ms.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        ms.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "RoadGuard is on duty")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Press the voice button, or play / next twice, to ask").build());
        claim();
    }

    private void btnOff() { main.removeCallbacksAndMessages(null); single = null; if (ms != null) { try { ms.setActive(false); ms.release(); } catch (Exception e) { } ms = null; } }

    /** Make RoadGuard the app that gets the play button: an active session that is "playing", plus half a second of silence. */
    private void claim() {
        if (ms == null) return;
        ms.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
        ms.setActive(true);
        if (Build.VERSION.SDK_INT >= 23) new Thread(() -> { try {
            int sr = 8000, n = sr / 2;
            AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            t.write(new short[n], 0, n); t.play(); Thread.sleep(700); t.stop(); t.release();
        } catch (Exception e) { } }).start();
    }

    private int lastKey = 0;
    private void press(int code) {
        main.post(() -> {
            long now = SystemClock.uptimeMillis();
            String name = code == KeyEvent.KEYCODE_MEDIA_NEXT ? "next" : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? "previous" : "play";
            notifyListeners("carButton", new JSObject().put("action", "key").put("key", name));
            if (single != null && now - lastPress < 550 && code == lastKey) { main.removeCallbacks(single); single = null; lastPress = 0; askNow(); return; }
            if (single != null) { main.removeCallbacks(single); single.run(); }
            lastPress = now; lastKey = code;
            single = () -> { single = null; forward(code); };
            main.postDelayed(single, 550);
        });
    }

    /** One press: hand it to the music app, then take the button back. */
    private void forward(int code) {
        if (ms == null) return;
        try {
            ms.setActive(false);
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            int k = code == KeyEvent.KEYCODE_HEADSETHOOK ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : code;
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, k, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, k, 0));
        } catch (Exception e) { }
        main.postDelayed(() -> { if (ms != null) claim(); }, 1500);
    }

    /** Two presses: bring RoadGuard to the front (Android only lets a visible app use the microphone) and ask. */
    private void askNow() {
        boolean hidden = !visible;
        if (hidden) { showOverLock(true); try {
            Intent i = new Intent(getContext(), MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            getContext().startActivity(i);
        } catch (Exception e) { } }
        JSObject o = new JSObject(); o.put("action", "ask"); o.put("hidden", hidden); o.put("key", "media");
        notifyListeners("carButton", o, true);
    }
}
