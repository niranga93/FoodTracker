# Nourio 1.1.0: Google Play resubmission kit

**Nourio 1.1.0 (versionCode 2)**, built from commit `347b22e` on 18 October 2026. It replaces 1.0.0, which Play rejected on 17 October under the Health Connect minimum-scope policy (Excessive Data Access).

The older `release/nourio-1.1.0-2.*` files from 16 October were built before the fix and still request the rejected permissions. **Upload the bundle from this folder, not those.**

## What's in this folder

| File | What it is | In git? |
|---|---|---|
| `nourio-1.1.0-2.aab` | **Upload this to Play.** Signed release bundle | No |
| `nourio-1.1.0-2.apk` | Same build as an APK, for testing on your phone (`adb install -r`) and for the declaration video | No |
| `mapping-1.1.0-2.txt` | R8 mapping for readable crash reports. Play also reads it from the bundle. | No |
| `SHA256SUMS.txt` | Checksums of the files above | Yes |
| `VERIFICATION.md` | What was checked for this build | Yes |
| `upload_certificate.pem` | Public certificate of the upload key | Yes |
| `play-console-forms.md` | App content answers, including the updated Health Connect reasons | Yes |
| `store-listing.md` | Store listing text, updated to drop VO2 max, blood oxygen and BMR | Yes |

Store graphics and screenshots have not changed. They are in `release/store/`.

## Why 1.0.0 was rejected

Play could not find any feature using these Health Connect types: BasalMetabolicRate, ExerciseSession, OxygenSaturation, RestingHeartRate and Vo2Max. The app did use them, but each one only appeared once a watch had recorded data.

## What changed for the fix

| Type | Decision |
|---|---|
| Basal metabolic rate | **Removed.** Resting burn always uses the profile formula. |
| Resting heart rate | **Removed** from the Heart rate card and Insights. |
| Oxygen saturation | **Removed.** |
| VO2 max | **Removed.** |
| Exercise sessions | **Kept.** The Workouts card now always shows on Burned and on Sleep, heart rate and vitals, with an empty-state message when nothing is recorded. |

Health Connect permissions in this build: 10 reads (steps, active calories, total calories, sleep, heart rate, exercise, weight, height, distance, blood pressure) and 3 writes (nutrition, hydration, weight).

## Checklist

### 1. Upload
- [ ] Play Console > Nourio > Test and release > the track 1.0.0 was on > **Create new release**.
- [ ] Upload `nourio-1.1.0-2.aab`. Release notes for 1.1.0 are in `store-listing.md`.
- [ ] **Save as draft. Don't send it for review yet.**

### 2. Health apps declaration (App content > Health apps)
- [ ] Step 1: the permission list now comes from the new bundle. Check that BASAL_METABOLIC_RATE, RESTING_HEART_RATE, OXYGEN_SATURATION and VO2_MAX are gone.
- [ ] Health features: update them to match the "Health apps declaration" section of `play-console-forms.md`. Resting heart rate, VO2 max and blood oxygen are no longer mentioned.
- [ ] Step 2: paste the reason for each permission from the "Health Connect permissions" table.
- [ ] Attach a screen recording made with `nourio-1.1.0-2.apk` on a phone that has Health Connect data:
  1. Profile > Connections > turn on "Read sleep, heart rate, workouts, weight" and allow everything.
  2. Burned: the Workouts card (ideally with a real synced workout) and Source data.
  3. Profile > Sleep, heart rate and vitals: sleep, heart rate, distance and Workouts.
  4. Insights: blood pressure compared with sodium.
  5. Profile: height filled from Health Connect, and weigh-ins imported from a scale.
- [ ] Save.

### 3. Other declarations
- [ ] Data safety: no change needed. The data categories are still the same (health and fitness info).
- [ ] Privacy policy: open https://niranga93.github.io/FoodTracker/ and check that the Health Connect table no longer mentions VO2 max, oxygen saturation or basal metabolic rate.

### 4. Submit
- [ ] Publishing overview > **Send changes for review**.
- [ ] Under Policy status, reply to the rejection if Play offers an appeal or "I've made changes" option, and say that the four types were removed and exercise sessions are shown in the Workouts card.
