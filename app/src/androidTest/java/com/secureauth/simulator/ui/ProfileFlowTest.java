package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.createUser;
import static com.secureauth.simulator.ui.TestUtils.enroll;
import static com.secureauth.simulator.ui.TestUtils.startSession;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitForScreen;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.io.AvatarStore;
import com.secureauth.simulator.io.TestImages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The profile screen and the toolbar photo on the Dashboard, with a throw-away user. */
@RunWith(AndroidJUnit4.class)
public class ProfileFlowTest {

    private static final long FIVE_MINUTES = 5 * 60 * 1000L;

    private Context context;
    private UserDao users;
    private TotpAccountDao accounts;
    private String username;
    private long userId;
    private final List<File> madeFiles = new ArrayList<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        accounts = new TotpAccountDao(db);
        username = "p" + Long.toString(System.currentTimeMillis(), 36);
        userId = createUser(users, username, 0, 0);
        startSession(context, userId, FIVE_MINUTES);
    }

    @After
    public void tearDown() {
        SessionManager.getInstance().clear();
        User u = users.findByUsername(username);
        if (u != null) {
            AvatarStore.delete(context, u.avatarUri);
            users.delete(u.id);
        }
        for (File f : madeFiles) {
            f.delete();
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ActivityScenario<ProfileActivity> launchProfile() {
        return ActivityScenario.launch(new Intent(context, ProfileActivity.class));
    }

    private File picture(String name) throws Exception {
        File f = TestImages.writeJpeg(context, name, TestImages.bands(800, 600, TestImages.RED, TestImages.BLUE));
        madeFiles.add(f);
        return f;
    }

    private String avatarPath() {
        return users.getById(userId).avatarUri;
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not reached in time");
            }
            Thread.sleep(100);
        }
    }

    /** Gives the user a saved photo, as an earlier visit to the profile screen would have. */
    private String givePhoto() throws Exception {
        String path = AvatarStore.saveFrom(context, userId, TestImages.uriOf(picture("existing.jpg")));
        assertNotNull(path);
        users.setAvatarUri(userId, path);
        return path;
    }

    // ---- showing the profile ----------------------------------------------------------------

    @Test
    public void showsUsernameMemberSinceAndIssuer() throws Exception {
        enroll(accounts, userId, TotpEngine.generateSecret());
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitUntilDisplayed(withText(username));
            String since = textOf(R.id.text_member_since);
            assertTrue(since, since.startsWith("Member since "));
            assertTrue(since, since.contains(String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))));
            onView(withId(R.id.text_issuer)).check(matches(withText("Authenticator: SecureAuth")));
        }
    }

    @Test
    public void withoutAnAuthenticatorItSaysSo() throws Exception {
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitUntilDisplayed(withText(username));
            onView(withId(R.id.text_issuer)).check(matches(withText(R.string.profile_not_enrolled)));
        }
    }

    @Test
    public void withoutAPhotoThePlaceholderIsDescribedAsNoPhoto() throws Exception {
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitUntilDisplayed(withText(username));
            onView(withId(R.id.image_avatar)).check(matches(withContentDescription(R.string.cd_avatar_none)));
        }
    }

    @Test
    public void aSavedPhotoIsShown() throws Exception {
        givePhoto();
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitUntilDisplayed(withContentDescription(R.string.cd_avatar));
        }
    }

    @Test
    public void withoutASessionTheScreenSendsYouToLogin() throws Exception {
        SessionManager.getInstance().clear();
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitForScreen(LoginActivity.class);
        }
    }

    // ---- choosing a photo -------------------------------------------------------------------

    @Test
    public void changePhotoOffersTheGalleryAndTheCameraWhenThereIsOne() throws Exception {
        boolean hasCamera = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitUntilDisplayed(withText(username));
            onView(withId(R.id.button_change_photo)).perform(click());

            waitUntilDisplayed(withText(R.string.photo_gallery));
            if (hasCamera) {
                onView(withText(R.string.photo_take)).check(matches(isDisplayed()));
            } else {
                onView(withText(R.string.photo_take)).check(doesNotExist());
            }
            onView(withText(R.string.button_cancel)).perform(click());
        }
    }

    @Test
    public void takingAPhotoWithoutCameraAccessExplainsFirstAndNotNowLeavesEverythingAsIs() throws Exception {
        org.junit.Assume.assumeTrue(context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY));
        org.junit.Assume.assumeTrue(androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED);
        try (ActivityScenario<ProfileActivity> ignored = launchProfile()) {
            waitUntilDisplayed(withText(username));
            onView(withId(R.id.button_change_photo)).perform(click());
            waitUntilDisplayed(withText(R.string.photo_take));
            onView(withText(R.string.photo_take)).perform(click());

            // Our own explanation comes before the system permission dialog
            waitUntilDisplayed(withText(R.string.camera_rationale_title));
            onView(withText(R.string.button_not_now)).perform(click());

            Thread.sleep(500);
            assertNull(avatarPath());
            onView(withId(R.id.button_change_photo)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void aDeniedCameraPermissionStartsNothingAndLeavesThePhotoAlone() throws Exception {
        String old = givePhoto();
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withContentDescription(R.string.cd_avatar));
            scenario.onActivity(a -> a.onCameraPermissionResult(false));

            Thread.sleep(500);
            assertEquals(old, avatarPath());
            onView(withId(R.id.button_change_photo)).check(matches(isEnabled()));
        }
    }

    @Test
    public void aPickedPhotoIsSavedForTheUserAndShown() throws Exception {
        File picked = picture("picked.jpg");
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withText(username));
            scenario.onActivity(a -> a.onPhotoPicked(TestImages.uriOf(picked)));

            waitUntil(() -> avatarPath() != null);
            String path = avatarPath();
            assertTrue(AvatarStore.isInside(AvatarStore.avatarDir(context), new File(path)));
            assertEquals(512, AvatarStore.load(context, path, 512).getWidth());
            waitUntilDisplayed(withContentDescription(R.string.cd_avatar));
            assertTrue(picked.exists()); // the user's own picture is left alone
        }
    }

    @Test
    public void closingTheGalleryWithoutChoosingChangesNothing() throws Exception {
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withText(username));
            scenario.onActivity(a -> a.onPhotoPicked(null));

            Thread.sleep(800);
            assertNull(avatarPath());
            onView(withId(R.id.image_avatar)).check(matches(withContentDescription(R.string.cd_avatar_none)));
        }
    }

    @Test
    public void aNewPhotoReplacesTheOldOneAndTheOldFileIsDeleted() throws Exception {
        String old = givePhoto();
        File picked = picture("second.jpg");
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withContentDescription(R.string.cd_avatar));
            scenario.onActivity(a -> a.onPhotoPicked(TestImages.uriOf(picked)));

            waitUntil(() -> !old.equals(avatarPath()));
            assertNotEquals(old, avatarPath());
            assertTrue(new File(avatarPath()).exists());
            assertFalse(new File(old).exists());
        }
    }

    @Test
    public void aFileThatIsNotAPictureKeepsTheCurrentPhotoAndReEnablesTheButton() throws Exception {
        String old = givePhoto();
        File text = TestImages.writeText(context, "notes.txt", "not a picture");
        madeFiles.add(text);
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withContentDescription(R.string.cd_avatar));
            scenario.onActivity(a -> a.onPhotoPicked(TestImages.uriOf(text)));

            waitUntilDisplayed(allOf(withId(R.id.button_change_photo), isEnabled()));
            Thread.sleep(500);
            assertEquals(old, avatarPath());
            assertTrue(new File(old).exists());
        }
    }

    // ---- the camera answers -----------------------------------------------------------------

    /** Pretends the camera app was started: remembers the file it would write to. */
    private File simulateCameraStarted(ActivityScenario<ProfileActivity> scenario) throws Exception {
        File dir = new File(context.getCacheDir(), "camera");
        dir.mkdirs();
        File file = new File(dir, "profile_capture.jpg");
        madeFiles.add(file);
        scenario.onActivity(activity -> {
            try {
                Field f = ProfileActivity.class.getDeclaredField("cameraFilePath");
                f.setAccessible(true);
                f.set(activity, file.getAbsolutePath());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        return file;
    }

    @Test
    public void aCancelledCameraRemovesItsFileAndChangesNothing() throws Exception {
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withText(username));
            File file = simulateCameraStarted(scenario);
            assertTrue(file.createNewFile()); // the camera app creates an empty file when it opens

            scenario.onActivity(a -> a.onPhotoTaken(false));

            assertFalse(file.exists());
            Thread.sleep(500);
            assertNull(avatarPath());
        }
    }

    @Test
    public void aCameraPictureIsSavedAndItsTemporaryFileRemoved() throws Exception {
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withText(username));
            File file = simulateCameraStarted(scenario);
            File source = picture("shot.jpg");
            java.nio.file.Files.copy(source.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            scenario.onActivity(a -> a.onPhotoTaken(true));

            waitUntil(() -> avatarPath() != null);
            waitUntil(() -> !file.exists());
            waitUntilDisplayed(withContentDescription(R.string.cd_avatar));
        }
    }

    @Test
    public void aCameraAnswerWithoutAPendingFileIsIgnored() throws Exception {
        try (ActivityScenario<ProfileActivity> scenario = launchProfile()) {
            waitUntilDisplayed(withText(username));
            scenario.onActivity(a -> a.onPhotoTaken(true)); // nothing was started

            Thread.sleep(500);
            assertNull(avatarPath());
        }
    }

    // ---- the Dashboard ----------------------------------------------------------------------

    private ActivityScenario<DashboardActivity> launchDashboard() throws Exception {
        enroll(accounts, userId, TotpEngine.generateSecret());
        return ActivityScenario.launch(new Intent(context, DashboardActivity.class));
    }

    @Test
    public void theDashboardToolbarShowsThePhotoAndOpensTheProfile() throws Exception {
        givePhoto();
        try (ActivityScenario<DashboardActivity> ignored = launchDashboard()) {
            waitUntilDisplayed(withContentDescription(R.string.menu_profile));
            onView(withContentDescription(R.string.menu_profile)).perform(click());
            waitForScreen(ProfileActivity.class);
        }
    }

    @Test
    public void theDashboardToolbarHasNoPhotoButtonWithoutAPhoto() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launchDashboard()) {
            waitUntilDisplayed(withId(R.id.text_account));
            Thread.sleep(500);
            onView(withContentDescription(R.string.menu_profile)).check(doesNotExist());
        }
    }

    @Test
    public void theProfileMenuEntryOpensTheProfileScreen() throws Exception {
        try (ActivityScenario<DashboardActivity> ignored = launchDashboard()) {
            waitUntilDisplayed(withId(R.id.text_account));
            androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu(context);
            onView(withText(R.string.menu_profile)).perform(click());
            waitForScreen(ProfileActivity.class);
        }
    }
}
