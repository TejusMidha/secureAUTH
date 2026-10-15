# Google Play — Data safety form: draft answers

*A draft to copy into Play Console → App content → Data safety. Check every answer once more before submitting: the form is a legal statement, and Google's own SDK disclosures are the source of truth for the Maps / Play-services rows.*

## How Play decides what counts

Play counts data as **collected** when it is **sent off the device** (to you, or to a service or SDK in your app). Data that stays on the phone is **not** "collected". **Shared** means sent to a third party. So most of this app's data does not appear in the form at all.

## Section 1 — Data collection and security

| Question | Draft answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (declare the rows in Section 2 that apply) — or **No** if the team decides, after reading Google's Maps SDK disclosure, that none applies | The app's own code sends nothing. The Google Maps SDK contacts Google when the login map is opened |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | The only network traffic is https (map tiles, the RFC page) |
| Do you provide a way for users to request that their data is deleted? | **Yes** | Uninstalling or "Clear storage" deletes everything; there is no server copy. Explain this in the free-text field |

## Section 2 — Data types (draft)

| Data type | Collected? | Shared? | Notes for the form |
|---|---|---|---|
| **Approximate location** | Stored on the device only; not sent by the app | No | Used for the optional login map and the "far from last login" warning. Optional. Processed on the device. *(Maps SDK / Play services behaviour: see the note below.)* |
| **Precise location** | Stored on the device only; not sent by the app | No | Same as above |
| Personal info (name, e-mail, address, phone, ...) | No | No | The app asks for a username only, and it never leaves the device |
| Financial info | No | No | — |
| Health and fitness | No | No | — |
| Messages | No | No | — |
| **Photos and videos** | No | No | The optional profile photo stays in private storage and is never sent anywhere |
| Audio, Files and docs, Calendar, Contacts | No | No | — |
| App activity (in-app actions, search history, installed apps) | No | No | The login history is kept on the device only |
| Web browsing | No | No | The Help screen opens one fixed public page; browsing history is not collected |
| App info and performance (crash logs, diagnostics) | No | No | No crash-reporting library is included |
| **Device or other IDs** | No | No | No advertising ID, no analytics ID |

### Note on Google Maps SDK and Play services

The login map uses the Google Maps SDK (`play-services-maps`), and location uses `play-services-location`. Google documents what the Maps SDK itself collects (for example IP address and device information needed to serve map tiles). Read Google's current "Maps SDK for Android — data safety" guidance and, if it says the SDK collects data, declare that data type here as **collected**, purpose **App functionality**, **not** used for advertising, **not** shared for other purposes. The app's own code never sends coordinates or login history to Google.

## Section 3 — Other App content answers

| Item | Draft answer |
|---|---|
| App category | Tools (or Productivity) |
| Contains ads | **No** |
| Target audience | **18 and over** (a security tool; not designed for children) |
| App access | Needed to review the app: create an account in the app, set up the authenticator (scan the QR with any authenticator app, or read the code on the Dashboard), and the reviewer's phone must have a screen lock or a fingerprint set up (the third login step needs it). Put these steps in the "App access" instructions |
| Content rating | Complete the questionnaire: no violence, no user-generated content shared with others, no purchases, no location sharing with other users. Expected: Everyone |
| News app / COVID-19 / Government | No |
| Permissions declaration | **Location** (optional, used for the login map and a far-away-login warning), **Camera** (profile photo only; the picture is taken by the phone's camera app). Neither is a "sensitive" permission needing a special form, but the in-app explanation text above can be used as the justification |
| Privacy policy link | The public address where `docs/privacy-policy.md` is hosted |

## Section 4 — Checklist before the first upload

- [ ] Fill in the contact placeholders in the privacy policy and host it publicly.
- [ ] Upload the signed `.aab` (see `docs/RELEASE.md`) and the `mapping.txt` file next to it, so crash reports can be read.
- [ ] Add the **release** signing certificate's SHA-1 (or, with Play App Signing, the SHA-1 Play shows under App integrity) to the Maps API key restriction, together with the package name `com.secureauth.simulator`.
- [ ] Add at least two phone screenshots. Screens that show codes or secrets (Dashboard, Enroll, Backup codes) block screenshots on purpose; use Login history, Settings, Help and Profile for the store listing, or take pictures of the phone with another device.
- [ ] Give the Play listing a short description, a full description and a 512 × 512 icon (the shield-and-key artwork in `res/drawable/ic_launcher_foreground.xml` can be exported from Android Studio: right-click → New → Image Asset).
