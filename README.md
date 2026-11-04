# RoadGuard GH – Android app

This repository builds the free RoadGuard GH Android app (an APK you can host on roadguard-gh.web.app).
The app is the same RoadGuard web app (`www/index.html`) inside an Android shell that can record GPS with the screen off.

**To build a new version:** go to the **Actions** tab → **Build RoadGuard GH Android app** → **Run workflow**.
When it finishes (about 5–10 minutes), open the run and download **RoadGuardGH-android-app** under *Artifacts*.

**To update the app:** replace `www/index.html` with the newest RoadGuard file, raise `"version"` in `package.json`, and commit. A build starts automatically.

Signing: the workflow needs two repository secrets, `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`. Keep the same key for every build, or phones won't accept updates.
