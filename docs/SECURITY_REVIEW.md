# Security review (Phase 16)

A review of the whole app against the hard rules in `SECUREAUTH_PLAN.md` (section 3) and the login handshake **Password → TOTP code (±1 step) → fingerprint → session**. It lists what was checked, every way found to get past a step, what was changed, and what is left as a known limit. Re-checked by automated tests (`ProjectRulesTest`, `ReleaseHygieneTest`, `LoginHandshakeTest`, `HandshakeGuardTest`).

## 1. The hard rules

| # | Rule | Result | How it is checked |
|---|---|---|---|
| 1 | No OTP, TOTP or Base32 library; hand-written engine | ✅ | No such import; the only libraries are the allowed ones (`ProjectRulesTest`); RFC 4648, 4226 and 6238 vectors in the unit tests |
| 2 | Secrets from `SecureRandom` only | ✅ | No `Random`, `Math.random`, `ThreadLocalRandom` in `src/main` |
| 3 | TOTP secret encrypted with an AES-GCM Keystore key before it is stored | ✅ | Only `EnrollActivity` builds an account row, and it calls `KeystoreHelper.encrypt` first |
| 4 | Passwords and backup codes stored only as salted PBKDF2WithHmacSHA256 | ✅ | `PasswordHasher` is the only password hash; `BackupCodeGenerator` uses it; SHA-256 is used in exactly one place (the session token, 256 random bits) |
| 5 | Course lab components used as required | ✅ | ListView + SimpleAdapter, WebView, Spinner, DatePicker and TimePicker dialogs, AlertDialog, Toast, options and context menus, explicit and implicit Intents, BroadcastReceiver |
| 6 | Activity Result APIs, not `startActivityForResult` | ✅ | None of the old APIs in `src/main` |
| 7 | Never log secrets, OTPs, passwords or backup codes | ✅ | No `Log.*`, `System.out` or `printStackTrace` anywhere in `src/main` |
| 8 | Student-readable code | ✅ | Small methods, a comment above every non-obvious block |

## 2. Ways to get past a step

| # | Attempt | Finding | Action |
|---|---|---|---|
| 1 | Start any screen from outside (`adb shell am start`, another app) | **Not possible.** Only `SplashActivity` is exported; Android answers "Permission Denial … not exported" (tried on the emulator for the fingerprint, code, Dashboard and Enroll screens) | none needed; a test counts the exported screens (`ReleaseHygieneTest`) |
| 2 | Open the Dashboard (or any private screen) without logging in | **Blocked.** Seven private screens override `requiresSession()` and check the session in `onCreate` and `onResume`; they take the user from the session, never from an Intent | `ProjectRulesTest` fails if a new screen is added without one of the two protections; `HandshakeGuardTest` opens every private screen with no session and sees Login |
| 3 | Reach the fingerprint step without the code step | **Gap inside the app.** `BiometricGateActivity` believed a user id in its Intent; nothing proved the code step had happened | **Fixed:** `LoginHandshake` (in memory). The fingerprint step opens only after the code step was marked passed for the same user, within 10 minutes |
| 4 | Reach the code step, or backup-code recovery, without the password step | **Gap inside the app** (same reason) | **Fixed:** both check that the password step passed for that user |
| 5 | Open Enroll for any user id and replace their authenticator | **Gap inside the app.** `EnrollActivity` trusted the id in its Intent | **Fixed:** Enroll opens only right after registering that account, after a correct password for it, or from a live session of that same user |
| 6 | Reuse old progress (go back, log out, cancel the fingerprint, then open a later step) | Possible with the old design | **Fixed:** progress is forgotten when Login is shown again, on logout, and when the fingerprint screen finishes; it expires after 10 minutes; one user at a time |
| 7 | Start a session some other way | Only `BiometricGateActivity.completeLogin` creates one | A test checks that no other class calls `SessionManager.create` |
| 8 | Leave the phone unlocked on a private screen | Session lasts the auto-lock time (default 5 min), is checked every second on the Dashboard, and ends at once when the screen turns off | Phase 14 (`ScreenOffReceiver`); `ReceiverFlowTest` |
| 9 | Take codes from a screenshot, screen recording or the recent-apps preview | Blocked on the Dashboard, Enroll and Backup codes screens (and the one-time codes dialog) with `FLAG_SECURE` | Phase 15; `SecureScreenTest` |
| 10 | Guess a 6-digit code or a backup code | One counter for password, code, backup-code and fingerprint failures; 3 in a row lock the account for 5 minutes; the lock survives closing the app; a correct password does not reset the counter | `LockoutManagerTest`, `TotpVerifyFlowTest`, `BackupRecoveryFlowTest` |

## 3. Known limits (left as they are, on purpose)

These are real, small, and explained so nobody is surprised in the viva.

| Limit | Why it is left | What would fix it |
|---|---|---|
| A TOTP code can be used again within its time window (up to about 90 seconds with the ±1 step) | RFC 6238 says a server should refuse a repeat. Doing so needs a stored "last accepted step" per account, and would make "enroll, then log in straight away" fail because both use the same code | Store the last accepted time step and refuse a code that is not newer |
| "Set up authenticator again" in Settings needs only the live session, not a fresh code | The session already needed all three steps and ends in 5 minutes or when the screen turns off | Ask for the current 6-digit code (or a backup code) before opening enrollment |
| First-time enrollment is protected by the password alone | There is no e-mail or phone to prove identity; an account that registered but never enrolled can be completed by whoever knows the password | Require enrollment to be finished before the account is usable; add an out-of-band step |
| An imported CSV row with a place can become the "previous login" for the 100 km check | The import is a user feature and always imports into the logged-in user's own history | Keep imported rows apart from real logins when looking for the previous place |
| The session length is counted from login, not from the last touch | Matches "auto-lock after N minutes" in the plan | Extend the expiry on user activity |
| "Share QR image", "Save as .txt" and "Export CSV" put secrets or history in files the user chooses | They are the user's own exports, each with a warning | Warn again, or encrypt the exports |
| Someone with root access to the phone, or an unlocked phone plus your password plus your fingerprint, can still sign in | Outside what an app can defend against | — |
| The map needs a Maps API key restricted to the release signing certificate | Done in Google Cloud, not in the app | See `docs/RELEASE.md` |
