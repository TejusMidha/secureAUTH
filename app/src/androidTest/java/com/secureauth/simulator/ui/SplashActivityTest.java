package com.secureauth.simulator.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.secureauth.simulator.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks that the splash screen shows the logo text and the "Get started" button. */
@RunWith(AndroidJUnit4.class)
public class SplashActivityTest {

    @Rule
    public ActivityScenarioRule<SplashActivity> rule = new ActivityScenarioRule<>(SplashActivity.class);

    @Test
    public void splashShowsLogoAndButton() {
        onView(withId(R.id.text_logo)).check(matches(isDisplayed()));
        onView(withText(R.string.get_started)).check(matches(isDisplayed()));
    }
}
