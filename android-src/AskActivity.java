package gh.roadguard.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** The car's voice button (Bluetooth "voice command"). During a journey, with the setting on: RoadGuard listens.
    Otherwise it goes straight on to Google Assistant, as before. */
public class AskActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            SharedPreferences p = AutoNotify.prefs(this);
            long now = System.currentTimeMillis();
            boolean running = p.getBoolean("journeyActive", false) && now - p.getLong("journeyActiveAt", 0) < 12 * 3600 * 1000L;
            boolean on = p.getBoolean("carAsk", false);
            if (running && on) {
                boolean wasVisible = now - AutoStartPlugin.lastPausedAt < 2000 || (AutoStartPlugin.instance != null && AutoStartPlugin.instance.appVisible());
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                i.putExtra("rg_ask", true); i.putExtra("rg_wasvisible", wasVisible);
                startActivity(i);
            } else passOn();
        } catch (Exception e) { passOn(); }
        finish();
    }

    private void passOn() {
        try { Intent g = new Intent(Intent.ACTION_VOICE_COMMAND); g.setPackage("com.google.android.googlequicksearchbox"); g.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(g); return; } catch (Exception e) { }
        try { Intent a = new Intent(Intent.ACTION_VOICE_ASSIST); a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(a); } catch (Exception e) { }
    }
}
