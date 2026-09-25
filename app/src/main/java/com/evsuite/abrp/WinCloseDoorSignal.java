package com.evsuite.abrp;

/** WinClose's TX_DOOR_SENSOR=6: zero is open; suppress repeated identical callbacks. */
final class WinCloseDoorSignal {
    private int previous = -1;

    boolean opening(int raw) {
        if (raw < 0) return false;
        boolean opening = raw == 0 && previous != 0;
        previous = raw;
        return opening;
    }

    /**
     * Records a reading that can never count as an opening.
     *
     * A fresh listener registration replays the door's existing state. Treating that replay
     * as an edge would close the windows on a driver who is halfway out when the binder
     * reconnects — and the binder reconnects every five seconds while it is down.
     */
    void seed(int raw) {
        if (raw >= 0) previous = raw;
    }

    void reset() { previous = -1; }
}
