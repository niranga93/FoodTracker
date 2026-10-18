# Play Console forms: Nourio 1.0.0

Answers for Policy and programs > App content, worked out from the app's code and privacy policy. Google changes these forms from time to time, so match each answer to the current question wording. Items marked **Judgment call** are where the right answer depends on how Google reads the question. The more cautious answer is given.

---

## Privacy policy
```
https://niranga93.github.io/FoodTracker/
```
It must be live and say Nourio before you submit (push the repo first).

## App access
**All or some functionality is restricted.** Reviewers need a Gemini key to test photo, describe, share and coach features. Add an instruction:

| Field | Value |
|---|---|
| Name | Gemini key for AI features |
| Instructions | `The app works without sign-in. Barcode scan, manual entry, packaged search, Health Connect and backup need no key. Photo analysis, meal descriptions, sharing into the app and the coach need a free Google Gemini API key: open Profile > Connections > Gemini key, paste the key below, and tap Save.` |
| Username / password fields | Put a working Gemini API key in the password field (create a separate one at https://aistudio.google.com/apikey for reviewers, and delete it after approval). |

## Ads
**No, my app does not contain ads.**

## Content rating
Start the questionnaire with your email, category **All other app types** (or Reference, News, or Educational if offered as the closest match). Answer **No** to violence, sexuality, language, controlled substances, gambling and similar.
- Users interact or exchange content: **No** (nothing is shared between users).
- Shares user location: **No**.
- Digital purchases: **No**.

## Target audience and content
- Target age: **18 and over** only. The app turns off burn estimates for younger profiles, and the policy says it is not meant for under-18s.
- Appeals to children: **No**.

## News app
**No.**

## Advertising ID
**No**, the app does not use an advertising ID. The release manifest has no `AD_ID` permission (checked).

## Government app / Financial features
**No** / **None of these**.

## Health apps declaration
Select the features that apply:
- **Nutrition and weight management** (calorie and nutrient tracking, weight log, weight outlook)
- **Activity and fitness** (steps, distance, calories burned, workouts, sleep and sleep stages, heart rate shown from Health Connect)
- If offered: **Health monitoring / vitals** (heart rate and blood pressure are shown for information and compared with food on the Insights page, not used for diagnosis)
- **Wellness recommendations**: daily tips based on the user's own data, worked out on the device; accepting one changes an in-app goal

Confirm it is **not a medical device** and does not diagnose or treat anything. The app shows: "Calories, burn, and weight outlook are estimates, not medical advice."

## Health Connect permissions
Play asks for a reason per permission, and a video or description of how each is used may be requested. Suggested text:

| Permission | Reason |
|---|---|
| READ_STEPS | Shows daily steps on Home and Burned and estimates calories burned from activity. |
| READ_ACTIVE_CALORIES_BURNED | Shows calories burned through activity and uses them in the daily calorie balance. |
| READ_TOTAL_CALORIES_BURNED | Shows total calories burned and uses them in the daily calorie balance and weight outlook. |
| READ_SLEEP | Shows the last 7 nights of sleep on the Burned page (opt-in switch). |
| READ_HEART_RATE | Shows daily average, lowest and highest heart rate and a 7-day trend on the Burned page (opt-in switch). |
| READ_EXERCISE | Lists the day's workouts (name, time, length without pauses, source app and calories burned during each) in the Workouts card on the Burned page and on Sleep, heart rate and vitals (opt-in switch). |
| READ_WEIGHT | Imports weights from smart scales and other apps into the user's weigh-ins, keeping calorie estimates current (opt-in switch). |
| READ_HEIGHT | Fills the user's height in their profile when it is empty; height is needed for calorie estimates (opt-in switch). |
| READ_BLOOD_PRESSURE | Compares blood pressure with sodium intake on the Insights page and shows its trend, so the user can see how diet relates to it; analysis runs on the device only (opt-in). |
| READ_DISTANCE | Shows distance walked or run, and uses it instead of a step-length guess to estimate walking calories (opt-in switch). |
| WRITE_NUTRITION | Writes each logged meal's calories and nutrients to Health Connect so the user's other health apps can use them (opt-in switch). |
| WRITE_HYDRATION | Writes the user's daily water total to Health Connect (opt-in switch). |
| WRITE_WEIGHT | Writes weigh-ins logged in the app to Health Connect (opt-in switch). |

All Health Connect data is used only on the device, is not sent to Gemini or any other service, and is not used for ads. The permissions rationale screen opens the privacy policy, which is already wired in the manifest.

---

## Data safety

### Overview questions
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS only; cleartext is blocked) |
| Do you provide a way for users to request that their data is deleted? | **Yes.** All data is on the device and the user deletes it in the app or by clearing app storage. Nothing is kept on a server of ours. |
| Account creation | **My app does not allow users to create an account** (no account-deletion URL needed). |

### Data types
"Collected" means sent off the device. Data processed only on the phone (profile, food log, Health Connect readings) is **not** collected. For each type below, mark it **Collected**, **Not shared** (sent to a service provider processing it for the user, not to an independent third party), **processed ephemerally** where noted, **Optional** (user can choose not to use the feature), and purpose **App functionality**.

| Category > Type | Collected? | Where it goes | Ephemeral | Notes |
|---|---|---|---|---|
| Photos and videos > Photos | Yes | Google Gemini (analysis) | Yes | Only meal photos the user chooses to analyze. **Judgment call:** requests use the user's own Gemini key, but declaring it is the safe choice. |
| Health and fitness > Fitness info | Yes | Google Gemini (coach summary, meal text); user's own Google Drive / backup file | No | Food log, calorie and nutrient totals, water, burn estimate. Diet and nutrition data fit "Fitness info". |
| Health and fitness > Health info | Yes | User's own Google Drive / backup file; Gemini (diet and allergy choices in coach summary) | No | Diet and allergy choices, weigh-ins. **Judgment call:** declared because allergies can count as health info. |
| App activity > Other user-generated content | Yes | Google Gemini (typed or spoken meal descriptions, shared text and recipe text); Open Food Facts (product search text) | Yes | |
| Personal info > Name, Other info | Yes | User's own Google Drive / backup file only | No | Name, year of birth, sex, height from the profile. **Judgment call:** only in the user's own backup; declare to be safe. |
| Personal info > Email address | No | | | The Drive account email is shown in the app only and never sent anywhere except Google's own sign-in. |
| Location, Contacts, Messages, Calendar, Audio files, Files and docs, Financial info, Web browsing, Device or other IDs | No | | | Spoken input is turned into text by Android's speech service; the app never receives audio. |

### Security practices summary to double-check against the policy
- No ads, analytics or tracking SDKs.
- USDA FoodData Central receives food names; Open Food Facts receives barcodes and search text (no personal data).
- Health Connect data stays on the device (only weights and an empty-profile height imported from Health Connect become part of the user's own Drive or file backup).
- Sleep, heart rate, blood pressure and other Health Connect data used by the Insights page is analysed on the device only, never sent anywhere, and is left out of Android's own device backup (`data_extraction_rules.xml`).
- Daily tip notifications hide their text on the lock screen.

---

## Other declarations Play may show
| Item | Answer |
|---|---|
| Foreground service permissions | Not needed: the release manifest has no foreground-service type (checked). |
| Exact alarm, SMS, call log, location, background location | Not used. |
| Camera | Used for meal photos and barcode scanning. Barcode scanning runs on the device. |
| Notifications | Only "meal ready to review" after an offline photo is analyzed. |
