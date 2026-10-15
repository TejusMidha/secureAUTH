# Viva guide: five likely questions per module

For each module of SecureAuth Simulator: the **course topic** it covers, the **CO** tag, who should answer, and five questions an examiner is likely to ask, with short answers that match the code. Longer notes (five key points per phase) are in `docs/VIVA_NOTES.md`; the demo order is in `docs/DEMO_SCRIPT.md`.

> **About the CO column.** The CO numbers are the ones written next to each phase in `SECUREAUTH_PLAN.md` (CO1 Phases 5 and 7, CO2 Phases 9, 10 and 13, CO3 Phases 2 and 11, CO4 Phases 15 and 16). The wording of the four course outcomes is in the course policy, which is not in this repository: copy it in next to the numbers before the viva. A dash means the plan gives that module no CO tag.

## The login handshake in one picture (everyone should be able to say this)

**Password → 6-digit code (±1 time step) → fingerprint or screen lock → session.** Any failure stops the flow. Three wrong answers in a row (counted across all three steps) lock the account for five minutes. Every step writes an audit row. A step can only open if the earlier ones really passed (`LoginHandshake`), and every private screen needs a live session.

| # | Module | Main files | Course topic | CO |
|---|---|---|---|---|
| 1 | Foundation and theme | `SplashActivity`, `BaseActivity`, `themes.xml` | U1 setup, U2 screen layout, Lab 1 | — |
| 2 | SQLite layer | `DbHelper`, models, six DAOs | U6 SQLite, Lab 7 CRUD | CO3 |
| 3 | Base32 and the TOTP engine | `Base32`, `TotpEngine` | Self-learning: hand-written TOTP/HMAC | — |
| 4 | Passwords, Keystore, backup-code maker | `PasswordHasher`, `KeystoreHelper`, `BackupCodeGenerator` | Self-learning: PBKDF2, Keystore AES-GCM | — |
| 5 | Register, login and lockout | `RegisterActivity`, `LoginActivity`, `LockoutManager` | U2 controls, check boxes, toasts, dialogs; Lab 3 explicit Intents | CO1 |
| 6 | Enrollment and QR | `EnrollActivity`, `EnrollViewModel` | U3 images; Lab 3 implicit Intents; context menu; ZXing | — |
| 7 | Code step and Dashboard | `TotpVerifyActivity`, `DashboardActivity`, `CountdownRingView` | U2 graphics, touch listener, menus, toasts | CO1 |
| 8 | Fingerprint gate and sessions | `BiometricGateActivity`, `SessionManager` | Self-learning: BiometricPrompt, SQLite sessions | — |
| 9 | Backup codes and recovery | `BackupCodesActivity`, `BackupRecoveryActivity`, `FileExporter` | U3 .txt files, radio buttons, dialogs, implicit Intents | CO2 |
| 10 | Login history and CSV | `LoginHistoryActivity`, `Csv`, `CsvImporter` | Lab 4 ListView + SimpleAdapter, Lab 6 date picker, U3 .csv | CO2 |
| 11 | Location and login map | `LocationHelper`, `LoginMapActivity` | U4 / Lab 8 Google Maps and location | CO3 |
| 12 | Trusted devices | `TrustedDevicesActivity`, `DeviceTrust` | SQLite update/delete, context menu, dialogs | — |
| 13 | Profile: camera and gallery | `ProfileActivity`, `AvatarStore` | Lab 9 camera via implicit Intent, U3 images on the device | CO2 |
| 14 | Settings, Help, receivers | `SettingsActivity`, `HelpActivity`, two receivers | U2 spinner, Lab 6 time picker, Lab 5 WebView, Lab 10 broadcast receiver | — |
| 15 | Release and polish | `build.gradle.kts`, icon, `FLAG_SECURE`, docs | U7 publishing, Material Design | CO4 |
| 16 | Integration and the handshake | `LoginHandshake`, `ProjectRulesTest` | Viva and demo | CO4 |

---

## 1. Foundation and theme
*Topic: U1 setup, U2 screen layout, Lab 1 · CO: —*

1. **What is `BaseActivity` for?** It is the parent of every screen. It holds what all screens share: the worker thread (`runInBackground`), toasts, the lock dialog, the location-permission flow, the session check (`enforceSession`), the broadcast receivers and `FLAG_SECURE`. A fix made once there applies everywhere.
2. **Why ViewBinding and not `findViewById`?** ViewBinding generates a class per layout with a typed field for every view that has an id, so a wrong id or a wrong cast is a compile error, not a crash.
3. **How is dark mode supported?** The theme is `Theme.Material3.DayNight`; colours come from theme attributes (`?attr/colorSecondary`) and a `values-night/colors.xml`, so the same layout is correct in both modes.
4. **Why is the splash screen its own Activity?** The plan makes it the entry point of the demo ("Get started"). It opens Login with an explicit Intent and finishes itself, so Back cannot return to it.
5. **What does `applySystemBarPadding` do?** The app draws edge to edge (behind the status and navigation bars). The method adds padding equal to the system bars and the keyboard, so nothing is hidden behind them.

## 2. SQLite layer
*Topic: U6 SQLite, Lab 7 CRUD · CO: CO3*

1. **Why one `DbHelper` instance?** `SQLiteOpenHelper` serialises access to one database file; two helpers could write at once and lock each other. `getInstance` uses the application context, so no Activity is leaked.
2. **What is a DAO?** A *Data Access Object*: the only class that contains the SQL for one table (insert, get, list, update, delete). Screens never write SQL; they call `userDao.findByUsername(...)`.
3. **How do you prevent SQL injection?** Every value goes through `?` placeholders or `ContentValues`; no SQL text is built by joining user input.
4. **Why `setForeignKeyConstraintsEnabled(true)` and `ON DELETE CASCADE`?** SQLite ignores foreign keys unless switched on per connection. With them on and cascades set, deleting a user removes all their codes, history, devices and sessions, and an orphan row cannot be inserted.
5. **Why an index on `audit_log(user_id, ts)`?** The history screens always filter by user and sort or filter by time; the index makes that a lookup instead of a full scan as the log grows.

## 3. Base32 and the TOTP engine
*Topic: self-learning, hand-written TOTP · CO: —*

1. **Walk through generating a code.** Counter = time ÷ period (30 s). Write the counter as 8 bytes (big-endian). HMAC it with the secret (SHA-1 or SHA-256). *Dynamic truncation*: the low 4 bits of the last byte give an offset; take 4 bytes from there, clear the top bit, then take the number modulo 10^digits and pad with leading zeros.
2. **Why is the top bit cleared?** Java's `int` is signed; clearing bit 31 keeps the 31-bit number positive, as RFC 4226 requires.
3. **Why accept ±1 time step in `verify`?** Phone clocks differ and people type slowly. A code from the previous or next 30 s is accepted, two steps away is not. All three candidates are always computed and compared with `MessageDigest.isEqual`, so timing does not reveal which one matched.
4. **How do you know your engine is right?** The unit tests use the official RFC 6238 Appendix B vectors (SHA-1 and SHA-256, times 59 to 20000000000) and the RFC 4648 Base32 vectors; Google Authenticator also shows the same code for the same secret.
5. **What is Base32 and why does the QR use it?** Every 5 bytes (40 bits) become 8 characters of 5 bits from `A–Z2–7`: letters and digits that are easy to read and type, no confusing `0/O` or `1/l`. `decode` tolerates lower case, spaces and `=` padding because people copy secrets that way.

## 4. Passwords, Keystore and backup-code maker
*Topic: self-learning, PBKDF2 and Keystore AES-GCM · CO: —*

1. **Why PBKDF2 and not SHA-256 for passwords?** SHA-256 is built to be fast, so a stolen database can be attacked at billions of guesses a second. PBKDF2 repeats the HMAC 120,000 times, making every guess slow. The random 16-byte salt makes equal passwords hash differently and defeats precomputed tables.
2. **Why `char[]` for passwords?** A `char[]` can be overwritten (`Arrays.fill(…, '\0')`) as soon as it has been used; a `String` stays in memory until garbage collection.
3. **What does the Android Keystore give you?** An AES-256 key created inside secure storage; the key bytes never enter our app, we only ask it to encrypt or decrypt. A copy of the database is therefore useless without the phone.
4. **What does GCM add over plain AES?** It also authenticates: if the ciphertext or IV is changed by one bit, `decrypt` throws instead of returning garbage. The Keystore picks a fresh random IV every time, which GCM needs (reusing an IV with one key breaks it).
5. **How are backup codes made?** 10 codes `XXXX-XXXX` from a 32-symbol alphabet without `0/O/1/I` (5 bits per character, 40 bits per code), chosen with `SecureRandom.nextInt` (uniform). Only a salted PBKDF2 hash is stored; the plain code is shown once.

## 5. Register, login and lockout
*Topic: U2 controls, check boxes, toasts, dialogs; Lab 3 explicit Intents · CO: CO1*

1. **Which Intent opens the next screen and why that kind?** `new Intent(this, TotpVerifyActivity.class)` is explicit: we name the exact Activity inside our app. We pass the user id as an extra; the receiving screen also checks that the earlier step really passed.
2. **Why the same message for "wrong user" and "wrong password"?** Different messages would tell an attacker which usernames exist. For an unknown name the app still runs a dummy hash, so the answer takes equally long.
3. **How does the lockout work?** Three failures in a row set `locked_until = now + 5 min` in the `users` table, so the lock survives closing the app. While locked, the password is not even checked. An expired lock is cleared first, otherwise the next mistake would lock again at once.
4. **Why is the failure counter NOT reset by a correct password?** If it were, someone who knows the password could log in again and again and get fresh attempts at the 6-digit code. It is reset after the code step succeeds (and again when the whole handshake completes).
5. **Why does the hashing run on a background thread?** PBKDF2 is slow on purpose; on the UI thread it would freeze the screen. `runInBackground` does the work on a worker thread and hands the result back to the UI thread, dropping it if the screen was closed.

## 6. Enrollment and QR
*Topic: U3 displaying images; Lab 3 implicit Intents; context menu; ZXing · CO: —*

1. **What is in the QR code?** An `otpauth://totp/SecureAuth:<user>?secret=<Base32>&issuer=SecureAuth&algorithm=SHA1&digits=6&period=30` link, with the names URL-encoded. The authenticator app reads it and stores the secret.
2. **Why is the secret saved only after the user types a code?** To prove the authenticator really has the secret. Otherwise a failed scan would leave the account protected by a code nobody can produce.
3. **What does the ViewModel do here?** It holds the new secret while the screen is open. After a rotation the Activity is rebuilt but the ViewModel survives, so the QR does not change under the user's feet. It wipes the secret in `onCleared`.
4. **How does "Share QR image" work?** An implicit `ACTION_SEND` Intent with a `content://` Uri from `FileProvider` for a file in `cache/qr/`; the user picks the receiving app. The app asks for confirmation first because the image contains the secret.
5. **What is a context menu and how did you open it?** A menu tied to one view, opened by a long-press after `registerForContextMenu(imageQr)`; the items are "Copy secret" and "Share QR image". The options menu belongs to the whole screen instead.

## 7. Code step and Dashboard
*Topic: U2 graphics, touch listener, menus, toasts · CO: CO1*

1. **How does the countdown ring work?** `CountdownRingView` extends `View` and draws in `onDraw` with a `Canvas`: a faint full circle plus an arc whose sweep is `360 × progress`. The Dashboard computes progress from the clock every second. In the last 5 seconds the paint changes to the error colour.
2. **How does the code refresh exactly when the window rolls over?** A `Handler` posts a `Runnable` that reschedules itself to fire just after the next full second, so the display never lags; the code is regenerated and the text only redrawn when it changed.
3. **Where is the secret kept while the Dashboard is open?** Decrypted in memory only while the screen is visible. `onStop` overwrites it with zeros and removes the Handler callbacks.
4. **What is the touch listener for?** An `OnTouchListener` dims the code on press and restores it on release, for instant feedback; it returns `false` so the normal click (copy to clipboard and Toast) still runs. The clip is marked sensitive on Android 13+.
5. **What does pull-to-refresh do?** `SwipeRefreshLayout` calls `loadData()` again: re-read the account and decrypt the secret.

## 8. Fingerprint gate and sessions
*Topic: self-learning, BiometricPrompt; SQLite sessions · CO: —*

1. **Which authenticators do you ask for?** `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (fingerprint or face, with the screen lock as fallback). On Android 9 and 10 that combination is not supported, so `BIOMETRIC_WEAK` is used there.
2. **What happens if the phone has no fingerprint or lock set?** `BiometricManager.canAuthenticate` says so; the screen explains how to set one up and opens the system settings. The step is never skipped, so the login cannot be completed without it.
3. **What is in the session?** 32 random bytes from `SecureRandom` as URL-safe Base64 (the token). The database stores only its SHA-256 hash with an expiry; the raw token lives in memory, so it disappears if the process dies.
4. **Why is SHA-256 enough for the session token when passwords need PBKDF2?** The token is 256 random bits, so it cannot be guessed, only stolen; the hash only stops a copy of the database from containing a usable token. Passwords are low-entropy and need a slow hash.
5. **How is the session enforced?** Every private screen overrides `requiresSession()` and `BaseActivity.enforceSession()` checks it in `onCreate` and `onResume`: memory token present, not expired, hash still in the table. Otherwise the screen ends the session and opens Login on an empty back stack.

## 9. Backup codes and recovery
*Topic: U3 .txt files, radio buttons, dialogs, implicit Intents · CO: CO2*

1. **Why are the codes shown only once?** Only salted hashes are stored, so the plain code cannot be shown again. A new set replaces the old set in one database transaction (`replaceForUser`), and the old codes stop working.
2. **How do you save the codes to a .txt file without storage permission?** The Storage Access Framework: `ActivityResultContracts.CreateDocument` shows the system file picker; the user chooses the place and Android gives us a Uri that is writable for that one file.
3. **Why can a ViewModel hold the codes?** The plain codes exist only in memory; if a rotation rebuilt the screen while they were showing they would be lost for good. The ViewModel survives rotation and also runs the slow hashing itself.
4. **What are the two recovery paths (RadioGroup)?** "Log in with a backup code" stands in for the 6-digit step; "Set up a new authenticator app" needs a code too, then opens Enroll. Both count wrong codes toward the same 3-strike lockout.
5. **How do you stop a code being used twice by two quick taps?** `markUsed` is a single SQL `UPDATE ... WHERE id = ? AND used = 0` and returns the number of rows changed; only the call that returns 1 wins.

## 10. Login history and CSV
*Topic: Lab 4 ListView + SimpleAdapter, Lab 6 date picker, U3 .csv · CO: CO2*

1. **How does a `SimpleAdapter` work?** You give it a list of `Map`s, a row layout, the map keys to read (`from`) and the view ids to fill (`to`). For each row it puts `map.get(from[i])` into the view `to[i]`. A `ViewBinder` sets the icon's description for screen readers.
2. **Why does the date filter end at 23:59:59.999?** A date picker returns only a day. The last millisecond of that day makes the range inclusive, so entries from the last day are not lost.
3. **How is the CSV written and read?** `Csv` follows RFC 4180: a field containing a comma, quote or line break is wrapped in quotes and inner quotes are doubled; rows end with CRLF. `parse` is a small state machine (inside quotes or not) that also copes with a leading BOM and LF/CR line endings.
4. **What is CSV injection and how is it stopped?** A cell beginning with `=`, `+`, `-` or `@` can run as a formula in a spreadsheet. On export such text gets a leading apostrophe; on import any event name that is not a plain upper-case code is rejected.
5. **What does import check before it stores anything?** File size under 2 MB, at most 50,000 rows, a header with `ts` and `event`, a numeric time, event and result in our own format, coordinates that are real numbers in range and given in pairs. Rows that fail are counted and skipped, as are duplicates (same time and event), and the rest go in one transaction for the logged-in user.

## 11. Location and login map
*Topic: U4 / Lab 8 Google Maps and location · CO: CO3*

1. **Why explain before asking for the permission, and what if the user says no?** Android's own dialog says nothing about why. Our dialog explains first; "no" is respected: logins work, the place is stored as `NULL` and we do not ask again.
2. **How is the position found?** `FusedLocationProviderClient.getCurrentLocation` with high accuracy, once when it is needed (at the password step and again when the fingerprint step succeeds), giving up after 8 seconds so a slow GPS never holds up a login. It is a single lookup each time, not continuous tracking.
3. **How is "far from the last login" decided?** The haversine formula gives the great-circle distance between the two points on a sphere of radius 6371 km; more than 100 km (strictly) is suspicious. The comparison is made before the new row is written, otherwise the login would be compared with itself.
4. **How does the map show the logins?** `SupportMapFragment` plus one `Marker` per entry with a place: green for success, red for failures and suspicious logins; the info window shows the event and the date-time; `LatLngBounds` moves the camera to fit all markers.
5. **Where is the API key and why?** In `local.properties` (git-ignored), injected into the manifest as `${MAPS_API_KEY}` by `manifestPlaceholders` at build time. A key in a public repository can be used (and billed) by anyone; it should also be restricted to this package and certificate.

## 12. Trusted devices
*Topic: SQLite update/delete, context menu, dialogs · CO: —*

1. **How does the app know which row is this phone?** The table has only a name column, which can be renamed, so `DeviceTrust` keeps the row id in SharedPreferences for each user. Renaming changes the name only; the link survives.
2. **How does a context menu differ from the options menu, and how do you know which row was pressed?** The options menu belongs to the screen; a context menu belongs to one view and opens on a long-press. Android passes an `AdapterContextMenuInfo` with the row `position`, which indexes the list we are showing.
3. **What does "revoke" change?** It sets `revoked = 1` (an SQL update, not a delete). If this phone is revoked, then after the 6-digit code a backup code is also required before the fingerprint step.
4. **Why not delete the row instead?** A deleted row would make the phone look like one that was never trusted, which logs in normally. The revoked row keeps the information the login needs, and the user can still see the history.
5. **Why can you not revoke this phone when you have no backup codes?** Every login on it would then need a backup code and the user could never get back in. The app refuses and says "Create backup codes first"; the dialog also warns that each login uses up one code.

## 13. Profile: camera and gallery
*Topic: Lab 9 camera via implicit Intent, U3 images on the device · CO: CO2*

1. **Which Intents are used?** An implicit `ACTION_IMAGE_CAPTURE` (through `ActivityResultContracts.TakePicture`) for the camera, and the system photo picker (`PickVisualMedia`) for the gallery. We only name an action; any camera app may answer.
2. **Why `FileProvider` and not a plain file path?** Since Android 7 giving another app a `file://` path throws `FileUriExposedException`. `FileProvider` makes a `content://` Uri that lets that one app write that one file for that one request, and only folders listed in `file_paths.xml` can be shared.
3. **How is the camera permission handled?** The QR library declares `CAMERA`, so Android refuses to start the camera app until it is granted. We ask at the moment of "Take photo", with our own explanation first; "no" points to the gallery. Phones with no camera do not get the choice.
4. **What happens to the picture?** `AvatarStore` copies it into private storage, turns it upright using the EXIF orientation tag, cuts the biggest centred square and shrinks it to at most 512 px (decoding only a fraction of the pixels first with `inSampleSize`), saves a JPEG and stores the path in `users.avatar_uri`. The old file is deleted.
5. **How is the circular avatar drawn?** A `ShapeableImageView` with a "full corner" shape appearance on the Profile screen, and a `RoundedBitmapDrawable` set circular for the small copy on the Dashboard toolbar.

## 14. Settings, Help and receivers
*Topic: U2 spinner, Lab 6 time picker, Lab 5 WebView, Lab 10 broadcast receiver · CO: —*

1. **Where are settings stored and what protects against bad values?** SharedPreferences (a small key-value file in private storage). `AppSettings` owns the keys and every getter checks its value, so a damaged value falls back to the default.
2. **Why do the Spinner choices only apply to the next enrollment?** The secret already shared with the authenticator was made with the old values; changing them would make the two sides disagree. A warning appears for any choice other than 6 digits, 30 s, SHA-1, and "Set up authenticator again" makes a new secret with the new values.
3. **Why can the auto-lock picker not offer "minutes:seconds"?** `TimePickerDialog` has hours (to 23) and minutes only, so 30 minutes cannot be reached if the hour field means minutes. It is used as hours:minutes (0:01 to 0:30) and anything else is refused.
4. **How is the WebView kept safe?** JavaScript and file access are off; a `WebViewClient` keeps links inside the WebView and lets only our own asset page and `datatracker.ietf.org` over https open, blocking everything else with a message; Back goes through the page history before it closes the screen.
5. **Why is the screen-off receiver registered in code and not in the manifest?** `ACTION_SCREEN_OFF` can only be received by a receiver registered at run time. `BaseActivity` registers it in `onStart` and unregisters in `onStop`, with `RECEIVER_NOT_EXPORTED` (required since Android 14) so other apps cannot send fake broadcasts. When it fires on a protected screen the session ends, `AUTO_LOCK` is logged and Login opens.

## 15. Release and polish
*Topic: U7 publishing, Material Design · CO: CO4*

1. **What do R8 and the resource shrinker do, and how did you check the release build?** They remove unused code and resources and rename what is left (the release APK is 2.2 MB against 9.8 MB for debug). Renaming can break code reached by reflection, so the shrunk build was installed and used: register, enroll, log in, reach the fingerprint prompt. R8 reported no missing keep rules.
2. **What does `FLAG_SECURE` do and where is it used?** It stops screenshots, screen recording and the recent-apps preview of a window. It is on the Dashboard, Enroll and Backup codes screens, and on the dialog that shows the one-time codes (a dialog is its own window).
3. **Why is Android backup switched off?** The TOTP secrets are encrypted with a Keystore key that never leaves the phone, so a restored database could not be used anyway, and the privacy policy promises nothing leaves the device.
4. **How is the app signed for Play, and where is the key?** A key store created once (Android Studio or `keytool`) outside the project; its path and passwords are read from `keystore.properties`, which is git-ignored. With Play App Signing, Google holds the real signing key and the team only holds an upload key that can be reset. See `docs/RELEASE.md`.
5. **What does the privacy policy say?** Data stays on the phone; location is optional; the camera is only for the profile photo; the Maps SDK talks to Google when the map is opened; nothing is backed up; uninstalling deletes everything. The Play data-safety answers are drafted in `docs/play-data-safety.md`.

## 16. Integration and the handshake
*Topic: viva and demo · CO: CO4*

1. **Can the dashboard or a middle step be opened directly?** From outside, no: only the Splash screen is exported, and Android refuses `am start` on any other screen ("not exported"; tried on the emulator). From inside, every private screen needs a live session, and the code, recovery, fingerprint and enroll screens check `LoginHandshake`, which records in memory how far this login got.
2. **What does `LoginHandshake` stop that exported=false does not?** A mistake or a second entry point inside the app that opens a later step without the earlier ones. A step opens only if the previous one was marked passed for the same user within ten minutes; going back to Login, a logout or a finished fingerprint step forgets it. `HandshakeGuardTest` tries to skip each step.
3. **Where can a session be created?** Only in `BiometricGateActivity.completeLogin`, after the fingerprint step succeeded (a project test checks that no other class calls `SessionManager.create`).
4. **What are the honest weaknesses?** A TOTP code can be reused within its time window (the standard says a server should refuse a repeat; this would need a stored "last used step"); someone holding an unlocked, logged-in phone can set up the authenticator again from Settings without a fresh code; an imported CSV row can serve as the "previous login" for the distance check; the Maps key must be restricted; the first-time enrollment after a password alone is only as strong as the password.
5. **How do you know the hard rules of the project still hold?** `ProjectRulesTest` reads the source: no Kotlin or Compose, only the allowed libraries, no OTP library, only `SecureRandom`, no old Activity Result APIs, nothing logged, the secret stored only through the Keystore, every screen classified as session-protected or handshake-guarded. `ReleaseHygieneTest` checks the manifest, the ignore file and the release settings.
