# SecureAuth Simulator

SecureAuth Simulator is an Android multi-factor authentication app whose time-based one-time
password (TOTP) engine is built from cryptographic first principles (RFC 6238 / RFC 4226) with no
OTP, TOTP or Base32 library. Users log in with a password, a TOTP code and a biometric check; every
step is recorded in an auditable, offline-only login history, and logins from unusual locations are
flagged. TOTP secrets are encrypted with an Android Keystore AES-GCM key, and passwords and backup
codes are stored only as salted PBKDF2 hashes.

## Features

**Login handshake:** Password → TOTP code (±1 window) → Biometric → Session.
Any failure stops the flow. 3 failures in a row trigger a 5-minute lockout.

| Screen | Function |
|---|---|
| Splash | Entry, "Get started" |
| Register / Login | Material fields, "Trust this device" check box, lockout dialog |
| Enroll | SecureRandom secret → otpauth URI → ZXing QR, confirm with a live code |
| TOTP Verify | 6-digit entry, link to backup-code recovery |
| Biometric Gate | BiometricPrompt → session token |
| Dashboard | Live code, Canvas countdown ring, tap to copy, swipe to refresh |
| Backup Codes | 10 hashed single-use codes, .txt export, share, radio-button recovery |
| Login History | ListView + SimpleAdapter, date-range filter, CSV export/import |
| Login Map | Google Map of logins, suspicious-location flag (>100 km) |
| Trusted Devices | List, rename, revoke |
| Profile | Avatar from camera or gallery |
| Settings | Spinners (digits, period, algorithm), auto-lock time picker |
| Help | WebView: local HTML explainer + RFC 6238 page |
| Background | Airplane-mode receiver, screen-off auto-lock |

## Tech stack

- Language: Java (no Kotlin), layouts in XML (no Jetpack Compose)
- minSdk 26, Material Components (Material 3, DayNight)
- Storage: SQLite only (`SQLiteOpenHelper`), no network backend
- Libraries: AppCompat, ConstraintLayout, Material, AndroidX Biometric, ZXing Android Embedded,
  Google Play Services Maps and Location, SwipeRefreshLayout, JUnit 4, AndroidX Test
- Crypto: hand-written Base32 / HOTP / TOTP, `javax.crypto.Mac`, PBKDF2WithHmacSHA256, Android Keystore AES-GCM

## Build and run

1. Open the project folder in Android Studio and let Gradle sync.
2. Create or start an emulator (Pixel, recent Google Play image). Set a screen-lock PIN and add a
   fingerprint under Extended Controls → Fingerprint.
3. Run the `app` configuration, or from a terminal:
   - Build: `./gradlew assembleDebug`
   - Unit tests: `./gradlew test`
   - Instrumented tests: `./gradlew connectedAndroidTest` (emulator running)
4. For the login map, add `MAPS_API_KEY=your_key` to `local.properties` (this file is not committed).

## Team

- Member A: [Member A] (crypto & core)
- Member B: [Member B] (media & files)
- Member C: [Member C] (data & platform)

## Course

- Course: Mobile Application Development (702AI0E002), NMIMS, Mini Project
- Faculty guide: Dr. Preetjot Kaur

The full build plan is in `SECUREAUTH_PLAN.md`.
