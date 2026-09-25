package com.evsuite.abrp;

import org.junit.Test;
import static org.junit.Assert.*;

public class WinCloseDoorSignalTest {
    @Test public void firstOpenEventWorksWithoutDrivingOrArming() {
        assertTrue(new WinCloseDoorSignal().opening(0));
    }

    @Test public void repeatedOpenIsIgnoredButAReopeningTriggers() {
        WinCloseDoorSignal signal = new WinCloseDoorSignal();
        assertFalse(signal.opening(2));
        assertTrue(signal.opening(0));
        assertFalse(signal.opening(0));
        assertFalse(signal.opening(1));
        assertFalse(signal.opening(2));
        assertTrue(signal.opening(0));
    }

    @Test public void aSeededOpenDoorIsNotTreatedAsSomeoneOpeningIt() {
        WinCloseDoorSignal signal = new WinCloseDoorSignal();
        // What a fresh listener registration replays: the door is already open.
        signal.seed(0);
        assertFalse(signal.opening(0));
        // Closing it and opening it again is a real edge.
        assertFalse(signal.opening(2));
        assertTrue(signal.opening(0));
    }

    @Test public void seedingAClosedDoorStillArmsTheNextOpening() {
        WinCloseDoorSignal signal = new WinCloseDoorSignal();
        signal.seed(2);
        assertTrue(signal.opening(0));
    }

    @Test public void seedIgnoresAnInvalidReadingRatherThanRecordingIt() {
        WinCloseDoorSignal signal = new WinCloseDoorSignal();
        signal.seed(0);
        signal.seed(-1);
        assertFalse(signal.opening(0));
    }

    @Test public void invalidReadingDoesNotInventAnotherOpening() {
        WinCloseDoorSignal signal = new WinCloseDoorSignal();
        assertTrue(signal.opening(0));
        assertFalse(signal.opening(-1));
        assertFalse(signal.opening(0));
        signal.reset();
        assertTrue(signal.opening(0));
    }
}
