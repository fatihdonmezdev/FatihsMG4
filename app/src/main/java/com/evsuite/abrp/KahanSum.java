package com.evsuite.abrp;

/**
 * Neumaier–Kahan compensated summation.
 *
 * Consumption is integrated from thousands of sub-watt-hour slices per drive. A plain
 * {@code sum += x} loses the low bits of every one of them, and the error is one-signed,
 * so it accumulates rather than cancelling. Ported from DriveHub_Dort, where this is what
 * keeps the lifetime counter honest over months.
 */
final class KahanSum {

    private double sum;
    private double compensation;

    void add(double input) {
        double y = input - compensation;
        double t = sum + y;
        compensation = (t - sum) - y;
        sum = t;
    }

    double get() {
        return sum;
    }

    /** Clears the total and the compensation term. */
    void reset() {
        sum = 0d;
        compensation = 0d;
    }

    /** Seeds the total from persistent storage; the compensation term starts clean. */
    void setTotal(double total) {
        sum = total;
        compensation = 0d;
    }
}
