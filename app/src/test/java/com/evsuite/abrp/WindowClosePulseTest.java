package com.evsuite.abrp;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class WindowClosePulseTest {
    private final List<String> writes = new ArrayList<>();
    private long now;

    private boolean send(int area, int command) {
        writes.add(area + ":" + command);
        return true;
    }

    private void assertStoppedAll() {
        assertEquals(Arrays.asList("0:0", "1:0", "2:0", "3:0"),
                writes.subList(writes.size() - 4, writes.size()));
    }

    @Test public void comfortPulseTargetsAllFourAndEndsAtFiveSeconds() {
        assertTrue(WindowClosePulse.run(() -> true, this::send, () -> now, ms -> now += ms));
        assertEquals(5_000L, now);
        assertEquals(Arrays.asList("0:1", "1:1", "2:1", "3:1"), writes.subList(0, 4));
        // Forty-two UP batches at 120 ms intervals, followed by a single STOP batch.
        assertEquals(42 * 4 + 4, writes.size());
        assertStoppedAll();
    }

    @Test public void refusedStartWritesNothing() {
        assertFalse(WindowClosePulse.run(() -> false, this::send, () -> now, ms -> now += ms));
        assertTrue(writes.isEmpty());
    }

    @Test public void leavingParkMovingOrDisablingStopsAtNextPulse() {
        assertFalse(WindowClosePulse.run(() -> now < 120L, this::send, () -> now, ms -> now += ms));
        assertEquals(8, writes.size());
        assertStoppedAll();
    }

    @Test public void interruptionReleasesEveryWindowAndPreservesInterrupt() {
        try {
            assertFalse(WindowClosePulse.run(() -> true, this::send, () -> now,
                    ms -> { throw new InterruptedException(); }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertStoppedAll();
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void oneFailedCommandEndsPulseAndReleasesEveryWindow() {
        assertFalse(WindowClosePulse.run(() -> true, (area, command) -> {
            send(area, command);
            return area != 2 || command == 0;
        }, () -> now, ms -> now += ms));
        assertEquals(0L, now);
        assertStoppedAll();
    }

    @Test public void unexpectedFailureStillReleasesEveryWindow() {
        try {
            WindowClosePulse.run(() -> true, (area, command) -> {
                send(area, command);
                if (area == 1 && command == 1) throw new IllegalStateException();
                return true;
            }, () -> now, ms -> now += ms);
            fail("Expected sender failure");
        } catch (IllegalStateException expected) {
            assertStoppedAll();
        }
    }

    @Test public void aFailedStopDoesNotPreventOtherStops() {
        assertFalse(WindowClosePulse.run(() -> true, (area, command) -> {
            send(area, command);
            if (area == 0 && command == 0) throw new IllegalStateException();
            return true;
        }, () -> now, ms -> now += ms));
        assertStoppedAll();
    }
}
