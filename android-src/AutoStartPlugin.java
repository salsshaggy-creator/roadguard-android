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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@CapacitorPlugin(name = "AutoStart", permissions = {
    @Permission(alias = "activity", strings = { "android.permission.ACTIVITY_RECOGNITION" }),
    @Permission(alias = "bluetooth", strings = { "android.permission.BLUETOOTH_CONNECT" })
})
public class AutoStartPlugin extends Plugin {
    static AutoStartPlugin instance;
    private String pendingReason = null;
    private boolean visible = true;

    @Override
    public void load() {
        instance = this;
        Intent i = getActivity() != null ? getActivity().getIntent() : null;
        if (i != null && i.hasExtra("rg_autostart")) { pendingReason = i.getStringExtra("rg_autostart"); i.removeExtra("rg_autostart"); }
    }

    @Override
    protected void handleOnNewIntent(Intent i) {
        super.handleOnNewIntent(i);
        if (i != null && i.hasExtra("rg_autostart")) { String r = i.getStringExtra("rg_autostart"); i.removeExtra("rg_autostart"); fire(r); }
    }

    @Override protected void handleOnResume() { super.handleOnResume(); visible = true; }
    @Override protected void handleOnPause() { super.handleOnPause(); visible = false; }
    boolean appVisible() { return visible; }

    void fire(String reason) {
        JSObject o = new JSObject(); o.put("reason", reason);
        notifyListeners("autostart", o, true);
    }

    private SharedPreferences prefs() { return AutoNotify.prefs(getContext()); }

    /** Was the app opened from the "driving detected" notification? */
    @PluginMethod
    public void consumeLaunch(PluginCall call) {
        JSObject o = new JSObject(); o.put("reason", pendingReason); pendingReason = null; call.resolve(o);
    }

    /** JS tells us when a journey is running, so we don't nag while recording. */
    @PluginMethod
    public void setJourneyActive(PluginCall call) {
        prefs().edit().putLong("journeyActiveAt", call.getBoolean("active", false) ? System.currentTimeMillis() : 0).apply();
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
}
