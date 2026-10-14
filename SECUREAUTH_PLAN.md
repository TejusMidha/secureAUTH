# SecureAuth Simulator — Master Build Plan

> **For Claude Code:** this file is the single source of truth for the project. Read it fully at the
> start of every session. Follow the *Execution protocol* below. Do only the phase you are asked to do.

| | |
|---|---|
| Project | SecureAuth Simulator: multi-factor authentication app with a hand-built TOTP engine |
| Course | Mobile Application Development (702AI0E002), NMIMS, Mini Project (40 marks) |
| Faculty guide | Dr. Preetjot Kaur |
| Project folder | `C:\Users\tejas\AndroidStudioProjects\SecureAuthSimulator` (built locally, pushed to https://github.com/TejusMidha/secureAUTH at the end) |
| Platform | Android, **Java + XML**, **SQLite only**, minSdk 26 |
| Claude Code setting | Sonnet, effort **medium** (switch to **high** for phases marked ⚡) |
| Team | **A** = crypto & core · **B** = media & files · **C** = data & platform |

---

## 1. How to use this file (for the team)

Everything is built and saved **locally** on one PC first. Each phase is saved as a **local git
commit** (no GitHub yet). At the end, Section 10 pushes the whole history to GitHub in one go.

1. Do **Section 4: Manual setup** once.
2. Open a terminal in the project folder and start Claude Code (Sonnet, medium).
3. For each phase, type:
   ```
   Read SECUREAUTH_PLAN.md and do Phase N.
   ```
4. Test the phase on the emulator.
5. Save it as a local commit, credited to the phase owner (Section 9 lists the commands).
6. Use `/clear` (or start a new session) before the next phase.
7. If something breaks, paste the exact error/logcat and say what you clicked. Don't re-run the phase.

**Do not ask Claude Code to "do everything" in one go.** One phase at a time keeps the code
correct, testable, and explainable in the viva. Commits credited to each owner are your
evidence for rubric 4 (Individual contribution).

---

## 2. Execution protocol (for Claude Code)

When asked to do Phase N:
1. Read this whole file. Check the **Progress tracker** (Section 8). If an earlier phase this
   one depends on is not ✅, say so and stop.
2. Inspect the current code relevant to the phase.
3. Post a short plan: the files you'll create or modify and the approach. Then implement it.
4. Follow every rule in **Section 3**. Do not implement features from later phases.
5. Run `./gradlew assembleDebug` and the relevant tests; fix failures until they pass.
6. Update the phase's row in the **Progress tracker**: status ✅ Done (or 🟡 Partial with the reason),
   the date, and a one-line note.
7. Append to `docs/VIVA_NOTES.md` under `## Phase N`: **5 bullets** explaining the key logic in plain
   words, plus 3 likely examiner questions with short answers.
8. Finish your reply with: the files changed, step-by-step manual test instructions for the
   emulator, and the suggested commit message.
9. Do **not** run `git commit`, `git push` or `git remote`. The team commits locally (Section 9).

---

## 3. Project rules & specification

Android mini-project for Mobile Application Development (702AI0E002), NMIMS.
A multi-factor authentication app whose TOTP engine is built from cryptographic
first principles (RFC 6238 / RFC 4226), with no OTP/authenticator library.

### Tech stack (fixed — do not change without asking)
- Language: **Java** (no Kotlin source files). Layouts: **XML** (no Jetpack Compose).
- minSdk 26, targetSdk = latest stable, compileSdk = latest stable.
- UI: Material Components (Material 3 theme, DayNight).
- Storage: **SQLite only** via `SQLiteOpenHelper`. No Room, no Firebase, no network backend.
- Allowed dependencies: `com.google.android.material`, `androidx.appcompat`,
  `androidx.constraintlayout`, `androidx.biometric`, `com.journeyapps:zxing-android-embedded`,
  `com.google.android.gms:play-services-maps`, `com.google.android.gms:play-services-location`,
  `androidx.swiperefreshlayout`, JUnit 4, AndroidX Test.
  **Ask before adding any other dependency.**

### Hard rules
1. **No OTP/TOTP/Base32 libraries.** Base32, HMAC usage (`javax.crypto.Mac`), dynamic truncation
   and TOTP must be written by hand in `crypto/`.
2. Secrets come from `java.security.SecureRandom` only.
3. TOTP secrets are encrypted with an AES-GCM key in the **Android Keystore** before being stored.
4. Passwords and backup codes are stored only as **salted PBKDF2WithHmacSHA256** hashes.
5. Where the course lab requires a specific component, use it exactly:
   ListView + **SimpleAdapter** (login history), **WebView** (help), **Spinner**, **DatePicker/TimePicker
   dialogs**, **AlertDialog**, **Toast**, options menu + context menu, explicit + implicit **Intents**,
   **BroadcastReceiver**.
6. Use the Activity Result APIs (`registerForActivityResult`), not `startActivityForResult`.
7. Never log secrets, OTPs, passwords or backup codes.
8. Keep code student-readable: small methods, clear names, a short comment above every
   non-obvious block explaining *why*. The team must be able to explain every line in a viva.

### Package structure
```
com.secureauth.simulator
├── crypto/    Base32, TotpEngine, PasswordHasher, KeystoreHelper, BackupCodeGenerator
├── data/      DbHelper, model classes, UserDao, TotpAccountDao, BackupCodeDao,
│              AuditLogDao, TrustedDeviceDao, SessionDao
├── ui/        Activities (one per screen) + BaseActivity
├── views/     CountdownRingView
├── io/        FileExporter (.txt/.csv), CsvImporter
├── location/  LocationHelper (FusedLocationProviderClient, distance check)
└── receiver/  AirplaneModeReceiver, ScreenOffReceiver
```

### SQLite schema (database: secureauth.db)
- `users(id INTEGER PK, username TEXT UNIQUE, password_hash TEXT, salt TEXT, avatar_uri TEXT,
  failed_attempts INTEGER DEFAULT 0, locked_until INTEGER DEFAULT 0, created_at INTEGER)`
- `totp_accounts(id PK, user_id FK, secret_enc TEXT, iv TEXT, issuer TEXT, digits INTEGER,
  period INTEGER, algo TEXT, created_at INTEGER)`
- `backup_codes(id PK, user_id FK, code_hash TEXT, salt TEXT, used INTEGER DEFAULT 0, used_at INTEGER)`
- `audit_log(id PK, user_id FK, event TEXT, result TEXT, lat REAL, lng REAL, ts INTEGER)`
- `trusted_devices(id PK, user_id FK, device_name TEXT, added_at INTEGER, revoked INTEGER DEFAULT 0)`
- `sessions(id PK, user_id FK, token_hash TEXT, created_at INTEGER, expires_at INTEGER)`
All timestamps are epoch milliseconds. Foreign keys ON.

### Login handshake (must never be bypassable)
Password → TOTP code (±1 time-step window) → BiometricPrompt → session token.
Any failure stops the flow. 3 consecutive failures → 5-minute lockout + dialog.
Every step writes an `audit_log` row.

### Version control (local first)
- The project is a **local git repository** only. No remote is added until Section 10.
- One commit per phase, credited to the phase owner. Message format: `P<N>: <what changed>`.
- Never commit `local.properties`, `keystore.properties`, keystores (`*.jks`, `*.keystore`),
  API keys or build outputs. `.gitignore` must exclude them.
- After each phase, append an entry to `docs/CONTRIBUTIONS.md`
  (date · phase · owner · what was built · problem faced · how it was solved · who helped).

### Commands
- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew test`
- Instrumented tests: `./gradlew connectedAndroidTest` (emulator running)

### Working style for every task
- First give a short plan (files to create/modify), then implement.
- After implementing: run the build and the relevant tests and fix failures.
- End with: files changed, how to test it manually on the emulator, and
  **5 bullet "viva notes"** explaining the key logic in plain words.
- Do not run `git commit`, `git push` or `git remote`; the team commits locally.
- Never introduce Flutter, Dart, Kotlin or Compose code.

---

## 4. Manual setup (done by a person, not Claude)

1. Rename the old `secureAUTH` folder to `secureAUTH_old` as a backup. Don't delete it.
2. Android Studio → **New Project → Empty Views Activity**
   - Name `SecureAuth Simulator` · package **`com.secureauth.simulator`**
   - Save location `C:\Users\tejas\AndroidStudioProjects\SecureAuthSimulator`
   - Language **Java** · Minimum SDK **API 26** · Build configuration: Kotlin DSL
3. Run it once on an emulator: a Pixel device with a recent Google Play system image. Set a
   screen-lock PIN, then add a fingerprint under Extended Controls → Fingerprint.
4. Copy this file into the project folder as `SECUREAUTH_PLAN.md`.
5. Before Phase 11 (Maps): create a Google Cloud project, enable **Maps SDK for Android**, set up
   billing (required to get a key), and create an API key restricted to Android apps.
   Put it in `local.properties` as `MAPS_API_KEY=...`.

---

## 5. Feature summary (what the finished app does)

**Login handshake:** Password → TOTP code (±1 window) → Biometric → Session.
Any failure stops the flow. 3 failures in a row trigger a 5-minute lockout.

| Screen | Function | Course topics |
|---|---|---|
| Splash | Entry, "Get started" | Images, multiscreen |
| Register / Login | Material fields, "Trust this device" check box, lockout dialog | Controls, check boxes, toasts, dialogs, explicit intents |
| Enroll | SecureRandom secret → otpauth URI → ZXing QR, confirm with a live code | Displaying images, context menu, implicit intent (share) |
| TOTP Verify | 6-digit entry, link to backup-code recovery | Controls, dialogs |
| Biometric Gate | BiometricPrompt → session token | Self-learning |
| Dashboard | Live code, Canvas countdown ring, tap to copy, swipe to refresh | Graphics, touch listener, options menu |
| Backup Codes | 10 hashed single-use codes, .txt export, share, radio-button recovery | .txt file handling, radio buttons |
| Login History | ListView + SimpleAdapter, date-range filter, CSV export/import | Lab 4, Lab 6, .csv file handling |
| Login Map | Google Map of logins, suspicious-location flag (>100 km) | Unit 4, Lab 8 |
| Trusted Devices | List, rename, revoke | SQLite update/delete, context menu |
| Profile | Avatar from camera or gallery | Lab 9, images on device |
| Settings | Spinners (digits, period, algorithm), auto-lock time picker | Spinner, time picker |
| Help | WebView: local HTML explainer + RFC 6238 page | Lab 5 |
| Background | Airplane-mode receiver, screen-off auto-lock | Lab 10 |

**Self-learning concepts (rubric 2):** hand-written TOTP/HMAC/Base32 · Android Keystore AES-GCM ·
BiometricPrompt · ZXing QR generation · PBKDF2 salted hashing.

**Rubric map (40 marks):**
- Originality (5): no OTP library; auditable, offline-only MFA; suspicious-login detection.
- Self-learning (5): the five concepts above.
- Workable app (10): Phase 15 (Material 3, release AAB, privacy policy).
- Individual contribution (10): one commit per phase credited to its owner, `docs/CONTRIBUTIONS.md`.
- Viva/demo (10): Phase 16 demo script + `docs/VIVA_NOTES.md`.

**Academic integrity and GenAI:** every member must be able to explain the code they own.
Log AI use in `docs/GENAI_USAGE.md`.

---

## 6. Phase 0 — Project bootstrap
- **Owner:** Member A · **Effort:** medium

**Tasks**
1. Create `CLAUDE.md` in the project root containing only:
   `Read SECUREAUTH_PLAN.md fully before doing anything. It contains all project rules, the
   execution protocol and the phase list. Never add Flutter, Dart, Kotlin or Compose code.`
2. Replace `.gitignore` with a complete Android Studio ignore list: build/, .gradle/, .idea/
   (except shared code style), *.iml, local.properties, keystore.properties, captures/, *.jks,
   *.keystore, *.apk, *.aab, .DS_Store.
3. Create `README.md`: project name, one-paragraph description, the feature list from
   Section 5, tech stack, build/run steps, team placeholders [Member A/B/C], faculty guide,
   course code.
4. Create `docs/CONTRIBUTIONS.md` with the table header:
   `Date | Phase | Owner | What was built | Problem faced | How it was solved | Helped by`
5. Create `docs/GENAI_USAGE.md` (log template: date, tool, prompt summary, what we verified
   or changed ourselves) and `docs/VIVA_NOTES.md` (title only).
6. Run `git init -b main` in the project root if it is not already a git repository
   (do not add a remote). Then run `git status` and confirm no ignored secret files are listed.
7. Confirm `./gradlew assembleDebug` passes. Mark Phase 0 ✅ in the tracker.

---

## 7. Phases 1–16

### Phase 1 — Project foundation
- **Owner:** C · **Effort:** medium
- **Course topics covered:** U1 setup, U2 screen layout, Lab 1

**Tasks**
Set up the project foundation:
1. Add the allowed dependencies from Section 3 to the version catalog / app build.gradle.kts
   (use current stable versions). Enable viewBinding.
2. Create the empty package structure from Section 3 (a placeholder class is fine where needed).
3. Set up a Material 3 DayNight theme with a security-style palette (deep navy primary,
   teal secondary), and a consistent text-appearance set in themes.xml / colors.xml.
4. Create ui/BaseActivity extending AppCompatActivity that all screens will extend later.
5. Replace MainActivity with ui/SplashActivity (logo text + "Get started" button) as the launcher.
Make sure ./gradlew assembleDebug passes.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 1".

---

### Phase 2 — SQLite layer
- **Owner:** C · **Effort:** medium
- **Course topics covered:** U6 SQLite, Lab 7 (CRUD), CO3

**Tasks**
Implement the full SQLite layer described in Section 3:
1. data/DbHelper (SQLiteOpenHelper, singleton) creating all 6 tables with foreign keys ON
   and an index on audit_log(user_id, ts).
2. A plain Java model class for each table.
3. One DAO per table with insert / getById / list / update / delete as relevant, plus:
   - UserDao: findByUsername, incrementFailedAttempts, resetFailedAttempts, setLockedUntil
   - BackupCodeDao: listUnused(userId), markUsed(id)
   - AuditLogDao: listForUser(userId), listForUserBetween(userId, fromTs, toTs)
   - SessionDao: findValid(userId, tokenHash, now), deleteExpired(now)
4. Use parameterised queries only (no string concatenation of values).
5. Add instrumented tests in androidTest that exercise insert/select/update/delete for
   users and audit_log on an in-memory database.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 2".

---

### Phase 3 — Base32 + TOTP engine
- **Owner:** A · **Effort:** **high** ⚡
- **Course topics covered:** Self-learning: hand-written TOTP/HMAC/Base32

**Tasks**
Implement the TOTP engine from first principles in crypto/ — no OTP or Base32 libraries.
1. Base32: encode(byte[]) and decode(String) per RFC 4648 (uppercase, no padding on encode,
   tolerate padding/spaces/lowercase on decode, reject invalid chars).
2. TotpEngine:
   - generateSecret(): 20 random bytes from SecureRandom
   - generate(byte[] secret, long unixTimeMillis, int digits, int periodSeconds, String algo)
     supporting HmacSHA1 / HmacSHA256, implementing HOTP dynamic truncation (RFC 4226 §5.3)
     step by step with comments for each step.
   - verify(secret, code, now, digits, period, algo) accepting ±1 time step; use a
     constant-time comparison.
   - secondsRemaining(now, period)
   - buildOtpAuthUri(issuer, accountName, base32Secret, digits, period, algo) with proper URL encoding.
3. JUnit tests in src/test:
   - RFC 6238 Appendix B test vectors for SHA1 and SHA256 (8 digits, times 59, 1111111109,
     1111111111, 1234567890, 2000000000, 20000000000)
   - Base32 round-trip and RFC 4648 test vectors
   - verify() accepts previous/next window and rejects 2 windows away
Run ./gradlew test and make all tests pass. In the viva notes, walk through the truncation
with a worked numeric example.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 3".

---

### Phase 4 — Password hashing + Keystore encryption
- **Owner:** A · **Effort:** **high** ⚡
- **Course topics covered:** Self-learning: Keystore AES-GCM, PBKDF2

**Tasks**
Implement in crypto/:
1. PasswordHasher: PBKDF2WithHmacSHA256, 16-byte random salt, 120000 iterations, 256-bit
   output; hash(char[] password) returns hash+salt (Base64), verify() uses constant-time
   comparison. Clear the char[] after use.
2. KeystoreHelper: creates/loads an AES-256 GCM key with alias "secureauth_totp_key" in
   AndroidKeyStore; encrypt(byte[]) returns ciphertext + IV (Base64), decrypt(ciphertext, iv).
3. BackupCodeGenerator: 10 codes in format XXXX-XXXX from an unambiguous alphabet
   (no 0/O/1/I), each returned in plain text once and stored hashed with PasswordHasher.
4. Unit tests for PasswordHasher and BackupCodeGenerator; an instrumented test for
   KeystoreHelper encrypt→decrypt round-trip.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 4".

---

### Phase 5 — Register & password login
- **Owner:** A · **Effort:** medium
- **Course topics covered:** U2 simple controls, check boxes, toasts, pop-up dialogs, multiscreen, Lab 3 explicit intents, CO1

**Tasks**
Build ui/RegisterActivity and ui/LoginActivity (Material TextInputLayouts, extend BaseActivity).
Register: username (unique, 3–20 chars), password (min 8 chars, show strength hint),
confirm password, CheckBox "Trust this device". Save with PasswordHasher via UserDao.
Login: username + password. On failure increment failed_attempts; at 3 failures set
locked_until = now + 5 min and show an AlertDialog with the remaining lock time.
On success reset attempts and go (explicit Intent, pass userId) to a placeholder
TotpVerifyActivity. Write audit_log rows: REGISTER, PASSWORD_OK, PASSWORD_FAIL, LOCKED_OUT.
Use Toasts for short feedback, inline errors on fields for validation.
SplashActivity "Get started" → Login; Login has a "Create account" link → Register.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 5".

---

### Phase 6 — TOTP enrollment with QR
- **Owner:** B · **Effort:** medium
- **Course topics covered:** U3 displaying images, Lab 3 implicit intents, context menu, self-learning: ZXing

**Tasks**
Build ui/EnrollActivity, shown right after registration (and reachable later from Dashboard).
1. Generate a secret with TotpEngine, encrypt with KeystoreHelper, save via TotpAccountDao
   (defaults: SHA1, 6 digits, 30 s — needed for Google Authenticator compatibility).
2. Build the otpauth:// URI and render it as a QR Bitmap with ZXing (BarcodeEncoder) in an ImageView.
3. Show the Base32 secret in groups of 4 under the QR with a "Copy" button, for manual entry.
4. "I've scanned it" → ask the user to type the current code to confirm enrollment; only mark
   enrollment complete if TotpEngine.verify passes.
5. Long-press on the QR opens a context menu: "Copy secret", "Share QR image" (implicit
   ACTION_SEND intent via FileProvider).
Audit events: TOTP_ENROLLED, ENROLL_VERIFY_FAIL.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 6".

---

### Phase 7 — TOTP verify screen + Dashboard
- **Owner:** A · **Effort:** medium
- **Course topics covered:** U2 graphics, touch listener, menus, toasts, CO1

**Tasks**
1. ui/TotpVerifyActivity: 6-digit input (number keyboard, auto-submit at 6 digits), verifies
   against the decrypted secret, counts toward the same 3-strike lockout. Link: "Use a backup
   code instead" (placeholder for now). On success → BiometricGateActivity placeholder.
2. views/CountdownRingView: custom View drawing an arc with Canvas that shrinks over the period,
   changing color in the last 5 seconds; exposes setProgress(float).
3. ui/DashboardActivity: shows the live code (formatted "123 456"), the ring, and the issuer.
   Update every second with a Handler; regenerate the code when the window rolls over.
   - Tap on the code (OnTouchListener / click) copies it to the clipboard + Toast.
   - SwipeRefreshLayout to force refresh.
   - Options menu: Login history, Backup codes, Trusted devices, Profile, Settings, Help, Logout.
Audit events: TOTP_OK, TOTP_FAIL.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 7".

---

### Phase 8 — Biometric gate + sessions
- **Owner:** C · **Effort:** medium
- **Course topics covered:** Self-learning: BiometricPrompt, SQLite sessions

**Tasks**
1. ui/BiometricGateActivity using androidx.biometric BiometricPrompt (BIOMETRIC_STRONG with
   DEVICE_CREDENTIAL fallback). Handle: no hardware, not enrolled (dialog explaining how to
   enroll; on emulator: Extended Controls → Fingerprint), errors, cancellation (back to login).
2. On success create a session: random 32-byte token, store only its SHA-256 hash in
   sessions with expires_at = now + auto-lock minutes (default 5); keep the raw token in a
   SessionManager singleton in memory. Then open DashboardActivity and clear the back stack.
3. BaseActivity checks SessionManager on onResume for protected screens; if missing/expired,
   send the user to LoginActivity.
4. Logout deletes the session row and clears memory.
Audit events: BIOMETRIC_OK, BIOMETRIC_FAIL, SESSION_START, LOGOUT, SESSION_EXPIRED.
The full handshake Password → TOTP → Biometric → Dashboard must now work end to end.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 8".

---

### Phase 9 — Backup codes & recovery
- **Owner:** B · **Effort:** medium
- **Course topics covered:** U3 .txt file handling, radio buttons, dialogs, implicit intents, CO2

**Tasks**
1. ui/BackupCodesActivity: "Generate new codes" (confirmation AlertDialog warns old codes
   become invalid), shows the 10 codes ONCE in a dialog, with buttons:
   - "Save as .txt" → io/FileExporter writes backup_codes.txt using the Storage Access
     Framework (ACTION_CREATE_DOCUMENT), no storage permission needed.
   - "Share" → implicit ACTION_SEND text intent.
   Shows "X of 10 codes remaining".
2. Recovery: from TotpVerifyActivity "Use a backup code instead" → screen with RadioGroup
   (Backup code / Re-enroll authenticator). Backup code path verifies against unused hashes,
   marks the matched code used, then continues to the biometric gate. Re-enroll path requires
   a backup code too, then opens EnrollActivity.
Audit events: BACKUP_CODES_GENERATED, BACKUP_CODE_USED, BACKUP_CODE_FAIL.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 9".

---

### Phase 10 — Login history, date filter, CSV
- **Owner:** C + B for CSV · **Effort:** medium
- **Course topics covered:** Lab 4 ListView + SimpleAdapter, Lab 6 date picker, U3 .csv file handling, CO2

**Tasks**
1. ui/LoginHistoryActivity: ListView with SimpleAdapter (required by the lab), row layout with
   an icon (success/fail), event name, formatted date-time, and "lat, lng" or "No location".
2. A "Filter by date" button opens DatePickerDialog (from and to) → AuditLogDao.listForUserBetween.
   A "Clear filter" chip.
3. Options menu: "Export CSV" → io/FileExporter writes audit_log.csv (header row, proper CSV
   escaping) via ACTION_CREATE_DOCUMENT; "Import CSV" → io/CsvImporter reads a file chosen with
   ACTION_OPEN_DOCUMENT, validates columns, and inserts rows (skip duplicates by ts+event).
   Show a Toast with how many rows were imported/skipped.
4. Unit tests for the CSV escape/parse logic.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 10".

---

### Phase 11 — Location + login map
- **Owner:** C · **Effort:** medium
- **Course topics covered:** U4 / Lab 8 Google Maps location tracking, CO3

**Tasks**
1. location/LocationHelper: request ACCESS_FINE/COARSE_LOCATION at runtime with a rationale
   dialog; get the current location once using FusedLocationProviderClient.getCurrentLocation.
   If permission is denied, the login still works and lat/lng are stored as null.
2. Attach the location to PASSWORD_OK and SESSION_START audit rows.
3. Suspicious login: if the new location is more than 100 km from the last successful login,
   write a SUSPICIOUS_LOCATION audit row and show a warning AlertDialog after login.
4. ui/LoginMapActivity (SupportMapFragment): a marker per located login; green for success,
   red for failures/suspicious; info window shows date-time and event. Camera fits all markers.
   Open it from LoginHistory's options menu.
5. Read the Maps API key from local.properties (MAPS_API_KEY) via manifestPlaceholders —
   never hard-code it. Tell me exactly where to paste my key.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 11".

---

### Phase 12 — Trusted devices
- **Owner:** C · **Effort:** medium
- **Course topics covered:** SQLite update/delete, context menu, dialogs

**Tasks**
ui/TrustedDevicesActivity: list of trusted_devices for the user (ListView). The current device
is added automatically at registration/login if "Trust this device" was checked, named with
Build.MANUFACTURER + Build.MODEL. Long-press → context menu "Rename" (dialog with EditText)
and "Revoke" (confirmation AlertDialog, sets revoked=1). If the current device is revoked,
the next login must require a backup code in addition to TOTP.
Audit events: DEVICE_TRUSTED, DEVICE_RENAMED, DEVICE_REVOKED.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 12".

---

### Phase 13 — Profile: camera & gallery
- **Owner:** B · **Effort:** medium
- **Course topics covered:** Lab 9 camera via implicit intent, U3 images stored on device, CO2

**Tasks**
ui/ProfileActivity: shows username, member-since date, enrolled issuer, and a circular avatar.
"Change photo" opens a dialog: "Take photo" (implicit MediaStore.ACTION_IMAGE_CAPTURE intent
with a FileProvider URI via ActivityResultContracts.TakePicture) or "Choose from gallery"
(ActivityResultContracts.PickVisualMedia). Copy the chosen image into app-internal storage,
downscale it, save the path in users.avatar_uri, and show it on the Dashboard toolbar too.
Handle camera permission and cancellation cleanly.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 13".

---

### Phase 14 — Settings, Help WebView, broadcast receivers
- **Owner:** A + B + C · **Effort:** medium
- **Course topics covered:** U2 spinner, Lab 6 time picker, Lab 5 WebView, Lab 10 broadcast receiver

**Tasks**
1. ui/SettingsActivity (A): Spinners for digits (6/8), period (30/60 s), algorithm
   (SHA1/SHA256) — show a warning that non-default values may not work in Google
   Authenticator and require re-enrollment; TimePickerDialog for auto-lock duration
   (minutes:seconds, 1–30 min); CheckBox "Record login location". Persist in SharedPreferences.
2. ui/HelpActivity (B): WebView with two tabs/buttons — static HTML from assets/help.html
   explaining how TOTP works (with a simple diagram in HTML/CSS) and the remote page
   https://datatracker.ietf.org/doc/html/rfc6238 (INTERNET permission, WebViewClient so it
   stays in-app, progress bar, back navigation inside WebView).
3. Receivers (C): receiver/AirplaneModeReceiver shows a Snackbar/Toast "Offline: TOTP still
   works — it only needs the clock" when airplane mode turns on; receiver/ScreenOffReceiver
   ends the session when the screen turns off. Register both dynamically in BaseActivity
   (onStart/onStop) with the correct Android 14 export flags. Audit event: AUTO_LOCK.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 14".

---

### Phase 15 — Polish & Play-Store readiness
- **Owner:** all · **Effort:** medium
- **Course topics covered:** U7 publishing, rubric 3 (Play Store + Material Design), CO4

**Tasks**
Review the whole app for release readiness:
1. Consistent Material 3 styling on every screen, dark mode check, content descriptions on
   images/buttons, touch targets ≥ 48dp, no hard-coded strings (move all to strings.xml).
2. Adaptive launcher icon (shield + key motif) as vector drawables.
3. Enable R8 minify + resource shrinking for release with keep rules where needed
   (ZXing, biometric); make sure the release build runs.
4. FLAG_SECURE on screens that show codes or secrets (Dashboard, Enroll, Backup codes).
5. Write a privacy policy as docs/privacy-policy.md (data stays on device, location optional,
   camera only for avatar) and a draft of the Play Store data-safety answers.
6. Walk me through creating a signing key and generating a signed release AAB in Android Studio.
List anything you found and fixed.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 15".

---

### Phase 16 — Integration test & viva prep
- **Owner:** all · **Effort:** **high** ⚡
- **Course topics covered:** Rubric 5 viva/demo, CO4

**Tasks**
1. Review the entire codebase against the hard rules in Section 3 and the login handshake; list
   any way to bypass a step (e.g. launching DashboardActivity directly) and fix it.
2. Write a 5-minute demo script covering: register → enroll (scan with Google Authenticator and
   show both apps give the same code) → logout → login with password → TOTP → biometric → dashboard → login
   history + date filter → CSV export → map with a suspicious login → backup-code recovery →
   airplane mode toast → screen-off auto-lock.
3. Produce docs/VIVA.md: for each module, 5 likely examiner questions with short answers,
   plus which course topic and CO it covers.

**Done when:** build passes, tests for this phase pass, the feature works on the emulator,
progress tracker updated, viva notes written to `docs/VIVA_NOTES.md` under "Phase 16".

---

## 8. Progress tracker (Claude Code updates this after every phase)

| Phase | Name | Status | Date | Notes |
|---|---|---|---|---|
| 0 | Project bootstrap | ✅ Done | 2026-10-14 | CLAUDE.md, .gitignore, README, docs/ created; git init (main, no remote); assembleDebug passes |
| 1 | Project foundation | ✅ Done | 2026-10-14 | Deps + viewBinding, package skeleton, navy/teal M3 theme, BaseActivity, SplashActivity launcher; build + unit tests pass; Espresso test compiled, run it on the emulator |
| 2 | SQLite layer | ✅ Done | 2026-10-14 | DbHelper + 6 models + 6 DAOs (parameterised); 23 instrumented tests pass on emulator (15 new on in-memory DB) |
| 3 | Base32 + TOTP engine | ✅ Done | 2026-10-14 | Hand-written Base32 + TotpEngine; 26 JVM unit tests (all RFC 6238 SHA1/SHA256 vectors, RFC 4648 vectors, ±1 window) + 1 on-device vector test pass |
| 4 | Password hashing + Keystore encryption | ✅ Done | 2026-10-14 | PasswordHasher, KeystoreHelper, BackupCodeGenerator; 15 new JVM tests + 6 Keystore tests on emulator pass; PBKDF2 checked against Python |
| 5 | Register & password login | ✅ Done | 2026-10-14 | Register + Login + 3-strike lockout + audit rows; placeholder TotpVerifyActivity; 5 JVM + 14 emulator tests pass. 'Trust this device' box is collected but stored in Phase 12 |
| 6 | TOTP enrollment with QR | ✅ Done | 2026-10-14 | EnrollActivity: QR + grouped secret + copy + confirm-with-code + context menu (copy / share via FileProvider). Secret saved only after a valid code. 12 new emulator tests incl. decoding the displayed QR. Not yet linked from Dashboard (Phase 7) |
| 7 | TOTP verify screen + Dashboard | ✅ Done | 2026-10-14 | TotpVerify (auto-submit, shared 3-strike lock), CountdownRingView, Dashboard (live code, tap-to-copy, swipe refresh, menu). BiometricGate is a placeholder with a TEMPORARY button to the Dashboard (remove in Phase 8). Menu items other than Logout are toasts until their phases. 20 new emulator tests |
| 8 | Biometric gate + sessions | ✅ Done | 2026-10-14 | BiometricPrompt (STRONG + screen-lock fallback), SessionManager (32-byte token, SHA-256 hash in DB), protected-screen check in BaseActivity, logout/expiry end the session. Verified end to end on the emulator with a real system prompt (temporary PIN, since removed). Fingerprint touch itself not exercised: the AVD has no enrolled fingerprint. Temporary Dashboard button removed |
| 9 | Backup codes & recovery | ✅ Done | 2026-10-14 | BackupCodesActivity (generate with warning, codes shown once, Save as .txt via SAF, Share, X of 10 remaining), BackupRecoveryActivity (RadioGroup: log in / re-enroll, needs a code, shared 3-strike lock), FileExporter. Verified on the emulator incl. the real file picker and a real code reaching the biometric step. A new user has no codes until they generate them from the Dashboard menu |
| 10 | Login history, date filter, CSV | ✅ Done | 2026-10-14 | LoginHistoryActivity (ListView + SimpleAdapter, From/To DatePickerDialogs, Clear filter chip), CSV export/import via the system file picker, Csv/CsvImporter with validation; 37 new JVM tests, 26 new emulator tests; checked on the emulator with the real picker (export, import with duplicate + bad row). Export writes the rows currently shown. No map yet (Phase 11) |
| 11 | Location + login map | ✅ Done | 2026-10-14 | LocationHelper (permission explained first, optional), place stored on PASSWORD_OK/PASSWORD_FAIL/SESSION_START, SUSPICIOUS_LOCATION when >100 km from the previous login + warning dialog, LoginMapActivity (green/red markers, info window, fit-all camera). Maps key via manifestPlaceholders from local.properties. Real GPS lookup and the 120 km warning verified on the emulator. Map TILES/markers are not visible until you paste a real MAPS_API_KEY |
| 12 | Trusted devices | ⬜ Not started | | |
| 13 | Profile: camera & gallery | ⬜ Not started | | |
| 14 | Settings, Help WebView, broadcast receivers | ⬜ Not started | | |
| 15 | Polish & Play-Store readiness | ⬜ Not started | | |
| 16 | Integration test & viva prep | ⬜ Not started | | |

---

## 9. Saving each phase locally (after testing)

One-time setup on the PC (use the repo owner's details):
```
git config user.name  "Tejus Midha"
git config user.email "<your GitHub email>"
```
After each phase, commit it credited to the **phase owner** (use their GitHub email so it links
to their profile later):
```
git add .
git status          (check: no .jks, keystore.properties or local.properties listed)
git commit -m "P3: TOTP engine + RFC tests" --author="Member Name <member@email.com>"
```
`git log --oneline` shows the history. Nothing goes online yet.

## 10. Pushing to GitHub (once, when the app is ready)

The GitHub repo `TejusMidha/secureAUTH` is empty, so the whole local history can be pushed directly:
```
git remote add origin https://github.com/TejusMidha/secureAUTH.git
git push -u origin main
```
Then add the teammates as collaborators on GitHub. Each member's commits will show under
their name if the `--author` email matches their GitHub account email.

---

## 11. Final demo checklist (week 14–15)
- [ ] Fresh install on the demo phone; fingerprint enrolled; location on; date and time set to automatic
- [ ] Register → enroll → scan with Google Authenticator → same code on both apps
- [ ] Logout → password → TOTP → biometric → dashboard (show the countdown ring)
- [ ] Wrong code 3 times → lockout dialog
- [ ] Login history → date filter → CSV export → open the file
- [ ] Login map with markers; suspicious-login example ready
- [ ] Backup-code recovery path
- [ ] Airplane mode toast; screen off → auto-lock
- [ ] Help WebView; settings spinners; profile photo from camera
- [ ] Every member presents and explains their own phases (use `docs/VIVA_NOTES.md`)
