package com.myfamcall.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Executes every media release even when an earlier resource fails to close. */
final class MediaCleanup {
    interface Release { void run() throws Exception; }
    private boolean released;

    /** Idempotent. Expected resource failures are returned, not allowed to skip later releases. */
    synchronized List<Exception> releaseAll(Release... releases) {
        if (released) return Collections.emptyList();
        released = true;
        List<Exception> failures = new ArrayList<>();
        for (Release release : releases) {
            try {
                release.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
            } catch (Exception failure) {
                failures.add(failure);
            }
        }
        return Collections.unmodifiableList(failures);
    }
}
