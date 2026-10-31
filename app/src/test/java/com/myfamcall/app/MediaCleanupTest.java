package com.myfamcall.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class MediaCleanupTest {
    @Test public void faultyCameraDoesNotPreventMicrophoneAndPeerRelease() {
        List<String> released = new ArrayList<>();
        IllegalStateException cameraFailure = new IllegalStateException("camera driver failed");
        List<Exception> failures = new MediaCleanup().releaseAll(
                () -> { released.add("camera"); throw cameraFailure; },
                () -> released.add("microphone"),
                () -> released.add("peer"),
                () -> released.add("renderer"));
        assertEquals(Arrays.asList("camera", "microphone", "peer", "renderer"), released);
        assertEquals(1, failures.size());
        assertSame(cameraFailure, failures.get(0));
    }

    @Test public void repeatedHangupNeverDisposesResourcesTwice() {
        MediaCleanup cleanup = new MediaCleanup();
        int[] released = {0};
        cleanup.releaseAll(() -> released[0]++);
        List<Exception> second = cleanup.releaseAll(() -> released[0]++);
        assertEquals(1, released[0]);
        assertTrue(second.isEmpty());
    }

    @Test public void multipleFailuresArePreservedAndEveryReleaseRuns() {
        List<String> released = new ArrayList<>();
        List<Exception> failures = new MediaCleanup().releaseAll(
                () -> { released.add("camera"); throw new IllegalStateException(); },
                () -> { released.add("peer"); throw new IllegalArgumentException(); },
                () -> released.add("egl"));
        assertEquals(Arrays.asList("camera", "peer", "egl"), released);
        assertEquals(2, failures.size());
    }

    @Test public void interruptedCameraStopPreservesInterruptAndReleasesAudio() {
        Thread.interrupted();
        int[] audioReleased = {0};
        try {
            List<Exception> failures = new MediaCleanup().releaseAll(
                    () -> { throw new InterruptedException("camera stop interrupted"); },
                    () -> audioReleased[0]++);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, audioReleased[0]);
            assertEquals(1, failures.size());
            assertTrue(failures.get(0) instanceof InterruptedException);
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void cleanupCanSafelyReenterWithoutDoubleRelease() {
        MediaCleanup cleanup = new MediaCleanup();
        int[] releases = {0};
        cleanup.releaseAll(
                () -> { releases[0]++; cleanup.releaseAll(() -> releases[0]++); },
                () -> releases[0]++);
        assertEquals(2, releases[0]);
    }
}
