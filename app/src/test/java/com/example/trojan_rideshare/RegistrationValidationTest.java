package com.example.trojan_rideshare;

import org.junit.Test;
import java.util.Calendar;
import static org.junit.Assert.*;

public class RegistrationValidationTest {

    @Test
    public void testUscIdValidation_valid10Digits() {
        String validId = "1234567890";
        assertTrue("10-digit USC ID should match regex", validId.matches("\\d{10}"));
    }

    @Test
    public void testUscIdValidation_invalidLength() {
        String shortId = "12345";
        String longId = "12345678901";
        assertFalse("Short ID should fail regex", shortId.matches("\\d{10}"));
        assertFalse("Long ID should fail regex", longId.matches("\\d{10}"));
    }

    @Test
    public void testUscEmailValidation_validUscEmail() {
        String email = "student@usc.edu";
        assertTrue("Email should end with @usc.edu", email.toLowerCase().endsWith("@usc.edu"));
    }

    @Test
    public void testUscEmailValidation_invalidNonUscEmail() {
        String email = "student@gmail.com";
        assertFalse("Non-USC email should be rejected", email.toLowerCase().endsWith("@usc.edu"));
    }

    @Test
    public void testGraduationYearValidation_activeRange() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        int maxGradYear = currentYear + 6;

        int validYear = currentYear + 2;
        assertTrue("Valid graduation year should be within range", validYear >= currentYear && validYear <= maxGradYear);

        int oldYear = currentYear - 1;
        int farFutureYear = maxGradYear + 1;
        assertFalse("Past year should be out of range", oldYear >= currentYear && oldYear <= maxGradYear);
        assertFalse("Far future year should be out of range", farFutureYear >= currentYear && farFutureYear <= maxGradYear);
    }

    @Test
    public void testNicknameDefaulting_emptyDefaultsToFullName() {
        User user = new User("test@usc.edu", "pass123", "John Doe", "", 2026, 1234567890L);
        assertEquals("Empty nickname should default to full name", "John Doe", user.getNickname());
    }

    @Test
    public void testUserPropertiesAndHashing() {
        User user = new User("user@usc.edu", "password", "Jane Doe", "Jane", 2028, 9876543210L);
        assertEquals("user@usc.edu", user.getEmail());
        assertEquals("Jane Doe", user.getFullName());
        assertEquals("Jane", user.getNickname());
        assertEquals(2028, user.getGraduationYear());
        assertEquals(9876543210L, user.getUscId());
        assertNotNull(user.getPassword());
    }
}
