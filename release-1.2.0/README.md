# Nourio 1.2.0: Google Play release kit

**Nourio 1.2.0 (versionCode 3)**, built on 24 October 2026 from commit `b5a58fd` on `main` (includes the fixes from the pre-release code review). It updates 1.1.0, which is live on Google Play.

The older `release/nourio-1.2.0-3.*` files and `release-1.2.0-outdated/` (24 October, 00:04) were built before the forecast, budget, barcode and review fixes. **Upload the bundle from this folder, not those.**

## What's in this folder

| File | What it is | In git? |
|---|---|---|
| `nourio-1.2.0-3.aab` | **Upload this to Play.** Signed release bundle | No |
| `nourio-1.2.0-3.apk` | Same build as an APK, for testing on a phone | No |
| `mapping-1.2.0-3.txt` | R8 mapping for readable crash reports. Play also reads it from the bundle. | No |
| `SHA256SUMS.txt` | Checksums of the files above | Yes |
| `VERIFICATION.md` | What was checked for this build | Yes |
| `upload_certificate.pem` | Public certificate of the upload key | Yes |
| `play-console-forms.md` | App content answers (unchanged from 1.1.0) | Yes |
| `store-listing.md` | Store listing, with the 1.2.0 release notes | Yes |

The APK is signed with the upload key, so it can't update the Play-installed app on a phone (Play re-signs with the app signing key). To test on a phone next to the Play version, use the `staging` build ("Nourio Test", package `com.nnrzone.nourio.test`): `gradlew assembleStaging`.

## What's new in 1.2.0

| Area | Change |
|---|---|
| Daily budget | Follows each day's burn: burn − your pace − half of yesterday's difference. A day's deficit is kept within 25% of its burn and 500 kcal, and the budget never goes below 1,500 kcal (men) / 1,200 (women). Shown as a chart on Home and step by step on the Budget page. |
| Burn | Samsung Health daily totals no longer count steps twice (burn was ~600 kcal/day too high); today's burn rises smoothly as you move. |
| Weight | A body model (after the NIH model, Hall 2011) with a weigh-in filter that learns the logging gap and how much heavier you are late in the day. |
| Journey | Estimated and actual weight on one chart, pace tiles, one-line verdict, details folded away. |
| AI | "Always allow" consent; photos only go to models that read images; live progress (service, model, key, step); 20 s per model while others are left; meals taking over 30 s finish in the background and are added to the log with a notification. |
| Fixes | Barcode scanner crash (R8), Open Food Facts and USDA lookups (certificate pins), food search ranking, rounding and alignment across the app, "No dietary requirements" / "No allergies". |

## Checklist

### 1. Before uploading
- [ ] Privacy policy: open https://niranga93.github.io/FoodTracker/ and check it shows "Effective Date: October 25, 2026" and the "Always allow" and background-analysis sentences under AI Features. (`index.html` was still the 14 October version; it now matches `privacy.html`.)

### 2. Upload
- [ ] Play Console > Nourio > Test and release > Production (or your testing track) > **Create new release**.
- [ ] Upload `nourio-1.2.0-3.aab`.
- [ ] Paste the release notes from `store-listing.md` (446 of 500 characters).
- [ ] Review the release. Play should show versionCode 3 replacing 2, and **no new permissions**.

### 3. Declarations
- [ ] Data safety: no change. Same data types, sent to the same places.
- [ ] Health apps declaration: no change. Same 10 Health Connect reads and 3 writes as 1.1.0.

### 4. Submit
- [ ] Publishing overview > **Send changes for review**.
