package com.example.trojan_rideshare;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CostSplittingWhiteBoxTest {
    private static final String ORGANIZER = "organizer";
    private static final String RIDER_A = "rider-a";
    private static final String RIDER_B = "rider-b";
    private static final List<String> PARTICIPANTS = Arrays.asList(ORGANIZER, RIDER_A, RIDER_B);

    @Test
    public void testEqualSplitEven() {
        Map<String, Double> allocation = calculate(12.00, CostSplitMode.EQUAL, null);
        assertEquals(shares(4.00, 4.00, 4.00), allocation);
        assertTotalCents(1200, allocation);
    }

    @Test
    public void testEqualSplitRounding() {
        Map<String, Double> allocation = calculate(10.00, CostSplitMode.EQUAL, null);
        assertEquals(shares(3.34, 3.33, 3.33), allocation);
        assertTotalCents(1000, allocation);
    }

    @Test
    public void testSingleRiderSplit() {
        Map<String, Double> allocation = CostSplitManager.calculateShares(
                25.50, Collections.singletonList(ORGANIZER), ORGANIZER, CostSplitMode.EQUAL, null);
        assertEquals(Collections.singletonMap(ORGANIZER, 25.50), allocation);
        assertTotalCents(2550, allocation);
    }

    @Test
    public void testValidCustomSplit() {
        Map<String, Double> allocation = calculate(10.00, CostSplitMode.CUSTOM, shares(0.00, 4.00, 6.00));
        assertEquals(shares(0.00, 4.00, 6.00), allocation);
        assertTotalCents(1000, allocation);
    }

    @Test
    public void testInvalidCustomSplit() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> calculate(10.00, CostSplitMode.CUSTOM, shares(3.00, 3.00, 3.00)));
        assertEquals("Shares must add up to the total", error.getMessage());
    }

    private static Map<String, Double> calculate(double total, CostSplitMode mode, Map<String, Double> custom) {
        return CostSplitManager.calculateShares(total, PARTICIPANTS, ORGANIZER, mode, custom);
    }

    private static Map<String, Double> shares(double organizer, double riderA, double riderB) {
        Map<String, Double> allocation = new HashMap<>();
        allocation.put(ORGANIZER, organizer);
        allocation.put(RIDER_A, riderA);
        allocation.put(RIDER_B, riderB);
        return allocation;
    }

    private static void assertTotalCents(long expected, Map<String, Double> allocation) {
        long total = allocation.values().stream()
                .mapToLong(share -> BigDecimal.valueOf(share).movePointRight(2).longValueExact())
                .sum();
        assertEquals(expected, total);
    }
}
