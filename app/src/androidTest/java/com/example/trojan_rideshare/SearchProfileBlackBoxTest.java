package com.example.trojan_rideshare;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputLayout;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertTrue;

/**
 * Black-box tests for search, filter, and sort.
 * Inputs come from the search rules: empty places, a missing place, radius at and below 0,
 * and the sort choices on the search screen.
 * These need the shared test account. They stop before Mapbox is called.
 */
@RunWith(AndroidJUnit4.class)
public class SearchProfileBlackBoxTest {

    private static final long NETWORK_TIMEOUT_MS = 30_000;
    private static final String TEST_EMAIL = "espresso@usc.edu";
    private static final String TEST_PASSWORD = "espresso";
    private static final String BOTH_PLACES = "Please enter both an origin and destination";
    private static final String FILTER = "Please enter a valid filter option.";

    @BeforeClass
    public static void logIn() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                AuthManager.getInstance().authenticateUserByPassword(TEST_EMAIL, TEST_PASSWORD, ok -> {
                    success.set(ok);
                    done.countDown();
                }));
        assertTrue("Login timed out", done.await(NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue("Could not log in as " + TEST_EMAIL, success.get());
    }

    @Test
    public void b1_emptyOriginAndDestinationShowBothPlacesError() {
        try (ActivityScenario<SearchRideActivity> ignored = ActivityScenario.launch(SearchRideActivity.class)) {
            onView(withId(R.id.btnSearch)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilOrigin)).check(matches(hasInputError(BOTH_PLACES)));
            onView(withId(R.id.tilDestination)).check(matches(hasInputError(BOTH_PLACES)));
        }
    }

    @Test
    public void b2_missingDestinationShowsBothPlacesError() {
        try (ActivityScenario<SearchRideActivity> ignored = ActivityScenario.launch(SearchRideActivity.class)) {
            onView(withId(R.id.etOrigin)).perform(scrollIntoView(), replaceText("USC"), closeSoftKeyboard());
            onView(withId(R.id.btnSearch)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilOrigin)).check(matches(hasInputError(null)));
            onView(withId(R.id.tilDestination)).check(matches(hasInputError(BOTH_PLACES)));
        }
    }

    @Test
    public void b3_zeroRadiusShowsFilterError() {
        try (ActivityScenario<SearchRideActivity> ignored = ActivityScenario.launch(SearchRideActivity.class)) {
            fillPlaces("0");
            onView(withId(R.id.btnSearch)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilRadius)).check(matches(hasInputError(FILTER)));
            onView(withId(R.id.tilOrigin)).check(matches(hasInputError(null)));
        }
    }

    @Test
    public void b4_negativeRadiusShowsFilterError() {
        try (ActivityScenario<SearchRideActivity> ignored = ActivityScenario.launch(SearchRideActivity.class)) {
            fillPlaces("-1");
            onView(withId(R.id.btnSearch)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilRadius)).check(matches(hasInputError(FILTER)));
        }
    }

    @Test
    public void b5_sortMenuListsEverySearchOrder() {
        try (ActivityScenario<SearchRideActivity> ignored = ActivityScenario.launch(SearchRideActivity.class)) {
            onView(withId(R.id.spinnerSort)).perform(scrollIntoView(), click());

            onView(withText("Earliest to latest")).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
            onView(withText("Latest to earliest")).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
            onView(withText("Proximity")).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
            onView(withText("Proximity and time")).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
            onView(withText("Expected cost")).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
        }
    }

    private static void fillPlaces(String radius) {
        onView(withId(R.id.etOrigin)).perform(scrollIntoView(), replaceText("USC"));
        onView(withId(R.id.etDestination)).perform(scrollIntoView(), replaceText("LAX"));
        onView(withId(R.id.etRadius)).perform(scrollIntoView(), replaceText(radius), closeSoftKeyboard());
    }

    private static ViewAction scrollIntoView() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return allOf(withEffectiveVisibility(VISIBLE), isDescendantOfA(isAssignableFrom(ScrollView.class)));
            }

            @Override
            public String getDescription() {
                return "scroll the enclosing ScrollView to the view";
            }

            @Override
            public void perform(UiController uiController, View view) {
                ViewParent parent = view.getParent();
                while (!(parent instanceof ScrollView)) {
                    parent = parent.getParent();
                }
                ScrollView scrollView = (ScrollView) parent;
                Rect rect = new Rect();
                view.getDrawingRect(rect);
                scrollView.offsetDescendantRectToMyCoords(view, rect);
                scrollView.scrollTo(0, Math.max(0, rect.top - scrollView.getHeight() / 3));
                uiController.loopMainThreadUntilIdle();
            }
        };
    }

    private static Matcher<View> hasInputError(String expected) {
        return new TypeSafeMatcher<View>() {
            @Override
            protected boolean matchesSafely(View view) {
                if (!(view instanceof TextInputLayout)) {
                    return false;
                }
                CharSequence error = ((TextInputLayout) view).getError();
                return expected == null ? error == null : error != null && expected.contentEquals(error);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("TextInputLayout with error: " + expected);
            }
        };
    }
}
