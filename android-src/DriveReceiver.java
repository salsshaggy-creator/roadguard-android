package gh.roadguard.app;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

/** Receives "you are now in a vehicle" from Android, "your car's Bluetooth connected",
 *  and phone restarts / app updates (which switch driving detection off, so we switch it back on). */
public class DriveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences p = AutoNotify.prefs(c);
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (p.getBoolean("detect", false)) AutoNotify.registerDetect(c);
            return;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            String car = p.getString("carAddress", null);
            try {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                boolean isCar = car != null && car.equalsIgnoreCase(d.getAddress());
                String name = null; try { name = d.getName(); } catch (SecurityException e) {}
                AutoNotify.signal(c, isCar ? "Your car's Bluetooth connected" : "Another Bluetooth device connected" + (name != null ? " (" + name + ")" : ""));
                if (isCar) trigger(c, "bt");
            } catch (SecurityException e) { AutoNotify.signal(c, "Bluetooth connected, but RoadGuard isn't allowed to check which device"); }
            return;
        }
        if (ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult r = ActivityTransitionResult.extractResult(intent);
            if (r == null) return;
            for (ActivityTransitionEvent e : r.getTransitionEvents()) {
                if (e.getActivityType() == DetectedActivity.IN_VEHICLE && e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    AutoNotify.signal(c, "Android detected you started driving");
                    if (p.getBoolean("detect", false)) trigger(c, "detect");
                    return;
                }
            }
        }
    }

    static void trigger(Context c, String reason) {
        // If RoadGuard is on screen, let it start by itself.
        if (AutoStartPlugin.instance != null && AutoStartPlugin.instance.appVisible()) { AutoStartPlugin.instance.fire(reason); return; }
        SharedPreferences p = AutoNotify.prefs(c);
        long now = System.currentTimeMillis();
        // a journey is already running (forget it after 12 hours in case the app was closed without ending it)
        boolean running = p.getBoolean("journeyActive", false) && now - p.getLong("journeyActiveAt", 0) < 12 * 3600 * 1000L;
        if (running) return;
        // never jump on screen during a phone call: ask with a notification instead
        boolean inCall = false;
        try { android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
              int m = am.getMode(); inCall = m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION || m == android.media.AudioManager.MODE_RINGTONE; } catch (Exception e) { }
        // Fully automatic: open RoadGuard by itself (Android allows this once "Display over other apps" is on).
        if (!inCall && p.getBoolean("fullAuto", false) && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c))
                && now - p.getLong("lastAutoOpen", 0) > 15 * 60 * 1000L) {
            try {
                Intent i = new Intent(c, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("rg_autostart", reason); i.putExtra("rg_bg", true);
                c.startActivity(i);
                p.edit().putLong("lastAutoOpen", now).apply();
                AutoNotify.signal(c, "RoadGuard started by itself (" + ("bt".equals(reason) ? "car Bluetooth" : "driving detected") + ")");
                return;
            } catch (Exception e) { AutoNotify.signal(c, "RoadGuard could not open by itself, so it asked instead"); }
        }
        AutoNotify.show(c, reason, false);
    }
}
