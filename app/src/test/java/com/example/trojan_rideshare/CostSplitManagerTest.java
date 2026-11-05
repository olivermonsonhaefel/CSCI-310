package com.example.trojan_rideshare;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Checklist examples and invalid inputs, independent of Android/Firebase. */
public class CostSplitManagerTest {
    private static final List<String> THREE = Arrays.asList("organizer", "a", "b");

    @Test
    public void tenDollarsAcrossThreeParticipantsPreservesEveryCent() {
        Map<String, Double> allocation = equal(10, THREE);
        assertEquals(3.34, allocation.get("organizer"), 0);
        assertEquals(3.33, allocation.get("a"), 0);
        assertEquals(3.33, allocation.get("b"), 0);
        assertEquals(1000, allocation.values().stream()
                .mapToLong(value -> Math.round(value * 100)).sum());
    }

    @Test
    public void soloOrganizerBearsWholeCost() {
        Map<String, Double> allocation = equal(12.75, Collections.singletonList("organizer"));
        assertEquals(12.75, allocation.get("organizer"), 0);
        assertEquals(1, allocation.size());
    }

    @Test
    public void smallTotalCanProduceZeroShares() {
        Map<String, Double> allocation = equal(0.01, THREE);
        assertEquals(0.01, allocation.get("organizer"), 0);
        assertEquals(0, allocation.get("a"), 0);
        assertEquals(0, allocation.get("b"), 0);
    }

    @Test
    public void customSharesAllowZeroAndIncludeOrganizer() {
        Map<String, Double> allocation = custom(10, shares(0, 4.25, 5.75));
        assertEquals(0, allocation.get("organizer"), 0);
        assertEquals(4.25, allocation.get("a"), 0);
        assertEquals(5.75, allocation.get("b"), 0);
    }

    @Test
    public void zeroNegativeNonfiniteAndFractionalCentTotalsAreRejected() {
        for (double total : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1.001}) {
            assertThrows(IllegalArgumentException.class, () -> equal(total, THREE));
        }
    }

    @Test
    public void customTotalMustMatchExactly() {
        assertThrows(IllegalArgumentException.class, () -> custom(10, shares(3.33, 3.33, 3.33)));
        assertThrows(IllegalArgumentException.class, () -> custom(10, shares(4, 4, 4)));
        // A decimal sum is compared in cents, without binary floating-point addition errors.
        assertEquals(60, custom(0.60, shares(0.10, 0.20, 0.30)).values().stream()
                .mapToLong(value -> Math.round(value * 100)).sum());
    }

    @Test
    public void invalidCustomSharesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> custom(10, shares(-1, 5, 6)));
        assertThrows(IllegalArgumentException.class, () -> custom(10, shares(1.001, 4, 4.999)));
        assertThrows(IllegalArgumentException.class, () -> custom(10, shares(Double.NaN, 4, 6)));
        Map<String, Double> missing = shares(2, 4, 4);
        missing.remove("organizer");
        assertThrows(IllegalArgumentException.class, () -> custom(10, missing));
        Map<String, Double> extra = shares(2, 4, 4);
        extra.put("outsider", 0.0);
        assertThrows(IllegalArgumentException.class, () -> custom(10, extra));
    }

    @Test
    public void invalidMembershipIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> equal(10, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> equal(10, Arrays.asList("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> equal(10, Arrays.asList("organizer", "a", "a")));
    }

    @Test
    public void allocationCannotBeChangedThroughInputOrOutputMaps() {
        Map<String, Double> input = shares(2, 4, 4);
        Map<String, Double> allocation = custom(10, input);
        input.put("a", 0.0);
        assertEquals(4, allocation.get("a"), 0);
        assertThrows(UnsupportedOperationException.class, () -> allocation.put("a", 0.0));
    }

    private static Map<String, Double> equal(double total, List<String> riders) {
        return CostSplitManager.calculateShares(total, riders, "organizer", CostSplitMode.EQUAL, null);
    }

    private static Map<String, Double> custom(double total, Map<String, Double> shares) {
        return CostSplitManager.calculateShares(total, THREE, "organizer", CostSplitMode.CUSTOM, shares);
    }

    private static Map<String, Double> shares(double organizer, double a, double b) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("organizer", organizer);
        result.put("a", a);
        result.put("b", b);
        return result;
    }
}
