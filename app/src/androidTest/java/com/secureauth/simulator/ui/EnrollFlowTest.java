package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.secureauth.simulator.ui.TestUtils.textOf;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.secureauth.simulator.R;
import com.secureauth.simulator.crypto.Base32;
import com.secureauth.simulator.crypto.KeystoreHelper;
import com.secureauth.simulator.crypto.TotpEngine;
import com.secureauth.simulator.data.AuditEvent;
import com.secureauth.simulator.data.AuditLog;
import com.secureauth.simulator.data.AuditLogDao;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Tests of the enroll screen against the real app database (one throw-away user per test). */
@RunWith(AndroidJUnit4.class)
public class EnrollFlowTest {

    private Context context;
    private UserDao users;
    private TotpAccountDao accounts;
    private AuditLogDao audit;
    private String username;
    private long userId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DbHelper db = DbHelper.getInstance(context);
        users = new UserDao(db);
        accounts = new TotpAccountDao(db);
        audit = new AuditLogDao(db);

        username = "e" + Long.toString(System.currentTimeMillis(), 36);
        User u = new User();
        u.username = username;
        u.passwordHash = "h";
        u.salt = "s";
        u.createdAt = System.currentTimeMillis();
        userId = users.insert(u);
    }

    @After
    public void tearDown() {
        users.delete(userId); // cascades to totp_accounts and audit_log
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ActivityScenario<EnrollActivity> launchEnroll() {
        Intent intent = new Intent(context, EnrollActivity.class)
                .putExtra(EnrollActivity.EXTRA_USER_ID, userId);
        return ActivityScenario.launch(intent);
    }

    private static byte[] shownSecret() {
        return Base32.decode(textOf(R.id.text_secret)); // decode() ignores the spaces
    }

    private static void typeCodeAndConfirm(String code) {
        onView(withId(R.id.input_code)).perform(scrollTo(), replaceText(code), closeSoftKeyboard());
        onView(withId(R.id.button_confirm)).perform(scrollTo(), click());
    }

    /** A 6-digit code that is NOT valid in the current, previous or next time step. */
    private static String invalidCode(byte[] secret) {
        long now = System.currentTimeMillis();
        for (int candidate = 0; candidate < 1_000_000; candidate += 7919) {
            String code = String.format("%06d", candidate);
            if (!TotpEngine.verify(secret, code, now, 6, 30, TotpEngine.ALGO_SHA1)) {
                return code;
            }
        }
        throw new IllegalStateException("no invalid code found");
    }

    private static void waitUntilClosed(ActivityScenario<?> scenario) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (scenario.getState() != Lifecycle.State.DESTROYED) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("EnrollActivity did not close");
            }
            Thread.sleep(100);
        }
    }

    private int countEvents(String event) {
        int count = 0;
        for (AuditLog row : audit.listForUser(userId)) {
            if (event.equals(row.event)) {
                count++;
            }
        }
        return count;
    }

    /** Reads the bitmap the screen is really showing and decodes it with ZXing's reader. */
    private static String decodeDisplayedQr(ActivityScenario<EnrollActivity> scenario) throws Exception {
        final Bitmap[] holder = new Bitmap[1];
        long deadline = System.currentTimeMillis() + 10_000;
        while (holder[0] == null) {
            scenario.onActivity(activity -> {
                ImageView view = activity.findViewById(R.id.image_qr);
                if (view.getDrawable() instanceof BitmapDrawable) {
                    holder[0] = ((BitmapDrawable) view.getDrawable()).getBitmap();
                }
            });
            if (holder[0] == null) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("QR code never appeared");
                }
                Thread.sleep(100);
            }
        }
        Bitmap bitmap = holder[0];
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)));
        return new QRCodeReader().decode(binary).getText();
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    public void secretIsShownInGroupsOfFour() {
        try (ActivityScenario<EnrollActivity> ignored = launchEnroll()) {
            String shown = textOf(R.id.text_secret);
            // 32 Base32 characters = 8 groups of 4: four per line, spaces between groups
            assertTrue(shown, shown.matches("([A-Z2-7]{4} ){3}[A-Z2-7]{4}\n([A-Z2-7]{4} ){3}[A-Z2-7]{4}"));
        }
    }

    @Test
    public void qrCodeContainsTheOtpAuthLinkForTheShownSecret() throws Exception {
        try (ActivityScenario<EnrollActivity> scenario = launchEnroll()) {
            String shownBase32 = textOf(R.id.text_secret).replaceAll("\\s", "");
            String decoded = decodeDisplayedQr(scenario);

            assertEquals("otpauth://totp/SecureAuth:" + username
                    + "?secret=" + shownBase32
                    + "&issuer=SecureAuth&algorithm=SHA1&digits=6&period=30", decoded);
        }
    }

    @Test
    public void confirmationBoxOpensAfterIHaveScannedIt() {
        try (ActivityScenario<EnrollActivity> ignored = launchEnroll()) {
            onView(withId(R.id.input_code)).check(matches(org.hamcrest.Matchers.not(isDisplayed())));
            onView(withId(R.id.button_scanned)).perform(scrollTo(), click());
            onView(withId(R.id.input_code)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test
    public void wrongCodeIsRejectedAndNothingIsSaved() throws Exception {
        try (ActivityScenario<EnrollActivity> ignored = launchEnroll()) {
            onView(withId(R.id.button_scanned)).perform(scrollTo(), click());

            typeCodeAndConfirm(invalidCode(shownSecret()));
            onView(withText(R.string.error_code_wrong)).check(matches(isDisplayed()));

            // not saved, and the failure was logged
            Thread.sleep(500); // the audit row is written on a background thread
            assertTrue(accounts.listForUser(userId).isEmpty());
            assertEquals(1, countEvents(AuditEvent.ENROLL_VERIFY_FAIL));
            assertEquals(0, countEvents(AuditEvent.TOTP_ENROLLED));
        }
    }

    @Test
    public void incompleteCodeShowsLengthErrorWithoutAuditRow() throws Exception {
        try (ActivityScenario<EnrollActivity> ignored = launchEnroll()) {
            onView(withId(R.id.button_scanned)).perform(scrollTo(), click());
            typeCodeAndConfirm("123");
            onView(withText(R.string.error_code_length)).check(matches(isDisplayed()));
            assertEquals(0, countEvents(AuditEvent.ENROLL_VERIFY_FAIL));
        }
    }

    @Test
    public void correctCodeStoresTheSecretEncryptedAndClosesTheScreen() throws Exception {
        try (ActivityScenario<EnrollActivity> scenario = launchEnroll()) {
            byte[] secret = shownSecret();
            String base32 = textOf(R.id.text_secret).replaceAll("\\s", "");
            onView(withId(R.id.button_scanned)).perform(scrollTo(), click());

            // what the authenticator app would show right now
            typeCodeAndConfirm(TotpEngine.generate(secret, System.currentTimeMillis(), 6, 30,
                    TotpEngine.ALGO_SHA1));
            waitUntilClosed(scenario);

            List<TotpAccount> saved = accounts.listForUser(userId);
            assertEquals(1, saved.size());
            TotpAccount a = saved.get(0);
            assertEquals("SecureAuth", a.issuer);
            assertEquals(6, a.digits);
            assertEquals(30, a.period);
            assertEquals(TotpEngine.ALGO_SHA1, a.algo);

            // stored encrypted, and decrypts back to exactly the secret that was on screen
            assertFalse(a.secretEnc.contains(base32));
            assertArrayEquals(secret, KeystoreHelper.decrypt(a.secretEnc, a.iv));
            assertEquals(1, countEvents(AuditEvent.TOTP_ENROLLED));
        }
    }

    @Test
    public void reEnrollingReplacesTheOldAuthenticator() throws Exception {
        TotpAccount old = new TotpAccount();
        old.userId = userId;
        old.secretEnc = "old-cipher";
        old.iv = "old-iv";
        old.issuer = "SecureAuth";
        old.createdAt = 1L;
        accounts.insert(old);

        try (ActivityScenario<EnrollActivity> scenario = launchEnroll()) {
            byte[] secret = shownSecret();
            onView(withId(R.id.button_scanned)).perform(scrollTo(), click());
            typeCodeAndConfirm(TotpEngine.generate(secret, System.currentTimeMillis(), 6, 30,
                    TotpEngine.ALGO_SHA1));
            waitUntilClosed(scenario);

            List<TotpAccount> saved = accounts.listForUser(userId);
            assertEquals(1, saved.size());
            assertNotEquals("old-cipher", saved.get(0).secretEnc);
        }
    }

    @Test
    public void longPressOnQrOffersCopyAndShare() throws Exception {
        try (ActivityScenario<EnrollActivity> scenario = launchEnroll()) {
            decodeDisplayedQr(scenario); // waits until the QR has been drawn

            onView(withId(R.id.image_qr)).perform(scrollTo(), longClick());
            onView(withText(R.string.menu_copy_secret)).check(matches(isDisplayed()));
            onView(withText(R.string.menu_share_qr)).check(matches(isDisplayed()));

            // "Share QR image" asks for confirmation first; Cancel leaves without sharing
            onView(withText(R.string.menu_share_qr)).perform(click());
            waitUntilDisplayed(withText(R.string.share_warning_title));
            onView(withText(R.string.button_cancel)).perform(click());
        }
    }

    @Test
    public void copySecretFromContextMenuPutsRawBase32OnTheClipboard() throws Exception {
        try (ActivityScenario<EnrollActivity> scenario = launchEnroll()) {
            decodeDisplayedQr(scenario);
            String base32 = textOf(R.id.text_secret).replaceAll("\\s", "");

            onView(withId(R.id.image_qr)).perform(scrollTo(), longClick());
            onView(withText(R.string.menu_copy_secret)).perform(click());

            final String[] clip = new String[1];
            scenario.onActivity(activity -> {
                android.content.ClipboardManager cm =
                        activity.getSystemService(android.content.ClipboardManager.class);
                clip[0] = String.valueOf(cm.getPrimaryClip().getItemAt(0).getText());
            });
            assertNotNull(clip[0]);
            assertEquals(base32, clip[0]);
        }
    }
}
