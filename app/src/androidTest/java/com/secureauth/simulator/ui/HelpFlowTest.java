package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.secureauth.simulator.ui.TestUtils.waitUntilDisplayed;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.secureauth.simulator.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The Help screen: the local page, the RFC button, Back inside the WebView and the safety settings. */
@RunWith(AndroidJUnit4.class)
public class HelpFlowTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private ActivityScenario<HelpActivity> launch() {
        return ActivityScenario.launch(new Intent(context, HelpActivity.class));
    }

    /** Reads something from the WebView on the main thread (WebView may only be touched there). */
    private static <T> T onWebView(ActivityScenario<HelpActivity> scenario, java.util.function.Function<WebView, T> read) {
        final Object[] holder = new Object[1];
        scenario.onActivity(a -> holder[0] = read.apply(a.findViewById(R.id.web_view)));
        @SuppressWarnings("unchecked") T value = (T) holder[0];
        return value;
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Condition not reached in time");
            }
            Thread.sleep(100);
        }
    }

    // ---- the local page ---------------------------------------------------------------------

    @Test
    public void theHowTotpWorksPageLoadsFromTheAppItself() throws Exception {
        try (ActivityScenario<HelpActivity> scenario = launch()) {
            waitUntil(() -> "How TOTP works".equals(onWebView(scenario, WebView::getTitle)));
            assertEquals(HelpPages.LOCAL_URL, onWebView(scenario, WebView::getUrl));
        }
    }

    @Test
    public void theProgressBarIsHiddenOnceThePageHasLoaded() throws Exception {
        try (ActivityScenario<HelpActivity> scenario = launch()) {
            waitUntil(() -> "How TOTP works".equals(onWebView(scenario, WebView::getTitle)));
            waitUntil(() -> {
                final boolean[] hidden = new boolean[1];
                scenario.onActivity(a -> hidden[0] =
                        a.findViewById(R.id.progress_page).getVisibility() != android.view.View.VISIBLE);
                return hidden[0];
            });
        }
    }

    // ---- the buttons ------------------------------------------------------------------------

    @Test
    public void theRfcButtonAsksForTheStandardAndTheOtherButtonComesBack() throws Exception {
        try (ActivityScenario<HelpActivity> scenario = launch()) {
            waitUntil(() -> "How TOTP works".equals(onWebView(scenario, WebView::getTitle)));

            onView(withId(R.id.button_help_rfc)).perform(click());
            // With a network the page opens; without one the error message is shown instead
            waitUntil(() -> HelpPages.RFC_URL.equals(onWebView(scenario, WebView::getUrl))
                    || isShown(scenario, R.id.text_error));

            onView(withId(R.id.button_help_totp)).perform(click());
            waitUntil(() -> HelpPages.LOCAL_URL.equals(onWebView(scenario, WebView::getUrl)));
            assertFalse(isShown(scenario, R.id.text_error));
        }
    }

    private static boolean isShown(ActivityScenario<HelpActivity> scenario, int viewId) {
        final boolean[] shown = new boolean[1];
        scenario.onActivity(a -> shown[0] = a.findViewById(viewId).getVisibility() == android.view.View.VISIBLE);
        return shown[0];
    }

    // ---- Back inside the WebView ------------------------------------------------------------

    @Test
    public void backGoesToThePreviousPageBeforeLeavingTheScreen() throws Exception {
        try (ActivityScenario<HelpActivity> scenario = launch()) {
            waitUntil(() -> "How TOTP works".equals(onWebView(scenario, WebView::getTitle)));

            // A second page in the history (the same file with a different query, so it is a real new load)
            final String second = HelpPages.LOCAL_URL + "?page=2";
            scenario.onActivity(a -> ((WebView) a.findViewById(R.id.web_view)).loadUrl(second));
            waitUntil(() -> second.equals(onWebView(scenario, WebView::getUrl)));
            waitUntil(() -> onWebView(scenario, WebView::canGoBack));

            scenario.onActivity(a -> a.getOnBackPressedDispatcher().onBackPressed());
            waitUntil(() -> HelpPages.LOCAL_URL.equals(onWebView(scenario, WebView::getUrl)));
            assertTrue(scenario.getState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)); // still open
        }
    }

    // ---- safety -----------------------------------------------------------------------------

    @Test
    public void javaScriptAndFileAccessAreSwitchedOff() throws Exception {
        try (ActivityScenario<HelpActivity> scenario = launch()) {
            waitUntil(() -> onWebView(scenario, WebView::getSettings) != null);
            WebSettings settings = onWebView(scenario, WebView::getSettings);
            assertFalse(settings.getJavaScriptEnabled());
            assertFalse(settings.getAllowFileAccess());
            assertFalse(settings.getAllowContentAccess());
        }
    }

    @Test
    public void linksToOtherSitesAreBlockedAndHelpPagesStayInsideTheWebView() throws Exception {
        try (ActivityScenario<HelpActivity> scenario = launch()) {
            waitUntil(() -> "How TOTP works".equals(onWebView(scenario, WebView::getTitle)));

            final boolean[] answers = new boolean[2];
            scenario.onActivity(a -> {
                WebView web = a.findViewById(R.id.web_view);
                answers[0] = web.getWebViewClient().shouldOverrideUrlLoading(web, request("https://example.com/"));
                answers[1] = web.getWebViewClient().shouldOverrideUrlLoading(web, request(HelpPages.RFC_URL));
            });
            assertTrue("a foreign site must be blocked", answers[0]);
            assertFalse("the RFC page must load inside the WebView", answers[1]);
        }
    }

    private static WebResourceRequest request(String url) {
        final Uri uri = Uri.parse(url);
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return uri; }
            @Override public boolean isForMainFrame() { return true; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return true; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Collections.emptyMap(); }
        };
    }

    @Test
    public void theScreenShowsItsToolbarAndButtons() throws Exception {
        try (ActivityScenario<HelpActivity> ignored = launch()) {
            waitUntilDisplayed(withId(R.id.button_help_totp));
            onView(withId(R.id.button_help_rfc)).check(matches(isDisplayed()));
        }
    }
}
