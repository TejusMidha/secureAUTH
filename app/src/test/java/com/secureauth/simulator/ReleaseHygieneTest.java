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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks on the project files themselves (not on running code) that guard release readiness:
 * secrets stay out of Git, the manifest is locked down, and release shrinking stays switched on.
 * Gradle runs unit tests with the module folder (app/) as the working directory.
 */
public class ReleaseHygieneTest {

    private static final Path MODULE = Paths.get("").toAbsolutePath();
    private static final Path ROOT = MODULE.getParent();
    private static final Path MAIN = MODULE.resolve("src/main");

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static List<Path> filesUnder(Path dir, String... extensions) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(p -> {
                String name = p.getFileName().toString();
                for (String ext : extensions) {
                    if (name.endsWith(ext)) {
                        return true;
                    }
                }
                return false;
            }).collect(Collectors.toList());
        }
    }

    // ---- secrets stay out of Git ------------------------------------------------------------

    @Test
    public void theGitIgnoreKeepsKeysAndLocalSettingsOutOfGit() throws IOException {
        String ignore = read(ROOT.resolve(".gitignore"));
        for (String must : new String[]{"local.properties", "keystore.properties", "*.jks", "*.keystore", "*.apk", "*.aab"}) {
            assertTrue(".gitignore must list " + must, ignore.contains(must));
        }
    }

    @Test
    public void noKeyOrSigningFileSitsInsideTheProjectFolders() throws IOException {
        // The two properties files hold secrets; they may exist on a developer's PC but are ignored (see above).
        // Key stores must never be in the source folders at all.
        List<Path> stores = filesUnder(MAIN, ".jks", ".keystore", ".p12", ".pem");
        assertTrue("key files in src/main: " + stores, stores.isEmpty());
    }

    @Test
    public void noGoogleApiKeyIsWrittenIntoSourceCode() throws IOException {
        Pattern googleKey = Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}");
        for (Path file : filesUnder(MAIN, ".java", ".xml", ".kts", ".html", ".json", ".properties")) {
            assertFalse("API key found in " + file, googleKey.matcher(read(file)).find());
        }
        assertFalse(googleKey.matcher(read(MODULE.resolve("build.gradle.kts"))).find());
    }

    @Test
    public void theMapsKeyComesFromTheManifestPlaceholder() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        assertTrue(manifest.contains("android:value=\"${MAPS_API_KEY}\""));
    }

    // ---- the manifest is locked down --------------------------------------------------------

    @Test
    public void onlyTheLauncherScreenIsExported() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        Matcher m = Pattern.compile("android:exported=\"true\"").matcher(manifest);
        int exported = 0;
        while (m.find()) {
            exported++;
        }
        assertEquals("only SplashActivity may be started by other apps", 1, exported);
        // (a regular expression, so Windows or Unix line endings do not matter)
        assertTrue(Pattern.compile("android:name=\"\\.ui\\.SplashActivity\"\\s+android:exported=\"true\"")
                .matcher(manifest).find());
    }

    @Test
    public void everyActivityInTheManifestIsDeclaredOnce() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        Matcher m = Pattern.compile("<activity\\s+android:name=\"\\.ui\\.(\\w+)\"").matcher(manifest);
        List<String> declared = new ArrayList<>();
        while (m.find()) {
            declared.add(m.group(1));
        }
        assertEquals("a screen is declared twice", declared.size(), declared.stream().distinct().count());

        // ...and every Activity class in ui/ is declared (a missing one crashes when opened)
        for (Path file : filesUnder(MAIN.resolve("java/com/secureauth/simulator/ui"), "Activity.java")) {
            String name = file.getFileName().toString().replace(".java", "");
            if (name.equals("BaseActivity")) {
                continue;
            }
            assertTrue(name + " is missing from AndroidManifest.xml", declared.contains(name));
        }
    }

    @Test
    public void androidBackupIsSwitchedOff() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        assertTrue(manifest.contains("android:allowBackup=\"false\""));
        for (String rules : new String[]{"backup_rules.xml", "data_extraction_rules.xml"}) {
            String xml = read(MAIN.resolve("res/xml/" + rules));
            assertTrue(rules + " should exclude the database", xml.contains("domain=\"database\""));
            assertTrue(rules + " should exclude shared preferences", xml.contains("domain=\"sharedpref\""));
            assertFalse(rules + " must not include anything", xml.contains("<include"));
        }
    }

    @Test
    public void theAppAsksForNoMorePermissionsThanItNeeds() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        Matcher m = Pattern.compile("<uses-permission\\s+android:name=\"([\\w.]+)\"").matcher(manifest);
        List<String> asked = new ArrayList<>();
        while (m.find()) {
            asked.add(m.group(1));
        }
        List<String> allowed = java.util.Arrays.asList(
                "android.permission.INTERNET",
                "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION");
        for (String permission : asked) {
            assertTrue("unexpected permission " + permission, allowed.contains(permission));
        }
        assertEquals(allowed.size(), asked.size());
    }

    @Test
    public void theFileProviderIsNotExportedAndSharesOnlyNamedFolders() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        int provider = manifest.indexOf("androidx.core.content.FileProvider");
        assertTrue(provider > 0);
        String tag = manifest.substring(provider, manifest.indexOf("</provider>", provider));
        assertTrue(tag.contains("android:exported=\"false\""));

        String paths = read(MAIN.resolve("res/xml/file_paths.xml"));
        assertFalse("sharing the whole cache or files folder would be too wide", paths.contains("path=\".\""));
        assertFalse(paths.contains("root-path"));
        assertFalse(paths.contains("external-path"));
    }

    // ---- release build ----------------------------------------------------------------------

    @Test
    public void theReleaseBuildShrinksCodeAndResources() throws IOException {
        String gradle = read(MODULE.resolve("build.gradle.kts"));
        int release = gradle.indexOf("release {", gradle.indexOf("buildTypes"));
        assertTrue(release > 0);
        String block = gradle.substring(release, gradle.indexOf("buildFeatures"));
        assertTrue("R8 must be on", block.contains("enable = true"));
        assertTrue("resource shrinking must be on", block.contains("isShrinkResources = true"));
    }

    @Test
    public void theReleaseSigningDetailsAreReadFromAFileNotWrittenIntoTheBuildScript() throws IOException {
        String gradle = read(MODULE.resolve("build.gradle.kts"));
        assertTrue(gradle.contains("keystore.properties"));
        assertFalse("a password must not be written in the build script",
                Pattern.compile("(storePassword|keyPassword)\\s*=\\s*\"").matcher(gradle).find());
    }

    @Test
    public void theLauncherIconIsTheShieldAndKeyNotTheTemplate() throws IOException {
        String background = read(MAIN.resolve("res/drawable/ic_launcher_background.xml"));
        assertFalse("still the green template background", background.contains("3DDC84"));
        String adaptive = read(MAIN.resolve("res/mipmap-anydpi/ic_launcher.xml"));
        assertTrue(adaptive.contains("<monochrome"));
        assertTrue(Files.exists(MAIN.resolve("res/drawable/ic_launcher_monochrome.xml")));
    }
}
