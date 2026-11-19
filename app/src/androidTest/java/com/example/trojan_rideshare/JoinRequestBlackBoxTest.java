package com.example.trojan_rideshare;

import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;

import org.hamcrest.Matcher;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Black-box tests for join requests (UCS-2.2.13, UCS-2.3.2 to UCS-2.3.9).
 * Uses two accounts: espresso@usc.edu (organizer) and espresso.rider@usc.edu (requester).
 * b3 and b5 use the request b2 sends, so the tests run in name order. Needs internet.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class JoinRequestBlackBoxTest {

    private static final long TIMEOUT_MS = 30_000;
    private static final String PASSWORD = "espresso";
    private static Ride openRide;
    private static Ride cancelledRide;

    // The organizer posts one open and one cancelled ride, then the requester logs in
    @BeforeClass
    public static void setUp() throws Exception {
        signIn("espresso@usc.edu");
        openRide = postRide(Status.ON_TIME);
        cancelledRide = postRide(Status.CANCELLED);
        if (!trySignIn("espresso.rider@usc.edu")) {
            registerRider(); // first run only: the rider account doesn't exist yet
        }
    }

    // B1 (UCS-2.3.4): a note one character over the 250 limit shows the count and disables the button
    @Test
    public void b1_noteOver250CharactersIsBlocked() {
        try (ActivityScenario<RideDetailActivity> ignored = openDetail(openRide)) {
            onView(withId(R.id.etJoinNote)).perform(scrollIntoView(), replaceText("a".repeat(251)), closeSoftKeyboard());

            onView(withText("Reduce character count (251/250).")).check(matches(isDisplayed()));
            onView(withId(R.id.btnRequestJoin)).check(matches(not(isEnabled())));
        }
    }

    // B2 (UCS-2.3.2, 2.3.6, 2.3.8): a valid request closes the screen and is listed as Pending
    @Test
    public void b2_validRequestIsListedAsPending() {
        try (ActivityScenario<RideDetailActivity> scenario = openDetail(openRide)) {
            onView(withId(R.id.etJoinNote)).perform(scrollIntoView(), replaceText("One carry-on bag."), closeSoftKeyboard());
            onView(withId(R.id.btnRequestJoin)).perform(scrollIntoView(), click());
            waitUntil(() -> assertEquals(Lifecycle.State.DESTROYED, scenario.getState()));
        }
        try (ActivityScenario<JoinRequestsActivity> ignored = ActivityScenario.launch(JoinRequestsActivity.class)) {
            Matcher<View> row = allOf(withParent(withId(R.id.myRequestsContainer)),
                    hasDescendant(withText(openRide.getTitle())));
            waitUntil(() -> onView(row).check(matches(hasDescendant(withText("Request status: Pending")))));
        }
    }

    // B3 (UCS-2.3.7): requesting again while Pending is refused, so the screen stays open
    @Test
    public void b3_secondRequestWhilePendingIsRefused() {
        try (ActivityScenario<RideDetailActivity> scenario = openDetail(openRide)) {
            onView(withId(R.id.btnRequestJoin)).perform(scrollIntoView(), click());
            waitUntil(() -> onView(withId(R.id.btnRequestJoin)).check(matches(isEnabled()))); // answer came back
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        }
    }

    // B4 (UCS-2.2.13): a cancelled ride shows the notice and hides Request to Join
    @Test
    public void b4_cancelledRideCannotBeRequested() {
        try (ActivityScenario<RideDetailActivity> ignored = launchDetail(cancelledRide)) {
            waitUntil(() -> onView(withId(R.id.tvUnavailable)).check(matches(isDisplayed())));
            onView(withId(R.id.btnRequestJoin)).check(matches(withEffectiveVisibility(GONE)));
        }
    }

    // B5 (UCS-2.3.9): the organizer accepts the request and it leaves the Pending list
    @Test
    public void b5_organizerAcceptsRequest() throws Exception {
        signIn("espresso@usc.edu");
        try (ActivityScenario<JoinRequestsActivity> ignored = ActivityScenario.launch(JoinRequestsActivity.class)) {
            Matcher<View> row = allOf(withParent(withId(R.id.pendingContainer)),
                    hasDescendant(withText(openRide.getTitle())));
            waitUntil(() -> onView(row).check(matches(hasDescendant(withText("1/3 riders")))));

            onView(allOf(withId(R.id.btnAccept), isDescendantOfA(row))).perform(scrollIntoView(), click());
            waitUntil(() -> onView(row).check(doesNotExist()));
        }
    }

    // ---- helpers ----

    /** Opens the ride's detail screen and waits for the ride to load (Request to Join turns on). */
    private static ActivityScenario<RideDetailActivity> openDetail(Ride ride) {
        ActivityScenario<RideDetailActivity> scenario = launchDetail(ride);
        waitUntil(() -> onView(withId(R.id.btnRequestJoin)).check(matches(isEnabled())));
        return scenario;
    }

    private static ActivityScenario<RideDetailActivity> launchDetail(Ride ride) {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), RideDetailActivity.class);
        intent.putExtra(RideDetailActivity.EXTRA_RIDE_ID, ride.getRideID());
        return ActivityScenario.launch(intent);
    }

    private static Ride postRide(Status status) throws Exception {
        User organizer = AuthManager.getInstance().getCurrentUser();
        Ride ride = new Ride(UUID.randomUUID().toString(), organizer, "Espresso " + status + " " + System.currentTimeMillis(),
                new Location("USC", 34.0224, -118.2851), new Location("LAX", 33.9416, -118.4085),
                new Date(System.currentTimeMillis() + 2 * 24 * 60 * 60 * 1000L), 3, 30.00, "",
                Collections.singletonList(organizer.getId()), status.name());
        Tasks.await(FirebaseFirestore.getInstance().collection("rides").document(ride.getRideID())
                .set(RideManager.toMap(ride)), TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return ride;
    }

    private static void signIn(String email) throws InterruptedException {
        assertTrue("Could not log in as " + email, trySignIn(email));
    }

    private static boolean trySignIn(String email) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                AuthManager.getInstance().authenticateUserByPassword(email, PASSWORD, ok -> {
                    success.set(ok);
                    done.countDown();
                }));
        return done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) && success.get();
    }

    private static void registerRider() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                AuthManager.getInstance().registerUserWithStatus("Espresso Rider", "espresso.rider@usc.edu",
                        9990000310L, 2027, PASSWORD, "Rider", status -> {
                            result.set(status);
                            done.countDown();
                        }));
        assertTrue("Registration timed out", done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("Could not register espresso.rider@usc.edu", "SUCCESS", result.get());
    }

    /** Firestore answers asynchronously, so retry the check until it passes or times out. */
    private static void waitUntil(Runnable check) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (true) {
            try {
                check.run();
                return;
            } catch (Throwable notYet) {
                if (SystemClock.uptimeMillis() > deadline) throw notYet;
                SystemClock.sleep(250);
            }
        }
    }

    /** Same workaround as the other black-box suites: Espresso's scrollTo() fails on the API 37 emulator. */
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
}