package com.example.trojan_rideshare;

import android.os.SystemClock;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewInteraction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputLayout;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Black-box tests for ride creation, ride management and ride history. Inputs come from the
 * spec's rules (equivalence partitions and boundary values). b3-b5 need internet, a Mapbox token
 * and the test account below. b4 uses the ride b3 posts, so the methods run in name order.
 * See docs/TESTING.md.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RideCreationHistoryBlackBoxTest {

    private static final long NETWORK_TIMEOUT_MS = 30_000;
    private static final String TEST_EMAIL = "espresso@usc.edu"; // shared test account
    private static final String TEST_PASSWORD = "espresso";
    private static String postedTitle; // set by b3, used by b4

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

    // B1: every required field empty
    @Test
    public void b1_emptyFormShowsRequiredFieldErrors() {
        try (ActivityScenario<CreateRideActivity> scenario = ActivityScenario.launch(CreateRideActivity.class)) {
            onView(withId(R.id.btnPostRide)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilRideTitle)).check(matches(hasInputError("Please enter a title")));
            onView(withId(R.id.tilOrigin)).check(matches(hasInputError("Please enter a valid origin.")));
            onView(withId(R.id.tilDestination)).check(matches(hasInputError("Please enter a valid destination.")));
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        }
    }

    // B2: riders one above the maximum (7) and a positive cost with 3 decimals (12.345)
    @Test
    public void b2_outOfRangeRidersAndBadCostAreRejected() {
        try (ActivityScenario<CreateRideActivity> scenario = ActivityScenario.launch(CreateRideActivity.class)) {
            fillRideForm("Bad numbers ride", "7", "12.345");
            onView(withId(R.id.btnPostRide)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilMaxRiders)).check(matches(hasInputError("Riders must be between 2 and 6")));
            onView(withId(R.id.tilExpectedCost)).check(matches(
                    hasInputError("Please enter a cost greater than $0 with up to 2 decimals")));
            onView(withId(R.id.tilRideTitle)).check(matches(hasInputError(null)));
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        }
    }

    // B3: all-valid partition, riders at the lower boundary (2), cost with exactly 2 decimals
    @Test
    public void b3_validRidePostsAndAppearsInUpcoming() {
        String title = "Espresso ride " + System.currentTimeMillis();
        try (ActivityScenario<CreateRideActivity> scenario = ActivityScenario.launch(CreateRideActivity.class)) {
            fillRideForm(title, "2", "25.50");
            onView(withId(R.id.btnPostRide)).perform(scrollIntoView(), click());
            waitForState(scenario, Lifecycle.State.DESTROYED);
        }
        postedTitle = title;

        try (ActivityScenario<RideHistoryActivity> ignored = ActivityScenario.launch(RideHistoryActivity.class)) {
            waitFor(rideRow(R.id.upcomingContainer, title));
        }
    }

    // B4: organizer manages the ride from b3: sees the organizer actions, cancels it, it moves to history
    @Test
    public void b4_organizerCanCancelRideFromHistory() {
        assertNotNull("Run b3 first: it posts the ride this test cancels", postedTitle);
        try (ActivityScenario<RideHistoryActivity> ignored = ActivityScenario.launch(RideHistoryActivity.class)) {
            waitFor(rideRow(R.id.upcomingContainer, postedTitle)).perform(scrollIntoView(), click());

            onView(withId(R.id.btnEditRide)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withId(R.id.btnChangeStatus)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withId(R.id.btnCancelRide)).inRoot(isDialog()).perform(scrollIntoView(), click());
            onView(withText("Yes")).inRoot(isDialog()).perform(click());

            waitFor(allOf(withParent(withId(R.id.historyContainer)),
                    hasDescendant(withText(postedTitle)),
                    hasDescendant(withText(startsWith("Cancelled")))));
        }
    }

    // B5: non-numeric radius in the history filter
    @Test
    public void b5_invalidRadiusShowsFilterError() {
        try (ActivityScenario<RideHistoryActivity> ignored = ActivityScenario.launch(RideHistoryActivity.class)) {
            onView(withId(R.id.etRadius)).perform(scrollIntoView(), replaceText("abc"), closeSoftKeyboard());
            onView(withId(R.id.btnSearch)).perform(scrollIntoView(), click());

            onView(withId(R.id.tilRadius)).check(matches(hasInputError("Please enter a valid filter option.")));
        }
    }

    // ---- helpers ----

    private static void fillRideForm(String title, String maxRiders, String cost) {
        onView(withId(R.id.etRideTitle)).perform(scrollIntoView(), replaceText(title));
        onView(withId(R.id.etOrigin)).perform(scrollIntoView(), replaceText("USC"));
        onView(withId(R.id.etDestination)).perform(scrollIntoView(), replaceText("LAX"));
        onView(withId(R.id.etMaxRiders)).perform(scrollIntoView(), replaceText(maxRiders));
        onView(withId(R.id.etExpectedCost)).perform(scrollIntoView(), replaceText(cost), closeSoftKeyboard());
    }

    private static Matcher<View> rideRow(int containerId, String title) {
        return allOf(withParent(withId(containerId)), hasDescendant(withText(title)));
    }

    /** Firestore and Mapbox answer asynchronously, so poll until the view shows up. */
    private static ViewInteraction waitFor(Matcher<View> matcher) {
        long deadline = SystemClock.uptimeMillis() + NETWORK_TIMEOUT_MS;
        while (true) {
            try {
                ViewInteraction view = onView(matcher);
                view.check(matches(withEffectiveVisibility(VISIBLE)));
                return view;
            } catch (Throwable notYet) {
                if (SystemClock.uptimeMillis() > deadline) {
                    throw notYet;
                }
                SystemClock.sleep(250);
            }
        }
    }

    private static void waitForState(ActivityScenario<?> scenario, Lifecycle.State state) {
        long deadline = SystemClock.uptimeMillis() + NETWORK_TIMEOUT_MS;
        while (scenario.getState() != state) {
            assertTrue("Activity did not reach " + state + " (ride was not posted)",
                    SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(250);
        }
    }

    /**
     * Espresso's scrollTo() reports "view is not displayed" on the API 37 emulator even after scrolling,
     * so scroll the enclosing ScrollView directly to put the view a third of the way down the screen.
     */
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

    /** The app shows errors on the TextInputLayout, which Espresso's hasErrorText can't read. Null means no error. */
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
