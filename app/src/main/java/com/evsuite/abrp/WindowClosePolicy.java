package com.evsuite.abrp;

import java.util.function.LongSupplier;

/** One close per opening event; events during an existing close are consumed, not queued. */
final class WindowClosePolicy {
    /**
     * WinClose's TRIGGER_COOLDOWN_MS. Getting back in to fetch something reopens the door,
     * and without this the glass would run a second full pulse each time.
     */
    static final long COOLDOWN_MS = 60_000L;

    private final LongSupplier clock;
    private boolean closing;
    private Long lastFinishedAt;

    WindowClosePolicy() {
        // Elapsed time, not wall clock: a head-unit time correction must not open the gate.
        this(() -> System.nanoTime() / 1_000_000L);
    }

    WindowClosePolicy(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized boolean begin(Boolean parked, Float speedKmh) {
        if (closing || !Boolean.TRUE.equals(parked)
                || speedKmh == null || !Float.isFinite(speedKmh) || speedKmh != 0f) {
            return false;
        }
        if (lastFinishedAt != null && clock.getAsLong() - lastFinishedAt < COOLDOWN_MS) {
            return false;
        }
        closing = true;
        return true;
    }

    synchronized void finish() {
        closing = false;
        lastFinishedAt = clock.getAsLong();
    }
}
