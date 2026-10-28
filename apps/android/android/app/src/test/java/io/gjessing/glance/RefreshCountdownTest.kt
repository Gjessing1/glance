package io.gjessing.glance

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshCountdownTest {
    @Test fun countsTheLastRefreshDownAndWaitsAtOne() {
        // The last tapped refresh took 5.3 s: 6, 5, … 1, and 1 until this one is in.
        assertEquals(6, RefreshCoordinator.secondsLeft(0, 5_300, 0))
        assertEquals(5, RefreshCoordinator.secondsLeft(0, 5_300, 300))
        assertEquals(1, RefreshCoordinator.secondsLeft(0, 5_300, 4_400))
        assertEquals(1, RefreshCoordinator.secondsLeft(0, 5_300, 20_000))
    }

    @Test fun firstRefreshGuessesFiveSecondsAndLongOnesAreCapped() {
        assertEquals(5, RefreshCoordinator.secondsLeft(0, 0, 0))
        assertEquals(9, RefreshCoordinator.secondsLeft(0, 30_000, 0))
    }
}
