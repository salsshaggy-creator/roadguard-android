package gh.roadguard.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.DetectedActivity;
import java.util.ArrayList;
import java.util.List;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Shows the one-tap "Looks like you're driving" notification. Tapping it opens RoadGuard and starts a journey. */
public class AutoNotify {
    static final String CHANNEL = "rg-drive";   // new channel: louder, with a long vibration
    static final String PREFS = "rg_auto";
    static final int NOTE_ID = 7301;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Remember the last thing the phone told us, so Settings can show it. */
    static void signal(Context c, String what) {
        prefs(c).edit().putString("lastSignal", what).putLong("lastSignalAt", System.currentTimeMillis()).apply();
    }

    static PendingIntent detectIntent(Context c) {
        Intent i = new Intent(c, DriveReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, 7303, i, flags);
    }

    /** Ask Android to tell us when the phone starts travelling in a vehicle. Lost after a restart or update, so it is called again then. */
    static void registerDetect(Context c) {
        try {
            List<ActivityTransition> t = new ArrayList<>();
            t.add(new ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(new ActivityTransitionRequest(t), detectIntent(c));
        } catch (SecurityException e) { /* permission removed */ }
    }

    static void show(Context c, String reason, boolean force) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("lastNote", 0) < 20 * 60 * 1000L) return;      // at most once every 20 minutes
        if (!force && p.getBoolean("journeyActive", false) && now - p.getLong("journeyActiveAt", 0) < 12 * 3600 * 1000L) return; // a journey is already running

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Driving detected", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Asks if you want to start RoadGuard when you start driving");
            ch.enableVibration(true); ch.setVibrationPattern(new long[]{0, 700, 300, 700, 300, 700});
            ch.enableLights(true); ch.setLightColor(0xFFF5B301); ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            try { nm.deleteNotificationChannel("rg-auto"); } catch (Exception e) { }
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("rg_autostart", reason);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, 7302, open, flags);

        String text = "test".equals(reason) ? "This is a test. When you start driving, this is the alert you will see. Tap it to start."
                    : "bt".equals(reason) ? "Connected to your car. Tap to start recording your journey."
                                          : "Tap to start RoadGuard and record your journey.";
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(c.getApplicationInfo().icon)
            .setContentTitle("🚗 Driving? Tap to start RoadGuard")
            .setColor(0xFFF5B301).setColorized(true)
            .setVibrate(new long[]{0, 700, 300, 700, 300, 700})
            .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_LIGHTS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(20 * 60 * 1000L)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Start RoadGuard", pi);
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) { signal(c, "A driving alert could not be shown because notifications are off"); return; }
        try { NotificationManagerCompat.from(c).notify(NOTE_ID, b.build()); p.edit().putLong("lastNote", now).apply(); }
        catch (SecurityException e) { signal(c, "A driving alert could not be shown because notifications are off"); }
    }
}
