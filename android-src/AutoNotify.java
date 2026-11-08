package gh.roadguard.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Shows the one-tap "Looks like you're driving" notification. Tapping it opens RoadGuard and starts a journey. */
public class AutoNotify {
    static final String CHANNEL = "rg-auto";
    static final String PREFS = "rg_auto";
    static final int NOTE_ID = 7301;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void show(Context c, String reason) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("lastNote", 0) < 20 * 60 * 1000L) return;      // at most once every 20 minutes
        if (now - p.getLong("journeyActiveAt", 0) < 3 * 60 * 1000L) return; // a journey is already running
        p.edit().putLong("lastNote", now).apply();

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Driving detected", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Asks if you want to start RoadGuard when you start driving");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("rg_autostart", reason);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(c, 7302, open, flags);

        String text = "bt".equals(reason) ? "Connected to your car. Tap to start recording your journey."
                                          : "Tap to start RoadGuard and record your journey.";
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(c.getApplicationInfo().icon)
            .setContentTitle("Looks like you're driving")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Start RoadGuard", pi);
        try { NotificationManagerCompat.from(c).notify(NOTE_ID, b.build()); } catch (SecurityException e) { /* notifications not allowed */ }
    }
}
