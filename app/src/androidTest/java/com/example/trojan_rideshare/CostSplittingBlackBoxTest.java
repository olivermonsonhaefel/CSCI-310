package com.example.trojan_rideshare;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingRootException;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.ViewInteraction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withSubstring;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.startsWith;

/**
 * Uses the project's configured Firebase service, matching the existing black-box tests.
 * Each test creates uniquely named data and removes it in {@link #removeFixtures()}.
 */
@RunWith(AndroidJUnit4.class)
public class CostSplittingBlackBoxTest {
    private static final long TIMEOUT_MS = 20_000L;
    private static FirebaseAuth auth;
    private static FirebaseFirestore db;

    private final String rideId = "feature4-" + UUID.randomUUID();
    private final String title = "Feature 4 ride " + rideId;
    private final String password = UUID.randomUUID().toString();
    private final List<TestUser> users = new ArrayList<>();
    private TestUser organizer;
    private TestUser rider;

    @BeforeClass
    public static void connectFirebase() {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    @After
    public void removeFixtures() throws Exception {
        if (users.isEmpty()) return;
        try {
            WriteBatch batch = db.batch();
            batch.delete(db.collection("rides").document(rideId));
            for (TestUser user : users) {
                batch.delete(db.collection("users").document(user.id));
                if (user != organizer) {
                    String expenseId = CostSplitManager.rideExpenseId(rideId, user.id);
                    batch.delete(db.collection("expenses").document(expenseId));
                    batch.delete(db.collection("riderFlags").document(expenseId));
                }
            }
            awaitTask(batch.commit());
        } finally {
            try {
                for (TestUser user : users) {
                    signIn(user);
                    awaitTask(auth.getCurrentUser().delete());
                }
            } finally {
                auth.signOut();
            }
        }
    }

    @Test
    public void testCompleteRideWithValidCost() throws Exception {
        seedRide(1, false);
        try (ActivityScenario<BalanceActivity> ignored = ActivityScenario.launch(BalanceActivity.class)) {
            openCompletionCostForm();
            enterActualCost("24.50");
            dialogButton(R.string.cost_split_review).perform(click());
            waitForDialog(withSubstring(string(R.string.cost_split_preview_total, "$24.50")));
            dialogButton(R.string.balance_save).perform(click());
            waitFor(onView(withId(R.id.balanceComplete)), isEnabled());
        }

        try (ActivityScenario<RideHistoryActivity> ignored = ActivityScenario.launch(RideHistoryActivity.class)) {
            assertHistoryStatus(1);
            assertHistoryCost(string(R.string.cost_split_organizer_cost, "24.50"));
        }
    }

    @Test
    public void testRejectInvalidActualCost() throws Exception {
        seedRide(1, false);
        try (ActivityScenario<BalanceActivity> ignored = ActivityScenario.launch(BalanceActivity.class)) {
            openCompletionCostForm();
            enterActualCost("10.123");
            dialogButton(R.string.cost_split_review).perform(click());
            onView(actualCostField()).inRoot(isDialog()).check(matches(allOf(
                    withText("10.123"), hasErrorText(string(R.string.balance_amount_error)))));
            onView(withText(R.string.cost_split_preview)).inRoot(isDialog()).check(doesNotExist());
            dialogButton(R.string.action_cancel).perform(click());
            waitFor(onView(withId(R.id.balanceSplit)), isEnabled()).perform(reveal(), click());
            selectRide();
            waitForDialog(actualCostField()).check(matches(withText("")));
        }

        try (ActivityScenario<RideHistoryActivity> ignored = ActivityScenario.launch(RideHistoryActivity.class)) {
            assertHistoryStatus(1);
            assertHistoryCost(string(R.string.label_cost_not_split));
        }
    }

    @Test
    public void testDisplayParticipantShares() throws Exception {
        seedRide(3, false);
        try (ActivityScenario<BalanceActivity> ignored = ActivityScenario.launch(BalanceActivity.class)) {
            openCompletionCostForm();
            onView(withText(R.string.balance_split_mode_equal)).inRoot(isDialog()).check(matches(isDisplayed()));
            enterActualCost("12.00");
            dialogButton(R.string.cost_split_review).perform(click());
            waitForDialog(allOf(withId(android.R.id.message),
                    withSubstring(string(R.string.cost_split_preview_total, "$12.00")),
                    withSubstring(string(R.string.cost_split_preview_share, "You", "$4.00")),
                    withSubstring(string(R.string.cost_split_preview_share, "Feature Rider A", "$4.00")),
                    withSubstring(string(R.string.cost_split_preview_share, "Feature Rider B", "$4.00"))));
            dialogButton(R.string.balance_save).check(matches(isEnabled()));
        }
    }

    @Test
    public void testMarkOwnSharePaid() throws Exception {
        seedRide(2, true);
        signIn(rider);
        try (ActivityScenario<BalanceActivity> ignored = ActivityScenario.launch(BalanceActivity.class)) {
            waitForExpenseStatus(R.string.balance_unpaid);
            onView(expenseText(withText(R.string.balance_record))).perform(reveal(), click());
            dialogButton(R.string.balance_payment_succeeded).perform(click());
            waitForExpenseStatus(R.string.balance_paid);
            onView(expenseText(withText(R.string.balance_record))).check(doesNotExist());
        }

        try (ActivityScenario<RideHistoryActivity> ignored = ActivityScenario.launch(RideHistoryActivity.class)) {
            assertHistoryCost(string(R.string.label_your_cost, "6.00", string(R.string.status_paid)));
        }
    }

    @Test
    public void testFlagUnpaidRider() throws Exception {
        seedRide(2, true);
        try (ActivityScenario<BalanceActivity> ignored = ActivityScenario.launch(BalanceActivity.class)) {
            ViewInteraction profile = onView(expenseText(withText(
                    string(R.string.cost_split_profile, "Feature Rider A"))));
            waitFor(profile, isEnabled()).perform(reveal(), click());
            waitForDialog(withText(R.string.cost_split_profile_clear));
            dialogButton(android.R.string.ok).perform(click());

            waitFor(onView(withId(R.id.balanceFlag)), isEnabled()).perform(reveal(), click());
            selectRide();
            waitForDialog(withText(string(R.string.balance_owes_you, "Feature Rider A", "$6.00"))).perform(click());
            dialogButton(R.string.balance_flag).perform(click());

            waitFor(profile, isEnabled()).perform(reveal(), click());
            waitForDialog(withText(R.string.cost_split_profile_flagged));
            onView(withText("Feature Rider A")).inRoot(isDialog()).check(matches(isDisplayed()));
        }
    }

    private void openCompletionCostForm() {
        waitFor(onView(withId(R.id.balanceComplete)), isEnabled()).perform(reveal(), click());
        selectRide();
        dialogButton(R.string.balance_complete).perform(click());
        waitForDialog(actualCostField());
    }

    private void selectRide() {
        waitForDialog(withText(startsWith(title + " • "))).perform(click());
    }

    private void enterActualCost(String amount) {
        onView(actualCostField()).inRoot(isDialog()).perform(replaceText(amount), closeSoftKeyboard());
    }

    private static Matcher<View> actualCostField() {
        return allOf(isAssignableFrom(TextInputEditText.class), withHint(R.string.balance_actual_total));
    }

    private static void waitForExpenseStatus(int status) {
        Matcher<View> label = withText(startsWith(string(status) + " •"));
        waitFor(onView(expenseText(label)), withEffectiveVisibility(VISIBLE))
                .perform(reveal()).check(matches(isDisplayed()));
    }

    private static Matcher<View> expenseText(Matcher<View> view) {
        return allOf(isDescendantOfA(withId(R.id.balanceExpenses)), view);
    }

    private void assertHistoryStatus(int participants) {
        String expected = string(R.string.label_history_status, "Completed", participants);
        waitFor(historyField(R.id.tvStatus), withText(expected));
    }

    private void assertHistoryCost(String expected) {
        waitFor(historyField(R.id.tvCost), withText(expected)).perform(reveal())
                .check(matches(withEffectiveVisibility(VISIBLE)));
    }

    private ViewInteraction historyField(int id) {
        return onView(allOf(withId(id), isDescendantOfA(allOf(withParent(withId(R.id.historyContainer)),
                hasDescendant(withText(title))))));
    }

    private static ViewInteraction dialogButton(int text) {
        return onView(allOf(isAssignableFrom(android.widget.Button.class), withText(text))).inRoot(isDialog());
    }

    private static ViewInteraction waitForDialog(Matcher<View> matcher) {
        return waitFor(onView(matcher).inRoot(isDialog()), isDisplayed());
    }

    private static ViewInteraction waitFor(ViewInteraction view, Matcher<View> condition) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (true) {
            try {
                view.check(matches(condition));
                return view;
            } catch (NoMatchingRootException | NoMatchingViewException | AssertionError pending) {
                if (SystemClock.uptimeMillis() >= deadline) throw pending;
                SystemClock.sleep(100);
            }
        }
    }

    private static ViewAction reveal() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return withEffectiveVisibility(VISIBLE);
            }

            @Override
            public String getDescription() {
                return "scroll the view into the viewport";
            }

            @Override
            public void perform(UiController controller, View view) {
                Rect bounds = new Rect();
                view.getDrawingRect(bounds);
                view.requestRectangleOnScreen(bounds, true);
                controller.loopMainThreadUntilIdle();
            }
        };
    }

    private void seedRide(int count, boolean withAllocation) throws Exception {
        organizer = createUser("Feature Organizer");
        if (count >= 2) rider = createUser("Feature Rider A");
        if (count == 3) createUser("Feature Rider B");
        signIn(organizer);

        List<String> participants = new ArrayList<>();
        for (TestUser user : users) participants.add(user.id);
        Date departure = new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(4));
        Map<String, Object> ride = new HashMap<>();
        ride.put("rideID", rideId);
        ride.put("title", title);
        ride.put("organizer", organizer.profile());
        ride.put("riders", participants);
        ride.put("origin", location("USC", 34.02, -118.29));
        ride.put("destination", location("LAX", 33.94, -118.41));
        ride.put("departureTime", departure);
        ride.put("rideStatus", withAllocation ? "COMPLETED" : "ON_TIME");
        ride.put("maxRiders", 3);
        ride.put("numOfRiders", count);
        ride.put("totalExpectedCost", 12.00);
        ride.put("note", "");

        WriteBatch batch = db.batch();
        for (TestUser user : users) {
            batch.set(db.collection("users").document(user.id), user.profile());
        }
        if (withAllocation) {
            Map<String, Double> shares = new HashMap<>();
            shares.put(organizer.id, 6.00);
            shares.put(rider.id, 6.00);
            ride.put("actualTotalCost", 12.00);
            ride.put("organizerShare", 6.00);
            ride.put("costShares", shares);
            ride.put("costSplitSavedAt", departure);
            String expenseId = CostSplitManager.rideExpenseId(rideId, rider.id);
            Map<String, Object> expense = new HashMap<>();
            expense.put("id", expenseId);
            expense.put("rideID", rideId);
            expense.put("amount", 6.00);
            expense.put("paid", false);
            expense.put("createdAt", departure);
            expense.put("payer", rider.profile());
            expense.put("payee", organizer.profile());
            batch.set(db.collection("expenses").document(expenseId), expense);
        }
        batch.set(db.collection("rides").document(rideId), ride);
        awaitTask(batch.commit());
    }

    private TestUser createUser(String name) throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String id = awaitTask(auth.createUserWithEmailAndPassword(email, password)).getUser().getUid();
        TestUser user = new TestUser(id, email, name);
        users.add(user);
        return user;
    }

    private void signIn(TestUser user) throws Exception {
        awaitTask(auth.signInWithEmailAndPassword(user.email, password));
    }

    private static <T> T awaitTask(Task<T> task) throws Exception {
        return Tasks.await(task, TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static String string(int id, Object... arguments) {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getString(id, arguments);
    }

    private static Map<String, Object> location(String name, double lat, double lon) {
        Map<String, Object> location = new HashMap<>();
        location.put("POIName", name);
        location.put("lat", lat);
        location.put("lon", lon);
        return location;
    }

    private static final class TestUser {
        final String id;
        final String email;
        final String name;

        TestUser(String id, String email, String name) {
            this.id = id;
            this.email = email;
            this.name = name;
        }

        Map<String, Object> profile() {
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", id);
            profile.put("email", email);
            profile.put("fullName", name);
            profile.put("nickname", name);
            return profile;
        }
    }
}
