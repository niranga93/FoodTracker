# Release verification: Nourio 1.2.0 (versionCode 4)

Checked on 24 October 2026, at commit `b5a58fd` (`main`).

## Build
| Check | Result |
|---|---|
| Kotlin compile | No errors |
| Android Lint (release) | 0 errors, 5 warnings |
| Unit tests | 223 pass, 0 fail; 8 skipped on purpose (store-screenshot renders and a live certificate check) |
| Code review | Full review of every change since 1.1.0; all 10 findings fixed in `b5a58fd` |
| Release bundle | `nourio-1.2.0-4.aab`, 31.8 MB |
| Release APK | `nourio-1.2.0-4.apk`, 33.9 MB |
| R8 | Code and resource shrinking on; ML Kit kept (the barcode scanner crashed under R8 before) |

## Signing
| Check | Result |
|---|---|
| Signer | `CN=NNRZONE, O=NNRZONE, C=SG` |
| Upload key SHA-1 | `D4:23:D3:BA:FF:F5:65:3F:24:BD:2D:DD:ED:C3:CB:AC:28:21:52:45` (same as 1.1.0) |

## Manifest (read from the APK)
| Check | Result |
|---|---|
| Package / version | `com.nnrzone.nourio`, 1.2.0 (4) |
| Target SDK | 37 |
| Health Connect reads | STEPS, ACTIVE_CALORIES_BURNED, TOTAL_CALORIES_BURNED, SLEEP, HEART_RATE, EXERCISE, WEIGHT, HEIGHT, DISTANCE, BLOOD_PRESSURE |
| Health Connect writes | NUTRITION, HYDRATION, WEIGHT |
| Changed since 1.1.0 | None |

## Tested on a phone
The same code was installed as the `staging` build ("Nourio Test") on a Samsung phone with real data and Health Connect connected:

| Area | Result |
|---|---|
| Every main screen and sub-page | Opens with no crash; layout checked screen by screen |
| Barcode scan | Camera opens and scanned products load |
| Packaged and food search | Open Food Facts and USDA results load ("Bananas, raw · 89 kcal") |
| Meal by description | Analyzed and shown for review |
| Over-30-second handoff | Tested with the limit set to 3 s: the meal moved to the background, was saved to the log and a notification opened it |
| Coach and Assistant | Answer, with the live progress line |
| Home budget chart, Budget page, Journey | Numbers add up; checked against today and yesterday |

Not re-tested on the phone after the code review fixes: the photo routing for non-Gemini AI services (covered by unit tests) and the AI router's network-error handling (unit tests).
