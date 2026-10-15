package com.secureauth.simulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The hard rules of SECUREAUTH_PLAN.md (section 3) turned into checks on the source files, so a
 * later change cannot quietly break them. Gradle runs unit tests with the module folder (app/)
 * as the working directory.
 */
public class ProjectRulesTest {

    private static final Path MODULE = Paths.get("").toAbsolutePath();
    private static final Path ROOT = MODULE.getParent();
    private static final Path JAVA = MODULE.resolve("src/main/java/com/secureauth/simulator");
    private static final Path UI = JAVA.resolve("ui");

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(JAVA)) {
            return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static List<String> mainJavaFilesContaining(Pattern pattern) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String text = read(file);
            // Comments may mention a forbidden word (for example to explain why it is not used)
            String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            if (pattern.matcher(code).find()) {
                found.add(file.getFileName().toString());
            }
        }
        return found;
    }

    // ---- rule: Java and XML only; no Kotlin, Compose or Flutter ------------------------------

    @Test
    public void thereIsNoKotlinDartOrComposeCode() throws IOException {
        try (Stream<Path> walk = Files.walk(MODULE.resolve("src"))) {
            List<Path> foreign = walk.filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".dart"))
                    .collect(Collectors.toList());
            assertTrue("foreign source files: " + foreign, foreign.isEmpty());
        }
        String gradle = read(MODULE.resolve("build.gradle.kts")) + read(ROOT.resolve("gradle/libs.versions.toml"));
        assertFalse(gradle.toLowerCase().contains("compose"));
        assertFalse(gradle.contains("kotlin"));
    }

    // ---- rule: only the allowed dependencies ------------------------------------------------

    @Test
    public void onlyTheAllowedLibrariesAreUsed() throws IOException {
        String catalog = read(ROOT.resolve("gradle/libs.versions.toml"));
        String libraries = catalog.substring(catalog.indexOf("[libraries]"), catalog.indexOf("[plugins]"));
        Matcher m = Pattern.compile("group = \"([^\"]+)\", name = \"([^\"]+)\"").matcher(libraries);
        List<String> used = new ArrayList<>();
        while (m.find()) {
            used.add(m.group(1) + ":" + m.group(2));
        }
        List<String> allowed = Arrays.asList(
                "com.google.android.material:material", "androidx.appcompat:appcompat",
                "androidx.constraintlayout:constraintlayout", "androidx.biometric:biometric",
                "com.journeyapps:zxing-android-embedded", "com.google.android.gms:play-services-maps",
                "com.google.android.gms:play-services-location", "androidx.swiperefreshlayout:swiperefreshlayout",
                "junit:junit", "androidx.test.ext:junit", "androidx.test.espresso:espresso-core");
        for (String library : used) {
            assertTrue("library not allowed by the plan: " + library, allowed.contains(library));
        }
        assertEquals(new TreeSet<>(allowed), new TreeSet<>(used));
    }

    // ---- rule 1: no OTP / Base32 library; the engine is our own -----------------------------

    @Test
    public void noOtpOrBase32LibraryIsImported() throws IOException {
        Pattern forbidden = Pattern.compile("import\\s+(org\\.apache\\.commons\\.codec|com\\.eatthepath|dev\\.samstevens"
                + "|de\\.taimos|com\\.warrenstrange|org\\.jboss\\.aerogear|com\\.google\\.common\\.io\\.BaseEncoding)");
        assertEquals(Collections.emptyList(), mainJavaFilesContaining(forbidden));
    }

    @Test
    public void theTotpEngineUsesOnlyTheHmacPrimitiveFromTheJdk() throws IOException {
        String engine = read(JAVA.resolve("crypto/TotpEngine.java"));
        assertTrue(engine.contains("javax.crypto.Mac"));
        assertFalse(engine.contains("android."));
        assertTrue(read(JAVA.resolve("crypto/Base32.java")).contains("ALPHABET"));
    }

    // ---- rule 2: secrets come from SecureRandom only ----------------------------------------

    @Test
    public void noWeakRandomnessIsUsed() throws IOException {
        Pattern weak = Pattern.compile("new\\s+Random\\s*\\(|Math\\.random\\s*\\(|ThreadLocalRandom|SplittableRandom");
        assertEquals(Collections.emptyList(), mainJavaFilesContaining(weak));
    }

    // ---- rule 3 and 4: where secrets are stored ---------------------------------------------

    @Test
    public void theTotpSecretIsOnlyStoredThroughTheKeystoreEncryption() throws IOException {
        // No screen except EnrollActivity builds an account row with a secret (the DAO only copies
        // a row it has read from the database into an object)
        List<String> storing = new ArrayList<>();
        try (Stream<Path> files = Files.list(UI)) {
            for (Path f : files.collect(Collectors.toList())) {
                if (Pattern.compile("\\.secretEnc\\s*=").matcher(read(f)).find()) {
                    storing.add(f.getFileName().toString());
                }
            }
        }
        assertEquals(Collections.singletonList("EnrollActivity.java"), storing);

        // ...and it encrypts with the Keystore before it fills the field
        String enroll = read(UI.resolve("EnrollActivity.java"));
        assertTrue(enroll.indexOf("KeystoreHelper.encrypt") < enroll.indexOf("account.secretEnc"));
    }

    @Test
    public void passwordsAndBackupCodesAreOnlyHashedWithPbkdf2() throws IOException {
        // SHA-256 is allowed in exactly one place: the session token, which is 256 random bits
        List<String> sha = mainJavaFilesContaining(Pattern.compile("MessageDigest\\.getInstance"));
        assertEquals(Collections.singletonList("SessionManager.java"), sha);
        assertTrue(read(JAVA.resolve("crypto/PasswordHasher.java")).contains("PBKDF2WithHmacSHA256"));
        String generator = read(JAVA.resolve("crypto/BackupCodeGenerator.java"));
        assertTrue("backup codes are hashed with PasswordHasher", generator.contains("PasswordHasher.hash"));
    }

    // ---- rule 6 and 7: Activity Result APIs, no logging --------------------------------------

    @Test
    public void theActivityResultApisAreUsedNotTheOldOnes() throws IOException {
        Pattern old = Pattern.compile("startActivityForResult\\s*\\(|void\\s+onActivityResult\\s*\\(|onRequestPermissionsResult\\s*\\(");
        assertEquals(Collections.emptyList(), mainJavaFilesContaining(old));
    }

    @Test
    public void nothingIsLoggedOrPrinted() throws IOException {
        Pattern logging = Pattern.compile("\\bLog\\.[a-z]+\\s*\\(|System\\.(out|err)\\.|printStackTrace\\s*\\(|\\bTimber\\.");
        assertEquals(Collections.emptyList(), mainJavaFilesContaining(logging));
    }

    // ---- the handshake: which screens need what ---------------------------------------------

    @Test
    public void everyScreenIsEitherProtectedByASessionOrOnTheKnownHandshakeList() throws IOException {
        // A NEW screen must be put on one of the two lists below on purpose. This stops a screen
        // that shows private data from being added without the session check (the "open
        // DashboardActivity directly" bypass).
        List<String> entryOrHandshake = Arrays.asList(
                "SplashActivity", "LoginActivity", "RegisterActivity",              // before any step
                "TotpVerifyActivity", "BackupRecoveryActivity",                     // guarded by LoginHandshake (step 2)
                "BiometricGateActivity",                                            // guarded by LoginHandshake (step 3)
                "EnrollActivity",                                                   // handshake or session
                "HelpActivity");                                                    // static help text, nothing private
        List<String> needSession = Arrays.asList(
                "DashboardActivity", "BackupCodesActivity", "LoginHistoryActivity", "LoginMapActivity",
                "TrustedDevicesActivity", "ProfileActivity", "SettingsActivity");

        List<String> screens = new ArrayList<>();
        try (Stream<Path> files = Files.list(UI)) {
            for (Path f : files.collect(Collectors.toList())) {
                String name = f.getFileName().toString().replace(".java", "");
                if (name.endsWith("Activity") && !name.equals("BaseActivity")) {
                    screens.add(name);
                }
            }
        }
        for (String screen : screens) {
            assertTrue(screen + " is on neither list; decide how it is protected",
                    entryOrHandshake.contains(screen) || needSession.contains(screen));
        }
        for (String screen : needSession) {
            String code = read(UI.resolve(screen + ".java"));
            assertTrue(screen + " must override requiresSession() to return true",
                    Pattern.compile("boolean requiresSession\\(\\)\\s*\\{\\s*return true;").matcher(code).find());
            assertTrue(screen + " must call enforceSession() before showing anything",
                    code.contains("enforceSession()"));
            assertFalse(screen + " must take the user from the session, not from an Intent",
                    code.contains("getLongExtra(EXTRA_USER_ID"));
        }
    }

    @Test
    public void theScreensThatTrustAnIntentUserIdAllCheckTheHandshake() throws IOException {
        for (String screen : new String[]{"TotpVerifyActivity", "BackupRecoveryActivity",
                "BiometricGateActivity", "EnrollActivity"}) {
            String code = read(UI.resolve(screen + ".java"));
            assertTrue(screen + " reads a user id from an Intent", code.contains("getLongExtra(EXTRA_USER_ID"));
            assertTrue(screen + " must check LoginHandshake", code.contains("LoginHandshake.getInstance().allows"));
        }
    }

    @Test
    public void theSessionIsOnlyCreatedAfterTheBiometricStep() throws IOException {
        List<String> creators = mainJavaFilesContaining(Pattern.compile("SessionManager\\.getInstance\\(\\)\\s*\\.create\\("));
        assertEquals("only the fingerprint step may start a session",
                Collections.singletonList("BiometricGateActivity.java"), creators);
    }

    @Test
    public void everyAuditEventNamedInTheCodeIsDeclaredInOnePlace() throws IOException {
        // Strings such as "PASSWORD_OK" must not be typed into screens; they come from AuditEvent
        Pattern typed = Pattern.compile("\\.log\\(\\s*[^,]+,\\s*\"");
        assertEquals(Collections.emptyList(), mainJavaFilesContaining(typed));
    }
}
