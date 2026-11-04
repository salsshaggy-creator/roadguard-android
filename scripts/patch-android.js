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
