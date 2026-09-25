package com.evsuite.abrp;

import org.junit.Test;
import static org.junit.Assert.*;

public class WindowClosePolicyTest {
    @Test public void onlyKnownParkAndStandstillCanStart() {
        WindowClosePolicy policy = new WindowClosePolicy();
        assertFalse(policy.begin(null, 0f));
        assertFalse(policy.begin(false, 0f));
        assertFalse(policy.begin(true, null));
        assertFalse(policy.begin(true, 0.1f));
        assertFalse(policy.begin(true, -1f));
        assertFalse(policy.begin(true, Float.NaN));
        assertFalse(policy.begin(true, Float.POSITIVE_INFINITY));
        assertTrue(policy.begin(true, 0f));
    }

    @Test public void openingsDuringACloseCannotExtendOrQueueIt() {
        long[] now = {0L};
        WindowClosePolicy policy = new WindowClosePolicy(() -> now[0]);
        assertTrue(policy.begin(true, 0f));
        assertFalse(policy.begin(true, 0f));
        assertFalse(policy.begin(true, 0f));
        policy.finish();
        // A later, distinct opening needs no previous drive or arming timer.
        now[0] += WindowClosePolicy.COOLDOWN_MS;
        assertTrue(policy.begin(true, 0f));
    }

    @Test public void reopeningTheDoorWithinTheCooldownDoesNotCloseAgain() {
        long[] now = {0L};
        WindowClosePolicy policy = new WindowClosePolicy(() -> now[0]);
        assertTrue(policy.begin(true, 0f));
        now[0] += 5_000L;
        policy.finish();
        // Reaching back into the car reopens the door; the glass must not run a second pulse.
        now[0] += WindowClosePolicy.COOLDOWN_MS - 1;
        assertFalse(policy.begin(true, 0f));
        now[0] += 1;
        assertTrue(policy.begin(true, 0f));
    }

    @Test public void theCooldownIsMeasuredFromTheEndOfTheCloseNotItsStart() {
        long[] now = {0L};
        WindowClosePolicy policy = new WindowClosePolicy(() -> now[0]);
        assertTrue(policy.begin(true, 0f));
        now[0] += WindowClosePulse.DURATION_MS;
        policy.finish();
        now[0] += WindowClosePolicy.COOLDOWN_MS - WindowClosePulse.DURATION_MS;
        assertFalse(policy.begin(true, 0f));
    }
}
