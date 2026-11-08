// Runs after `npx cap add android`: sets version, signing, notification text and battery-friendly options.
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', 'android');
const pkg = require('../package.json');
const [maj, min, pat] = pkg.version.split('.').map(Number);
const versionCode = process.env.GITHUB_RUN_NUMBER ? Number(process.env.GITHUB_RUN_NUMBER) + 100 : maj * 10000 + min * 100 + pat;

// 1. app/build.gradle: version + release signing from the key in GitHub secrets
const gradleFile = path.join(root, 'app', 'build.gradle');
let g = fs.readFileSync(gradleFile, 'utf8');
g = g.replace(/versionCode\s+\d+/, `versionCode ${versionCode}`).replace(/versionName\s+"[^"]*"/, `versionName "${pkg.version}"`);
if (!g.includes('signingConfigs')) {
  g = g.replace(/(android\s*\{)/, `$1
    signingConfigs {
        release {
            storeFile file('roadguard.jks')
            storePassword System.getenv('RG_STORE_PASSWORD')
            keyAlias 'roadguard'
            keyPassword System.getenv('RG_STORE_PASSWORD')
        }
    }`);
  g = g.replace(/(buildTypes\s*\{\s*release\s*\{)/, `$1
            signingConfig signingConfigs.release`);
}
fs.writeFileSync(gradleFile, g);

// 2. strings.xml: notification channel name + colour for the "recording" notification
const stringsFile = path.join(root, 'app', 'src', 'main', 'res', 'values', 'strings.xml');
let s = fs.readFileSync(stringsFile, 'utf8');
if (!s.includes('capacitor_background_geolocation_notification_channel_name')) {
  s = s.replace('</resources>', `    <string name="capacitor_background_geolocation_notification_channel_name">Journey recording</string>
    <string name="capacitor_background_geolocation_notification_color">#F5B301</string>
</resources>`);
}
fs.writeFileSync(stringsFile, s);

// 3. AndroidManifest: keep the screen orientation free and allow cleartext off (https only)
const manFile = path.join(root, 'app', 'src', 'main', 'AndroidManifest.xml');
let m = fs.readFileSync(manFile, 'utf8');
if (!m.includes('android.permission.WAKE_LOCK')) m = m.replace('<uses-permission android:name="android.permission.INTERNET" />', '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.WAKE_LOCK" />');
fs.writeFileSync(manFile, m);

console.log(`Android project patched: version ${pkg.version} (${versionCode})`);

// 4. MainActivity: keep the web engine awake in the background, so voice alerts, live sharing and the
//    demo keep running when the phone is locked or another app (music, WhatsApp) is in front.
//    Android otherwise marks the page hidden and slows its JavaScript almost to a stop.
const mainDir = path.join(root, 'app', 'src', 'main', 'java', 'gh', 'roadguard', 'app');
fs.mkdirSync(mainDir, { recursive: true });
fs.writeFileSync(path.join(mainDir, 'MainActivity.java'), `package gh.roadguard.app;

import android.view.View;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(AutoStartPlugin.class);
        super.onCreate(savedInstanceState);
    }

    private void stayAwake() {
        try {
            WebView w = getBridge() != null ? getBridge().getWebView() : null;
            if (w != null) {
                w.onResume();
                w.resumeTimers();
                w.dispatchWindowVisibilityChanged(View.VISIBLE);
            }
        } catch (Exception e) { /* never crash the app over this */ }
    }

    @Override
    public void onPause() {
        super.onPause();
        stayAwake();
    }

    @Override
    public void onStop() {
        super.onStop();
        stayAwake();
    }
}
`);
console.log('MainActivity: web engine stays awake in the background');

// 5. Auto-start: driving detection, car Bluetooth and the one-tap "Looks like you're driving" notification
for (const f of ['AutoStartPlugin.java', 'DriveReceiver.java', 'AutoNotify.java'])
  fs.copyFileSync(path.join(__dirname, '..', 'android-src', f), path.join(mainDir, f));
let g2 = fs.readFileSync(gradleFile, 'utf8');
if (!g2.includes('play-services-location')) {
  g2 = g2.replace(/dependencies\s*\{/, 'dependencies {\n    implementation "com.google.android.gms:play-services-location:21.3.0"');
  fs.writeFileSync(gradleFile, g2);
}
let m2 = fs.readFileSync(manFile, 'utf8');
if (!m2.includes('ACTIVITY_RECOGNITION')) {
  m2 = m2.replace('<uses-permission android:name="android.permission.INTERNET" />', `<uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`);
  m2 = m2.replace('</application>', `    <receiver android:name=".DriveReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.bluetooth.device.action.ACL_CONNECTED" />
            </intent-filter>
        </receiver>
    </application>`);
  fs.writeFileSync(manFile, m2);
}
console.log('Auto-start parts added');
