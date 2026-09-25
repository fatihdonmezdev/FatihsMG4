package com.evsuite.abrp;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** WinClose's Comfort sequence: areas 0..3, UP=1 every 120 ms, then STOP=0. */
final class WindowClosePulse {
    static final long DURATION_MS = 5_000L;
    static final long INTERVAL_MS = 120L;

    interface Sender { boolean send(int area, int command); }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    static boolean run(BooleanSupplier allowed, Sender sender) {
        return run(allowed, sender, () -> System.nanoTime() / 1_000_000L, Thread::sleep);
    }

    static boolean run(BooleanSupplier allowed, Sender sender, LongSupplier clock, Sleeper sleeper) {
        if (!allowed.getAsBoolean() || Thread.currentThread().isInterrupted()) return false;
        long deadline = clock.getAsLong() + DURATION_MS;
        boolean ok = true;
        try {
            while (clock.getAsLong() < deadline) {
                if (Thread.currentThread().isInterrupted() || !allowed.getAsBoolean()) return false;
                for (int area = 0; area < 4; area++) {
                    if (!sender.send(area, 1)) ok = false;
                }
                if (!ok) break;
                long remaining = deadline - clock.getAsLong();
                if (remaining > 0) sleeper.sleep(Math.min(INTERVAL_MS, remaining));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        } finally {
            // Release every motor even after cancellation, a lost P/speed reading, or failure.
            for (int area = 0; area < 4; area++) {
                try {
                    if (!sender.send(area, 0)) ok = false;
                } catch (RuntimeException e) {
                    ok = false;
                }
            }
        }
        return ok;
    }
}
