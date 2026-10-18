# Release verification: Nourio 1.1.0 (versionCode 2)

Checked on 18 October 2026, at commit `347b22e`.

## Build
| Check | Result |
|---|---|
| Kotlin compile | No errors |
| Android Lint (release) | 0 errors, 14 warnings |
| Unit tests | 167 of 167 pass; 7 store-screenshot renders skipped (174 total) |
| Release bundle | `nourio-1.1.0-2.aab`, 31.5 MB (includes the 128 MB R8 mapping, which Play strips from downloads) |
| Release APK | `nourio-1.1.0-2.apk`, 33.7 MB |
| R8 | Code and resource shrinking on |
| Installed on a phone | **Not yet done.** Install the APK and check Burned and Sleep, heart rate and vitals before submitting |

## Signing
| Check | Result |
|---|---|
| Bundle signer | `CN=NNRZONE, O=NNRZONE, C=SG` |
| Upload key SHA-1 | `D4:23:D3:BA:FF:F5:65:3F:24:BD:2D:DD:ED:C3:CB:AC:28:21:52:45` |

## Manifest (read from the bundle)
| Check | Result |
|---|---|
| Package / version | `com.nnrzone.nourio`, 1.1.0 (2) |
| Min / target SDK | 26 / 37 |
| Health Connect reads | STEPS, ACTIVE_CALORIES_BURNED, TOTAL_CALORIES_BURNED, SLEEP, HEART_RATE, EXERCISE, WEIGHT, HEIGHT, DISTANCE, BLOOD_PRESSURE |
| Health Connect writes | NUTRITION, HYDRATION, WEIGHT |
| Removed since 1.0.0 | BASAL_METABOLIC_RATE, RESTING_HEART_RATE, OXYGEN_SATURATION, VO2_MAX |

## Each remaining Health Connect type and where a reviewer sees it
| Type | Visible in |
|---|---|
| Steps, active and total calories | Home, Burned (Steps card, burn split) |
| Sleep | Home sleep line, Sleep, heart rate and vitals |
| Heart rate | Heart rate card on Sleep, heart rate and vitals |
| Exercise | Workouts card on Burned and on Sleep, heart rate and vitals (always shown, with an empty state) |
| Weight (read) | Weigh-ins: scale weights are imported |
| Height | Profile height, filled when empty |
| Distance | Distance card; walking-burn estimate |
| Blood pressure | Insights: compared with sodium |
| Nutrition, hydration and weight (write) | Profile > Connections > "Share meals, water, weight" |
