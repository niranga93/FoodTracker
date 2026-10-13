# Nourio: Google Play release kit

Everything needed to publish **Nourio 1.0.0** to Google Play. Work through the checklist from the top.

| File | What it is |
|---|---|
| `nourio-1.0.0-1.aab` | Signed release bundle to upload (version 1.0.0, versionCode 1) |
| `upload_certificate.pem` | Public certificate of the upload key. Only needed if you ever ask Google to reset the upload key. Safe to share. |
| `store-listing.md` | App name, short and full description, release notes, category, contact details |
| `play-console-forms.md` | Answers for App content: privacy policy, ads, audience, health declaration, data safety, Health Connect permissions |
| `store/icon-512.png` | 512 x 512 app icon for the store listing |
| `store/feature-graphic-1024x500.png` | Feature graphic for the store listing |

Not in git (gitignored): the `.aab` itself, and `debug-app-data-backup/` (a raw copy of the old debug install's data). If this folder came from git, rebuild the bundle with the steps under **Next releases**.

## App facts

| | |
|---|---|
| App name | Nourio |
| Package (app ID) | `com.nnrzone.nourio` (permanent after the first upload) |
| Version | 1.0.0 (versionCode 1) |
| Min / target Android | API 26 (Android 8.0) / API 37 |
| Developer | NNRZONE, SG |
| Support email | foodtracker777@gmail.com |
| Privacy policy | https://niranga93.github.io/FoodTracker/ |

## Signing keys

| Key | SHA-1 | Where it is used |
|---|---|---|
| Upload key (`C:\Users\niran\keystores\nourio-upload.jks`, alias `upload`) | `D4:23:D3:BA:FF:F5:65:3F:24:BD:2D:DD:ED:C3:CB:AC:28:21:52:45` | Signs the bundle you upload, and release APKs you install yourself (`gradlew :app:assembleRelease`). Needs its own Google OAuth client for Drive sign-in in those APKs. |
| Debug key (`C:\Users\niran\.android\debug.keystore`) | `D5:18:38:D1:61:01:A9:50:DB:75:B5:6E:BB:24:79:10:FD:56:D4:C5` | Builds run from Android Studio. Google OAuth client for testing. |
| Play App Signing key | Shown after the first upload: Play Console > Test and release > App integrity | What users' installs are signed with. **Needs its own Google OAuth client, or Drive sign-in fails on Play installs.** |

The keystore password is only in `D:\claude projects\FoodTracker\keystore.properties` (gitignored). **Back up the `.jks` file and that password together** (a password manager is ideal). If they are lost, Google can reset the upload key using `upload_certificate.pem` from a new key, but it takes a support request.

## Checklist

### 1. Before uploading
- [ ] Commit and push the repo so `privacy.html` / `index.html` (the Nourio policy) are live at the privacy policy URL. Open the URL and check it says Nourio.
- [ ] Google Cloud > Google Auth Platform:
  - [ ] Branding: app name **Nourio**, support email, logo, privacy policy URL, home page.
  - [ ] Data access: only `https://www.googleapis.com/auth/drive.appdata`.
  - [ ] Clients: one Android client per signing key, all with package `com.nnrzone.nourio`:
    - [ ] **debug** SHA-1 (builds from Android Studio)
    - [ ] **upload key** SHA-1 (release APKs you install yourself). Without it, Drive says "Google sign-in isn't set up for this version of the app" (`UNREGISTERED_ON_API_CONSOLE` in the log).
    - [ ] **Play App Signing** SHA-1, after the first upload (step 4)
  - [ ] Audience: **Publish app** (In production), so any Google account can connect Drive. Test users are limited to 100 and must re-approve every 7 days.

### 2. Create the app in Play Console
- [ ] Create app: name **Nourio**, default language English, **App**, **Free**, accept the declarations.

### 3. First upload (internal testing)
- [ ] Test and release > Testing > Internal testing > Create new release.
- [ ] Accept **Play App Signing** when asked.
- [ ] Upload `nourio-1.0.0-1.aab`. Release name `1.0.0`, notes from `store-listing.md`.
- [ ] Add yourself as a tester, open the opt-in link on your phone and install from Play.

### 4. Right after the first upload
- [ ] Test and release > App integrity: copy the **App signing key SHA-1**.
- [ ] Google Cloud > Clients: add an Android client for `com.nnrzone.nourio` with that SHA-1.
- [ ] Wait 5 to 10 minutes, then check Profile > Connections > Google Drive > Connect works on the Play install.

### 5. App content (Policy and programs > App content)
Fill in every item using `play-console-forms.md`.

### 6. Store listing (Grow users > Store presence > Main store listing)
- [ ] Text from `store-listing.md`.
- [x] App icon: `store/icon-512.png` (512 x 512 PNG, drawn from the launcher icon).
- [x] Feature graphic: `store/feature-graphic-1024x500.png`.
- [ ] Phone screenshots: 2 to 8, 9:16, each side 320 to 3840 px, and the long side no more than twice the short side. A full 1080 x 2316 phone screenshot is too tall, so crop to 1080 x 1920. Use demo data, not your own. Suggested: Home, Review meal (after a photo), Intake, Burned, Packaged search, Connections.

### 7. Go live
- [ ] Test the internal build on a phone (photo, share, barcode, Packaged search, Health Connect, Drive backup, backup file).
- [ ] New personal developer accounts must run a **closed test** (at least 12 testers for 14 days) before production access. Check the Dashboard for what your account needs.
- [ ] Production > Create release > promote the tested build > countries > roll out.

## Build notes
- Release builds are shrunk by R8 over the whole app. Don't add `packageScope` back to the `optimization` block in `app/build.gradle.kts`: it made R8 split part of `kotlin.text` across packages, and the app crashed at launch (`IllegalAccessError`, fixed 14 Oct 2026).
- Always install and open a release build on a phone before uploading. Debug builds aren't shrunk, so they don't show R8 problems.

## Next releases
1. Increase `versionCode` (and `versionName`) in `app/build.gradle.kts`. Every upload needs a higher versionCode.
2. From `D:\claude projects\FoodTracker`, run `.\gradlew :app:assembleRelease :app:bundleRelease`.
3. Install `app\build\outputs\apk\release\app-release.apk` on a phone (`adb install -r`) and check it opens.
4. Upload `app\build\outputs\bundle\release\app-release.aab`.
