# Privacy Policy — SecureAuth Simulator

*Effective date: 15 October 2026. Applies to the Android app "SecureAuth Simulator" (package `com.secureauth.simulator`).*

> **Before publishing:** replace the two `[ ... ]` placeholders in the "Contact" section with the team's real details and host this page at a public web address (the Play Console asks for a link). Nothing else needs changing unless the app changes.

## The short version

* **Your data stays on your phone.** The app has no account system, no server, no analytics and no advertising. We cannot see anything you do in it.
* **Location is optional.** It is used only to note roughly where a login happened, so the app can warn you about a login far from your last one. You can say no, and you can switch it off in Settings.
* **The camera is used only for a profile photo**, and only when you tap "Take photo".
* **Nothing is backed up or copied** by Android's backup or phone-transfer features.
* **Uninstalling the app deletes everything it stored.**

## What the app stores, and where

Everything below is kept in the app's private storage on your phone. Other apps cannot read it.

| What | Why | How it is protected |
|---|---|---|
| Username | To find your account | Stored as typed (lower case) |
| Password | To check you when you log in | **Never stored.** Only a salted PBKDF2-HMAC-SHA256 hash (120,000 rounds) is kept |
| Authenticator secret (the key behind your 6-digit codes) | To work out your codes | Encrypted with AES-256-GCM using a key inside the Android Keystore; the key never leaves the phone |
| Backup codes | To let you in if you lose your authenticator | **Never stored in plain text.** Only salted PBKDF2 hashes are kept; the plain codes are shown once |
| Login history (event name, date and time, result) | So you can review what happened on your account | On the phone only; you can export it to a file you choose |
| Place of a login (latitude and longitude) | Only if you allowed location: to show logins on a map and warn about a far-away login | On the phone only; stored as "none" when you did not allow location |
| Trusted device names (for example "Google Pixel 8") | So you can see and revoke devices | On the phone only |
| Session record (a one-way hash of a random token and an expiry time) | To know you are logged in | The token itself is only in memory; the stored hash cannot be turned back into it |
| Profile photo (optional) | To show your picture in the app | A small copy (at most 512 × 512 pixels) in private storage; the original is not kept |
| Settings (code length, how often a code changes, algorithm, auto-lock time, "record location") | To remember your choices | Plain preferences on the phone |

## Permissions the app asks for

| Permission | When | Why | If you refuse |
|---|---|---|---|
| Location (approximate or precise) | The first time you log in, after an explanation | To note where a login happened | Everything still works; logins are stored without a place |
| Camera | When you tap "Take photo" in Profile, after an explanation | To take your profile photo with the phone's camera app | Choose a photo from the gallery instead |
| Internet | Only when you open the "RFC 6238" page in Help | To show a public web page (datatracker.ietf.org) | The page does not load; the built-in help page still works |

The app does not ask for storage, contacts, microphone, phone or SMS permissions. Files you export (login history, backup codes) are written through Android's own file picker, to a place you choose.

## Who can receive your data

**Nobody, unless you send it.** The app itself never sends your data anywhere. The only ways data leaves the phone are actions you take on purpose:

* **Share QR image** (Enroll screen): sends the QR picture, which contains your authenticator secret, to the app you choose. The app asks you to confirm first.
* **Save as .txt / Share** (Backup codes): writes or sends your backup codes where you choose.
* **Export CSV** (Login history): writes your history to a file you choose.

Anyone who gets those files or images can use what is in them, so keep them private.

## Third-party components

* **Google Maps SDK and Google Play services (Fused Location).** The login map is drawn with Google Maps, which downloads map pictures from Google over the internet and may receive technical information such as your IP address and the map area being viewed. The app does not send Google your login history, your usernames or your secrets. Google's own terms and privacy policy apply to the map: <https://policies.google.com/privacy>. If you never open the map, the Maps SDK is not used. Location lookups use Google Play services on your phone.
* **ZXing** (draws the QR code) and **AndroidX Biometric** (the fingerprint or screen-lock prompt) run entirely on the phone. The app never receives your fingerprint or your screen-lock PIN; Android only tells it "yes" or "no".

## How long data is kept

Until you uninstall the app or clear its data in Android Settings. There is no copy anywhere else, so deleted data cannot be recovered by us or by anyone else.

## Security measures

Passwords and backup codes are stored only as slow, salted hashes; the authenticator secret is encrypted with a hardware-backed key where the phone supports it; the app locks itself after the auto-lock time and when the screen turns off; screens that show codes or secrets block screenshots and the recent-apps preview; the app blocks Android backups. No system is perfectly secure: someone who has your unlocked phone, your password, your authenticator and your fingerprint or screen lock can sign in as you.

## Children

The app is not aimed at children under 13 and does not knowingly collect their information.

## Changes to this policy

If the app starts to handle data differently, this page will be updated and the effective date changed.

## Contact

Questions about this policy: **[team name / developer name]**, **[contact e-mail address]**.
