# SecureAuth Simulator: 5-minute demo script

Everything the plan's Phase 16 asks the demo to show, in the order that fits five minutes. The times are a guide: practise it once with a timer. **Say the bold lines out loud; they are what the examiner should remember.**

## Before you start (10 minutes earlier)

| Check | How |
|---|---|
| Phone or emulator has a **screen lock and a fingerprint** | Settings → Security. On the emulator: Extended controls (…) → Fingerprint to "touch" the sensor |
| Date and time are **automatic** | Settings → System → Date & time (a wrong clock makes every code wrong) |
| **Location is on** and the Maps key is in `local.properties` | `MAPS_API_KEY=...` before building, otherwise the map is blank |
| A **second phone with Google Authenticator** (or any authenticator app) | To prove our codes match a real app. No second phone? Type the secret into an authenticator website or app on a laptop |
| A **fresh install** (uninstall first), debug or release build | `adb uninstall com.secureauth.simulator` |
| One earlier login **recorded far from the demo location** | See "Staging the far-away login" below |
| Emulator only: airplane mode and the lock button work from the toolbar / `adb` | `adb shell cmd connectivity airplane-mode enable` (and `disable`) |

### Staging the far-away login (so the warning appears live)

The warning needs an earlier successful login more than 100 km away. Do it once, before the audience arrives:

1. Emulator location: `adb emu geo fix 72.8777 19.0760` (**longitude first**: Mumbai).
2. Register + log in fully once (password → code → fingerprint). Log out.
3. Right before the demo, move the phone: `adb emu geo fix 73.8567 18.5204` (Pune, about 120 km away).

On a real phone, do step 2 in one city and the demo in another, or import a prepared CSV (see "If something goes wrong").

## The script

| Time | What you do | What you say |
|---|---|---|
| **0:00** | Show the Splash screen → **Get started** | "SecureAuth Simulator is a three-factor login: password, a 6-digit code, then fingerprint. **The code engine is written by hand from the standards, no OTP library.**" |
| **0:20** | **Create account**: username, password (show the strength hint turning from weak to strong), tick **Trust this device**, Register | "The password is never stored. Only a salted PBKDF2 hash (120,000 rounds) is kept, and the work runs off the main thread." |
| **0:50** | **Enroll** screen: the QR and the grouped secret. On the second phone, open Google Authenticator → scan the QR | "The QR holds an `otpauth://` link made by our `TotpEngine`. **Google Authenticator and our Dashboard will show the same number, because both follow RFC 6238.**" |
| **1:10** | Tap **I've scanned it**, type the code from Google Authenticator, **Confirm** | "The secret is saved only after a correct code proves the authenticator really has it, and it is stored encrypted with a key from the Android Keystore." |
| **1:25** | Back on Login (username already filled). Type the **password** → (first time only) location explanation → **Allow** | "Wrong passwords count toward a lockout: three in a row and the account is locked for five minutes." |
| **1:40** | **Code step**: type the 6-digit code shown by Google Authenticator; it submits itself at the last digit | "Same counter for wrong codes, and a correct password alone never resets it. A code from the step before or after is accepted too, to forgive clock drift." |
| **1:55** | **Fingerprint** prompt → touch the sensor | "Step three. **Only a successful fingerprint creates the session**, a random 32-byte token held in memory; the database keeps only its hash." |
| **2:05** | **Warning dialog** "Unusual login location" appears on the Dashboard → **View map** → red marker, green marker, tap one to show its info window | "This login is 120 km from the last one, so the app wrote a `SUSPICIOUS_LOCATION` entry using the haversine distance. Green is a normal login, red is a failure or a warning." |
| **2:35** | Back → Dashboard: point at the **countdown ring** and the code; **tap the code** (Toast "Code copied"); pull down to refresh | "The ring is drawn by hand on a Canvas and turns red in the last five seconds. The code refreshes on the second the window rolls over." |
| **2:55** | Menu → **Login history** → **Filter by date** (from, to) → **Clear filter** | "A ListView with a SimpleAdapter. The end date is the last millisecond of the day, so that day is included." |
| **3:20** | History menu → **Export CSV** → pick a place → open the file in a file app or spreadsheet | "Written through the system file picker, so the app asks for no storage permission. The CSV is escaped, and text that looks like a spreadsheet formula is neutralised." |
| **3:40** | Menu → **Backup codes** → **Generate** (read the warning) → show the 10 codes → **Done** ("7 of 10 remaining") | "Shown once; only hashes are stored. Screenshots of this screen are blocked on purpose." |
| **4:00** | **Log out** → password → on the code screen tap **Use a backup code instead** → enter one code → carries on to the fingerprint step | "Lost your authenticator? One backup code stands in for the 6-digit step. Each code works once, and wrong ones count toward the same lockout." |
| **4:25** | Pull down the quick settings and switch on **airplane mode** (or `adb shell cmd connectivity airplane-mode enable`) | "**TOTP needs no network, only the secret and the clock**" (read the Snackbar aloud). |
| **4:40** | Press the **power button** (screen off), wake the phone | "The screen-off receiver ended the session as soon as the screen went dark. **We are back at Login: the app locked itself.**" (This also shows the AUTO_LOCK row in the history.) |
| **4:55** | Close | "Phases 0 to 16: hand-written crypto, SQLite, Keystore, biometrics, maps, CSV, receivers, WebView, camera. Every member can explain their own files." |

## Things worth showing if time allows (30 seconds each)

* **Settings**: change the code length to 8 digits → the red note appears ("set up the authenticator again"); the time picker for auto-lock.
* **Help**: the offline page with the diagram, then the RFC 6238 page loading inside the app; Back walks through the pages.
* **Trusted devices**: long-press → Rename / Revoke. Revoke this phone, log in again: **a backup code is needed on top of the 6-digit code**.
* **Profile**: Take photo or Choose from gallery; the round photo appears on the Dashboard toolbar.
* **Wrong code three times**: the lockout dialog with the countdown "4:59".

## If something goes wrong

| Problem | Quick fix |
|---|---|
| Codes are rejected | Phone clock is wrong: set date and time to automatic. The code is accepted for about ±30 seconds |
| No "Unusual login location" dialog | The previous login was not far enough (more than 100 km) or had no location: re-stage as above, or **Import CSV** a file whose last `SESSION_START` row has `lat,lng` in another city |
| Map is blank (grey grid) | The Maps key is missing or not restricted for this build's certificate (see `docs/RELEASE.md`); the markers and the info windows are still described by the Dashboard dialog |
| Fingerprint prompt does nothing on the emulator | Extended controls → Fingerprint → *Touch the sensor*. Or use the screen-lock PIN: the prompt accepts it |
| Locked out during rehearsal | Wait five minutes, or uninstall and reinstall |
| Authenticator app shows a different code | A setting other than 6 digits / 30 s / SHA-1 was chosen in Settings: set up the authenticator again with the defaults |
