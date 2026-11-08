package gh.roadguard.app;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

/** Receives "you are now in a vehicle" from Android, and "your car's Bluetooth connected". */
public class DriveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        SharedPreferences p = AutoNotify.prefs(c);
        String action = intent.getAction();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            String car = p.getString("carAddress", null);
            if (car == null) return;
            try {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && car.equalsIgnoreCase(d.getAddress())) trigger(c, "bt");
            } catch (SecurityException e) { /* Bluetooth permission not granted */ }
            return;
        }
        if (!p.getBoolean("detect", false)) return;
        if (ActivityTransitionResult.hasResult(intent)) {
            ActivityTransitionResult r = ActivityTransitionResult.extractResult(intent);
            if (r == null) return;
            for (ActivityTransitionEvent e : r.getTransitionEvents()) {
                if (e.getActivityType() == DetectedActivity.IN_VEHICLE && e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    trigger(c, "detect");
                    return;
                }
            }
        }
    }

    static void trigger(Context c, String reason) {
        // If RoadGuard is running, let it decide (it may start by itself); otherwise ask with a notification.
        if (AutoStartPlugin.instance != null && AutoStartPlugin.instance.appVisible()) AutoStartPlugin.instance.fire(reason);
        else AutoNotify.show(c, reason);
    }
}
