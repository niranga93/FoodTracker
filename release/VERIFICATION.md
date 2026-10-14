# Release verification: Nourio 1.0.0 (versionCode 1)

Checked on 14 October 2026, before the first Play upload.

## Build
| Check | Result |
|---|---|
| Kotlin compile (full rebuild) | No errors, no warnings |
| Android Lint | **No issues** (was 83 warnings and 18 hints at the first review) |
| Unit tests | 16 of 16 pass: report-card grading (4), Gemini model ranking (4), insights engine (7), example (1). The 7 store-screenshot renders are skipped unless STORE_SHOTS=1. |
| Release bundle | `nourio-1.0.0-1.aab`, 17.7 MB |
| Release APK | `nourio-1.0.0-1.apk`, 24.7 MB |
| R8 | Whole-app shrinking; release build installed and opened on a Samsung phone with no crash in the log |

## Signing
| Check | Result |
|---|---|
| Bundle signer | `CN=NNRZONE, O=NNRZONE, C=SG` |
| Upload key SHA-1 | `D4:23:D3:BA:FF:F5:65:3F:24:BD:2D:DD:ED:C3:CB:AC:28:21:52:45` |
| Keystore and password in git | No (`keystore.properties`, `*.jks` are gitignored) |

## Secrets
| Check | Result |
|---|---|
| The 3 Gemini dev keys from `local.properties` inside the `.aab` / `.apk` | Not present (release build blanks them) |
| Any real key value anywhere in git history | 0 matches for each of the 4 keys in `local.properties` |
| `local.properties`, keystores, `.env`, credentials ever committed | Never |
| Private keys, tokens or passwords in tracked files | None (the only `.pem` is the public upload certificate) |
| USDA key | Ships in the app by design (see README, Build notes) |

## Manifest (release)
| Check | Result |
|---|---|
| `debuggable` | false |
| Cleartext HTTP | Not allowed |
| Exported components of our own | `MainActivity` (launcher and share target, inputs validated) and the Health Connect permission-usage screen (protected by `START_VIEW_PERMISSION_USAGE`). Everything else exported comes from libraries and is permission-protected. |
| Tip action receiver | Not exported |
| Advertising ID permission | None |
| Health Connect permissions | 15 read/write types, each listed in the privacy policy and `play-console-forms.md` |

## Privacy and security changes in this release
- Shared photos: only `content://` from other apps, 40 MB cap, must decode as an image.
- Shared recipe links: connections to loopback, private and link-local addresses are blocked (also after redirects); `http` is upgraded to `https`.
- Backup file restore capped at 50 MB.
- Gemini key encrypted with an Android Keystore key, sent in a header (never in a URL), rotated across up to 10 keys.
- Android device backup excludes the Gemini keys and Health Connect-derived values (`health.xml`, `insights.xml`).
- Daily tip notifications hide their text on the lock screen.
- Food names are only logged in debug builds.
- Insights analysis runs on the phone only.
