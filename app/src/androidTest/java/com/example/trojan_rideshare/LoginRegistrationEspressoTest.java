package com.example.trojan_rideshare;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

@RunWith(AndroidJUnit4.class)
public class LoginRegistrationEspressoTest {

    @Rule
    public ActivityScenarioRule<RegisterActivity> registerActivityRule =
            new ActivityScenarioRule<>(RegisterActivity.class);

    @Rule
    public ActivityScenarioRule<LoginActivity> loginActivityRule =
            new ActivityScenarioRule<>(LoginActivity.class);

    @Test
    public void test1_registerActivityLaunched() {
        onView(withId(R.id.btnRegister)).check(matches(isDisplayed()));
    }

    @Test
    public void test2_signupRejectsInvalidUscId() {
        onView(withId(R.id.etUscId)).perform(typeText("12345"), closeSoftKeyboard());
        onView(withId(R.id.btnRegister)).perform(click());
        onView(withId(R.id.btnRegister)).check(matches(isDisplayed()));
    }

    @Test
    public void test3_signupRejectsNonUscEmail() {
        onView(withId(R.id.etEmail)).perform(typeText("student@gmail.com"), closeSoftKeyboard());
        onView(withId(R.id.btnRegister)).perform(click());
        onView(withId(R.id.btnRegister)).check(matches(isDisplayed()));
    }

    @Test
    public void test4_signupRejectsInvalidGradYear() {
        onView(withId(R.id.etGradYear)).perform(typeText("2010"), closeSoftKeyboard());
        onView(withId(R.id.btnRegister)).perform(click());
        onView(withId(R.id.btnRegister)).check(matches(isDisplayed()));
    }

    @Test
    public void test5_loginActivityLaunched() {
        onView(withId(R.id.btnLogin)).check(matches(isDisplayed()));
    }
}
