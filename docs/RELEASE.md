# Release guide: signing key, signed AAB, checks

How to turn this project into a signed Android App Bundle (`.aab`) that can be uploaded to Google Play. Do this once; the key you create must be kept for the whole life of the app.

## 0. What is already set up

| Item | State |
|---|---|
| R8 code shrinking + resource shrinking for `release` | On (`app/build.gradle.kts`, flag `android.r8.gradual.support=true` in `gradle.properties`) |
| Keep rules | `app/src/main/keepRules/rules.keep` (none needed so far: the release build shows no missing-rule warnings) |
| Signing | Read from a file called `keystore.properties` in the project root. **That file and any `*.jks` / `*.keystore` are git-ignored: never commit them** |
| Without `keystore.properties` | The release build still works, it is just **unsigned** (fine for testing, useless for Play) |
| Maps key | `MAPS_API_KEY` in `local.properties` (git-ignored) |
| Version | `versionCode = 1`, `versionName = "1.0"` in `app/build.gradle.kts`. **Raise `versionCode` by one for every upload** |

## 1. Create the signing key (one time)

### Option A — Android Studio (recommended for beginners)

1. Menu **Build → Generate Signed App Bundle / APK…**
2. Choose **Android App Bundle**, click **Next**.
3. Next to *Key store path* click **Create new…**
4. Fill in the dialog:
   * **Key store path:** a folder **outside the project**, for example `C:\Users\<you>\keys\secureauth-release.jks` (a place that is backed up, but not inside Git).
   * **Password** and **Confirm:** a long password. This is the *store* password.
   * **Alias:** `secureauth`
   * **Key password:** the same or another long password.
   * **Validity (years):** `25` or more (Play requires the key to be valid until at least 2033).
   * **Certificate:** your name (First and Last Name) and country code. The rest may stay empty.
5. Click **OK**. Studio fills the form in; you may stop here (do not need to finish building from this dialog), or continue to step 3 below.

### Option B — command line

```
keytool -genkeypair -v -keystore C:\Users\<you>\keys\secureauth-release.jks ^
        -alias secureauth -keyalg RSA -keysize 2048 -validity 9125
```

`keytool` ships with the JDK that comes with Android Studio (`<Android Studio>\jbr\bin\keytool.exe`).

### Keep the key safe

* **Back it up** (two places, for example a password manager and an encrypted USB stick). If you lose the key you can no longer update the app, unless you use *Play App Signing* (below) and ask Google to reset the upload key.
* **Never** commit it, e-mail it, or paste its passwords into chat or code.

## 2. Tell Gradle where the key is

Create `keystore.properties` in the project root (next to `settings.gradle.kts`). It is already in `.gitignore`.

```
storeFile=C:/Users/<you>/keys/secureauth-release.jks
storePassword=<the store password>
keyAlias=secureauth
keyPassword=<the key password>
```

Use forward slashes (`/`) in `storeFile`.

## 3. Build the signed bundle

**In Android Studio:** Build → Generate Signed App Bundle / APK… → Android App Bundle → choose the key store (or leave the form filled in) → select the **release** build variant → Finish.

**Or on the command line:**

```
gradlew bundleRelease
```

Output:

* Bundle: `app/build/outputs/bundle/release/app-release.aab` ← upload this to Google Play
* R8 mapping: `app/build/outputs/mapping/release/mapping.txt` ← upload this too (Play Console → App bundle explorer → Downloads / or "Upload deobfuscation file") so crash stack traces are readable
* A test APK: `gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed when `keystore.properties` exists)

Check the signature of an APK:

```
<Android SDK>\build-tools\<version>\apksigner.bat verify --verbose app\build\outputs\apk\release\app-release.apk
```

## 4. Try the release build on a phone

The release build behaves differently from debug (code is renamed and shrunk), so run it once by hand before uploading:

1. `gradlew assembleRelease` (signed with your key), then install: `adb install -r app\build\outputs\apk\release\app-release.apk`
2. Walk through: register → enroll (scan the QR with another authenticator app or type the secret) → log out → log in (password, 6-digit code, fingerprint or screen lock) → Dashboard → Login history → Login map → Backup codes → Settings → Help → Profile.
3. Make sure the Maps key allows the **release** certificate (see the next section) or the map stays blank.

(The development team already did the first half of this on an emulator with a locally signed copy: register, enroll with a code computed by an independent program, log in and reach the fingerprint prompt, all in the shrunk build.)

## 5. Google Maps key for the release build

In Google Cloud Console → *APIs & Services → Credentials* → your key → *Application restrictions: Android apps*:

* Package name: `com.secureauth.simulator`
* SHA-1: the certificate fingerprint of the key that signs the app people install. Print yours with
  `keytool -list -v -keystore C:\Users\<you>\keys\secureauth-release.jks -alias secureauth`
* If you use **Play App Signing** (the default for new apps), Google re-signs the app with its own key: add the **App signing key** SHA-1 shown in Play Console → *Setup → App integrity* as well.
* Keep the restriction to **Maps SDK for Android** only.

## 6. Upload to Google Play

1. Play Console → *Create app* → fill in the details (name, language, app or game, free or paid).
2. Complete *App content*: privacy policy link (`docs/privacy-policy.md` hosted publicly), *Data safety* (draft answers in `docs/play-data-safety.md`), content rating, target audience, ads declaration, app access instructions.
3. *Production* (or *Internal testing* first, which is quicker and a good rehearsal) → *Create new release* → accept **Play App Signing** → upload `app-release.aab`.
4. Add release notes → *Review release* → *Start rollout*.

## 7. Pre-release checklist

- [ ] `gradlew lintDebug` shows **0 errors**
- [ ] `gradlew test` passes; the emulator tests pass (`gradlew connectedDebugAndroidTest`, on an emulator with **no screen lock**, so the fingerprint prompt does not hide the app under test)
- [ ] `versionCode` raised since the last upload
- [ ] `keystore.properties` present, and `git status` does **not** list it, `local.properties` or any `.jks`
- [ ] Privacy policy contact details filled in and the page is public
- [ ] Maps key restricted to the release SHA-1(s)
- [ ] Release build tried by hand (section 4)
