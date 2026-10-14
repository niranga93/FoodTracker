# Nourio 1.0.0: Google Play release kit

Everything for the first Play release of **Nourio 1.0.0 (versionCode 1)**. Work through the checklist from the top.

## What's in this folder

| File | What it is | In git? |
|---|---|---|
| `nourio-1.0.0-1.aab` | **Upload this to Play.** Signed release bundle | No (rebuild with the steps below) |
| `nourio-1.0.0-1.apk` | The same build as an APK, for installing on your own phone to test (`adb install -r`) | No |
| `mapping-1.0.0-1.txt` | R8 mapping, to turn crash reports back into readable code. Play reads it from the bundle automatically; keep this copy for the release. | No |
| `SHA256SUMS.txt` | Checksums of the files above | Yes |
| `VERIFICATION.md` | What was checked before this release (tests, lint, signing, secrets, permissions) | Yes |
| `upload_certificate.pem` | Public certificate of the upload key. Only needed if you ask Google to reset the upload key. Safe to share. | Yes |
| `store-listing.md` | Name, short and full description, release notes, category, contact details | Yes |
| `play-console-forms.md` | Answers for App content: privacy policy, app access, ads, rating, audience, health declaration, Health Connect permissions, data safety | Yes |
| `store/icon-512.png` | 512 x 512 app icon | Yes |
| `store/feature-graphic-1024x500.png` | Feature graphic | Yes |
| `store/screenshots/` | Phone screenshots go here (see step 6) | Yes |
| `tools/CropScreenshots.java` | Crops phone screenshots to Play's size limits | Yes |
| `debug-app-data-backup/` | Raw copy of the old debug install's data. Private. | No |

## App facts

| | |
|---|---|
| App name | Nourio |
| Package (app ID) | `com.nnrzone.nourio` (permanent after the first upload) |
| Version | 1.0.0 (versionCode 1) |
| Min / target Android | API 26 (Android 8.0) / API 37 |
| Developer | NNRZONE |
| Support email | foodtracker777@gmail.com |
| Privacy policy | https://niranga93.github.io/FoodTracker/ |

## Signing keys

| Key | SHA-1 | Used for |
|---|---|---|
| Upload key (`C:\Users\niran\keystores\nourio-upload.jks`, alias `upload`) | `D4:23:D3:BA:FF:F5:65:3F:24:BD:2D:DD:ED:C3:CB:AC:28:21:52:45` | Signs the bundle you upload and release APKs you install yourself. Needs its own Google OAuth client for Drive sign-in in those APKs. |
| Debug key (`C:\Users\niran\.android\debug.keystore`) | `D5:18:38:D1:61:01:A9:50:DB:75:B5:6E:BB:24:79:10:FD:56:D4:C5` | Builds run from Android Studio |
| Play App Signing key | Play Console > Test and release > App integrity, after the first upload | What users' installs are signed with. **Needs its own Google OAuth client, or Drive sign-in fails on Play installs.** |

The keystore password is only in `D:\claude projects\FoodTracker\keystore.properties` (gitignored). **Back up the `.jks` file and that password together**, for example in a password manager.

## Checklist

### 1. Before uploading
- [ ] Push the repo so the privacy policy (`privacy.html` / `index.html`) is live. Open https://niranga93.github.io/FoodTracker/ and check it says Nourio and has the "Insights, Patterns and Daily Tips" section.
- [ ] Google Cloud > Google Auth Platform:
  - [ ] Branding: app name **Nourio**, support email, logo (`store/icon-512.png`), privacy policy URL, home page.
  - [ ] Data access: only `https://www.googleapis.com/auth/drive.appdata`.
  - [ ] Clients: one Android client per signing key, all with package `com.nnrzone.nourio`:
    - [ ] debug SHA-1
    - [ ] upload key SHA-1. Without it, Drive in the APK says "Google sign-in isn't set up for this version of the app".
    - [ ] Play App Signing SHA-1, after the first upload (step 4)
  - [ ] Audience: **Publish app** (In production), so any Google account can connect Drive.

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
- [ ] Wait 5 to 10 minutes, then check Profile > Connections > Google Drive > Connect on the Play install.

### 5. App content (Policy and programs > App content)
Fill in every item from `play-console-forms.md`. Create a separate Gemini key for the reviewers (App access) and delete it after approval.

### 6. Store listing (Grow users > Store presence > Main store listing)
- [ ] Text from `store-listing.md`.
- [x] App icon: `store/icon-512.png`.
- [x] Feature graphic: `store/feature-graphic-1024x500.png`.
- [ ] Phone screenshots, 2 to 8. Suggested: Home report card, Move and This week, Intake, Burned charts, Insights, Profile.
  1. On the phone, open each screen and take a screenshot (power + volume down).
  2. Copy them to `release\store\screenshots\raw\`.
  3. Crop them to Play's limits (long side at most twice the short side):
     ```
     "C:\Program Files\Android\Android Studio\jbr\bin\java" release\tools\CropScreenshots.java release\store\screenshots\raw release\store\screenshots
     ```
  4. Upload the cropped PNGs from `release\store\screenshots\`.
  - These are public: use demo data or check that no name, weight or health detail you'd rather keep private is visible.

### 7. Go live
- [ ] Test the internal build: photo, describe, share a recipe link, barcode, Packaged search, Health Connect (allow the new data types), Insights (tap "Send today's tip now" and Accept from the notification), Drive backup, backup file.
- [ ] New personal developer accounts must run a **closed test** (at least 12 testers for 14 days) before production access. Check the Dashboard.
- [ ] Production > Create release > promote the tested build > countries > roll out.

## Build notes
- Release builds are shrunk by R8 over the whole app. Don't add `packageScope` back to the `optimization` block in `app/build.gradle.kts`: it made R8 split part of `kotlin.text` across packages and the app crashed at launch.
- Always install and open a release build on a phone before uploading. Debug builds aren't shrunk, so they don't show R8 problems.
- The USDA FoodData Central key ships inside the app by design (it's how the app looks up nutrition without a server). It is rate-limited and free; if it's ever abused, create a new one at api.data.gov, put it in `local.properties` and release an update.

## Next releases
1. Increase `versionCode` (and `versionName`) in `app/build.gradle.kts`. Every upload needs a higher versionCode.
2. From `D:\claude projects\FoodTracker`: `.\gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease`
3. Install `app\build\outputs\apk\release\app-release.apk` on a phone and check it opens.
4. Copy the `.aab`, `.apk` and `mapping.txt` here with the new version in the names, and update `SHA256SUMS.txt`.
5. Upload the `.aab`.
